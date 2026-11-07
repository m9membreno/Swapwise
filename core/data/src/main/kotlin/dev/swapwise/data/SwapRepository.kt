package dev.swapwise.data

import androidx.room.withTransaction
import dev.swapwise.data.db.EventDao
import dev.swapwise.data.db.PendingEventEntity
import dev.swapwise.data.db.ReceiptDao
import dev.swapwise.data.db.ReceiptLineEntity
import dev.swapwise.data.db.SuggestionDao
import dev.swapwise.data.db.SuggestionEntity
import dev.swapwise.data.db.SuggestionStatus
import dev.swapwise.data.db.SwapwiseDatabase
import dev.swapwise.data.settings.AppSettings
import dev.swapwise.data.work.WorkScheduler
import dev.swapwise.domain.AcceptedCard
import dev.swapwise.domain.BasketBuilder
import dev.swapwise.domain.CardAnswer
import dev.swapwise.domain.CardOutcome
import dev.swapwise.domain.FollowThrough
import dev.swapwise.domain.FollowThroughChecker
import dev.swapwise.domain.FoodCategory
import dev.swapwise.domain.PriceBook
import dev.swapwise.domain.PurchaseUnit
import dev.swapwise.domain.ReceiptCategories
import dev.swapwise.domain.ReceiptItem
import dev.swapwise.domain.SwapCards
import dev.swapwise.optimizer.SwapEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One swap inside a stored card. */
@Serializable
data class StoredSwap(val receiptLineIndex: Int, val fromName: String, val toCategory: String, val kg: Double)

/** A card as the UI sees it. */
data class SwapCardModel(
    val id: String,
    val swaps: List<StoredSwap>,
    val kgCo2eSaved: Double,
    val costDeltaCents: Long,
    val status: SuggestionStatus,
)

@Singleton
class SwapRepository @Inject constructor(
    private val db: SwapwiseDatabase,
    private val receiptDao: ReceiptDao,
    private val suggestionDao: SuggestionDao,
    private val eventDao: EventDao,
    private val factors: FactorRepository,
    private val settings: AppSettings,
    private val scheduler: WorkScheduler,
    private val json: Json,
    private val clock: Clock,
) {
    private val engine = SwapEngine()

    fun observeCards(receiptId: String): Flow<List<SwapCardModel>> =
        suggestionDao.observeForReceipt(receiptId).map { list ->
            list.map { SwapCardModel(it.id, json.decodeFromString(it.swapsJson), it.kgCo2eSaved, it.costDeltaCents, it.status) }
        }

    /**
     * Computes and stores the cards for a parsed receipt, once. Later calls do nothing, so a card a shopper
     * already answered is never replaced by a different one. Returns false when factors are missing.
     */
    suspend fun ensureCards(receiptId: String): Boolean {
        if (suggestionDao.countForReceipt(receiptId) > 0) return true
        val factorMap = factors.factors()
        val version = factors.version() ?: return false

        val items = receiptDao.parsedLines(receiptId).mapNotNull { it.toItem() }
        val history = receiptDao.allParsedLines().mapNotNull { it.toItem() }

        // CPU-bound: the optimizer runs off the main thread, on the pool sized for computation.
        val cards = withContext(Dispatchers.Default) {
            val basket = BasketBuilder.build(items, factorMap, PriceBook.fromHistory(history, factorMap))
            SwapCards.from(basket, engine.suggest(basket.problem))
        }
        val now = clock.millis()
        val entities = cards.mapIndexed { rank, card ->
            SuggestionEntity(
                id = UUID.randomUUID().toString(),
                receiptId = receiptId,
                rank = rank,
                swapsJson = json.encodeToString(card.swaps.map { StoredSwap(it.receiptLineIndex, it.fromName, it.to.name, it.kg) }),
                kgCo2eSaved = card.kgCo2eSaved,
                costDeltaCents = card.costDeltaCents,
                factorVersion = version,
                status = SuggestionStatus.SHOWN,
                createdAtMillis = now,
            )
        }
        val share = settings.shareStats.first() == true
        db.withTransaction {
            // Re-check inside the transaction: two screens racing must not both insert cards.
            if (suggestionDao.countForReceipt(receiptId) == 0) {
                suggestionDao.insertAll(entities)
                if (share) eventDao.insertAll(entities.map { it.toEvent("SHOWN", now) })
            }
        }
        if (share && entities.isNotEmpty()) scheduler.enqueueEventUpload()
        return true
    }

    suspend fun accept(suggestionId: String) = answer(suggestionId, SuggestionStatus.ACCEPTED, "ACCEPTED")

    suspend fun dismiss(suggestionId: String) = answer(suggestionId, SuggestionStatus.DISMISSED, "DISMISSED")

    /** Status change and its event in one transaction: the outbox can never disagree with what the UI shows. */
    private suspend fun answer(suggestionId: String, status: SuggestionStatus, eventType: String) {
        val share = settings.shareStats.first() == true
        val changed = db.withTransaction {
            val suggestion = suggestionDao.get(suggestionId) ?: return@withTransaction false
            if (suggestion.status != SuggestionStatus.SHOWN) return@withTransaction false // answered already
            suggestionDao.setAnswered(suggestionId, status, clock.millis())
            if (share) eventDao.insert(suggestion.toEvent(eventType, clock.millis()))
            true
        }
        if (changed && share) scheduler.enqueueEventUpload()
    }

    /** Impact screen data: every card and how it turned out. */
    fun observeOutcomes(): Flow<List<CardOutcome>> = suggestionDao.observeAll().map { list ->
        list.map { entity ->
            CardOutcome(
                answer = when (entity.status) {
                    SuggestionStatus.SHOWN -> CardAnswer.SHOWN
                    SuggestionStatus.ACCEPTED -> CardAnswer.ACCEPTED
                    SuggestionStatus.DISMISSED -> CardAnswer.DISMISSED
                },
                kgCo2eSaved = entity.kgCo2eSaved,
                costDeltaCents = entity.costDeltaCents,
                followThrough = entity.followThrough?.let { name -> FollowThrough.entries.firstOrNull { it.name == name } },
            )
        }
    }

    /**
     * Re-checks accepted cards against all parsed receipts. Runs after every newly parsed receipt and when the
     * Impact screen opens (so cards whose window ended move to NOT_SEEN). A result is written only when it
     * changes, and a VERIFIED event is queued only on the change to VERIFIED_NEW, so running this repeatedly
     * never sends duplicates. Already-bought swaps are kept on the phone but not reported as verified.
     */
    suspend fun verifyAccepted() {
        val cards = suggestionDao.acceptedUnresolved()
        if (cards.isEmpty()) return
        val receipts = receiptDao.receiptCategories()
            .groupBy { it.receiptId }
            .map { (_, rows) -> ReceiptCategories(rows.first().scannedAtMillis, rows.mapNotNull { FoodCategory.fromCode(it.category) }.toSet()) }
        val now = clock.millis()
        val changes = cards.mapNotNull { entity ->
            val targets = json.decodeFromString<List<StoredSwap>>(entity.swapsJson).mapNotNull { FoodCategory.fromCode(it.toCategory) }.toSet()
            if (targets.isEmpty()) return@mapNotNull null
            val result = FollowThroughChecker.evaluate(AcceptedCard(entity.id, entity.answeredAtMillis ?: entity.createdAtMillis, targets), receipts, now)
            if (result.name == entity.followThrough) null else entity to result
        }
        if (changes.isEmpty()) return
        val share = settings.shareStats.first() == true
        db.withTransaction {
            changes.forEach { (entity, result) ->
                suggestionDao.setFollowThrough(entity.id, result.name)
                if (share && result == FollowThrough.VERIFIED_NEW) eventDao.insert(entity.toEvent("VERIFIED", now))
            }
        }
        if (share && changes.any { it.second == FollowThrough.VERIFIED_NEW }) scheduler.enqueueEventUpload()
    }

    private fun SuggestionEntity.toEvent(type: String, at: Long) = PendingEventEntity(
        eventId = UUID.randomUUID().toString(),
        type = type,
        suggestionId = id,
        swapCount = json.decodeFromString<List<StoredSwap>>(swapsJson).size,
        kgCo2eSaved = kgCo2eSaved,
        costDeltaCents = costDeltaCents,
        factorVersion = factorVersion,
        occurredAtMillis = at,
    )

    private fun ReceiptLineEntity.toItem(): ReceiptItem? {
        val category = FoodCategory.fromCode(category) ?: return null
        val unit = PurchaseUnit.fromCode(unit) ?: return null
        return ReceiptItem(lineIndex, name ?: return null, category, quantity ?: return null, unit, priceCents)
    }
}
