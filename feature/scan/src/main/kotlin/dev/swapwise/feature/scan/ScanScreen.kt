package dev.swapwise.feature.scan

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@Composable
fun ScanRoute(onReceiptSaved: (String) -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        (state as? ScanUiState.Saved)?.let {
            onReceiptSaved(it.receiptId)
            viewModel.reset()
        }
    }
    ScanScreen(state = state, onImage = { uri -> viewModel.onImage(uri.toString()) })
}

/**
 * Two ways in, both without the CAMERA permission: the system camera app (it holds the permission, we only
 * receive the photo) and the photo picker (no storage permission either). See ADR-005.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(state: ScanUiState, onImage: (Uri) -> Unit) {
    val context = LocalContext.current
    // Survives rotation: the camera app may take long enough for the activity to be recreated.
    var pendingPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhoto
        if (saved && uri != null) onImage(uri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImage)
    }
    val reading = state == ScanUiState.Reading

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.scan_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = {
                    val uri = newPhotoUri(context)
                    pendingPhoto = uri
                    takePicture.launch(uri)
                },
                enabled = !reading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scan_take_photo)) }
            OutlinedButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !reading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scan_choose_photo)) }

            when (state) {
                ScanUiState.Reading -> CircularProgressIndicator()
                is ScanUiState.Error -> Text(
                    text = stringResource(
                        if (state.reason == ScanUiState.Reason.NO_TEXT) R.string.scan_error_no_text else R.string.scan_error_failed,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }
}

/** A new file in the app's private cache for the camera app to write into, shared through our FileProvider. */
private fun newPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
    val file = File.createTempFile("receipt_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
