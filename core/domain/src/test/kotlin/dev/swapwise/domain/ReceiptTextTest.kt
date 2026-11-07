package dev.swapwise.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiptTextTest {
    @Test
    fun `rebuilds rows when names and prices come back as separate columns`() {
        // Two OCR blocks: the name column first, then the price column, as recognizers often return them.
        val fragments = listOf(
            OcrFragment("GRND BEEF", left = 10, top = 100, bottom = 120),
            OcrFragment("OAT MILK", left = 10, top = 130, bottom = 150),
            OcrFragment("7.49", left = 300, top = 102, bottom = 121), // slightly lower than its name: camera tilt
            OcrFragment("3.99", left = 300, top = 131, bottom = 149),
        )
        assertEquals(listOf("GRND BEEF 7.49", "OAT MILK 3.99"), OcrRowAssembler.assemble(fragments))
    }

    @Test
    fun `keeps tightly spaced rows apart and orders each row left to right`() {
        val fragments = listOf(
            OcrFragment("1.99", left = 300, top = 0, bottom = 10),
            OcrFragment("EGGS", left = 0, top = 0, bottom = 10),
            OcrFragment("BREAD", left = 0, top = 11, bottom = 21),
            OcrFragment("2.50", left = 300, top = 12, bottom = 22),
        )
        assertEquals(listOf("EGGS 1.99", "BREAD 2.50"), OcrRowAssembler.assemble(fragments))
    }

    @Test
    fun `ignores blank and zero-height fragments`() {
        val fragments = listOf(OcrFragment(" ", 0, 0, 10), OcrFragment("MILK", 0, 0, 0), OcrFragment("TEA", 0, 0, 10))
        assertEquals(listOf("TEA"), OcrRowAssembler.assemble(fragments))
    }

    @Test
    fun `reads the line total, the last amount on the line`() {
        assertEquals(798, PriceExtractor.lineTotalCents("GRND BEEF 2 @ 3.99 7.98 F"))
        assertEquals(349, PriceExtractor.lineTotalCents("OAT MILK 3,49"))
        assertEquals(12_00, PriceExtractor.lineTotalCents("SALMON 12.00"))
        assertEquals(null, PriceExtractor.lineTotalCents("BANANAS"))
        assertEquals(null, PriceExtractor.lineTotalCents("COUPON -1.00"), "discount lines are not prices")
        assertEquals(null, PriceExtractor.lineTotalCents("ITEM 12.345"), "three decimals is not money")
    }

    @Test
    fun `sanitizer matches the backend rules`() {
        assertEquals("STORE BRAND MILK 1GAL", LineSanitizer.sanitize("STORE BRAND MILK 1GAL"))
        assertEquals("GRND BEEF # 7.49", LineSanitizer.sanitize("GRND BEEF 0041220 7.49"))
        assertEquals(null, LineSanitizer.sanitize("VISA ************4242"))
        assertEquals(null, LineSanitizer.sanitize("TOTAL 48.90"))
        assertEquals(null, LineSanitizer.sanitize("11/06/2026 14:32"))
    }

    @Test
    fun `converts purchase units to kilograms`() {
        assertEquals(0.90718474, UnitConverter.toKg(2.0, PurchaseUnit.LB, null)!!, 1e-9)
        assertEquals(0.25, UnitConverter.toKg(250.0, PurchaseUnit.G, null)!!, 1e-9)
        assertEquals(0.72, UnitConverter.toKg(12.0, PurchaseUnit.EACH, 0.06)!!, 1e-9)
        assertEquals(null, UnitConverter.toKg(1.0, PurchaseUnit.EACH, null), "unknown item weight")
        assertEquals(null, UnitConverter.toKg(0.0, PurchaseUnit.KG, null))
    }

    @Test
    fun `unknown codes from a newer backend map to null`() {
        assertEquals(FoodCategory.BEEF, FoodCategory.fromCode("BEEF"))
        assertEquals(null, FoodCategory.fromCode("SPACE_FOOD"))
        assertEquals(null, PurchaseUnit.fromCode(null))
    }
}
