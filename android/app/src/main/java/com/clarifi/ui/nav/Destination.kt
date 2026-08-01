package com.clarifi.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.clarifi.ui.icons.ClariFiIcons

/**
 * Every top-level place in the app.
 *
 * The five [inBottomBar] entries are the ones reached daily, and they live in the
 * bottom bar where a thumb can get to them in one tap. The rest are occasional -
 * importing a statement, changing a setting, syncing - and live in the drawer, so
 * the bar stays readable instead of being crammed with nine tiny targets.
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = false,
    /** Shorter caption for the bottom bar, where space is tight. */
    val shortLabel: String = label,
) {
    Dashboard("dashboard", "Dashboard", ClariFiIcons.Dashboard, inBottomBar = true),
    Transactions("transactions", "Transactions", ClariFiIcons.Transactions, inBottomBar = true, shortLabel = "Activity"),
    Scan("scan", "Scan Receipt", ClariFiIcons.Scan, inBottomBar = true, shortLabel = "Scan"),
    Fixed("fixed", "Fixed Transactions", ClariFiIcons.Fixed, inBottomBar = true, shortLabel = "Fixed"),
    Accounts("accounts", "Accounts", ClariFiIcons.Accounts, inBottomBar = true, shortLabel = "Accounts"),

    Statement("statement", "Import Statement", ClariFiIcons.Statement),
    Settings("settings", "Settings", ClariFiIcons.Settings),
    About("about", "About", ClariFiIcons.Info);

    companion object {
        val bottomBar: List<Destination> = entries.filter { it.inBottomBar }
        val drawerSecondary: List<Destination> = entries.filter { !it.inBottomBar }

        fun fromRoute(route: String?): Destination =
            entries.firstOrNull { it.route == route } ?: Dashboard
    }
}
