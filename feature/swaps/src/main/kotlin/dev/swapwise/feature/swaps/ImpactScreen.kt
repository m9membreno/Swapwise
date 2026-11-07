package dev.swapwise.feature.swaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.swapwise.domain.ImpactStats
import kotlin.math.roundToInt

@Composable
fun ImpactRoute(viewModel: ImpactViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    ImpactScreen(stats)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpactScreen(stats: ImpactStats?) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.impact_title)) }) }) { padding ->
        if (stats == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stats.shown == 0) {
                Text(stringResource(R.string.impact_empty), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Stat(
                value = stringResource(R.string.impact_kg_value, Format.kg(stats.kgCo2eVerified)),
                label = stringResource(R.string.impact_kg_verified),
            )
            Stat(
                value = Format.money(stats.centsSavedVerified),
                label = stringResource(R.string.impact_money_verified),
            )
            Stat(
                value = stringResource(R.string.impact_count_of, stats.accepted, stats.accepted + stats.dismissed),
                label = stringResource(R.string.impact_accepted) + (stats.acceptanceRate?.let { " (${percent(it)})" } ?: ""),
            )
            Stat(
                value = stats.followThroughRate?.let { percent(it) } ?: "–",
                label = stringResource(R.string.impact_follow_through),
            )
            Text(
                stringResource(
                    R.string.impact_breakdown,
                    stats.verifiedNew,
                    stats.verifiedAlreadyBought,
                    stats.pending,
                    Format.kg(stats.kgCo2eAccepted),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.impact_method), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"
