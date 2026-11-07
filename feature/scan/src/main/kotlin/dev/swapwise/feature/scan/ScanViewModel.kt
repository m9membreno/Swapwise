package dev.swapwise.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.swapwise.domain.OcrRowAssembler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Reading : ScanUiState
    data class Saved(val receiptId: String) : ScanUiState
    data class Error(val reason: Reason) : ScanUiState

    enum class Reason { NO_TEXT, OCR_FAILED }
}

/** Saves the OCR rows of a receipt. Kept separate from Room so the ViewModel is easy to test. */
fun interface ReceiptSaver {
    suspend fun save(rows: List<String>): String
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val ocr: OcrEngine,
    private val saver: ReceiptSaver,
) : ViewModel() {
    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun onImage(imageUri: String) {
        if (_state.value == ScanUiState.Reading) return // ignore double taps while working
        _state.value = ScanUiState.Reading
        viewModelScope.launch {
            _state.value = try {
                val rows = OcrRowAssembler.assemble(ocr.recognize(imageUri))
                if (rows.isEmpty()) ScanUiState.Error(ScanUiState.Reason.NO_TEXT) else ScanUiState.Saved(saver.save(rows))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ScanUiState.Error(ScanUiState.Reason.OCR_FAILED)
            }
        }
    }

    /** Called after navigating away from a saved receipt, so coming back shows the scan buttons again. */
    fun reset() {
        _state.value = ScanUiState.Idle
    }
}
