package nz.co.mixport.customsvision.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = BrandOrange,
    onPrimary = CardWhite,
    primaryContainer = WarmPeach,
    onPrimaryContainer = BrandOrangeDark,
    secondary = HarborBlue,
    onSecondary = CardWhite,
    secondaryContainer = ColorTokens.SkyWash,
    onSecondaryContainer = DeepOcean,
    tertiary = DeepOcean,
    onTertiary = CardWhite,
    background = SiteBackground,
    onBackground = Slate,
    surface = CardWhite,
    onSurface = Slate,
    surfaceVariant = WarmPeach,
    onSurfaceVariant = TextMuted,
    outline = BorderSoft,
)

private val DarkScheme = darkColorScheme(
    primary = BrandOrange,
    onPrimary = CardWhite,
    primaryContainer = BrandOrangeDark,
    onPrimaryContainer = CardWhite,
    secondary = ColorTokens.NightSky,
    onSecondary = CardWhite,
    tertiary = ColorTokens.DeepSea,
    onTertiary = CardWhite,
    background = ColorTokens.NightSky,
    onBackground = CardWhite,
    surface = ColorTokens.DeepSea,
    onSurface = CardWhite,
    surfaceVariant = ColorTokens.SlateSurface,
    onSurfaceVariant = ColorTokens.MutedMist,
    outline = ColorTokens.DarkOutline,
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
    val SkyWash = HarborBlue.copy(alpha = 0.12f)
    val NightSky = Color(0xFF14232C)
    val DeepSea = Color(0xFF1A2E39)
    val SlateSurface = Color(0xFF263A46)
    val MutedMist = Color(0xFFDCE4E8)
    val DarkOutline = Color(0xFF35505E)
}
