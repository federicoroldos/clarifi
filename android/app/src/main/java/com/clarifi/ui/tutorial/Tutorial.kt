package com.clarifi.ui.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Every control the tour can point at.
 *
 * A control that is not on screen right now simply has no bounds, and its step
 * falls back to a plain card - which is what happens on a fresh install, where
 * there is no transaction row to point at yet.
 */
enum class TutorialTarget {
    Menu,
    Fab,
    NavActivity,
    NavFixed,
    NavScan,
    TxnRow,
    FixedApply,
    DrawerSettings,
}

/** How the user gets from one step to the next. */
enum class Advance {
    /** By tapping the highlighted control. */
    Tap,

    /** By swiping the highlighted row away, which is the only way to learn that one. */
    Gesture,

    /** By pressing the button on the card, for steps that only explain something. */
    Confirm,
}

data class TutorialStep(
    val target: TutorialTarget?,
    val title: String,
    val body: String,
    val advance: Advance,
    /**
     * Swallows the control's own click, leaving only the tour's step to advance.
     *
     * On for anything that would take over the screen: the + opens a sheet in a
     * window above the tour, and applying a fixed payment writes a real
     * transaction. Off for the ones whose whole job is to move you to the screen
     * the next step talks about.
     */
    val suppressAction: Boolean = false,
    val confirmLabel: String = "Got it",
)

/**
 * The state behind the guided tour.
 *
 * The tour drives nothing itself: it highlights a control, blocks everything
 * else, and waits. The user's own tap is what opens the drawer or changes the
 * screen, so the app is never in a state the tour put it in and the user could
 * not have reached alone.
 */
class TutorialController(private val onDone: () -> Unit) {

    var running by mutableStateOf(false)
        private set

    var stepIndex by mutableStateOf(0)
        private set

    /**
     * Hidden without being cancelled. A bottom sheet is a window of its own and
     * draws above this overlay, so the tour steps aside while one is open and
     * picks up again when it closes.
     */
    var paused by mutableStateOf(false)

    private val bounds = mutableStateMapOf<TutorialTarget, Rect>()

    val step: TutorialStep?
        get() = if (running) TutorialSteps.getOrNull(stepIndex) else null

    val stepCount: Int get() = TutorialSteps.size

    fun boundsOf(target: TutorialTarget?): Rect? = target?.let { bounds[it] }

    fun start() {
        stepIndex = 0
        paused = false
        running = true
    }

    fun finish() {
        running = false
        onDone()
    }

    fun next() {
        if (stepIndex >= TutorialSteps.lastIndex) finish() else stepIndex++
    }

    /** Reported by the highlighted control itself, on the way to its own onClick. */
    fun onTargetTapped(target: TutorialTarget) {
        val current = step ?: return
        if (current.advance == Advance.Tap && current.target == target) next()
    }

    /** True while the active step wants this control's own click swallowed. */
    fun suppresses(target: TutorialTarget): Boolean {
        val current = step ?: return false
        return current.target == target && current.suppressAction
    }

    fun report(target: TutorialTarget, rect: Rect?) {
        if (rect == null) {
            val wasOnScreen = bounds.remove(target) != null
            // A swipe step ends when the row it pointed at leaves the screen, which
            // is exactly what the swipe does to it. Nothing else can move it: every
            // other gesture on the page is sealed off while the step is up.
            val current = step
            if (wasOnScreen && current?.advance == Advance.Gesture && current.target == target) {
                next()
            }
        } else if (bounds[target] != rect) {
            bounds[target] = rect
        }
    }
}

/** Null outside a tour, so [tutorialTarget] costs nothing when no tour is running. */
val LocalTutorial = staticCompositionLocalOf<TutorialController?> { null }

/**
 * Marks a control as something the tour can point at.
 *
 * The pointer handler runs in the Initial pass, ahead of the control's own
 * clickable, and normally consumes nothing: the tap that advances the tour is the
 * same tap that opens the drawer or switches the screen, so there is no second
 * code path mimicking the button. When the step asks for it, the same handler
 * consumes the gesture instead, and the control does nothing at all - that is how
 * a step can point at the + without a sheet covering the tour that sent you there.
 */
@Composable
fun Modifier.tutorialTarget(target: TutorialTarget): Modifier {
    val controller = LocalTutorial.current ?: return this

    DisposableEffect(controller, target) {
        onDispose { controller.report(target, null) }
    }

    return this
        .onGloballyPositioned { controller.report(target, it.boundsInRoot()) }
        .pointerInput(controller, target) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (controller.suppresses(target)) {
                        event.changes.forEach { it.consume() }
                    }
                    if (event.type == PointerEventType.Release) controller.onTargetTapped(target)
                }
            }
        }
}
