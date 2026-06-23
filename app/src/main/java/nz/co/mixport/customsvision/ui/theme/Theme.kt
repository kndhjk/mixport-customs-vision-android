package nz.co.mixport.customsvision.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = HarborBlue,
    onPrimary = Mist,
    primaryContainer = Foam,
    onPrimaryContainer = DeepOcean,
    secondary = SafetyOrange,
    onSecondary = DeepOcean,
    secondaryContainer = ColorTokens.Peach,
    onSecondaryContainer = DeepOcean,
    tertiary = DeepOcean,
    onTertiary = Mist,
    background = Mist,
    onBackground = Slate,
    surface = Mist,
    onSurface = Slate,
    surfaceVariant = Foam,
    onSurfaceVariant = HarborBlue,
)

private val DarkScheme = darkColorScheme(
    primary = Foam,
    onPrimary = DeepOcean,
    primaryContainer = HarborBlue,
    onPrimaryContainer = Mist,
    secondary = SafetyOrange,
    onSecondary = DeepOcean,
    tertiary = Foam,
    onTertiary = DeepOcean,
    background = DeepOcean,
    onBackground = Mist,
    surface = DeepOcean,
    onSurface = Mist,
    surfaceVariant = HarborBlue,
    onSurfaceVariant = Foam,
)

@Composable
fun MixportCustomsTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

private object ColorTokens {
    val Peach = SafetyOrange.copy(alpha = 0.22f)
}

