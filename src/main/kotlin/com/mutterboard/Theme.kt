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
