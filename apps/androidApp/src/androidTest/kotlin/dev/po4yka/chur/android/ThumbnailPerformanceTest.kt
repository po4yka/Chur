package dev.po4yka.chur.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.vault.ThumbnailRendition
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Records frame completion latency and PSS. Timings have no pass threshold. */
@RunWith(AndroidJUnit4::class)
class ThumbnailPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val controller: ChurController get() = ChurHost.of(activity).controller

    @Before
    fun start() {
        instrumentation.setInTouchMode(true)
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        assertTrue("the vault must start", await { controller.vaultState.value !is VaultState.Starting })
    }

    @After
    fun finish() {
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun thumbnailRenditionsReportFrameAndMemoryMetrics() = inTestVault {
        val imported = mutableListOf<ByteArray>()
        val display = activity.resources.displayMetrics
        @Suppress("DEPRECATION")
        val refreshRate = activity.windowManager.defaultDisplay.refreshRate
        val report = JSONObject()
            .put("device", Build.MODEL)
            .put("fingerprint", Build.FINGERPRINT)
            .put("api", Build.VERSION.SDK_INT)
            .put("widthPx", display.widthPixels)
            .put("heightPx", display.heightPixels)
            .put("densityDpi", display.densityDpi)
            .put("density", display.density.toDouble())
            .put("refreshRateHz", refreshRate.toDouble())
            .put("buildConfiguration", if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debuggable" else "release")
            .put("photos", 48)
            .put("trials", 2)
            .put("workload", "32 scroll-to-index and redraw steps after warming both renditions")
        val measurements = JSONArray()
        try {
            repeat(48) { index -> imported += importPhoto(index) }
            assertTrue("all synthetic photos reach the current page", await(60_000) {
                controller.page.value.objects.count { projection ->
                    imported.any { it.contentEquals(projection.objectId) }
                } == 48
            })
            report.put("galleryObjects", controller.page.value.objects.size)
            val generation = (controller.vaultState.value as VaultState.Unlocked).generation
            val photos = controller.page.value.objects.filter { projection ->
                imported.any { it.contentEquals(projection.objectId) }
            }
            runBlocking {
                for (rendition in ThumbnailRendition.entries) {
                    for (photo in photos) {
                        assertNotNull("a real derivative warms each rendition", controller.thumbnailCache.load(
                            controller.vault, generation, photo.objectId, photo.id, rendition = rendition))
                    }
                }
            }
            for (rendition in ThumbnailRendition.entries) {
                setRendition(rendition)
                workload()
            }
            repeat(2) { trial ->
                val order = if (trial % 2 == 0) ThumbnailRendition.entries else ThumbnailRendition.entries.reversed()
                for ((orderIndex, rendition) in order.withIndex()) {
                    setRendition(rendition)
                    scrollTo(0)
                    awaitFrames()
                    shell("dumpsys gfxinfo ${activity.packageName} reset")
                    workload()
                    val sorted = frameCompletionLatenciesNs(
                        shell("dumpsys gfxinfo ${activity.packageName} framestats"),
                    ).sorted()
                    assertTrue("gfxinfo must report completed frames for $rendition trial $trial", sorted.isNotEmpty())
                    val p95 = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)] / 1_000_000.0
                    measurements.put(JSONObject().put("mode", rendition.name).put("trial", trial + 1)
                        .put("order", orderIndex + 1).put("frames", sorted.size)
                        .put("p95FrameCompletionLatencyMs", p95).put("processPssKb", Debug.getPss()))
                }
            }
            report.put("modes", measurements).put("beforeLockPssKb", Debug.getPss())
            lockQuietly()
            awaitFrames()
            report.put("afterLockPssKb", Debug.getPss())
            val metrics = report.toString()
            File(instrumentation.targetContext.getExternalFilesDir(null), "thumbnail-performance.json").writeText(metrics)
            instrumentation.sendStatus(0, Bundle().apply { putString("thumbnail_performance", metrics) })
            android.util.Log.i("ThumbnailPerformance", metrics)
        } finally {
            if (!isOpen()) {
                instrumentation.runOnMainSync {
                    controller.goTo(AppRoute.Unlock)
                    controller.unlock(PASSWORD)
                }
                assertTrue("the test vault reopens for cleanup", await(60_000) { isOpen() })
            }
            if (imported.isNotEmpty()) {
                settle { controller.deleteAll(imported, it) }
                settle { controller.permanentlyDeleteAll(imported, it) }
            }
        }
    }

    private fun setRendition(rendition: ThumbnailRendition) {
        val expected = if (rendition == ThumbnailRendition.Mosaic) ToggleableState.On else ToggleableState.Off
        fun switch(nodes: List<SemanticsNode>) = nodes.single { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Hide thumbnails" } ||
                "Hide thumbnails" in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        fun state() = onScreen { switch(it).config.getOrNull(SemanticsProperties.ToggleableState) }
        if (state() != expected) {
            onScreen { nodes ->
                assertTrue("the thumbnail switch accepts the action",
                    switch(nodes).config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            awaitFrames()
        }
        assertEquals("the measured rendition must match the switch state", expected, state())
    }

    private fun workload() {
        val targets = listOf(0, 8, 16, 24, 32, 40, 32, 24)
        repeat(4) {
            for (target in targets) {
                scrollTo(target)
                awaitFrames()
            }
        }
    }

    private fun scrollTo(index: Int) = onScreen { nodes ->
        val grid = nodes.single { SemanticsProperties.CollectionInfo in it.config &&
            SemanticsProperties.VerticalScrollAxisRange in it.config }
        assertTrue("the scroll action runs", grid.config[SemanticsActions.ScrollToIndex].action?.invoke(index) == true)
    }

    private fun importPhoto(index: Int): ByteArray {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "export-scratch/thumbnail-performance-$index.jpg")
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(320 * 240) { position ->
                val x = position % 320
                val y = position / 320
                0xFF000000.toInt() or (((x + index * 17) % 256) shl 16) or
                    (((y + index * 29) % 256) shl 8) or ((x xor y xor index * 7) and 255)
            }
            bitmap.setPixels(pixels, 0, 320, 0, 0, 320, 240)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            val codec = AndroidMediaCodec(context.contentResolver)
            val outcome = runBlocking { controller.importMedia(MediaImporter(codec)) { codec.open(uri) } }
            assertTrue("synthetic photo $index must import: $outcome", outcome is MediaImporter.Outcome.Imported)
            instrumentation.runOnMainSync { controller.reportImport(null) }
            return (outcome as MediaImporter.Outcome.Imported).objectId
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private fun <T> onScreen(read: (List<SemanticsNode>) -> T): T {
        fun View.roots(): List<ViewRootForTest> = when (this) {
            is ViewRootForTest -> listOf(this)
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).roots() }
            else -> emptyList()
        }
        var value: Result<T>? = null
        instrumentation.runOnMainSync {
            value = runCatching { read(activity.window.decorView.roots().flatMap {
                it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            }) }
        }
        return checkNotNull(value).getOrThrow()
    }

    private fun inTestVault(body: () -> Unit) {
        val opening = controller.vaultState.value
        instrumentation.runOnMainSync {
            controller.report(null)
            when (opening) {
                is VaultState.NoVault -> {
                    controller.goTo(AppRoute.CreateVault)
                    controller.create(PASSWORD, offerRecovery = false)
                }
                is VaultState.Unlocked -> controller.goTo(AppRoute.Vault)
                else -> {
                    controller.goTo(AppRoute.Unlock)
                    controller.unlock(PASSWORD)
                }
            }
        }
        await(60_000) { isOpen() || controller.formError.value != null }
        if (!isOpen()) {
            assumeTrue("a vault this test did not create", opening is VaultState.Locked && controller.formError.value != null)
            fail("the test vault did not open: ${controller.formError.value}")
        }
        try {
            awaitFrames()
            body()
        } finally {
            lockQuietly()
        }
    }

    private fun isOpen() = controller.route.value == AppRoute.Vault && controller.vaultState.value is VaultState.Unlocked

    private fun lockQuietly() {
        if (controller.vaultState.value !is VaultState.Unlocked && controller.recoveryPhrase.value == null) return
        instrumentation.runOnMainSync { controller.lock() }
        assertTrue("the vault locks", await { controller.vaultState.value !is VaultState.Unlocked &&
            controller.route.value.let { it == AppRoute.PublicShell || it == AppRoute.AppUnlock } })
    }

    private fun settle(call: (done: () -> Unit) -> Unit) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { call { done.countDown() } }
        assertTrue("cleanup completes", done.await(60, TimeUnit.SECONDS))
    }

    private fun awaitFrames() {
        repeat(2) {
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { frame.countDown() } }
            assertTrue(frame.await(5, TimeUnit.SECONDS))
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** Completion minus intended vsync is latency, not the interval between presented frames. */
    private fun frameCompletionLatenciesNs(framestats: String): List<Long> {
        var flags = -1
        var intended = -1
        var completed = -1
        return buildList {
            for (line in framestats.lineSequence()) {
                val columns = line.trim().split(',')
                if (columns.firstOrNull() == "Flags") {
                    flags = columns.indexOf("Flags")
                    intended = columns.indexOf("IntendedVsync")
                    completed = columns.indexOf("FrameCompleted")
                    continue
                }
                if (flags < 0 || intended < 0 || completed < 0) continue
                if (columns.getOrNull(flags)?.toLongOrNull() != 0L) continue
                val start = columns.getOrNull(intended)?.toLongOrNull() ?: continue
                val end = columns.getOrNull(completed)?.toLongOrNull() ?: continue
                if (start > 0 && end > start && end < Long.MAX_VALUE) add(end - start)
            }
        }
    }

    private companion object {
        // Shares only the synthetic fixture credential with the navigation suite.
        const val PASSWORD = "BackNavigationTest-password"
    }
}
