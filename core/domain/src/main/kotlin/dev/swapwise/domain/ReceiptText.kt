package dev.swapwise.domain

/** One piece of text found by OCR, with its position on the image in pixels. */
data class OcrFragment(val text: String, val left: Int, val top: Int, val bottom: Int) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = bottom - top
}

/**
 * Rebuilds receipt rows from OCR fragments.
 *
 * Text recognizers group text into blocks by layout, and on receipts the item names and the prices often end
 * up in separate blocks (two columns). Reading block by block would give all names, then all prices. This puts
 * fragments back into rows by vertical position, then orders each row left to right.
 */
object OcrRowAssembler {
    fun assemble(fragments: List<OcrFragment>): List<String> {
        val rows = ArrayList<MutableList<OcrFragment>>()
        for (fragment in fragments.filter { it.text.isNotBlank() && it.height > 0 }.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, fragment)) row += fragment else rows += mutableListOf(fragment)
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    /** Same row when the fragment's vertical center falls inside the row's band, within half a line height. */
    private fun sameRow(row: List<OcrFragment>, fragment: OcrFragment): Boolean {
        val rowCenter = row.sumOf { it.centerY } / row.size
        val tolerance = minOf(row.minOf { it.height }, fragment.height) / 2.0
        return kotlin.math.abs(fragment.centerY - rowCenter) <= tolerance
    }
}

/** Reads the line total from a receipt line such as `GRND BEEF 2 @ 3.99 7.98 F`. */
object PriceExtractor {
    // Two decimals, not glued to other digits, not negative (discount lines are handled separately).
    private val MONEY = Regex("""(?<![\d.,-])(\d{1,4})[.,](\d{2})(?![\d.,])""")

    /** The last money amount on the line, in cents, or null. The last one is the line total on US receipts. */
    fun lineTotalCents(line: String): Long? {
        val match = MONEY.findAll(line).lastOrNull() ?: return null
        return match.groupValues[1].toLong() * 100 + match.groupValues[2].toLong()
    }
}

/**
 * Same rules as the backend's `LineSanitizer` (keep both in sync; their tests share cases). The app runs it
 * before upload so totals, payment details and long numbers never leave the phone.
 */
object LineSanitizer {
    private val NON_PRODUCT = Regex(
        """\b(sub\s*total|total|tax|vat|balance|change\s+due|cash|visa|mastercard|amex|debit|credit|card|auth|""" +
            """approval|tender|payment|thank\s*you|receipt|cashier|register|tel|phone|www|com|""" +
            """points|you\s*saved|coupon)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LONG_NUMBER = Regex("""\b\d(?:[ -]?\d){5,}\b(?![.,]\d)""")
    private val DATE_OR_TIME = Regex("""\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b|\b\d{1,2}:\d{2}(:\d{2})?\b""")
    private val HAS_LETTER = Regex("""\p{L}""")
    private val SPACES = Regex("""\s+""")

    fun sanitize(raw: String): String? {
        val line = raw.trim().replace(SPACES, " ")
        if (line.isEmpty() || !HAS_LETTER.containsMatchIn(line)) return null
        if (NON_PRODUCT.containsMatchIn(line) || DATE_OR_TIME.containsMatchIn(line)) return null
        return LONG_NUMBER.replace(line, "#")
    }
}
