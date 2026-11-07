package dev.swapwise.data

import dev.swapwise.data.db.EventDao
import dev.swapwise.data.network.EventBatchDto
import dev.swapwise.data.network.SwapEventDto
import dev.swapwise.data.network.SwapwiseApi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventUploader @Inject constructor(
    private val dao: EventDao,
    private val api: SwapwiseApi,
) {
    /**
     * Uploads pending events in batches of [BATCH_SIZE], deleting each batch only after the server confirms it.
     * If the app dies between the upload and the delete, the next run re-sends the batch; the server recognizes
     * the event IDs and stores nothing twice. Throws on network or server errors so the worker can retry.
     */
    suspend fun uploadAll(): Int {
        var uploaded = 0
        while (true) {
            val batch = dao.oldest(BATCH_SIZE)
            if (batch.isEmpty()) return uploaded
            api.uploadEvents(
                EventBatchDto(
                    batch.map {
                        SwapEventDto(
                            eventId = it.eventId,
                            type = it.type,
                            suggestionId = it.suggestionId,
                            swapCount = it.swapCount,
                            kgCo2eSaved = it.kgCo2eSaved,
                            costDeltaCents = it.costDeltaCents,
                            factorVersion = it.factorVersion,
                            occurredAt = Instant.ofEpochMilli(it.occurredAtMillis).toString(),
                        )
                    },
                ),
            )
            dao.delete(batch.map { it.eventId })
            uploaded += batch.size
        }
    }

    private companion object {
        const val BATCH_SIZE = 100 // the backend's maximum per request
    }
}
