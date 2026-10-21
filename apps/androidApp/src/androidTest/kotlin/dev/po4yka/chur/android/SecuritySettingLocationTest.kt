package dev.po4yka.chur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The security settings are outside every backup, `DISCREET_MODE.md`.
 *
 * Each is written only from an open vault, so a copy in the backed-up
 * `public/` directory showed that a vault was set up. An older copy there
 * moves out the first time the setting is asked for.
 */
@RunWith(AndroidJUnit4::class)
class SecuritySettingLocationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aSettingInThePublicDirectoryMovesOutOfTheBackup() {
        val name = "security-setting-location-test.txt"
        val legacy = File(File(context.filesDir, "public").apply { mkdirs() }, name)
        legacy.writeText("strict")
        try {
            val path = context.securitySettingFile(name)
            val moved = File(path)
            assertTrue(moved.path.startsWith(context.noBackupFilesDir.path))
            assertEquals("strict", moved.readText())
            assertFalse("the backed-up copy must be gone", legacy.exists())
        } finally {
            legacy.delete()
            File(File(context.noBackupFilesDir, "settings"), name).delete()
        }
    }
}
