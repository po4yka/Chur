@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.po4yka.chur.app

import androidx.compose.runtime.CompositionLocalProvider
import dev.po4yka.chur.app.vault.IosPrivateModalHost
import dev.po4yka.chur.app.vault.LocalPrivateModalPresenter
import dev.po4yka.chur.app.vault.IosVaultChrome
import dev.po4yka.chur.app.vault.LocalIosVaultChrome
import dev.po4yka.chur.app.vault.IosVaultScenes
import dev.po4yka.chur.app.vault.LocalIosVaultScenes
import dev.po4yka.chur.app.vault.AdaptiveRegion
import dev.po4yka.chur.app.vault.adaptiveContentRegion
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.notes.NoteStore
import dev.po4yka.chur.sync.PeerSyncCoordinator
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIStatusBarAnimation
import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDefault
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import kotlin.native.ref.WeakReference

/**
 * The iOS entry point.
 *
 * The Xcode project in `apps/iosApp` creates one [ChurController], presents
 * this controller, and drives the two transitions no Kotlin code can see: the
 * privacy cover on `sceneWillResignActive` and `sceneDidEnterBackground`, and
 * the picker and share sheet.
 * Everything else is the shared controller, so the two hosts differ in binding
 * and nowhere else.
 *
 * The function takes no `@Composable` parameter, and that is a constraint
 * rather than a style: Kotlin/Native cannot map a composable lambda to
 * Objective-C, so a signature with one is silently absent from the framework
 * header instead of being an error.
 *
 * The controller does not pan the view to a focused field. [ChurApp] already
 * ends every route above the keyboard, `DESIGN.md` §25.5, and a pan on top of
 * that would move the field a second time.
 */
fun ChurViewController(controller: ChurController, gate: GateResult, privacy: IosPrivacyCover): UIViewController {
    val chrome = IosVaultChrome()
    val modals = IosPrivateModalHost(controller, chrome)
    val scenes = IosVaultScenes(controller, gate, chrome, modals)
    val modalReference = WeakReference(modals)
    chrome.onClearPrivateModals = { modalReference.get()?.clear() }
    val compose = ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
        val route by controller.route.collectAsState()
        val state by controller.vaultState.collectAsState()
        CompositionLocalProvider(LocalIosVaultChrome provides chrome, LocalIosVaultScenes provides scenes,
            LocalPrivateModalPresenter provides modals) {
            ChurApp(gate = gate, route = route) {
                IosRoutes(controller = controller, route = route, vaultState = state)
            }
        }
    }
    val root = ChurRootViewController(compose, chrome)
    modals.attach(root)
    val rootReference = WeakReference(root)
    chrome.onGuideLayout = { rootReference.get()?.view?.setNeedsLayout() }
    val controllerReference = WeakReference(controller)
    val chromeReference = WeakReference(chrome)
    val scenesReference = WeakReference(scenes)
    scenes.viewerChrome = { rootReference.get()?.viewerChrome = it }
    // Lock conceals the shared route before enabling its logical cover. Retire
    // UIKit on that same UI turn, including controller-driven/background locks.
    privacy.onStateChange = { enabled ->
        if (enabled && controllerReference.get()?.route?.value != AppRoute.Vault) {
            chromeReference.get()?.clear()
            scenesReference.get()?.clear()
            rootReference.get()?.viewerChrome = null
        }
    }
    return root
}

/**
 * The root the Xcode host presents, with the Compose controller as its child.
 *
 * UIKit asks the root controller how to draw the status bar. Compose
 * deprecated its own hook for that in favour of a parent controller, and this
 * is that parent. The viewer sets [viewerChrome]: `DESIGN.md` §6.2 gives the
 * viewer a black canvas in both themes, so the status bar is light over it,
 * and §13.1 has a tap hide the chrome, so the status bar goes with it.
 */
internal class ChurRootViewController(
    private val content: UIViewController,
    private val vaultChrome: IosVaultChrome,
) : UIViewController(nibName = null, bundle = null) {
    private var occlusions: List<AdaptiveRegion> = emptyList()
    private var divisions: List<AdaptiveRegion> = emptyList()
    private var statusBarInSplit = false

    internal fun updateRegions(occlusions: List<AdaptiveRegion>, divisions: List<AdaptiveRegion>) {
        if (this.occlusions == occlusions && this.divisions == divisions) return
        this.occlusions = occlusions
        this.divisions = divisions
        view.setNeedsLayout()
    }

    /** `null` outside the viewer, else whether the viewer shows its chrome. */
    var viewerChrome: Boolean? = null
        set(value) {
            if (field == value) return
            field = value
            UIView.animateWithDuration(0.15) { setNeedsStatusBarAppearanceUpdate() }
        }

    override fun viewDidLoad() {
        super.viewDidLoad()
        addChildViewController(content)
        content.view.setFrame(view.bounds)
        content.view.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
        view.addSubview(content.view)
        content.didMoveToParentViewController(this)
        val tabs = vaultChrome.presentationController
        addChildViewController(tabs)
        tabs.view.setFrame(view.bounds)
        tabs.view.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
        view.addSubview(tabs.view)
        tabs.didMoveToParentViewController(this)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val (width, height) = view.bounds.useContents { size.width to size.height }
        // Authentication and public content must not bridge an active fold.
        val region = adaptiveContentRegion(width, height, occlusions, divisions)
        content.view.setFrame(CGRectMake(region.x, region.y, region.width, region.height))
        val vaultHeight = (height - vaultChrome.guideBottomInset.coerceAtMost(height * .4)).coerceAtLeast(0.0)
        vaultChrome.presentationController.view.setFrame(CGRectMake(0.0, 0.0, width, vaultHeight))
        vaultChrome.updateGeometry(width, vaultHeight, occlusions, divisions)
        if (statusBarInSplit != vaultChrome.dualPane) {
            statusBarInSplit = vaultChrome.dualPane
            setNeedsStatusBarAppearanceUpdate()
        }
    }

    override fun preferredStatusBarStyle(): UIStatusBarStyle =
        if (viewerChrome == null || vaultChrome.dualPane) UIStatusBarStyleDefault else UIStatusBarStyleLightContent

    override fun prefersStatusBarHidden(): Boolean = viewerChrome == false && !vaultChrome.dualPane

    override fun preferredStatusBarUpdateAnimation(): UIStatusBarAnimation =
        UIStatusBarAnimation.UIStatusBarAnimationFade
}

/** Geometry only: no route, media identity or vault data crosses this bridge. */
fun churUpdateLayout(controller: UIViewController, occlusions: List<AdaptiveRegion>, divisions: List<AdaptiveRegion>) {
    (controller as? ChurRootViewController)?.updateRegions(occlusions, divisions)
}

/** Check the linked native library before opening a runtime. */
fun churNativeGate(releaseApplication: Boolean): GateResult {
    val handshake = ChurVault.handshake()
    return gate(
        NativeHandshake(
            abiVersionMajor = handshake.major.toUInt(),
            abiVersionMinor = handshake.minor.toUInt(),
            capabilities = handshake.capabilities.toULong(),
            objectFormatMin = handshake.objectFormatMin.toUInt(),
            objectFormatMax = handshake.objectFormatMax.toUInt(),
            keySlotFormatMin = handshake.keySlotFormatMin.toUInt(),
            keySlotFormatMax = handshake.keySlotFormatMax.toUInt(),
            buildFlavor = handshake.buildFlavor.toUInt(),
        ),
        releaseApplication = releaseApplication,
    )
}

/** Bind the one controller and foreground peer coordinator used by the scenes. */
fun churController(privacy: IosPrivacyCover, exports: ExportSink): ChurController {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    val freshInstall = runCatching {
        val existing = NSFileManager.defaultManager.contentsOfDirectoryAtPath(documents, null) ?: error("Cannot inspect existing files")
        existing.isEmpty() && listOf("publicShell", "autoLock", "lockWholeApp", "deviceSlotStrict").all {
            NSUserDefaults.standardUserDefaults.objectForKey(it) == null
        }
    }.getOrDefault(false)
    val guide = dev.po4yka.chur.app.onboarding.GuideCoordinator(
        dev.po4yka.chur.app.onboarding.platformGuideStore("$documents/guide-settings"), freshInstall)
    val obsolete = "$documents/chur-sync"
    if (NSFileManager.defaultManager.fileExistsAtPath(obsolete))
        check(NSFileManager.defaultManager.removeItemAtPath(obsolete, error = null)) { "obsolete network state could not be removed" }
    val clock = { (NSDate().timeIntervalSince1970 * 1000).toLong() }
    val sync = PeerSyncCoordinator(clock = clock)
    val controller = ChurController(
        storageRoot = churStorageRoot(),
        privacy = privacy,
        exports = exports,
        appleDeviceUnlock = IosAppleDeviceUnlock(),
        deviceSlotPolicy = DeviceSlotPolicySetting(
            reader = { NSUserDefaults.standardUserDefaults.boolForKey("deviceSlotStrict") },
            writer = { NSUserDefaults.standardUserDefaults.setBool(it, forKey = "deviceSlotStrict") },
        ),
        appLockSetting = AppLockSetting(
            reader = { NSUserDefaults.standardUserDefaults.boolForKey("lockWholeApp") },
            writer = { NSUserDefaults.standardUserDefaults.setBool(it, forKey = "lockWholeApp") },
        ),
        publicUtilities = dev.po4yka.chur.app.publicshell.publicUtilityStore("$documents/public/utilities", "$documents/utility-settings"),
        guide = guide,
        publicShellSetting = PublicShellSetting(
            reader = { NSUserDefaults.standardUserDefaults.stringForKey("publicShell") },
            writer = { NSUserDefaults.standardUserDefaults.setObject(it, forKey = "publicShell") },
        ),
        autoLockSetting = AutoLockSetting(
            reader = { NSUserDefaults.standardUserDefaults.stringForKey("autoLock") },
            writer = { NSUserDefaults.standardUserDefaults.setObject(it, forKey = "autoLock") },
        ),
        clock = clock,
        notes = churNoteStore(),
        sync = sync,
        contentExtractor = dev.po4yka.chur.app.vault.IosContentTextExtractor(),
        // Protect the system local-network prompt during an explicit peer connection.
        localNetworkAlert = true,
    )
    sync.bind(RepositorySyncBoundary(controller.vault))
    return controller
}

/**
 * The storage root, `docs/ARCHITECTURE.md` §14.4.
 *
 * The documents directory is app-private. The Xcode project marks it excluded
 * from iCloud and iTunes backup, which `PLAINTEXT_LIFECYCLE.md` §5 requires of
 * every directory Chur writes into, and this creates it so the exclusion has
 * something to apply to at first launch.
 */
fun churStorageRoot(): String {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    ).first() as String
    val root = "$documents/chur"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = root,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return root
}

/**
 * Where the public shell keeps its notes.
 *
 * It returns the bound store rather than the path, because a path in the
 * framework header is an invitation for the host to open the file itself, and
 * the file's format belongs to `:shared:feature-notes`.
 *
 * The file sits beside the vault root rather than inside it. Nothing forbids a
 * public file in that directory, but a directory that holds only the vault is
 * one an inspection can reason about, and `PLAINTEXT_LIFECYCLE.md` §1 draws the
 * line between the two stores exactly here.
 */
fun churNoteStore(): NoteStore {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    ).first() as String
    return FileNoteStore("$documents/notes.json")
}
