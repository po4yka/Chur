@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import dev.po4yka.chur.app.IosAdaptiveBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.objcPtr
import kotlin.native.ref.WeakReference
import platform.CoreGraphics.CGRectMake
import platform.darwin.NSObject
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIAccessibilityCustomAction
import platform.UIKit.UIAction
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonSystemItem.UIBarButtonSystemItemFlexibleSpace
import platform.UIKit.toolbarItems
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.separatorColor
import platform.UIKit.UIContextMenuInteraction
import platform.UIKit.UIContextMenuInteractionAppearanceUnknown
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIImage
import platform.UIKit.UISearchBar
import platform.UIKit.UISearchBarDelegateProtocol
import platform.UIKit.UISearchController
import platform.UIKit.UISearchResultsUpdatingProtocol
import platform.UIKit.UITextAutocapitalizationType.UITextAutocapitalizationTypeNone
import platform.UIKit.UITextAutocorrectionType.UITextAutocorrectionTypeNo
import platform.UIKit.UITextSpellCheckingType.UITextSpellCheckingTypeNo
import platform.UIKit.UITextInlinePredictionType.UITextInlinePredictionTypeNo
import platform.UIKit.UITextSmartQuotesType.UITextSmartQuotesTypeNo
import platform.UIKit.UITextSmartDashesType.UITextSmartDashesTypeNo
import platform.UIKit.UITextSmartInsertDeleteType.UITextSmartInsertDeleteTypeNo
import platform.UIKit.UILongPressGestureRecognizer
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementAttributesDisabled
import platform.UIKit.UINavigationBar
import platform.UIKit.UINavigationBarAppearance
import platform.UIKit.UITabBarAppearance
import platform.UIKit.UISearchControllerDelegateProtocol
import platform.UIKit.UINavigationItem
import platform.UIKit.UINavigationItemSearchBarPlacement.UINavigationItemSearchBarPlacementStacked
import platform.UIKit.UITabBar
import platform.UIKit.UITabBarController
import platform.UIKit.UITabBarControllerDelegateProtocol
import platform.UIKit.UINavigationController
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleDark
import platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
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
    internal val detailNavigation = UINavigationController()
    internal val presentationController = IosVaultPaneController(controller, detailNavigation)
    internal var dualPane by mutableStateOf(false)
        private set
    private var geometryWidth = 0.0
    private var geometryHeight = 0.0
    private var occlusions = emptyList<AdaptiveRegion>()
    private var divisions = emptyList<AdaptiveRegion>()
    private data class Projection(val generation: Long, val screens: List<IosVaultScreen>, val destination: VaultDestination)
    private var projection: Projection? = null
    internal var onVisibleScreens: ((Set<IosVaultScreen>) -> Unit)? = null

    internal fun updateGeometry(width: Double, height: Double, occlusions: List<AdaptiveRegion> = emptyList(), divisions: List<AdaptiveRegion> = emptyList()) {
        if (geometryWidth == width && geometryHeight == height && this.occlusions == occlusions && this.divisions == divisions) return
        geometryWidth = width
        geometryHeight = height
        this.occlusions = occlusions
        this.divisions = divisions
        projection?.let { reconcile(it.generation, it.screens, it.destination, animated = false) }
    }

    private fun layoutFor(screens: List<IosVaultScreen>) = adaptiveVaultLayout(
        geometryWidth, geometryHeight, occlusions, divisions, allowSplit = screens.lastOrNull() is IosVaultScreen.Viewer,
    )
    internal val navigationControllers: List<UINavigationController> = VaultDestination.entries.map { UINavigationController() }
    private val selectedNavigation: UINavigationController
        get() = navigationControllers.getOrElse(controller.selectedIndex.toInt()) { navigationControllers.first() }
    internal val navigationBar: UINavigationBar get() = selectedNavigation.navigationBar
    internal val tabBar: UITabBar get() = controller.tabBar
    private val navigationItem: UINavigationItem? get() = selectedNavigation.topViewController?.navigationItem
    private data class Frame(val screen: IosVaultScreen, val content: UIViewController, val controls: ChromeControls) {
        var scroll: ScrollSource? = null
    }
    /** Only an attached real Compose viewport can publish its current edges. */
    private class ScrollSource(
        chrome: IosVaultChrome?,
        val generation: Long,
        val screen: IosVaultScreen,
        val observerToken: Any,
        content: UIViewController?,
        canPublish: () -> Boolean,
    ) : VaultScrollObservation {
        val owner = chrome?.let(::WeakReference)
        var content = content?.let(::WeakReference)
        var canPublish: (() -> Boolean)? = canPublish
        var edges: VaultScrollEdges? = null
        override fun update(edges: VaultScrollEdges) { owner?.get()?.updateScroll(this, edges) }
        override fun dispose() { owner?.get()?.detachScroll(this); retire() }
        fun retire() { canPublish = null; edges = null }
    }
    private var scrollOperationActive = false
    internal var scrollInteractionEnabled by mutableStateOf(false)
        private set
    private val pendingScroll = mutableMapOf<IosVaultScreen, ScrollSource>()
    private val scrollObservers = mutableMapOf<IosVaultScreen, Any>()

    internal fun scrollObserver(generation: Long, screen: IosVaultScreen, canPublish: () -> Boolean): VaultScrollObserver {
        val owner = WeakReference(this)
        val observerToken = Any()
        if (this.generation == generation && !lockRequested) scrollObservers[screen] = observerToken
        return object : VaultScrollObserver {
            override fun attach(): VaultScrollObservation {
                val chrome = owner.get()
                val frame = chrome?.stacks?.values?.flatten()?.firstOrNull { it.screen == screen }
                val source = ScrollSource(chrome, generation, screen, observerToken, frame?.content, canPublish)
                if (chrome != null && chrome.generation == generation && !chrome.lockRequested &&
                    chrome.scrollObservers[screen] === observerToken) {
                    if (frame != null) {
                        frame.scroll?.retire()
                        frame.scroll = source
                        chrome.renderScrollAppearance()
                    } else {
                        // A Compose child may lay out while its factory is still
                        // creating the native Frame. Adopt only this exact lease.
                        chrome.pendingScroll.put(screen, source)?.retire()
                    }
                } else source.retire()
                return source
            }
        }
    }

    private fun scrollFrame(source: ScrollSource): Frame? = stacks.values.flatten().firstOrNull {
        it.scroll === source && it.content.objcPtr() == source.content?.get()?.objcPtr()
    }

    private fun scrollCanPublish(source: ScrollSource): Boolean {
        val frame = scrollFrame(source) ?: return false
        return source.generation == generation && scrollObservers[source.screen] === source.observerToken && nativeScrollEnabled() &&
            frame == stacks[selectedNavigation]?.lastOrNull() && selectedNavigation.topViewController == frame.content &&
            frame.screen !is IosVaultScreen.Viewer && source.canPublish?.invoke() == true
    }

    private fun updateScroll(source: ScrollSource, edges: VaultScrollEdges) {
        if (!scrollCanPublish(source)) return
        if (source.edges == edges) return
        source.edges = edges
        renderScrollAppearance()
    }

    private fun detachScroll(source: ScrollSource) {
        if (pendingScroll[source.screen] === source) pendingScroll.remove(source.screen)
        val frame = scrollFrame(source) ?: return
        frame.scroll = null
        renderScrollAppearance()
    }

    private fun nativeScrollEnabled(): Boolean {
        val frame = stacks[selectedNavigation]?.lastOrNull() ?: return false
        return frame.content == selectedNavigation.topViewController && frame.screen !is IosVaultScreen.Viewer &&
            generation != 0L && !lockRequested && !navigationBlocked && !covered &&
            !privateModalActive && !selectionBlocksPop && !scrollOperationActive && !transitionInFlight &&
            search?.controller?.active != true && search?.engaged != true
    }

    private var renderedScroll: Pair<UINavigationController, VaultScrollEdges?>? = null
    private fun renderScrollAppearance() {
        // Reattaching the Compose observer on resume republishes actual edges:
        // the content can keep scrolling while selection or search is active.
        val enabled = nativeScrollEnabled()
        if (scrollInteractionEnabled && !enabled) stacks[selectedNavigation]?.lastOrNull()?.scroll?.edges = null
        scrollInteractionEnabled = enabled
        val source = stacks[selectedNavigation]?.lastOrNull()?.scroll
        val edges = source?.takeIf(::scrollCanPublish)?.edges
        val projection = selectedNavigation to edges
        if (renderedScroll == projection) return
        renderedScroll = projection
        // Compose does not expose a UIScrollView. Project real viewport edges
        // into public UIKit appearance APIs without a dummy scroll view, moving
        // controls, changing insets or claiming automatic UIKit scroll tracking.
        val navigation = UINavigationBarAppearance().apply {
            if (edges?.atTop == true) configureWithTransparentBackground() else configureWithDefaultBackground()
            // On iOS 26 the default navigation background is already hidden,
            // identical to transparent. A system separator supplies a real
            // scroll boundary while retaining the native floating glass groups.
            if (edges != null && !edges.atTop) shadowColor = UIColor.separatorColor
        }
        selectedNavigation.navigationBar.apply {
            standardAppearance = navigation
            compactAppearance = navigation
            scrollEdgeAppearance = navigation
            compactScrollEdgeAppearance = navigation
        }
        val tabs = UITabBarAppearance().apply {
            if (edges?.atBottom == true) configureWithTransparentBackground() else configureWithDefaultBackground()
        }
        tabBar.standardAppearance = tabs
        tabBar.scrollEdgeAppearance = tabs
    }
    /** UIKit bars must never share a UIBarButtonItem or its custom UIView. */
    private class ChromeControls(owner: IosVaultChrome) : NSObject() {
        private val presenter = WeakReference(owner)
        val lock = button("Lock now", "lock").apply { tag = 7301L }
        val primaryButton = button("", null).apply { tag = 7302L }
        val primary: UIBarButtonItem
        private val lockAction: UIAction
        private val backAction: UIAction
        val secondary = UIBarButtonItem(title = "", menu = null)
        val back = button("Back", "chevron.backward")
        private val panicGesture = UILongPressGestureRecognizer(this, NSSelectorFromString("panic:"))
        private var active = true

        private fun current(): IosVaultChrome? = presenter.get()?.takeIf {
            active && !it.lockRequested && it.generation != 0L && it.controlsAreVisible(this)
        }

        init {
            val controls = WeakReference(this)
            val primaryAction = UIAction.actionWithTitle("", image = null, identifier = null) { _ ->
                controls.get()?.current()?.performPrimary()
            }
            primaryButton.addAction(primaryAction, forControlEvents = UIControlEventTouchUpInside)
            primary = UIBarButtonItem(primaryAction = primaryAction).apply { customView = primaryButton }
            IosAdaptiveBars.configureCustomItem?.invoke(primary)
            lockAction = UIAction.actionWithTitle("Lock now", image = null, identifier = null) { _ ->
                controls.get()?.current()?.lockNow()
            }
            backAction = UIAction.actionWithTitle("Back", image = null, identifier = null) { _ ->
                controls.get()?.current()?.goBack()
            }
            lock.addAction(lockAction, forControlEvents = UIControlEventTouchUpInside)
            back.addAction(backAction, forControlEvents = UIControlEventTouchUpInside)
            lock.addGestureRecognizer(panicGesture)
            lock.accessibilityCustomActions = listOf(
                UIAccessibilityCustomAction(name = "Lock immediately", actionHandler = {
                    controls.get()?.current()?.let { it.panicNow(); true } ?: false
                }),
            )
        }

        @ObjCAction fun panic(recognizer: UILongPressGestureRecognizer) { current()?.panic(recognizer) }

        fun retire() {
            active = false
            primary.primaryAction = null
            primary.target = null
            primary.action = null
            primary.menu = null
            primary.title = null
            secondary.menu = null
            secondary.title = null
            lock.removeAction(lockAction, forControlEvents = UIControlEventTouchUpInside)
            back.removeAction(backAction, forControlEvents = UIControlEventTouchUpInside)
            lock.removeGestureRecognizer(panicGesture)
        }
    }
    private val currentControls: ChromeControls? get() = stacks[selectedNavigation]?.lastOrNull()?.controls
    private val viewerNavigation: UINavigationController get() = if (dualPane) detailNavigation else selectedNavigation
    private val allNavigations: List<UINavigationController> get() = navigationControllers + detailNavigation
    private fun controlsAreVisible(controls: ChromeControls): Boolean =
        currentControls?.objcPtr() == controls.objcPtr() || (dualPane && stacks[detailNavigation]?.lastOrNull()?.controls?.objcPtr() == controls.objcPtr())
    private val stacks = mutableMapOf<UINavigationController, List<Frame>>()
    private val retiring = mutableMapOf<UINavigationController, List<Frame>>()
    private var screenFactory: ((IosVaultScreen) -> UIViewController)? = null
    private var popCallback: ((IosVaultScreen) -> Unit)? = null
    private var retireCallback: ((IosVaultScreen) -> Unit)? = null
    private var generation = 0L
    private var reconciling = false
    private val transitioning = mutableSetOf<UINavigationController>()
    private var searchDismissal: UISearchController? = null
    private var pendingReconcile: (() -> Unit)? = null
    private var pendingChromeUpdate: (() -> Unit)? = null
    private var chromeUpdateEpoch = 0L
    internal val transitionInFlight: Boolean get() = transitioning.isNotEmpty() || searchDismissal != null
    private var actions: VaultActions? = null
    private data class ShellPresentation(val state: VaultUiState, val actions: VaultActions, val accent: Color, val navigationBusy: Boolean, val covered: Boolean)
    private var shellPresentation: ShellPresentation? = null
    private var key: ChromeKey? = null
    private var navigationBlocked = true
    private var lockRequested = false
    private var primaryCommand: ((VaultActions) -> Unit)? = null
    private var primaryIsImport = false
    internal var guideBottomInset = 0.0
        private set
    internal var onGuideLayout: (() -> Unit)? = null
    internal fun setGuideInset(height: Double) {
        val next = height.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        if (guideBottomInset != next) { guideBottomInset = next; onGuideLayout?.invoke() }
    }
    private var guideHighlighted: UIView? = null
    private var guideOwner: dev.po4yka.chur.app.onboarding.GuideCoordinator? = null

    /** Native controls own their highlight; only target identifiers leave this adapter. */
    internal fun updateGuide(guide: dev.po4yka.chur.app.onboarding.GuideCoordinator?) {
        if (guideOwner !== guide) { guideOwner?.releaseTargets(this); guideOwner = guide }
        guideHighlighted?.layer?.borderWidth = 0.0
        guideHighlighted = null
        val step = guide?.session?.value?.step
        val targets = mapOf(
            "lock" to currentControls?.lock,
            "import" to currentControls?.primaryButton?.takeIf { primaryIsImport && currentControls?.primary?.enabled == true },
            "search" to search?.controller?.searchBar?.searchTextField?.takeIf { searchAllowed },
        )
        fun hierarchyVisible(view: UIView): Boolean {
            var current: UIView? = view
            while (current != null) {
                if (current.hidden || current.alpha <= 0.0) return false
                current = current.superview
            }
            return true
        }
        for ((id, target) in targets) {
            val window = target?.window
            val visible = guide != null && !lockRequested && !transitionInFlight && !privateModalActive && !covered &&
                target != null && window != null && hierarchyVisible(target) &&
                platform.CoreGraphics.CGRectContainsRect(window.bounds, target.convertRect(target.bounds, toView = window)) &&
                !platform.CoreGraphics.CGRectIsEmpty(target.bounds)
            guide?.target(this, id, visible)
            if (visible && step?.target == id) {
                target!!.layer.borderColor = accentTint.CGColor
                target.layer.borderWidth = 2.0
                guideHighlighted = target
            }
        }
    }
    private var backCommand: ((VaultActions) -> Unit)? = null
    private var menuEpoch = 0L
    private val menuCommands = mutableListOf<Command>()
    private var accentTint: UIColor = UIColor(red = 0.0, green = 0.0, blue = 1.0, alpha = 1.0)
    private var viewerState: IosViewerPanelState? = null
    private var viewerActions: IosViewerPanelActions? = null
    private var viewerEpoch = 0L
    private var renderedViewer: Triple<IosViewerPanelState, Boolean, Boolean>? = null
    private data class SearchScope(val destination: VaultDestination, val album: String?, val libraryScope: String?,
        val trash: Boolean, val recentlyAdded: Boolean, val context: BrowseSearchScope?)
    private data class SearchPresentation(val frame: Frame, val scope: SearchScope, val text: String)
    private var searchPresentation: SearchPresentation? = null
    private var search: NativeSearch? = null
    private var searchAllowed = false
    private var searchQueryLoading = false
    private var pendingModalPresentation: (() -> Unit)? = null
    internal var onClearPrivateModals: (() -> Unit)? = null
    internal var privateModalActive = false
        private set

    internal fun setPrivateModalActive(active: Boolean) {
        privateModalActive = active
        key = null
        if (active) suspendNavigation() else {
            renderedViewer = null
            renderViewer()
            reconcileVisibility()
        }
    }

    private fun freezeModalSourceItems() {
        // UIPopoverPresentationController retains this exact sourceItem. Keep
        // its mounted identity until dismissal has completed; state is still
        // recorded below and the next ordinary projection rebuilds the bars.
        navigationItem?.leftBarButtonItem?.enabled = false
        navigationItem?.rightBarButtonItems?.filterIsInstance<UIBarButtonItem>()?.forEach {
            it.enabled = it.customView == currentControls?.lock
        }
        selectedNavigation.topViewController?.toolbarItems?.filterIsInstance<UIBarButtonItem>()?.forEach { it.enabled = false }
        if (dualPane) {
            val frame = stacks[detailNavigation]?.lastOrNull()
            frame?.content?.navigationItem?.leftBarButtonItem?.enabled = false
            frame?.content?.navigationItem?.rightBarButtonItems?.filterIsInstance<UIBarButtonItem>()?.forEach {
                it.enabled = it.customView == frame?.controls?.lock
            }
            frame?.content?.toolbarItems?.filterIsInstance<UIBarButtonItem>()?.forEach { it.enabled = false }
        }
        tabBar.userInteractionEnabled = false
        tabBar.items?.filterIsInstance<UITabBarItem>()?.forEach { it.enabled = false }
        reconcileVisibility()
    }

    /** Search must finish its own dismissal before a modal can use the same root. */
    internal fun preparePrivateModal(present: () -> Unit) {
        retireSearch()
        if (searchDismissal == null) present() else pendingModalPresentation = present
    }

    internal fun privateModalAnchor(anchor: PrivateModalAnchor): UIBarButtonItem? = when (anchor) {
        PrivateModalAnchor.Automatic -> null
        PrivateModalAnchor.Info -> viewerNavigation.topViewController?.navigationItem?.rightBarButtonItems?.filterIsInstance<UIBarButtonItem>()
            ?.firstOrNull { it.title == "Info" || it.title == "Hide info" }
        PrivateModalAnchor.Export -> viewerNavigation.topViewController?.toolbarItems?.filterIsInstance<UIBarButtonItem>()
            ?.firstOrNull { it.title == "Export" }
    }

    /** A fresh delegate object is a visit lease, including same-screen re-entry. */
    private class NativeSearch(owner: IosVaultChrome, frame: Frame, val generation: Long, scope: SearchScope) :
        NSObject(), UISearchResultsUpdatingProtocol, UISearchBarDelegateProtocol, UISearchControllerDelegateProtocol {
        private val owner = WeakReference(owner)
        var frame: Frame? = frame
        var scope: SearchScope? = scope
        val controller = UISearchController(searchResultsController = null)
        var publishedText = ""
        var syncing = false
        var engaged = false
        override fun willPresentSearchController(searchController: UISearchController) {
            if (searchController == controller) { engaged = true; owner.get()?.renderScrollAppearance() }
        }
        override fun didDismissSearchController(searchController: UISearchController) {
            if (searchController == controller) { engaged = false; owner.get()?.renderScrollAppearance() }
        }
        override fun updateSearchResultsForSearchController(searchController: UISearchController) {
            if (searchController == controller && !syncing) owner.get()?.searchChanged(this)
        }
        override fun searchBarShouldBeginEditing(searchBar: UISearchBar): Boolean {
            val allowed = searchBar == controller.searchBar && owner.get()?.searchCanAct(this) == true
            if (allowed) { engaged = true; owner.get()?.renderScrollAppearance() }
            return allowed
        }
        override fun searchBarCancelButtonClicked(searchBar: UISearchBar) {
            if (searchBar == controller.searchBar) owner.get()?.cancelSearch(this)
        }
        override fun searchBarSearchButtonClicked(searchBar: UISearchBar) {
            if (searchBar == controller.searchBar && owner.get()?.searchCanAct(this) == true) searchBar.resignFirstResponder()
        }
    }
    internal val toolbar get() = selectedNavigation.toolbar
    private val viewerIsTop: Boolean get() = viewerState != null &&
        (stacks[viewerNavigation]?.lastOrNull()?.screen as? IosVaultScreen.Viewer)?.token == viewerState?.token &&
            viewerNavigation.topViewController == stacks[viewerNavigation]?.lastOrNull()?.content

    init {
        navigationBar.translucent = true
        tabBar.translucent = true
        controller.delegate = this
        allNavigations.forEach { it.delegate = this; it.navigationBar.translucent = true; it.toolbar.translucent = true }
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
        val browseScreen = if (layoutFor(screens).viewer != null) screens.dropLast(1).lastOrNull() else screens.lastOrNull()
        searchPresentation?.let { current ->
            if (current.frame.screen != browseScreen || current.scope.destination != destination) retireSearch()
        }
        if (searchDismissal != null) {
            val owner = WeakReference(this@IosVaultChrome)
            pendingReconcile = { owner.get()?.reconcile(generation, screens, destination, animated) }
            return
        }
        require(screens.firstOrNull() == IosVaultScreen.Root(destination))
        projection = Projection(generation, screens, destination)
        val layout = layoutFor(screens)
        val split = layout.viewer != null
        val wasSplit = dualPane
        val candidates = (stacks.values.flatten() + retiring.values.flatten()).distinct()
        val desired = navigationControllers.mapIndexed { index, nav ->
            nav to if (VaultDestination.entries[index] == destination) {
                if (split) screens.filterNot { it is IosVaultScreen.Viewer } else screens
            } else listOf(IosVaultScreen.Root(VaultDestination.entries[index]))
        }.toMap() + (detailNavigation to if (split) screens.filterIsInstance<IosVaultScreen.Viewer>() else emptyList())
        val retainedScreens = desired.values.flatten().toSet()
        // Move existing controllers between navigation owners without retiring their scene.
        // Empty the former owner first: UIKit permits only one parent per controller.
        reconciling = true
        try {
            if (wasSplit != split) {
                retireSearch()
                allNavigations.forEach { nav ->
                    nav.setViewControllers(emptyList<UIViewController>(), animated = false)
                }
                transitioning.clear()
            }
            dualPane = split
            desired.forEach { (nav, wanted) ->
                val old = stacks[nav].orEmpty()
                val next = wanted.map { screen -> candidates.firstOrNull { it.screen == screen }
                    ?: Frame(screen, checkNotNull(screenFactory).invoke(screen), ChromeControls(this)).also {
                        it.content.hidesBottomBarWhenPushed = screen is IosVaultScreen.Viewer
                        pendingScroll.remove(screen)?.let { source ->
                            source.content = WeakReference(it.content)
                            it.scroll = source
                        }
                    }
                }
                val removed = (old + retiring[nav].orEmpty()).filter { it.screen !in retainedScreens }.distinct()
                val animate = animated && wasSplit == split && old.isNotEmpty() &&
                    nav == navigationControllers[destination.ordinal] && presentationController.view.window != null
                if (animate && removed.isNotEmpty()) retiring[nav] = removed
                else { removed.forEach(::retire); retiring.remove(nav) }
                stacks[nav] = next
                if (wasSplit != split || old != next) {
                    nav.setViewControllers(next.map { it.content }, animated = animate)
                    key = null
                    renderedViewer = null
                }
            }
            navigationControllers.forEachIndexed { index, nav ->
                val tab = VaultDestination.entries[index]
                nav.tabBarItem = UITabBarItem(tab.label, UIImage.systemImageNamed(tab.symbol()), index.toLong())
            }
            if (controller.viewControllers.isNullOrEmpty()) controller.setViewControllers(navigationControllers, animated = false)
            if (controller.selectedIndex != destination.ordinal.toULong()) presentationController.view.endEditing(true)
            controller.selectedIndex = destination.ordinal.toULong()
            controller.view.hidden = false
            controller.view.accessibilityElementsHidden = false
            controller.view.userInteractionEnabled = true
            presentationController.view.hidden = false
            presentationController.view.accessibilityElementsHidden = false
            presentationController.view.userInteractionEnabled = true
            presentationController.apply(layout)
            publishVisibleScreens()
            renderViewer()
            reconcileVisibility()
        } finally { reconciling = false }
        if (wasSplit != split) shellPresentation?.let {
            update(it.state, it.actions, it.accent, it.navigationBusy, it.covered)
        }
    }

    private fun publishVisibleScreens() {
        onVisibleScreens?.invoke(buildSet {
            stacks[selectedNavigation]?.lastOrNull()?.screen?.let(::add)
            if (dualPane) stacks[detailNavigation]?.lastOrNull()?.screen?.let(::add)
        })
    }

    private fun retire(frame: Frame) {
        scrollObservers.remove(frame.screen)
        frame.scroll?.retire()
        frame.scroll = null
        frame.controls.retire()
        if (search?.frame == frame || searchPresentation?.frame == frame) retireSearch()
        // Retire the Compose slot before releasing UIKit's reference to it.
        retireCallback?.invoke(frame.screen)
        frame.content.view.endEditing(true)
        frame.content.view.hidden = true
        frame.content.view.accessibilityElementsHidden = true
        frame.content.navigationItem.title = null
        frame.content.navigationItem.leftBarButtonItem = null
        frame.content.navigationItem.rightBarButtonItems = null
        frame.content.toolbarItems = null
        if ((frame.screen as? IosVaultScreen.Viewer)?.token == viewerState?.token) resetViewer()
    }

    private fun retireStacks() {
        pendingScroll.values.forEach(ScrollSource::retire)
        pendingScroll.clear()
        scrollObservers.clear()
        (stacks.values.flatten() + retiring.values.flatten()).distinct().forEach(::retire)
        stacks.clear()
        retiring.clear()
        transitioning.clear()
        // Concealment and slot retirement above are immediate. UIKit releases
        // its stack after the owned search presentation has actually ended.
        if (searchDismissal != null) return
        allNavigations.forEach {
            it.delegate = null
            it.setViewControllers(emptyList<UIViewController>(), animated = false)
            it.delegate = this
        }
        controller.setViewControllers(null, animated = false)
    }

    private fun reconcileVisibility() {
        // Native modals cover these bars themselves and need their source items
        // to stay mounted for regular-width popover positioning.
        val covered = this.covered && !privateModalActive
        val selected = selectedNavigation
        stacks.forEach { (nav, frames) -> frames.forEach { frame ->
            frame.content.view.accessibilityElementsHidden = (nav != selected && !(dualPane && nav == detailNavigation)) || frame != frames.lastOrNull()
        } }
        val viewer = stacks[selected]?.lastOrNull()?.screen is IosVaultScreen.Viewer
        // UIKit's willShow controls bar interpolation during a native transition.
        // Ordinary content updates must not snap a Viewer pop back to hidden.
        val nativeViewer = viewer && viewerState != null
        if (selected !in transitioning) applyBarAppearance(selected, viewer)
        if (selected !in transitioning || covered) selected.setNavigationBarHidden((viewer && !nativeViewer) || covered, animated = false)
        if (selected !in transitioning || covered) selected.setToolbarHidden(
            covered || if (viewer) !nativeViewer || viewerState?.visible != true else selectionBlocksPop.not(), animated = false)
        if (selected !in transitioning || covered) {
            // The system owns the floating tab bar's content layout. Selection
            // replaces tabs with the navigation controller's action toolbar.
            controller.setTabBarHidden(viewer || selectionBlocksPop || covered, animated = false)
        }
        navigationBar.accessibilityElementsHidden = (viewer && !nativeViewer) || covered
        toolbar.accessibilityElementsHidden = covered || selected.toolbarHidden
        tabBar.accessibilityElementsHidden = viewer || selectionBlocksPop || covered
        if (dualPane) {
            val nativeDetail = viewerState != null
            applyBarAppearance(detailNavigation, viewer = true)
            detailNavigation.setNavigationBarHidden(covered || !nativeDetail, animated = false)
            detailNavigation.setToolbarHidden(covered || !nativeDetail || viewerState?.visible != true, animated = false)
            detailNavigation.navigationBar.accessibilityElementsHidden = covered || !nativeDetail
            detailNavigation.toolbar.accessibilityElementsHidden = covered || detailNavigation.toolbarHidden
        }
        renderScrollAppearance()
        allNavigations.forEach { nav ->
            val canPop = nav != detailNavigation && !navigationBlocked && !covered && !selectionBlocksPop && !viewerBlocksNavigation(nav)
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
    fun suspendNavigation(preserveSearch: Boolean = false) {
        searchQueryLoading = preserveSearch && searchAllowed && search != null
        if (!searchQueryLoading) retireSearch()
        retireMenu()
        key = null
        navigationBlocked = true
        currentControls?.let {
            it.back.enabled = false
            it.primary.enabled = false
            it.secondary.enabled = false
        }
        navigationItem?.setHidesBackButton(true, animated = false)
        navigationItem?.leftBarButtonItem?.enabled = false
        selectedNavigation.topViewController?.toolbarItems?.filterIsInstance<UIBarButtonItem>()?.forEach { it.enabled = false }
        if (dualPane) {
            val frame = stacks[detailNavigation]?.lastOrNull()
            frame?.content?.navigationItem?.leftBarButtonItem?.enabled = false
            frame?.content?.navigationItem?.rightBarButtonItems?.filterIsInstance<UIBarButtonItem>()?.forEach {
                it.enabled = it.customView == frame?.controls?.lock
            }
            frame?.content?.toolbarItems?.filterIsInstance<UIBarButtonItem>()?.forEach { it.enabled = false }
        }
        tabBar.userInteractionEnabled = false
        tabBar.items?.filterIsInstance<UITabBarItem>()?.forEach { it.enabled = false }
        allNavigations.forEach { dismissMenus(it.navigationBar); dismissMenus(it.toolbar) }
        // Reuse the ordinary busy policy without replacing the displayed
        // title, selected destination or callbacks; Lock remains available.
        reconcileVisibility()
    }

    fun update(state: VaultUiState, actions: VaultActions, accent: Color, navigationBusy: Boolean, covered: Boolean = false) {
        if (lockRequested) return
        val controls = currentControls ?: return
        shellPresentation = ShellPresentation(state, actions, accent, navigationBusy, covered)
        val lock = controls.lock
        val primary = controls.primary
        val secondary = controls.secondary
        val back = controls.back
        val updateEpoch = ++chromeUpdateEpoch
        if (searchDismissal != null) {
            val owner = WeakReference(this@IosVaultChrome)
            pendingChromeUpdate = {
                owner.get()?.takeIf { it.chromeUpdateEpoch == updateEpoch }
                    ?.update(state, actions, accent, navigationBusy, covered)
            }
            return
        }
        this.covered = covered
        scrollOperationActive = state.operation != null
        accentTint = UIColor(red = accent.red.toDouble(), green = accent.green.toDouble(), blue = accent.blue.toDouble(), alpha = 1.0)
        selectionBlocksPop = state.selectedCount > 0
        // Selection actions share one operation owner. Progress can arrive
        // without a modal/navigation-busy report from the Compose shell.
        navigationBlocked = privateModalActive || covered || navigationBusy || (selectionBlocksPop && state.operation != null)
        if (navigationBlocked) { dismissMenus(navigationBar); dismissMenus(toolbar) }
        this.actions = actions
        updateSearch(state, navigationBusy)
        if (privateModalActive) { freezeModalSourceItems(); return }
        if (viewerIsTop) {
            tabBar.userInteractionEnabled = !navigationBlocked
            tabBar.items?.filterIsInstance<UITabBarItem>()?.forEach { it.enabled = !navigationBlocked }
            if (!dualPane) { renderViewer(); return }
        }
        navigationItem?.setHidesBackButton(navigationBlocked || selectionBlocksPop, animated = false)
        navigationItem?.leftBarButtonItem?.enabled = !navigationBlocked
        back.enabled = !navigationBlocked
        primary.enabled = !navigationBlocked && (state.operation == null ||
            (state.destination == VaultDestination.ALBUMS && state.openAlbum == null && state.selectedCount == 0))
        controls.primaryButton.enabled = primary.enabled
        secondary.enabled = !navigationBlocked
        selectedNavigation.topViewController?.toolbarItems?.filterIsInstance<UIBarButtonItem>()?.forEach { it.enabled = !navigationBlocked }
        tabBar.userInteractionEnabled = !navigationBlocked
        tabBar.items?.filterIsInstance<UITabBarItem>()?.forEach { it.enabled = !navigationBlocked }
        reconcileVisibility()
        val next = ChromeKey(
            state.destination, state.openAlbum?.id,
            state.openAlbum?.name ?: state.libraryScopeTitle ?: state.destination.label,
            state.selectedCount, state.selectionIds.toSet(), selectionBlocksPop && navigationBlocked,
            state.operation != null, state.trashOpen,
            state.libraryScopeTitle != null, state.recentlyAdded, actions.onOpenPrivateNotes != null, state.tiles.isNotEmpty(),
        )
        navigationBar.tintColor = accentTint
        tabBar.tintColor = accentTint
        if (key == next) {
            if (dualPane) renderViewer()
            // UIKit selects before its delegate runs; shared state is authoritative
            // even when a navigation request was rejected while becoming busy.
            controller.selectedIndex = state.destination.ordinal.toULong()
            return
        }
        if (selectionBlocksPop || (key?.selection ?: 0) > 0) {
            dismissMenus(navigationBar)
            dismissMenus(toolbar)
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
            UIBarButtonItem(customView = back).also { it.enabled = !navigationBlocked; IosAdaptiveBars.configureCustomItem?.invoke(it) }
        } else null
        primary.menu = null
        secondary.menu = null
        primaryCommand = null
        val right = mutableListOf(UIBarButtonItem(customView = lock).also { IosAdaptiveBars.configureCustomItem?.invoke(it) })
        val openAlbum = state.openAlbum
        val menu = when {
            state.selectedCount > 0 -> emptyList()
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
            !next.busy && next.hasMedia && state.selectedCount == 0 && state.openAlbum != null -> "Import"
            !next.busy && next.hasMedia && state.selectedCount == 0 && state.destination == VaultDestination.LIBRARY &&
                (state.libraryScopeTitle == null || state.recentlyAdded) -> "Import"
            state.selectedCount == 0 && state.destination == VaultDestination.ALBUMS && state.openAlbum == null -> "New album"
            else -> null
        }
        primaryIsImport = primaryTitle == "Import"
        if (primaryTitle != null) {
            primary.title = primaryTitle
            controls.primaryButton.setTitle(primaryTitle, forState = UIControlStateNormal)
            controls.primaryButton.accessibilityLabel = primaryTitle
            primary.primaryAction?.title = primaryTitle
            primaryCommand = when (primaryTitle) {
                "New album" -> { a -> a.onCreateAlbum() }
                else -> { a -> a.onImport() }
            }
            if (state.selectedCount == 0) right += primary
        }
        navigationItem?.rightBarButtonItems = right
        selectedNavigation.topViewController?.toolbarItems = if (state.selectedCount > 0) selectionToolbar(state) else null
        controller.selectedIndex = state.destination.ordinal.toULong()
        if (dualPane) renderViewer()
        reconcileVisibility()
    }

    /** Called before lock commands and when the Compose vault subtree leaves. */
    fun clear() {
        updateGuide(null)
        // Conceal first: transition snapshots and presentation callbacks may still
        // exist in UIKit until the next run-loop, but can no longer be displayed.
        presentationController.view.hidden = true
        presentationController.view.accessibilityElementsHidden = true
        presentationController.view.userInteractionEnabled = false
        controller.view.hidden = true
        controller.view.accessibilityElementsHidden = true
        controller.view.userInteractionEnabled = false
        projection = null
        onVisibleScreens?.invoke(emptySet())
        lockRequested = true
        renderedScroll = null
        scrollInteractionEnabled = false
        scrollOperationActive = false
        onClearPrivateModals?.invoke()
        pendingModalPresentation = null
        chromeUpdateEpoch++
        pendingReconcile = null
        pendingChromeUpdate = null
        retireSearch()
        actions = null
        shellPresentation = null
        onVisibleScreens = null
        resetViewer()
        navigationBlocked = true
        retireMenu()
        allNavigations.forEach { dismissMenus(it.navigationBar); dismissMenus(it.toolbar) }
        key = null
        primaryCommand = null
        backCommand = null
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
        if (searchDismissal == null) controller.dismissViewControllerAnimated(false, completion = null)
        retireStacks()
        screenFactory = null
        popCallback = null
        retireCallback = null
        // Keep UINavigationController's own visibility state coherent. Directly
        // hiding only its bar leaves navigationBarHidden=false; a fresh visit's
        // setNavigationBarHidden(false) can then be a no-op and strand the bar.
        allNavigations.forEach {
            applyBarAppearance(it, viewer = false)
            it.setNavigationBarHidden(true, animated = false)
            it.setToolbarHidden(true, animated = false)
        }
        dualPane = false
        controller.setTabBarHidden(true, animated = false)
        navigationBar.accessibilityElementsHidden = true
        tabBar.accessibilityElementsHidden = true
    }

    override fun tabBarController(tabBarController: UITabBarController, shouldSelectViewController: UIViewController): Boolean {
        if (!navigationBlocked && !lockRequested && !transitionInFlight) {
            retireSearch()
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
            retireSearch(preservePresentation = true)
            transitioning += navigationController
        }
        val viewer = frame.screen is IosVaultScreen.Viewer
        if (navigationController == selectedNavigation) {
            controller.setTabBarHidden(viewer || selectionBlocksPop || covered, animated)
            tabBar.accessibilityElementsHidden = viewer || selectionBlocksPop || covered
        }
        applyBarAppearance(navigationController, viewer)
        navigationController.setNavigationBarHidden((viewer && viewerState?.token != (frame.screen as? IosVaultScreen.Viewer)?.token) || covered, animated)
        navigationController.setToolbarHidden(covered || if (viewer) viewerState?.visible != true else !selectionBlocksPop, animated)
        renderScrollAppearance()
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
        if (index == frames.lastIndex) { resumeSearch(); reconcileVisibility(); return }
        if (navigationBlocked || selectionBlocksPop || viewerBlocksNavigation(navigationController)) {
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
        publishVisibleScreens()
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

    private fun searchCanAct(binding: NativeSearch): Boolean {
        val frame = binding.frame ?: return false
        return search === binding && !lockRequested && binding.generation == generation &&
            searchAllowed && !covered && !selectionBlocksPop && (!navigationBlocked || searchQueryLoading) &&
            !transitionInFlight && stacks[selectedNavigation]?.lastOrNull() == frame &&
            selectedNavigation.topViewController == frame.content &&
            frame.content.navigationItem.searchController == binding.controller
    }

    private fun searchChanged(binding: NativeSearch) {
        if (!searchCanAct(binding)) return
        val text = binding.controller.searchBar.text.orEmpty()
        if (text == binding.publishedText) return
        binding.publishedText = text
        searchPresentation = searchPresentation?.copy(text = text)
        actions?.onSearch?.invoke(text)
    }

    private fun cancelSearch(binding: NativeSearch) {
        if (!searchCanAct(binding)) return
        val callback = actions?.onSearch
        retireSearch()
        callback?.invoke("")
    }

    private fun retireSearch(preservePresentation: Boolean = false) {
        if (!preservePresentation) searchPresentation = null
        // Revoke the delegate before UIKit's synchronous dismissal callbacks.
        val previous = search
        search = null
        searchAllowed = false
        searchQueryLoading = false
        if (previous == null) return
        val native = previous.controller
        val frame = previous.frame
        previous.frame = null
        previous.scope = null
        native.searchResultsUpdater = null
        native.delegate = null
        previous.engaged = false
        native.searchBar.delegate = null
        native.searchBar.resignFirstResponder()
        native.searchBar.searchTextField.resignFirstResponder()
        native.searchBar.text = ""
        previous.publishedText = ""
        native.searchBar.hidden = true
        native.searchBar.accessibilityElementsHidden = true
        native.view.accessibilityElementsHidden = true
        val presenter = native.presentingViewController
        if (presenter != null) {
            // UIKit rejects navigation-stack changes during this presentation.
            // Dismiss once through its actual owner, then project the latest
            // shared navigation instead of racing active=false with a push.
            searchDismissal = native
            val owner = WeakReference(this@IosVaultChrome)
            presenter.dismissViewControllerAnimated(false) {
                native.active = false
                if (frame != null && frame.content.navigationItem.searchController == native) {
                    frame.content.navigationItem.searchController = null
                }
                val chrome = owner.get()
                if (chrome?.searchDismissal == native) {
                    chrome.searchDismissal = null
                    val reconcile = chrome.pendingReconcile
                    val update = chrome.pendingChromeUpdate
                    chrome.pendingReconcile = null
                    chrome.pendingChromeUpdate = null
                    if (chrome.lockRequested) chrome.retireStacks()
                    else {
                        reconcile?.invoke()
                        update?.invoke()
                        chrome.resumeSearch()
                        val present = chrome.pendingModalPresentation
                        chrome.pendingModalPresentation = null
                        present?.invoke()
                    }
                }
            }
        } else {
            native.active = false
            if (frame != null && frame.content.navigationItem.searchController == native) {
                frame.content.navigationItem.searchController = null
            }
        }
    }

    private fun updateSearch(state: VaultUiState, navigationBusy: Boolean) {
        val frame = stacks[selectedNavigation]?.lastOrNull()
        val eligible = frame?.screen == IosVaultScreen.Root(VaultDestination.LIBRARY) || frame?.screen is IosVaultScreen.Album
        val allowed = eligible && frame?.content == selectedNavigation.topViewController &&
            state.selectedCount == 0 && state.operation == null && !state.trashOpen && !covered && !navigationBusy
        if (!allowed || frame == null) { retireSearch(); return }
        val scope = SearchScope(state.destination, state.openAlbum?.id, state.libraryScopeTitle, state.trashOpen, state.recentlyAdded, state.searchContext)
        searchPresentation = SearchPresentation(frame, scope, state.searchTerms)
        if (transitionInFlight) { retireSearch(preservePresentation = true); return }
        renderSearch(checkNotNull(searchPresentation))
    }

    private fun resumeSearch() {
        val presentation = searchPresentation ?: return
        if (!lockRequested && !navigationBlocked && !covered && !selectionBlocksPop && !transitionInFlight &&
            stacks[selectedNavigation]?.lastOrNull() == presentation.frame &&
            selectedNavigation.topViewController == presentation.frame.content) renderSearch(presentation)
    }

    private fun renderSearch(presentation: SearchPresentation) {
        val (frame, scope, text) = presentation
        if (search?.frame != frame || search?.scope != scope) {
            retireSearch(preservePresentation = true)
            if (searchDismissal != null) return
            val binding = NativeSearch(this, frame, generation, scope)
            val native = binding.controller
            native.obscuresBackgroundDuringPresentation = false
            native.hidesNavigationBarDuringPresentation = false
            native.searchResultsUpdater = binding
            native.delegate = binding
            native.searchBar.delegate = binding
            native.searchBar.placeholder = if (frame.screen is IosVaultScreen.Album) "Search album" else "Search Library"
            native.searchBar.searchTextField.apply {
                autocapitalizationType = UITextAutocapitalizationTypeNone
                autocorrectionType = UITextAutocorrectionTypeNo
                spellCheckingType = UITextSpellCheckingTypeNo
                inlinePredictionType = UITextInlinePredictionTypeNo
                smartQuotesType = UITextSmartQuotesTypeNo
                smartDashesType = UITextSmartDashesTypeNo
                smartInsertDeleteType = UITextSmartInsertDeleteTypeNo
                textContentType = null
            }
            frame.content.definesPresentationContext = true
            frame.content.navigationItem.hidesSearchBarWhenScrolling = false
            frame.content.navigationItem.preferredSearchBarPlacement = UINavigationItemSearchBarPlacementStacked
            search = binding
            frame.content.navigationItem.searchController = native
        }
        searchAllowed = true
        searchQueryLoading = false
        val binding = checkNotNull(search)
        binding.syncing = true
        try {
            binding.publishedText = text
            // Setting text unconditionally disrupts selection/marked text.
            if (binding.controller.searchBar.text.orEmpty() != text) binding.controller.searchBar.text = text
        } finally { binding.syncing = false }
    }

    fun hasVisibleMenu(): Boolean = allNavigations.flatMap { menuInteractions(it.navigationBar) + menuInteractions(it.toolbar) }.any {
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
        val start = menuCommands.size
        menuCommands.addAll(commands)
        val epoch = menuEpoch
        val owner = WeakReference(this@IosVaultChrome)
        val escape = UIAction.actionWithTitle("Lock and hide", image = null, identifier = null) { _ ->
            val presenter = owner.get()
            // Privacy remains available while ordinary navigation is blocked.
            if (presenter != null && epoch == presenter.menuEpoch) presenter.panicNow()
        }
        val native = listOf(escape) + commands.mapIndexed { index, command ->
            UIAction.actionWithTitle(command.title, image = null, identifier = null) { _ ->
                val presenter = owner.get()
                if (presenter != null && epoch == presenter.menuEpoch && !presenter.navigationBlocked && !presenter.transitionInFlight) {
                    val current = presenter.menuCommands.getOrNull(start + index)
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

    private fun flexibleSpace() = UIBarButtonItem(barButtonSystemItem = UIBarButtonSystemItemFlexibleSpace, target = null, action = null).also { it.enabled = false }

    private fun selectionItem(command: Command, symbol: String): UIBarButtonItem {
        val index = menuCommands.size
        menuCommands += command
        val epoch = menuEpoch
        val owner = WeakReference(this@IosVaultChrome)
        val action = UIAction.actionWithTitle(command.title, image = UIImage.systemImageNamed(symbol), identifier = null) { _ ->
            val presenter = owner.get()
            if (presenter != null && epoch == presenter.menuEpoch && !presenter.navigationBlocked &&
                !presenter.transitionInFlight && !presenter.viewerIsTop) {
                presenter.menuCommands.getOrNull(index)?.takeIf { it.enabled }?.let { current -> presenter.actions?.let(current.run) }
            }
        }
        return UIBarButtonItem(primaryAction = action)
            .also { it.enabled = !navigationBlocked && command.enabled }
    }

    private fun selectionToolbar(state: VaultUiState): List<UIBarButtonItem> = buildList {
        if (!state.trashOpen) {
            add(selectionItem(Command("Export") { it.onExportSelection() }, "square.and.arrow.up"))
            add(flexibleSpace())
            add(selectionItem(Command("Add to album") { it.onAddSelectionToAlbum() }, "rectangle.stack.badge.plus"))
        } else add(selectionItem(Command("Restore") { it.onRestoreSelection() }, "arrow.uturn.backward"))
        add(flexibleSpace())
        add(selectionItem(Command(if (state.trashOpen) "Delete permanently" else "Move to Trash") { it.onDeleteSelection() }, "trash"))
        add(flexibleSpace())
        add(UIBarButtonItem(image = UIImage.systemImageNamed("ellipsis"),
            menu = nativeMenu(selectionMenu(state))).also { it.title = "More"; it.enabled = !navigationBlocked })
    }

    /** Published only by the active shared Viewer scene; paging rotates the native action epoch. */
    fun updateViewer(state: IosViewerPanelState, actions: IosViewerPanelActions, accent: Color) {
        if (lockRequested || state.generation != generation || (stacks[viewerNavigation]?.lastOrNull()?.screen as? IosVaultScreen.Viewer)?.token != state.token) return
        if (viewerState != state) {
            viewerEpoch++
            dismissMenus(navigationBar)
            dismissMenus(toolbar)
        }
        viewerState = state
        viewerActions = actions
        accentTint = UIColor(red = accent.red.toDouble(), green = accent.green.toDouble(), blue = accent.blue.toDouble(), alpha = 1.0)
        key = null
        renderViewer()
    }

    fun clearViewer(generation: Long, token: Long) {
        if (viewerState?.generation != generation || viewerState?.token != token) return
        resetViewer()
        key = null
        reconcileVisibility()
    }

    private fun resetViewer() {
        viewerEpoch++
        viewerState = null
        viewerActions = null
        renderedViewer = null
        allNavigations.forEach { nav ->
            dismissMenus(nav.toolbar)
            nav.viewControllers?.filterIsInstance<UIViewController>()?.filter {
                stacks[nav]?.firstOrNull { frame -> frame.content == it }?.screen is IosVaultScreen.Viewer
            }?.forEach { content ->
                content.navigationItem.title = null
                content.navigationItem.leftBarButtonItem = null
                content.navigationItem.rightBarButtonItems = null
                content.toolbarItems = null
            }
        }
    }

    private fun applyBarAppearance(nav: UINavigationController, viewer: Boolean) {
        // Media always uses the black canvas/white controls ladder. A custom
        // Lock UIButton does not receive UIBarButtonItem's adaptive foreground;
        // an explicit white tint keeps the privacy escape legible on media.
        val style = if (viewer) UIUserInterfaceStyleDark else UIUserInterfaceStyleUnspecified
        val tint = if (viewer) UIColor.whiteColor else accentTint
        nav.navigationBar.overrideUserInterfaceStyle = style
        nav.toolbar.overrideUserInterfaceStyle = style
        nav.navigationBar.tintColor = tint
        nav.toolbar.tintColor = tint
        (stacks[nav].orEmpty() + retiring[nav].orEmpty()).forEach { it.controls.lock.tintColor = tint }
    }

    /** Local publication closes the gap before the shared busy effect reaches its owner. */
    private val viewerControlsBlocked: Boolean
        get() = navigationBlocked || viewerState?.busy == true || viewerState?.infoVisible == true

    private fun viewerBlocksNavigation(nav: UINavigationController): Boolean {
        val state = viewerState ?: return false
        val top = stacks[nav]?.lastOrNull()?.screen as? IosVaultScreen.Viewer ?: return false
        return top.token == state.token && (state.busy || state.infoVisible)
    }

    private fun viewerAction(title: String, symbol: String, allowsDetailClose: Boolean = false, run: (IosViewerPanelActions) -> Unit): UIAction {
        val epoch = viewerEpoch
        val owner = WeakReference(this@IosVaultChrome)
        return UIAction.actionWithTitle(title, image = UIImage.systemImageNamed(symbol), identifier = null) { _ ->
            val presenter = owner.get()
            val state = presenter?.viewerState
            if (presenter != null && state != null && epoch == presenter.viewerEpoch && presenter.viewerIsTop &&
                !presenter.lockRequested && !presenter.privateModalActive && (!presenter.viewerControlsBlocked ||
                    (allowsDetailClose && state.infoVisible && !state.busy && !presenter.covered)) && !presenter.transitionInFlight) {
                presenter.viewerActions?.let { current -> current.onTouch(); run(current) }
            }
        }
    }

    private fun viewerItem(title: String, symbol: String, allowsDetailClose: Boolean = false, run: (IosViewerPanelActions) -> Unit): UIBarButtonItem =
        UIBarButtonItem(primaryAction = viewerAction(title, symbol, allowsDetailClose, run))
            .also { it.enabled = !viewerControlsBlocked ||
                (allowsDetailClose && viewerState?.infoVisible == true && viewerState?.busy == false && !covered) }

    private fun renderViewer() {
        if (!viewerIsTop) return
        if (privateModalActive) { freezeModalSourceItems(); return }
        val state = viewerState ?: return
        val content = viewerNavigation.topViewController ?: return
        val lock = stacks[viewerNavigation]?.lastOrNull()?.controls?.lock ?: return
        val renderKey = Triple(state, navigationBlocked, covered)
        if (renderedViewer == renderKey) { reconcileVisibility(); return }
        renderedViewer = renderKey
        // The emergency escape stays reachable when media controls auto-hide or are busy.
        content.navigationItem.title = state.title.takeIf { state.visible }
        content.navigationItem.setHidesBackButton(true, animated = false)
        content.navigationItem.leftBarButtonItem = if (state.visible) viewerItem("Back", "chevron.backward") { it.onBack() } else null
        content.navigationItem.rightBarButtonItems = buildList {
            add(UIBarButtonItem(customView = lock).also { IosAdaptiveBars.configureCustomItem?.invoke(it) })
            if (state.visible) add(viewerItem(if (state.infoVisible) "Hide info" else "Info", "info.circle", allowsDetailClose = true) { it.onInfo() })
        }
        content.toolbarItems = if (!state.visible) null else buildList {
            if (state.trashed) add(viewerItem("Restore", "arrow.uturn.backward") { it.onRestore() })
            else {
                add(viewerItem(if (state.favorite) "Remove from favorites" else "Add to favorites",
                    if (state.favorite) "heart.fill" else "heart") { it.onFavorite() })
                add(flexibleSpace())
                add(viewerItem("Export", "square.and.arrow.up") { it.onExport() })
                add(flexibleSpace())
                val epoch = viewerEpoch
                val owner = WeakReference(this@IosVaultChrome)
                val escape = UIAction.actionWithTitle("Lock and hide", image = UIImage.systemImageNamed("lock"), identifier = null) { _ ->
                    val presenter = owner.get()
                    if (presenter != null && epoch == presenter.viewerEpoch && presenter.viewerIsTop) presenter.panicNow()
                }
                val menu = UIMenu.menuWithTitle("", children = listOf(escape, viewerAction("Tags", "tag") { it.onTags() }))
                add(UIBarButtonItem(image = UIImage.systemImageNamed("ellipsis"), menu = menu)
                    .also { it.title = "More"; it.enabled = !viewerControlsBlocked })
            }
            add(flexibleSpace())
            add(viewerItem(if (state.trashed) "Delete permanently" else "Move to Trash", "trash") { it.onDelete() })
        }
        reconcileVisibility()
    }

    private fun selectionMenu(state: VaultUiState): List<Command> = buildList {
        add(Command("Select loaded items") { it.onSelectLoadedItems() })
        if (!state.trashOpen) {
            add(Command("Encrypted archive…", state.operation == null) { it.onEncryptedExportSelection() })
            if (state.openAlbum != null) add(Command("Move to album") { it.onMoveSelectionToAlbum() })
            add(Command("Tags") { it.onTagSelection() })
            add(Command("Add to favorites") { it.onSetSelectionFavorite(true) })
            add(Command("Remove from favorites") { it.onSetSelectionFavorite(false) })
            if (state.openAlbum != null) add(Command("Remove from album") { it.onRemoveSelectionFromAlbum() })
        }
    }

    private data class Command(val title: String, val enabled: Boolean = true, val run: (VaultActions) -> Unit)
    private data class ChromeKey(val destination: VaultDestination, val album: String?, val title: String,
        val selection: Int, val selectionIds: Set<String>, val selectionBlocked: Boolean,
        val busy: Boolean, val trash: Boolean, val scoped: Boolean,
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
