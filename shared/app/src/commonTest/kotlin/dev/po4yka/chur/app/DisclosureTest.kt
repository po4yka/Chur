package dev.po4yka.chur.app

import dev.po4yka.chur.app.notes.Disclosure
import dev.po4yka.chur.app.publicshell.PUBLIC_CAMERA_DISCLOSURE
import kotlin.test.*

class DisclosureTest {
    @Test fun publicDisclosureStatesStorageAndBackupWithoutRevealingPrivateFeatures() {
        for (text in listOf(Disclosure.FIRST_WRITE, Disclosure.SETTINGS, PUBLIC_CAMERA_DISCLOSURE)) {
            assertTrue("not encrypted" in text)
            assertTrue("system backup" in text)
            assertFalse("vault" in text.lowercase())
            assertFalse("chur" in text.lowercase())
            assertFalse("only you" in text.lowercase())
        }
    }
    @Test fun publicDataCategoriesAreNamed() {
        for (category in listOf("Notes", "calculation history", "photos", "videos")) assertTrue(category in Disclosure.SETTINGS)
    }
}
