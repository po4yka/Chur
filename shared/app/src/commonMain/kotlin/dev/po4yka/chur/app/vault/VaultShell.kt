package dev.po4yka.chur.app.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.AutoLock
import dev.po4yka.chur.app.Notice
import dev.po4yka.chur.app.privateKeyboardOptions
import dev.po4yka.chur.app.secretKeyboardOptions
import dev.po4yka.chur.app.syncCopy
import dev.po4yka.chur.app.theme.AlbumsGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.DestructiveButton
import dev.po4yka.chur.app.theme.IntegrityGlyph
import dev.po4yka.chur.app.theme.LibraryGlyph
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.LockGlyph
import dev.po4yka.chur.app.theme.PlusGlyph
import dev.po4yka.chur.app.theme.SearchGlyph
import dev.po4yka.chur.app.theme.SettingsGlyph
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.QuerySort
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
    /** Active library scope, if narrower than all media. */
    val libraryScopeTitle: String? = null,
    val trashOpen: Boolean = false,
    /** Session-only presentation choices; never written outside the unlocked vault. */
    val mediaView: ContentView = ContentView.GRID,
    val albumView: ContentView = ContentView.LIST,
    val sort: QuerySort = QuerySort.CAPTURE_DESC,
    val kinds: Int = 0,
    val albumOrder: AlbumOrder = AlbumOrder.MANUAL,
    val albumFilter: String = "",
    /** The outcome of the last action, shown once as a snackbar, or a security notice until dismissed. */
    val notice: Notice? = null,
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
    /** How long an idle vault stays open, `DESIGN.md` §14.4. */
    val autoLock: AutoLock = AutoLock.DEFAULT,
    /** The Android host offers an explicit, foreground desktop session. */
    val deviceControlAvailable: Boolean = false,
    /** How many tiles the selection holds, §11.4. */
    val selectedCount: Int = 0,
    val canLoadMore: Boolean = false,
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
    /**
     * The system security patch line, `ANDROID.md` §25.1, or `null` where the
     * host reads no patch level and the row is hidden.
     */
    val systemSecurity: String? = null,
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
    val onShowAllMedia: () -> Unit = {},
    val onShowFavorites: () -> Unit = {},
    val onShowTags: () -> Unit = {},
    val onShowTrash: () -> Unit = {},
    val onShowQuarantine: () -> Unit = {},
    val onEmptyTrash: () -> Unit = {},
    val onRestoreTrash: () -> Unit = {},
    val onRenameAlbum: (AlbumSummary, String) -> Unit = { _, _ -> },
    val onDeleteAlbum: (AlbumSummary) -> Unit = {},
    val onMoveAlbum: (AlbumSummary, AlbumSummary?, AlbumSummary?) -> Unit = { _, _, _ -> },
    val onMoveAlbumMember: (ObjectProjection, ObjectProjection?) -> Unit = { _, _ -> },
    val onDropMediaIntoAlbum: (List<ObjectProjection>, AlbumSummary?) -> Unit = { _, _ -> },
    val onLoadMore: () -> Unit = {},
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
    /** Compare the system patch level with the published bulletin, ADR-0059. */
    val onCheckSystemSecurity: () -> Unit = {},
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
    /** Choose the auto-lock delay of `DESIGN.md` §14.4. */
    val onSetAutoLock: (AutoLock) -> Unit = {},
    /** Open the Android-only, user-approved desktop control dialog. */
    val onDeviceControl: () -> Unit = {},
    /** Select every tile the current scope shows. */
    val onSelectAll: () -> Unit = {},
    /** Leave selection mode without acting. */
    val onClearSelection: () -> Unit = {},
    /** Export every selected object. */
    val onExportSelection: () -> Unit = {},
    /** Add the selection while retaining existing memberships. */
    val onAddSelectionToAlbum: () -> Unit = {},
    /** Move the selection out of the open album. */
    val onMoveSelectionToAlbum: () -> Unit = {},
    /** Assign or remove a private catalog tag. */
    val onTagSelection: () -> Unit = {},
    val onSetSelectionFavorite: (Boolean) -> Unit = {},
    /** Remove every selected object from the open album, §11.4. */
    val onRemoveSelectionFromAlbum: () -> Unit = {},
    /** Move or permanently delete the selection, according to the current scope. */
    val onDeleteSelection: () -> Unit = {},
    val onRestoreSelection: () -> Unit = {},
    val onMediaViewChange: (ContentView) -> Unit = {},
    val onAlbumViewChange: (ContentView) -> Unit = {},
    val onSortChange: (QuerySort) -> Unit = {},
    val onKindsChange: (Int) -> Unit = {},
    val onAlbumOrderChange: (AlbumOrder) -> Unit = {},
    val onAlbumFilterChange: (String) -> Unit = {},
    /** Stop the native operation at its next cooperative cancellation point. */
    val onCancelOperation: () -> Unit = {},
    /**
     * The snackbar showed [VaultUiState.notice] with this id, or left a
     * routine one, or Settings showed what the notice pointed to.
     */
    val onNoticeShown: (Long) -> Unit = {},
    /** Connect the vault to the server the user named, `SYNC_PROTOCOL_V1.md` §6. */
    val onConfigureSync: (serverUrl: String, bootstrapSecret: String) -> Unit = { _, _ -> },
    /** Run one sync cycle now. */
    val onSyncNow: () -> Unit = {},
    /** Forget the configured server. */
    val onDisconnectSync: () -> Unit = {},
    /** Record that the user saw the sync stop, `ROLLBACK_PROTECTION.md` §4. */
    val onAcknowledgeSyncStop: () -> Unit = {},
    val onInspectSharingRecipient: (String) -> Unit = {},
    val onShareWithRecipient: (SharingPermission) -> Unit = {},
    val onRevokeSharingMember: (SharingMember) -> Unit = {},
)

/**
 * The step system Back takes in the shell, or `null` at its root.
 *
 * It is the step a visible control already takes - the selection bar's close,
 * the top bar's Back, the Library tab - innermost first, so a press never skips
 * a level the user can see. The Library root returns `null`: Back there belongs
 * to the platform, which goes home, and the background lock of `ANDROID.md`
 * §19.1 follows as before. `DESIGN.md` §22.1 has navigation respect the
 * platform's back behavior, and §25.4 lets the gesture differ per platform.
 */
internal fun VaultUiState.backStep(actions: VaultActions): (() -> Unit)? = when {
    selectedCount > 0 -> actions.onClearSelection
    openAlbum != null -> actions.onCloseAlbum
    libraryScopeTitle != null -> actions.onShowAllMedia
    destination != VaultDestination.LIBRARY -> {
        { actions.onDestination(VaultDestination.LIBRARY) }
    }
    else -> null
}

/**
 * The private shell.
 *
 * It is a pure function of [VaultUiState]: nothing here reads a repository, so
 * a screenshot test renders any state without a vault, and a lock transition
 * cannot leave a half-rendered screen behind because there is no state to
 * leave.
 *
 * What Back does is decided here, by [backStep]. The dispatcher that delivers
 * it is [systemBack]. This module compiles against neither `activity-compose`
 * nor a common back handler, so the Android host passes its `BackHandler`, and
 * iOS, which has no system Back, passes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultShell(
    state: VaultUiState,
    actions: VaultActions,
    // No default: a host that forgot it would compile, pass every test, and
    // send Back home, where the background lock closes the vault.
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
) {
    val colors = LocalChurColors.current
    val back = state.backStep(actions)
    systemBack(back != null) { back?.invoke() }
    // §26: a notice that only sends the user to Settings has said its part
    // once Settings is on screen, where the banner says the same and stays.
    val notice = state.notice
    val settingsShown = state.openAlbum == null && state.destination == VaultDestination.SETTINGS
    LaunchedEffect(notice?.id, settingsShown) {
        if (settingsShown && notice?.pointsToSettings == true) actions.onNoticeShown(notice.id)
    }
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            // §11.4: selection replaces the ordinary top actions rather than
            // adding to them, so only the lock control is in both modes.
            if (state.selectedCount > 0) {
                SelectionBar(state, actions)
            } else {
                TopAppBar(
                    title = { Text(state.openAlbum?.name ?: state.libraryScopeTitle ?: state.destination.label) },
                    navigationIcon = {
                        if (state.openAlbum != null || state.libraryScopeTitle != null) {
                            IconButton(onClick = {
                                if (state.openAlbum != null) actions.onCloseAlbum() else actions.onShowAllMedia()
                            }) {
                                Icon(
                                    dev.po4yka.chur.app.theme.BackGlyph,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                    actions = {
                        if (state.destination == VaultDestination.LIBRARY && state.openAlbum == null) {
                            var scopesExpanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { scopesExpanded = true }) { Text("Browse") }
                                DropdownMenu(expanded = scopesExpanded,
                                    onDismissRequest = { scopesExpanded = false }) {
                                    DropdownMenuItem(text = { Text("All media") }, onClick = {
                                        scopesExpanded = false; actions.onShowAllMedia()
                                    })
                                    DropdownMenuItem(text = { Text("Favorites") }, onClick = {
                                        scopesExpanded = false; actions.onShowFavorites()
                                    })
                                    DropdownMenuItem(text = { Text("Tags…") }, onClick = {
                                        scopesExpanded = false; actions.onShowTags()
                                    })
                                    DropdownMenuItem(text = { Text("Trash") }, onClick = {
                                        scopesExpanded = false; actions.onShowTrash()
                                    })
                                    DropdownMenuItem(text = { Text("Quarantine") }, onClick = {
                                        scopesExpanded = false; actions.onShowQuarantine()
                                    })
                                    if (state.trashOpen) DropdownMenuItem(
                                        text = { Text("Restore all") },
                                        onClick = { scopesExpanded = false; actions.onRestoreTrash() },
                                    )
                                    if (state.trashOpen) DropdownMenuItem(
                                        text = { Text("Empty trash") },
                                        onClick = { scopesExpanded = false; actions.onEmptyTrash() },
                                    )
                                }
                            }
                        }
                        LockControl(onLock = actions.onLock, onPanic = actions.onPanic)
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
        // §26: the outcome of an action is a snackbar, which the scaffold
        // places above the floating action and the navigation bar.
        snackbarHost = { NoticeHost(notice, actions.onNoticeShown) },
        floatingActionButton = {
            // §10.1: import is the primary floating action on Library and a
            // contextual action inside an open album, and a destination
            // nowhere. §11.4 replaces the ordinary actions while a selection
            // runs, and the floating action is one of them.
            if (state.operation == null && state.selectedCount == 0 &&
                ((state.destination == VaultDestination.LIBRARY && state.libraryScopeTitle == null) ||
                    state.openAlbum != null)
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
                    LibraryBody(state, actions, state.mediaView, actions.onMediaViewChange)
                state.destination == VaultDestination.ALBUMS ->
                    AlbumOrganizer(state.albums, actions, state.albumView, actions.onAlbumViewChange,
                        state.albumOrder, actions.onAlbumOrderChange,
                        state.albumFilter, actions.onAlbumFilterChange)
                state.destination == VaultDestination.SEARCH ->
                    SearchBody(state, actions, state.mediaView, actions.onMediaViewChange)
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
        }
    }
}

/**
 * Shows [notice] once, as the snackbar of `DESIGN.md` §26.
 *
 * A routine confirmation times out, and a security notice stays until it is
 * dismissed or its action is taken. Material's host marks the snackbar a
 * polite live region, so a screen reader announces the outcome (§23.2), and
 * it lengthens the timeout to the platform's accessibility setting. Its
 * container is the inverse surface, which `ChurTheme` maps to `ink`, the
 * `surface-inverse` of the `DESIGN.md` `components.snackbar` token, and the
 * shape is that token's `md` radius of 10dp.
 *
 * [onShown] runs when the snackbar ends, so a notice shows once. A routine
 * notice is also marked shown when this host leaves the screen or is handed
 * no notice, so it never comes back. A security notice is not: it ends only
 * with a dismiss or its action, so it moves from the shell to the viewer and
 * back while the user looks at an item, where it used to be dropped.
 */
@Composable
internal fun NoticeHost(notice: Notice?, onShown: (Long) -> Unit, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(notice?.id) {
        val shown = notice ?: return@LaunchedEffect
        var ended = false
        try {
            val result = host.showSnackbar(
                message = shown.text,
                actionLabel = shown.action?.first,
                withDismissAction = shown.security,
                duration = if (shown.security) SnackbarDuration.Indefinite else SnackbarDuration.Short,
            )
            ended = true
            if (result == SnackbarResult.ActionPerformed) shown.action?.second?.invoke()
        } finally {
            if (ended || !shown.security) onShown(shown.id)
        }
    }
    SnackbarHost(host, modifier) { Snackbar(it, shape = RoundedCornerShape(10.dp)) }
}

/**
 * Progress uses only the bounded numeric snapshot published by the FFI.
 *
 * A screen reader hears [ActiveOperation.spoken] from a polite live region,
 * `DESIGN.md` §23.2. The byte count on screen is kept out of the semantics:
 * it changes on every poll, and each change would be spoken.
 */
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
            Text(
                operation.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = operation.spoken
                    liveRegion = LiveRegionMode.Polite
                },
            )
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
 * The lock control, `DISCREET_MODE.md` "The panic gesture".
 *
 * A press locks and a long press performs the panic transition, on one
 * control, and every private screen draws it in its own chrome so the
 * gesture is reachable without navigating first. That is why it is one
 * composable rather than a part of the shell's top bar: the viewer draws
 * its own chrome over the media, and a screen without the control is a
 * screen the section's property does not hold on.
 *
 * The long press is exposed as a custom accessibility action, which is the
 * accessible alternative that section requires. It is not wrapped in a
 * tooltip, because a tooltip's long-press trigger would take the gesture
 * the panic is bound to.
 */
@Composable
internal fun LockControl(
    onLock: () -> Unit,
    onPanic: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    Box(
        modifier = Modifier
            .combinedClickable(
                onClick = onLock,
                onLongClick = onPanic,
                onLongClickLabel = "Lock immediately",
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Lock immediately") {
                        onPanic()
                        true
                    },
                )
            }
            .padding(ChurSpacing.three),
    ) {
        Icon(LockGlyph, contentDescription = "Lock now", tint = tint)
    }
}

/**
 * The selection bar of `DESIGN.md` §11.4.
 *
 * §11.4 fixes both the contents and the wording. The count comes first; the
 * destructive actions name their scope, so "Move to Trash" and "Remove
 * from album" are two actions and never one ambiguous `Delete`; and the album
 * action appears only where it has a scope to act in.
 *
 * The menu keeps the destructive actions reachable on narrow screens.
 *
 * The bar replaces the shell's top bar, so it draws [LockControl] itself:
 * `DISCREET_MODE.md` "The panic gesture" holds on every private screen, and
 * a selection that asked for "Clear selection" before the panic would put a
 * step in front of it.
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
            if (!state.trashOpen) TextButton(onClick = actions.onExportSelection,
                enabled = state.operation == null) { Text("Export") }
            Box {
                TextButton(onClick = { expanded = true }) { Text("More") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        onClick = { expanded = false; actions.onSelectAll() },
                    )
                    if (!state.trashOpen) DropdownMenuItem(
                        text = { Text("Add to album") },
                        onClick = { expanded = false; actions.onAddSelectionToAlbum() },
                    )
                    if (state.openAlbum != null && !state.trashOpen) {
                        DropdownMenuItem(
                            text = { Text("Move to album") },
                            onClick = { expanded = false; actions.onMoveSelectionToAlbum() },
                        )
                    }
                    if (!state.trashOpen) DropdownMenuItem(
                        text = { Text("Tags") },
                        onClick = { expanded = false; actions.onTagSelection() },
                    )
                    if (!state.trashOpen) DropdownMenuItem(text = { Text("Add to favorites") }, onClick = {
                        expanded = false; actions.onSetSelectionFavorite(true)
                    })
                    if (!state.trashOpen) DropdownMenuItem(text = { Text("Remove from favorites") }, onClick = {
                        expanded = false; actions.onSetSelectionFavorite(false)
                    })
                    if (state.openAlbum != null && !state.trashOpen) {
                        DropdownMenuItem(
                            text = { Text("Remove from album") },
                            onClick = { expanded = false; actions.onRemoveSelectionFromAlbum() },
                        )
                    }
                    if (state.trashOpen) DropdownMenuItem(
                        text = { Text("Restore") },
                        onClick = { expanded = false; actions.onRestoreSelection() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.trashOpen) "Delete permanently" else "Move to Trash") },
                        onClick = { expanded = false; actions.onDeleteSelection() },
                    )
                }
            }
            LockControl(onLock = actions.onLock, onPanic = actions.onPanic)
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
private fun LibraryBody(state: VaultUiState, actions: VaultActions,
    view: ContentView, onViewChange: (ContentView) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ContentViewToggle(view, onViewChange)
        if (state.selectedCount == 0) MediaSortFilterControls(
            sort = state.sort, kinds = state.kinds,
            album = state.openAlbum != null, trash = state.trashOpen,
            onSort = actions.onSortChange, onKinds = actions.onKindsChange,
        )
        if (state.tiles.isEmpty()) {
            if (state.libraryScopeTitle == null && state.kinds == 0) EmptyLibrary(
                onImport = actions.onImport.takeIf { state.operation == null },
                modifier = Modifier.weight(1f),
            )
            else Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.kinds != 0) "No media matches these filters" else
                    "No media in ${state.libraryScopeTitle}",
                    color = LocalChurColors.current.inkMuted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            MediaBrowser(
                tiles = state.tiles,
                view = view,
                onOpen = actions.onOpen,
                onToggleSelection = actions.onToggleSelection,
                onMove = if (state.openAlbum != null && state.sort == QuerySort.ALBUM_MANUAL &&
                    state.kinds == 0 && state.selectedCount == 0) actions.onMoveAlbumMember else null,
                albumTargets = state.albums.filterNot { it.id == state.openAlbum?.id },
                onDropIntoAlbum = if (state.trashOpen) null else actions.onDropMediaIntoAlbum,
                onLoadMore = if (state.canLoadMore) actions.onLoadMore else null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchBody(state: VaultUiState, actions: VaultActions,
    view: ContentView, onViewChange: (ContentView) -> Unit) {
    val colors = LocalChurColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchTerms,
            onValueChange = actions.onSearch,
            singleLine = true,
            label = { Text("Search filenames, captions, and tags") },
            keyboardOptions = privateKeyboardOptions(),
            modifier = Modifier.fillMaxWidth().padding(ChurSpacing.gutter),
            colors = churOutlinedTextFieldColors(),
        )
        ContentViewToggle(view, onViewChange)
        if (state.selectedCount == 0) MediaSortFilterControls(
            sort = state.sort, kinds = state.kinds, album = false, trash = false,
            onSort = actions.onSortChange, onKinds = actions.onKindsChange,
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
                Text("No matching items. Try another word or remove a filter.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            }
            else -> MediaBrowser(
                tiles = state.tiles,
                view = view,
                onOpen = actions.onOpen,
                onToggleSelection = actions.onToggleSelection,
                albumTargets = state.albums,
                onDropIntoAlbum = actions.onDropMediaIntoAlbum,
                onLoadMore = if (state.canLoadMore) actions.onLoadMore else null,
            )
        }
    }
}

@Composable
private fun SettingsBody(state: VaultUiState, actions: VaultActions) {
    val colors = LocalChurColors.current
    var changingPassword by remember { mutableStateOf(false) }
    var confirmingDisconnect by remember { mutableStateOf(false) }
    var askingRecovery by remember { mutableStateOf(false) }
    val hasRecovery = state.slots.any { it.slotType == 4 }
    var choosingAutoLock by remember { mutableStateOf(false) }
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
            // `RECOVERY.md` §8: a confirmed phrase replaces the one before it,
            // so once a Recovery slot exists the row says so. Either way the
            // row opens the explanation of `DESIGN.md` §17.1 step 1, and the
            // device authentication of step 2 follows its confirm. The phrase
            // screen takes the place of the vault route, and a running import
            // ends with that route, so the row waits for the operation as the
            // other actions do.
            SettingsAction(
                if (hasRecovery) "Replace recovery phrase" else "Set up a recovery phrase",
                { askingRecovery = true },
                enabled = state.operation == null,
            )
        }
        // §4 of KEY_SLOTS makes the device unlock code a vault credential in
        // the convenient mode, so the label says which factor it enrolls
        // rather than promising a stronger one. The strict policy enrolls
        // biometry only, so the label then names biometrics, not a screen
        // lock the slot does not accept.
        if (state.deviceSlotAvailable) {
            item {
                SettingsAction(
                    if (state.deviceSlotStrict == true) {
                        "Unlock with this device's biometrics"
                    } else {
                        "Unlock with this device's screen lock"
                    },
                    actions.onAddDeviceSlot,
                )
            }
            state.deviceSlotStrict?.let { strict ->
                item {
                    // `KEY_SLOTS.md` §1: the policy is a per-vault setting, and
                    // strict is the only configuration that resists an
                    // adversary who knows the device unlock code, so the row
                    // shows which one is in force and what it means. The
                    // controller re-enrolls an existing slot under the new
                    // policy (`ANDROID.md` §9.3) and publishes the policy that
                    // survives, so after a cancelled prompt the switch still
                    // shows the policy in force.
                    SettingsSwitch(
                        "Biometrics only",
                        if (strict) {
                            "Unlocking with this device needs your biometrics. " +
                                "Your screen lock can't open the vault."
                        } else {
                            "Unlocking with this device also accepts your screen lock, " +
                                "so anyone who knows it can open the vault."
                        },
                        checked = strict,
                        onToggle = actions.onToggleDeviceSlotPolicy,
                        enabled = state.operation == null,
                    )
                }
            }
        }
        item {
            // `DESIGN.md` §14.4: the row shows the choice in force, and the
            // dialog it opens explains what the timer counts.
            ListItem(
                headlineContent = { Text("Auto-lock") },
                supportingContent = { Text(state.autoLock.label) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(role = Role.Button) { choosingAutoLock = true },
            )
        }
        item {
            SettingsSwitch(
                "Lock whole app",
                "Also hides public Notes until you unlock. " +
                    "Public Notes remain unencrypted on this device.",
                checked = state.appLockEnabled,
                onToggle = actions.onToggleAppLock,
            )
        }
        item {
            Text("Backup", style = MaterialTheme.typography.titleMedium)
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
        state.systemSecurity?.let { line ->
            item {
                // `ANDROID.md` §25.1: information only. The row never blocks an
                // action, and it reads the same in every identity because it
                // describes the device, not the vault. The tap is the only
                // trigger for the request that ADR-0059 permits.
                ListItem(
                    headlineContent = { Text("System security update") },
                    supportingContent = { Text(line) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button, onClick = actions.onCheckSystemSecurity),
                )
            }
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
                if (sync.integrityStop != null) {
                    item {
                        SyncStoppedBanner(
                            acknowledged = sync.integrityAcknowledged,
                            onStop = { confirmingDisconnect = true },
                            onAcknowledge = actions.onAcknowledgeSyncStop,
                        )
                    }
                }
                item {
                    // A second run while one is in progress would only queue
                    // behind the engine's lock, so the row waits for it.
                    SettingsAction("Sync now", actions.onSyncNow, enabled = !sync.busy)
                }
                item {
                    SharingCard(state, actions)
                }
                // The engine reports a stop as a status, and the copy for it
                // is this layer's, `ERROR_MODEL.md` "Layer mapping".
                (sync.failure?.let(::syncCopy) ?: sync.message)?.let { message ->
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
                    SettingsAction("Stop using the server", { confirmingDisconnect = true })
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
            // The creation form takes the place of the vault route, and a
            // running import ends with that route, so the row waits for the
            // operation as the other actions do.
            SettingsAction(
                "Set up a second vault",
                actions.onCreateSecondIdentity,
                enabled = state.operation == null,
            )
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
        // A desktop session is not a backup, so it has a heading of its own.
        if (state.deviceControlAvailable) {
            item {
                Text("Advanced", style = MaterialTheme.typography.titleMedium)
            }
            item {
                SettingsAction("Control from computer", actions.onDeviceControl, enabled = state.operation == null)
            }
        }
    }
    if (confirmingDisconnect) {
        // `DESIGN.md` §26: a dialog for a short, high-consequence decision.
        // The disconnect forgets this device's transport token at once, and
        // that cannot be taken back, so the confirm is destructive. §27: the
        // copy says only what is true. Nothing on this device is deleted, and
        // it promises nothing about connecting again.
        AlertDialog(
            onDismissRequest = { confirmingDisconnect = false },
            title = { Text("Stop syncing with this server?") },
            text = { Text("This device stops sending and receiving changes. Nothing is deleted from this device.") },
            confirmButton = {
                DestructiveButton(onClick = {
                    confirmingDisconnect = false
                    actions.onDisconnectSync()
                }) { Text("Stop syncing") }
            },
            dismissButton = { TextButton(onClick = { confirmingDisconnect = false }) { Text("Cancel") } },
        )
    }
    if (askingRecovery) {
        // `DESIGN.md` §17.1 step 1, before the prompt of step 2, for a first
        // phrase and a replacement alike: what the phrase opens, and what no
        // phrase brings back, `RECOVERY.md` §4. Media does not sync, so a
        // lost device without a backup file is the loss to name. §8 there,
        // last step: backups keep the phrase they were written under, so a
        // replacement says the old phrase still opens them. Nothing changes
        // until the new words are typed back, so the confirm is not
        // destructive.
        AlertDialog(
            onDismissRequest = { askingRecovery = false },
            title = { Text(if (hasRecovery) "Replace recovery phrase?" else "Set up a recovery phrase?") },
            text = {
                Text(
                    "A recovery phrase is 24 words that open this vault if you forget your password or " +
                        "PIN. It can't bring back items: if this device is lost and you have no backup " +
                        "file, they are gone. " +
                        (
                            if (hasRecovery) {
                                "After you confirm the new words, the current phrase stops opening this " +
                                    "vault. Backup files you wrote earlier still open with the current phrase. "
                            } else {
                                ""
                            }
                        ) +
                        "Next, this device asks you to confirm it's you.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingRecovery = false
                        actions.onAddRecoverySlot()
                    },
                    enabled = state.operation == null,
                ) { Text(if (hasRecovery) "Replace" else "Continue") }
            },
            dismissButton = { TextButton(onClick = { askingRecovery = false }) { Text("Cancel") } },
        )
    }
    if (choosingAutoLock) {
        AutoLockDialog(
            current = state.autoLock,
            onChoose = {
                choosingAutoLock = false
                actions.onSetAutoLock(it)
            },
            onDismiss = { choosingAutoLock = false },
        )
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

/**
 * The single choice of `DESIGN.md` §14.4, with the explanation it asks for.
 *
 * The idle clock counts vault reads and writes while Chur is open, not
 * attention. A leased read, which a photo on screen or a playing video makes,
 * does not refresh it; a running import or export does until it ends. Leaving
 * the application locks at once whatever the choice, `PLAINTEXT_LIFECYCLE.md`
 * §7, and that lock stops playback. A tap applies the choice, so the dialog
 * has no confirm button. The selected radio is the accent, not Material
 * primary, §25.2, and the unselected one `inkMuted`, as [SettingsSwitch]
 * explains.
 */
@Composable
private fun AutoLockDialog(current: AutoLock, onChoose: (AutoLock) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalChurColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-lock") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
            ) {
                Text(
                    "The vault locks after this long without activity while Chur is open. " +
                        "Looking at one photo or playing a video is not activity, so the vault " +
                        "can lock during either. An import or export keeps it open until it ends. " +
                        "Leaving Chur always locks the vault at once and stops playback.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    AutoLock.entries.forEach { choice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ChurSpacing.two),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = choice == current, role = Role.RadioButton) {
                                    onChoose(choice)
                                },
                        ) {
                            RadioButton(
                                selected = choice == current,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.accent,
                                    unselectedColor = colors.inkMuted,
                                ),
                            )
                            Text(choice.label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                    // Next moves to the repeat field, as on the creation form.
                    keyboardOptions = secretKeyboardOptions(pin = usePin, imeAction = ImeAction.Next),
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
                    keyboardOptions = secretKeyboardOptions(pin = usePin),
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

/**
 * The `DESIGN.md` §20.2 banner for a fork or rollback verdict on one device.
 *
 * `ROLLBACK_PROTECTION.md` §4 has the user told, and its exits, reconciliation
 * and revoking the device, have no surface here yet. What the user can do is
 * stop using the server that delivered that history, so the action is the
 * disconnect, behind its own confirmation. The copy names no status and no
 * cryptography, §20.2, and the banner stays for as long as the engine reports
 * the verdict, as §26 keeps a security failure on screen. It is a polite live
 * region, so a reader hears it when a run on this screen finds the verdict.
 *
 * §4's `acknowledged` state is the secondary action: the chain stays frozen,
 * so the banner stays too and only its sentence changes, and an unlock stops
 * announcing it.
 */
@Composable
private fun SyncStoppedBanner(acknowledged: Boolean, onStop: () -> Unit, onAcknowledge: () -> Unit) {
    val colors = LocalChurColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.errorSoft, contentColor = colors.ink),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.padding(ChurSpacing.three),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ChurSpacing.two),
            ) {
                Icon(IntegrityGlyph, contentDescription = null, tint = colors.error)
                Text("Sync stopped for one device", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                if (acknowledged) {
                    "You acknowledged this. Chur still does not apply changes from that device."
                } else {
                    "Changes from that device do not match what this device already accepted, " +
                        "so Chur does not apply them."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onStop) { Text("Stop using the server") }
            if (!acknowledged) {
                TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
            }
        }
    }
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.padding(ChurSpacing.three))
    }
}

/**
 * A setting that is on or off, named by what it is rather than by what a tap
 * does, so the row reads the same whichever state it is in.
 *
 * The whole row toggles, with the switch role, so a screen reader hears the
 * name and the state. The switch's shape carries the state as well as its
 * color, `DESIGN.md` §23.5, and its checked track is the accent, not Material
 * primary, §25.2. The row is transparent, as the `settings-row` token says.
 *
 * Off is the default for both rows, so the unchecked thumb and border are
 * `inkMuted`, as the unchecked Checkbox is. Material's defaults map them to
 * `outline`, the hairline token (`#DCDCD8` light, `#2A2A2A` dark), which is
 * about 1.2:1 against the `surfaceSunken` track (`#EEEEEB`) and 1.3:1 against
 * the canvas. `inkMuted` (`#5C5C58` light, `#A3A39D` dark) is about 5.8:1 on
 * the light track and 6.6:1 on the dark `surfaceRaised` track (`#1D1D1D`),
 * which meets the 3:1 of §6.4 and WCAG 2.2 SC 1.4.11.
 */
@Composable
private fun SettingsSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalChurColors.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.accent,
                    checkedThumbColor = colors.onInk,
                    uncheckedThumbColor = colors.inkMuted,
                    uncheckedBorderColor = colors.inkMuted,
                ),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) { onToggle() },
    )
}
