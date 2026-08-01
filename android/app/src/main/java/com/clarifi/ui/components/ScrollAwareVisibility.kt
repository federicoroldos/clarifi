package com.clarifi.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Hides a chrome element while the user scrolls down and brings it back the
 * moment they scroll up, which is what makes the bottom bar "collapsible"
 * without ever costing a tap to get it back.
 *
 * Collapsing is only allowed while something on screen actually scrolls. A drag
 * on a screen whose content already fits used to hide the bar too, because a
 * nested-scroll parent sees the gesture whether or not anyone acts on it, and
 * that trades away the navigation for reading room the user did not gain.
 *
 * A small threshold keeps it from flickering on the tiny jitter a finger produces
 * while it rests on the screen.
 */
class ScrollAwareVisibility {

    var visible by mutableStateOf(true)
        private set

    /** The scrollables currently on screen that have somewhere left to go. */
    private val scrollables = mutableSetOf<Any>()

    fun show() {
        visible = true
    }

    /**
     * Screens report their own scroll state here. Several can overlap during a
     * screen transition, so they are tracked by identity rather than as one flag:
     * the outgoing screen going away must not silence the incoming one.
     */
    fun report(owner: Any, canScroll: Boolean) {
        if (canScroll) scrollables += owner else scrollables -= owner
        if (scrollables.isEmpty()) visible = true
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (scrollables.isEmpty()) return Offset.Zero
            when {
                available.y < -THRESHOLD -> visible = false
                available.y > THRESHOLD -> visible = true
            }
            return Offset.Zero
        }
    }

    private companion object {
        const val THRESHOLD = 2f
    }
}

/** The shell's instance, so any screen can report its scroll state without plumbing. */
val LocalBarVisibility = staticCompositionLocalOf { ScrollAwareVisibility() }

@Composable
fun rememberScrollAwareVisibility(): ScrollAwareVisibility = remember { ScrollAwareVisibility() }

/** A [LazyListState] that keeps the app bars honest about whether it can scroll. */
@Composable
fun rememberBarAwareLazyListState(): LazyListState {
    val state = rememberLazyListState()
    ReportScrollable(state) { state.canScrollForward || state.canScrollBackward }
    return state
}

/** The [ScrollState] equivalent, for screens built on a plain scrolling Column. */
@Composable
fun rememberBarAwareScrollState(): ScrollState {
    val state = rememberScrollState()
    ReportScrollable(state) { state.canScrollForward || state.canScrollBackward }
    return state
}

@Composable
private fun ReportScrollable(owner: Any, canScroll: () -> Boolean) {
    val visibility = LocalBarVisibility.current
    LaunchedEffect(visibility, owner) {
        snapshotFlow(canScroll).collect { visibility.report(owner, it) }
    }
    DisposableEffect(visibility, owner) {
        onDispose { visibility.report(owner, false) }
    }
}
