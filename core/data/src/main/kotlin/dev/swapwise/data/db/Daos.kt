package dev.swapwise.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A receipt with counts for the ledger list. */
data class ReceiptSummary(
    val id: String,
    val scannedAtMillis: Long,
    val status: ReceiptStatus,
    val itemCount: Int,
    val totalCents: Long?,
)

/** Parsed result for one uploaded row, keyed by its position in the upload. */
data class ParsedRow(
    val uploadPosition: Int,
    val name: String,
    val category: String,
    val quantity: Double,
    val unit: String,
)

@Dao
interface ReceiptDao {
    @Insert
    suspend fun insertReceipt(receipt: ReceiptEntity)

    @Insert
    suspend fun insertLines(lines: List<ReceiptLineEntity>)

    @Query(
        """
        select r.id, r.scannedAtMillis, r.status,
               count(l.category) as itemCount,
               sum(case when l.category is not null then l.priceCents end) as totalCents
        from receipt r left join receipt_line l on l.receiptId = r.id
        group by r.id
        order by r.scannedAtMillis desc
        """,
    )
    fun observeSummaries(): Flow<List<ReceiptSummary>>

    @Query("select * from receipt where id = :id")
    fun observeReceipt(id: String): Flow<ReceiptEntity?>

    @Query("select * from receipt_line where receiptId = :receiptId order by lineIndex")
    fun observeLines(receiptId: String): Flow<List<ReceiptLineEntity>>

    @Query("select * from receipt_line where receiptId = :receiptId and uploadPosition is not null order by uploadPosition")
    suspend fun linesToUpload(receiptId: String): List<ReceiptLineEntity>

    @Query("select * from receipt_line where receiptId = :receiptId and category is not null order by lineIndex")
    suspend fun parsedLines(receiptId: String): List<ReceiptLineEntity>

    /** Every parsed line from every receipt: the shopper's own price history. */
    @Query("select * from receipt_line where category is not null and priceCents is not null")
    suspend fun allParsedLines(): List<ReceiptLineEntity>

    @Query("select status from receipt where id = :id")
    suspend fun status(id: String): ReceiptStatus?

    @Query("update receipt set status = :status where id = :id")
    suspend fun setStatus(id: String, status: ReceiptStatus)

    @Query(
        """
        update receipt_line set name = :name, category = :category, quantity = :quantity, unit = :unit
        where receiptId = :receiptId and uploadPosition = :uploadPosition
        """,
    )
    suspend fun setParsed(receiptId: String, uploadPosition: Int, name: String, category: String, quantity: Double, unit: String)

    /** Writes every parsed row and the new status together, so the UI never sees a half-parsed receipt. */
    @Transaction
    suspend fun applyParseResult(receiptId: String, rows: List<ParsedRow>) {
        rows.forEach { setParsed(receiptId, it.uploadPosition, it.name, it.category, it.quantity, it.unit) }
        setStatus(receiptId, ReceiptStatus.PARSED)
    }
}

@Dao
interface FactorDao {
    @Query("select * from carbon_factor")
    suspend fun all(): List<CarbonFactorEntity>

    @Query("select max(version) from carbon_factor")
    suspend fun version(): Int?

    @Query("select max(version) from carbon_factor")
    fun observeVersion(): Flow<Int?>

    @Query("delete from carbon_factor")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(factors: List<CarbonFactorEntity>)

    /** Swaps the whole release at once: readers see either the old factors or the new ones. */
    @Transaction
    suspend fun replaceAll(factors: List<CarbonFactorEntity>) {
        deleteAll()
        insertAll(factors)
    }
}

@Dao
interface SuggestionDao {
    @Query("select * from suggestion where receiptId = :receiptId order by rank")
    fun observeForReceipt(receiptId: String): Flow<List<SuggestionEntity>>

    @Query("select count(*) from suggestion where receiptId = :receiptId")
    suspend fun countForReceipt(receiptId: String): Int

    @Query("select * from suggestion where id = :id")
    suspend fun get(id: String): SuggestionEntity?

    @Insert
    suspend fun insertAll(suggestions: List<SuggestionEntity>)

    @Query("update suggestion set status = :status where id = :id")
    suspend fun setStatus(id: String, status: SuggestionStatus)
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PendingEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<PendingEventEntity>)

    @Query("select * from pending_event order by occurredAtMillis limit :limit")
    suspend fun oldest(limit: Int): List<PendingEventEntity>

    @Query("delete from pending_event where eventId in (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("select count(*) from pending_event")
    suspend fun count(): Int
}
