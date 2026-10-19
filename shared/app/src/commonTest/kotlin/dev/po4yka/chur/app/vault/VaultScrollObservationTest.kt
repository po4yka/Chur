package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VaultScrollObservationTest {
    @Test
    fun a_removed_viewport_rejects_a_late_collector_and_disposes_once() {
        val updates = mutableListOf<VaultScrollEdges>()
        var disposals = 0
        val lease = VaultScrollObservationLease()
        lease.attach(object : VaultScrollObserver {
            override fun attach() = object : VaultScrollObservation {
                override fun update(edges: VaultScrollEdges) { updates += edges }
                override fun dispose() { disposals++ }
            }
        })
        lease.update(VaultScrollEdges(atTop = true, atBottom = false))
        lease.dispose()
        lease.update(VaultScrollEdges(atTop = false, atBottom = true))
        lease.dispose()

        assertEquals(listOf(VaultScrollEdges(atTop = true, atBottom = false)), updates)
        assertEquals(1, disposals)
    }

    @Test
    fun a_new_viewport_gets_a_fresh_observation_and_cannot_revive_the_previous_one() {
        val observations = mutableListOf<MutableList<VaultScrollEdges>>()
        val observer = object : VaultScrollObserver {
            override fun attach(): VaultScrollObservation {
                val updates = mutableListOf<VaultScrollEdges>().also(observations::add)
                return object : VaultScrollObservation {
                    override fun update(edges: VaultScrollEdges) { updates += edges }
                    override fun dispose() = Unit
                }
            }
        }
        val grid = VaultScrollObservationLease()
        grid.attach(observer)
        grid.update(VaultScrollEdges(atTop = false, atBottom = false))
        grid.dispose()
        val list = VaultScrollObservationLease()
        list.attach(observer)
        list.update(VaultScrollEdges(atTop = true, atBottom = true))
        grid.update(VaultScrollEdges(atTop = true, atBottom = false))

        assertEquals(listOf(
            listOf(VaultScrollEdges(atTop = false, atBottom = false)),
            listOf(VaultScrollEdges(atTop = true, atBottom = true)),
        ), observations)
        assertFailsWith<IllegalStateException> { grid.attach(observer) }
        list.dispose()
    }
}
