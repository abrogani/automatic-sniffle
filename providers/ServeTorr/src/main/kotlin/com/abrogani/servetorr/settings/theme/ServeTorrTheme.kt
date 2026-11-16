package com.abrogani.servetorr.settings.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

internal val ServeTorrDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7DD9A0),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0E5234),
    onPrimaryContainer = Color(0xFF9DF5BB),
    inversePrimary = Color(0xFF1B6C44),
    secondary = Color(0xFF80D4C6),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF1D4D47),
    onSecondaryContainer = Color(0xFFA3F0E2),
    tertiary = Color(0xFFE9C46F),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF584200),
    onTertiaryContainer = Color(0xFFFFDF9F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1511),
    onBackground = Color(0xFFDDE5DE),
    surface = Color(0xFF0E1511),
    onSurface = Color(0xFFDDE5DE),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBEC9C0),
    surfaceTint = Color(0xFF7DD9A0),
    inverseSurface = Color(0xFFDDE5DE),
    inverseOnSurface = Color(0xFF2B322D),
    outline = Color(0xFF88938B),
    outlineVariant = Color(0xFF3F4943),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333B36),
    surfaceDim = Color(0xFF0E1511),
    surfaceContainerLowest = Color(0xFF09100C),
    surfaceContainerLow = Color(0xFF161D19),
    surfaceContainer = Color(0xFF1A211D),
    surfaceContainerHigh = Color(0xFF232B26),
    surfaceContainerHighest = Color(0xFF2E3631),
)

internal val ServeTorrLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B6C44),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2C0),
    onPrimaryContainer = Color(0xFF002112),
    inversePrimary = Color(0xFF7DD9A0),
    secondary = Color(0xFF00695F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA0F2E3),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF765A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9F),
    onTertiaryContainer = Color(0xFF251A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF4),
    onBackground = Color(0xFF161D19),
    surface = Color(0xFFF4FBF4),
    onSurface = Color(0xFF161D19),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404942),
    surfaceTint = Color(0xFF1B6C44),
    inverseSurface = Color(0xFF2B322D),
    inverseOnSurface = Color(0xFFECF3EC),
    outline = Color(0xFF707971),
    outlineVariant = Color(0xFFC0C9C1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF4FBF4),
    surfaceDim = Color(0xFFD5DCD5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF6EE),
    surfaceContainer = Color(0xFFE8F0E9),
    surfaceContainerHigh = Color(0xFFE2EAE3),
    surfaceContainerHighest = Color(0xFFDDE5DD),
)

internal val ServeTorrShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
internal fun ServeTorrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) ServeTorrDarkColors else ServeTorrLightColors,
        typography = MaterialTheme.typography,
        shapes = ServeTorrShapes,
        content = content,
    )
}
