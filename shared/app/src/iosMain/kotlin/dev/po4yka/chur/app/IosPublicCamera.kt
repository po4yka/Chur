@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.po4yka.chur.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import dev.po4yka.chur.app.publicshell.*
import kotlinx.cinterop.readBytes
import org.jetbrains.skia.Image
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIView

public data class IosCameraStatus(val ready: Boolean = false, val recording: Boolean = false,
    val seconds: Long = 0, val zoom: Float = 1f, val minZoom: Float = 1f, val maxZoom: Float = 1f,
    val exposure: Float = 0f, val minExposure: Float = 0f, val maxExposure: Float = 0f,
    val hasFlash: Boolean = false, val flash: Boolean = false, val sound: Boolean = true,
    val lenses: List<PublicCameraLens> = emptyList(), val selectedLens: String = "",
    val busy: Boolean = false, val message: String? = null)

/** File references and public camera controls only. No private session crosses this bridge. */
public object IosPublicCamera {
    public var previewView: (() -> UIView)? = null
    public var start: (() -> Unit)? = null
    public var capture: (() -> Unit)? = null
    public var setVideo: ((Boolean) -> Unit)? = null
    public var flip: (() -> Unit)? = null
    public var setZoom: ((Float) -> Unit)? = null
    public var setLens: ((String) -> Unit)? = null
    public var setExposure: ((Float) -> Unit)? = null
    public var toggleFlash: (() -> Unit)? = null
    public var toggleSound: (() -> Unit)? = null
    public var list: (() -> List<PublicCameraPhoto>)? = null
    public var thumbnail: ((String, Int, (String?) -> Unit) -> Unit)? = null
    public var playbackView: ((String) -> UIView?)? = null
    public var closePlayback: (() -> Unit)? = null
    public var delete: ((String) -> String?)? = null
    public var export: ((List<String>) -> Unit)? = null
    public var cancel: (() -> Unit)? = null
    public var retry: (() -> Unit)? = null
    internal val state = mutableStateOf(IosCameraStatus())
    public fun didChange(status: IosCameraStatus) { state.value = status }
    public fun didCancel() { state.value = IosCameraStatus() }
}

@Composable
internal fun IosPublicCameraScreen(onOpenSettings: () -> Unit, showFirstWriteDisclosure: Boolean,
    onAcknowledgeDisclosure: () -> Unit, onNavigationBusyChange: (Boolean) -> Unit = {}, onSubmitCode: (String) -> Boolean = { false }) {
    val state by IosPublicCamera.state
    var video by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf(emptyList<PublicCameraPhoto>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val owner = remember { PublicCameraOwner() }
    fun refresh() { photos = IosPublicCamera.list?.invoke().orEmpty() }
    LaunchedEffect(state.busy, state.recording, state.message, selected) { refresh(); onNavigationBusyChange(state.busy || state.recording || selected != null) }
    DisposableEffect(Unit) {
        IosPublicCamera.start?.invoke()
        onDispose { owner.active = false; IosPublicCamera.closePlayback?.invoke(); IosPublicCamera.cancel?.invoke(); onNavigationBusyChange(false) }
    }
    CameraScreen(preview, false, state.busy, state.message, showFirstWriteDisclosure, onAcknowledgeDisclosure,
        onCapture = { IosPublicCamera.capture?.invoke() }, onOpenSettings = onOpenSettings,
        photos = photos, selectedPhotoId = selected,
        onOpenPhoto = { id ->
            selected = id; preview = null
            IosPublicCamera.thumbnail?.invoke(id, 1600) { path -> if (owner.active && selected == id) preview = path?.let(::readPublicCameraPreview) }
        }, onClosePhoto = { IosPublicCamera.closePlayback?.invoke(); selected = null; preview = null },
        onDeletePhoto = { id -> IosPublicCamera.delete?.invoke(id); if (selected == id) { IosPublicCamera.closePlayback?.invoke(); selected = null; preview = null }; refresh() },
        thumbnail = { id -> kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val load = IosPublicCamera.thumbnail
            if (load == null) continuation.resumeWith(Result.success(null))
            else load(id, 320) { path -> if (continuation.isActive) continuation.resumeWith(Result.success(path?.let(::readPublicCameraPreview))) }
        } },
        onExport = { IosPublicCamera.export?.invoke(it) }, onSubmitCode = onSubmitCode,
        onGalleryChange = { if (it) IosPublicCamera.cancel?.invoke() else IosPublicCamera.start?.invoke() },
        livePreview = {
            val factory = IosPublicCamera.previewView
            if (factory != null) UIKitView(factory = factory, modifier = Modifier.fillMaxSize(), properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative))
            if (!state.ready) Column {
                Button(onClick = { IosPublicCamera.start?.invoke() }) { Text(utilityText("Allow camera", "Разрешить камеру")) }
                TextButton(onClick = {
                    platform.Foundation.NSURL.URLWithString(platform.UIKit.UIApplicationOpenSettingsURLString)?.let {
                        platform.UIKit.UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
                    }
                }) { Text(utilityText("System settings", "Настройки системы")) }
            }
            if (state.message != null) TextButton(onClick = { IosPublicCamera.retry?.invoke() }) { Text(utilityText("Retry", "Повторить")) }
        },
        videoContent = { selected?.let { id -> key(id) {
            val view = remember(id) { IosPublicCamera.playbackView?.invoke(id) }
            if (view != null) UIKitView(factory = { view }, modifier = Modifier.fillMaxSize(), onRelease = { IosPublicCamera.closePlayback?.invoke() }, properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = true))
        } } },
        captureReady = state.ready, videoMode = video, recording = state.recording, elapsedSeconds = state.seconds,
        onVideoMode = { video = it; IosPublicCamera.setVideo?.invoke(it) }, onSwitchCamera = { IosPublicCamera.flip?.invoke() },
        zoom = state.zoom, zoomRange = state.minZoom..state.maxZoom, onZoom = { IosPublicCamera.setZoom?.invoke(it) },
        exposure = state.exposure, exposureRange = state.minExposure..state.maxExposure, onExposure = { IosPublicCamera.setExposure?.invoke(it) },
        lenses = state.lenses, selectedLens = state.selectedLens, onLens = { IosPublicCamera.setLens?.invoke(it) },
        hasFlash = state.hasFlash, flash = state.flash, onFlash = { IosPublicCamera.toggleFlash?.invoke() },
        sound = state.sound, onSound = { IosPublicCamera.toggleSound?.invoke() },
    )
}
private class PublicCameraOwner(var active: Boolean = true)
private fun readPublicCameraPreview(path: String): ImageBitmap? = runCatching {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    if (data.length == 0uL || data.length > 4uL * 1024uL * 1024uL) return null
    val bytes = data.bytes?.readBytes(data.length.toInt()) ?: return null
    val encoded = Data.makeFromBytes(bytes)
    try { val codec = Codec.makeFromData(encoded); try { if (codec.width !in 1..1600 || codec.height !in 1..1600) return null } finally { codec.close() } }
    finally { encoded.close() }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
