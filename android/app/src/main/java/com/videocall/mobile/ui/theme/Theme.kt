package com.videocall.mobile.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videocall.mobile.net.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fixed (non-themed) colors: presence, call surfaces, semantic status. */
object VcColor {
    val Online = Color(0xFF22C55E)
    val Away = Color(0xFFF59E0B)
    val Dnd = Color(0xFFEF4444)
    val Offline = Color(0xFF94A3B8)

    // Call surface (always dark, regardless of app theme)
    val CallBg = Color(0xFF07090E)
    val TileBg = Color(0xFF141922)
    val TileBgAlt = Color(0xFF1B2230)
    val Danger = Color(0xFFEF4444)
    val Success = Color(0xFF22C55E)

    // Avatar fallback gradients, picked pairwise for contrast with white text
    val AvatarGradients = listOf(
        Color(0xFF6366F1) to Color(0xFF8B5CF6),
        Color(0xFF10B981) to Color(0xFF06B6D4),
        Color(0xFFF59E0B) to Color(0xFFF43F5E),
        Color(0xFF8B5CF6) to Color(0xFFEC4899),
        Color(0xFF0EA5E9) to Color(0xFF6366F1),
        Color(0xFFF43F5E) to Color(0xFFF59E0B),
    )
}

/**
 * The same theme / accent / corner presets the web client offers
 * (web/src/lib/theme.ts), so an account looks the same on every device.
 */
data class ThemePreset(val id: String, val name: String, val dark: Boolean, val bg: Color, val card: Color)

val ThemePresets = listOf(
    ThemePreset("dark", "Dark", true, Color(0xFF0B0E14), Color(0xFF151B28)),
    ThemePreset("light", "Light", false, Color(0xFFF4F4F5), Color(0xFFFFFFFF)),
    ThemePreset("midnight", "Midnight", true, Color(0xFF000000), Color(0xFF0D0D0D)),
    ThemePreset("sunset", "Sunset", true, Color(0xFF120B18), Color(0xFF1D1226)),
    ThemePreset("forest", "Forest", true, Color(0xFF06110C), Color(0xFF0C2017)),
    ThemePreset("cyberpunk", "Cyberpunk", true, Color(0xFF060913), Color(0xFF0E162C)),
)

data class AccentPreset(val id: String, val name: String, val color: Color)

val AccentPresets = listOf(
    AccentPreset("blue", "Blue", Color(0xFF3B82F6)),
    AccentPreset("purple", "Violet", Color(0xFF8B5CF6)),
    AccentPreset("emerald", "Emerald", Color(0xFF10B981)),
    AccentPreset("rose", "Rose", Color(0xFFF43F5E)),
    AccentPreset("amber", "Amber", Color(0xFFF59E0B)),
    AccentPreset("cyan", "Cyan", Color(0xFF06B6D4)),
)

val RadiusPresets = listOf("rounded" to "Smooth", "compact" to "Sharp", "pill" to "Pill")

private fun themePreset(id: String) = ThemePresets.firstOrNull { it.id == id } ?: ThemePresets[0]
private fun accentPreset(id: String) = AccentPresets.firstOrNull { it.id == id } ?: AccentPresets[0]

private fun darkScheme(t: ThemePreset, accent: Color): ColorScheme {
    val onSurface = Color(0xFFE8EAF0)
    val muted = Color(0xFF9AA3B5)
    fun tone(f: Float) = lerp(t.bg, Color.White, f)
    return darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.22f).compositeOver(t.card),
        onPrimaryContainer = lerp(accent, Color.White, 0.55f),
        secondary = lerp(accent, Color.White, 0.35f),
        onSecondary = Color.Black,
        secondaryContainer = tone(0.10f),
        onSecondaryContainer = onSurface,
        tertiary = VcColor.Online,
        onTertiary = Color.Black,
        background = t.bg,
        onBackground = onSurface,
        surface = t.bg,
        onSurface = onSurface,
        surfaceVariant = tone(0.09f),
        onSurfaceVariant = muted,
        surfaceTint = accent,
        surfaceContainerLowest = t.bg,
        surfaceContainerLow = lerp(t.bg, t.card, 0.6f),
        surfaceContainer = t.card,
        surfaceContainerHigh = lerp(t.card, Color.White, 0.05f),
        surfaceContainerHighest = lerp(t.card, Color.White, 0.09f),
        surfaceBright = lerp(t.card, Color.White, 0.12f),
        surfaceDim = t.bg,
        inverseSurface = onSurface,
        inverseOnSurface = t.bg,
        outline = tone(0.22f),
        outlineVariant = tone(0.12f),
        error = Color(0xFFF87171),
        onError = Color.Black,
        errorContainer = Color(0xFF3B1418),
        onErrorContainer = Color(0xFFFECACA),
        scrim = Color.Black,
    )
}

private fun lightScheme(t: ThemePreset, accent: Color): ColorScheme {
    val onSurface = Color(0xFF111827)
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.12f).compositeOver(Color.White),
        onPrimaryContainer = lerp(accent, Color.Black, 0.45f),
        secondary = lerp(accent, Color.Black, 0.2f),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEDEEF2),
        onSecondaryContainer = onSurface,
        tertiary = Color(0xFF16A34A),
        onTertiary = Color.White,
        background = t.bg,
        onBackground = onSurface,
        surface = t.bg,
        onSurface = onSurface,
        surfaceVariant = Color(0xFFE9EAEF),
        onSurfaceVariant = Color(0xFF5B6272),
        surfaceTint = accent,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFAFAFB),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFE8EAEF),
        surfaceContainerHighest = Color(0xFFDDE0E7),
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFE4E4E7),
        outline = Color(0xFFCBCED6),
        outlineVariant = Color(0xFFE3E5EA),
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
    )
}

private fun shapesFor(radius: String): Shapes {
    val scale = when (radius) {
        "compact" -> 0.45f
        "pill" -> 1.45f
        else -> 1f
    }
    fun r(v: Int) = RoundedCornerShape((v * scale).dp)
    return Shapes(extraSmall = r(6), small = r(10), medium = r(14), large = r(20), extraLarge = r(28))
}

private val VcType = Typography().let { base ->
    Typography(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp, letterSpacing = 0.1.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodySmall = base.bodySmall.copy(lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    )
}

/** Motion tokens so every screen shares the same rhythm. */
object VcMotion {
    const val Fast = 120
    const val Standard = 220
    const val Slow = 320
}

/**
 * The account's look (theme/accent/corners — synced with the server, same
 * values as the web client) plus one Android-only switch: follow the system
 * light/dark setting, in which case a light system uses the Light theme and a
 * dark system uses the account's chosen dark theme.
 */
object ThemeState {
    private val _prefs = MutableStateFlow(UserPreferences())
    val prefs: StateFlow<UserPreferences> = _prefs
    private val _followSystem = MutableStateFlow(false)
    val followSystem: StateFlow<Boolean> = _followSystem

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("vc_theme", Context.MODE_PRIVATE)
        _prefs.value = UserPreferences(
            theme = p.getString("theme", "dark") ?: "dark",
            accent_color = p.getString("accent", "blue") ?: "blue",
            radius = p.getString("radius", "rounded") ?: "rounded",
        )
        _followSystem.value = p.getBoolean("follow_system", false)
    }

    private fun persist(ctx: Context) {
        val v = _prefs.value
        ctx.getSharedPreferences("vc_theme", Context.MODE_PRIVATE).edit()
            .putString("theme", v.theme).putString("accent", v.accent_color).putString("radius", v.radius)
            .putBoolean("follow_system", _followSystem.value)
            .apply()
    }

    /** Applies the account's saved preferences (login / session resume). */
    fun applyServer(ctx: Context, prefs: UserPreferences) {
        _prefs.value = prefs
        persist(ctx)
    }

    /** A local change from Settings; the caller also saves it to the server. */
    fun update(ctx: Context, prefs: UserPreferences) {
        _prefs.value = prefs
        persist(ctx)
    }

    fun setFollowSystem(ctx: Context, on: Boolean) {
        _followSystem.value = on
        persist(ctx)
    }
}

/** True when the effective theme is dark (for system bar icon contrast etc.). */
@Composable
fun isAppInDarkTheme(): Boolean {
    val prefs by ThemeState.prefs.collectAsState()
    val follow by ThemeState.followSystem.collectAsState()
    val systemDark = isSystemInDarkTheme()
    return if (follow && !systemDark) false else themePreset(prefs.theme).dark
}

@Composable
fun VisionCallTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val prefs by ThemeState.prefs.collectAsState()
    val follow by ThemeState.followSystem.collectAsState()
    val systemDark = isSystemInDarkTheme()
    var preset = themePreset(prefs.theme)
    if (follow && !systemDark) preset = themePreset("light")
    if (follow && systemDark && !preset.dark) preset = themePreset("dark")
    if (forceDark && !preset.dark) preset = themePreset("dark")
    val accent = accentPreset(prefs.accent_color).color
    val colors = if (preset.dark) darkScheme(preset, accent) else lightScheme(preset, accent)
    MaterialTheme(colorScheme = colors, typography = VcType, shapes = shapesFor(prefs.radius), content = content)
}
