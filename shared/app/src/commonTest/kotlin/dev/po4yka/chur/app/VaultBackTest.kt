package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.app.vault.backStep
import dev.po4yka.chur.app.vault.SelectionActionLease
import dev.po4yka.chur.app.vault.selectionIdentity
import dev.po4yka.chur.ffi.AlbumSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            VaultUiState(libraryScopeTitle = "Recently added", recentlyAdded = true, selectedCount = 2),
            VaultUiState(libraryScopeTitle = "Recently added", recentlyAdded = true),
            VaultUiState(destination = VaultDestination.SETTINGS),
        ).forEach { state -> state.backStep(actions)!!.invoke() }

        assertEquals(listOf("selection", "album", "all media", "selection", "all media", "tab:LIBRARY"), steps)
        assertNull(VaultUiState().backStep(actions))
    }

    @Test
    fun a_busy_selection_consumes_back_without_clearing_or_leaving_its_album() {
        var clears = 0
        var closes = 0
        val actions = actions().copy(onClearSelection = { clears++ }, onCloseAlbum = { closes++ })
        val state = VaultUiState(
            destination = VaultDestination.ALBUMS,
            openAlbum = AlbumSummary(ByteArray(16) { 7 }, 0, "Album"),
            selectedCount = 1,
            selectionIds = setOf("object-a"),
            operation = ActiveOperation(1, "export"),
        )

        state.backStep(actions)!!.invoke()
        assertEquals(0, clears)
        assertEquals(0, closes)
        state.copy(operation = null).backStep(actions)!!.invoke()
        assertEquals(1, clears)
        assertEquals(0, closes)
        assertNull(VaultUiState(operation = state.operation).backStep(actions))
    }

    @Test
    fun a_retained_selection_command_rejects_equal_count_other_objects_busy_and_retired_panels() {
        val state = VaultUiState(selectedCount = 1, selectionIds = setOf("object-a"))
        val lease = SelectionActionLease(state.selectionIdentity())
        assertTrue(lease.canAct(state))
        assertFalse(lease.canAct(state.copy(selectionIds = setOf("object-b"))))
        assertFalse(lease.canAct(state.copy(selectedCount = 0, selectionIds = emptySet())))
        assertFalse(lease.canAct(state.copy(libraryScopeTitle = "Trash", trashOpen = true)))
        assertFalse(lease.canAct(state.copy(operation = ActiveOperation(1, "export"))))
        lease.retire()
        assertFalse(lease.canAct(state))
    }

    private fun actions() = VaultActions(
        onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
        onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
        onAddRecoverySlot = {}, onChangePassword = {},
    )

}
