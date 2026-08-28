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
import dev.po4yka.chur.app.notes.PublicSettingsScreen
import dev.po4yka.chur.app.vault.CreateVaultScreen
import dev.po4yka.chur.app.vault.LibraryTile
import dev.po4yka.chur.app.vault.RecoveryPhraseScreen
import dev.po4yka.chur.app.vault.RecoveryScreen
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.UnlockScreen
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.app.vault.playbackFor
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.StreamKind
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
        AppRoute.PublicShell, AppRoute.PublicSettings -> PublicShell(controller, route)
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
private fun PublicShell(controller: ChurController, route: AppRoute) {
    val notes by controller.notesState.collectAsState()
    val disclosureDue by controller.disclosureDue.collectAsState()
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
        onOpenSettings = { controller.goTo(AppRoute.PublicSettings) },
        showFirstWriteDisclosure = disclosureDue,
        onAcknowledgeDisclosure = controller::acknowledgeDisclosure,
    )
    if (route == AppRoute.PublicSettings) {
        PublicSettingsScreen(
            onBack = { controller.goTo(AppRoute.PublicShell) },
            onOpenVault = controller::openVaultEntry,
        )
    }
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
    var viewing by remember { mutableStateOf<ObjectProjection?>(null) }

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
            // Opening is opening. Before the viewer existed on this host it
            // toggled selection, which made a video unreachable and a tap on a
            // photograph mean two things.
            onOpen = { projection -> viewing = projection },
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
            onPanic = {
                scope.launch { cache.clear() }
                controller.panic()
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

    viewing?.let { projection ->
        IosViewerRoute(
            controller = controller,
            cache = cache,
            generation = generation,
            projection = projection,
            onBack = { viewing = null },
            onDeleted = { viewing = null },
        )
    }
}

/**
 * The viewer over one object, with the platform player when it has one.
 *
 * `MEDIA_PIPELINE.md` §8 has the timeline read derivatives and the viewer
 * decrypt more only for detailed viewing, which is why the preview is loaded
 * here rather than in the grid, and §9 has a video ask for ranges rather than
 * for a decoded file.
 */
@Composable
private fun IosViewerRoute(
    controller: ChurController,
    cache: ThumbnailCache,
    generation: Long,
    projection: ObjectProjection,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    var detail by remember(projection.id) { mutableStateOf<ObjectDetail?>(null) }
    var preview by remember(projection.id) { mutableStateOf<ImageBitmap?>(null) }
    var showDetail by remember(projection.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(projection.id, generation) {
        preview = cache.load(
            repository = controller.vault,
            generation = generation,
            objectId = projection.objectId,
            id = projection.id,
            kind = StreamKind.SCREEN_PREVIEW,
        ) ?: cache.load(
            repository = controller.vault,
            generation = generation,
            objectId = projection.objectId,
            id = projection.id,
            kind = StreamKind.THUMBNAIL,
        )
        detail = controller.detailOf(projection.objectId)
    }

    val playback = playbackFor(
        vault = controller.vault,
        objectId = projection.objectId,
        mediaKind = projection.mediaKind,
        detail = detail,
    )

    ViewerScreen(
        projection = projection,
        detail = detail,
        preview = preview,
        showDetail = showDetail,
        onBack = onBack,
        onToggleFavorite = {
            controller.setFavorite(projection.objectId, !projection.favorite)
        },
        onExport = { controller.export(projection.objectId) },
        onDelete = {
            scope.launch {
                controller.delete(projection.objectId)
                onDeleted()
            }
        },
        onToggleDetail = { showDetail = !showDetail },
        player = playback?.let { source ->
            { modifier -> VaultPlayer(source, modifier) }
        },
    )
}

/** The object identifiers the selection names, in the page's order. */
private fun selectedObjects(
    page: ObjectPage,
    selection: Set<String>,
): List<ByteArray> = page.objects.filter { it.id in selection }.map { it.objectId }
