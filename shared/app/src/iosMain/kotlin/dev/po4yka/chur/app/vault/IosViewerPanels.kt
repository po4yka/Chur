package dev.po4yka.chur.app.vault

/** One media projection within a stable native Viewer visit. */
internal data class IosViewerPanelState(
    val generation: Long,
    val token: Long,
    val itemId: String,
    val title: String,
    val favorite: Boolean,
    val trashed: Boolean,
    val busy: Boolean,
    val visible: Boolean,
    val infoVisible: Boolean = false,
)

/** Shared scene guards remain authoritative even for queued UIKit callbacks. */
internal data class IosViewerPanelActions(
    val onBack: () -> Unit,
    val onTouch: () -> Unit,
    val onFavorite: () -> Unit,
    val onInfo: () -> Unit,
    val onExport: () -> Unit,
    val onTags: () -> Unit,
    val onDelete: () -> Unit,
    val onRestore: () -> Unit,
)
