package dev.swapwise.optimizer

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Offline evaluation: on baskets small enough to brute-force, how much of the best possible carbon
 * saving does each optimizer reach? This is the "% of best possible" number in the README.
 */
class OptimizerQualityTest {
    private val oracle = ExhaustiveOptimizer()

    @Test
    fun `nsga2 reaches at least 99 percent of the best possible saving on average and beats greedy`() {
        val greedyShares = ArrayList<Double>()
        val nsgaShares = ArrayList<Double>()
        repeat(40) { seed ->
            val problem = randomProblem(seed.toLong() + 1_000, lines = 8, maxAlternatives = 3, slackCents = (seed % 3) * 100L)
            val bestSaving = problem.originalKgCo2e - oracle.bestKgCo2e(problem)
            if (bestSaving <= 1e-9) return@repeat // nothing to save on this receipt
            greedyShares += (problem.originalKgCo2e - GreedyOptimizer().optimize(problem).single().objectives.kgCo2e) / bestSaving
            nsgaShares += (problem.originalKgCo2e - Nsga2Optimizer().optimize(problem).minOf { it.objectives.kgCo2e }) / bestSaving
        }
        val greedyMean = greedyShares.average()
        val nsgaMean = nsgaShares.average()
        println("%% of best possible over ${nsgaShares.size} baskets: greedy %.1f%%, NSGA-II %.1f%% (worst %.1f%%)"
            .format(greedyMean * 100, nsgaMean * 100, nsgaShares.min() * 100))
        assertTrue(nsgaMean >= 0.99, "NSGA-II mean share was $nsgaMean")
        assertTrue(nsgaMean >= greedyMean, "NSGA-II ($nsgaMean) should not trail greedy ($greedyMean)")
    }
}
