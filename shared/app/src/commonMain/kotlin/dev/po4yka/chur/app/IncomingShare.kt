package dev.po4yka.chur.app

import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The host retains provider access. No source is opened before confirmation. */
interface IncomingShareSource {
    val count: Int
    suspend fun open(index: Int, prepare: (Long, Long) -> Boolean): PickedMedia?
    /** Releases every unconsumed source. Called once, after import custody drains. */
    fun close()
}

/** Public state contains no filenames, provider references, or shared text. */
data class IncomingShareState(val id: Long, val count: Int, val busy: Boolean = false)

/**
 * One foreground handoff, owned by the process host and called on the UI thread.
 * A second handoff cannot replace a pending or running batch. Nothing here is
 * saved in instance state, public Notes, or a public preferences file.
 */
class IncomingShareInbox {
    private var source: IncomingShareSource? = null
    private var nextId = 0L
    private val mutableState = MutableStateFlow<IncomingShareState?>(null)
    val state: StateFlow<IncomingShareState?> = mutableState.asStateFlow()

    fun offer(incoming: IncomingShareSource): Boolean {
        if (source != null || incoming.count !in 1..MAX_ITEMS) {
            incoming.close()
            return false
        }
        source = incoming
        mutableState.value = IncomingShareState(++nextId, incoming.count)
        return true
    }

    /** A running importer owns its sources until its cancellation has drained. */
    fun discard() {
        if (mutableState.value?.busy == true) return
        val discarded = source
        source = null
        mutableState.value = null
        discarded?.close()
    }

    suspend fun import(
        controller: ChurController,
        importer: MediaImporter,
        albumId: ByteArray?,
    ): List<MediaImporter.Outcome> {
        val pending = mutableState.value ?: return emptyList()
        val incoming = source ?: return emptyList()
        val generation = (controller.vaultState.value as? VaultState.Unlocked)?.generation ?: return emptyList()
        if (pending.busy || controller.route.value != AppRoute.Vault || controller.activeOperation.value != null) {
            return emptyList()
        }
        mutableState.value = pending.copy(busy = true)
        try {
            return controller.importAll(importer, pending.count, albumId?.copyOf(), expectedGeneration = generation) { index, prepare ->
                incoming.open(index, prepare)
            }
        } finally {
            source = null
            mutableState.value = null
            incoming.close()
        }
    }

    companion object { const val MAX_ITEMS = 100 }
}
