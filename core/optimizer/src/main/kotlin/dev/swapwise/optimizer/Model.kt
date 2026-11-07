package dev.swapwise.optimizer

/**
 * A product the shopper bought or could buy instead.
 *
 * Money is stored as whole cents ([Long]) so totals never pick up floating-point rounding errors.
 * Carbon is an estimate (a category average), so [Double] is fine there.
 *
 * Price and carbon are per *purchase unit* (one item, one pack, one pound...). An alternative is only
 * offered when it is sold in a comparable unit, so a swap keeps the original quantity.
 */
data class Product(
    val id: String,
    val name: String,
    val category: String,
    val brand: String?,
    val unitPriceCents: Long,
    val kgCo2ePerUnit: Double,
) {
    init {
        require(id.isNotBlank()) { "Product id must not be blank" }
        require(unitPriceCents >= 0) { "Price must not be negative: $unitPriceCents" }
        require(kgCo2ePerUnit.isFinite() && kgCo2ePerUnit >= 0.0) { "Carbon must be a finite, non-negative number: $kgCo2ePerUnit" }
    }
}

/**
 * How big a change a swap asks of the shopper. Smaller, more familiar changes are more likely to be
 * accepted, so the optimizer treats [disruption] as a third objective to minimize.
 */
enum class Familiarity(val disruption: Double) {
    /** Keep what you already buy. */
    ORIGINAL(0.0),

    /** Same brand, different product (for example, the brand's oat milk instead of its dairy milk). */
    SAME_BRAND(0.5),

    /** Same kind of product from another brand. */
    SAME_CATEGORY(1.0),

    /** A different kind of product that fills the same role (for example, lentils instead of beef). */
    DIFFERENT_CATEGORY(2.0),
}

/** One possible replacement for a basket line. */
data class Alternative(val product: Product, val familiarity: Familiarity) {
    init {
        require(familiarity != Familiarity.ORIGINAL) { "An alternative cannot be marked ORIGINAL" }
    }
}

/** One line of a receipt plus the alternatives the shopper could swap it for. */
data class BasketLine(
    val original: Product,
    val quantity: Int,
    val alternatives: List<Alternative> = emptyList(),
) {
    init {
        require(quantity > 0) { "Quantity must be positive: $quantity" }
        require(alternatives.none { it.product.id == original.id }) { "An alternative cannot be the original product" }
    }

    /** Option 0 is always "keep the original"; options 1..n are the alternatives in order. */
    internal val options: List<Option> =
        listOf(Option(original, Familiarity.ORIGINAL)) + alternatives.map { Option(it.product, it.familiarity) }

    internal data class Option(val product: Product, val familiarity: Familiarity)
}

/**
 * The input to every optimizer.
 *
 * [budgetCents] defaults to what the shopper already paid, which encodes the product rule
 * "a suggestion must never make the basket more expensive". A shopper who is happy to spend a little
 * more can raise it, but it can never be below the original cost: the original basket must always be
 * a valid answer, which is what guarantees that [BudgetRepair] can always succeed.
 */
class SwapProblem(
    val lines: List<BasketLine>,
    budgetCents: Long? = null,
) {
    val originalCostCents: Long = lines.sumOf { it.original.unitPriceCents * it.quantity }
    val originalKgCo2e: Double = lines.sumOf { it.original.kgCo2ePerUnit * it.quantity }
    val budgetCents: Long = budgetCents ?: originalCostCents

    init {
        require(this.budgetCents >= originalCostCents) {
            "Budget (${this.budgetCents}) must be at least the original cost ($originalCostCents)"
        }
    }

    /** Number of choices per line, including "keep the original". */
    internal val optionCounts: IntArray = IntArray(lines.size) { lines[it].options.size }

    /** Total number of possible baskets, capped at [Long.MAX_VALUE] to avoid overflow. */
    val searchSpaceSize: Long =
        optionCounts.fold(1L) { acc, n -> if (acc > Long.MAX_VALUE / n) Long.MAX_VALUE else acc * n }

    /** "Keep everything" as a choice vector. */
    internal fun originalChoices(): IntArray = IntArray(lines.size)
}

/** The three objectives for one basket. All three are minimized. */
data class Objectives(
    val costCents: Long,
    val kgCo2e: Double,
    val disruption: Double,
)

/**
 * A candidate basket: [choices] holds, for each line, the index of the chosen option
 * (0 = keep the original, i = the (i-1)th alternative).
 */
data class ScoredBasket(val choices: List<Int>, val objectives: Objectives) {
    fun kgCo2eSaved(problem: SwapProblem): Double = problem.originalKgCo2e - objectives.kgCo2e

    fun costDeltaCents(problem: SwapProblem): Long = objectives.costCents - problem.originalCostCents
}

/** Anything that turns a [SwapProblem] into a set of good baskets. */
fun interface BasketOptimizer {
    /**
     * Returns the non-dominated baskets found, every one of them within budget, sorted by carbon
     * (lowest first). The original basket is always feasible, so the list is never empty.
     */
    fun optimize(problem: SwapProblem): List<ScoredBasket>
}
