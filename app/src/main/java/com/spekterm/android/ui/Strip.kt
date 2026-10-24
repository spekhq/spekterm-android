package com.spekterm.android.ui

/**
 * A collapsed run's row of picture tiles (`tool-result-content` D3): fixed squares, so how many fit is known before any
 * picture is read, and only those shown are read.
 */
object Strip {
    const val TILE = 72
    const val GAP = 6
    const val MORE = 48
    const val MAX = 3

    /** [shown] tiles, then a "+[more]" tile when [more] is above 0. */
    data class Count(val shown: Int, val more: Int)

    /** For a row [width] dp wide and [pictures] in the run. */
    fun count(width: Float, pictures: Int): Count {
        val fit = ((width + GAP) / (TILE + GAP)).toInt().coerceAtLeast(0)
        if (pictures <= minOf(MAX, fit)) return Count(pictures, 0)
        val shown = minOf(MAX, ((width + GAP - (MORE + GAP)) / (TILE + GAP)).toInt().coerceAtLeast(0))
        return Count(shown, pictures - shown)
    }
}
