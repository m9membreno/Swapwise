package dev.swapwise.optimizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptimizerTest {
    private val greedy = GreedyOptimizer()
    private val oracle = ExhaustiveOptimizer()
    private val nsga2 = Nsga2Optimizer()

    @Test
    fun `greedy misses the funded swap`() {
        val result = greedy.optimize(fundedSwapProblem()).single()
        assertEquals(listOf(0, 1), result.choices) // only B1
        assertEquals(19.0, result.objectives.kgCo2e, 1e-9)
    }

    @Test
    fun `oracle finds the funded swap`() {
        val problem = fundedSwapProblem()
        assertEquals(11.0, oracle.bestKgCo2e(problem), 1e-9)
        val best = oracle.optimize(problem).first()
        assertEquals(listOf(1, 1), best.choices) // A1 + B1
        assertEquals(1_000, best.objectives.costCents)
    }

    @Test
    fun `oracle refuses search spaces above its limit`() {
        assertFailsWith<IllegalArgumentException> { ExhaustiveOptimizer(maxSearchSpace = 5).optimize(fundedSwapProblem()) }
    }

    @Test
    fun `oracle front is exactly the non-dominated feasible baskets`() {
        val problem = fundedSwapProblem()
        // Feasible baskets (cost <= 1000), as cost / kg / disruption:
        //   [0,0] 1000 / 20 / 0, [0,1] 900 / 19 / 1, [1,1] 1000 / 11 / 2. Over budget: [0,2] 1050, [1,0] 1100, [1,2] 1150.
        // None of the three feasible ones dominates another: each wins on one objective.
        val front = oracle.optimize(problem)
        assertEquals(listOf(listOf(1, 1), listOf(0, 1), listOf(0, 0)), front.map { it.choices })
    }

    @Test
    fun `nsga2 finds the funded swap greedy misses`() {
        val problem = fundedSwapProblem()
        assertEquals(oracle.bestKgCo2e(problem), nsga2.optimize(problem).minOf { it.objectives.kgCo2e }, 1e-9)
    }

    @Test
    fun `nsga2 never returns an over-budget or dominated basket`() {
        repeat(30) { seed ->
            val problem = randomProblem(seed.toLong(), lines = 20, slackCents = (seed % 3) * 100L)
            val front = nsga2.optimize(problem)
            assertTrue(front.isNotEmpty())
            assertTrue(front.all { it.fitsBudget(problem) }, "seed $seed has an over-budget basket")
            front.forEach { a ->
                assertTrue(front.none { b -> Pareto.dominates(b.objectives, a.objectives) }, "seed $seed front has a dominated basket")
            }
        }
    }

    @Test
    fun `nsga2 is never worse than greedy on carbon`() {
        repeat(50) { seed ->
            val problem = randomProblem(seed.toLong(), lines = 25)
            val greedyKg = greedy.optimize(problem).single().objectives.kgCo2e
            val nsgaKg = nsga2.optimize(problem).minOf { it.objectives.kgCo2e }
            assertTrue(nsgaKg <= greedyKg + 1e-9, "seed $seed: nsga2 $nsgaKg > greedy $greedyKg")
        }
    }

    @Test
    fun `nsga2 gives the same answer for the same seed`() {
        val problem = randomProblem(7, lines = 15)
        assertEquals(nsga2.optimize(problem), Nsga2Optimizer().optimize(problem))
    }

    @Test
    fun `handles receipts with nothing to swap`() {
        val problem = SwapProblem(listOf(BasketLine(product("salt", 99, 0.1), 1)))
        listOf(greedy, oracle, nsga2).forEach { optimizer ->
            assertEquals(listOf(listOf(0)), optimizer.optimize(problem).map { it.choices })
        }
    }

    @Test
    fun `handles an empty receipt`() {
        val problem = SwapProblem(emptyList())
        listOf(greedy, oracle, nsga2).forEach { optimizer ->
            assertEquals(listOf(emptyList()), optimizer.optimize(problem).map { it.choices })
        }
    }

    @Test
    fun `optimizers never touch quantities or invent products`() {
        val problem = randomProblem(3, lines = 10)
        val front = nsga2.optimize(problem)
        front.forEach { basket ->
            basket.choices.forEachIndexed { line, option -> assertTrue(option in 0 until problem.optionCounts[line]) }
        }
    }
}
