package com.clarifi.ui.fixed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.core.money.Money
import com.clarifi.core.time.Dates
import com.clarifi.data.repo.FixedPaymentView
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.ConfirmDialog
import com.clarifi.ui.components.EmptyState
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.rememberBarAwareLazyListState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette

@Composable
fun FixedScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val viewModel: FixedViewModel = containerViewModel { FixedViewModel(it.fixed, it.accounts) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<FixedPaymentView?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FixedPaymentView?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    NotificationPermissionPrompt(hasPayments = !state.isEmpty)

    fun openEditor(view: FixedPaymentView?) {
        editing = view
        editorOpen = true
    }

    LazyColumn(
        state = rememberBarAwareLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.loaded && state.isEmpty) {
            item {
                EmptyState(
                    icon = ClariFiIcons.Fixed,
                    title = "No fixed transactions",
                    message = "Set up the payments and income that repeat every month (rent, " +
                        "subscriptions, your salary) and apply them in one tap.",
                    actionLabel = "Add the first one",
                    onAction = { openEditor(null) },
                )
            }
        }

        if (state.due.isNotEmpty()) {
            item { SectionHeader("Due this month") }
            items(state.due, key = { "due-${it.id}" }) { view ->
                FixedRow(
                    view = view,
                    onApply = { viewModel.apply(view) },
                    onUndo = { viewModel.undo(view) },
                    onEdit = { openEditor(view) },
                    onDelete = { pendingDelete = view },
                )
            }
        }

        if (state.rest.isNotEmpty()) {
            item {
                SectionHeader(
                    title = if (state.due.isEmpty()) "Scheduled" else "Rest of the month",
                    modifier = Modifier.padding(top = if (state.due.isEmpty()) 0.dp else 12.dp),
                )
            }
            items(state.rest, key = { "rest-${it.id}" }) { view ->
                FixedRow(
                    view = view,
                    onApply = { viewModel.apply(view) },
                    onUndo = { viewModel.undo(view) },
                    onEdit = { openEditor(view) },
                    onDelete = { pendingDelete = view },
                )
            }
        }

        if (!state.isEmpty) {
            item {
                OutlinedButton(
                    onClick = { openEditor(null) },
                    shape = PillShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(top = 4.dp),
                ) {
                    Icon(ClariFiIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Add fixed transaction", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (editorOpen) {
        FixedEditorSheet(
            editing = editing,
            accounts = state.accounts,
            onDismiss = { editorOpen = false },
            onSave = { name, amount, accountId, category, day, type ->
                viewModel.save(editing?.id, name, amount, accountId, category, day, type)
                editorOpen = false
            },
        )
    }

    pendingDelete?.let { view ->
        ConfirmDialog(
            title = "Delete ${view.name}?",
            message = "It stops repeating from now on. Transactions it already created are kept, " +
                "because that money did move.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(view.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * Asks for notification permission the first time there is something worth being
 * reminded about - not at app start, where the request has no context and gets
 * refused out of reflex.
 */
@Composable
private fun NotificationPermissionPrompt(hasPayments: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declining is fine: the whole screen still works, just without reminders. */ }

    LaunchedEffect(hasPayments, asked) {
        if (!hasPayments || asked) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        asked = true
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun FixedRow(
    view: FixedPaymentView,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = clarifiPalette
    var menuOpen by remember { mutableStateOf(false) }
    val accent = if (view.isIncome) palette.green else palette.red

    ClariFiCard(contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (view.isIncome) ClariFiIcons.Download else ClariFiIcons.Upload,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = view.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${view.account.bank} · ${Dates.ordinal(view.day)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = Money.format(view.currency, view.amount),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                maxLines = 1,
            )

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = ClariFiIcons.More,
                        contentDescription = "More options for ${view.name}",
                        modifier = Modifier.size(18.dp),
                        tint = palette.textMuted,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(ClariFiIcons.Edit, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = palette.red) },
                        leadingIcon = {
                            Icon(ClariFiIcons.Delete, null, modifier = Modifier.size(18.dp), tint = palette.red)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Applied and not-applied are the same one-tap toggle, so the button swaps
        // rather than the row growing an extra control.
        if (view.appliedThisMonth) {
            OutlinedButton(
                onClick = onUndo,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Icon(ClariFiIcons.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (view.isIncome) "Received this month · Undo" else "Paid this month · Undo",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        } else {
            Button(
                onClick = onApply,
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (view.dueThisMonth) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (view.dueThisMonth) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Text(
                    text = when {
                        view.dueThisMonth && view.isIncome -> "Receive now"
                        view.dueThisMonth -> "Pay now"
                        view.isIncome -> "Receive early"
                        else -> "Pay early"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
