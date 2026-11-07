package dev.swapwise.feature.scan

import app.cash.turbine.test
import dev.swapwise.domain.OcrFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    private val photo = "content://test/receipt.jpg"

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `saves rebuilt rows and reports the receipt id`() = runTest {
        var savedRows: List<String> = emptyList()
        val viewModel = ScanViewModel(
            ocr = { listOf(OcrFragment("MILK", 0, 0, 10), OcrFragment("1.99", 200, 1, 11)) },
            saver = { rows -> savedRows = rows; "receipt-1" },
        )
        viewModel.state.test {
            assertEquals(ScanUiState.Idle, awaitItem())
            viewModel.onImage(photo)
            assertEquals(ScanUiState.Saved("receipt-1"), expectMostRecentItem())
        }
        assertEquals(listOf("MILK 1.99"), savedRows)
    }

    @Test
    fun `reports no text instead of saving an empty receipt`() = runTest {
        val viewModel = ScanViewModel(ocr = { emptyList() }, saver = { error("must not save") })
        viewModel.onImage(photo)
        assertEquals(ScanUiState.Error(ScanUiState.Reason.NO_TEXT), viewModel.state.value)
    }

    @Test
    fun `reports OCR failures`() = runTest {
        val viewModel = ScanViewModel(ocr = { throw IllegalStateException("model missing") }, saver = { "x" })
        viewModel.onImage(photo)
        assertEquals(ScanUiState.Error(ScanUiState.Reason.OCR_FAILED), viewModel.state.value)
    }
}
