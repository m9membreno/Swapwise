package dev.swapwise.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
    exportSchema = true,
)
abstract class SwapwiseDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao
    abstract fun factorDao(): FactorDao
    abstract fun suggestionDao(): SuggestionDao
    abstract fun eventDao(): EventDao
}

/**
 * v1 -> v2: adds when a card was answered and its follow-through result. Cards answered before the upgrade get
 * their creation time as the best available answer time, so follow-through checks still work for them.
 * Tested in `MigrationTest` against the exported schemas.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestion ADD COLUMN answeredAtMillis INTEGER")
        db.execSQL("ALTER TABLE suggestion ADD COLUMN followThrough TEXT")
        db.execSQL("UPDATE suggestion SET answeredAtMillis = createdAtMillis WHERE status != 'SHOWN'")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
