package com.spekterm.android.desktop

import com.spekterm.android.net.SessionList

/** A session's label from the name its agent is messaged by (`tool-call-display` D1). */
object SessionNames {
    /** The `label` of the listed session whose `peerName` is [name], or `null` when none is (or it has no label). */
    fun label(name: String?, list: SessionList?): String? {
        if (name.isNullOrEmpty() || list == null) return null
        return list.sessions.firstOrNull { it.peerName == name }?.label?.takeIf { it.isNotBlank() }
    }
}
