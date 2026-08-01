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
     *
     * Reporting deliberately never brings the bar back. Hiding it hands its height
     * to the content, so a page that only just overflowed stops scrolling the
     * instant it goes away - and a rule that showed the bar again there would show
     * it, overflow the page, hide it, and bounce like that for as long as the finger
     * moved. About was exactly that height. The bar comes back on a scroll up or on
     * a navigation instead, both of which the user asked for.
     */
    fun report(owner: Any, canScroll: Boolean) {
        if (canScroll) scrollables += owner else scrollables -= owner
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            when {
                // Dragging back down always returns it, even on a page that no longer
                // scrolls, which is the state hiding it can create.
                available.y > THRESHOLD -> visible = true
                // Hiding, though, stays reserved for pages with somewhere to scroll:
                // a drag on a page that fits would otherwise cost the navigation for
                // reading room the user never gained.
                available.y < -THRESHOLD && scrollables.isNotEmpty() -> visible = false
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
