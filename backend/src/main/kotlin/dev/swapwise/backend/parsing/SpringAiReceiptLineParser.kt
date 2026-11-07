package dev.swapwise.backend.parsing

import dev.swapwise.backend.catalog.FoodCategory
import dev.swapwise.backend.catalog.PurchaseUnit
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor
import org.springframework.stereotype.Component

/**
 * Asks the LLM to parse all cache misses of one receipt in a single call, returning strict JSON that Spring AI
 * maps onto [LlmResponse]. If the JSON does not match the schema (for example, an unknown category), the
 * validation advisor asks the model again, up to two more times.
 *
 * Deliberately no logging advisor: prompts contain receipt lines, and those must never reach the logs.
 */
@Component
class SpringAiReceiptLineParser(builder: ChatClient.Builder) : ReceiptLineParser {
    private val chatClient: ChatClient = builder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultAdvisors(
            StructuredOutputValidationAdvisor.builder()
                .outputType(LlmResponse::class.java)
                .maxRepeatAttempts(2)
                .build(),
        )
        .build()

    override fun parse(lines: List<String>): List<ParsedLine> {
        if (lines.isEmpty()) return emptyList()
        val numbered = lines.withIndex().joinToString("\n") { (i, line) -> "$i: $line" }
        val response = chatClient.prompt().user(numbered).call().entity(LlmResponse::class.java)
            ?: throw IllegalStateException("LLM returned no content")

        val byIndex = response.items.associateBy { it.index }
        check(byIndex.size == response.items.size) { "LLM returned duplicate indexes" }
        check(byIndex.keys == lines.indices.toSet()) { "LLM returned indexes ${byIndex.keys.sorted()} for ${lines.size} lines" }
        return lines.indices.map { i ->
            val item = byIndex.getValue(i)
            ParsedLine(
                isGrocery = item.grocery,
                name = item.name.trim().take(MAX_NAME_LENGTH),
                category = item.category,
                quantity = item.quantity.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
                unit = item.unit,
            )
        }
    }

    /**
     * The JSON shape the model must produce. Every field has a default so the class can be created by any
     * JSON mapper, with or without Kotlin support. The boolean is named `grocery`, not `isGrocery`, because
     * Java-style mappers turn an `is` prefix into a different JSON name than Kotlin-aware ones.
     */
    data class LlmResponse(val items: List<LlmItem> = emptyList())

    data class LlmItem(
        val index: Int = -1,
        val grocery: Boolean = false,
        val name: String = "",
        val category: FoodCategory = FoodCategory.OTHER_FOOD,
        val quantity: Double = 1.0,
        val unit: PurchaseUnit = PurchaseUnit.EACH,
    )

    private companion object {
        const val MAX_NAME_LENGTH = 80

        val SYSTEM_PROMPT = """
            You turn grocery receipt lines into structured items. Each user line starts with its index and a colon.
            Return one item for every index, in any order, and nothing else.

            For each line:
            - index: the number before the colon.
            - grocery: false for fees, deposits, bags, discounts and anything that is not food or drink.
            - name: a short, readable English product name without brand or size, e.g. "ORG BNNA 2LB" -> "Organic bananas".
            - category: exactly one of: ${FoodCategory.promptValues()}. Use OTHER_FOOD only if nothing else fits.
            - quantity and unit: the amount bought, with unit one of ${PurchaseUnit.entries.joinToString(", ")}.
              Use quantity 1 and unit EACH when the line does not say.

            Do not guess information that is not on the line.
        """.trimIndent()
    }
}
