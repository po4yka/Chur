package dev.po4yka.chur.android

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.app.publicshell.CameraScreen
import dev.po4yka.chur.app.publicshell.PublicCameraPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AndroidPublicCameraScreen(
    onOpenSettings: () -> Unit,
    showFirstWriteDisclosure: Boolean,
    onAcknowledgeDisclosure: () -> Unit,
    onBeginCapture: () -> (() -> Unit),
) {
    val context = LocalContext.current
    var storageResult by remember(context) {
        mutableStateOf(runCatching { PublicCameraStorage(context.applicationContext) })
    }
    val storage = storageResult.getOrNull()
    if (storage == null) {
        CameraScreen(
            preview = null,
            hasCapture = false,
            busy = false,
            message = "Public camera storage is not available. Open Settings or restart Chur, then try again.",
            showFirstWriteDisclosure = false,
            onAcknowledgeDisclosure = onAcknowledgeDisclosure,
            onCapture = { storageResult = runCatching { PublicCameraStorage(context.applicationContext) } },
            onSave = {},
            onDiscard = {},
            onOpenSettings = onOpenSettings,
        )
        return
    }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selectedPhotoId by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf(storage.photos().map { PublicCameraPhoto(it.id, it.label) }) }
    fun refreshPhotos() { photos = storage.photos().map { PublicCameraPhoto(it.id, it.label) } }
    var unfinishedCapture by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(
        if (unfinishedCapture || storage.discardedPreviousCapture)
            "The previous camera session ended. Its unsaved photo was discarded." else null,
    ) }
    val captureLease = remember { arrayOfNulls<() -> Unit>(1) }
    fun releaseCaptureLease() {
        val close = captureLease[0]
        captureLease[0] = null
        close?.invoke()
    }
    DisposableEffect(storage) { onDispose { releaseCaptureLease(); storage.close() } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        releaseCaptureLease()
        unfinishedCapture = false
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { storage.finishCapture(result.resultCode == Activity.RESULT_OK) }
                preview = bitmap?.asImageBitmap()
                message = if (bitmap == null) "Photo cancelled. No photo was saved." else "Review the photo before saving it to the public Chur album."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storage.discard(); message = "The photo could not be read. Try again." }
            finally { busy = false }
        }
    }
    CameraScreen(
        preview = preview,
        hasCapture = preview != null && selectedPhotoId == null,
        busy = busy,
        message = message,
        showFirstWriteDisclosure = showFirstWriteDisclosure,
        onAcknowledgeDisclosure = onAcknowledgeDisclosure,
        onCapture = {
            try {
                val intent = storage.beginCapture()
                selectedPhotoId = null
                preview = null
                busy = true
                message = null
                unfinishedCapture = true
                captureLease[0] = onBeginCapture()
                camera.launch(intent)
            } catch (_: Exception) {
                releaseCaptureLease()
                unfinishedCapture = false
                storage.discard()
                busy = false
                message = "No camera is available. Install or enable a camera app and try again."
            }
        },
        onSave = {
            busy = true
            scope.launch {
                try {
                    if (withContext(Dispatchers.IO) { storage.save() }) {
                        preview = null
                        refreshPhotos()
                        message = "Photo saved to the public Chur album."
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    message = if (failure.message?.startsWith("The public album is full") == true)
                        failure.message else "The photo could not be saved. Try again or discard it."
                }
                finally { busy = false }
            }
        },
        onDiscard = {
            storage.discard()
            preview = null
            message = "Photo discarded."
        },
        onOpenSettings = onOpenSettings,
        photos = photos,
        selectedPhotoId = selectedPhotoId,
        onOpenPhoto = { id ->
            if (!busy && (selectedPhotoId != null || preview == null)) {
                busy = true
                scope.launch {
                    try {
                        preview = withContext(Dispatchers.IO) { storage.openPhoto(id) }.asImageBitmap()
                        selectedPhotoId = id
                        message = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "The photo could not be opened." }
                    finally { busy = false }
                }
            }
        },
        onClosePhoto = { selectedPhotoId = null; preview = null; message = null },
        onDeletePhoto = { id ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { storage.deletePhoto(id) }
                        if (selectedPhotoId == id) { selectedPhotoId = null; preview = null }
                        refreshPhotos()
                        message = "Photo deleted from the public Chur album."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "The photo could not be deleted. Try again." }
                    finally { busy = false }
                }
            }
        },
    )
}
