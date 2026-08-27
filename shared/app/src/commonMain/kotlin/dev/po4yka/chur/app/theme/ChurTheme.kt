package dev.po4yka.chur.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The surface ladder of `DESIGN.md` §6.2 and the one accent of §6.1.
 *
 * The tokens are the document's hexadecimal values and nothing else. §6.1's
 * rule is what makes the palette look the way it does: neutral structure, user
 * media, and one accent, with the strongest chroma left to the content. The
 * accent is reserved for focus, selection, progress, links, and explicit active
 * state, so a primary action is near-black on light and near-white on dark.
 */
@Immutable
data class ChurColors(
    /** The page behind every surface. */
    val canvas: Color,
    /** An ordinary raised surface. */
    val surface: Color,
    /** A quieter surface, for grouping. */
    val surfaceSubtle: Color,
    /** A recessed surface, for wells and fields. */
    val surfaceSunken: Color,
    /** Primary text and primary action fill. */
    val ink: Color,
    /** Secondary text. */
    val inkMuted: Color,
    /** The one accent, §6.1. */
    val accent: Color,
    /** The accent under press. */
    val accentPressed: Color,
    /** A tinted background for the accent. */
    val accentSoft: Color,
    /** Integrity uncertainty, §6.3. */
    val warning: Color,
    /** Confirmed corruption, §6.3. */
    val error: Color,
    /** A hairline. */
    val outline: Color,
    /** True when the dark ladder is in use. */
    val dark: Boolean,
) {
    /** The text that reads on [ink] when it is a fill. */
    val onInk: Color get() = if (dark) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
}

/** The light ladder of §6.2. */
val ChurLightColors = ChurColors(
    canvas = Color(0xFFFAFAF9),
    surface = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFF4F4F2),
    surfaceSunken = Color(0xFFEEEEEB),
    ink = Color(0xFF171717),
    inkMuted = Color(0xFF5C5C58),
    accent = Color(0xFF315EF7),
    accentPressed = Color(0xFF2448C9),
    accentSoft = Color(0xFFE9EEFF),
    warning = Color(0xFF8A5A00),
    error = Color(0xFFB3261E),
    outline = Color(0xFFDCDCD8),
    dark = false,
)

/** The dark ladder of §6.2. */
val ChurDarkColors = ChurColors(
    canvas = Color(0xFF0A0A0A),
    surface = Color(0xFF111111),
    surfaceSubtle = Color(0xFF171717),
    surfaceSunken = Color(0xFF1D1D1D),
    ink = Color(0xFFF5F5F3),
    inkMuted = Color(0xFFA3A39D),
    accent = Color(0xFF7D98FF),
    accentPressed = Color(0xFFA7B7FF),
    accentSoft = Color(0xFF1C2852),
    warning = Color(0xFFE0A34A),
    error = Color(0xFFF2B8B5),
    outline = Color(0xFF2A2A2A),
    dark = true,
)

/** The viewer's own ladder, §6.2. */
object ViewerColors {
    /** The viewer canvas is black whatever the theme. */
    val canvas = Color(0xFF000000)

    /** The chrome scrim over media. */
    val chromeScrim = Color(0xB8000000)

    /** Viewer text and icons. */
    val content = Color(0xFFFFFFFF)
}

/** The 4dp rhythm of §8.1. The 2dp token is optical correction only. */
object ChurSpacing {
    /** Optical correction and hairline-adjacent adjustment. */
    val hairline = 2.dp

    /** One step. */
    val one = 4.dp

    /** Two steps. */
    val two = 8.dp

    /** Three steps. */
    val three = 12.dp

    /** Four steps, the compact gutter. */
    val gutter = 16.dp

    /** Six steps, the medium gutter. */
    val gutterMedium = 24.dp

    /** Eight steps, the expanded gutter. */
    val gutterExpanded = 32.dp
}

/** Where the colours come from inside a composition. */
val LocalChurColors: ProvidableCompositionLocal<ChurColors> =
    staticCompositionLocalOf { ChurLightColors }

/**
 * The typography of §7.
 *
 * The stack is left to the platform: §7.1 requires that security-critical
 * readability not depend on a custom font, and a bundled face that failed to
 * load would take the unlock screen's instruction with it. Weight stops at 600,
 * headings use modest negative tracking, and nothing routine is all caps.
 */
private val churTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.W600,
            letterSpacing = (-0.4).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.W600,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.W600),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.W500),
    )
}

/** A style for a diagnostic identifier or a format version, §7.1. */
val DiagnosticTextStyle: TextStyle = TextStyle(
    fontSize = 13.sp,
    fontWeight = FontWeight.W400,
    letterSpacing = 0.sp,
)

/**
 * The application theme.
 *
 * Material's `colorScheme` is mapped from the tokens rather than the other way
 * round, which §25 of `DESIGN.md` asks for: the Chur accent becomes Material's
 * secondary, and Material's primary is the near-black or near-white ink, so a
 * Material component that reaches for `primary` gets the ink rather than the
 * blue.
 */
@Composable
fun ChurTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) ChurDarkColors else ChurLightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.ink,
            onPrimary = colors.onInk,
            secondary = colors.accent,
            onSecondary = colors.onInk,
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.ink,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSubtle,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.outline,
            error = colors.error,
        )
    } else {
        lightColorScheme(
            primary = colors.ink,
            onPrimary = colors.onInk,
            secondary = colors.accent,
            onSecondary = Color.White,
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.ink,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSubtle,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.outline,
            error = colors.error,
        )
    }
    CompositionLocalProvider(LocalChurColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = churTypography, content = content)
    }
}
