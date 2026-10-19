@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.uikit.OnFocusBehavior
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurApp
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.GateResult
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.vault.VaultState
import kotlin.native.ref.WeakReference
import platform.UIKit.UIViewController

internal val LocalIosVaultScenes = compositionLocalOf<IosVaultScenes?> { null }
internal val LocalIosVaultScene = compositionLocalOf<IosVaultScene?> { null }

/** A single presentation lease. Retired leases are never revived or reused. */
internal class IosVaultScene(
    val generation: Long,
    controller: ChurController,
    chrome: IosVaultChrome,
) {
    private val controller = WeakReference(controller)
    private val chrome = WeakReference(chrome)
    var active by mutableStateOf(false)
        internal set
    var retired by mutableStateOf(false)
        private set
    internal var content by mutableStateOf<(@Composable (IosVaultScene) -> Unit)?>(null)

    private fun currentOwner(): Boolean {
        val owner = controller.get() ?: return false
        return !retired && owner.route.value == AppRoute.Vault &&
            (owner.vaultState.value as? VaultState.Unlocked)?.generation == generation
    }

    fun canAct(): Boolean = active && currentOwner() && chrome.get()?.transitionInFlight == false
    fun canObserve(): Boolean = active && currentOwner()
    fun canEscape(): Boolean = currentOwner()

    internal fun retire() {
        retired = true
        active = false
        content = null
    }
}

/** Pure render slots: the shared route owns all queries, workers and dialogs. */
internal class IosVaultScenes(
    controller: ChurController,
    private val gate: GateResult,
    chrome: IosVaultChrome,
) {
    private val controller = WeakReference(controller)
    private val chrome = WeakReference(chrome)
    private val slots = mutableMapOf<IosVaultScreen, IosVaultScene>()
    private var generation = 0L
    private var activeScreen: IosVaultScreen? = null
    var viewerChrome: ((Boolean?) -> Unit)? = null

    fun begin(generation: Long) {
        require(generation != 0L)
        clear()
        this.generation = generation
    }

    private fun scene(screen: IosVaultScreen): IosVaultScene = slots.getOrPut(screen) {
        check(generation != 0L) { "A native scene needs a presentation visit" }
        IosVaultScene(generation, checkNotNull(controller.get()), checkNotNull(chrome.get())).also {
            it.active = screen == activeScreen
        }
    }

    fun publish(screen: IosVaultScreen, content: @Composable (IosVaultScene) -> Unit) {
        scene(screen).content = content
    }

    fun activate(screen: IosVaultScreen?) {
        activeScreen = screen
        slots.forEach { (key, scene) -> scene.active = key == screen && !scene.retired }
    }

    fun retire(screen: IosVaultScreen) {
        slots.remove(screen)?.retire()
        if (activeScreen == screen) activeScreen = null
    }

    fun clear() {
        slots.values.forEach(IosVaultScene::retire)
        slots.clear()
        activeScreen = null
        generation = 0L
        viewerChrome?.invoke(null)
    }

    fun createController(screen: IosVaultScreen): UIViewController {
        val lease = scene(screen)
        val store = WeakReference(this)
        val controllerOwner = controller
        val chromeOwner = chrome
        val admissionGate = gate
        // UIKit retains this block, not a controller owned by the store. Empty
        // retired leases may outlive a transition without retaining its body.
        return ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
            val currentStore = store.get()
            val owner = controllerOwner.get()
            val route = owner?.route?.collectAsState()?.value
            val state = owner?.vaultState?.collectAsState()?.value
            val focus = LocalFocusManager.current
            DisposableEffect(lease.active) {
                if (!lease.active) focus.clearFocus(force = true)
                onDispose { focus.clearFocus(force = true) }
            }
            ChurApp(gate = admissionGate, route = AppRoute.Vault) {
                CompositionLocalProvider(
                    LocalIosVaultScenes provides currentStore,
                    LocalIosVaultScene provides lease,
                    LocalIosVaultChrome provides chromeOwner.get(),
                    LocalPrivatePanic provides {
                        if (lease.canEscape()) chromeOwner.get()?.requestPanic()
                    },
                ) {
                    if (currentStore != null && !lease.retired && route == AppRoute.Vault &&
                        (state as? VaultState.Unlocked)?.generation == lease.generation
                    ) {
                        lease.content?.invoke(lease) ?: PendingScene()
                    } else EmptyScene()
                }
            }
        }
    }
}

/** An unvisited ancestor waits for the sole route owner's committed-pop query. */
@Composable
private fun PendingScene() {
    val colors = LocalChurColors.current
    Box(Modifier.fillMaxSize().background(colors.canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = colors.accent)
            Text("Loading…", color = colors.ink)
        }
    }
}

@Composable
private fun EmptyScene() {
    Box(Modifier.fillMaxSize().background(LocalChurColors.current.canvas))
}

/** Every retained outgoing callback is tied to its never-reused scene lease. */
internal fun VaultActions.forScene(scene: IosVaultScene): VaultActions {
    val source = WeakReference(this)
    return copy(
        onDestination = { p0 -> if (scene.canAct()) source.get()?.onDestination?.invoke(p0) },
        onOpen = { p0 -> if (scene.canAct()) source.get()?.onOpen?.invoke(p0) },
        onToggleSelection = { p0 -> if (scene.canAct()) source.get()?.onToggleSelection?.invoke(p0) },
        onImport = { if (scene.canAct()) source.get()?.onImport?.invoke() },
        onSearch = { p0 -> if (scene.canAct()) source.get()?.onSearch?.invoke(p0) },
        onIndexContent = { if (scene.canAct()) source.get()?.onIndexContent?.invoke() },
        onRebuildContentIndex = { if (scene.canAct()) source.get()?.onRebuildContentIndex?.invoke() },
        onCancelContentIndex = { if (scene.canAct()) source.get()?.onCancelContentIndex?.invoke() },
        onClearContentIndex = { if (scene.canAct()) source.get()?.onClearContentIndex?.invoke() },
        onOpenAlbum = { p0 -> if (scene.canAct()) source.get()?.onOpenAlbum?.invoke(p0) },
        onCloseAlbum = { if (scene.canAct()) source.get()?.onCloseAlbum?.invoke() },
        onCreateAlbum = { if (scene.canAct()) source.get()?.onCreateAlbum?.invoke() },
        onShowAllMedia = { if (scene.canAct()) source.get()?.onShowAllMedia?.invoke() },
        onShowFavorites = { if (scene.canAct()) source.get()?.onShowFavorites?.invoke() },
        onOpenPrivateNotes = if (this.onOpenPrivateNotes == null) null else ({ if (scene.canAct()) source.get()?.onOpenPrivateNotes?.invoke() }),
        onShowTags = { if (scene.canAct()) source.get()?.onShowTags?.invoke() },
        onShowTrash = { if (scene.canAct()) source.get()?.onShowTrash?.invoke() },
        onShowQuarantine = { if (scene.canAct()) source.get()?.onShowQuarantine?.invoke() },
        onEmptyTrash = { if (scene.canAct()) source.get()?.onEmptyTrash?.invoke() },
        onDeleteDamagedFiles = { if (scene.canAct()) source.get()?.onDeleteDamagedFiles?.invoke() },
        onRestoreTrash = { if (scene.canAct()) source.get()?.onRestoreTrash?.invoke() },
        onRenameAlbum = { p0, p1 -> if (scene.canAct()) source.get()?.onRenameAlbum?.invoke(p0, p1) },
        onDeleteAlbum = { p0 -> if (scene.canAct()) source.get()?.onDeleteAlbum?.invoke(p0) },
        onExportAlbum = { p0 -> if (scene.canAct()) source.get()?.onExportAlbum?.invoke(p0) },
        onEncryptedExportAlbum = { p0 -> if (scene.canAct()) source.get()?.onEncryptedExportAlbum?.invoke(p0) },
        onMoveAlbum = { p0, p1, p2 -> if (scene.canAct()) source.get()?.onMoveAlbum?.invoke(p0, p1, p2) },
        onMoveAlbumMember = { p0, p1 -> if (scene.canAct()) source.get()?.onMoveAlbumMember?.invoke(p0, p1) },
        onDropMediaIntoAlbum = { p0, p1 -> if (scene.canAct()) source.get()?.onDropMediaIntoAlbum?.invoke(p0, p1) },
        onLoadMore = { if (scene.canObserve()) source.get()?.onLoadMore?.invoke() },
        onVisibleMediaChanged = { p0 -> if (scene.canObserve()) source.get()?.onVisibleMediaChanged?.invoke(p0) },
        onLock = { if (scene.canEscape()) source.get()?.onLock?.invoke() },
        onPanic = { if (scene.canEscape()) source.get()?.onPanic?.invoke() },
        onVerifyAll = { if (scene.canAct()) source.get()?.onVerifyAll?.invoke() },
        onCheckSystemSecurity = { if (scene.canAct()) source.get()?.onCheckSystemSecurity?.invoke() },
        onAddRecoverySlot = { if (scene.canAct()) source.get()?.onAddRecoverySlot?.invoke() },
        onChangePassword = { p0 -> if (scene.canAct()) source.get()?.onChangePassword?.invoke(p0) },
        onCreateBackup = { if (scene.canAct()) source.get()?.onCreateBackup?.invoke() },
        onEncryptExternalFiles = if (this.onEncryptExternalFiles == null) null else ({ if (scene.canAct()) source.get()?.onEncryptExternalFiles?.invoke() }),
        onImportArchive = { if (scene.canAct()) source.get()?.onImportArchive?.invoke() },
        onRestoreArchive = { if (scene.canAct()) source.get()?.onRestoreArchive?.invoke() },
        onCreateSecondIdentity = { if (scene.canAct()) source.get()?.onCreateSecondIdentity?.invoke() },
        onAddDeviceSlot = { if (scene.canAct()) source.get()?.onAddDeviceSlot?.invoke() },
        onToggleDeviceSlotPolicy = { if (scene.canAct()) source.get()?.onToggleDeviceSlotPolicy?.invoke() },
        onToggleAppLock = { if (scene.canAct()) source.get()?.onToggleAppLock?.invoke() },
        onSetAutoLock = { p0 -> if (scene.canAct()) source.get()?.onSetAutoLock?.invoke(p0) },
        onDeviceControl = { if (scene.canAct()) source.get()?.onDeviceControl?.invoke() },
        onAddQuickSettingsLock = { if (scene.canAct()) source.get()?.onAddQuickSettingsLock?.invoke() },
        onSelectLoadedItems = { if (scene.canAct()) source.get()?.onSelectLoadedItems?.invoke() },
        onClearSelection = { if (scene.canAct()) source.get()?.onClearSelection?.invoke() },
        onExportSelection = { if (scene.canAct()) source.get()?.onExportSelection?.invoke() },
        onEncryptedExportSelection = { if (scene.canAct()) source.get()?.onEncryptedExportSelection?.invoke() },
        onAddSelectionToAlbum = { if (scene.canAct()) source.get()?.onAddSelectionToAlbum?.invoke() },
        onMoveSelectionToAlbum = { if (scene.canAct()) source.get()?.onMoveSelectionToAlbum?.invoke() },
        onTagSelection = { if (scene.canAct()) source.get()?.onTagSelection?.invoke() },
        onSetSelectionFavorite = { p0 -> if (scene.canAct()) source.get()?.onSetSelectionFavorite?.invoke(p0) },
        onRemoveSelectionFromAlbum = { if (scene.canAct()) source.get()?.onRemoveSelectionFromAlbum?.invoke() },
        onDeleteSelection = { if (scene.canAct()) source.get()?.onDeleteSelection?.invoke() },
        onRestoreSelection = { if (scene.canAct()) source.get()?.onRestoreSelection?.invoke() },
        onMediaViewChange = { p0 -> if (scene.canAct()) source.get()?.onMediaViewChange?.invoke(p0) },
        onThumbnailsConcealedChange = { p0 -> if (scene.canAct()) source.get()?.onThumbnailsConcealedChange?.invoke(p0) },
        onAlbumViewChange = { p0 -> if (scene.canAct()) source.get()?.onAlbumViewChange?.invoke(p0) },
        onSortChange = { p0 -> if (scene.canAct()) source.get()?.onSortChange?.invoke(p0) },
        onKindsChange = { p0 -> if (scene.canAct()) source.get()?.onKindsChange?.invoke(p0) },
        onAlbumOrderChange = { p0 -> if (scene.canAct()) source.get()?.onAlbumOrderChange?.invoke(p0) },
        onAlbumFilterChange = { p0 -> if (scene.canAct()) source.get()?.onAlbumFilterChange?.invoke(p0) },
        onCancelOperation = { if (scene.canAct()) source.get()?.onCancelOperation?.invoke() },
        onNoticeShown = { p0 -> if (scene.canObserve()) source.get()?.onNoticeShown?.invoke(p0) },
        onConfigureSync = { p0, p1 -> if (scene.canAct()) source.get()?.onConfigureSync?.invoke(p0, p1) },
        onLoadPairedDevices = { if (scene.canAct()) source.get()?.onLoadPairedDevices?.invoke() },
        onRevokePairedDevice = { p0 -> if (scene.canAct()) source.get()?.onRevokePairedDevice?.invoke(p0) },
        onPrepareDevicePairing = { if (scene.canAct()) source.get()?.onPrepareDevicePairing?.invoke() },
        onInspectDevicePairing = { p0 -> if (scene.canAct()) source.get()?.onInspectDevicePairing?.invoke(p0) },
        onApproveDevicePairing = { p0, p1 -> if (scene.canAct()) source.get()?.onApproveDevicePairing?.invoke(p0, p1) },
        onSavePairingApproval = { if (scene.canAct()) source.get()?.onSavePairingApproval?.invoke() },
        onOpenPairingApproval = { if (scene.canAct()) source.get()?.onOpenPairingApproval?.invoke() },
        onSyncNow = { if (scene.canAct()) source.get()?.onSyncNow?.invoke() },
        onDisconnectSync = { if (scene.canAct()) source.get()?.onDisconnectSync?.invoke() },
        onAcknowledgeSyncStop = { if (scene.canAct()) source.get()?.onAcknowledgeSyncStop?.invoke() },
        onInspectSharingRecipient = { p0 -> if (scene.canAct()) source.get()?.onInspectSharingRecipient?.invoke(p0) },
        onShareWithRecipient = { p0 -> if (scene.canAct()) source.get()?.onShareWithRecipient?.invoke(p0) },
        onRevokeSharingMember = { p0 -> if (scene.canAct()) source.get()?.onRevokeSharingMember?.invoke(p0) },
        onAlbumRoot = { if (scene.canAct()) source.get()?.onAlbumRoot?.invoke() },
        onShowRecentlyAdded = { if (scene.canAct()) source.get()?.onShowRecentlyAdded?.invoke() },
    )
}
