package com.clarifi.ui.accounts

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.core.money.Money
import com.clarifi.data.db.Account
import com.clarifi.ui.components.AccountAvatar
import com.clarifi.ui.components.AnimatedAmount
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.ConfirmDialog
import com.clarifi.ui.components.EmptyState
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.rememberBarAwareLazyListState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette

/** What the screen is currently asking the user to confirm, if anything. */
private sealed interface PendingAction {
    data class Archive(val account: Account) : PendingAction
    data class Delete(val account: Account) : PendingAction
}

@Composable
fun AccountsScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val viewModel: AccountsViewModel = containerViewModel { AccountsViewModel(it.accounts) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var editorFor by remember { mutableStateOf<Account?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
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
        item { SectionHeader("Active") }

        if (state.loaded && state.active.isEmpty()) {
            item {
                EmptyState(
                    icon = ClariFiIcons.Wallet,
                    title = "No active accounts",
                    message = "Add the bank accounts, wallets or cards you want to track.",
                    actionLabel = "Add an account",
                    onAction = {
                        editorFor = null
                        editorOpen = true
                    },
                )
            }
        }

        items(state.active, key = { it.id }) { account ->
            AccountRow(
                account = account,
                onEdit = {
                    editorFor = account
                    editorOpen = true
                },
                menuItems = listOf(
                    MenuAction("Archive", ClariFiIcons.Archive) { pending = PendingAction.Archive(account) },
                ),
            )
        }

        item {
            OutlinedButton(
                onClick = {
                    editorFor = null
                    editorOpen = true
                },
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(top = 4.dp),
            ) {
                Icon(ClariFiIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add account", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (state.archived.isNotEmpty()) {
            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader("Archived") }

            items(state.archived, key = { it.id }) { account ->
                AccountRow(
                    account = account,
                    dimmed = true,
                    onEdit = null,
                    menuItems = listOf(
                        MenuAction("Restore", ClariFiIcons.Restore) { viewModel.restore(account.id) },
                        MenuAction("Delete permanently", ClariFiIcons.Delete, danger = true) {
                            pending = PendingAction.Delete(account)
                        },
                    ),
                )
            }
        }
    }

    if (editorOpen) {
        AccountEditorSheet(
            editing = editorFor,
            onDismiss = { editorOpen = false },
            onSave = { bank, currency, balance, color ->
                viewModel.save(editorFor?.id, bank, currency, balance, color)
                editorOpen = false
            },
        )
    }

    when (val action = pending) {
        is PendingAction.Archive -> ConfirmDialog(
            title = "Archive account?",
            message = "${action.account.bank} will be hidden, but its balance and history are kept. " +
                "You can restore it at any time.",
            confirmLabel = "Archive",
            danger = false,
            onConfirm = {
                viewModel.archive(action.account.id)
                pending = null
            },
            onDismiss = { pending = null },
        )

        is PendingAction.Delete -> ConfirmDialog(
            title = "Delete permanently?",
            message = "This deletes ${action.account.bank} along with every transaction and fixed " +
                "payment attached to it. This cannot be undone.",
            confirmLabel = "Delete forever",
            onConfirm = {
                viewModel.permanentDelete(action.account.id)
                pending = null
            },
            onDismiss = { pending = null },
        )

        null -> Unit
    }
}

private data class MenuAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun AccountRow(
    account: Account,
    menuItems: List<MenuAction>,
    onEdit: (() -> Unit)?,
    dimmed: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val contentAlpha = if (dimmed) 0.55f else 1f

    ClariFiCard(
        contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        modifier = if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(account = account, modifier = Modifier.alpha(contentAlpha))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .alpha(contentAlpha),
            ) {
                Text(
                    text = account.bank,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${account.currencyMeta.name} · ${account.currencyMeta.code}",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarifiPalette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End, modifier = Modifier.alpha(contentAlpha)) {
                AnimatedAmount(
                    currency = account.currencyMeta,
                    amount = account.balance,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (account.balance < 0) clarifiPalette.red else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "current balance",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarifiPalette.textMuted,
                )
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = ClariFiIcons.More,
                        contentDescription = "More options for ${account.bank}",
                        modifier = Modifier.size(18.dp),
                        tint = clarifiPalette.textMuted,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    onEdit?.let {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = {
                                Icon(ClariFiIcons.Edit, null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuOpen = false
                                it()
                            },
                        )
                    }
                    menuItems.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = action.label,
                                    color = if (action.danger) clarifiPalette.red else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (action.danger) clarifiPalette.red else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}
