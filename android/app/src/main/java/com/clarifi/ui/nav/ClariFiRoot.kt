package com.clarifi.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clarifi.AppContainer
import com.clarifi.data.ai.AiProvider
import com.clarifi.data.repo.Summary
import com.clarifi.ui.LocalAppContainer
import com.clarifi.ui.accounts.AccountsScreen
import com.clarifi.ui.components.AiEngineBadge
import com.clarifi.ui.components.LocalBarVisibility
import com.clarifi.ui.components.rememberScrollAwareVisibility
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.dashboard.DashboardScreen
import com.clarifi.ui.fixed.FixedScreen
import com.clarifi.ui.about.AboutScreen
import com.clarifi.ui.scan.ScanScreen
import com.clarifi.ui.settings.SettingsScreen
import com.clarifi.ui.statement.StatementScreen
import com.clarifi.ui.transactions.MovementResult
import com.clarifi.ui.transactions.MovementSheet
import com.clarifi.ui.transactions.TransactionsScreen
import com.clarifi.ui.transactions.TransactionsViewModel
import com.clarifi.ui.theme.ClariFiTheme
import com.clarifi.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * The whole app below the Activity: theme, drawer, scaffold and routing.
 *
 * Screens are pure composables driven by state collected here or by their own
 * view models - so any screen can be previewed or tested in isolation.
 */
@Composable
fun ClariFiRoot(container: AppContainer) {
    val themeMode by container.settings.themeModeFlow
        .collectAsStateWithLifecycle(initialValue = container.settings.themeMode)

    // Provided before anything else in the tree: view models are built from the
    // container, so it has to be readable by the first composable that asks.
    ClariFiTheme(mode = themeMode) {
      val barVisibility = rememberScrollAwareVisibility()
      CompositionLocalProvider(
          LocalAppContainer provides container,
          LocalBarVisibility provides barVisibility,
      ) {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        val summary by container.summaries.summary
            .collectAsStateWithLifecycle(initialValue = Summary())

        val backStackEntry by navController.currentBackStackEntryAsState()
        val current = Destination.fromRoute(backStackEntry?.destination?.route)

        // Which provider is about to read a receipt or a statement, for the badge in
        // the app bar. Read on every navigation rather than watched: the key can only
        // be changed in Settings, which is a screen away from the two that show it.
        var aiProvider by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(current) {
            aiProvider = container.secrets.aiApiKey?.let { AiProvider.detect(it).label }
        }

        // The FAB is part of the shell, so its sheet is owned here rather than by
        // whichever screen happens to be on top.
        var addSheetOpen by remember { mutableStateOf(false) }
        var rates by remember { mutableStateOf(emptyMap<String, Double>()) }
        val movements: TransactionsViewModel = containerViewModel {
            TransactionsViewModel(it.accounts, it.txns)
        }

        LaunchedEffect(movements) {
            movements.messages.collect { snackbarHostState.showSnackbar(it) }
        }

        fun navigateTo(destination: Destination) {
            scope.launch { drawerState.close() }
            barVisibility.show()

            // Asked of the controller, not of `current`. The bars keep the callback
            // they were first given, so the `current` captured in it can be several
            // screens out of date: every Dashboard button was comparing against the
            // screen you started from and returning early, which is why only the
            // system back button got you home.
            if (destination.route == navController.currentDestination?.route) return
            navController.navigate(destination.route) {
                // Top-level destinations are siblings, not a stack: always come back
                // to the dashboard rather than unwinding a long history.
                //
                // The pop is never inclusive. Popping the dashboard while saving its
                // state stored the stack that had the *current* screen on top, and the
                // restoreState below then put that same screen straight back, so every
                // Dashboard button did nothing. Leaving the dashboard in place makes
                // the pop land on it, and launchSingleTop stops it being duplicated.
                popUpTo(Destination.Dashboard.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        // Back closes the drawer before it leaves the screen.
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ClariFiDrawer(
                    current = current,
                    dueCount = summary.dueCount,
                    onNavigate = ::navigateTo,
                )
            },
        ) {
            ClariFiScaffold(
                current = current,
                dueCount = summary.dueCount,
                barVisibility = barVisibility,
                snackbarHostState = snackbarHostState,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onSelect = ::navigateTo,
                onAdd = {
                    scope.launch { rates = movements.exchangeRates() }
                    addSheetOpen = true
                },
                actions = {
                    val provider = aiProvider
                    val usesAi = current == Destination.Scan || current == Destination.Statement
                    if (provider != null && usesAi) AiEngineBadge(provider)
                },
            ) { padding ->
                ClariFiNavHost(
                    navController = navController,
                    snackbarHostState = snackbarHostState,
                    contentPadding = padding,
                    onNavigate = ::navigateTo,
                )
            }

            if (addSheetOpen) {
                MovementSheet(
                    accounts = summary.accounts,
                    rates = rates,
                    onDismiss = { addSheetOpen = false },
                    onSave = { result ->
                        when (result) {
                            is MovementResult.Entry -> movements.add(
                                type = result.type,
                                accountId = result.accountId,
                                amount = result.amount,
                                date = result.date,
                                description = result.description,
                                category = result.category,
                            )

                            is MovementResult.Transfer -> movements.transfer(
                                sourceId = result.sourceId,
                                destinationId = result.destinationId,
                                amountSent = result.amountSent,
                                amountReceived = result.amountReceived,
                                date = result.date,
                                note = result.note,
                            )
                        }
                        addSheetOpen = false
                    },
                )
            }
        }
      }
    }
}

@Composable
private fun ClariFiNavHost(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onNavigate: (Destination) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Dashboard.route,
        // Sibling sections cross-fade; a slide would imply a hierarchy that isn't there.
        enterTransition = { fadeIn(Motion.fade()) },
        exitTransition = { fadeOut(Motion.fade()) },
    ) {
        composable(Destination.Dashboard.route) {
            DashboardScreen(contentPadding = contentPadding, onNavigate = onNavigate)
        }
        composable(Destination.Accounts.route) {
            AccountsScreen(contentPadding = contentPadding, snackbarHostState = snackbarHostState)
        }
        composable(Destination.Transactions.route) {
            TransactionsScreen(contentPadding = contentPadding, snackbarHostState = snackbarHostState)
        }
        composable(Destination.Fixed.route) {
            FixedScreen(contentPadding = contentPadding, snackbarHostState = snackbarHostState)
        }
        composable(Destination.Scan.route) {
            ScanScreen(
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
                onNavigate = onNavigate,
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(contentPadding = contentPadding, snackbarHostState = snackbarHostState)
        }
        composable(Destination.Statement.route) {
            StatementScreen(
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
                onNavigate = onNavigate,
            )
        }
        composable(Destination.About.route) {
            AboutScreen(contentPadding = contentPadding)
        }

        val built = setOf(
            Destination.Dashboard,
            Destination.Accounts,
            Destination.Transactions,
            Destination.Fixed,
            Destination.Scan,
            Destination.Settings,
            Destination.Statement,
            Destination.About,
        )
        // Still to come; each is swapped for its real screen as the phases land.
        Destination.entries.filterNot { it in built }.forEach { destination ->
            composable(destination.route) {
                SectionPlaceholder(destination = destination, contentPadding = contentPadding)
            }
        }
    }
}
