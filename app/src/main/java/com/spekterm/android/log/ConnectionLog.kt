package com.spekterm.android.log

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import java.io.File
import java.time.Instant

/**
 * The on-device connection log (`desktop-connection`, "Keep a connection log"): one line per entry, for the
 * spike's measurements.
 *
 * It never holds a credential, a pairing code or notification text. Callers pass only the fields below; there is
 * no free-text field that event text could reach. Bounded: rotated at [MAX_BYTES], keeping one previous file.
 */
class ConnectionLog(
    private val dir: File,
    private val conditions: () -> Conditions = { Conditions() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The phone's state at the time of an entry. */
    data class Conditions(
        val battery: Int? = null,
        val idle: Boolean? = null,
        val powerSave: Boolean? = null,
        val bucket: Int? = null,
    )

    val current = File(dir, "connection.log")
    val previous = File(dir, "connection.log.1")

    @Synchronized
    fun write(kind: Kind, vararg fields: Pair<String, Any?>) {
        val c = conditions()
        val line = buildString {
            append(Instant.ofEpochMilli(now()).toString()).append(' ').append(kind.name.lowercase())
            for ((key, value) in fields) if (value != null) append(' ').append(key).append('=').append(clean(value))
            c.battery?.let { append(" battery=").append(it) }
            c.idle?.let { append(" idle=").append(it) }
            c.powerSave?.let { append(" powerSave=").append(it) }
            c.bucket?.let { append(" bucket=").append(it) }
            append('\n')
        }
        dir.mkdirs()
        if (current.exists() && current.length() + line.length > MAX_BYTES) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(line)
    }

    /** Both files, oldest first, as one text. */
    @Synchronized
    fun export(): String = listOf(previous, current).filter { it.exists() }.joinToString("") { it.readText() }

    /** Values are identifiers, numbers and codes; anything else is cut to keep one entry on one line. */
    private fun clean(value: Any): String = value.toString().replace(Regex("[\\s=]+"), "_").take(120)

    /** What an entry records. Field names in the call sites are fixed; see the spec for the list. */
    enum class Kind {
        SERVICE_START, SERVICE_STOP, ATTEMPT, PIN_MISMATCH, UNREACHABLE, AUTHENTICATED, WATCHING, DISCONNECTED,
        BACKOFF, NETWORK, IDLE_MODE, POWER_SAVE, ATTENTION, UNPAIRED, PAUSED, RESUMED, RESTART_REFUSED,
        STORE_UNAVAILABLE, ERROR,
    }

    companion object {
        const val MAX_BYTES = 1L shl 20

        fun forApp(context: Context): ConnectionLog {
            val app = context.applicationContext
            return ConnectionLog(File(app.filesDir, "log"), conditions = { conditionsOf(app) })
        }

        fun conditionsOf(context: Context): Conditions {
            val power = context.getSystemService(PowerManager::class.java)
            val battery = context.getSystemService(BatteryManager::class.java)
            val usage = context.getSystemService(UsageStatsManager::class.java)
            return Conditions(
                battery = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                idle = power?.isDeviceIdleMode,
                powerSave = power?.isPowerSaveMode,
                // No broadcast exists for the standby bucket; it is read at every entry.
                bucket = usage?.appStandbyBucket,
            )
        }
    }
}
