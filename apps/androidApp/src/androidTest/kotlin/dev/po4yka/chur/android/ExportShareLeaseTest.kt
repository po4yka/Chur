package dev.po4yka.chur.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.ExportTarget
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real FileProvider files and descriptors with deterministic lease scheduling. */
@RunWith(AndroidJUnit4::class)
class ExportShareLeaseTest {
    private val context = RecordingContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val sink = ExportDestinations(context, {}, {}, { delay, cleanup -> scheduled.add(delay to cleanup) })
    private val bytes = "verified original".toByteArray()

    @Test
    fun returningStartsAShortLeaseThenRevokesAndUnlinksWithoutBreakingAnOpenReader() {
        try {
            val uri = share()
            val reader = context.contentResolver.openFileDescriptor(uri, "r")!!
            sink.completePendingShares()
            assertEquals(listOf(1_800_000L, 60_000L), scheduled.map { it.first })
            assertTrue(context.revoked.isEmpty())
            assertEquals(1, scratch().size)

            scheduled.last().second()

            assertEquals(listOf(uri), context.revoked)
            assertTrue(scratch().isEmpty())
            ParcelFileDescriptor.AutoCloseInputStream(reader).use { assertArrayEquals(bytes, it.readBytes()) }
        } finally {
            sink.cancelPending()
        }
    }

    @Test
    fun anEarlierReturnCleanupCannotRevokeANewerShare() {
        try {
            val previous = share()
            sink.completePendingShares()
            val returnCleanup = scheduled.last().second
            val current = share()

            returnCleanup()

            assertEquals(listOf(previous), context.revoked)
            assertFalse(context.revoked.contains(current))
            assertEquals(1, scratch().size)
            context.contentResolver.openInputStream(current)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            // An older hard timeout is harmless after its return lease expired.
            scheduled.first().second()
            assertEquals(listOf(previous), context.revoked)
        } finally {
            sink.cancelPending()
        }
    }

    @Test
    fun aShareStillExpiresIfTheHostDoesNotReturn() {
        try {
            val uri = share()
            assertEquals(1_800_000L, scheduled.single().first)

            scheduled.single().second()

            assertEquals(listOf(uri), context.revoked)
            assertTrue(scratch().isEmpty())
        } finally {
            sink.cancelPending()
        }
    }

    private fun scratch(): List<File> = File(context.cacheDir, "export-scratch").listFiles().orEmpty().toList()

    private fun share(): Uri {
        val destination = sink.create("original.bin", "application/octet-stream", ExportTarget.SHARE, null)
        assertNotNull(destination)
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.fromFd(destination!!.descriptor)).use {
            it.write(bytes)
        }
        destination.publish()
        @Suppress("DEPRECATION")
        val send = context.launched.last().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        return send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val launched = mutableListOf<Intent>()
        val revoked = mutableListOf<Uri>()
        override fun startActivity(intent: Intent) { launched.add(intent) }
        override fun revokeUriPermission(uri: Uri, modeFlags: Int) {
            revoked.add(uri)
            super.revokeUriPermission(uri, modeFlags)
        }
    }
}
