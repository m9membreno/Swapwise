package dev.swapwise.optimizer

/**
 * The oracle: tries every possible basket and returns the exact Pareto front.
 *
 * Exponential (the product of every line's option count), so it is only for tests and offline
 * evaluation on small baskets, never for the app. It answers "how close to the true best does
 * NSGA-II get?", which is the number reported as "% of best possible".
 */
class ExhaustiveOptimizer(
    private val maxSearchSpace: Long = DEFAULT_MAX_SEARCH_SPACE,
) : BasketOptimizer {
    override fun optimize(problem: SwapProblem): List<ScoredBasket> {
        require(problem.searchSpaceSize <= maxSearchSpace) {
            "Search space ${problem.searchSpaceSize} exceeds the oracle limit of $maxSearchSpace baskets"
        }
        val archive = ArrayList<ScoredBasket>()
        val choices = problem.originalChoices()
        val counts = problem.optionCounts
        while (true) {
            if (Evaluator.costOf(problem, choices) <= problem.budgetCents) {
                offer(archive, Evaluator.score(problem, choices))
            }
            if (!advance(choices, counts)) break
        }
        return archive.sortedWith(Pareto.BY_CARBON_THEN_COST)
    }

    /** The lowest carbon any within-budget basket can reach. */
    fun bestKgCo2e(problem: SwapProblem): Double = optimize(problem).minOf { it.objectives.kgCo2e }

    /** Adds [candidate] to [archive] if nothing dominates it, removing anything it dominates. */
    private fun offer(archive: MutableList<ScoredBasket>, candidate: ScoredBasket) {
        if (archive.any { Pareto.dominates(it.objectives, candidate.objectives) || it.objectives == candidate.objectives }) return
        archive.removeAll { Pareto.dominates(candidate.objectives, it.objectives) }
        archive.add(candidate)
    }

    /** Odometer-style increment: returns false after the last combination. */
    private fun advance(choices: IntArray, counts: IntArray): Boolean {
        for (i in choices.indices) {
            choices[i]++
            if (choices[i] < counts[i]) return true
            choices[i] = 0
        }
        return false
    }

    companion object {
        const val DEFAULT_MAX_SEARCH_SPACE: Long = 2_000_000
    }
}
