@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.uikit.LocalUIViewController
import dev.po4yka.chur.app.notes.NoteEditorScreen
import dev.po4yka.chur.app.notes.NotesScreen
import dev.po4yka.chur.app.notes.OpenNote
import dev.po4yka.chur.app.notes.PublicSettingsScreen
import dev.po4yka.chur.app.notes.rememberOpenNote
import dev.po4yka.chur.app.vault.CreateVaultScreen
import dev.po4yka.chur.app.vault.LibraryTile
import dev.po4yka.chur.app.vault.AlbumPickerDialog
import dev.po4yka.chur.app.vault.DeleteSelectionDialog
import dev.po4yka.chur.app.vault.ExportOptionsDialog
import dev.po4yka.chur.app.vault.canSaveToPhotos
import dev.po4yka.chur.app.vault.NewAlbumDialog
import dev.po4yka.chur.app.vault.TagPickerDialog
import dev.po4yka.chur.app.vault.TagBrowserDialog
import dev.po4yka.chur.app.vault.RecoveryPhraseScreen
import dev.po4yka.chur.app.vault.RecoveryScreen
import dev.po4yka.chur.app.vault.RestoreBackupScreen
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.UnlockScreen
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.ContentView
import dev.po4yka.chur.app.vault.AlbumOrder
import dev.po4yka.chur.app.vault.browseQuery
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.MEDIA_CLASS_AUDIO
import dev.po4yka.chur.app.vault.viewerPages
import dev.po4yka.chur.app.vault.viewerStill
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.app.vault.ViewerChrome
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.app.vault.PresentedState
import dev.po4yka.chur.app.vault.playbackFor
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.QuerySort
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.ffi.TagSummary
import dev.po4yka.chur.ffi.fromHex
import dev.po4yka.chur.imports.IosMediaCodec
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.unlink

/**
 * The iOS route table.
 *
 * The photo picker is presented by the Xcode host through [IosMediaPicker].
 */
@Composable
internal fun IosRoutes(controller: ChurController, route: AppRoute, vaultState: VaultState) {
    val phrase by controller.recoveryPhrase.collectAsState()
    val formError by controller.formError.collectAsState()
    val unlocking by controller.unlocking.collectAsState()

    phrase?.let { value ->
        // Copying 24 words by hand, `RECOVERY.md` §2.3, outlasts the
        // auto-lock, which backgrounds the scene and drops the phrase. The
        // repository's phrase window bounds how long the display stays awake.
        DisposableEffect(Unit) {
            UIApplication.sharedApplication.idleTimerDisabled = true
            onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
        }
        RecoveryPhraseScreen(phrase = value, onAcknowledged = controller::acknowledgeRecoveryPhrase)
        return
    }

    when (route) {
        AppRoute.PublicShell, AppRoute.PublicSettings -> PublicShell(controller, route)
        AppRoute.CreateVault -> CreateVaultScreen(
            busy = vaultState is VaultState.Creating,
            error = formError,
            onCreate = controller::create,
            // A second vault is set up over an open session, and the public
            // shell is the last step of a lock, `PLAINTEXT_LIFECYCLE.md` §8.
            // The route check keeps a lock that already moved the route from
            // being undone by this composition's stale vault state.
            onCancel = {
                if (controller.route.value == route) {
                    controller.goTo(if (vaultState is VaultState.Unlocked) AppRoute.Vault else AppRoute.PublicShell)
                }
            },
            onRestore = if (vaultState is VaultState.NoVault) {
                { controller.goTo(AppRoute.RestoreBackup) }
            } else {
                null
            },
        )
        AppRoute.RestoreBackup -> IosRestoreRoute(controller)
        AppRoute.Unlock -> UnlockScreen(
            busy = unlocking,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || formError != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.Recover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithAppleDevice,
        )
        AppRoute.AppUnlock -> UnlockScreen(
            busy = unlocking,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || formError != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.AppRecover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithAppleDevice,
            appGate = true,
        )
        AppRoute.Recover -> RecoveryScreen(
            busy = unlocking,
            error = formError,
            onRecover = controller::recover,
            onBack = { controller.goTo(AppRoute.Unlock) },
        )
        AppRoute.AppRecover -> RecoveryScreen(
            busy = unlocking,
            error = formError,
            onRecover = controller::recover,
            onBack = { controller.goTo(AppRoute.AppUnlock) },
        )
        AppRoute.Vault -> VaultRoute(controller, vaultState)
    }
}

@Composable
private fun PublicShell(controller: ChurController, route: AppRoute) {
    val notes by controller.notesState.collectAsState()
    val disclosureDue by controller.disclosureDue.collectAsState()
    var query by remember { mutableStateOf("") }
    // Saved state, shared with the Android host. This host does not persist
    // the composition's saved state, so on iOS it lasts as long as the view
    // controller; the editor saves its own draft whenever it leaves.
    var editing by rememberOpenNote()

    editing?.let { open ->
        NoteEditorScreen(
            open = open,
            onSave = controller::putNote,
            onRemove = controller::removeNote,
            onBack = { editing = null },
        )
        return
    }

    NotesScreen(
        notes = notes,
        query = query,
        onQueryChange = { query = it },
        onOpen = { editing = OpenNote(it, isNew = false) },
        onCreate = {
            val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
            editing = OpenNote(Note(id = "note-$now", title = "", body = "", updatedMs = now), isNew = true)
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
    val tags by controller.tags.collectAsState()
    val slots by controller.slots.collectAsState()
    val deviceSlotStrict by controller.deviceSlotStrict.collectAsState()
    val appLockEnabled by controller.appLockEnabled.collectAsState()
    val notice by controller.notice.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    val syncStatus by controller.syncStatus.collectAsState()
    val sharingIdentity by controller.sharingIdentity.collectAsState()
    val sharingOverview by controller.sharingOverview.collectAsState()
    val sharingRecipient by controller.sharingRecipient.collectAsState()
    var destination by remember { mutableStateOf(VaultDestination.LIBRARY) }
    var mediaView by remember { mutableStateOf(ContentView.GRID) }
    var albumView by remember { mutableStateOf(ContentView.LIST) }
    var mediaSort by remember { mutableStateOf(QuerySort.CAPTURE_DESC) }
    var albumSort by remember { mutableStateOf(QuerySort.ALBUM_MANUAL) }
    var mediaKinds by remember { mutableStateOf(0) }
    var albumOrder by remember { mutableStateOf(AlbumOrder.MANUAL) }
    var albumFilter by remember { mutableStateOf("") }
    var terms by remember { mutableStateOf("") }
    var openAlbum by remember { mutableStateOf<AlbumSummary?>(null) }
    LaunchedEffect(albums) {
        openAlbum = openAlbum?.let { current -> albums.firstOrNull { it.id == current.id } }
    }
    var openTag by remember { mutableStateOf<TagSummary?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var trashOpen by remember { mutableStateOf(false) }
    var quarantineOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tags) {
        openTag = openTag?.let { current -> tags.firstOrNull { it.id == current.id } }
    }
    var selection by remember { mutableStateOf(setOf<String>()) }
    // The item on screen in the viewer; a swipe moves it through the page.
    var viewing by remember { mutableStateOf<ObjectProjection?>(null) }
    var creatingAlbum by remember { mutableStateOf(false) }
    var choosingAlbum by remember { mutableStateOf(false) }
    var movingSelection by remember { mutableStateOf(false) }
    var organizingIds by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var choosingTag by remember { mutableStateOf(false) }
    var managingTags by remember { mutableStateOf(false) }
    var taggingIds by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingEmptyTrash by remember { mutableStateOf(false) }
    var choosingExport by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val codec = remember { IosMediaCodec() }
    val importer = remember { MediaImporter(codec) }

    LaunchedEffect(destination, openAlbum, openTag, favoritesOnly, trashOpen, quarantineOpen,
        terms, mediaSort, albumSort, mediaKinds) {
        val query = browseQuery(destination, openAlbum, openTag, favoritesOnly,
            trashOpen, terms, mediaSort, albumSort, mediaKinds, quarantine = quarantineOpen)
        when {
            query != null -> controller.load(query)
            destination == VaultDestination.SEARCH -> controller.load(
                ObjectQuery(QueryScope.SEARCH, terms = ""))
            destination == VaultDestination.ALBUMS -> controller.loadAlbums()
            destination == VaultDestination.SETTINGS -> {
                controller.loadSlots()
                controller.loadSharing()
            }
            else -> Unit
        }
    }

    // The cache is the controller's: its lock transitions clear it whether
    // or not this screen is composed, §4 of `PLAINTEXT_LIFECYCLE.md`. The
    // session generation in the key is what makes a stale entry unreachable
    // after a new session opens.
    val cache = controller.thumbnailCache
    val generation = (vaultState as? VaultState.Unlocked)?.generation ?: 0L
    LaunchedEffect(generation) {
        if (vaultState is VaultState.Unlocked) controller.loadAlbums()
    }

    // The tiles carry whatever the cache already holds, and a tile whose
    // thumbnail is missing loads it. §11.1 keeps the geometry stable while it
    // arrives, so nothing jumps.
    var thumbnails by remember { mutableStateOf(mapOf<String, ImageBitmap>()) }
    LaunchedEffect(page, generation) {
        val visible = page.objects.map { it.id }.toSet()
        thumbnails = thumbnails.filterKeys { it in visible }
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

    if (creatingAlbum) {
        NewAlbumDialog(
            onCreate = { name ->
                creatingAlbum = false
                controller.createAlbum(name)
            },
            onDismiss = { creatingAlbum = false },
        )
    }
    if (choosingAlbum) {
        AlbumPickerDialog(
            albums = albums,
            currentAlbum = openAlbum,
            move = movingSelection,
            onChoose = { album ->
                choosingAlbum = false
                controller.placeObjectsInAlbum(
                    album.albumId, organizingIds, openAlbum?.albumId, movingSelection,
                ) { selection = emptySet() }
            },
            onCreate = { name, parent ->
                choosingAlbum = false
                controller.createAlbumWithObjects(
                    name, parent?.albumId, organizingIds, openAlbum?.albumId, movingSelection,
                ) { selection = emptySet() }
            },
            onDismiss = { choosingAlbum = false },
        )
    }
    if (choosingTag) {
        TagPickerDialog(
            tags = tags,
            onAdd = { tag ->
                choosingTag = false
                controller.setTagForAll(tag.tagId, taggingIds, true) {
                    selection = emptySet()
                }
            },
            onRemove = { tag ->
                choosingTag = false
                controller.setTagForAll(tag.tagId, taggingIds, false) {
                    selection = emptySet()
                }
            },
            onCreate = { name ->
                choosingTag = false
                controller.createTagWithObjects(name, taggingIds) {
                    selection = emptySet()
                }
            },
            onDismiss = { choosingTag = false },
        )
    }
    if (managingTags) {
        TagBrowserDialog(tags = tags,
            onOpen = { tag ->
                managingTags = false
                selection = emptySet()
                openTag = tag
                favoritesOnly = false
                trashOpen = false
                quarantineOpen = false
                destination = VaultDestination.LIBRARY
            },
            onCreate = controller::createTag,
            onRename = { tag, name -> controller.renameTag(tag.tagId, name) },
            onDelete = { tag -> controller.deleteTag(tag.tagId) {
                if (openTag?.id == tag.id) openTag = null
            } },
            onDismiss = { managingTags = false },
        )
    }
    if (confirmingDelete) {
        DeleteSelectionDialog(
            count = selection.size,
            permanent = trashOpen,
            onDelete = {
                confirmingDelete = false
                val ids = selectedObjects(page, selection)
                if (trashOpen) controller.permanentlyDeleteAll(ids) { selection = emptySet() }
                else controller.deleteAll(ids) { selection = emptySet() }
            },
            onDismiss = { confirmingDelete = false },
        )
    }
    if (confirmingEmptyTrash) {
        DeleteSelectionDialog(count = 0, permanent = true, emptyTrash = true,
            onDelete = { confirmingEmptyTrash = false; controller.emptyTrash { selection = emptySet() } },
            onDismiss = { confirmingEmptyTrash = false })
    }
    if (choosingExport) {
        val selected = page.objects.filter { it.id in selection }
        ExportOptionsDialog(
            media = selected.isNotEmpty() && selected.all { canSaveToPhotos(it.mediaKind) },
            files = true,
            share = true,
            onChoose = { target ->
                choosingExport = false
                controller.exportAll(selectedObjects(page, selection), target) { selection = emptySet() }
            },
            onDismiss = { choosingExport = false },
        )
    }

    // The viewer below is drawn over the shell, so the shell stays composed
    // and keeps its scroll position. Covered, it is out of a screen reader's
    // reach, §4 of `PLAINTEXT_LIFECYCLE.md`, and out of keyboard focus, §23.3
    // of `DESIGN.md`: opening the viewer takes the focus from it, and a
    // hardware keyboard cannot move the focus back in.
    Box(
        if (viewing != null) {
            Modifier
                .clearAndSetSemantics {}
                .focusProperties { onEnter = { cancelFocusChange() } }
                .focusGroup()
        } else {
            Modifier
        },
    ) {
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
                libraryScopeTitle = if (trashOpen) "Trash" else if (quarantineOpen) "Quarantine" else openTag?.name ?: if (favoritesOnly) "Favorites" else null,
                trashOpen = trashOpen,
                mediaView = mediaView,
                albumView = albumView,
                sort = if (openAlbum != null) albumSort else mediaSort,
                kinds = mediaKinds,
                albumOrder = albumOrder,
                albumFilter = albumFilter,
                // The viewer is drawn over the shell here, so the shell leaves
                // the notice to it while it is open rather than show it twice.
                notice = notice.takeIf { viewing == null },
                operation = operation,
                selectedCount = selection.size,
                canLoadMore = page.nextCursor != null,
                deviceSlotAvailable = controller.deviceUnlockAvailable,
                deviceSlotStrict = deviceSlotStrict,
                appLockEnabled = appLockEnabled,
                sync = syncStatus,
                sharingIdentity = sharingIdentity,
                sharingOverview = sharingOverview,
                sharingRecipient = sharingRecipient,
            ),
            actions = VaultActions(
                onDestination = {
                    openAlbum = null
                    openTag = null
                    favoritesOnly = false
                    trashOpen = false
                    quarantineOpen = false
                    selection = emptySet()
                    destination = it
                },
                // Opening is opening. Before the viewer existed on this host it
                // toggled selection, which made a video unreachable and a tap on a
                // photograph mean two things.
                onOpen = { projection ->
                    if (selection.isEmpty()) {
                        controller.report(null)
                        // A text field of the shell left focused would keep
                        // the keyboard open over the viewer, which is edge to
                        // edge (`ChurApp`), and take what is typed under it.
                        focusManager.clearFocus()
                        viewing = projection
                    } else {
                        selection = selection.toggle(projection.id)
                    }
                },
                onToggleSelection = { projection ->
                    selection = selection.toggle(projection.id)
                },
                onImport = {
                    val present = IosMediaPicker.present
                    if (present == null) {
                        controller.report("This build cannot open the photo picker.")
                    } else {
                        val albumId = openAlbum?.albumId
                        controller.beginHostActivity()
                        present { path ->
                            controller.endHostActivity()
                            if (path != null) {
                                scope.launch {
                                    try {
                                        // The picker answers with one item, so
                                        // this is a batch of one.
                                        controller.importAll(importer, 1, albumId) {
                                            codec.open(NSURL.fileURLWithPath(path))
                                        }
                                    } finally {
                                        if (path.startsWith(NSTemporaryDirectory())) unlink(path)
                                    }
                                }
                            }
                        }
                    }
                },
                onSearch = {
                    terms = it
                },
                onOpenAlbum = { openAlbum = it; openTag = null; favoritesOnly = false; trashOpen = false; quarantineOpen = false },
                onCloseAlbum = { openAlbum = null },
                onCreateAlbum = { creatingAlbum = true },
                onMediaViewChange = { mediaView = it },
                onAlbumViewChange = { albumView = it },
                onSortChange = {
                    if (openAlbum != null) albumSort = it else mediaSort = it
                    selection = emptySet()
                },
                onKindsChange = { mediaKinds = it; selection = emptySet() },
                onAlbumOrderChange = { albumOrder = it },
                onAlbumFilterChange = { albumFilter = it },
                onShowAllMedia = { openTag = null; favoritesOnly = false; trashOpen = false; quarantineOpen = false; selection = emptySet() },
                onShowFavorites = { openTag = null; favoritesOnly = true; trashOpen = false; quarantineOpen = false; selection = emptySet() },
                onShowTags = { controller.loadTags(); managingTags = true },
                onShowTrash = { openTag = null; favoritesOnly = false; trashOpen = true; quarantineOpen = false; selection = emptySet() },
                onShowQuarantine = { openTag = null; favoritesOnly = false; trashOpen = false; quarantineOpen = true; selection = emptySet() },
                onEmptyTrash = { confirmingEmptyTrash = true },
                onRestoreTrash = { controller.restoreTrash { selection = emptySet() } },
                onRenameAlbum = { album, name -> controller.renameAlbum(album.albumId, name) },
                onDeleteAlbum = { album ->
                    controller.deleteAlbum(album.albumId) {
                        if (openAlbum?.id == album.id) openAlbum = null
                    }
                },
                onMoveAlbum = { album, parent, before ->
                    controller.moveAlbum(album.albumId, parent?.albumId, before?.albumId)
                },
                onMoveAlbumMember = { objectToMove, before ->
                    openAlbum?.let { album ->
                        controller.moveAlbumMember(album.albumId, objectToMove.objectId, before?.objectId)
                    }
                },
                onDropMediaIntoAlbum = { objects, target ->
                    if (target != null) {
                        controller.placeObjectsInAlbum(target.albumId, objects.map { it.objectId },
                            openAlbum?.albumId, openAlbum != null) { selection = emptySet() }
                    } else {
                        controller.loadAlbums()
                        organizingIds = objects.map { it.objectId }
                        movingSelection = openAlbum != null
                        choosingAlbum = true
                    }
                },
                onLoadMore = controller::loadNextPage,
                onLock = { controller.lock() },
                onPanic = { controller.panic() },
                onVerifyAll = { controller.verifyEverything() },
                onAddRecoverySlot = controller::addRecoverySlot,
                onChangePassword = controller::changePassword,
                onToggleAppLock = controller::toggleAppLock,
                onAddDeviceSlot = controller::enrollAppleDeviceSlot,
                onToggleDeviceSlotPolicy = controller::toggleDeviceSlotPolicy,
                onCreateBackup = controller::createBackup,
                onCreateSecondIdentity = controller::createSecondIdentity,
                onSelectAll = { selection = page.objects.map { it.id }.toSet() },
                onClearSelection = { selection = emptySet() },
                onExportSelection = { choosingExport = true },
                onAddSelectionToAlbum = {
                    controller.loadAlbums()
                    organizingIds = selection.sorted().map { it.fromHex() }
                    movingSelection = false
                    choosingAlbum = true
                },
                onMoveSelectionToAlbum = {
                    controller.loadAlbums()
                    organizingIds = selection.sorted().map { it.fromHex() }
                    movingSelection = true
                    choosingAlbum = true
                },
                onTagSelection = {
                    controller.loadTags()
                    taggingIds = selection.sorted().map { it.fromHex() }
                    choosingTag = true
                },
                onSetSelectionFavorite = { favorite ->
                    controller.setFavoritesForAll(selection.sorted().map { it.fromHex() }, favorite) {
                        selection = emptySet()
                    }
                },
                onRemoveSelectionFromAlbum = {
                    openAlbum?.let { album ->
                        controller.removeAllFromAlbum(album.albumId, selectedObjects(page, selection)) {
                            selection = emptySet()
                        }
                    }
                },
                onDeleteSelection = { confirmingDelete = true },
                onRestoreSelection = { controller.restoreAll(selectedObjects(page, selection)) {
                    selection = emptySet()
                } },
                onCancelOperation = controller::cancelActiveOperation,
                onNoticeShown = controller::consume,
                onConfigureSync = controller::configureSync,
                onSyncNow = controller::syncNow,
                onDisconnectSync = controller::disconnectSync,
                onInspectSharingRecipient = controller::inspectSharingRecipient,
                onShareWithRecipient = controller::shareWithRecipient,
                onRevokeSharingMember = controller::revokeSharingMember,
            ),
            // iOS has no system Back to deliver, `DESIGN.md` §25.4.
            systemBack = { _, _ -> },
        )
    }

    viewing?.let { projection ->
        IosViewerRoute(
            controller = controller,
            operation = operation,
            notice = notice,
            cache = cache,
            generation = generation,
            projection = projection,
            pages = viewerPages(page.objects, projection),
            thumbnails = thumbnails,
            canLoadMore = page.nextCursor != null,
            onSettled = { viewing = it },
            trashOpen = trashOpen,
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
 *
 * A swipe moves through [pages], `DESIGN.md` §13.1, and this route follows
 * it: [projection] is the item on screen, and its detail, its still, its
 * player and its dialogs are keyed to it, so they are made once, for that
 * item, and not for each page a swipe passes.
 */
@Composable
private fun IosViewerRoute(
    controller: ChurController,
    operation: ActiveOperation?,
    notice: Notice?,
    cache: ThumbnailCache,
    generation: Long,
    projection: ObjectProjection,
    pages: List<ObjectProjection>,
    thumbnails: Map<String, ImageBitmap>,
    canLoadMore: Boolean,
    onSettled: (ObjectProjection) -> Unit,
    trashOpen: Boolean,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    var detail by remember(projection.id) { mutableStateOf<ObjectDetail?>(null) }
    var favorite by remember(projection.id) { mutableStateOf(projection.favorite) }
    val tags by controller.tags.collectAsState()
    var choosingTags by remember(projection.id) { mutableStateOf(false) }
    val viewerScope = rememberCoroutineScope()
    var preview by remember(projection.id, generation) { mutableStateOf<ImageBitmap?>(null) }
    var showDetail by remember(projection.id) { mutableStateOf(false) }
    var waveform by remember(projection.id) { mutableStateOf<ByteArray?>(null) }
    var confirmingDelete by remember(projection.id) { mutableStateOf(false) }
    var choosingExport by remember(projection.id) { mutableStateOf(false) }
    val chrome = remember { ViewerChrome() }
    val chromePinned = showDetail || choosingTags || choosingExport || confirmingDelete || operation != null
    val chromeVisible = chrome.visible(chromePinned)
    // §6.2 and §13.1: the status bar is light over the black canvas and goes
    // with the chrome, and it gets the theme's style back when the viewer goes.
    val root = LocalUIViewController.current.parentViewController as? ChurRootViewController
    DisposableEffect(root, chromeVisible) {
        root?.viewerChrome = chromeVisible
        onDispose { root?.viewerChrome = null }
    }

    LaunchedEffect(projection.id, generation) {
        // `IOS.md` §18: a preview before full-resolution ranges. The cached
        // thumbnail shows at once, and the sharper still comes last so the
        // detail does not wait for an original to decode.
        preview = cache.load(controller.vault, generation, projection.objectId, projection.id)
        if (projection.mediaKind == MEDIA_CLASS_AUDIO) {
            waveform = controller.derivativeOf(projection.objectId, StreamKind.AUDIO_WAVEFORM)
        }
        detail = controller.detailOf(projection.objectId)
        viewerStill(controller.vault, cache, generation, projection)?.let { preview = it }
    }

    // DESIGN.md §20.3: a quarantined object is never silently retried in a
    // viewer, and CATALOG_SCHEMA_V1.md §5.1 says its container is absent or
    // unreadable. A player would retry the failed lease with no sign to the
    // user, so the viewer opens it without one.
    val playback = if (PresentedState.of(projection) == PresentedState.QUARANTINED) {
        null
    } else {
        playbackFor(
            vault = controller.vault,
            objectId = projection.objectId,
            mediaKind = projection.mediaKind,
            detail = detail,
        )
    }

    ViewerScreen(
        projection = projection.copy(favorite = favorite),
        pages = pages,
        thumbnails = thumbnails,
        canLoadMore = canLoadMore,
        onLoadMore = controller::loadNextPage,
        onSettled = onSettled,
        detail = detail,
        preview = preview,
        showDetail = showDetail,
        chromeVisible = chromeVisible,
        onToggleChrome = { chrome.toggle(chromePinned) },
        onTouch = chrome::touched,
        onBack = onBack,
        onLock = { controller.lock() },
        onPanic = { controller.panic() },
        onToggleFavorite = {
            val next = !favorite
            controller.setFavorite(projection.objectId, next) { favorite = next }
        },
        onEditTags = { controller.loadTags(); choosingTags = true },
        onExport = { choosingExport = true },
        onDelete = { confirmingDelete = true },
        trashOpen = trashOpen,
        onRestore = { controller.restoreAll(listOf(projection.objectId), onDeleted) },
        onToggleDetail = { showDetail = !showDetail },
        player = playback?.let { source ->
            { modifier, controlsPadding ->
                // `DESIGN.md` §13.1 and §13.2: the video opens on its poster,
                // and its controls show and hide with the chrome.
                VaultPlayer(
                    source = source,
                    modifier = modifier,
                    poster = preview,
                    controlsVisible = chromeVisible,
                    controlsPinned = chromePinned,
                    onControlsVisibleChange = { chrome.follow(it, chromePinned) },
                    controlsPadding = controlsPadding,
                    touches = chrome.touches,
                )
            }
        },
        waveform = waveform,
        operation = operation,
        onCancelOperation = controller::cancelActiveOperation,
        notice = notice,
        onNoticeShown = controller::consume,
    )
    if (choosingTags) {
        val selected = listOf(projection.objectId)
        val refresh: () -> Unit = { viewerScope.launch { detail = controller.detailOf(projection.objectId) } }
        TagPickerDialog(tags = tags,
            onAdd = { tag -> choosingTags = false; controller.setTagForAll(tag.tagId, selected, true, refresh) },
            onRemove = { tag -> choosingTags = false; controller.setTagForAll(tag.tagId, selected, false, refresh) },
            onCreate = { name -> choosingTags = false; controller.createTagWithObjects(name, selected, refresh) },
            onDismiss = { choosingTags = false })
    }
    if (confirmingDelete) {
        DeleteSelectionDialog(
            count = 1,
            permanent = trashOpen,
            onDelete = {
                confirmingDelete = false
                if (trashOpen) controller.permanentlyDeleteAll(listOf(projection.objectId), onDeleted)
                else controller.delete(projection.objectId, onDeleted)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
    if (choosingExport) {
        ExportOptionsDialog(
            media = canSaveToPhotos(projection.mediaKind),
            files = true,
            share = true,
            onChoose = { target ->
                choosingExport = false
                controller.export(projection.objectId, target)
            },
            onDismiss = { choosingExport = false },
        )
    }
}

/** The object identifiers the selection names, in the page's order. */
private fun selectedObjects(
    page: ObjectPage,
    selection: Set<String>,
): List<ByteArray> = page.objects.filter { it.id in selection }.map { it.objectId }

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

/** The Xcode host presents PHPicker and returns a readable copy in the app's temp directory. */
public object IosMediaPicker {
    public var present: ((answer: (String?) -> Unit) -> Unit)? = null
}

/**
 * The restore route, `docs/format/BACKUP_FORMAT_V1.md` §8.
 *
 * The document picker belongs to the Xcode project for the reason the photo
 * picker does: a Compose composable cannot present a UIKit view controller
 * without the host's window. [IosBackupPicker] is that seam, and
 * `apps/iosApp/README.md` step 7 is its specification.
 *
 * The password does not leave Kotlin. The host is asked for a path and answers
 * with one; the descriptor is opened here and closed here.
 */
@Composable
private fun IosRestoreRoute(controller: ChurController) {
    val formError by controller.formError.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    var running by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }

    RestoreBackupScreen(
        busy = running,
        error = formError,
        cancelled = cancelled,
        operation = operation,
        onChoose = { password ->
            // The flag and the message it styles clear together, as on the
            // other host: a dismissed picker reports nothing.
            cancelled = false
            controller.report(null)
            val present = IosBackupPicker.present
            if (present == null) {
                controller.report("This build cannot open the file picker.")
            } else {
                running = true
                // A file provider can take the foreground while its picker is
                // up, and the background transition would close the route this
                // result returns to, as it does on the other host.
                controller.beginHostActivity()
                present { path ->
                    controller.endHostActivity()
                    val descriptor = path?.let { open(it, O_RDONLY) } ?: -1
                    if (descriptor < 0) {
                        if (path != null) controller.report("That file could not be opened.")
                        path?.let { unlink(it) }
                        running = false
                    } else {
                        controller.restoreBackup(descriptor, password) { wasCancelled ->
                            close(descriptor)
                            path?.let { unlink(it) }
                            running = false
                            cancelled = wasCancelled
                        }
                    }
                }
            }
        },
        onBack = { controller.goTo(AppRoute.PublicShell) },
        onCancel = controller::cancelActiveOperation,
    )
}

/**
 * The document picker the Xcode project presents, `BACKUP_FORMAT_V1.md` §8.
 *
 * It is a hook rather than a call for the reason the photo picker is one: the
 * presentation needs the host's window, which no composable has. The host
 * installs [present] once at launch, the way it registers the background task
 * of [IosSyncBackground], and answers with the path of a file this process can
 * open and seek. `apps/iosApp/README.md` step 7 says which file that is.
 */
public object IosBackupPicker {
    /** Presents the picker, and answers with a path or `null` on a dismissal. */
    public var present: ((answer: (String?) -> Unit) -> Unit)? = null
}
