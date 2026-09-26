package dev.po4yka.chur.app.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.theme.AlbumsGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LibraryGlyph
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.LockGlyph
import dev.po4yka.chur.app.theme.PlusGlyph
import dev.po4yka.chur.app.theme.SearchGlyph
import dev.po4yka.chur.app.theme.SettingsGlyph
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.SlotSummary
import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SharingMember
import dev.po4yka.chur.ffi.SharingOverview
import dev.po4yka.chur.ffi.SharingPermission
import dev.po4yka.chur.ffi.SharingRecipient
import dev.po4yka.chur.sync.SyncStatus

/**
 * The four vault destinations of `DESIGN.md` §10.1.
 *
 * §10.1 calls the compact set decided and says a fifth destination is a change
 * to that section rather than a product option, so this enum is the section.
 * Import is not one of them: it is the primary floating action on Library and a
 * contextual action inside an open album.
 */
enum class VaultDestination(val label: String) {
    /** The media grid. */
    LIBRARY("Library"),

    /** The albums list. */
    ALBUMS("Albums"),

    /** Catalog search, §16.4 of the catalog schema. */
    SEARCH("Search"),

    /** Slots, auto-lock, and integrity. */
    SETTINGS("Settings"),
}

/** Everything the vault shell renders, gathered so the shell itself is pure. */
data class VaultUiState(
    /** The current destination. */
    val destination: VaultDestination = VaultDestination.LIBRARY,
    /** The tiles of the library or of an open album. */
    val tiles: List<LibraryTile> = emptyList(),
    /** The albums, for the albums destination. */
    val albums: List<AlbumSummary> = emptyList(),
    /** The search text. */
    val searchTerms: String = "",
    /** The key slots, for settings. */
    val slots: List<SlotSummary> = emptyList(),
    /** The album whose contents the library is showing, if any. */
    val openAlbum: AlbumSummary? = null,
    /** The available width, which fixes the grid geometry of §11.1. */
    val widthDp: Int = 400,
    /** A bounded operation message, carrying no private value. */
    val progress: String? = null,
    val operation: ActiveOperation? = null,
    /** Whether this platform can hold a device slot at all. */
    val deviceSlotAvailable: Boolean = false,
    /**
     * The device-slot policy of `KEY_SLOTS.md` §1, or `null` where this
     * platform has no device slot and no row can act on one.
     */
    val deviceSlotStrict: Boolean? = null,
    /** Whether the lock screen also covers the public shell. */
    val appLockEnabled: Boolean = false,
    /** The Android host offers an explicit, foreground desktop session. */
    val deviceControlAvailable: Boolean = false,
    /** How many tiles the selection holds, §11.4. */
    val selectedCount: Int = 0,
    /**
     * The sync engine's state, or `null` when the host bound no engine.
     *
     * A `null` hides the whole section rather than showing an empty one: a
     * surface that offered "Sync now" with no engine behind it would promise
     * something nothing can deliver.
     */
    val sync: SyncStatus? = null,
    val sharingIdentity: SharingIdentity? = null,
    val sharingOverview: SharingOverview? = null,
    val sharingRecipient: SharingRecipient? = null,
)

/** What the shell can ask the application to do. */
data class VaultActions(
    /** Move to a destination. */
    val onDestination: (VaultDestination) -> Unit,
    /** Open one object in the viewer. */
    val onOpen: (ObjectProjection) -> Unit,
    /** Toggle one object's selection. */
    val onToggleSelection: (ObjectProjection) -> Unit,
    /** Start an import. */
    val onImport: () -> Unit,
    /** Change the search text. */
    val onSearch: (String) -> Unit,
    /** Open one album. */
    val onOpenAlbum: (AlbumSummary) -> Unit,
    /** Leave an open album. */
    val onCloseAlbum: () -> Unit,
    /** Create an album. */
    val onCreateAlbum: () -> Unit,
    /** Lock the vault now. */
    val onLock: () -> Unit,
    /**
     * Lock immediately, `DISCREET_MODE.md` "The panic gesture".
     *
     * It is the same security transition as [onLock] and differs only in
     * urgency: no confirmation and no in-flight save. The default runs the
     * ordinary lock, so a host that has not bound it is safe rather than
     * silent.
     */
    val onPanic: () -> Unit = onLock,
    /** Run an integrity scan. */
    val onVerifyAll: () -> Unit,
    /** Add a recovery slot. */
    val onAddRecoverySlot: () -> Unit,
    /** Change the password slot of the open vault. */
    val onChangePassword: (String) -> Unit,
    /** Write a backup package, `BACKUP_FORMAT_V1.md` §7. */
    val onCreateBackup: () -> Unit = {},
    /** Provision a second vault identity, `DECOY_VAULT.md` §3. */
    val onCreateSecondIdentity: () -> Unit = {},
    /** Enroll this device's platform key slot. */
    val onAddDeviceSlot: () -> Unit = {},
    /** Switch the device-slot policy of `KEY_SLOTS.md` §1. */
    val onToggleDeviceSlotPolicy: () -> Unit = {},
    /** Switch between locking the vault and locking the whole app. */
    val onToggleAppLock: () -> Unit = {},
    /** Open the Android-only, user-approved desktop control dialog. */
    val onDeviceControl: () -> Unit = {},
    /** Select every tile the current scope shows. */
    val onSelectAll: () -> Unit = {},
    /** Leave selection mode without acting. */
    val onClearSelection: () -> Unit = {},
    /** Export every selected object. */
    val onExportSelection: () -> Unit = {},
    /** Add the selection to an album, or move it from the open album. */
    val onOrganizeSelection: () -> Unit = {},
    /** Assign or remove a private catalog tag. */
    val onTagSelection: () -> Unit = {},
    /** Remove every selected object from the open album, §11.4. */
    val onRemoveSelectionFromAlbum: () -> Unit = {},
    /** Delete every selected object from this vault, §11.4. */
    val onDeleteSelection: () -> Unit = {},
    /** Stop the native operation at its next cooperative cancellation point. */
    val onCancelOperation: () -> Unit = {},
    /** Connect the vault to the server the user named, `SYNC_PROTOCOL_V1.md` §6. */
    val onConfigureSync: (serverUrl: String, bootstrapSecret: String) -> Unit = { _, _ -> },
    /** Run one sync cycle now. */
    val onSyncNow: () -> Unit = {},
    /** Forget the configured server. */
    val onDisconnectSync: () -> Unit = {},
    val onInspectSharingRecipient: (String) -> Unit = {},
    val onShareWithRecipient: (SharingPermission) -> Unit = {},
    val onRevokeSharingMember: (SharingMember) -> Unit = {},
)

/**
 * The private shell.
 *
 * It is a pure function of [VaultUiState]: nothing here reads a repository, so
 * a screenshot test renders any state without a vault, and a lock transition
 * cannot leave a half-rendered screen behind because there is no state to
 * leave.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultShell(state: VaultUiState, actions: VaultActions) {
    val colors = LocalChurColors.current
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            // §11.4: selection replaces the ordinary top actions rather than
            // adding to them, so nothing here is reachable in both modes.
            if (state.selectedCount > 0) {
                SelectionBar(state, actions)
            } else {
                TopAppBar(
                    title = { Text(state.openAlbum?.name ?: state.destination.label) },
                    navigationIcon = {
                        if (state.openAlbum != null) {
                            IconButton(onClick = actions.onCloseAlbum) {
                                Icon(
                                    dev.po4yka.chur.app.theme.BackGlyph,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                    actions = {
                        // `DISCREET_MODE.md` "The panic gesture": a press locks
                        // and a long press performs the panic transition, on one
                        // control so it is reachable from every private screen
                        // without navigating first. The long press is exposed as
                        // a custom accessibility action, which is the accessible
                        // alternative that section requires.
                        Box(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = actions.onLock,
                                    onLongClick = actions.onPanic,
                                    onLongClickLabel = "Lock immediately",
                                )
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction("Lock immediately") {
                                            actions.onPanic()
                                            true
                                        },
                                    )
                                }
                                .padding(ChurSpacing.three),
                        ) {
                            Icon(LockGlyph, contentDescription = "Lock now")
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                VaultDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = state.destination == destination && state.openAlbum == null,
                        onClick = { actions.onDestination(destination) },
                        icon = { Icon(glyphFor(destination), contentDescription = null) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.accent,
                            selectedTextColor = colors.accent,
                            indicatorColor = colors.accentSoft,
                            unselectedIconColor = colors.inkMuted,
                            unselectedTextColor = colors.inkMuted,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            // §10.1: import is the primary floating action on Library and a
            // contextual action inside an open album, and a destination
            // nowhere. §11.4 replaces the ordinary actions while a selection
            // runs, and the floating action is one of them.
            if (state.operation == null && state.selectedCount == 0 &&
                (state.destination == VaultDestination.LIBRARY || state.openAlbum != null)
            ) {
                FloatingActionButton(onClick = actions.onImport) {
                    Icon(PlusGlyph, contentDescription = "Import")
                }
            } else if (
                state.selectedCount == 0 &&
                state.destination == VaultDestination.ALBUMS &&
                state.openAlbum == null
            ) {
                FloatingActionButton(onClick = actions.onCreateAlbum) {
                    Icon(PlusGlyph, contentDescription = "New album")
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.openAlbum != null || state.destination == VaultDestination.LIBRARY ->
                    LibraryBody(state, actions)
                state.destination == VaultDestination.ALBUMS -> AlbumsBody(state, actions)
                state.destination == VaultDestination.SEARCH -> SearchBody(state, actions)
                else -> SettingsBody(state, actions)
            }
            state.operation?.let { operation ->
                OperationProgressCard(
                    operation = operation,
                    onCancel = actions.onCancelOperation,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(
                        horizontal = ChurSpacing.gutter,
                        vertical = 88.dp,
                    ),
                )
            }
            if (state.operation == null) state.progress?.let { message ->
                // §10 of the FFI contract: progress carries only bounded
                // non-private numbers, so this line never names a file.
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(ChurSpacing.gutter),
                )
            }
        }
    }
}

/** Progress uses only the bounded numeric snapshot published by the FFI. */
@Composable
internal fun OperationProgressCard(
    operation: ActiveOperation,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChurColors.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
        ) {
            Text(operation.description, style = MaterialTheme.typography.bodyMedium)
            val fraction = operation.fraction
            if (fraction == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.surfaceSunken,
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.surfaceSunken,
                )
            }
            if (operation.cancellable) {
                TextButton(onClick = onCancel, enabled = !operation.cancelling) {
                    Text(if (operation.cancelling) "Cancelling…" else "Cancel")
                }
            }
        }
    }
}

/**
 * The selection bar of `DESIGN.md` §11.4.
 *
 * §11.4 fixes both the contents and the wording. The count comes first; the
 * destructive actions name their scope, so "Delete from this vault" and "Remove
 * from album" are two actions and never one ambiguous `Delete`; and the album
 * action appears only where it has a scope to act in.
 *
 * The menu keeps the destructive actions reachable on narrow screens.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SelectionBar(state: VaultUiState, actions: VaultActions) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("${state.selectedCount} selected") },
        navigationIcon = {
            IconButton(onClick = actions.onClearSelection) {
                Icon(dev.po4yka.chur.app.theme.BackGlyph, contentDescription = "Clear selection")
            }
        },
        actions = {
            TextButton(onClick = actions.onExportSelection, enabled = state.operation == null) {
                Text("Export")
            }
            Box {
                TextButton(onClick = { expanded = true }) { Text("More") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        onClick = { expanded = false; actions.onSelectAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.openAlbum == null) "Add to album" else "Move to album") },
                        onClick = { expanded = false; actions.onOrganizeSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text("Tags") },
                        onClick = { expanded = false; actions.onTagSelection() },
                    )
                    if (state.openAlbum != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from album") },
                            onClick = { expanded = false; actions.onRemoveSelectionFromAlbum() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete from this vault") },
                        onClick = { expanded = false; actions.onDeleteSelection() },
                    )
                }
            }
        },
    )
}

private fun glyphFor(destination: VaultDestination) = when (destination) {
    VaultDestination.LIBRARY -> LibraryGlyph
    VaultDestination.ALBUMS -> AlbumsGlyph
    VaultDestination.SEARCH -> SearchGlyph
    VaultDestination.SETTINGS -> SettingsGlyph
}

@Composable
private fun LibraryBody(state: VaultUiState, actions: VaultActions) {
    if (state.tiles.isEmpty()) {
        EmptyLibrary()
    } else {
        MediaGrid(
            tiles = state.tiles,
            widthDp = state.widthDp,
            onOpen = actions.onOpen,
            onToggleSelection = actions.onToggleSelection,
        )
    }
}

@Composable
private fun AlbumsBody(state: VaultUiState, actions: VaultActions) {
    val colors = LocalChurColors.current
    if (state.albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No albums yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Group objects you want to find together.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(ChurSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
    ) {
        items(state.albums, key = { it.id }) { album ->
            Card(onClick = { actions.onOpenAlbum(album) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(ChurSpacing.three)) {
                    Text(
                        album.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${album.memberCount} item${if (album.memberCount == 1L) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBody(state: VaultUiState, actions: VaultActions) {
    val colors = LocalChurColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchTerms,
            onValueChange = actions.onSearch,
            singleLine = true,
            label = { Text("Search filenames, captions, and tags") },
            modifier = Modifier.fillMaxWidth().padding(ChurSpacing.gutter),
            colors = churOutlinedTextFieldColors(),
        )
        when {
            state.searchTerms.isBlank() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // §16.4 of the catalog: the index covers exactly these
                    // three, so the copy says so rather than implying more.
                    "Search covers filenames, captions, and tag names.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
            state.tiles.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("No results", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            }
            else -> MediaGrid(
                tiles = state.tiles,
                widthDp = state.widthDp,
                onOpen = actions.onOpen,
                onToggleSelection = actions.onToggleSelection,
            )
        }
    }
}

@Composable
private fun SettingsBody(state: VaultUiState, actions: VaultActions) {
    val colors = LocalChurColors.current
    var changingPassword by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(ChurSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
    ) {
        item {
            Text("Access", style = MaterialTheme.typography.titleMedium)
        }
        items(state.slots, key = { it.id }) { slot ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(ChurSpacing.three)) {
                    Text(slot.familyName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // §10 of KEY_SLOTS: portability is what decides whether
                        // a slot survives a lost device, so it is the fact the
                        // row states.
                        if (slot.portable) {
                            "Portable: survives losing this device"
                        } else {
                            "This device only"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                    )
                }
            }
        }
        item {
            SettingsAction(
                "Change password or PIN",
                { changingPassword = true },
                enabled = state.operation == null,
            )
        }
        item {
            SettingsAction("Add a recovery phrase", actions.onAddRecoverySlot)
        }
        // §4 of KEY_SLOTS makes the device unlock code a vault credential in
        // the convenient mode, so the label says which factor it enrolls
        // rather than promising a stronger one.
        if (state.deviceSlotAvailable) {
            item {
                SettingsAction("Unlock with this device's screen lock", actions.onAddDeviceSlot)
            }
            state.deviceSlotStrict?.let { strict ->
                item {
                    // `KEY_SLOTS.md` §1: the policy is a per-vault setting shown
                    // at device-slot creation, and strict is the only
                    // configuration that resists an adversary who knows the
                    // device unlock code. The label names what toggling does.
                    SettingsAction(
                        if (strict) {
                            "Allow the device screen lock to unlock"
                        } else {
                            "Require biometrics only to unlock"
                        },
                        actions.onToggleDeviceSlotPolicy,
                    )
                }
            }
        }
        item {
            SettingsAction(
                if (state.appLockEnabled) "Lock vault only" else "Lock whole app",
                actions.onToggleAppLock,
            )
        }
        item {
            Text(
                "Whole-app lock also hides public Notes until you unlock. " +
                    "Public Notes remain unencrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = ChurSpacing.three),
            )
        }
        item {
            Text("Backup", style = MaterialTheme.typography.titleMedium)
        }
        if (state.deviceControlAvailable) {
            item {
                SettingsAction("Control from computer", actions.onDeviceControl, enabled = state.operation == null)
            }
        }
        item {
            SettingsAction("Write a backup file", actions.onCreateBackup, enabled = state.operation == null)
        }
        item {
            // `BACKUP_FORMAT_V1.md` §12: rotating a password does not revoke an
            // old package, and a user who does not know that keeps a file that
            // an old credential still opens. §9 is the other half: the outer
            // package reveals its size and its creation time to anyone holding
            // it, whatever it is stored on.
            Text(
                "A backup file is opened by the password or recovery phrase it " +
                    "was written with. Changing either does not close an old file.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = ChurSpacing.three),
            )
        }
        item {
            Text("Integrity", style = MaterialTheme.typography.titleMedium)
        }
        item {
            SettingsAction("Verify every object", actions.onVerifyAll, enabled = state.operation == null)
        }
        state.sync?.let { sync ->
            item {
                Text("Sync", style = MaterialTheme.typography.titleMedium)
            }
            if (!sync.configured) {
                item {
                    SyncSetupCard(onConfigure = actions.onConfigureSync)
                }
            } else {
                item {
                    SettingsAction("Sync now", actions.onSyncNow)
                }
                item {
                    SharingCard(state, actions)
                }
                sync.message?.let { message ->
                    item {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                            modifier = Modifier.padding(horizontal = ChurSpacing.three),
                        )
                    }
                }
                item {
                    Text(
                        // The address is the one fact of the configuration a
                        // user needs to see; the token this hides is a bearer
                        // credential and never reaches a surface.
                        "Connected to ${sync.serverUrl}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(horizontal = ChurSpacing.three),
                    )
                }
                item {
                    SettingsAction("Stop using the server", actions.onDisconnectSync)
                }
            }
        }
        // The entry is always here, and never conditional on whether a second
        // identity already exists. `DECOY_VAULT.md` §10 forbids a setting that
        // differs according to whether a sibling is present: an entry that
        // disappeared once the registry was full would answer, from inside
        // either identity, the one question the design refuses to answer. An
        // attempt on a full registry reports a bounded message instead.
        item {
            Text("Another vault", style = MaterialTheme.typography.titleMedium)
        }
        item {
            SettingsAction("Set up a second vault", actions.onCreateSecondIdentity)
        }
        item {
            // `DECOY_VAULT.md` §3 requires the §10 limitation to be stated
            // before the identity is created, and §10 fixes what may be
            // claimed: the feature is public by design, so a coercer may know a
            // second credential can exist before demanding anything.
            Text(
                "A second vault has its own password, its own recovery, and " +
                    "nothing in common with this one. This feature is described " +
                    "in the store listing, so someone demanding your password may " +
                    "already know a second vault can exist.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = ChurSpacing.three),
            )
        }
    }
    if (changingPassword) {
        ChangePasswordDialog(
            enabled = state.operation == null,
            onChange = {
                actions.onChangePassword(it)
                changingPassword = false
            },
            onDismiss = { changingPassword = false },
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    enabled: Boolean,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var usePin by remember { mutableStateOf(false) }
    val matching = password.isNotEmpty() && password == confirmation &&
        (!usePin || isValidVaultPin(password))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password or PIN") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
            ) {
                Text(
                    "Old backup files still accept the password or PIN used when they were written. " +
                        "Write a new backup and delete old copies if you need to retire it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        usePin = !usePin
                        password = ""
                        confirmation = ""
                    },
                    enabled = enabled,
                ) {
                    Text(if (usePin) "Use a password instead" else "Use a PIN instead")
                }
                if (usePin) {
                    Text("Choose 12–20 digits.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        if (!usePin || (it.length <= 20 && it.all { digit -> digit in '0'..'9' })) {
                            password = it
                        }
                    },
                    singleLine = true,
                    enabled = enabled,
                    label = { Text(if (usePin) "New PIN" else "New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (usePin) KeyboardType.NumberPassword else KeyboardType.Password,
                    ),
                    colors = churOutlinedTextFieldColors(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        if (!usePin || (it.length <= 20 && it.all { digit -> digit in '0'..'9' })) {
                            confirmation = it
                        }
                    },
                    singleLine = true,
                    enabled = enabled,
                    label = { Text(if (usePin) "Repeat PIN" else "Repeat password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (usePin) KeyboardType.NumberPassword else KeyboardType.Password,
                    ),
                    isError = confirmation.isNotEmpty() && !matching,
                    colors = churOutlinedTextFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChange(password) }, enabled = enabled && matching) {
                Text("Change")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.padding(ChurSpacing.three))
    }
}
