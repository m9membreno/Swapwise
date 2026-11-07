package dev.swapwise.optimizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParetoTest {
    private fun o(cost: Long, kg: Double, disruption: Double = 0.0) = Objectives(cost, kg, disruption)

    @Test
    fun `dominance needs no-worse everywhere and better somewhere`() {
        assertTrue(Pareto.dominates(o(100, 1.0), o(100, 2.0)))
        assertFalse(Pareto.dominates(o(100, 1.0), o(100, 1.0)), "equal points do not dominate each other")
        assertFalse(Pareto.dominates(o(90, 2.0), o(100, 1.0)), "a trade-off is not dominance")
    }

    @Test
    fun `sorts points into fronts`() {
        val points = listOf(
            o(100, 1.0), // 0: front 0
            o(50, 3.0), // 1: front 0 (cheaper, more carbon)
            o(110, 1.5), // 2: dominated by 0 -> front 1
            o(120, 2.0), // 3: dominated by 0 and 2 -> front 2
        )
        val fronts = Pareto.nonDominatedSort(points)
        assertEquals(listOf(setOf(0, 1), setOf(2), setOf(3)), fronts.map { it.toSet() })
    }

    @Test
    fun `boundary points get infinite crowding distance`() {
        val points = listOf(o(100, 1.0, 1.0), o(80, 2.0, 1.0), o(60, 3.0, 1.0), o(40, 4.0, 1.0))
        val distance = Pareto.crowdingDistance(listOf(0, 1, 2, 3), points)
        assertEquals(Double.POSITIVE_INFINITY, distance[0])
        assertEquals(Double.POSITIVE_INFINITY, distance[3])
        // Interior points: (gap in cost / cost range) + (gap in kg / kg range) = 40/60 + 2/3 each.
        assertEquals(4.0 / 3.0, distance.getValue(1), 1e-9)
        assertEquals(4.0 / 3.0, distance.getValue(2), 1e-9)
    }

    @Test
    fun `front drops duplicates and dominated baskets`() {
        val baskets = listOf(
            ScoredBasket(listOf(0), o(100, 2.0)),
            ScoredBasket(listOf(0), o(100, 2.0)),
            ScoredBasket(listOf(1), o(100, 1.0)),
        )
        assertEquals(listOf(listOf(1)), Pareto.front(baskets).map { it.choices })
    }
}
