package dev.po4yka.chur.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.MediaImporter
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
import dev.po4yka.chur.app.vault.RecoveryPhraseScreen
import dev.po4yka.chur.app.vault.RecoveryScreen
import dev.po4yka.chur.app.vault.RestoreBackupScreen
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.UnlockScreen
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.app.vault.MEDIA_CLASS_AUDIO
import dev.po4yka.chur.app.vault.MEDIA_CLASS_VIDEO
import dev.po4yka.chur.app.vault.VaultPlayer
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
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The route table.
 *
 * `docs/security/PROVISIONING.md` §2 fixes the first route: the public shell,
 * with the route to the vault a visible settings entry. Nothing here can reach
 * a private route without passing through [AppRoute.Unlock] or
 * [AppRoute.CreateVault], because those are the only two that call into the
 * application's unlock and create.
 */
@Composable
fun ChurRoutes(controller: ChurController, route: AppRoute, vaultState: VaultState) {
    val phrase by controller.recoveryPhrase.collectAsState()
    val message by controller.message.collectAsState()

    // The phrase is shown once and takes precedence over every route, because
    // `RECOVERY.md` §2 shows it exactly once and a navigation that skipped it
    // would be that once spent.
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
            // The offer exists only where no identity does. `DECOY_VAULT.md`
            // §8 and §10: this screen is also where a second identity is
            // created, and a restore offered there would reach for a registry
            // that already holds one.
            onRestore = if (vaultState is VaultState.NoVault) {
                { controller.goTo(AppRoute.RestoreBackup) }
            } else {
                null
            },
        )
        AppRoute.RestoreBackup -> RestoreRoute(controller)
        AppRoute.Unlock -> UnlockScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || message != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.Recover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithDevice,
        )
        AppRoute.AppUnlock -> UnlockScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || message != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.AppRecover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithDevice,
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
        AppRoute.Vault -> VaultRoute(controller)
    }
}

@Composable
private fun PublicShell(controller: ChurController, route: AppRoute) {
    val notes by controller.notesState.collectAsState()
    val vaultState by controller.vaultState.collectAsState()
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
            editing = Note(
                id = "note-${System.nanoTime()}",
                title = "",
                body = "",
                updatedMs = System.currentTimeMillis(),
            )
        },
        // §2: the route to the vault is a visible settings entry.
        // `DISCREET_MODE.md` "The v1 decision" makes it the session gate, and
        // it now opens the public shell's own settings, where the permanent
        // disclosure sits beside the vault row.
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

/**
 * The restore route, `docs/format/BACKUP_FORMAT_V1.md` §8.
 *
 * The picker is `OpenDocument` and its filter is unrestricted: a package has no
 * registered media type, and one copied from another device carries whatever
 * type the provider that holds it reports.
 *
 * The bracket around the launch is the media picker's, for the media picker's
 * reason. The platform stops this activity while the documents UI is up, and
 * the background transition would move the shell back to the public route and
 * take this screen with it - and would mark a storage root that holds no vault
 * as `Locked`.
 */
@Composable
private fun RestoreRoute(controller: ChurController) {
    val message by controller.message.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        controller.endHostActivity()
        // §8 reads the package from both ends, so the descriptor must seek. A
        // provider that streams gives one that cannot, and the boundary refuses
        // with its own status rather than reading a package it cannot verify.
        //
        // `openFileDescriptor` throws when the provider cannot open the
        // document, and throws again when a grant has gone - which a process
        // death while the documents UI was up produces, because the result is
        // then delivered into a new composition. This runs on the main thread
        // outside the controller's guard, where a throw ends the process rather
        // than the attempt: `PLAINTEXT_LIFECYCLE.md` §8 wants the orderly lock.
        val handle = uri?.let {
            runCatching { context.contentResolver.openFileDescriptor(it, "r") }.getOrNull()
        }
        if (handle == null) {
            // A dismissal and an unreadable file end the attempt the same way.
            // Only the second one has anything to say.
            if (uri != null) controller.report("That file could not be opened.")
            running = false
            password = ""
        } else {
            controller.restoreBackup(handle.fd, password) {
                handle.close()
                running = false
                password = ""
            }
        }
    }

    RestoreBackupScreen(
        busy = running,
        error = message,
        operation = operation,
        onChoose = { entered ->
            // The flag is what stops a second package being restored on top of
            // the first: both would pass the registry check of §11 while the
            // first was still running, and the root would end with two
            // identities behind one credential.
            password = entered
            running = true
            controller.beginHostActivity()
            picker.launch(arrayOf("*/*"))
        },
        onBack = { controller.goTo(AppRoute.PublicShell) },
        onCancel = controller::cancelActiveOperation,
    )
}

@Composable
private fun VaultRoute(controller: ChurController) {
    val page by controller.page.collectAsState()
    val albums by controller.albums.collectAsState()
    val tags by controller.tags.collectAsState()
    val slots by controller.slots.collectAsState()
    val message by controller.message.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    val vaultState by controller.vaultState.collectAsState()
    val syncStatus by controller.syncStatus.collectAsState()
    val sharingIdentity by controller.sharingIdentity.collectAsState()
    val sharingOverview by controller.sharingOverview.collectAsState()
    val sharingRecipient by controller.sharingRecipient.collectAsState()
    val deviceSlotStrict by controller.deviceSlotStrict.collectAsState()
    val appLockEnabled by controller.appLockEnabled.collectAsState()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceControl = remember(context) { ChurHost.of(context).deviceControl }
    val devicePairing by deviceControl.pairing.collectAsState()

    var destination by remember { mutableStateOf(VaultDestination.LIBRARY) }
    var terms by remember { mutableStateOf("") }
    var openAlbum by remember { mutableStateOf<AlbumSummary?>(null) }
    LaunchedEffect(albums) {
        openAlbum = openAlbum?.let { current -> albums.firstOrNull { it.id == current.id } }
    }
    var viewing by remember { mutableStateOf<ObjectProjection?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var creatingAlbum by remember { mutableStateOf(false) }
    var choosingAlbum by remember { mutableStateOf(false) }
    var choosingTag by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var choosingExport by remember { mutableStateOf(false) }
    var choosingImport by remember { mutableStateOf(false) }
    var importAlbumId by remember { mutableStateOf<ByteArray?>(null) }

    DisposableEffect(deviceControl) {
        onDispose { deviceControl.stop() }
    }
    LaunchedEffect(destination, vaultState) {
        if (destination != VaultDestination.SETTINGS || vaultState !is VaultState.Unlocked) {
            deviceControl.stop()
        }
    }

    // The cache is the controller's: its lock transitions clear it whether
    // or not this screen is composed, §4 of `PLAINTEXT_LIFECYCLE.md`. The
    // session generation in the key is what makes a stale entry unreachable
    // after a new session opens.
    val cache = controller.thumbnailCache
    val generation = (vaultState as? VaultState.Unlocked)?.generation ?: 0L

    val codec = remember { AndroidMediaCodec(context.contentResolver) }
    val importer = remember { MediaImporter(codec) }
    val onPicked: (Uri?) -> Unit = { uri ->
        // The picker is an activity of ours, so the vault stayed open while it
        // ran. It ends here whether the user chose something or dismissed it.
        controller.endHostActivity()
        val albumId = importAlbumId
        importAlbumId = null
        if (uri != null) {
            scope.launch {
                val outcome = controller.importMedia(importer) { codec.open(uri) }
                if (controller.vaultState.value !is VaultState.Unlocked) return@launch
                when (outcome) {
                    is MediaImporter.Outcome.Imported -> {
                        controller.reportImport(when {
                            outcome.previewsSkipped -> "Imported original; remaining previews cancelled."
                            albumId == null -> "Imported into vault."
                            else -> null
                        })
                        if (albumId != null) {
                            controller.putAllInAlbum(albumId, listOf(outcome.objectId)) {
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
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), onPicked)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), onPicked)

    LaunchedEffect(destination, openAlbum, page.catalogGeneration) {
        when {
            openAlbum != null -> controller.load(
                ObjectQuery(QueryScope.ALBUM, sort = QuerySort.ALBUM_MANUAL, scopeId = openAlbum!!.albumId),
            )
            destination == VaultDestination.LIBRARY -> controller.load(ObjectQuery())
            destination == VaultDestination.ALBUMS -> controller.loadAlbums()
            destination == VaultDestination.SETTINGS -> {
                controller.loadSlots()
                controller.loadSharing()
            }
            else -> Unit
        }
    }

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

    viewing?.let { projection ->
        ViewerRoute(
            controller = controller,
            operation = operation,
            status = message,
            cache = cache,
            generation = generation,
            projection = projection,
            onBack = { viewing = null },
            onDeleted = {
                viewing = null
                controller.load(ObjectQuery())
            },
        )
        return
    }

    if (choosingImport) {
        AlertDialog(
            onDismissRequest = { choosingImport = false },
            title = { Text("Import media") },
            text = { Text("Choose photos and videos or an audio file.") },
            confirmButton = {
                TextButton(onClick = {
                    choosingImport = false
                    importAlbumId = openAlbum?.albumId
                    controller.beginHostActivity()
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }) { Text("Photos and videos") }
            },
            dismissButton = {
                TextButton(onClick = {
                    choosingImport = false
                    importAlbumId = openAlbum?.albumId
                    controller.beginHostActivity()
                    audioPicker.launch(arrayOf("audio/*"))
                }) { Text("Audio files") }
            },
        )
    }

    devicePairing?.let { pairing ->
        var copied by remember(pairing) { mutableStateOf(false) }
        val sessionLink = "chur://device-control/v1?port=${pairing.port}#${pairing.code}"
        AlertDialog(
            onDismissRequest = deviceControl::stop,
            title = { Text("Control from computer") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Session active", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                    Text("Keep Chur open and unlocked. Connect your computer with USB debugging enabled.")
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Port", style = MaterialTheme.typography.labelMedium)
                            Text("${pairing.port}", fontFamily = FontFamily.Monospace)
                            Text("One-session code", style = MaterialTheme.typography.labelMedium)
                            Text(pairing.code, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(
                        "Copy the link to use with scripts/chur-device.py. Keep it private: " +
                            "anyone with the link and ADB access can control this vault. " +
                            "Stop revokes the session.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clip = ClipData.newPlainText("Chur control session", sessionLink)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(clip)
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy session link") }
            },
            dismissButton = { TextButton(onClick = deviceControl::stop) { Text("Stop") } },
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
            onChoose = { album ->
                choosingAlbum = false
                controller.putAllInAlbum(
                    album.albumId, selectedObjects(page, selection), openAlbum?.albumId,
                ) { selection = emptySet() }
            },
            onCreate = { name ->
                choosingAlbum = false
                controller.createAlbumWithObjects(
                    name, selectedObjects(page, selection), openAlbum?.albumId,
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
                controller.setTagForAll(tag.tagId, selectedObjects(page, selection), true) {
                    selection = emptySet()
                }
            },
            onRemove = { tag ->
                choosingTag = false
                controller.setTagForAll(tag.tagId, selectedObjects(page, selection), false) {
                    selection = emptySet()
                }
            },
            onCreate = { name ->
                choosingTag = false
                controller.createTagWithObjects(name, selectedObjects(page, selection)) {
                    selection = emptySet()
                }
            },
            onDismiss = { choosingTag = false },
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
            files = false,
            share = false,
            downloads = true,
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
            widthDp = configuration.screenWidthDp,
            progress = message,
            operation = operation,
            selectedCount = selection.size,
            canLoadMore = page.nextCursor != null,
            deviceSlotAvailable = true,
            deviceSlotStrict = deviceSlotStrict,
            appLockEnabled = appLockEnabled,
            deviceControlAvailable = true,
            sync = syncStatus,
            sharingIdentity = sharingIdentity,
            sharingOverview = sharingOverview,
            sharingRecipient = sharingRecipient,
        ),
        actions = VaultActions(
            onDestination = {
                openAlbum = null
                selection = emptySet()
                destination = it
            },
            onOpen = { projection ->
                // §11.4: a tap opens the viewer, unless a selection is running,
                // in which case it extends the selection. Selection is a mode
                // and an open would leave it silently.
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
            onImport = { choosingImport = true },
            onSearch = {
                terms = it
                controller.search(it)
            },
            onOpenAlbum = { openAlbum = it },
            onCloseAlbum = { openAlbum = null },
            onCreateAlbum = { creatingAlbum = true },
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
            onDeviceControl = { deviceControl.start() },
            onCreateBackup = controller::createBackup,
            onCreateSecondIdentity = controller::createSecondIdentity,
            onSelectAll = { selection = page.objects.map { it.id }.toSet() },
            onClearSelection = { selection = emptySet() },
            onExportSelection = { choosingExport = true },
            onOrganizeSelection = {
                controller.loadAlbums()
                choosingAlbum = true
            },
            onTagSelection = {
                controller.loadTags()
                choosingTag = true
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
            onAddDeviceSlot = controller::enrollDeviceSlot,
            onToggleDeviceSlotPolicy = controller::toggleDeviceSlotPolicy,
            onConfigureSync = controller::configureSync,
            onSyncNow = controller::syncNow,
            onDisconnectSync = controller::disconnectSync,
            onInspectSharingRecipient = controller::inspectSharingRecipient,
            onShareWithRecipient = controller::shareWithRecipient,
            onRevokeSharingMember = controller::revokeSharingMember,
        ),
    )
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

/**
 * The viewer, `DESIGN.md` §13.
 *
 * The preview is the screen-preview derivative when one exists and the small
 * thumbnail otherwise: §8 of the media pipeline decrypts the full-resolution
 * original only for detailed viewing and export, and a photograph that never
 * needed a preview is already small enough to be its own.
 */
@Composable
private fun ViewerRoute(
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
    var preview by remember(projection.id) { mutableStateOf<ImageBitmap?>(null) }
    var showDetail by remember(projection.id) { mutableStateOf(false) }
    var waveform by remember(projection.id) { mutableStateOf<ByteArray?>(null) }
    var confirmingDelete by remember(projection.id) { mutableStateOf(false) }
    var choosingExport by remember(projection.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.endHostActivity()
        result.data?.data?.let { uri ->
            if (controller.vaultState.value is VaultState.Unlocked) {
                controller.export(projection.objectId, ExportTarget.FILES, uri.toString())
            } else {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

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
        projection = projection,
        detail = detail,
        preview = preview,
        showDetail = showDetail,
        onBack = onBack,
        onToggleFavorite = {
            controller.setFavorite(projection.objectId, !projection.favorite)
        },
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
            downloads = true,
            onChoose = { target ->
                choosingExport = false
                if (target == ExportTarget.FILES) {
                    controller.beginHostActivity()
                    filePicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = detail?.contentType ?: "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, detail?.filename?.ifBlank { "chur-export" } ?: "chur-export")
                    })
                } else {
                    controller.export(projection.objectId, target)
                }
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
