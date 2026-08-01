package com.clarifi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.ConfirmDialog
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette

/**
 * The desktop's Cloud Sync panel: connect a Supabase project, then move the
 * whole database up or down by hand.
 *
 * Same connection string as the desktop, same tables, same manual Push and Pull.
 */
@Composable
fun CloudCard(
    state: CloudUiState,
    onConnect: (String) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onForget: () -> Unit,
) {
    var dsn by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var confirmPush by remember { mutableStateOf(false) }
    var confirmPull by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    ClariFiCard {
        Text("Cloud database", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        // The desktop's wording, with its emphasis: the accent marks the service,
        // the bold marks the two actions that are the whole feature.
        CloudBody(
            buildAnnotatedString {
                append("Keep a copy of your data in a ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Supabase") }
                append(
                    " Postgres database to sync across devices. ClariFi always works on the " +
                        "fast local database on this device; the cloud is only touched when you "
                )
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Push") }
                append(" or ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Pull") }
                append(".")
            }
        )
        Spacer(Modifier.height(10.dp))
        CloudBody(
            buildAnnotatedString {
                append(
                    "Paste the connection string and save it, then push your local data up or " +
                        "pull the cloud data down whenever you want. Use Supabase's "
                )
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Session pooler") }
                append(" string (Get connected → Direct → Session pooler), not the default one.")
            }
        )

        if (!state.configured) {
            Spacer(Modifier.height(8.dp))

            // Label above the field, like the AI key and like the desktop's
            // `<label class="fl">`, so it stays readable once the field is filled.
            SectionHeader("Connection string")
            OutlinedTextField(
                value = dsn,
                onValueChange = { dsn = it },
                singleLine = true,
                visualTransformation = if (revealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(
                            text = if (revealed) "Hide" else "Show",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConnect(dsn) },
                enabled = dsn.isNotBlank() && !state.busy,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Checking…")
                } else {
                    Text("Connect", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            Spacer(Modifier.height(14.dp))
            StatusRow("Database", state.host)
            StatusRow("Last push", state.lastPush ?: "never")
            StatusRow("Last pull", state.lastPull ?: "never")

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { confirmPush = true },
                    enabled = !state.busy,
                    shape = PillShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(ClariFiIcons.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Push")
                }
                OutlinedButton(
                    onClick = { confirmPull = true },
                    enabled = !state.busy,
                    shape = PillShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(ClariFiIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Pull")
                }
            }

            if (state.busy) {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Syncing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.textMuted,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            // Same shape as Clear All Data, without the bin: disconnecting throws
            // away a credential, not the data.
            OutlinedButton(
                onClick = { confirmForget = true },
                enabled = !state.busy,
                shape = PillShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = clarifiPalette.red),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Disconnect", style = MaterialTheme.typography.labelLarge)
            }
        }

        state.error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = clarifiPalette.red,
            )
        }
    }

    if (confirmPush) {
        ConfirmDialog(
            title = "Push to the cloud?",
            message = "Everything in the cloud is replaced by what is on this phone. If another " +
                "device pushed something newer, it is lost.",
            confirmLabel = "Push",
            onConfirm = {
                confirmPush = false
                onPush()
            },
            onDismiss = { confirmPush = false },
        )
    }

    if (confirmPull) {
        ConfirmDialog(
            title = "Pull from the cloud?",
            message = "Everything on this phone is replaced by what is in the cloud. A copy of " +
                "the current data is saved on the device first.",
            confirmLabel = "Pull",
            onConfirm = {
                confirmPull = false
                onPull()
            },
            onDismiss = { confirmPull = false },
        )
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Disconnect from the cloud?",
            message = "The connection string is removed from this device. Your data stays both " +
                "here and in the cloud.",
            confirmLabel = "Disconnect",
            onConfirm = {
                confirmForget = false
                onForget()
            },
            onDismiss = { confirmForget = false },
        )
    }
}

/** The desktop's `card-bd` paragraph. */
@Composable
private fun CloudBody(text: AnnotatedString) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = clarifiPalette.textMuted,
        lineHeight = 20.sp,
    )
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = clarifiPalette.textMuted)
        // The host is long enough to wrap; without a weight it grew under the label
        // instead of beside it.
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
