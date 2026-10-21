package dev.po4yka.chur.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A security setting that cannot be read is the stricter choice.
 *
 * The files used to be written with a truncate first, so a power loss could
 * leave them empty, and an empty file read as the convenient policy, the
 * vault-only lock and the default auto-lock, with no word to the user.
 */
class SettingTextTest {
    @Test
    fun a_missing_file_is_the_documented_default() {
        assertFalse(SettingText.strictPolicy(null))
        assertFalse(SettingText.wholeAppLock(null))
        assertEquals(AutoLock.DEFAULT, AutoLockSetting({ null }, {}).read())
    }

    @Test
    fun the_stored_words_round_trip() {
        for (strict in listOf(true, false)) {
            assertEquals(strict, SettingText.strictPolicy(SettingText.policyWord(strict)))
            assertEquals(strict, SettingText.wholeAppLock(SettingText.appLockWord(strict)))
        }
        assertFalse(SettingText.strictPolicy(" convenient\n"))
        assertTrue(SettingText.wholeAppLock("whole-app\n"))
    }

    @Test
    fun an_empty_or_damaged_file_is_the_stricter_choice() {
        for (damaged in listOf("", " ", "conven", "STRICT", "\u0000\u0000")) {
            assertTrue(SettingText.strictPolicy(damaged), "policy from '$damaged'")
            assertTrue(SettingText.wholeAppLock(damaged), "app lock from '$damaged'")
            assertEquals(AutoLock.AFTER_30_SECONDS, AutoLockSetting({ damaged }, {}).read())
        }
    }
}
