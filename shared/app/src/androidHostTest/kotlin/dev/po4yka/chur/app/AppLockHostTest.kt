package dev.po4yka.chur.app

import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppLockHostTest {
    @Test
    fun whole_app_lock_opens_only_the_public_shell_and_survives_relaunch() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-app-lock-${System.nanoTime()}")
        root.mkdirs()
        var wholeApp = false
        val setting = AppLockSetting({ wholeApp }, { wholeApp = it })
        fun controller() = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            appLockSetting = setting,
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
        )
        try {
            val first = controller()
            first.start()
            first.create("123456789012", offerRecovery = false)
            withTimeout(10_000) { first.route.first { it == AppRoute.Vault } }
            first.toggleAppLock()
            withTimeout(10_000) { first.appLockEnabled.first { it } }

            first.onBackground()
            assertEquals(AppRoute.AppUnlock, first.route.value)
            assertIs<VaultState.Locked>(first.vaultState.value)

            first.unlock("123456789012")
            withTimeout(10_000) { first.route.first { it == AppRoute.PublicShell } }
            assertIs<VaultState.Locked>(first.vaultState.value)
            assertTrue(wholeApp)
            first.vault.shutdown()

            val relaunched = controller()
            assertEquals(AppRoute.AppUnlock, relaunched.route.value)
            relaunched.start()
            assertEquals(AppRoute.AppUnlock, relaunched.route.value)
            relaunched.vault.shutdown()
        } finally {
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private object NoExports : ExportSink {
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(
            displayName: String,
            contentType: String,
            target: ExportTarget,
            uri: String?,
        ): ExportSink.Destination? = null
    }
}
