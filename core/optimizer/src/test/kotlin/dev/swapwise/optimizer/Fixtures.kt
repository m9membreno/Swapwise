package dev.swapwise.optimizer

import kotlin.random.Random

internal fun product(
    id: String,
    priceCents: Long,
    kg: Double,
    category: String = "test",
    brand: String? = null,
) = Product(id = id, name = id, category = category, brand = brand, unitPriceCents = priceCents, kgCo2ePerUnit = kg)

internal fun alt(product: Product, familiarity: Familiarity = Familiarity.SAME_CATEGORY) = Alternative(product, familiarity)

/**
 * A two-line receipt where greedy fails: the biggest carbon saver on line A costs $1 more, and the only
 * way to afford it is a cheaper swap on line B. Greedy tries A first, cannot afford it, and moves on.
 *
 * - A: $5.00, 10 kg -> A1: $6.00, 2 kg (saves 8 kg, +$1.00)
 * - B: $5.00, 10 kg -> B1: $4.00, 9 kg (saves 1 kg, -$1.00) or B2: $5.50, 3 kg (saves 7 kg, +$0.50)
 *
 * Budget $10.00. Best possible: A1 + B1 = $10.00 and 11 kg (saves 9 kg). Greedy: only B1 (saves 1 kg).
 */
internal fun fundedSwapProblem(): SwapProblem {
    val a = product("A", 500, 10.0)
    val b = product("B", 500, 10.0)
    return SwapProblem(
        listOf(
            BasketLine(a, 1, listOf(alt(product("A1", 600, 2.0)))),
            BasketLine(b, 1, listOf(alt(product("B1", 400, 9.0)), alt(product("B2", 550, 3.0)))),
        ),
    )
}

/** Random but realistic-looking receipts, reproducible from [seed]. */
internal fun randomProblem(
    seed: Long,
    lines: Int,
    maxAlternatives: Int = 3,
    slackCents: Long = 0,
): SwapProblem {
    val random = Random(seed)
    val familiarities = Familiarity.entries.filter { it != Familiarity.ORIGINAL }
    val basket = List(lines) { i ->
        val price = random.nextLong(100, 1_500)
        val kg = random.nextDouble(0.1, 20.0)
        val original = product("L$i", price, kg, category = "c$i")
        val alternatives = List(random.nextInt(0, maxAlternatives + 1)) { j ->
            val altPrice = (price * random.nextDouble(0.6, 1.4)).toLong().coerceAtLeast(1)
            val altKg = kg * random.nextDouble(0.1, 1.2)
            alt(product("L$i-A$j", altPrice, altKg, category = "c$i"), familiarities[random.nextInt(familiarities.size)])
        }
        BasketLine(original, random.nextInt(1, 4), alternatives)
    }
    val cost = basket.sumOf { it.original.unitPriceCents * it.quantity }
    return SwapProblem(basket, budgetCents = cost + slackCents)
}

internal fun ScoredBasket.fitsBudget(problem: SwapProblem) = objectives.costCents <= problem.budgetCents
