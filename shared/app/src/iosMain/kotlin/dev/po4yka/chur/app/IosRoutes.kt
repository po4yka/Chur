package dev.po4yka.chur.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.app.notes.NoteEditorScreen
import dev.po4yka.chur.app.notes.NotesScreen
import dev.po4yka.chur.app.vault.CreateVaultScreen
import dev.po4yka.chur.app.vault.LibraryTile
import dev.po4yka.chur.app.vault.RecoveryPhraseScreen
import dev.po4yka.chur.app.vault.RecoveryScreen
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.UnlockScreen
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * The iOS route table.
 *
 * It is the Android one without the one thing the Android host owns: the photo
 * picker, which is a `PHPickerViewController` the Xcode project presents. The
 * picker calls back into [ChurController] with the file URL, so the flow is the
 * same and the presentation is the platform's.
 */
@Composable
internal fun IosRoutes(controller: ChurController, route: AppRoute, vaultState: VaultState) {
    val phrase by controller.recoveryPhrase.collectAsState()
    val message by controller.message.collectAsState()

    phrase?.let { value ->
        RecoveryPhraseScreen(phrase = value, onAcknowledged = controller::acknowledgeRecoveryPhrase)
        return
    }

    when (route) {
        AppRoute.PublicShell, AppRoute.PublicSettings -> PublicShell(controller)
        AppRoute.CreateVault -> CreateVaultScreen(
            busy = vaultState is VaultState.Creating,
            error = message,
            onCreate = controller::create,
            onCancel = { controller.goTo(AppRoute.PublicShell) },
        )
        AppRoute.Unlock -> UnlockScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.Recover) },
        )
        AppRoute.Recover -> RecoveryScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null,
            onRecover = controller::recover,
            onBack = { controller.goTo(AppRoute.Unlock) },
        )
        AppRoute.Vault -> VaultRoute(controller, vaultState)
    }
}

@Composable
private fun PublicShell(controller: ChurController) {
    val notes by controller.notesState.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }

    editing?.let { note ->
        NoteEditorScreen(
            note = note,
            onSave = controller::putNote,
            onDelete = {
                controller.removeNote(note.id)
                editing = null
            },
            onBack = { editing = null },
        )
        return
    }

    NotesScreen(
        notes = notes,
        query = query,
        onQueryChange = { query = it },
        onOpen = { editing = it },
        onCreate = {
            val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
            editing = Note(id = "note-$now", title = "", body = "", updatedMs = now)
        },
        onOpenSettings = controller::openVaultEntry,
    )
}

@Composable
private fun VaultRoute(controller: ChurController, vaultState: VaultState) {
    val page by controller.page.collectAsState()
    val albums by controller.albums.collectAsState()
    val slots by controller.slots.collectAsState()
    val message by controller.message.collectAsState()
    var destination by remember { mutableStateOf(VaultDestination.LIBRARY) }
    var terms by remember { mutableStateOf("") }
    var openAlbum by remember { mutableStateOf<AlbumSummary?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(destination, openAlbum) {
        when {
            openAlbum != null -> controller.load(
                ObjectQuery(QueryScope.ALBUM, scopeId = openAlbum!!.albumId),
            )
            destination == VaultDestination.LIBRARY -> controller.load(ObjectQuery())
            destination == VaultDestination.ALBUMS -> controller.loadAlbums()
            destination == VaultDestination.SETTINGS -> controller.loadSlots()
            else -> Unit
        }
    }

    val scope = rememberCoroutineScope()
    val cache = remember { ThumbnailCache() }
    val generation = (vaultState as? VaultState.Unlocked)?.generation ?: 0L
    // §4 of `PLAINTEXT_LIFECYCLE.md`: the decoded cache is cleared on lock, and
    // the session generation is what makes a stale entry unreachable after a
    // new session opens.
    LaunchedEffect(generation) { cache.clear() }

    // The tiles carry whatever the cache already holds, and a tile whose
    // thumbnail is missing loads it. §11.1 keeps the geometry stable while it
    // arrives, so nothing jumps.
    var thumbnails by remember { mutableStateOf(mapOf<String, ImageBitmap>()) }
    LaunchedEffect(page, generation) {
        page.objects.filter { it.thumbnailReady }.forEach { projection ->
            val image = cache.load(
                repository = controller.vault,
                generation = generation,
                objectId = projection.objectId,
                id = projection.id,
            )
            if (image != null) {
                thumbnails = thumbnails + (projection.id to image)
            }
        }
    }

    VaultShell(
        state = VaultUiState(
            destination = destination,
            tiles = page.objects.map { projection ->
                LibraryTile(
                    projection = projection,
                    thumbnail = thumbnails[projection.id],
                    selected = projection.id in selection,
                )
            },
            albums = albums,
            searchTerms = terms,
            slots = slots,
            openAlbum = openAlbum,
            widthDp = 400,
            progress = message,
            selectedCount = selection.size,
        ),
        actions = VaultActions(
            onDestination = {
                openAlbum = null
                selection = emptySet()
                destination = it
            },
            onOpen = { projection ->
                selection = if (projection.id in selection) {
                    selection - projection.id
                } else {
                    selection + projection.id
                }
            },
            onToggleSelection = { projection ->
                selection = if (projection.id in selection) {
                    selection - projection.id
                } else {
                    selection + projection.id
                }
            },
            // The picker is the Xcode project's, so this asks it rather than
            // presenting one: a Compose composable cannot present a UIKit view
            // controller without the host's window.
            onImport = { controller.report("Choose a photo from the picker.") },
            onSearch = {
                terms = it
                controller.search(it)
            },
            onOpenAlbum = { openAlbum = it },
            onCloseAlbum = { openAlbum = null },
            onCreateAlbum = { controller.createAlbum("Album") },
            onLock = {
                // §8 step 7 clears the decoded cache, as the Android host does.
                scope.launch { cache.clear() }
                controller.lock()
            },
            onVerifyAll = { controller.verifyEverything() },
            onAddRecoverySlot = controller::addRecoverySlot,
            onSelectAll = { selection = page.objects.map { it.id }.toSet() },
            onClearSelection = { selection = emptySet() },
            onExportSelection = {
                controller.exportAll(selectedObjects(page, selection))
                selection = emptySet()
            },
            onRemoveSelectionFromAlbum = {
                openAlbum?.let { album ->
                    controller.removeAllFromAlbum(album.albumId, selectedObjects(page, selection))
                }
                selection = emptySet()
            },
            onDeleteSelection = {
                controller.deleteAll(selectedObjects(page, selection))
                selection = emptySet()
            },
        ),
    )
}

/** The object identifiers the selection names, in the page's order. */
private fun selectedObjects(
    page: ObjectPage,
    selection: Set<String>,
): List<ByteArray> = page.objects.filter { it.id in selection }.map { it.objectId }
