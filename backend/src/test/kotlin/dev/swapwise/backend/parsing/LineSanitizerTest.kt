package dev.swapwise.backend.parsing

import kotlin.test.Test
import kotlin.test.assertEquals

class LineSanitizerTest {
    @Test
    fun `keeps product lines, including store-brand names`() {
        listOf("ORG BNNA 2LB 1.99", "STORE BRAND MILK 1GAL", "MEMBER'S MARK EGGS 18CT", "CASHEWS 12OZ").forEach {
            assertEquals(it, LineSanitizer.sanitize(it), "dropped a product line: $it")
        }
    }

    @Test
    fun `drops payment, total and visit lines`() {
        listOf(
            "SUBTOTAL 45.12",
            "TOTAL 48.90",
            "TAX 3.78",
            "VISA ************4242",
            "CHANGE DUE 1.10",
            "THANK YOU FOR SHOPPING",
            "11/06/2026 14:32",
            "WWW.GROCER.COM",
            "TEL 555-123-4567",
            "",
            "   ",
            "12.99",
        ).forEach { assertEquals(null, LineSanitizer.sanitize(it), "kept a non-product line: '$it'") }
    }

    @Test
    fun `masks long digit runs such as loyalty or barcode numbers`() {
        assertEquals("GRND BEEF # 7.49", LineSanitizer.sanitize("GRND BEEF 0041220 7.49"))
        assertEquals("OAT MILK # 3.99", LineSanitizer.sanitize("OAT MILK 4011-2000-12 3.99"))
    }

    @Test
    fun `collapses whitespace and normalizes case for the cache`() {
        val sanitized = LineSanitizer.sanitize("  org   bnna\t2lb ")!!
        assertEquals("org bnna 2lb", sanitized)
        assertEquals("ORG BNNA 2LB", LineSanitizer.normalize(sanitized))
    }
}
