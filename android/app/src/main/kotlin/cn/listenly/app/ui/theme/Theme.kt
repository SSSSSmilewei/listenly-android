package cn.listenly.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** The seven accent choices in 我的 → 外观. [id] is what gets stored and synced. */
enum class Accent(
    val id: String,
    val label: String,
    val light: Color,
    val dark: Color,
    /** Text/icon tint on glass; darker than [light] so it keeps 4.5:1 on translucent white. */
    val lightText: Color,
    val darkText: Color,
    val onDark: Color = Color.White,
) {
    Mist("mist", "晨雾蓝", Color(0xFF3482FF), Color(0xFF4A90FF), Color(0xFF1F6FEB), Color(0xFF7FB0FF)),
    Pine("pine", "松针绿", Color(0xFF2F9E6E), Color(0xFF3DB683), Color(0xFF1D7F55), Color(0xFF6FD3A6)),
    Amber("amber", "琥珀橙", Color(0xFFE8770F), Color(0xFFF0912E), Color(0xFFB95A00), Color(0xFFF59A3C), onDark = Color(0xFF1A0E02)),
    Sakura("sakura", "樱粉", Color(0xFFE5577E), Color(0xFFF06F92), Color(0xFFC23A63), Color(0xFFFF9BB6)),
    Iris("iris", "鸢尾紫", Color(0xFF7B61FF), Color(0xFF8F7BFF), Color(0xFF5B41E0), Color(0xFFB4A6FF)),
    Ink("ink", "墨黑", Color(0xFF1C1C1E), Color(0xFFE8E8EA), Color(0xFF1C1C1E), Color(0xFFF2F2F4), onDark = Color(0xFF111113)),

    /** Android 12+ wallpaper colors; falls back to [Mist] where the platform has none. */
    Monet("monet", "跟随壁纸取色", Color(0xFF3A8E96), Color(0xFF5FB3BB), Color(0xFF2F7F86), Color(0xFF8FD3D9)),
    ;

    companion object {
        val Default = Mist
        fun fromId(id: String?): Accent = entries.firstOrNull { it.id == id } ?: Default
        val monetAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

enum class DarkMode(val id: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色"),
    ;

    companion object {
        fun fromId(id: String?): DarkMode = entries.firstOrNull { it.id == id } ?: System
    }
}

/** Colors miuix has no slot for: glass tints, wallpaper, readable accent text. */
@Immutable
data class ListenlyColors(
    val isDark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val accentSoft: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val hairline: Color,
    val glassRegular: Color,
    val glassClear: Color,
    val glassPill: Color,
    val wrong: Color,
    val onWrong: Color,
)

private val LocalListenlyColors = staticCompositionLocalOf<ListenlyColors> { error("ListenlyTheme missing") }

object Listenly {
    val colors: ListenlyColors
        @Composable @ReadOnlyComposable
        get() = LocalListenlyColors.current
}

/** Used by the vendored miuix liquid-glass components. */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = LocalListenlyColors.current.isDark

@Composable
fun ListenlyTheme(
    accent: Accent,
    darkMode: DarkMode,
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        DarkMode.System -> isSystemInDarkTheme()
        DarkMode.Light -> false
        DarkMode.Dark -> true
    }
    val miuixColors = if (accent == Accent.Monet && Accent.monetAvailable) {
        val controller = remember(dark) {
            ThemeController(if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight)
        }
        controller.currentColors()
    } else {
        remember(accent, dark) { accentColorScheme(accent, dark) }
    }
    val primary = miuixColors.primary
    val listenly = remember(accent, dark, primary) {
        val accentText = if (accent == Accent.Monet && Accent.monetAvailable) {
            primary
        } else if (dark) {
            accent.darkText
        } else {
            accent.lightText
        }
        val ink = if (dark) Color.White else Color(0xFF0B0F12)
        ListenlyColors(
            isDark = dark,
            accent = primary,
            onAccent = if (dark) accent.onDark else Color.White,
            accentText = accentText,
            accentSoft = primary.copy(alpha = if (dark) 0.18f else 0.13f),
            text = ink.copy(alpha = if (dark) 0.92f else 1f),
            textSecondary = ink.copy(alpha = if (dark) 0.62f else 0.64f),
            textTertiary = ink.copy(alpha = if (dark) 0.42f else 0.45f),
            hairline = ink.copy(alpha = if (dark) 0.08f else 0.07f),
            glassRegular = if (dark) Color(0xFF1C1E22).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.58f),
            glassClear = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.34f),
            glassPill = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f),
            // Wrong answers stay one fixed hue whatever the accent, and always pair with an icon.
            wrong = if (dark) Color(0xFFFF7A6B) else Color(0xFFD9442F),
            onWrong = Color.White,
        )
    }
    CompositionLocalProvider(LocalListenlyColors provides listenly) {
        MiuixTheme(colors = miuixColors, content = content)
    }
}

private fun accentColorScheme(accent: Accent, dark: Boolean) = if (dark) {
    val primary = accent.dark
    darkColorScheme(
        primary = primary,
        onPrimary = accent.onDark,
        primaryVariant = primary,
        primaryContainer = primary,
        onPrimaryContainer = accent.onDark,
        tertiaryContainer = primary.copy(alpha = 0.22f),
        onTertiaryContainer = accent.darkText,
        tertiaryContainerVariant = primary.copy(alpha = 0.22f),
        sliderKeyPointForeground = primary,
        disabledPrimary = primary.copy(alpha = 0.4f),
        disabledPrimaryButton = primary.copy(alpha = 0.4f),
        disabledPrimarySlider = primary.copy(alpha = 0.4f),
    )
} else {
    val primary = accent.light
    lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryVariant = primary,
        primaryContainer = primary,
        onPrimaryContainer = Color.White,
        tertiaryContainer = primary.copy(alpha = 0.12f),
        onTertiaryContainer = accent.lightText,
        tertiaryContainerVariant = primary.copy(alpha = 0.12f),
        sliderKeyPointForeground = primary,
        disabledPrimary = primary.copy(alpha = 0.35f),
        disabledPrimaryButton = primary.copy(alpha = 0.35f),
        disabledPrimarySlider = primary.copy(alpha = 0.35f),
    )
}
