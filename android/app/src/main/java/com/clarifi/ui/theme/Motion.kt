package com.clarifi.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * One place for how ClariFi moves.
 *
 * The desktop animates everything with `cubic-bezier(0.34, 1.56, 0.64, 1)` - a
 * curve that overshoots slightly before settling. The closest honest translation
 * on Android is a spring with low-bouncy damping, which reads the same and, unlike
 * a fixed-duration curve, stays natural when a gesture interrupts it mid-flight.
 */
object Motion {

    /** Default for size, offset and colour changes on screen elements. */
    fun <T> spring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Snappier variant for things that must not feel loose, like a bottom bar hiding. */
    fun <T> quick(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    val offset: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Cross-fades between screens; matches the 0.35s transitions in the CSS. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(durationMillis = 220)

    /**
     * Counting a figure up or down to its new value.
     *
     * No bounce here: money that overshoots and comes back reads as a glitch, and
     * a balance is the one number on screen nobody wants to see wrong, even for a
     * frame.
     */
    fun <T> number(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

    const val SCREEN_ENTER_MILLIS = 260
    const val SCREEN_EXIT_MILLIS = 200
}
