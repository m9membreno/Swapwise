package dev.swapwise.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ReceiptStatus { PENDING_PARSE, PARSED, PARSE_FAILED }

enum class SuggestionStatus { SHOWN, ACCEPTED, DISMISSED }

@Entity(tableName = "receipt")
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val scannedAtMillis: Long,
    val status: ReceiptStatus,
)

/**
 * One OCR row. The raw text never leaves the phone; only rows that pass the sanitizer ([uploadPosition] not
 * null) are sent for parsing, and [uploadPosition] maps the server's answer back to this row.
 */
@Entity(
    tableName = "receipt_line",
    primaryKeys = ["receiptId", "lineIndex"],
    foreignKeys = [ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("receiptId"), Index("category")],
)
data class ReceiptLineEntity(
    val receiptId: String,
    val lineIndex: Int,
    val rawText: String,
    val sanitizedText: String?,
    val uploadPosition: Int?,
    val priceCents: Long?,
    val name: String? = null,
    val category: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
)

@Entity(tableName = "carbon_factor")
data class CarbonFactorEntity(
    @PrimaryKey val category: String,
    val version: Int,
    val kgCo2ePerKg: Double,
    val kgCo2ePerKgLow: Double,
    val kgCo2ePerKgHigh: Double,
    val typicalItemKg: Double?,
)

/** A swap card shown for a receipt. [swapsJson] holds the card's swaps (see `StoredSwap`). */
@Entity(
    tableName = "suggestion",
    foreignKeys = [ForeignKey(entity = ReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("receiptId")],
)
data class SuggestionEntity(
    @PrimaryKey val id: String,
    val receiptId: String,
    val rank: Int,
    val swapsJson: String,
    val kgCo2eSaved: Double,
    val costDeltaCents: Long,
    val factorVersion: Int,
    val status: SuggestionStatus,
    val createdAtMillis: Long,
)

/**
 * Transactional outbox: an event is written in the same database transaction as the state change it describes,
 * so the two can never disagree. A worker uploads pending events and deletes them only after the server
 * confirms. The [eventId] is reused on every retry, which is what makes the upload idempotent.
 */
@Entity(tableName = "pending_event")
data class PendingEventEntity(
    @PrimaryKey val eventId: String,
    val type: String,
    val suggestionId: String,
    val swapCount: Int,
    val kgCo2eSaved: Double,
    val costDeltaCents: Long,
    val factorVersion: Int,
    val occurredAtMillis: Long,
)
