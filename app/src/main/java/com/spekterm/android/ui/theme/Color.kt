package com.spekterm.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// The desktop's tokens (spekterm `src/renderer/src/index.css`, `@theme`), which is dark only (`ui-polish` D1).
private val Shell = Color(0xFF07090C)
private val Rail = Color(0xFF0A0C0F)
private val Stage = Color(0xFF0F1216)
private val Panel = Color(0xFF14181D)
private val Hover = Color(0xFF1B2027)
private val Raised = Color(0xFF232A33)
private val Hairline = Color(0xFF1E242C)
private val Ink = Color(0xFFE2E8F0)
private val InkDim = Color(0xFF94A3B8)
private val InkFaint = Color(0xFF5B6675)
private val Accent = Color(0xFFF59E0B)
private val Blue = Color(0xFF60A5FA)
private val Green = Color(0xFF34D399)
private val Danger = Color(0xFFF87171)

// Fixed roles: the same in both schemes, from the light palette's containers (D1).
private val PrimaryFixed = Color(0xFFFEF3C7)
private val PrimaryFixedDim = Color(0xFFFDE68A)
private val OnPrimaryFixed = Color(0xFF78350F)
private val OnPrimaryFixedVariant = Color(0xFF92400E)
private val SecondaryFixed = Color(0xFFE2E8F0)
private val SecondaryFixedDim = Color(0xFFCBD5E1)
private val OnSecondaryFixed = Color(0xFF0F172A)
private val OnSecondaryFixedVariant = Color(0xFF334155)
private val TertiaryFixed = Color(0xFFDBEAFE)
private val TertiaryFixedDim = Color(0xFFBFDBFE)
private val OnTertiaryFixed = Color(0xFF1E3A8A)
private val OnTertiaryFixedVariant = Color(0xFF1E40AF)

/** Every one of the 48 slots is passed: a slot left out would fall back to Material's baseline purple. */
val DarkColors: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1C1300),
    primaryContainer = Color(0xFF2B2315), // accent-soft over stage
    onPrimaryContainer = Color(0xFFFCD34D),
    inversePrimary = Color(0xFFB45309),
    secondary = InkDim,
    onSecondary = Stage,
    secondaryContainer = Hover,
    onSecondaryContainer = Ink,
    tertiary = Blue,
    onTertiary = Stage,
    tertiaryContainer = Color(0xFF15253A),
    onTertiaryContainer = Color(0xFFBFDBFE),
    background = Stage,
    onBackground = Ink,
    surface = Stage,
    onSurface = Ink,
    surfaceVariant = Hover,
    onSurfaceVariant = InkDim,
    surfaceTint = Stage,
    inverseSurface = Ink,
    inverseOnSurface = Stage,
    error = Danger,
    onError = Stage,
    errorContainer = Color(0xFF3A1D1F),
    onErrorContainer = Color(0xFFFECACA),
    outline = InkFaint,
    outlineVariant = Hairline,
    scrim = Color.Black,
    surfaceBright = Raised,
    surfaceContainer = Panel,
    surfaceContainerHigh = Hover,
    surfaceContainerHighest = Raised,
    surfaceContainerLow = Rail,
    surfaceContainerLowest = Shell,
    surfaceDim = Shell,
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

private val LightBackground = Color(0xFFF8FAFC)

/** The same hues on slate. Amber-700 is the accent: the desktop's amber is about 2.1:1 on white (D1). */
val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF78350F),
    inversePrimary = Color(0xFFFBBF24),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF2563EB),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDBEAFE),
    onTertiaryContainer = Color(0xFF1E3A8A),
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightBackground,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = LightBackground,
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFE2E8F0),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    surfaceContainerLow = LightBackground,
    surfaceContainerLowest = Color.White,
    surfaceDim = Color(0xFFE2E8F0),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

/** The colors of a state, which Material has no slot for (D1). */
@Immutable
data class SpekColors(
    /** Waiting for you, asks for a choice. */
    val attention: Color,
    /** Working, running: dots only. */
    val working: Color,
    /** An agent's offer to close its session. */
    val offer: Color,
    val danger: Color,
    /** Dormant: dots only, too faint for text in light mode. */
    val dormant: Color,
)

val DarkSpekColors = SpekColors(attention = Accent, working = Green, offer = Blue, danger = Danger, dormant = InkFaint)

val LightSpekColors = SpekColors(
    attention = LightColors.primary,
    working = Color(0xFF059669),
    offer = LightColors.tertiary,
    danger = LightColors.error,
    dormant = LightColors.outline,
)
