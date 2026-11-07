package dev.swapwise.optimizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelTest {
    @Test
    fun `rejects negative prices and invalid carbon`() {
        assertFailsWith<IllegalArgumentException> { product("x", -1, 1.0) }
        assertFailsWith<IllegalArgumentException> { product("x", 100, -0.1) }
        assertFailsWith<IllegalArgumentException> { product("x", 100, Double.NaN) }
    }

    @Test
    fun `rejects a zero quantity and an alternative that is the original`() {
        val milk = product("milk", 300, 3.0)
        assertFailsWith<IllegalArgumentException> { BasketLine(milk, 0) }
        assertFailsWith<IllegalArgumentException> { BasketLine(milk, 1, listOf(alt(milk))) }
    }

    @Test
    fun `budget defaults to the original cost and cannot be lower`() {
        val line = BasketLine(product("milk", 300, 3.0), quantity = 2)
        assertEquals(600, SwapProblem(listOf(line)).budgetCents)
        assertFailsWith<IllegalArgumentException> { SwapProblem(listOf(line), budgetCents = 599) }
    }

    @Test
    fun `search space multiplies option counts including keep-the-original`() {
        assertEquals(6, fundedSwapProblem().searchSpaceSize) // (1 + 1) * (1 + 2)
    }

    @Test
    fun `search space saturates instead of overflowing`() {
        val alternatives = List(9) { alt(product("a$it", 100, 0.5)) }
        val lines = List(40) { BasketLine(product("p$it", 100, 1.0), 1, alternatives) } // 10^40 baskets
        assertEquals(Long.MAX_VALUE, SwapProblem(lines).searchSpaceSize)
    }

    @Test
    fun `rejects invalid optimizer settings`() {
        assertFailsWith<IllegalArgumentException> { Nsga2Config(populationSize = 5) }
        assertFailsWith<IllegalArgumentException> { Nsga2Config(crossoverRate = 1.5) }
        assertFailsWith<IllegalArgumentException> { Nsga2Config(mutationRate = -0.1) }
        assertFailsWith<IllegalArgumentException> { SuggestionPicker(maxSuggestions = 0) }
        assertFailsWith<IllegalArgumentException> { Suggestion(emptyList()) }
    }
}
