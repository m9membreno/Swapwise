package dev.swapwise.backend.parsing

import com.jayway.jsonpath.JsonPath
import dev.swapwise.backend.BaseIT
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceiptParsingIT : BaseIT() {
    private val uri = "/api/v1/receipts/parse"

    @BeforeEach
    fun resetParser() = parser.reset()

    private fun linesJson(vararg lines: String) = """{"lines": [${lines.joinToString { "\"$it\"" }}]}"""

    @Test
    fun `parses products, skips totals and fees, and keeps line positions`() {
        val veg = uniqueWord()
        val result = postJson(uri, linesJson("GRND BEEF $veg 7.49", "TOTAL 12.00", "BAG FEE $veg 0.10", "$veg 1.99"))

        assertEquals(200, result.response.status)
        val json = result.response.contentAsString
        assertEquals(listOf(0, 3), JsonPath.read<List<Int>>(json, "$.items[*].index"))
        assertEquals("BEEF", JsonPath.read(json, "$.items[0].category"))
        assertEquals(listOf(1, 2), JsonPath.read<List<Int>>(json, "$.skippedIndexes"))
        assertEquals(3, parser.linesSeen.get(), "the total line never reaches the LLM")
    }

    @Test
    fun `second identical receipt is served from the cache without calling the LLM`() {
        val body = linesJson("${uniqueWord()} 2.49", "${uniqueWord()} 3.99")
        assertEquals(200, postJson(uri, body).response.status)
        assertEquals(1, parser.calls.get())

        val second = postJson(uri, body)
        assertEquals(200, second.response.status)
        assertEquals(1, parser.calls.get(), "no new LLM call")
        assertEquals(2, JsonPath.read<List<Any>>(second.response.contentAsString, "$.items").size)
    }

    @Test
    fun `the same product twice on one receipt is parsed once`() {
        val word = uniqueWord()
        val result = postJson(uri, linesJson("$word 1.00", "  ${word.lowercase()}   1.00"))
        assertEquals(200, result.response.status)
        assertEquals(1, parser.linesSeen.get())
        assertEquals(2, JsonPath.read<List<Any>>(result.response.contentAsString, "$.items").size)
    }

    @Test
    fun `a slow LLM gets a 503 with Retry-After, and the quota is given back`() {
        parser.delayMillis = 2_000
        val installId = UUID.randomUUID()
        val lines = Array(15) { uniqueWord() }

        val slow = postJson(uri, linesJson(*lines), installId)
        assertEquals(503, slow.response.status)
        assertEquals("60", slow.response.getHeader("Retry-After"))
        assertTrue("parser-unavailable" in slow.response.contentAsString)

        // 15 of 20 daily lines were reserved and must have been released, or this would be refused.
        parser.delayMillis = 0
        assertEquals(200, postJson(uri, linesJson(*lines), installId).response.status)
    }

    @Test
    fun `an LLM error also maps to 503`() {
        parser.failWith = IllegalStateException("model returned garbage")
        assertEquals(503, postJson(uri, linesJson(uniqueWord())).response.status)
    }

    @Test
    fun `an install over its daily limit gets 429 until midnight UTC`() {
        val installId = UUID.randomUUID()
        assertEquals(200, postJson(uri, linesJson(*Array(20) { uniqueWord() }), installId).response.status)

        val refused = postJson(uri, linesJson(uniqueWord()), installId)
        assertEquals(429, refused.response.status)
        assertTrue(refused.response.getHeader("Retry-After")!!.toLong() in 1..86_400)
    }

    @Test
    fun `cached lines do not count against the quota`() {
        val installId = UUID.randomUUID()
        val lines = Array(20) { uniqueWord() }
        assertEquals(200, postJson(uri, linesJson(*lines)).response.status) // cached by another install
        assertEquals(200, postJson(uri, linesJson(*lines), installId).response.status)
        assertEquals(200, postJson(uri, linesJson(*Array(20) { uniqueWord() }), installId).response.status)
    }

    @Test
    fun `rejects bad input with problem details`() {
        assertEquals(400, postJson(uri, linesJson()).response.status)
        assertEquals(400, postJson(uri, linesJson(*Array(81) { "X" })).response.status)
        assertEquals(400, postJson(uri, linesJson("A".repeat(121))).response.status)
        val noHeader = mvc.post().uri(uri).contentType("application/json").content(linesJson("MILK")).exchange()
        assertEquals(400, noHeader.response.status)
        val badHeader = mvc.post().uri(uri).header("X-Install-Id", "not-a-uuid").contentType("application/json")
            .content(linesJson("MILK")).exchange()
        assertEquals(400, badHeader.response.status)
    }
}
