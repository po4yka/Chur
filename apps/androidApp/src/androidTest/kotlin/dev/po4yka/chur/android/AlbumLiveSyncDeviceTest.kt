package dev.po4yka.chur.android

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AlbumLiveSyncFiles
import dev.po4yka.chur.app.AlbumLiveSyncScenario
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device phases use the production sync client and retain the vault between runs. */
@RunWith(AndroidJUnit4::class)
class AlbumLiveSyncDeviceTest {
    @Test
    fun liveAlbumSyncPhase() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("runId")
        if (runId == null) {
            report("NOT RUN: supply runId and phase for live album sync acceptance")
            return
        }
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "invalid live sync runId" }
        val phase = requireNotNull(arguments.getString("phase")) { "live sync phase is required" }
        require(phase.isNotBlank()) { "live sync phase is required" }
        val endpoint = arguments.getString("endpoint") ?: "http://127.0.0.1:17880"
        val bootstrapSecret = arguments.getString("bootstrapSecret") ?: "42".repeat(32)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "album-live-$runId")
        check(root.isDirectory || root.mkdirs()) { "could not create live sync fixture directory" }
        report("$phase START pid=${android.os.Process.myPid()}")
        runBlocking {
            withTimeout(180_000L) {
                AlbumLiveSyncScenario(root.absolutePath, AndroidFiles, System::currentTimeMillis)
                    .run(phase, endpoint, bootstrapSecret)
            }
        }
        report("$phase PASS")
    }

    private object AndroidFiles : AlbumLiveSyncFiles {
        override fun read(path: String): ByteArray = File(path).readBytes()

        override fun write(path: String, bytes: ByteArray) {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }

        override suspend fun input(path: String, block: suspend (Int) -> Unit) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                block(descriptor.fd)
            }
        }

        override suspend fun output(path: String, block: suspend (Int) -> Unit) {
            val file = File(path)
            file.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            ).use { descriptor -> block(descriptor.fd) }
        }
    }

    private fun report(message: String) {
        Log.i("AlbumLiveSync", message)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("album_live_sync", message)
        })
    }
}
