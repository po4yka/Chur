package dev.po4yka.chur.app.vault

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull

/** Actual content edges, independent of row size, grid columns, or touch direction. */
internal data class VaultScrollEdges(val atTop: Boolean, val atBottom: Boolean)

internal interface VaultScrollObserver {
    fun attach(): VaultScrollObservation
}

internal interface VaultScrollObservation {
    fun update(edges: VaultScrollEdges)
    fun dispose()
}

/** Platforms without a scroll-reactive native container do not collect scroll state. */
internal val LocalVaultScrollObserver = compositionLocalOf<VaultScrollObserver?> { null }

/** A cancelled collector cannot publish through a viewport that already left composition. */
internal class VaultScrollObservationLease {
    private var observation: VaultScrollObservation? = null
    private var disposed = false

    fun attach(observer: VaultScrollObserver) {
        check(!disposed && observation == null)
        observation = observer.attach()
    }

    fun update(edges: VaultScrollEdges) {
        if (!disposed) observation?.update(edges)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val previous = observation
        observation = null
        previous?.dispose()
    }
}

@Composable
internal fun ObserveVaultScroll(state: LazyListState) {
    val observer = LocalVaultScrollObserver.current ?: return
    ObserveVaultScroll(observer, state) {
        if (state.layoutInfo.viewportSize.height > 0 && state.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            VaultScrollEdges(!state.canScrollBackward, !state.canScrollForward)
        } else null
    }
}

@Composable
internal fun ObserveVaultScroll(state: LazyGridState) {
    val observer = LocalVaultScrollObserver.current ?: return
    ObserveVaultScroll(observer, state) {
        if (state.layoutInfo.viewportSize.height > 0 && state.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            VaultScrollEdges(!state.canScrollBackward, !state.canScrollForward)
        } else null
    }
}

@Composable
private fun ObserveVaultScroll(
    observer: VaultScrollObserver,
    source: Any,
    readEdges: () -> VaultScrollEdges?,
) {
    val lease = remember(observer, source) { VaultScrollObservationLease() }
    val currentEdges by rememberUpdatedState(readEdges)
    DisposableEffect(lease) {
        lease.attach(observer)
        onDispose { lease.dispose() }
    }
    LaunchedEffect(lease) {
        snapshotFlow { currentEdges() }.filterNotNull().collect { lease.update(it) }
    }
}
