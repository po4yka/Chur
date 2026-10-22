package dev.po4yka.chur.app.onboarding

import kotlin.test.*

class GuideTest {
    @Test fun outgoing_native_scene_cannot_remove_the_active_scenes_target() {
        val guide = GuideCoordinator()
        val outgoing = Any()
        val active = Any()
        guide.target(outgoing, "open", false)
        guide.target(active, "open", true)
        guide.releaseTargets(outgoing)
        assertEquals(true, guide.targets.value["open"])
        guide.releaseTargets(active)
        assertNull(guide.targets.value["open"])
    }
    @Test fun verified_entry_can_offer_only_the_public_pet_lesson_after_concealment() {
        val guide = GuideCoordinator().apply { offerPetLesson = true; startPrivate() }
        guide.conceal()
        guide.verifiedEntry()
        assertNull(guide.session.value)
        assertTrue(guide.petLessonPending)
        guide.showPendingPetLesson()
        assertTrue(guide.session.value!!.publicOnly)
        assertEquals(GuideStep.PET_GREET, guide.session.value!!.step)
        assertFalse(guide.petLessonPending)
    }
    @Test fun fresh_install_starts_once_and_never_restores_a_private_route() {
        var saved: String? = null
        val store = GuideStore({ saved }, { saved = it })
        val first = GuideCoordinator(store, true)
        assertEquals(GuideStep.CHOOSE, first.session.value?.step)
        assertEquals("1:seen", saved)
        first.event(GuideStep.CHOOSE)
        assertEquals(GuideStep.PRIVACY, first.session.value?.step)
        assertNull(GuideCoordinator(store, true).session.value)
    }
    @Test fun existing_install_restored_data_or_read_failure_never_starts_automatically() {
        assertNull(GuideCoordinator(GuideStore(), false).session.value)
        assertNull(GuideCoordinator(GuideStore({ error("read failed") }), true).session.value)
        assertNull(GuideCoordinator(GuideStore({ "1:complete" }), true).session.value)
    }
    @Test fun unknown_or_corrupt_status_is_not_overwritten() {
        for (raw in listOf("", "2:seen", "1:private-route", "broken")) {
            var writes = 0
            val guide = GuideCoordinator(GuideStore({ raw }, { writes++ }), true)
            guide.startPrivate(); guide.stop()
            assertEquals(0, writes)
            assertFalse(guide.bootstrapAllowed)
        }
    }
    @Test fun write_failure_does_not_claim_fresh_bootstrap() {
        val guide = GuideCoordinator(GuideStore(write = { error("disk full") }), true)
        assertFalse(guide.bootstrapAllowed)
        assertNull(guide.session.value)
    }
    @Test fun only_the_expected_result_advances_the_current_session() {
        val guide = GuideCoordinator()
        guide.startPrivate()
        val old = guide.session.value!!.token
        guide.event(GuideStep.OPEN, old)
        assertEquals(GuideStep.IMPORT, guide.session.value?.step)
        guide.event(GuideStep.IMPORT, old)
        assertEquals(GuideStep.OPEN, guide.session.value?.step)
        guide.conceal(); guide.startPrivate()
        guide.event(GuideStep.IMPORT, old)
        assertEquals(GuideStep.IMPORT, guide.session.value?.step)
    }
    @Test fun concealing_revokes_late_results_and_cannot_resume_by_itself() {
        val guide = GuideCoordinator()
        guide.startPrivate()
        val token = guide.session.value!!.token
        guide.conceal()
        guide.event(GuideStep.IMPORT, token)
        assertNull(guide.session.value)
    }
    @Test fun public_pet_tour_has_no_private_steps() {
        val guide = GuideCoordinator()
        guide.startPet()
        assertTrue(guide.session.value!!.publicOnly)
        assertEquals(GuideCoordinator.petSteps, guide.session.value!!.steps)
        repeat(GuideCoordinator.petSteps.size) { guide.skip() }
        assertNull(guide.session.value)
    }
}
