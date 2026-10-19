package dev.po4yka.chur.app.vault

import dev.po4yka.chur.app.IosRouteModalVisit
import kotlin.test.Test
import kotlin.test.assertEquals

class IosRouteModalVisitTest {
    @Test
    fun choosing_or_cancelling_consumes_the_opening_before_reentrant_callbacks() {
        val visit = IosRouteModalVisit()
        var actions = 0
        visit.complete(true) {
            actions++
            visit.complete(true) { actions++ }
        }
        visit.complete(true) { actions++ }
        assertEquals(1, actions)
    }

    @Test
    fun retired_opening_cannot_act_after_the_same_route_is_opened_again() {
        val old = IosRouteModalVisit()
        old.retire()
        val current = IosRouteModalVisit()
        var actions = 0
        old.complete(true) { actions += 100 }
        current.complete(true) { actions++ }
        old.complete(true) { actions += 100 }
        assertEquals(1, actions)
    }

    @Test
    fun failed_live_eligibility_does_not_dispatch_and_disposal_permanently_revokes_it() {
        val visit = IosRouteModalVisit()
        var actions = 0
        visit.complete(false) { actions++ }
        assertEquals(0, actions)
        visit.retire()
        visit.complete(true) { actions++ }
        assertEquals(0, actions)
    }
}
