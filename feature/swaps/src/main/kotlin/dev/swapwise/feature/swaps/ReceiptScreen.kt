package dev.swapwise.feature.swaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapwise.data.SwapCardModel
import dev.swapwise.data.db.ReceiptStatus
import dev.swapwise.data.db.SuggestionStatus
import dev.swapwise.domain.FoodCategory

@Composable
fun ReceiptRoute(viewModel: ReceiptViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReceiptScreen(
        state = state,
        onAccept = { viewModel.accept(it) },
        onDismiss = { viewModel.dismiss(it) },
        onRetry = { viewModel.retryParse() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScreen(state: ReceiptUiState, onAccept: (String) -> Unit, onDismiss: (String) -> Unit, onRetry: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.receipt_title)) }) }) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.status) {
                ReceiptStatus.PENDING_PARSE, null -> item { Text(stringResource(R.string.receipt_pending)) }
                ReceiptStatus.PARSE_FAILED -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.receipt_failed))
                        Button(onClick = onRetry) { Text(stringResource(R.string.receipt_retry)) }
                    }
                }
                ReceiptStatus.PARSED -> {
                    item { Text(stringResource(R.string.receipt_swaps), style = MaterialTheme.typography.titleLarge) }
                    when {
                        state.waitingForFactors -> item { Text(stringResource(R.string.receipt_waiting_factors)) }
                        state.cards.isEmpty() -> item { Text(stringResource(R.string.receipt_no_swaps)) }
                        else -> {
                            items(state.cards, key = { it.id }) { card -> SwapCard(card, onAccept, onDismiss) }
                            item { Text(stringResource(R.string.card_estimate_note), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    item { Text(stringResource(R.string.receipt_items), style = MaterialTheme.typography.titleLarge) }
                    items(state.items, key = { it.lineIndex }) { row -> ItemRowView(row) }
                }
            }
        }
    }
}

@Composable
internal fun SwapCard(card: SwapCardModel, onAccept: (String) -> Unit, onDismiss: (String) -> Unit) {
    val saves = stringResource(R.string.card_saves, Format.kg(card.kgCo2eSaved))
    val price = when (val change = Format.priceChange(card.costDeltaCents)) {
        is Format.PriceChange.Cheaper -> stringResource(R.string.card_cheaper, change.amount)
        is Format.PriceChange.MoreExpensive -> stringResource(R.string.card_more, change.amount)
        Format.PriceChange.Same -> stringResource(R.string.card_same_price)
    }
    Card(Modifier.fillMaxWidth().testTag("swap-card-${card.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.swaps.size > 1) Text(stringResource(R.string.card_bundle), style = MaterialTheme.typography.labelLarge)
            card.swaps.forEach { swap ->
                val to = FoodCategory.fromCode(swap.toCategory)?.label ?: swap.toCategory
                Text(stringResource(R.string.card_swap, swap.fromName, to), style = MaterialTheme.typography.titleMedium)
            }
            // Read as one sentence by TalkBack; meaning never depends on color.
            Text("$saves · $price", modifier = Modifier.semantics { contentDescription = "$saves, $price" })
            when (card.status) {
                SuggestionStatus.SHOWN -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAccept(card.id) }) { Text(stringResource(R.string.card_accept)) }
                    OutlinedButton(onClick = { onDismiss(card.id) }) { Text(stringResource(R.string.card_dismiss)) }
                }
                SuggestionStatus.ACCEPTED -> Text(stringResource(R.string.card_accepted), style = MaterialTheme.typography.labelLarge)
                SuggestionStatus.DISMISSED -> Text(stringResource(R.string.card_dismissed), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ItemRowView(row: ItemRow) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            row.category?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) }
        }
        row.priceCents?.let { Text(Format.money(it)) }
    }
}
