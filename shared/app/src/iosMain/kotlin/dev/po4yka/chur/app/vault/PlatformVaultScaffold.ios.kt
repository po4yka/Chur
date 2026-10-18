@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.LocalChurColors

internal val LocalIosVaultSession = compositionLocalOf { 0L }

internal val LocalIosVaultChromeCovered = compositionLocalOf { false }

internal val LocalIosVaultChrome = compositionLocalOf<IosVaultChrome?> { null }

/** UIKit is a sibling above the shared content, so its material samples real content. */
@Composable
internal actual fun PlatformVaultScaffold(
    state: VaultUiState,
    actions: VaultActions,
    navigationBusy: Boolean,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val chrome = checkNotNull(LocalIosVaultChrome.current) { "Vault chrome requires the iOS root controller" }
    val session = LocalIosVaultSession.current
    val covered = LocalIosVaultChromeCovered.current
    val colors = LocalChurColors.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val bottom = safe.calculateBottomPadding() + 49.dp
    DisposableEffect(chrome, session) {
        if (session != 0L) chrome.beginSession() else chrome.clear()
        onDispose { chrome.clear() }
    }
    SideEffect {
        if (session != 0L) chrome.update(state, actions, colors.accent, navigationBusy, covered) else chrome.clear()
    }
    CompositionLocalProvider(LocalContentColor provides colors.ink, LocalVaultBottomInset provides bottom) {
        Box(Modifier.fillMaxSize().background(colors.canvas)) {
            content(PaddingValues(
                start = safe.calculateStartPadding(direction),
                top = safe.calculateTopPadding() + 44.dp,
                end = safe.calculateEndPadding(direction),
            ))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 8.dp)) { snackbarHost() }
        }
    }
}
