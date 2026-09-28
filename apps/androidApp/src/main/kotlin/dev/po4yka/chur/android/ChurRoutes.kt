package dev.po4yka.chur.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.Notice
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
import dev.po4yka.chur.app.vault.SourceDeletionDialog
import dev.po4yka.chur.app.vault.importedOriginals
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.UnlockScreen
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.ContentView
import dev.po4yka.chur.app.vault.AlbumOrder
import dev.po4yka.chur.app.vault.browseQuery
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.app.vault.MEDIA_CLASS_AUDIO
import dev.po4yka.chur.app.vault.viewerPages
import dev.po4yka.chur.app.vault.viewerStill
import dev.po4yka.chur.app.vault.VaultPlayer
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
 *
 * System Back does what the screen's own back or cancel control does, and a
 * screen without one returns to where it is entered from. A screen that must
 * not be left (the recovery phrase, a creation or a restore in progress) holds
 * Back. Without a handler Back is the platform's back-to-home, and the
 * background lock of `MainActivity.onPause` then closes the vault and drops
 * whatever the screen held. Only the roots let it through: the public shell,
 * the app gate, and the vault's Library.
 */
@Composable
fun ChurRoutes(controller: ChurController, route: AppRoute, vaultState: VaultState) {
    val phrase by controller.recoveryPhrase.collectAsState()
    val formError by controller.formError.collectAsState()
    val unlocking by controller.unlocking.collectAsState()

    // The phrase is shown once and takes precedence over every route, because
    // `RECOVERY.md` §2 shows it exactly once and a navigation that skipped it
    // would be that once spent. Back is held for the same reason: leaving
    // would run the background lock, which clears the phrase for good.
    phrase?.let { value ->
        BackHandler {}
        // Copying 24 words by hand, `RECOVERY.md` §2.3, outlasts the screen
        // timeout, and the pause that follows it used to lock and drop the
        // phrase mid-copy. The repository's phrase window bounds how long the
        // display stays awake.
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
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
        AppRoute.PublicShell, AppRoute.PublicSettings -> PublicShell(controller, route)
        AppRoute.CreateVault -> {
            // A second identity is created from an open session, `DECOY_VAULT.md`
            // §3, so leaving returns to that vault and never shows the public
            // shell over it: a vault entry from there would open a second
            // session beside the first. Nothing leaves while the vault is being
            // created, as 'Not now' is disabled then.
            val leave = {
                when (controller.vaultState.value) {
                    is VaultState.Creating -> Unit
                    is VaultState.Unlocked -> controller.goTo(AppRoute.Vault)
                    else -> controller.goTo(AppRoute.PublicShell)
                }
            }
            BackHandler(onBack = leave)
            CreateVaultScreen(
                busy = vaultState is VaultState.Creating,
                error = formError,
                onCreate = controller::create,
                onCancel = leave,
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
        }
        AppRoute.RestoreBackup -> RestoreRoute(controller)
        AppRoute.Unlock -> {
            // The screen has no way back of its own. It is entered from the
            // vault entry of the public settings, or after a restore, and Back
            // returns to that entry either way.
            BackHandler { controller.goTo(AppRoute.PublicSettings) }
            UnlockScreen(
                busy = unlocking,
                failed = (vaultState as? VaultState.Locked)?.lastFailure != null || formError != null,
                onUnlock = controller::unlock,
                onUseRecovery = { controller.goTo(AppRoute.Recover) },
                deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
                onUseDevice = controller::unlockWithDevice,
            )
        }
        AppRoute.AppUnlock -> UnlockScreen(
            busy = unlocking,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null || formError != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.AppRecover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithDevice,
            appGate = true,
        )
        AppRoute.Recover -> {
            val back = { controller.goTo(AppRoute.Unlock) }
            BackHandler(onBack = back)
            RecoveryScreen(
                busy = unlocking,
                error = formError,
                onRecover = controller::recover,
                onBack = back,
            )
        }
        AppRoute.AppRecover -> {
            val back = { controller.goTo(AppRoute.AppUnlock) }
            BackHandler(onBack = back)
            RecoveryScreen(
                busy = unlocking,
                error = formError,
                onRecover = controller::recover,
                onBack = back,
            )
        }
        AppRoute.Vault -> VaultRoute(controller)
    }
}

@Composable
private fun PublicShell(controller: ChurController, route: AppRoute) {
    val notes by controller.notesState.collectAsState()
    val vaultState by controller.vaultState.collectAsState()
    val disclosureDue by controller.disclosureDue.collectAsState()
    var query by remember { mutableStateOf("") }
    // Saved state, so a recreation or a process restart reopens the note being
    // written; the editor saves its own draft whenever it leaves.
    var editing by rememberOpenNote()
    val focusManager = LocalFocusManager.current

    editing?.let { open ->
        // System and predictive Back close the editor, as its arrow does,
        // `DESIGN.md` §22.1. Closing is all either does: the editor writes the
        // draft as it leaves composition, so no save rides on the press.
        BackHandler { editing = null }
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
            editing = OpenNote(
                Note(
                    id = "note-${System.nanoTime()}",
                    title = "",
                    body = "",
                    updatedMs = System.currentTimeMillis(),
                ),
                isNew = true,
            )
        },
        // §2: the route to the vault is a visible settings entry.
        // `DISCREET_MODE.md` "The v1 decision" makes it the session gate, and
        // it now opens the public shell's own settings, where the permanent
        // disclosure sits beside the vault row. The search field stays
        // composed under them, so it gives up the keyboard first: an open IME
        // would take keystrokes meant for nothing and the first Back.
        onOpenSettings = {
            focusManager.clearFocus()
            controller.goTo(AppRoute.PublicSettings)
        },
        showFirstWriteDisclosure = disclosureDue,
        onAcknowledgeDisclosure = controller::acknowledgeDisclosure,
    )
    if (route == AppRoute.PublicSettings) {
        BackHandler { controller.goTo(AppRoute.PublicShell) }
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
 * take this screen with it.
 */
@Composable
private fun RestoreRoute(controller: ChurController) {
    val formError by controller.formError.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }

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
            controller.restoreBackup(handle.fd, password) { wasCancelled ->
                handle.close()
                running = false
                cancelled = wasCancelled
                password = ""
            }
        }
    }

    // Back waits while a restore runs, as the screen's own Back does.
    val back = { controller.goTo(AppRoute.PublicShell) }
    BackHandler { if (!running) back() }
    RestoreBackupScreen(
        busy = running,
        error = formError,
        cancelled = cancelled,
        operation = operation,
        onChoose = { entered ->
            // The flag is what stops a second package being restored on top of
            // the first: both would pass the registry check of §11 while the
            // first was still running, and the root would end with two
            // identities behind one credential.
            password = entered
            running = true
            // The flag and the message it styles clear together. A dismissed
            // picker reports nothing, so a "Cancelled." left from the last
            // attempt would otherwise stay on screen as an error.
            cancelled = false
            controller.report(null)
            controller.beginHostActivity()
            picker.launch(arrayOf("*/*"))
        },
        onBack = back,
        onCancel = controller::cancelActiveOperation,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultRoute(controller: ChurController) {
    val page by controller.page.collectAsState()
    val albums by controller.albums.collectAsState()
    val tags by controller.tags.collectAsState()
    val slots by controller.slots.collectAsState()
    val notice by controller.notice.collectAsState()
    val operation by controller.activeOperation.collectAsState()
    val vaultState by controller.vaultState.collectAsState()
    val syncStatus by controller.syncStatus.collectAsState()
    val sharingIdentity by controller.sharingIdentity.collectAsState()
    val sharingOverview by controller.sharingOverview.collectAsState()
    val sharingRecipient by controller.sharingRecipient.collectAsState()
    val deviceSlotStrict by controller.deviceSlotStrict.collectAsState()
    val appLockEnabled by controller.appLockEnabled.collectAsState()
    val autoLock by controller.autoLock.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val deviceControl = remember(context) { ChurHost.of(context).deviceControl }
    val devicePairing by deviceControl.pairing.collectAsState()
    val deviceConnected by deviceControl.connected.collectAsState()

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
    // The item on screen in the viewer; a swipe moves it through the page.
    var viewing by remember { mutableStateOf<ObjectProjection?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
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
    var choosingImport by remember { mutableStateOf(false) }
    var importAlbumId by remember { mutableStateOf<ByteArray?>(null) }
    // The picked items whose import committed, until the user answers the
    // step of `DESIGN.md` §15.3. A lock disposes of the route and of them.
    var originals by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val deleteOriginals = rememberOriginalDeletion(controller)
    val localNetwork = rememberLocalNetworkAccess(controller)
    // A local server without the grant only times out, in the worker as in
    // the app, so Settings names the cause, `ANDROID.md` §24. Each new status,
    // and each unlock, checks again.
    val localNetworkOff by produceState(false, syncStatus) {
        value = syncStatus?.serverUrl?.let { localNetworkBlocked(context, it) } == true
    }

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
    LaunchedEffect(generation) {
        if (vaultState is VaultState.Unlocked) controller.loadAlbums()
    }

    val codec = remember { AndroidMediaCodec(context.contentResolver) }
    val importer = remember { MediaImporter(codec) }
    val onPicked: (List<Uri>) -> Unit = { uris ->
        // The picker is an activity of ours, so the vault stayed open while it
        // ran. It ends here whether the user chose something or dismissed it.
        controller.endHostActivity()
        val albumId = importAlbumId
        importAlbumId = null
        // The grants live in this list for the one batch and its §15.3 step,
        // and never reach saved state, `ANDROID.md` §14.2.
        if (uris.isNotEmpty()) {
            scope.launch {
                val outcomes = controller.importAll(importer, uris.size, albumId) { index, _ -> codec.open(uris[index]) }
                if (controller.vaultState.value is VaultState.Unlocked) originals = importedOriginals(uris, outcomes)
            }
        }
    }
    // Both pickers take many items in one session. The photo picker keeps
    // its platform limit, and on API levels without it the contract falls
    // back to a document picker that allows multiple selection (§14.1).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), onPicked)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onPicked)

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

    if (choosingImport) {
        // `DESIGN.md` §26: a sheet for a contextual choice. Each source is a
        // row of its own, and the scrim and Back dismiss the sheet. The
        // dialog it replaces put "Audio files" in the dismiss slot, so no
        // button only closed it.
        ModalBottomSheet(onDismissRequest = { choosingImport = false }) {
            Text(
                "Import media",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            ListItem(
                headlineContent = { Text("Photos and videos") },
                modifier = Modifier.clickable(role = Role.Button) {
                    choosingImport = false
                    importAlbumId = openAlbum?.albumId
                    controller.beginHostActivity()
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
            )
            ListItem(
                headlineContent = { Text("Audio files") },
                modifier = Modifier.clickable(role = Role.Button) {
                    choosingImport = false
                    importAlbumId = openAlbum?.albumId
                    controller.beginHostActivity()
                    audioPicker.launch(arrayOf("audio/*"))
                },
            )
        }
    }

    if (originals.isNotEmpty()) {
        SourceDeletionDialog(
            count = originals.size,
            onKeep = { originals = emptyList() },
            onReview = {
                deleteOriginals(originals)
                originals = emptyList()
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
                    Text(if (deviceConnected) "Computer connected" else "Waiting for computer",
                        color = MaterialTheme.colorScheme.primary,
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
                            "Stop revokes the session. It also stops after 10 minutes without a command.",
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

    // The viewer is drawn over the shell rather than in its place, as on
    // iOS: `ChurApp` stacks a route's content, so the shell stays composed and
    // its grid and list keep their scroll positions when the viewer closes.
    // Covered, the shell is out of a screen reader's reach, §4 of
    // `PLAINTEXT_LIFECYCLE.md`, and the viewer's pointer input keeps touches
    // from it. It is out of keyboard focus too, §23.3 of `DESIGN.md`: opening
    // the viewer takes the focus from it, and a hardware keyboard cannot move
    // the focus back in. The viewer's Back handlers are registered after the
    // shell's, so they answer first.
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
                // The viewer is drawn over the shell, so the shell leaves the
                // notice to it while it is open rather than show it twice.
                notice = notice.takeIf { viewing == null },
                operation = operation,
                selectedCount = selection.size,
                canLoadMore = page.nextCursor != null,
                deviceSlotAvailable = true,
                deviceSlotStrict = deviceSlotStrict,
                appLockEnabled = appLockEnabled,
                autoLock = autoLock,
                deviceControlAvailable = true,
                sync = if (localNetworkOff) syncStatus?.copy(message = LOCAL_NETWORK_OFF, failure = null) else syncStatus,
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
                onOpen = { projection ->
                    // §11.4: a tap opens the viewer, unless a selection is running,
                    // in which case it extends the selection. Selection is a mode
                    // and an open would leave it silently.
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
                onImport = { choosingImport = true },
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
                onSetAutoLock = controller::setAutoLock,
                onDeviceControl = { deviceControl.start() },
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
                onAddDeviceSlot = controller::enrollDeviceSlot,
                onToggleDeviceSlotPolicy = controller::toggleDeviceSlotPolicy,
                onConfigureSync = { serverUrl, secret ->
                    localNetwork(serverUrl) { controller.configureSync(serverUrl, secret) }
                },
                onSyncNow = {
                    syncStatus?.serverUrl?.let { localNetwork(it, controller::syncNow) } ?: controller.syncNow()
                },
                onDisconnectSync = controller::disconnectSync,
                onInspectSharingRecipient = controller::inspectSharingRecipient,
                onShareWithRecipient = controller::shareWithRecipient,
                onRevokeSharingMember = controller::revokeSharingMember,
            ),
            systemBack = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
        )
    }

    viewing?.let { projection ->
        ViewerRoute(
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
            // Delete, restore and permanent delete reload the current query
            // themselves, and a second load would empty the page first.
            onDeleted = { viewing = null },
        )
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

/**
 * The viewer, `DESIGN.md` §13.
 *
 * The thumbnail the grid already decoded shows at once, and `viewerStill`
 * replaces it with the sharpest still the object has: the screen preview, or
 * for a photograph that never needed one the decoded original, which §8 of
 * the media pipeline permits for detailed viewing and which is small enough
 * to be its own preview.
 *
 * A swipe moves through [pages], §13.1, and this route follows it:
 * [projection] is the item on screen, and its detail, its still, its player
 * and its dialogs are keyed to it, so they are made once, for that item, and
 * not for each page a swipe passes.
 */
@Composable
private fun ViewerRoute(
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
    // The viewer is drawn over the shell, not a back-stack entry, so Back is
    // routed to its own Back here; a press that reached the shell would act
    // on the screen under it, and one that reached the platform would go
    // home and lock.
    BackHandler(onBack = onBack)
    // Registered after the viewer's handler, so it answers first: Back closes
    // the Info overlay before it closes the viewer. The overlay is drawn only
    // once the detail has loaded.
    BackHandler(enabled = showDetail && detail != null) { showDetail = false }
    // §6.2: the viewer's canvas is black in both themes, so the system bars
    // draw light icons over it while it is shown and get the theme's choice
    // back when it goes, whether Back, a delete, or a lock ends it. The
    // activity handles rotation and theme changes itself, and edge-to-edge
    // then reapplies the theme's icons, so the effect runs again on every
    // configuration and restores from the theme now in force rather than
    // from a value saved under the old one.
    val window = LocalActivity.current?.window
    val configuration = LocalConfiguration.current
    DisposableEffect(window, configuration) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            // What `enableEdgeToEdge`'s automatic style picks: dark icons on
            // the light theme, light icons on the dark one.
            val night = window?.context?.resources?.configuration?.uiMode
                ?.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            bars?.isAppearanceLightStatusBars = !night
            bars?.isAppearanceLightNavigationBars = !night
        }
    }
    val chrome = remember { ViewerChrome() }
    val chromePinned = showDetail || choosingTags || choosingExport || confirmingDelete || operation != null
    val chromeVisible = chrome.visible(chromePinned)
    // §13.1: the system bars go with the chrome, so a tap leaves the media
    // alone on the screen. An edge swipe shows them for a moment without the
    // chrome, and they come back for good when the viewer goes.
    DisposableEffect(window, chromeVisible) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) {
            bars?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
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
        // `ANDROID.md` §16: a preview before full-resolution ranges. The
        // cached thumbnail shows at once, and the sharper still comes last so
        // the detail does not wait for an original to decode.
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
