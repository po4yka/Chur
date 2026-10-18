package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** Scrollable content extends behind the platform tab bar, with reachable final rows. */
internal val LocalVaultBottomInset = compositionLocalOf { 0.dp }

/** The session owns navigation; platform chrome only presents it and forwards commands. */
@Composable
internal expect fun PlatformVaultScaffold(
    state: VaultUiState,
    actions: VaultActions,
    navigationBusy: Boolean,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
)
