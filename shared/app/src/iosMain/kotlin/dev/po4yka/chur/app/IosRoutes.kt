@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import dev.po4yka.chur.app.vault.LocalIosVaultChrome
import dev.po4yka.chur.app.vault.LocalIosVaultScenes
import dev.po4yka.chur.app.vault.LocalIosVaultScene
import dev.po4yka.chur.app.vault.IosVaultScreen
import dev.po4yka.chur.app.vault.IosViewerPanelState
import dev.po4yka.chur.app.vault.IosViewerPanelActions
import dev.po4yka.chur.app.vault.forScene
import dev.po4yka.chur.app.theme.LocalChurColors
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import dev.po4yka.chur.app.notes.NoteEditorScreen
import dev.po4yka.chur.app.notes.NotesScreen
import dev.po4yka.chur.app.notes.OpenNote
import dev.po4yka.chur.app.notes.PublicSettingsScreen
import dev.po4yka.chur.app.PublicShell as PublicShellKind
import dev.po4yka.chur.app.publicshell.CalculatorScreen
import androidx.compose.runtime.key
import dev.po4yka.chur.app.notes.rememberOpenNote
import dev.po4yka.chur.app.vault.CreateVaultScreen
import dev.po4yka.chur.app.vault.loadedObjectSelection
import dev.po4yka.chur.app.vault.LibraryTile
import dev.po4yka.chur.app.vault.AlbumExportDialog
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
import dev.po4yka.chur.app.vault.SourceDeletionCopy
import dev.po4yka.chur.app.vault.SourceDeletionDialog
import dev.po4yka.chur.app.vault.importedOriginals
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.ThumbnailRendition
import dev.po4yka.chur.app.vault.rememberGalleryThumbnails
import dev.po4yka.chur.app.vault.rememberViewerThumbnails
import dev.po4yka.chur.app.vault.thumbnailRendition
import dev.po4yka.chur.app.vault.UnlockScreen
import dev.po4yka.chur.app.vault.LocalPrivatePanic
import dev.po4yka.chur.app.vault.PrivateAlertDialog
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.ContentView
import dev.po4yka.chur.app.vault.AlbumOrder
import dev.po4yka.chur.app.vault.browseQuery
import dev.po4yka.chur.app.vault.SelectiveExportDialog
import dev.po4yka.chur.app.vault.SelectiveImportDialog
import dev.po4yka.chur.app.vault.PrivateNotesRoute
import dev.po4yka.chur.app.vault.albumParent
import dev.po4yka.chur.app.vault.albumPath
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.MEDIA_CLASS_AUDIO
import dev.po4yka.chur.app.vault.MEDIA_CLASS_IMAGE
import dev.po4yka.chur.app.vault.MEDIA_CLASS_VIDEO
import dev.po4yka.chur.app.vault.viewerPages
import dev.po4yka.chur.app.vault.ViewerStillState
import dev.po4yka.chur.app.vault.viewerStill
import dev.po4yka.chur.app.vault.rememberViewerAnimation
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.app.vault.VaultDocument
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.endEditing
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.navigationItem
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
    val panic: (() -> Unit)? = if (route == AppRoute.Vault || vaultState is VaultState.Unlocked || phrase != null) {
        { controller.panic() }
    } else null
    CompositionLocalProvider(LocalPrivatePanic provides panic) {
        IosRoutesContent(controller, route, vaultState)
    }
}

@Composable
private fun IosRoutesContent(controller: ChurController, route: AppRoute, vaultState: VaultState) {
    val phrase by controller.recoveryPhrase.collectAsState()
    val formError by controller.formError.collectAsState()
    val unlocking by controller.unlocking.collectAsState()
    val maskRequest by IosLauncherActions.maskRequest.collectAsState()
    LaunchedEffect(route, maskRequest, phrase) {
        if (maskRequest > 0 && phrase == null && (route == AppRoute.PublicShell || route == AppRoute.AppUnlock)) {
            // Let the neutral composition draw before removing the UIKit cover.
            withFrameNanos { }
            withFrameNanos { }
            IosLauncherActions.onMaskRendered?.invoke()
        }
    }
    val incomingCodec = remember { IosMediaCodec() }
    val incomingImporter = remember(incomingCodec) { MediaImporter(incomingCodec) }
    DisposableEffect(controller) {
        IosIncomingShares.cancelOperation = controller::cancelActiveOperation
        onDispose { IosIncomingShares.cancelOperation = null }
    }
    IncomingSharePrompt(IosIncomingShares.inbox, controller, route, incomingImporter)

    phrase?.let { value ->
        // Copying 24 words by hand, `RECOVERY.md` §2.3, outlasts the
        // auto-lock, which backgrounds the scene and drops the phrase. The
        // repository's phrase window bounds how long the display stays awake.
        DisposableEffect(Unit) {
            UIApplication.sharedApplication.idleTimerDisabled = true
            onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
        }
        RecoveryPhraseScreen(
            phrase = value,
            onAcknowledged = controller::acknowledgeRecoveryPhrase,
            onLock = { controller.lock() },
            onPanic = { controller.panic() },
        )
        return
    }

    when (route) {
        AppRoute.PublicShell, AppRoute.PublicSettings -> PublicShellRoute(controller, route)
        AppRoute.CreateVault -> CreateVaultScreen(
            busy = vaultState is VaultState.Creating,
            error = formError,
            onCreate = controller::create,
            onHide = { controller.panic() },
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
        AppRoute.EncryptFiles -> IosExternalArchiveRoute(controller)
        AppRoute.Unlock -> UnlockScreen(
            busy = unlocking,
            error = formError,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.Recover) },
            onReturnToPublic = { controller.panic() },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithAppleDevice,
        )
        AppRoute.AppUnlock -> UnlockScreen(
            busy = unlocking,
            error = formError,
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
            onReturnToPublic = { controller.panic() },
        )
        AppRoute.AppRecover -> RecoveryScreen(
            busy = unlocking,
            error = formError,
            onRecover = controller::recover,
            onBack = { controller.panic() },
        )
        AppRoute.Vault -> key((vaultState as? VaultState.Unlocked)?.generation) {
            VaultRoute(controller, vaultState)
        }
    }
}

@Composable
private fun PublicShellRoute(controller: ChurController, route: AppRoute) {
    val shell by controller.publicShell.collectAsState()
    val error by controller.publicShellError.collectAsState()
    val disclosureDue by controller.publicDisclosureRequired.collectAsState()
    if (route == AppRoute.PublicSettings) {
        PublicSettingsScreen(
            onBack = { controller.goTo(AppRoute.PublicShell) },
            onOpenVault = controller::openVaultEntry,
            onEncryptExternalFiles = controller::openExternalArchive,
            selectedShell = shell,
            onSelectShell = controller::setPublicShell,
            error = error,
        )
        return
    }
    key(shell) {
        when (shell) {
            PublicShellKind.NOTES -> PublicNotesShell(controller) { controller.goTo(AppRoute.PublicSettings) }
            PublicShellKind.CALCULATOR -> CalculatorScreen(onOpenSettings = { controller.goTo(AppRoute.PublicSettings) })
            PublicShellKind.CAMERA -> IosPublicCameraScreen(
                onOpenSettings = { controller.goTo(AppRoute.PublicSettings) },
                showFirstWriteDisclosure = disclosureDue,
                onAcknowledgeDisclosure = controller::acknowledgeDisclosure,
                onNavigationBusyChange = controller::setLauncherNavigationBusy,
            )
        }
    }
}

@Composable
private fun PublicNotesShell(controller: ChurController, onOpenSettings: () -> Unit) {
    val focus = LocalFocusManager.current
    val notes by controller.notesState.collectAsState()
    val writeFailureId by controller.publicNoteWriteFailureId.collectAsState()
    val disclosureDue by controller.disclosureDue.collectAsState()
    var query by remember { mutableStateOf("") }
    // Saved state, shared with the Android host. This host does not persist
    // the composition's saved state, so on iOS it lasts as long as the view
    // controller; the editor saves its own draft whenever it leaves.
    var editing by rememberOpenNote()
    DisposableEffect(controller, editing != null) {
        controller.setLauncherNavigationBusy(editing != null)
        onDispose { controller.setLauncherNavigationBusy(false) }
    }

    editing?.let { open ->
        NoteEditorScreen(
            open = open,
            savedNote = notes.firstOrNull { it.id == open.note.id },
            writeFailed = writeFailureId == open.note.id,
            onSave = controller::putNote,
            onAddAttachment = controller::addPublicNoteAttachment,
            onReadAttachment = { id -> controller.readPublicNoteAttachment(open.note.id, id) },
            onRemoveAttachment = { id -> controller.removePublicNoteAttachment(open.note.id, id) },
            onExportPackage = controller::exportPublicNotePackage,
            onRemove = controller::removeNote,
            onBack = { editing = null },
            onOpenSettings = {
                focus.clearFocus(force = true)
                onOpenSettings()
            },
        )
        return
    }

    NotesScreen(
        notes = notes,
        onImport = { title, body ->
            val imported = Note(id = "note-${(NSDate().timeIntervalSince1970 * 1_000_000).toLong()}", title = title, body = body, updatedMs = (NSDate().timeIntervalSince1970 * 1000).toLong())
            controller.putNote(imported)
            editing = OpenNote(imported, isNew = false)
        },
        onImportPackage = { bytes ->
            val imported = controller.importPublicNotePackage(bytes, "note-${(NSDate().timeIntervalSince1970 * 1_000_000).toLong()}")
            editing = OpenNote(imported, isNew = false)
        },
        query = query,
        onQueryChange = { query = it },
        onOpen = { editing = OpenNote(it, isNew = false) },
        onCreate = {
            val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
            editing = OpenNote(Note(id = "note-$now", title = "", body = "", updatedMs = now), isNew = true)
        },
        onOpenSettings = { onOpenSettings() },
        showFirstWriteDisclosure = disclosureDue,
        onAcknowledgeDisclosure = controller::acknowledgeDisclosure,
    )
}

/** Each source keeps its scoped provider lease until native encryption returns. */
@Composable
private fun IosExternalArchiveRoute(controller: ChurController) {
    val codec = remember { IosMediaCodec() }
    var alive by remember(controller) { mutableStateOf(true) }
    var pending by remember(controller) { mutableStateOf<((ExternalArchiveSelection?) -> Unit)?>(null) }
    DisposableEffect(controller) {
        onDispose {
            alive = false
            val pickerWasOpen = pending != null
            pending = null
            // Once handed over, only the operation owner may close source grants.
            if (pickerWasOpen) {
                IosDocumentPicker.cancel?.invoke()
                IosAudioPicker.cancel?.invoke()
                IosLivePhotoFilesPicker.cancel?.invoke()
            }
        }
    }
    ExternalArchiveScreen(
        controller = controller,
        selectFiles = { answer ->
            val present = IosDocumentPicker.present
            if (present == null) {
                controller.report("This build cannot open the document picker.")
                answer(null)
            } else {
                pending = answer
                try {
                    present { count, load, releaseSelection ->
                        val current = pending
                        pending = null
                        if (!alive || current == null || count <= 0 || count > 32) {
                            releaseSelection()
                            if (alive && current != null) {
                                if (count > 32) controller.report("Select at most 32 files for one encrypted archive.")
                                current(null)
                            }
                        } else {
                            current(ExternalArchiveSelection(
                                count = count,
                                open = { index, prepare ->
                                    withContext(Dispatchers.Main) {
                                        suspendCancellableCoroutine { continuation ->
                                            load(index, prepare) { fd, name, type, size, release ->
                                                val picked = codec.openDocument(fd, name, type, size, release)
                                                continuation.resume(picked) { _, item, _ -> item?.close?.invoke() }
                                            }
                                        }
                                    }
                                },
                                close = releaseSelection,
                            ))
                        }
                    }
                } catch (_: Exception) {
                    pending = null
                    controller.report("The file picker could not be opened.")
                    answer(null)
                }
            }
        },
    )
}

@Composable
private fun VaultRoute(controller: ChurController, vaultState: VaultState) {
    val contentIndex by controller.contentIndex.collectAsState()
    val page by controller.page.collectAsState()
    val albums by controller.albums.collectAsState()
    val tags by controller.tags.collectAsState()
    val slots by controller.slots.collectAsState()
    val deviceSlotStrict by controller.deviceSlotStrict.collectAsState()
    val appLockEnabled by controller.appLockEnabled.collectAsState()
    val autoLock by controller.autoLock.collectAsState()
    val notice by controller.notice.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    val syncStatus by controller.syncStatus.collectAsState()
    val sharingIdentity by controller.sharingIdentity.collectAsState()
    val sharingOverview by controller.sharingOverview.collectAsState()
    val sharingRecipient by controller.sharingRecipient.collectAsState()
    val focusManager = LocalFocusManager.current
    val vaultDevicePairing by controller.devicePairing.collectAsState()
    val pairingFiles = rememberPairingFiles(controller)
    val generation = (vaultState as? VaultState.Unlocked)?.generation ?: 0L
    val archiveTransfers = remember(controller, generation) { ArchiveTransferOwner(controller, generation) }
    DisposableEffect(archiveTransfers) { onDispose { archiveTransfers.close() } }
    var choosingArchive by remember(generation) { mutableStateOf<SelectiveExportRequest?>(null) }
    var choosingArchiveImport by remember(generation) { mutableStateOf(false) }
    var showingPrivateNotes by remember(generation) { mutableStateOf(false) }
    var privateNotesBusy by remember(generation) { mutableStateOf(false) }
    var initialCreateRequest by remember(generation) { mutableStateOf<Long?>(null) }
    var searchFocusRequest by remember(generation) { mutableStateOf<Long?>(null) }
    val nativeChrome = checkNotNull(LocalIosVaultChrome.current)
    val scenes = checkNotNull(LocalIosVaultScenes.current)
    val colors = LocalChurColors.current
    DisposableEffect(controller, nativeChrome, scenes, generation) {
        if (controller.route.value == AppRoute.Vault &&
            (controller.vaultState.value as? VaultState.Unlocked)?.generation == generation
        ) {
            nativeChrome.beginPresentation(generation)
            scenes.begin(generation)
        }
        onDispose { nativeChrome.clear(); scenes.clear() }
    }
    var viewerNavigationBusy by remember(generation) { mutableStateOf(false) }
    var viewerVisit by remember(generation) { mutableStateOf(0L) }
    var shellNavigationBusy by remember(generation) { mutableStateOf(false) }
    var choosingPrivateNoteExport by remember(generation) { mutableStateOf<PrivateNoteExportRequest?>(null) }
    var thumbnailsConcealed by remember(generation) { mutableStateOf(true) }
    var destination by remember { mutableStateOf(VaultDestination.LIBRARY) }
    var mediaView by remember { mutableStateOf(ContentView.GRID) }
    var albumView by remember { mutableStateOf(ContentView.LIST) }
    var mediaSort by remember { mutableStateOf(QuerySort.CAPTURE_DESC) }
    var albumSort by remember { mutableStateOf(QuerySort.ALBUM_MANUAL) }
    var mediaKinds by remember { mutableStateOf(0) }
    var albumOrder by remember { mutableStateOf(AlbumOrder.MANUAL) }
    var albumFilter by remember { mutableStateOf("") }
    var terms by remember { mutableStateOf("") }
    var openAlbum by remember(generation) { mutableStateOf<AlbumSummary?>(null) }
    var exportingAlbum by remember(generation) { mutableStateOf<AlbumSummary?>(null) }
    var albumPickerPending by remember(generation) { mutableStateOf(false) }
    var documentPickerPending by remember(generation) { mutableStateOf(false) }
    var choosingImportSource by remember(generation) { mutableStateOf(false) }
    DisposableEffect(controller, generation) {
        onDispose {
            IosDocumentPicker.cancel?.invoke()
            IosAudioPicker.cancel?.invoke()
            IosLivePhotoFilesPicker.cancel?.invoke()
            if (documentPickerPending) {
                documentPickerPending = false
                val current = controller.vaultState.value as? VaultState.Unlocked
                if (current?.generation == generation) controller.endHostActivity()
            }
            if (albumPickerPending) {
                IosAlbumExportPicker.cancel?.invoke()
                if (albumPickerPending) {
                    albumPickerPending = false
                    val current = controller.vaultState.value as? VaultState.Unlocked
                    if (current?.generation == generation) controller.endHostActivity()
                }
            }
        }
    }

    var openTag by remember { mutableStateOf<TagSummary?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var trashOpen by remember { mutableStateOf(false) }
    var quarantineOpen by remember { mutableStateOf(false) }
    var recentlyAdded by remember(generation) { mutableStateOf(false) }
    LaunchedEffect(tags) {
        openTag = openTag?.let { current -> tags.firstOrNull { it.id == current.id } }
    }
    var selection by remember(generation) { mutableStateOf(setOf<String>()) }
    // The item on screen in the viewer; a swipe moves it through the page.
    var viewing by remember(generation) { mutableStateOf<ObjectProjection?>(null) }
    LaunchedEffect(albums) {
        val current = openAlbum
        if (current != null) {
            val resolved = albumPath(albums, current.id).lastOrNull()
            if (resolved == null) {
                selection = emptySet()
                viewing = null
                destination = VaultDestination.ALBUMS
            }
            openAlbum = resolved
        }
    }
    fun navigateAlbum(target: AlbumSummary?) {
        focusManager.clearFocus()
        searchFocusRequest = null
        viewing = null
        selection = emptySet()
        openTag = null
        favoritesOnly = false
        trashOpen = false
        quarantineOpen = false
        recentlyAdded = false
        destination = VaultDestination.ALBUMS
        openAlbum = target?.let { albumPath(albums, it.id).lastOrNull() }
    }

    var visibleMediaIds by remember(generation) { mutableStateOf(emptySet<String>()) }
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
    // The originals of the last import, until the user answers the step of
    // `DESIGN.md` §15.3. A lock disposes of the route and of them.
    var originals by remember { mutableStateOf<PickedOriginals?>(null) }
    val scope = rememberCoroutineScope()
    val codec = remember { IosMediaCodec() }
    val importer = remember { MediaImporter(codec) }
    val launcherRequest by controller.launcherRequest.collectAsState()
    val launcherSceneReady by IosLauncherActions.sceneReady.collectAsState()
    val incomingShare by IosIncomingShares.inbox.state.collectAsState()
    val modalNavigationBusy = choosingArchive != null || choosingArchiveImport || choosingPrivateNoteExport != null ||
        exportingAlbum != null || albumPickerPending || documentPickerPending || choosingImportSource ||
        creatingAlbum || choosingAlbum || choosingTag || managingTags || confirmingDelete ||
        confirmingEmptyTrash || choosingExport || originals != null || incomingShare != null
    val navigationBusy = shellNavigationBusy || privateNotesBusy || viewing != null || selection.isNotEmpty() || modalNavigationBusy
    DisposableEffect(controller, navigationBusy) {
        controller.setLauncherNavigationBusy(navigationBusy)
        onDispose { controller.setLauncherNavigationBusy(false) }
    }
    LaunchedEffect(launcherRequest?.token, generation, navigationBusy, launcherSceneReady) {
        val request = launcherRequest ?: return@LaunchedEffect
        if (!launcherSceneReady) return@LaunchedEffect
        if (navigationBusy || nativeChrome.hasVisibleMenu()) {
            controller.rejectLauncherAction(request.token)
            return@LaunchedEffect
        }
        if (!controller.acknowledgeLauncherAction(request.token, generation)) return@LaunchedEffect
        focusManager.clearFocus()
        openAlbum = null
        openTag = null
        favoritesOnly = false
        trashOpen = false
        quarantineOpen = false
        recentlyAdded = false
        selection = emptySet()
        terms = ""
        mediaKinds = 0
        searchFocusRequest = null
        when (request.action) {
            LauncherAction.IMPORT -> {
                showingPrivateNotes = false
                initialCreateRequest = null
                destination = VaultDestination.LIBRARY
                choosingImportSource = true
            }
            LauncherAction.SEARCH -> {
                showingPrivateNotes = false
                initialCreateRequest = null
                destination = VaultDestination.SEARCH
                searchFocusRequest = request.token
            }
            LauncherAction.NEW_PRIVATE_NOTE -> {
                initialCreateRequest = request.token
                showingPrivateNotes = true
            }
            LauncherAction.LOCK_AND_HIDE -> Unit // The controller handles this synchronously.
        }
    }

    val browseLocation = listOf(destination, openAlbum, openTag, favoritesOnly, trashOpen,
        quarantineOpen, recentlyAdded, terms, mediaSort, albumSort, mediaKinds)
    var loadedLocation by remember(generation) { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(destination, openAlbum, openTag, favoritesOnly, trashOpen, quarantineOpen, recentlyAdded,
        terms, mediaSort, albumSort, mediaKinds) {
        val query = browseQuery(destination, openAlbum, openTag, favoritesOnly,
            trashOpen, terms, mediaSort, albumSort, mediaKinds, quarantine = quarantineOpen,
            recentlyAdded = recentlyAdded)
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
        loadedLocation = browseLocation
    }

    // The cache is the controller's: its lock transitions clear it whether
    // or not this screen is composed, §4 of `PLAINTEXT_LIFECYCLE.md`. The
    // session generation in the key is what makes a stale entry unreachable
    // after a new session opens.
    val cache = controller.thumbnailCache
    LaunchedEffect(generation) {
        if (vaultState is VaultState.Unlocked) controller.loadAlbums()
    }

    val thumbnails = rememberGalleryThumbnails(
        controller.vault, cache, generation, page.objects.filter { it.id in visibleMediaIds }, thumbnailsConcealed,
    )
    // Plain pixels are loaded separately only while the viewer is open. They
    // never enter the gallery map, including the viewer's adjacent pages.
    val viewerThumbnails = rememberViewerThumbnails(
        controller.vault, cache, generation, page.objects, currentObjectId = viewing?.id,
    )

    originals?.let { picked ->
        SourceDeletionDialog(
            count = picked.count,
            onKeep = { originals = null },
            onReview = {
                originals = null
                // Photos asks for access and then to confirm. Each alert only
                // makes the scene resign active, so the two run in one prompt,
                // which leaving the app ends with a lock, `IOS.md` §21.2.
                val session = controller.vaultState.value
                val prompt = controller.beginPrompt()
                picked.delete { found, deleted ->
                    // Leaving or a lock ended the prompt with the session, and
                    // the outcome belongs to no screen of a later one.
                    if (controller.endPrompt(prompt) && controller.vaultState.value == session) {
                        controller.report(SourceDeletionCopy.verifiedOutcome(picked.count, found, deleted))
                    }
                }
            },
        )
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
                recentlyAdded = false
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
    val importFiles: () -> Unit = {
        val present = IosDocumentPicker.present
        if (present == null) {
            controller.report("This build cannot open the document picker.")
        } else {
            val albumId = openAlbum?.albumId
            documentPickerPending = true
            controller.beginHostActivity()
            present { count, load, releaseSelection ->
                val current = controller.vaultState.value as? VaultState.Unlocked
                val currentSession = current?.generation == generation
                val accepted = documentPickerPending && currentSession
                if (documentPickerPending) {
                    documentPickerPending = false
                    if (currentSession) controller.endHostActivity()
                }
                if (!accepted || count <= 0) {
                    releaseSelection()
                } else {
                    scope.launch {
                        try {
                            controller.importAll(importer, count, albumId) { index, prepare ->
                                suspendCancellableCoroutine { continuation ->
                                    load(index, prepare) { fd, name, type, size, release ->
                                        val picked = codec.openDocument(fd, name, type, size, release)
                                        continuation.resume(picked) { _, item, _ -> item?.close?.invoke() }
                                    }
                                }
                            }
                        } finally {
                            releaseSelection()
                        }
                    }
                }
            }
        }
    }
    val importMediaFiles: (Boolean) -> Unit = { livePhotoPackages ->
        val present = if (livePhotoPackages) IosLivePhotoFilesPicker.present else IosAudioPicker.present
        if (present == null) {
            controller.report("This build cannot open the media file picker.")
        } else {
            val albumId = openAlbum?.albumId
            documentPickerPending = true
            controller.beginHostActivity()
            present { count, load, releaseSelection ->
                val currentSession = (controller.vaultState.value as? VaultState.Unlocked)?.generation == generation
                val accepted = documentPickerPending && currentSession
                if (documentPickerPending) {
                    documentPickerPending = false
                    if (currentSession) controller.endHostActivity()
                }
                if (!accepted || count <= 0) {
                    releaseSelection()
                } else {
                    scope.launch {
                        try {
                            controller.importAll(importer, count, albumId) { index, prepare ->
                                suspendCancellableCoroutine { continuation ->
                                    load(index, prepare) { fd, name, type, size, path, release ->
                                        try {
                                            val picked = path?.let {
                                                codec.openMediaDocument(NSURL.fileURLWithPath(it), fd, name, type, size, release)
                                            }
                                            if (path == null) release()
                                            continuation.resume(picked) { _, item, _ -> item?.close?.invoke() }
                                        } catch (failure: Exception) {
                                            release()
                                            continuation.resumeWith(Result.failure(failure))
                                        }
                                    }
                                }
                            }
                        } finally {
                            releaseSelection()
                        }
                    }
                }
            }
        }
    }
    val importMedia: (Boolean) -> Unit = { fullLivePhoto ->
        val present = if (fullLivePhoto) IosLivePhotoPicker.present else IosMediaPicker.present
        if (present == null) {
            controller.report("This build cannot open the photo picker.")
        } else {
            val albumId = openAlbum?.albumId
            controller.beginHostActivity()
            present { count, load, review, canDeleteOriginal ->
                controller.endHostActivity()
                if (count > 0) {
                    scope.launch {
                        val outcomes = controller.importAll(importer, count, albumId) { index, prepare ->
                            // One item is fetched and copied at a
                            // time, and its copy is deleted when
                            // its import closes it, so at most one
                            // plaintext copy exists, `IOS.md` §15.2.
                            val (path, name) = withContext(Dispatchers.Main) {
                                suspendCancellableCoroutine { continuation ->
                                    load(index, prepare) { path, name ->
                                        continuation.resume(path to name) { _, _, _ ->
                                            path?.let(::discardPickedCopy)
                                        }
                                    }
                                }
                            }
                            path?.let { copy ->
                                val media = codec.open(NSURL.fileURLWithPath(copy), name)
                                if (media == null) discardPickedCopy(copy)
                                media?.copy(close = {
                                    media.close()
                                    discardPickedCopy(copy)
                                })
                            }
                        }
                        val imported = importedOriginals(List(count) { it }, outcomes)
                        val deletable = imported.filter(canDeleteOriginal)
                        if ((controller.vaultState.value as? VaultState.Unlocked)?.generation == generation) {
                            if (!fullLivePhoto && imported.size > deletable.size) {
                                val summary = controller.notice.value?.text.orEmpty()
                                controller.report("$summary Some Photos originals may contain additional media or edits. " +
                                    "Keep them in Photos; Chur has not verified a complete copy.")
                            }
                            if (deletable.isNotEmpty()) {
                                originals = PickedOriginals(deletable.size) { done -> review(deletable, done) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (choosingImportSource) {
        PrivateAlertDialog(
            onDismissRequest = { choosingImportSource = false },
            title = { Text("Import") },
            text = {
                Column {
                    TextButton(onClick = { choosingImportSource = false; importMedia(false) }, modifier = Modifier.fillMaxWidth()) { Text("Photos and videos") }
                    TextButton(onClick = { choosingImportSource = false; importMedia(true) }, modifier = Modifier.fillMaxWidth()) { Text("Full Live Photos (Photos access)") }
                    TextButton(onClick = { choosingImportSource = false; importMediaFiles(false) }, modifier = Modifier.fillMaxWidth()) { Text("Audio files") }
                    TextButton(onClick = { choosingImportSource = false; importMediaFiles(true) }, modifier = Modifier.fillMaxWidth()) { Text("Live Photo packages") }
                    TextButton(onClick = { choosingImportSource = false; importFiles() }, modifier = Modifier.fillMaxWidth()) { Text("Documents and files") }
                    TextButton(onClick = { choosingImportSource = false; choosingArchiveImport = true }, modifier = Modifier.fillMaxWidth()) { Text("Encrypted archive") }
                }
            },
            confirmButton = { TextButton(onClick = { choosingImportSource = false }) { Text("Cancel") } },
        )
    }
    exportingAlbum?.let { album ->
        AlbumExportDialog(
            album = album,
            onConfirm = {
                exportingAlbum = null
                val present = IosAlbumExportPicker.present
                if (present == null) {
                    controller.report("This build cannot open the folder picker.")
                } else {
                    controller.beginHostActivity()
                    albumPickerPending = true
                    present { token ->
                        val current = controller.vaultState.value as? VaultState.Unlocked
                        val currentSession = current?.generation == generation
                        val accepted = albumPickerPending && currentSession
                        if (albumPickerPending) {
                            albumPickerPending = false
                            if (currentSession) controller.endHostActivity()
                        }
                        if (token != null) {
                            if (accepted && controller.activeOperation.value == null) {
                                controller.exportAlbum(album.albumId, token)
                            } else {
                                IosAlbumExportPicker.discard?.invoke(token)
                            }
                        }
                    }
                }
            },
            onDismiss = { exportingAlbum = null },
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

    choosingArchive?.let { request ->
        SelectiveExportDialog(summary = request.summary, enabled = operation == null,
            onChoose = { password, target ->
                choosingArchive = null
                archiveTransfers.export(request, ArchiveCredential(password), target)
            }, onDismiss = { choosingArchive = null })
    }
    if (choosingArchiveImport) {
        SelectiveImportDialog(enabled = operation == null,
            onChoose = { password ->
                choosingArchiveImport = false
                archiveTransfers.import(ArchiveCredential(password)) {
                    val present = IosBackupPicker.present ?: error("the file picker is unavailable")
                    present { path ->
                        val descriptor = path?.let { open(it, O_RDONLY) } ?: -1
                        if (descriptor < 0) {
                            path?.let { unlink(it) }
                            if (path != null && (controller.vaultState.value as? VaultState.Unlocked)?.generation == generation) {
                                controller.report("That file could not be opened.")
                            }
                            archiveTransfers.importedFile(null)
                        } else {
                            archiveTransfers.importedFile(ArchiveSource(descriptor) {
                                close(descriptor)
                                path?.let { unlink(it) }
                            })
                        }
                    }
                }
            }, onDismiss = { choosingArchiveImport = false })
    }

    if (showingPrivateNotes) {
        SideEffect {
            scenes.activate(null)
            nativeChrome.suspendNavigation()
            nativeChrome.controller.view.endEditing(true)
            nativeChrome.controller.view.hidden = true
            nativeChrome.controller.view.accessibilityElementsHidden = true
            scenes.viewerChrome?.invoke(null)
        }
        PrivateNotesRoute(
            vault = controller.vault, generation = generation,
            onClose = { focusManager.clearFocus(); showingPrivateNotes = false; initialCreateRequest = null },
            onLock = { controller.lock() }, onPanic = controller::panic,
            onTouch = { controller.recordPrivateNoteActivity(generation) },
            onImportActivity = { active ->
                if ((controller.vaultState.value as? VaultState.Unlocked)?.generation == generation) {
                    if (active) controller.beginHostActivity() else controller.endHostActivity()
                }
            },
            onExport = { choosingPrivateNoteExport = PrivateNoteExportRequest.from(it, generation) },
            onAttachmentExport = { note, attachment -> choosingPrivateNoteExport = PrivateNoteExportRequest.fromAttachment(note, attachment, generation) },
            systemBack = { _, _ -> }, operation = operation,
            onCancelOperation = controller::cancelActiveOperation,
            notice = notice, onNoticeShown = controller::consume,
            onEncryptedExport = { choosingArchive = SelectiveExportRequest(generation, notes = it) },
            initialCreateRequest = initialCreateRequest,
            onNavigationBusyChange = { privateNotesBusy = it },
        )
        choosingPrivateNoteExport?.let { request ->
            ExportOptionsDialog(media = false, files = true, share = true,
                onChoose = { target ->
                    choosingPrivateNoteExport = null
                    controller.exportPrivateNote(request, target)
                }, onDismiss = { choosingPrivateNoteExport = null })
        }
        return
    }

    // Query registration clears the old page synchronously. Do not publish a
    // destination with another screen's page while its load effect is pending.
    val currentPage = if (loadedLocation == browseLocation) controller.page.value
        else ObjectPage(emptyList(), 0, 0, null)
    val shellState = VaultUiState(
        destination = destination,
        tiles = currentPage.objects.map { projection ->
            LibraryTile(
                projection = projection,
                thumbnail = thumbnails[projection.id],
                selected = projection.id in selection,
                thumbnailConcealed = thumbnailRendition(projection.mediaKind, thumbnailsConcealed) ==
                    ThumbnailRendition.Mosaic,
            )
        },
        albums = albums,
        searchTerms = terms,
        contentIndex = contentIndex,
        slots = slots,
        openAlbum = openAlbum,
        libraryScopeTitle = if (recentlyAdded) "Recently added" else if (trashOpen) "Trash" else if (quarantineOpen) "Quarantine" else openTag?.name ?: if (favoritesOnly) "Favorites" else null,
        trashOpen = trashOpen,
        recentlyAdded = recentlyAdded,
        mediaView = mediaView,
        thumbnailsConcealed = thumbnailsConcealed,
        albumView = albumView,
        sort = if (openAlbum != null) albumSort else if (recentlyAdded) QuerySort.IMPORT_DESC else mediaSort,
        kinds = mediaKinds,
        albumOrder = albumOrder,
        albumFilter = albumFilter,
        // The viewer is drawn over the shell here, so the shell leaves
        // the notice to it while it is open rather than show it twice.
        notice = notice.takeIf { viewing == null },
        operation = operation,
        selectedCount = selection.size,
        canLoadMore = currentPage.nextCursor != null,
        deviceSlotAvailable = controller.deviceUnlockAvailable,
        deviceSlotStrict = deviceSlotStrict,
        appLockEnabled = appLockEnabled,
        autoLock = autoLock,
        sync = syncStatus,
        devicePairing = vaultDevicePairing,
        sharingIdentity = sharingIdentity,
        sharingOverview = sharingOverview,
        sharingRecipient = sharingRecipient,
    )
    val shellActions = VaultActions(
        onThumbnailsConcealedChange = { thumbnailsConcealed = it },
        onDestination = {
            searchFocusRequest = null
            openAlbum = null
            openTag = null
            favoritesOnly = false
            trashOpen = false
            quarantineOpen = false
            recentlyAdded = false
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
                searchFocusRequest = null
                viewerVisit++
                viewing = projection
            } else {
                selection = selection.toggle(projection.id)
            }
        },
        onToggleSelection = { projection ->
            selection = selection.toggle(projection.id)
        },
        onOpenPrivateNotes = { focusManager.clearFocus(); searchFocusRequest = null; selection = emptySet(); recentlyAdded = false; initialCreateRequest = null; showingPrivateNotes = true },
        onImport = { choosingImportSource = true },
        onSearch = {
            terms = it
        },
        onIndexContent = { controller.indexContent() },
        onRebuildContentIndex = { controller.indexContent(rebuild = true) },
        onCancelContentIndex = controller::cancelContentIndex,
        onClearContentIndex = controller::clearContentIndex,
        onExportAlbum = { exportingAlbum = it },
        onEncryptedExportAlbum = { choosingArchive = SelectiveExportRequest(generation, albumIds = listOf(it.albumId)) },
        onOpenAlbum = { navigateAlbum(it) },
        onCloseAlbum = { navigateAlbum(albumParent(albums, openAlbum?.id)) },
        onAlbumRoot = { navigateAlbum(null) },
        onCreateAlbum = { creatingAlbum = true },
        onMediaViewChange = { mediaView = it },
        onAlbumViewChange = { albumView = it },
        onSortChange = {
            if (openAlbum != null) albumSort = it else if (!recentlyAdded) mediaSort = it
            selection = emptySet()
        },
        onKindsChange = { mediaKinds = it; selection = emptySet() },
        onAlbumOrderChange = { albumOrder = it },
        onAlbumFilterChange = { albumFilter = it },
        onShowRecentlyAdded = {
            focusManager.clearFocus()
            openAlbum = null
            openTag = null
            favoritesOnly = false
            trashOpen = false
            quarantineOpen = false
            selection = emptySet()
            destination = VaultDestination.LIBRARY
            recentlyAdded = true
        },
        onShowAllMedia = { recentlyAdded = false; openTag = null; favoritesOnly = false; trashOpen = false; quarantineOpen = false; selection = emptySet() },
        onShowFavorites = { recentlyAdded = false; openTag = null; favoritesOnly = true; trashOpen = false; quarantineOpen = false; selection = emptySet() },
        onShowTags = { controller.loadTags(); managingTags = true },
        onShowTrash = { recentlyAdded = false; openTag = null; favoritesOnly = false; trashOpen = true; quarantineOpen = false; selection = emptySet() },
        onShowQuarantine = { recentlyAdded = false; openTag = null; favoritesOnly = false; trashOpen = false; quarantineOpen = true; selection = emptySet() },
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
        onVisibleMediaChanged = { visibleMediaIds = it },
        onLock = { controller.lock() },
        onPanic = { controller.panic() },
        onDeleteDamagedFiles = { controller.deleteDamagedFiles() },
        onVerifyAll = { controller.verifyEverything() },
        onAddRecoverySlot = controller::addRecoverySlot,
        onChangePassword = controller::changePassword,
        onToggleAppLock = controller::toggleAppLock,
        onSetAutoLock = controller::setAutoLock,
        onAddDeviceSlot = controller::enrollAppleDeviceSlot,
        onToggleDeviceSlotPolicy = controller::toggleDeviceSlotPolicy,
        onPrepareDevicePairing = { controller.prepareDevicePairing() },
        onInspectDevicePairing = { controller.inspectDevicePairing(it) },
        onApproveDevicePairing = { request, fingerprint -> controller.approveDevicePairing(request, fingerprint) },
        onSavePairingApproval = pairingFiles.save,
        onOpenPairingApproval = pairingFiles.open,
        onLoadPairedDevices = { controller.loadPairedDevices() },
        onRevokePairedDevice = { controller.revokePairedDevice(it) },
        onCreateBackup = controller::createBackup,
        onEncryptExternalFiles = controller::openExternalArchive,
        onImportArchive = { choosingArchiveImport = true },
        onRestoreArchive = controller::restoreArchiveAsSeparateVault,
        onCreateSecondIdentity = controller::createSecondIdentity,
        onSelectLoadedItems = { selection = loadedObjectSelection(page) },
        onClearSelection = { selection = emptySet() },
        onExportSelection = { choosingExport = true },
        onEncryptedExportSelection = {
            if (selection.size > 1000) controller.report("Select at most 1000 items for one encrypted archive.")
            else choosingArchive = SelectiveExportRequest(generation, objectIds = selection.sorted().map { it.fromHex() })
        },
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
        // iOS asks for local network access by itself, as the first
        // connection to a local server goes out; the controller the
        // entry point binds holds the lock off under that alert.
        onConfigureSync = controller::configureSync,
        onSyncNow = controller::syncNow,
        onDisconnectSync = controller::disconnectSync,
        onAcknowledgeSyncStop = controller::acknowledgeSyncStop,
        onInspectSharingRecipient = controller::inspectSharingRecipient,
        onShareWithRecipient = controller::shareWithRecipient,
        onRevokeSharingMember = controller::revokeSharingMember,
    )
    val albumScreens = openAlbum?.let { albumPath(albums, it.id) }.orEmpty()
        .map { IosVaultScreen.Album(it.id) }
    val shellScreen = albumScreens.lastOrNull() ?: IosVaultScreen.Root(destination)
    val shellSearchFocusRequest = searchFocusRequest
    val projection = viewing
    val viewerScreen = projection?.let { IosVaultScreen.Viewer(viewerVisit) }
    val viewerPage = projection?.let { viewerPages(currentPage.objects, it) }.orEmpty()
    val screenStack = listOf(IosVaultScreen.Root(destination)) + albumScreens + listOfNotNull(viewerScreen)
    // Read child-owned busy state during composition. A read made only inside
    // SideEffect is not observed by this composition, so opening Viewer Info
    // would otherwise leave UIKit Back and toolbar commands enabled.
    val nativeNavigationBusy = modalNavigationBusy || shellNavigationBusy || viewerNavigationBusy
    SideEffect {
        if (controller.route.value != AppRoute.Vault ||
            (controller.vaultState.value as? VaultState.Unlocked)?.generation != generation
        ) return@SideEffect
        if (loadedLocation != browseLocation) {
            scenes.activate(null)
            nativeChrome.suspendNavigation()
            return@SideEffect
        }
        scenes.publish(shellScreen) { scene ->
            VaultShell(
                state = shellState,
                actions = shellActions.forScene(scene),
                searchFocusRequest = shellSearchFocusRequest,
                onNavigationBusyChange = { if (scene.canObserve()) shellNavigationBusy = it },
                systemBack = { _, _ -> },
            )
        }
        if (viewerScreen != null && projection != null) {
            scenes.publish(viewerScreen) { scene ->
                IosViewerRoute(
                    controller = controller, operation = operation, notice = notice,
                    cache = cache, generation = generation, projection = projection,
                    viewerToken = viewerScreen.token,
                    isCurrentItem = { viewing?.id == projection.id },
                    pages = viewerPage, thumbnails = viewerThumbnails,
                    canLoadMore = currentPage.nextCursor != null, trashOpen = shellState.trashOpen,
                    onSettled = { if (scene.canAct()) viewing = it },
                    onBack = { if (scene.canAct()) viewing = null },
                    onDeleted = { if (scene.canObserve()) viewing = null },
                    onChromeChanged = { if (scene.canObserve()) scenes.viewerChrome?.invoke(it) },
                    onNavigationBusyChange = { if (scene.canObserve()) viewerNavigationBusy = it },
                )
            }
        } else {
            viewerNavigationBusy = false
            scenes.viewerChrome?.invoke(null)
        }
        scenes.activate(viewerScreen ?: shellScreen)
        nativeChrome.configureScreens(factory = { screen ->
            scenes.createController(screen).also { content ->
                content.navigationItem.title = when (screen) {
                    is IosVaultScreen.Root -> screen.destination.label
                    is IosVaultScreen.Album -> albums.firstOrNull { it.id == screen.id }?.name
                    is IosVaultScreen.Viewer -> null
                }
            }
        }, onPop = { target ->
            if (controller.route.value == AppRoute.Vault &&
                (controller.vaultState.value as? VaultState.Unlocked)?.generation == generation
            ) {
                if (viewing != null && target == shellScreen) viewing = null
                else when (target) {
                    is IosVaultScreen.Album -> navigateAlbum(albums.firstOrNull { it.id == target.id })
                    is IosVaultScreen.Root -> if (target.destination == VaultDestination.ALBUMS)
                        navigateAlbum(null) else shellActions.onDestination(target.destination)
                    is IosVaultScreen.Viewer -> Unit
                }
            }
        }, onRetire = scenes::retire)
        nativeChrome.reconcile(generation, screenStack, destination)
        nativeChrome.update(shellState, shellActions, colors.accent,
            navigationBusy = nativeNavigationBusy,
            covered = modalNavigationBusy)
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
    viewerToken: Long,
    isCurrentItem: () -> Boolean,
    pages: List<ObjectProjection>,
    thumbnails: Map<String, ImageBitmap>,
    canLoadMore: Boolean,
    onSettled: (ObjectProjection) -> Unit,
    trashOpen: Boolean,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onChromeChanged: (Boolean?) -> Unit,
    onNavigationBusyChange: (Boolean) -> Unit,
) {
    val scene = checkNotNull(LocalIosVaultScene.current)
    val nativeChrome = checkNotNull(LocalIosVaultChrome.current)
    val colors = LocalChurColors.current
    // A scene survives paging. A never-reused identity also rejects an old A
    // callback after paging A → B → A; the parent predicate closes the gap
    // between a settled page changing and this composition being refreshed.
    val itemVisit = remember(projection.id, generation) { Any() }
    val currentItemVisit by rememberUpdatedState(itemVisit)
    fun canAct(): Boolean = scene.canAct() && isCurrentItem() && currentItemVisit === itemVisit
    fun canObserve(): Boolean = scene.canObserve() && isCurrentItem() && currentItemVisit === itemVisit
    val currentChromeChanged by rememberUpdatedState(onChromeChanged)
    val currentNavigationBusyChanged by rememberUpdatedState(onNavigationBusyChange)
    var detail by remember(projection.id) { mutableStateOf<ObjectDetail?>(null) }
    var favorite by remember(projection.id) { mutableStateOf(projection.favorite) }
    val tags by controller.tags.collectAsState()
    var choosingTags by remember(projection.id) { mutableStateOf(false) }
    val viewerScope = rememberCoroutineScope()
    var livePlayback by remember(projection.id, generation) { mutableStateOf<dev.po4yka.chur.app.vault.PlaybackSource?>(null) }
    var preview by remember(projection.id, generation) { mutableStateOf<ImageBitmap?>(null) }
    var stillState by remember(projection.id, generation) { mutableStateOf(ViewerStillState.Loading) }
    var showDetail by remember(projection.id) { mutableStateOf(false) }
    var waveform by remember(projection.id) { mutableStateOf<ByteArray?>(null) }
    var confirmingDelete by remember(projection.id) { mutableStateOf(false) }
    var choosingExport by remember(projection.id) { mutableStateOf(false) }
    val chrome = remember { ViewerChrome() }
    val chromePinned = projection.mediaKind == 4 || showDetail || choosingTags || choosingExport || confirmingDelete || operation != null
    val chromeVisible = chrome.visible(chromePinned)
    // §6.2 and §13.1: the status bar is light over the black canvas and goes
    // with the chrome, and it gets the theme's style back when the viewer goes.
    val navigationBusy = showDetail || choosingTags || choosingExport || confirmingDelete || operation != null
    LaunchedEffect(scene, scene.active, chromeVisible) {
        if (canObserve()) currentChromeChanged(chromeVisible)
    }
    LaunchedEffect(scene, scene.active, navigationBusy) {
        if (canObserve()) currentNavigationBusyChanged(navigationBusy)
    }
    DisposableEffect(scene) {
        onDispose {
            nativeChrome.clearViewer(generation, viewerToken)
            // A retired outgoing scene must not reset a newer Viewer's status.
            if (canObserve()) {
                currentChromeChanged(null)
                currentNavigationBusyChanged(false)
            }
        }
    }

    var zoomedPhoto by remember(projection.id, generation) { mutableStateOf(false) }
    val animation = rememberViewerAnimation(controller.vault, projection, generation, detail?.contentType)
    LaunchedEffect(projection.id, generation, zoomedPhoto, scene.active) {
        if (!canObserve()) return@LaunchedEffect
        // `IOS.md` §18: a preview before full-resolution ranges. A retired
        // native scene must never accept a late decrypted result.
        val cached = cache.load(controller.vault, generation, projection.objectId, projection.id)
        if (!canObserve()) return@LaunchedEffect
        preview = cached
        if (projection.mediaKind == MEDIA_CLASS_AUDIO) {
            val loaded = controller.derivativeOf(projection.objectId, StreamKind.AUDIO_WAVEFORM)
            if (!canObserve()) return@LaunchedEffect
            waveform = loaded
        }
        val loadedDetail = controller.detailOf(projection.objectId)
        if (!canObserve()) return@LaunchedEffect
        detail = loadedDetail
        if (loadedDetail?.contentType == dev.po4yka.chur.imports.LivePhotoBundle.CONTENT_TYPE) {
            val loaded = dev.po4yka.chur.app.vault.livePhotoPlaybackFor(
                controller.vault, projection.objectId,
                onVerify = { if (canAct()) controller.verifyEverything() })
            if (!canObserve()) return@LaunchedEffect
            livePlayback = loaded
        }
        val still = viewerStill(controller.vault, cache, generation, projection,
            highResolution = zoomedPhoto && animation == null)
        if (!canObserve()) return@LaunchedEffect
        still?.let { preview = it }
        stillState = if (preview != null || thumbnails[projection.id] != null) {
            ViewerStillState.Ready
        } else {
            ViewerStillState.Unavailable
        }
    }

    // DESIGN.md §20.3: a quarantined object is never silently retried in a
    // viewer, and CATALOG_SCHEMA_V1.md §5.1 says its container is absent or
    // unreadable. A player would retry the failed lease with no sign to the
    // user, so the viewer opens it without one.
    val playback = if (PresentedState.of(projection) == PresentedState.QUARANTINED) {
        null
    } else {
        livePlayback ?: playbackFor(
            vault = controller.vault,
            objectId = projection.objectId,
            mediaKind = projection.mediaKind,
            detail = detail,
            onVerify = { if (canAct()) controller.verifyEverything() },
        )
    }

    val touch: () -> Unit = { if (canAct()) chrome.touched() }
    val back: () -> Unit = { if (canAct()) onBack() }
    val toggleFavorite: () -> Unit = {
        if (canAct()) {
            val next = !favorite
            controller.setFavorite(projection.objectId, next) { if (canObserve()) favorite = next }
        }
    }
    val editTags: () -> Unit = { if (canAct()) { controller.loadTags(); choosingTags = true } }
    val export: () -> Unit = { if (canAct()) choosingExport = true }
    val delete: () -> Unit = { if (canAct()) confirmingDelete = true }
    val restore: () -> Unit = {
        if (canAct()) controller.restoreAll(listOf(projection.objectId)) { if (canObserve()) onDeleted() }
    }
    val toggleDetail: () -> Unit = { if (canAct()) showDetail = !showDetail }
    SideEffect {
        if (canObserve()) nativeChrome.updateViewer(
            IosViewerPanelState(
                generation = generation,
                token = viewerToken,
                itemId = projection.id,
                title = when (projection.mediaKind) {
                    MEDIA_CLASS_IMAGE -> "Photo"
                    MEDIA_CLASS_VIDEO -> "Video"
                    MEDIA_CLASS_AUDIO -> "Audio"
                    else -> "File"
                },
                favorite = favorite,
                trashed = trashOpen,
                busy = choosingTags || choosingExport || confirmingDelete || operation != null,
                visible = chromeVisible,
                infoVisible = showDetail,
            ),
            IosViewerPanelActions(
                onBack = back,
                onTouch = touch,
                onFavorite = toggleFavorite,
                onInfo = toggleDetail,
                onExport = export,
                onTags = editTags,
                onDelete = delete,
                onRestore = restore,
            ),
            colors.accent,
        )
    }

    ViewerScreen(
        projection = projection.copy(favorite = favorite),
        pages = pages,
        thumbnails = thumbnails,
        canLoadMore = canLoadMore,
        onLoadMore = { if (canObserve()) controller.loadNextPage() },
        onSettled = { if (canAct()) onSettled(it) },
        detail = detail,
        preview = preview,
        stillState = stillState,
        onVerifyStill = { if (canAct()) controller.verifyEverything() },
        onZoomChanged = { if (canAct()) zoomedPhoto = it },
        animation = animation,
        showDetail = showDetail,
        chromeVisible = chromeVisible,
        onToggleChrome = { if (canAct()) chrome.toggle(chromePinned) },
        onTouch = touch,
        onBack = back,
        onLock = { if (scene.canEscape()) nativeChrome.lockNow() },
        onPanic = { if (scene.canEscape()) nativeChrome.requestPanic() },
        onToggleFavorite = toggleFavorite,
        onEditTags = editTags,
        onExport = export,
        onDelete = delete,
        trashOpen = trashOpen,
        onRestore = restore,
        onToggleDetail = toggleDetail,
        nativeControls = true,
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
                    onControlsVisibleChange = { if (canObserve()) chrome.follow(it, chromePinned) },
                    controlsPadding = controlsPadding,
                    touches = chrome.touches,
                )
            }
        },
        document = if (projection.mediaKind == 4) {
            { modifier, contentPadding ->
                VaultDocument(controller.vault, generation, projection, detail, modifier, contentPadding)
            }
        } else null,
        waveform = waveform,
        operation = operation,
        onCancelOperation = { if (canAct()) controller.cancelActiveOperation() },
        notice = notice,
        onNoticeShown = { if (canObserve()) controller.consume(it) },
    )
    if (choosingTags) {
        val selected = listOf(projection.objectId)
        val refresh: () -> Unit = {
            if (canObserve()) viewerScope.launch {
                val loaded = controller.detailOf(projection.objectId)
                if (canObserve()) detail = loaded
            }
        }
        TagPickerDialog(tags = tags,
            onAdd = { tag -> if (canAct()) { choosingTags = false; controller.setTagForAll(tag.tagId, selected, true, refresh) } },
            onRemove = { tag -> if (canAct()) { choosingTags = false; controller.setTagForAll(tag.tagId, selected, false, refresh) } },
            onCreate = { name -> if (canAct()) { choosingTags = false; controller.createTagWithObjects(name, selected, refresh) } },
            onDismiss = { if (canAct()) choosingTags = false })
    }
    if (confirmingDelete) {
        DeleteSelectionDialog(
            count = 1,
            permanent = trashOpen,
            onDelete = {
                if (canAct()) {
                    confirmingDelete = false
                    if (trashOpen) controller.permanentlyDeleteAll(listOf(projection.objectId)) { if (canObserve()) onDeleted() }
                    else controller.delete(projection.objectId) { if (canObserve()) onDeleted() }
                }
            },
            onDismiss = { if (canAct()) confirmingDelete = false },
        )
    }
    if (choosingExport) {
        ExportOptionsDialog(
            media = canSaveToPhotos(projection.mediaKind) || detail?.contentType == dev.po4yka.chur.imports.LivePhotoBundle.CONTENT_TYPE,
            files = true,
            share = true,
            onChoose = { target ->
                if (canAct()) {
                    choosingExport = false
                    controller.export(projection.objectId, target)
                }
            },
            onDismiss = { if (canAct()) choosingExport = false },
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

/**
 * The photo picker the Xcode host presents, `IOS.md` §15.1.
 *
 * The host answers once, with how many items the user picked, in the order
 * picked, and a way to load each one. A load fetches the item, from iCloud
 * when it is not on the device, and reports the fetch to `prepare`; when
 * `prepare` answers false, the user has cancelled and the host stops the
 * fetch. It then answers with the path of a protected copy in the app's
 * temporary directory and the item's own name, or with no path when there is
 * nothing to import. The copy has a random name, because §12 keeps a file
 * name from telling the filename; the name travels here instead.
 *
 * `review` deletes the originals of the items at the given indices from the
 * photo library, `DESIGN.md` §15.3 and `IOS.md` §15.4: the host asks for
 * read-write access then and not before, and Photos asks the user before it
 * deletes. It answers on the main thread with how many originals it found
 * and how many were deleted. The host keeps the items' Photos identifiers,
 * which §28 keeps out of every log, only as long as Kotlin keeps `review`.
 */
public object IosMediaPicker {
    public var present: (
        (
            answer: (
                count: Int,
                load: (
                    index: Int,
                    prepare: (processed: Long, total: Long) -> Boolean,
                    loaded: (path: String?, name: String?) -> Unit,
                ) -> Unit,
                review: (indices: List<Int>, done: (found: Int, deleted: Int) -> Unit) -> Unit,
                canDeleteOriginal: (index: Int) -> Boolean,
            ) -> Unit,
        ) -> Unit
    )? = null
}

/** Full resource capture uses the same protected-file bridge and never offers source deletion. */
public object IosLivePhotoPicker {
    public var present: (
        (
            answer: (
                count: Int,
                load: (
                    index: Int,
                    prepare: (processed: Long, total: Long) -> Boolean,
                    loaded: (path: String?, name: String?) -> Unit,
                ) -> Unit,
                review: (indices: List<Int>, done: (found: Int, deleted: Int) -> Unit) -> Unit,
                canDeleteOriginal: (index: Int) -> Boolean,
            ) -> Unit,
        ) -> Unit
    )? = null
}

/** The originals an import committed, and the host's way to delete them. */
private class PickedOriginals(val count: Int, val delete: (done: (found: Int, deleted: Int) -> Unit) -> Unit)

/** Deletes a copy the photo picker made, and nothing outside the temporary directory. */
private fun discardPickedCopy(path: String) {
    if (path.startsWith(NSTemporaryDirectory())) unlink(path)
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
    var pending by remember { mutableStateOf<ArchiveCredential?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        onDispose {
            pending?.clear(); pending = null
            if (pickerOpen) { pickerOpen = false; controller.endHostActivity() }
        }
    }
    RestoreBackupScreen(
        busy = running, error = formError, cancelled = cancelled, operation = operation,
        onChoose = { password ->
            cancelled = false
            controller.report(null)
            val present = IosBackupPicker.present
            if (present == null) {
                controller.report("This build cannot open the file picker.")
            } else {
                pending?.clear()
                pending = ArchiveCredential(password)
                running = true
                controller.beginHostActivity()
                pickerOpen = true
                try {
                    present { path ->
                        val credential = pending
                        pending = null
                        if (pickerOpen) { pickerOpen = false; controller.endHostActivity() }
                        val descriptor = if (credential != null) path?.let { open(it, O_RDONLY) } ?: -1 else -1
                        if (descriptor < 0) {
                            credential?.clear()
                            if (path != null && credential != null) controller.report("That file could not be opened.")
                            path?.let { unlink(it) }
                            running = false
                        } else {
                            controller.restoreBackup(descriptor, requireNotNull(credential)) { wasCancelled ->
                                close(descriptor)
                                path?.let { unlink(it) }
                                running = false
                                cancelled = wasCancelled
                            }
                        }
                    }
                } catch (_: Exception) {
                    pending?.clear(); pending = null
                    pickerOpen = false; controller.endHostActivity()
                    running = false
                    controller.report("The file picker could not be opened.")
                }
            }
        },
        onBack = { if (!running) controller.goTo(AppRoute.PublicShell) },
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

/** The host keeps a selected folder grant and returns one transient opaque token. */
public object IosAlbumExportPicker {
    public var present: ((answer: (String?) -> Unit) -> Unit)? = null
    public var discard: ((token: String) -> Unit)? = null
    public var cancel: (() -> Unit)? = null
}

/** The host retains source grants only until the sequential import closes them. */
public object IosDocumentPicker {
    public var present: ((answer: (
        count: Int,
        load: (index: Int, prepare: (processed: Long, total: Long) -> Boolean, loaded: (descriptor: Int, name: String?, contentType: String, size: Long, release: () -> Unit) -> Unit) -> Unit,
        releaseSelection: () -> Unit,
    ) -> Unit) -> Unit)? = null
    public var cancel: (() -> Unit)? = null
}

/** Audio files use a coordinated readable URL until the import releases the source. */
public object IosAudioPicker {
    public var present: ((answer: (
        count: Int,
        load: (index: Int, prepare: (processed: Long, total: Long) -> Boolean,
            loaded: (descriptor: Int, name: String?, contentType: String, size: Long, path: String?, release: () -> Unit) -> Unit) -> Unit,
        releaseSelection: () -> Unit,
    ) -> Unit) -> Unit)? = null
    public var cancel: (() -> Unit)? = null
}

/** Portable Live Photo packages use the media pipeline and keep their scoped source lease. */
public object IosLivePhotoFilesPicker {
    public var present: ((answer: (
        count: Int,
        load: (index: Int, prepare: (processed: Long, total: Long) -> Boolean,
            loaded: (descriptor: Int, name: String?, contentType: String, size: Long, path: String?, release: () -> Unit) -> Unit) -> Unit,
        releaseSelection: () -> Unit,
    ) -> Unit) -> Unit)? = null
    public var cancel: (() -> Unit)? = null
}
