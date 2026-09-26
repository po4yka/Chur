package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.app.vault.backStep
import dev.po4yka.chur.ffi.AlbumSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * System Back in the vault shell, `DESIGN.md` §22.1.
 *
 * Each press leaves the innermost level the user can see, and the Library root
 * declines, so the platform's back-to-home and the background lock stay where
 * they were.
 */
class VaultBackTest {
    @Test
    fun back_leaves_selection_then_album_then_scope_then_tab_and_stops_at_library() {
        val steps = mutableListOf<String>()
        val actions = VaultActions(
            onDestination = { steps += "tab:$it" },
            onOpen = {},
            onToggleSelection = {},
            onImport = {},
            onSearch = {},
            onOpenAlbum = {},
            onCloseAlbum = { steps += "album" },
            onCreateAlbum = {},
            onShowAllMedia = { steps += "all media" },
            onLock = {},
            onVerifyAll = {},
            onAddRecoverySlot = {},
            onChangePassword = {},
            onClearSelection = { steps += "selection" },
        )
        val album = AlbumSummary(ByteArray(16) { 7 }, 0, "Album")

        listOf(
            VaultUiState(destination = VaultDestination.ALBUMS, openAlbum = album, selectedCount = 2),
            VaultUiState(destination = VaultDestination.ALBUMS, openAlbum = album),
            VaultUiState(libraryScopeTitle = "Trash", trashOpen = true),
            VaultUiState(destination = VaultDestination.SETTINGS),
        ).forEach { state -> state.backStep(actions)!!.invoke() }

        assertEquals(listOf("selection", "album", "all media", "tab:LIBRARY"), steps)
        assertNull(VaultUiState().backStep(actions))
    }
}
