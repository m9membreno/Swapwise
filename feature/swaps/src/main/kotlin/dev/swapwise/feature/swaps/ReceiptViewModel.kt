package dev.swapwise.feature.swaps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.swapwise.data.FactorRepository
import dev.swapwise.data.ReceiptRepository
import dev.swapwise.data.SwapCardModel
import dev.swapwise.data.SwapRepository
import dev.swapwise.data.db.ReceiptLineEntity
import dev.swapwise.data.db.ReceiptStatus
import dev.swapwise.data.work.WorkScheduler
import dev.swapwise.domain.FoodCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ItemRow(val lineIndex: Int, val name: String, val category: FoodCategory?, val priceCents: Long?)

data class ReceiptUiState(
    val loading: Boolean = true,
    val status: ReceiptStatus? = null,
    val items: List<ItemRow> = emptyList(),
    val cards: List<SwapCardModel> = emptyList(),
    /** Parsed, but no carbon factors downloaded yet, so no cards can be computed. */
    val waitingForFactors: Boolean = false,
)

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val receipts: ReceiptRepository,
    private val swaps: SwapRepository,
    private val factors: FactorRepository,
    private val scheduler: WorkScheduler,
) : ViewModel() {
    private val receiptId: String = checkNotNull(savedStateHandle[RECEIPT_ID]) { "Missing $RECEIPT_ID" }
    private val waitingForFactors = MutableStateFlow(false)

    val state: StateFlow<ReceiptUiState> = combine(
        receipts.observeReceipt(receiptId),
        receipts.observeLines(receiptId),
        swaps.observeCards(receiptId),
        waitingForFactors,
    ) { receipt, lines, cards, waiting ->
        ReceiptUiState(
            loading = false,
            status = receipt?.status,
            items = lines.filter { it.category != null }.map { it.toRow() },
            cards = cards,
            waitingForFactors = waiting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptUiState())

    init {
        // Cards need a parsed receipt and carbon factors. Either can arrive later (WorkManager runs when online),
        // so wait for both, then compute the cards once.
        viewModelScope.launch {
            receipts.observeReceipt(receiptId).map { it?.status }.first { it == ReceiptStatus.PARSED }
            factors.observeVersion().first { version ->
                if (version == null) {
                    waitingForFactors.value = true
                    scheduler.syncFactorsNow()
                }
                version != null
            }
            waitingForFactors.value = false
            swaps.ensureCards(receiptId)
        }
    }

    fun accept(cardId: String) = viewModelScope.launch { swaps.accept(cardId) }

    fun dismiss(cardId: String) = viewModelScope.launch { swaps.dismiss(cardId) }

    fun retryParse() = viewModelScope.launch { receipts.retryParse(receiptId) }

    private fun ReceiptLineEntity.toRow() = ItemRow(lineIndex, name ?: rawText, FoodCategory.fromCode(category), priceCents)

    companion object {
        const val RECEIPT_ID = "receiptId"
    }
}
