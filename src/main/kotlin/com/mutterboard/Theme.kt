package com.mutterboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Mutterboard palette, ported verbatim from the Android app's ui/theme/Color.kt
// so the desktop app is visually identical: monochrome (black) chrome on a
// light-peach canvas with white cards; warm charcoal in dark mode.

private val BrandPrimaryLight = Color(0xFF1A1A1A)
private val BrandOnPrimaryLight = Color(0xFFFFFFFF)
private val BrandPrimaryContainerLight = Color(0xFFE2DEDB)
private val BrandOnPrimaryContainerLight = Color(0xFF1A1A1A)
private val BrandSecondaryLight = Color(0xFF44403E)
private val BrandOnSecondaryLight = Color(0xFFFFFFFF)
private val BrandSecondaryContainerLight = Color(0xFFE7E2DF)
private val BrandOnSecondaryContainerLight = Color(0xFF1A1A1A)
private val BrandSurfaceContainerHighLight = Color(0xFFF3EDE9)
private val BrandSurfaceContainerHighestLight = Color(0xFFEDE6E1)
private val BrandBackgroundLight = Color(0xFFFCF2EB)
private val BrandOnBackgroundLight = Color(0xFF1A1A1A)
private val BrandSurfaceLight = Color(0xFFFCF2EB)
private val BrandOnSurfaceLight = Color(0xFF1A1A1A)
private val BrandSurfaceVariantLight = Color(0xFFEDE9E7)
private val BrandOnSurfaceVariantLight = Color(0xFF5A5A5A)
private val BrandSurfaceContainerLight = Color(0xFFFFFFFF)
private val BrandOutlineLight = Color(0xFF2E2E2E)
private val BrandOutlineVariantLight = Color(0xFFE2DEDB)
private val BrandErrorLight = Color(0xFFBA1A1A)

private val BrandPrimaryDark = Color(0xFFE6E6E6)
private val BrandOnPrimaryDark = Color(0xFF1A1A1A)
private val BrandPrimaryContainerDark = Color(0xFF7A2E1A)
private val BrandOnPrimaryContainerDark = Color(0xFFFFDBCF)
private val BrandSecondaryDark = Color(0xFFE7BCAC)
private val BrandOnSecondaryDark = Color(0xFF442A1F)
private val BrandSecondaryContainerDark = Color(0xFF5D4033)
private val BrandOnSecondaryContainerDark = Color(0xFFFFDBCF)
private val BrandSurfaceContainerHighDark = Color(0xFF352B26)
private val BrandSurfaceContainerHighestDark = Color(0xFF403530)
private val BrandBackgroundDark = Color(0xFF1A1411)
private val BrandOnBackgroundDark = Color(0xFFECE0DB)
private val BrandSurfaceDark = Color(0xFF1A1411)
private val BrandOnSurfaceDark = Color(0xFFECE0DB)
private val BrandSurfaceVariantDark = Color(0xFF4A3A33)
private val BrandOnSurfaceVariantDark = Color(0xFFD8C2B8)
private val BrandSurfaceContainerDark = Color(0xFF2A211D)
private val BrandOutlineDark = Color(0xFFA08C84)
private val BrandOutlineVariantDark = Color(0xFF52423B)
private val BrandErrorDark = Color(0xFFFFB4AB)

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimaryLight,
    onPrimary = BrandOnPrimaryLight,
    primaryContainer = BrandPrimaryContainerLight,
    onPrimaryContainer = BrandOnPrimaryContainerLight,
    secondary = BrandSecondaryLight,
    onSecondary = BrandOnSecondaryLight,
    secondaryContainer = BrandSecondaryContainerLight,
    onSecondaryContainer = BrandOnSecondaryContainerLight,
    surfaceContainerHigh = BrandSurfaceContainerHighLight,
    surfaceContainerHighest = BrandSurfaceContainerHighestLight,
    background = BrandBackgroundLight,
    onBackground = BrandOnBackgroundLight,
    surface = BrandSurfaceLight,
    onSurface = BrandOnSurfaceLight,
    surfaceVariant = BrandSurfaceVariantLight,
    onSurfaceVariant = BrandOnSurfaceVariantLight,
    surfaceContainer = BrandSurfaceContainerLight,
    outline = BrandOutlineLight,
    outlineVariant = BrandOutlineVariantLight,
    error = BrandErrorLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = BrandOnPrimaryDark,
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = BrandOnPrimaryContainerDark,
    secondary = BrandSecondaryDark,
    onSecondary = BrandOnSecondaryDark,
    secondaryContainer = BrandSecondaryContainerDark,
    onSecondaryContainer = BrandOnSecondaryContainerDark,
    surfaceContainerHigh = BrandSurfaceContainerHighDark,
    surfaceContainerHighest = BrandSurfaceContainerHighestDark,
    background = BrandBackgroundDark,
    onBackground = BrandOnBackgroundDark,
    surface = BrandSurfaceDark,
    onSurface = BrandOnSurfaceDark,
    surfaceVariant = BrandSurfaceVariantDark,
    onSurfaceVariant = BrandOnSurfaceVariantDark,
    surfaceContainer = BrandSurfaceContainerDark,
    outline = BrandOutlineDark,
    outlineVariant = BrandOutlineVariantDark,
    error = BrandErrorDark,
)

// Desktop type scale. Sizes are in sp and rely on the app's HiDPI scaling
// (sun.java2d.uiScale) for physical size; grounded in desktop guidance — body
// ~15, headers ~22, secondary/labels 12-13, line-height ~1.4.
private val MutterboardTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

// Peach "accent pill" used for confirmation chips and vocabulary badges,
// ported from the Android app: peach with near-black content on light, warm
// brown with light-peach content on dark.
private val AccentContainerLight = Color(0xFFF8E6DA)
private val OnAccentContainerLight = Color(0xFF1A1A1A)
private val AccentContainerDark = Color(0xFF5D4033)
private val OnAccentContainerDark = Color(0xFFFFDBCF)

val accentContainerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) AccentContainerDark else AccentContainerLight

val onAccentContainerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) OnAccentContainerDark else OnAccentContainerLight

// Inputs get a plain field background so they read as editable, distinct from
// the peach chips: white on light, the card tone on dark. The peach ring
// carries the definition.
private val InputBackgroundLight = Color(0xFFFFFFFF)
private val InputBackgroundDark = BrandSurfaceContainerDark

val inputBackgroundColor: Color
    @Composable get() = if (isSystemInDarkTheme()) InputBackgroundDark else InputBackgroundLight

// A deeper peach ring for interactive pills (inputs, the shortcut button) so
// they read as controls against the plain accent chips.
private val AccentBorderLight = Color(0xFFDCAA8B)
private val AccentBorderDark = Color(0xFF8F6A52)

val accentBorderColor: Color
    @Composable get() = if (isSystemInDarkTheme()) AccentBorderDark else AccentBorderLight

@Composable
fun MutterboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = MutterboardTypography,
        content = content,
    )
}
