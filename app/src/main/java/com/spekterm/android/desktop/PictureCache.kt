package com.spekterm.android.desktop

/**
 * The tool-result pictures read on one screen (`tool-result-content` D3), kept in [entries] (the screen passes a
 * snapshot map, so a read shows). Each picture on screen pins its entry; only entries not pinned are evicted, the least
 * recently unpinned first, beyond [capacity] of them. An evicted picture is read again when it is shown again.
 */
class PictureCache<V : Any>(private val capacity: Int = CAPACITY, private val entries: MutableMap<String, V> = HashMap()) {
    private val pins = HashMap<String, Int>()

    /** Unpinned keys, least recently unpinned first. */
    private val idle = LinkedHashSet<String>()

    operator fun get(key: String): V? = entries[key]

    operator fun set(key: String, value: V) {
        entries[key] = value
        if ((pins[key] ?: 0) == 0) {
            idle.remove(key)
            idle += key
            trim()
        }
    }

    fun remove(key: String) {
        entries.remove(key)
        idle.remove(key)
    }

    fun pin(key: String) {
        pins[key] = (pins[key] ?: 0) + 1
        idle.remove(key)
    }

    fun unpin(key: String) {
        val n = (pins[key] ?: return) - 1
        if (n > 0) {
            pins[key] = n
            return
        }
        pins.remove(key)
        if (key in entries) {
            idle += key
            trim()
        }
    }

    private fun trim() {
        while (idle.size > capacity) {
            val oldest = idle.first()
            idle.remove(oldest)
            entries.remove(oldest)
        }
    }

    companion object {
        const val CAPACITY = 24
    }
}
