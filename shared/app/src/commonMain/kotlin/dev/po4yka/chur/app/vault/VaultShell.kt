package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.po4yka.chur.app.theme.AlbumsGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LibraryGlyph
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.LockGlyph
import dev.po4yka.chur.app.theme.PlusGlyph
import dev.po4yka.chur.app.theme.SearchGlyph
import dev.po4yka.chur.app.theme.SettingsGlyph
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.SlotSummary

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
    /** Whether this platform can hold a device slot at all. */
    val deviceSlotAvailable: Boolean = false,
    /** How many tiles the selection holds, §11.4. */
    val selectedCount: Int = 0,
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
    /** Run an integrity scan. */
    val onVerifyAll: () -> Unit,
    /** Add a recovery slot. */
    val onAddRecoverySlot: () -> Unit,
    /** Enroll this device's platform key slot. */
    val onAddDeviceSlot: () -> Unit = {},
    /** Select every tile the current scope shows. */
    val onSelectAll: () -> Unit = {},
    /** Leave selection mode without acting. */
    val onClearSelection: () -> Unit = {},
    /** Export every selected object. */
    val onExportSelection: () -> Unit = {},
    /** Remove every selected object from the open album, §11.4. */
    val onRemoveSelectionFromAlbum: () -> Unit = {},
    /** Delete every selected object from this vault, §11.4. */
    val onDeleteSelection: () -> Unit = {},
)

/**
 * The private shell.
 *
 * It is a pure function of [VaultUiState]: nothing here reads a repository, so
 * a screenshot test renders any state without a vault, and a lock transition
 * cannot leave a half-rendered screen behind because there is no state to
 * leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                        IconButton(onClick = actions.onLock) {
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
                    )
                }
            }
        },
        floatingActionButton = {
            // §10.1: import is the primary floating action on Library and a
            // contextual action inside an open album, and a destination
            // nowhere. §11.4 replaces the ordinary actions while a selection
            // runs, and the floating action is one of them.
            if (state.selectedCount == 0 && state.destination == VaultDestination.LIBRARY) {
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
            state.progress?.let { message ->
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

/**
 * The selection bar of `DESIGN.md` §11.4.
 *
 * §11.4 fixes both the contents and the wording. The count comes first; the
 * destructive actions name their scope, so "Delete from this vault" and "Remove
 * from album" are two actions and never one ambiguous `Delete`; and the album
 * action appears only where it has a scope to act in.
 *
 * "More" and "move to album" are not here. They are the two entries of §11.4
 * that need a picker this shell does not have yet, and an action that opens
 * nothing would be worse than one that is absent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(state: VaultUiState, actions: VaultActions) {
    TopAppBar(
        title = { Text("${state.selectedCount} selected") },
        navigationIcon = {
            IconButton(onClick = actions.onClearSelection) {
                Icon(dev.po4yka.chur.app.theme.BackGlyph, contentDescription = "Clear selection")
            }
        },
        actions = {
            TextButton(onClick = actions.onSelectAll) { Text("Select all") }
            TextButton(onClick = actions.onExportSelection) { Text("Export") }
            if (state.openAlbum != null) {
                TextButton(onClick = actions.onRemoveSelectionFromAlbum) {
                    Text("Remove from album")
                }
            }
            TextButton(onClick = actions.onDeleteSelection) { Text("Delete from this vault") }
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
            SettingsAction("Add a recovery phrase", actions.onAddRecoverySlot)
        }
        // §4 of KEY_SLOTS makes the device unlock code a vault credential in
        // the convenient mode, so the label says which factor it enrolls
        // rather than promising a stronger one.
        if (state.deviceSlotAvailable) {
            item {
                SettingsAction("Unlock with this device's screen lock", actions.onAddDeviceSlot)
            }
        }
        item {
            Text("Integrity", style = MaterialTheme.typography.titleMedium)
        }
        item {
            SettingsAction("Verify every object", actions.onVerifyAll)
        }
    }
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.padding(ChurSpacing.three))
    }
}
