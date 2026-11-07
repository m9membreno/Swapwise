package dev.swapwise.optimizer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class BudgetRepairTest {
    @Test
    fun `every repaired basket fits the budget`() {
        repeat(200) { seed ->
            val problem = randomProblem(seed.toLong(), lines = 12)
            val random = Random(seed)
            val choices = IntArray(problem.lines.size) { random.nextInt(problem.optionCounts[it]) }
            BudgetRepair.repair(problem, choices)
            assertTrue(Evaluator.costOf(problem, choices) <= problem.budgetCents, "seed $seed still over budget")
        }
    }

    @Test
    fun `a basket that already fits is left alone`() {
        val problem = fundedSwapProblem()
        val choices = intArrayOf(1, 1) // A1 + B1 = exactly the budget
        BudgetRepair.repair(problem, choices)
        assertContentEquals(intArrayOf(1, 1), choices)
    }

    @Test
    fun `undoes the swap that adds the most cost first`() {
        val problem = fundedSwapProblem()
        val choices = intArrayOf(1, 2) // A1 (+100) and B2 (+50) = 1150, over the 1000 budget
        BudgetRepair.repair(problem, choices)
        // Undoing A1 alone brings it to 1050, still over, so B2 is undone too.
        assertContentEquals(intArrayOf(0, 0), choices)
    }

    @Test
    fun `stops as soon as the basket fits`() {
        val problem = SwapProblem(
            listOf(
                BasketLine(product("A", 500, 10.0), 1, listOf(alt(product("A1", 700, 1.0)))), // +200
                BasketLine(product("B", 500, 10.0), 1, listOf(alt(product("B1", 550, 2.0)))), // +50
            ),
            budgetCents = 1_100,
        )
        val choices = intArrayOf(1, 1) // 1250
        BudgetRepair.repair(problem, choices)
        assertContentEquals(intArrayOf(0, 1), choices) // undo A1 -> 1050, fits, keep B1
    }
}
