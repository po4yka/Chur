package dev.po4yka.chur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The system security patch line, `ANDROID.md` §25.1. */
@RunWith(AndroidJUnit4::class)
class SystemSecurityTest {
    private val current = SystemPatchLevels(installed = "2026-08-05", ready = null)

    @Test
    fun noInstalledLevelHidesTheRow() {
        assertNull(systemSecurityLine(null, PublishedCheck.NotChecked))
    }

    @Test
    fun theRowNamesTheHostBeforeTheFirstTap() {
        assertEquals(
            "Installed: 2026-08-05. Tap to compare with the Android Security Bulletin on osv.dev.",
            systemSecurityLine(current, PublishedCheck.NotChecked),
        )
    }

    @Test
    fun aStagedUpdateAndAnOlderDeviceAreBothStated() {
        assertEquals(
            "Installed: 2026-08-05. Update to 2026-09-01 is ready in system settings. Latest published: 2026-09-01.",
            systemSecurityLine(current.copy(ready = "2026-09-01"), PublishedCheck.Done("2026-09-01", covered = false)),
        )
    }

    @Test
    fun aFailedCheckKeepsTheInstalledLevel() {
        assertEquals(
            "Installed: 2026-08-05. Couldn't reach osv.dev. Tap to try again.",
            systemSecurityLine(current, PublishedCheck.Failed),
        )
    }

    @Test
    fun aReportAboveTheLimitIsRefused() {
        val bytes = ByteArray(10) { it.toByte() }
        assertEquals(bytes.toList(), ByteArrayInputStream(bytes).readAtMost(10)?.toList())
        assertNull(ByteArrayInputStream(bytes).readAtMost(9))
    }
}
