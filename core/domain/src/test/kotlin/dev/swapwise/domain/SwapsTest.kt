package dev.swapwise.domain

import dev.swapwise.optimizer.SwapEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwapsTest {
    // Illustrative factors for tests only.
    private val factors = listOf(
        CategoryFactor(FoodCategory.BEEF, 30.0, 20.0, 40.0, null),
        CategoryFactor(FoodCategory.POULTRY, 6.0, 4.0, 8.0, null),
        CategoryFactor(FoodCategory.PORK, 7.0, 5.0, 9.0, null),
        CategoryFactor(FoodCategory.LEGUMES, 1.0, 0.5, 1.5, null),
        CategoryFactor(FoodCategory.EGGS, 4.5, 3.0, 6.0, 0.06),
        CategoryFactor(FoodCategory.MILK, 1.3, 1.0, 1.6, null),
        CategoryFactor(FoodCategory.PLANT_MILK, 0.4, 0.3, 0.6, null),
    ).associateBy { it.category }

    private fun item(i: Int, category: FoodCategory, qty: Double, unit: PurchaseUnit, cents: Long?) =
        ReceiptItem(i, "item $i", category, qty, unit, cents)

    @Test
    fun `price book prefers what the shopper paid over the seed`() {
        val history = listOf(
            item(0, FoodCategory.POULTRY, 1.0, PurchaseUnit.KG, 800),
            item(1, FoodCategory.POULTRY, 2.0, PurchaseUnit.KG, 2_000), // 1000 / kg
            item(2, FoodCategory.POULTRY, 1.0, PurchaseUnit.KG, 1_200),
            item(3, FoodCategory.EGGS, 12.0, PurchaseUnit.EACH, null), // no price: ignored
        )
        val book = PriceBook.fromHistory(history, factors)
        assertEquals(1_000, book.centsPerKg(FoodCategory.POULTRY))
        assertTrue(book.isObserved(FoodCategory.POULTRY))
        assertEquals(PriceBook.SEED_CENTS_PER_KG[FoodCategory.LEGUMES], book.centsPerKg(FoodCategory.LEGUMES))
    }

    @Test
    fun `builds a problem from items it can estimate and reports the rest`() {
        val items = listOf(
            item(0, FoodCategory.BEEF, 1.0, PurchaseUnit.LB, 600),
            item(2, FoodCategory.EGGS, 12.0, PurchaseUnit.EACH, 450),
            item(3, FoodCategory.CHOCOLATE, 1.0, PurchaseUnit.EACH, 300), // no factor in this test set
            item(5, FoodCategory.MILK, 1.0, PurchaseUnit.L, null), // no price found on the line
        )
        val basket = BasketBuilder.build(items, factors, PriceBook(emptyMap()))

        assertEquals(listOf(0, 2), basket.items.map { it.lineIndex })
        assertEquals(listOf(3, 5), basket.notEstimated.map { it.lineIndex })
        val beef = basket.problem.lines[0]
        assertEquals(600, beef.original.unitPriceCents, "the original keeps the price paid")
        assertEquals(30.0 * 0.45359237, beef.original.kgCo2ePerUnit, 1e-9)
        assertEquals(listOf("POULTRY", "PORK", "LEGUMES"), beef.alternatives.map { it.product.category }) // PLANT_PROTEIN has no factor
        val chicken = beef.alternatives.first().product
        assertEquals(Math.round(900 * 0.45359237), chicken.unitPriceCents, "alternatives priced per kg at the same weight")
        assertEquals(problemCost(basket), 1_050)
    }

    private fun problemCost(basket: PreparedBasket) = basket.problem.originalCostCents

    @Test
    fun `end to end, beef and milk produce cards that point back at receipt lines`() {
        val items = listOf(
            item(4, FoodCategory.BEEF, 1.0, PurchaseUnit.KG, 1_300),
            item(7, FoodCategory.MILK, 2.0, PurchaseUnit.L, 300),
        )
        val prices = PriceBook(mapOf(FoodCategory.POULTRY to listOf(900L), FoodCategory.PLANT_MILK to listOf(150L)))
        val basket = BasketBuilder.build(items, factors, prices)
        val cards = SwapCards.from(basket, SwapEngine().suggest(basket.problem))

        assertTrue(cards.isNotEmpty())
        cards.forEach { card ->
            assertTrue(card.costDeltaCents <= 0, "never costs more")
            assertTrue(card.kgCo2eSaved > 0)
            card.swaps.forEach { swap -> assertTrue(swap.receiptLineIndex in setOf(4, 7)) }
        }
        val milk = cards.flatMap { it.swaps }.single { it.receiptLineIndex == 7 }
        assertEquals(FoodCategory.PLANT_MILK, milk.to)
        assertEquals(2.0, milk.kg, 1e-9)
    }

    @Test
    fun `an empty or unestimable receipt gives no cards`() {
        val basket = BasketBuilder.build(listOf(item(0, FoodCategory.CHOCOLATE, 1.0, PurchaseUnit.EACH, 300)), factors, PriceBook(emptyMap()))
        assertEquals(0, basket.problem.lines.size)
        assertEquals(emptyList(), SwapCards.from(basket, SwapEngine().suggest(basket.problem)))
    }
}
