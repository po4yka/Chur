@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.po4yka.chur.app.publicshell.CameraScreen
import dev.po4yka.chur.app.publicshell.PublicCameraPhoto
import kotlinx.cinterop.readBytes
import org.jetbrains.skia.Image
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/** Native public camera only. These operations never receive a vault session or export sink. */
public object IosPublicCamera {
    public var capture: (((path: String?, previewPath: String?, message: String?) -> Unit) -> Unit)? = null
    public var save: ((path: String, answer: (saved: Boolean, message: String?) -> Unit) -> Unit)? = null
    public var list: (() -> List<PublicCameraPhoto>)? = null
    public var open: ((id: String, answer: (previewPath: String?, message: String?) -> Unit) -> Unit)? = null
    public var delete: ((id: String) -> String?)? = null
    public var discard: ((path: String) -> Unit)? = null
    public var cancel: (() -> Unit)? = null
    internal var onCancellation: (() -> Unit)? = null
    public fun didCancel() { onCancellation?.invoke() }
}

@Composable
internal fun IosPublicCameraScreen(
    onOpenSettings: () -> Unit,
    showFirstWriteDisclosure: Boolean,
    onAcknowledgeDisclosure: () -> Unit,
) {
    val owner = remember { PublicCameraOwner() }
    var capturePath by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var selectedPhotoId by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf(IosPublicCamera.list?.invoke().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) {
        IosPublicCamera.onCancellation = {
            if (owner.active) {
                capturePath = null
                selectedPhotoId = null
                preview = null
                busy = false
                message = "Camera closed. Take another photo to continue."
            }
        }
        onDispose {
            IosPublicCamera.onCancellation = null
            owner.active = false
            IosPublicCamera.cancel?.invoke()
            capturePath?.let { IosPublicCamera.discard?.invoke(it) }
        }
    }
    CameraScreen(
        preview = preview,
        hasCapture = capturePath != null,
        busy = busy,
        message = message,
        showFirstWriteDisclosure = showFirstWriteDisclosure,
        onAcknowledgeDisclosure = onAcknowledgeDisclosure,
        onCapture = {
            val capture = IosPublicCamera.capture
            if (capture == null) message = "Camera is unavailable."
            else {
                selectedPhotoId = null
                preview = null
                busy = true
                message = null
                capture { path, previewPath, resultMessage ->
                    if (!owner.active) path?.let { IosPublicCamera.discard?.invoke(it) }
                    else {
                        busy = false
                        capturePath = path
                        preview = previewPath?.let(::readPublicCameraPreview)
                        message = resultMessage ?: if (path != null && preview == null) "Photo is ready. Preview is unavailable." else null
                    }
                }
            }
        },
        onSave = {
            val path = capturePath
            val save = IosPublicCamera.save
            if (path != null && save != null) {
                busy = true
                message = null
                save(path) { saved, resultMessage ->
                    if (owner.active) {
                        busy = false
                        message = resultMessage
                        if (saved) {
                            capturePath = null
                            preview = null
                            photos = IosPublicCamera.list?.invoke().orEmpty()
                        }
                    }
                }
            } else message = "Saving photos is unavailable."
        },
        onDiscard = {
            capturePath?.let { IosPublicCamera.discard?.invoke(it) }
            capturePath = null
            preview = null
            message = "Photo discarded."
        },
        onOpenSettings = onOpenSettings,
        photos = photos,
        selectedPhotoId = selectedPhotoId,
        onOpenPhoto = { id ->
            val open = IosPublicCamera.open
            if (open == null) message = "Photo is unavailable."
            else {
                busy = true
                open(id) { path, resultMessage ->
                    if (owner.active) {
                        busy = false
                        selectedPhotoId = if (path != null) id else null
                        preview = path?.let(::readPublicCameraPreview)
                        message = resultMessage
                    }
                }
            }
        },
        onClosePhoto = {
            selectedPhotoId = null
            preview = null
            message = null
        },
        onDeletePhoto = { id ->
            val delete = IosPublicCamera.delete
            message = if (delete == null) "Photo could not be deleted." else delete(id)
            if (message == null) {
                photos = IosPublicCamera.list?.invoke().orEmpty()
                selectedPhotoId = null
                preview = null
                message = "Photo deleted."
            }
        },
    )
}

private class PublicCameraOwner(var active: Boolean = true)

/** Swift writes a normalized review JPEG of at most 960 pixels and 2 MiB. */
private fun readPublicCameraPreview(path: String): ImageBitmap? = runCatching {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    if (data.length == 0uL || data.length > 2uL * 1024uL * 1024uL) return null
    val bytes = data.bytes?.readBytes(data.length.toInt()) ?: return null
    try {
        val encoded = Data.makeFromBytes(bytes)
        try {
            val codec = Codec.makeFromData(encoded)
            try {
                if (codec.width !in 1..960 || codec.height !in 1..960) return null
            } finally { codec.close() }
        } finally { encoded.close() }
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } finally { bytes.fill(0) }
}.getOrNull()
