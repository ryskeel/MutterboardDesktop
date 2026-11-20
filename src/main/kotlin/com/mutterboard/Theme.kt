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
// so the desktop app is visually identical: black, greys and white, like the
// squiggle icon. A light-grey canvas just dark enough to separate the white
// cards from the page; charcoal in dark mode.

private val BrandPrimaryLight = Color(0xFF1A1A1A)
private val BrandOnPrimaryLight = Color(0xFFFFFFFF)
private val BrandPrimaryContainerLight = Color(0xFFE0E0E0)
private val BrandOnPrimaryContainerLight = Color(0xFF1A1A1A)
private val BrandSecondaryLight = Color(0xFF424242)
private val BrandOnSecondaryLight = Color(0xFFFFFFFF)
private val BrandSecondaryContainerLight = Color(0xFFE6E6E6)
private val BrandOnSecondaryContainerLight = Color(0xFF1A1A1A)
private val BrandSurfaceContainerHighLight = Color(0xFFEEEEEE)
private val BrandSurfaceContainerHighestLight = Color(0xFFE8E8E8)
private val BrandBackgroundLight = Color(0xFFF2F2F2)
private val BrandOnBackgroundLight = Color(0xFF1A1A1A)
private val BrandSurfaceLight = Color(0xFFF2F2F2)
private val BrandOnSurfaceLight = Color(0xFF1A1A1A)
private val BrandSurfaceVariantLight = Color(0xFFEBEBEB)
private val BrandOnSurfaceVariantLight = Color(0xFF5A5A5A)
private val BrandSurfaceContainerLight = Color(0xFFFFFFFF)
private val BrandOutlineLight = Color(0xFF2E2E2E)
private val BrandOutlineVariantLight = Color(0xFFE0E0E0)
private val BrandErrorLight = Color(0xFFBA1A1A)

private val BrandPrimaryDark = Color(0xFFE6E6E6)
private val BrandOnPrimaryDark = Color(0xFF1A1A1A)
private val BrandPrimaryContainerDark = Color(0xFF3A3A3A)
private val BrandOnPrimaryContainerDark = Color(0xFFE6E6E6)
private val BrandSecondaryDark = Color(0xFFC8C8C8)
private val BrandOnSecondaryDark = Color(0xFF1A1A1A)
private val BrandSecondaryContainerDark = Color(0xFF3A3A3A)
private val BrandOnSecondaryContainerDark = Color(0xFFE6E6E6)
private val BrandSurfaceContainerHighDark = Color(0xFF2B2B2B)
private val BrandSurfaceContainerHighestDark = Color(0xFF363636)
private val BrandBackgroundDark = Color(0xFF121212)
private val BrandOnBackgroundDark = Color(0xFFE6E6E6)
private val BrandSurfaceDark = Color(0xFF121212)
private val BrandOnSurfaceDark = Color(0xFFE6E6E6)
private val BrandSurfaceVariantDark = Color(0xFF3A3A3A)
private val BrandOnSurfaceVariantDark = Color(0xFFBDBDBD)
private val BrandSurfaceContainerDark = Color(0xFF212121)
private val BrandOutlineDark = Color(0xFF8C8C8C)
private val BrandOutlineVariantDark = Color(0xFF424242)
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

// "Accent pill" used for confirmation chips and vocabulary badges, ported
// from the Android app: light grey with near-black content on light, mid grey
// with light content on dark.
private val AccentContainerLight = Color(0xFFE6E6E6)
private val OnAccentContainerLight = Color(0xFF1A1A1A)
private val AccentContainerDark = Color(0xFF3A3A3A)
private val OnAccentContainerDark = Color(0xFFE6E6E6)

val accentContainerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) AccentContainerDark else AccentContainerLight

val onAccentContainerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) OnAccentContainerDark else OnAccentContainerLight

// Inputs get a plain field background so they read as editable, distinct from
// the grey chips: white on light, the card tone on dark. The ring carries the
// definition.
private val InputBackgroundLight = Color(0xFFFFFFFF)
private val InputBackgroundDark = BrandSurfaceContainerDark

val inputBackgroundColor: Color
    @Composable get() = if (isSystemInDarkTheme()) InputBackgroundDark else InputBackgroundLight

// A deeper grey ring for interactive pills (inputs, the shortcut button) so
// they read as controls against the plain accent chips.
private val AccentBorderLight = Color(0xFFA6A6A6)
private val AccentBorderDark = Color(0xFF6E6E6E)

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
