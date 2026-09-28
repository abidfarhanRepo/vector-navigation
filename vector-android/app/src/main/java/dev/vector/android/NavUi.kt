package dev.vector.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.vector.android.design.*
import dev.vector.geo.Units
import org.maplibre.android.maps.MapView

/**
 * The chrome palette is the design system's.
 *
 * This file used to own a private `Chrome` data class with two literal
 * instances, argued for on the grounds that chrome sits ON the map and so has to
 * be derived from the cartography rather than from a token sheet. That reasoning
 * is kept and is now encoded in the design system itself: [VectorColors]
 * carries explicit guidance roles — [VectorColors.guidanceSurface],
 * [VectorColors.guidanceSurfaceSub], [VectorColors.onGuidance],
 * [VectorColors.guidanceAccent] — and the maneuver band is still dark in BOTH
 * themes, which is the one cartography decision the reference audit says must
 * survive.
 *
 * What changed is where the values live. `Chrome` restated 20 roles that the
 * cartography palette, the Material bridge and the component library each needed
 * a version of, so one decision had three homes and only one of them was
 * checked for contrast. Every screen now reads a single [VectorColors] instance
 * provided by [VectorTheme], and `design/ContrastTest` asserts every role pair
 * the UI actually uses.
 */



/**
 * The whole screen.
 *
 * Layout rule, stated once: **every overlay belongs to exactly one [Phase]**,
 * and top-anchored and bottom-anchored groups are each a single Column. That is
 * what stops the search bar, the route card and the turn banner from stacking
 * on top of one another — the previously reported "overlapping bubbles" —
 * because two of them can never be composed at the same time.
 * `NavUiTest."no two text elements overlap in any phase"` holds it.
 */
@Composable
fun VectorApp(
    mapView: MapView,
    ui: UiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (VectorApi.Place) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRecenter: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleSteps: () -> Unit,
    onToggleContributing: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onToggleOrientation: () -> Unit = {},
    onToggleOverview: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onSettingsChange: (Settings) -> Unit = {},
    onPickRecent: (Recents.Entry) -> Unit = {},
    onClearRecents: () -> Unit = {},
    onChooseRoute: (Int) -> Unit = {},
    onDismissArrival: () -> Unit = {},
    onSavePlace: (Places.Slot) -> Unit = {},
    onPickPlace: (Places.Saved) -> Unit = {},
    /** Forget a saved slot. See Places.clear — until V6 nothing called it. */
    onClearPlace: (Places.Slot) -> Unit = {},
    /** Drive again to somewhere the driver has already been. */
    onPickDrive: (Drives.Drive) -> Unit = {},
    /** Forget one drive. See Drives.remove. */
    onDeleteDrive: (Drives.Drive) -> Unit = {},
    onClearDrives: () -> Unit = {},
    /**
     * Vector Pro. Every one of these is inert until `ProCatalogue` has an
     * entry — see `ui.offersPro` — so a build with nothing gated carries the
     * callbacks and draws none of the surfaces.
     */
    onOpenPaywall: () -> Unit = {},
    onClosePaywall: () -> Unit = {},
    onBuy: (com.revenuecat.purchases.Package) -> Unit = {},
    onRestore: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
    onDismissProNotice: () -> Unit = {},
    /**
     * The Last Mile (V7 Phase 4). Inert until a journey has been composed.
     */
    onToggleJourney: () -> Unit = {},
    onScrubSunTime: (Long?) -> Unit = {},
    onToggleCooler: () -> Unit = {},
    /**
     * Navigate to the destination ON FOOT (V7.4 4C final).
     *
     * Deliberately a separate callback from [onStart] rather than a mode flag
     * read inside it: walking is an explicit MODE, and the two paths plan
     * against different graphs (`/foot` against `/navigate`), so a single
     * entry point that branched internally would be the one place a walking
     * request could silently become a driving one.
     */
    onWalk: () -> Unit = {},
    /**
     * One zoom level closer / further out. See `MainActivity.zoomStep` for why
     * a whole level and not a fraction of one, and [MapControls] for why the
     * buttons sit where they do.
     */
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
) {
    // `resolvedTheme`, not the raw preference.
    //
    // The MAP has resolved SYSTEM since V3 (`MainActivity.applyStyle` calls
    // `resolvedTheme(systemInDark())`); the CHROME compared `settings.theme`
    // against LIGHT directly, and SYSTEM is neither. So the default setting on
    // a phone in light mode produced a light map underneath dark panels — two
    // halves of the same app disagreeing about the time of day, and the reason
    // the status-bar icons were being asked for the wrong colour on top of it.
    val resolved = ui.settings.resolvedTheme(isSystemInDarkTheme())
    // The one place the theme is resolved for the whole app. Everything below
    // reads `VectorTheme.colors`; nothing outside `design/` names a colour.
    VectorTheme(darkTheme = resolved == VectorStyle.MapTheme.DARK) {
        Box(Modifier.fillMaxSize().background(VectorTheme.colors.field)) {
            // The map is the only part that needs a GL surface. Keeping it out of
            // VectorChrome is what lets the whole overlay layer be composed and
            // asserted on the JVM (app/src/test/.../NavUiTest.kt) — the emulator
            // SIGSEGVs on this host, so without this split the UI would never have
            // been rendered anywhere at all.
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            VectorChrome(
                ui, onQueryChange, onSearch, onPick, onStart, onCancel, onRecenter,
                onToggleVoice, onToggleSteps, onToggleContributing, onOpenSearch, onCloseSearch,
                onToggleOrientation, onToggleOverview, onOpenSettings, onCloseSettings,
                onSettingsChange, onPickRecent, onClearRecents, onChooseRoute,
                onDismissArrival, onSavePlace, onPickPlace,
                onClearPlace, onPickDrive, onDeleteDrive, onClearDrives,
                onOpenPaywall, onClosePaywall, onBuy, onRestore, onManageSubscription,
                onDismissProNotice,
                onToggleJourney, onScrubSunTime, onToggleCooler, onWalk,
                onZoomIn, onZoomOut,
            )
        }
    }
}

/** Every overlay. No map, no GL, no Android View — composable anywhere. */
@Composable
fun VectorChrome(
    ui: UiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (VectorApi.Place) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRecenter: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleSteps: () -> Unit,
    onToggleContributing: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onToggleOrientation: () -> Unit = {},
    onToggleOverview: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onSettingsChange: (Settings) -> Unit = {},
    onPickRecent: (Recents.Entry) -> Unit = {},
    onClearRecents: () -> Unit = {},
    onChooseRoute: (Int) -> Unit = {},
    onDismissArrival: () -> Unit = {},
    onSavePlace: (Places.Slot) -> Unit = {},
    onPickPlace: (Places.Saved) -> Unit = {},
    /** Forget a saved slot. See Places.clear — until V6 nothing called it. */
    onClearPlace: (Places.Slot) -> Unit = {},
    /** Drive again to somewhere the driver has already been. */
    onPickDrive: (Drives.Drive) -> Unit = {},
    /** Forget one drive. See Drives.remove. */
    onDeleteDrive: (Drives.Drive) -> Unit = {},
    onClearDrives: () -> Unit = {},
    /**
     * Vector Pro. Every one of these is inert until `ProCatalogue` has an
     * entry — see `ui.offersPro` — so a build with nothing gated carries the
     * callbacks and draws none of the surfaces.
     */
    onOpenPaywall: () -> Unit = {},
    onClosePaywall: () -> Unit = {},
    onBuy: (com.revenuecat.purchases.Package) -> Unit = {},
    onRestore: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
    onDismissProNotice: () -> Unit = {},
    /**
     * The Last Mile (V7 Phase 4). Inert until a journey has been composed.
     */
    onToggleJourney: () -> Unit = {},
    onScrubSunTime: (Long?) -> Unit = {},
    onToggleCooler: () -> Unit = {},
    /** Navigate on foot. See the same parameter on [VectorApp]. */
    onWalk: () -> Unit = {},
    /**
     * One zoom level closer / further out. See `MainActivity.zoomStep` for why
     * a whole level and not a fraction of one, and [MapControls] for why the
     * buttons sit where they do.
     */
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
) {
    // `resolvedTheme`, not the raw preference — SYSTEM is neither LIGHT nor
    // DARK, so comparing against LIGHT resolved it to the dark palette. The MAP
    // has resolved it properly since V3 (`MainActivity.applyStyle`), so the
    // default setting on a phone in light mode drew a light map underneath dark
    // panels. Fixed in both places: this one is what actually paints, and
    // `VectorApp` above had its own copy of the same comparison.
    val light = ui.settings.resolvedTheme(isSystemInDarkTheme()) == VectorStyle.MapTheme.LIGHT
    // The palette is the design system's; [VectorApp] provides the theme.
    // `light` is still read here because one decision is about the MAP rather
    // than about the palette — the status-bar gradient has to match the
    // cartography underneath it, not the chrome on top of it.
    val c = VectorTheme.colors
    // Read once: the turn-list sheet both APPEARS and hides the chrome it
    // covers, and those two decisions must not be able to disagree.
    val stepsOpen = ui.phase == Phase.NAVIGATING && ui.showSteps
    Box(Modifier.fillMaxSize()) {
        // ---- the status-bar scrim -------------------------------------
        //
        // Vector draws edge to edge, so outside NAVIGATING the system's own
        // wifi, signal and battery icons are composited directly over the
        // MAP — and a map is arbitrary content. Reported from the S24 that
        // the icons could not be seen at all; the immediate cause was the
        // app asking for the wrong icon colour (see
        // [statusBarWantsDarkIcons]), and fixing that makes them legible
        // over the ground colour.
        //
        // It does not make them legible over a place label, and the
        // screenshot that confirmed the fix has "Al Doha Al Jadeeda" set in
        // 20 sp bold across the battery indicator. A colour rule cannot
        // solve that, because both the label and the icons are drawn in the
        // theme's ink.
        //
        // So: a short gradient from the ground colour to nothing, exactly
        // the height of the status bar inset. Both reference products do
        // the same thing and for the same reason. Not a solid bar — that
        // would put a hard edge across the top of the map and cost the
        // driver a strip of cartography; a gradient reads as the map fading
        // out and cannot be mistaken for a UI element.
        //
        // Skipped while NAVIGATING, where the full-bleed maneuver band
        // already occupies that space and a scrim over it would only muddy
        // the one element that has to be read in sunlight.
        if (ui.phase != Phase.NAVIGATING) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(
                        Brush.verticalGradient(
                            listOf(c.field.copy(alpha = 0.92f), c.field.copy(alpha = 0f))
                        )
                    )
            )
        }

        // ---- top group ------------------------------------------------
        //
        // Two nested groups, and the split is the point.
        //
        // The OUTER one has no horizontal padding, because the navigation
        // band — maneuver banner, lane strip, "then" preview — is
        // **full-bleed**. It runs edge to edge and up behind the status
        // bar. That is the largest single composition change in V4: both
        // reference products draw the driving instruction as a band across
        // the display, and Vector drew it as a rounded translucent card
        // inset 12 dp with an 8 dp gap under the clock. The card was the
        // reason the HUD read as a phone application rather than as an
        // instrument (see `v4-evidence/` — `waze/04` and `gmaps/05`
        // against `vector/before-04`).
        //
        // The INNER one keeps the 12 dp margins, and holds everything that
        // genuinely is a card floating over a map: the search bar, the
        // destination chip, the exit badge, warnings.
        //
        // The single-phase-ownership rule from V3 is unchanged — the search
        // bar, the destination chip and the maneuver banner still cannot be
        // composed together, which is what stops the "overlapping bubbles"
        // the layout was rebuilt to prevent.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            if (ui.phase == Phase.NAVIGATING) {
                // WALKING and DRIVING are exclusive, and the branch is
                // here rather than inside each element for the reason the
                // phase split exists: two bands that can never be composed
                // together cannot overlap, and neither can drift into the
                // other's state. `ui.walk` is null on every car journey, so
                // a drive composes exactly what it composed before V7.4.
                val walk = ui.walk
                if (walk != null) {
                    WalkBanner(walk, ui.units, c)
                    // The attribute lines, on the same strip the lane
                    // diagram uses while driving — and present only when
                    // there is something known to put on them.
                    //
                    // TWO lines, and they are different kinds of fact:
                    //
                    //  * the crossing/stair attributes the backend
                    //    surveyed, which on most real Qatari crossings and
                    //    almost every staircase are absent, so this line is
                    //    usually not drawn at all;
                    //  * the route's modelled sun exposure, which is a
                    //    ROUTE fact rather than a maneuver attribute and
                    //    comes from `walk.shade` — a different provenance
                    //    with a different certainty, kept as its own line
                    //    so neither can be read as the other.
                    //
                    // Shade is NEVER allowed to displace the instruction:
                    // it is a secondary line on the strip below the band,
                    // and the band above it carries the maneuver, the
                    // off-route state and the arrival unchanged.
                    //
                    // It is also withheld in exactly the states where the
                    // instruction is withheld, for the same reason: while
                    // off-route the strip would be describing a walk the
                    // walker is demonstrably not on, and "Walk back to the
                    // route to continue" with "Mostly shaded (estimated)"
                    // under it reads as guidance about the ground between
                    // them. After arrival there is no walk left to
                    // describe.
                    val speaking = !walk.offRoute && !walk.rerouting && !walk.arrived
                    val detail = walk.instruction?.detail
                    val shade = if (speaking) walk.shade?.stripLine() else null
                    VectorMotion.Strip(detail != null || shade != null) {
                        WalkDetailStrip(detail, shade, c)
                    }
                } else {
                ManeuverBanner(ui, c)
                // Lane guidance is ATTACHED to the banner rather than
                // floating under it. §15: "the lane UI should feel
                // integrated with the maneuver rather than bolted
                // underneath it" — it was a separate rounded pill with a
                // gap above it, which is precisely bolted underneath.
                // `ui.laneGuidance`, not `currentManeuver.lanes`: the
                // strip is gated on how far the maneuver is and how fast
                // the driver is going, so it appears in time to change
                // lanes and not for the whole leg. See UiState.laneGuidance.
                val lanes = ui.laneGuidance
                VectorMotion.Strip(lanes.isNotEmpty()) {
                    LaneStrip(lanes, c)
                }
                VectorMotion.Strip(ui.nextManeuver != null) {
                    ui.nextManeuver?.let { ThenStrip(it, c) }
                }
                }
            }
            Column(
                Modifier
                    // Only when there is no band above to have cleared it.
                    .then(
                        if (ui.phase == Phase.NAVIGATING) Modifier
                        else Modifier.statusBarsPadding()
                    )
                    .padding(
                        horizontal = VectorTokens.Space.s12,
                        vertical = VectorTokens.Space.s8,
                    )
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
            ) {
                when (ui.phase) {
                    // EXPLORE's top group is now empty on purpose. The search
                    // entry moved into the discovery sheet at the bottom of the
                    // screen (see [ExploreSheet]) — it belongs with the results
                    // it filters and with the categories and recents it shares a
                    // job with, and a pill floating alone at the top of a map was
                    // the whole of this app's resting composition.
                    //
                    // The slot itself stays, because the warning strips below
                    // (error, GPS, snap) are composed in this Column and are
                    // still top-anchored: a positioning warning has to be near
                    // the map it is about, not at the bottom behind a sheet.
                    Phase.EXPLORE -> Unit
                    Phase.PREVIEW ->
                        DestinationChip(ui, c, onCancel, onSavePlace,
                                        onToggleJourney, onOpenPaywall)
                    Phase.NAVIGATING -> Unit
                }
                // The journey sits directly under the destination, in the
                // same column, because it is the rest of the answer to the
                // question the destination chip asks.
                if (ui.phase == Phase.PREVIEW) {
                    JourneyCard(ui, c, onScrubSunTime, onToggleCooler)
                }
                if (ui.phase == Phase.NAVIGATING) {
                    val m = ui.currentManeuver
                    VectorMotion.Strip(m?.exitRef != null) {
                        m?.exitRef?.let { ExitBadge(it, m.destination, c) }
                    }
                }
                VectorMotion.Strip(ui.error != null) {
                    ui.error?.let { ErrorBanner(it, c) }
                }
                VectorMotion.Strip(ui.snapWarningM != null) {
                    ui.snapWarningM?.let { SnapWarning(it, ui.units, c) }
                }
                // Why there is no walking route, kept distinct from
                // [ErrorBanner] above: a pedestrian network split is not a
                // failure, it is an answer, and on Qatar's foot graph it is
                // the common one. See [WalkRefusalBanner].
                VectorMotion.Strip(ui.walkRefusal != null) {
                    ui.walkRefusal?.let { WalkRefusalBanner(it, c) }
                }
                // Positioning trouble sits in the same slot as the other
                // things-you-should-know-about-your-position strips, one
                // below the instruction band. Deliberately NOT in place of
                // the band: the last maneuver Vector computed is still the
                // best guidance available, and replacing it with an
                // apology would take away the one thing the driver needs
                // while they are between the tunnel and the junction.
                VectorMotion.Strip(ui.gps.degraded) {
                    GpsWarning(ui.gps, c)
                }

                // EXPLORE's map controls, in the FLOW of the top group rather
                // than pinned to the corner.
                //
                // They were `align(Alignment.TopEnd)` on the outer Box, which
                // is absolute positioning — and it collided the moment anything
                // else wanted the top of the screen. The capture of the
                // location-off state shows the GPS banner running underneath
                // the zoom capsule with its sentence cut mid-word ("so Vector
                // ca…"), because a full-width banner and a corner-pinned column
                // cannot both have that space.
                //
                // Composed last in this Column, right-aligned, the controls sit
                // under whatever warnings are present and move down when one
                // appears — which is the responsive answer the brief asks for
                // instead of solving an overlap by nudging coordinates.
                if (ui.phase == Phase.EXPLORE) {
                    Box(Modifier.align(Alignment.End)) {
                        MapControls(
                            ui, c, onRecenter, onToggleOrientation, onToggleOverview,
                            onToggleVoice, onToggleContributing, onOpenSettings,
                            onOpenPaywall, onZoomIn, onZoomOut,
                        )
                    }
                }
            }
        }

        // The floating results panel that used to hang under the search pill
        // is gone. It was a card pinned 72 dp from the top with `imePadding`,
        // which meant that on a phone with the keyboard up the discovery
        // content had roughly a third of the display to live in and the
        // remaining two thirds were map nobody could see past the IME. All of
        // it — categories, saved places, recents, results, the empty state —
        // is now the sheet's content, where it gets the height it needs and
        // the keyboard pushes the surface instead of covering it. See
        // [ExploreSheet] and [SearchResultsBody].

        // ---- bottom group ---------------------------------------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = VectorTokens.Space.s12, vertical = VectorTokens.Space.s12)
                // One rule for every bottom-anchored surface. See
                // [VectorTokens.Size.bottomGap].
                .padding(bottom = VectorTokens.Size.bottomGap - VectorTokens.Space.s12)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The road the vehicle is on is NOT drawn over the map.
            //
            // It was, as a centred pill above the bottom controls, and it
            // was wrong in every phase for a different reason. Reported
            // twice from the S24 — *"it shows IBN KATHEER STREET in a
            // bubble, this shouldn't be there, it's just too big and too
            // consuming on the screen"*, and then, after it had merely been
            // made smaller, *"I am not happy with how the name of the
            // street is still on a bubble right above the GPS direction
            // icon while a user is driving"*.
            //
            // The complaint is not about the size. It is that a floating
            // bubble over the cartography, next to the vehicle, is the one
            // place a piece of secondary information must not be: it sits
            // in the part of the screen the driver is reading the road
            // from, and it moves with nothing.
            //
            // In EXPLORE the answer is that the map already gives it — V4's
            // font-stack fix made road labels render throughout, which is
            // the same release whose argument for the pill was that they
            // did not. While DRIVING the answer is that it belongs in the
            // furniture: it is now the trip bar's second line, beside the
            // distance it is context for. See [TripBar].
            //
            // Free either way: it rides along on the speed-limit lookup the
            // client already makes every 150 m. See `VectorApi.roadHere`.

            // A confirmation the driver can actually see, in PREVIEW.
            //
            // The bottom group's phase switch gives PREVIEW to the route
            // chooser, NAVIGATING to the trip bar and the status line to
            // EXPLORE only — so "Home set to Villaggio Mall", written by
            // `onSavePlace`, was going somewhere nothing renders. A
            // confirmation nobody can see is worse than none, because the
            // code reads as though the feature confirms itself.
            //
            // This slot is the one the road pill vacated and it is not
            // phase-owned, so PREVIEW can borrow it without competing with
            // the chooser below.
            VectorMotion.Strip(ui.phase == Phase.PREVIEW && ui.status.isNotEmpty()) {
                StatusLine(ui.status, ui.busy, c)
            }

            // The turn list is no longer one of these rows: it is a sheet
            // on the bottom EDGE of the screen and it owns everything
            // below the banner while it is open, so the chrome it covers
            // is not composed underneath it. See [StepSheet].
            if (!stepsOpen) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // The speedometer and the speed-limit sign are
                    // DRIVING instruments and are absent on foot. A
                    // pedestrian has no speed worth reading — at 1.35 m/s
                    // the receiver's estimate is the same size as its own
                    // error, which is why the walking follower refuses to
                    // use one — and a posted limit is a fact about a
                    // carriageway, not about a person walking beside it.
                    if (ui.phase == Phase.NAVIGATING && !ui.walking) {
                        // `md`, not `sm`. The two dials are both white
                        // discs, and 8 dp of light basemap between two
                        // white discs is not a gap the eye finds at a
                        // glance — reported as the limit roundel
                        // "overlapping" the speedometer, which by layout it
                        // never did. 12 dp plus the size difference the
                        // speedometer gained is what separates them; see
                        // [Speedometer].
                        Row(verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s12)) {
                            Speedometer(ui, c)
                            val limit = VectorMotion.rememberLast(ui.speedLimitKmh)
                            VectorMotion.Badge(ui.speedLimitKmh != null) {
                                limit?.let {
                                    SpeedLimitSign(
                                        ui.units.postedLimit(it), ui.units,
                                        inferred = ui.speedLimitInferred,
                                    )
                                }
                            }
                        }
                    } else Spacer(Modifier.width(1.dp))
                    // EXPLORE's controls are NOT here. The discovery sheet owns
                    // the bottom of that screen, and a floating column anchored
                    // to the bottom edge would be underneath it — which is
                    // exactly what the first build of the sheet did, and the
                    // screenshot shows a map with no zoom, no compass and no
                    // recenter at all. They move to the top-right corner, which
                    // the search pill vacated when it moved into the sheet.
                    //
                    // While NAVIGATING nothing changes: there is no sheet, the
                    // speedometer is beside them, and the column stays under the
                    // driver's hand where V4 put it.
                    if (ui.phase != Phase.EXPLORE) {
                        MapControls(
                            ui, c, onRecenter, onToggleOrientation, onToggleOverview,
                            onToggleVoice, onToggleContributing, onOpenSettings,
                            onOpenPaywall, onZoomIn, onZoomOut,
                        )
                    }
                }
            }
            when (ui.phase) {
                Phase.PREVIEW ->
                    RouteChooser(ui, c, onStart, onCancel, onChooseRoute, onWalk)
                Phase.NAVIGATING -> if (!stepsOpen) {
                    val walk = ui.walk
                    if (walk != null) WalkTripBar(walk, ui, c, onCancel)
                    else TripBar(ui, c, onCancel, onToggleSteps)
                }
                // No "Ready".
                //
                // `status` used to be seeded with the literal string
                // "Ready" at startup and after every journey, which put a
                // 12 sp grey word in the middle of an otherwise empty
                // bottom third of the screen — §40's "no placeholder UI",
                // and visible in `v4-evidence/vector/before-01-explore.png`.
                // It said nothing a driver could use. The line stays for
                // messages that are genuinely about something ("Arrived at
                // Villaggio Mall", "Finding routes…"); MainActivity no
                // longer sets the placeholder.
                // The arrival card takes the slot the status line would
                // have used, because it is the same statement made
                // properly. Both cannot be present: `arrive()` clears the
                // status when it sets the summary.
                // EXPLORE's arrival card and status line are NOT composed
                // here any more — they are the first things in the discovery
                // sheet (see [DiscoveryBody]).
                //
                // They had to move. The sheet owns the bottom of this phase, so
                // a panel drawn in the bottom group sits UNDERNEATH it: the
                // overlap assertion in `NavUiTest` caught exactly that, with
                // "Ready" and the sheet's own empty state occupying the same
                // 35 px of screen. Occlusion, not a near miss.
                //
                // In the sheet they are also in the right place. "Arrived at
                // Villaggio Mall" is a statement about the journey that just
                // ended, and the thing a driver does next is look for the next
                // destination — which is the surface it now sits at the top of.
                Phase.EXPLORE -> Unit
            }
        }

        // ---- the discovery sheet --------------------------------------
        //
        // EXPLORE only, and that is the phase-ownership rule this file has
        // enforced since V3 rather than a new exception to it: the sheet owns
        // the bottom of the screen in EXPLORE exactly as the route chooser owns
        // it in PREVIEW and the trip bar owns it while NAVIGATING, so no two of
        // them can be composed together and none can overlap another.
        //
        // Composed after the bottom group so it layers above the map controls
        // when dragged up, and before the modal sheets below so settings, the
        // turn list and the paywall still come over the top of it.
        // Not while a modal sheet is over it.
        //
        // Settings and the paywall are full-surface sheets with a scrim, and
        // the discovery sheet underneath them is invisible — but it was still
        // composed, which means a screen reader walked its search field, its
        // four category chips and every saved place and recent *behind* a modal
        // dialog. `NavUiTest` found the same thing from the other side: two
        // nodes reading "Home" and two reading "Clear" while the settings sheet
        // was open, so a test asking for "the Clear on the recents row" could
        // pick the one on a surface nobody can see.
        //
        // A covered surface is not composed. That is the same rule the turn
        // list already follows (`stepsOpen` suppresses the chrome it covers).
        if (ui.phase == Phase.EXPLORE && !ui.showSettings && !ui.showPaywall) {
            ExploreSheet(
                ui = ui,
                onOpenSearch = onOpenSearch,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onCloseSearch = onCloseSearch,
                onPick = onPick,
                onPickPlace = onPickPlace,
                onPickRecent = onPickRecent,
                onClearRecents = onClearRecents,
                onOpenSettings = onOpenSettings,
                arrivalContent = {
                    // Exactly the rule the bottom group used to enforce: the
                    // arrival summary and the status line are the same slot,
                    // and `arrive()` clears the status when it sets the
                    // summary, so they can never both be drawn.
                    //
                    // There is still no "Ready". `status` is only set for
                    // messages that are genuinely about something ("Arrived at
                    // Villaggio Mall", "Finding routes…"); the placeholder was
                    // removed in V4 and nothing here brings it back.
                    if (ui.arrival != null) {
                        VectorMotion.Panel(true) {
                            ui.arrival?.let {
                                ArrivalCard(it, ui.units, c, onDismissArrival, onPick)
                            }
                        }
                    } else {
                        VectorMotion.Strip(ui.status.isNotEmpty()) {
                            StatusLine(ui.status, ui.busy, c)
                        }
                    }
                },
            )
        }

        // Anchored to the bottom edge rather than to the bottom group, so
        // it rises from off-screen instead of growing inside a stack.
        VectorMotion.Sheet(stepsOpen, Modifier.align(Alignment.BottomCenter)) {
            StepSheet(ui, c, onToggleSteps)
        }

        if (ui.showSettings) {
            SettingsSheet(
                ui, c, onSettingsChange, onClearRecents, onCloseSettings,
                onClearPlace, onPickDrive, onDeleteDrive, onClearDrives,
                onOpenPaywall, onRestore, onManageSubscription,
            )
        }

        // Over the settings sheet, not instead of it: the driver opened
        // Pro FROM settings and closing the paywall must put them back
        // where they were, not on the map.
        if (ui.showPaywall) {
            PaywallSheet(ui, c, onBuy, onRestore, onClosePaywall, onDismissProNotice,
                         onRetry = onOpenPaywall)
        }
    }
}

// ---------------------------------------------------------------------------
// Map controls
// ---------------------------------------------------------------------------

/**
 * The controls that act on the map rather than on the journey.
 *
 * **What is here and what is in settings is a driving-safety decision, not a
 * space one.** North-up-vs-heading-up and 2D-vs-3D are set-once preferences: a
 * driver picks one and keeps it for years. Putting them on the HUD would add two
 * permanent buttons to be pressed by accident at 100 km/h in exchange for a
 * choice nobody re-makes, so they live in [SettingsSheet] — and the brief's rule
 * is explicit that a control should not exist because a competitor has one.
 *
 * What stays reachable while driving is only what is time-critical:
 *
 * * **Recenter**, and only when the camera is not already on the vehicle. A
 *   control that is always present but usually a no-op teaches the driver to
 *   ignore it.
 * * **Overview**, because "how much further and which way" is a question that
 *   arrives mid-journey.
 * * **The compass**, which is an INDICATOR first: it states which way is up and
 *   whether the driver has rotated the map by hand. Tapping it is the fast way
 *   back to north.
 * * **Mute**, which has to be instant — a passenger on the phone is exactly
 *   when you need it, and a settings sheet is three taps too many.
 * * **The contribution toggle**, kept on the HUD deliberately: it is reachable
 *   while driving because that is when it is relevant, and its label states what
 *   it does rather than a brand word, so consent is legible at the moment it is
 *   given (adr-0068).
 * * **Zoom in and zoom out**, added in V8. See below — they are the one pair
 *   that the paragraph above argues against and that still earns its place.
 *
 * Settings itself is NOT reachable while navigating. Opening a preferences
 * sheet over a live maneuver card is the interaction this whole layout exists to
 * prevent.
 *
 * ## Zoom, against this file's own rule
 *
 * The rule above rejects a control that is a set-once preference nobody
 * re-makes, and rejects a control that exists because a competitor has one.
 * Zoom is neither. It is a decision a driver re-makes several times in a
 * journey — out to see what the motorway is doing two junctions ahead, back in
 * to read which slip road the sign means — and until now the ONLY way to make
 * it was a two-finger pinch, which is a gesture that wants a hand off the wheel
 * and an eye on the glass to place. A button is the version of that decision
 * that can be made by feel.
 *
 * Two choices follow from the safety argument rather than from convention:
 *
 *  * **They sit at the TOP of the column, furthest from the resting thumb.** A
 *    mis-tap here is not harmless. A zoom press is a driver-owned zoom (see
 *    `MainActivity.zoomStep`), so an accidental one hands the driver the zoom
 *    and stops the maneuver camera acting until they undo it — a more
 *    persistent consequence than any other control in this column has, and
 *    therefore the one to put furthest out of the way.
 *  * **They are present in every phase**, unlike overview and voice. Zoom is
 *    not a driving-only need, and a control that appears and disappears with
 *    the phase is one the driver has to look for.
 */
@Composable
private fun MapControls(
    ui: UiState,
    c: VectorColors,
    onRecenter: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleOverview: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleContributing: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPaywall: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        // 12, not 8. The controls are GROUPS now (see [ControlStack]), and the
        // gap between two groups has to be visibly larger than the gap between
        // two controls inside one — otherwise grouping communicates nothing.
        verticalArrangement = Arrangement.spacedBy(VectorTokens.Space.s12),
    ) {
        if (ui.phase != Phase.NAVIGATING) {
            // Drawn only for a free driver in a build that has something to
            // sell, so a subscriber never sees it and a self-hosted build has
            // no such thing. See [ProPill].
            // Advertised only while there is something to buy. `offersPro`
            // says this build has a tier the driver does not have; it says
            // nothing about whether the store can complete a purchase, and a
            // pill that leads to a price list with no prices is how a driver
            // concludes the payment is broken. See `UiState.proSellable`.
            if (ui.proSellable) ProPill(c, onOpenPaywall)
            // Settings has left this column. Every other control here acts on
            // the MAP — zoom, orientation, recenter, overview — and settings
            // acted on the app, which made it the one button in a control
            // cluster that did something of a different kind. It now sits
            // beside the search entry in the discovery sheet; see
            // [ExploreSearchHeader]. In PREVIEW, where there is no sheet, it is
            // reachable by cancelling back to EXPLORE, which is the same number
            // of taps as before for a control nobody opens mid-route.
        }
        // ONE capsule, not a column of loose discs.
        //
        // Reviewed against the reference set on Mobbin: Journal, Slopes, My
        // BMW, Grab, Strava, komoot and Tesla all put their map controls in a
        // single floating container with hairline separators between the
        // controls inside it. Vector had four to six detached 48 dp white
        // circles stacked down the edge of the map, which is the Android-maps
        // default and reads as scattered rather than composed — it was the
        // thing about this screen that still looked stock after everything
        // else had been rebuilt.
        //
        // Grouping is not only cosmetic. Zoom in, zoom out and the compass all
        // act on the camera, so they belong to one object; a control that acts
        // on something else (overview, voice, recenter) is a SEPARATE group
        // below, and the 12 dp gap between groups against the 0 dp between
        // members is what says so.
        ControlStack(c) {
            // Plus above minus, always in that order and never separated: the
            // pair is what makes either one legible, because a lone bar is not
            // a control anybody can name.
            StackControl(VectorIcons.Glyph.ZOOM_IN, "Zoom in", c.inkSecondary, c, onClick = onZoomIn)
            StackDivider(c)
            StackControl(VectorIcons.Glyph.ZOOM_OUT, "Zoom out", c.inkSecondary, c, onClick = onZoomOut)
            StackDivider(c)
            Compass(ui, c, onToggleOrientation)
        }
        if (ui.phase == Phase.NAVIGATING) {
            val inOverview = ui.cam.mode == CameraMode.OVERVIEW
            ControlStack(c) {
                StackControl(
                    glyph = if (inOverview) VectorIcons.Glyph.FOLLOW else VectorIcons.Glyph.OVERVIEW,
                    label = if (inOverview) "Follow the vehicle" else "Show the whole route",
                    tint = if (inOverview) c.primary else c.inkSecondary,
                    c = c,
                    onClick = onToggleOverview,
                )
            }
        }
        // Only when it would do something. See the KDoc above.
        //
        // Animated in and out since V4: it is the one control that comes and
        // goes, and 48 dp of button appearing under the driver's thumb between
        // two frames is how a mis-tap happens. The transition is 220 ms, which
        // is long enough to be seen and far too short to be waited for.
        // Recenter, and the two rules that stop it shoving the column around.
        //
        // ## 1. Never while the whole route is being shown
        //
        // Pressing "show the whole route" moves the camera off the vehicle, so
        // `needsRecenter` became true and this capsule appeared — in the MIDDLE
        // of the column, pushing voice and the sharing badge down, then pulling
        // them back up when the driver pressed follow again. Reported as "I
        // don't like how the buttons shift when I press the full camera view
        // while a nav is on".
        //
        // It should never have appeared in that state anyway: in overview the
        // overview control already reads "Follow the vehicle" and does exactly
        // this. Two controls for one action, and the redundant one was the one
        // moving the furniture.
        //
        // ## 2. It is composed at the column's ANCHORED end
        //
        // A driver can still pan the map by hand, and then the capsule is
        // genuinely needed. While NAVIGATING this column is bottom-aligned, so
        // a control added at the TOP grows it upward and nothing below moves;
        // in EXPLORE the column hangs from the top of the screen, so the same
        // control goes LAST and grows it downward. Either way it extends the
        // free end and never displaces a sibling.
        val showRecenter = ui.cam.needsRecenter && ui.cam.mode != CameraMode.OVERVIEW
        val recenter: @Composable () -> Unit = {
            VectorMotion.Badge(showRecenter) {
                // Its OWN capsule, and the only tinted one — the brand fill,
                // not the soft container. At `primaryContainer` this was a dark
                // navy square with a pale glyph on it, which against a night map
                // is one dark shape on another: the control a driver is actively
                // hunting for was the least visible thing in the column.
                ControlStack(c, tint = c.primary) {
                    StackControl(
                        VectorIcons.Glyph.RECENTER, "Put the camera back on the vehicle",
                        c.onPrimary, c, onClick = onRecenter,
                    )
                }
            }
        }
        if (ui.phase == Phase.NAVIGATING) recenter()

        if (ui.phase == Phase.NAVIGATING) {
            // The glyph carries HOW MUCH sound, not just on/off — three waves,
            // one wave, an exclamation, a slash. §27's four modes; the shape
            // means the state is not colour-only.
            ControlStack(c) {
                StackControl(
                    glyph = ui.settings.voice.glyph,
                    label = "Voice: ${ui.settings.voice.label}. Tap to change.",
                    tint = if (ui.settings.voice == VoiceMode.OFF) c.inkMuted else c.primary,
                    c = c,
                    onClick = onToggleVoice,
                )
            }
            // A small, non-interactive marker that data is being sent, shown
            // only for the few seconds after a batch actually leaves the
            // phone. It replaces a toggle labelled "Share traffic", which was
            // reported as vague and was: the words do not say who is sharing
            // what with whom, and a two-word pill has nowhere to explain it.
            // The DECISION now lives in the settings sheet next to a sentence
            // describing what leaves the phone; what appears here is the
            // EVENT. See [SharingBadge].
            SharingBadge(ui, c)
        }
        // See the ordering note above: top-anchored column, so it goes last.
        if (ui.phase != Phase.NAVIGATING) recenter()
    }
}

/**
 * How long the sending notice stays up after a batch has gone.
 *
 * Four seconds. Long enough to be caught in peripheral vision and read on the
 * next glance down, which for a driver is the unit this has to be measured in —
 * anything under about two seconds is a flicker they will only ever half-see
 * and then wonder about. Short enough that it is gone again before the next
 * maneuver.
 *
 * Deliberately not in [VectorTokens.Motion]: that scale is about how long a
 * change takes, and its longest band is a camera flight. This is how long a
 * finished thing REMAINS, which is a different kind of number and would be
 * misread as a transition duration if it sat next to them.
 *
 * `internal` rather than private so `NavUiTest` can assert the pill is gone
 * again after it, rather than hardcoding a second copy of the number.
 */
internal const val SHARING_NOTICE_MS = 4_000L

/**
 * "Vector just sent anonymised speed data."
 *
 * ## Why this stopped being permanent
 *
 * It was drawn for the whole journey whenever `settings.contributing` was on,
 * which made it a fixture of the production driving screen — a pill that never
 * changes is furniture, and furniture is what a driver stops seeing. Worse, it
 * was not even reporting what it claimed to: `contributing` is a PREFERENCE,
 * so the pill said "Sending speed data" on a phone with no network, with an
 * empty probe buffer, and while parked. The one state it could not distinguish
 * was the one it was named after.
 *
 * ## What it says now, and why the transparency is intact
 *
 * It is keyed on [UiState.probesSent], which `MainActivity.collectProbe`
 * increments only when `POST /probes` has actually returned success. So the
 * pill now appears when — and only when — data has genuinely left the device,
 * for [SHARING_NOTICE_MS], and `ProbeBuffer` flushes every 30 points or 30
 * seconds, so a contributing driver sees it repeatedly through a drive without
 * it ever becoming part of the scenery.
 *
 * That is strictly MORE transparency than the permanent badge gave, not less,
 * which is the bar the original KDoc set and the reason this was not simply
 * deleted: the driver was always meant to be able to see that it is happening.
 * The standing answer to "is this on at all?" lives where it can be explained —
 * the settings sheet, beside the sentence describing what leaves the phone —
 * and that is also where it is withdrawn. Consent was never meant to be
 * answered by a badge.
 *
 * Rejected: hiding it behind `BuildConfig.DEBUG`. A build flag would have made
 * the only people who can see the upload happening the people who wrote it,
 * which turns a transparency feature into a diagnostic and is the opposite of
 * adr-0068's intent.
 *
 * Still deliberately not a button. A mis-tap under the driver's thumb must not
 * be able to silently end the contribution they opted into.
 */
@Composable
private fun SharingBadge(ui: UiState, c: VectorColors) {
    // `probesSent` is monotonic within a drive and reset to zero when consent
    // is withdrawn, so a CHANGE in it is exactly one successful upload. The
    // zero guard matters: the effect also runs on first composition, and
    // without it every navigating driver would be greeted by a notice about an
    // upload that has not happened.
    var justSent by remember { mutableStateOf(false) }
    LaunchedEffect(ui.probesSent) {
        if (ui.probesSent == 0) return@LaunchedEffect
        justSent = true
        kotlinx.coroutines.delay(SHARING_NOTICE_MS)
        justSent = false
    }
    VectorMotion.Badge(ui.contributing && justSent) { SharingPill(c) }
}

/** The notice itself. Separated so [SharingBadge] holds only the timing. */
@Composable
private fun SharingPill(c: VectorColors) {
    Surface(color = c.surface, shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20)) {
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = "Anonymised speed data for the roads " +
                        "you drive has just been sent"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(8.dp)) {
                drawCircle(c.success, radius = size.minDimension / 2f)
            }
            Spacer(Modifier.width(VectorTokens.Space.s8))
            // Past tense, because that is now what is true. "Sending speed
            // data" described a preference being on; this describes an upload
            // that has completed, and the difference is the whole point of the
            // change — see [SharingBadge].
            Text(
                "Speed data sent", color = c.inkMuted,
                style = VectorTheme.typography.caption,
            )
        }
    }
}

/**
 * Which way is up, and whether the driver put it there.
 *
 * Three states, three glyphs, because the brief is right that the control has
 * to communicate state at a glance:
 *
 * * **N** — north-up. The map does not rotate.
 * * **↑** — heading-up. The map faces the direction of travel.
 * * **↺** in the accent colour — the driver rotated the map by hand and it is
 *   neither. This is the state that most needs saying, because it is the one
 *   the driver did not choose in settings and may not remember causing.
 *
 * The glyph carries a `contentDescription` naming the state in words, so the
 * information is not colour- or shape-only (§20 of the brief) and a screen
 * reader gets the same answer a sighted driver gets.
 */
@Composable
private fun Compass(ui: UiState, c: VectorColors, onToggle: () -> Unit) {
    val manual = ui.cam.manualBearing != null
    val headingUp = ui.cam.orientation == MapOrientation.HEADING_UP
    val glyph = when {
        manual -> VectorIcons.Glyph.ROTATED
        headingUp -> VectorIcons.Glyph.HEADING_UP
        else -> VectorIcons.Glyph.COMPASS_NORTH
    }
    val label = when {
        manual -> "Map rotated by hand — tap to face north"
        headingUp -> "Facing the direction of travel — tap for north up"
        else -> "North up — tap to face the direction of travel"
    }
    // The rose ROTATES to state the map's actual bearing.
    //
    // This is what a drawn glyph buys that the letter "N" could not: when the
    // driver has turned the map by hand the needle points at where north
    // actually is, rather than merely admitting that it is not up. Google Maps
    // does the same thing with a red north needle
    // (`v4-evidence/gmaps/05-nav-hud.png`), and it is the difference between a
    // control that reports a STATE and one that reports a VALUE.
    //
    // Accumulated via shortestAngle rather than animated to an absolute value,
    // so a compass crossing north travels 2 degrees and not 358 — §8's rule,
    // applied to the control as well as to the map.
    val target = if (manual) -(ui.cam.manualBearing ?: 0.0).toFloat() else 0f
    val spin = remember { Animatable(target) }
    LaunchedEffect(target) {
        val delta = MapCamera.shortestAngle(spin.value.toDouble(), target.toDouble()).toFloat()
        spin.animateTo(
            targetValue = spin.value + delta,
            animationSpec = tween(
                VectorTokens.Motion.MEDIUM,
                easing = VectorTokens.Motion.Standard,
            ),
        )
    }
    // A cell, not its own disc. The compass is composed inside the camera
    // [ControlStack] alongside the zoom pair, and a control that drew its own
    // white circle inside the capsule would be a button inside a button.
    StackControl(
        glyph = glyph,
        label = label,
        tint = if (manual) c.primary else c.inkSecondary,
        c = c,
        rotationDeg = if (glyph == VectorIcons.Glyph.COMPASS_NORTH) spin.value else 0f,
        onClick = onToggle,
    )
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

/**
 * The search field.
 *
 * ## The two-tap keyboard
 *
 * Reported from the S24: *"the search bar needs to be tapped twice for the
 * keyboard to pop up."* Tapping the collapsed bar set `searching = true`, which
 * swapped a [TextField] into the tree — and **nothing asked for focus**. So the
 * first tap produced a field, and the second tap (now landing on the field
 * itself) focused it and raised the IME. The [FocusRequester] below closes that:
 * the field takes focus the moment it appears, which is the only behaviour that
 * makes sense for a field the user opened deliberately.
 *
 * This also corrects a conclusion from the V2 pass, which recorded the missing
 * keyboard under `adb shell input tap` as *"most likely an artefact of injected
 * taps rather than a defect"* and marked it NOT VERIFIED pending a real finger.
 * A real finger found it. It was a defect, and `mInputShown=false` was the
 * honest reading of it all along — the app never asked for the keyboard, so of
 * course the IME never showed. Worth recording: "probably the harness" was the
 * comfortable explanation and it was wrong.
 *
 * [keyboardController] is used as well as the focus request. Requesting focus
 * usually raises the IME on its own, but not reliably when the window is in
 * edge-to-edge mode with `setDecorFitsSystemWindows(false)`, and a search box
 * that sometimes opens a keyboard is worse than one that never does.
 */
@Composable
private fun SearchBar(
    ui: UiState,
    onOpen: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(ui.searching) {
        if (ui.searching) {
            // runCatching: requestFocus throws if the node is not attached yet,
            // and a race here must not crash the app on a search tap.
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    // Resting and active are two different controls, not one control in two
    // modes — see `VectorSearchButton` for why they are separate components.
    // They share a height, a radius, a leading glyph and an elevation, so the
    // transition between them moves nothing on screen.
    if (!ui.searching) {
        VectorSearchButton(
            label = "Where to?",
            onClick = onOpen,
            icon = VectorIcons.glyphIcon(VectorIcons.Glyph.SEARCH),
            testTag = "search:rest",
        )
    } else {
        VectorSearchField(
            value = ui.query,
            onValueChange = onQueryChange,
            placeholder = "Where to?",
            onSearch = onSearch,
            onClose = onClose,
            onClear = { onQueryChange("") },
            loading = ui.searchInFlight,
            closeIcon = VectorIcons.glyphIcon(VectorIcons.Glyph.BACK),
            clearIcon = VectorIcons.glyphIcon(VectorIcons.Glyph.CLOSE),
            focusRequester = focus,
            testTag = "search:field",
        )
    }
}

/**
 * Search results.
 *
 * Each row leads with the name and then says what it is AND how far away —
 * distance is what disambiguates the four branches of the same chain, and it
 * was missing, so the list was four identical rows.
 */
@Composable
private fun Results(
    results: List<VectorApi.Place>,
    query: String,
    myLocation: dev.vector.geo.LngLat?,
    units: Units,
    c: VectorColors,
    onPick: (VectorApi.Place) -> Unit,
) {
    VectorCard(
        modifier = Modifier.fillMaxWidth(),
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        elevation = VectorTheme.elevation.floating,
        contentPadding = PaddingValues(VectorTokens.Space.s4),
        testTag = "results",
    ) {
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            itemsIndexed(results) { i, p ->
                val away = myLocation?.let {
                    dev.vector.geo.RouteGeometry.haversineM(it.lng, it.lat, p.position.lng, p.position.lat)
                }
                // A row, not a `VectorListRow`: this one carries a highlighted
                // title (see [highlightMatch]) and a right-hand distance column,
                // and a generic row that grew both would be a worse component for
                // every other list in the app.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = VectorTokens.Size.row)
                        .vectorPressable(pressScale = 0.99f) { onPick(p) }
                        .padding(
                            horizontal = VectorTokens.Space.s12,
                            vertical = VectorTokens.Space.s12,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A tinted tile per row rather than a bare glyph: it gives
                    // the list a left edge to scan down, and the tint is derived
                    // from the place's own name so the same place keeps the same
                    // colour on every launch.
                    VectorMediaTile(
                        key = p.name,
                        modifier = Modifier.size(VectorTokens.Size.control),
                        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r12),
                        icon = VectorIcons.glyphIcon(VectorIcons.Glyph.PLACE),
                        height = VectorTokens.Size.control,
                    )
                    Spacer(Modifier.width(VectorTokens.Space.s12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            highlightMatch(titleCase(p.name), query, c),
                            style = VectorTheme.typography.bodyStrong,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // categoryLabel, never `kind`. `kind` is the basemap
                        // layer the feature came from, so it read "poi" for every
                        // POI in the country — jargon shipped to a driver.
                        p.categoryLabel?.let {
                            Spacer(Modifier.height(VectorTokens.Space.s2))
                            Text(
                                it,
                                color = c.inkMuted,
                                style = VectorTheme.typography.metadata,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Distance in its own right-hand column.
                    //
                    // It used to be concatenated into the detail line —
                    // "Parking · 8.7 km away" — so comparing distances meant
                    // reading four sentences. In a column they form a scannable
                    // vertical stack, which is how both references present it and
                    // it is the whole reason the number is there.
                    away?.let {
                        Spacer(Modifier.width(VectorTokens.Space.s8))
                        Text(
                            shortDistance(it, units),
                            color = c.inkMuted,
                            style = VectorTheme.typography.metadata,
                            maxLines = 1,
                        )
                    }
                }
                if (i < results.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = VectorTokens.Space.s12)
                            .height(1.dp)
                            .background(c.border),
                    )
                }
            }
        }
    }
}

/**
 * Title-case a shouted OSM name.
 *
 * `VILLAGGIO MALL` is what the extract actually contains, and it is what
 * Vector rendered — see `v4-evidence/vector/before-02-search.png`, where the
 * top result is in caps and the two below it are not, so the list looks
 * broken. Both references normalise. OSM has no case convention and never
 * will; the client is the only place this can be fixed.
 *
 * Only touches strings that are **entirely** upper case, so an acronym inside
 * a normal name survives ("Souq Waqif KFC" is left alone) and a deliberately
 * capitalised brand in an otherwise-mixed name is not damaged. Words of one or
 * two letters are left up, which keeps "KFC" as "KFC" rather than "Kfc" when
 * the whole name is the acronym.
 */
internal fun titleCase(name: String): String {
    if (name.none { it.isLetter() }) return name
    // Latin-only check: an Arabic name has no case and must not be touched.
    val latin = name.filter { it in 'A'..'Z' || it in 'a'..'z' }
    if (latin.isEmpty() || latin.any { it.isLowerCase() }) return name
    return name.split(' ').joinToString(" ") { word ->
        if (word.length <= 3 && word.all { it.isUpperCase() || !it.isLetter() }) word
        else word.lowercase().replaceFirstChar { it.uppercase() }
    }
}

/**
 * Dim the part of a result the driver typed; keep the rest bright.
 *
 * Borrowed from Google Maps, which renders the matched prefix in grey and the
 * remainder in bold white — so "Villaggio **Mall**", "Villaggio **mall gate
 * 3**", "Villaggio **mall gate 6**" put the emphasis exactly on what differs
 * between the rows (`v4-evidence/gmaps/02-search-results.png`).
 *
 * It looks backwards for a second and then obviously right: you already know
 * what you typed, so highlighting it tells you nothing. What you are scanning
 * for is the disambiguator.
 *
 * Case-insensitive, first occurrence only. Falls back to the plain name when
 * there is no match, which is the case for a result matched on its
 * transliteration rather than its literal text.
 */
@Composable
private fun highlightMatch(name: String, query: String, c: VectorColors): AnnotatedString {
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else name.indexOf(q, ignoreCase = true)
    return buildAnnotatedString {
        if (at < 0) {
            withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.Medium)) { append(name) }
            return@buildAnnotatedString
        }
        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.Medium)) {
            append(name.substring(0, at))
        }
        withStyle(SpanStyle(color = c.inkMuted, fontWeight = FontWeight.Normal)) {
            append(name.substring(at, at + q.length))
        }
        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.Bold)) {
            append(name.substring(at + q.length))
        }
    }
}

/** Said out loud rather than left as an empty panel. */
@Composable
private fun NoResults(query: String, c: VectorColors) {
    VectorCard(
        modifier = Modifier.fillMaxWidth(),
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        elevation = VectorTheme.elevation.floating,
        contentPadding = PaddingValues(0.dp),
        testTag = "no-results",
    ) {
        VectorEmptyState(
            title = "Nothing found for \"$query\"",
            body = "Try a shorter name, or long-press the map to drop a pin",
            icon = VectorIcons.glyphIcon(VectorIcons.Glyph.SEARCH),
            testTag = "no-results:body",
        )
    }
}

/**
 * Home and Work.
 *
 * Above [RecentList] in the same slot, and only when at least one is set. Two
 * rows rather than a favourites list: see [Places] for what that trade buys and
 * what it refuses.
 *
 * The stored NAME is shown beside the slot label rather than instead of it —
 * "Home · Villaggio Mall" — because the label is what the driver is looking for
 * and the name is how they check they set it to the right place. Waze shows the
 * same pair.
 */
@Composable
private fun PlaceList(
    places: List<Places.Saved>,
    c: VectorColors,
    onPick: (Places.Saved) -> Unit,
) {
    VectorCard(
        modifier = Modifier.fillMaxWidth(),
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        elevation = VectorTheme.elevation.floating,
        contentPadding = PaddingValues(VectorTokens.Space.s4),
        testTag = "places",
    ) {
        Column {
            for (place in places) {
                VectorListRow(
                    title = place.slot.label,
                    subtitle = titleCase(place.name),
                    // The row reads "Home / Msheireb" on screen, which is two
                    // fragments and says nothing about what tapping it does. The
                    // spoken form states the verb, and the stored name is the
                    // raw one rather than the title-cased display form so a
                    // screen reader hears the place as the user set it.
                    contentDescription = "Navigate to ${place.slot.label}, ${place.name}",
                    leading = {
                        VectorMediaTile(
                            key = place.slot.label,
                            modifier = Modifier.size(36.dp),
                            shape = VectorTheme.shapes.pill,
                            icon = VectorIcons.glyphIcon(
                                if (place.slot == Places.Slot.HOME) VectorIcons.Glyph.HOME
                                else VectorIcons.Glyph.WORK,
                            ),
                            height = 36.dp,
                        )
                    },
                    onClick = { onPick(place) },
                    testTag = "place:${place.slot.name.lowercase()}",
                )
            }
        }
    }
}

/**
 * Where the driver has been.
 *
 * Shown in the results slot when the search box is open and empty, which is the
 * moment a navigator should be most useful and Vector was previously blank. The
 * commute is the single most likely destination and it was costing a full
 * retype every morning.
 */
@Composable
private fun RecentList(
    recents: List<Recents.Entry>,
    c: VectorColors,
    onPick: (Recents.Entry) -> Unit,
    onClear: () -> Unit,
) {
    VectorCard(
        modifier = Modifier.fillMaxWidth(),
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        elevation = VectorTheme.elevation.floating,
        contentPadding = PaddingValues(VectorTokens.Space.s4),
        testTag = "recents",
    ) {
        Column {
            // The section label and its action, on one row.
            //
            // "RECENT" stays in the app's one sanctioned all-caps role rather
            // than becoming a sentence-case title, and the literal string is
            // load-bearing: `uidriver.sh` and three device harnesses find this
            // list by it.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = VectorTokens.Space.s16,
                        end = VectorTokens.Space.s8,
                        top = VectorTokens.Space.s12,
                        bottom = VectorTokens.Space.s4,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "RECENT",
                    style = VectorTheme.typography.eyebrow,
                    color = c.inkMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Clear",
                    style = VectorTheme.typography.bodyStrong,
                    color = c.primaryText,
                    modifier = Modifier
                        .clip(VectorTheme.shapes.pill)
                        .vectorPressable(pressScale = 0.96f, onClick = onClear)
                        .padding(horizontal = VectorTokens.Space.s12, vertical = VectorTokens.Space.s8),
                )
            }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                itemsIndexed(recents) { _, r ->
                    VectorListRow(
                        title = titleCase(r.name),
                        leading = {
                            VectorMediaTile(
                                key = r.name,
                                modifier = Modifier.size(36.dp),
                                shape = VectorTheme.shapes.pill,
                                icon = VectorIcons.glyphIcon(VectorIcons.Glyph.HISTORY),
                                height = 36.dp,
                            )
                        },
                        onClick = { onPick(r) },
                    )
                }
            }
        }
    }
}

/**
 * Category shortcuts: fuel, food, parking, pharmacy.
 *
 * Both references put a row of these under the search field — Waze's is
 * `Saved / Crisis / Fuel / Food` (`v4-evidence/waze/02-search-open.png`),
 * Google's is `Restaurants / Coffee / …`. Vector had none, and the
 * geocoder has answered category queries since V3 gave POIs a humanised
 * `category`: 15,231 POIs, 7,924 of them named.
 *
 * **Four, and only four.** Each one has to be a thing a driver actually looks
 * for from behind the wheel, which is the test that excludes most of what could
 * go here: you search for fuel because the needle is low, for parking because
 * you have arrived, for food and a pharmacy because they are errands with a
 * deadline. A "Shopping" chip would be a category browser, and §36 is explicit
 * that a longer feature list is not the goal.
 *
 * Each chip issues an ordinary [onSearch] with its term, so there is no new
 * endpoint and no new failure mode — a chip is a query the driver did not have
 * to type.
 */
@Composable
private fun CategoryChips(c: VectorColors, onSearch: (String) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
        // The chips run to the screen edge rather than stopping short of it, so
        // the last one is visibly cut — the reference's own cue that the row
        // scrolls. A row that fits exactly reads as the whole list.
        contentPadding = PaddingValues(horizontal = VectorTokens.Space.s2),
    ) {
        items(SEARCH_CATEGORIES) { (label, term) ->
            VectorChip(
                label = label,
                selected = false,
                onClick = { onSearch(term) },
                leadingIcon = SEARCH_CATEGORY_ICON(label),
                testTag = "chip:$label",
            )
        }
    }
}

/**
 * The glyph for a category chip.
 *
 * Mapped by the label the chip already carries rather than by index, so adding a
 * category to [SEARCH_CATEGORIES] cannot silently shift every icon one place to
 * the left — which is the failure a parallel list of glyphs always eventually
 * has.
 */
private fun SEARCH_CATEGORY_ICON(label: String): VectorIcon? = when (label.lowercase()) {
    "fuel" -> VectorIcons.glyphIcon(VectorIcons.Extra.FUEL)
    "food", "coffee" -> VectorIcons.glyphIcon(VectorIcons.Extra.COFFEE)
    "parking" -> VectorIcons.glyphIcon(VectorIcons.Extra.PARKING)
    // No glyph, no icon. The first version of this mapped everything to the
    // nearest available picture, which put a flag on "Fuel" and a house on
    // "Parking" — an icon that means something else is worse than no icon, and
    // a text-only chip is still a perfectly good chip.
    else -> null
}

/**
 * The category chips, as (label, query).
 *
 * The query is the OSM tag value the geocoder indexes, not the label: a driver
 * reads "Fuel" and the index knows `fuel`.
 */
internal val SEARCH_CATEGORIES = listOf(
    "Fuel" to "fuel",
    "Food" to "restaurant",
    "Parking" to "parking",
    "Pharmacy" to "pharmacy",
)

@Composable
private fun DestinationChip(
    ui: UiState,
    c: VectorColors,
    onCancel: () -> Unit,
    onSavePlace: (Places.Slot) -> Unit,
    onToggleJourney: () -> Unit = {},
    onOpenPaywall: () -> Unit = {},
) {
    Surface(
        color = c.surfaceFloating,
        // Continuous, like every other card that floats over the map. A
        // circular-arc r16 next to the sheet's squircle is the mismatch the
        // shape scale exists to prevent.
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // A place header, not a single crowded row.
        //
        // Everything used to sit on ONE line: the origin, the destination, the
        // journey summary, two save controls and a close control. On a 360 dp
        // phone the three 48 dp controls take 144 dp of a 336 dp row, so the
        // name got under half the width and "City Center Doha" wrapped onto two
        // lines with "7 min drive · 7 min walk" wrapping under it — the capture
        // of this screen shows both.
        //
        // Split into three bands it reads in the order the question is asked:
        // WHERE you are going (with its symbol), WHAT it costs, and then what
        // you can do about it. The name gets the full width; the actions get
        // labels instead of being three unexplained glyphs.
        Column(
            Modifier.padding(
                start = VectorTokens.Space.s12,
                end = VectorTokens.Space.s12,
                top = VectorTokens.Space.s12,
                bottom = VectorTokens.Space.s12,
            ),
        ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The destination's own family tile, so the card the driver is
            // looking at matches the row they tapped to get here. Without it
            // the transition from a list of coloured places to a plain text
            // header loses the one thing that identified the place.
            val family = PlaceFamily.of(ui.destinationName)
            Box(
                Modifier
                    .size(VectorTokens.Size.control)
                    .clip(VectorTheme.shapes.continuous(VectorTokens.Radius.r12))
                    .background(family.container(c)),
                contentAlignment = Alignment.Center,
            ) {
                family.icon.render(20.dp, family.onContainer(c))
            }
            Spacer(Modifier.width(VectorTokens.Space.s12))
            Column(Modifier.weight(1f)) {
                // BOTH ends, not just the destination.
                //
                // Vector said "Destination / VILLAGGIO MALL" and left the
                // origin implicit. Both references state the pair — Waze
                // "Your location → Villaggio Mall", Google an editable
                // two-row field with a swap button — because a route has two
                // ends and the driver has only confirmed one of them.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Your location", color = c.inkMuted,
                        style = VectorTheme.typography.caption,
                    )
                    Text(
                        "  →  ", color = c.inkMuted,
                        style = VectorTheme.typography.caption,
                    )
                }
                Text(
                    titleCase(ui.destinationName.ifEmpty { "Dropped pin" }),
                    color = c.ink,
                    style = VectorTheme.typography.hudContext,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // The second line: the rest of the trip.
                //
                // "14 min drive · 6 min walk" is the entire product thesis on
                // one line, and it belongs HERE rather than in a panel below,
                // because the question it answers is the same question the
                // destination chip is already asking. Tapping it opens the
                // legs.
            }
            // Close stays on the title row: it is the way out of this phase and
            // it belongs beside the thing it dismisses.
            VectorIconButton(
                icon = VectorIcons.glyphIcon(VectorIcons.Glyph.CLOSE),
                contentDescription = "Clear the destination",
                onClick = onCancel,
                variant = VectorButtonVariant.Ghost,
                size = VectorButtonSize.Small,
                contentTint = c.inkSecondary,
                testTag = "preview:clear",
            )
        }
        Spacer(Modifier.height(VectorTokens.Space.s4))
        // The second line: the rest of the trip.
        //
        // "14 min drive · 6 min walk" is the entire product thesis on one line.
        // It now gets the card's full width instead of the third of it that was
        // left over beside three round buttons.
        JourneySummaryLine(ui, c, onToggleJourney, onOpenPaywall)
        Spacer(Modifier.height(VectorTokens.Space.s12))
        // Save this destination as Home or Work.
        //
        // Here, and nowhere else, because this is the only moment the app holds
        // a coordinate the driver has deliberately chosen. Setting them from a
        // settings screen would need a place picker, and a place picker is a
        // second search UI. See [Places].
        //
        // **With words on them now.** They were two unlabelled round glyphs — a
        // house and a briefcase — sharing a row with a close button that was
        // also an unlabelled round glyph, so the one destructive control on the
        // card looked exactly like the two that save. A house icon does not say
        // "save"; it says "home", which is a place, on a screen that is full of
        // places. The label is the affordance.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
        ) {
            // Only the slots that are NOT set yet.
            //
            // It used to offer both slots on every preview forever — "Change
            // Home" and "Change Work" sitting under every destination a driver
            // ever looked at, long after both were set. Reported directly:
            // *"once home and work is set, it shouldnt prompt again."* And it
            // is right: saving Home is a once-a-year decision, so a control for
            // it on a screen reached several times a day is a permanent prompt
            // for something already answered.
            //
            // Changing one is still possible and still one place: clear it in
            // Settings → Your data, and the slot is offered here again the next
            // time. That keeps the setting reachable without spending the
            // preview card's most valuable row on it every single journey.
            val unset = Places.Slot.entries.filter { slot ->
                ui.places.none { it.slot == slot }
            }
            for (slot in unset) {
                VectorButton(
                    // "Save as Home" until it is set, then "Replace Home" —
                    // the state is on the control, so overwriting is a decision
                    // made before the tap rather than a dialog after it.
                    // Two words, not three. "Save as Home" wrapped onto two
                    // lines inside a half-width button at the default font
                    // scale, which is a label that does not fit its control.
                    text = "Set ${slot.label}",
                    onClick = { onSavePlace(slot) },
                    variant = VectorButtonVariant.Secondary,
                    size = VectorButtonSize.Small,
                    leadingIcon = VectorIcons.glyphIcon(
                        if (slot == Places.Slot.HOME) VectorIcons.Glyph.HOME
                        else VectorIcons.Glyph.WORK,
                    ),
                    modifier = Modifier.weight(1f),
                    testTag = "preview:save:${slot.label}",
                )
            }
        }
        }
    }
}

// ---------------------------------------------------------------------------
// The Last Mile (V7 Phase 4)
// ---------------------------------------------------------------------------

/**
 * Everything between choosing a destination and standing at it.
 *
 * ## Why this card is the product
 *
 * Vector has always answered "how long to drive there". The thing a person in
 * Doha actually wants to know in August is how far they will be walking at the
 * other end, and in which direction the sun will be. Collapsed, this card is
 * one line — **"14 min drive · 6 min walk"** — and that line is the entire
 * pitch. Expanded, it is the three legs, the walk drawn in two colours, and a
 * slider that moves the sun.
 *
 * ## What it must never say
 *
 * A bare shade percentage. Every figure on this card comes from a model with an
 * assumed facade height and no building data, so the copy comes from
 * [dev.vector.geo.sun.RouteShade.shadeLabel] — which cannot produce one without
 * the word "estimated" — and the provenance line under it says so in words.
 * That is the invariant from the journey architecture, enforced here rather
 * than left to whoever edits the string next.
 *
 * The V7 plan's version of the summary line ends "· 42 °C". There is no
 * temperature here: Vector has no weather source, and a plausible-looking
 * number in the most prominent line in the app is worse than an absent one.
 */
/**
 * The second line of the destination chip: the rest of the trip.
 *
 * "14 min drive · 6 min walk", with the modelled shade under it. Tapping opens
 * [JourneyCard].
 *
 * Renders nothing at all until there is a walk to describe — while the parking
 * search and the walk request are in flight the chip stays exactly as it was,
 * rather than reserving space for an answer that may turn out to be "no parking
 * found". A line that appears, says "finding...", and then changes height is a
 * worse experience than one that appears once, already true.
 */
@Composable
private fun JourneySummaryLine(
    ui: UiState,
    c: VectorColors,
    onToggleJourney: () -> Unit,
    onOpenPaywall: () -> Unit,
) {
    val journey = ui.journey ?: return
    if (!journey.hasWalk) return
    // The walk is Pro. The OFFER is made here, in the line where the answer
    // would have been, and only once Vector actually has an answer to sell:
    // the journey above has been composed and a walk was found. Offering it
    // before knowing that is how you sell a feature that then does not appear
    // for this destination -- the defect `ProCatalogue` exists to prevent, one
    // layer in.
    if (ui.offersPro) {
        // This build has a tier and the driver does not. Whether a price may be
        // shown is a different question — see `proSellable`.
        //
        // When the store cannot sell, this renders NOTHING rather than falling
        // through to the line below. Falling through would draw the walk
        // summary and its shade — the two things the tier is sold on — for
        // free, because "the store is unreachable" is not a reason to give the
        // product away. Silence is the honest third option: the chip keeps
        // whatever it already said, and the walk appears the moment the store
        // answers.
        if (ui.proSellable) ProWalkOffer(c, onOpenPaywall)
        return
    }
    Column(
        // `fillMaxWidth`, so the line WRAPS instead of running off the card.
        //
        // Without it the Column takes its intrinsic width, which for "7 min
        // drive · 7 min walk" at a 2.0x font scale is wider than the phone —
        // the 200% capture shows it cut mid-word at the screen edge, with no
        // ellipsis and no wrap, which reads as a broken card rather than as
        // text that did not fit. The product's whole thesis is on this line, so
        // it is the last thing that should be silently clipped.
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (ui.journeyExpanded) "Hide the journey legs"
                               else "Show the journey legs",
            ) { onToggleJourney() },
    ) {
        Text(
            journey.summaryLine(),
            color = c.primary,
            style = VectorTheme.typography.hudSupporting,
            fontWeight = FontWeight.Medium,
        )
        journey.walkShade?.let { shade ->
            Text(
                shade.shadeLabel(),
                color = c.inkMuted,
                style = VectorTheme.typography.caption,
            )
        }
    }
}

/**
 * What a free driver is shown where the walk would have been.
 *
 * One line, in the destination chip, reached only when a walk was actually
 * found — see [JourneySummaryLine]. It names the tier rather than the feature,
 * which makes tapping it a DELIBERATE presentation in `PaywallGate`'s sense:
 * the driver is choosing to look at the paywall, not being interrupted by it,
 * so it opens every time rather than once per session.
 */
@Composable
private fun ProWalkOffer(c: VectorColors, onOpenPaywall: () -> Unit) {
    Column(
        Modifier.clickable(onClickLabel = "See what Vector Pro includes") {
            onOpenPaywall()
        },
    ) {
        // Two short lines, neither of which wraps. The chip's text column is
        // narrow — three controls sit to the right of it — and the first draft
        // of this broke "Vector Pro" across two lines, which reads as a layout
        // fault rather than as an offer.
        Text(
            "The walk after the car",
            color = c.primary,
            style = VectorTheme.typography.hudSupporting,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Included with Vector Pro",
            color = c.inkMuted,
            style = VectorTheme.typography.caption,
        )
    }
}

/**
 * Everything between choosing a destination and standing at it.
 *
 * ## Why this exists
 *
 * Vector has always answered "how long to drive there". The thing a person in
 * Doha actually wants to know in August is how far they will be walking at the
 * other end, and where the sun will be. The one-line version of that answer is
 * on the destination chip ([JourneySummaryLine]); this is what opens under it:
 * the three legs, how much of the walk is stairs, a slider that moves the sun,
 * and the cooler route when there is one worth offering.
 *
 * ## What it must never say
 *
 * A bare shade percentage. Every figure here comes from a model with an assumed
 * facade height and no building data, so the copy comes from
 * [dev.vector.geo.sun.RouteShade.shadeLabel] — which cannot produce one without
 * the word "estimated" — and the provenance line at the bottom says so in
 * words. That is the journey architecture's invariant (the UI may not render an
 * annotation without its provenance), enforced here rather than left to
 * whoever edits the string next.
 *
 * The V7 plan's summary line ends "· 42 °C". There is no temperature anywhere
 * in this card: Vector has no weather source, and a plausible-looking number in
 * the most prominent line of the application is worse than an absent one.
 */
@Composable
private fun JourneyCard(
    ui: UiState,
    c: VectorColors,
    onScrubTime: (Long?) -> Unit,
    onToggleCooler: () -> Unit,
) {
    val journey = ui.journey ?: return
    if (!journey.hasWalk || !ui.journeyExpanded) return
    // Sold by `ProCatalogue` as "The walk after the car", so it is behind the
    // tier that sells it. A free driver gets [ProWalkOffer] in the chip above
    // and an unchanged drive; nothing gated here is on the path to anywhere.
    if (ui.offersPro) return

    Surface(
        color = c.surface,
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r16),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(VectorTokens.Space.s12)) {

            journey.drive?.let { drive ->
                JourneyLegRow(
                    title = "Drive",
                    detail = "${ui.units.shortDistance(drive.distanceM)} · " +
                        "${dev.vector.geo.journey.Journey.minutes(drive.durationS)} min",
                    tint = c.primary,
                    c = c,
                )
            }

            // The active walk, not the baseline one: with the cooler route on,
            // the driver is being shown a walk from a DIFFERENT car park, and
            // the park name, distance, duration and shade must all move with
            // it. Reading `journey` here is what made the card disagree with
            // the line on the map.
            ParkRow(journey.parkingKnown, ui.activePark, c)

            ui.activeWalk?.let { walk ->
                WalkLegRow(walk, ui.activeShade, ui, c)
            }

            Spacer(Modifier.height(VectorTokens.Space.s12))
            SunSlider(ui, c, onScrubTime)

            CoolerRouteRow(ui, c, onToggleCooler)

            // The architecture's invariant: the UI may not render an annotation
            // without rendering its provenance. This is that, in words a person
            // reads rather than an enum.
            ui.activeShade?.let {
                Spacer(Modifier.height(VectorTokens.Space.s8))
                Text(
                    "Shade is estimated from street orientation and the sun's " +
                        "position. Vector has no building or tree data.",
                    color = c.inkMuted,
                    style = VectorTheme.typography.caption,
                )
            }
        }
    }
}

/** One leg, as a row: what it is, and what it costs. */
@Composable
private fun JourneyLegRow(title: String, detail: String, tint: Color, c: VectorColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = VectorTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(tint, RoundedCornerShape(VectorTokens.Radius.pill)),
        )
        Spacer(Modifier.width(VectorTokens.Space.s8))
        Text(title, color = c.ink, style = VectorTheme.typography.hudSupporting,
             fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(detail, color = c.inkMuted, style = VectorTheme.typography.hudSupporting)
    }
}

/**
 * Where the car is left — and, when nothing was found, the fact that nothing
 * was found.
 *
 * "No parking found nearby" is not a failure message, it is the answer. The
 * arrival card has said exactly this since V5 rather than guessing a car park,
 * and a journey card that silently measured the walk from the destination's own
 * kerb while looking identical to one measured from a real car park would undo
 * that.
 */
@Composable
private fun ParkRow(
    parkingKnown: Boolean,
    park: dev.vector.geo.journey.ParkSpot?,
    c: VectorColors,
) {
    if (parkingKnown) {
        JourneyLegRow(
            title = "Park",
            detail = park?.name ?: "Parking",
            tint = c.inkMuted,
            c = c,
        )
    } else {
        JourneyLegRow(
            title = "Park",
            detail = "No parking found nearby",
            tint = c.inkMuted,
            c = c,
        )
    }
}

/**
 * The walk: how far, how long, how much of it is stairs, and how much sun.
 *
 * [WalkLeg.stepsM] earns its place on this row rather than in a detail view:
 * the walking model penalises stairs rather than banning them, so a route may
 * legitimately include them, and a person with a suitcase or a pushchair needs
 * to be told before they set off rather than when they arrive at the steps.
 */
@Composable
private fun WalkLegRow(
    walk: dev.vector.geo.journey.WalkLeg,
    shade: dev.vector.geo.sun.RouteShade?,
    ui: UiState,
    c: VectorColors,
) {
    Column {
        JourneyLegRow(
            title = "Walk",
            detail = "${ui.units.shortDistance(walk.distanceM)} · " +
                "${dev.vector.geo.journey.Journey.minutes(walk.durationS)} min",
            tint = c.success,
            c = c,
        )
        val notes = buildList {
            shade?.let { add(it.shadeLabel()) }
            if (walk.stepsM >= 1.0) add("${ui.units.shortDistance(walk.stepsM)} of steps")
        }
        if (notes.isNotEmpty()) {
            Text(
                notes.joinToString(" · "),
                color = c.inkMuted,
                style = VectorTheme.typography.caption,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/**
 * The sun, on a slider.
 *
 * ## Why this exists
 *
 * The shade model is a pure function of time, so letting someone scrub it costs
 * almost nothing — and it converts "a colour overlay somebody chose" into "a
 * physical simulation" in about two seconds of watching the walk re-colour as
 * the sun crosses. It is also genuinely useful: a walk planned at 09:00 for a
 * 16:00 return is an ordinary question in Doha, and the answer is different.
 *
 * Scrubbing sets an ABSOLUTE instant on the state rather than an offset, so the
 * answer does not drift while the card is open. Releasing to "Now" clears it.
 */
@Composable
private fun SunSlider(ui: UiState, c: VectorColors, onScrubTime: (Long?) -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    val nowMs = System.currentTimeMillis()
    val shownMs = ui.journeyTimeMs ?: nowMs
    val shown = java.time.Instant.ofEpochMilli(shownMs).atZone(zone)
    val minuteOfDay = shown.hour * 60 + shown.minute

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sun at %02d:%02d".format(shown.hour, shown.minute),
                color = c.ink,
                style = VectorTheme.typography.hudSupporting,
                modifier = Modifier.weight(1f),
            )
            if (ui.journeyTimeMs != null) {
                Text(
                    "Now",
                    color = c.primary,
                    style = VectorTheme.typography.hudSupporting,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClickLabel = "Show the sun now") { onScrubTime(null) }
                        .padding(horizontal = VectorTokens.Space.s8),
                )
            }
        }
        Slider(
            value = minuteOfDay.toFloat(),
            onValueChange = { minutes ->
                val day = shown.toLocalDate().atStartOfDay(zone)
                onScrubTime(day.plusMinutes(minutes.toLong()).toInstant().toEpochMilli())
            },
            // Dawn to dusk. Outside this the model reports no useful sun, and a
            // slider that spends half its travel on "it is dark" wastes the
            // half that matters.
            valueRange = (5f * 60f)..(19f * 60f),
            modifier = Modifier.semantics { contentDescription = "Time of day for shade" },
        )
    }
}

/**
 * The cooler walk, when there is one worth offering.
 *
 * Absent — not disabled — when [dev.vector.geo.journey.CoolerRoute] declines to
 * offer one. A control that is present and refuses advertises a feature and
 * then does not perform it, which is the shape of the paywall defect this
 * project has already shipped once (see `pro/ProAccess.kt`).
 */
@Composable
private fun CoolerRouteRow(ui: UiState, c: VectorColors, onToggleCooler: () -> Unit) {
    if (!ui.coolerOffer.available) return
    val locked = !dev.vector.android.pro.ProAccess.hasPro(ui.pro)
    val saved = ui.coolerOffer.deltaPoints.toInt()
    val extraMin = dev.vector.geo.journey.Journey.minutes(
        kotlin.math.abs(ui.coolerOffer.extraDurationS)
    )
    val cost = when {
        ui.coolerOffer.extraDurationS > 30 -> "$extraMin min longer"
        ui.coolerOffer.extraDurationS < -30 -> "$extraMin min shorter"
        else -> "same time"
    }

    Spacer(Modifier.height(VectorTokens.Space.s8))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (ui.coolerChosen) "Use the direct walk"
                               else "Use the cooler walk",
            ) { onToggleCooler() }
            .padding(vertical = VectorTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (locked) "Cooler route (Pro)" else "Cooler route",
                color = if (ui.coolerChosen) c.primary else c.ink,
                style = VectorTheme.typography.hudSupporting,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "~$saved points less sun, estimated · $cost",
                color = c.inkMuted,
                style = VectorTheme.typography.caption,
            )
        }
        Text(
            if (ui.coolerChosen) "On" else "Off",
            color = if (ui.coolerChosen) c.primary else c.inkMuted,
            style = VectorTheme.typography.hudSupporting,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// Navigation HUD
// ---------------------------------------------------------------------------

/**
 * The driving instruction. The one thing a driver looks at.
 *
 * ## What changed in V4, and why
 *
 * This was `ManeuverCard`: a rounded, translucent, 12 dp-inset card holding a
 * text-glyph arrow, a 26 sp accent-coloured distance, and the full instruction
 * sentence at **17 sp over two lines**. Four things about that were wrong, and
 * all four were measured against the reference products rather than felt:
 *
 * 1. **It was a card.** Waze draws this as pure black edge to edge; Google Maps
 *    as a deep teal band with margins but no transparency. Neither treats the
 *    instruction as a floating element over the map, because it is not
 *    *content* — it is an instrument. It is now full-bleed and runs up behind
 *    the status bar.
 *
 * 2. **The colour roles were inverted.** Vector put the DISTANCE in the accent
 *    colour and the road name in plain foreground. Waze does the opposite:
 *    `160 m` in white, `Hisham Bin Oqba St` in cyan. The reason is that the
 *    road name is what the driver matches against the sign above the
 *    carriageway, so it is the thing that should catch the eye; the distance is
 *    a number that decrements and needs no emphasis to be found.
 *
 * 3. **It showed a sentence.** "Turn left onto Suhaim Bin Hamad Street" cannot
 *    be set at a size that reads at a glance, so it was set at 17 sp — half the
 *    size of Waze's road name (~30 sp, measured off a 450 dpi capture). The
 *    *action* is carried entirely by the arrow, which is what an arrow is for,
 *    so the text is now `[distance] / [road]` and both halves are 31 sp.
 *    [Maneuver.road] has been on the wire since V1 and was being dropped.
 *
 * 4. **The arrow was the character `←`** at whatever weight the system font
 *    felt like — a hairline where both references draw a ~9 dp stroke. See
 *    [VectorIcons].
 *
 * ## What deliberately did NOT change
 *
 * The off-route copy. V3's version distinguishes "Rerouting" (a route is
 * coming) from "Off route" (one is not, because we are stopped or because the
 * nearest road is beyond the threshold) and says what the driver should do. It
 * is better than anything captured from either reference product, and it is
 * restyled here, not rewritten.
 *
 * And there is **no animation on this element**. See [VectorMotion]'s KDoc: the
 * instruction cuts, because any transition is time during which the text on
 * screen is not yet true. Both references do the same, and it was verified
 * frame by frame rather than assumed.
 */
@Composable
private fun ManeuverBanner(ui: UiState, c: VectorColors) {
    Surface(color = c.guidanceSurface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .statusBarsPadding()
                .heightIn(min = VectorTokens.Size.bannerMinHeight)
                .padding(
                    horizontal = VectorTokens.Space.s16,
                    vertical = VectorTokens.Space.s12,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.offRoute && ui.currentManeuver == null) {
                OffRouteBanner(ui, c)
                return@Row
            }
            val m = ui.currentManeuver ?: return@Row
            VectorIcons.Maneuver(
                type = m.type,
                dim = VectorTokens.Size.maneuverArrow,
                // White, not the accent: on the banner the accent belongs to
                // the road name, and an arrow competing with it for the same
                // colour would flatten the hierarchy the banner exists to
                // create.
                color = Color.White,
                label = m.instruction,
            )
            Spacer(Modifier.width(VectorTokens.Space.s16))
            Column(Modifier.weight(1f)) {
                Text(
                    shortDistance(ui.distanceToManeuverM, ui.units),
                    color = Color.White,
                    style = VectorTheme.typography.hudPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    bannerSubject(m, ui.destinationName),
                    color = c.guidanceAccent,
                    style = VectorTheme.typography.hudPrimary,
                    fontWeight = FontWeight.Bold,
                    // Two lines, because Doha's road names are long and
                    // bilingual and truncating the one field that says WHICH
                    // ROAD is the mistake the route selector was rebuilt to
                    // stop making.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What the banner names: the road being joined, or the destination on arrival.
 *
 * Falls back to the whole instruction when the router gave no `road`. That is
 * not a nice degradation — a sentence at 31 sp will wrap and truncate — but it
 * is an honest one, and it is better than a blank line where the road name
 * should be. `road` is populated for every step the live router returns.
 */
private fun bannerSubject(m: Maneuver, destinationName: String): String = when {
    // On arrival the road is the street the destination sits on, which is not
    // what the driver is looking for at 30 m.
    m.type == "arrive" -> destinationName.ifBlank { "Destination" }
    !m.road.isNullOrBlank() -> m.road
    else -> plainRoadWords(m.instruction)
}

/**
 * Strip OSM road-class jargon out of an instruction before a driver reads it.
 *
 * ## The defect
 *
 * A capture of the driving screen on this build reads, in 28 sp across the top
 * of the display:
 *
 *     Bear left to stay on the tertiary road
 *
 * "Tertiary" is an OSM `highway=` value. It is a *classification used by
 * cartographers* — it sits between `secondary` and `unclassified` and describes
 * the road's place in a network hierarchy. It is not a thing a driver can see
 * out of the windscreen, it is not written on any sign, and there is no action
 * it distinguishes from any other: every instruction that says it would mean
 * exactly the same with the word removed.
 *
 * This is the same class of bug the codebase already fixed twice — `kind` read
 * "poi" for every POI in the country (see `VectorApi.Place.categoryLabel`), and
 * names arrived shouted from the extract (see [titleCase]). Developer
 * terminology reaching the user is the failure; the road classes are the last
 * place it still does, and it does it on the one surface that is read at speed.
 *
 * ## Why it is fixed here and not in the router
 *
 * The instruction string is the backend's, and the backend is right to carry the
 * class — the ranker and the camera both use it. What is wrong is *rendering* it,
 * which is a presentation concern and belongs on the client, beside the two
 * other presentation cleanups this file already owns.
 *
 * Only whole words, and only in the fixed phrase the router emits ("the <class>
 * road"), so a real road actually named "Tertiary Road" is untouched and nothing
 * is rewritten that a driver could be looking for on a sign.
 */
internal fun plainRoadWords(instruction: String): String {
    var out = instruction
    for (klass in ROAD_CLASS_WORDS) {
        // "stay on the tertiary road" -> "stay on this road". Not "the road":
        // the definite article with nothing to point at reads as a road the
        // driver was told about earlier and missed.
        out = out.replace("the $klass road", "this road", ignoreCase = true)
        out = out.replace("a $klass road", "a road", ignoreCase = true)
    }
    return out
}

/**
 * The OSM `highway=` values that describe a road's rank rather than the road.
 *
 * `motorway`, `trunk` and `primary` are deliberately NOT here: a driver does
 * know what a motorway is, it is signposted, and "join the motorway" is a real
 * instruction. The list is the classes that exist only inside the data model.
 */
private val ROAD_CLASS_WORDS = listOf(
    "tertiary", "secondary", "unclassified", "residential", "service",
    "living street", "track",
)

/**
 * No guidance, and why.
 *
 * The copy is V3's, unchanged, because it was already right. Only the styling
 * moved: it now fills the same full-bleed band as the instruction it replaces,
 * so the driver's eye does not have to relocate to find out what happened.
 */
@Composable
private fun RowScope.OffRouteBanner(ui: UiState, c: VectorColors) {
    // Not a hazard triangle.
    //
    // This drew `Glyph.REPORT` — the yellow warning triangle — at maneuver
    // size, which is the largest, loudest mark the HUD has, in the slot the
    // driving instruction normally occupies. Reported as needing refinement,
    // and it does: being off route is not a hazard. Nothing is wrong with the
    // car, nothing is dangerous, and the app is already fixing it. A triangle
    // is the symbol this product reserves for a speed camera, a sharp bend and
    // a positioning failure, and spending it on "you turned early" both alarms
    // the driver and devalues it for the cases that are alarming.
    //
    // What it is instead: **the state, drawn as itself.** Rerouting shows the
    // circular arrow the map already uses for "recomputing"; being off route
    // shows a hollow locator ring — the vehicle, not on a line. Both in the
    // band's own accent rather than in warning yellow.
    VectorIcons.Control(
        kind = if (ui.rerouting) VectorIcons.Glyph.ROTATED else VectorIcons.Glyph.RECENTER,
        dim = VectorTokens.Size.maneuverArrow,
        color = c.guidanceAccent,
        label = if (ui.rerouting) "Rerouting" else "Off route",
    )
    Spacer(Modifier.width(VectorTokens.Space.s16))
    Column(Modifier.weight(1f)) {
        Text(
            if (ui.rerouting) "Rerouting" else "Off route",
            // The band's accent, the same colour the road name is set in one
            // state over. The heading used to be the only yellow text on the
            // driving screen.
            color = c.guidanceAccent,
            style = VectorTheme.typography.hudSecondary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (ui.rerouting) "Finding a new route from here"
            else "Drive back to the route to continue",
            color = c.onGuidance,
            style = VectorTheme.typography.hudContext,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Walking navigation (V7.4 4C final)
// ---------------------------------------------------------------------------

/**
 * The walking instruction band.
 *
 * ## Where every word on it comes from
 *
 * [WalkNavState.instruction] — the SAME object
 * [dev.vector.geo.walk.WalkVoice] speaks from. Nothing is composed here: the
 * action phrase, the road clause and the attribute line are all built in
 * [dev.vector.geo.walk.WalkInstructions], so the band and the voice cannot
 * describe different maneuvers. This composable only decides where the strings
 * sit.
 *
 * ## Why it is its own band and not a mode flag on [ManeuverBanner]
 *
 * Because the two carry different facts and one of them must not leak into the
 * other. The car banner's subject falls back to the router's whole localised
 * sentence when no road is known; a walking instruction has no sentence to
 * fall back to (4B.2 publishes facts and explicitly no prose), and the honest
 * rendering of an unknown road is the ACTION ALONE — "Turn left", with no
 * trailing clause and no placeholder. Sharing the composable would have meant
 * a conditional at every line, which is how one mode's fallback ends up on the
 * other's screen.
 *
 * ## The three states it can be in
 *
 * 1. **Off route.** The instruction is null — suppressed at the session, so
 *    the voice falls silent at the same instant — and the band says so. It
 *    does NOT go on showing the last turn: the plan describes a route the
 *    walker is demonstrably not on, and a stale instruction is worse than
 *    none.
 * 2. **Arrived.** The route's endpoint was reached. Deliberately not "You have
 *    arrived at X": the walk ends where the pedestrian network ends, and
 *    `snap_max_m` records how far that was from what was asked for.
 * 3. **An instruction**, with its distance.
 *
 * [WalkNavState.uncertain] is a quiet qualifier on the distance rather than a
 * fourth state. The follower's UNCERTAIN band means "I cannot tell", the brief
 * forbids announcing a departure from it, and replacing a usable instruction
 * with an apology would take away the only guidance available at the moment it
 * is least certain — the same argument [GpsWarning] makes for not replacing
 * the maneuver band.
 */
@Composable
private fun WalkBanner(walk: WalkNavState, units: Units, c: VectorColors) {
    Surface(color = c.guidanceSurface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .statusBarsPadding()
                .heightIn(min = VectorTokens.Size.bannerMinHeight)
                .padding(
                    horizontal = VectorTokens.Space.s16,
                    vertical = VectorTokens.Space.s12,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (walk.offRoute || walk.rerouting) {
                WalkOffRouteBanner(walk, c)
                return@Row
            }
            if (walk.arrived) {
                WalkArrivedBanner(c)
                return@Row
            }
            val instruction = walk.instruction ?: return@Row
            VectorIcons.Maneuver(
                type = dev.vector.geo.walk.WalkInstructions.iconType(instruction.kind),
                dim = VectorTokens.Size.maneuverArrow,
                color = Color.White,
                label = instruction.banner,
            )
            Spacer(Modifier.width(VectorTokens.Space.s16))
            Column(Modifier.weight(1f)) {
                // The distance is omitted once the walker is ON a crossing or
                // a staircase: "0 m · Cross the road" while standing in the
                // road is a countdown to something already happening. The span
                // is the event, and the words are what is left to say.
                val onSpan = instruction.status ==
                    dev.vector.geo.walk.WalkEventStatus.ACTIVE && instruction.spanM > 0.0
                if (!onSpan) {
                    Text(
                        // The driver's own units, not a hardcoded metric.
                        //
                        // ## The defect this parameter exists because of
                        //
                        // It read `Units.METRIC` directly. Qatar is metric so
                        // it was invisible here, but the setting is real and
                        // applies to the whole product — the car banner, the
                        // trip bar, the step list and the VOICE all go through
                        // `ui.units` — so a user who chose imperial would have
                        // got feet everywhere except the one number they are
                        // looking at most while walking. Exactly the
                        // half-wired-setting failure `Units`' own KDoc was
                        // written about.
                        shortDistance(
                            instruction.distanceM.coerceAtLeast(0.0),
                            units,
                        ),
                        color = Color.White,
                        style = VectorTheme.typography.hudPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    instruction.banner,
                    color = c.guidanceAccent,
                    style = VectorTheme.typography.hudPrimary,
                    fontWeight = FontWeight.Bold,
                    // Two lines, for the reason the car banner takes two:
                    // Doha's road names are long and bilingual, and the field
                    // that says WHICH road is the one that must not truncate.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The walker has left the route, or a replacement is being fetched.
 *
 * Two sentences, and neither invents a way back. It does not say "turn
 * around", because Vector does not know which way the walker is facing — the
 * follower refuses device heading on foot for measured reasons — and it does
 * not name a direction to the route, because the route back IS a route and
 * asking for one is what rerouting is.
 */
@Composable
private fun RowScope.WalkOffRouteBanner(walk: WalkNavState, c: VectorColors) {
    val title = if (walk.rerouting) "Finding a new route" else "Off the walking route"
    VectorIcons.Control(
        kind = VectorIcons.Glyph.REPORT,
        dim = VectorTokens.Size.maneuverArrow,
        color = c.warning,
        label = title,
    )
    Spacer(Modifier.width(VectorTokens.Space.s16))
    Column(Modifier.weight(1f)) {
        Text(
            title,
            color = c.warning,
            style = VectorTheme.typography.hudSecondary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (walk.rerouting) "Planning a walking route from here"
            else "Walk back to the route to continue",
            color = Color.White,
            style = VectorTheme.typography.hudContext,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The end of the walking route.
 *
 * "the end of the walking route", not "your destination". The distinction is
 * the whole point and it is the same one [WalkNavSession.Action.Arrived]
 * makes: the walk ends where the pedestrian network ends, which on Qatar's
 * foot graph can be a measurable distance from the door — `snap_max_m` says
 * how far, and the snap warning says so separately when it is worth saying.
 */
@Composable
private fun RowScope.WalkArrivedBanner(c: VectorColors) {
    VectorIcons.Maneuver(
        type = "arrive",
        dim = VectorTokens.Size.maneuverArrow,
        color = Color.White,
        label = "Arrived",
    )
    Spacer(Modifier.width(VectorTokens.Space.s16))
    Column(Modifier.weight(1f)) {
        Text(
            "Arrived",
            color = Color.White,
            style = VectorTheme.typography.hudSecondary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "You have reached the end of the walking route",
            color = c.guidanceAccent,
            style = VectorTheme.typography.hudContext,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The secondary line under the walking instruction.
 *
 * Attached to the band, in [Chrome.bannerSub], exactly as the lane strip is
 * attached while driving — one instrument rather than two objects that happen
 * to be near each other.
 *
 * ## Two lines, and why they are separate rather than joined
 *
 * [detail] is the maneuver's own surveyed attributes, built by
 * [dev.vector.geo.walk.WalkInstructions] from what the backend supplied: a
 * crossing's type and kerb, a staircase's step count. [shade] is the ROUTE's
 * modelled sun exposure, built by
 * [dev.vector.geo.walk.WalkShade.stripLine]. They are different facts with
 * different provenance — one surveyed, one modelled — and joining them with a
 * middot would put a guess and a survey in the same sentence, where the
 * certainty of one borrows from the other. So they are two lines on one strip.
 *
 * ## Both are optional, and the strip is not reserved
 *
 * On the real Qatar bake most crossings carry no attributes and almost every
 * staircase carries none, so [detail] is usually null; a backend that sent no
 * per-segment `classes` produces "Shade not estimated", which is a sentence,
 * but a walk with no usable geometry produces none at all. When both are null
 * the caller does not compose this at all — an empty strip is never reserved,
 * and the absence of a fact is never rendered as a negative claim.
 *
 * ## What the shade line may say
 *
 * Only what [dev.vector.geo.walk.WalkShade.stripLine] returns, which is the
 * single sanctioned producer: a band word with "(estimated)" attached, or one
 * of the three sentences for a walk whose shade was not modelled at all. The
 * strip asserts nothing of its own, and there is no wording here that could
 * turn "Limited shade data" into "shaded" or a deleted line into "no shade".
 */
@Composable
private fun WalkDetailStrip(detail: String?, shade: String?, c: VectorColors) {
    Surface(color = c.guidanceSurfaceSub, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(
                horizontal = VectorTokens.Space.s16,
                vertical = VectorTokens.Space.s8,
            ),
        ) {
            detail?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.85f),
                    style = VectorTheme.typography.hudSupporting,
                    // Two lines, UNCHANGED from before this stage. A surveyed
                    // crossing can carry four attributes ("Signal-controlled
                    // crossing · Marked · Lowered kerb · Tactile paving") and
                    // truncating one of them to make room for a model would be
                    // trading a fact for an estimate.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            shade?.let {
                Text(
                    it,
                    // Dimmer than the surveyed line above it, for the reason
                    // the map overlay dashes rather than draws solid: this one
                    // is a model. The distinction is carried by the words as
                    // well, so it does not depend on a colour a person has to
                    // learn.
                    color = Color.White.copy(alpha = 0.7f),
                    style = VectorTheme.typography.hudSupporting,
                    // One line: the longest sentence this producer can emit is
                    // "Low sun; shade not estimated".
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Why there is no walking route.
 *
 * ## Why this is not [ErrorBanner]
 *
 * Because two of the three kinds are not errors. A
 * `pedestrian_network_split` is an ANSWER — the footpaths Vector knows about
 * do not connect these points — and on Qatar's graph it is the common one:
 * 2,811 components, the largest holding 32% of nodes. 4A.1 gave it its own
 * `reason` on the wire precisely so a client could say which of the three
 * happened, and collapsing them into one red sentence would throw that away.
 *
 * Only [dev.vector.geo.walk.WalkRefusalKind.BACKEND_FAILURE] is styled as an
 * error, because it is the only one where something went wrong rather than
 * something being absent, and the only one where trying again is sensible.
 *
 * **No route is drawn behind this.** That is the point of a refusal: a
 * straight line between two points on separate pedestrian networks would be
 * exactly the claim the backend declined to make.
 */
@Composable
private fun WalkRefusalBanner(kind: dev.vector.geo.walk.WalkRefusalKind, c: VectorColors) {
    val failure = kind == dev.vector.geo.walk.WalkRefusalKind.BACKEND_FAILURE
    val tint = if (failure) c.dangerText else c.warning
    Surface(
        color = if (failure) c.dangerContainer else c.warningContainer,
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r12),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            VectorIcons.Control(
                kind = VectorIcons.Glyph.REPORT,
                dim = 16.dp,
                color = tint,
                label = dev.vector.geo.walk.WalkRefusalText.title(kind),
            )
            Spacer(Modifier.width(VectorTokens.Space.s8))
            Column {
                Text(
                    dev.vector.geo.walk.WalkRefusalText.title(kind),
                    color = tint, style = VectorTheme.typography.metadata,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    dev.vector.geo.walk.WalkRefusalText.detail(kind),
                    color = c.inkMuted, style = VectorTheme.typography.caption,
                )
            }
        }
    }
}

/**
 * The walking trip readout.
 *
 * ## What it shows, and the ETA decision it records
 *
 * Distance left, then the walk time, then — separately — the expected crossing
 * delay when there is one worth mentioning.
 *
 * **`duration_s` is shown unchanged.** The 4B.4 contract froze it as pure pace
 * time, four shipping features already depend on that meaning (the journey
 * card prints it and `MainActivity` selects parking walks by it), and folding
 * the crossing wait into it would silently redefine a published field.
 *
 * So the crossing delay is a SEPARATE, explicitly additive phrase — "plus
 * about 2 min of crossing waits" — built by
 * [dev.vector.geo.walk.WalkEtaText]. A walker sees both numbers and can tell
 * which is which; they never see one number that quietly means both.
 *
 * ## What is deliberately absent
 *
 * **No arrival clock.** The driving bar leads with "arrive 14:32", which is
 * the right lead for a drive because it answers "will I make the meeting". It
 * would be dishonest here: the walk time excludes an expected crossing delay
 * that is itself a per-edge model rather than a measurement of any particular
 * junction, so a clock reading would assert a precision the contract does not
 * have. Distance and a hedged duration are what Vector can actually support.
 *
 * **No turn-list control.** The step sheet renders `ui.maneuvers`, which is
 * the CAR maneuver list and is empty on a walk. A control that opened an empty
 * sheet is the dead UI this codebase keeps refusing to add.
 */
@Composable
private fun WalkTripBar(walk: WalkNavState, ui: UiState, c: VectorColors, onExit: () -> Unit) {
    Surface(
        color = c.surface,
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(
                horizontal = VectorTokens.Space.s8,
                vertical = VectorTokens.Space.s8,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(
                glyph = VectorIcons.Glyph.CLOSE,
                label = "End the walk",
                tint = c.inkMuted,
                c = c,
                onClick = onExit,
            )
            Column(
                Modifier.weight(1f).padding(horizontal = VectorTokens.Space.s8),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // `remainingM` is null when the route lock has been dropped
                    // — a confirmed departure, before a reroute lands. Coalescing
                    // that to 0.0 rendered the literal "0 m left" beside an
                    // off-route banner: a STRONGER and WRONGER claim than saying
                    // nothing, and the one reading a walker cannot act on. When
                    // the distance is unknown the row simply omits it and keeps
                    // whatever the ETA still honestly supports.
                    walk.remainingM?.let { remaining ->
                        Text(
                            shortDistance(remaining, ui.units) + " left",
                            color = c.ink,
                            style = VectorTheme.typography.hudSecondary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    dev.vector.geo.walk.WalkEtaText.walkTime(walk.eta)?.let {
                        if (walk.remainingM != null) {
                            Spacer(Modifier.width(VectorTokens.Space.s8))
                        }
                        Text(
                            it,
                            color = c.ink,
                            style = VectorTheme.typography.hudContext,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                // The crossing delay, on its own line and clearly additive.
                // Null when the backend reported none or too little to be
                // worth a hedged minute — see WalkEtaText.MIN_CROSSING_DELAY_S.
                dev.vector.geo.walk.WalkEtaText.crossingDelay(walk.eta)?.let {
                    Text(
                        it,
                        color = c.inkMuted,
                        style = VectorTheme.typography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Which lane to be in.
 *
 * Valid lanes are bright; the rest are dimmed rather than hidden, because the
 * driver counts lanes across the whole carriageway — showing only the usable
 * ones would make "the second from the left" wrong. That rule is V1's and is
 * unchanged.
 *
 * **Attached, not floating.** It was a rounded pill with a gap above it, which
 * is exactly §15's complaint that lane guidance is "bolted underneath" the
 * maneuver rather than integrated with it. It is now a full-bleed strip flush
 * against the banner, in [Chrome.bannerSub], so the driver reads one continuous
 * instrument rather than two objects that happen to be near each other.
 *
 * Its appearance and disappearance are animated ([VectorMotion.stripEnter]) —
 * the one place in the navigation band where motion is right, because §15's
 * question is precisely *when* lane advice arrives and leaves, and an
 * instantaneous 40 dp of new content shoving the map down is the version of
 * that which startles.
 */
@Composable
private fun LaneStrip(lanes: List<dev.vector.geo.LaneGuidance.Lane>, c: VectorColors) {
    // Read out loud as one sentence rather than as N separate arrows: "lane 2
    // of 4, right" is what a screen reader can usefully say about a diagram.
    val spoken = lanes.mapIndexed { i, l ->
        "lane ${i + 1} ${dev.vector.geo.LaneGuidance.spoken(l.primary)}" +
            if (l.valid) ", take this one" else ""
    }.joinToString("; ")
    Surface(color = c.guidanceSurfaceSub, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(vertical = VectorTokens.Space.s8)
                .semantics { contentDescription = "Lanes: $spoken" },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((i, l) in lanes.withIndex()) {
                // A divider between lanes, not just a gap.
                //
                // The driver's task is COUNTING — "the second lane from the
                // left" — and a row of evenly spaced arrows is harder to count
                // than a row of marked bays. Both reference products separate
                // them. Drawn only between lanes, so the strip has no edge
                // rules that could be mistaken for a lane.
                if (i > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(VectorTokens.Size.laneArrow)
                            .background(Color.White.copy(alpha = 0.14f))
                    )
                }
                Box(
                    Modifier
                        .padding(horizontal = VectorTokens.Space.s8)
                        // The valid lane is given a tinted bay behind it as
                        // well as a brighter arrow. Never colour alone: a
                        // driving-critical distinction has to survive both
                        // colour-blindness and direct sun, which is the same
                        // rule the speedometer's over-limit ring follows.
                        .background(
                            if (l.valid) c.guidanceAccent.copy(alpha = 0.16f) else Color.Transparent,
                            RoundedCornerShape(VectorTokens.Radius.r8),
                        )
                        .padding(horizontal = VectorTokens.Space.s4, vertical = VectorTokens.Space.s4),
                ) {
                    VectorIcons.LaneArrow(
                        indication = l.primary,
                        dim = VectorTokens.Size.laneArrow,
                        color = if (l.valid) c.guidanceAccent else Color.White,
                        valid = l.valid,
                    )
                }
            }
        }
    }
}

/**
 * A motorway exit: the number, what it is signposted to, and the shape.
 *
 * ## Why there is a drawing on it
 *
 * Asked for directly from the S24: *"the card at the top where it displays the
 * direction and everything should also show pictorially when exits need to be
 * taken or free rights, using a graphic — this way, if the user has to take a
 * turn from an intersection, they can keep the correct lane so they don't have
 * to take hard swerves at the end to reach the turn through traffic."*
 *
 * The lane half of that is [LaneStrip], and it is the half that answers the
 * swerve. This is the other half: the shape of a road peeling away from the one
 * you are on is what a driver recognises on a gantry before they have read
 * anything, and the badge was three words of text. The through carriageway is
 * drawn dimmed — it is the road NOT being taken, and at full weight the glyph
 * would be a fork with no advice in it.
 *
 * The ref keeps [Chrome.go]'s green because that is what the sign it is copying
 * uses, and it is the same green the Start control uses for the same reason:
 * this is the thing to do.
 */
@Composable
private fun ExitBadge(ref: String, destination: String?, c: VectorColors) {
    Surface(color = c.surface, shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r8)) {
        Row(
            Modifier
                .padding(
                    horizontal = VectorTokens.Space.s12,
                    vertical = VectorTokens.Space.s8,
                )
                .semantics {
                    contentDescription = buildString {
                        append("Exit ").append(ref)
                        destination?.let { append(", signposted ").append(it) }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VectorIcons.Control(
                kind = VectorIcons.Glyph.EXIT,
                dim = VectorTokens.Size.exitGlyph,
                // Green because a motorway exit sign IS green, here and
                // in every country this map covers — the badge mimics real
                // signage, which is the one thing on the HUD a driver matches
                // against the world rather than reads. `leafText` rather than
                // `success`: the same family at text contrast, and a role that
                // means "the green accent" instead of one that means "this
                // operation succeeded".
                color = c.leafText,
                label = null,
            )
            Spacer(Modifier.width(VectorTokens.Space.s8))
            Text(
                "Exit $ref", color = c.leafText,
                style = VectorTheme.typography.hudSupporting, fontWeight = FontWeight.Bold,
            )
            destination?.let {
                Spacer(Modifier.width(VectorTokens.Space.s8))
                Text(
                    it, color = c.inkMuted, style = VectorTheme.typography.metadata,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The maneuver after the next one. An arrow, and no words.
 *
 * Dropping the text was a deliberate subtraction and it is the change on this
 * screen that most needed the reference products to justify it. Vector showed
 * "then [glyph] Turn right onto Al Waab Street" at 13 sp. **Both Waze and
 * Google Maps show the word "then" and an arrow, with no road name at all** —
 * `v4-evidence/waze/04-route-preview.png` and `gmaps/05-nav-hud.png`, and they
 * arrived there independently.
 *
 * The reason is worth stating because it is not obvious: the second maneuver
 * exists to tell the driver about *shape* — "left then immediately right",
 * "exit then keep left" — which is what determines which lane to be in now.
 * The road's name is not actionable until it becomes the primary instruction,
 * and 13 sp of it is text a driver will try to read at exactly the wrong moment.
 *
 * Full-bleed and one tone lighter than the banner, so it reads as subordinate
 * by position and value rather than by being small.
 */
@Composable
private fun ThenStrip(next: Maneuver, c: VectorColors) {
    Surface(color = c.guidanceSurfaceSub, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(
                    horizontal = VectorTokens.Space.s16,
                    vertical = VectorTokens.Space.s8,
                )
                .semantics { contentDescription = "Then ${next.instruction}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "then",
                color = Color.White.copy(alpha = 0.75f),
                style = VectorTheme.typography.hudSupporting,
            )
            Spacer(Modifier.width(VectorTokens.Space.s12))
            VectorIcons.Maneuver(
                type = next.type,
                dim = VectorTokens.Size.maneuverArrowSmall,
                color = Color.White,
            )
        }
    }
}


/**
 * Choose a route, then start it. One card.
 *
 * ## What was wrong with the previous version
 *
 * Reported as poorly designed, and it was, in four separate ways:
 *
 * 1. **The alternatives were not drawn.** Only the chosen route appeared on
 *    the map, so a chip reading "via شارع حالول" asked the driver to pick a
 *    road they could not see, and the only way to find out where it went was to
 *    select it. That is the deepest problem and it is fixed outside this file —
 *    `MainActivity.drawAlternatives` puts every option on the map, the unchosen
 *    ones muted underneath (`route-alt` in [VectorStyle]).
 * 2. **Everything was said twice.** A selected chip read "9 min / 12 km / via
 *    Al Arouba" and the bar directly beneath it read "9 min / 12 km · arrive
 *    04:27 · 16 turns". Two cards, one job.
 * 3. **Horizontal chips starved the road name.** "via شارع سحيم بن حمد" does
 *    not fit in a third of a phone's width, so the one field that says WHICH
 *    WAY the route goes was the field that got truncated. Rows are full-width.
 * 4. **The turn count truncated the line it was on** ("16 tur…") to carry the
 *    least useful number on the card. Dropped; the step list is one tap away
 *    once driving.
 *
 * ## What the rows say
 *
 * The chosen route is the top row and the only one that spells out the arrival
 * clock — "will I make the meeting" is asked once, about the route you are
 * about to drive, not three times. The others are compact and selectable.
 *
 * `Fastest` and `Shortest` are shown because time and distance alone do not
 * rank: the fastest route here is also the LONGEST (12 km in 9 min against
 * 11 km in 12 min), which looks like an error until something on the card says
 * why it is first. `Shortest` only appears when it is a different route from
 * the fastest, because a badge that is always on the same row is decoration.
 */
@Composable
private fun RouteChooser(
    ui: UiState,
    c: VectorColors,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onChoose: (Int) -> Unit,
    onWalk: () -> Unit = {},
) {
    val options = ui.alternatives
    val chosen = options.getOrNull(ui.chosenRoute)
    // Index of the shortest route, but only when it is not also the fastest.
    // options[0] is the fastest: the backend orders them and the label would be
    // meaningless if that were not true.
    val shortest = options.indices.minByOrNull { options[it].distanceM }
        ?.takeIf { it != 0 && options.size > 1 }

    Surface(color = c.surface, shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r16), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {

            // ---- the route being started ------------------------------
            ChosenRouteRow(
                durationS = chosen?.durationS ?: ui.routeDurationS,
                distanceM = chosen?.distanceM ?: ui.routeDistanceM,
                label = chosen?.label ?: "",
                badge = badgeFor(0, ui.chosenRoute, shortest, options.size),
                trafficNote = trafficNote(ui.jamCount),
                units = ui.units,
                c = c,
            )

            // ---- the ones on offer ------------------------------------
            if (options.size > 1) {
                for (i in options.indices) {
                    if (i == ui.chosenRoute) continue
                    // No rule between the options.
                    //
                    // A hairline is how a *list* separates rows; these are
                    // three alternative answers to one question, and each one
                    // is now a tappable sunken card (see [AlternativeRow]) with
                    // its own edge. Two separators doing the same job is what
                    // made the old chooser read as a settings list.
                    Spacer(Modifier.height(VectorTokens.Space.s8))
                    AlternativeRow(
                        option = options[i],
                        chosenDurationS = chosen?.durationS ?: ui.routeDurationS,
                        badge = badgeFor(i, ui.chosenRoute, shortest, options.size),
                        units = ui.units,
                        c = c,
                        onClick = { onChoose(i) },
                    )
                }
            }

            Spacer(Modifier.height(VectorTokens.Space.s12))
            // Two rows, not three buttons crowded onto one.
            //
            // All three used to sit in an end-aligned row with wrap-content
            // widths, and on a 360 dp phone they did not fit: the capture of
            // this screen shows the primary action rendered as "Sta / rt"
            // across two lines inside a circle. A row that only works when the
            // labels are short is a layout with a bug in it, not a layout.
            //
            // Splitting it also fixes the hierarchy the brief asks for. Start
            // is the one thing this screen exists to offer, so it gets the full
            // width and the brand fill on its own line; Cancel and Walk are the
            // two ways of not doing that, so they share the line below at equal
            // weight. The primary action is now unmissable rather than the
            // third item in a row of three.
            Column(
                Modifier.fillMaxWidth().padding(
                    horizontal = VectorTokens.Space.s12,
                    vertical = VectorTokens.Space.s4,
                ),
            ) {
                // A filled secondary rather than a bare text button. Waze
                // pairs "Leave later" with "Go now" and Google pairs "Add
                // stops" with "Start"; in both the secondary is a real
                // container, because a text button beside a filled one reads as
                // a caption rather than as the other half of a decision.
                // Primary, in the brand colour — not `c.success`.
                //
                // Start was a **dark green** filled surface, and green is this
                // palette's *status* accent: it means "this succeeded", it is
                // what the arrival card and the shade line are drawn in, and
                // nothing else in the product uses it as an action. So the one
                // button the whole preview screen exists to offer was the only
                // saturated colour on screen belonging to a different semantic
                // family than every other primary action in the app — which is
                // why it read as a stray Material button in the before-shot.
                // The brand cerulean is what "the primary action" means
                // everywhere else, and now here.
                VectorButton(
                    text = "Start",
                    onClick = onStart,
                    variant = VectorButtonVariant.Primary,
                    testTag = "preview:start",
                )
                Spacer(Modifier.height(VectorTokens.Space.s8))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
                ) {
                VectorButton(
                    text = "Cancel",
                    onClick = onCancel,
                    variant = VectorButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    testTag = "preview:cancel",
                )
                // WALK — the entry point to walking navigation (V7.4 4C).
                //
                // ## Why it sits here, beside Start
                //
                // Because this is the one moment the app holds everything a
                // walk needs — a destination the user deliberately chose, and
                // a position to walk from — and it is the moment the question
                // "drive or walk?" is actually being asked. A mode switch
                // anywhere earlier would be a preference set before there is
                // anything to apply it to.
                //
                // ## Why it is a secondary rather than half of a pair
                //
                // Vector is a driving navigator and the overwhelmingly common
                // answer is Start. A 50/50 split would make every driver make
                // a choice they have already made. This is the same secondary
                // treatment Cancel gets — a real container rather than a bare
                // text button — so it is findable without competing.
                //
                // ## Not gated on Pro
                //
                // The journey card's walk leg is a sold feature and stays so.
                // Walking NAVIGATION is a baseline capability: nothing here
                // reads `offersPro`, and no paywall can appear between a user
                // and this control.
                VectorButton(
                    text = "Walk",
                    onClick = onWalk,
                    variant = VectorButtonVariant.Tonal,
                    leadingIcon = VectorIcons.glyphIcon(VectorIcons.Extra.WALK),
                    modifier = Modifier.weight(1f),
                    testTag = "preview:walk",
                )
                }
            }
        }
    }
}

/**
 * "Fastest" / "Shortest", or nothing.
 *
 * Returns null for a route that is neither, rather than inventing a third
 * label: "via a different road" is what the road name already says.
 */
private fun badgeFor(index: Int, chosenIndex: Int, shortest: Int?, count: Int): String? = when {
    count <= 1 -> null
    index == 0 -> "Fastest"
    index == shortest -> "Shortest"
    else -> null
}

/**
 * How this route compares with the one being driven.
 *
 * ## Compare, do not enumerate
 *
 * This is the deepest thing the reference reconnaissance turned up about route
 * choice, and it is Waze's, not Google's. Waze labels an alternative
 * **"Similar ETA"** (`v4-evidence/waze/06-alternatives.png`) rather than
 * printing its duration next to the current one.
 *
 * The difference is arithmetic. "17 min" beside "16 min" requires the driver to
 * subtract, at the wheel, to answer the only question they are actually asking:
 * *is it worth switching?* "+1 min" **is** the answer. Vector printed absolute
 * minutes in every row and left the subtraction to the driver.
 *
 * ## Where "the same" stops
 *
 * Exactly where the rounded minutes reach zero, and nowhere else — so the rule
 * the code follows and the rule the label states are the same rule, which is
 * what a separate threshold constant kept getting wrong.
 *
 * It used to be 90 seconds, defended as the honest precision of a 15-minute
 * ETA. The argument is reasonable and the result was not: a real chooser
 * offering 7, 7 and 8 minutes labelled BOTH alternatives "Similar time", so
 * the line whose whole job is to separate two routes said the same thing on
 * every row and separated nothing. Reported from a drive. Suppressing a
 * difference the driver can see in the figures directly underneath is not
 * withholding noise, it is withholding the answer.
 *
 * @param seconds this route's duration.
 * @param chosenSeconds the duration of the route currently selected.
 */
/**
 * The phone's clock preference.
 *
 * Reported as a defect: every arrival time was written as a 24-hour clock
 * regardless of how the phone is set, so a driver on a 12-hour phone read
 * "19:24" everywhere else in their day and "19:24" here too. `is24HourFormat`
 * is the platform's own answer and already accounts for the locale default as
 * well as an explicit user override.
 */
@Composable
internal fun uses24HourClock(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
}

internal fun routeComparison(seconds: Double, chosenSeconds: Double): String {
    val deltaS = seconds - chosenSeconds
    // Rounded, not truncated: 179 s is closer to 3 minutes than to 2, and
    // truncating would under-report every difference by up to a minute.
    val deltaMin = Math.round(Math.abs(deltaS) / 60.0).toInt()
    // "Same time" only when the difference genuinely rounds to nothing.
    //
    // It used to be any difference under 90 s, which sounds reasonable and in
    // practice made the label useless: a chooser offering 7, 7 and 8 minutes
    // printed "Similar time" on BOTH alternatives, so the one line that is
    // supposed to tell two routes apart said the same thing on every row.
    // Reported exactly that way. A minute of difference is a minute, and the
    // driver can decide whether it matters; the app should not decide for them
    // by rounding it away.
    if (deltaMin == 0) return "Same time"
    // "3 min faster" rather than "-3 min": a minus sign is ambiguous about
    // which way it helps.
    return if (deltaS > 0) "+$deltaMin min" else "$deltaMin min faster"
}

/**
 * Say something true about the traffic behind an ETA, or nothing at all.
 *
 * Google Maps writes a sentence here — "Best route now due to traffic
 * conditions", "Fastest route, the usual traffic" — and it is doing real work:
 * it tells the driver whether the number in front of them is a normal day or an
 * unusual one, which is what decides whether to believe it.
 *
 * **Vector cannot say that yet, and must not pretend to.** V3 established that
 * the traffic feed is synthetic and put a 3-probe evidence floor on `/traffic`
 * so the map withholds rather than fabricates; `jamCount` is what survives that
 * floor. So the honest wording is about the *count Vector actually has*, and
 * when the floor withholds everything there is nothing to say — which is why
 * this returns null rather than a reassuring default like "typical traffic".
 *
 * A sentence claiming normal conditions on the strength of no data would be
 * exactly the "fake live traffic product" §25 forbids.
 */
internal fun trafficNote(jamCount: Int?): String? = when {
    jamCount == null -> null           // not loaded, or withheld by the floor
    jamCount == 0 -> null              // nothing measured is not "clear roads"
    jamCount == 1 -> "1 jam reported on the network"
    else -> "$jamCount jams reported on the network"
}

@Composable
private fun ChosenRouteRow(
    durationS: Double,
    distanceM: Double,
    label: String,
    badge: String?,
    trafficNote: String?,
    units: Units,
    c: VectorColors,
) {
    val use24h = uses24HourClock()
    Column(
        Modifier.fillMaxWidth().padding(
            start = VectorTokens.Space.s16,
            end = VectorTokens.Space.s16,
            top = VectorTokens.Space.s16,
            bottom = VectorTokens.Space.s12,
        ),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${(durationS / 60).toInt()} min",
                color = c.ink,
                style = VectorTheme.typography.hudSecondary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(VectorTokens.Space.s12))
            badge?.let { Badge(it, c) }
        }
        Text(
            // No turn count: it truncated this line to carry the least useful
            // number on the card.
            "${shortDistance(distanceM, units)} · arrive " +
                arrivalClock(durationS, System.currentTimeMillis(),
                             java.util.TimeZone.getDefault(), use24h),
            color = c.inkMuted, style = VectorTheme.typography.metadata,
        )
        if (label.isNotBlank()) {
            Text(
                label, color = c.ink,
                style = VectorTheme.typography.metadata,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = VectorTokens.Space.s4),
            )
        }
        // What Vector can honestly say about the traffic behind that ETA, and
        // nothing when it can say nothing. See trafficNote.
        trafficNote?.let {
            Text(
                it, color = c.warning,
                style = VectorTheme.typography.caption,
                modifier = Modifier.padding(top = VectorTokens.Space.s4),
            )
        }
    }
}

@Composable
private fun AlternativeRow(
    option: RouteOption,
    chosenDurationS: Double,
    badge: String?,
    units: Units,
    c: VectorColors,
    onClick: () -> Unit,
) {
    with(VectorMotion) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = VectorTokens.Space.s12)
                // Its own surface and its own edge, so an alternative reads as
                // a thing you can choose rather than as the next row of a list.
                .clip(VectorTheme.shapes.continuous(VectorTokens.Radius.r16))
                .background(c.surfaceSunken)
                .pressable { onClick() }
                // 56dp minimum: this is a control a driver taps, and the
                // previous chips were the only route affordance on the screen.
                .heightIn(min = VectorTokens.Size.row)
                .padding(
                    horizontal = VectorTokens.Space.s16,
                    vertical = VectorTokens.Space.s12,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Stacked, not two columns side by side.
            //
            // The old row put the comparison in a 92 dp left column at
            // `hudContext` — a HUD role, sized to be read at arm's length from
            // a driving band — and the road name in a column to the right of
            // it at `metadata`. The result is in the before-shot: "Similar
            // time" set nearly twice the size of "via Ahmed Bin Ali Street",
            // the two baselines not aligned with each other, and the one field
            // that says WHICH WAY the route goes rendered as the smallest text
            // on its own row. The label was louder than the thing it labels.
            //
            // Stacking puts them in reading order at three deliberate weights:
            // the comparison (why you would pick this), the road (what it is),
            // then the figures (what it costs). Full width, so a long Arabic
            // street name has the whole row instead of a third of it.
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        routeComparison(option.durationS, chosenDurationS),
                        color = c.ink,
                        style = VectorTheme.typography.bodyStrong,
                        maxLines = 1,
                    )
                    badge?.let {
                        Spacer(Modifier.width(VectorTokens.Space.s8))
                        Badge(it, c)
                    }
                }
                if (option.label.isNotBlank()) {
                    Spacer(Modifier.height(VectorTokens.Space.s2))
                    Text(
                        option.label,
                        color = c.inkSecondary,
                        style = VectorTheme.typography.body,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(VectorTokens.Space.s2))
                // The absolute figures stay, quietly, one step down: a driver
                // who does want the number should not have to open anything.
                Text(
                    "${(option.durationS / 60).toInt()} min · " +
                        shortDistance(option.distanceM, units),
                    color = c.inkMuted,
                    style = VectorTheme.typography.caption,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(VectorTokens.Space.s8))
            VectorIcons.glyphIcon(VectorIcons.Extra.CHEVRON_RIGHT).render(18.dp, c.inkMuted)
        }
    }
}

@Composable
private fun Badge(text: String, c: VectorColors) {
    Surface(color = c.primary.copy(alpha = 0.16f), shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r8)) {
        Text(
            text, color = c.primary, style = VectorTheme.typography.caption,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/**
 * The trip readout, and the two controls that act on the journey.
 *
 * ## Layout
 *
 * `[✕]  … arrival · duration / distance left …  [☰]`
 *
 * Both reference products put circular icon buttons at the ends of this bar
 * with the readout between them, and Vector had the readout hard-left with a
 * bare red `Exit` text button hard-right. Three changes:
 *
 * 1. **Exit is a control, not a word.** It was `TextButton { Text("Exit",
 *    color = danger) }` — the lowest-affordance element on the driving screen,
 *    in a colour that says "danger" about an action that is neither dangerous
 *    nor irreversible. Google Maps uses a neutral `✕` circle; Waze uses a
 *    soft-red filled pill that is *narrower* than the primary action. Vector
 *    takes Google's reading: ending navigation is ordinary, so it gets an
 *    ordinary control, sized like every other control on the screen and
 *    reachable without aiming at 13 sp of text.
 *
 * 2. **The step list has its own button.** It used to open by tapping the ETA
 *    text, which is an invisible affordance on the one element a driver reads
 *    without touching — so the step list was effectively undiscoverable, and
 *    the ETA was a control nobody knew was a control.
 *
 * 3. **The duration is emphasised.** Vector led with the arrival clock at
 *    22 sp and put "10 min" beside it at 14 sp dim. Waze leads with the clock,
 *    Google Maps leads with the duration; they genuinely disagree, so Vector
 *    keeps V3's clock-first argument ("will I make the meeting" is asked once,
 *    about a time) and simply stops whispering the duration.
 *
 * ## What is NOT here
 *
 * A route-alternatives button, which both references have in this bar. Vector
 * clears `alternatives` when navigation starts, so the control would have to
 * re-request `/navigate?alternatives=1` mid-drive and reconcile the result with
 * a route already being tracked. That is a feature, not a button, and shipping
 * the button first would make it the dead control this codebase keeps
 * refusing to add. Recorded as deferred in the V4 gap matrix.
 */
@Composable
private fun TripBar(ui: UiState, c: VectorColors, onExit: () -> Unit, onToggleSteps: () -> Unit) {
    Surface(
        color = c.surface,
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(
                horizontal = VectorTokens.Space.s8,
                vertical = VectorTokens.Space.s8,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(
                glyph = VectorIcons.Glyph.CLOSE,
                label = "End navigation",
                tint = c.inkMuted,
                c = c,
                onClick = onExit,
            )
            Column(
                Modifier.weight(1f).padding(horizontal = VectorTokens.Space.s8),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // TWO type sizes on this line, not three.
                //
                // It was the clock at `hudSecondary`, the duration at
                // `hudContext` and the distance at `metadata` — three
                // consecutive steps of the scale sitting side by side, so the
                // row read as three unrelated items rather than as one fact
                // (see the before capture: "06:24  7 min  5.8 km left", each in
                // a different size, with the eye given no place to start).
                //
                // The clock is the anchor, because "what time do I get there"
                // is the question; the duration and the distance are one
                // supporting phrase in one size. All three are still the same
                // fact — how much journey is left — which is why they share a
                // line at all.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        arrivalClock(ui.remainingS, System.currentTimeMillis(),
                                     java.util.TimeZone.getDefault(), uses24HourClock()),
                        color = c.ink,
                        style = VectorTheme.typography.hudSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(VectorTokens.Space.s8))
                    Text(
                        ui.etaLabel + "  ·  " +
                            shortDistance(ui.remainingM, ui.units) + " left",
                        color = c.inkSecondary,
                        style = VectorTheme.typography.metadata,
                        maxLines = 1,
                    )
                }
                // `roadLabel` is a computed property, so it is read ONCE into a
                // local rather than three times through a getter that a smart
                // cast cannot see through — which is what forced a `!!` here.
                val road = ui.roadLabel
                // V7 Stage 5. Phase text exists ONLY when a valid timing model
                // produced it -- `SignalText.phaseText` returns null for the
                // LOCATION and STALE bases, which is every signal on today's
                // data. So with no timing evidence this slot never carries a
                // word about signals, which is the honest screen: the map pill
                // says the junction exists, and nothing claims a colour.
                //
                // It outranks the road name because a phase claim is a live
                // fact about the next decision and the road name is context;
                // it does NOT outrank a jam count or a reroute, which are about
                // the journey as a whole.
                val signalText = ui.signalAhead?.prediction
                    ?.let { dev.vector.geo.signal.SignalText.phaseText(it) }
                // V7.3 / V7.7. The camera line carries the camera's TYPE and
                // the distance — "Speed camera ahead  ·  400 m" — and it
                // appears only inside the alert window, so it can never hold
                // this slot for a camera several kilometres away. The sentence
                // comes from the camera model (one function decides it, and
                // the voice says the same string); the number comes from the
                // shared Units architecture, exactly like the distance on
                // line one. Neither can claim the camera is active.
                val cameraText = ui.cameraAhead?.let {
                    it.line + "  ·  " + ui.units.shortDistance(it.distanceM)
                }
                // Line two, in strict priority order.
                //
                // Rerouting and traffic are statements about the numbers above,
                // and they outrank the road name: a driver who is being
                // rerouted does not need to be told which road they are on. So
                // the slot carries whichever of the three matters most, and
                // never two of them — which is also what stops this line
                // truncating on a phone, the thing the previous version did
                // when rerouting and a jam count appeared together.
                when {
                    ui.rerouting -> Text(
                        "Rerouting…", color = c.warning,
                        style = VectorTheme.typography.metadata,
                    )
                    (ui.jamCount ?: 0) > 0 -> {
                        // Say how many, not just "traffic": a count is
                        // actionable, a warning light is not.
                        val n = VectorMotion.counted(ui.jamCount ?: 0)
                        Text(
                            "$n ${if (n == 1) "jam" else "jams"}",
                            color = c.danger, style = VectorTheme.typography.metadata,
                        )
                    }
                    signalText != null -> Text(
                        signalText,
                        color = c.inkMuted,
                        style = VectorTheme.typography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            contentDescription = "Next signal: $signalText"
                        },
                    )
                    cameraText != null -> Text(
                        cameraText,
                        color = c.warning,
                        style = VectorTheme.typography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            contentDescription = "$cameraText"
                        },
                    )
                    road != null -> Text(
                        road,
                        color = c.inkMuted,
                        style = VectorTheme.typography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            contentDescription = "Driving on $road"
                        },
                    )
                }
            }
            RoundControl(
                glyph = VectorIcons.Glyph.STEPS,
                label = if (ui.showSteps) "Hide the turn list" else "Show the turn list",
                tint = if (ui.showSteps) c.primary else c.inkMuted,
                c = c,
                onClick = onToggleSteps,
            )
        }
    }
}

/**
 * The whole route, as a list of turns.
 *
 * **A sheet on the bottom edge, not a card in the middle of the stack.** It
 * used to be one more item in the bottom `Column`: it faded in above the
 * speedometer and pushed the trip bar down, so it read as furniture that had
 * grown rather than as something the driver had opened. Waze's route list
 * rises from the bottom of the display and covers what is under it, which is
 * both the honest animation for the gesture — the list comes from the control
 * at the bottom of the screen — and the only way it can be tall enough to be
 * worth opening.
 *
 * So while it is open it OWNS the bottom of the screen and the chrome beneath
 * it is not composed at all (see the `stepsOpen` branches in [VectorChrome]),
 * which is also what keeps `NavUiTest."no two text elements overlap"` true.
 * Nothing is lost by that: the header carries the trip bar's own two numbers —
 * how far is left and when you arrive — and the maneuver banner at the top is
 * untouched, so guidance continues while the list is being read.
 *
 * ## Where you are in it
 *
 * The list is the WHOLE route, already-driven parts included, so the row that
 * matters has to be found rather than assumed. It opens scrolled to the
 * maneuver being driven toward — one row of context above it — and follows the
 * journey as it advances. Before this it always opened at step one, which on a
 * forty-minute drive is a screen of turns the driver made half an hour ago.
 *
 * The current row is marked three ways, because §20 forbids colour-only state
 * on the driving screen: an accent bar down its leading edge, bold text, and a
 * tinted ground. Rows already driven are dimmed rather than dropped — "did I
 * miss it?" is a question about the past, and a list that deletes the past
 * cannot answer it.
 */
@Composable
private fun StepSheet(ui: UiState, c: VectorColors, onClose: () -> Unit) {
    // Matched by `index`, not by position: `currentManeuver` is the same
    // object the banner is showing, and identity is what survives a reroute
    // replacing the list underneath it.
    val currentIndex = ui.currentManeuver?.let { cur ->
        ui.maneuvers.indexOfFirst { it.index == cur.index }
    } ?: -1

    val listState = rememberLazyListState()
    // The first scroll JUMPS and later ones animate. The list should already
    // be at the right place when it arrives from the bottom edge: animating a
    // scroll underneath a sliding sheet is two motions for one event, and the
    // driver watches the rows blur past instead of reading them.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        val target = (currentIndex - 1).coerceAtLeast(0)
        if (opened) {
            listState.animateScrollToItem(target)
        } else {
            listState.scrollToItem(target)
            opened = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Bounded by the SCREEN, like the settings sheet, and deliberately
        // short of half of it: the list may never grow into the maneuver
        // banner, which has to stay readable the entire time the sheet is up.
        val listMax = maxHeight * 0.42f
        Surface(
            color = c.surface,
            shape = RoundedCornerShape(
                topStart = VectorTokens.Radius.r20,
                topEnd = VectorTokens.Radius.r20,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = VectorTokens.Size.bottomGap)
            ) {
                // The grab handle says "this came from the edge and goes back
                // there". It is an affordance, not a control: the close button
                // is the control, because a drag is not a gesture to ask a
                // driver for.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = VectorTokens.Space.s8, bottom = VectorTokens.Space.s4),
                    Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .background(c.border, RoundedCornerShape(VectorTokens.Radius.pill))
                    )
                }
                // Header laid out like Waze's trip sheet
                // (`v4-evidence/waze/08-trip-sheet-expanded.png`): a round
                // control at the leading edge, and the arrival time CENTRED
                // above its two supporting numbers rather than set as a title.
                // The sheet does not need to be labelled "Directions" — it is
                // full of directions — and the slot is worth more to the
                // numbers the trip bar underneath it is no longer showing.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = VectorTokens.Space.s8,
                            vertical = VectorTokens.Space.s4,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(
                        glyph = VectorIcons.Glyph.CLOSE,
                        label = "Hide the turn list",
                        tint = c.inkMuted,
                        c = c,
                        onClick = onClose,
                    )
                    Column(
                        Modifier.weight(1f).padding(horizontal = VectorTokens.Space.s8),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            arrivalClock(
                                ui.remainingS, System.currentTimeMillis(),
                                java.util.TimeZone.getDefault(), uses24HourClock(),
                            ),
                            color = c.ink,
                            style = VectorTheme.typography.hudSecondary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            ui.etaLabel + " · " + shortDistance(ui.remainingM, ui.units) + " left",
                            color = c.inkMuted,
                            style = VectorTheme.typography.metadata,
                        )
                    }
                    // Balances the close control so the clock is centred on the
                    // SHEET rather than on the space left over beside it.
                    Spacer(Modifier.size(VectorTokens.Size.control))
                }
                Spacer(Modifier.height(VectorTokens.Space.s8))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = listMax),
                ) {
                    itemsIndexed(ui.maneuvers) { i, m ->
                        StepRow(
                            m = m,
                            state = when {
                                currentIndex < 0 -> StepState.AHEAD
                                i < currentIndex -> StepState.PASSED
                                i == currentIndex -> StepState.CURRENT
                                else -> StepState.AHEAD
                            },
                            // On the row being driven, the distance that means
                            // something is the one the driver is counting down
                            // to THIS turn — not the length of the leg after
                            // it, which is what every other row shows.
                            //
                            // The arrival row shows NO distance. Its leg is
                            // zero metres long, so it rendered "0 m" — a number
                            // that is true and says nothing, against the one
                            // row whose meaning is that there is nothing after
                            // it.
                            distance = when {
                                m.legM <= 0.0 && i == ui.maneuvers.lastIndex -> ""
                                i == currentIndex -> shortDistance(ui.distanceToManeuverM, ui.units)
                                else -> shortDistance(m.legM, ui.units)
                            },
                            c = c,
                        )
                        // No rule under the last row: it would sit on the
                        // bottom edge of the screen and read as a list that had
                        // been cut off rather than one that had ended.
                        // The steps are a sequence, and a sequence is read
                        // down the maneuver arrows on the left, not across a
                        // rule. Removing it also stops the list looking like
                        // the settings screen it sits one tap away from.
                    }
                }
            }
        }
    }
}

/** Where one row sits relative to the vehicle. */
private enum class StepState { PASSED, CURRENT, AHEAD }

@Composable
private fun StepRow(m: Maneuver, state: StepState, distance: String, c: VectorColors) {
    val current = state == StepState.CURRENT
    // Dimmer than `c.inkMuted`, which V4 deliberately lifted for legibility at
    // arm's length. A driven turn is the one thing on this screen that SHOULD
    // recede — it is there to be found, not to be read.
    val faded = c.inkMuted.copy(alpha = 0.45f)
    val glyph = when (state) {
        StepState.PASSED -> faded
        StepState.CURRENT -> c.primary
        StepState.AHEAD -> c.inkMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) c.primary.copy(alpha = 0.12f) else Color.Transparent)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The bar, not the tint, is what marks the row for a driver who cannot
        // separate the hues — and it survives direct sunlight, where a 12%
        // wash does not.
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (current) c.primary else Color.Transparent)
        )
        Row(
            Modifier
                .weight(1f)
                .padding(
                    horizontal = VectorTokens.Space.s16,
                    vertical = VectorTokens.Space.s12,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VectorIcons.Maneuver(
                type = m.type,
                dim = VectorTokens.Size.maneuverArrowSmall,
                color = glyph,
            )
            Spacer(Modifier.width(VectorTokens.Space.s12))
            Text(
                m.instruction,
                color = if (state == StepState.PASSED) faded else c.ink,
                style = VectorTheme.typography.metadata,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        // A screen reader gets the state the accent bar carries
                        // visually; without this the list is a flat recital of
                        // a route with no "you are here" in it.
                        contentDescription = when (state) {
                            StepState.CURRENT -> "Now: ${m.instruction}"
                            StepState.PASSED -> "Passed: ${m.instruction}"
                            StepState.AHEAD -> m.instruction
                        }
                    },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (distance.isNotEmpty()) Text(
                distance,
                color = when (state) {
                    StepState.PASSED -> faded
                    StepState.CURRENT -> c.primary
                    StepState.AHEAD -> c.inkMuted
                },
                style = VectorTheme.typography.metadata,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/**
 * Current speed.
 *
 * Over the limit is shown by colour AND by a ring, never by colour alone: about
 * 8% of male drivers cannot reliably separate the amber from the neutral
 * foreground, and §20 of the brief is explicit that driving-critical state must
 * not be colour-only. The ring is also what makes the state legible in bright
 * sun, where hue washes out before contrast does.
 *
 * ## The three-digit defect
 *
 * This disc was [VectorTokens.Size.speedDial] — the same 54 dp as the limit
 * sign beside it — and a three-digit speed does not fit in it. The arithmetic
 * is in [VectorTokens.Size.speedCurrentDial]; the short version is that the far
 * corner of "100" lands 27.1 dp from the centre of a disc whose radius is 27.
 * Doha's expressways are posted at 100 and 120, so this was not an edge case,
 * it was the Corniche.
 *
 * Two things changed and they are both structural rather than nudges:
 *
 *  * the disc is sized FROM the widest number it has to hold, plus
 *    [VectorTokens.Size.speedDialInset] of clear rim on every side;
 *  * that inset is real padding on the text column, so a driver running a
 *    large system font scale loses the end of a number inside the disc rather
 *    than painting it over the rim.
 *
 * ## Why it cannot collide with the limit sign
 *
 * It never could by layout — the two are siblings in a `Row` with
 * `Arrangement.spacedBy`, so neither is positioned relative to the other and
 * no speed/limit combination can bring them together. What made them *read* as
 * colliding is that both are white discs and 8 dp of light basemap between two
 * white discs is not a gap the eye finds. They are now different sizes with
 * [VectorTokens.Space.s12] between them, and `NavUiTest` asserts the separation
 * on the DISC bounds rather than on the text inside them — the text bounds were
 * what the old overlap test looked at, which is why it passed throughout.
 */
@Composable
private fun Speedometer(ui: UiState, c: VectorColors) {
    val over = ui.overSpeedLimit
    // The over-limit colour crosses over rather than snapping. This is one of
    // the few state changes on the driving screen worth animating: it is a
    // warning the driver should NOTICE, and a value that flips instantly at
    // exactly the tolerance boundary flickers when GPS speed jitters across it.
    val valueColor by animateColorAsState(
        targetValue = if (over) c.warning else c.ink,
        animationSpec = VectorTokens.Motion.short(),
        label = "speedColor",
    )
    val ringColor by animateColorAsState(
        targetValue = if (over) c.warning else Color.Transparent,
        animationSpec = VectorTokens.Motion.short(),
        label = "speedRing",
    )
    Surface(
        color = c.surface,
        shape = CircleShape,
        // A ring as well as a colour, never colour alone: about 8% of male
        // drivers cannot reliably separate the amber from the neutral
        // foreground, and driving-critical state must not be colour-only. The
        // ring is also what survives bright sun, where hue washes out before
        // contrast does. Circular since V4, matching the dial both references
        // use — it reads as an instrument rather than as another rounded card.
        modifier = Modifier
            .border(2.5.dp, ringColor, CircleShape)
            // The dial had no accessible name at all: a screen reader was
            // handed the bare number "58" and the bare token "km/h" as two
            // unrelated nodes, and nothing said which of the two discs in this
            // corner they belonged to. It also said nothing about the
            // over-limit state, which sighted drivers get from the ring — so
            // the one piece of driving-critical information here that §20
            // insists must not be colour-only was, for a screen reader, not
            // present in any form.
            //
            // Not a merged node: the value and the unit stay separate leaves,
            // exactly as they do on [SpeedLimitSign], so this adds a name
            // without changing what the layout tests walk.
            .semantics {
                contentDescription = ui.speedKmh?.let {
                    val v = ui.units.postedLimit(it)
                    if (ui.overSpeedLimit) {
                        "Travelling at $v ${ui.units.speedLabel}, over the limit"
                    } else {
                        "Travelling at $v ${ui.units.speedLabel}"
                    }
                } ?: "Speed not known"
            },
    ) {
        Box(
            Modifier.size(VectorTokens.Size.speedCurrentDial),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // The inset lives HERE, as a constraint on the text, rather
                // than as a gap that happens to exist because the numbers
                // currently fit. A column padded inside a fixed-size box is
                // measured against the narrower width, so the failure mode at
                // a large font scale is a number that runs out of room inside
                // the rim instead of one drawn across it.
                Modifier.padding(horizontal = VectorTokens.Size.speedDialInset),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // "--", not "0", when the receiver has reported no speed.
                //
                // `speedKmh` was `Int = 0`, so a parked phone with no speed fix
                // displayed a confident 0 km/h — see
                // `v4-evidence/vector/before-04-navigating.png`. Google Maps in
                // the same state shows `--` (`gmaps/05-nav-hud.png`). The two
                // facts are different: 0 means stopped, `--` means not known,
                // and only one of them was representable.
                Text(
                    ui.speedKmh?.let { "${ui.units.postedLimit(it)}" } ?: "--",
                    color = if (ui.speedKmh == null) c.inkMuted else valueColor,
                    style = VectorTheme.typography.hudSecondary,
                    fontWeight = FontWeight.Bold,
                    // One line, always. A number has no break opportunity a
                    // driver would recognise, so wrapping "120" would produce
                    // a two-line dial rather than a narrower one — and the
                    // second line would leave the disc through the bottom,
                    // which is the failure the inset above exists to prevent.
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    ui.units.speedLabel, color = c.inkMuted,
                    style = VectorTheme.typography.caption,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The posted limit, drawn as the road sign it is so it reads at a glance.
 *
 * Always the same white disc with a red ring, in both themes. A speed limit
 * sign is a real-world object the driver already recognises; restyling it for
 * night would make it something else.
 *
 * ## The inferred case
 *
 * `/speed` has always answered with `source: "tag" | "default"` and its own
 * docstring says the client "must honour it" — *"presenting an inferred number
 * as a posted sign is the kind of confident wrong answer a driver acts on"* —
 * and no client ever read the field. **90% of Qatar's ways carry no usable
 * `maxspeed`**, so for most roads Vector has been drawing the class median as
 * a regulatory sign.
 *
 * An inferred limit is now drawn as a *dashed* ring rather than a solid one and
 * captioned `typical`, which is the honest version: the number is still useful
 * (it is what the router costs the road at) and it no longer claims to be
 * surveyed. It also does not raise the over-limit warning — see
 * [UiState.overSpeedLimit].
 */
@Composable
private fun SpeedLimitSign(value: Int, units: Units, inferred: Boolean) {
    Box(
        Modifier.semantics {
            contentDescription = if (inferred) {
                "Typical speed for this road, $value ${units.speedLabel}, not a posted limit"
            } else {
                "Speed limit $value ${units.speedLabel}"
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(VectorTokens.Size.speedDial)) {
            val r = size.minDimension / 2f
            drawCircle(SIGN_FIELD, radius = r)
            val ring = r * 0.115f
            if (inferred) {
                // Dashed ring: the sign is Vector's estimate rather than a
                // posted limit, and the dash says so without a word. The caption
                // below repeats it in text, because a dash-and-colour
                // distinction is not available to every driver.
                drawCircle(
                    SIGN_INFERRED_RING,
                    radius = r - ring / 2f,
                    style = Stroke(
                        width = ring,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(r * 0.30f, r * 0.22f), 0f,
                        ),
                    ),
                )
            } else {
                drawCircle(
                    SIGN_RING,
                    radius = r - ring / 2f,
                    style = Stroke(width = ring),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // The inset the dial was sized around: a larger system font scale
            // must run out of room inside the disc rather than over its rim.
            modifier = Modifier.padding(VectorTokens.Size.speedDialInset),
        ) {
            Text(
                "$value",
                color = SIGN_INK,
                style = VectorTheme.typography.hudSupporting,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (inferred) {
                Text(
                    "typical",
                    color = SIGN_INFERRED_INK,
                    style = VectorTheme.typography.caption,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The speed-limit sign's own colours.
 *
 * Deliberately NOT theme roles. This is a depiction of a **legal road sign** — a
 * white field inside a red annulus — and a sign whose colours moved with the
 * app's theme would be a sign the driver could not trust at a glance. It is the
 * same category of exception as a brand mark: the object has a colour of its own
 * and the theme does not get a vote.
 *
 * The *inferred* variant is different, and is not a legal sign at all: it is
 * Vector's own estimate, so it is drawn dashed and grey, which is the convention
 * every map product uses for "we think".
 */
private val SIGN_FIELD = Color(0xFFFFFFFF)
private val SIGN_RING = Color(0xFFC0221C)
private val SIGN_INK = Color(0xFF17191C)
private val SIGN_INFERRED_RING = Color(0xFF8A929B)
private val SIGN_INFERRED_INK = Color(0xFF5B636C)

/**
 * The upgrade entry point that is not in Settings.
 *
 * Vector Pro was reachable from exactly two places: a button at the bottom of
 * the Settings sheet, and the "Cooler route (Pro)" row inside the journey card
 * — which only exists once a destination has been chosen AND a walk was found
 * at the other end. A driver who did neither was never told the tier existed.
 *
 * So: one pill, on the map, above the settings gear. Deliberately NOT a dialog,
 * a banner, or anything that appears on its own — this is a navigation app, and
 * §27's rule about what may sit over the road applies to an upsell first.
 *
 * Drawn only when [UiState.offersPro], which is false for a subscriber, false
 * for a build with no key, and false for a build with an empty catalogue. It is
 * absent while NAVIGATING for the same reason the settings gear is.
 *
 * Tapping it is a DELIBERATE presentation in `PaywallGate`'s sense — the driver
 * asked — so it is not rationed by the once-per-session gate.
 */
@Composable
private fun ProPill(c: VectorColors, onClick: () -> Unit) {
    with(VectorMotion) {
        Surface(
            color = c.surface,
            shape = CircleShape,
            modifier = Modifier.pressable(onClick = onClick),
        ) {
            Box(
                Modifier
                    .heightIn(min = VectorTokens.Size.control)
                    .padding(horizontal = VectorTokens.Space.s12),
                Alignment.Center,
            ) {
                Text(
                    "PRO",
                    color = c.primary,
                    style = VectorTheme.typography.caption,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics {
                        contentDescription = "Vector Pro — see what it includes"
                    },
                )
            }
        }
    }
}

/**
 * A round control on the map or the trip bar. 48 dp.
 *
 * Replaces `IconPill`, which took a text glyph. Presses are acknowledged by a
 * short scale-down ([VectorMotion.pressable]) — Vector's controls previously
 * had `clickable {}` with no indication at all, so a tap produced no feedback
 * until whatever it did became visible.
 */
@Composable
private fun RoundControl(
    glyph: VectorIcons.Glyph,
    label: String,
    tint: Color,
    c: VectorColors,
    rotationDeg: Float = 0f,
    onClick: () -> Unit,
) {
    with(VectorMotion) {
        Surface(
            color = c.surface,
            shape = CircleShape,
            modifier = Modifier.pressable(onClick = onClick),
        ) {
            Box(Modifier.size(VectorTokens.Size.control), Alignment.Center) {
                VectorIcons.Control(
                    kind = glyph,
                    dim = VectorTokens.Size.controlGlyph,
                    color = tint,
                    label = label,
                    rotationDeg = rotationDeg,
                )
            }
        }
    }
}

/**
 * The container the map controls live in.
 *
 * ## Why a capsule and not a column of circles
 *
 * Reviewed against the map screens Mobbin curates for this pattern — Journal,
 * Slopes, My BMW, Grab, Strava, komoot, Tesla. Every one of them groups the
 * controls that act on the map into a **single floating container** with the
 * controls stacked inside it and a hairline between them. None of them draws
 * four separate discs down the edge, which is the platform-default arrangement
 * and the one Vector had.
 *
 * The difference is not decoration. Four identical circles give the eye four
 * objects to resolve and no statement about how they relate; one capsule with
 * three cells in it is a single object whose parts are obviously siblings, and
 * it frees the vertical gap to mean something — see [MapControls], where the
 * gap between capsules says "different kind of control".
 *
 * ## The shape
 *
 * `continuous`, at half the stack's width, so a one-control stack is a
 * squircle-cornered square rather than a circle and a three-control stack is a
 * capsule: the family is the same at every length. Border plus the floating
 * shadow because this sits on arbitrary cartography and has to separate from a
 * dark park, a white motorway and a beige block equally.
 */
@Composable
private fun ControlStack(
    c: VectorColors,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20)
    Column(
        Modifier
            // Explicitly one control wide.
            //
            // [StackDivider] fills the width so the hairline spans the capsule,
            // and in a Column with no width of its own that makes the COLUMN
            // take the maximum available — the first build of this stretched
            // the capsule across the whole screen. The stack is a column of
            // 48 dp cells, so 48 dp is its width, stated rather than inferred.
            .width(VectorTokens.Size.control)
            .vectorShadow(VectorTheme.elevation.floating, shape, VectorTheme.elevation)
            .clip(shape)
            .background(tint ?: c.surfaceFloating)
            .border(1.dp, c.border, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** One cell of a [ControlStack]: the same 48 dp target, without its own disc. */
@Composable
private fun StackControl(
    glyph: VectorIcons.Glyph,
    label: String,
    tint: Color,
    c: VectorColors,
    rotationDeg: Float = 0f,
    onClick: () -> Unit,
) {
    with(VectorMotion) {
        Box(
            Modifier
                .size(VectorTokens.Size.control)
                .pressable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            VectorIcons.Control(
                kind = glyph,
                dim = VectorTokens.Size.controlGlyph,
                color = tint,
                label = label,
                rotationDeg = rotationDeg,
            )
        }
    }
}

/**
 * The hairline between two cells.
 *
 * Inset from both edges so it reads as a separator inside an object rather than
 * as a cut across it — a rule that runs the full width makes one capsule look
 * like two stacked ones, which is the thing the capsule exists to stop.
 */
@Composable
private fun StackDivider(c: VectorColors) {
    Box(
        Modifier
            .padding(horizontal = VectorTokens.Space.s8)
            .fillMaxWidth()
            .height(1.dp)
            .background(c.border),
    )
}

/** A labelled pill control, for the two places a word is clearer than an icon. */
@Composable
private fun SmallPill(label: String, tint: Color, c: VectorColors, onClick: () -> Unit) {
    with(VectorMotion) {
        Surface(
            color = c.surface,
            shape = RoundedCornerShape(VectorTokens.Radius.pill),
            modifier = Modifier.pressable(onClick = onClick),
        ) {
            Text(
                label, color = tint, style = VectorTheme.typography.metadata,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(
                    horizontal = VectorTokens.Space.s16,
                    vertical = VectorTokens.Space.s12,
                ),
            )
        }
    }
}

/**
 * A transient sentence about what the app is doing.
 *
 * On a panel since V4, not bare text on the map. It was `Text(...)` with no
 * background, so "Finding routes…" was 12 sp of grey sitting directly on
 * whatever the cartography happened to be — unreadable over a road, invisible
 * over a label.
 */
@Composable
private fun StatusLine(text: String, busy: Boolean, c: VectorColors) {
    Surface(color = c.surface, shape = RoundedCornerShape(VectorTokens.Radius.pill)) {
        Row(
            Modifier.padding(
                horizontal = VectorTokens.Space.s16,
                vertical = VectorTokens.Space.s8,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The spinner rides WITH the sentence rather than replacing it.
            // A bare indeterminate ring says "something is happening"; the
            // sentence says which something, and §23 asks for the second.
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = c.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(VectorTokens.Space.s8))
            }
            Text(text, color = c.inkMuted, style = VectorTheme.typography.metadata)
        }
    }
}

@Composable
private fun ErrorBanner(text: String, c: VectorColors) {
    Surface(color = c.dangerContainer, shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r12), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = c.dangerText, style = VectorTheme.typography.metadata,
             modifier = Modifier.padding(VectorTokens.Space.s12))
    }
}

/**
 * What the receiver is doing, when it is not doing it well.
 *
 * The defect this closes is described on [GpsHealth]: when the fixes stopped,
 * the vehicle froze and **nothing else on the screen changed**. Every number
 * around the frozen puck — the maneuver, the countdown, the ETA — went on
 * looking authoritative, so a driver in a tunnel could not tell a stopped car
 * from a stopped receiver.
 *
 * Two lines, because the state and its consequence are different facts. "GPS
 * signal lost" tells the driver what happened; "Holding the last known
 * position" tells them what the map is now showing, which is the part that
 * decides whether they trust it. Both reference products print only the first
 * line; the second is Vector's, and it is there because a frozen puck with no
 * explanation is the specific confusion being fixed.
 *
 * [GpsHealth.GOOD] renders nothing at all. A permanent "GPS OK" indicator is
 * chrome competing with the instruction band for the same glance, which V5 §9
 * rules out — the absence of a warning IS the good state.
 */
@Composable
private fun GpsWarning(health: GpsHealth, c: VectorColors) {
    val message = health.message ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val consequence = when (health) {
        GpsHealth.LOST -> "Vector is holding the last position it had. " +
            "Guidance will catch up when the signal returns."
        GpsHealth.WEAK -> "The vehicle may be drawn a little off the road until " +
            "the signal steadies."
        GpsHealth.ACQUIRING -> "This usually takes a few seconds with a clear " +
            "view of the sky."
        // The only one a driver can DO something about, and the only one that
        // gets a button. Reported from the S24 as "always showing searching for
        // GPS" — which it did, because the cause is almost never inside the
        // app: location switched off, a revoked permission, or a provider that
        // will not service the request.
        // Two causes, one sentence, and it does not guess which.
        //
        // UNAVAILABLE is reached from a denied permission AND from location
        // being switched off for the whole phone. The first draft said
        // "Location is switched off for this phone", which is simply wrong in
        // the permission case — the radio is on and the driver would go looking
        // for a switch that is already where it should be. The honest statement
        // is about the effect, which is the same either way, and the control
        // below opens the one screen that shows both.
        GpsHealth.UNAVAILABLE -> "Vector has no access to your location, so it " +
            "cannot place you on the map or guide you."
        GpsHealth.GOOD -> null
    }
    // UNAVAILABLE is a *failure* the driver can fix; the other three are
    // transient conditions that resolve themselves. Colouring them the same
    // made "waiting a moment for a fix" look exactly as alarming as "location
    // is off", which is how a warning becomes scenery.
    val actionable = health == GpsHealth.UNAVAILABLE
    val container = if (actionable) c.dangerContainer else c.warningContainer
    val ink = if (actionable) c.dangerText else c.sunnyText
    val shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20)
    Column(
        Modifier
            .fillMaxWidth()
            .vectorShadow(VectorTheme.elevation.card, shape, VectorTheme.elevation)
            .clip(shape)
            .background(container)
            .padding(VectorTokens.Space.s16)
            .semantics(mergeDescendants = true) {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                contentDescription = listOfNotNull(message, consequence).joinToString(". ")
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The glyph sits in its own disc so the banner has an anchor at the
            // same size as every other leading tile in the product, rather than
            // a 16 dp mark floating against a paragraph.
            Box(
                Modifier
                    .size(32.dp)
                    .clip(VectorTheme.shapes.pill)
                    .background(ink.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                VectorIcons.Control(
                    kind = VectorIcons.Glyph.REPORT,
                    dim = 18.dp,
                    color = ink,
                )
            }
            Spacer(Modifier.width(VectorTokens.Space.s12))
            Text(
                message,
                color = c.ink,
                style = VectorTheme.typography.cardTitle,
                modifier = Modifier.weight(1f),
            )
        }
        consequence?.let {
            Spacer(Modifier.height(VectorTokens.Space.s8))
            // A sentence, not a fragment. "Check that location is turned on"
            // was four words of instruction with no explanation and no way to
            // act on it; this says what is wrong, what it costs, and — when
            // there is one — offers the fix below.
            Text(
                it,
                color = c.inkSecondary,
                style = VectorTheme.typography.metadata,
            )
        }
        if (actionable) {
            Spacer(Modifier.height(VectorTokens.Space.s12))
            // The button is the point.
            //
            // Location being off is fixed in the SYSTEM settings, which is
            // three taps away and not obviously where to go. Every reference
            // that handles this state well pairs the sentence with the control
            // that resolves it, and this banner was a sentence alone.
            VectorButton(
                text = "Open location settings",
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                variant = VectorButtonVariant.Primary,
                size = VectorButtonSize.Medium,
                testTag = "gps:turn-on",
            )
        }
    }
}

/**
 * How many car parks Vector will offer.
 *
 * Three. In a crowded part of Doha — the Pearl, Souq Waqif, a mall forecourt —
 * the index answers with every garage in the block, and four rows in a card the
 * driver is reading with the engine still running is a list they scroll rather
 * than choose from. Three is what can be taken in at a glance, and a driver who
 * wants the fourth-best car park is a driver who is going to search instead.
 *
 * Applied in BOTH places, which is why it is a shared constant rather than an
 * argument spelled at each call: `MainActivity` asks the index for this many,
 * and [ArrivalCard] draws at most this many. Either alone would be a cap the
 * other could quietly exceed — the render path used to draw whatever arrived,
 * so a server that ignored `limit`, or a later call site that passed a
 * different one, would have put the extra rows on screen with nothing failing.
 */
const val PARKING_SUGGESTIONS = 3

/**
 * What the journey came to.
 *
 * ## Why this exists
 *
 * Arriving used to produce one thing: `"Arrived at Villaggio Mall"` in a 12 sp
 * grey pill on an empty map, with every number from the drive discarded in the
 * same statement. Reported from the S24 as the weakest moment in the product,
 * and it is — the end of a drive is when the driver has questions, and Vector
 * held the answers to all of them and threw them away.
 *
 * ## The hierarchy
 *
 * Reversed from the driving screen, deliberately. While driving, the road and
 * the next maneuver are everything and the destination's name is context; on
 * arrival the car has stopped, nothing is urgent, and the NAME is the headline
 * — it is the confirmation the driver is looking for. The numbers below it are
 * a receipt, set at `detail`.
 *
 * ## The arrival-intelligence direction, honestly
 *
 * `VECTOR-PRO-RESCOPED.md` governs Pro copy and forbids shipping arrival
 * intelligence as a claimed feature; nothing here claims one. The parking row
 * is a real query against the real index — see `VectorApi.nearby` — so it
 * shows what is actually there, says plainly when that is nothing, and tapping
 * one routes to it through the same path as any other destination. That is the
 * seam V5 §14 asks to be kept open, occupied by the one true thing available
 * today rather than by a promise.
 */
@Composable
private fun ArrivalCard(
    a: ArrivalSummary,
    units: Units,
    c: VectorColors,
    onDismiss: () -> Unit,
    onPick: (VectorApi.Place) -> Unit,
) {
    Surface(
        color = c.surface,
        shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(VectorTokens.Space.s16)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Arrived",
                        color = c.success,
                        style = VectorTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        a.name,
                        color = c.ink,
                        style = VectorTheme.typography.hudSecondary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RoundControl(
                    glyph = VectorIcons.Glyph.CLOSE,
                    label = "Dismiss the arrival summary",
                    tint = c.inkMuted,
                    c = c,
                    onClick = onDismiss,
                )
            }
            Spacer(Modifier.height(VectorTokens.Space.s8))
            // The receipt. Distance and the time it actually took, then how
            // that compared with the prediction — which is a statement about
            // Vector and is therefore last and quietest.
            Text(
                buildString {
                    append(shortDistance(a.distanceM, units))
                    append(" in ")
                    append(spokenDuration(a.actualS))
                    a.etaVerdict?.let { append(" · ").append(it) }
                },
                color = c.inkMuted,
                style = VectorTheme.typography.metadata,
            )

            // Parking. `null` means the query is still in flight, which is a
            // different statement from "there is none" and has to look like
            // one — the same distinction the search panel draws between "no
            // results yet" and "no results".
            val parking = a.parking
            if (parking == null || parking.isNotEmpty()) {
                Spacer(Modifier.height(VectorTokens.Space.s12))
                Text(
                    "Parking nearby",
                    color = c.ink,
                    style = VectorTheme.typography.metadata,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(VectorTokens.Space.s4))
            }
            when {
                parking == null -> Text(
                    "Looking…",
                    color = c.inkMuted,
                    style = VectorTheme.typography.metadata,
                )
                parking.isEmpty() -> Unit   // Say nothing rather than "none found".
                // Capped HERE as well as in the query. The list arrives from
                // the network and this card is the last thing between it and
                // the driver; trusting the caller to have asked for the right
                // number is exactly the assumption that let a `limit = 4`
                // become four rows on screen. See [PARKING_SUGGESTIONS].
                else -> for (p in parking.take(PARKING_SUGGESTIONS)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(p) }
                            .padding(vertical = VectorTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VectorIcons.Control(
                            kind = VectorIcons.Glyph.PLACE,
                            dim = 18.dp,
                            color = c.primary,
                            label = null,
                        )
                        Spacer(Modifier.width(VectorTokens.Space.s8))
                        Text(
                            p.name,
                            color = c.ink,
                            style = VectorTheme.typography.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics {
                                contentDescription = "Park at ${p.name}"
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A duration as a driver would say it. Never "0 min"; see [UiState.etaLabel].
 */
private fun spokenDuration(seconds: Double): String {
    val mins = Math.round(seconds / 60.0).toInt()
    return when {
        mins < 1 -> "under a minute"
        mins < 60 -> "$mins min"
        else -> {
            val h = mins / 60
            val m = mins % 60
            if (m == 0) "$h h" else "$h h $m min"
        }
    }
}

/**
 * Shown when an endpoint had to be moved a long way to reach a road.
 *
 * The backend reports `snap_max_m` precisely so this can exist: a route that
 * starts 400 m from where the user tapped is not wrong, but they should be told
 * rather than left to wonder why the blue line begins somewhere else.
 */
@Composable
private fun SnapWarning(distanceM: Double, units: Units, c: VectorColors) {
    Surface(color = c.warningContainer, shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r12), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Nearest road is ${shortDistance(distanceM, units)} from the point you picked",
            color = c.warning, style = VectorTheme.typography.metadata,
            modifier = Modifier.padding(VectorTokens.Space.s12),
        )
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/**
 * The preferences sheet.
 *
 * Every row changes something the driver would go looking for, and the list is
 * short on purpose — see the [Settings] KDoc for what was considered and
 * rejected, and why an inert control is worse than a missing one.
 *
 * Reachable only outside navigation. A scrim swallows taps on the map behind it
 * so a mis-tap cannot drop a pin under the sheet.
 */
@Composable
private fun SettingsSheet(
    ui: UiState,
    c: VectorColors,
    onChange: (Settings) -> Unit,
    onClearRecents: () -> Unit,
    onClose: () -> Unit,
    onClearPlace: (Places.Slot) -> Unit,
    onPickDrive: (Drives.Drive) -> Unit,
    onDeleteDrive: (Drives.Drive) -> Unit,
    onClearDrives: () -> Unit,
    onOpenPaywall: () -> Unit = {},
    onRestore: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
) {
    val s = ui.settings
    Box(
        Modifier
            .fillMaxSize()
            // `c.scrim`, not a literal. The theme already owns this role — and
            // it owns it per theme, because the same 60 % black that dims a
            // daylight map is not the right value over a dark one.
            .background(c.scrim)
            .clickable { onClose() },
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Bounded by the SCREEN, not by a magic number. It was `max = 560.dp`,
        // and on a 412x915 phone that put the consent explanation below the
        // fold — consent a driver has to scroll to find is weaker consent, and
        // it is the one block on this sheet that has to be read rather than
        // merely operated. The scroll stays for short screens and landscape.
        val sheetMax = maxHeight * 0.9f
        // The sheet's own shape, not a rectangle with two rounded corners.
        //
        // `RoundedCornerShape(topStart = 20)` is the stock sheet silhouette and
        // it is the one the discovery sheet deliberately does not use: Vector's
        // sheets are `shapes.continuous(28)`, a squircle whose curvature eases
        // in rather than starting abruptly at the tangent. Having one sheet in
        // the app with a circular-arc corner and another with a continuous one
        // is exactly the "screens that do not belong to one system" the review
        // is looking for.
        Surface(
            color = c.surfaceFloating,
            shape = VectorTheme.shapes.sheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = VectorTokens.Space.s20)
                    .padding(bottom = VectorTokens.Size.bottomGap)
                    .heightIn(max = sheetMax)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(VectorTokens.Space.s4),
            ) {
                // The same grab handle the discovery sheet has. This sheet is
                // not draggable, and the handle is still right: it is what says
                // "this is a sheet and it goes away downwards", and two sheets
                // in one app that announce themselves differently is the
                // inconsistency the redesign is supposed to remove.
                Box(
                    Modifier.fillMaxWidth().height(VectorTokens.Size.sheetHandleTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            // 40 x 4. `sheetHandleBar` is the bar's THICKNESS
                            // (4 dp) and passing it as the width drew a 4 x 4
                            // dot — visible in the first capture of this sheet
                            // as a speck above the title. The discovery sheet's
                            // handle is 40 dp wide and these two have to match.
                            .width(40.dp)
                            .height(VectorTokens.Size.sheetHandleBar)
                            .clip(VectorTheme.shapes.pill)
                            .background(c.borderStrong),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Settings",
                        color = c.ink,
                        style = VectorTheme.typography.screenTitle,
                        modifier = Modifier.weight(1f),
                    )
                    // A tonal pill, not a Material `TextButton`. "Done" is the
                    // way out of a sheet and it was a bare blue word with a
                    // ripple — the one control on the screen that looked like it
                    // came from a different application, because it did.
                    VectorButton(
                        text = "Done",
                        onClick = onClose,
                        variant = VectorButtonVariant.Tonal,
                        size = VectorButtonSize.Small,
                        fillWidth = false,
                        testTag = "settings:done",
                    )
                }

                SettingsSection("Map", c) {
                Choice(
                    "Theme", c,
                    listOf(
                        "Light" to VectorStyle.MapTheme.LIGHT,
                        "Dark" to VectorStyle.MapTheme.DARK,
                        "System" to VectorStyle.MapTheme.SYSTEM,
                    ),
                    s.theme,
                ) { onChange(s.copy(theme = it)) }

                Choice(
                    "Orientation", c,
                    listOf(
                        "North up" to MapOrientation.NORTH_UP,
                        "Heading up" to MapOrientation.HEADING_UP,
                    ),
                    s.orientation,
                ) { onChange(s.copy(orientation = it)) }

                // 2D/3D only applies heading-up (see MapPerspective.TILTED), so
                // it is only offered there rather than being offered and then
                // silently ignored.
                if (s.orientation == MapOrientation.HEADING_UP) {
                    Choice(
                        "Navigation view", c,
                        listOf(
                            "2D" to MapPerspective.FLAT,
                            "3D" to MapPerspective.TILTED,
                        ),
                        s.perspective,
                    ) { onChange(s.copy(perspective = it)) }
                }

                Toggle("Show traffic before setting off", c, s.trafficInExplore) {
                    onChange(s.copy(trafficInExplore = it))
                }

                }

                SettingsSection("Navigation", c) {
                // Waze ships `Auto zoom` on and Vector had no equivalent at
                // all: one hardcoded navigation zoom of 16.5, which is a
                // reasonable city zoom and shows about eight seconds of road
                // at 120 km/h.
                Toggle("Zoom out at speed", c, s.autoZoom) {
                    onChange(s.copy(autoZoom = it))
                }
                Choice(
                    "Distances", c,
                    listOf(
                        "Kilometres" to Units.METRIC,
                        "Miles" to Units.IMPERIAL,
                    ),
                    s.units,
                ) { onChange(s.copy(units = it)) }

                }

                SettingsSection("Voice", c) {
                // Four modes, as a radio list rather than a chip row: the
                // labels are long enough that chips would truncate, and Waze
                // uses a list for the same four for the same reason. Each row
                // carries the glyph that encodes how much sound it means.
                VoiceChoice(s.voice, c) { onChange(s.copy(voice = it)) }
                }

                // ---- Vector Pro ------------------------------------
                //
                // Drawn only when there is something to say. Three states, and
                // the third one is the important one:
                //
                //   offersPro   a free driver in a build with at least one Pro
                //               feature -> the entry point.
                //   pro == PRO  a subscriber -> status and the controls they
                //               might need. Never an upsell.
                //   otherwise   an unconfigured build, or a configured build
                //               with nothing gated yet -> NOTHING. Not a
                //               greyed row, not a "coming soon" — absent.
                //
                // That last case is why this is not a `Toggle`: an inert
                // control is worse than a missing one (see the Settings KDoc),
                // and a paid tier nobody can buy is the exact defect
                // GOALS.md recorded against the previous attempt.
                if (ui.offersPro) {
                    SettingsSection("Vector Pro", c) {
                    Text(
                        "Vector gets you there. Vector Pro gets you from the " +
                            "car to the door.",
                        color = c.inkMuted,
                        style = VectorTheme.typography.metadata,
                        modifier = Modifier.padding(bottom = VectorTokens.Space.s12),
                    )
                    // `VectorButton`, not Material's `Button` with two colours
                    // passed to `ButtonDefaults`. Same reasoning as the Start
                    // button on the preview sheet: overriding a container
                    // colour leaves the height, the corner radius, the ripple
                    // and the label style of a stock Android button, and this
                    // one sat two rows under a chip row that had the same
                    // problem.
                    VectorButton(
                        text = "See what Pro includes",
                        onClick = onOpenPaywall,
                        variant = VectorButtonVariant.Primary,
                        testTag = "pro:open",
                    )
                    Spacer(Modifier.height(VectorTokens.Space.s8))
                    VectorButton(
                        text = "Restore a purchase",
                        onClick = onRestore,
                        variant = VectorButtonVariant.Ghost,
                        size = VectorButtonSize.Small,
                        testTag = "pro:restore",
                    )
                    }
                } else if (ui.pro == dev.vector.android.pro.ProStatus.PRO) {
                    SettingsSection("Vector Pro", c) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = VectorTokens.Size.row),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Subscription active", color = c.ink,
                             style = VectorTheme.typography.body,
                             modifier = Modifier.weight(1f))
                        VectorBadge("PRO", tint = c.leafContainer, onTint = c.leafText)
                    }
                    // Only where there is somewhere to send them. Vector's
                    // deep link goes to Play, and RevenueCat returns no
                    // management URL at all for the Test Store — a control that
                    // opens the wrong store is worse than one that is absent,
                    // which is the same rule the rest of this sheet follows.
                    if (dev.vector.android.pro.ProStoreCopy.managesSubscriptions(ui.proStore)) {
                        VectorButton(
                            text = "Manage subscription",
                            onClick = onManageSubscription,
                            variant = VectorButtonVariant.Secondary,
                            size = VectorButtonSize.Small,
                            testTag = "pro:manage",
                        )
                        Spacer(Modifier.height(VectorTokens.Space.s8))
                    }
                    VectorButton(
                        text = "Restore a purchase",
                        onClick = onRestore,
                        variant = VectorButtonVariant.Ghost,
                        size = VectorButtonSize.Small,
                        testTag = "pro:restore2",
                    )
                    }
                }

                SettingsSection("Privacy", c) {
                // The label says what HAPPENS, not what the feature is called.
                // The previous HUD pill read "Share traffic", which was reported
                // as vague: it does not say what is shared, with whom, or that
                // the driver's own position is what is being sent.
                Toggle("Send my speed data to improve the map", c, s.contributing) {
                    onChange(s.copy(contributing = it))
                }
                Text(
                    // Consent needs to say what it means, not just be a switch.
                    "While you navigate, Vector sends how fast traffic moved on the " +
                        "roads you drove. The start and end of each trip are cut back " +
                        "by 200 m, a road is only learned once five separate trips " +
                        "agree, and nothing is kept that could identify one journey " +
                        "or one phone.",
                    color = c.inkMuted,
                    style = VectorTheme.typography.caption,
                    modifier = Modifier.padding(top = VectorTokens.Space.s8),
                )
                }

                // ---- the driver's own data ---------------------------------
                //
                // Everything Vector remembers about this driver, in one place,
                // with a way to remove each of it. It used to be one row —
                // "Recent destinations / Clear" — and the two stores added
                // since had no management surface at all: `Places.clear` was
                // written, tested, and called by nothing, so a Home set to the
                // wrong building could be re-set but never removed. V6 §13.E
                // asks for clearing to work and §6 rules out a persistence API
                // that no interactive element reaches.
                //
                // Here rather than in the search sheet, which is a surface for
                // going somewhere: the search sheet OFFERS Home, Work and the
                // recents, and offering them is a different job from curating
                // them. Putting a delete control next to a destination a driver
                // is trying to tap at a junction is how the wrong one gets hit.
                val hasData = ui.recents.isNotEmpty() || ui.places.isNotEmpty() ||
                    ui.drives.isNotEmpty()
                if (hasData) {
                SettingsSection("Your data", c) {
                // Home and Work, each with the control that forgets it. Only
                // the slots that are SET are listed — an empty row with a dead
                // "Clear" beside it is exactly the invisible-success class of
                // bug §6 is about.
                for (place in ui.places) {
                    SavedDataRow(
                        label = place.slot.label,
                        detail = titleCase(place.name),
                        c = c,
                        onClear = { onClearPlace(place.slot) },
                    )
                }

                if (ui.recents.isNotEmpty()) {
                    SavedDataRow(
                        label = "Recent destinations",
                        detail = "${ui.recents.size} saved",
                        c = c,
                        onClear = onClearRecents,
                    )
                }

                if (ui.drives.isNotEmpty()) {
                    DriveHistory(ui.drives, ui.units, c, onPickDrive, onDeleteDrive,
                                 onClearDrives)
                }
                }
                }

                // Map data attribution.
                //
                // MapLibre's own attribution widget and logo are switched OFF
                // in MainActivity, because a permanent watermark over the
                // bottom-left of a navigation map is chrome the driver did not
                // ask for. Removing it does NOT remove the obligation: the
                // basemap is OpenStreetMap under ODbL, which requires the
                // credit to be shown. So it moves HERE, where it is available
                // and legible, rather than disappearing — hiding the widget and
                // dropping the credit would be a licence breach dressed up as a
                // UI improvement.
                SettingsSection("About", c) {
                Text(
                    "Map data © OpenStreetMap contributors, licensed under ODbL. " +
                        "Rendered by Vector with MapLibre.",
                    color = c.inkMuted,
                    style = VectorTheme.typography.caption,
                )
                }
                Spacer(Modifier.height(VectorTokens.Space.s16))
            }
        }
    }
}

/**
 * The shape of a drive, drawn small.
 *
 * ## What it is
 *
 * The recorded track (`Drives.Drive.track`) fitted to the tile and stroked.
 * Not a map — there is no basemap behind it and there should not be: at 48 dp
 * a road network is noise, and the only thing that distinguishes one journey
 * from another at that size is the LINE. Both reference products use exactly
 * this for a trip history, and a fitness app's route thumbnail is the same
 * idea for the same reason.
 *
 * ## Why the fit is uniform and centred
 *
 * Scaling x and y independently would make every drive fill the tile, which
 * sounds better and is worse: a straight 12 km run up the Corniche and a
 * twisting drive through Msheireb would both become a shape that fills a
 * square, and the one thing the thumbnail is for — telling two journeys apart
 * at a glance — would be gone. One scale for both axes preserves the drive's
 * real proportions, and a track that is mostly north-south stays tall and thin.
 *
 * Latitude is flipped because screen y grows downward and north does not.
 * Longitude is NOT corrected for latitude here: over a single city journey the
 * cos(lat) factor is constant to four decimal places, and applying it would
 * only scale the whole shape by a number the uniform fit immediately divides
 * out again.
 */
@Composable
private fun DriveTrackThumbnail(
    track: List<dev.vector.geo.LngLat>,
    stroke: Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier) {
        var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        for (p in track) {
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
        }
        val spanLng = (maxLng - minLng).coerceAtLeast(1e-9)
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-9)
        // One scale for both axes; see the KDoc.
        val scale = kotlin.math.min(size.width / spanLng, size.height / spanLat)
        val drawnW = spanLng * scale
        val drawnH = spanLat * scale
        val offX = (size.width - drawnW) / 2f
        val offY = (size.height - drawnH) / 2f
        val path = androidx.compose.ui.graphics.Path()
        track.forEachIndexed { i, p ->
            val x = (offX + (p.lng - minLng) * scale).toFloat()
            // Flipped: north is up, screen y is down.
            val y = (offY + (maxLat - p.lat) * scale).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = stroke,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
        // Where the drive ended, so the line has a direction. The start needs no
        // mark: a line with one dot on it is read from the undotted end.
        track.lastOrNull()?.let { end ->
            drawCircle(
                color = stroke,
                radius = 2.4.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(
                    (offX + (end.lng - minLng) * scale).toFloat(),
                    (offY + (maxLat - end.lat) * scale).toFloat(),
                ),
            )
        }
    }
}

/**
 * One thing Vector remembers, and the control that forgets it.
 *
 * `[label] · [detail] ................ [Clear]`
 *
 * Shared by Home, Work and the recents list so the three read as one set of
 * rows rather than three bespoke layouts, and so the delete affordance is in
 * the same place and the same colour in each. `c.danger` on the action, because
 * it is the one control on this sheet that destroys something.
 */
@Composable
private fun SavedDataRow(
    label: String,
    detail: String,
    c: VectorColors,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = VectorTokens.Size.row)
            .padding(top = VectorTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = c.ink, style = VectorTheme.typography.body)
            Text(
                detail,
                color = c.inkMuted,
                style = VectorTheme.typography.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(VectorTokens.Space.s12))
        ConfirmableClear(
            what = label.lowercase(),
            onConfirm = onClear,
            testTag = "clear:$label",
        )
    }
}

/**
 * "Clear", and the question it should always have asked.
 *
 * ## What was wrong with it
 *
 * Two of these sat on the settings sheet as bare red words in a Material
 * `TextButton`, and each one **deleted immediately**. Tapping "Clear" beside
 * "Past drives" destroyed the entire journey log with no confirmation and no
 * undo — a mis-tap on a 48 dp target next to a scrollable list. The redesign
 * brief requires every destructive action to confirm, and this is the one place
 * in the product where data actually goes away.
 *
 * ## What it is now
 *
 * A quiet coral-tinted pill (not a red word, which reads as a link and gives no
 * target edge), and a [VectorDialog] with `destructive = true` in front of the
 * deletion. The dialog names what is about to go, because "Are you sure?" is
 * not a question anybody can answer.
 *
 * The state is local on purpose: a pending confirmation is not something the
 * rest of the app has any reason to know about, and hoisting it would put a
 * transient dialog flag into `UiState` where every screenshot and every
 * `NavStateTest` case would carry it.
 */
@Composable
private fun ConfirmableClear(
    what: String,
    onConfirm: () -> Unit,
    testTag: String? = null,
) {
    var asking by remember { mutableStateOf(false) }
    // Quiet, not loud.
    //
    // These were solid coral pills, and the "Your data" section has four of
    // them stacked — Home, Work, recents, drives — so the capture of that
    // screen is a column of red buttons shouting at a driver who came to read
    // a privacy paragraph. A destructive action should be *findable*, not
    // dominant, and the confirmation dialog is what now carries the weight:
    // the filled coral lives on the dialog's confirm button, where the decision
    // is actually made.
    VectorButton(
        text = "Clear",
        onClick = { asking = true },
        variant = VectorButtonVariant.Ghost,
        size = VectorButtonSize.Small,
        fillWidth = false,
        contentTint = VectorTheme.colors.dangerText,
        testTag = testTag,
    )
    if (asking) {
        VectorDialog(
            title = "Clear $what?",
            body = "This removes it from this phone. It cannot be undone.",
            confirmLabel = "Clear",
            onConfirm = { asking = false; onConfirm() },
            dismissLabel = "Keep",
            onDismiss = { asking = false },
            destructive = true,
            testTag = "dialog:clear",
        )
    }
}

/**
 * The drives the driver has driven.
 *
 * ## Why this is a list and the rest of the group is rows
 *
 * The other stores answer "what is saved" with a count. A journey log answers
 * "when did I go there and what did it take", and a count answers none of that
 * — so this is the one entry in the group that shows its contents.
 *
 * Each row carries three things, in the order a person recalls a drive: WHERE,
 * WHEN, and what it cost. Distance and duration are on the second line at label
 * size because they are the part you check rather than the part you scan for.
 *
 * ## The rows are not decoration
 *
 * Tapping one routes there again, which is the only reason a driver looks at
 * this list — "I went to that clinic in March, what was it called" ends in
 * wanting to go back. Without that the whole section would be a read-only
 * report, and §6 asks every visible interactive element to reach a state.
 *
 * An abandoned drive is marked. It has to be: a 2 km, 6-minute entry for a
 * 40 km journey to the airport is not a wrong number, it is a drive that was
 * given up on, and without the label the log would look like it was lying.
 */
@Composable
private fun DriveHistory(
    drives: List<Drives.Drive>,
    units: dev.vector.geo.Units,
    c: VectorColors,
    onPick: (Drives.Drive) -> Unit,
    onDelete: (Drives.Drive) -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = VectorTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Past drives", color = c.ink, style = VectorTheme.typography.body,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(VectorTokens.Space.s12))
        ConfirmableClear(
            what = "every past drive",
            onConfirm = onClearAll,
            testTag = "clear:drives",
        )
    }
    // Bounded on SCREEN as well as in the store.
    //
    // Drives.LIMIT keeps twenty; this shows the most recent eight, because the
    // sheet is already scrollable for the consent text and a twenty-row log
    // would push the map attribution below two screens of history. Eight is
    // about a working week, and the count below says what is not shown rather
    // than pretending the list is complete.
    val shown = drives.take(DRIVE_ROWS)
    for ((i, d) in shown.withIndex()) {
        with(VectorMotion) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = VectorTokens.Size.row)
                    .pressable { onPick(d) }
                    .padding(vertical = VectorTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same clock-arrow `RecentList` uses for a place the driver
                // has been to.
                //
                // Not decoration. Every other tappable destination row in the
                // app carries a leading glyph, and without one these rows read
                // as a read-only report — so the one thing they are for, going
                // back somewhere, is the thing a driver would not discover.
                // Matching the established pattern rather than inventing a
                // second one is also what keeps "a row with an icon is a row
                // you can tap" true across the product.
                // The ROUTE that was driven, when there is one — otherwise the
                // place's family tile.
                //
                // A past drive is the only row in the product that has a shape
                // to show: the positions the car actually passed through. See
                // `Drives.Drive.track` for why that is the track and not the
                // plan. Drives recorded before this shipped, and drives that
                // never got a fix, have no track and keep the family tile, so
                // the list never has a hole in it.
                val family = PlaceFamily.of(d.destination)
                val hasTrack = d.track.size >= 2
                Box(
                    Modifier
                        // A route needs more than a 48 dp icon slot. A drive
                        // with a shape gets a 64 dp tile — big enough that the
                        // line is a route rather than a scratch — and a drive
                        // without one keeps the standard tile so the two kinds
                        // of row still share a left edge.
                        .size(if (hasTrack) 64.dp else VectorTokens.Size.control)
                        .clip(VectorTheme.shapes.continuous(VectorTokens.Radius.r16))
                        .background(family.container(c)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasTrack) {
                        DriveTrackThumbnail(
                            track = d.track,
                            stroke = family.onContainer(c),
                            modifier = Modifier.fillMaxSize()
                                .padding(VectorTokens.Space.s8),
                        )
                    } else {
                        family.icon.render(20.dp, family.onContainer(c))
                    }
                }
                Spacer(Modifier.width(VectorTokens.Space.s12))
                Column(Modifier.weight(1f)) {
                    Text(
                        titleCase(d.destination),
                        color = c.ink,
                        style = VectorTheme.typography.bodyStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        driveSummaryLine(d, units),
                        color = c.inkMuted,
                        style = VectorTheme.typography.caption,
                        // Two lines. "Today 06:18 · 800 m · under a minute ·
                        // stopped" does not fit one line at any font scale this
                        // product supports, and the before-shot shows it cut to
                        // "under a minut…" — which loses the word that says the
                        // drive was abandoned, the single most useful fact on
                        // the row.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Forget this one.
                //
                // Present because `Drives.remove` exists, and an API with no
                // control that reaches it is the dead-persistence case §6 rules
                // out — the same criticism this session made of `Places.clear`.
                // A per-row control rather than a long-press because a
                // long-press is undiscoverable, and rather than a swipe because
                // the sheet it lives in already scrolls vertically and a
                // horizontal gesture inside it fights that.
                //
                // The row itself stays tappable for the useful action (drive
                // there again); this is the small, dim, right-hand affordance
                // that `SavedDataRow` uses for the same job.
                Spacer(Modifier.width(VectorTokens.Space.s8))
                // The icon set's delete glyph in a real icon button, not a
                // multiplication sign in a Material `TextButton`. The old one
                // had no accessible name at all — a screen reader read it as
                // the character "×".
                VectorIconButton(
                    icon = VectorIcons.glyphIcon(VectorIcons.Extra.DELETE),
                    contentDescription = "Forget the drive to ${titleCase(d.destination)}",
                    onClick = { onDelete(d) },
                    variant = VectorButtonVariant.Ghost,
                    size = VectorButtonSize.Small,
                    // Muted ink, not the Ghost variant's brand blue. A blue
                    // glyph beside a row you can tap to drive there again reads
                    // as the row's primary action; this one deletes.
                    contentTint = c.inkMuted,
                    testTag = "drive:delete",
                )
            }
        }
        // A rule between rows, as `RecentList` has. These rows are two lines at
        // label size, so without one the second line of a drive and the first
        // line of the next one sit closer together than the two lines of the
        // same drive — which makes the list read as the wrong pairs.
        // No rule between drives: each row now leads with a coloured family
        // tile, which gives the eye the left edge a divider was drawing.
    }
    if (drives.size > shown.size) {
        Text(
            "${drives.size - shown.size} older ${if (drives.size - shown.size == 1) "drive" else "drives"} kept",
            color = c.inkMuted,
            style = VectorTheme.typography.caption,
            modifier = Modifier.padding(top = VectorTokens.Space.s4),
        )
    }
}

/** How many history rows the settings sheet draws. See [DriveHistory]. */
internal const val DRIVE_ROWS = 8

/**
 * The second line of a history row: when, how far, how long, and how it ended.
 *
 * Separated from the composable so it can be asserted directly — the interesting
 * cases (an abandoned drive, a drive with no prediction to compare against, a
 * drive that crosses midnight) are string cases, and testing them through a
 * rendered row would test Compose rather than the rule.
 */
internal fun driveSummaryLine(
    d: Drives.Drive,
    units: dev.vector.geo.Units,
    nowMs: Long = System.currentTimeMillis(),
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String {
    val parts = mutableListOf(
        driveWhen(d.endedAtMs, nowMs, zone),
        units.shortDistance(d.distanceM),
        driveDuration(d.durationS),
    )
    // "Stopped" rather than "abandoned", which is a judgement, or "incomplete",
    // which sounds like Vector failed at something. The driver stopped; that is
    // all this says.
    if (!d.completed) parts.add("stopped")
    else d.verdict?.let { if (it != "as predicted") parts.add(it) }
    return parts.joinToString(" · ")
}

/**
 * When a drive happened, as a person would say it.
 *
 * "Today 14:32" / "Yesterday 09:10" / "8 Sep 14:32". Relative for the two days
 * a driver thinks of relatively and absolute after that — a log reading "3 days
 * ago" makes you do arithmetic to answer "was that the trip on the Tuesday".
 *
 * Compared by CALENDAR DAY, not by elapsed hours: a drive at 23:50 and a glance
 * at the log at 00:10 is "yesterday", and 20 minutes of elapsed time would call
 * it "today".
 */
internal fun driveWhen(
    atMs: Long,
    nowMs: Long,
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
): String {
    val then = java.util.Calendar.getInstance(zone).apply { timeInMillis = atMs }
    val now = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    val clock = String.format(
        "%02d:%02d",
        then.get(java.util.Calendar.HOUR_OF_DAY),
        then.get(java.util.Calendar.MINUTE),
    )
    val sameYear = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    val dayDiff = if (sameYear) {
        now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
    } else {
        // Only the sign matters past a year, and a drive from last year is
        // never "yesterday" — except across New Year, which the branch below
        // handles by falling through to the absolute form.
        Int.MAX_VALUE
    }
    return when (dayDiff) {
        0 -> "Today $clock"
        1 -> "Yesterday $clock"
        else -> {
            val month = java.text.DateFormatSymbols(java.util.Locale.getDefault())
                .shortMonths[then.get(java.util.Calendar.MONTH)]
            "${then.get(java.util.Calendar.DAY_OF_MONTH)} $month $clock"
        }
    }
}

/**
 * A drive's duration, compactly.
 *
 * Rounded to the nearest minute and never "0 min", for the same reasons
 * [UiState.etaLabel] is not — except that a drive really can be under a minute
 * (a wrong tap, started and arrived), and "under a minute" is the honest way to
 * say so rather than rounding it up to one.
 */
internal fun driveDuration(seconds: Double): String {
    if (seconds < 30.0) return "under a minute"
    val mins = Math.round(seconds / 60.0).toInt().coerceAtLeast(1)
    if (mins < 60) return "$mins min"
    val h = mins / 60
    val m = mins % 60
    return if (m == 0) "$h h" else "$h h $m min"
}

/**
 * A group heading in the settings sheet.
 *
 * §20 asks for "clear hierarchy" and logical grouping, and the sheet had
 * neither: eleven controls in a flat column, ordered by the release that added
 * them. Waze's answer is a two-level tree (a list of categories, each opening
 * its own screen) — `v4-evidence/waze/11-settings-list.png`. Vector
 * deliberately does **not** copy that. Waze needs a second level for roughly
 * forty settings; Vector has eleven, and a tree for eleven is a tap tax on
 * every one of them plus a navigation stack to maintain.
 *
 * What transfers is the cheap half: **a labelled group**. It costs one
 * composable, it needs no navigation, and it turns a pile into a document. The
 * groups themselves are §20's list, minus the ones Vector has nothing to put
 * in. What used to be a labelled *rule* is now a labelled *card* — see
 * [SettingsSection], which is what every group on this sheet is built from.
 */
/**
 * A settings group: an eyebrow, and a card holding the controls it names.
 *
 * ## Why the groups became cards
 *
 * The sheet was one flat white column with a hairline above each group label,
 * which is the stock Android preference screen — the before-shot is five ruled
 * bands of controls on one slab, and nothing on it says which control belongs to
 * which heading except proximity and a line.
 *
 * A card per group makes the grouping structural rather than typographic: the
 * controls for "Map" sit *inside* a surface that starts and stops, so the answer
 * to "what does this heading cover" is visible without reading. It also gives
 * the sheet the layered look the rest of the redesign uses — a floating sheet
 * with sunken wells on it — instead of a single plane with text at different
 * sizes.
 *
 * The card is [VectorTheme.colors.surface] on the sheet's `surfaceFloating`,
 * which is a *lighter* card on a light page: that is this palette's rule (see
 * `VectorColor.kt` — surfaces lift as they nest) and the reason the separation
 * survives without a border doing the work.
 */
@Composable
private fun SettingsSection(
    label: String,
    c: VectorColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = VectorTokens.Space.s20)) {
        Text(
            label.uppercase(),
            color = c.inkMuted,
            style = VectorTheme.typography.eyebrow,
            modifier = Modifier.padding(
                start = VectorTokens.Space.s4,
                bottom = VectorTokens.Space.s8,
            ),
        )
        VectorCard(
            modifier = Modifier.fillMaxWidth(),
            shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20),
            color = c.surface,
            elevation = VectorTheme.elevation.none,
            border = true,
            contentPadding = PaddingValues(
                horizontal = VectorTokens.Space.s16,
                vertical = VectorTokens.Space.s8,
            ),
            content = { Column(Modifier.fillMaxWidth(), content = content) },
        )
    }
}

@Composable
private fun SettingsGroup(label: String, c: VectorColors) {
    // No divider.
    //
    // A hairline above every group is the stock preference-screen look, and on
    // this sheet there were five of them — so the screen read as a ruled form.
    // Space and a typographic eyebrow separate the groups now, which is what
    // the type scale has an `eyebrow` role for; the groups are far enough apart
    // that a line between them would be describing a gap the eye has already
    // seen.
    Column(Modifier.fillMaxWidth().padding(top = VectorTokens.Space.s24)) {
        Text(
            label.uppercase(),
            color = c.inkMuted,
            style = VectorTheme.typography.eyebrow,
            modifier = Modifier.padding(bottom = VectorTokens.Space.s4),
        )
    }
}

/**
 * The four voice modes, as a radio list.
 *
 * Each row is `[glyph] [label] [·································] [✓]`, and
 * the glyph is doing real work: three waves / one wave / an exclamation / a
 * slash says *how much sound* before the label is read. Copied as a principle
 * from `v4-evidence/waze/13-voice-sound.png`, where the same four modes carry
 * the same four shapes — and drawn as Vector's own paths rather than as
 * Waze's artwork.
 *
 * A checkmark as well as a tint, so the selection is not colour-only.
 */
@Composable
private fun VoiceChoice(selected: VoiceMode, c: VectorColors, onPick: (VoiceMode) -> Unit) {
    Column(Modifier.padding(vertical = VectorTokens.Space.s8)) {
        Text("Spoken directions", color = c.inkMuted, style = VectorTheme.typography.caption)
        Spacer(Modifier.height(VectorTokens.Space.s4))
        for (mode in VoiceMode.entries) {
            val on = mode == selected
            with(VectorMotion) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = VectorTokens.Size.row)
                        .pressable { onPick(mode) }
                        .padding(vertical = VectorTokens.Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The glyph sits in a disc that FILLS when selected, so
                    // the state is a shape as well as a colour. It was a bare
                    // glyph that changed from grey to blue, which is a
                    // colour-only state — the thing the rest of this sheet is
                    // careful not to do.
                    Box(
                        Modifier
                            .size(VectorTokens.Size.control)
                            .clip(VectorTheme.shapes.pill)
                            .background(if (on) c.ink else c.surfaceSunken),
                        contentAlignment = Alignment.Center,
                    ) {
                        VectorIcons.Control(
                            kind = mode.glyph,
                            dim = VectorTokens.Size.controlGlyph,
                            color = if (on) c.inkInverse else c.inkMuted,
                        )
                    }
                    Spacer(Modifier.width(VectorTokens.Space.s12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            mode.label,
                            // Charcoal either way. The selected label was set in
                            // brand blue AND bold, so the one row a driver has
                            // chosen was the only blue text on the sheet and
                            // read as a link.
                            color = c.ink,
                            style = if (on) VectorTheme.typography.bodyStrong
                                    else VectorTheme.typography.body,
                        )
                        Text(
                            voiceModeDetail(mode),
                            color = c.inkMuted,
                            style = VectorTheme.typography.caption,
                        )
                    }
                    if (on) {
                        Spacer(Modifier.width(VectorTokens.Space.s8))
                        // The icon set's own check, not a "\u2713" character
                        // set in the body style. A text glyph inherits the
                        // font's metrics and optical weight, which is why it
                        // sat a pixel high and thin next to everything else.
                        VectorIcons.glyphIcon(VectorIcons.Extra.CHECK)
                            .render(20.dp, c.ink)
                    }
                }
            }
        }
    }
}

/**
 * One line saying what each mode actually does.
 *
 * Not decoration: "Brief" and "Alerts only" are indistinguishable from their
 * names, and a setting whose effect the driver has to discover by driving is a
 * setting they will leave alone.
 */
private fun voiceModeDetail(mode: VoiceMode): String = when (mode) {
    VoiceMode.FULL -> "Every turn, announced early and again at the junction"
    VoiceMode.BRIEF -> "Turns only as you reach them — no early warning"
    VoiceMode.ALERTS -> "Silent unless something changes: rerouting, off route, " +
        "arrival, a camera warning"
    VoiceMode.OFF -> "Nothing is spoken"
}

/**
 * A labelled row of mutually exclusive options.
 *
 * Built from [VectorChip], so the selected state is the SAME inversion the
 * filter row and every other selected control in the product uses: a filled
 * brand pill with light text.
 *
 * It used to be its own thing — a brand-tinted fill, a brand border, brand
 * text and a heavier weight — which is Material's "selected filter chip" look
 * and was perfectly legible, but it was a *second* selection language in an app
 * that already had one. Two ways to say "this one is chosen" is how a design
 * system quietly stops being a system, and it is the kind of drift that only
 * shows up when someone puts two screens side by side.
 */
@Composable
private fun <T> Choice(
    label: String,
    c: VectorColors,
    options: List<Pair<String, T>>,
    selected: T,
    onPick: (T) -> Unit,
) {
    Column(Modifier.padding(vertical = VectorTokens.Space.s8)) {
        Text(label, color = c.inkMuted, style = VectorTheme.typography.metadata)
        Spacer(Modifier.height(VectorTokens.Space.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8)) {
            for ((text, value) in options) {
                VectorChip(
                    label = text,
                    selected = value == selected,
                    onClick = { onPick(value) },
                    testTag = "choice:$label:$text",
                )
            }
        }
    }
}

@Composable
private fun Toggle(label: String, c: VectorColors, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = VectorTokens.Size.row)
            .vectorPressable(pressScale = 0.99f) { onChange(!on) }
            .semantics {
                role = Role.Switch
                stateDescription = if (on) "On" else "Off"
            }
            .padding(vertical = VectorTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = c.ink,
            style = VectorTheme.typography.body,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(VectorTokens.Space.s12))
        // Vector's own switch, not Material's with two colours overridden. See
        // [dev.vector.android.design.VectorToggle] for what is actually
        // different and why overriding M3's colours was never going to be
        // enough. It takes no click of its own: the ROW is the 48 dp target and
        // carries `Role.Switch` and the state description above.
        VectorToggle(checked = on)
    }
}

/**
 * The paywall.
 *
 * ## Why this is hand-built rather than RevenueCat's drop-in
 *
 * `purchases-ui` renders a paywall authored in the RevenueCat dashboard. It
 * would be the one screen in Vector that does not come from `Chrome`,
 * `VectorTokens` and `VectorIcons` — a different type scale, a different
 * accent, different corner radii — appearing at the exact moment the driver is
 * deciding whether to trust the product with money. Two hundred lines of
 * Compose is cheaper than that, and cheaper than the artifact weight.
 *
 * ## What it is allowed to claim
 *
 * Exactly [dev.vector.android.pro.ProCatalogue.features], which is empty until
 * a feature actually ships. There is no hardcoded list here and no "coming
 * soon" row.
 *
 * ## What it is allowed to *do* when there is nothing to sell
 *
 * The sheet can be reached while the store has nothing in it — deliberately,
 * from Settings, which is a request to look rather than an interruption. In
 * that state it must not sit on "Checking prices…" forever, because a payment
 * screen that never resolves reads as a broken payment. It says what happened
 * and offers [onRetry], which re-reads the offering. See `ProOffering` for why
 * "still asking" and "answered with nothing" are different states.
 *
 * Prices come from the store, formatted by the store, in the store's currency.
 * Vector never writes a number here.
 */
@Composable
private fun PaywallSheet(
    ui: UiState,
    c: VectorColors,
    onBuy: (com.revenuecat.purchases.Package) -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    onDismissNotice: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable { onClose() },
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val sheetMax = maxHeight * 0.92f
        Surface(
            color = c.surface,
            // The same continuous sheet silhouette as the settings sheet and
            // the discovery sheet. This was the last surface in the app still
            // drawn with the stock two-rounded-corners rectangle.
            shape = VectorTheme.shapes.sheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = VectorTokens.Space.s20)
                    .padding(top = VectorTokens.Space.s16, bottom = VectorTokens.Size.bottomGap)
                    .heightIn(max = sheetMax)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Vector Pro", color = c.ink, style = VectorTheme.typography.screenTitle,
                         fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    VectorButton(
                        text = "Not now",
                        onClick = onClose,
                        variant = VectorButtonVariant.Ghost,
                        size = VectorButtonSize.Small,
                        contentTint = c.inkSecondary,
                        testTag = "pro:not-now",
                    )
                }
                Text(
                    "Vector gets you there. Pro gets you from the car to the door.",
                    color = c.inkMuted, style = VectorTheme.typography.metadata,
                    modifier = Modifier.padding(bottom = 14.dp),
                )

                for (f in ui.proFeatures) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text("—", color = c.primary, style = VectorTheme.typography.body,
                             fontWeight = FontWeight.Bold,
                             modifier = Modifier.padding(end = 10.dp))
                        Column {
                            Text(f.title, color = c.ink, style = VectorTheme.typography.bodyStrong,
                                 fontWeight = FontWeight.Bold)
                            Text(f.detail, color = c.inkMuted,
                                 style = VectorTheme.typography.metadata)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                val offer = ui.proOffer
                when {
                    // The store answered, and there is nothing in the answer.
                    //
                    // This is the branch that used to be a lie by omission: an
                    // offering that failed to load left `offer` null, which is
                    // also its value a half-second after launch, so the sheet
                    // sat on "Checking prices…" indefinitely. A driver cannot
                    // tell that from a hung app, and the correct conclusion
                    // from a payment screen that never resolves is that the
                    // payment is broken.
                    //
                    // So it says what happened and offers the one useful
                    // action. `onOpenPaywall` is reused as the retry because
                    // that is already what it does — it re-reads the offering
                    // and re-opens this sheet — and a second callback that only
                    // ever has this one caller would be a second thing to keep
                    // correct.
                    ui.proOffering == dev.vector.android.pro.ProOffering.UNAVAILABLE -> {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            Text(
                                dev.vector.android.pro.ProStoreCopy
                                    .unavailable(ui.proStore),
                                color = c.ink, style = VectorTheme.typography.metadata,
                            )
                            Text(
                                "Nothing was charged. Your navigation is unaffected " +
                                    "— this is the paid tier, and the walk still " +
                                    "works without it.",
                                color = c.inkMuted, style = VectorTheme.typography.caption,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        VectorButton(
                            text = "Try again",
                            onClick = onRetry,
                            enabled = !ui.proBusy,
                            variant = VectorButtonVariant.Ghost,
                            size = VectorButtonSize.Small,
                            modifier = Modifier.padding(bottom = VectorTokens.Space.s4),
                            testTag = "pro:retry",
                        )
                    }
                    // The store has not answered yet. Say so rather than
                    // drawing a dead button — a purchase control that does
                    // nothing when tapped is how a driver decides the payment
                    // is broken and never tries again.
                    offer == null -> Text(
                        dev.vector.android.pro.ProStoreCopy
                            .checkingPrices(ui.proStore),
                        color = c.inkMuted, style = VectorTheme.typography.metadata,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    offer.isEmpty -> Text(
                        "Vector Pro is not available on this account yet.",
                        color = c.inkMuted, style = VectorTheme.typography.metadata,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    else -> for (pkg in offer.packages) {
                        // The store's own formatting, in the store's own
                        // currency. Vector has no business rendering a price it
                        // computed itself.
                        //
                        // `VectorButton`, and `loading` rather than merely
                        // `enabled = false` while a purchase is in flight: the
                        // old button greyed out with no indication that anything
                        // was happening, which on a payment is the worst moment
                        // in the product to look inert.
                        VectorButton(
                            text = "${packageCadence(pkg)} · ${pkg.product.price.formatted}",
                            onClick = { onBuy(pkg) },
                            enabled = !ui.proBusy,
                            loading = ui.proBusy,
                            variant = VectorButtonVariant.Primary,
                            modifier = Modifier.padding(vertical = VectorTokens.Space.s4),
                            testTag = "pro:buy",
                        )
                    }
                }

                ui.proNotice?.let { notice ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(notice, color = c.dangerText,
                             style = VectorTheme.typography.metadata,
                             modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(VectorTokens.Space.s8))
                        VectorButton(
                            text = "OK",
                            onClick = onDismissNotice,
                            variant = VectorButtonVariant.Ghost,
                            size = VectorButtonSize.Small,
                            fillWidth = false,
                            testTag = "pro:notice-ok",
                        )
                    }
                }

                VectorButton(
                    text = "Already subscribed? Restore",
                    onClick = onRestore,
                    enabled = !ui.proBusy,
                    variant = VectorButtonVariant.Ghost,
                    size = VectorButtonSize.Small,
                    testTag = "pro:restore3",
                )
                Text(
                    // Named for the store the key actually selected, not for
                    // the one every shipping build will use. See ProStore.kt.
                    dev.vector.android.pro.ProStoreCopy.billing(ui.proStore),
                    color = c.inkMuted, style = VectorTheme.typography.caption,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * How often a package bills, as a driver says it.
 *
 * Falls back to the package's own identifier rather than to a guess: an
 * offering built in the dashboard with custom package types is still
 * purchasable, and printing its identifier is honest where printing "Monthly"
 * would be wrong.
 */
internal fun packageCadence(pkg: com.revenuecat.purchases.Package): String =
    when (pkg.packageType) {
        com.revenuecat.purchases.PackageType.MONTHLY -> "Monthly"
        com.revenuecat.purchases.PackageType.ANNUAL -> "Yearly"
        com.revenuecat.purchases.PackageType.LIFETIME -> "Lifetime"
        com.revenuecat.purchases.PackageType.WEEKLY -> "Weekly"
        else -> pkg.identifier
    }
