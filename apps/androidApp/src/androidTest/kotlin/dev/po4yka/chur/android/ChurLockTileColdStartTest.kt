package dev.po4yka.chur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Run in its own instrumentation process, before any test that creates a host. */
@RunWith(AndroidJUnit4::class)
class ChurLockTileColdStartTest {
    @Test
    fun tapping_without_a_host_does_not_create_a_runtime_or_launch_an_activity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, true)
        try {
            assertNull("This test requires a fresh instrumentation process", ChurHost.existing())
            instrumentation.runOnMainSync {
                val service = ChurLockTileService()
                service.onClick()
                service.onClick()
            }
            instrumentation.waitForIdleSync()
            assertNull("A tile tap must never initialize the process host", ChurHost.existing())
            assertEquals("A tile tap must not open Chur", 0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
