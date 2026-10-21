package dev.po4yka.chur.core.platformkeys

import android.security.keystore.KeyProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `KEY_SLOTS.md` section 4: a device-slot key lives in secure hardware.
 *
 * A key the Keystore keeps in software is refused after generation, because a
 * copy of the app data then carries everything an offline attack needs.
 */
class SlotKeyHardwareTest {
    @Test
    fun api_31_reads_the_security_level() {
        assertTrue(isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, false))
        assertTrue(isHardwareBacked(33, KeyProperties.SECURITY_LEVEL_STRONGBOX, false))
        assertTrue(isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE, false))
        assertFalse(isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_SOFTWARE, true))
    }

    @Test
    fun an_unknown_level_and_older_releases_use_the_hardware_flag() {
        assertTrue(isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_UNKNOWN, true))
        assertFalse(isHardwareBacked(31, KeyProperties.SECURITY_LEVEL_UNKNOWN, false))
        assertTrue(isHardwareBacked(29, 0, true))
        assertFalse(isHardwareBacked(30, 0, false))
    }
}
