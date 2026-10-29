package alarmclock.vanbaelen.dev.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Design tokens shared with the panel (docs/design.md). */
@Immutable
data class AppColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val soft: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val off: Color,
    val knob: Color,
    val danger: Color,
    val warn: Color,
)

val DarkTokens = AppColors(
    dark = true,
    bg = Color(0xFF0D0D0F),
    surface = Color(0xFF17171A),
    ink = Color(0xFFF1F0EC),
    muted = Color(0xFFA09FA6),
    line = Color(0xFF2A2A2F),
    soft = Color(0xFF1F1F23),
    accent = Color(0xFFFFAD5C),
    onAccent = Color(0xFF1C1005),
    accentSoft = Color(0xFF3A2A1B),
    off = Color(0xFF38383E),
    knob = Color(0xFF9A99A0),
    danger = Color(0xFFFF8A7A),
    warn = Color(0xFFE8B04A),
)

val LightTokens = AppColors(
    dark = false,
    bg = Color(0xFFF5F5F2),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF151515),
    muted = Color(0xFF5C5C58),
    line = Color(0xFFE2E1DB),
    soft = Color(0xFFEAE9E4),
    accent = Color(0xFFB4520A),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFF6E3D2),
    off = Color(0xFFD3D2CC),
    knob = Color(0xFFFFFFFF),
    danger = Color(0xFFB3261E),
    warn = Color(0xFF8A5A00),
)

/** Night display and wake-up screen (docs/design.md, Night display): warm amber on black. */
val NightTokens = AppColors(
    dark = true,
    bg = Color(0xFF000000),
    surface = Color(0xFF000000),
    ink = Color(0xFFE6B183),
    muted = Color(0xFFAD8059),
    line = Color(0xFF3A2816),
    soft = Color(0xFF120C06),
    accent = Color(0xFFF2994A),
    onAccent = Color(0xFF1A0E04),
    accentSoft = Color(0xFF2E1607),
    off = Color(0xFF3A2A1C),
    knob = Color(0xFF8C6B4E),
    danger = Color(0xFFFF8A7A),
    warn = Color(0xFFE8B04A),
)

/** The night display's clock. */
val NightGlow = Color(0xFFE08C45)

/** End of a sunrise and the good-morning screen after it. */
val DayTokens = AppColors(
    dark = false,
    bg = Color(0xFFFFFDF8),
    surface = Color(0xFFFFFDF8),
    ink = Color(0xFF2A1A0E),
    muted = Color(0xFF6B5644),
    line = Color(0xFFEADCCB),
    soft = Color(0xFFF6EBDD),
    accent = Color(0xFFF2994A),
    onAccent = Color(0xFF1A0E04),
    accentSoft = Color(0xFFF6EBDD),
    off = Color(0xFFD9C9B8),
    knob = Color(0xFFFFFFFF),
    danger = Color(0xFFB3261E),
    warn = Color(0xFF8A5A00),
)

/** Rain bars and chances on the good-morning screen. */
fun rainColor(t: AppColors): Color = if (t.dark) Color(0xFF7FA7CF) else Color(0xFF3F6A94)

val LocalAppColors = staticCompositionLocalOf { LightTokens }

/** The design tokens of the current theme. */
val tokens: AppColors
    @Composable get() = LocalAppColors.current

/** Text roles of the design (docs/design.md, Type); all with tabular figures. */
object AppType {
    private val base = TextStyle(fontFeatureSettings = "tnum")
    val screenTitle = base.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
    val listTime = base.copy(fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.035).em, lineHeight = 44.sp)
    val detailTime = base.copy(fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.045).em, lineHeight = 59.sp)
    val body = base.copy(fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val secondary = base.copy(fontSize = 13.sp, lineHeight = 19.sp)
    val label = base.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val small = base.copy(fontSize = 12.sp, lineHeight = 16.sp)
}

private fun TextStyle.tnum() = copy(fontFeatureSettings = "tnum")

private val Base = Typography()
private val AppTypography = Base.copy(
    displayLarge = Base.displayLarge.tnum(),
    displayMedium = Base.displayMedium.tnum(),
    displaySmall = Base.displaySmall.tnum(),
    headlineLarge = Base.headlineLarge.tnum(),
    headlineMedium = Base.headlineMedium.tnum(),
    headlineSmall = Base.headlineSmall.tnum(),
    titleLarge = Base.titleLarge.tnum(),
    titleMedium = Base.titleMedium.tnum(),
    titleSmall = Base.titleSmall.tnum(),
    bodyLarge = Base.bodyLarge.tnum(),
    bodyMedium = Base.bodyMedium.tnum(),
    bodySmall = Base.bodySmall.tnum(),
    labelLarge = Base.labelLarge.tnum(),
    labelMedium = Base.labelMedium.tnum(),
    labelSmall = Base.labelSmall.tnum(),
)

private fun scheme(t: AppColors) = if (t.dark) {
    darkColorScheme(
        primary = t.accent, onPrimary = t.onAccent, primaryContainer = t.accentSoft, onPrimaryContainer = t.ink,
        secondary = t.ink, onSecondary = t.bg, secondaryContainer = t.soft, onSecondaryContainer = t.ink,
        tertiary = t.warn, onTertiary = t.bg, tertiaryContainer = t.soft, onTertiaryContainer = t.ink,
        background = t.bg, onBackground = t.ink, surface = t.bg, onSurface = t.ink,
        surfaceVariant = t.soft, onSurfaceVariant = t.muted, surfaceTint = Color.Transparent,
        inverseSurface = t.ink, inverseOnSurface = t.bg, inversePrimary = t.accent,
        error = t.danger, onError = t.bg, errorContainer = t.soft, onErrorContainer = t.ink,
        outline = t.line, outlineVariant = t.line,
        surfaceContainerLowest = t.bg, surfaceContainerLow = t.surface, surfaceContainer = t.surface,
        surfaceContainerHigh = t.soft, surfaceContainerHighest = t.soft,
    )
} else {
    lightColorScheme(
        primary = t.accent, onPrimary = t.onAccent, primaryContainer = t.accentSoft, onPrimaryContainer = t.ink,
        secondary = t.ink, onSecondary = t.bg, secondaryContainer = t.soft, onSecondaryContainer = t.ink,
        tertiary = t.warn, onTertiary = t.bg, tertiaryContainer = t.soft, onTertiaryContainer = t.ink,
        background = t.bg, onBackground = t.ink, surface = t.bg, onSurface = t.ink,
        surfaceVariant = t.soft, onSurfaceVariant = t.muted, surfaceTint = Color.Transparent,
        inverseSurface = t.ink, inverseOnSurface = t.bg, inversePrimary = t.accent,
        error = t.danger, onError = t.surface, errorContainer = t.soft, onErrorContainer = t.ink,
        outline = t.line, outlineVariant = t.line,
        surfaceContainerLowest = t.surface, surfaceContainerLow = t.surface, surfaceContainer = t.surface,
        surfaceContainerHigh = t.surface, surfaceContainerHighest = t.soft,
    )
}

/** The app's own palette (docs/design.md); follows the system dark mode, no dynamic colors. */
@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    CompositionLocalProvider(LocalAppColors provides t) {
        MaterialTheme(colorScheme = scheme(t), typography = AppTypography, content = content)
    }
}
