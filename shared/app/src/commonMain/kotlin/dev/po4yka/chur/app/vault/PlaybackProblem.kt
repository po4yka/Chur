package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.core.model.ChurStatus

/** Safe user-facing categories. Native diagnostic strings never reach the viewer. */
enum class PlaybackProblem {
    INTEGRITY,
    UNAVAILABLE,
    UNSUPPORTED,
}

internal fun playbackProblem(status: ChurStatus): PlaybackProblem = when (status) {
    ChurStatus.OBJECT_CORRUPT, ChurStatus.VAULT_CORRUPT, ChurStatus.CATALOG_CORRUPT -> PlaybackProblem.INTEGRITY
    ChurStatus.UNSUPPORTED_VERSION, ChurStatus.UNSUPPORTED_SUITE -> PlaybackProblem.UNSUPPORTED
    else -> PlaybackProblem.UNAVAILABLE
}

internal fun playbackProblemMessage(problem: PlaybackProblem): String = when (problem) {
    PlaybackProblem.INTEGRITY -> "Playback stopped because this file failed its integrity check."
    PlaybackProblem.UNAVAILABLE -> "This file is not available for playback. Verify the library to check its status."
    PlaybackProblem.UNSUPPORTED -> "This device cannot play this file. You can export it to use a compatible player."
}

@Composable
internal fun PlaybackProblemPanel(
    problem: PlaybackProblem,
    onVerify: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(problem) {
        if (problem == PlaybackProblem.INTEGRITY) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }
    Column(
        modifier.background(ViewerColors.chromeScrim).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(playbackProblemMessage(problem), color = ViewerColors.content)
        if (onVerify != null) {
            TextButton(onClick = onVerify) { Text("Verify library", color = ViewerColors.content) }
        }
    }
}
