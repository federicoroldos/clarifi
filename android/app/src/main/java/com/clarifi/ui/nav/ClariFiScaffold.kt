package com.clarifi.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.clarifi.ui.components.ScrollAwareVisibility
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.Motion

/**
 * The app shell: top bar with the drawer handle, a bottom bar that gets out of
 * the way while reading, and a FAB where adding money is the obvious next action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClariFiScaffold(
    current: Destination,
    dueCount: Int,
    barVisibility: ScrollAwareVisibility,
    snackbarHostState: SnackbarHostState,
    onOpenDrawer: () -> Unit,
    onSelect: (Destination) -> Unit,
    onAdd: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val showFab = current == Destination.Dashboard || current == Destination.Transactions

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(barVisibility.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(current.label, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = ClariFiIcons.Menu,
                            contentDescription = "Open navigation menu",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = barVisibility.visible,
                enter = slideInVertically(Motion.offset) { it },
                exit = slideOutVertically(Motion.offset) { it },
            ) {
                ClariFiBottomBar(current = current, dueCount = dueCount, onSelect = onSelect)
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showFab && barVisibility.visible,
                enter = scaleIn(Motion.spring()) + fadeIn(Motion.fade()),
                exit = scaleOut(Motion.quick()) + fadeOut(Motion.fade()),
            ) {
                FloatingActionButton(
                    onClick = onAdd,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(ClariFiIcons.Plus, contentDescription = "New movement")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = content,
    )
}

@Composable
private fun ClariFiBottomBar(
    current: Destination,
    dueCount: Int,
    onSelect: (Destination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Destination.bottomBar.forEach { destination ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    // Only Fixed carries a count, and only when something is actually due.
                    if (destination == Destination.Fixed && dueCount > 0) {
                        BadgedBox(badge = { Badge { Text("$dueCount") } }) {
                            Icon(destination.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        Icon(destination.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                },
                label = { Text(destination.shortLabel, style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
