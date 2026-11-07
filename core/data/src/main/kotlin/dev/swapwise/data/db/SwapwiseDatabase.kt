package dev.swapwise.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Bump [version] and add a migration (plus a migration test against the exported schema) for every schema change.
 * Never use destructive migration in release: this database holds the shopper's receipts.
 */
@Database(
    entities = [
        ReceiptEntity::class,
        ReceiptLineEntity::class,
        CarbonFactorEntity::class,
        SuggestionEntity::class,
        PendingEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SwapwiseDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao
    abstract fun factorDao(): FactorDao
    abstract fun suggestionDao(): SuggestionDao
    abstract fun eventDao(): EventDao
}
