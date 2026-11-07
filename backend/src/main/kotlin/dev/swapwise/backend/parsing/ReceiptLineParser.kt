package dev.swapwise.backend.parsing

import dev.swapwise.backend.catalog.FoodCategory
import dev.swapwise.backend.catalog.PurchaseUnit

/** What one receipt line means. [isGrocery] is false for lines like bag fees or bottle deposits. */
data class ParsedLine(
    val isGrocery: Boolean,
    val name: String,
    val category: FoodCategory,
    val quantity: Double,
    val unit: PurchaseUnit,
)

/**
 * Turns cleaned receipt lines into structured items. An interface so tests use a fake and the LLM provider
 * can change without touching the service.
 *
 * Contract: returns exactly one [ParsedLine] per input line, in the same order, or throws.
 */
fun interface ReceiptLineParser {
    fun parse(lines: List<String>): List<ParsedLine>
}
