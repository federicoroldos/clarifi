package com.clarifi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clarifi.AppContainer

/**
 * The dependency graph, reachable from any composable.
 *
 * With hand-wired dependencies there is no generated component to inject from, so
 * the container travels down the tree and each view model is built by a one-line
 * lambda at its call site - which is also what makes them trivial to construct
 * with fakes in a test.
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided - wrap the tree in ClariFiRoot.")
}

@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}
