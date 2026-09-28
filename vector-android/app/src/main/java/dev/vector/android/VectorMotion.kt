package dev.vector.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import dev.vector.android.VectorTokens.Motion

/**
 * Vector's motion vocabulary.
 *
 * ## What was here before
 *
 * Nothing. Not one Compose animation API was used anywhere in the client:
 *
 * ```
 * $ grep -rn "animate|AnimatedVisibility|tween|spring|Crossfade" app/src/main
 * (0 matches)
 * ```
 *
 * Every appearance, disappearance and state change was a hard cut. A lane
 * strip materialised at full size, a speed-limit sign popped into existence
 * (visible frame-by-frame in `v4-evidence/` — it appears complete between two
 * frames of `v-navstart.mp4`), and the off-route warning replaced the maneuver
 * card with no indication that anything had happened other than the words
 * being different.
 *
 * ## What is deliberately NOT animated
 *
 * §33's [Motion.INSTANT] band is a real value, not an absence, and the
 * **maneuver banner is in it**. The distance, the arrow and the road name cut.
 * Three reasons, in order of how much they matter:
 *
 * 1. A driver reads that banner in the half-second before a junction. Any
 *    transition, however short, is time during which the text on screen is not
 *    yet the text that is true.
 * 2. It was measured rather than assumed. In `gmaps/` and `waze/`'s nav-start
 *    filmstrips the navigation chrome appears **complete in a single frame**
 *    while the camera then flies for two seconds. Both products animate the
 *    map and cut the instruction.
 * 3. The countdown does not need it. `distanceToManeuverM` is advanced by
 *    `RouteTracker` on the Choreographer callback, so the underlying value is
 *    already continuous at the display's refresh rate — 812 m to 640 m is a
 *    smooth ramp, and the visible stepping is `Units.shortDistance`'s rounding
 *    ladder doing its job. Animating a value that is already continuous would
 *    be animating the *formatter*, which is how §14's warning about animating
 *    every number gets earned.
 *
 * So what animates is **things arriving and leaving**, and the camera. Which is
 * what the reference products animate.
 */
object VectorMotion {

    // -----------------------------------------------------------------------
    // Enter / exit transitions
    // -----------------------------------------------------------------------

    /**
     * A HUD strip appearing or leaving: lane guidance, the exit badge, the
     * "then" preview, the road-you-are-on pill.
     *
     * Expands from zero height while fading, so the strips below it are pushed
     * rather than covered — the group is a `Column`, and a strip that faded in
     * at full height would make everything under it jump on the first frame.
     *
     * Exit is [Motion.FAST], enter is [Motion.SHORT]. Asymmetric on purpose:
     * §15 asks for lane information "when it becomes useful", and the moment it
     * stops being useful the driver has already passed the junction — there is
     * nothing left to look at, so it should get out of the way quickly.
     */
    fun stripEnter(): EnterTransition =
        expandVertically(
            animationSpec = tween(Motion.SHORT, easing = Motion.Standard),
            expandFrom = Alignment.Top,
        ) + fadeIn(tween(Motion.SHORT, easing = Motion.Standard))

    fun stripExit(): ExitTransition =
        shrinkVertically(
            animationSpec = tween(Motion.FAST, easing = Motion.Exit),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(tween(Motion.FAST, easing = Motion.Exit))

    /**
     * A bottom sheet or card arriving from the bottom edge.
     *
     * Slides its own full height, so it appears to come from off-screen rather
     * than to grow in place. Measured against Waze's route card, which rises
     * over about 250 ms with its content already laid out — the content does
     * NOT fade in separately, which is why the card reads as one object moving
     * instead of as a container plus arriving text.
     */
    fun sheetEnter(): EnterTransition =
        slideInVertically(
            animationSpec = tween(Motion.SHORT, easing = Motion.Standard),
            initialOffsetY = { it },
        ) + fadeIn(tween(Motion.FAST, easing = Motion.Standard))

    fun sheetExit(): ExitTransition =
        slideOutVertically(
            animationSpec = tween(Motion.FAST, easing = Motion.Exit),
            targetOffsetY = { it },
        ) + fadeOut(tween(Motion.FAST, easing = Motion.Exit))

    /**
     * A badge or sign appearing in place — the speed-limit disc, a jam count.
     *
     * Scales from 85% rather than from zero. A sign that grows from nothing
     * reads as a cartoon; one that arrives at nearly its final size reads as a
     * sign that was always there and has just been noticed, which is the
     * correct impression for a speed limit that has applied since the last
     * junction.
     */
    fun badgeEnter(): EnterTransition =
        scaleIn(tween(Motion.SHORT, easing = Motion.Standard), initialScale = 0.85f) +
            fadeIn(tween(Motion.SHORT, easing = Motion.Standard))

    fun badgeExit(): ExitTransition =
        scaleOut(tween(Motion.FAST, easing = Motion.Exit), targetScale = 0.9f) +
            fadeOut(tween(Motion.FAST, easing = Motion.Exit))

    /** A panel over the map: search results, the step list. */
    fun panelEnter(): EnterTransition =
        fadeIn(tween(Motion.FAST, easing = Motion.Standard)) +
            slideInVertically(
                animationSpec = tween(Motion.SHORT, easing = Motion.Standard),
                initialOffsetY = { -it / 8 },
            )

    fun panelExit(): ExitTransition = fadeOut(tween(Motion.FAST, easing = Motion.Exit))

    // -----------------------------------------------------------------------
    // Composable helpers
    // -----------------------------------------------------------------------

    /** [AnimatedVisibility] with the HUD-strip transitions already applied. */
    @Composable
    fun Strip(visible: Boolean, content: @Composable () -> Unit) {
        AnimatedVisibility(visible, enter = stripEnter(), exit = stripExit()) { content() }
    }

    /** [AnimatedVisibility] with the badge transitions already applied. */
    @Composable
    fun Badge(visible: Boolean, content: @Composable () -> Unit) {
        AnimatedVisibility(visible, enter = badgeEnter(), exit = badgeExit()) { content() }
    }

    /**
     * Hold the last non-null value, so an exit animation has something to draw.
     *
     * [AnimatedVisibility] recomposes its content while it is leaving, and the
     * state that made it leave is usually the state that emptied it — the lane
     * strip disappears *because* `lanes` went empty, so a naive
     * `Strip(lanes.isNotEmpty()) { LaneStrip(lanes) }` animates an empty strip
     * out. Keeping the last real value means the driver watches the lane
     * guidance they were actually looking at slide away, which is the only
     * version of that animation that carries any information.
     */
    @Composable
    fun <T : Any> rememberLast(value: T?): T? {
        val holder = remember { mutableStateOf<T?>(null) }
        if (value != null) holder.value = value
        return holder.value
    }

    /** [AnimatedVisibility] with the panel transitions already applied. */
    @Composable
    fun Panel(visible: Boolean, content: @Composable () -> Unit) {
        AnimatedVisibility(visible, enter = panelEnter(), exit = panelExit()) { content() }
    }

    /**
     * [AnimatedVisibility] with the bottom-sheet transitions already applied.
     *
     * Takes a [Modifier] because a sheet is positioned by its PARENT — it is
     * aligned to the bottom of the screen rather than placed in a stack — and
     * the alignment has to be on the animating node, not on the content inside
     * it, or the sheet slides within a box that is already the wrong size.
     */
    @Composable
    fun Sheet(
        visible: Boolean,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        AnimatedVisibility(
            visible, modifier = modifier,
            enter = sheetEnter(), exit = sheetExit(),
        ) { content() }
    }

    /**
     * An integer readout that should count rather than jump.
     *
     * For values that arrive in steps from a poll rather than continuously
     * from the frame loop — the jam count, the ETA in whole minutes, the
     * remaining distance after a reroute has replaced the route under the
     * driver. Those genuinely do change discontinuously, and a reroute that
     * silently rewrites "12 km left" as "17 km left" hides the one thing the
     * driver most needs to notice about it.
     *
     * NOT for the maneuver countdown. See the object KDoc.
     */
    @Composable
    fun counted(value: Int, durationMs: Int = Motion.MEDIUM): Int {
        val v by animateIntAsState(
            targetValue = value,
            animationSpec = tween(durationMs, easing = Motion.Standard),
            label = "counted",
        )
        return v
    }

    /**
     * A clickable that acknowledges the press by shrinking slightly.
     *
     * §22 asks for microinteractions that make the product feel responsive
     * without animating for its own sake, and a press response is the cheapest
     * one there is: it costs nothing, it delays nothing, and its absence is
     * exactly what makes a control feel like a picture of a button. Vector's
     * controls had none — `Surface(modifier = Modifier.clickable { })` with no
     * indication, so a tap on the recenter pill produced no feedback at all
     * until the camera moved.
     *
     * 0.93 at [Motion.FAST]. Deliberately not a Material ripple: a ripple
     * spreads over ~300 ms, which on a control the driver stabs at and looks
     * away from is feedback that arrives after they have stopped watching.
     */
    @Composable
    fun Modifier.pressable(
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Modifier {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.93f else 1f,
            animationSpec = tween(Motion.FAST, easing = Motion.Standard),
            label = "press",
        )
        return this
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                // No ripple: see above.
                indication = null,
                enabled = enabled,
            ) { onClick() }
    }

    // The bearing/angle helpers live in MapCamera, not here: they are the
    // camera's policy, they are pure, and MapCameraTest already exercises that
    // file on the JVM without the Compose runtime on the classpath. Putting
    // them in an object that also holds @Composable functions would have made
    // the one piece of arithmetic in V4 that MUST be right the one piece that
    // needed Robolectric to test. See MapCamera.shortestAngle.

    /** Density-independent pixels to raw pixels, for MapLibre's padding API. */
    @Composable
    fun dpToPx(dp: Float): Float = with(LocalDensity.current) { dp * density }
}
