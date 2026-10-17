package com.spekterm.android.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.store.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings shows the appearance choice and reports another one tapped (`app-appearance`). */
@RunWith(AndroidJUnit4::class)
class AppearanceSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsTheChoiceAndReportsAnother() {
        val chosen = mutableListOf<ThemeChoice>()
        compose.setContent { AppearanceSection(ThemeChoice.SYSTEM) { chosen += it } }
        compose.onNodeWithText("System").assertIsSelected()
        compose.onNodeWithText("Dark").assertIsNotSelected()
        compose.onNodeWithText("Dark").performClick()
        // Tapping the one already chosen reports nothing.
        compose.onNodeWithText("System").performClick()
        assertEquals(listOf(ThemeChoice.DARK), chosen)
    }
}
