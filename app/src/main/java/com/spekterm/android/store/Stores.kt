package com.spekterm.android.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.util.AtomicFile
import com.spekterm.android.net.Frames
import com.spekterm.android.net.Position
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/** The Keystore or the file could not be read right now; not the same as "not paired". */
class StoreUnavailableException(cause: Throwable) : IOException("the pairing store is unavailable", cause)

/** The one paired desktop (`device-pairing`). */
@Serializable
data class Pairing(
    val desktopName: String,
    val addresses: List<String>,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
    val deviceSecret: String,
    /** The address that last authenticated; tried first (design D6). */
    val lastAddress: String? = null,
) {
    override fun toString(): String =
        "Pairing(desktopName=$desktopName, addresses=$addresses, port=$port, deviceId=$deviceId, deviceSecret=<redacted>, lastAddress=$lastAddress)"

    /** Last-good first, then the link's order. */
    fun addressOrder(): List<String> = listOfNotNull(lastAddress?.takeIf { it in addresses }) + addresses.filter { it != lastAddress }
}

/**
 * The pairing, encrypted with an AES-256-GCM key that never leaves Android Keystore (design D3), in
 * `noBackupFilesDir`. The key requires no user authentication and no unlocked device: the service reads it
 * while the phone is locked.
 */
class CredentialStore(context: Context, private val onChange: () -> Unit = {}) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pairing.bin"))

    /**
     * The pairing, or `null` when there is none: no file, no key, or a file the key cannot open (a key lost with
     * a restore, a corrupt file). Any other failure — a Keystore that is momentarily unavailable — throws
     * [StoreUnavailableException], so a transient error is never mistaken for "not paired".
     */
    @Synchronized
    fun load(): Pairing? {
        if (!file.baseFile.exists()) return null
        try {
            val key = existingKey() ?: return null
            val bytes = file.readFully()
            val ivSize = bytes.firstOrNull()?.toInt() ?: return null
            if (ivSize <= 0 || bytes.size <= 1 + ivSize) return null
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize).decodeToString()
            return Frames.json.decodeFromString(Pairing.serializer(), plain)
        } catch (_: AEADBadTagException) {
            return null
        } catch (_: KeyPermanentlyInvalidatedException) {
            return null
        } catch (_: UnrecoverableKeyException) {
            return null
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (e: Exception) {
            throw StoreUnavailableException(e)
        }
    }

    /**
     * Rewrites the pairing only if it is still the one with [deviceId]: a service that is stopping must never
     * overwrite a pairing that replaced its own, nor bring back one the user removed.
     */
    @Synchronized
    fun updateIf(deviceId: String, change: (Pairing) -> Pairing) {
        val current = load() ?: return
        if (current.deviceId == deviceId) save(change(current))
    }

    /** Deletes the pairing only if it is still the one with [deviceId]. Returns whether it did. */
    @Synchronized
    fun deleteIf(deviceId: String): Boolean {
        val current = try {
            load()
        } catch (_: StoreUnavailableException) {
            null
        }
        if (current?.deviceId != deviceId) return false
        delete()
        return true
    }

    @Synchronized
    fun save(pairing: Pairing) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(Frames.json.encodeToString(Pairing.serializer(), pairing).encodeToByteArray())
        val out = file.startWrite()
        try {
            out.write(byteArrayOf(iv.size.toByte()))
            out.write(iv)
            out.write(sealed)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        onChange()
    }

    @Synchronized
    fun delete() {
        file.delete()
        onChange()
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "pairing"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** A small JSON file written atomically. Not secret. */
private class JsonFile<T>(private val file: AtomicFile, private val serializer: kotlinx.serialization.KSerializer<T>) {
    fun read(): T? = try {
        if (file.baseFile.exists()) Frames.json.decodeFromString(serializer, file.readFully().decodeToString()) else null
    } catch (_: Exception) {
        null
    }

    fun write(value: T) {
        val out = file.startWrite()
        try {
            out.write(Frames.json.encodeToString(serializer, value).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun delete() = file.delete()
}

@Serializable
private data class StoredPosition(val deviceId: String, val position: Position)

/**
 * The position of the last attention event handled (`attention-notifications`), bound to the pairing it belongs
 * to: a position written late by a service of an older pairing is never read as the new pairing's.
 */
class PositionStore(context: Context) {
    private val file = JsonFile(AtomicFile(File(context.noBackupFilesDir, "position.json")), StoredPosition.serializer())

    @Synchronized fun load(deviceId: String): Position? = file.read()?.takeIf { it.deviceId == deviceId }?.position
    @Synchronized fun save(deviceId: String, position: Position) = file.write(StoredPosition(deviceId, position))
    @Synchronized fun delete() = file.delete()
}

/**
 * States the app announces with a notification, kept on disk so the status screen shows them even when the
 * notification permission is denied or the process ended (design D5), and whether the user paused.
 */
@Serializable
data class Announcements(
    val paused: Boolean = false,
    val pairAgain: Boolean = false,
    val identityChanged: Boolean = false,
    val connectionStopped: Boolean = false,
    /** `UPDATE_APP`, `UPDATE_DESKTOP` or `UNKNOWN` when the versions do not overlap; null otherwise. */
    val unsupported: String? = null,
)

class AnnouncementStore(context: Context, private val onChange: () -> Unit = {}) {
    private val file = JsonFile(AtomicFile(File(context.noBackupFilesDir, "announcements.json")), Announcements.serializer())

    @Synchronized fun load(): Announcements = file.read() ?: Announcements()

    @Synchronized fun update(change: (Announcements) -> Announcements): Announcements {
        val next = change(load())
        file.write(next)
        onChange()
        return next
    }
}
