@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.notes.NoteStore
import dev.po4yka.chur.sync.FileSyncStateStore
import dev.po4yka.chur.sync.SyncCoordinator
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
fun ChurViewController(controller: ChurController, gate: GateResult): UIViewController =
    ChurRootViewController(
        ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
            val route by controller.route.collectAsState()
            val state by controller.vaultState.collectAsState()
            ChurApp(gate = gate, route = route) {
                IosRoutes(controller = controller, route = route, vaultState = state)
            }
        },
    )

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
) : UIViewController(nibName = null, bundle = null) {
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
    }

    override fun preferredStatusBarStyle(): UIStatusBarStyle =
        if (viewerChrome == null) UIStatusBarStyleDefault else UIStatusBarStyleLightContent

    override fun prefersStatusBarHidden(): Boolean = viewerChrome == false

    override fun preferredStatusBarUpdateAnimation(): UIStatusBarAnimation =
        UIStatusBarAnimation.UIStatusBarAnimationFade
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

/** Bind the one controller and sync engine used by the scene and background task. */
fun churController(privacy: IosPrivacyCover, exports: ExportSink): ChurController {
    val clock = { (NSDate().timeIntervalSince1970 * 1000).toLong() }
    val sync = SyncCoordinator(store = churSyncStateStore(), clock = clock)
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
        autoLockSetting = AutoLockSetting(
            reader = { NSUserDefaults.standardUserDefaults.stringForKey("autoLock") },
            writer = { NSUserDefaults.standardUserDefaults.setObject(it, forKey = "autoLock") },
        ),
        clock = clock,
        notes = churNoteStore(),
        sync = sync,
        // iOS shows its local network alert as a run's first connection to a
        // local server goes out. It has no link check, `IOS.md` §27.
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

/**
 * Where the sync engine keeps its configuration and pull cursors.
 *
 * It returns the bound store rather than the path, for the reason
 * [churNoteStore] does. The file holds the device's transport token — a bearer
 * credential — so it lives beside the vault root, in the directory the Xcode
 * project marks excluded from iCloud and iTunes backup, which
 * `SYNC_PROTOCOL_V1.md` §7 (SEC-034) requires of sync state. The store
 * applies Data Protection and backup exclusion when it accesses this path.
 */
fun churSyncStateRoot(): String {
    val documents = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    ).first() as String
    val root = "$documents/chur-sync"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = root,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return root
}

fun churSyncStateStore(): FileSyncStateStore = FileSyncStateStore("${churSyncStateRoot()}/state.json")
