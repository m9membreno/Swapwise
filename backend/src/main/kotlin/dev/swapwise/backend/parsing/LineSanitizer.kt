package dev.swapwise.backend.parsing

/**
 * Removes receipt lines that are not products and masks anything that looks personal, before a line is
 * cached or sent to the LLM.
 *
 * The app runs the same rules before upload; doing it again here is defense in depth, because the server
 * cannot trust that every client is up to date.
 */
object LineSanitizer {
    /**
     * Lines about payment, totals or the visit, never products. Deliberately does not include words like
     * "store" or "member", which appear in real product names ("STORE BRAND MILK", "MEMBER'S MARK").
     */
    private val NON_PRODUCT = Regex(
        """\b(sub\s*total|total|tax|vat|balance|change\s+due|cash|visa|mastercard|amex|debit|credit|card|auth|""" +
            """approval|tender|payment|thank\s*you|receipt|cashier|register|tel|phone|www|com|""" +
            """points|you\s*saved|coupon)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 6+ digits in a row, possibly split by spaces or dashes: card, phone, loyalty or transaction numbers.
     * The lookahead stops a run from swallowing the start of a price ("0041220 7.49" must keep "7.49").
     */
    private val LONG_NUMBER = Regex("""\b\d(?:[ -]?\d){5,}\b(?![.,]\d)""")

    private val DATE_OR_TIME = Regex("""\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b|\b\d{1,2}:\d{2}(:\d{2})?\b""")

    private val HAS_LETTER = Regex("""\p{L}""")

    /** Returns the cleaned line, or null if the line should be dropped. */
    fun sanitize(raw: String): String? {
        val line = raw.trim().replace(Regex("""\s+"""), " ")
        if (line.isEmpty() || !HAS_LETTER.containsMatchIn(line)) return null
        if (NON_PRODUCT.containsMatchIn(line) || DATE_OR_TIME.containsMatchIn(line)) return null
        return LONG_NUMBER.replace(line, "#")
    }

    /**
     * The form used for the cache key: case and spacing do not matter ("ORG BNNA 2LB" and
     * "org  bnna 2lb" are the same line).
     */
    fun normalize(sanitized: String): String = sanitized.uppercase().replace(Regex("""\s+"""), " ").trim()
}
