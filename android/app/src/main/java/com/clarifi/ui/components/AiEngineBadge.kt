package com.clarifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clarifi.ui.theme.PillShape

/**
 * The desktop's `✦ Powered by <provider>` chip, cut down for a phone's app bar.
 *
 * "Powered by" nearly doubles the pill without adding anything: on the two screens
 * that carry it, the page already says a model is going to read the file. The
 * provider's name is the part that answers the question the badge exists for,
 * which is whose model is about to see my receipt.
 *
 * It only appears when a key is saved. The desktop also shows a warning version of
 * it, but here a screen with no key is already nothing but "Importing needs an AI
 * key" and a button to Settings, and a second warning on top of that is noise.
 */
@Composable
fun AiEngineBadge(provider: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text = "✦ $provider",
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        maxLines = 1,
        modifier = modifier
            // Sits as far from the right edge as the drawer handle does from the left:
            // the handle is a 48dp target around a 22dp icon, this is a bare pill.
            .padding(end = 14.dp)
            .background(accent.copy(alpha = 0.14f), PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
