package com.clarifi.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clarifi.ui.components.ClariFiWordmark

/**
 * The drawer carries the desktop sidebar's identity - wordmark on top, the full
 * list of sections - while the bottom bar handles the day-to-day switching.
 * The theme lives in Settings only; two places to change it was one too many.
 */
@Composable
fun ClariFiDrawer(
    current: Destination,
    dueCount: Int,
    onNavigate: (Destination) -> Unit,
) {
    ModalDrawerSheet(
        drawerShape = MaterialTheme.shapes.extraLarge,
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(300.dp),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ClariFiWordmark(modifier = Modifier.padding(start = 10.dp, top = 20.dp, bottom = 22.dp))

            Destination.bottomBar.forEach { destination ->
                DrawerEntry(
                    destination = destination,
                    selected = destination == current,
                    badge = dueCount.takeIf { destination == Destination.Fixed && it > 0 },
                    onClick = { onNavigate(destination) },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline,
            )

            Destination.drawerSecondary.forEach { destination ->
                DrawerEntry(
                    destination = destination,
                    selected = destination == current,
                    badge = null,
                    onClick = { onNavigate(destination) },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerEntry(
    destination: Destination,
    selected: Boolean,
    badge: Int?,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(destination.label, style = MaterialTheme.typography.titleSmall) },
        icon = {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        badge = badge?.let { { Badge { Text("$it") } } },
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Shared inset padding for screen content that sits under the bottom bar. */
val ScreenContentPadding = PaddingValues(horizontal = 16.dp)
