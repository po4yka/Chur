@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import kotlinx.coroutines.flow.MutableStateFlow
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRetirementIosTest {
    @Test
    fun retirement_returns_before_a_blocked_read_and_closes_after_it_exactly_once() {
        val queue = dispatch_queue_create("dev.po4yka.chur.test.playback-retirement", null)
        val enteredRead = dispatch_semaphore_create(0)
        val unblockRead = dispatch_semaphore_create(0)
        val closed = dispatch_semaphore_create(0)
        val readFinished = MutableStateFlow(false)
        val closeAfterRead = MutableStateFlow(false)
        val closes = MutableStateFlow(0)
        dispatch_async(queue) {
            dispatch_semaphore_signal(enteredRead)
            // Bound the blocker so a regression cannot hang the native suite.
            dispatch_semaphore_wait(unblockRead, dispatch_time(DISPATCH_TIME_NOW, 2_000_000_000))
            readFinished.value = true
        }
        assertEquals(0L, dispatch_semaphore_wait(enteredRead, dispatch_time(DISPATCH_TIME_NOW, 2_000_000_000)))
        val retirement = IosPlaybackReaderRetirement(queue) {
            closeAfterRead.value = readFinished.value
            closes.value += 1
            dispatch_semaphore_signal(closed)
        }
        try {
            retirement.retire()
            retirement.retire()
            assertTrue(retirement.terminal, "Queued requests must see disposal immediately")
            assertFalse(readFinished.value, "Disposal must return while the read is still blocked")
            assertEquals(0, closes.value, "Closing cannot race the active reader")
        } finally {
            dispatch_semaphore_signal(unblockRead)
        }
        assertEquals(0L, dispatch_semaphore_wait(closed, dispatch_time(DISPATCH_TIME_NOW, 2_000_000_000)))
        assertTrue(closeAfterRead.value)
        assertEquals(1, closes.value)
        retirement.retire()
        val drained = dispatch_semaphore_create(0)
        dispatch_async(queue) { dispatch_semaphore_signal(drained) }
        assertEquals(0L, dispatch_semaphore_wait(drained, dispatch_time(DISPATCH_TIME_NOW, 2_000_000_000)))
        assertEquals(1, closes.value, "A retired reader cannot be reopened or closed twice")
    }
}
