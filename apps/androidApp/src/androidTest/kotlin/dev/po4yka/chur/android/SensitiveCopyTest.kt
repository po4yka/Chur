package dev.po4yka.chur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.chur.app.sensitiveClipData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Invitation and pairing codes reach the clipboard only with the sensitive flag. */
@RunWith(AndroidJUnit4::class)
class SensitiveCopyTest {
    @Test
    fun aCopiedCodeIsMarkedSensitive() {
        val clip = sensitiveClipData("Chur sync invitation", "secret-code")
        assertEquals("secret-code", clip.getItemAt(0).text.toString())
        // ClipDescription.EXTRA_IS_SENSITIVE, which the system clipboard preview reads.
        assertTrue(clip.description.extras.getBoolean("android.content.extra.IS_SENSITIVE"))
    }
}
