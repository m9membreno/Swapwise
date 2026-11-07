package dev.swapwise.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades a real v1 database (built from the committed schema JSON) to the current version and checks that the
 * result matches what Room expects and that the shopper's data survived.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SwapwiseDatabase::class.java)

    @Test
    fun migrate1To2_keepsCardsAndBackfillsAnswerTime() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO receipt (id, scannedAtMillis, status) VALUES ('r1', 1000, 'PARSED')")
            db.execSQL(
                "INSERT INTO suggestion (id, receiptId, rank, swapsJson, kgCo2eSaved, costDeltaCents, factorVersion, status, createdAtMillis) " +
                    "VALUES ('answered', 'r1', 0, '[]', 5.0, -100, 1, 'ACCEPTED', 2000)",
            )
            db.execSQL(
                "INSERT INTO suggestion (id, receiptId, rank, swapsJson, kgCo2eSaved, costDeltaCents, factorVersion, status, createdAtMillis) " +
                    "VALUES ('open', 'r1', 1, '[]', 1.0, 0, 1, 'SHOWN', 3000)",
            )
        }

        // Validates the migrated schema against Room's exported v2 schema (columns, types, indices, keys).
        val db = helper.runMigrationsAndValidate(DB, 2, true, *ALL_MIGRATIONS)

        db.query("SELECT id, answeredAtMillis, followThrough FROM suggestion ORDER BY id").use { cursor ->
            cursor.moveToFirst()
            assertEquals("answered", cursor.getString(0))
            assertEquals(2000L, cursor.getLong(1))
            assertNull(cursor.getString(2))
            cursor.moveToNext()
            assertEquals("open", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
