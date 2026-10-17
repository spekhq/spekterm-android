package com.spekterm.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    /** Every color slot, by reflection: a hand-written list would miss slots a newer Material 3 adds. */
    private fun slots(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { it.name.removePrefix("get").substringBefore('-') to Color((it.invoke(scheme) as Long).toULong()) }

    private fun neutral(c: Color) = c.red == c.green && c.green == c.blue

    private fun assertNoBaseline(ours: ColorScheme, baseline: ColorScheme) {
        val mine = slots(ours)
        val base = slots(baseline)
        // Material 3 1.4.0 has 48 color slots, all set in Color.kt; a new one fails here until it is set too.
        assertEquals(48, mine.size)
        val fallen = mine.filter { (name, color) -> color == base[name] && !neutral(color) }.keys
        assertTrue("slots left at the baseline: $fallen", fallen.isEmpty())
    }

    @Test
    fun darkSetsEverySlot() = assertNoBaseline(DarkColors, darkColorScheme())

    @Test
    fun lightSetsEverySlot() = assertNoBaseline(LightColors, lightColorScheme())
}
