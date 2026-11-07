package dev.swapwise.feature.swaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.swapwise.data.ReceiptRepository
import dev.swapwise.data.db.ReceiptSummary
import dev.swapwise.data.settings.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LedgerUiState(
    val loading: Boolean = true,
    val receipts: List<ReceiptSummary> = emptyList(),
    /** Show the one-time question about sharing anonymous swap stats. */
    val askToShareStats: Boolean = false,
)

@HiltViewModel
class LedgerViewModel @Inject constructor(
    receipts: ReceiptRepository,
    private val settings: AppSettings,
) : ViewModel() {
    val state: StateFlow<LedgerUiState> = combine(receipts.observeSummaries(), settings.shareStats) { list, share ->
        LedgerUiState(loading = false, receipts = list, askToShareStats = share == null)
    }.stateIn(
        scope = viewModelScope,
        // Keep collecting for 5 s after the screen leaves, so a rotation does not restart the database query.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LedgerUiState(),
    )

    fun answerShareStats(allowed: Boolean) {
        viewModelScope.launch { settings.setShareStats(allowed) }
    }
}
