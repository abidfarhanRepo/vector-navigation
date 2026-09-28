package dev.vector.android.design

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.vector.android.VectorTokens

/**
 * Vector's motion vocabulary.
 *
 * ## The bands
 *
 * The brief specifies 180–260 ms for microinteractions and 300–450 ms for
 * sheet/scene transitions. Those are the values below, and they are *bands*, not
 * arbitrary numbers: [micro] is the acknowledgement of a press, [standard] is a
 * card or chip arriving, [scene] is a sheet or a whole surface changing, and
 * [sceneLong] is the one long move (the camera crossing scales).
 *
 * ## Springs, not just curves
 *
 * Cards, chips, map pins, sheets and saved-state toggles use springs rather than
 * eased tweens. The difference is not decoration: a spring has *velocity*, so a
 * chip interrupted mid-flight — which happens constantly when someone taps
 * through a filter row — continues from where it was instead of restarting.
 * Tweens are reserved for opacity and for anything whose distance the caller
 * cannot know in advance.
 *
 * ## Reduced motion is a value, not a branch
 *
 * When the system reports animations are off, the theme provides a **reduced**
 * instance of this object whose durations are zero and whose springs are
 * [snap]. Call sites do not check a flag: they use `VectorTheme.motion.springCard`
 * and get an instant transition. That is deliberate — a `if (reducedMotion)`
 * at 40 call sites is 40 chances to forget one, and the forgotten one is always
 * the one that makes someone motion-sick.
 */
@Immutable
data class VectorMotion(
    /** No animation at all. A real value: the maneuver banner uses it by policy. */
    val instant: Int,
    /** A press acknowledgement. */
    val microFast: Int,
    /** The standard microinteraction: a chip, a badge, a row state. */
    val micro: Int,
    /** A card or panel arriving. */
    val standard: Int,
    /** A sheet, a scene change, a route redraw. */
    val scene: Int,
    /** The one long move: the camera crossing scales. */
    val sceneLong: Int,
    /** The gap between staggered siblings. */
    val stagger: Int,
    /** Decelerate into place. Anything arriving. */
    val standardEasing: Easing,
    /** Accelerate away. Anything leaving. */
    val exitEasing: Easing,
    /** Eases at both ends. Long moves the eye must follow. */
    val emphasizedEasing: Easing,
    /** Cards and sheets settling into place: visible overshoot, no bounce. */
    val springSurface: FiniteAnimationSpec<Float>,
    /** Chips, pins, saved-state toggles: quick, slightly springier. */
    val springControl: FiniteAnimationSpec<Float>,
    /** A sheet dragged to an anchor and released. */
    val springSheet: FiniteAnimationSpec<Float>,
    /** A pin scaling in or lifting. */
    val springPin: FiniteAnimationSpec<Float>,
    /** True when this instance collapses everything to a cut. */
    val reduced: Boolean,
) {
    /** A tween in the [micro] band. */
    fun <T> micro(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(micro, easing = standardEasing)

    /** A tween in the [standard] band. */
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(standard, easing = standardEasing)

    /** A tween in the [scene] band. */
    fun <T> scene(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(scene, easing = standardEasing)

    /** A tween in the [sceneLong] band, eased at both ends. */
    fun <T> sceneLong(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(sceneLong, easing = emphasizedEasing)

    /** A tween in the [microFast] band, accelerating away. */
    fun <T> exit(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(microFast, easing = exitEasing)
}

internal val FullMotion = VectorMotion(
    instant = VectorTokens.Motion.INSTANT,
    microFast = VectorTokens.Motion.MICRO_FAST,
    micro = VectorTokens.Motion.MICRO,
    standard = VectorTokens.Motion.STANDARD,
    scene = VectorTokens.Motion.SCENE,
    sceneLong = VectorTokens.Motion.SCENE_LONG,
    stagger = VectorTokens.Motion.STAGGER,
    standardEasing = VectorTokens.Motion.Standard,
    exitEasing = VectorTokens.Motion.Exit,
    emphasizedEasing = VectorTokens.Motion.Emphasized,
    springSurface = VectorTokens.Motion.springSurface,
    springControl = VectorTokens.Motion.springControl,
    springSheet = VectorTokens.Motion.springSheet,
    springPin = VectorTokens.Motion.springPin,
    reduced = false,
)

/**
 * The reduced-motion instance.
 *
 * Durations are zero and springs are snaps. Nothing here is "shorter" — a
 * 40 ms animation is still an animation, and the point of the setting is that
 * the screen does not move.
 */
internal val ReducedMotion = FullMotion.copy(
    instant = 0,
    microFast = 0,
    micro = 0,
    standard = 0,
    scene = 0,
    sceneLong = 0,
    stagger = 0,
    springSurface = snap(),
    springControl = snap(),
    springSheet = snap(),
    springPin = snap(),
    reduced = true,
)

// ---------------------------------------------------------------------------
// Locals
// ---------------------------------------------------------------------------

private val LocalVectorColors = staticCompositionLocalOf { LightColors }
private val LocalVectorTypography = staticCompositionLocalOf { LightTypography }
private val LocalVectorShapes = staticCompositionLocalOf { VectorShapesLight }
private val LocalVectorElevation = staticCompositionLocalOf { LightElevation }
private val LocalVectorMotion = staticCompositionLocalOf { FullMotion }

/**
 * True when the system has asked for reduced motion.
 *
 * Read from `Settings.Global.ANIMATOR_DURATION_SCALE`, which is what Android's
 * own "Remove animations" developer/accessibility setting writes, and which is
 * also what an OEM accessibility suite toggles. It is re-read on every
 * `ON_RESUME` rather than once, because the setting is changed in a *different*
 * app — the user leaves Vector, turns animations off, and comes back.
 */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduced by remember { mutableStateOf(readAnimationsDisabled(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = readAnimationsDisabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduced
}

internal fun readAnimationsDisabled(context: Context): Boolean {
    val cr = context.contentResolver
    fun scale(name: String): Float = runCatching {
        Settings.Global.getFloat(cr, name, 1f)
    }.getOrDefault(1f)
    return scale(Settings.Global.ANIMATOR_DURATION_SCALE) == 0f ||
        scale(Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f
}

/**
 * Vector's theme.
 *
 * Wrap the whole app once. It provides Vector's roles to every screen and, at
 * the same time, binds Material 3 to the same families and roles so a Material
 * component that Vector has not replaced cannot introduce a second typeface or
 * a second blue.
 */
@Composable
fun VectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = rememberSystemReducedMotion(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val typography = if (darkTheme) DarkTypography else LightTypography
    val elevation = if (darkTheme) DarkElevation else LightElevation
    val motion = if (reducedMotion) ReducedMotion else FullMotion

    CompositionLocalProvider(
        LocalVectorColors provides colors,
        LocalVectorTypography provides typography,
        LocalVectorShapes provides VectorShapesLight,
        LocalVectorElevation provides elevation,
        LocalVectorMotion provides motion,
        LocalContentColor provides colors.ink,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme(colors, darkTheme),
            typography = materialTypographyFrom(typography),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

/**
 * Read Vector's design system.
 *
 * ```
 * Text("Rerouting…", style = VectorTheme.typography.body, color = VectorTheme.colors.inkMuted)
 * ```
 */
object VectorTheme {
    val colors: VectorColors
        @Composable @ReadOnlyComposable get() = LocalVectorColors.current

    val typography: VectorTypography
        @Composable @ReadOnlyComposable get() = LocalVectorTypography.current

    val shapes: VectorShapes
        @Composable @ReadOnlyComposable get() = LocalVectorShapes.current

    val elevation: VectorElevation
        @Composable @ReadOnlyComposable get() = LocalVectorElevation.current

    val motion: VectorMotion
        @Composable @ReadOnlyComposable get() = LocalVectorMotion.current
}

/**
 * Material's scheme, built from Vector's roles.
 *
 * `surface`/`onSurface`/`primary` and the container pairs are mapped, which is
 * the whole of what Material needs to render an unreplaced component (a
 * `Slider`, a `Tooltip`, a `Checkbox`) without it looking like a different app.
 * Nothing in Vector's own screens reads this.
 */
private fun materialColorScheme(c: VectorColors, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.lilac,
        onSecondary = c.onAccent,
        tertiary = c.deepBlue,
        onTertiary = c.onAccent,
        background = c.field,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.surfaceSunken,
        onSurfaceVariant = c.inkMuted,
        outline = c.controlBorder,
        outlineVariant = c.border,
        error = c.danger,
        onError = c.onAccent,
        errorContainer = c.dangerContainer,
        onErrorContainer = c.dangerText,
        scrim = c.scrim,
        inverseSurface = c.ink,
        inverseOnSurface = c.field,
    )
} else {
    lightColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.lilac,
        onSecondary = c.onAccent,
        tertiary = c.deepBlue,
        onTertiary = c.onAccent,
        background = c.field,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.surfaceSunken,
        onSurfaceVariant = c.inkMuted,
        outline = c.controlBorder,
        outlineVariant = c.border,
        error = c.danger,
        onError = c.onAccent,
        errorContainer = c.dangerContainer,
        onErrorContainer = c.dangerText,
        scrim = c.scrim,
        inverseSurface = c.ink,
        inverseOnSurface = c.field,
    )
}

/**
 * The theme without a `MaterialTheme` wrapper, for tests and previews.
 *
 * Robolectric screenshot and semantics tests compose fragments of the UI
 * without the app shell; this lets them do it in Vector's roles.
 */
@Composable
fun VectorThemeForTest(
    darkTheme: Boolean = false,
    reducedMotion: Boolean = true,
    content: @Composable () -> Unit,
) = VectorTheme(darkTheme = darkTheme, reducedMotion = reducedMotion, content = content)
