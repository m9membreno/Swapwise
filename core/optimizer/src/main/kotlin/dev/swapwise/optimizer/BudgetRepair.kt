package dev.swapwise.optimizer

/**
 * Turns an over-budget basket back into a valid one instead of just penalizing it.
 *
 * Why repair instead of a penalty: with a penalty, the search wastes most of its effort on baskets the
 * shopper could never be shown, and the final answer can still be infeasible. Repair keeps every
 * member of the population valid. (SustainGym's lesson for RL agents: project infeasible actions back
 * into the feasible set.)
 *
 * Rule: while the basket is over budget, undo the swap that adds the most cost compared to the
 * original line. Termination and success are guaranteed because the budget is never below the
 * original cost (enforced by [SwapProblem]): if the basket is over budget, at least one line must cost
 * more than its original, so there is always a swap to undo, and undoing every swap gives the original
 * basket, which fits.
 */
internal object BudgetRepair {
    /** Repairs [choices] in place and returns it. */
    fun repair(problem: SwapProblem, choices: IntArray): IntArray {
        var cost = Evaluator.costOf(problem, choices)
        while (cost > problem.budgetCents) {
            var worstLine = -1
            var worstIncrease = 0L
            for (i in choices.indices) {
                if (choices[i] == 0) continue
                val increase = lineCostIncrease(problem, i, choices[i])
                if (increase > worstIncrease) {
                    worstIncrease = increase
                    worstLine = i
                }
            }
            // Cannot happen while budget >= original cost; fail loudly if that invariant is ever broken.
            check(worstLine >= 0) { "Over budget but no swap adds cost; budget invariant violated" }
            choices[worstLine] = 0
            cost -= worstIncrease
        }
        return choices
    }

    /** How many cents option [option] adds to line [line] compared to keeping the original. */
    fun lineCostIncrease(problem: SwapProblem, line: Int, option: Int): Long {
        val l = problem.lines[line]
        return (l.options[option].product.unitPriceCents - l.original.unitPriceCents) * l.quantity
    }
}
