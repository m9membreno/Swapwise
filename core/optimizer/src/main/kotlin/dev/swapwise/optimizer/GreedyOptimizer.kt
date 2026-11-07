package dev.swapwise.optimizer

/**
 * Baseline: one pass over all possible swaps, biggest carbon saving first, keeping a swap whenever
 * the basket still fits the budget. At most one swap per line.
 *
 * It is fast (O(S log S) for S possible swaps) and often decent, but it never trades a little money on
 * one line for a bigger saving on another, because it commits to choices in a fixed order. That gap is
 * exactly what NSGA-II is measured against.
 */
class GreedyOptimizer : BasketOptimizer {
    override fun optimize(problem: SwapProblem): List<ScoredBasket> =
        listOf(Evaluator.score(problem, greedyChoices(problem)))

    internal fun greedyChoices(problem: SwapProblem): IntArray {
        val choices = problem.originalChoices()
        val moves = ArrayList<Move>()
        problem.lines.forEachIndexed { lineIndex, line ->
            line.options.forEachIndexed { option, candidate ->
                if (option == 0) return@forEachIndexed
                val saved = (line.original.kgCo2ePerUnit - candidate.product.kgCo2ePerUnit) * line.quantity
                if (saved > 0.0) {
                    moves += Move(lineIndex, option, saved, BudgetRepair.lineCostIncrease(problem, lineIndex, option))
                }
            }
        }
        // Ties are broken by cost, then by position, so the result never depends on hash order.
        moves.sortWith(compareByDescending<Move> { it.kgSaved }.thenBy { it.costDelta }.thenBy { it.line }.thenBy { it.option })

        var cost = problem.originalCostCents
        for (move in moves) {
            if (choices[move.line] != 0) continue
            if (cost + move.costDelta <= problem.budgetCents) {
                choices[move.line] = move.option
                cost += move.costDelta
            }
        }
        return choices
    }

    private data class Move(val line: Int, val option: Int, val kgSaved: Double, val costDelta: Long)
}
