package com.spekterm.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalSpekColors = staticCompositionLocalOf { DarkSpekColors }

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** The app's look, from the desktop's tokens, following the system's light or dark setting (`ui-polish` D1). */
@Composable
fun SpektermTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSpekColors provides if (dark) DarkSpekColors else LightSpekColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = Typography(),
            shapes = shapes,
            content = content,
        )
    }
}

/** The state colors of the current theme. */
val spekColors: SpekColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSpekColors.current
