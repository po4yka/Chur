package dev.po4yka.chur.app

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateNoteClipboardIosTest {
    @Test
    fun ios_guard_forwards_explicit_paste_but_never_writes(): Unit = runBlocking {
        var reads = 0
        var writes = 0
        val original = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? { reads += 1; return null }
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { writes += 1 }
        }
        val guarded = privateNoteClipboard(original)
        guarded.setClipEntry(null)
        assertEquals(0, writes)
        assertEquals(0, reads)
        assertNull(guarded.getClipEntry())
        assertEquals(1, reads)
    }
}
