package com.clarifi.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.core.time.Dates
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.ConfirmDialog
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.rememberBarAwareScrollState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.ThemeMode
import com.clarifi.ui.theme.clarifiPalette

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val viewModel: SettingsViewModel = containerViewModel { container ->
        SettingsViewModel(
            context = container.appContext,
            settings = container.settings,
            secrets = container.secrets,
            aiClient = container.aiClient,
            backup = container.jsonBackup,
            database = container.database,
        )
    }
    val cloudViewModel: CloudViewModel = containerViewModel { CloudViewModel(it.cloud) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cloudState by cloudViewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(cloudViewModel) {
        cloudViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::export) }

    // Picking the file only queues it; the desktop asks before overwriting and so
    // does this, because import replaces everything on the device.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImport = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberBarAwareScrollState())
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Appearance")
        ClariFiCard {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
        }

        // Two cards with the buttons paired, which is the shape that works on a
        // phone, carrying the desktop's wording. The desktop now uses this shape too.
        SectionHeader("Data")
        ClariFiCard {
            CardTitle("Import / Export")
            CardBody(
                buildAnnotatedString {
                    append(
                        "Download a complete backup of all accounts, transactions, fixed " +
                            "transactions and settings as a JSON file."
                    )
                }
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("clarifi-${Dates.today()}.json") },
                    enabled = !state.busy,
                    shape = PillShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(ClariFiIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Export JSON")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !state.busy,
                    shape = PillShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(ClariFiIcons.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Choose File")
                }
            }
            CardBody(
                buildAnnotatedString {
                    append("Restoring from a JSON backup ")
                    withStyle(SpanStyle(color = clarifiPalette.red, fontWeight = FontWeight.SemiBold)) {
                        append("will overwrite all current data.")
                    }
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        ClariFiCard {
            CardTitle("Clear Data")
            CardBody(
                buildAnnotatedString {
                    append("Permanently erase all accounts, transactions, and fixed transactions. ")
                    withStyle(SpanStyle(color = clarifiPalette.red, fontWeight = FontWeight.SemiBold)) {
                        append("This cannot be undone.")
                    }
                }
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !state.busy,
                shape = PillShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = clarifiPalette.red),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(ClariFiIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Clear All Data")
            }
        }

        SectionHeader("AI")
        AiKeyCard(state = state, onSave = viewModel::saveApiKey, onRemove = viewModel::removeApiKey)

        SectionHeader("Cloud Sync")
        CloudCard(
            state = cloudState,
            onConnect = cloudViewModel::connect,
            onPush = cloudViewModel::push,
            onPull = cloudViewModel::pull,
            onForget = cloudViewModel::forget,
        )

        Spacer(Modifier.height(28.dp))
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = "Import data?",
            message = "This will overwrite ALL current accounts, transactions, and fixed transactions.",
            confirmLabel = "Overwrite",
            onConfirm = {
                viewModel.import(uri)
                pendingImport = null
            },
            onDismiss = { pendingImport = null },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear all data?",
            message = "Every account, transaction and fixed transaction on this device is deleted. " +
                "This cannot be undone, so export a backup first if you are unsure.",
            confirmLabel = "Delete everything",
            onConfirm = {
                viewModel.clearAll()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** The desktop's `card-hd-title`. */
@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
}

/** The desktop's `card-bd` paragraph. */
@Composable
private fun CardBody(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = clarifiPalette.textMuted,
        lineHeight = 20.sp,
        modifier = modifier,
    )
}

@Composable
private fun AiKeyCard(
    state: SettingsUiState,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    ClariFiCard {
        CardTitle("AI Key")
        CardBody(
            buildAnnotatedString {
                append(
                    "Required for receipt scanning and bank-statement import. Paste an AI API " +
                        "key and ClariFi will detect the provider automatically. Feel free to " +
                        "use "
                )
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Groq") }
                append(", ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Gemini") }
                append(" or ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Claude") }
                append(".")
            }
        )
        Spacer(Modifier.height(12.dp))
        CardBody(
            buildAnnotatedString {
                append(
                    "The receipt and bank statement files are sent to your chosen provider, " +
                        "which reads them directly for the best accuracy. Without a key, these " +
                        "two features are unavailable."
                )
            }
        )
        Spacer(Modifier.height(16.dp))

        SectionHeader("AI API key")
        OutlinedTextField(
            // A stored key is shown the way the desktop shows it: masked, read-only,
            // and cleared through Remove rather than edited in place.
            value = if (state.hasApiKey) "••••••••••••${state.keyHint}" else draft,
            onValueChange = { if (!state.hasApiKey) draft = it },
            readOnly = state.hasApiKey,
            singleLine = true,
            visualTransformation = if (revealed || state.hasApiKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = if (state.hasApiKey) {
                null
            } else {
                {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(if (revealed) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onSave(draft) },
                enabled = draft.isNotBlank() && !state.hasApiKey && !state.verifying,
                shape = PillShape,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                if (state.verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Checking…")
                } else {
                    Text("Save Key", style = MaterialTheme.typography.labelLarge)
                }
            }
            OutlinedButton(
                onClick = onRemove,
                enabled = state.hasApiKey,
                shape = PillShape,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Text("Remove", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            text = if (state.hasApiKey) {
                "✓ API key saved. AI features will use ${state.provider?.label ?: "AI"}."
            } else {
                "No API key saved. Receipt scanning and statement import both need a key."
            },
            style = MaterialTheme.typography.bodySmall,
            color = clarifiPalette.textMuted,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}
