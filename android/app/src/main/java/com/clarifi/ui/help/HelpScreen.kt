package com.clarifi.ui.help

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.rememberBarAwareScrollState
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.clarifiPalette

/**
 * The guided tour in writing.
 *
 * The tour only ever runs once, on a fresh install, and it can be skipped in one
 * tap - so everything it says has to live somewhere a user can go back to. Each
 * entry keeps the tour's shape: a title naming the thing, and one line saying what
 * it does. Anything longer belongs in the app itself, not here.
 *
 * The topics the tour covers are worded the same way in both places on purpose.
 * Change one and change the other.
 *
 * One card per area rather than one per entry: twenty separate cards read as a
 * wall, and the thing someone is looking for is the area, not the sentence.
 */
private data class HelpEntry(val title: String, val body: String)

private data class HelpTopic(
    val header: String,
    val icon: ImageVector,
    val entries: List<HelpEntry>,
)

private val Topics = listOf(
    HelpTopic(
        header = "The basics",
        icon = ClariFiIcons.Wallet,
        entries = listOf(
            HelpEntry(
                "Accounts",
                "Every movement belongs to an account. Add one per card, bank or wallet.",
            ),
            HelpEntry(
                "Log your movements",
                "Expenses, income and transfers are recorded pressing the + button.",
            ),
            HelpEntry(
                "Transfers",
                "A transfer moves money between two of your own accounts. If they hold " +
                    "different currencies, enter both amounts and the rate is worked out.",
            ),
            HelpEntry(
                "Balances",
                "A balance changes as soon as you save, whatever date the movement carries.",
            ),
        ),
    ),
    HelpTopic(
        header = "Activity",
        icon = ClariFiIcons.Transactions,
        entries = listOf(
            HelpEntry(
                "Check your movements",
                "Every movement is recorded in Activity, newest first.",
            ),
            HelpEntry("Tap to edit", "Tap any entry to modify it."),
            HelpEntry(
                "Swipe to delete",
                "Swipe any entry to the left to delete it. The message that follows undoes it.",
            ),
            HelpEntry(
                "Find an old one",
                "Filter by type with the chips, or open Filters for account, category, " +
                    "dates and amounts.",
            ),
            HelpEntry(
                "Transfers are a pair",
                "Deleting either half deletes both. To change one, delete it and record it again.",
            ),
        ),
    ),
    HelpTopic(
        header = "Recurring movements",
        icon = ClariFiIcons.Fixed,
        entries = listOf(
            HelpEntry(
                "Recurring movements",
                "Add and manage recurring expenses or incomes.",
            ),
            HelpEntry(
                "Log its payment",
                "Tap the button to log the execution of that recurring payment. Tap it " +
                    "again to undo it.",
            ),
            HelpEntry(
                "Day of the month",
                "A recurring movement set to a day the month does not have falls due on " +
                    "that month's last day.",
            ),
            HelpEntry(
                "Reminders",
                "A notification tells you when one comes due, and logs it from the shade.",
            ),
        ),
    ),
    HelpTopic(
        header = "AI",
        icon = ClariFiIcons.Scan,
        entries = listOf(
            HelpEntry(
                "Scanning",
                "Scan a receipt and have AI fill in the movement register form.",
            ),
            HelpEntry(
                "Statement imports",
                "Import a bank statement and pick which movements to keep.",
            ),
            HelpEntry(
                "API key",
                "Both need an AI API key, set in Settings. Groq, Gemini and Claude all work.",
            ),
            HelpEntry(
                "What is sent",
                "Only the receipt or statement you choose, to the provider your key belongs " +
                    "to. Nothing is saved until you have checked it.",
            ),
        ),
    ),
    HelpTopic(
        header = "Your data",
        icon = ClariFiIcons.Cloud,
        entries = listOf(
            HelpEntry(
                "It stays on the phone",
                "Accounts, movements and recurring movements live in this device's own " +
                    "database, not on a server.",
            ),
            HelpEntry(
                "Exports",
                "Export a JSON backup of everything, and restore it on any device.",
            ),
            HelpEntry(
                "Cloud sync",
                "Connect a Supabase database to share your data with the desktop app. Push " +
                    "sends this phone's data up, Pull brings the cloud's down.",
            ),
            HelpEntry(
                "Clear data",
                "Clear All Data erases every account, movement and recurring movement on " +
                    "this phone. It cannot be undone.",
            ),
        ),
    ),
)

@Composable
fun HelpScreen(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberBarAwareScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Topics.forEach { topic ->
            ClariFiCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = topic.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Text(
                        text = topic.header,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }

                topic.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 14.dp),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.textMuted,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}
