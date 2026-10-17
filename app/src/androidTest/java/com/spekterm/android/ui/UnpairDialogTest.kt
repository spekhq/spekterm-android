package com.spekterm.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unpairing asks first; only its "Unpair" unpairs (`device-pairing`, "End the pairing"). */
@RunWith(AndroidJUnit4::class)
class UnpairDialogTest {
    @get:Rule val compose = createComposeRule()

    private var unpaired = 0

    private fun show() {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) UnpairDialog("desk", onUnpair = {
                unpaired++
                open = false
            }, onDismiss = { open = false })
        }
    }

    @Test
    fun cancelLeavesThePairing() {
        show()
        compose.onNodeWithText("Unpair from desk?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Unpair from desk?").assertDoesNotExist()
        assertEquals(0, unpaired)
    }

    @Test
    fun unpairUnpairsOnce() {
        show()
        compose.onNodeWithText("Unpair").performClick()
        compose.onNodeWithText("Unpair from desk?").assertDoesNotExist()
        assertEquals(1, unpaired)
    }
}
