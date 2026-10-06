package com.logisticsmanagement.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF146C60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDE4),
    onPrimaryContainer = Color(0xFF05372F),
    secondary = Color(0xFF465F55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E8DF),
    onSecondaryContainer = Color(0xFF10291E),
    tertiary = Color(0xFF805600),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA5),
    onTertiaryContainer = Color(0xFF281900),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF17201C),
    surface = Color.White,
    onSurface = Color(0xFF17201C),
    surfaceVariant = Color(0xFFEEF3EF),
    onSurfaceVariant = Color(0xFF4F5D55),
    surfaceContainer = Color(0xFFEEF3EF),
    surfaceContainerLow = Color(0xFFF3F7F3),
    surfaceContainerHigh = Color(0xFFE7EEE8),
    surfaceContainerHighest = Color(0xFFE1E9E2),
    outline = Color(0xFF718078),
    outlineVariant = Color(0xFFC5D0C8),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD5BD),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF125143),
    onPrimaryContainer = Color(0xFFADF1D8),
    secondary = Color(0xFFB5CDBF),
    onSecondary = Color(0xFF20362A),
    secondaryContainer = Color(0xFF374D40),
    onSecondaryContainer = Color(0xFFD0E9DA),
    tertiary = Color(0xFFF0C36E),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF604000),
    onTertiaryContainer = Color(0xFFFFDEA5),
    background = Color(0xFF151B18),
    onBackground = Color(0xFFE3EBE4),
    surface = Color(0xFF1C2520),
    onSurface = Color(0xFFE3EBE4),
    surfaceVariant = Color(0xFF24312A),
    onSurfaceVariant = Color(0xFFB7C6BC),
    surfaceContainer = Color(0xFF24312A),
    surfaceContainerLow = Color(0xFF202A24),
    surfaceContainerHigh = Color(0xFF2B3930),
    surfaceContainerHighest = Color(0xFF344237),
    outline = Color(0xFF8A9A8F),
    outlineVariant = Color(0xFF3C4C41),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Immutable
data class DeliveryColors(val warning: Color, val success: Color)

val LocalDeliveryColors = staticCompositionLocalOf {
    DeliveryColors(warning = Color(0xFF805600), success = Color(0xFF146C60))
}

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
    )

private val DeliveryTypography = Typography(
    displayLarge = textStyle(40, 48, FontWeight.Bold),
    displayMedium = textStyle(36, 44, FontWeight.Bold),
    displaySmall = textStyle(32, 40, FontWeight.Bold),
    headlineLarge = textStyle(28, 36, FontWeight.SemiBold),
    headlineMedium = textStyle(26, 34, FontWeight.SemiBold),
    headlineSmall = textStyle(24, 32, FontWeight.SemiBold),
    titleLarge = textStyle(22, 28, FontWeight.Medium),
    titleMedium = textStyle(16, 24, FontWeight.Medium),
    titleSmall = textStyle(14, 20, FontWeight.Medium),
    bodyLarge = textStyle(16, 24),
    bodyMedium = textStyle(14, 20),
    bodySmall = textStyle(14, 20),
    labelLarge = textStyle(14, 20, FontWeight.Medium),
    labelMedium = textStyle(14, 20, FontWeight.Medium),
    labelSmall = textStyle(14, 20, FontWeight.Medium),
)

@Composable
fun LogisticsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val deliveryColors = if (darkTheme) {
        DeliveryColors(warning = Color(0xFFF0C36E), success = Color(0xFF8AD5BD))
    } else {
        DeliveryColors(warning = Color(0xFF805600), success = Color(0xFF146C60))
    }
    CompositionLocalProvider(LocalDeliveryColors provides deliveryColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = DeliveryTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}
