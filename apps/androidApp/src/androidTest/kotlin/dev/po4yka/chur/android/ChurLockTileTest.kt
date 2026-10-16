package dev.po4yka.chur.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.GateResult
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The production tile callback and native vault, with disposable public fixture storage. */
@RunWith(AndroidJUnit4::class)
class ChurLockTileTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun manifest_exposes_only_a_permission_guarded_same_process_non_toggleable_tile() {
        val context = instrumentation.targetContext
        val component = ComponentName(context, ChurLockTileService::class.java)
        val service = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertTrue(service.exported)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, service.permission)
        assertEquals(context.applicationInfo.processName, service.processName)
        assertFalse(service.metaData?.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE, false) ?: false)
        val matches = context.packageManager.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        assertEquals(1, matches.count { it.serviceInfo.name == component.className })
        assertEquals("Lock Chur", service.loadLabel(context.packageManager).toString())
    }

    /** Run this class in its own process: the production singleton has no test reset hook. */
    @Test
    fun tile_locks_through_host_activity_suppression_and_preserves_native_and_public_notes() = runBlocking {
        assertNull("This class requires a fresh instrumentation process", ChurHost.existing())
        val root = File(instrumentation.targetContext.cacheDir, "quick-settings-lock-${System.nanoTime()}").apply { mkdirs() }
        val context = FixtureContext(instrumentation.targetContext, root)
        val publicFile = File(context.filesDir, "public/notes.json")
        publicFile.parentFile!!.mkdirs()
        val publicStore = FileNoteStore(publicFile.path)
        val publicNote = Note("tile-public-fixture", "Public tile fixture", "Public notes stay unchanged", 1L)
        publicStore.put(publicNote)
        val originalPublicBytes = publicFile.readBytes()
        assertTrue("The native ABI gate must pass before host creation", runGate(context) is GateResult.Compatible)
        val host = withContext(Dispatchers.Main) { ChurHost.of(context) }
        val controller = host.controller
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, true)
        try {
            withContext(Dispatchers.Main) { controller.start(); controller.openVaultEntry() }
            assertEquals(AppRoute.CreateVault, controller.route.value)
            clickTile()
            awaitRoute(AppRoute.PublicShell)
            assertEquals(VaultState.NoVault, controller.vaultState.value)

            withContext(Dispatchers.Main) { controller.openVaultEntry(); controller.create(PASSWORD, offerRecovery = false) }
            awaitRoute(AppRoute.Vault)
            val generation = (controller.vaultState.value as VaultState.Unlocked).generation
            val note = controller.vault.savePrivateNote(generation, title = "Public native fixture", body = PRIVATE_BODY)
            withContext(Dispatchers.Main) {
                controller.beginHostActivity()
                host.deviceControl.start()
                // This is the picker case: ordinary background handling must leave it open.
                controller.onBackground()
            }
            assertTrue(controller.vaultState.value is VaultState.Unlocked)
            assertTrue(host.deviceControl.pairing.value != null)
            clickTile()
            awaitRoute(AppRoute.PublicShell)
            assertTrue("The tile must lock despite the host activity bracket", controller.vaultState.value is VaultState.Locked)
            assertNull("Desktop control must close with the session", host.deviceControl.pairing.value)
            assertSame(host, ChurHost.existing())
            assertEquals(listOf(publicNote), publicStore.all())
            assertTrue(originalPublicBytes.contentEquals(publicFile.readBytes()))

            // A repeated tap is still a lock, never an unlock or a new activity.
            withContext(Dispatchers.Main) { controller.openVaultEntry() }
            clickTile()
            awaitRoute(AppRoute.PublicShell)
            assertTrue(controller.vaultState.value is VaultState.Locked)
            unlock()
            val reopened = (controller.vaultState.value as VaultState.Unlocked).generation
            assertEquals(PRIVATE_BODY, controller.vault.privateNote(reopened, note.noteId).body)

            withContext(Dispatchers.Main) { controller.toggleAppLock() }
            withTimeout(20_000) { controller.appLockEnabled.first { it } }
            clickTile()
            awaitRoute(AppRoute.AppUnlock)
            assertTrue(controller.vaultState.value is VaultState.Locked)
            assertEquals(listOf(publicNote), publicStore.all())
            assertTrue(originalPublicBytes.contentEquals(publicFile.readBytes()))
            assertEquals("No tile tap may launch an activity", 0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            withContext(Dispatchers.Main) { host.deviceControl.stop(); controller.endHostActivity(); controller.shutdown() }
            originalPublicBytes.fill(0)
            root.deleteRecursively()
        }
    }

    private suspend fun clickTile() = withContext(Dispatchers.Main) { ChurLockTileService().onClick() }

    private suspend fun awaitRoute(route: AppRoute) {
        withTimeout(20_000) { requireNotNull(ChurHost.existing()).controller.route.first { it == route } }
    }

    private suspend fun unlock() {
        val controller = requireNotNull(ChurHost.existing()).controller
        withContext(Dispatchers.Main) { controller.openVaultEntry(); controller.unlock(PASSWORD) }
        awaitRoute(AppRoute.Vault)
        withTimeout(20_000) { controller.unlocking.first { !it } }
        assertTrue(controller.vaultState.value is VaultState.Unlocked)
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    }

    private companion object {
        const val PASSWORD = "Quick-settings-native-fixture-password"
        const val PRIVATE_BODY = "Synthetic note retained after system tile lock"
    }
}
