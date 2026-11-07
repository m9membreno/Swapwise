package dev.swapwise.optimizer

import kotlin.random.Random

/**
 * Tuning knobs for [Nsga2Optimizer]. The defaults evaluate about 20,000 baskets, which is enough
 * for receipts of 30+ lines and still fast on a phone (measure it; see the README's metrics table).
 *
 * @property mutationRate chance of changing each line in a child; null means 1 / (lines with alternatives).
 * @property seed fixed by default so the same receipt always gets the same suggestions, and tests are repeatable.
 */
data class Nsga2Config(
    val populationSize: Int = 100,
    val generations: Int = 200,
    val crossoverRate: Double = 0.9,
    val mutationRate: Double? = null,
    val seed: Long = 42L,
) {
    init {
        // A front has at most 2 boundary points per objective (6 here). Keeping room for all of them is
        // what guarantees the lowest-carbon basket is never dropped between generations.
        require(populationSize >= 6) { "Population must be at least 6" }
        require(generations >= 0) { "Generations must not be negative" }
        require(crossoverRate in 0.0..1.0) { "Crossover rate must be in [0, 1]" }
        require(mutationRate == null || mutationRate in 0.0..1.0) { "Mutation rate must be in [0, 1]" }
    }
}

/**
 * NSGA-II (Deb et al., 2002) over three objectives: cost, kg CO2e and disruption, all minimized,
 * with the budget as a hard constraint enforced by [BudgetRepair].
 *
 * A basket is encoded as one gene per receipt line: the index of the chosen option.
 * Each generation:
 * 1. Pick parents by binary tournament (lower front rank wins; ties go to the less crowded one).
 * 2. Uniform crossover, then per-line mutation.
 * 3. Repair any child that is over budget.
 * 4. Merge parents and children, sort into Pareto fronts, keep the best [Nsga2Config.populationSize]
 *    (whole fronts first, then the least crowded points of the front that does not fit).
 *
 * The starting population includes the original basket and the greedy answer. Because NSGA-II is
 * elitist and always keeps the extremes of the best front, the lowest-carbon basket it returns can
 * never be worse than the greedy baseline.
 *
 * Each call keeps its state in a private [Run], so one optimizer instance can be shared across threads.
 */
class Nsga2Optimizer(private val config: Nsga2Config = Nsga2Config()) : BasketOptimizer {

    override fun optimize(problem: SwapProblem): List<ScoredBasket> {
        val original = problem.originalChoices()
        val mutableLines = problem.optionCounts.indices.filter { problem.optionCounts[it] > 1 }
        if (mutableLines.isEmpty()) return listOf(Evaluator.score(problem, original))

        val run = Run(problem, Random(config.seed), mutableLines)
        var population = run.initialPopulation()
        run.rankAndTrim(population, config.populationSize) // assigns rank and crowding for the first tournaments
        repeat(config.generations) {
            val offspring = run.offspring(population, config.populationSize)
            population = run.rankAndTrim(population + offspring, config.populationSize)
        }
        return Pareto.front(population.map { ScoredBasket(it.genes.toList(), it.objectives) })
    }

    private class Individual(val genes: IntArray, val objectives: Objectives) {
        var rank: Int = 0
        var crowding: Double = 0.0
    }

    /** All per-call state lives here, so the optimizer itself stays stateless. */
    private inner class Run(
        private val problem: SwapProblem,
        private val random: Random,
        private val mutableLines: List<Int>,
    ) {
        private val mutationRate = config.mutationRate ?: (1.0 / mutableLines.size)

        fun initialPopulation(): List<Individual> {
            val seeds = listOf(problem.originalChoices(), GreedyOptimizer().greedyChoices(problem))
            val population = seeds.map { individual(it) }.toMutableList()
            while (population.size < config.populationSize) {
                val genes = IntArray(problem.lines.size) { random.nextInt(problem.optionCounts[it]) }
                population += individual(BudgetRepair.repair(problem, genes))
            }
            return population
        }

        fun offspring(parents: List<Individual>, count: Int): List<Individual> = List(count) {
            val a = tournament(parents)
            val b = tournament(parents)
            val genes = if (random.nextDouble() < config.crossoverRate) crossover(a.genes, b.genes) else a.genes.copyOf()
            mutate(genes)
            individual(BudgetRepair.repair(problem, genes))
        }

        /** Sorts [candidates] into fronts, assigns rank and crowding, and keeps the best [size]. */
        fun rankAndTrim(candidates: List<Individual>, size: Int): List<Individual> {
            val objectives = candidates.map { it.objectives }
            val next = ArrayList<Individual>(size)
            for ((rank, front) in Pareto.nonDominatedSort(objectives).withIndex()) {
                val crowding = Pareto.crowdingDistance(front, objectives)
                front.forEach { i ->
                    candidates[i].rank = rank
                    candidates[i].crowding = crowding.getValue(i)
                }
                if (next.size + front.size <= size) {
                    front.mapTo(next) { candidates[it] }
                } else {
                    // Tie-break on index keeps the order stable, so a fixed seed always gives the same result.
                    front.sortedWith(compareByDescending<Int> { candidates[it].crowding }.thenBy { it })
                        .take(size - next.size)
                        .mapTo(next) { candidates[it] }
                }
                if (next.size == size) break
            }
            return next
        }

        private fun tournament(population: List<Individual>): Individual {
            val a = population[random.nextInt(population.size)]
            val b = population[random.nextInt(population.size)]
            return when {
                a.rank != b.rank -> if (a.rank < b.rank) a else b
                else -> if (a.crowding >= b.crowding) a else b
            }
        }

        private fun crossover(a: IntArray, b: IntArray): IntArray =
            IntArray(a.size) { if (random.nextBoolean()) a[it] else b[it] }

        private fun mutate(genes: IntArray) {
            for (line in mutableLines) {
                if (random.nextDouble() < mutationRate) {
                    // Pick a different option uniformly: draw from n-1 values and skip the current one.
                    val n = problem.optionCounts[line]
                    val draw = random.nextInt(n - 1)
                    genes[line] = if (draw >= genes[line]) draw + 1 else draw
                }
            }
        }

        private fun individual(genes: IntArray) = Individual(genes, Evaluator.evaluate(problem, genes))
    }
}
