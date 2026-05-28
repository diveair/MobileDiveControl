package com.mobiledivecontrol.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DiveColorScheme = darkColorScheme(
    primary = DiveColors.DiveCyan,
    onPrimary = DiveColors.DeepBlack,
    primaryContainer = DiveColors.DiveCyanDim,
    onPrimaryContainer = DiveColors.TextPrimary,
    secondary = DiveColors.DiveTeal,
    onSecondary = DiveColors.DeepBlack,
    background = DiveColors.DeepBlack,
    onBackground = DiveColors.TextPrimary,
    surface = DiveColors.SurfaceCard,
    onSurface = DiveColors.TextPrimary,
    surfaceVariant = DiveColors.SurfaceElevated,
    onSurfaceVariant = DiveColors.TextSecondary,
    error = DiveColors.Critical,
    onError = DiveColors.TextPrimary,
    outline = DiveColors.SurfaceBorder,
)

/**
 * Typography optimized for underwater readability.
 * Large sizes, high contrast, bold weights for glanceability.
 * Using system sans-serif (similar to Inter) for reliability.
 */
private val DiveTypography = Typography(
    // Massive — sensor values, depth gauge
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.5).sp,
    ),
    // Large numbers — camera control values
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    // Mode titles
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    // Section headers
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    // Card titles
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    // HUD values — battery %, depth, temp
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    // HUD labels
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // Secondary info
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // Button hints
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    // Tiny labels
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

private val DiveShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun DiveControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DiveColorScheme,
        typography = DiveTypography,
        shapes = DiveShapes,
        content = content,
    )
}
