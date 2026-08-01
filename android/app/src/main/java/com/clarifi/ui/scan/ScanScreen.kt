package com.clarifi.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.ui.components.EmptyState
import com.clarifi.ui.components.rememberBarAwareScrollState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.nav.Destination
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette
import com.clarifi.ui.transactions.TransactionsViewModel
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Destination) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ScanViewModel = containerViewModel {
        ScanViewModel(it.receiptScanner, it.secrets, it.accounts)
    }
    val movements: TransactionsViewModel = containerViewModel {
        TransactionsViewModel(it.accounts, it.txns)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshKey() }
    LaunchedEffect(movements) {
        movements.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::analyse) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.openCamera()
    }

    fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.openCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // The camera takes the whole screen; everything else scrolls under the chrome.
    if (state.stage is ScanStage.Capturing) {
        CameraCapture(
            onCaptured = viewModel::analyse,
            onCancel = viewModel::reset,
            onError = { message ->
                viewModel.reset()
                // Surfaced immediately: a camera that will not open is not a scan failure.
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberBarAwareScrollState())
            .padding(horizontal = 16.dp),
    ) {
        when (val stage = state.stage) {
            is ScanStage.Idle -> {
                if (!state.hasApiKey) {
                    EmptyState(
                        icon = ClariFiIcons.Scan,
                        title = "Scanning needs an AI key",
                        message = "ClariFi sends the photo to the provider you choose (Groq, " +
                            "Gemini or Claude) and reads the total, date, merchant and category " +
                            "off it. Add your key to get started.",
                        actionLabel = "Open Settings",
                        onAction = { onNavigate(Destination.Settings) },
                    )
                } else if (state.accounts.isEmpty()) {
                    EmptyState(
                        icon = ClariFiIcons.Wallet,
                        title = "No accounts yet",
                        message = "A scanned receipt has to be filed somewhere. Add an account first.",
                        actionLabel = "Go to Accounts",
                        onAction = { onNavigate(Destination.Accounts) },
                    )
                } else {
                    ScanIntro(
                        onCamera = ::requestCamera,
                        onGallery = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                }
            }

            is ScanStage.Analysing -> AnalysingState()

            is ScanStage.Review -> ReceiptReview(
                fields = stage.fields,
                accounts = state.accounts,
                suggestedAccountId = stage.suggestedAccountId,
                onCancel = viewModel::reset,
                onSave = { type, accountId, amount, date, description, category ->
                    movements.add(type, accountId, amount, date, description, category)
                    viewModel.reset()
                },
            )

            is ScanStage.Failed -> EmptyState(
                icon = ClariFiIcons.Scan,
                title = "That didn't work",
                message = stage.message,
                actionLabel = "Try again",
                onAction = viewModel::reset,
            )

            is ScanStage.Capturing -> Unit
        }
    }
}

@Composable
private fun ScanIntro(onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = ClariFiIcons.Scan,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text("Scan a receipt", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Take a photo and ClariFi reads the total, date, merchant and category. " +
                "You review everything before it is saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = clarifiPalette.textMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onCamera,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Icon(ClariFiIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text("Take a photo", style = MaterialTheme.typography.labelLarge)
        }

        OutlinedButton(
            onClick = onGallery,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Icon(ClariFiIcons.Gallery, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text("Choose an existing photo", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AnalysingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text("Reading the receipt…", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "This usually takes a few seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = clarifiPalette.textMuted,
            )
        }
    }
}
