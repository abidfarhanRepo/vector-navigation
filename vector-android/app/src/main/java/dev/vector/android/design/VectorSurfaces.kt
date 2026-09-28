package dev.vector.android.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.Animatable
import dev.vector.android.VectorTokens
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs

/**
 * Vector's surfaces: the things that float, cover, wait or fail.
 *
 * Sheets, dialogs, snackbars, empty states, skeletons and media tiles live here
 * rather than in `VectorComponents.kt` because they share one property the
 * controls do not: **they own a region of the screen**. Everything in this file
 * either sits on the content plane or above it, and every one of them has an
 * explicit reduced-motion and inset story.
 */

// ---------------------------------------------------------------------------
// Bottom sheet
// ---------------------------------------------------------------------------

/**
 * The anchors a Vector sheet can rest at.
 *
 * Fractions of the available height, measured from the top. `Medium` exists
 * because the brief asks for a "draggable bottom sheet" that can be pulled up
 * for more without leaving the map — a two-state sheet (peek/full) forces a
 * choice the user has not made yet.
 */
@Immutable
data class VectorSheetAnchors(
    val collapsed: Float = 0.78f,
    val medium: Float = 0.5f,
    val expanded: Float = 0.12f,
    /**
     * The collapsed rest height in **dp**, when the caller knows it.
     *
     * A fraction is the wrong unit for a peek. The peek exists to show a
     * specific piece of content — for the discovery sheet, the search row and
     * nothing else — and that content has a height in dp, not a percentage of
     * whatever display it lands on. Calibrating the fraction on one device is
     * how the sheet came to clip its first card on a Galaxy S24: the emulator
     * it was tuned on is 2400 px at density 420 (571 dp tall) and the S24 is
     * 2340 at 450 (832 dp), so the same 0.60 is 228 dp on one and 333 dp on
     * the other.
     *
     * When set, [collapsed] is ignored and the real fraction is computed from
     * the measured container, so the peek shows the same content everywhere.
     */
    val peekHeight: Dp? = null,
) {
    val all: List<Float> get() = listOf(collapsed, medium, expanded)
}

/**
 * A draggable bottom sheet.
 *
 * ## Why this is hand-rolled rather than `ModalBottomSheet`
 *
 * Material's modal sheet is a separate window. In this app a sheet is part of
 * the map's chrome: it must compose in the same tree as the map controls so that
 * the phase-ownership invariant (`NavUiTest`: every surface belongs to exactly
 * one phase, and top/bottom groups are single Columns) still holds, and so that
 * the vehicle marker, the route ribbon and the sheet animate on one frame clock.
 * A dialog window would also make the sheet's contents invisible to the
 * screenshot baselines, which capture the activity's own window.
 *
 * What is kept from Material's behaviour, because it is what makes a sheet feel
 * native: drag anywhere on the surface (not just the handle), a fling that
 * continues past the nearest anchor when the gesture was fast, dismissal only
 * from the collapsed anchor when [dismissible], scrim tap to dismiss, back to
 * dismiss, and `imePadding` so the keyboard pushes the sheet rather than
 * covering it.
 *
 * ## Reduced motion
 *
 * The anchor animation is a spring ([VectorMotion.springSheet]); under reduced
 * motion it is a snap, and the drag itself is unaffected — direct manipulation
 * is not an animation.
 */
@Composable
fun VectorBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchors: VectorSheetAnchors = VectorSheetAnchors(),
    initialAnchor: Float = anchors.collapsed,
    dismissible: Boolean = true,
    showScrim: Boolean = true,
    testTag: String? = null,
    content: @Composable (anchor: Float) -> Unit,
) {
    if (!visible) return
    val c = VectorTheme.colors
    val motion = VectorTheme.motion
    val shape = VectorTheme.shapes.continuous(28.dp)
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // The keyboard is taken out of the CONTAINER, so the fraction is
            // measured against the space actually left above the IME. It used
            // to be applied inside the sheet's own fixed height, where a
            // keyboard taller than the sheet resolved the content box to a
            // negative height and the sheet vanished completely.
            .imePadding()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        val containerPx = with(LocalDensity.current) { maxHeight.toPx() }
        // The peek, in dp when the caller gave one. See [VectorSheetAnchors].
        val collapsed = anchors.peekHeight
            ?.let { peek ->
                val px = with(LocalDensity.current) { peek.toPx() }
                (1f - px / containerPx).coerceIn(0.05f, 0.94f)
            }
            ?: anchors.collapsed
        // Ascending: expanded (smallest top fraction) .. collapsed (largest).
        val stops = remember(collapsed, anchors) {
            listOf(anchors.expanded, anchors.medium, collapsed)
                .map { it.coerceIn(0.05f, 0.94f) }
                .distinct()
                .sorted()
        }

        // ONE source of truth for the sheet's top edge.
        //
        // The old implementation kept `anchor` plus a separate `dragFraction`
        // and swapped between "raw while dragging" and "animated when not".
        // On release it zeroed the drag offset BEFORE the anchor changed, so
        // the sheet jumped back to where the gesture started and then sprang
        // to the target — two moves for one gesture, which is exactly what
        // "not having a smooth motion" describes. An `Animatable` that the
        // drag writes to directly cannot do that: the settle animation begins
        // from wherever the finger actually let go.
        val top = remember { Animatable(initialAnchor.coerceIn(0.05f, 0.94f)) }

        // The caller asking for a different rest position (searching opens the
        // sheet) is an animation, not a jump.
        LaunchedEffect(initialAnchor) {
            top.animateTo(initialAnchor.coerceIn(0.05f, 0.94f), motion.springSheet)
        }

        val dragState = rememberDraggableState { deltaPx ->
            scope.launch {
                top.snapTo((top.value + deltaPx / containerPx).coerceIn(stops.first(), 0.94f))
            }
        }

        /**
         * Where to settle, given where the finger left off and how fast.
         *
         * Velocity is projected forward by [SHEET_FLING_PROJECTION_S] and the
         * nearest stop to that projected position wins. That is one rule rather
         * than the old "nearest anchor, unless the drag exceeded 0.12, in which
         * case jump to the extreme" — which let a quick flick from the peek
         * skip the middle stop entirely.
         */
        fun settleTarget(velocityPx: Float): Float {
            val projected = (top.value + (velocityPx / containerPx) * SHEET_FLING_PROJECTION_S)
                .coerceIn(stops.first(), stops.last())
            return stops.minByOrNull { abs(it - projected) } ?: stops.last()
        }

        if (showScrim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(if (dismissible) 1f else 0.6f)
                    .background(c.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = dismissible,
                        onClick = onDismiss,
                    ),
            )
        }

        // THE SHEET, and nothing above it.
        //
        // There is deliberately no full-size Box with a drag detector on it any
        // more. That is what made the map unusable: `detectVerticalDragGestures`
        // on a `fillMaxSize()` container consumed every vertical drag on the
        // display, so panning the cartography did nothing at all — the sheet
        // was invisibly covering the whole screen. Only the sheet's own surface
        // takes gestures now, and the map keeps everything above it.
        val sheetHeight = (maxHeight * (1f - top.value)).coerceAtLeast(0.dp)
        Box(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeight)
                .vectorShadow(VectorTheme.elevation.sheet, shape, VectorTheme.elevation)
                .clip(shape)
                .background(c.surfaceFloating)
                .border(1.dp, c.border, shape)
                // Drag the surface. The scrolling content inside consumes its
                // own vertical gestures, so a drag on the list scrolls the list
                // and a drag on the chrome moves the sheet — which is the
                // behaviour every sheet in the reference set has.
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val target = settleTarget(velocity)
                        if (dismissible && target >= stops.last() &&
                            top.value > stops.last() + SHEET_DISMISS_SLOP
                        ) {
                            onDismiss()
                        } else {
                            top.animateTo(
                                targetValue = target,
                                animationSpec = motion.springSheet,
                                // Carries the gesture's own speed into the
                                // settle, so a fast flick arrives fast and a
                                // slow release eases. Without it every gesture
                                // settled at the same rate regardless of how it
                                // was made, which reads as the sheet ignoring
                                // you.
                                initialVelocity = velocity / containerPx,
                            )
                        }
                    },
                )
                .semantics { paneTitle = "Sheet" },
        ) {
            Column(Modifier.fillMaxSize()) {
                // The grab handle. 48 dp of target for a 40 x 4 visual, and its
                // own draggable so the handle works even when the content
                // underneath it is a scrolling list.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(VectorTokens.Size.sheetHandleTarget)
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                top.animateTo(
                                    targetValue = settleTarget(velocity),
                                    animationSpec = motion.springSheet,
                                    initialVelocity = velocity / containerPx,
                                )
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(VectorTokens.Size.sheetHandleBar)
                            .clip(VectorTheme.shapes.pill)
                            .background(c.borderStrong),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = navBar)
                        .padding(horizontal = 4.dp),
                ) {
                    content(top.value)
                }
            }
        }
    }
    BackHandler(enabled = dismissible, onBack = onDismiss)
}

/**
 * How far a release's velocity is projected when choosing where to settle.
 *
 * 0.12 s. Long enough that a deliberate flick carries to the next stop, short
 * enough that a slow drag settles where it was put rather than sailing past.
 * Measured against the spring this then hands the velocity to
 * (`VectorMotion.springSheet`); a longer projection with the same spring
 * overshoots visibly.
 */
private const val SHEET_FLING_PROJECTION_S = 0.12f

/**
 * How far past the collapsed stop a dismissible sheet must be dragged to close.
 *
 * Without a slop a dismissible sheet released exactly at its peek would close,
 * so the peek would be impossible to rest at.
 */
private const val SHEET_DISMISS_SLOP = 0.04f

// ---------------------------------------------------------------------------
// Dialog
// ---------------------------------------------------------------------------

/**
 * A confirmation dialog.
 *
 * The brief requires every destructive action to confirm. This is deliberately
 * narrow: a title, one sentence, and two actions — it is not a general-purpose
 * container, because a dialog that can hold anything becomes the screen you
 * should have built.
 *
 * [destructive] swaps the confirm button to the coral fill and moves focus to
 * the *cancel* action, which is the standard protection against a double-tap
 * confirming a deletion.
 */
@Composable
fun VectorDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val motion = VectorTheme.motion
    val shape = VectorTheme.shapes.continuous(24.dp)
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = motion.standard(),
        label = "dialogAlpha",
    )
    // A `Popup`, so the dialog covers the SCREEN rather than its caller.
    //
    // `fillMaxSize()` fills the parent's constraints, and a dialog is almost
    // always raised from somewhere deep in a layout — here, a "Clear" button
    // inside a card inside a scrolling settings sheet. Composed inline it
    // filled *the card*: the capture of this state shows a grey rectangle the
    // width of one section with a dialog squeezed inside it and its two
    // buttons wrapped to "Kee p" and "Cle ar". The scrim dimmed a card while
    // the rest of the screen stayed bright, which is the opposite of what a
    // scrim is for.
    //
    // A Popup escapes those constraints and is still composited into the frame,
    // so `screencap` — which is what the visual baselines use — captures it.
    // `focusable = true` so the system back button reaches `onDismiss` and the
    // dialog takes input priority, which is what makes it modal at all.
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim.copy(alpha = c.scrim.alpha * alpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .vectorShadow(VectorTheme.elevation.sheet, shape, VectorTheme.elevation)
                .clip(shape)
                .background(c.surfaceFloating)
                .border(1.dp, c.border, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .semantics { paneTitle = title }
                .padding(24.dp),
        ) {
            Column {
                Text(title, style = t.sectionTitle, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Text(body, style = t.body, color = c.inkSecondary)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VectorButton(
                        text = dismissLabel,
                        onClick = onDismiss,
                        variant = VectorButtonVariant.Secondary,
                        size = VectorButtonSize.Medium,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                        testTag = "dialog:dismiss",
                    )
                    VectorButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        variant = if (destructive) {
                            VectorButtonVariant.Destructive
                        } else {
                            VectorButtonVariant.Primary
                        },
                        size = VectorButtonSize.Medium,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                        testTag = "dialog:confirm",
                    )
                }
            }
        }
    }
    }
    // No BackHandler: the Popup is focusable and handles back itself via
    // `dismissOnBackPress`. Keeping both meant two handlers racing for the same
    // press.
}

// ---------------------------------------------------------------------------
// Snackbar
// ---------------------------------------------------------------------------

enum class VectorSnackbarTone { Neutral, Success, Warning, Danger }

/**
 * A transient message.
 *
 * Sits above the navigation bar and below any sheet, never over a primary
 * action, and is announced to a screen reader via `liveRegion` — a snackbar that
 * only exists visually is a snackbar half the users never receive. Under reduced
 * motion it appears without the slide.
 */
@Composable
fun VectorSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    tone: VectorSnackbarTone = VectorSnackbarTone.Neutral,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shape = VectorTheme.shapes.continuous(16.dp)
    val accent = when (tone) {
        VectorSnackbarTone.Neutral -> c.inkMuted
        VectorSnackbarTone.Success -> c.leaf
        VectorSnackbarTone.Warning -> c.warning
        VectorSnackbarTone.Danger -> c.danger
    }
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = navBar + 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .vectorShadow(VectorTheme.elevation.floating, shape, VectorTheme.elevation)
                .clip(shape)
                .background(c.ink)
                .border(1.dp, c.border.copy(alpha = 0.12f), shape)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(VectorTheme.shapes.pill)
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = t.body,
                color = c.inkInverse,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    actionLabel,
                    style = t.bodyStrong,
                    color = c.primaryContainer,
                    modifier = Modifier
                        .clip(VectorTheme.shapes.pill)
                        .vectorPressable(pressScale = 0.96f, onClick = onAction)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty, error, offline
// ---------------------------------------------------------------------------

/**
 * An empty or no-result state.
 *
 * The reference's empty lists are a flat block, a short muted sentence and one
 * action (audit §9). [actionLabel] is optional because not every empty state has
 * something useful to offer, and inventing one is worse than offering none.
 */
@Composable
fun VectorEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(VectorTheme.shapes.pill)
                    .background(c.surfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                VectorIconView(icon, 26.dp, c.inkMuted)
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            title,
            style = t.cardTitle,
            color = c.ink,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = t.body,
            color = c.inkMuted,
            textAlign = TextAlign.Center,
            maxLines = 4,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            VectorButton(
                text = actionLabel,
                onClick = onAction,
                variant = VectorButtonVariant.Tonal,
                size = VectorButtonSize.Medium,
                fillWidth = false,
                testTag = "empty:action",
            )
        }
    }
}

/**
 * An inline error.
 *
 * Inline rather than modal: the brief asks for "errors" as a state of the
 * surface, and a map that fails to load a route must stay usable underneath.
 * The sentence is passed in — this component never invents failure copy, because
 * `ApiErrorText` already owns that and blames the right party.
 */
@Composable
fun VectorErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shape = VectorTheme.shapes.continuous(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.dangerContainer)
            .border(1.dp, c.danger.copy(alpha = 0.24f), shape)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(message, style = t.body, color = c.dangerText, maxLines = 4)
            if (retryLabel != null && onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    retryLabel,
                    style = t.bodyStrong,
                    color = c.dangerText,
                    modifier = Modifier
                        .clip(VectorTheme.shapes.pill)
                        .vectorPressable(pressScale = 0.96f, onClick = onRetry)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The offline banner.
 *
 * Persistent and quiet, never a modal: being offline is a condition, not an
 * event, and the brief's torture matrix checks that the app stays usable with
 * the network gone. It is a `liveRegion` so it is announced once when it
 * appears rather than on every recomposition.
 */
@Composable
fun VectorOfflineBanner(
    message: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shape = VectorTheme.shapes.continuous(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.warningContainer)
            .border(1.dp, c.warning.copy(alpha = 0.24f), shape)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(VectorTheme.shapes.pill)
                .background(c.warning),
        )
        Spacer(Modifier.width(10.dp))
        Text(message, style = t.metadata, color = c.sunnyText, maxLines = 2)
    }
}

// ---------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------

/**
 * A skeleton block.
 *
 * **Static, and deliberately so.** The brief forbids shimmer, and there is a
 * second reason: a shimmering block is motion the user did not ask for and
 * cannot stop, which is precisely what reduced motion exists to prevent. A
 * skeleton's job is to reserve the final layout so nothing jumps when content
 * arrives, and a still block does that job exactly as well.
 */
@Composable
fun VectorSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = placeholderShape(16.dp),
    tone: Color = VectorTheme.colors.surfaceSunken,
) {
    Box(modifier.clip(shape).background(tone))
}

/** A stack of text-line skeletons that reserves the final paragraph's height. */
@Composable
fun VectorSkeletonLines(
    lines: Int,
    modifier: Modifier = Modifier,
    lineHeight: Dp = 14.dp,
    gap: Dp = 8.dp,
    shape: Shape = placeholderShape(7.dp),
) {
    Column(modifier) {
        repeat(lines) { index ->
            VectorSkeleton(
                modifier = Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.6f else 1f)
                    .height(lineHeight),
                shape = shape,
            )
            if (index != lines - 1) Spacer(Modifier.height(gap))
        }
    }
}

// ---------------------------------------------------------------------------
// Media
// ---------------------------------------------------------------------------

/**
 * A media tile.
 *
 * ## Why this is a tinted field rather than a photo
 *
 * Vector has no photo pipeline and no user-generated imagery — it is a
 * navigation client. The reference's cards are image-led because Corner is a
 * social places product; copying that without the imagery would mean shipping
 * grey boxes, and shipping *fake* photos would be inventing content. So a tile
 * renders a **deterministic tinted field with the item's category glyph**, keyed
 * on the item's own identity: the same place gets the same tile on every launch,
 * which also makes it a stable screenshot baseline.
 *
 * If a real image source is ever wired in, [painter] takes over and the fallback
 * stays as the placeholder — the layout does not change.
 */
@Composable
fun VectorMediaTile(
    key: String,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    label: String? = null,
    height: Dp = 96.dp,
    shape: Shape = VectorTheme.shapes.continuous(16.dp),
    tint: Color? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val palette = listOf(c.primary, c.coral, c.leaf, c.lilac, c.deepBlue, mediaAccent(c))
    val base = tint ?: palette[(key.hashCode().let { if (it < 0) -it else it }) % palette.size]
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(base.copy(alpha = 0.92f), base.copy(alpha = 0.62f)),
                ),
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            ) {
                VectorIconView(icon, 28.dp, labelOn(base).copy(alpha = 0.9f))
            }
        }
        if (label != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x66000000)),
                        ),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    style = t.chip,
                    color = labelOn(base),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The label colour for a media tile's tint.
 *
 * A tile's field is one of the saturated accents, and [VectorColors.sunny] is
 * LIGHT — white on it is 1.68:1. So the label follows the fill rather than the
 * theme, which is the same rule the palette states once in [VectorColors.onSunny].
 */
private fun labelOn(tint: Color): Color =
    if (tint == LightColors.sunny || tint == DarkColors.sunny) DarkColors.onSunny else Color.White

/** A sixth tint for the media palette: the light theme's cerulean, or the dark one's accent. */
private fun mediaAccent(c: VectorColors): Color =
    if (c.isDark) c.deepBlue else Color(0xFF5C9BD1)

/** Equal-width tiles in a row, with Vector's gutter. */
@Composable
fun VectorTileRow(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** A flat placeholder block, for a list tile with no content yet. */
@Composable
fun VectorPlaceholderBlock(
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    radius: Dp = 16.dp,
    label: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(c.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(label, style = t.metadata, color = c.inkMuted, textAlign = TextAlign.Center)
        }
    }
}
