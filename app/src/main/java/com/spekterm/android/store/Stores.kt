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
import kotlinx.serialization.Serializable

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
class CredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pairing.bin"))

    @Synchronized
    fun load(): Pairing? {
        if (!file.baseFile.exists()) return null
        return try {
            val bytes = file.readFully()
            val iv = bytes.copyOfRange(1, 1 + bytes[0].toInt())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            Frames.json.decodeFromString(Pairing.serializer(), cipher.doFinal(bytes, 1 + iv.size, bytes.size - 1 - iv.size).decodeToString())
        } catch (_: Exception) {
            // Undecryptable (a key lost with a restore, a corrupt file): there is no pairing.
            null
        }
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
    }

    @Synchronized
    fun delete() {
        file.delete()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
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

/** The position of the last attention event handled (`attention-notifications`). */
class PositionStore(context: Context) {
    private val file = JsonFile(AtomicFile(File(context.noBackupFilesDir, "position.json")), Position.serializer())

    @Synchronized fun load(): Position? = file.read()
    @Synchronized fun save(position: Position) = file.write(position)
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

class AnnouncementStore(context: Context) {
    private val file = JsonFile(AtomicFile(File(context.noBackupFilesDir, "announcements.json")), Announcements.serializer())

    @Synchronized fun load(): Announcements = file.read() ?: Announcements()

    @Synchronized fun update(change: (Announcements) -> Announcements): Announcements {
        val next = change(load())
        file.write(next)
        return next
    }
}
