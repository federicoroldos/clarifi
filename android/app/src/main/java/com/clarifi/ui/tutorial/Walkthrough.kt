package com.clarifi.ui.tutorial

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clarifi.ui.components.ClariFiWordmark
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.Motion
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette
import kotlinx.coroutines.launch

/**
 * One page of the walkthrough: what a part of the app is for, then the taps and
 * swipes that are not visible until someone tries them.
 */
private data class TutorialPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val tips: List<String> = emptyList(),
    /** The first page leads with the wordmark instead of an icon. */
    val wordmark: Boolean = false,
)

/**
 * The text is the desktop's where the desktop has any, and describes only
 * behaviour that exists: every gesture named below is one the app really handles.
 */
private val Pages = listOf(
    TutorialPage(
        icon = ClariFiIcons.Help,
        wordmark = true,
        title = "Your money, on your phone",
        body = "ClariFi keeps a full ledger of what you have, what you spend and what " +
            "repeats every month. Everything lives in this device's own database. " +
            "Nothing leaves it unless you ask it to.",
        tips = listOf(
            "Swipe left to read on, or use Next",
            "You can reopen this from the menu at any time",
        ),
    ),
    TutorialPage(
        icon = ClariFiIcons.Menu,
        title = "Getting around",
        body = "The bar along the bottom holds the five screens you use daily. The menu " +
            "in the top left corner holds the rest: statement import, settings and about.",
        tips = listOf(
            "The bottom bar slides away as you scroll down and returns as you scroll up",
            "Swipe in from the left edge to open the menu",
            "On the Dashboard, touch a chart to read the value under your finger",
        ),
    ),
    TutorialPage(
        icon = ClariFiIcons.Accounts,
        title = "Start with an account",
        body = "One account per card, bank or wallet, each with its own currency and " +
            "colour. Its balance is the number every other screen is built from.",
        tips = listOf(
            "Tap an account to rename it, recolour it or correct its balance",
            "Archiving hides an account and keeps its history; only an archived " +
                "account can then be deleted for good",
        ),
    ),
    TutorialPage(
        icon = ClariFiIcons.Plus,
        title = "Logging money",
        body = "The + button on the Dashboard and on Activity opens one sheet for all " +
            "three kinds: an expense, an income, or a transfer between two of your " +
            "own accounts. A transfer across currencies asks for both amounts and " +
            "works out the rate.",
        tips = listOf(
            "The balance changes the moment you save, whatever date is on the row",
            "A category is only asked for on expenses; income always files as Others",
        ),
    ),
    TutorialPage(
        icon = ClariFiIcons.Transactions,
        title = "Activity",
        body = "Every movement, newest first, grouped by day. The chips filter by type, " +
            "and Filters adds account, category, date range and amount range.",
        tips = listOf(
            "Tap a row to edit it",
            "Swipe a row from right to left to delete it",
            "Transfers cannot be edited: deleting either leg removes both",
        ),
    ),
    TutorialPage(
        icon = ClariFiIcons.Fixed,
        title = "Fixed transactions",
        body = "Rent, a subscription, a salary: set the amount and the day of the month " +
            "and ClariFi tells you when it comes due, and reminds you in the " +
            "notification shade.",
        tips = listOf(
            "One tap marks it paid, which writes the transaction and moves the balance",
            "The same button undoes it if you tapped too early",
            "A day the month does not have falls due on that month's last day",
        ),
    ),
    TutorialPage(
        icon = ClariFiIcons.Scan,
        title = "Scan and import",
        body = "With an AI key saved in Settings, photograph a receipt and the total, " +
            "date, merchant and category are read off it. A bank statement can be " +
            "imported the same way, row by row.",
        tips = listOf(
            "Nothing is saved until you have reviewed and confirmed it",
            "Groq, Gemini and Claude all work; Settings links to each one's key page",
        ),
    ),
    TutorialPage(
        icon = ClariFiIcons.Cloud,
        title = "Backups and sync",
        body = "Settings exports everything as a JSON file you keep, and can connect a " +
            "Supabase database shared with the desktop app.",
        tips = listOf(
            "The phone always works on its local data; the cloud is only touched on " +
                "Push or Pull",
            "Push overwrites the cloud, Pull overwrites this phone. Neither is automatic",
        ),
    ),
)

/**
 * The first-run tour, and the one the menu reopens.
 *
 * A pager rather than coach marks pinned to real controls: the marks would have to
 * drive navigation to reach each screen they point at, and every one of them would
 * be a second place that has to be moved whenever the screen it points at changes.
 */
@Composable
fun Walkthrough(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { Pages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == Pages.lastIndex

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                // Always offered. A tour nobody can leave is worse than no tour.
                TextButton(onClick = onFinish) {
                    Text(if (lastPage) "Close" else "Skip", color = clarifiPalette.textMuted)
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { index ->
                PageBody(page = Pages[index])
            }

            Dots(count = Pages.size, current = pagerState.currentPage)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    enabled = pagerState.currentPage > 0,
                ) {
                    Text("Back")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (lastPage) {
                            onFinish()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    shape = PillShape,
                    modifier = Modifier.height(50.dp),
                ) {
                    Text(
                        text = if (lastPage) "Start using ClariFi" else "Next",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageBody(page: TutorialPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (page.wordmark) {
            ClariFiWordmark()
            Spacer(Modifier.height(22.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
        }

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = clarifiPalette.textMuted,
            textAlign = TextAlign.Center,
        )

        if (page.tips.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                page.tips.forEach { tip ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            imageVector = ClariFiIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(16.dp),
                        )
                        Text(
                            text = tip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The page indicator: the current one stretches into a pill rather than growing. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(
                targetValue = if (selected) 20.dp else 7.dp,
                animationSpec = Motion.quick(),
                label = "dotWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                animationSpec = Motion.quick(),
                label = "dotColor",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(7.dp)
                    .width(width)
                    .background(color, CircleShape)
            )
        }
    }
}
