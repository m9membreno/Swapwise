package dev.swapwise.feature.swaps

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun `formats money in dollars`() {
        assertEquals("$1.20", Format.money(120))
        assertEquals("$0.05", Format.money(5))
    }

    @Test
    fun `describes the price change without a sign`() {
        assertEquals(Format.PriceChange.Cheaper("$1.20"), Format.priceChange(-120))
        assertEquals(Format.PriceChange.MoreExpensive("$0.40"), Format.priceChange(40))
        assertEquals(Format.PriceChange.Same, Format.priceChange(0))
    }

    @Test
    fun `rounds carbon to what an estimate supports`() {
        assertEquals("4.2", Format.kg(4.23))
        assertEquals("24", Format.kg(23.6))
    }
}
