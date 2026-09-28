package dev.vector.android

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The numeric scales: space, radius, size, motion.
 *
 * ## What this file is, and what it is not
 *
 * It is the **numbers** and nothing else. Colour, type and elevation live in
 * `dev.vector.android.design`, because those need a theme (a role, a family, a
 * shadow colour) and a number does not. Splitting them this way is what lets
 * `VectorShapes` and `VectorMotion` be *built from* these ladders instead of
 * restating them — there is exactly one place a radius or a duration is
 * defined, and every component, screen and test reads that place.
 *
 * ## The ladders are numeric, and that was a deliberate change
 *
 * V4 named its steps by size (`xs`, `sm`, `md`, `lg`). It reads nicely and it
 * stops scaling the moment the ladder needs a step that has no obvious name: a
 * 20 dp gap between `lg` (16) and `xl` (24) has no letter to take, so the choice
 * is to invent `lg2`, to lie with a name, or to skip the step and use the wrong
 * number. All three happened.
 *
 * A numeric name cannot be ambiguous about which step is bigger, and it makes
 * "this is 20, not 16" reviewable in a diff. So: `s20`. The editorial rhythm the
 * brief asks for — 4/8/12/16/20/24/32, extended with 6/40/48 where a screen
 * genuinely needs it — is now expressible exactly.
 */
object VectorTokens {

    // -----------------------------------------------------------------------
    // Space
    // -----------------------------------------------------------------------

    /**
     * The spacing ladder, in dp.
     *
     * A 4 dp grid with the editorial steps the brief asks for. The steps that
     * are *not* multiples of 4 ([s2], [s6]) exist for optical alignment only:
     * 2 dp is what a border or an icon inset needs to look centred, and 6 dp is
     * the gap between a glyph and its label, where 8 reads loose and 4 reads
     * cramped. Neither is a layout step.
     */
    object Space {
        /** Optical only: a border inset, a hairline offset. */
        val s2 = 2.dp
        /** The 4 dp base unit. */
        val s4 = 4.dp
        /** Optical only: glyph-to-label. See the object KDoc. */
        val s6 = 6.dp
        /** Inside a chip, between stacked metadata lines. */
        val s8 = 8.dp
        /** Between the rows of a compact group. */
        val s12 = 12.dp
        /** The default gap, and the screen margin on a phone. */
        val s16 = 16.dp
        /** The editorial step: a section's inner breathing room. */
        val s20 = 20.dp
        /** Between a card and the next, and a screen's block rhythm. */
        val s24 = 24.dp
        /** Between sections on a scrolling screen. */
        val s32 = 32.dp
        /** A hero block's separation, and a sheet's top padding. */
        val s40 = 40.dp
        /** The largest step: onboarding, empty states, a dialog's rhythm. */
        val s48 = 48.dp
    }

    // -----------------------------------------------------------------------
    // Radius
    // -----------------------------------------------------------------------

    /**
     * Corner radii, in dp.
     *
     * The reference audit measured Corner's cards at an arc spanning ~109 px at
     * 3× — roughly 30–36 dp — and the brief asks for 16–24 dp depending on size.
     * The ladder below runs to 32 dp so the large surfaces can take the
     * reference's larger rounding while dense controls stay inside the brief's
     * band. `VectorShapes` maps these to roles; a screen should reach for a role.
     *
     * [r0] exists and is used: the maneuver band is **full-bleed**, because a
     * driving instruction is not a card on a page.
     */
    object Radius {
        val r0 = 0.dp
        /** Badges, tiny tiles, input wells. */
        val r8 = 8.dp
        /** Small cards, chips at their largest. */
        val r12 = 12.dp
        /** The default card. */
        val r16 = 16.dp
        /** A large card, an image tile. */
        val r20 = 20.dp
        /** A hero card, an inset panel. */
        val r24 = 24.dp
        /** A bottom sheet, a full-width modal surface. */
        val r28 = 28.dp
        /** The largest surface: an onboarding card, a dialog. */
        val r32 = 32.dp
        /** Any control whose height it is half of. */
        val pill = 999.dp
    }

    // -----------------------------------------------------------------------
    // Size
    // -----------------------------------------------------------------------

    /**
     * Component sizes, in dp.
     *
     * These are **footprints**, not type sizes. Anything that renders text takes
     * its type from `VectorTypography` and its box from here.
     */
    object Size {
        /**
         * One lane arrow in the lane diagram.
         *
         * Smaller than the maneuver arrow and larger than a control glyph: the
         * diagram is a supporting tier that has to be countable at a glance,
         * and six of these have to fit across a 412 dp phone with dividers
         * between them.
         */
        val laneArrow = 30.dp

        /**
         * The drawn exit shape on the exit badge.
         *
         * Sized to the badge's own text rather than to the lane arrows: it is
         * an adornment on a label, not a member of a diagram.
         */
        val exitGlyph = 20.dp

        /**
         * A round control on the map.
         *
         * 48 dp, which is Android's own stated minimum rather than two dp above
         * the 44 that WCAG asks for — a control a driver stabs at without
         * looking should not be sized to the floor.
         */
        val control = 48.dp

        /** The glyph inside a [control]. */
        val controlGlyph = 22.dp

        /** Minimum height of a tappable list row. */
        val row = 56.dp

        /**
         * Minimum height of the full-bleed maneuver banner.
         *
         * Measured from Waze: 277 px at 450 dpi is 98 dp for the two-line
         * variant. Vector's is a minimum rather than a fixed height because a
         * long bilingual road name has to be allowed to wrap.
         */
        val bannerMinHeight = 96.dp

        /** The drawn maneuver arrow in the banner. */
        val maneuverArrow = 58.dp

        /** The drawn maneuver arrow in the "then" strip and the step list. */
        val maneuverArrowSmall = 26.dp

        /**
         * The speed-limit disc.
         *
         * A drawn road sign, and sized as one: it carries at most three digits
         * at the supporting type size on a plain white field, which is the same
         * proportion the real sign uses. It does NOT move with
         * [speedCurrentDial] — see that constant for why the two stopped being
         * one number.
         */
        val speedDial = 54.dp

        /**
         * The current-speed dial.
         *
         * ## Why this is no longer [speedDial]
         *
         * Both discs were 54 dp because they are drawn side by side and it
         * seemed obvious they should match. They carry different amounts of
         * ink. The limit sign holds one line; the speedometer holds a value at
         * the secondary display size **with a unit line underneath it**, and a
         * circle is the least forgiving container there is for a two-line
         * stack, because the width available shrinks the further the text sits
         * from the centre.
         *
         * Measured (Robolectric, native graphics, 1.0 font scale): three bold
         * digits at the secondary size ink 43.0 dp wide and 19.5 dp tall, and
         * the two-line column puts the top of those digits 16.6 dp above the
         * centre. The far corner of the number is therefore
         * `hypot(21.5, 16.6) = 27.1 dp` from the centre — **past the 27 dp
         * radius of a 54 dp disc**. So "100" did not merely look tight at Qatari
         * expressway speeds, it did not fit, and the app's own speed bands go
         * past 115 km/h.
         *
         * 68 dp is that 27.1 dp plus [speedDialInset] on each side, rounded up
         * to an even dp. It is deliberately derived rather than chosen: if the
         * type scale moves, the number that has to move with it is named here
         * and asserted in `NavUiTest`.
         */
        val speedCurrentDial = 68.dp

        /**
         * Clear space between the ink in [speedCurrentDial] and its rim.
         *
         * Reported as *"km/h runs right to the edge of the circle"*, and it
         * did: the unit line cleared the old rim by about 4 dp, most of which
         * was spent on the 2.5 dp over-limit ring. Six dp leaves the ring plus
         * a gap that reads as deliberate.
         *
         * Applied as PADDING on the text column rather than as a gap someone
         * has to remember, so a larger system font scale runs out of room
         * inside the disc instead of over the rim.
         */
        val speedDialInset = 6.dp

        /** The drag handle's hit area at the top of a sheet. */
        /**
         * The gap every bottom-anchored surface leaves above the navigation bar.
         *
         * One number, because there were five. Audited after a UI review noted
         * that "some of the bottom paddings are off", and they were: measured
         * above the navigation-bar inset, the trip bar and route chooser left
         * 12 dp, the turn list 8, the settings sheet and the paywall 16, and
         * the discovery sheet 0 from the component plus an ad-hoc 24 inside its
         * own content. Five surfaces that all sit on the same screen edge, and
         * no two of them agreeing.
         *
         * 16 dp, which is where the two largest surfaces already were. It is
         * applied ON TOP of `navigationBarsPadding()` rather than instead of
         * it: the inset keeps content clear of the system bar, and this is the
         * breathing room above that — a surface flush against the gesture bar
         * reads as clipped even when nothing is.
         */
        val bottomGap = 16.dp

        val sheetHandleTarget = 48.dp

        /** The drag handle's visible bar. */
        val sheetHandleBar = 4.dp

        /** A circular icon button drawn small on the map. */
        val iconButtonSmall = 36.dp

        /** A circular icon button at its default size. */
        val iconButton = 44.dp

        /** A media tile in a two-up grid. */
        val mediaTile = 96.dp

        /** An avatar. */
        val avatar = 40.dp

        /**
         * How far down the screen the vehicle sits while navigating, as a
         * fraction of the map's height.
         *
         * 0.68 puts roughly two thirds of the screen ahead of the driver. Both
         * references measure at about the same place; Vector was centring the
         * vehicle, which spends half the display on road the driver has already
         * passed. See `MapCamera.lookAheadPadding`.
         */
        const val LOOK_AHEAD_FRACTION = 0.68
    }

    // -----------------------------------------------------------------------
    // Motion
    // -----------------------------------------------------------------------

    /**
     * Durations, in milliseconds, and the easings that go with them.
     *
     * Ordered by **how much of the user's attention the change deserves**, not
     * by how far anything moves:
     *
     * * [INSTANT] — critical driver information. Zero. A maneuver banner that
     *   fades in is a banner that cannot be read yet. This is a real value in
     *   the system and not an absence: both reference products swap navigation
     *   chrome on a hard cut, and it was measured rather than assumed.
     * * [FAST] — a control acknowledging a press.
     * * [SHORT] — a card, sheet or row appearing.
     * * [MEDIUM] — a camera nudge, a route redraw, a theme change.
     * * [FLIGHT] — the one long move: the camera crossing scales, from a route
     *   overview to the driving view. Google Maps measures ~2100 ms for exactly
     *   this and Vector was doing it in 450, which reads as a glitch rather
     *   than as travel. 1800 is a shade quicker because Vector's flight does
     *   not also have to load imagery.
     *
     * The brief's bands — 180–260 ms for a microinteraction, 300–450 ms for a
     * sheet or a scene change — are the [MICRO_FAST], [MICRO], [STANDARD],
     * [SCENE] and [SCENE_LONG] steps. They are the same ladder as [FAST] and
     * [SHORT], spelled out at the resolution the redesign needs, and
     * `VectorTheme.motion` is built from them so there is one set of numbers.
     *
     * Nothing here is above [FLIGHT], and [FLIGHT] applies to the camera only.
     * The driver must never wait on an animation, which means no *interactive*
     * surface may be gated by anything longer than [STANDARD].
     */
    object Motion {
        const val INSTANT = 0

        /** A press acknowledgement. */
        const val FAST = 110

        /** The floor of the brief's microinteraction band. */
        const val MICRO_FAST = 180

        /** A card, sheet or row appearing. */
        const val SHORT = 220

        /** The standard microinteraction: a chip, a badge, a row state. */
        const val MICRO = 220

        /** A card or panel arriving, at the top of the micro band. */
        const val STANDARD = 260

        /** A camera nudge, a route redraw, a theme change. */
        const val MEDIUM = 380

        /** A sheet, a scene change. The bottom of the brief's scene band. */
        const val SCENE = 380

        /** The top of the brief's scene band. */
        const val SCENE_LONG = 450

        /** The camera crossing scales. See the object KDoc. */
        const val FLIGHT = 1800

        /** The gap between staggered siblings, where a stagger aids comprehension. */
        const val STAGGER = 40

        /**
         * Standard easing: decelerate into place.
         *
         * Used for anything appearing or moving to a rest position, which is
         * almost everything. Fast out of the gate so the change is acknowledged
         * immediately, long tail so it settles rather than stops.
         */
        val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /**
         * Easing for something leaving.
         *
         * Accelerating, and paired with a shorter duration: nobody needs to
         * watch a dismissed sheet travel. Asymmetric enter/exit is the cheapest
         * way to make an interface feel responsive rather than slow.
         */
        val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        /**
         * Easing for the camera flight.
         *
         * Eases at BOTH ends. A camera that starts instantly is a camera that
         * appears to have been kicked; over 1800 ms the driver can actually
         * follow where the view is going, which is the entire purpose of
         * animating it rather than cutting.
         */
        val Flight: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

        /**
         * Eases at both ends, for a long move the eye must follow.
         *
         * The scene band's counterpart to [Flight]: a sheet travelling most of
         * the screen height, or a whole surface changing.
         */
        val Emphasized: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

        /**
         * How hard a continuously-tracked value is smoothed, per second.
         *
         * Not a duration: this is the coefficient of an exponential filter
         * applied on the frame callback, so it is independent of frame rate.
         * Used for the vehicle bearing, where the input is GPS heading — which
         * at low speed is close to random and would otherwise make the map
         * shake. See `MapCamera.smoothBearing`.
         */
        const val BEARING_SMOOTH_PER_S = 4.5

        // -- springs -------------------------------------------------------
        //
        // A spring has velocity, so a control interrupted mid-flight continues
        // from where it was instead of restarting. That matters most on the
        // filter row, where someone taps through four chips in a second.

        /** Cards and sheets settling into place: visible overshoot, no bounce. */
        val springSurface: FiniteAnimationSpec<Float> =
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

        /** Chips, pins, saved-state toggles: quick and slightly springier. */
        val springControl: FiniteAnimationSpec<Float> =
            spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium)

        /** A sheet dragged to an anchor and released. */
        val springSheet: FiniteAnimationSpec<Float> =
            spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessLow)

        /** A pin scaling in or lifting. */
        val springPin: FiniteAnimationSpec<Float> =
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium)

        // -- ready-made specs, so call sites do not re-type tween(...) --------

        fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST, easing = Standard)
        fun <T> short(): FiniteAnimationSpec<T> = tween(SHORT, easing = Standard)
        fun <T> micro(): FiniteAnimationSpec<T> = tween(MICRO, easing = Standard)
        fun <T> standard(): FiniteAnimationSpec<T> = tween(STANDARD, easing = Standard)
        fun <T> scene(): FiniteAnimationSpec<T> = tween(SCENE, easing = Standard)
        fun <T> sceneLong(): FiniteAnimationSpec<T> = tween(SCENE_LONG, easing = Emphasized)
        fun <T> flight(): FiniteAnimationSpec<T> = tween(FLIGHT, easing = Flight)
        fun <T> exit(): FiniteAnimationSpec<T> = tween(FAST, easing = Exit)
    }
}
