package com.richard.tunnelkeeper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val GeekBackground = Color(0xFF0B0E14)
val GeekPanel = Color(0xFF151923)
val GeekPanelRaised = Color(0xFF1C2130)
val GeekOutline = Color(0xFF2C3344)
val GeekText = Color(0xFFF2F4F8)
val GeekTextMuted = Color(0xFF9CA6B7)
val GeekPurple = Color(0xFF7C5CFF)
val GeekPurpleSoft = Color(0xFF9D8AFF)
val GeekGreen = Color(0xFF32D6A6)
val GeekLime = Color(0xFFD9F46A)
val GeekWarning = Color(0xFFF1C75B)
val GeekError = Color(0xFFFF7373)

private val GeekColorScheme = darkColorScheme(
    primary = GeekPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF30275C),
    onPrimaryContainer = Color(0xFFE7E1FF),
    secondary = GeekGreen,
    onSecondary = Color(0xFF002118),
    secondaryContainer = Color(0xFF123B31),
    onSecondaryContainer = Color(0xFF9CF4D3),
    tertiary = GeekLime,
    onTertiary = Color(0xFF1C2200),
    background = GeekBackground,
    onBackground = GeekText,
    surface = GeekPanel,
    onSurface = GeekText,
    surfaceVariant = GeekPanelRaised,
    onSurfaceVariant = GeekTextMuted,
    outline = GeekOutline,
    error = GeekError,
    errorContainer = Color(0xFF482326),
    onErrorContainer = Color(0xFFFFDAD9),
)

private val GeekTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

@Composable
fun GeekOCTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GeekColorScheme,
        typography = GeekTypography,
        content = content,
    )
}
