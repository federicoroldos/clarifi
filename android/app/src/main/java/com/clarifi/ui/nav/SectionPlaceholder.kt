package com.clarifi.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.clarifi.ui.components.EmptyState

/**
 * Stand-in for a section that has not been built yet.
 *
 * Temporary: each entry is replaced by its real screen as the phases land, and
 * this file goes away once the last one does.
 */
@Composable
fun SectionPlaceholder(
    destination: Destination,
    contentPadding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = destination.icon,
            title = destination.label,
            message = "This section is being built.",
        )
    }
}
