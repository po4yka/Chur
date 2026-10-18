@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.open
import kotlin.test.Test

/** Opt-in device phase; album records and media use the production network sync path. */
class AlbumLiveSyncIosTest {
    @Test
    fun live_album_sync_phase(): Unit = runBlocking {
        val phase = environment("CHUR_LIVE_PHASE")
        if (phase == null) {
            println("NOT RUN: live album sync requires CHUR_LIVE_PHASE.")
            return@runBlocking
        }
        val root = requireNotNull(environment("CHUR_LIVE_ROOT")) { "CHUR_LIVE_ROOT is required" }
        println("ALBUM_LIVE_SYNC process=${getpid()} phase=$phase")
        check(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)) {
            "cannot create the live sync fixture directory"
        }
        val endpoint = environment("CHUR_LIVE_ENDPOINT") ?: "http://127.0.0.1:17880"
        val bootstrap = environment("CHUR_LIVE_BOOTSTRAP") ?: "42".repeat(32)
        withTimeout(180_000) {
            AlbumLiveSyncScenario(root, IosFiles) { (NSDate().timeIntervalSince1970 * 1000).toLong() }
                .run(phase, endpoint, bootstrap)
        }
    }
}

private object IosFiles : AlbumLiveSyncFiles {
    override fun read(path: String): ByteArray {
        val data = checkNotNull(NSData.dataWithContentsOfFile(path)) { "cannot read live sync fixture: $path" }
        check(data.length <= Int.MAX_VALUE.toULong()) { "live sync fixture is too large" }
        if (data.length == 0uL) return byteArrayOf()
        return checkNotNull(data.bytes).readBytes(data.length.toInt())
    }

    override fun write(path: String, bytes: ByteArray) {
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
            NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
        }
        check(data.writeToFile(path, atomically = true)) { "cannot write live sync fixture: $path" }
    }

    override suspend fun input(path: String, block: suspend (Int) -> Unit) {
        val fd = open(path, O_RDONLY)
        check(fd >= 0) { "cannot open live sync fixture input: $path" }
        try { block(fd) } finally { close(fd) }
    }

    override suspend fun output(path: String, block: suspend (Int) -> Unit) {
        val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, 384)
        check(fd >= 0) { "cannot open live sync fixture output: $path" }
        try { block(fd) } finally { close(fd) }
    }
}

private fun environment(name: String): String? = getenv(name)?.toKString()
