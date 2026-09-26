@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.MEDIA_CLASS_AUDIO
import dev.po4yka.chur.app.vault.MEDIA_CLASS_VIDEO
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
            onRestore = if (vaultState is VaultState.NoVault) {
                { controller.goTo(AppRoute.RestoreBackup) }
            } else {
                null
            },
        )
        AppRoute.RestoreBackup -> IosRestoreRoute(controller)
        AppRoute.Unlock -> UnlockScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || message != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.Recover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithAppleDevice,
        )
        AppRoute.AppUnlock -> UnlockScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || message != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.AppRecover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithAppleDevice,
            appGate = true,
        )
        AppRoute.Recover -> RecoveryScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null,
            onRecover = controller::recover,
            onBack = { controller.goTo(AppRoute.Unlock) },
        )
        AppRoute.AppRecover -> RecoveryScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || message != null,
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
    val tags by controller.tags.collectAsState()
    val slots by controller.slots.collectAsState()
    val deviceSlotStrict by controller.deviceSlotStrict.collectAsState()
    val appLockEnabled by controller.appLockEnabled.collectAsState()
    val message by controller.message.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    val syncStatus by controller.syncStatus.collectAsState()
    val sharingIdentity by controller.sharingIdentity.collectAsState()
    val sharingOverview by controller.sharingOverview.collectAsState()
    val sharingRecipient by controller.sharingRecipient.collectAsState()
    var destination by remember { mutableStateOf(VaultDestination.LIBRARY) }
    var terms by remember { mutableStateOf("") }
    var openAlbum by remember { mutableStateOf<AlbumSummary?>(null) }
    LaunchedEffect(albums) {
        openAlbum = openAlbum?.let { current -> albums.firstOrNull { it.id == current.id } }
    }
    var openTag by remember { mutableStateOf<TagSummary?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    LaunchedEffect(tags) {
        openTag = openTag?.let { current -> tags.firstOrNull { it.id == current.id } }
    }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<ObjectProjection?>(null) }
    var creatingAlbum by remember { mutableStateOf(false) }
    var choosingAlbum by remember { mutableStateOf(false) }
    var movingSelection by remember { mutableStateOf(false) }
    var organizingIds by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var choosingTag by remember { mutableStateOf(false) }
    var managingTags by remember { mutableStateOf(false) }
    var taggingIds by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var choosingExport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val codec = remember { IosMediaCodec() }
    val importer = remember { MediaImporter(codec) }

    LaunchedEffect(destination, openAlbum, openTag, favoritesOnly) {
        when {
            openAlbum != null -> controller.load(
                ObjectQuery(QueryScope.ALBUM, sort = QuerySort.ALBUM_MANUAL, scopeId = openAlbum!!.albumId),
            )
            destination == VaultDestination.LIBRARY && openTag != null ->
                controller.load(ObjectQuery(QueryScope.TAG, scopeId = openTag!!.tagId))
            destination == VaultDestination.LIBRARY && favoritesOnly ->
                controller.load(ObjectQuery(QueryScope.FAVORITES))
            destination == VaultDestination.LIBRARY -> controller.load(ObjectQuery())
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
            onDelete = {
                confirmingDelete = false
                controller.deleteAll(selectedObjects(page, selection)) { selection = emptySet() }
            },
            onDismiss = { confirmingDelete = false },
        )
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
            libraryScopeTitle = openTag?.name ?: if (favoritesOnly) "Favorites" else null,
            widthDp = 400,
            progress = message,
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
                selection = emptySet()
                destination = it
            },
            // Opening is opening. Before the viewer existed on this host it
            // toggled selection, which made a video unreachable and a tap on a
            // photograph mean two things.
            onOpen = { projection ->
                if (selection.isEmpty()) {
                    controller.report(null)
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
                                    val outcome = controller.importMedia(importer) {
                                        codec.open(NSURL.fileURLWithPath(path))
                                    }
                                    if (controller.vaultState.value !is VaultState.Unlocked) return@launch
                                    when (outcome) {
                                        is MediaImporter.Outcome.Imported -> {
                                            controller.reportImport(when {
                                                outcome.previewsSkipped -> "Imported original; remaining previews cancelled."
                                                albumId == null -> "Imported into vault."
                                                else -> null
                                            })
                                            if (albumId != null) {
                                                controller.placeObjectsInAlbum(albumId, listOf(outcome.objectId)) {
                                                    controller.report(if (outcome.previewsSkipped) {
                                                        "Imported original into album; remaining previews cancelled."
                                                    } else "Imported into album.")
                                                }
                                            }
                                        }
                                        is MediaImporter.Outcome.TooLarge -> controller.reportImport(outcome.reason)
                                        MediaImporter.Outcome.Unreadable -> controller.reportImport("That file could not be opened.")
                                        is MediaImporter.Outcome.Refused -> controller.reportImport(
                                            if (outcome.status == "CANCELLED") "Cancelled." else outcome.status,
                                        )
                                        null -> Unit
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
                controller.search(it)
            },
            onOpenAlbum = { openAlbum = it; openTag = null; favoritesOnly = false },
            onCloseAlbum = { openAlbum = null },
            onCreateAlbum = { creatingAlbum = true },
            onShowAllMedia = { openTag = null; favoritesOnly = false; selection = emptySet() },
            onShowFavorites = { openTag = null; favoritesOnly = true; selection = emptySet() },
            onShowTags = { controller.loadTags(); managingTags = true },
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
            onCancelOperation = controller::cancelActiveOperation,
            onConfigureSync = controller::configureSync,
            onSyncNow = controller::syncNow,
            onDisconnectSync = controller::disconnectSync,
            onInspectSharingRecipient = controller::inspectSharingRecipient,
            onShareWithRecipient = controller::shareWithRecipient,
            onRevokeSharingMember = controller::revokeSharingMember,
        ),
    )

    viewing?.let { projection ->
        IosViewerRoute(
            controller = controller,
            operation = operation,
            status = message,
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
    operation: ActiveOperation?,
    status: String?,
    cache: ThumbnailCache,
    generation: Long,
    projection: ObjectProjection,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    var detail by remember(projection.id) { mutableStateOf<ObjectDetail?>(null) }
    var favorite by remember(projection.id) { mutableStateOf(projection.favorite) }
    val tags by controller.tags.collectAsState()
    var choosingTags by remember(projection.id) { mutableStateOf(false) }
    val viewerScope = rememberCoroutineScope()
    var preview by remember(projection.id) { mutableStateOf<ImageBitmap?>(null) }
    var showDetail by remember(projection.id) { mutableStateOf(false) }
    var waveform by remember(projection.id) { mutableStateOf<ByteArray?>(null) }
    var confirmingDelete by remember(projection.id) { mutableStateOf(false) }
    var choosingExport by remember(projection.id) { mutableStateOf(false) }

    LaunchedEffect(projection.id, generation) {
        // A video's still is its poster frame, which `MEDIA_PIPELINE.md` §6
        // generates for every video; a photograph's is its screen preview. Both
        // fall back to the thumbnail, which every object has.
        val first = if (projection.mediaKind == MEDIA_CLASS_VIDEO) {
            StreamKind.VIDEO_POSTER
        } else {
            StreamKind.SCREEN_PREVIEW
        }
        preview = cache.load(
            repository = controller.vault,
            generation = generation,
            objectId = projection.objectId,
            id = projection.id,
            kind = first,
        ) ?: cache.load(
            repository = controller.vault,
            generation = generation,
            objectId = projection.objectId,
            id = projection.id,
            kind = StreamKind.THUMBNAIL,
        )
        if (projection.mediaKind == MEDIA_CLASS_AUDIO) {
            waveform = controller.derivativeOf(projection.objectId, StreamKind.AUDIO_WAVEFORM)
        }
        detail = controller.detailOf(projection.objectId)
    }

    val playback = playbackFor(
        vault = controller.vault,
        objectId = projection.objectId,
        mediaKind = projection.mediaKind,
        detail = detail,
    )

    ViewerScreen(
        projection = projection.copy(favorite = favorite),
        detail = detail,
        preview = preview,
        showDetail = showDetail,
        onBack = onBack,
        onToggleFavorite = {
            val next = !favorite
            controller.setFavorite(projection.objectId, next) { favorite = next }
        },
        onEditTags = { controller.loadTags(); choosingTags = true },
        onExport = { choosingExport = true },
        onDelete = { confirmingDelete = true },
        onToggleDetail = { showDetail = !showDetail },
        player = playback?.let { source ->
            { modifier -> VaultPlayer(source, modifier) }
        },
        waveform = waveform,
        operation = operation,
        onCancelOperation = controller::cancelActiveOperation,
        status = status,
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
            onDelete = {
                confirmingDelete = false
                controller.delete(projection.objectId, onDeleted)
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
    val message by controller.message.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    var running by remember { mutableStateOf(false) }

    RestoreBackupScreen(
        busy = running,
        error = message,
        operation = operation,
        onChoose = { password ->
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
                        controller.restoreBackup(descriptor, password) {
                            close(descriptor)
                            path?.let { unlink(it) }
                            running = false
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
