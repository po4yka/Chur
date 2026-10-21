package dev.po4yka.chur.android

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.core.platformkeys.DeviceSlot
import dev.po4yka.chur.core.platformkeys.DeviceSlotException
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
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

    /**
     * `ANDROID.md` §9.2: a slot key lives in unlocked secure hardware.
     *
     * The test first asks the Keystore what it gives a key under the slot's
     * own spec. On secure hardware the slot key must carry today's policy. On
     * a Keystore that keeps keys in software, an emulator for example,
     * `provision` must refuse with `PLATFORM_KEY_UNAVAILABLE` and leave no key
     * behind. Both branches assert; neither skips.
     */
    @Test
    fun deviceSlotKeyPolicy() {
        val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
        assertTrue("set a secure lock screen before this device test", keyguard.isDeviceSecure)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val hardware = keystoreKeepsSlotKeysInHardware(store)
        println("CHUR_SECURITY_KEYSTORE_HARDWARE=$hardware")
        store.load(null)
        val before = store.aliases().toList().toSet()
        val identifier = ByteArray(32).also(SecureRandom()::nextBytes)
        val slot = DeviceSlot(identifier)

        if (!hardware) {
            val refused = try {
                slot.provision(DeviceSlotPolicy.CONVENIENT)
                null
            } catch (failure: DeviceSlotException) {
                failure
            }
            try {
                assertNotNull("a software Keystore key must be refused", refused)
                assertEquals(ChurStatus.PLATFORM_KEY_UNAVAILABLE, refused?.status)
                assertFalse("the refused key must be deleted", slot.isProvisioned())
                store.load(null)
                assertEquals("no slot key may stay behind", before, store.aliases().toList().toSet())
            } finally {
                slot.destroy()
            }
            return
        }

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
                assertEquals(5, info.userAuthenticationValidityDurationSeconds)
            }
            assertNull("the secret key must not be exportable", key.encoded)
            assertTrue("the slot key must be in secure hardware", isSecureHardware(info))
        } finally {
            slot.destroy()
            assertFalse(slot.isProvisioned())
        }
    }

    /**
     * Generates a throwaway key under the slot's spec, reads where the
     * Keystore keeps it, and deletes it. The lock-screen and authentication
     * settings match `DeviceSlot`, so the answer is the one a slot key gets.
     */
    private fun keystoreKeepsSlotKeysInHardware(store: KeyStore): Boolean {
        val alias = "chur-keystore-probe-" + ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") {
            "%02x".format(it)
        }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUnlockedDeviceRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(5)
                }
            }
            .build()
        try {
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec) }
                .generateKey()
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                println("CHUR_SECURITY_KEY_LEVEL=${info.securityLevel}")
            }
            @Suppress("DEPRECATION")
            println("CHUR_SECURITY_KEY_IN_HARDWARE=${info.isInsideSecureHardware}")
            return isSecureHardware(info)
        } finally {
            store.load(null)
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
    }

    /** The rule `DeviceSlot.provision` applies, `ANDROID.md` §9.2. */
    private fun isSecureHardware(info: KeyInfo): Boolean {
        @Suppress("DEPRECATION")
        val inside = info.isInsideSecureHardware
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return inside
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            KeyProperties.SECURITY_LEVEL_STRONGBOX,
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
            -> true
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> false
            else -> inside
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
            // FLAG_SECURE does not stop autofill or content capture from reading the views.
            val decor = activity.window.decorView
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, decor.importantForAutofill)
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, decor.importantForContentCapture)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun mainActivityHasNoTaskAffinityThatAnotherAppCouldClaim() {
        val context = instrumentation.targetContext
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java), 0,
        )
        // The default affinity is the package name, which another app can declare to
        // put its own activity into Chur's task (task hijacking on API 29 and 30).
        assertTrue("taskAffinity=${info.taskAffinity}", info.taskAffinity.isNullOrEmpty())
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
