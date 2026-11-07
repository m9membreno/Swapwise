package dev.swapwise.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImpactTest {
    private val day = 24L * 60 * 60 * 1000
    private val acceptedAt = 100 * day
    private val chicken = AcceptedCard("c1", acceptedAt, setOf(FoodCategory.POULTRY))

    private fun receipt(dayOffset: Long, vararg categories: FoodCategory) =
        ReceiptCategories(acceptedAt + dayOffset * day, categories.toSet())

    @Test
    fun `verified new when the swap shows up later and was not bought before`() {
        val receipts = listOf(receipt(-3, FoodCategory.BEEF), receipt(7, FoodCategory.POULTRY))
        assertEquals(FollowThrough.VERIFIED_NEW, FollowThroughChecker.evaluate(chicken, receipts, acceptedAt + 8 * day))
    }

    @Test
    fun `already bought when the shopper bought it in the month before too`() {
        val receipts = listOf(receipt(-20, FoodCategory.POULTRY), receipt(7, FoodCategory.POULTRY))
        assertEquals(FollowThrough.VERIFIED_ALREADY_BOUGHT, FollowThroughChecker.evaluate(chicken, receipts, acceptedAt + 8 * day))
    }

    @Test
    fun `purchases older than the window do not count as already bought`() {
        val receipts = listOf(receipt(-45, FoodCategory.POULTRY), receipt(7, FoodCategory.POULTRY))
        assertEquals(FollowThrough.VERIFIED_NEW, FollowThroughChecker.evaluate(chicken, receipts, acceptedAt + 8 * day))
    }

    @Test
    fun `pending inside the window, not seen after it`() {
        val receipts = listOf(receipt(5, FoodCategory.BEEF))
        assertEquals(FollowThrough.PENDING, FollowThroughChecker.evaluate(chicken, receipts, acceptedAt + 10 * day))
        assertEquals(FollowThrough.NOT_SEEN, FollowThroughChecker.evaluate(chicken, receipts, acceptedAt + 31 * day))
    }

    @Test
    fun `a purchase after the window does not verify the card`() {
        val receipts = listOf(receipt(35, FoodCategory.POULTRY))
        assertEquals(FollowThrough.NOT_SEEN, FollowThroughChecker.evaluate(chicken, receipts, acceptedAt + 40 * day))
    }

    @Test
    fun `a bundle needs every swapped-to category`() {
        val bundle = AcceptedCard("b1", acceptedAt, setOf(FoodCategory.POULTRY, FoodCategory.PLANT_MILK))
        val half = listOf(receipt(3, FoodCategory.POULTRY))
        assertEquals(FollowThrough.PENDING, FollowThroughChecker.evaluate(bundle, half, acceptedAt + 4 * day))
        val both = half + receipt(9, FoodCategory.PLANT_MILK)
        assertEquals(FollowThrough.VERIFIED_NEW, FollowThroughChecker.evaluate(bundle, both, acceptedAt + 10 * day))
    }

    @Test
    fun `rejects a card with no targets`() {
        assertFailsWith<IllegalArgumentException> { AcceptedCard("x", 0, emptySet()) }
    }

    @Test
    fun `impact counts only verified-new swaps as avoided carbon`() {
        val stats = ImpactStats.from(
            listOf(
                CardOutcome(CardAnswer.ACCEPTED, 10.0, -100, FollowThrough.VERIFIED_NEW),
                CardOutcome(CardAnswer.ACCEPTED, 4.0, 0, FollowThrough.VERIFIED_ALREADY_BOUGHT),
                CardOutcome(CardAnswer.ACCEPTED, 2.0, -50, FollowThrough.NOT_SEEN),
                CardOutcome(CardAnswer.ACCEPTED, 3.0, 0, null), // not checked yet
                CardOutcome(CardAnswer.DISMISSED, 8.0, 0, null),
                CardOutcome(CardAnswer.SHOWN, 1.0, 0, null),
            ),
        )
        assertEquals(6, stats.shown)
        assertEquals(4, stats.accepted)
        assertEquals(0.8, stats.acceptanceRate!!, 1e-9) // 4 of 5 answered
        assertEquals(1, stats.pending)
        assertEquals(1.0 / 3.0, stats.followThroughRate!!, 1e-9) // 1 verified-new of 3 with a final result
        assertEquals(19.0, stats.kgCo2eAccepted, 1e-9)
        assertEquals(10.0, stats.kgCo2eVerified, 1e-9)
        assertEquals(100, stats.centsSavedVerified)
    }

    @Test
    fun `rates are unknown until there is data`() {
        val empty = ImpactStats.from(emptyList())
        assertEquals(null, empty.acceptanceRate)
        assertEquals(null, empty.followThroughRate)
    }
}
