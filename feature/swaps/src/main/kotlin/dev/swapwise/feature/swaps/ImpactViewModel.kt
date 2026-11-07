package dev.swapwise.feature.swaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.swapwise.data.SwapRepository
import dev.swapwise.domain.ImpactStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImpactViewModel @Inject constructor(private val swaps: SwapRepository) : ViewModel() {
    /** null while loading. */
    val stats: StateFlow<ImpactStats?> = swaps.observeOutcomes()
        .map { ImpactStats.from(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Cards whose 30-day window has ended move from pending to "not seen" here, even with no new receipt.
        viewModelScope.launch { swaps.verifyAccepted() }
    }
}
