package dev.swapwise.feature.swaps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.swapwise.data.StoredSwap
import dev.swapwise.data.SwapCardModel
import dev.swapwise.data.db.SuggestionStatus
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class SwapCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val bundle = SwapCardModel(
        id = "card-1",
        swaps = listOf(StoredSwap(0, "Ground beef", "POULTRY", 0.45), StoredSwap(3, "Whole milk", "PLANT_MILK", 2.0)),
        kgCo2eSaved = 12.4,
        costDeltaCents = -150,
        status = SuggestionStatus.SHOWN,
    )

    @Test
    fun showsBundleSavingsAndPriceAndReportsAccept() {
        var accepted: String? = null
        compose.setContent { SwapCard(bundle, onAccept = { accepted = it }, onDismiss = {}) }

        compose.onNodeWithText("Swap these together").assertIsDisplayed()
        compose.onNodeWithText("Ground beef → Chicken or turkey").assertIsDisplayed()
        compose.onNodeWithText("About 12 kg CO2e less · $1.50 cheaper").assertIsDisplayed()
        compose.onNodeWithText("I'll try it").performClick()
        assertEquals("card-1", accepted)
    }

    @Test
    fun answeredCardsShowTheirStateInsteadOfButtons() {
        compose.setContent { SwapCard(bundle.copy(status = SuggestionStatus.ACCEPTED), onAccept = {}, onDismiss = {}) }
        compose.onNodeWithText("You said you'll try it").assertIsDisplayed()
        compose.onNodeWithText("I'll try it").assertDoesNotExist()
    }
}
