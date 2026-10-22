package dev.po4yka.chur.app.publicshell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import dev.po4yka.chur.app.theme.*

fun utilityText(english: String, russian: String): String = if (Locale.current.language == "ru") russian else english

@Composable
fun PublicUtilityTheme(store: PublicUtilityStore, content: @Composable () -> Unit) {
    val appearance by store.appearance.collectAsState()
    val dark = when (appearance) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    ChurTheme(dark = dark) {
        val base = LocalChurColors.current
        val colors = base.copy(
            canvas = if (dark) Color(0xFF141719) else Color(0xFFF5F7F8),
            accent = if (dark) Color(0xFF91C9C4) else Color(0xFF266963),
            accentSoft = if (dark) Color(0xFF243C3A) else Color(0xFFDCEEEB),
            brandCord = Color.Transparent,
        )
        CompositionLocalProvider(LocalPublicUtilities provides store, LocalChurColors provides colors) {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                primary = colors.accent, onPrimary = if (dark) Color(0xFF102C29) else Color.White,
                primaryContainer = colors.accentSoft, onPrimaryContainer = colors.ink,
                background = colors.canvas, secondary = colors.accent, surfaceTint = Color.Transparent,
            ), typography = Typography(), content = content)
        }
    }
}
