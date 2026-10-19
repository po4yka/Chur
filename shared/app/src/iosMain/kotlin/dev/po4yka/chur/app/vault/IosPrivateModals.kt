@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.LocalChurMotion
import dev.po4yka.chur.vault.VaultState
import kotlin.native.ref.WeakReference
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.*
import platform.darwin.NSObject

/** A modal visit never revives, even when the same route opens the same form again. */
internal class IosPrivateModalLease(val generation: Long, val epoch: Long,
    val presentation: PrivateModalPresentation = PrivateModalPresentation.Sheet,
    val anchor: PrivateModalAnchor = PrivateModalAnchor.Automatic) {
    var active by mutableStateOf(true)
        private set
    var body by mutableStateOf<(@Composable () -> Unit)?>(null)
    var guard: (() -> Boolean)? = null
    var onDismiss: (() -> Unit)? = null
    var panic: (() -> Unit)? = null
    var afterDismiss: (() -> Unit)? = null
    var controller: UIViewController? = null
    var delegate: IosPrivateModalDelegate? = null
    var presenting = false
    var dismissPending = false

    fun conceal() {
        active = false
        body = null
        guard = null
        onDismiss = null
        panic = null
        controller?.view?.let {
            it.endEditing(true)
            it.hidden = true
            it.accessibilityElementsHidden = true
            it.userInteractionEnabled = false
        }
        controller?.presentationController?.delegate = null
        controller?.popoverPresentationController?.delegate = null
        delegate = null
    }

    fun retire() {
        afterDismiss = null
        conceal()
    }
}

/** UIKit's delegates are weak. The lease retains this object, whose owners are weak. */
internal class IosPrivateModalDelegate(host: IosPrivateModalHost, lease: IosPrivateModalLease) :
    NSObject(), UIPopoverPresentationControllerDelegateProtocol {
    private val host = WeakReference(host)
    private val lease = WeakReference(lease)

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        lease.get()?.let { host.get()?.dismissed(it) }
    }

    override fun popoverPresentationControllerDidDismissPopover(popoverPresentationController: UIPopoverPresentationController) {
        lease.get()?.let { host.get()?.dismissed(it) }
    }
}

/** Owned by the app's UIKit root, including dialogs composed in the hidden route owner. */
internal class IosPrivateModalHost(controller: ChurController, chrome: IosVaultChrome) : PrivateModalPresenter {
    private val owner = WeakReference(controller)
    private val chrome = WeakReference(chrome)
    private var root: WeakReference<UIViewController>? = null
    private var epoch = 0L
    private var current: IosPrivateModalLease? = null
    private var pending: IosPrivateModalLease? = null

    fun attach(root: UIViewController) { this.root = WeakReference(root) }

    private fun currentGeneration(): Long = (owner.get()?.vaultState?.value as? VaultState.Unlocked)?.generation ?: 0L
    private fun admitted(lease: IosPrivateModalLease): Boolean =
        lease.epoch == epoch && lease.generation != 0L && currentGeneration() == lease.generation && owner.get()?.route?.value == AppRoute.Vault

    @Composable
    override fun Present(
        onDismissRequest: () -> Unit,
        confirmButton: @Composable () -> Unit,
        modifier: Modifier,
        dismissButton: (@Composable () -> Unit)?,
        title: (@Composable () -> Unit)?,
        text: (@Composable () -> Unit)?,
        presentation: PrivateModalPresentation,
        anchor: PrivateModalAnchor,
    ) {
        // A new UIKit controller must keep its own WindowInfo, WindowInsets,
        // focus and keyboard owners. Forward only app/theme locals, never the
        // originating controller's entire CompositionLocalContext.
        val colors = LocalChurColors.current
        val motion = LocalChurMotion.current
        val selectionColors = LocalTextSelectionColors.current
        val colorScheme = MaterialTheme.colorScheme
        val typography = MaterialTheme.typography
        val shapes = MaterialTheme.shapes
        val scenes = LocalIosVaultScenes.current
        val vaultChrome = LocalIosVaultChrome.current
        val flowGuard = LocalPrivateModalGuard.current
        val scene = LocalIosVaultScene.current
        val operation = owner.get()?.activeOperation?.collectAsState()?.value
        val observedEligibility = scene?.canObserve() != false && operation == null && flowGuard()
        val guard = { scene?.canObserve() != false && owner.get()?.activeOperation?.value == null && flowGuard() }
        val panic = LocalPrivatePanic.current
        val lease = remember(this, presentation, anchor) { IosPrivateModalLease(currentGeneration(), epoch, presentation, anchor) }
        DisposableEffect(lease) { onDispose { abandon(lease) } }
        SideEffect {
            if (lease.active) {
                lease.guard = guard
                lease.onDismiss = onDismissRequest
                lease.panic = panic
                lease.body = {
                    CompositionLocalProvider(
                        LocalChurColors provides colors,
                        LocalChurMotion provides motion,
                        LocalTextSelectionColors provides selectionColors,
                        LocalIosVaultScenes provides scenes,
                        LocalIosVaultScene provides scene,
                        LocalIosVaultChrome provides vaultChrome,
                        LocalPrivateModalPresenter provides this@IosPrivateModalHost,
                        LocalPrivateModalGuard provides flowGuard,
                        LocalPrivatePanic provides { if (lease.active && admitted(lease)) lease.panic?.invoke() },
                        LocalPrivateModalActionDispatcher provides { action -> dispatch(lease, action) },
                    ) {
                        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
                            ModalBody(lease, modifier, title, text, dismissButton, confirmButton)
                        }
                    }
                }
                if (!observedEligibility) close(lease, dismissed = true)
                else show(lease, presentation, anchor)
            }
        }
    }

    private fun show(lease: IosPrivateModalLease, presentation: PrivateModalPresentation, anchor: PrivateModalAnchor) {
        if (!lease.active || !admitted(lease)) { lease.retire(); return }
        if (current === lease) return
        if (current != null) { pending = lease; return }
        current = lease
        val binding = WeakReference(this)
        val visit = WeakReference(lease)
        val anchorItem = chrome.get()?.privateModalAnchor(anchor)
        chrome.get()?.setPrivateModalActive(true)
        chrome.get()?.preparePrivateModal {
            val host = binding.get()
            val currentLease = visit.get()
            if (host != null && currentLease != null) host.present(currentLease, presentation, anchorItem)
        }
    }

    private fun present(lease: IosPrivateModalLease, presentation: PrivateModalPresentation, anchor: UIBarButtonItem?) {
        if (current !== lease || !lease.active || !admitted(lease) || lease.guard?.invoke() != true) {
            close(lease, dismissed = true)
            return
        }
        val presenter = root?.get()
        // A selected flow never takes ownership of a Photos/Files/share controller.
        if (presenter == null || presenter.view.window == null || presenter.presentedViewController != null) {
            close(lease, dismissed = true)
            return
        }
        val reference = WeakReference(lease)
        val content = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
            reference.get()?.takeIf { it.active }?.body?.invoke()
        }
        val native = IosPrivateModalController(content)
        lease.controller = native
        val delegate = IosPrivateModalDelegate(this, lease)
        lease.delegate = delegate
        configurePrivateModal(native, presenter, presentation, anchor)
        native.presentationController?.delegate = delegate
        native.popoverPresentationController?.delegate = delegate
        lease.presenting = true
        val host = WeakReference(this)
        presenter.presentViewController(native, animated = true) {
            lease.presenting = false
            if (lease.dismissPending) {
                lease.dismissPending = false
                host.get()?.dismissNative(lease) ?: native.dismissViewControllerAnimated(false, completion = null)
            }
        }
    }

    internal fun dispatch(lease: IosPrivateModalLease, action: () -> Unit) {
        if (current !== lease || !lease.active || !admitted(lease) || lease.guard?.invoke() != true) return
        close(lease, action = action)
    }

    internal fun dismissed(lease: IosPrivateModalLease) {
        if (current === lease && lease.active) close(lease, dismissed = true, alreadyDismissed = true)
    }

    private fun close(lease: IosPrivateModalLease, action: (() -> Unit)? = null,
        dismissed: Boolean = false, alreadyDismissed: Boolean = false) {
        if (!lease.active) return
        val guard = lease.guard
        val callback = if (dismissed) lease.onDismiss else action
        lease.afterDismiss = callback?.let { continuation ->
            { if (dismissed || guard?.invoke() == true) continuation() }
        }
        lease.conceal()
        if (pending === lease) pending = null
        if (current !== lease) {
            val continuation = lease.afterDismiss
            lease.afterDismiss = null
            if (admitted(lease)) continuation?.invoke()
            return
        }
        if (alreadyDismissed) finish(lease) else dismissNative(lease)
    }

    private fun dismissNative(lease: IosPrivateModalLease) {
        if (lease.presenting) { lease.dismissPending = true; return }
        val native = lease.controller
        if (native?.presentingViewController == null) finish(lease)
        else {
            val host = WeakReference(this)
            val visit = WeakReference(lease)
            native.dismissViewControllerAnimated(false) {
                visit.get()?.let { host.get()?.finish(it) }
            }
        }
    }

    private fun finish(lease: IosPrivateModalLease) {
        if (current !== lease) return
        current = null
        lease.controller = null
        val callback = lease.afterDismiss
        lease.afterDismiss = null
        chrome.get()?.setPrivateModalActive(false)
        if (admitted(lease)) callback?.invoke()
        // A newer composition can replace a form while UIKit finishes dismissal.
        val next = pending
        pending = null
        if (next != null && next.active) {
            show(next, next.presentation, next.anchor)
        }
    }

    private fun abandon(lease: IosPrivateModalLease) {
        lease.afterDismiss = null
        close(lease)
        if (pending === lease) { pending = null; lease.retire() }
    }

    /** Revoke closures and conceal synchronously, before any UIKit dismissal callback. */
    fun clear() {
        epoch++
        pending?.retire()
        pending = null
        val old = current
        current = null
        old?.retire()
        chrome.get()?.setPrivateModalActive(false)
        if (old != null) dismissNative(old)
    }
}

/** UIKit owns the modal's actual safe area and keyboard avoidance exactly once. */
internal class IosPrivateModalController(private val content: UIViewController) : UIViewController(nibName = null, bundle = null) {
    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.systemBackgroundColor
        addChildViewController(content)
        content.view.setTranslatesAutoresizingMaskIntoConstraints(false)
        view.addSubview(content.view)
        // Compose 1.12 unions the window root's safe insets into every scene,
        // including sheets. Constrain this child to UIKit's own modal guides
        // instead; Compose content must not apply safeDrawing/imePadding again.
        val safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activateConstraints(listOf(
            content.view.leadingAnchor.constraintEqualToAnchor(safe.leadingAnchor),
            content.view.trailingAnchor.constraintEqualToAnchor(safe.trailingAnchor),
            content.view.topAnchor.constraintEqualToAnchor(safe.topAnchor),
            content.view.bottomAnchor.constraintEqualToAnchor(view.keyboardLayoutGuide.topAnchor),
        ))
        content.didMoveToParentViewController(this)
    }
}

/** Configuration is separately testable without presenting a synthetic UIWindow. */
internal fun configurePrivateModal(native: UIViewController, presenter: UIViewController,
    presentation: PrivateModalPresentation, anchor: UIBarButtonItem?) {
    val compact = presenter.traitCollection.horizontalSizeClass == UIUserInterfaceSizeClassCompact
    native.modalPresentationStyle = if (presentation == PrivateModalPresentation.Popover && !compact)
        UIModalPresentationPopover else UIModalPresentationPageSheet
    native.setPreferredContentSize(CGSizeMake(420.0, 560.0))
    native.view.setAccessibilityViewIsModal(true)
    fun configureSheet(sheet: UISheetPresentationController?) {
        sheet ?: return
        sheet.detents = listOf(UISheetPresentationControllerDetent.mediumDetent(), UISheetPresentationControllerDetent.largeDetent())
        sheet.prefersGrabberVisible = true
        sheet.prefersScrollingExpandsWhenScrolledToEdge = true
        sheet.largestUndimmedDetentIdentifier = null
    }
    configureSheet(native.sheetPresentationController)
    native.popoverPresentationController?.let { popover ->
        if (anchor != null) {
            // UIKit publicly adopts this protocol in a UIBarButtonItem category
            // since iOS 16 (below our iOS 18 floor). Kotlin's generated class
            // supertypes omit Objective-C category protocol adoption.
            popover.sourceItem = anchor as UIPopoverPresentationControllerSourceItemProtocol
        } else {
            popover.sourceView = presenter.view
            popover.sourceRect = presenter.view.bounds.useContents {
                CGRectMake(size.width / 2.0, size.height / 2.0, 1.0, 1.0)
            }
            popover.permittedArrowDirections = 0uL
        }
        popover.passthroughViews = emptyList<UIView>()
        configureSheet(popover.adaptiveSheetPresentationController)
    }
}

@Composable
private fun ModalBody(lease: IosPrivateModalLease, modifier: Modifier, title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?, dismissButton: (@Composable () -> Unit)?, confirmButton: @Composable () -> Unit) {
    val colors = LocalChurColors.current
    CompositionLocalProvider(LocalContentColor provides colors.ink) {
        Column(Modifier.fillMaxSize().background(colors.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp).then(modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { if (lease.active) lease.panic?.invoke() }, modifier = Modifier.fillMaxWidth()) {
                Text("Lock and hide")
            }
            if (title != null) {
                Box(Modifier.fillMaxWidth().semantics { heading() }) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) { text?.invoke() }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
