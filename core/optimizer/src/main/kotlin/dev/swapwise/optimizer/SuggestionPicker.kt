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
 * Turns the optimizer's Pareto front into at most [maxSuggestions] cards.
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
        val basket = chooseBasket(problem, front) ?: return emptyList()
        val swaps = basket.choices.withIndex()
            .filter { (_, option) -> option != 0 }
            .map { (lineIndex, option) ->
                val line = problem.lines[lineIndex]
                val chosen = line.options[option]
                Swap(lineIndex, line.original, chosen.product, line.quantity, chosen.familiarity)
            }
            .filter { it.kgCo2eSaved > 0.0 }

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
     * Picks the basket with the most carbon saved per unit of disruption. Returns null when no basket
     * saves any carbon (for example, when nothing on the receipt has a greener alternative).
     */
    private fun chooseBasket(problem: SwapProblem, front: List<ScoredBasket>): ScoredBasket? =
        front.filter { it.kgCo2eSaved(problem) > 0.0 && it.objectives.costCents <= problem.budgetCents }
            .maxWithOrNull(
                compareBy<ScoredBasket> { it.kgCo2eSaved(problem) / (1.0 + it.objectives.disruption) }
                    .thenByDescending { it.objectives.costCents }, // on a tie, prefer the cheaper basket
            )

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
