package dev.swapwise.data

import dev.swapwise.data.db.ParsedRow
import dev.swapwise.data.db.ReceiptDao
import dev.swapwise.data.db.ReceiptEntity
import dev.swapwise.data.db.ReceiptLineEntity
import dev.swapwise.data.db.ReceiptStatus
import dev.swapwise.data.db.ReceiptSummary
import dev.swapwise.data.network.ParseRequestDto
import dev.swapwise.data.network.SwapwiseApi
import dev.swapwise.data.work.WorkScheduler
import dev.swapwise.domain.LineSanitizer
import dev.swapwise.domain.PriceExtractor
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the parse step did, so the worker can decide between done, retry and give up. */
sealed interface ParseOutcome {
    data object Parsed : ParseOutcome
    data object NothingToParse : ParseOutcome
}

@Singleton
class ReceiptRepository @Inject constructor(
    private val dao: ReceiptDao,
    private val api: SwapwiseApi,
    private val scheduler: WorkScheduler,
    private val clock: Clock,
) {
    fun observeSummaries(): Flow<List<ReceiptSummary>> = dao.observeSummaries()

    fun observeReceipt(id: String): Flow<ReceiptEntity?> = dao.observeReceipt(id)

    fun observeLines(id: String): Flow<List<ReceiptLineEntity>> = dao.observeLines(id)

    /**
     * Saves OCR rows as a new receipt and schedules parsing. Works offline: the receipt is stored now and
     * parsed whenever WorkManager sees a network connection. Returns the new receipt's ID.
     */
    suspend fun saveScannedReceipt(rows: List<String>): String {
        require(rows.isNotEmpty()) { "No text found on the receipt" }
        val id = UUID.randomUUID().toString()
        var position = 0
        val lines = rows.mapIndexed { index, raw ->
            val sanitized = LineSanitizer.sanitize(raw)
            ReceiptLineEntity(
                receiptId = id,
                lineIndex = index,
                rawText = raw,
                sanitizedText = sanitized,
                uploadPosition = sanitized?.let { position++ },
                priceCents = PriceExtractor.lineTotalCents(raw),
            )
        }
        dao.insertReceipt(ReceiptEntity(id, clock.millis(), ReceiptStatus.PENDING_PARSE))
        dao.insertLines(lines)
        scheduler.enqueueParse(id)
        return id
    }

    /**
     * Sends the receipt's cleaned rows to the backend and stores the result. Throws on network or server errors;
     * the worker turns those into a retry or a failure.
     */
    suspend fun parse(receiptId: String): ParseOutcome {
        if (dao.status(receiptId) == ReceiptStatus.PARSED) return ParseOutcome.Parsed // already done (retried work)
        val lines = dao.linesToUpload(receiptId)
        if (lines.isEmpty()) {
            dao.setStatus(receiptId, ReceiptStatus.PARSED)
            return ParseOutcome.NothingToParse
        }
        // Upload positions are contiguous from 0, so the server's index is the position.
        val response = api.parse(ParseRequestDto(lines.map { it.sanitizedText!! }))
        val rows = response.items
            .filter { it.index in lines.indices }
            .map { ParsedRow(it.index, it.name, it.category, it.quantity, it.unit) }
        dao.applyParseResult(receiptId, rows)
        return ParseOutcome.Parsed
    }

    suspend fun markParseFailed(receiptId: String) = dao.setStatus(receiptId, ReceiptStatus.PARSE_FAILED)

    /** For the "Try again" button after parsing gave up. */
    suspend fun retryParse(receiptId: String) {
        dao.setStatus(receiptId, ReceiptStatus.PENDING_PARSE)
        scheduler.enqueueParse(receiptId)
    }
}
