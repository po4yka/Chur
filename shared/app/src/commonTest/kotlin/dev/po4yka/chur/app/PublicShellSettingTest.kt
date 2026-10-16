package dev.po4yka.chur.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PublicShellSettingTest {
    @Test
    fun missing_or_unknown_preferences_keep_notes_available() {
        for (stored in listOf(null, "", "unknown", "vault", "../camera")) {
            assertEquals(PublicShell.NOTES, PublicShellSetting({ stored }, {}).read())
        }
    }

    @Test
    fun each_public_screen_round_trips_through_a_stable_token() {
        var stored: String? = null
        val setting = PublicShellSetting({ stored }, { stored = it })
        for (shell in PublicShell.entries) {
            setting.write(shell)
            assertEquals(shell.storedValue, stored)
            assertEquals(shell, setting.read())
        }
    }

    @Test
    fun reading_a_preference_reads_storage_once() {
        var reads = 0
        val setting = PublicShellSetting({ reads++; " camera " }, {})
        assertEquals(PublicShell.CAMERA, setting.read())
        assertEquals(1, reads)
    }
}
