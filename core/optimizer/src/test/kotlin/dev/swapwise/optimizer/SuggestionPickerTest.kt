package dev.swapwise.optimizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestionPickerTest {
    private val picker = SuggestionPicker()
    private val engine = SwapEngine()

    @Test
    fun `a pricier swap is shown bundled with the swap that pays for it`() {
        val problem = fundedSwapProblem()
        val cards = engine.suggest(problem)
        val card = cards.single()
        assertTrue(card.isBundle)
        assertEquals(setOf("A1", "B1"), card.swaps.map { it.to.id }.toSet())
        assertEquals(0, card.costDeltaCents)
        assertEquals(9.0, card.kgCo2eSaved, 1e-9)
    }

    @Test
    fun `a pricier swap can be shown alone when the budget has slack`() {
        val base = fundedSwapProblem()
        val problem = SwapProblem(base.lines, budgetCents = base.originalCostCents + 100)
        val front = listOf(Evaluator.score(problem, intArrayOf(1, 0))) // only A1 (+100)
        val card = picker.pick(problem, front).single()
        assertEquals(false, card.isBundle)
        assertEquals(100, card.costDeltaCents)
    }

    @Test
    fun `a pricier swap is dropped when only a higher-carbon swap could pay for it`() {
        val problem = SwapProblem(
            listOf(
                BasketLine(product("A", 500, 10.0), 1, listOf(alt(product("A1", 550, 2.0)))), // +50, saves 8 kg
                BasketLine(product("B", 500, 5.0), 1, listOf(alt(product("Bx", 400, 6.0)))), // -100, adds 1 kg
            ),
        )
        // The basket fits the budget (950 <= 1000), but its only money saver raises carbon, so it is never
        // shown, and A1 on its own would put the shopper over budget.
        val front = listOf(Evaluator.score(problem, intArrayOf(1, 1)))
        assertEquals(emptyList(), picker.pick(problem, front))
    }

    @Test
    fun `any combination of accepted cards stays within budget`() {
        repeat(60) { seed ->
            val problem = randomProblem(seed.toLong(), lines = 15, slackCents = (seed % 4) * 75L)
            val cards = engine.suggest(problem)
            for (mask in 0 until (1 shl cards.size)) {
                val accepted = cards.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                val cost = problem.originalCostCents + accepted.sumOf { it.costDeltaCents }
                assertTrue(cost <= problem.budgetCents, "seed $seed, subset $mask costs $cost > ${problem.budgetCents}")
            }
        }
    }

    @Test
    fun `cards never raise carbon, never repeat a line, and there are at most three`() {
        repeat(60) { seed ->
            val problem = randomProblem(seed.toLong(), lines = 15, maxAlternatives = 4)
            val cards = engine.suggest(problem)
            assertTrue(cards.size <= 3)
            val swaps = cards.flatMap { it.swaps }
            assertTrue(swaps.all { it.kgCo2eSaved > 0.0 }, "seed $seed suggests a higher-carbon swap")
            assertEquals(swaps.size, swaps.map { it.lineIndex }.toSet().size, "seed $seed reuses a line")
        }
    }

    @Test
    fun `cards are ranked by carbon saved per unit of change`() {
        repeat(50) { seed ->
            val cards = engine.suggest(randomProblem(seed.toLong(), lines = 15))
            assertEquals(cards.sortedByDescending { it.score }.map { it.score }, cards.map { it.score })
        }
    }

    @Test
    fun `no cards when nothing has a greener alternative`() {
        val milk = product("milk", 300, 3.0)
        val problem = SwapProblem(listOf(BasketLine(milk, 1, listOf(alt(product("fancy-milk", 250, 3.5))))))
        assertEquals(emptyList(), engine.suggest(problem))
    }

    @Test
    fun `prefers a familiar swap over a slightly bigger unfamiliar one`() {
        val beef = product("beef", 800, 27.0)
        val problem = SwapProblem(
            listOf(
                BasketLine(
                    beef,
                    1,
                    listOf(
                        alt(product("lentils", 300, 1.0), Familiarity.DIFFERENT_CATEGORY), // saves 26, score 26 / 3
                        alt(product("chicken", 700, 7.0), Familiarity.SAME_CATEGORY), // saves 20, score 20 / 2
                    ),
                ),
            ),
        )
        assertEquals("chicken", engine.suggest(problem).single().swaps.single().to.id)
    }
}
