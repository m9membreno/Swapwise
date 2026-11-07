package dev.swapwise.backend.events

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class SwapEventType { SHOWN, ACCEPTED, DISMISSED, VERIFIED }

/**
 * One thing that happened to a swap card. [eventId] is created by the app when the event happens and is
 * reused on every retry; that is what makes uploads safe to repeat.
 */
data class SwapEventDto(
    val eventId: UUID,
    val type: SwapEventType,
    val suggestionId: UUID,
    @field:Min(1) @field:Max(3) val swapCount: Int,
    val kgCo2eSaved: Double,
    val costDeltaCents: Long,
    @field:Min(1) val factorVersion: Int,
    val occurredAt: Instant,
)

data class EventBatch(
    @field:NotEmpty @field:Size(max = 100) @field:Valid val events: List<SwapEventDto> = emptyList(),
)

data class EventBatchResult(val accepted: Int, val duplicates: Int)

@Repository
class SwapEventRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /**
     * Inserts the events; ones whose ID is already stored are skipped by the database, not by application
     * code, so even two retries racing each other cannot create a duplicate. First write wins.
     *
     * Note: keep the Postgres driver's `reWriteBatchedInserts` off, or batch counts come back as "unknown"
     * and duplicates cannot be counted.
     */
    fun insertAll(installId: UUID, events: List<SwapEventDto>): EventBatchResult {
        val counts = jdbc.batchUpdate(
            """
            insert into swap_event
                (event_id, install_id, type, suggestion_id, swap_count, kg_co2e_saved, cost_delta_cents, factor_version, occurred_at)
            values
                (:eventId, :installId, :type, :suggestionId, :swapCount, :kgSaved, :costDelta, :factorVersion, :occurredAt)
            on conflict (event_id) do nothing
            """.trimIndent(),
            events.map { e ->
                MapSqlParameterSource()
                    .addValue("eventId", e.eventId)
                    .addValue("installId", installId)
                    .addValue("type", e.type.name)
                    .addValue("suggestionId", e.suggestionId)
                    .addValue("swapCount", e.swapCount)
                    .addValue("kgSaved", e.kgCo2eSaved)
                    .addValue("costDelta", e.costDeltaCents)
                    .addValue("factorVersion", e.factorVersion)
                    .addValue("occurredAt", Timestamp.from(e.occurredAt))
            }.toTypedArray(),
        )
        val accepted = counts.count { it > 0 }
        return EventBatchResult(accepted = accepted, duplicates = events.size - accepted)
    }
}

@RestController
@RequestMapping("/api/v1/events")
class SwapEventController(private val repository: SwapEventRepository) {
    /**
     * Accepts a batch of opt-in events from WorkManager. Always safe to retry: sending the same batch twice
     * returns the same 200 and stores nothing new. Duplicate IDs inside one batch are also handled.
     */
    @PostMapping
    fun upload(
        @RequestHeader("X-Install-Id") installId: UUID,
        @RequestBody @Valid batch: EventBatch,
    ): EventBatchResult = repository.insertAll(installId, batch.events)
}
