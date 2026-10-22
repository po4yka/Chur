package dev.po4yka.chur.android

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidArchiveInputTest {
    @Test
    fun formatMarkerAllowsSeekableTransportWithoutClaimingAuthentication() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Synthetic marker-prefixed bytes exercise transport only. They are not
        // an authenticated package, and no native restore is called by this test.
        val expected = "CHURBAK1".encodeToByteArray() + IncomingShareFixtureProvider.fixtureBytes()
        val selected = File.createTempFile("selected-package-", ".bin", context.cacheDir)
        selected.writeBytes(expected)
        val source = try { AndroidArchiveInput.open(context, Uri.fromFile(selected)) }
            finally { selected.delete() }
        val staged = File(Os.readlink("/proc/self/fd/${source.descriptor}"))
        try {
            assertTrue(staged.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            ParcelFileDescriptor.fromFd(source.descriptor).use { local ->
                assertEquals(0L, Os.lseek(local.fileDescriptor, 0, OsConstants.SEEK_CUR))
                assertEquals(expected.size.toLong(), Os.lseek(local.fileDescriptor, 0, OsConstants.SEEK_END))
                assertEquals(0L, Os.lseek(local.fileDescriptor, 0, OsConstants.SEEK_SET))
                val actual = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(local.fileDescriptor)).use { it.readBytes() }
                assertArrayEquals(expected, actual)
            }
        } finally { source.close() }
        withTimeout(5_000) { while (staged.exists()) delay(10) }
    }
    @Test
    fun ordinaryPlaintextAndShortMarkersNeverCreateAStagedFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun stagedNames() = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("archive-input-") }.map { it.name }.toSet()
        val before = stagedNames()
        val selected = File.createTempFile("not-an-archive-", ".txt", context.cacheDir)
        try {
            for (bytes in listOf("A private plain text document".encodeToByteArray(), "CHURBAK".encodeToByteArray(), byteArrayOf())) {
                selected.writeBytes(bytes)
                try {
                    AndroidArchiveInput.open(context, Uri.fromFile(selected)).close()
                    fail("A file without the complete native marker was accepted")
                } catch (_: IOException) { }
                assertEquals(before, stagedNames())
            }
            // Also reject ordinary bytes arriving through the separate test APK's pipe.
            try {
                AndroidArchiveInput.open(context, IncomingShareFixtureProvider.uri).close()
                fail("Unmarked provider bytes were accepted")
            } catch (_: IOException) { }
            assertEquals(before, stagedNames())
        } finally { selected.delete() }
    }

}
