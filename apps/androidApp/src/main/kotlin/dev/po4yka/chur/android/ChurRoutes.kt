package dev.po4yka.chur.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.app.vault.playbackFor
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
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
        )
        AppRoute.Unlock -> UnlockScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null,
            onUnlock = controller::unlock,
            onUseRecovery = { controller.goTo(AppRoute.Recover) },
            deviceUnlockOffered = controller.deviceUnlockOffered.collectAsState().value,
            onUseDevice = controller::unlockWithDevice,
        )
        AppRoute.Recover -> RecoveryScreen(
            busy = false,
            failed = (vaultState as? VaultState.Locked)?.lastFailure != null,
            onRecover = controller::recover,
            onBack = { controller.goTo(AppRoute.Unlock) },
        )
        AppRoute.Vault -> VaultRoute(controller)
    }
}

@Composable
private fun PublicShell(controller: ChurController, route: AppRoute) {
    val notes by controller.notesState.collectAsState()
    val vaultState by controller.vaultState.collectAsState()
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
        // §2: the route to the vault is a visible settings entry, and it goes
        // to creation or to the gate depending on whether a vault exists.
        onOpenSettings = {
            controller.goTo(
                if (vaultState is VaultState.NoVault) AppRoute.CreateVault else AppRoute.Unlock,
            )
        },
    )
    if (route == AppRoute.PublicSettings) {
        // Reserved for the public shell's own settings; the vault entry above
        // is the only one v1 needs and §2 forbids removing it.
    }
}

@Composable
private fun VaultRoute(controller: ChurController) {
    val page by controller.page.collectAsState()
    val albums by controller.albums.collectAsState()
    val slots by controller.slots.collectAsState()
    val message by controller.message.collectAsState()
    val vaultState by controller.vaultState.collectAsState()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var destination by remember { mutableStateOf(VaultDestination.LIBRARY) }
    var terms by remember { mutableStateOf("") }
    var openAlbum by remember { mutableStateOf<AlbumSummary?>(null) }
    var viewing by remember { mutableStateOf<ObjectProjection?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var creatingAlbum by remember { mutableStateOf(false) }

    val cache = remember { ThumbnailCache() }
    val generation = (vaultState as? VaultState.Unlocked)?.generation ?: 0L
    // §4 of `PLAINTEXT_LIFECYCLE.md`: the decoded cache is cleared on lock, and
    // the session generation is what makes a stale entry unreachable after a
    // new session opens.
    LaunchedEffect(generation) { cache.clear() }

    val importer = remember { ChurImporter(AndroidMediaCodec(context.contentResolver)) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                controller.report("Importing")
                val outcome = withContext(Dispatchers.IO) {
                    importer.import(controller.vault, context.contentResolver, uri)
                }
                controller.reportImport(
                    when (outcome) {
                        is ChurImporter.Outcome.Imported -> null
                        is ChurImporter.Outcome.TooLarge -> outcome.reason
                        ChurImporter.Outcome.Unreadable -> "That file could not be opened."
                        is ChurImporter.Outcome.Refused -> outcome.status
                    },
                )
            }
        }
    }

    LaunchedEffect(destination, openAlbum, page.catalogGeneration) {
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

    if (creatingAlbum) {
        NameDialog(
            title = "New album",
            label = "Album name",
            onConfirm = { name ->
                creatingAlbum = false
                controller.createAlbum(name)
            },
            onDismiss = { creatingAlbum = false },
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
            selectedCount = selection.size,
            deviceSlotAvailable = true,
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
                    viewing = projection
                } else {
                    selection = selection.toggle(projection.id)
                }
            },
            onToggleSelection = { projection ->
                selection = selection.toggle(projection.id)
            },
            onImport = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onSearch = {
                terms = it
                controller.search(it)
            },
            onOpenAlbum = { openAlbum = it },
            onCloseAlbum = { openAlbum = null },
            onCreateAlbum = { creatingAlbum = true },
            onLock = {
                // §8 step 7 clears the decoded cache. The lock does not wait
                // for it, because the cache holds no handle: what matters is
                // that the pixels go, not the order they go in.
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
            onAddDeviceSlot = controller::enrollDeviceSlot,
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

/** A single-field dialog, for an album or a tag name. */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(label) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The object identifiers the selection names, in the page's order. */
private fun selectedObjects(
    page: ObjectPage,
    selection: Set<String>,
): List<ByteArray> = page.objects.filter { it.id in selection }.map { it.objectId }
