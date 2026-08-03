package com.clarifi.ui.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette
import kotlin.math.roundToInt

/**
 * The guided tour, drawn over the running app.
 *
 * Three separate pieces, deliberately: a Canvas that only draws (it has no pointer
 * input, so it blocks nothing), blocker strips that swallow every touch around the
 * highlighted control, and the card. The highlighted control itself is left
 * uncovered, which is what lets it receive the real tap. The tour never simulates
 * a press or drives navigation of its own: it just makes that one control the only
 * one available, and whatever it normally does is what happens.
 */
@Composable
fun TutorialOverlay(controller: TutorialController) {
    val step = controller.step ?: return
    if (controller.paused) return

    val hole = controller.boundsOf(step.target)
    // A step whose control is not on screen - no transactions logged yet, no AI key
    // saved - keeps its explanation and grows a button, rather than pointing at
    // nothing and stranding the user.
    val stranded = step.target != null && hole == null
    val showButton = step.advance == Advance.Confirm || stranded

    BackHandler(enabled = true) { controller.finish() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        val padPx = with(density) { 8.dp.toPx() }
        val cornerPx = with(density) { 16.dp.toPx() }
        val ringPx = with(density) { 2.dp.toPx() }

        val cutout = hole?.let {
            Rect(
                left = (it.left - padPx).coerceAtLeast(0f),
                top = (it.top - padPx).coerceAtLeast(0f),
                right = (it.right + padPx).coerceAtMost(screenWidth),
                bottom = (it.bottom + padPx).coerceAtMost(screenHeight),
            )
        }

        val pulse by rememberInfiniteTransition(label = "spotlight").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "spotlightPulse",
        )
        val accent = MaterialTheme.colorScheme.primary

        Canvas(modifier = Modifier.fillMaxSize()) {
            val scrim = Path().apply {
                addRect(Rect(Offset.Zero, Size(size.width, size.height)))
                if (cutout != null) {
                    addRoundRect(RoundRect(cutout, CornerRadius(cornerPx, cornerPx)))
                }
                fillType = PathFillType.EvenOdd
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.72f))

            if (cutout != null) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.35f + 0.45f * pulse),
                    topLeft = cutout.topLeft,
                    size = cutout.size,
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = ringPx),
                )
            }
        }

        if (cutout == null) {
            Box(modifier = Modifier.fillMaxSize().blockTouches())
        } else {
            Blocker(0f, 0f, screenWidth, cutout.top)
            Blocker(0f, cutout.bottom, screenWidth, screenHeight - cutout.bottom)
            Blocker(0f, cutout.top, cutout.left, cutout.height)
            Blocker(cutout.right, cutout.top, screenWidth - cutout.right, cutout.height)
        }

        // The card goes on the far side of the screen from what is highlighted, so
        // it can never be the thing covering it.
        val alignment = when {
            cutout == null -> Alignment.Center
            cutout.center.y > screenHeight / 2f -> Alignment.TopCenter
            else -> Alignment.BottomCenter
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = alignment,
        ) {
            StepCard(
                step = step,
                index = controller.stepIndex,
                total = controller.stepCount,
                showButton = showButton,
                // "Got it" would be a lie on a step whose control never appeared.
                buttonLabel = if (stranded) "Continue" else step.confirmLabel,
                onConfirm = controller::next,
                onSkip = controller::finish,
            )
        }
    }
}

/** One sealed strip of screen, sized in raw pixels because the cutout is. */
@Composable
private fun Blocker(left: Float, top: Float, width: Float, height: Float) {
    if (width <= 0f || height <= 0f) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(density) { width.toDp() },
                height = with(density) { height.toDp() },
            )
            .blockTouches()
    )
}

/**
 * Swallows every pointer event, drags included, so the page underneath cannot be
 * scrolled or tapped while the tour is waiting on one particular control.
 */
private fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

@Composable
private fun StepCard(
    step: TutorialStep,
    index: Int,
    total: Int,
    showButton: Boolean,
    buttonLabel: String,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    // A Surface, not a bare Box: a Box provides no content colour, so every Text
    // inside fell back to Compose's default black, which on the dark card was very
    // nearly the card itself. The surface hands its own onSurface down instead.
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Border and shadow, because this card floats over a dimmed screen rather
        // than sitting on a page: it needs an edge of its own to read as separate.
        border = BorderStroke(1.dp, clarifiPalette.borderStrong.copy(alpha = 0.5f)),
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            // Above the scrim, so a tap that misses the button stops here rather
            // than falling through to the screen behind.
            .blockTouches(),
    ) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "${index + 1} of $total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = step.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = step.body,
            style = MaterialTheme.typography.bodyMedium,
            // The second text tier, not the third: muted grey on a mid grey card is
            // too close a match to read comfortably.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSkip) {
                // Quieter than the green button, but still readable on the card: the
                // third text tier disappears into this grey.
                Text("Skip tour", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (showButton) {
                Button(onClick = onConfirm, shape = PillShape) {
                    Text(
                        text = buttonLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            } else {
                Text(
                    text = if (step.advance == Advance.Gesture) {
                        "Swipe the highlight"
                    } else {
                        "Tap the highlight"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
    }
}
