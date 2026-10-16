package dev.po4yka.chur.app

import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PrivateNoteClipboardHostTest {
    @Test
    fun modern_guard_keeps_android_metadata_interface_and_explicit_reads_without_writes(): Unit = runBlocking {
        var reads = 0
        var writes = 0
        val original = object : AndroidClipboard {
            override val clipboardManager: android.content.ClipboardManager
                get() = error("this host test needs no native clipboard")
            override suspend fun getClipEntry(): ClipEntry? { reads += 1; return null }
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { writes += 1 }
        }
        val guarded = privateNoteClipboard(original)
        assertIs<AndroidClipboard>(guarded, "Compose checks this subtype before offering Paste")
        assertEquals(0, reads, "installing the guard must not read the clipboard")
        guarded.setClipEntry(null)
        assertEquals(0, writes)
        assertNull(guarded.getClipEntry())
        assertEquals(1, reads)
    }
}
