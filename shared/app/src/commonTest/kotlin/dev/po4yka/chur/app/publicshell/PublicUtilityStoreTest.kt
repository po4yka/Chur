package dev.po4yka.chur.app.publicshell

import kotlin.test.*

class PublicUtilityStoreTest {
    @Test fun entryIsExplicitAndPersistsOnlyADigest() {
        val files = mutableMapOf<String, String>()
        val store = PublicUtilityStore(files::get, files::put)
        assertFalse(store.discreet.value)
        assertFalse(store.matches("01234567"))
        store.configure("01234567")
        assertTrue(store.matches("01234567"))
        assertFalse(store.matches("1234567"))
        assertFalse(store.matches("01234568"))
        assertFalse(files.values.any { "01234567" in it })
        val restored = PublicUtilityStore(files::get, files::put)
        assertTrue(restored.matches("01234567"))
        restored.disable()
        assertFalse(restored.matches("01234567"))
    }
    @Test fun historyRoundTripAndLimits() {
        val files = mutableMapOf<String, String>()
        val store = PublicUtilityStore(files::get, files::put)
        val rows = (1..500).map { Calculation("$it + 1", (it+1).toString()) }
        store.saveHistory(rows)
        assertEquals(rows, PublicUtilityStore(files::get, files::put).history())
        assertFailsWith<IllegalArgumentException> { store.saveHistory(rows + rows.first()) }
        assertEquals(rows, store.history())
    }
    @Test fun unknownFormatsAreNotOverwrittenAndDoNotRevealEntry() {
        val files = mutableMapOf("entry" to "{\"version\":99}", "calculator" to "{\"version\":99}")
        val original = files.toMap()
        val store = PublicUtilityStore(files::get, files::put)
        assertTrue(store.discreet.value)
        assertFalse(store.matches("12345678"))
        assertFailsWith<IllegalArgumentException> { store.configure("12345678") }
        assertFailsWith<IllegalArgumentException> { store.saveHistory(emptyList()) }
        assertEquals(original, files)
    }
    @Test fun failedWritesNeverChangePublishedState() {
        val store = PublicUtilityStore(write = { _, _ -> error("disk full") })
        assertFails { store.configure("12345678") }
        assertFalse(store.discreet.value)
        assertFails { store.setAppearance("dark") }
        assertEquals("system", store.appearance.value)
    }
}
