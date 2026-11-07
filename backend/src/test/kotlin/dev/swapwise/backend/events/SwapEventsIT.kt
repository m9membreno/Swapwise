package dev.swapwise.backend.events

import com.jayway.jsonpath.JsonPath
import dev.swapwise.backend.BaseIT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.assertEquals

class SwapEventsIT : BaseIT() {
    @Autowired
    lateinit var jdbc: JdbcClient

    private val uri = "/api/v1/events"

    private fun event(id: UUID, type: String = "ACCEPTED", swapCount: Int = 1) = """
        {"eventId": "$id", "type": "$type", "suggestionId": "${UUID.randomUUID()}", "swapCount": $swapCount,
         "kgCo2eSaved": 4.2, "costDeltaCents": -50, "factorVersion": 1, "occurredAt": "2026-11-07T15:00:00Z"}
    """.trimIndent()

    private fun batch(vararg events: String) = """{"events": [${events.joinToString()}]}"""

    private fun stored(id: UUID): Long =
        jdbc.sql("select count(*) from swap_event where event_id = :id").param("id", id).query(Long::class.javaObjectType).single()

    @Test
    fun `a retried batch is accepted again but stored only once`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val body = batch(event(a), event(b, "SHOWN"))

        val first = postJson(uri, body)
        assertEquals(200, first.response.status)
        assertEquals(2, JsonPath.read(first.response.contentAsString, "$.accepted"))

        val retry = postJson(uri, body)
        assertEquals(200, retry.response.status)
        assertEquals(0, JsonPath.read(retry.response.contentAsString, "$.accepted"))
        assertEquals(2, JsonPath.read(retry.response.contentAsString, "$.duplicates"))
        assertEquals(1L, stored(a))
        assertEquals(1L, stored(b))
    }

    @Test
    fun `duplicate IDs inside one batch are stored once`() {
        val id = UUID.randomUUID()
        val result = postJson(uri, batch(event(id), event(id)))
        assertEquals(1, JsonPath.read(result.response.contentAsString, "$.accepted"))
        assertEquals(1L, stored(id))
    }

    @Test
    fun `rejects invalid events`() {
        assertEquals(400, postJson(uri, batch()).response.status, "empty batch")
        assertEquals(400, postJson(uri, batch(event(UUID.randomUUID(), swapCount = 4))).response.status, "swapCount above 3")
        assertEquals(400, postJson(uri, batch(event(UUID.randomUUID(), type = "CLICKED"))).response.status, "unknown type")
        assertEquals(400, postJson(uri, batch(*Array(101) { event(UUID.randomUUID()) })).response.status, "batch too large")
    }
}
