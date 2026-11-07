package dev.swapwise.feature.swaps

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapwise.data.db.ReceiptStatus
import dev.swapwise.data.db.ReceiptSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun LedgerRoute(onScan: () -> Unit, onOpenReceipt: (String) -> Unit, viewModel: LedgerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LedgerScreen(state, onScan, onOpenReceipt, viewModel::answerShareStats)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    state: LedgerUiState,
    onScan: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    onAnswerShareStats: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.ledger_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onScan) { Text(stringResource(R.string.ledger_scan)) }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.askToShareStats) item { ShareStatsCard(onAnswerShareStats) }
                if (state.receipts.isEmpty()) {
                    item { Text(stringResource(R.string.ledger_empty), style = MaterialTheme.typography.bodyLarge) }
                }
                items(state.receipts, key = { it.id }) { receipt -> ReceiptRow(receipt, onClick = { onOpenReceipt(receipt.id) }) }
            }
        }
    }
}

@Composable
private fun ShareStatsCard(onAnswer: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.share_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.share_no)) }
                TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.share_yes)) }
            }
        }
    }
}

@Composable
private fun ReceiptRow(receipt: ReceiptSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(receipt.scannedAtMillis)), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when (receipt.status) {
                        ReceiptStatus.PENDING_PARSE -> stringResource(R.string.status_pending)
                        ReceiptStatus.PARSE_FAILED -> stringResource(R.string.status_failed)
                        ReceiptStatus.PARSED -> pluralStringResource(R.plurals.ledger_items, receipt.itemCount, receipt.itemCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            receipt.totalCents?.let { Text(Format.money(it), style = MaterialTheme.typography.titleMedium) }
        }
    }
}
