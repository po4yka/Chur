package dev.po4yka.chur.app

import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PublicShellHostTest {
    @Test
    fun camera_requires_the_public_disclosure_on_first_start_and_until_acknowledged() = scenario { root ->
        val notes = InMemoryNoteStore()
        val setting = PublicShellSetting({ "camera" }, {})
        var controller = controller(root, setting, notes)
        try {
            controller.start()
            assertTrue(controller.publicDisclosureRequired.value)
            // Cancelling capture does not acknowledge anything. Restart still asks.
            controller.shutdown()
            controller = controller(root, setting, notes)
            controller.start()
            assertTrue(controller.publicDisclosureRequired.value)
            controller.acknowledgeDisclosure()
            withTimeout(10_000) { controller.publicDisclosureRequired.first { !it } }
            controller.shutdown()
            controller = controller(root, setting, notes)
            controller.start()
            assertFalse(controller.publicDisclosureRequired.value)
        } finally { controller.shutdown() }
    }

    @Test
    fun selection_requires_authentication_and_survives_restart_without_changing_notes() = scenario { root ->
        var stored: String? = null
        val notes = InMemoryNoteStore()
        val note = Note("public-shell-fixture", "Public fixture", "Notes stay in Notes", 1L)
        notes.put(note)
        val setting = PublicShellSetting({ stored }, { stored = it })
        var controller = controller(root, setting, notes)
        try {
            controller.start()
            controller.goTo(AppRoute.PublicSettings)
            controller.setPublicShell(PublicShell.CAMERA)
            assertEquals(PublicShell.NOTES, controller.publicShell.value)
            controller.create("Public-shell-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            for (shell in PublicShell.entries) {
                controller.setPublicShell(shell)
                assertEquals(shell, controller.publicShell.value)
                assertEquals(listOf(note), notes.all())
            }
            controller.lock()
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
            controller.shutdown()
            controller = controller(root, setting, notes)
            controller.start()
            assertEquals(PublicShell.PET, controller.publicShell.value)
            assertEquals(listOf(note), notes.all())
            controller.openVaultEntry()
            assertEquals(AppRoute.Unlock, controller.route.value)
        } finally { controller.shutdown() }
    }

    @Test
    fun failed_preference_write_keeps_the_old_shell_and_reports_an_error() = scenario { root ->
        val controller = controller(root, PublicShellSetting({ "notes" }, { error("fixture write failure") }))
        try {
            controller.start()
            controller.setPublicShell(PublicShell.CAMERA)
            assertEquals(PublicShell.NOTES, controller.publicShell.value)
            controller.create("Public-shell-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            controller.setPublicShell(PublicShell.CAMERA)
            assertEquals(PublicShell.NOTES, controller.publicShell.value)
            assertNotNull(controller.publicShellError.value)
        } finally { controller.shutdown() }
    }

    @Test
    fun a_camera_lease_ends_once_and_cannot_end_a_newer_prompt_after_panic() = scenario { root ->
        val controller = controller(root, PublicShellSetting({ "camera" }, {}))
        try {
            controller.start()
            controller.create("Public-camera-lease-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            controller.goTo(AppRoute.PublicShell)
            val old = controller.beginPublicCameraActivity()
            controller.onBackground()
            assertIs<VaultState.Unlocked>(controller.vaultState.value)
            controller.goTo(AppRoute.PublicSettings)
            controller.panic()
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
            withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
            controller.unlock("Public-camera-lease-password")
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            withTimeout(10_000) { controller.unlocking.first { !it } }
            val prompt = controller.beginPrompt()
            old(); old()
            controller.onBackground()
            assertIs<VaultState.Unlocked>(controller.vaultState.value)
            controller.endPrompt(prompt)
            controller.onBackground()
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
        } finally { controller.shutdown() }
    }

    @Test
    fun entry_setup_requires_the_real_trigger_and_successful_authentication() = scenario { root ->
        val files = mutableMapOf<String, String>()
        val store = dev.po4yka.chur.app.publicshell.PublicUtilityStore(files::get, files::put)
        val controller = ChurController(storageRoot = root.path, privacy = NoPrivacyCover, exports = NoExports,
            notes = InMemoryNoteStore(), publicUtilities = store, clock = { 1_700_000_000_000L })
        try {
            controller.start()
            controller.create("Public-shell-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            controller.setPublicShell(PublicShell.PET)
            controller.beginDiscreetSetup("01234567")
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
            assertFalse(store.discreet.value)
            assertFalse(controller.submitPublicCode("1234567"))
            assertTrue(controller.submitPublicCode("01234567"))
            assertEquals(AppRoute.Unlock, controller.route.value)
            assertFalse(store.discreet.value)
            controller.unlock("wrong-password")
            withTimeout(10_000) { controller.unlocking.first { !it } }
            assertFalse(store.discreet.value)
            controller.unlock("Public-shell-password")
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            assertTrue(store.discreet.value)
            assertTrue(store.matches("01234567"))
            assertFalse(files.values.any { "01234567" in it })
            controller.disableDiscreetMode()
            controller.beginDiscreetSetup("87654321")
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
            controller.openVaultEntry() // Recovery entry authenticates, but does not complete a trial.
            controller.unlock("Public-shell-password")
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            assertFalse(store.discreet.value)
        } finally { controller.shutdown() }
    }

    @Test
    fun first_launch_choice_is_one_use_and_never_grants_vault_access() = scenario { root ->
        var choice: String? = null
        val guide = dev.po4yka.chur.app.onboarding.GuideCoordinator(freshInstall = true)
        val controller = ChurController(storageRoot = root.path, privacy = NoPrivacyCover, exports = NoExports,
            publicShellSetting = PublicShellSetting({ choice }, { choice = it }), guide = guide,
            clock = { 1_700_000_000_000L })
        try {
            controller.start()
            controller.chooseInitialShell(PublicShell.PET)
            assertEquals("pet", choice)
            assertEquals(AppRoute.PublicShell, controller.route.value)
            assertIs<VaultState.NoVault>(controller.vaultState.value)
            controller.chooseInitialShell(PublicShell.CAMERA)
            controller.setPublicShell(PublicShell.CAMERA)
            assertEquals("pet", choice)
            controller.startPrivateGuide()
            assertEquals(dev.po4yka.chur.app.onboarding.GuideStep.PRIVACY, guide.session.value?.step)
            guide.event(dev.po4yka.chur.app.onboarding.GuideStep.PRIVACY)
            controller.openVaultEntry()
            controller.create("Guide-recovery-fixture", offerRecovery = true)
            withTimeout(10_000) { controller.recoveryPhrase.first { it != null } }
            assertEquals(dev.po4yka.chur.app.onboarding.GuideStep.RECOVERY, guide.session.value?.step)
            guide.skip()
            assertNotNull(controller.recoveryPhrase.value)
            assertIs<VaultState.Creating>(controller.vaultState.value)
            controller.acknowledgeRecoveryPhrase()
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            assertEquals(dev.po4yka.chur.app.onboarding.GuideStep.IMPORT, guide.session.value?.step)
        } finally { controller.shutdown() }
    }

    @Test
    fun private_guide_is_concealed_on_background_but_picker_return_can_continue() = scenario { root ->
        val controller = controller(root, PublicShellSetting.unset())
        try {
            controller.start()
            controller.create("Guide-test-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            controller.startPrivateGuide()
            val token = controller.guide.session.value!!.token
            controller.setVaultNavigationBusy(busy = true, obscureGuide = false)
            assertFalse(controller.guideObscured.value)
            controller.setVaultNavigationBusy(busy = true, obscureGuide = true)
            controller.foreground()
            assertTrue(controller.guideObscured.value)
            controller.setVaultNavigationBusy(busy = true, obscureGuide = false)
            controller.beginHostActivity()
            controller.onBackground()
            assertTrue(controller.guideObscured.value)
            assertEquals(token, controller.guide.session.value?.token)
            controller.endHostActivity()
            controller.foreground()
            assertFalse(controller.guideObscured.value)
            controller.panic()
            assertNull(controller.guide.session.value)
            controller.guide.event(dev.po4yka.chur.app.onboarding.GuideStep.IMPORT, token)
            assertNull(controller.guide.session.value)
        } finally { controller.shutdown() }
    }

    @Test
    fun library_search_result_completes_the_search_lesson_even_when_empty() = scenario { root ->
        val controller = controller(root, PublicShellSetting.unset())
        try {
            controller.start()
            controller.create("Guide-test-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            controller.startPrivateGuide()
            controller.guide.event(dev.po4yka.chur.app.onboarding.GuideStep.IMPORT)
            controller.guide.event(dev.po4yka.chur.app.onboarding.GuideStep.OPEN)
            controller.load(dev.po4yka.chur.ffi.ObjectQuery(dev.po4yka.chur.ffi.QueryScope.TIMELINE))
            assertEquals(dev.po4yka.chur.app.onboarding.GuideStep.SEARCH, controller.guide.session.value?.step)
            controller.load(dev.po4yka.chur.ffi.ObjectQuery(dev.po4yka.chur.ffi.QueryScope.TIMELINE, terms = "no matches"))
            withTimeout(10_000) { controller.guide.session.first { it?.step == dev.po4yka.chur.app.onboarding.GuideStep.LOCK } }
        } finally { controller.shutdown() }
    }

    private fun controller(root: File, setting: PublicShellSetting, notes: InMemoryNoteStore = InMemoryNoteStore()) =
        ChurController(storageRoot = root.path, privacy = NoPrivacyCover, exports = NoExports,
            publicShellSetting = setting, notes = notes, clock = { 1_700_000_000_000L })

    private fun scenario(body: suspend (File) -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-public-shells-${System.nanoTime()}").apply { mkdirs() }
        try { body(root) } finally { Dispatchers.resetMain(); root.deleteRecursively() }
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }
}
