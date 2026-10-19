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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.LocalChurColors

internal val LocalIosVaultChrome = compositionLocalOf<IosVaultChrome?> { null }

/** UIKit containers own chrome and safe-area metrics; this body only renders content. */
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
    val colors = LocalChurColors.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val bottom = safe.calculateBottomPadding()
    CompositionLocalProvider(LocalContentColor provides colors.ink, LocalVaultBottomInset provides bottom) {
        Box(Modifier.fillMaxSize().background(colors.canvas)) {
            content(PaddingValues(
                start = safe.calculateStartPadding(direction),
                top = safe.calculateTopPadding(),
                end = safe.calculateEndPadding(direction),
            ))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 8.dp)) { snackbarHost() }
        }
    }
}
