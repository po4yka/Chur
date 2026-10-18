@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import kotlin.native.ref.WeakReference
import platform.CoreGraphics.CGRectMake
import platform.darwin.NSObject
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIAccessibilityCustomAction
import platform.UIKit.UIAction
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonItemStyle.UIBarButtonItemStylePlain
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIContextMenuInteraction
import platform.UIKit.UIContextMenuInteractionAppearanceUnknown
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIImage
import platform.UIKit.UILongPressGestureRecognizer
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementAttributesDisabled
import platform.UIKit.UINavigationBar
import platform.UIKit.UINavigationItem
import platform.UIKit.UITabBar
import platform.UIKit.UITabBarDelegateProtocol
import platform.UIKit.UITabBarItem
import platform.UIKit.UIView
import platform.UIKit.accessibilityCustomActions
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.accessibilityLabel
import platform.UIKit.interactions

/**
 * A session-scoped UIKit presenter, without a second navigation stack.
 *
 * Standard UIKit bars adopt the system's Liquid Glass on iOS 26+. We leave
 * their appearance to UIKit, including Reduce Transparency and older iOS.
 */
internal class IosVaultChrome : NSObject(), UITabBarDelegateProtocol {
    internal val navigationBar = UINavigationBar()
    internal val tabBar = UITabBar()
    private val navigationItem = UINavigationItem()
    private var actions: VaultActions? = null
    private var key: ChromeKey? = null
    private var navigationBlocked = true
    private var lockRequested = false
    private val lock = button("Lock now", "lock")
    private val primary = UIBarButtonItem(title = "", style = UIBarButtonItemStylePlain,
        target = this, action = NSSelectorFromString("performPrimary"))
    private val secondary = UIBarButtonItem(title = "", menu = null)
    private val back = button("Back", "chevron.backward")
    private var primaryCommand: ((VaultActions) -> Unit)? = null
    private var backCommand: ((VaultActions) -> Unit)? = null
    private var menuEpoch = 0L
    private val menuCommands = mutableListOf<Command>()

    init {
        navigationBar.translucent = true
        tabBar.translucent = true
        tabBar.delegate = this
        lock.addTarget(this, NSSelectorFromString("lockNow"), UIControlEventTouchUpInside)
        lock.addGestureRecognizer(UILongPressGestureRecognizer(this, NSSelectorFromString("panic:")))
        // Objective-C blocks must not retain the Kotlin presenter that owns them.
        val owner = WeakReference(this)
        lock.accessibilityCustomActions = listOf(
            UIAccessibilityCustomAction(name = "Lock immediately", actionHandler = {
                owner.get()?.let { it.panicNow(); true } ?: false
            }),
        )
        back.addTarget(this, NSSelectorFromString("goBack"), UIControlEventTouchUpInside)
        clear()
    }

    fun attach(view: UIView) {
        view.addSubview(navigationBar)
        view.addSubview(tabBar)
        layout()
    }

    fun layout() {
        val view = navigationBar.superview ?: return
        val width = view.bounds.useContents { size.width }
        val height = view.bounds.useContents { size.height }
        val top = view.safeAreaInsets.useContents { top }
        val bottom = view.safeAreaInsets.useContents { bottom }
        navigationBar.setFrame(CGRectMake(0.0, top, width, 44.0))
        tabBar.setFrame(CGRectMake(0.0, height - 49.0 - bottom, width, 49.0 + bottom))
        if (key != null) {
            view.bringSubviewToFront(navigationBar)
            view.bringSubviewToFront(tabBar)
        }
    }

    /** Only mounting a new Compose vault shell can end a previous lock request. */
    fun beginSession() { clear(); lockRequested = false }

    fun update(state: VaultUiState, actions: VaultActions, accent: Color, navigationBusy: Boolean, covered: Boolean = false) {
        if (lockRequested) return
        navigationBlocked = covered || navigationBusy
        if (navigationBlocked) dismissMenus(navigationBar)
        navigationItem.leftBarButtonItem?.enabled = !navigationBlocked
        back.enabled = !navigationBlocked
        primary.enabled = !navigationBlocked && (state.operation == null ||
            (state.destination == VaultDestination.ALBUMS && state.openAlbum == null && state.selectedCount == 0))
        secondary.enabled = !navigationBlocked
        tabBar.userInteractionEnabled = !navigationBlocked
        tabBar.items?.filterIsInstance<UITabBarItem>()?.forEach { it.enabled = !navigationBlocked }
        navigationBar.hidden = covered
        tabBar.hidden = covered
        navigationBar.accessibilityElementsHidden = covered
        tabBar.accessibilityElementsHidden = covered
        this.actions = actions
        val next = ChromeKey(
            state.destination, state.openAlbum?.id,
            state.openAlbum?.name ?: state.libraryScopeTitle ?: state.destination.label,
            state.selectedCount, state.operation != null, state.trashOpen,
            state.libraryScopeTitle != null, state.recentlyAdded, actions.onOpenPrivateNotes != null, state.tiles.isNotEmpty(),
        )
        val tint = UIColor(red = accent.red.toDouble(), green = accent.green.toDouble(), blue = accent.blue.toDouble(), alpha = 1.0)
        navigationBar.tintColor = tint
        tabBar.tintColor = tint
        if (key == next) {
            // UIKit selects before its delegate runs; shared state is authoritative
            // even when a navigation request was rejected while becoming busy.
            tabBar.selectedItem = tabBar.items?.filterIsInstance<UITabBarItem>()
                ?.getOrNull(state.destination.ordinal)
            return
        }
        retireMenu()
        key = next
        navigationItem.title = if (state.selectedCount > 0) "${state.selectedCount} selected" else next.title
        backCommand = when {
            state.selectedCount > 0 -> { a -> a.onClearSelection() }
            state.openAlbum != null -> { a -> a.onCloseAlbum() }
            state.libraryScopeTitle != null -> { a -> a.onShowAllMedia() }
            else -> null
        }
        back.accessibilityLabel = if (state.selectedCount > 0) "Clear selection" else "Back"
        navigationItem.leftBarButtonItem = if (backCommand != null) {
            UIBarButtonItem(customView = back).also { it.enabled = !navigationBlocked }
        } else null
        primary.menu = null
        secondary.menu = null
        primaryCommand = null
        val right = mutableListOf(UIBarButtonItem(customView = lock))
        val openAlbum = state.openAlbum
        val menu = when {
            state.selectedCount > 0 -> selectionMenu(state)
            openAlbum != null -> listOf(
                Command("Unencrypted folder", !next.busy) { it.onExportAlbum(openAlbum) },
                Command("Encrypted archive…", !next.busy) { it.onEncryptedExportAlbum(openAlbum) },
            )
            state.destination == VaultDestination.LIBRARY -> browseMenu(state)
            else -> emptyList()
        }
        if (menu.isNotEmpty()) {
            secondary.title = when {
                state.selectedCount > 0 -> "More"
                state.openAlbum != null -> "Export album"
                else -> "Browse"
            }
            secondary.menu = nativeMenu(menu)
            right += secondary
        }
        val primaryTitle = when {
            state.selectedCount > 0 && !state.trashOpen -> "Export"
            !next.busy && next.hasMedia && state.selectedCount == 0 && state.openAlbum != null -> "Import"
            !next.busy && next.hasMedia && state.selectedCount == 0 && state.destination == VaultDestination.LIBRARY &&
                (state.libraryScopeTitle == null || state.recentlyAdded) -> "Import"
            state.selectedCount == 0 && state.destination == VaultDestination.ALBUMS && state.openAlbum == null -> "New album"
            else -> null
        }
        if (primaryTitle != null) {
            primary.title = primaryTitle
            primaryCommand = when (primaryTitle) {
                "Export" -> { a -> a.onExportSelection() }
                "New album" -> { a -> a.onCreateAlbum() }
                else -> { a -> a.onImport() }
            }
            right += primary
        }
        navigationItem.rightBarButtonItems = right
        navigationBar.setItems(listOf(navigationItem), animated = false)
        val tabs = VaultDestination.entries.map { destination ->
            UITabBarItem(destination.label, UIImage.systemImageNamed(destination.symbol()), destination.ordinal.toLong())
                .also { it.enabled = !navigationBlocked }
        }
        tabBar.setItems(tabs, animated = false)
        tabBar.selectedItem = tabs[state.destination.ordinal]
        layout()
    }

    /** Called before lock commands and when the Compose vault subtree leaves. */
    fun clear() {
        actions = null
        navigationBlocked = true
        retireMenu()
        dismissMenus(navigationBar)
        key = null
        primaryCommand = null
        backCommand = null
        primary.menu = null
        secondary.menu = null
        primary.title = null
        secondary.title = null
        navigationItem.title = null
        navigationItem.leftBarButtonItem = null
        navigationItem.rightBarButtonItems = null
        navigationBar.setItems(emptyList<UINavigationItem>(), animated = false)
        tabBar.setItems(emptyList<UITabBarItem>(), animated = false)
        navigationBar.hidden = true
        tabBar.hidden = true
        navigationBar.accessibilityElementsHidden = true
        tabBar.accessibilityElementsHidden = true
    }

    override fun tabBar(tabBar: UITabBar, didSelectItem: UITabBarItem) {
        if (navigationBlocked) return
        VaultDestination.entries.getOrNull(didSelectItem.tag.toInt())?.let { actions?.onDestination?.invoke(it) }
    }

    @ObjCAction fun lockNow() { val lock = actions?.onLock; clear(); lockRequested = true; lock?.invoke() }
    @ObjCAction fun panic(recognizer: UILongPressGestureRecognizer) {
        if (recognizer.state == UIGestureRecognizerStateBegan) panicNow()
    }
    private fun panicNow() { val panic = actions?.onPanic; clear(); lockRequested = true; panic?.invoke() }
    @ObjCAction fun performPrimary() { if (!navigationBlocked) actions?.let { primaryCommand?.invoke(it) } }
    @ObjCAction fun goBack() { if (!navigationBlocked) actions?.let { backCommand?.invoke(it) } }

    fun hasVisibleMenu(): Boolean = menuInteractions(navigationBar).any {
        it.menuAppearance != UIContextMenuInteractionAppearanceUnknown
    }

    private fun menuInteractions(view: UIView): List<UIContextMenuInteraction> =
        view.interactions.filterIsInstance<UIContextMenuInteraction>() +
            view.subviews.filterIsInstance<UIView>().flatMap(::menuInteractions)

    private fun dismissMenus(view: UIView) {
        menuInteractions(view).forEach { it.dismissMenu() }
    }

    private fun retireMenu() {
        // UIKit keeps immutable menu/action copies, including after dismissal.
        // Their labels must stay generic; private projections live only here.
        // Copies retain only a weak owner and index/epoch, so retiring the epoch
        // and commands releases private data and fences every retained callback.
        menuEpoch++
        menuCommands.clear()
    }

    private fun nativeMenu(commands: List<Command>): UIMenu {
        menuCommands.addAll(commands)
        val epoch = menuEpoch
        val owner = WeakReference(this)
        val escape = UIAction.actionWithTitle("Lock and hide", image = null, identifier = null) { _ ->
            val presenter = owner.get()
            // Privacy remains available while ordinary navigation is blocked.
            if (presenter != null && epoch == presenter.menuEpoch) presenter.panicNow()
        }
        val native = listOf(escape) + commands.mapIndexed { index, command ->
            UIAction.actionWithTitle(command.title, image = null, identifier = null) { _ ->
                val presenter = owner.get()
                if (presenter != null && epoch == presenter.menuEpoch && !presenter.navigationBlocked) {
                    val current = presenter.menuCommands.getOrNull(index)
                    if (current?.enabled == true) presenter.actions?.let(current.run)
                }
            }.also { if (!command.enabled) it.attributes = UIMenuElementAttributesDisabled }
        }
        return UIMenu.menuWithTitle("", children = native)
    }

    private fun browseMenu(state: VaultUiState): List<Command> = buildList {
        add(Command("All media") { it.onShowAllMedia() })
        add(Command("Recently added") { it.onShowRecentlyAdded() })
        if (actions?.onOpenPrivateNotes != null) add(Command("Private notes") { it.onOpenPrivateNotes?.invoke() })
        add(Command("Favorites") { it.onShowFavorites() })
        add(Command("Tags…") { it.onShowTags() })
        add(Command("Trash") { it.onShowTrash() })
        add(Command("Quarantine") { it.onShowQuarantine() })
        if (state.trashOpen) {
            add(Command("Restore all") { it.onRestoreTrash() })
            add(Command("Empty trash") { it.onEmptyTrash() })
        }
    }

    private fun selectionMenu(state: VaultUiState): List<Command> = buildList {
        add(Command("Select loaded items") { it.onSelectLoadedItems() })
        if (!state.trashOpen) {
            add(Command("Encrypted archive…", state.operation == null) { it.onEncryptedExportSelection() })
            add(Command("Add to album") { it.onAddSelectionToAlbum() })
            if (state.openAlbum != null) add(Command("Move to album") { it.onMoveSelectionToAlbum() })
            add(Command("Tags") { it.onTagSelection() })
            add(Command("Add to favorites") { it.onSetSelectionFavorite(true) })
            add(Command("Remove from favorites") { it.onSetSelectionFavorite(false) })
            if (state.openAlbum != null) add(Command("Remove from album") { it.onRemoveSelectionFromAlbum() })
        } else add(Command("Restore") { it.onRestoreSelection() })
        add(Command(if (state.trashOpen) "Delete permanently" else "Move to Trash") { it.onDeleteSelection() })
    }

    private data class Command(val title: String, val enabled: Boolean = true, val run: (VaultActions) -> Unit)
    private data class ChromeKey(val destination: VaultDestination, val album: String?, val title: String,
        val selection: Int, val busy: Boolean, val trash: Boolean, val scoped: Boolean,
        val recent: Boolean, val notes: Boolean, val hasMedia: Boolean)
}

private fun button(title: String, symbol: String?): UIButton = UIButton.buttonWithType(UIButtonTypeSystem).apply {
    if (symbol != null) setImage(UIImage.systemImageNamed(symbol), UIControlStateNormal)
    else setTitle(title, UIControlStateNormal)
    accessibilityLabel = title
    setFrame(CGRectMake(0.0, 0.0, if (symbol == null) 88.0 else 44.0, 44.0))
}

private fun VaultDestination.symbol(): String = when (this) {
    VaultDestination.LIBRARY -> "photo.on.rectangle"
    VaultDestination.ALBUMS -> "rectangle.stack"
    VaultDestination.SEARCH -> "magnifyingglass"
    VaultDestination.SETTINGS -> "gearshape"
}
