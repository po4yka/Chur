@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
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
import platform.UIKit.UITabBarController
import platform.UIKit.UITabBarControllerDelegateProtocol
import platform.UIKit.UINavigationController
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import platform.UIKit.UITabBarItem
import platform.UIKit.UIView
import platform.UIKit.accessibilityCustomActions
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.accessibilityLabel
import platform.UIKit.interactions
import platform.UIKit.tabBarItem
import platform.UIKit.hidesBottomBarWhenPushed
import platform.UIKit.endEditing
import platform.UIKit.navigationItem

/** Stable presentation identities; paging within a Viewer does not push another controller. */
internal sealed interface IosVaultScreen {
    data class Root(val destination: VaultDestination) : IosVaultScreen
    data class Album(val id: String) : IosVaultScreen
    data class Viewer(val token: Long) : IosVaultScreen
}

/** UIKit owns transitions; the shared route remains the authoritative stack. */
internal class IosVaultChrome : NSObject(), UITabBarControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    internal val controller = UITabBarController()
    internal val navigationControllers: List<UINavigationController> = VaultDestination.entries.map { UINavigationController() }
    private val selectedNavigation: UINavigationController
        get() = navigationControllers.getOrElse(controller.selectedIndex.toInt()) { navigationControllers.first() }
    internal val navigationBar: UINavigationBar get() = selectedNavigation.navigationBar
    internal val tabBar: UITabBar get() = controller.tabBar
    private val navigationItem: UINavigationItem? get() = selectedNavigation.topViewController?.navigationItem
    private data class Frame(val screen: IosVaultScreen, val content: UIViewController)
    private val stacks = mutableMapOf<UINavigationController, List<Frame>>()
    private val retiring = mutableMapOf<UINavigationController, List<Frame>>()
    private var screenFactory: ((IosVaultScreen) -> UIViewController)? = null
    private var popCallback: ((IosVaultScreen) -> Unit)? = null
    private var retireCallback: ((IosVaultScreen) -> Unit)? = null
    private var generation = 0L
    private var reconciling = false
    private val transitioning = mutableSetOf<UINavigationController>()
    internal val transitionInFlight: Boolean get() = transitioning.isNotEmpty()
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
        controller.delegate = this
        navigationControllers.forEach { it.delegate = this; it.navigationBar.translucent = true }
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

    /**
     * A verified, freshly mounted shared owner starts a presentation visit.
     * Repository generation can stay unchanged across a public file workflow;
     * ordinary queued reconciliation still cannot release a clear/lock latch.
     */
    fun beginPresentation(generation: Long) {
        require(generation != 0L)
        clear()
        this.generation = generation
        lockRequested = false
    }

    fun configureScreens(
        factory: (IosVaultScreen) -> UIViewController,
        onPop: (IosVaultScreen) -> Unit,
        onRetire: (IosVaultScreen) -> Unit,
    ) {
        screenFactory = factory
        popCallback = onPop
        retireCallback = onRetire
    }

    /** Project shared navigation without allowing a retired visit to reopen itself. */
    fun reconcile(generation: Long, screens: List<IosVaultScreen>, destination: VaultDestination, animated: Boolean = true) {
        if (generation == 0L || screenFactory == null) return
        if (this.generation != generation) {
            // Configuration belongs to the new owner and survives retirement of old frames.
            retireStacks()
            this.generation = generation
            lockRequested = false
        }
        if (lockRequested) return
        require(screens.firstOrNull() == IosVaultScreen.Root(destination))
        reconciling = true
        try {
            navigationControllers.forEachIndexed { index, nav ->
                val tab = VaultDestination.entries[index]
                val desired = if (tab == destination) screens else listOf(IosVaultScreen.Root(tab))
                val old = stacks[nav].orEmpty()
                if (old.map { it.screen } != desired) {
                    controller.view.endEditing(true)
                    val candidates = old + retiring[nav].orEmpty()
                    val next = desired.map { screen -> candidates.firstOrNull { it.screen == screen }
                        ?: Frame(screen, checkNotNull(screenFactory).invoke(screen)).also {
                            it.content.hidesBottomBarWhenPushed = screen is IosVaultScreen.Viewer
                        }
                    }
                    val animate = animated && old.isNotEmpty() && tab == destination && controller.view.window != null
                    val removed = old.filter { it !in next }
                    val pending = retiring[nav].orEmpty().filter { it !in next }
                    if (animate) retiring[nav] = (pending + removed).distinct()
                    else {
                        (pending + removed).distinct().forEach(::retire)
                        retiring.remove(nav)
                    }
                    stacks[nav] = next
                    nav.setViewControllers(next.map { it.content }, animated = animate)
                    key = null
                }
                nav.tabBarItem = UITabBarItem(tab.label, UIImage.systemImageNamed(tab.symbol()), index.toLong())
            }
            if (controller.viewControllers.isNullOrEmpty()) controller.setViewControllers(navigationControllers, animated = false)
            if (controller.selectedIndex != destination.ordinal.toULong()) controller.view.endEditing(true)
            controller.selectedIndex = destination.ordinal.toULong()
            controller.view.hidden = false
            controller.view.accessibilityElementsHidden = false
            controller.view.userInteractionEnabled = true
            reconcileVisibility()
        } finally { reconciling = false }
    }

    private fun retire(frame: Frame) {
        // Retire the Compose slot before releasing UIKit's reference to it.
        retireCallback?.invoke(frame.screen)
        frame.content.view.endEditing(true)
        frame.content.view.hidden = true
        frame.content.view.accessibilityElementsHidden = true
        frame.content.navigationItem.title = null
        frame.content.navigationItem.leftBarButtonItem = null
        frame.content.navigationItem.rightBarButtonItems = null
    }

    private fun retireStacks() {
        (stacks.values.flatten() + retiring.values.flatten()).distinct().forEach(::retire)
        stacks.clear()
        retiring.clear()
        transitioning.clear()
        navigationControllers.forEach {
            it.delegate = null
            it.setViewControllers(emptyList<UIViewController>(), animated = false)
            it.delegate = this
        }
        controller.setViewControllers(null, animated = false)
    }

    private fun reconcileVisibility() {
        val selected = selectedNavigation
        stacks.forEach { (nav, frames) -> frames.forEach { frame ->
            frame.content.view.accessibilityElementsHidden = nav != selected || frame != frames.lastOrNull()
        } }
        val viewer = stacks[selected]?.lastOrNull()?.screen is IosVaultScreen.Viewer
        // UIKit's willShow controls bar interpolation during a native transition.
        // Ordinary content updates must not snap a Viewer pop back to hidden.
        if (selected !in transitioning || covered) selected.setNavigationBarHidden(viewer || covered, animated = false)
        if (selected !in transitioning || covered) tabBar.hidden = viewer || covered
        navigationBar.accessibilityElementsHidden = viewer || covered
        tabBar.accessibilityElementsHidden = viewer || covered
        navigationControllers.forEach { nav ->
            val canPop = !navigationBlocked && !covered && !selectionBlocksPop
            nav.interactivePopGestureRecognizer?.enabled = canPop
            if ((UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) >= 26) {
                // ViewerPager owns horizontal drags across the media body.
                // UIKit owns only its edge there; album lists retain the
                // system's full-width interactive Back on iOS 26 and later.
                val viewerTop = stacks[nav]?.lastOrNull()?.screen is IosVaultScreen.Viewer
                nav.interactiveContentPopGestureRecognizer?.enabled = canPop && !viewerTop
            }
        }
    }

    private var covered = false
    private var selectionBlocksPop = false

    /** Freeze ordinary input while the shared owner registers its next query. */
    fun suspendNavigation() {
        navigationBlocked = true
        back.enabled = false
        primary.enabled = false
        secondary.enabled = false
        navigationItem?.setHidesBackButton(true, animated = false)
        navigationItem?.leftBarButtonItem?.enabled = false
        tabBar.userInteractionEnabled = false
        tabBar.items?.filterIsInstance<UITabBarItem>()?.forEach { it.enabled = false }
        navigationControllers.forEach { dismissMenus(it.navigationBar) }
        // Reuse the ordinary busy policy without replacing the displayed
        // title, selected destination or callbacks; Lock remains available.
        reconcileVisibility()
    }

    fun update(state: VaultUiState, actions: VaultActions, accent: Color, navigationBusy: Boolean, covered: Boolean = false) {
        if (lockRequested) return
        this.covered = covered
        selectionBlocksPop = state.selectedCount > 0
        navigationBlocked = covered || navigationBusy
        if (navigationBlocked) dismissMenus(navigationBar)
        navigationItem?.setHidesBackButton(navigationBlocked || selectionBlocksPop, animated = false)
        navigationItem?.leftBarButtonItem?.enabled = !navigationBlocked
        back.enabled = !navigationBlocked
        primary.enabled = !navigationBlocked && (state.operation == null ||
            (state.destination == VaultDestination.ALBUMS && state.openAlbum == null && state.selectedCount == 0))
        secondary.enabled = !navigationBlocked
        tabBar.userInteractionEnabled = !navigationBlocked
        tabBar.items?.filterIsInstance<UITabBarItem>()?.forEach { it.enabled = !navigationBlocked }
        reconcileVisibility()
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
            controller.selectedIndex = state.destination.ordinal.toULong()
            return
        }
        retireMenu()
        key = next
        navigationItem?.title = if (state.selectedCount > 0) "${state.selectedCount} selected" else next.title
        backCommand = when {
            state.selectedCount > 0 -> { a -> a.onClearSelection() }
            state.libraryScopeTitle != null -> { a -> a.onShowAllMedia() }
            else -> null
        }
        back.accessibilityLabel = if (state.selectedCount > 0) "Clear selection" else "Back"
        navigationItem?.leftBarButtonItem = if (backCommand != null) {
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
        navigationItem?.rightBarButtonItems = right
        controller.selectedIndex = state.destination.ordinal.toULong()
        reconcileVisibility()
    }

    /** Called before lock commands and when the Compose vault subtree leaves. */
    fun clear() {
        // Conceal first: transition snapshots and presentation callbacks may still
        // exist in UIKit until the next run-loop, but can no longer be displayed.
        controller.view.hidden = true
        controller.view.accessibilityElementsHidden = true
        controller.view.userInteractionEnabled = false
        lockRequested = true
        actions = null
        navigationBlocked = true
        retireMenu()
        navigationControllers.forEach { dismissMenus(it.navigationBar) }
        key = null
        primaryCommand = null
        backCommand = null
        primary.menu = null
        secondary.menu = null
        primary.title = null
        secondary.title = null
        navigationItem?.title = null
        navigationItem?.leftBarButtonItem = null
        navigationItem?.rightBarButtonItems = null
        controller.view.hidden = true
        controller.view.accessibilityElementsHidden = true
        controller.view.userInteractionEnabled = false
        var presented = controller.presentedViewController
        while (presented != null) {
            presented.view.hidden = true
            presented.view.accessibilityElementsHidden = true
            presented = presented.presentedViewController
        }
        controller.dismissViewControllerAnimated(false, completion = null)
        retireStacks()
        screenFactory = null
        popCallback = null
        retireCallback = null
        // Keep UINavigationController's own visibility state coherent. Directly
        // hiding only its bar leaves navigationBarHidden=false; a fresh visit's
        // setNavigationBarHidden(false) can then be a no-op and strand the bar.
        navigationControllers.forEach { it.setNavigationBarHidden(true, animated = false) }
        tabBar.hidden = true
        navigationBar.accessibilityElementsHidden = true
        tabBar.accessibilityElementsHidden = true
    }

    override fun tabBarController(tabBarController: UITabBarController, shouldSelectViewController: UIViewController): Boolean {
        if (!navigationBlocked && !lockRequested && !transitionInFlight) {
            val index = navigationControllers.indexOf(shouldSelectViewController)
            VaultDestination.entries.getOrNull(index)?.let { actions?.onDestination?.invoke(it) }
        }
        // Shared state chooses the tab, including rejection while busy.
        return false
    }

    @ObjCSignatureOverride
    override fun navigationController(navigationController: UINavigationController, willShowViewController: UIViewController, animated: Boolean) {
        if (lockRequested || generation == 0L || navigationController !in stacks) return
        val frame = stacks[navigationController]?.firstOrNull { it.content == willShowViewController } ?: return
        if (animated) {
            transitioning += navigationController
            // The navigation controller animates its hidesBottomBarWhenPushed
            // geometry; do not leave our settled Viewer concealment on top.
            if (!covered) tabBar.hidden = false
        }
        navigationController.setNavigationBarHidden(frame.screen is IosVaultScreen.Viewer || covered, animated)
    }

    @ObjCSignatureOverride
    override fun navigationController(navigationController: UINavigationController, didShowViewController: UIViewController, animated: Boolean) {
        if (lockRequested || generation == 0L || navigationController.topViewController != didShowViewController) return
        val frames = stacks[navigationController] ?: return
        val index = frames.indexOfFirst { it.content == didShowViewController }
        // A delegate completion from a retired generation cannot finish a new
        // transition or retire its pending frames on the reused native stack.
        if (index < 0) return
        transitioning -= navigationController
        if (reconciling) return
        retiring.remove(navigationController)?.forEach(::retire)
        // Cancelled interactive pops show the original top. A completed native
        // pop alone commits shared state; willShow never changes navigation.
        if (index == frames.lastIndex) { reconcileVisibility(); return }
        if (navigationBlocked || selectionBlocksPop) {
            reconciling = true
            try { navigationController.setViewControllers(frames.map { it.content }, animated = false) }
            finally { reconciling = false }
            reconcileVisibility()
            return
        }
        val target = frames[index].screen
        controller.view.endEditing(true)
        frames.drop(index + 1).forEach(::retire)
        stacks[navigationController] = frames.take(index + 1)
        key = null
        popCallback?.invoke(target)
        reconcileVisibility()
    }

    fun requestPanic() { panicNow() }

    @ObjCAction fun lockNow() { val lock = actions?.onLock; clear(); lockRequested = true; lock?.invoke() }
    @ObjCAction fun panic(recognizer: UILongPressGestureRecognizer) {
        if (recognizer.state == UIGestureRecognizerStateBegan) panicNow()
    }
    private fun panicNow() { val panic = actions?.onPanic; clear(); lockRequested = true; panic?.invoke() }
    @ObjCAction fun performPrimary() { if (!navigationBlocked && !transitionInFlight) actions?.let { primaryCommand?.invoke(it) } }
    @ObjCAction fun goBack() { if (!navigationBlocked && !transitionInFlight) actions?.let { backCommand?.invoke(it) } }

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
                if (presenter != null && epoch == presenter.menuEpoch && !presenter.navigationBlocked && !presenter.transitionInFlight) {
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
