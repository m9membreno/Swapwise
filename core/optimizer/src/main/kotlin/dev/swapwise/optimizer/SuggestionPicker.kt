package dev.swapwise.optimizer

/** One line changed from [from] to [to]. Deltas are for the whole line (price or carbon times quantity). */
data class Swap(
    val lineIndex: Int,
    val from: Product,
    val to: Product,
    val quantity: Int,
    val familiarity: Familiarity,
) {
    val costDeltaCents: Long get() = (to.unitPriceCents - from.unitPriceCents) * quantity
    val kgCo2eSaved: Double get() = (from.kgCo2ePerUnit - to.kgCo2ePerUnit) * quantity
}

/**
 * One card in the app: a single swap, or a small bundle where cheaper swaps pay for a pricier one.
 * The shopper accepts or dismisses a card as a whole.
 */
data class Suggestion(val swaps: List<Swap>) {
    init {
        require(swaps.isNotEmpty()) { "A suggestion needs at least one swap" }
    }

    val costDeltaCents: Long get() = swaps.sumOf { it.costDeltaCents }
    val kgCo2eSaved: Double get() = swaps.sumOf { it.kgCo2eSaved }
    val disruption: Double get() = swaps.sumOf { it.familiarity.disruption }
    val isBundle: Boolean get() = swaps.size > 1

    /** Carbon saved per unit of change asked of the shopper: the ranking score. */
    val score: Double get() = kgCo2eSaved / (1.0 + disruption)
}

/**
 * Turns the optimizer's Pareto front into at most [maxSuggestions] cards. Candidates are the best swap per
 * line among all baskets on the front (see [bestSwapPerLine]).
 *
 * The key rule (see ADR-002): the shopper can accept *any* combination of the cards, so every
 * combination must stay within budget, not just the full set. A card is therefore either:
 * - a swap that does not raise the cost, or
 * - a bundle where cheaper swaps fully pay for a pricier one, or
 * - a pricier swap paid for by the budget's slack (budget minus original cost). The total of these is
 *   capped at the slack, so even accepting all of them fits.
 *
 * Swaps that raise carbon are never shown, even if the optimizer used them to save money.
 */
class SuggestionPicker(
    private val maxSuggestions: Int = 3,
    private val maxSwapsPerBundle: Int = 3,
) {
    init {
        require(maxSuggestions > 0) { "maxSuggestions must be positive" }
        require(maxSwapsPerBundle >= 2) { "A bundle needs room for at least two swaps" }
    }

    fun pick(problem: SwapProblem, front: List<ScoredBasket>): List<Suggestion> {
        val swaps = bestSwapPerLine(problem, front)
        if (swaps.isEmpty()) return emptyList()

        val savers = swaps.filter { it.costDeltaCents <= 0 }
            .sortedBy { it.costDeltaCents } // the biggest money savers first: they fund bundles best
            .toMutableList()
        val spenders = swaps.filter { it.costDeltaCents > 0 }
            .sortedWith(compareByDescending<Swap> { it.kgCo2eSaved }.thenBy { it.lineIndex })

        val cards = ArrayList<Suggestion>()
        var slack = problem.budgetCents - problem.originalCostCents
        for (spender in spenders) {
            if (spender.costDeltaCents <= slack) {
                cards += Suggestion(listOf(spender))
                slack -= spender.costDeltaCents
                continue
            }
            val bundle = fund(spender, savers)
            if (bundle != null) {
                savers.removeAll(bundle.drop(1))
                cards += Suggestion(bundle)
            }
            // Otherwise drop the spender: showing it alone could push the shopper over budget.
        }
        savers.mapTo(cards) { Suggestion(listOf(it)) }

        return cards
            .sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.swaps.first().lineIndex })
            .take(maxSuggestions)
    }

    /**
     * Collects the swaps that appear anywhere on the Pareto front and keeps, for each line, the one with the
     * best card score (carbon saved per unit of change; on a tie, the cheaper one).
     *
     * Why not pick one basket from the front: a basket that is ideal as a set of cards can be dominated as a
     * whole. "Chicken instead of beef plus oat milk" is dominated by "lentils instead of beef" alone (cheaper,
     * lower carbon, same total change), yet chicken and oat milk make two better cards than lentils makes one.
     * Cards are accepted one by one, so they are chosen one by one; the front supplies the candidates.
     */
    private fun bestSwapPerLine(problem: SwapProblem, front: List<ScoredBasket>): List<Swap> {
        val best = HashMap<Int, Swap>()
        for (basket in front) {
            if (basket.objectives.costCents > problem.budgetCents) continue
            for (swap in greenSwaps(problem, basket)) {
                val current = best[swap.lineIndex]
                val better = current == null ||
                    swap.score() > current.score() ||
                    (swap.score() == current.score() && swap.costDeltaCents < current.costDeltaCents)
                if (better) best[swap.lineIndex] = swap
            }
        }
        return best.values.sortedBy { it.lineIndex }
    }

    private fun Swap.score(): Double = kgCo2eSaved / (1.0 + familiarity.disruption)

    /** A basket's swaps that lower carbon; swaps that raise it are never shown. */
    private fun greenSwaps(problem: SwapProblem, basket: ScoredBasket): List<Swap> =
        basket.choices.withIndex()
            .filter { (_, option) -> option != 0 }
            .map { (lineIndex, option) ->
                val line = problem.lines[lineIndex]
                val chosen = line.options[option]
                Swap(lineIndex, line.original, chosen.product, line.quantity, chosen.familiarity)
            }
            .filter { it.kgCo2eSaved > 0.0 }

    /** Greedily adds the biggest money savers until the bundle no longer raises the cost. */
    private fun fund(spender: Swap, savers: List<Swap>): List<Swap>? {
        val bundle = mutableListOf(spender)
        var delta = spender.costDeltaCents
        for (saver in savers) {
            if (delta <= 0 || bundle.size == maxSwapsPerBundle) break
            if (saver.costDeltaCents == 0L) continue // cannot help pay
            bundle += saver
            delta += saver.costDeltaCents
        }
        return if (delta <= 0) bundle else null
    }
}
