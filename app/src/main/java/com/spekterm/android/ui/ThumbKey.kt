package com.spekterm.android.ui

/** The key a picture is held under on the conversation screen (`tool-result-content` D3). */
object ThumbKey {
    /** [STRIP]: a collapsed run's tile, decoded small; [FULL]: under a call's line or a message. */
    enum class Size { STRIP, FULL }

    /** A sent picture by its call and position, as before; a result picture apart from it, and per size. */
    fun of(ref: SentFileRef, size: Size): String = when (ref.source) {
        SentFileRef.Source.SENT -> "${ref.toolUseId}#${ref.index}"
        SentFileRef.Source.RESULT -> "r:${ref.toolUseId}:${ref.index}:" + if (size == Size.STRIP) "s" else "m"
    }
}
