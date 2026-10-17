package dev.po4yka.chur.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** Motion tokens from `DESIGN.md` §22. Privacy covers must remain immediate. */
@Immutable
data class ChurMotion(val reducedMotion: Boolean = true) {
    val stateDurationMillis: Int get() = if (reducedMotion) 120 else 180
    val navigationDurationMillis: Int get() = if (reducedMotion) 120 else 240
    val mediaDurationMillis: Int get() = if (reducedMotion) 120 else 280
    val pressDurationMillis: Int get() = if (reducedMotion) 0 else 90
}

/** Use short crossfades, without spatial transforms, when [ChurMotion.reducedMotion] is true. */
val LocalChurMotion: ProvidableCompositionLocal<ChurMotion> =
    staticCompositionLocalOf { ChurMotion() }

@Composable
internal expect fun rememberReducedMotion(): Boolean
