package dev.po4yka.chur.android

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.video.*
import androidx.camera.view.*
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.po4yka.chur.app.publicshell.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
internal fun AndroidPublicCameraScreen(
    onOpenSettings: () -> Unit, showFirstWriteDisclosure: Boolean, onAcknowledgeDisclosure: () -> Unit,
    onBeginCapture: () -> (() -> Unit), onNavigationBusyChange: (Boolean) -> Unit = {},
    onSubmitCode: (String) -> Boolean = { false },
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val albumResult = remember { runCatching { PublicMediaAlbum(context.applicationContext) } }
    val album = albumResult.getOrNull()
    val camera = remember { LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        videoCaptureTargetFrameRate = android.util.Range(30, 30)
        videoCaptureQualitySelector = QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
    } }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionLease by remember { mutableStateOf<(() -> Unit)?>(null) }
    var ready by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordingStarted by remember { mutableLongStateOf(0L) }
    var seconds by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf(emptyList<PublicCameraPhoto>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var gallery by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var exposureMin by remember { mutableFloatStateOf(0f) }
    var exposureMax by remember { mutableFloatStateOf(0f) }
    var hasFlash by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var lenses by remember { mutableStateOf(emptyList<PublicCameraLens>()) }
    var selectedLens by remember { mutableStateOf("") }
    var front by remember { mutableStateOf(false) }
    var exportIds by remember { mutableStateOf(emptyList<String>()) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val owner = remember { CameraOwner() }
    suspend fun refresh() { photos = withContext(Dispatchers.IO) { album?.list().orEmpty() } }
    fun bind() {
        if (!allowed || gallery || !owner.active) return
        try {
            camera.bindToLifecycle(lifecycle)
            camera.initializationFuture.addListener({
                if (owner.active) try {
                    camera.initializationFuture.get()
                    val info = camera.cameraInfo
                    hasFlash = info?.hasFlashUnit() == true
                    if (selectedLens.isEmpty()) lenses = listOf(PublicCameraLens("", utilityText("Auto", "Авто"))) + info?.physicalCameraInfos.orEmpty().map { physical ->
                        val native = Camera2CameraInfo.from(physical)
                        val focal = native.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                        PublicCameraLens(native.cameraId, focal?.let { "${(it * 10).toInt() / 10f} mm" } ?: utilityText("Lens", "Объектив"))
                    }
                    info?.zoomState?.value?.let { minZoom = it.minZoomRatio; maxZoom = it.maxZoomRatio; zoom = it.zoomRatio }
                    info?.exposureState?.let { exposureMin = it.exposureCompensationRange.lower.toFloat(); exposureMax = it.exposureCompensationRange.upper.toFloat() }
                    ready = info != null
                } catch (_: Exception) { message = utilityText("Camera is unavailable.", "Камера недоступна."); ready = false }
            }, executor)
        } catch (_: Exception) { message = utilityText("Camera is unavailable. Try again.", "Камера недоступна. Повторите попытку."); ready = false }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionLease?.invoke(); permissionLease = null; allowed = granted
        if (!granted) message = utilityText("Camera permission is off. Enable it in system Settings.", "Доступ к камере отключён. Разрешите его в настройках системы.")
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionLease?.invoke(); permissionLease = null; sound = granted
        message = if (granted) utilityText("Sound is on. Tap Record to start.", "Звук включён. Нажмите запись.") else utilityText("Microphone access is off. Video will have no sound.", "Микрофон отключён. Видео будет без звука.")
    }
    LaunchedEffect(allowed, gallery) { bind() }
    LaunchedEffect(album) {
        if (album == null) message = utilityText("Camera storage is unavailable.", "Хранилище камеры недоступно.")
        else try {
            val recovered = withContext(Dispatchers.IO) { album.recover() }
            pendingSave = withContext(Dispatchers.IO) { album.pendingRecovery() }
            refresh()
            if (pendingSave != null) message = utilityText("An interrupted capture could not be recovered. Its temporary file is retained.", "Не удалось восстановить прерванную съёмку. Временный файл сохранён.")
            else if (recovered > 0) message = utilityText("Recovered captures: $recovered.", "Восстановлено файлов: $recovered.")
        }
        catch (_: Exception) { message = utilityText("The gallery could not be read.", "Не удалось открыть галерею.") }
    }
    LaunchedEffect(recording) { while (recording != null) { seconds = (SystemClock.elapsedRealtime() - recordingStarted) / 1000; delay(250) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { recording?.stop(); ready = false }
            if (event == Lifecycle.Event.ON_START) { allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; bind() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { owner.active = false; lifecycle.lifecycle.removeObserver(observer); recording?.stop(); camera.unbind(); permissionLease?.invoke(); onNavigationBusyChange(false) }
    }
    LaunchedEffect(busy, recording, selected) { onNavigationBusyChange(busy || recording != null || selected != null) }
    fun complete(file: File, interrupted: Boolean = false) {
        // Finalize on a route-independent I/O executor. A backgrounded route cannot lose completed captures.
        java.util.concurrent.CompletableFuture.supplyAsync {
            runCatching { requireNotNull(album).commit(file) }
        }.thenAccept { result -> executor.execute {
            if (owner.active) {
                busy = false
                if (result.isSuccess) { pendingSave = null; scope.launch { refresh() }; message = if (interrupted) utilityText("Recording ended. Available video saved.", "Запись завершена. Доступное видео сохранено.") else utilityText("Saved", "Сохранено") }
                else { pendingSave = file; message = utilityText("Could not save. Retry or keep the app data for recovery.", "Не удалось сохранить. Повторите попытку; файл сохранён для восстановления.") }
            }
        } }
    }
    fun capture() {
        if (recording != null) { recording?.stop(); return }
        if (!allowed) { permissionLease = onBeginCapture(); permission.launch(Manifest.permission.CAMERA); return }
        if (!ready || album == null || busy) return
        if (video && sound && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLease = onBeginCapture(); microphone.launch(Manifest.permission.RECORD_AUDIO); return
        }
        try {
            val file = album.pending(video)
            busy = true; message = null
            if (video) {
                recordingStarted = SystemClock.elapsedRealtime(); seconds = 0
                recording = camera.startRecording(FileOutputOptions.Builder(file).setFileSizeLimit(album.availableBytes()).build(),
                    AudioConfig.create(sound), executor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        recording = null
                        if (!event.hasError() || event.error in listOf(VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE, VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE)) complete(file, interrupted = event.hasError())
                        else { busy = false; pendingSave = file; message = utilityText("Recording interrupted. Try to recover the file.", "Запись прервана. Попробуйте восстановить файл.") }
                    }
                }
            } else camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = complete(file)
                override fun onError(exception: ImageCaptureException) { if (owner.active) { busy = false; message = utilityText("Photo could not be captured.", "Не удалось сделать снимок.") }; runCatching { album.discard(file) } }
            })
        } catch (_: Exception) { busy = false; message = utilityText("Capture could not start. Check available storage and try again.", "Не удалось начать съёмку. Проверьте свободное место и повторите попытку.") }
    }
    if (exportIds.isNotEmpty()) AlertDialog(onDismissRequest = { exportIds = emptyList() }, title = { Text(utilityText("Export", "Экспорт")) }, text = {
        Text(utilityText("Exported files can be read by other apps and may enter photo sync.", "Экспортированные файлы доступны другим приложениям и могут попасть в синхронизацию фото."))
    }, confirmButton = { TextButton(onClick = {
        val ids = exportIds; exportIds = emptyList(); busy = true
        scope.launch { try { withContext(Dispatchers.IO) { album!!.exportToLibrary(context, ids) }; message = utilityText("Saved to the system gallery.", "Сохранено в системную галерею.") }
            catch (_: Exception) { message = utilityText("Export could not be completed. Already exported files remain in the system gallery.", "Не удалось завершить экспорт. Уже экспортированные файлы остаются в системной галерее.") } finally { busy = false } }
    }) { Text(utilityText("Save to Photos", "Сохранить в Фото")) } }, dismissButton = { TextButton(onClick = {
        try {
            val uris = ArrayList(exportIds.map { FileProvider.getUriForFile(context, "${context.packageName}.public-camera", album!!.file(it)) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, "", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            context.startActivity(Intent.createChooser(intent, utilityText("Share", "Поделиться"))); exportIds = emptyList()
        } catch (_: Exception) { message = utilityText("Sharing is unavailable.", "Отправка недоступна."); exportIds = emptyList() }
    }) { Text(utilityText("Share", "Поделиться")) } })
    CameraScreen(preview, false, busy, message, showFirstWriteDisclosure, onAcknowledgeDisclosure, ::capture,
        systemBack = { handler -> androidx.activity.compose.BackHandler(onBack = handler) },
        onOpenSettings = onOpenSettings, photos = photos, selectedPhotoId = selected,
        onOpenPhoto = { id -> scope.launch { try { preview = withContext(Dispatchers.IO) { album?.thumbnail(id, 1600)?.asImageBitmap() }; selected = id }
            catch (_: Exception) { message = utilityText("The file could not be opened.", "Не удалось открыть файл.") } } },
        onClosePhoto = { selected = null; preview = null },
        onDeletePhoto = { id -> scope.launch { try { withContext(Dispatchers.IO) { album?.delete(id) }; if (selected == id) { selected = null; preview = null }; refresh() }
            catch (_: Exception) { message = utilityText("The file could not be deleted.", "Не удалось удалить файл.") } } },
        thumbnail = { id -> withContext(Dispatchers.IO) { runCatching { album?.thumbnail(id)?.asImageBitmap() }.getOrNull() } },
        onExport = { exportIds = it }, onSubmitCode = onSubmitCode,
        onGalleryChange = { gallery = it; if (it) { camera.unbind(); ready = false } },
        livePreview = {
            if (allowed) AndroidView(factory = { PreviewView(it).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; controller = camera } }, modifier = Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Button(onClick = { permissionLease = onBeginCapture(); permission.launch(Manifest.permission.CAMERA) }) { Text(utilityText("Allow camera", "Разрешить камеру")) }
                TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text(utilityText("System settings", "Настройки системы")) }
            }
            if (pendingSave != null) TextButton(onClick = { pendingSave?.let { complete(it, interrupted = true) } }) { Text(utilityText("Retry save", "Повторить сохранение")) }
        },
        videoContent = {
            val file = selected?.let { runCatching { album?.file(it) }.getOrNull() }
            if (file != null) AndroidView(factory = { VideoView(it).apply { setMediaController(MediaController(it)); setVideoPath(file.path); setOnPreparedListener { player -> player.isLooping = false; start() } } },
                modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback() })
        },
        captureReady = ready, videoMode = video, recording = recording != null, elapsedSeconds = seconds,
        onVideoMode = { value -> try { ready = false; video = value; camera.setEnabledUseCases(if (value) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE); bind() } catch (_: Exception) { message = utilityText("This mode is unavailable.", "Этот режим недоступен.") } },
        onSwitchCamera = { try { ready = false; front = !front; selectedLens = ""; camera.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; bind() } catch (_: Exception) { message = utilityText("This camera is unavailable.", "Эта камера недоступна.") } },
        lenses = lenses, selectedLens = selectedLens, onLens = { id ->
            val previous = camera.cameraSelector
            try {
                camera.cameraSelector = CameraSelector.Builder().requireLensFacing(if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK).apply { if (id.isNotEmpty()) setPhysicalCameraId(id) }.build()
                selectedLens = id; bind()
            } catch (_: Exception) { camera.cameraSelector = previous; message = utilityText("This lens does not support the selected mode.", "Объектив не поддерживает выбранный режим.") }
        },
        zoom = zoom, zoomRange = minZoom..maxZoom, onZoom = { zoom = it; camera.setZoomRatio(it) },
        exposure = exposure, exposureRange = exposureMin..exposureMax, onExposure = { exposure = it; camera.cameraControl?.setExposureCompensationIndex(it.toInt()) },
        hasFlash = hasFlash, flash = flash, onFlash = { flash = !flash; camera.imageCaptureFlashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF; if (video) camera.enableTorch(flash) },
        sound = sound, onSound = { sound = !sound },
    )
}
private class CameraOwner(var active: Boolean = true)
