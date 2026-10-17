package dev.po4yka.chur.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticeHapticTest {
    @Test
    fun a_security_notice_is_acknowledged_once_across_hosts() {
        val notice = Notice(1, "The item could not be verified.", security = true)
        assertTrue(notice.consumeSecurityHaptic())
        // The same event stays when the viewer opens and when the shell returns.
        repeat(3) { assertFalse(notice.consumeSecurityHaptic()) }
        // A later event with the same message still needs its acknowledgement.
        assertTrue(Notice(2, notice.text, security = true).consumeSecurityHaptic())
    }

    @Test
    fun the_typed_security_flag_decides_and_message_text_does_not() {
        assertFalse(Notice(1, "Integrity failure").consumeSecurityHaptic())
        assertFalse(Notice(2, "Added 1 item to the album.").consumeSecurityHaptic())
        assertTrue(Notice(3, "Action required", security = true).consumeSecurityHaptic())
        // A new controller can reuse an ID without suppressing a distinct event.
        assertTrue(Notice(3, "Action required", security = true).consumeSecurityHaptic())
    }
}
