package dev.po4yka.chur.android

import android.content.Intent
import android.view.Choreographer
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.vault.VaultState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back on the routes of `ChurRoutes`.
 *
 * Without a handler Back is the platform's back-to-home: the activity pauses,
 * and the background lock of `MainActivity.onPause` closes the vault and drops
 * what the screen held. Each case presses Back once and checks that the
 * application stayed in front and landed where the screen's own control goes.
 * The vault's own ladder is a pure function, pinned by `VaultBackTest`.
 */
@RunWith(AndroidJUnit4::class)
class BackNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val controller: ChurController get() = ChurHost.of(activity).controller

    @Before
    fun start() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
    }

    @After
    fun finish() {
        activity.finish()
    }

    @Test
    fun theNotesRootLetsBackThrough() {
        instrumentation.runOnMainSync { controller.goTo(AppRoute.PublicShell) }
        awaitFrames()
        instrumentation.runOnMainSync {
            assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }

    @Test
    fun publicSettingsReturnsToNotes() {
        assertEquals(AppRoute.PublicShell, backFrom(AppRoute.PublicSettings))
    }

    @Test
    fun unlockReturnsToTheSettingsEntry() {
        assertEquals(AppRoute.PublicSettings, backFrom(AppRoute.Unlock))
    }

    @Test
    fun recoveryReturnsToItsUnlockScreen() {
        assertEquals(AppRoute.Unlock, backFrom(AppRoute.Recover))
        assertEquals(AppRoute.AppUnlock, backFrom(AppRoute.AppRecover))
    }

    @Test
    fun creationAndRestoreWithoutASessionReturnToNotes() {
        assertTrue(controller.vaultState.value !is VaultState.Unlocked)
        assertEquals(AppRoute.PublicShell, backFrom(AppRoute.CreateVault))
        assertEquals(AppRoute.PublicShell, backFrom(AppRoute.RestoreBackup))
    }

    @Test
    fun backDoesNotSpendTheOneShowingOfTheRecoveryPhrase() {
        // It needs a first creation, so it runs on an application without a
        // vault: a fresh install, or after `adb shell pm clear dev.po4yka.chur`.
        // A device that holds a vault skips it rather than touching that vault.
        assumeTrue(controller.vaultState.value is VaultState.NoVault)
        try {
            instrumentation.runOnMainSync {
                controller.goTo(AppRoute.CreateVault)
                controller.create("BackNavigationTest-password", offerRecovery = true)
            }
            val deadline = System.currentTimeMillis() + 60_000
            while (controller.recoveryPhrase.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            assertNotNull("the creation must reach the phrase", controller.recoveryPhrase.value)

            pressBack()

            assertNotNull("Back must not leave the phrase", controller.recoveryPhrase.value)
        } finally {
            // A phrase left on the singleton controller would cover every
            // route of the tests that follow.
            instrumentation.runOnMainSync {
                controller.acknowledgeRecoveryPhrase()
                controller.lock()
            }
        }
    }

    /** Shows [route], presses Back once, and returns where the press landed. */
    private fun backFrom(route: AppRoute): AppRoute {
        instrumentation.runOnMainSync { controller.goTo(route) }
        pressBack()
        return controller.route.value
    }

    private fun pressBack() {
        awaitFrames()
        instrumentation.runOnMainSync {
            // Without a handler the press below would go home. This fails at
            // once rather than through the pause that follows.
            assertTrue("the route must register a Back handler", activity.onBackPressedDispatcher.hasEnabledCallbacks())
            activity.onBackPressedDispatcher.onBackPressed()
        }
        awaitFrames()
        assertEquals(
            "Back must stay in the application",
            Lifecycle.State.RESUMED,
            activity.lifecycle.currentState,
        )
    }

    /** One frame recomposes the new route, the next runs the effects that register its handler. */
    private fun awaitFrames() {
        repeat(2) {
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync {
                Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
            assertTrue(frame.await(5, TimeUnit.SECONDS))
        }
        instrumentation.waitForIdleSync()
    }
}
