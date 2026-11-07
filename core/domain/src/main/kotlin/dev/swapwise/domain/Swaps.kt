package dev.swapwise.domain

import dev.swapwise.optimizer.Alternative
import dev.swapwise.optimizer.BasketLine
import dev.swapwise.optimizer.Familiarity
import dev.swapwise.optimizer.Product
import dev.swapwise.optimizer.Suggestion
import dev.swapwise.optimizer.SwapProblem
import kotlin.math.roundToLong

/**
 * Which categories can stand in for which, and how big a change each swap is. v1 suggests swaps at the
 * category level ("chicken instead of beef") at the same weight; product-level swaps come later.
 */
object SwapGraph {
    private val S = Familiarity.SAME_CATEGORY
    private val D = Familiarity.DIFFERENT_CATEGORY

    val edges: Map<FoodCategory, List<Pair<FoodCategory, Familiarity>>> = mapOf(
        FoodCategory.BEEF to listOf(FoodCategory.POULTRY to S, FoodCategory.PORK to S, FoodCategory.PLANT_PROTEIN to D, FoodCategory.LEGUMES to D),
        FoodCategory.LAMB to listOf(FoodCategory.POULTRY to S, FoodCategory.PORK to S, FoodCategory.LEGUMES to D),
        FoodCategory.PORK to listOf(FoodCategory.POULTRY to S, FoodCategory.PLANT_PROTEIN to D),
        FoodCategory.POULTRY to listOf(FoodCategory.PLANT_PROTEIN to D, FoodCategory.LEGUMES to D),
        FoodCategory.SEAFOOD to listOf(FoodCategory.FISH to S),
        FoodCategory.MILK to listOf(FoodCategory.PLANT_MILK to S),
        FoodCategory.BUTTER to listOf(FoodCategory.OIL to S),
        FoodCategory.RICE to listOf(FoodCategory.PASTA to S, FoodCategory.POTATOES to S),
        FoodCategory.CHEESE to listOf(FoodCategory.PLANT_PROTEIN to D),
        FoodCategory.COFFEE to listOf(FoodCategory.TEA to D),
        FoodCategory.JUICE to listOf(FoodCategory.FRUIT to D),
    )
}

/**
 * Price per kg for each category. Uses the median of what this shopper actually paid (from their receipts)
 * when known, so suggestions reflect their stores; otherwise a seeded estimate.
 */
class PriceBook(
    observedCentsPerKg: Map<FoodCategory, List<Long>>,
    private val seedCentsPerKg: Map<FoodCategory, Long> = SEED_CENTS_PER_KG,
) {
    private val observedMedian: Map<FoodCategory, Long> = observedCentsPerKg
        .filterValues { it.isNotEmpty() }
        .mapValues { (_, prices) -> prices.sorted().let { it[(it.size - 1) / 2] } } // lower median, no averaging cents

    fun centsPerKg(category: FoodCategory): Long? = observedMedian[category] ?: seedCentsPerKg[category]

    fun isObserved(category: FoodCategory): Boolean = category in observedMedian

    companion object {
        /**
         * PLACEHOLDER rough US retail prices in cents per kg, only so a new user sees sensible suggestions before
         * their own receipts fill the price book. Replace with BLS "Average Price Data" before the beta.
         */
        val SEED_CENTS_PER_KG: Map<FoodCategory, Long> = mapOf(
            FoodCategory.BEEF to 1_300, FoodCategory.LAMB to 2_000, FoodCategory.PORK to 900,
            FoodCategory.POULTRY to 900, FoodCategory.FISH to 2_000, FoodCategory.SEAFOOD to 2_200,
            FoodCategory.EGGS to 600, FoodCategory.MILK to 110, FoodCategory.PLANT_MILK to 300,
            FoodCategory.CHEESE to 1_300, FoodCategory.YOGURT to 600, FoodCategory.BUTTER to 1_100,
            FoodCategory.PLANT_PROTEIN to 1_000, FoodCategory.LEGUMES to 400, FoodCategory.NUTS to 2_000,
            FoodCategory.RICE to 300, FoodCategory.PASTA to 350, FoodCategory.BREAD to 600,
            FoodCategory.CEREAL to 900, FoodCategory.POTATOES to 200, FoodCategory.VEGETABLES to 400,
            FoodCategory.FRUIT to 450, FoodCategory.COFFEE to 2_500, FoodCategory.TEA to 3_000,
            FoodCategory.CHOCOLATE to 2_000, FoodCategory.SNACKS to 1_200, FoodCategory.SOFT_DRINKS to 150,
            FoodCategory.JUICE to 250, FoodCategory.OIL to 600, FoodCategory.OTHER_FOOD to 800,
        )

        /** Builds observations from past receipt items: price paid divided by weight bought. */
        fun fromHistory(items: List<ReceiptItem>, factors: Map<FoodCategory, CategoryFactor>): PriceBook {
            val observed = HashMap<FoodCategory, MutableList<Long>>()
            for (item in items) {
                val price = item.priceCents ?: continue
                val kg = UnitConverter.toKg(item.quantity, item.unit, factors[item.category]?.typicalItemKg) ?: continue
                if (kg < 0.01 || price <= 0) continue // ignore weights too small to give a stable price per kg
                observed.getOrPut(item.category) { ArrayList() } += (price / kg).roundToLong()
            }
            return PriceBook(observed)
        }
    }
}

/** The optimizer input plus what is needed to map its answer back to receipt lines. */
data class PreparedBasket(
    val problem: SwapProblem,
    /** problem line index -> receipt item it came from */
    val items: List<ReceiptItem>,
    /** problem line index -> weight in kg used for the swap */
    val kgPerLine: List<Double>,
    /** Items that could not be estimated (no factor, unknown weight or no price); shown without swaps. */
    val notEstimated: List<ReceiptItem>,
)

object BasketBuilder {
    /**
     * Turns parsed receipt items into a [SwapProblem]. An item is optimized only when we know its category's
     * factor, its weight and its price; the original price is what the shopper paid, and alternatives are
     * priced from the [PriceBook] at the same weight.
     */
    fun build(
        items: List<ReceiptItem>,
        factors: Map<FoodCategory, CategoryFactor>,
        prices: PriceBook,
        graph: Map<FoodCategory, List<Pair<FoodCategory, Familiarity>>> = SwapGraph.edges,
    ): PreparedBasket {
        val lines = ArrayList<BasketLine>()
        val used = ArrayList<ReceiptItem>()
        val weights = ArrayList<Double>()
        val skipped = ArrayList<ReceiptItem>()
        for (item in items) {
            val factor = factors[item.category]
            val kg = factor?.let { UnitConverter.toKg(item.quantity, item.unit, it.typicalItemKg) }
            val price = item.priceCents
            if (factor == null || kg == null || price == null || price <= 0) {
                skipped += item
                continue
            }
            val alternatives = graph[item.category].orEmpty().mapNotNull { (category, familiarity) ->
                val altFactor = factors[category] ?: return@mapNotNull null
                val altPrice = prices.centsPerKg(category) ?: return@mapNotNull null
                Alternative(
                    Product(
                        id = "category:${category.name}",
                        name = category.label,
                        category = category.name,
                        brand = null,
                        unitPriceCents = (altPrice * kg).roundToLong().coerceAtLeast(1),
                        kgCo2ePerUnit = altFactor.kgCo2ePerKg * kg,
                    ),
                    familiarity,
                )
            }
            lines += BasketLine(
                original = Product(
                    id = "line:${item.lineIndex}",
                    name = item.name,
                    category = item.category.name,
                    brand = null,
                    unitPriceCents = price,
                    kgCo2ePerUnit = factor.kgCo2ePerKg * kg,
                ),
                quantity = 1, // the whole receipt line is one unit; its weight is already in the price and carbon
                alternatives = alternatives,
            )
            used += item
            weights += kg
        }
        return PreparedBasket(SwapProblem(lines), used, weights, skipped)
    }
}

/** What a swap card shows. Strings are left to the UI so they can be translated. */
data class SwapCard(
    val swaps: List<CardSwap>,
    val kgCo2eSaved: Double,
    val costDeltaCents: Long,
) {
    val isBundle: Boolean get() = swaps.size > 1
}

data class CardSwap(val receiptLineIndex: Int, val fromName: String, val to: FoodCategory, val kg: Double)

object SwapCards {
    fun from(basket: PreparedBasket, suggestions: List<Suggestion>): List<SwapCard> = suggestions.map { suggestion ->
        SwapCard(
            swaps = suggestion.swaps.map { swap ->
                val item = basket.items[swap.lineIndex]
                val to = FoodCategory.fromCode(swap.to.category)
                    ?: error("Alternative has unknown category ${swap.to.category}")
                CardSwap(item.lineIndex, item.name, to, basket.kgPerLine[swap.lineIndex])
            },
            kgCo2eSaved = suggestion.kgCo2eSaved,
            costDeltaCents = suggestion.costDeltaCents,
        )
    }
}
