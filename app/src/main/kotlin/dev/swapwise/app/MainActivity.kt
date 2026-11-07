package dev.swapwise.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.swapwise.feature.scan.ScanRoute
import dev.swapwise.feature.swaps.ImpactRoute
import dev.swapwise.feature.swaps.LedgerRoute
import dev.swapwise.feature.swaps.ReceiptRoute
import kotlinx.serialization.Serializable

/** Type-safe routes. The property name in [Receipt] is the SavedStateHandle key ReceiptViewModel reads. */
@Serializable
data object Ledger

@Serializable
data object Scan

@Serializable
data class Receipt(val receiptId: String)

@Serializable
data object Impact

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SwapwiseTheme { SwapwiseNavHost() } }
    }
}

@Composable
private fun SwapwiseNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Ledger) {
        composable<Ledger> {
            LedgerRoute(
                onScan = { nav.navigate(Scan) },
                onOpenReceipt = { id -> nav.navigate(Receipt(id)) },
                onOpenImpact = { nav.navigate(Impact) },
            )
        }
        composable<Scan> {
            ScanRoute(
                onReceiptSaved = { id ->
                    // Replace the scan screen, so Back from the receipt goes to the ledger, not the camera.
                    nav.navigate(Receipt(id)) { popUpTo<Scan> { inclusive = true } }
                },
            )
        }
        composable<Receipt> { ReceiptRoute() }
        composable<Impact> { ImpactRoute() }
    }
}

@Composable
private fun SwapwiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}
