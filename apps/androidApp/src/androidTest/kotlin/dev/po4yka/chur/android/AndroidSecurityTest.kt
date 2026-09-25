package dev.po4yka.chur.android

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.core.platformkeys.DeviceSlot
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level checks for the Android security controls in SECURITY_TEST_PLAN.md §8. */
@RunWith(AndroidJUnit4::class)
class AndroidSecurityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun deviceSlotKeyPolicy() {
        val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
        assertTrue("set a secure lock screen before this device test", keyguard.isDeviceSecure)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val before = store.aliases().toList().toSet()
        val identifier = ByteArray(32).also(SecureRandom()::nextBytes)
        val slot = DeviceSlot(identifier)

        try {
            slot.provision(DeviceSlotPolicy.CONVENIENT)
            assertTrue(slot.isProvisioned())
            store.load(null)
            val created = store.aliases().toList().toSet() - before
            assertEquals("one disposable slot key must be created", 1, created.size)
            val key = (store.getEntry(created.single(), null) as KeyStore.SecretKeyEntry).secretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo

            assertEquals(256, info.keySize)
            assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
            assertTrue(info.blockModes.contains(KeyProperties.BLOCK_MODE_GCM))
            assertTrue(info.isUserAuthenticationRequired)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertEquals(0, info.userAuthenticationValidityDurationSeconds)
                assertEquals(
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    info.userAuthenticationType,
                )
            } else {
                assertEquals(10, info.userAuthenticationValidityDurationSeconds)
            }
            assertNull("the secret key must not be exportable", key.encoded)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                println("CHUR_SECURITY_KEY_LEVEL=${info.securityLevel}")
            } else {
                println("CHUR_SECURITY_KEY_IN_HARDWARE=${info.isInsideSecureHardware}")
            }
        } finally {
            slot.destroy()
            assertFalse(slot.isProvisioned())
        }
    }

    @Test
    fun backgroundWindowIsSecure() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            val paused = CountDownLatch(1)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) paused.countDown()
            }
            var moved = false
            instrumentation.runOnMainSync {
                activity.lifecycle.addObserver(observer)
                moved = activity.moveTaskToBack(true)
            }
            assertTrue("the app task must move to the background", moved)
            assertTrue("the activity must pause", paused.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { activity.lifecycle.removeObserver(observer) }
            assertTrue(
                "the background window must block recents capture; state=${activity.lifecycle.currentState}",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        } finally {
            activity.finish()
        }
    }

    @Test
    fun exportProviderIsNotExported() {
        val context = instrumentation.targetContext
        val provider = context.packageManager.resolveContentProvider(
            "${context.packageName}.exports",
            PackageManager.GET_META_DATA,
        )
        assertNotNull(provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
    }
}
