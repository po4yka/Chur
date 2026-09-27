package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `DESIGN.md` §13.1 and §13.4: what a tap on the viewer does to its chrome. */
class ViewerChromeTest {

    @Test
    fun a_tap_hides_the_chrome_and_the_next_tap_brings_it_back() {
        val chrome = ViewerChrome()
        assertTrue(chrome.visible(pinned = false), "the viewer opens with its chrome")

        chrome.toggle(pinned = false)
        assertFalse(chrome.visible(pinned = false))

        chrome.toggle(pinned = false)
        assertTrue(chrome.visible(pinned = false))
    }

    @Test
    fun a_pinned_chrome_stays_and_a_tap_leaves_it_as_it_is() {
        val chrome = ViewerChrome()

        chrome.toggle(pinned = true)
        assertTrue(chrome.visible(pinned = true), "a sheet or dialog keeps the chrome")
        assertTrue(chrome.visible(pinned = false), "and closing it does not hide the chrome")

        chrome.toggle(pinned = false)
        assertTrue(chrome.visible(pinned = true), "a sheet opened later shows the hidden chrome")
        assertFalse(chrome.visible(pinned = false))
    }

    /** §13.2: a tap on a video reaches its player, and the chrome follows the player's controls. */
    @Test
    fun the_chrome_goes_with_the_player_controls_unless_it_is_pinned() {
        val chrome = ViewerChrome()

        chrome.follow(visible = false, pinned = false)
        assertFalse(chrome.visible(pinned = false), "controls that hide take the chrome")
        chrome.follow(visible = false, pinned = false)
        assertFalse(chrome.visible(pinned = false), "a second report is not a toggle")

        chrome.follow(visible = true, pinned = false)
        assertTrue(chrome.visible(pinned = false), "controls that show bring it back")

        chrome.follow(visible = false, pinned = true)
        assertTrue(chrome.visible(pinned = false), "a sheet or dialog keeps the chrome, and the controls come back to it")
    }
}
