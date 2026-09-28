package dev.vector.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import dev.vector.geo.LngLat
import dev.vector.geo.LaneGuidance
import dev.vector.geo.ManeuverAnnouncer
import dev.vector.geo.ManeuverCamera
import dev.vector.geo.ProbeBuffer
import dev.vector.geo.RouteTracker
import dev.vector.geo.SpeedPillLatch
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * How far one press of a zoom control moves the camera.
 *
 * One whole zoom level, and it is not a taste decision — it is a contract with
 * [CameraGate].
 *
 * A zoom button is a DISCRETE TRANSITION, so it goes through
 * [MainActivity.applyCamera] like every other one. But unlike every other one
 * it must not be recorded as a zoom VECTOR asserted, because it is not: the
 * driver asserted it, and the gate's whole job is to tell those apart. A press
 * therefore leaves `CameraGate.assertedZoom` pointing at the last value Vector
 * chose and moves the live camera away from it — which is structurally
 * identical to a pinch, and is read as one by [CameraGate.isDriverZoom].
 *
 * That only works if the move is big enough to be seen as one. `isDriverZoom`
 * asks whether the live zoom has departed the asserted zoom by more than
 * [MapCamera.DRIVER_ZOOM_TOLERANCE], which is 0.8 — so a step of 0.5 would
 * land INSIDE the band the gate still treats as Vector's, and the next GPS fix
 * would let the auto-zoom band or the maneuver camera put the zoom straight
 * back. The button would appear to do nothing, intermittently, and only while
 * moving. This must stay above that tolerance; `NavUiTest` asserts it.
 *
 * One level is also the right amount to feel. It halves or doubles the ground
 * covered, which is a change the driver can see happened without having to
 * compare the before and after.
 */
internal const val ZOOM_BUTTON_STEP = 1.0

/**
 * Vector native client (ADR-0075).
 *
 * No WebView, no HTML, no JS bridge. MapLibre Native renders the same MVT tiles
 * the web client uses straight onto a GPU surface, and the vehicle position is
 * advanced by [RouteTracker] on the display's frame callback rather than
 * animated toward 1 Hz GPS fixes.
 *
 * This class is the ADAPTER and nothing else. The decisions live in pure,
 * JVM-testable code — [NavSession] (when to reroute, speak, arrive),
 * [MapCamera] (where the camera goes), [Settings] (what the driver chose) — so
 * that the riskiest logic in a navigator does not require a device to verify.
 */
class MainActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private var maplibre: MapLibreMap? = null
    /**
     * A style source, looked up fresh every time. Never cached.
     *
     * ## The crash this exists because of
     *
     * These were six `var GeoJsonSource?` fields, assigned in the `setStyle`
     * callback and written to from ten places. On the S24, in
     * `scenario-d-wrong-road`:
     *
     * ```
     * FATAL EXCEPTION: main
     * java.lang.IllegalStateException: invalid native peer
     *   at GeoJsonSource.nativeSetGeoJsonString(Native Method)
     *   at MainActivity.drawPuck(...)
     *   at MainActivity.perform(...)
     * ```
     *
     * A `GeoJsonSource` is a thin Kotlin object over a native peer owned by the
     * **style**. Replace or destroy the style and every peer goes with it,
     * while the Kotlin references stay perfectly non-null — so `?.setGeoJson`
     * is not null-safe in the way it looks, and the JNI call throws.
     *
     * There are at least three windows where that happens: shutdown
     * (`onDestroy` destroys the map, and the Choreographer loop was still
     * running — see [mapAlive]); a theme change, because `setStyle` is
     * asynchronous and drops the old style *now* while re-acquiring handles
     * only in its callback; and launch, which is where the crash above
     * actually landed — one millisecond after the first fix, with the drive not
     * yet started.
     *
     * Patching the windows one at a time was the first attempt and it is the
     * wrong shape: the bug is not any particular window, it is **caching a
     * handle whose lifetime is shorter than the field holding it**. So there is
     * no cache. `getSourceAs` is a lookup against the style that exists right
     * now, which is either valid or null, and `isFullyLoaded` excludes the one
     * in-between state. Every draw site keeps its `?.` and each becomes a
     * genuine no-op instead of a crash.
     *
     * Cost: a string lookup per call rather than a field read. `drawPuck` runs
     * at 120 Hz, and the JNI `setGeoJson` it guards already dominates that by
     * orders of magnitude.
     */
    private fun source(id: String): GeoJsonSource? {
        if (!mapAlive) return null
        val style = maplibre?.style ?: return null
        if (!style.isFullyLoaded) return null
        return runCatching { style.getSourceAs<GeoJsonSource>(id) }.getOrNull()
    }

    private val api = VectorApi()
    private val tracker = RouteTracker()
    private val announcer = ManeuverAnnouncer()
    private val probes = ProbeBuffer()

    /**
     * Vector Pro.
     *
     * `by lazy` rather than a constructor field because it touches
     * SharedPreferences, which needs a Context that exists. It is safe to
     * construct before [onCreate] finishes and safe to never touch at all —
     * a build with no RevenueCat key never calls anything on it that reaches
     * the network. See `pro/ProEntitlement.kt`.
     */
    private val pro by lazy { dev.vector.android.pro.ProEntitlement(this) }

    /**
     * How smoothly the display is actually presenting.
     *
     * The answer to V3's release blocker 2: `dumpsys gfxinfo` cannot see a
     * `SurfaceView`, so the 120 Hz premise ADR-0075 was justified by has never
     * been checked. The frame callback below already fires once per display
     * frame with its presentation timestamp — the measurement was already in
     * hand and was being thrown away. See [dev.vector.geo.FrameMeter].
     */
    private val frames = dev.vector.geo.FrameMeter()
    private var lastFrameReportAtMs = 0L
    // The nav loop's decisions live in NavSession so they can be tested without
    // a device (app/src/test/.../NavSessionTest.kt). This Activity is the
    // adapter: it supplies fixes and performs the Actions that come back.
    private val session by lazy { NavSession(tracker, announcer) }

    /**
     * The walking navigation loop, or null when this journey is a drive
     * (V7.4 4C final).
     *
     * **Null is the car case, and that is the whole isolation mechanism.**
     * Every walking branch in this class tests this field, so a drive takes
     * exactly the path it took before V7.4 — the walking session is not
     * constructed, not ticked, and not reachable. It is built by [beginWalk]
     * and destroyed by [endJourney].
     *
     * A separate object rather than a mode flag on [session], for the reason
     * [WalkNavSession] gives: the two loops disagree about speed bands,
     * heading gates, reroute cooldowns, lane offsets and speed alerts, and
     * folding them together would mean a conditional at every one of those.
     */
    private var walkSession: WalkNavSession? = null

    /** The walk being planned. Cancelled by the next destination or mode. */
    private var walkJob: kotlinx.coroutines.Job? = null

    /** The walking reroute in flight, if any. */
    private var walkRerouteJob: kotlinx.coroutines.Job? = null

    private lateinit var voice: VoiceGuide
    private lateinit var fused: FusedLocationProviderClient

    /**
     * The replayed drive, in debug builds only. See [MockDrive].
     *
     * Nullable rather than always constructed: it holds a `Handler` and a
     * reference to the fused client, and a release build has no business
     * carrying either for a feature it cannot use.
     */
    private var mock: MockDrive? = null

    /** Where a replayed drive should navigate to, from the launch intent. */
    /**
     * Start driving as soon as the next route resolves.
     *
     * Set only by a [NavBridge.Command.NavigateTo] from Android Auto. The car
     * has no preview screen and no Start button — a template list row is the
     * whole gesture — so the PREVIEW phase the phone shows would strand the
     * driver on a screen with nothing to tap. See showRoute.
     */
    private var carAutoStart = false
    private var mockDest: LngLat? = null
    private var mockSpeedup: Double = 1.0
    private lateinit var prefs: SharedPreferences

    /**
     * The whole UI state, and the one place every change to it passes through.
     *
     * A custom setter rather than `by mutableStateOf`, so that [logNav] cannot
     * be forgotten. It was: the transitions were logged from the two places
     * that assign `ui` inside the navigation loop, and `startNavigation`,
     * `showRoute` and `cancelRoute` assign it too — so a device run showed nine
     * maneuvers, a deviation and a reroute while reporting that navigation had
     * never been entered. The log is the device harness's only evidence, and a
     * log with holes in it is worse than none because the holes look like
     * findings.
     *
     * Compose still recomposes on read: the getter reads the `MutableState`'s
     * value, which is what registers the dependency.
     */
    private val uiState = mutableStateOf(UiState())
    private var ui: UiState
        get() = uiState.value
        set(value) {
            val before = uiState.value
            uiState.value = value
            logNav(before, value)
            // The car's copy of the truth, published from the same one place
            // every other observer reads. See NavBridge: Android Auto renders
            // this exact object, so it cannot drift from the phone HUD.
            NavBridge.publish(value)
            // The system bar icons depend on the phase, not only on the theme —
            // see statusBarWantsDarkIcons. Applied here rather than at each
            // transition so a new phase cannot be added without it.
            if (before.phase != value.phase || before.settings.theme != value.settings.theme) {
                applySystemBarAppearance(value.settings.resolvedTheme(systemInDark()), value.phase)
            }
            // Holding the display awake is a property of the phase too, and for
            // the same reason it is applied here: the setter is the one place
            // every transition passes through, so a new phase cannot be added
            // that forgets to release the flag. See screenShouldStayOn.
            if (before.phase != value.phase) {
                applyKeepScreenOn(value.phase)
            }
        }
    private var lastFix: LngLat? = null
    private var lastBearing = 0.0
    private var lastSpeedProbeAtMs = 0L
    private var lastTrafficAtMs = 0L
    private var lastSpeedProbeAt: LngLat? = null

    /**
     * The camera bearing actually in use, as opposed to the one measured.
     *
     * Carried between frames so [bearingForFrame] has something to smooth
     * from. See MapCamera.smoothBearing for why this exists at all.
     */
    private var smoothedBearing = 0.0
    private var lastBearingAtNanos = 0L

    /**
     * Whether extruded buildings are currently part of the style (V7 3D).
     *
     * Delegates to [MapCamera.extrudesBuildings], which keys off the driver's
     * stated 2D/3D preference — NOT off the transient pitch. See there for why
     * that distinction is load-bearing: the style is built at launch while the
     * driver is in EXPLORE, where nothing is tilted, so a pitch-derived answer
     * was always false and the layer was never emitted at all.
     *
     * On foot it is always false: [MapCamera.tiltFor] returns 0 on a walk and
     * `WalkCameraPolicy` pins `WALK_TILT_DEG`, so extruding volumes into a flat
     * view is exactly the "3D for the sake of looking impressive" this stage's
     * contract forbids.
     */
    private val extrudedBuildings: Boolean
        get() = MapCamera.extrudesBuildings(ui.cam, onFoot = walkSession != null)

    /** The top padding currently on the map. See [applyLookAhead]. */
    private var appliedTopPadding = -1.0

    /**
     * The auto-zoom band currently applied, or -1 for none.
     *
     * A band index rather than a zoom value, because the hysteresis that stops
     * the map breathing in and out at a band boundary has to be applied to the
     * SPEED — see [MapCamera.BAND_HYSTERESIS_KMH], and the first version of
     * this code, which compared zoom values and flapped.
     */
    private var autoZoomBand = -1
    /**
     * The per-maneuver stage machine (V7 Stage 2).
     *
     * Pure policy in core-geo, fed facts on GPS fixes and applied as discrete
     * eased transitions — see [maybeManeuverCamera]. Reset on route replace,
     * arrival and reroute, exactly like [announcer].
     */
    private val maneuverCamera = ManeuverCamera()

    // ---- V7 Stage 3 device-validation instrumentation ---------------------
    //
    // Debug-only, write-only telemetry: nothing here is read by any policy, so
    // the drive it measures is the drive the driver gets. It exists because the
    // Stage 3 acceptance asks for measured camera values (zoom, pitch, stage,
    // who wrote) at checkpoints, and none of those are otherwise observable
    // from outside the process. Remove with the Stage 3 report.
    private var camWritesManeuver = 0
    private var camWritesAutoZoom = 0
    private var camGateBlocked = 0
    private var camDriverZoomFixes = 0
    private var camLastDecision = "-"

    /**
     * How many features the `route` source was last given (V7 Stage 4).
     *
     * Debug telemetry, counted where the source is written rather than read
     * back out of MapLibre — there is no API to ask a GeoJSON source how many
     * features it holds, and a count taken here is the count that was handed
     * over. One means the model declined and the ribbon is a single unadorned
     * LineString; more means the offset varies along the route and it had to
     * be cut, because `line-offset` is evaluated once per feature.
     */
    private var camRouteFeatures = 0

    /**
     * Keeps the follow loop from cancelling a deliberate camera move.
     *
     * See [CameraGate]. Without it the first followed frame after a transition
     * begins destroys it, which is why the nav-start flight and auto-zoom both
     * did nothing at all while the vehicle was moving.
     */
    private val cameraGate = CameraGate()

    /**
     * When the current drive began, for the arrival summary.
     *
     * Wall clock, and measured from the moment the driver tapped Start rather
     * than from the first fix: what they want to know at the end is how long
     * the journey took them, which includes the seconds spent pulling out.
     */
    private var navStartedAtMs: Long? = null

    /**
     * Where the current drive began, for the journey history.
     *
     * Captured at [startNavigation] because by the time the drive ends the
     * driver is somewhere else, and "where did I set off from" cannot be
     * recovered afterwards. Nullable because navigation can be started before
     * the first fix lands — see [Drives.Drive.startLng], which represents that
     * as unavailable rather than as (0, 0).
     */
    private var navStartedAt: LngLat? = null

    /**
     * For the two deferred jobs that must not depend on the map existing.
     *
     * Both [resumeJourneyWhenReady] and [mockRouteWhenReady] poll for a fix,
     * and both used `mapView.postDelayed`. They are started from `onCreate`
     * — BEFORE `mapView` is assigned — so the first launch with a saved
     * journey died on `lateinit property mapView has not been initialized`,
     * which is precisely the process-death path the saved journey exists to
     * survive. Caught by a device run, on the build that introduced it.
     *
     * A plain main-looper handler has no ordering dependency to get wrong.
     */
    private val main = Handler(Looper.getMainLooper())

    /** The in-flight (or pending) search, so a new keystroke can cancel it. */
    private var searchJob: kotlinx.coroutines.Job? = null

    /**
     * The in-flight route request, so a second destination can cancel the first.
     *
     * ## The race
     *
     * `pickPoint` launched an unguarded coroutine and kept no handle on it, so
     * two destinations chosen in quick succession — a mis-tap on a results list
     * and an immediate correction, which is the ordinary way to use a search
     * box — put two `navigateAlternatives` calls in flight with no ordering
     * between them. The router answers in whatever order it answers, so the
     * FIRST destination's routes could land last and win: the map drew A's
     * route, the alternatives list held A's options, and the destination chip,
     * the recents entry and the Home/Work save all still said B.
     *
     * Every other async reply in this class already guards against exactly
     * this. `doSearch` drops a reply whose query has moved on; `resolveName`
     * drops a name whose destination has moved on; `arrive`'s parking query
     * checks the arrival it belongs to is still the one on screen. The route
     * request — the most consequential of the four — was the one that did not,
     * and the omission looks like an oversight rather than a decision.
     */
    private var routeJob: kotlinx.coroutines.Job? = null

    /** The walk half of the answer. Cancelled by the next destination. */
    private var journeyJob: kotlinx.coroutines.Job? = null

    /**
     * The zoom range the running tile set contains, and the theme it is drawn
     * with. Held because switching theme means rebuilding the style document,
     * and the document needs both.
     *
     * V7.7: it also carries the RELEASE, which is what `?v=` is built from.
     * Assigned in exactly two places — the cold-start read, and
     * [adoptRelease] — so that "which release is this style built from" has
     * one answer and one writer.
     */
    private var tileSet = VectorApi.TileSet(0L, 11, 13)
    private var appliedTheme: VectorStyle.MapTheme? = null

    /**
     * Whether the tile release the app is bound to is still the active one.
     *
     * The app used to ask once, at `onCreate`, and never again — AC-19's D1,
     * and the reason a rollback was invisible in-session. It now asks on
     * resume, and [ReleaseWatch] decides whether the answer may be acted on.
     *
     * The decision lives in that class rather than here on purpose: applying a
     * release rebuilds the style, and a style rebuild during guidance is the
     * documented `invalid native peer` window ([applyStyle]). Keeping the rule
     * in one testable object is what makes "no restyle while navigating" a
     * property that can be enumerated instead of a convention to be re-checked
     * at every call site.
     */
    private val releaseWatch = ReleaseWatch()

    /** In flight, so a second resume cancels nothing and duplicates nothing. */
    private var releaseJob: kotlinx.coroutines.Job? = null

    /**
     * False once the map's native objects have been destroyed.
     *
     * ## The crash this exists because of
     *
     * `scenario-d-wrong-road` on the S24, in the V6 batch:
     *
     * ```
     * FATAL EXCEPTION: main
     * java.lang.IllegalStateException: invalid native peer
     *   at GeoJsonSource.nativeSetGeoJsonString(Native Method)
     *   at MainActivity.drawPuck(MainActivity.kt:2005)
     *   at MainActivity.perform(MainActivity.kt:1559)
     * ```
     *
     * `startFrameLoop` posts a Choreographer callback that re-posts itself and
     * **was never cancelled**. `onDestroy` calls `mapView.onDestroy()`, which
     * destroys the style and every `GeoJsonSource` on it — and then the next
     * display frame, up to 8 ms later, called `drawPuck` and wrote to one of
     * them. A `?.` does not help: the Kotlin object is still there, it is the
     * NATIVE PEER behind it that is gone, so the only null-safe call is one
     * that does not happen.
     *
     * Two independent guards, because a crash on the way out of a navigation
     * app is worth belt and braces: this flag stops the loop, and `onDestroy`
     * also drops the source references so that any *other* path which draws
     * during teardown — an async reply landing mid-destroy, which is the same
     * class of defect as the reroute race in `rerouteReplyStillWanted` —
     * becomes a no-op rather than a crash.
     */
    private var mapAlive = true

    /** The traffic GeoJSON currently on the map, so a restyle can redraw it. */
    private var lastTrafficJson: String? = null
    private var lastRouteCoords: List<LngLat> = emptyList()
    /** The signals of the route currently on the map (V7 Stage 5), for restyles. */
    private var lastRouteSignals: List<VectorApi.Signal> = emptyList()
    /**
     * The matched signal profile of the route currently on the map, so the
     * callout layer can hang a pill on each signal without rebuilding it —
     * and so the map and the loop describe the same junctions.
     */
    private var lastSignalProfile: dev.vector.geo.signal.SignalProfile? = null

    /**
     * The surveyed signals standing on the CURRENT walk's crossings (V7 traffic
     * lights), so the map can mark them without rebuilding the profile.
     *
     * Separate from [lastSignalProfile] because the two describe different
     * routes in different spaces: the driving profile is a per-route projection
     * over a car graph, the walking one is a set of graph-identity attachments
     * read straight off the walk's own plan. A walk tears the driving profile
     * down, and vice versa.
     */
    private var lastWalkSignalProfile: dev.vector.geo.signal.SignalProfile? = null

    /**
     * How many signal pills are in the callout source right now (V7 Stage 5).
     *
     * Read by the device telemetry, which must be able to say "the pill was
     * drawn" and not just "the model matched a signal" — a pill that never
     * survives setGeoJson is a rendering problem, and the two have different
     * fixes (the exact distinction Stage 4's `feats` field drew for the
     * ribbon).
     */
    private var lastSignalPills = 0

    /** The cameras of the route currently on the map (V7.3), for restyles. */
    private var lastRouteCameras: List<VectorApi.Camera> = emptyList()

    /**
     * The matched camera profile of the route currently on the map, so the
     * callout layer can hang a pill on each camera and the HUD tick can warn
     * — the map and the loop describe the same locations.
     */
    private var lastCameraProfile: dev.vector.geo.camera.CameraProfile? = null

    /**
     * How many camera pills are in the callout source right now (V7.3).
     *
     * Same telemetry contract as the signals: the device run must be able to
     * say "the pill was drawn", not just "the model matched a camera".
     */
    private var lastCameraPills = 0

    /**
     * How many camera voice lines this journey has spoken (V7.3, debug).
     *
     * The device run reads it to prove the camera warning actually reached
     * the voice path — the unit suite pins that it fires once per camera, but
     * a silent emulator TTS would otherwise hide whether the action fired.
     */
    private var cameraVoices = 0

    /**
     * The look-ahead speed-limit pill, latched to the maneuver it was found
     * for.
     *
     * Kept so the callout source can be rebuilt without re-asking, and so the
     * pill is decided once per maneuver rather than re-decided against the live
     * speed-limit baseline as the car moves. See [SpeedPillLatch] for the
     * flicker that rule closes.
     */
    private val speedPill = SpeedPillLatch()

    /** Where the warning window last started, so it is only rebuilt when it moves. */
    private var lastCalloutWindowFromM = -1e9

    /**
     * The positions this journey has actually passed through.
     *
     * Filled only while NAVIGATING, cleared when the journey is recorded, and
     * written into `Drives.Drive.track` so the history can draw the road that
     * was driven rather than the road that was suggested.
     */
    private val driveTrack = ArrayList<dev.vector.geo.LngLat>(Drives.TRACK_MAX_POINTS)

    /**
     * Keep this fix if the car has moved far enough since the last one kept.
     *
     * ## Sampling by distance, not by time
     *
     * See `Drives.TRACK_MIN_SPACING_M`. A time sample spends the whole budget
     * on a queue at a junction and misses the curve that follows it.
     *
     * ## What happens when the budget runs out
     *
     * The list is halved in place — every second point dropped — and the
     * spacing requirement doubles from then on. So a 40 km journey is recorded
     * at 100 m spacing and a 5 km one at 25 m, and in both cases the whole
     * drive is present. Truncating at the cap instead would have stored the
     * first ten kilometres of every long journey and none of the rest, which is
     * the shape of a drive that did not happen.
     */
    private var trackSpacingM = Drives.TRACK_MIN_SPACING_M

    private fun appendTrackPoint(here: dev.vector.geo.LngLat) {
        if (ui.phase != Phase.NAVIGATING) return
        val last = driveTrack.lastOrNull()
        if (last != null) {
            val moved = dev.vector.geo.RouteGeometry
                .haversineM(last.lng, last.lat, here.lng, here.lat)
            if (moved < trackSpacingM) return
        }
        driveTrack.add(here)
        if (driveTrack.size >= Drives.TRACK_MAX_POINTS) {
            val halved = driveTrack.filterIndexed { i, _ -> i % 2 == 0 }
            driveTrack.clear()
            driveTrack.addAll(halved)
            trackSpacingM *= 2.0
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) startLocation()
            // No error banner here any more.
            //
            // A denied permission produces `GpsHealth.UNAVAILABLE`, and the GPS
            // banner already owns that story completely: it names the state,
            // explains the consequence in a sentence, and carries the control
            // that fixes it. Setting an error as well drew a SECOND red bar
            // directly above it saying the same thing in fewer words —
            // "Location permission denied — Vector cannot navigate" stacked on
            // "No position available / Location is switched off for this phone"
            // — which is the same fact twice, the top one unactionable.
            //
            // One condition, one statement, in the place that can do something
            // about it. See [GpsWarning].
            else ui = ui.copy(gps = GpsHealth.UNAVAILABLE)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: the splash must be installed while the window is
        // still the launch theme, or the hand-off to `Theme.Vector` happens
        // without anything having drawn the mark. It stays up only until the
        // first composition, so a cold start is: mark on Vector's field, then the
        // app — never a bare platform window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The display cutout mode, set here rather than in the theme.
        //
        // `android:windowLayoutInDisplayCutoutMode` is API 27 and this app's
        // minSdk is 26, so putting it in `themes.xml` fails lint's NewApi check —
        // and a `values-v27` copy of the whole style would mean every other item
        // in it existed twice. Guarding it here keeps one definition and states
        // the intent where the rest of the window configuration is.
        //
        // SHORT_EDGES lets the map draw into the cutout area in landscape, which
        // is what a navigation app wants: a letterboxed map is a smaller map.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        MapLibre.getInstance(this)   // no API key: every tile and glyph is self-hosted
        fused = LocationServices.getFusedLocationProviderClient(this)
        voice = VoiceGuide(this)

        // Preferences are read BEFORE the first fix and before the first frame:
        // consent must be known before any point could be buffered (adr-0068),
        // and the camera and theme must be right on the first draw rather than
        // snapping a moment later.
        prefs = Settings.prefs(this)
        val settings = Settings.load(prefs)
        voice.enabled = settings.voice.speaksAlerts
        announcer.units = settings.units
        ui = ui.copy(
            settings = settings,
            cam = CameraState(
                orientation = settings.orientation,
                perspective = settings.perspective,
            ),
            recents = Recents.load(prefs),
            places = Places.load(prefs),
            drives = Drives.load(prefs),
        )

        // Before anything asks for a position: if a previous replay left this
        // device's fused provider stuck in mock mode, let it go. A force-stop
        // runs no lifecycle callbacks, so `MockDrive.stop()` does not get to —
        // and the provider then produces nothing for ANY app on the handset.
        // See MockDrive.releaseStaleMockMode.
        //
        // NOT when this launch is itself a replay. `setMockMode` is
        // asynchronous, so releasing and then immediately claiming would put
        // two Tasks in flight with no ordering between them, and a `false`
        // landing after the `true` would silently un-mock the provider under a
        // running trace. That is the same defect V5 found in `MockDrive.start`
        // — a `setMockMode` that was fired and not awaited, which cost a whole
        // device run — and it would be careless to reintroduce it here while
        // fixing its sibling.
        if (intent?.getStringExtra("vectorTrace") == null) {
            MockDrive.releaseStaleMockMode(fused)
        }

        readMockIntent(intent)
        // A journey the process died in the middle of. Restored once there is a
        // position to route from — see resumeJourneyWhenReady.
        Journey.load(prefs, System.currentTimeMillis())?.let { resumeJourneyWhenReady(it) }

        // The paid tier, before the first frame.
        //
        // `pro.state` is already seeded from the preference store by the time
        // this returns, so a subscriber never sees a free screen flash while
        // RevenueCat is asked. The network confirmation follows below and
        // corrects it if the subscription has actually lapsed.
        pro.start()
        ui = ui.copy(
            pro = pro.state.value,
            proStore = pro.store.value,
            proOffering = pro.offering.value,
        )
        lifecycleScope.launch {
            pro.state.collect { status ->
                val was = ui.pro
                ui = ui.copy(pro = status)
                // A tier that just went away while the paywall is open would
                // leave the driver staring at a purchase they already made.
                if (status == dev.vector.android.pro.ProStatus.PRO &&
                    was != dev.vector.android.pro.ProStatus.PRO) {
                    ui = ui.copy(showPaywall = false, proNotice = null)
                }
            }
        }
        lifecycleScope.launch { pro.offer.collect { ui = ui.copy(proOffer = it) } }
        // Whether there is anything to sell, tracked separately from what it
        // costs. An offering that fails to load leaves `proOffer` null, which is
        // also its value before the first answer — this is what tells the two
        // apart, so the paywall can say something true instead of "Checking
        // prices…" forever. See `pro/ProAccess.kt`'s `ProOffering`.
        lifecycleScope.launch {
            pro.offering.collect { ui = ui.copy(proOffering = it) }
        }
        lifecycleScope.launch {
            pro.refresh()
            // Prices only matter if there is something to sell. Skipped in a
            // build with nothing gated, which is one fewer network call on
            // every launch of a self-hosted Vector.
            if (ui.offersPro) pro.loadOffer()
        }

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)

        // A recreation must not close the driver's search box.
        //
        // The activity declares `configChanges` for every configuration change
        // that happens while driving — rotation, theme, font scale, density — so
        // turning the phone does NOT come through here. A system-initiated
        // recreation or a process death does, and losing a half-typed query is
        // the kind of small loss that reads as the app forgetting what you were
        // doing. Found by `LaunchTortureTest.warmStartKeepsTheQueryAndThePhase`.
        //
        // `searched` and the results are deliberately NOT restored. `searched`
        // licenses the "Nothing found for X" panel, which is a claim about the
        // INDEX; the results list is not persisted, so restoring the flag
        // without the rows would assert that a place does not exist because a
        // process was killed — the exact defect `doSearch`'s failure branch
        // already refuses to commit. The query is re-run instead, which is what
        // the driver expects to happen, and it re-establishes `searched`
        // legitimately.
        val restoredQuery = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        val restoredSearching = savedInstanceState?.getBoolean(STATE_SEARCHING) == true
        if (restoredQuery.isNotEmpty() || restoredSearching) {
            ui = ui.copy(query = restoredQuery, searching = restoredSearching)
        }

        setContent {
            VectorApp(
                mapView = mapView,
                ui = ui,
                onQueryChange = ::onQueryChanged,
                onSearch = { doSearch(ui.query) },
                onPick = ::pickDestination,
                onStart = ::startNavigation,
                onCancel = ::cancelRoute,
                onRecenter = ::recenter,
                onToggleVoice = {
                    // The control and the setting are the same value, so muting
                    // from the HUD persists. It used to be a loose UiState flag
                    // that reset to ON at every launch.
                    //
                    // Cycles all four modes rather than toggling two. The HUD
                    // control is the fast path — a passenger takes a phone
                    // call and you want quiet NOW — and requiring a trip to
                    // settings to reach "alerts only" would leave the only
                    // reachable options as "narrate everything" and "silence",
                    // which is the boolean this replaced.
                    updateSettings(ui.settings.copy(voice = ui.settings.voice.next()))
                },
                onToggleSteps = { ui = ui.copy(showSteps = !ui.showSteps) },
                onToggleContributing = ::toggleContributing,
                // Opening the search box clears a stale error.
                //
                // Photographed on the S24 during the adversarial run: an
                // offline search had set `error = "Search failed: … hostname"`,
                // and a later query's "Nothing found for …" card rendered
                // straight over the top of it. The banner lives in the top
                // strip and the card in the search panel, so the two occupy the
                // same band and the driver gets two messages about one failure
                // with the lower half of each hidden.
                //
                // Fixed as STATE rather than as layout, because the layout was
                // not the thing that was wrong: the error was simply stale.
                // Opening the box is the driver saying "I am trying again", and
                // the panel below owns its own reporting from that point —
                // `doSearch` already clears the error when it starts a request,
                // and this covers the moments before one is started.
                onOpenSearch = { ui = ui.copy(searching = true, error = null) },
                onCloseSearch = {
                    searchJob?.cancel()
                    ui = ui.copy(searching = false, query = "", results = emptyList(),
                                 searchInFlight = false, searched = false)
                },
                onToggleOrientation = ::toggleOrientation,
                onToggleOverview = ::toggleOverview,
                onZoomIn = { zoomStep(ZOOM_BUTTON_STEP) },
                onZoomOut = { zoomStep(-ZOOM_BUTTON_STEP) },
                onOpenSettings = { ui = ui.copy(showSettings = true) },
                onCloseSettings = { ui = ui.copy(showSettings = false) },
                onSettingsChange = ::updateSettings,
                onPickRecent = { pickPoint(LngLat(it.lng, it.lat), it.name, remember = false) },
                onClearRecents = {
                    Recents.clear(prefs)
                    ui = ui.copy(recents = emptyList())
                },
                onChooseRoute = ::chooseRoute,
                onDismissArrival = { ui = ui.copy(arrival = null) },
                // Saving a place needs a coordinate the driver has chosen, and
                // PREVIEW is the only phase that has one. See Places.
                onSavePlace = { slot ->
                    val dest = ui.destination
                    if (dest == null) {
                        ui = ui.copy(error = "Choose a destination first")
                    } else {
                        val name = ui.destinationName.ifBlank { slot.label }
                        ui = ui.copy(
                            places = Places.save(prefs, slot, name, dest.lng, dest.lat),
                            // Written where PREVIEW actually renders it — see
                            // the strip in NavUi's bottom group. An icon
                            // changing tint is not confirmation that anything
                            // was stored, and the first version of this line
                            // went to a slot PREVIEW does not draw.
                            status = "${slot.label} set to ${titleCase(name)}",
                        )
                    }
                },
                onPickPlace = { p -> pickPoint(LngLat(p.lng, p.lat), p.name, remember = false) },
                // Forget a slot. `Places.clear` has existed, been tested, and
                // been called by nothing since V5 — so a Home set to the wrong
                // building could be overwritten but never removed.
                onClearPlace = { slot ->
                    ui = ui.copy(
                        places = Places.clear(prefs, slot),
                        // Said where the SETTINGS SHEET renders it. The same
                        // trap as `onSavePlace` above, whose first version
                        // wrote its confirmation to a line only EXPLORE draws:
                        // a row disappearing is the confirmation here, and the
                        // status line is the belt to that braces.
                        status = "${slot.label} cleared",
                    )
                },
                onPickDrive = { d ->
                    // Drive there again. A history row that only reads back is
                    // the read-only report §6 rules out, and going back is the
                    // reason to look at the list.
                    ui = ui.copy(showSettings = false)
                    pickPoint(LngLat(d.destLng, d.destLat), d.destination, remember = false)
                },
                onDeleteDrive = { d ->
                    ui = ui.copy(drives = Drives.remove(prefs, d.endedAtMs))
                },
                onClearDrives = {
                    Drives.clear(prefs)
                    ui = ui.copy(drives = emptyList())
                },
                onOpenPaywall = {
                    pro.gate.markPresented()
                    ui = ui.copy(showPaywall = true, proNotice = null)
                    lifecycleScope.launch { pro.loadOffer() }
                },
                onClosePaywall = { ui = ui.copy(showPaywall = false, proNotice = null) },
                onBuy = ::buyPro,
                onRestore = ::restorePro,
                onManageSubscription = ::openPlaySubscriptions,
                onDismissProNotice = { ui = ui.copy(proNotice = null) },
                onToggleJourney = ::toggleJourney,
                onScrubSunTime = ::scrubSunTime,
                onToggleCooler = ::toggleCooler,
                onWalk = ::startWalkNavigation,
            )
        }

        // Re-run a restored query, so the rows come back rather than the box
        // coming back empty. Safe before the first frame: it only updates `ui`,
        // which the composition reads.
        if (restoredSearching && restoredQuery.isNotBlank()) doSearch(restoredQuery)

        mapView.getMapAsync { map ->
            maplibre = map

            // MapLibre draws its own logo and an "©" attribution button over
            // the bottom-left corner by default. Reported from the S24 as
            // clutter, and it is: on a navigation map that corner is where the
            // speedometer and the speed-limit sign live, and a permanent
            // watermark is chrome the driver did not ask for.
            //
            // The LICENCE OBLIGATION does not go away with the widget. The
            // basemap is OpenStreetMap under ODbL, which requires the credit to
            // be shown, so it moves into the settings sheet where it is legible
            // and reachable (see SettingsSheet). Hiding the mark and dropping
            // the credit would be a licence breach dressed up as a UI fix — the
            // style's `attribution` field keeps it on the source as well.
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false
            // The compass is ours too: Vector's own control states three
            // states (north-up, heading-up, hand-rotated) and offers a
            // recenter, which MapLibre's does not.
            map.uiSettings.isCompassEnabled = false

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(25.2854, 51.5310)).zoom(13.0).build()

            lifecycleScope.launch {
                // The zoom range comes from the SERVER, not from a constant
                // here: a style that declares a zoom the bake does not contain
                // renders a black screen, and nothing in the app can tell.
                tileSet = api.tileSet()
                // The baseline the release watch compares against. Seeded
                // rather than compared: the first read is not a change, there
                // being nothing to have changed from.
                //
                // It also closes an ordering hazard. `onResume` runs BEFORE
                // this callback completes, so the first resume check can be in
                // flight while this line has not run yet. Seeding here means
                // whichever arrives first establishes the baseline and the
                // other finds it unchanged — neither can produce a restyle the
                // driver did not ask for.
                releaseWatch.seed(tileSet)
                Log.i(RELEASE_TAG, "release_seeded token=${tileSet.token} " +
                    "release=${tileSet.release} epoch=${tileSet.epoch} " +
                    "zooms=${tileSet.minZoom}-${tileSet.maxZoom}")
                // Below a vector source's minzoom MapLibre requests NOTHING and
                // draws the background colour — it does not under-zoom the way
                // it over-zooms above the maximum. So without this the driver
                // could pinch out past the bake and watch the country vanish,
                // which is exactly what was reported. Stopping the gesture at
                // the data boundary is the honest behaviour: there is nothing
                // to show further out.
                map.setMinZoomPreference(tileSet.minZoom.toDouble())
                applyStyle(map)
                requestLocation()
                startFrameLoop()
            }

            // Android Auto. Collected here rather than at the top of onCreate
            // because every command needs a live map and a GPS fix to be
            // actionable, and both are ready by this point.
            lifecycleScope.launch { collectCarCommands() }

            // A drag means the driver took over; stop fighting them for the
            // camera. Rotation and pinch are handled differently — see
            // MapCamera.onRotate, and note that zoom needs no listener at all
            // now that the follow camera never asserts one.
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(d: org.maplibre.android.gestures.MoveGestureDetector) {
                    // A gesture wins immediately, including over a camera
                    // transition still in flight: making the driver wait out
                    // an animation before the map responds to their finger is
                    // worse than the clobbering CameraGate exists to stop.
                    cameraGate.cancel()
                    ui = ui.copy(cam = MapCamera.onPan(ui.cam, System.currentTimeMillis()))
                }
                override fun onMove(d: org.maplibre.android.gestures.MoveGestureDetector) {}
                override fun onMoveEnd(d: org.maplibre.android.gestures.MoveGestureDetector) {}
            })

            // A rotate gesture was previously not observed at all, so the
            // follow camera overwrote the driver's bearing on the very next
            // frame — the map snapped back and the gesture appeared broken.
            // It now records a manual bearing and KEEPS following by position,
            // because turning the map to look at a junction should not cost the
            // driver their follow camera.
            map.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(d: org.maplibre.android.gestures.RotateGestureDetector) {}
                override fun onRotate(d: org.maplibre.android.gestures.RotateGestureDetector) {}
                override fun onRotateEnd(d: org.maplibre.android.gestures.RotateGestureDetector) {
                    cameraGate.cancel()
                    ui = ui.copy(
                        cam = MapCamera.onRotate(
                            ui.cam, map.cameraPosition.bearing, System.currentTimeMillis(),
                        ),
                    )
                }
            })

            // Long-press to drop a destination, so a route can be planned
            // without knowing the name of anywhere.
            map.addOnMapLongClickListener { p ->
                pickPoint(LngLat(p.longitude, p.latitude), "Dropped pin")
                true
            }
        }
    }

    // ---- style / theme -----------------------------------------------------

    /**
     * (Re)build and install the style document.
     *
     * Called at startup and again whenever the theme changes, because a
     * MapLibre style is a document rather than a set of live properties: there
     * is no "switch palette" call, the document has to be replaced.
     *
     * **Replacing it drops every source**, which is the trap here. `route`,
     * `puck` and `traffic` are GeoJSON sources declared inside the style, so
     * after a restyle the handles are stale and the map is missing the route
     * line, the vehicle and the congestion the driver was looking at a moment
     * ago — with no error anywhere. Re-acquiring the handles and redrawing what
     * we already hold is therefore part of applying a style, not a separate
     * step a caller might forget.
     */
    private fun applyStyle(map: MapLibreMap) {
        val theme = ui.settings.resolvedTheme(systemInDark())
        appliedTheme = theme
        applySystemBarAppearance(theme)
        // Let the old style's sources go BEFORE asking for a new one.
        //
        // `setStyle` is asynchronous: it destroys the current style now and
        // calls back when the replacement has loaded, and the handles below are
        // only re-acquired in that callback. So between the two there is a
        // window — long enough to load a style document — in which every one of
        // these references points at a destroyed native peer, and the frame
        // loop is still running at up to 120 Hz.
        //
        // Writing to one of those is the `IllegalStateException: invalid native
        // peer` crash `mapAlive` documents, reached here by a different route:
        // not shutdown, but a driver **changing the theme while navigating**,
        // which is an ordinary thing to do. The comment further down already
        // knew "replacing a style drops the image registry along with the
        // sources"; what it handled was re-acquiring them afterwards, not the
        // gap in the middle.
        //
        // Nulling them makes that gap a no-op — `drawPuck` and friends are all
        // `source?.setGeoJson(...)` — and the callback redraws everything from
        // `lastRouteCoords`, `lastTrafficJson` and `lastFix` anyway.
        val styleJson = VectorStyle.json(
            BuildConfig.API_BASE, tileSet.epoch,
            tileSet.minZoom, tileSet.maxZoom, theme,
            // The MAP's labels, in the same language the router already
            // gives its instructions in. Both read `name:en` where the
            // road has one; before V4 only the router did, so the
            // banner said "Al Urouba Street" over a map that said
            // "شارع العروبة".
            lang = java.util.Locale.getDefault().language,
            // V7 3D: the driver's own 2D/3D choice decides whether the
            // extruded building layer is part of the style at all. One
            // control, two consumers — the camera pitch and the
            // extrusion — so they cannot disagree about which mode the
            // map is in.
            extruded = extrudedBuildings,
            // V7.7: the release becomes the `?v=` token when the server
            // names one. Empty keeps the epoch, which is what an
            // un-migrated volume and an older server both report.
            release = tileSet.release,
        )
        map.setStyle(
            Style.Builder().fromJson(
                styleJson.also {
                    // V7 3D device telemetry, debug-only, in the same spirit as
                    // VectorCam / VectorSignals: the one fact none of the other
                    // logs carry is whether the extrusion layer actually made
                    // it into the style the map was given. A style that parses
                    // and a layer that renders are different claims, and three
                    // device runs were spent on the gap between them.
                    if (BuildConfig.DEBUG) {
                        Log.i("Vector3D", "style extruded=$extrudedBuildings " +
                            "perspective=${ui.cam.perspective} phase=${ui.phase} " +
                            "onFoot=${walkSession != null} " +
                            "extrusionLayers=${Regex("fill-extrusion").findAll(it).count()} " +
                            // V7.7: the release alongside the epoch, so every
                            // future emulator run is self-describing. AC-19
                            // depended on this line and had to infer the
                            // release from which tiles were requested.
                            "tileSet=${tileSet.epoch}/${tileSet.minZoom}-${tileSet.maxZoom} " +
                            "release=${tileSet.release.ifEmpty { "-" }} " +
                            "v=${tileSet.token}")
                    }
                }
            )
        ) { style ->
            // Marker images, BEFORE anything is drawn.
            //
            // A `symbol` layer whose `icon-image` is not registered renders
            // nothing and logs nothing, so registering these late would mean an
            // invisible vehicle for however long it took — and replacing a
            // style drops the image registry along with the sources, which is
            // the same trap the source handles below are re-acquired for.
            val m = resources.displayMetrics
            style.addImage(
                VectorMarkers.VEHICLE,
                VectorMarkers.vehicle(m, VEHICLE_FILL, VEHICLE_STROKE),
            )
            style.addImage(
                VectorMarkers.ORIGIN,
                VectorMarkers.origin(m, VEHICLE_FILL, VEHICLE_STROKE),
            )
            style.addImage(
                VectorMarkers.DESTINATION,
                VectorMarkers.destination(m, DESTINATION_FILL, VEHICLE_STROKE),
            )
            // The warning pill, registered STRETCHABLE. `icon-text-fit` on its
            // own would scale the bitmap, so "Left" and "Exit Q3;Q5" would end
            // up with different corner radii — one looking like a squashed
            // version of the other. Telling MapLibre which two pixels may
            // repeat keeps the ends round whatever the text is.
            val (sx0, sx1) = VectorMarkers.pillStretch(m)
            val box = VectorMarkers.pillContent(m)
            style.addImage(
                VectorMarkers.CALLOUT_PILL,
                VectorMarkers.pill(m, CALLOUT_PILL_FILL, CALLOUT_PILL_RIM),
                // The same band on both axes.
                //
                // Vertical stretching never actually happens — `icon-text-fit:
                // width` in the style keeps the capsule's height fixed — but
                // the axis cannot be left EMPTY. `emptyList()` here crashed the
                // app on every style load with
                // `ArrayIndexOutOfBoundsException: float[] offset=0 length=1
                // src.length=0` out of `nativeAddImages`: the JNI reads one
                // float pair per axis and does not check that there is one.
                // A style load happens at launch and on every theme switch, so
                // this was a hard crash on the first frame.
                listOf(org.maplibre.android.maps.ImageStretches(sx0, sx1)),
                listOf(org.maplibre.android.maps.ImageStretches(sx0, sx1)),
                org.maplibre.android.maps.ImageContent(box[0], box[1], box[2], box[3]),
            )

            // The warning glyphs, registered under exactly the ids a callout
            // carries in its `icon` property — the style draws them with
            // `["get", "icon"]`, so any id missing from here is a callout that
            // renders as nothing at all, with no error anywhere. The list comes
            // from `VectorMarkers`, which is also where `Callouts.Icon` is
            // asserted against it, so the two ends cannot drift.
            VectorMarkers.warningImages(m).forEach { (id, bitmap) ->
                style.addImage(id, bitmap)
            }

            // No handles are cached — see `source(id)`. Redraw what the
            // previous style was showing, now that there is a style to draw on.
            if (lastRouteCoords.isNotEmpty()) drawRoute(lastRouteCoords, ui.maneuvers, lastRouteSignals, lastRouteCameras)
            drawAlternatives(ui.alternatives, ui.chosenRoute)
            drawEndpoints(lastRouteCoords)
            drawRouteLabels(ui.alternatives, ui.chosenRoute)
            lastTrafficJson?.let { source(SRC_TRAFFIC)?.setGeoJson(it) }
            lastFix?.let { drawPuck(it, lastBearing) }
            // No "Ready".
            //
            // This used to seed the status line with the literal word, which
            // put a grey 12 sp label in the middle of an otherwise empty
            // bottom third of the explore screen and told the driver nothing
            // (§40, "no placeholder UI"). The line still exists for messages
            // that are about something — arrival, a search in flight, a route
            // being calculated.
        }
    }

    /**
     * Make the status-bar clock and icons legible against the map.
     *
     * The window is edge-to-edge (`setDecorFitsSystemWindows(false)`), so the
     * system bars sit directly on top of the map and Android does not repaint
     * their contents for us. With the light theme selected the result was white
     * icons on a near-white ground: the clock, the battery percentage and the
     * signal bars were all invisible. Verified on an S24 — the time read as a
     * faint smudge in the corner.
     *
     * Note the inversion: a LIGHT theme needs `isAppearanceLightStatusBars =
     * true`, which means "the bars are on a light background, so draw their
     * contents DARK". The flag names the background, not the content.
     */
    /**
     * Tell the system which way round to draw its own bar icons.
     *
     * Depends on the PHASE as well as the theme, because the status bar sits
     * over the maneuver banner while navigating and over the map otherwise, and
     * the banner is dark in both themes. The rule is
     * [statusBarWantsDarkIcons]; this is the only place it is applied, and it
     * is applied from the `ui` setter so a phase change cannot miss it.
     */
    private fun applySystemBarAppearance(
        theme: VectorStyle.MapTheme,
        phase: Phase = ui.phase,
    ) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = statusBarWantsDarkIcons(theme, phase)
            isAppearanceLightNavigationBars = navBarWantsDarkIcons(theme)
        }
    }

    /**
     * Hold the display awake, or stop holding it.
     *
     * `FLAG_KEEP_SCREEN_ON` rather than a `WakeLock`: it is scoped to this
     * window, so it needs no permission, and — the part that matters — the
     * system drops it when the window goes away. A wake lock survives its
     * owner, so a crash mid-drive would leave the screen pinned on until the
     * battery ran out, and the release path would have to be correct on every
     * exit including the ones that do not run code.
     *
     * The rule is [screenShouldStayOn]; this is the only place it is applied,
     * and it is applied from the `ui` setter so a phase change cannot miss it.
     */
    private fun applyKeepScreenOn(phase: Phase = ui.phase) {
        if (screenShouldStayOn(phase)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun systemInDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ---- tile release ------------------------------------------------------

    /**
     * Whether the app is under active guidance right now.
     *
     * `Phase.NAVIGATING` covers DRIVING AND WALKING — `beginWalk` sets the same
     * phase as `startNavigation` — which is why this reads the phase rather
     * than `walkSession != null`. A pedestrian being guided is as badly served
     * by a style rebuilt underneath them as a driver.
     */
    private fun underGuidance(): Boolean = ui.phase == Phase.NAVIGATING

    /**
     * Ask the server which release is active, and act on the answer if it is
     * safe to.
     *
     * **This is the fix for AC-19's D1.** The app read `/tiles/version` exactly
     * once, at `onCreate`, and never again, so a release swapped underneath a
     * running app was invisible — not because the client was misled but
     * because it never asked. Eight measured emulator phases produced two
     * pairs of byte-identical screenshots across two genuinely different
     * releases.
     *
     * Asking is unconditional; ACTING on the answer is not. [ReleaseWatch]
     * owns that rule and cannot emit an apply while guidance is running.
     *
     * Cheap by construction: at most one request per resume, floored by
     * `ReleaseWatch.minIntervalMs`, against an endpoint that Commit 4 turned
     * from a 0.4 s walk of 18,311 files into a `stat` and a small JSON read.
     * Resume already does network work — `/speed` and `/traffic` both fire —
     * so this is not a new cost pattern.
     */
    private fun checkRelease() {
        // `elapsedRealtime`, not `currentTimeMillis`: this measures an
        // INTERVAL, and a wall clock is allowed to jump — an NTP correction or
        // a driver crossing into a country that resets the phone's clock. A
        // backwards jump on a wall clock would silence the check until the
        // clock caught up. Monotonic since boot is the right clock for
        // "how long since the last one", and the codebase already uses it for
        // every other interval it measures.
        val step = releaseWatch.beginCheck(android.os.SystemClock.elapsedRealtime())
        logRelease(step)
        if (step !is ReleaseWatch.Step.CheckStarted) return

        // Not cancelled by a subsequent resume: `beginCheck` has already
        // marked a check in flight, and cancelling this job would leave that
        // flag set forever with no `complete` to clear it. The in-flight guard
        // IS the deduplication; the job reference exists so teardown can stop
        // it (see onDestroy).
        releaseJob = lifecycleScope.launch {
            // `tileSetOrNull`, NOT `tileSet`: the latter is total and returns
            // the conservative default on failure, so a timeout would read as
            // "the release changed to epoch 0" and rebuild the style on a
            // network blip. Null here means preserve what is running.
            val fetched = api.tileSetOrNull()
            releaseWatch.complete(fetched, underGuidance()).forEach { applyReleaseStep(it) }
        }
    }

    /**
     * Apply a release that was found during guidance, if guidance has ended.
     *
     * Called from [endJourney] — the single funnel both exits take, verified
     * by enumerating every `phase` assignment in this file — and again from
     * [onResume]. The second is redundant today and is there so correctness
     * does not depend on `endJourney` staying the only way out of
     * `Phase.NAVIGATING`: a future exit would delay adoption to the next
     * resume rather than stranding the release.
     */
    private fun applyPendingRelease() {
        releaseWatch.applyPendingIfSafe(underGuidance()).forEach { applyReleaseStep(it) }
    }

    /** Log a step, and carry out the one kind that authorises a restyle. */
    private fun applyReleaseStep(step: ReleaseWatch.Step) {
        logRelease(step)
        if (step !is ReleaseWatch.Step.Apply) return

        // The map may be gone — the check is asynchronous and teardown does not
        // wait for it. `mapAlive` is the same guard the frame loop uses; after
        // onDestroy nothing may touch a native MapLibre object.
        val map = maplibre
        if (!mapAlive || map == null) {
            Log.i(RELEASE_TAG, "release_change_applied_safe deferred_no_map " +
                "to=${step.to}")
            return
        }

        tileSet = step.tileSet
        // The new release may cover a different zoom range, and the gesture
        // floor is a property of the bake rather than of the app: without this
        // a release that starts at z12 would let the driver pinch out past its
        // data and watch the country vanish, which is the reported defect the
        // cold-start path sets this for.
        map.setMinZoomPreference(tileSet.minZoom.toDouble())
        applyStyle(map)
    }

    private fun logRelease(step: ReleaseWatch.Step) {
        val detail = when (step) {
            is ReleaseWatch.Step.DuplicateSuppressed -> "reason=${step.reason}"
            is ReleaseWatch.Step.CheckSucceeded -> "token=${step.token}"
            is ReleaseWatch.Step.Unchanged -> "token=${step.token}"
            is ReleaseWatch.Step.ChangeDetected -> "from=${step.from} to=${step.to}"
            is ReleaseWatch.Step.DeferredForGuidance ->
                "from=${step.from} to=${step.to} phase=${ui.phase}"
            is ReleaseWatch.Step.Apply ->
                "from=${step.from} to=${step.to} deferred=${step.deferred} " +
                    "zooms=${step.tileSet.minZoom}-${step.tileSet.maxZoom}"
            else -> ""
        }
        // Not gated on BuildConfig.DEBUG, unlike the V7 3D style telemetry.
        // These are operational events at a rate of at most one per resume,
        // and "which release was this phone on when it went wrong" is a
        // question a production bug report has to be able to answer. AC-19
        // cost five emulator phases establishing facts a line like this states.
        Log.i(RELEASE_TAG, if (detail.isEmpty()) step.event else "${step.event} $detail")
    }

    /**
     * The system flipped between light and dark while the app was open.
     *
     * Only acts when the driver's preference is SYSTEM, and only when the
     * resolved theme actually changed — a configuration change also fires for
     * rotation, font scale and locale, and restyling the map on each of those
     * would drop and rebuild every source for nothing.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val map = maplibre ?: return
        if (ui.settings.resolvedTheme(systemInDark()) != appliedTheme) applyStyle(map)
    }

    // ---- settings ----------------------------------------------------------

    /**
     * Persist a preference change and apply its consequences.
     *
     * One place, so that "the driver chose X" and "X is stored" and "X is in
     * effect" cannot come apart. Each consequence below is something a settings
     * screen quietly fails to do if it only writes the value:
     *
     * * theme -> the style document has to be rebuilt (see [applyStyle]);
     * * units -> the ANNOUNCER has to be told, or the screen changes to miles
     *   and the voice keeps saying metres;
     * * voice -> the engine has to be stopped, or the current sentence finishes
     *   playing after the driver muted it;
     * * withdrawing collection consent -> the buffer has to be DISCARDED, not
     *   just stopped (adr-0068): what has not been sent must not be sent.
     */
    // ---- Vector Pro -------------------------------------------------------

    /**
     * Buy.
     *
     * The Activity is passed through because Google Play's purchase sheet is an
     * Activity result, not a background call — there is no way to buy without
     * one on screen.
     */
    private fun buyPro(pkg: com.revenuecat.purchases.Package) {
        if (ui.proBusy) return
        ui = ui.copy(proBusy = true, proNotice = null)
        lifecycleScope.launch {
            val problem = pro.purchase(this@MainActivity, pkg)
            ui = ui.copy(proBusy = false, proNotice = problem)
        }
    }

    private fun restorePro() {
        if (ui.proBusy) return
        ui = ui.copy(proBusy = true, proNotice = null)
        lifecycleScope.launch {
            val problem = pro.restore()
            ui = ui.copy(
                proBusy = false,
                proNotice = problem ?: when (pro.state.value) {
                    dev.vector.android.pro.ProStatus.PRO -> null
                    // A restore that succeeds and finds nothing is not an
                    // error, and must not be reported as one — but it does have
                    // to be reported, or the button looks broken.
                    else -> dev.vector.android.pro.ProStoreCopy
                        .noSubscriptionFound(ui.proStore)
                },
            )
        }
    }

    /**
     * Open the driver's subscription in Play.
     *
     * Deep-linked to Vector's own entry rather than the subscription list,
     * because "manage subscription" on a list of nine subscriptions is a
     * search task. Falls back to the list if the deep link cannot be resolved,
     * and reports rather than crashing if neither can.
     */
    private fun openPlaySubscriptions() {
        val deep = "https://play.google.com/store/account/subscriptions" +
            "?sku=vector_pro&package=${packageName}"
        val opened = runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(deep)))
            true
        }.getOrElse {
            runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/account/subscriptions")))
                true
            }.getOrDefault(false)
        }
        if (!opened) ui = ui.copy(
            proNotice = "Could not open " +
                dev.vector.android.pro.ProStoreCopy.name(ui.proStore) +
                " on this device.",
        )
    }

    private fun updateSettings(next: Settings) {
        val prev = ui.settings
        // Captured BEFORE `ui` is replaced: the restyle below needs to know
        // whether the extrusion state actually changed, and `extrudedBuildings`
        // reads `ui`.
        val wasExtruded = extrudedBuildings
        Settings.save(prefs, next)
        ui = ui.copy(
            settings = next,
            cam = ui.cam.copy(
                orientation = next.orientation,
                perspective = next.perspective,
                // Changing the orientation preference is an explicit decision
                // and must beat a stale manual rotation, or the setting would
                // appear to do nothing.
                manualBearing = if (next.orientation != prev.orientation) null else ui.cam.manualBearing,
            ),
        )

        if (next.units != prev.units) announcer.units = next.units
        if (next.voice != prev.voice) {
            voice.enabled = next.voice.speaksAlerts
            // Stop the sentence that is playing, not just the next one. A
            // driver who mutes mid-instruction has muted mid-instruction.
            if (!next.voice.speaksAlerts) voice.stop()
        }
        if (!next.contributing && prev.contributing) probes.discard()
        if (next.theme != prev.theme) maplibre?.let { applyStyle(it) }
        if (next.trafficInExplore && !prev.trafficInExplore) {
            lastTrafficAtMs = 0L
            refreshTraffic()
        }
        if (!next.trafficInExplore && ui.phase != Phase.NAVIGATING) clearTraffic()
        // The camera preference only takes effect on the next followed frame,
        // which at 1 Hz would be up to a second of the map looking unchanged.
        // Applying it now makes the setting feel like a switch rather than a
        // suggestion.
        if (next.orientation != prev.orientation || next.perspective != prev.perspective) {
            recenter()
            // V7 3D. The 2D/3D choice also decides whether the extruded
            // building layer is in the style at all, and that is a style
            // change rather than a camera one. Restyling only when the
            // EXTRUSION state actually changed keeps a plain orientation flip
            // from paying for a style reload it does not need — `recenter`
            // above already handles the camera half.
            if (extrudedBuildings != wasExtruded) maplibre?.let { applyStyle(it) }
        }
    }

    // ---- camera ------------------------------------------------------------

    /**
     * Put the camera back on the vehicle.
     *
     * A whole-camera command, not a nudge: it restores position, bearing, tilt
     * AND zoom. Zoom is asserted here and at [startNavigation] and nowhere
     * else — never on a followed frame, which is what used to make pinching out
     * during navigation impossible.
     */
    /**
     * Hand the camera back to the vehicle once a takeover has gone stale.
     *
     * Driven from the fix path, not the frame loop. [MapCamera.AUTO_RESUME_MS]
     * is ten seconds and fixes land at 1 Hz, so a tenth of the resolution is
     * already an order of magnitude more than the decision needs — and the
     * frame loop is the one place in this app that must stay free of work that
     * does not have to be there.
     *
     * The whole policy is in [MapCamera.autoResume], which is pure and tested
     * on the JVM; this is the adapter that gives it a clock and turns its
     * answer into the same camera command the recenter button issues. See its
     * KDoc for the four-minute frozen map that made it necessary.
     */
    private fun maybeResumeFollow() {
        if (MapCamera.autoResume(ui.cam, ui.phase, System.currentTimeMillis()) == null) return
        Log.i(NAV_TAG, "camera auto-resumed after ${MapCamera.AUTO_RESUME_MS} ms")
        // `assertZoom = false`: this recenter is the APP's decision, not the
        // driver's. Putting the map back in front of the car is the whole
        // point of auto-resume; re-asserting a zoom the driver chose is not,
        // and doing it on a 10 s timer undid every deliberate pinch for a
        // whole journey. See MapCamera.recenter's `zoom` parameter.
        recenter(assertZoom = false)
    }

    /**
     * @param assertZoom whether this recenter owns the zoom. True for one the
     *   driver asked for; false for [maybeResumeFollow]'s timer.
     */
    private fun recenter(assertZoom: Boolean = true) {
        val cam = MapCamera.onRecenter(ui.cam)
        ui = ui.copy(cam = cam)
        val here = lastFix ?: return
        applyLookAhead()
        val onFoot = walkSession != null
        applyCamera(
            MapCamera.recenter(
                // ## The walking defect this `copy` exists because of
                //
                // `MapCamera.tiltFor` returns [MapCamera.TILT_DEG] — 60° — for
                // a TILTED, heading-up camera while NAVIGATING. That is right
                // for a drive and wrong for a walk: `WalkCameraPolicy` asserts
                // a FLAT pitch deliberately (the car buys tilt to claw back
                // look-ahead a closer zoom spends, and a walk at 120 m of
                // look-ahead is already 89 seconds ahead — it does not need
                // the trade, and tilt costs legibility of a map someone reads
                // while standing still).
                //
                // So a walker who panned away and waited out `autoResume`, or
                // tapped recenter, got the CAR's 60° written over the walking
                // framing. The policy corrects it on the next fix in every
                // path reachable today — it forgets `writtenZoom` while the
                // camera is not its own, so the first fix after hand-back
                // writes — but that is a flash of the wrong camera, and it is
                // a property of which paths happen to be reachable rather than
                // of the decision being stated anywhere.
                //
                // Fixed through the EXISTING authority rather than beside it:
                // FLAT is `MapCamera`'s own vocabulary for "do not tilt", so
                // there is still exactly one function deciding tilt and the
                // car's behaviour is untouched.
                if (onFoot) cam.copy(perspective = MapPerspective.FLAT) else cam,
                ui.phase, here, lastBearing,
                // Through `transitionZoom` rather than a hardcoded NAV_ZOOM so
                // a driver-asked recenter lands on the speed band the map
                // would have picked anyway, instead of jumping to 16.5 and
                // tripping the 0.8 tolerance in `maybeAutoZoom`.
                //
                // Never on foot: `transitionZoom` is the SPEED ladder, every
                // walk sits in its bottom rung forever, and that rung means
                // ~15 s of travel — 20 m on foot, closer than the shortest
                // real gap between two walking maneuvers. A null hands the
                // zoom back to `WalkCameraPolicy`, which derives it from
                // ground distance.
                zoom = if (assertZoom && !onFoot) {
                    MapCamera.transitionZoom(ui.phase, ui.speedKmh, ui.settings.autoZoom)
                } else {
                    null
                },
            ),
            // A recenter is a command about THIS moment, not a tour. MEDIUM,
            // not FLIGHT: the driver tapped because they want the map back
            // now, and 1.8 seconds of travel would be the animation §7 warns
            // about making the user wait for.
            durationMs = VectorTokens.Motion.MEDIUM,
        )
    }

    private fun toggleOrientation() {
        val cam = MapCamera.toggleOrientation(ui.cam)
        ui = ui.copy(cam = cam)
        // Persist it: a driver who turns north-up on today means it tomorrow.
        updateSettings(ui.settings.copy(orientation = cam.orientation))
    }

    /**
     * Show the whole route, or go back to following.
     *
     * Framing the route is the one camera state that cannot be expressed as a
     * position and a bearing, so it does not go through [MapCamera.follow] —
     * it needs the route's bounds.
     */
    private fun toggleOverview() {
        val cam = MapCamera.toggleOverview(ui.cam)
        ui = ui.copy(cam = cam)
        if (cam.mode == CameraMode.OVERVIEW) fitRoute(lastRouteCoords) else recenter()
    }

    /**
     * One press of a zoom control.
     *
     * ## Why this is not `map.setZoom`
     *
     * Because a zoom button is a discrete transition and this app has an
     * invariant about those. A followed frame never asserts a zoom — that is
     * what makes pinching work at all — and zoom is only written at moments
     * Vector can name: starting navigation, recentering, an auto-zoom band
     * change, a maneuver stage. Writing the camera from outside [applyCamera]
     * would put a fourth writer in the loop that [CameraGate] cannot see, and
     * the first follow frame afterwards would cancel it with `moveCamera` and
     * re-supply the zoom it read back mid-move. That is precisely the defect
     * CameraGate was built for.
     *
     * ## Why it is not recorded as Vector's zoom
     *
     * `ownsZoom = false`. A press is the DRIVER's decision, so it leaves
     * `CameraGate.assertedZoom` where the last Vector transition put it and
     * simply moves the camera away from it — which is what a pinch does, and
     * is read the same way by `CameraGate.isDriverZoom`. From the next fix
     * onwards the auto-zoom band and the maneuver camera both see a driver who
     * owns the zoom and stay out of it, instead of fighting the button once a
     * second. [ZOOM_BUTTON_STEP] carries the arithmetic that makes the move
     * large enough to be seen as one.
     *
     * ## Why the mode is untouched
     *
     * A pinch does not drop the camera to FREE and neither does this. Asking
     * "how much can I see" is not the same as taking the map, and answering it
     * should not cost the driver a recenter tap they did not ask to owe — the
     * same argument `MapCamera.onRotate` makes about a rotate gesture.
     */
    private fun zoomStep(delta: Double) {
        val map = maplibre ?: return
        val live = map.cameraPosition
        // Clamped to what the BAKE contains, for the reason the style load
        // gives: below a vector source's minzoom MapLibre requests nothing and
        // draws the background colour, so an unclamped zoom-out is a button
        // that makes the country disappear.
        val to = (live.zoom + delta).coerceIn(map.minZoomLevel, map.maxZoomLevel)
        // Already against the stop. Silently doing nothing is right here: the
        // alternative is an animation that does not move, which reads as a
        // dropped press rather than as a limit.
        if (kotlin.math.abs(to - live.zoom) < 1e-6) return
        val target = lastFix
            ?.let { MapCamera.follow(ui.cam, ui.phase, it, lastBearing) }
            // V7 3D fix. `follow` supplies the CAR's tilt, and the HUD zoom
            // buttons are shared, so pressing one during a walk wrote a
            // 60-degree pitch onto a walking map — breaking the flat-pitch
            // contract that `WalkCameraPolicy.WALK_TILT_DEG` and its three
            // tests exist to hold. The tilt is therefore stated explicitly
            // rather than inherited: see [MapCamera.tiltForZoomStep].
            ?.copy(zoom = to,
                   tilt = MapCamera.tiltForZoomStep(
                       ui.cam, ui.phase, onFoot = walkSession != null))
            // FREE, OVERVIEW, PREVIEW, or no fix yet: `follow` returns null
            // because the camera is deliberately not on the vehicle, and
            // zooming has to happen about WHERE THE DRIVER IS LOOKING rather
            // than yank the map back to the car. So the live camera supplies
            // everything except the zoom.
            ?: live.target?.let {
                CameraTarget(
                    position = LngLat(it.longitude, it.latitude),
                    bearing = live.bearing,
                    tilt = live.tilt,
                    zoom = to,
                )
            } ?: return
        applyCamera(
            target,
            // MEDIUM, not FLIGHT. One level is a nudge, not a change of scale,
            // and §7's rule is that the driver never waits on an animation.
            durationMs = VectorTokens.Motion.MEDIUM,
            ownsZoom = false,
        )
    }

    /**
     * Put the camera somewhere.
     *
     * @param durationMs 0 to cut (the frame-loop case), otherwise the length of
     *   the eased move. [VectorTokens.Motion.FLIGHT] for crossing scales,
     *   [VectorTokens.Motion.MEDIUM] for a nudge.
     * @param ownsZoom whether VECTOR is asserting this zoom. True for every
     *   transition the app decided on by itself. False for [zoomStep], which is
     *   a transition the DRIVER decided on: it still has to come through here
     *   so the follow loop stays out of its way, but recording it as Vector's
     *   own zoom would reset the baseline `CameraGate` measures a takeover
     *   against, and the next fix would hand the zoom straight back. See
     *   [ZOOM_BUTTON_STEP].
     */
    private fun applyCamera(
        target: CameraTarget,
        durationMs: Int = 0,
        ownsZoom: Boolean = true,
    ) {
        val map = maplibre ?: return
        val now = System.currentTimeMillis()
        if (durationMs > 0) {
            // The zoom goes in with the transition: this is the ONE place every
            // Vector-initiated camera move passes through, so it is the only
            // place that can honestly say "Vector asserted this zoom". See
            // CameraGate.isDriverZoom.
            //
            // A null zoom here is `beginTransition`'s existing "this move
            // claims no ownership" case, which is exactly what a driver-owned
            // zoom needs: the busy window still opens, so the follow loop does
            // not cancel the ease mid-flight, and the asserted value is left
            // where it was for the gate to measure against.
            cameraGate.beginTransition(now, durationMs, if (ownsZoom) target.zoom else null)
        } else if (!cameraGate.mayFollow(now)) {
            // A transition is in flight. A followed frame here would cancel it
            // with `moveCamera` and re-supply the zoom it read back mid-move —
            // see CameraGate. The vehicle keeps being drawn; only the camera
            // waits, for at most the length of the transition.
            if (BuildConfig.DEBUG) camGateBlocked++
            return
        }
        val builder = CameraPosition.Builder()
            .target(LatLng(target.position.lat, target.position.lng))
            .bearing(bearingForFrame(target.bearing, durationMs))
            .tilt(target.tilt)
        // A null zoom means LEAVE IT ALONE. MapLibre's builder has no "keep"
        // value, so the current zoom is read back and re-supplied.
        builder.zoom(target.zoom ?: map.cameraPosition.zoom)
        val update = CameraUpdateFactory.newCameraPosition(builder.build())
        if (durationMs > 0) {
            map.easeCamera(update, durationMs)
        } else {
            // moveCamera, not animateCamera: on a followed frame the position is
            // already continuous, and layering an animation on top is what made
            // the web client fight itself.
            map.moveCamera(update)
        }
    }

    /**
     * Smooth the bearing, the short way round, at a frame-rate-independent rate.
     *
     * ## The two defects this closes
     *
     * §8 of the V4 brief names both, and both were real:
     *
     * 1. **`heading 359° → 0°` rotated the long way.** The follow camera
     *    assigned the measured bearing straight onto the MapLibre camera, so
     *    every crossing of north handed the renderer a 359-degree difference to
     *    interpolate. Driving north up Al Corniche was enough to trigger it.
     * 2. **GPS noise made the map shake.** Heading is close to random below
     *    walking pace, so a stationary phone in heading-up rotated
     *    continuously in response to a value the receiver was guessing.
     *
     * [MapCamera.smoothBearing] is the fix and it is pure and unit-tested;
     * this method is the adapter that gives it a `dt` and a stationary flag.
     *
     * Skipped entirely for an EASED move. A 1.8-second flight already
     * interpolates, and filtering its target as well would make the camera
     * chase a moving goal for the whole animation.
     */
    private fun bearingForFrame(measured: Double, durationMs: Int): Double {
        if (durationMs > 0) {
            // Adopt the value so the next frame smooths from where the eased
            // move actually landed, not from a stale reading.
            smoothedBearing = measured
            lastBearingAtNanos = System.nanoTime()
            return measured
        }
        val now = System.nanoTime()
        val dt = if (lastBearingAtNanos == 0L) 0.0 else (now - lastBearingAtNanos) / 1e9
        lastBearingAtNanos = now
        // Below MOVING_MS the receiver's heading is not information. A parked
        // car has no heading, and holding the last one the driver saw is better
        // than inventing one — see MapCamera.smoothBearing.
        val stationary = (ui.speedKmh ?: 0) < (MOVING_MS * 3.6)
        smoothedBearing = MapCamera.smoothBearing(smoothedBearing, measured, dt, stationary)
        return smoothedBearing
    }

    /**
     * Push the vehicle down the screen so the road ahead has room.
     *
     * MapLibre centres the camera target inside the *padded* viewport, so top
     * padding moves the target down. To land the vehicle at fraction `f` of the
     * height: the padded region's centre is `padTop/2 + H/2`, and setting that
     * equal to `f·H` gives `padTop = 2·(f − 0.5)·H`.
     *
     * Applied with `setPadding` rather than per-camera-position, so it survives
     * every subsequent camera update instead of having to be re-specified sixty
     * times a second — and so the frame loop cannot forget it.
     *
     * §10's look-ahead. Vector centred the vehicle, which spends half a 2340 px
     * display on road that has already been driven past.
     */
    private fun applyLookAhead() {
        val map = maplibre ?: return
        val f = MapCamera.lookAheadFraction(ui.phase, ui.cam)
        val top = (2.0 * (f - 0.5) * mapView.height).coerceAtLeast(0.0)
        if (Math.abs(top - appliedTopPadding) < 4.0) return
        appliedTopPadding = top
        runCatching { map.setPadding(0, top.toInt(), 0, 0) }
    }

    /**
     * Zoom out at speed, back in for junctions.
     *
     * Called on GPS fixes, which arrive at 1 Hz — **not** from the frame loop.
     * That is the invariant V3's camera rework established and this must not
     * break: a followed frame never asserts a zoom, because
     * `coerceAtLeast(16.5)` running sixty times a second is what made pinching
     * out during navigation impossible. A band change is a discrete
     * transition, so it is allowed to move the camera; a frame is not.
     *
     * The threshold in [MapCamera.zoomChangeWorthMaking] is what stops a driver
     * holding 54-56 km/h from having the map breathe in and out indefinitely
     * across a band boundary.
     */
    private fun maybeAutoZoom() {
        if (!ui.settings.autoZoom) return
        if (ui.phase != Phase.NAVIGATING) return
        if (ui.cam.mode != CameraMode.FOLLOW) return
        // A maneuver stage owns the zoom right now (V7 Stage 2): a band change
        // would fight the progressive tightening that is already in flight.
        // The maneuver camera runs BEFORE this on the fix path, so isActive()
        // is already true for the stage it just entered.
        if (maneuverCamera.isActive()) return
        val map = maplibre ?: return
        val here = lastFix ?: return
        val band = MapCamera.autoZoomBand(ui.speedKmh, autoZoomBand)
        if (band == autoZoomBand) return
        val want = MapCamera.zoomForBand(band)
        // The driver's own zoom wins.
        //
        // If they have pinched away from where Vector put the camera, the zoom
        // is theirs and a band change must not take it back. Adopting their
        // value as the new baseline means auto-zoom picks up again from where
        // they left it rather than fighting them — the same principle as V3's
        // rule that a followed frame never asserts a zoom at all.
        //
        // The question goes to CameraGate rather than being re-derived from the
        // band here, so that this and [maybeManeuverCamera] cannot disagree
        // about who owns the zoom. Deriving it from the band was also wrong on
        // its own terms: after an ANTICIPATE lift the camera legitimately sits
        // above the band, and this read that as a pinch and silently stopped
        // applying band changes for the rest of the drive (V7 Stage 3, D1).
        if (cameraGate.isDriverZoom(map.cameraPosition.zoom, System.currentTimeMillis())) {
            autoZoomBand = band
            return
        }
        autoZoomBand = band
        if (BuildConfig.DEBUG) camWritesAutoZoom++
        applyCamera(
            MapCamera.follow(ui.cam, ui.phase, here, lastBearing)
                ?.copy(zoom = want) ?: return,
            durationMs = VectorTokens.Motion.MEDIUM,
        )
    }

    /**
     * The camera's reaction to the maneuver ahead (V7 Stage 2).
     *
     * Runs on GPS fixes BESIDE — actually just before — [maybeAutoZoom], and
     * never from the frame loop: that is the invariant that keeps pinch-to-zoom
     * working, and the plan's hard rule is one eased write per stage, once per
     * maneuver. The whole policy is pure in [ManeuverCamera]; this function
     * feeds it wire facts and applies its rare discrete writes.
     *
     * The mode gates (FREE/OVERVIEW/PREVIEW/manual bearing/off-route) live
     * here because they are camera facts, not maneuver facts — see
     * [ManeuverCamera]'s KDoc.
     */
    private fun maybeManeuverCamera() {
        if (ui.phase != Phase.NAVIGATING) return
        if (ui.cam.mode != CameraMode.FOLLOW) return
        // A hand-rotated map is the driver looking at the road ahead; a
        // maneuver camera would be the app taking their look away.
        if (ui.cam.manualBearing != null) return
        if (ui.offRoute) return
        val map = maplibre ?: return
        val here = lastFix ?: return
        val m = ui.currentManeuver ?: return
        val following = followingManeuver(ui.maneuvers, m)
        val input = ManeuverCamera.Input(
            maneuverIndex = m.index,
            maneuverType = m.type,
            distanceToManeuverM = ui.distanceToManeuverM,
            speedMs = (ui.speedKmh ?: 0) / 3.6,
            approachLanes = m.approachLanes,
            laneUseful = m.laneStatus == LaneGuidance.Status.USEFUL,
            nextManeuverGapM = following?.let { it.cumulativeM - m.cumulativeM },
        )
        // Recovery returns to the baseline the SPEED would have chosen anyway,
        // so the band auto-zoom picks up seamlessly whether it is on or off.
        val baseZoom = MapCamera.transitionZoom(ui.phase, ui.speedKmh, ui.settings.autoZoom)
        // Who owns the zoom right now — the same question maybeAutoZoom asks,
        // answered in the same place.
        //
        // It used to be re-derived here by comparing the live zoom against the
        // speed band, which cannot tell a driver's finger from this camera's own
        // ANTICIPATE (+0.85 at complexity 3) or FRAME (+1.05..+1.55) write. The
        // camera therefore read its own move as a takeover on the very next fix,
        // suspended itself, and never recovered — V7 Stage 3, D1, measured on
        // all five Doha drives.
        val driverZoomed =
            cameraGate.isDriverZoom(map.cameraPosition.zoom, System.currentTimeMillis())
        if (BuildConfig.DEBUG && driverZoomed) camDriverZoomFixes++
        when (val decision = maneuverCamera.update(input, baseZoom, driverZoomed)) {
            is ManeuverCamera.Decision.Noop -> if (BuildConfig.DEBUG) camLastDecision = "noop"
            is ManeuverCamera.Decision.Transition -> applyCamera(
                MapCamera.follow(ui.cam, ui.phase, here, lastBearing)
                    ?.copy(zoom = decision.targetZoom) ?: return,
                durationMs = decision.durationMs,
            ).also {
                if (BuildConfig.DEBUG) {
                    camWritesManeuver++
                    camLastDecision =
                        "%s->%.3f/%dms".format(decision.stage, decision.targetZoom,
                            decision.durationMs)
                }
            }
        }
    }

    /**
     * One line per GPS fix describing the camera, for the Stage 3 device run.
     *
     * Debug-only and side-effect free. Everything on it is measured AFTER both
     * zoom policies have had their turn on this fix, so the zoom/pitch are what
     * the renderer is actually holding and the write counters say which policy
     * moved it.
     */
    private fun camTrace() {
        if (!BuildConfig.DEBUG) return
        val map = maplibre ?: return
        val pos = map.cameraPosition
        val m = ui.currentManeuver
        val strip = ui.laneGuidance
        val band = if (autoZoomBand >= 0) autoZoomBand else -1
        Log.i(
            CAM_TAG,
            ("t=%d phase=%s mode=%s idx=%s type=%s dist=%.1f speed=%.1f " +
                "stage=%s dec=%s zoom=%.3f pitch=%.1f bearing=%.1f base=%.2f " +
                "band=%d laneStatus=%s laneSpec=%s lanes=%d strip=%d ded=%d " +
                "fwd=%s appr=%s lateral=%s feats=%d " +
                "wMan=%d wAuto=%d gateBlk=%d drvZoom=%d owned=%s off=%b active=%b").format(
                android.os.SystemClock.elapsedRealtime(),
                ui.phase, ui.cam.mode,
                m?.index?.toString() ?: "-", m?.type ?: "-",
                ui.distanceToManeuverM, (ui.speedKmh ?: 0).toDouble(),
                maneuverCamera.stage(), camLastDecision,
                pos.zoom, pos.tilt, pos.bearing,
                MapCamera.transitionZoom(ui.phase, ui.speedKmh, ui.settings.autoZoom),
                band,
                m?.laneStatus?.toString() ?: "-",
                m?.laneSpec ?: "-",
                m?.lanes?.size ?: 0, strip.size, strip.count { it.dedicated },
                // V7 Stage 4. `lateral` is what the vehicle was actually drawn
                // with, not what the model would say if asked — the two differ
                // whenever there is no route lock, and a drive has to be able
                // to tell "the model declined" from "nothing was drawn".
                // `feats` is how many features the route source carries, which
                // is the one number that says whether the ribbon was CUT for a
                // varying offset or left whole.
                m?.forwardLanes?.toString() ?: "-",
                m?.approachLanes?.toString() ?: "-",
                session.lastLateral?.let { "%.2f".format(it) } ?: "-",
                camRouteFeatures,
                camWritesManeuver, camWritesAutoZoom, camGateBlocked,
                camDriverZoomFixes,
                cameraGate.assertedZoom()?.let { "%.3f".format(it) } ?: "-",
                ui.offRoute, maneuverCamera.isActive(),
            )
        )
        camLastDecision = "-"
    }

    /**
     * One line per GPS fix describing the signal ahead (V7 Stage 5), for the
     * device run.
     *
     * Debug-only and side-effect free, like [camTrace]. The line exists so a
     * drive can PROVE the four claims this feature makes:
     *
     *  * a signal was matched (sig/along) and a pill was drawn (pills > 0);
     *  * the prediction is UNKNOWN with basis LOCATION on today's data — a
     *    phase claim would be visible here as a GREEN/RED and a TIMING basis,
     *    and its absence is the assertion, not a side effect;
     *  * the arrival window is sane (eta >= 0, unc >= the model's floor);
     *  * the camera and frame loop are untouched (the VectorCam/VectorFrames
     *    lines this run also captures stay exactly as Stage 4 measured them).
     */
    private fun signalTrace() {
        if (!BuildConfig.DEBUG) return
        val s = ui.signalAhead
        val along = (ui.routeDistanceM - ui.remainingM).coerceAtLeast(0.0)
        val sig = s?.let {
            val n = it.prediction
            "sig=${it.signalId} along=${(along + it.distanceM).toInt()}m " +
                "dist=${it.distanceM.toInt()}m eta=${it.arrival.etaS.toInt()}s " +
                "unc=${it.arrival.uncertaintyS.toInt()}s pred=${n.phase} " +
                "basis=${n.basis} conf=${String.format("%.2f", n.confidence)}"
        } ?: "sig=- along=${along.toInt()}m"
        Log.i(
            SIG_TAG,
            ("t=%d phase=%s off=%b pills=%d %s").format(
                android.os.SystemClock.elapsedRealtime(),
                ui.phase, ui.offRoute, lastSignalPills, sig,
            ),
        )
    }

    private fun cameraTrace() {
        if (!BuildConfig.DEBUG) return
        val cam = ui.cameraAhead
        val along = (ui.routeDistanceM - ui.remainingM).coerceAtLeast(0.0)
        val s = cam?.let {
            "cam=${it.cameraId} type=${it.type.name.lowercase()} " +
                "along=${(along + it.distanceM).toInt()}m " +
                "dist=${it.distanceM.toInt()}m maxspeed=${it.maxspeedTag ?: "-"} " +
                "voices=$cameraVoices"
        } ?: "cam=- along=${along.toInt()}m voices=$cameraVoices"
        Log.i(
            CAMERAS_TAG,
            ("t=%d phase=%s off=%b pills=%d %s").format(
                android.os.SystemClock.elapsedRealtime(),
                ui.phase, ui.offRoute, lastCameraPills, s,
            ),
        )
    }

    // ---- search / routing --------------------------------------------------

    /**
     * The driver typed.
     *
     * Search now runs AS YOU TYPE. Reported from the S24: *"no real time
     * search, I have to press enter to get a result."* Every consumer navigator
     * filters while you type, and requiring a submit makes finding a place a
     * three-action task (tap, type, submit) at exactly the moment the driver
     * wants it to be one.
     *
     * Debounced, and the previous request is CANCELLED rather than left to
     * land. Both matter:
     *
     * * without the debounce, "villaggio" is nine requests to the geocoder for
     *   eight prefixes nobody will read;
     * * without the cancellation, those nine replies race, and the list can
     *   settle on the results for "vil" because that response happened to
     *   arrive last. That is the classic autocomplete defect, and it looks like
     *   a broken search rather than a race.
     *
     * [SEARCH_DEBOUNCE_MS] is chosen against typing speed rather than network
     * latency: /search answers locally in single-digit milliseconds, so the
     * only thing being smoothed out is the keystroke rate.
     */
    private fun onQueryChanged(text: String) {
        ui = ui.copy(query = text)
        searchJob?.cancel()
        val q = text.trim()
        if (q.length < MIN_QUERY_CHARS) {
            // One letter matches most of Qatar. Clearing rather than searching
            // also restores the recents list, which is the more useful thing to
            // be looking at with an almost-empty box.
            // `error = null` here too: a query shortened below the search
            // threshold is the driver backing out of a failed attempt, and
            // leaving the banner up would put it under the recents list that
            // replaces the results. Same defect as `onOpenSearch`.
            ui = ui.copy(results = emptyList(), searchInFlight = false,
                         searched = false, error = null)
            return
        }
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            doSearch(q)
        }
    }

    /**
     * Run one query.
     *
     * Results are discarded if the text has moved on since the request was
     * sent — the cancellation above handles the common case, but a reply can
     * already be in flight when the driver types again.
     */
    private fun doSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        ui = ui.copy(searchInFlight = true, error = null)
        searchJob = lifecycleScope.launch {
            runCatching { api.search(q, lastFix) }
                .onSuccess { hits ->
                    if (ui.query.trim() != q) return@onSuccess
                    ui = ui.copy(results = hits, searchInFlight = false, searched = true)
                }
                .onFailure {
                    if (ui.query.trim() != q) return@onFailure
                    // A failed keystroke is not worth a red banner: the driver
                    // is mid-word and the next one will retry. Only a submitted
                    // search that fails is worth interrupting for.
                    //
                    // `searched = false`, NOT true — and this was a defect.
                    //
                    // `searched` is what licenses the panel to say "Nothing
                    // found for X", which is a claim about the INDEX. A request
                    // that failed supports no such claim; it says nothing at
                    // all about whether the place exists. Setting it here
                    // produced both messages at once — the no-results card
                    // drawn over the error banner, since the two occupy the
                    // same band — and the more authoritative-looking of the two
                    // was the wrong one. Photographed on the S24 during the
                    // adversarial run and pinned by a bounds assertion in
                    // NavUiTest.
                    //
                    // With this false the panel asserts nothing and the error
                    // banner is left to tell the truth on its own.
                    ui = ui.copy(searchInFlight = false, searched = false,
                                 error = "Search failed: ${it.message}")
                }
        }
    }

    private fun pickDestination(p: VectorApi.Place) {
        ui = ui.copy(searching = false, results = emptyList(), query = "")
        pickPoint(p.position, p.name)
    }

    /**
     * Choose a destination and ask for routes to it.
     *
     * @param remember whether to add it to the recents list. False when the
     *   destination CAME from that list: re-picking a recent should move it to
     *   the front, which `Recents.add` does, but doing it here as well would
     *   re-add a name the list may since have improved on.
     */
    private fun pickPoint(dest: LngLat, name: String, remember: Boolean = true) {
        val from = lastFix
        if (from == null) {
            ui = ui.copy(error = "Waiting for a GPS fix before routing")
            return
        }
        ui = ui.copy(
            destination = dest, destinationName = name,
            // Choosing anywhere at all ends the previous journey's summary.
            // Leaving it up while a new route is being planned would show two
            // journeys at once, and the card holds the CLOSE control for the
            // old one on top of the new destination's chip.
            arrival = null,
            // The previous destination's walk, shade and cooler offer are
            // about a place the driver has moved on from.
            journey = null, journeyBusy = true, journeyExpanded = false,
            coolerWalk = null, coolerPark = null, coolerShade = null,
            coolerOffer = dev.vector.geo.journey.CoolerRoute.Offer.none,
            coolerChosen = false,
            // Names the OPERATION, not the state of the machine. §23: "avoid
            // generic spinning indicators when a more contextual state is
            // possible" — Waze's equivalent reads "Finding optimal routes…"
            // over a dimmed results list rather than showing a bare spinner
            // (`v4-evidence/` filmstrip `fs-nav-a.png`, frames 8-21).
            status = "Finding routes…", busy = true, error = null, searching = false,
        )
        if (remember) {
            ui = ui.copy(recents = Recents.add(prefs, Recents.Entry(name, dest.lng, dest.lat)))
        }
        // A long-press has no name, and "Dropped pin" is what the driver sees in
        // the destination chip, in the recents list and at arrival. The
        // geocoder can say what is actually there, and the request is
        // fire-and-forget: a route must not wait on a label.
        if (name == DROPPED_PIN) resolveName(dest)

        // Whatever was being planned is not what the driver wants any more.
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            runCatching { api.navigateAlternatives(from, dest, wanted = 3) }
                .onSuccess { options ->
                    // Still the destination this reply is about? The cancel
                    // above closes the common case, but a reply can already be
                    // in flight when the driver picks again — and applying it
                    // then would draw one destination's route under another
                    // destination's name. See `routeJob`.
                    if (!routeReplyStillWanted(ui.destination, dest)) return@onSuccess
                    if (options.isEmpty()) {
                        ui = ui.copy(error = "Could not plan a route", status = "",
                                     busy = false, phase = Phase.EXPLORE)
                        return@onSuccess
                    }
                    ui = ui.copy(alternatives = options, chosenRoute = 0,
                                 status = "", busy = false)
                    showRoute(options[0], Phase.PREVIEW)
                    drawAlternatives(options, 0)
                    drawRouteLabels(options, 0)
                    // Frame ALL of them, not just the chosen one: the point of
                    // showing alternatives is that the driver can compare where
                    // they go, and a camera fitted to one of them can leave
                    // another off screen entirely.
                    fitRoute(options.flatMap { it.geometry })
                    // The drive is only half the answer. See composeJourney.
                    composeJourney(dest, name, options[0])
                }
                .onFailure {
                    // Same guard on the failure path. A failed request for a
                    // destination the driver has already replaced must not put
                    // a red banner over the route that is being planned now,
                    // nor drag the phase back to EXPLORE underneath it.
                    if (!routeReplyStillWanted(ui.destination, dest)) return@onFailure
                    ui = ui.copy(error = friendlyRouteError(it), status = "",
                                 busy = false, phase = Phase.EXPLORE)
                }
        }
    }

    /**
     * Work out the rest of the trip: where the car stops, and the walk from
     * there to the actual door.
     *
     * ## Why this runs on destination select rather than on arrival
     *
     * The question "how far will I be walking" is one a driver wants answered
     * BEFORE committing to a route, because it is sometimes the reason to
     * choose a different destination entirely — the mall entrance rather than
     * the mall. Vector already had the pieces (`/along` finds indexed parking,
     * `/foot` walks a pedestrian graph) and answered the question only after
     * the drive was over, on the arrival card.
     *
     * ## How the parking candidates are used
     *
     * Every indexed car park near the destination is walked, not just the
     * nearest one. Distance to the destination is a bad proxy for how long the
     * walk takes — a car park 80 m away on the far side of a compound wall is a
     * 600 m walk — so the candidates are ranked by the WALK, which is the thing
     * the driver actually experiences. That ranking is also what produces the
     * cooler alternative: see [coolerAlternative].
     *
     * The candidate pool is [PARKING_SUGGESTIONS] deep. Note that here the
     * constant is doing a slightly different job from the one its name
     * describes — nothing on the journey card is a list of three, it shows the
     * best walk and at most one cooler alternative. It is the same number on
     * purpose: "how many car parks will Vector consider" and "how many will it
     * offer" being two constants that could drift apart is how a driver ends up
     * with a card whose second option is not in the list the arrival card would
     * have shown them.
     *
     * Fire and forget, and never fatal. A failed parking lookup or a failed
     * walk leaves the destination, the route and the drive exactly as they
     * were; the journey card simply does not appear. Nothing here may stop a
     * driver getting somewhere.
     */
    private fun composeJourney(
        dest: LngLat,
        name: String,
        drive: RouteOption,
    ) {
        journeyJob?.cancel()
        journeyJob = lifecycleScope.launch {
            val driveLeg = dev.vector.geo.journey.DriveLeg(
                geometry = drive.geometry,
                distanceM = drive.distanceM,
                durationS = drive.durationS,
            )
            val parking = runCatching {
                api.nearby(dest, kinds = "parking", radiusM = 600, limit = PARKING_SUGGESTIONS)
            }.getOrDefault(emptyList())

            // Where the walk starts from. With no indexed parking the honest
            // answer is the end of the drive — the kerb the route actually
            // finishes at — and the card says that is what it is.
            val origins: List<Pair<LngLat, VectorApi.Place?>> =
                if (parking.isNotEmpty()) parking.map { it.position to it }
                else listOfNotNull(drive.geometry.lastOrNull()?.let { it to null })

            val walks = origins.mapNotNull { (from, place) ->
                runCatching { api.foot(from, dest) }.getOrNull()
                    ?.takeIf { it.segments.isNotEmpty() }
                    ?.let { Triple(from, place, it) }
            }
            if (!routeReplyStillWanted(ui.destination, dest)) return@launch
            if (walks.isEmpty()) {
                ui = ui.copy(journeyBusy = false)
                return@launch
            }

            // The default is the SHORTEST WALK, not the nearest car park.
            val best = walks.minByOrNull { it.third.durationS }!!
            val atMs = ui.journeyTimeMs ?: System.currentTimeMillis()
            val shade = dev.vector.geo.journey.ShadeAnnotator.shade(best.third, atMs)

            val journey = dev.vector.geo.journey.Journey(
                destinationName = name,
                destination = dest,
                drive = driveLeg,
                park = best.second?.let {
                    dev.vector.geo.journey.ParkSpot(it.position, it.name)
                },
                walk = best.third,
                walkShade = shade,
                parkingKnown = best.second != null,
                annotations = dev.vector.geo.journey.ShadeAnnotator.annotate(best.third, atMs),
            )
            ui = ui.copy(journey = journey, journeyBusy = false)
            val (cooler, offer) = coolerAlternative(
                walks.map { (_, place, leg) -> place to leg },
                best.third, shade, atMs,
            )
            ui = ui.copy(
                coolerWalk = cooler?.second,
                coolerPark = cooler?.first?.let {
                    dev.vector.geo.journey.ParkSpot(it.position, it.name)
                },
                coolerShade = cooler?.second?.let {
                    dev.vector.geo.journey.ShadeAnnotator.shade(it, atMs)
                },
                coolerOffer = offer,
            )
            drawWalk()
        }
    }

    /**
     * The coolest of the candidate walks, if it is worth offering.
     *
     * ## An honest limitation
     *
     * The V7 plan describes the cooler route as re-routing the walk leg with an
     * exposure penalty. That needs a shade-aware `/foot`, which is server work
     * neither Phase 3 nor Phase 4 authorised — and doing it on the client would
     * mean shipping the pedestrian graph to the phone.
     *
     * So this offers the coolest walk among the ones the destination's own
     * parking options already produce. It is a real choice between real routes
     * over real footways, and it is not the same thing: it changes where you
     * park, not the path you take from a fixed car park. The copy says "cooler
     * route" and the card shows what it costs in time, so nothing is claimed
     * that is not delivered — but a genuinely shade-aware walk is still owed.
     */
    private fun coolerAlternative(
        walks: List<Pair<VectorApi.Place?, dev.vector.geo.journey.WalkLeg>>,
        chosen: dev.vector.geo.journey.WalkLeg,
        chosenShade: dev.vector.geo.sun.RouteShade,
        atMs: Long,
    ): Pair<
        Pair<VectorApi.Place?, dev.vector.geo.journey.WalkLeg>?,
        dev.vector.geo.journey.CoolerRoute.Offer,
    > {
        val others = walks.filter { it.second !== chosen }
        var best: Pair<VectorApi.Place?, dev.vector.geo.journey.WalkLeg>? = null
        var bestOffer = dev.vector.geo.journey.CoolerRoute.Offer.none
        for (candidate in others) {
            val shade = dev.vector.geo.journey.ShadeAnnotator.shade(candidate.second, atMs)
            val offer = dev.vector.geo.journey.CoolerRoute.consider(
                direct = chosen, directShade = chosenShade,
                alternative = candidate.second, alternativeShade = shade,
            )
            if (offer.available && offer.deltaPoints > bestOffer.deltaPoints) {
                best = candidate
                bestOffer = offer
            }
        }
        return best to bestOffer
    }

    /**
     * Draw the walk, amber where the model puts it in the sun.
     *
     * Rebuilt from whichever walk is currently chosen and whatever instant the
     * sun slider is on, so scrubbing the slider re-colours the line without
     * another network call — the shade model is a pure function and the walk
     * geometry has not changed.
     */
    private fun drawWalk() {
        val journey = ui.journey
        val walk = ui.activeWalk
        // The walk leg is Pro, and a line on the map is the feature as surely
        // as the card is. `offersPro` is true only for a free driver in a build
        // that sells something, so an unconfigured build still draws it.
        if (ui.offersPro || journey == null || walk == null) {
            source(SRC_WALK)?.setGeoJson(EMPTY_FC)
            return
        }
        val atMs = ui.journeyTimeMs ?: System.currentTimeMillis()
        val shade = dev.vector.geo.journey.ShadeAnnotator.shade(walk, atMs)
        source(SRC_WALK)?.setGeoJson(VectorStyle.walkGeoJson(walk, shade))
    }

    /** Open or close the journey legs. */
    private fun toggleJourney() {
        ui = ui.copy(journeyExpanded = !ui.journeyExpanded)
    }

    /**
     * Move the sun.
     *
     * Re-models the shade for the walk on screen and redraws it. No network:
     * the estimate is a pure function of (geometry, instant), which is the
     * whole reason the calculation lives on the device.
     */
    private fun scrubSunTime(atMs: Long?) {
        ui = ui.copy(journeyTimeMs = atMs)
        val journey = ui.journey ?: return
        val walk = journey.walk ?: return
        val instant = atMs ?: System.currentTimeMillis()
        val shade = dev.vector.geo.journey.ShadeAnnotator.shade(walk, instant)
        ui = ui.copy(
            journey = journey.copy(
                walkShade = shade,
                annotations = dev.vector.geo.journey.ShadeAnnotator.annotate(walk, instant),
            ),
        )
        // The offer is time-dependent too: a route that is cooler at 16:00 is
        // not necessarily cooler at noon, and an offer left over from another
        // hour would be a claim about a sun that is no longer there.
        val cooler = ui.coolerWalk
        if (cooler != null) {
            val coolerShade = dev.vector.geo.journey.ShadeAnnotator.shade(cooler, instant)
            ui = ui.copy(
                coolerShade = coolerShade,
                coolerOffer = dev.vector.geo.journey.CoolerRoute.consider(
                    direct = walk, directShade = shade,
                    alternative = cooler, alternativeShade = coolerShade,
                ),
            )
        }
        drawWalk()
    }

    /**
     * Switch the walk leg between the direct and the cooler route.
     *
     * Gated on Pro, and the gate is the contextual one: a free driver gets the
     * paywall once per session and the walk stays as it was. See
     * `pro/ProAccess.kt` for why "once" is enforced by an object rather than a
     * boolean at this call site.
     */
    private fun toggleCooler() {
        if (!ui.coolerOffer.available) return
        if (!dev.vector.android.pro.ProAccess.hasPro(ui.pro)) {
            // Only offer what can be completed. Opening a paywall while the
            // store has nothing to sell is the "paywall nobody can unlock"
            // defect with a network outage for a cause; the walk simply stays
            // as it was and the map shows nothing it cannot back up.
            if (ui.proSellable && pro.gate.shouldAutoPresent()) {
                pro.gate.markPresented()
                ui = ui.copy(showPaywall = true, proNotice = null)
                lifecycleScope.launch { pro.loadOffer() }
            }
            return
        }
        ui = ui.copy(coolerChosen = !ui.coolerChosen)
        drawWalk()
    }

    /** Ask the geocoder what is at a dropped pin, and rename it if it knows. */
    private fun resolveName(at: LngLat) {
        lifecycleScope.launch {
            // Bounded and kind-aware — see `PinName`. This used to take
            // `api.reverse(at)`'s first row, whatever it was and however far
            // away it was, which is how a pin in the desert acquired the name
            // of a village 3.5 km off and a pin on a street acquired the name
            // of the shop beside it.
            val name = PinName.choose(api.reverseNear(at), DROPPED_PIN)
            // Only if the driver has not moved on to another destination.
            if (ui.destination != at) return@launch
            if (name == DROPPED_PIN) return@launch
            ui = ui.copy(
                destinationName = name,
                recents = Recents.add(prefs, Recents.Entry(name, at.lng, at.lat)),
            )
        }
    }

    /** The driver picked one of the offered routes. */
    private fun chooseRoute(index: Int) {
        val option = ui.alternatives.getOrNull(index) ?: return
        ui = ui.copy(chosenRoute = index)
        showRoute(option, ui.phase)
        drawAlternatives(ui.alternatives, index)
        drawRouteLabels(ui.alternatives, index)
        // Deliberately NOT re-fitting the camera. The driver is comparing lines
        // they can now all see, and moving the map under them on every tap
        // makes the comparison impossible — the previous version re-fitted to
        // the newly chosen route, so each tap threw away the view they were
        // using to decide.
    }

    /**
     * Make one route the live one.
     *
     * The tracker, the drawn line and the HUD numbers are all set from the same
     * [RouteOption] here, in one place. Setting them separately is how a client
     * ends up drawing one route and navigating another — which is precisely
     * what choosing an alternative did before `/navigate?alternatives=1`
     * existed, because the alternative had no steps to navigate.
     */
    private fun showRoute(option: RouteOption, phase: Phase) {
        tracker.setRoute(option.geometry)
        announcer.reset()
        maneuverCamera.reset()
        drawRoute(option.geometry, option.maneuvers, option.signals, option.cameras)
        ui = ui.copy(
            phase = phase,
            routeDistanceM = option.distanceM,
            routeDurationS = option.durationS,
            maneuvers = option.maneuvers,
            remainingM = option.distanceM,
            remainingS = option.durationS,
            // Only worth mentioning when it is far enough to notice.
            snapWarningM = option.snapMaxM.takeIf { it >= 150.0 },
            routeSnapMaxM = option.snapMaxM,
            // The ORIGIN's snap, separately. NavSession compares THIS against
            // the off-route threshold to decide whether a reroute could help,
            // because the destination's distance from the road network says
            // nothing about the driver's.
            routeOriginSnapM = option.originSnapM,
        )
        // A destination chosen in the car skips the preview step: the driver
        // picked it on a car screen, which is already the decision to go. The
        // flag is cleared first so a route that fails to start cannot leave it
        // armed for the next, unrelated, route.
        if (phase == Phase.PREVIEW && carAutoStart) {
            carAutoStart = false
            startNavigation()
        }
    }

    private fun friendlyRouteError(t: Throwable): String = ApiErrorText.friendly(t)

    private fun startNavigation() {
        // Persist the journey BEFORE anything else, so a process death in the
        // first second of a drive is survivable too.
        ui.destination?.let {
            Journey.save(prefs, ui.destinationName, it.lng, it.lat, System.currentTimeMillis())
        }
        navStartedAtMs = System.currentTimeMillis()
        navStartedAt = lastFix
        driveTrack.clear()
        trackSpacingM = Drives.TRACK_MIN_SPACING_M
        lastFix?.let { driveTrack.add(it) }
        session.reset()
        // The route was planned from where the driver is, so its origin snap is
        // current evidence at the moment navigation starts. This is what keeps
        // the S24 defect closed — a driver starting from 80 m off the network
        // does not get a reroute request every eight seconds — now that the
        // test no longer reads the destination's snap distance.
        session.noteRoutePlannedFrom(lastFix, ui.routeOriginSnapM)
        val cam = MapCamera.onRecenter(ui.cam)
        ui = ui.copy(phase = Phase.NAVIGATING, cam = cam, showSteps = false,
                     alternatives = emptyList())
        // The choice has been made; leaving the rejected routes on the map
        // during navigation would be three blue-grey lines competing with the
        // one the driver is following.
        source(SRC_ROUTE_ALT)?.setGeoJson(EMPTY_FC)
        source(SRC_ROUTE_LABELS)?.setGeoJson(EMPTY_FC)
        // The phase decides which endpoints are drawn, and it has just
        // changed — see drawEndpoints.
        drawEndpoints(lastRouteCoords)
        // The one place other than recenter that asserts a zoom.
        applyLookAhead()
        // The one long camera move in the product.
        //
        // Starting navigation crosses scales: from a route overview framing 12
        // km of Doha to a street-level driving camera, with a rotation to
        // heading-up and a tilt on the way. Vector did that in 450 ms, which
        // reads as a glitch rather than as travel — measured against Google
        // Maps, which takes ~2100 ms for the same move
        // (`v4-evidence/` filmstrip `fs-g-navstart.png`, frames 4-24).
        //
        // The CHROME does not wait for it: `ui` is already NAVIGATING above,
        // so the maneuver banner is on screen and readable while the map is
        // still flying. That split — instant instrument, travelling map — is
        // what both reference products do and it is the whole point of having
        // separate motion bands.
        lastFix?.let {
            applyCamera(
                MapCamera.recenter(
                    cam, Phase.NAVIGATING, it, lastBearing,
                    zoom = MapCamera.transitionZoom(
                        Phase.NAVIGATING, ui.speedKmh, ui.settings.autoZoom),
                ),
                durationMs = VectorTokens.Motion.FLIGHT,
            )
        }
        autoZoomBand = ui.speedKmh?.let { MapCamera.rawBand(it) } ?: -1
        voice.say("Starting navigation", dev.vector.geo.SpeechArbiter.Kind.COURTESY)
    }

    /**
     * Write the drive that has just ended into the journey history.
     *
     * Called from [endJourney], before the teardown, because every number it
     * needs is about to be cleared.
     *
     * ## What is recorded, and how honest each field is
     *
     * * **distance** — how far along the route the drive actually got, which is
     *   `routeDistanceM - remainingM` rather than the route's planned length.
     *   On an arrival those are the same number to within the arrival radius;
     *   on an abandoned drive they are not, and reporting the planned distance
     *   there would claim a drive that did not happen. Where the route was
     *   replaced by a reroute mid-drive this describes the LAST route, which is
     *   the closest thing to "the road actually driven" the app measures.
     * * **duration** — wall clock from the tap on Start. Includes red lights
     *   and a stop for petrol, because that is what the driver's own watch said.
     * * **planned** — the router's prediction for the route being driven at the
     *   end, or null when it never gave one. Null rather than zero: see
     *   [Drives.Drive.verdict], which withholds a comparison rather than
     *   reporting the whole drive as an overrun against a prediction of nothing.
     *
     * A drive with no destination coordinate is not recorded. That is not a
     * reachable state from the UI — NAVIGATING requires a route, which requires
     * a destination — but a record whose coordinates are unknown could not be
     * re-driven from the list, and a row that cannot be tapped is the dead UI
     * §6 rules out.
     */
    private fun recordDrive(arrived: Boolean) {
        // Two conditions, not one, and they answer §4's "what happens if the
        // event occurs twice".
        //
        // `navStartedAtMs` alone is not enough: it used to be cleared only on
        // the ARRIVAL path, so after a driver abandoned a drive it stayed set,
        // and the next tap on Cancel from PREVIEW — a journey that was never
        // driven — would have written a phantom drive with the old start time
        // and the new destination. It is cleared here now, for both exits.
        //
        // The phase check is the direct statement of the rule: a drive is a
        // journey that entered NAVIGATING. Backing out of a route preview is
        // not a drive, and the destination is already in Recents.
        if (ui.phase != Phase.NAVIGATING) return
        val startedAtMs = navStartedAtMs ?: return
        navStartedAtMs = null
        val dest = ui.destination ?: return
        val now = System.currentTimeMillis()
        val covered = (ui.routeDistanceM - ui.remainingM).coerceIn(0.0, ui.routeDistanceM)
        val drive = Drives.Drive(
            destination = ui.destinationName.ifBlank { "Unnamed destination" },
            destLng = dest.lng,
            destLat = dest.lat,
            startLng = navStartedAt?.lng,
            startLat = navStartedAt?.lat,
            startedAtMs = startedAtMs,
            endedAtMs = now,
            distanceM = covered,
            durationS = (now - startedAtMs) / 1000.0,
            plannedS = ui.routeDurationS.takeIf { it > 0.0 },
            completed = arrived,
            track = driveTrack.toList(),
        )
        ui = ui.copy(drives = Drives.add(prefs, drive))
        Log.i(NAV_TAG, "drive recorded arrived=$arrived " +
            "dist=${covered.toInt()}m dur=${drive.durationS.toInt()}s")
        navStartedAt = null
        driveTrack.clear()
    }


    /**
     * The driver tapped out of a route, or one was replaced by a new choice.
     *
     * The UI's own exit control. Records the drive as **abandoned** when there
     * was a drive to abandon — see [endJourney].
     */
    private fun cancelRoute() = endJourney(arrived = false)

    /**
     * Tear the journey down, and remember that it happened.
     *
     * ## Why the two exits are one function with a flag
     *
     * `arrive()` used to call `cancelRoute()` for the teardown, which is right
     * — the teardown is identical and duplicating it is how one of the two
     * paths forgets to close the probe trip. But the journey HISTORY has to
     * tell them apart: a drive that ended by arriving and a drive the driver
     * gave up on are different facts, and §13.D asks for the completion state
     * explicitly. Recording inside `cancelRoute` would have written an
     * abandoned drive and then a completed one for every arrival.
     *
     * So the outcome is a parameter, both exits go through here, and there is
     * exactly one place that writes a drive.
     *
     * ## What counts as a drive
     *
     * Only a journey that actually entered NAVIGATING, which is what
     * [navStartedAtMs] being non-null means. Choosing a destination, looking at
     * the route and backing out is not a drive and must not appear in a list of
     * them — the destination is already in [Recents], which is the store for
     * "places I was interested in".
     */
    private fun endJourney(arrived: Boolean) {
        recordDrive(arrived)
        // The journey is over, however it ended — arrival or a driver tapping
        // out. Both reach here, and both must forget it: a resumed journey the
        // driver has already completed is worse than no resume at all.
        Journey.clear(prefs)
        // Close the probe trip explicitly so the server releases its pending
        // tail now rather than waiting out the idle timeout.
        if (ui.contributing) {
            probes.endTrip()?.let { b ->
                lifecycleScope.launch { runCatching { api.postProbes(b.trip, b.points, true) } }
            }
        }
        tracker.clearRoute()
        announcer.reset()
        maneuverCamera.reset()
        voice.stop()
        // With the route, not after it: a profile that outlived its journey
        // would offset the next one's puck by lane geometry that no longer
        // describes the road under it.
        session.setLanes(null)
        // Same rule for the signal profile: signals belong to a route, and a
        // profile that outlived its journey would announce junctions from a
        // road the driver is no longer on.
        session.setSignals(null)
        lastSignalProfile = null
        session.setCameras(null)
        lastCameraProfile = null
        lastRouteCoords = emptyList()
        lastRouteSignals = emptyList()
        lastRouteCameras = emptyList()
        source(SRC_ROUTE)?.setGeoJson(EMPTY_FC)
        source(SRC_CHEVRONS)?.setGeoJson(EMPTY_FC)
        source(SRC_CALLOUTS)?.setGeoJson(EMPTY_FC)
        speedPill.clear()
        source(SRC_ROUTE_ALT)?.setGeoJson(EMPTY_FC)
        source(SRC_ENDPOINTS)?.setGeoJson(EMPTY_FC)
        source(SRC_ROUTE_LABELS)?.setGeoJson(EMPTY_FC)
        // The walk goes with the drive it was the last mile of.
        journeyJob?.cancel()
        source(SRC_WALK)?.setGeoJson(EMPTY_FC)
        // And the walking JOURNEY, which is a different thing from the walk
        // overlay above: this is the route someone was navigating on foot.
        // Torn down here rather than in a walking-specific exit so that both
        // ways out — arriving and tapping out — go through one teardown, the
        // argument this function's KDoc makes about the two driving exits.
        walkJob?.cancel()
        walkRerouteJob?.cancel()
        walkSession = null
        source(SRC_WALK_ROUTE)?.setGeoJson(EMPTY_FC)
        // The walk's signal markers go with the walk they describe, and
        // SRC_CALLOUTS was emptied above for the same reason. Clearing the
        // profile too means a later restyle cannot resurrect them.
        lastWalkSignalProfile = null
        lastSpeedProbeAt = null
        lastSpeedProbeAtMs = 0L
        if (!ui.settings.trafficInExplore) clearTraffic()
        // Settings, camera preferences and recents survive a journey ending;
        // everything about the journey does not. Rebuilding UiState from
        // defaults and then re-seeding those three is what keeps a new journey
        // from inheriting the last one's route, and it is also why they are not
        // loose flags any more.
        ui = UiState(
            phase = Phase.EXPLORE,
            settings = ui.settings,
            cam = CameraState(
                orientation = ui.settings.orientation,
                perspective = ui.settings.perspective,
            ),
            recents = ui.recents,
            places = ui.places,
            // The journey log outlives the journey, obviously — but it is
            // re-seeded here for the same reason recents and places are: this
            // rebuild starts from UiState() and anything not named is
            // discarded, and `recordDrive` has just added an entry to it.
            drives = ui.drives,
            myLocation = lastFix,
            // Positioning is a property of the device, not of the journey. A
            // driver who ends a route inside a car park should not be told the
            // signal is fine until the next fix disagrees.
            gps = ui.gps,
            gpsAccuracyM = ui.gpsAccuracyM,
        )
        // The vehicle goes back to the middle of the screen: look-ahead is a
        // navigation behaviour, and in EXPLORE the driver is reading a map
        // rather than travelling along it.
        //
        // AFTER the state change, not before. It was before, and
        // `applyLookAhead` reads `ui.phase` — so it computed the look-ahead
        // for the phase being left and the map kept its driving padding after
        // arrival, leaving the vehicle two thirds down an EXPLORE map.
        applyLookAhead()

        // The safe transition point (V7.7). A release published during the
        // journey that just ended has been held back rather than rebuilding
        // the style under a driver being guided; guidance is over, so it can
        // be adopted now.
        //
        // AFTER the phase change for the same reason `applyLookAhead` is:
        // this reads `ui.phase` to decide whether guidance is still running,
        // and called above the rebuild it would read NAVIGATING and defer the
        // release it was called to apply.
        applyPendingRelease()
    }

    private fun reroute() {
        val from = lastFix ?: return
        val dest = ui.destination ?: return
        // No cooldown here: NavSession owns it, and having two would mean two
        // places to get it wrong.
        //
        // A reroute asks for ONE route, not alternatives: the driver is mid
        // journey and is not choosing, and three searches would cost three
        // times as long at the moment latency matters most.
        lifecycleScope.launch {
            runCatching { api.navigate(from, dest) }
                .onSuccess { r ->
                    // Is there still a journey for this route to belong to?
                    //
                    // A reroute takes as long as the router takes, and the
                    // driver can arrive, or tap out, while it is in flight —
                    // §3.E's "navigation -> arrival" and "navigation -> reroute"
                    // transitions crossing each other. Without this guard the
                    // reply RESURRECTED the journey it belonged to: it called
                    // `tracker.setRoute` and `drawRoute` and repopulated the
                    // maneuvers, so the app sat in EXPLORE with a route drawn,
                    // a tracker holding it, an ETA counting down and no way to
                    // exit, because the exit control belongs to NAVIGATING.
                    //
                    // The destination is checked as well as the phase, so a
                    // reroute cannot land on the next journey either.
                    if (!rerouteReplyStillWanted(ui.phase, ui.destination, dest)) {
                        Log.i(NAV_TAG, "reroute reply discarded: journey moved on")
                        return@onSuccess
                    }
                    tracker.setRoute(r.geometry)
                    // The origin snap of the route that just landed is the app's
                    // only measurement of whether the road network can
                    // represent where the driver is NOW. See
                    // NavSession.rerouteCouldHelp.
                    session.noteRoutePlannedFrom(from, r.originSnapM)
                    // NOT session.reset(): the route is replaced, the JOURNEY is
                    // not. reset() clears the reroute cooldown, so a driver the
                    // router cannot match would deviate/reroute at the GPS rate
                    // — the storm the cooldown exists to stop, at the moment it
                    // is most needed.
                    session.onRouteReplaced()
                    // The maneuver camera's latches are keyed to the OLD
                    // route's indexes; a rerouted route renumbers from 0, so
                    // everything must clear (V7 Stage 2).
                    maneuverCamera.reset()
                    val rerouted = r.steps.mapIndexed { i, s ->
                        Maneuver(i, s.type, s.instruction, s.distanceM, s.cumulativeM,
                             s.turnLanes, s.exitRef, s.destination, s.road,
                             s.approachLanes, s.laneData)
                    }
                    // The route the driver is now on is a different road ahead,
                    // so a limit found for the old one is not a fact about this
                    // one. Cleared rather than carried over.
                    speedPill.clear()
                    drawRoute(r.geometry, rerouted, r.signals, r.cameras)
                    ui = ui.copy(
                        rerouting = false, offRoute = false,
                        routeDistanceM = r.distanceM, routeDurationS = r.durationS,
                        routeSnapMaxM = r.snapMaxM,
                        routeOriginSnapM = r.originSnapM,
                        maneuvers = rerouted,
                    )
                    Log.i(NAV_TAG, "reroute applied dist=${r.distanceM.toInt()}m " +
                        "steps=${r.steps.size} originSnap=${r.originSnapM.toInt()}m")
                    voice.say("Route updated", dev.vector.geo.SpeechArbiter.Kind.COURTESY)
                }
                .onFailure {
                    Log.i(NAV_TAG, "reroute failed")
                    // Same reasoning as the success path: a failure that
                    // arrives after the driver has arrived would raise a red
                    // banner about a journey that ended well.
                    if (!rerouteReplyStillWanted(ui.phase, ui.destination, dest)) return@onFailure
                    ui = ui.copy(rerouting = false, error = friendlyRouteError(it))
                }
        }
    }

    // ---- location ----------------------------------------------------------

    /**
     * Serve the car's requests for the rest of the Activity's life.
     *
     * Each one is expressed in terms of a control the phone UI already has:
     * NavigateTo is [pickPoint] plus the Start the driver would have tapped,
     * Stop is [cancelRoute]. Nothing here is a second implementation of
     * anything — that is the whole point of routing car input back through the
     * Activity rather than letting the car screens act on state directly.
     */
    private suspend fun collectCarCommands() {
        NavBridge.commands.collect { command ->
            when (command) {
                is NavBridge.Command.NavigateTo -> {
                    // The car list rows come from Recents and Places, so the
                    // destination is already in the history it was read from;
                    // re-adding it would reorder the list under the driver.
                    carAutoStart = true
                    ui = ui.copy(showSettings = false, showSteps = false)
                    pickPoint(command.dest, command.name, remember = false)
                }
                NavBridge.Command.Stop -> {
                    carAutoStart = false
                    if (ui.phase != Phase.EXPLORE) cancelRoute()
                }
                NavBridge.Command.ToggleVoice -> {
                    // Back to FULL rather than to whatever it was before:
                    // remembering a previous mode across a mute would mean
                    // the same tap gives different results on different
                    // drives, and unmuting to "alerts only" is silence where
                    // the driver asked for a voice.
                    val next = if (ui.voice == VoiceMode.OFF) VoiceMode.FULL else VoiceMode.OFF
                    updateSettings(ui.settings.copy(voice = next))
                }
            }
        }
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) startLocation()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        // The Task's failure, not just the callback's silence.
        //
        // `requestLocationUpdates` returns a Task and this call DISCARDED it.
        // When the registration fails — Google Play services unavailable or too
        // old, the device's location settings not satisfied, the request refused
        // — no callback ever fires, and the app's only expression of that was
        // "Searching for GPS" with no time limit and a search box that refused
        // every destination. Reported from the S24 as "always showing searching
        // for GPS".
        //
        // The unbounded wait is fixed separately and at the right layer
        // (GpsHealth.UNAVAILABLE, after GPS_ACQUIRE_PATIENCE_MS). This is the
        // other half: when the platform tells us the request will never be
        // serviced, say what it said instead of waiting for a timeout to guess.
        fused.requestLocationUpdates(req, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val here = LngLat(loc.longitude, loc.latitude)
                // Before ANYTHING else, including probe collection: is this a
                // position a car could be at? A reflected fix a few hundred
                // metres away used to be believed, and one of them cost an
                // entire journey — see dev.vector.geo.FixGate.
                if (!session.acceptFix(here, System.currentTimeMillis())) {
                    Log.i(FRAME_TAG, "dropped an implausible fix (${session.droppedFixes} total)")
                    return
                }
                val firstFix = lastFix == null
                lastFix = here
                // `hasBearing()` for exactly the reason `hasSpeed()` and
                // `hasAccuracy()` are guarded below: the field is a float that
                // reads **0.0** when unset, and 0.0 is not "unknown" to a
                // camera -- it is DUE NORTH. A receiver reports no bearing
                // below walking pace, on a network fix, and for the first
                // fixes after a signal gap, all of which happen constantly in
                // a car (lights, car parks, tunnels, underpasses). Every
                // transition that reads `lastBearing` -- recenter (965),
                // auto-resume (956), auto-zoom (1133), nav start (1463),
                // first fix (1807) -- would swing the map to north and let the
                // frame loop rotate it back over ~0.7 s. That swing-and-return
                // is the "camera instability" reported after the 2026-09-14
                // drives. Keeping the last KNOWN-GOOD bearing is both correct
                // and what the driver sees: a car that is barely moving is
                // still pointing where it last pointed.
                if (loc.hasBearing()) lastBearing = loc.bearing.toDouble()
                // Published so the results list can say how far away each hit
                // is. Cheap: the state object is rebuilt on every fix anyway.
                if (ui.myLocation != here) ui = ui.copy(myLocation = here)
                // Keep the shape of the drive, for the history. See
                // [appendTrackPoint] and `Drives.Drive.track`.
                appendTrackPoint(here)
                // Native gives real speed/bearing fields; the WebView shim did not.
                val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
                val onFoot = walkSession != null
                // A WALKER'S POSITION IS NOT A TRAFFIC PROBE.
                //
                // `collectProbe` feeds the learned road-speed layer, which is
                // the one part of this system that is supposed to only ever
                // see measurements of VEHICLES. A person walking down a
                // pavement beside a 100 km/h expressway would contribute
                // 1.35 m/s samples against that road, and the layer has no way
                // to tell them apart afterwards. So walking fixes are never
                // contributed — a correctness rule about the data, not a
                // battery saving.
                if (!onFoot) collectProbe(loc, here, speed)
                // On foot the raw fix is the only position there is. In a car
                // the lookup runs after `session.onFix` below, from the point
                // the fix was MATCHED to on the route — see
                // `NavSession.roadProbePoint` for the wrong limits the raw fix
                // produced on the 2026-09-24 drive.
                if (onFoot) refreshRoadHere(here)
                // Both are facts about a CARRIAGEWAY. A posted limit does not
                // apply to someone on foot, and the walking HUD draws neither,
                // so requesting them on a walk would be spending a driver's
                // battery on numbers nothing renders.
                if (!onFoot) {
                    refreshSpeedAhead()
                    refreshTraffic()
                }

                val walking = walkSession
                if (walking != null) {
                    // The walking loop, which shares the camera AUTHORITY with
                    // the car loop and nothing else: it is handed `ui.cam` and
                    // returns decisions, exactly as NavSession does, and
                    // `maybeResumeFollow` below is the same unmodified
                    // hand-back for both modes.
                    val wr = walking.onFix(
                        here, System.currentTimeMillis(), ui.cam, ui.phase,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        // One voice setting governs both modes. See
                        // WalkVoice.Event.isManeuver for the split.
                        speaksManeuvers = ui.voice.speaksManeuvers,
                        speaksAlerts = ui.voice.speaksAlerts,
                    )
                    ui = ui.copy(walk = wr.state)
                    performWalk(wr.actions)
                    maybeResumeFollow()
                    // Deliberately NOT maybeManeuverCamera / maybeAutoZoom:
                    // both are car camera policies (a speed ladder and a
                    // maneuver stage machine sized for 110 km/h), and the
                    // walking camera is WalkCameraPolicy's. Running both would
                    // be the second camera authority 4C.2 exists to prevent.
                } else {
                val r = session.onFix(
                    // `lastBearing`, not `loc.bearing`: see the hasBearing()
                    // guard above. NavSession feeds this straight to the puck
                    // and the camera when off-route or route-less.
                    ui, here, speed, lastBearing,
                    System.nanoTime(), System.currentTimeMillis(),
                    // `hasAccuracy()` for the same reason as `hasSpeed()`: the
                    // field is a float that reads 0.0 when unset, and 0 m of
                    // error is the one value it certainly does not mean.
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                )
                ui = r.ui
                perform(r.actions)
                refreshRoadHere(session.roadProbePoint(here))
                maybeResumeFollow()
                // Before auto-zoom: the stage it enters this fix must already
                // be marked active so the band change stands down (see
                // maybeAutoZoom).
                maybeManeuverCamera()
                maybeAutoZoom()
                camTrace()
                signalTrace()
                cameraTrace()
                maybeRedrawCallouts()
                }

                // The first fix is the only one worth a zoom: the map opened on
                // a hardcoded Doha centre at z13, and a driver who is elsewhere
                // in Qatar would otherwise have to find themselves by hand.
                if (firstFix && ui.phase == Phase.EXPLORE && ui.cam.mode == CameraMode.FOLLOW) {
                    applyCamera(
                        MapCamera.recenter(
                            ui.cam, ui.phase, here, lastBearing,
                            zoom = MapCamera.transitionZoom(
                                ui.phase, ui.speedKmh, ui.settings.autoZoom),
                        ),
                        durationMs = VectorTokens.Motion.FLIGHT,
                    )
                }
            }
        }, mainLooper)
            .addOnFailureListener {
                Log.w(TAG, "location updates refused: ${it.message}")
                ui = ui.copy(
                    error = "Vector cannot read this device's location " +
                        "(${it.message ?: "the request was refused"})",
                )
            }
    }

    /**
     * Advance the puck every frame the display actually draws.
     *
     * Choreographer, not a fixed timer: it fires in step with the refresh rate,
     * so on a 120 Hz panel the vehicle moves 120 times a second in small steps.
     * This is what replaces the web client's 600 ms easeTo-per-fix, which
     * measured ~13 fps and spent ~95% of its per-fix cost re-rendering under
     * overlapping animations.
     */
    private fun startFrameLoop() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // The map's native objects are gone; there is nothing to draw
                // and nothing to draw it onto. Return WITHOUT re-posting, so
                // the loop ends here rather than running for the lifetime of
                // the process. See `mapAlive`.
                if (!mapAlive) return
                // One `add` per frame, before anything else, so the
                // measurement is of the display's cadence rather than of how
                // long Vector's own work took inside the callback.
                frames.onFrame(frameTimeNanos)
                // Wall clock as well as the frame clock: the fixes are stamped
                // with one and the frames with the other, and GPS staleness is
                // the difference between them. See NavSession.onFrame.
                val r = session.onFrame(ui, frameTimeNanos, System.currentTimeMillis())
                // The state is adopted whenever it CHANGED, not only when there
                // is something to draw. `if (r.actions.isNotEmpty())` was the
                // condition, and the two are not the same thing: the one frame
                // that has a new fact and no action is the frame that notices
                // the fixes have stopped arriving. So the GPS-lost state was
                // computed correctly, returned correctly, and thrown away
                // sixty times a second.
                //
                // Reference identity, not equality: NavSession returns the
                // caller's own instance when nothing changed, so this is a
                // pointer compare per frame rather than a deep compare of a
                // thirty-field data class.
                if (r.ui !== ui) ui = r.ui
                if (r.actions.isNotEmpty()) perform(r.actions)
                reportFramesOccasionally()
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    /**
     * Log a frame-timing summary every [FRAME_REPORT_MS].
     *
     * To logcat rather than to the screen: it is evidence for
     * `scripts/verify_on_device.sh` and for a validation document, not
     * something a driver should be shown. `Log.i` with a fixed tag so the
     * script can grep it, and the line's shape is a contract —
     * `FrameMeter.Report.oneLine`.
     *
     * Only while NAVIGATING. Frame timings from a stationary map are the
     * easiest possible case and quoting them would be the unevidenced
     * performance claim §29 warns about; the number that matters is the one
     * from a moving vehicle with a route drawn, a camera following and the HUD
     * recomposing.
     */
    private fun reportFramesOccasionally() {
        if (ui.phase != Phase.NAVIGATING) return
        val now = System.currentTimeMillis()
        if (now - lastFrameReportAtMs < FRAME_REPORT_MS) return
        val r = frames.report() ?: return
        lastFrameReportAtMs = now
        Log.i(FRAME_TAG, r.oneLine())
    }

    /**
     * Log the navigation state machine's transitions.
     *
     * The device harness's assertions are made of these lines, and they are the
     * counterpart to what `DriveScenarioTest` asserts in simulation: that a
     * deviation was noticed, that exactly one reroute followed it, that
     * positioning was reported lost and then recovered, that an arrival
     * happened. Without them a device run can only say "it did not crash",
     * which V5 §10 explicitly rejects.
     *
     * **No coordinates.** A logcat buffer is device-wide and readable by any
     * process with the permission, and adr-0068's whole position is that the
     * one thing this app must not leak is where its user has been. Transitions
     * and magnitudes are enough to assert on; the offset is a distance, not a
     * place.
     */
    private fun logNav(before: UiState, after: UiState) {
        if (before.phase != after.phase) Log.i(NAV_TAG, "phase ${before.phase} -> ${after.phase}")
        if (!before.offRoute && after.offRoute) Log.i(NAV_TAG, "offroute")
        if (before.offRoute && !after.offRoute) Log.i(NAV_TAG, "onroute")
        if (!before.rerouting && after.rerouting) Log.i(NAV_TAG, "rerouting")
        if (before.gps != after.gps) Log.i(NAV_TAG, "gps ${before.gps} -> ${after.gps}")
        val b = before.currentManeuver?.index
        val a = after.currentManeuver?.index
        if (a != null && a != b) Log.i(NAV_TAG, "maneuver $a")
    }

    /** Carry out what NavSession decided. The only side-effecting half. */
    private fun perform(actions: List<NavSession.Action>) {
        for (a in actions) when (a) {
            is NavSession.Action.Puck -> drawPuck(a.position, a.bearing)
            is NavSession.Action.Camera -> applyCamera(a.target)
            is NavSession.Action.Speak -> {
                // V7.3/V7.7 device evidence: the camera utterance must fire at
                // most once per camera per route. It is recognised by the
                // sentence being one the camera vocabulary produces, so a
                // sentence added for a new camera type is counted too (the
                // unit suite pins the dedup; this makes the device run able to
                // prove it fired at all, including on an emulator whose TTS
                // engine is absent).
                if (BuildConfig.DEBUG &&
                    dev.vector.geo.camera.CameraType.entries.any {
                        dev.vector.geo.camera.CameraText.line(it) == a.text
                    }
                ) {
                    cameraVoices++
                    Log.i(CAMERAS_TAG, "voice=${cameraVoices} text=\"${a.text}\"")
                }
                voice.say(a.text, a.kind)
            }
            is NavSession.Action.Reroute -> { Log.i(NAV_TAG, "reroute requested"); reroute() }
            is NavSession.Action.Arrived -> { Log.i(NAV_TAG, "arrived"); arrive() }
        }
    }

    // -----------------------------------------------------------------------
    // Walking navigation (V7.4 4C final)
    // -----------------------------------------------------------------------

    /**
     * Carry out what [WalkNavSession] decided. The walking counterpart of
     * [perform], and deliberately the same shape.
     *
     * Every side effect here reuses an existing mechanism rather than adding
     * one: [drawPuck] is the same puck the car draws, [applyCamera] is the
     * same single camera writer the [CameraGate] interlocks, and [voice] is
     * the same [VoiceGuide] — there is no second TTS stack, which is what
     * keeps the language handling (device locale, Arabic runs, graceful
     * silence when no engine exists) identical in both modes.
     */
    private fun performWalk(actions: List<WalkNavSession.Action>) {
        for (a in actions) when (a) {
            // A matched walking position. `bearingDeg` is the ROUTE's bearing
            // and is null on degenerate geometry, in which case the last known
            // heading is kept rather than snapping the puck to due north —
            // the same 0.0-is-not-unknown trap the fix path guards.
            is WalkNavSession.Action.Puck ->
                drawPuck(a.position, a.bearingDeg ?: lastBearing)
            is WalkNavSession.Action.RawPuck -> drawPuck(a.position, lastBearing)
            is WalkNavSession.Action.Camera -> applyCamera(
                CameraTarget(
                    position = a.position,
                    // Null means "leave the bearing alone" — the off-route
                    // regime asserts none, because the route's direction has
                    // stopped describing where this person is going.
                    bearing = a.bearingDeg ?: (maplibre?.cameraPosition?.bearing ?: 0.0),
                    tilt = a.tiltDeg,
                    zoom = a.zoom,
                ),
                durationMs = a.durationMs,
            )
            is WalkNavSession.Action.Speak -> voice.say(a.text)
            is WalkNavSession.Action.Reroute -> {
                Log.i(NAV_TAG, "walk reroute requested")
                walkReroute(a.from)
            }
            is WalkNavSession.Action.Arrived -> {
                Log.i(NAV_TAG, "walk arrived")
                arriveOnFoot()
            }
        }
    }

    /**
     * Start walking to the chosen destination.
     *
     * Asks `/foot` — never the car's `/navigate` — and refuses honestly when
     * there is no walk. The three outcomes are kept apart all the way to the
     * screen (see [WalkRouteResult] and
     * [dev.vector.geo.walk.WalkRefusalText]), because on Qatar's pedestrian
     * graph a network split is the COMMON answer and is a statement about the
     * map rather than a failure.
     *
     * **Not gated on Pro.** The journey card's walk leg is a sold feature and
     * stays so; walking NAVIGATION is a baseline capability and nothing here
     * consults `offersPro`.
     */
    private fun startWalkNavigation() {
        val from = lastFix
        if (from == null) {
            ui = ui.copy(error = "Waiting for a GPS fix before routing")
            return
        }
        val dest = ui.destination ?: return
        walkJob?.cancel()
        ui = ui.copy(
            walkBusy = true, walkRefusal = null, error = null,
            status = "Planning the walk…", busy = true,
        )
        walkJob = lifecycleScope.launch {
            val result = api.footRoute(from, dest)
            // The destination may have moved on while the request was open —
            // the same guard every other async reply in this class applies.
            if (!routeReplyStillWanted(ui.destination, dest)) return@launch
            when (result) {
                is WalkRouteResult.Ok -> beginWalk(result.contract)
                else -> {
                    val kind = result.refusalKind
                    if (result is WalkRouteResult.Failed) {
                        Log.w(NAV_TAG, "walk request failed", result.cause)
                    }
                    // No route is drawn and no fallback is offered. A straight
                    // line here, or a car route in answer to a walking
                    // request, would be exactly the claim the backend declined
                    // to make.
                    ui = ui.copy(
                        walkBusy = false, busy = false, status = "",
                        walkRefusal = kind,
                    )
                }
            }
        }
    }

    /**
     * Take up a walking route and enter navigation.
     *
     * The car journey is torn down first and completely — tracker, announcer,
     * maneuver camera, lane/signal/camera profiles and every car source — so
     * there is no state from a drive still live underneath a walk. That is the
     * same convergence argument [drawRoute] makes: one place where the mode
     * changes, rather than each caller remembering the list.
     */
    private fun beginWalk(contract: dev.vector.geo.walk.WalkContract) {
        val route = dev.vector.geo.walk.WalkRoute.of(contract)
        val s = WalkNavSession(route)
        s.units = ui.units
        walkSession = s
        navStartedAtMs = System.currentTimeMillis()
        navStartedAt = lastFix
        driveTrack.clear()
        trackSpacingM = Drives.TRACK_MIN_SPACING_M
        lastFix?.let { driveTrack.add(it) }

        // The drive, comprehensively put away.
        tracker.clearRoute()
        announcer.reset()
        maneuverCamera.reset()
        session.reset()
        session.setLanes(null)
        session.setSignals(null)
        session.setCameras(null)
        lastSignalProfile = null
        lastCameraProfile = null
        lastRouteCoords = emptyList()
        speedPill.clear()
        source(SRC_ROUTE)?.setGeoJson(EMPTY_FC)
        source(SRC_ROUTE_ALT)?.setGeoJson(EMPTY_FC)
        source(SRC_ROUTE_LABELS)?.setGeoJson(EMPTY_FC)
        source(SRC_CHEVRONS)?.setGeoJson(EMPTY_FC)
        source(SRC_CALLOUTS)?.setGeoJson(EMPTY_FC)
        clearTraffic()

        drawWalkRoute(contract.geometry)
        // V7 traffic lights: the signals the backend attached to this walk's own
        // crossings, drawn as static location pills. Placed once here rather
        // than per fix — see `WalkSignals.callouts` for why a walk needs no
        // sliding window — and torn down with the walk below.
        drawWalkSignalCallouts(route)
        val cam = MapCamera.onRecenter(ui.cam)
        ui = ui.copy(
            phase = Phase.NAVIGATING, cam = cam, showSteps = false,
            alternatives = emptyList(), walk = s.state,
            walkBusy = false, busy = false, status = "", walkRefusal = null,
            // The CAR maneuver list is not a walking plan, and the step sheet
            // renders it. Cleared so nothing can present a drive's turns
            // during a walk.
            maneuvers = emptyList(), currentManeuver = null, nextManeuver = null,
            routeDistanceM = contract.distanceM ?: 0.0,
            routeDurationS = contract.durationS ?: 0.0,
            // The walk ends where the pedestrian network ends. Worth saying
            // when that is far from the point the walker actually asked for —
            // the same threshold the driving snap warning uses.
            snapWarningM = contract.diagnostics.snapMaxM?.takeIf { it >= 150.0 },
            routeSnapMaxM = contract.diagnostics.snapMaxM,
            speedKmh = null, speedLimitKmh = null, speedLimitInferred = false,
        )
        drawEndpoints(contract.geometry)
        applyLookAhead()
        lastFix?.let {
            applyCamera(
                MapCamera.recenter(
                    // FLAT, for the reason spelled out in [recenter]: the car's
                    // 60° tilt is bought to claw back look-ahead a closer zoom
                    // spends, and a walk does not need that trade. Expressed
                    // through `MapCamera`'s own vocabulary so tilt still has
                    // exactly one decider.
                    cam.copy(perspective = MapPerspective.FLAT),
                    Phase.NAVIGATING, it, lastBearing,
                    // NULL, deliberately, and this is the one place the choice
                    // is interesting.
                    //
                    // The driving equivalent passes `transitionZoom`, which is
                    // the SPEED ladder — 18.0 below 25 km/h down to 15.6 above
                    // 115. Every walk sits in its bottom rung forever and that
                    // rung means "about fifteen seconds of travel", which on
                    // foot is 20 m: closer than the shortest real gap between
                    // two walking maneuvers. Asserting it here would put a car
                    // zoom on the map for the one flight the walker actually
                    // watches.
                    //
                    // Null leaves the zoom alone, and the first fix hands it to
                    // `WalkCameraPolicy`, which derives it from ground distance
                    // (120 m of look-ahead at cruise) rather than from speed.
                    // One camera authority per mode, and the walking one owns
                    // the walking zoom.
                    zoom = null,
                ),
                durationMs = VectorTokens.Motion.FLIGHT,
            )
        }
    }

    /**
     * Ask for a replacement walking route from here.
     *
     * `/foot` again, to the SAME destination. The session has already decided
     * this is worth asking — a confirmed departure, sustained past the action
     * threshold, with ground movement, outside the cooldown — so there is no
     * second opinion about that here.
     *
     * A refusal leaves the walker on the route they have. That is deliberate:
     * a reroute failing is not a reason to take away the only guidance
     * available, and [WalkNavSession.onRerouteFailed] speaks once rather than
     * once per attempt.
     */
    private fun walkReroute(from: LngLat) {
        val dest = ui.destination ?: return
        walkRerouteJob?.cancel()
        walkRerouteJob = lifecycleScope.launch {
            val result = api.footRoute(from, dest)
            val s = walkSession ?: return@launch
            // Is there still a walk for this route to belong to? A reply can
            // land after arrival or after the walker taps out, and applying it
            // then would resurrect a journey that is over — the defect
            // `rerouteReplyStillWanted` was written for.
            if (!rerouteReplyStillWanted(ui.phase, ui.destination, dest)) {
                Log.i(NAV_TAG, "walk reroute discarded: journey moved on")
                return@launch
            }
            when (result) {
                is WalkRouteResult.Ok -> {
                    // ATOMIC: the follower, the progress latch, the camera
                    // regime and the voice's per-maneuver dedup are all rebuilt
                    // together inside `replaceRoute`, so there is no fix at
                    // which the new geometry is followed while the old plan is
                    // still being announced.
                    s.replaceRoute(dev.vector.geo.walk.WalkRoute.of(result.contract))
                    drawWalkRoute(result.contract.geometry)
                    drawEndpoints(result.contract.geometry)
                    // The signal markers belong to the OLD route: a replacement
                    // walk crosses different roads, so the pills must be
                    // rebuilt from the new plan or they would keep pointing at
                    // crossings this walker is no longer approaching.
                    drawWalkSignalCallouts(s.route)
                    Log.i(NAV_TAG, "walk route replaced")
                    ui = ui.copy(
                        walk = s.state, walkRefusal = null,
                        routeDistanceM = result.contract.distanceM ?: 0.0,
                        routeDurationS = result.contract.durationS ?: 0.0,
                    )
                }
                else -> {
                    if (result is WalkRouteResult.Failed) {
                        Log.w(NAV_TAG, "walk reroute failed", result.cause)
                    }
                    result.refusalKind?.let { kind ->
                        s.onRerouteFailed(kind)?.let { spoken ->
                            if (ui.voice.speaksAlerts) voice.say(spoken.text)
                        }
                        ui = ui.copy(walk = s.state, walkRefusal = kind)
                    }
                }
            }
        }
    }

    /** Draw the walking route. See [VectorStyle.walkRouteGeoJson]. */
    private fun drawWalkRoute(coords: List<LngLat>) {
        lastRouteCoords = coords
        source(SRC_WALK_ROUTE)?.setGeoJson(VectorStyle.walkRouteGeoJson(coords))
    }

    /**
     * Mark the surveyed signals standing on this walk's crossings (V7 traffic lights).
     *
     * ## What a pill says, and what it cannot
     *
     * A location, through [dev.vector.geo.signal.SignalText.LOCATION] — the same
     * vocabulary the driving map already uses for the same claim. There is no
     * phase pill, no red/green, no animated or flashing marker and no countdown
     * on this map, and there cannot be: the backend attaches only a surveyed
     * signal's identity and position, and `WalkSignal` has no field that could
     * carry a state. See `WalkSignals` for the placement and
     * `SignalTextTest` for the vocabulary guarantee.
     *
     * ## Why this is not `drawCallouts`
     *
     * The driving drawer builds junctions, bends, speed changes and cameras for
     * a car's route index, and its callers pass the CAR's maneuvers. Reusing it
     * here would mean handing it an empty maneuver list and hoping nothing else
     * in it fires; a walk's only callouts are these, so it has its own two-line
     * path to the same source.
     */
    private fun drawWalkSignalCallouts(route: dev.vector.geo.walk.WalkRoute) {
        val signals = dev.vector.geo.walk.WalkSignals.profile(route)
        lastWalkSignalProfile = signals
        val callouts = dev.vector.geo.walk.WalkSignals.callouts(route, signals)
        source(SRC_CALLOUTS)?.setGeoJson(VectorStyle.calloutGeoJson(callouts))
        // One line per walk, the same argument the driving drawer makes: a
        // marker that does not appear is otherwise indistinguishable from one
        // that was never generated, and the two have different causes.
        Log.i(
            NAV_TAG,
            "walk signals ${callouts.size} on route of ${route.totalM.toInt()}m " +
                signals.signals.joinToString(",") {
                    "${it.ref.id}@${it.approach.alongM.toInt()}m"
                },
        )
    }

    /**
     * The walk is over.
     *
     * Says what the route can support and no more. The driving [arrive] ends
     * with "You have arrived" and an [ArrivalSummary] naming the destination;
     * a walk ends where the PEDESTRIAN NETWORK ends, which `snap_max_m` says
     * may be a measurable distance from the door — so the banner and the voice
     * both say "the end of the walking route", and the summary records the
     * distance rather than claiming a door was reached.
     *
     * The voice line is [WalkNavSession]'s, spoken once from the announcer's
     * own arrival latch; nothing is said here, or arrival would be announced
     * twice from two places.
     */
    private fun arriveOnFoot() {
        val name = ui.destinationName
        val plannedS = ui.routeDurationS
        val distanceM = ui.routeDistanceM
        val startedAt = navStartedAtMs
        val now = System.currentTimeMillis()
        val actualS = startedAt?.let { (now - it) / 1000.0 } ?: 0.0
        endJourney(arrived = true)
        ui = ui.copy(
            status = "",
            arrival = ArrivalSummary(
                name = name.ifBlank { "your destination" },
                atMs = now,
                distanceM = distanceM,
                plannedS = plannedS,
                actualS = actualS,
            ),
        )
    }

    /**
     * The journey is over.
     *
     * Ends navigation the same way `cancelRoute` does — the teardown is
     * identical and duplicating it is how one of the two paths forgets to close
     * the probe trip — but says so out loud and leaves the destination named in
     * the status line rather than resetting to a bare "Ready".
     *
     * Nothing did this before. NAVIGATING had no exit but a manual tap, so a
     * driver who arrived kept a maneuver card reading "Arrive at destination,
     * 0 m", a camera locked at a 45-degree navigation tilt, and — because the
     * probe trip was only closed by `cancelRoute` — a collection session still
     * running after the journey they consented to had ended.
     */
    private fun arrive() {
        val name = ui.destinationName
        val plannedS = ui.routeDurationS
        val distanceM = ui.routeDistanceM
        val startedAt = navStartedAtMs
        val dest = ui.destination
        val now = System.currentTimeMillis()
        voice.say("You have arrived")
        endJourney(arrived = true)

        // The summary, built from numbers the app already had and used to throw
        // away. See ArrivalSummary for why the end of a drive deserved more
        // than a grey pill reading "Arrived at Villaggio Mall".
        val actualS = startedAt?.let { (now - it) / 1000.0 } ?: 0.0
        ui = ui.copy(
            status = "",
            arrival = ArrivalSummary(
                name = name.ifBlank { "your destination" },
                atMs = now,
                distanceM = distanceM,
                plannedS = plannedS,
                actualS = actualS,
            ),
        )
        // navStartedAtMs is cleared by recordDrive, which owns it now.

        // Close the ETA loop. `POST /eta` has existed since issue 07 for
        // exactly this moment and no client had ever called it, which is why
        // the evolution dashboard's ETA-error panel was always empty.
        if (plannedS > 0 && actualS > 0) {
            lifecycleScope.launch {
                api.postEta(plannedS, actualS, coverage = 1.0)
            }
        }

        // And the one arrival-side question a driver actually asks. A real
        // query against the real index — see VectorApi.nearby — so an empty
        // answer is reported as an empty answer.
        if (dest != null) {
            lifecycleScope.launch {
                val found =
                    api.nearby(dest, kinds = "parking", radiusM = 500, limit = PARKING_SUGGESTIONS)
                // Only if the driver has not moved on to something else.
                val current = ui.arrival ?: return@launch
                if (current.atMs != now) return@launch
                ui = ui.copy(arrival = current.copy(parking = found))
            }
        }
    }

    /**
     * Keep the posted speed limit current.
     *
     * Throttled by BOTH time and distance: the limit only changes when the road
     * does, so polling every fix would be one request per second per driver for
     * a value that is usually identical. 150 m is roughly the shortest block
     * worth re-checking.
     */
    private fun refreshRoadHere(here: LngLat) {
        // Every phase since V4, not NAVIGATING only.
        //
        // The reply carries the road NAME as well as the limit, and "which road
        // am I on" is a question a parked driver asks too — Waze answers it on
        // its idle map (`v4-evidence/waze/01-map-idle.png` reads "Ibn Katheer
        // St" with no route loaded). The speed-limit SIGN stays
        // navigation-only in the UI; only the lookup widened.
        //
        // The throttle below is what makes that affordable: 5 s AND 150 m, so
        // a parked phone makes one request and then stops.
        val now = System.currentTimeMillis()
        val moved = lastSpeedProbeAt?.let {
            dev.vector.geo.RouteGeometry.haversineM(it.lng, it.lat, here.lng, here.lat)
        } ?: Double.MAX_VALUE
        if (now - lastSpeedProbeAtMs < 5_000 && moved < 150.0) return
        lastSpeedProbeAtMs = now
        lastSpeedProbeAt = here
        lifecycleScope.launch {
            val r = api.roadHere(here) ?: return@launch
            // Debug builds only: the device drive checks read this to confirm
            // the limit was looked up on the ROUTE's road (see
            // NavSession.roadProbePoint), never shipped in a release build.
            if (BuildConfig.DEBUG) Log.i("VectorRoad",
                "probe=${"%.6f".format(here.lat)},${"%.6f".format(here.lng)} " +
                    "road=${r.name} ref=${r.ref} limit=${r.limitKmh} inferred=${r.inferred}")
            // null limit means the road carries neither a maxspeed tag nor a
            // class default. Clear the sign rather than leaving the previous
            // road's limit on screen, which would be actively misleading.
            //
            // The road NAME arrives on the same reply and is what feeds the
            // road-you-are-on readout (§13's Context tier). It cost no extra
            // request: see VectorApi.roadHere.
            if (r.limitKmh != ui.speedLimitKmh ||
                r.inferred != ui.speedLimitInferred ||
                r.name != ui.roadName ||
                r.ref != ui.roadRef
            ) {
                ui = ui.copy(
                    speedLimitKmh = r.limitKmh,
                    speedLimitInferred = r.inferred,
                    roadName = r.name,
                    roadRef = r.ref,
                )
            }
        }
    }

    /**
     * Ask what the limit is on the road AFTER the next maneuver.
     *
     * ## Why this is a second request and not a field on the route
     *
     * `/navigate` returns maneuvers with types, instructions, exit refs and
     * road names, and no speed limits. `/speed` answers "what is the limit at
     * this point", which is exactly the question — asked at a point just past
     * the next maneuver, it describes the road the driver is about to be on.
     *
     * Once per MANEUVER, not per fix. The answer cannot change until the road
     * ahead does, and on a Doha journey that is one request every thirty to
     * sixty seconds — cheaper than the limit poll that already runs every 150 m
     * for the road underneath.
     *
     * ## What is refused
     *
     * An INFERRED limit is a class median, and `VectorApi.RoadHere` is explicit
     * that a caller must honour the distinction: "presenting an inferred number
     * as a posted sign is the kind of confident wrong answer a driver acts on."
     * The speed disc already handles this by captioning the number `typical`.
     * A pill stuck to a stretch of road has no room for that caption and would
     * read as a sign, so an inferred limit produces no callout at all.
     *
     * A limit equal to the one already in force is also dropped: the callout is
     * about a CHANGE, and a pill restating the number on the disc is clutter
     * over the road.
     *
     * ## Why the decision is latched, and against what baseline
     *
     * The "is it a change?" comparison is made against the limit in force when
     * the query is SENT, captured below, and the result is latched to the
     * maneuver by [SpeedPillLatch]. The reply can land after the car has
     * crossed the change point, by which time `refreshRoadHere` has moved
     * `ui.speedLimitKmh` to the new limit; comparing against that live value is
     * what withdrew the pill and let it reappear, which is the flicker the
     * latch closes. One query and one decision per maneuver, and the pill is
     * released only when a later maneuver's reply replaces it (or on arrival,
     * reroute or teardown).
     */
    private fun refreshSpeedAhead() {
        if (ui.phase != Phase.NAVIGATING) return
        val next = ui.currentManeuver ?: return
        // One query per maneuver: a projection that jitters across the
        // maneuver boundary must not ask again for one already answered.
        if (!speedPill.beginProbe(next.index)) return
        val coords = lastRouteCoords
        val index = dev.vector.geo.RouteGeometry.index(coords) ?: return
        // Far enough past the node to be on the new road rather than in the
        // junction, near enough to still be the road the maneuver joins.
        val atM = next.cumulativeM + SPEED_AHEAD_PROBE_M
        if (atM >= index.totalM) return
        val at = index.pointAt(atM)?.position ?: return
        val forManeuver = next.index
        // Captured NOW, while the maneuver is still ahead. See the KDoc above.
        val baselineKmh = ui.speedLimitKmh
        lifecycleScope.launch {
            val r = api.roadHere(at) ?: return@launch
            // The journey moved on while the request was in flight.
            if (ui.phase != Phase.NAVIGATING) return@launch
            if (ui.currentManeuver?.index != forManeuver) return@launch
            speedPill.resolve(
                maneuverIndex = forManeuver,
                limitKmh = r.limitKmh,
                inferred = r.inferred,
                baselineKmh = baselineKmh,
                alongM = atM,
            )
            drawCallouts(dev.vector.geo.RouteGeometry.index(lastRouteCoords), ui.maneuvers)
        }
    }

    /**
     * Refresh live congestion.
     *
     * 60 s. Traffic is the one layer where a stale value is actively harmful —
     * a jam that cleared ten minutes ago sends the driver the long way round —
     * but it is also a whole-city payload, so polling it per fix would be
     * absurd.
     *
     * It used to be fetched ONLY while navigating, which meant the layer was
     * invisible at the one moment it could still change the driver's mind:
     * before setting off. Now it also runs in EXPLORE and PREVIEW unless the
     * driver has turned it off.
     */
    private fun refreshTraffic() {
        if (ui.phase != Phase.NAVIGATING && !ui.settings.trafficInExplore) return
        val now = System.currentTimeMillis()
        if (now - lastTrafficAtMs < 60_000) return
        lastTrafficAtMs = now
        lifecycleScope.launch {
            val t = api.traffic() ?: return@launch
            lastTrafficJson = t.geoJson
            source(SRC_TRAFFIC)?.setGeoJson(t.geoJson)
            ui = ui.copy(jamCount = t.jamCount)
        }
    }

    private fun clearTraffic() {
        lastTrafficJson = null
        lastTrafficAtMs = 0L
        source(SRC_TRAFFIC)?.setGeoJson(EMPTY_FC)
        ui = ui.copy(jamCount = null)
    }

    // ---- traffic contribution ----------------------------------------------

    /**
     * Feed the probe buffer and upload whatever it hands back.
     *
     * This is the streamlining: the driver is already navigating, so the probe
     * stream is a by-product rather than a separate activity. The web client
     * required visiting a /collect page and pressing record, which is why the
     * learned-speed layer has never seen a real trip.
     */
    private fun collectProbe(loc: android.location.Location, here: LngLat, speed: Double?) {
        if (!ui.contributing) return
        val batch = probes.offer(
            ProbeBuffer.Probe(
                lng = here.lng,
                lat = here.lat,
                ts = System.currentTimeMillis(),
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                speedMs = speed,
                bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            )
        ) ?: return
        lifecycleScope.launch {
            val ok = runCatching { api.postProbes(batch.trip, batch.points, batch.end) }
                .getOrDefault(false)
            // A failed upload is not worth telling the driver about mid-drive;
            // the buffer already bounds itself, so the data simply ages out.
            if (ok) ui = ui.copy(probesSent = ui.probesSent + batch.points.size)
            else Log.d(TAG, "probe batch upload failed (${batch.points.size} points)")
        }
    }

    /** The HUD pill. Goes through [updateSettings] so consent is persisted. */
    private fun toggleContributing() {
        val on = !ui.contributing
        updateSettings(ui.settings.copy(contributing = on))
        if (!on) ui = ui.copy(probesSent = 0)
    }

    // ---- map drawing -------------------------------------------------------

    /**
     * Frame the whole route in the part of the screen that is not covered.
     *
     * ## The defect
     *
     * This used `newLatLngBounds(bounds, 120)` — a **symmetric** 120 px
     * padding — while `RouteChooser` is around 660 px tall. So the bottom
     * quarter of every previewed route was drawn underneath the card
     * describing it, including the destination end of the line. Visible in
     * `v4-evidence/vector/before-03-preview.png`: the route's western leg is
     * clipped exactly at the card's top edge, and the driver is asked to choose
     * between routes whose destination they cannot see.
     *
     * That is the same class of mistake V3 fixed when it found the alternatives
     * were never drawn at all — the interface describing something the map does
     * not show — and the asymmetric-padding version of it survived.
     *
     * ## The fix
     *
     * Per-edge padding, with the bottom edge sized to whatever chrome is
     * actually there. MapLibre's four-argument overload takes
     * `(bounds, left, top, right, bottom)`.
     *
     * The values are measured against the composition rather than guessed: the
     * top clears the status bar and the destination chip, the bottom clears the
     * route card in PREVIEW or the trip bar in NAVIGATING, and the sides get a
     * comfortable margin so the line does not touch the bezel.
     */
    private fun fitRoute(coords: List<LngLat>) {
        val map = maplibre ?: return
        if (coords.size < 2) return
        val b = LatLngBounds.Builder()
        coords.forEach { b.include(LatLng(it.lat, it.lng)) }
        val d = resources.displayMetrics.density
        fun px(dp: Int) = (dp * d).toInt()
        // PREVIEW carries the route chooser (up to four rows plus the action
        // row); NAVIGATING carries the trip bar and the speed dials.
        val bottomDp = if (ui.phase == Phase.PREVIEW) 260 else 150
        runCatching {
            map.easeCamera(
                CameraUpdateFactory.newLatLngBounds(
                    b.build(),
                    px(28), px(120), px(28), px(bottomDp),
                ),
                // MEDIUM, not FLIGHT: this is a re-frame of something the
                // driver is already looking at, not a change of scale.
                VectorTokens.Motion.MEDIUM,
            )
        }
    }

    /**
     * Put the routes the driver did NOT choose on the map, muted.
     *
     * This is the fix for the deepest problem with route selection: the
     * alternatives were described and never drawn. A chip reading "via شارع
     * حالول" asked the driver to choose a road they could not see, and the only
     * way to find out where it went was to select it — at which point the one
     * they had been looking at disappeared instead. Choosing between three
     * invisible lines is not a choice.
     *
     * Drawn as one FeatureCollection into a single source rather than one
     * source per route: the count varies (1-4), and a set of sources whose
     * membership changes would have to be added to and removed from the style
     * at runtime, which is where a stale layer referencing a dead source comes
     * from.
     *
     * The chosen route is excluded here and drawn by [drawRoute] into its own
     * source, so it paints ABOVE the others (see the layer order in
     * [VectorStyle]) and stays the most legible line on screen.
     */
    private fun drawAlternatives(options: List<RouteOption>, chosen: Int) {
        val src = source(SRC_ROUTE_ALT) ?: return
        if (options.size <= 1) {
            src.setGeoJson(EMPTY_FC)
            return
        }
        val feats = JSONArray()
        options.forEachIndexed { i, opt ->
            if (i == chosen) return@forEachIndexed
            val arr = JSONArray()
            opt.geometry.forEach { arr.put(JSONArray().put(it.lng).put(it.lat)) }
            feats.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject().put("route_id", i))
                    .put("geometry", JSONObject()
                        .put("type", "LineString").put("coordinates", arr))
            )
        }
        src.setGeoJson(
            JSONObject().put("type", "FeatureCollection").put("features", feats).toString()
        )
    }

    /**
     * Mark where the route begins and ends.
     *
     * Vector drew a bare line and nothing else, so which end was the
     * destination had to be inferred (`v4-evidence/vector/before-03-preview.png`).
     * Both reference products mark both ends unmistakably.
     *
     * One source with two features rather than two sources, for the same
     * reason `drawAlternatives` uses one: a set of sources whose membership
     * changes has to be added to and removed from the style at runtime, which
     * is where a live layer pointing at a dead source comes from.
     */
    private fun drawEndpoints(coords: List<LngLat>) {
        val src = source(SRC_ENDPOINTS) ?: return
        if (coords.size < 2) {
            src.setGeoJson(EMPTY_FC)
            return
        }
        fun point(p: LngLat, marker: String) = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject().put("marker", marker))
            .put("geometry", JSONObject().put("type", "Point")
                .put("coordinates", JSONArray().put(p.lng).put(p.lat)))
        val feats = JSONArray()
        // The origin marker is PREVIEW only.
        //
        // Caught on the S24 (`v4-evidence/vector/after-04-navigating.png`
        // before this): the route starts at the snapped origin, which is a few
        // metres from the vehicle, so while navigating the origin dot and the
        // vehicle arrow drew almost on top of each other and read as **two
        // vehicles**. Once the drive has started the vehicle marker *is* where
        // the journey began from, and a second marker for it is not context,
        // it is a contradiction.
        if (ui.phase != Phase.NAVIGATING) {
            feats.put(point(coords.first(), VectorMarkers.ORIGIN))
        }
        feats.put(point(coords.last(), VectorMarkers.DESTINATION))
        src.setGeoJson(
            JSONObject()
                .put("type", "FeatureCollection")
                .put("features", feats)
                .toString()
        )
    }

    /**
     * Write each route's comparison ON the line it describes.
     *
     * ## Why this is the fix that was still missing
     *
     * V3 found that the alternatives were never drawn, so a chip reading "via
     * شارع حالول" asked the driver to pick a road they could not see, and it
     * drew them. What it did not fix is **which line is which**: three blue-grey
     * lines and three card rows, with nothing connecting a row to a line except
     * the driver selecting one and watching what changed.
     *
     * Both reference products solve it the same way and it is not a card
     * problem at all — the time is written on the line. Waze puts a filled
     * cyan "15 min / Best" pill on the chosen route and plain "Similar ETA"
     * pills on the others (`v4-evidence/waze/06-alternatives.png`); Google
     * puts a filled dark-blue "16 min" on the chosen and white "16 min" pills
     * on the alternatives (`gmaps/04-route-options.png`).
     *
     * Vector writes the same comparative text the card row uses, in the same
     * colours, so a row and a line are visibly the same object.
     *
     * ## Placement
     *
     * At a fraction along each route that varies with its index, so labels for
     * routes that share most of their length still separate. `text-allow-overlap`
     * is false, so MapLibre drops a label rather than stacking two — losing one
     * label is better than an unreadable pile, and the card still carries every
     * route.
     *
     * A halo rather than a drawn pill: a real pill needs a stretchable image
     * and `icon-text-fit`, and a halo is legible over every surface in the
     * palette for one line of style rather than a bitmap pipeline.
     */
    private fun drawRouteLabels(options: List<RouteOption>, chosen: Int) {
        val src = source(SRC_ROUTE_LABELS) ?: return
        if (options.size <= 1) {
            src.setGeoJson(EMPTY_FC)
            return
        }
        val chosenDuration = options.getOrNull(chosen)?.durationS ?: return
        val feats = JSONArray()
        options.forEachIndexed { i, opt ->
            val at = pointAlong(opt.geometry, 0.32 + 0.14 * i) ?: return@forEachIndexed
            val label = if (i == chosen) {
                "${(opt.durationS / 60).toInt()} min"
            } else {
                routeComparison(opt.durationS, chosenDuration)
            }
            feats.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject()
                        .put("label", label)
                        .put("chosen", i == chosen))
                    .put("geometry", JSONObject().put("type", "Point")
                        .put("coordinates", JSONArray().put(at.lng).put(at.lat)))
            )
        }
        src.setGeoJson(
            JSONObject().put("type", "FeatureCollection").put("features", feats).toString()
        )
    }

    /**
     * A point at [fraction] of the way along a polyline, by distance.
     *
     * By DISTANCE rather than by vertex index: the router emits far more shape
     * points on a curve than on a straight, so "the 40th of 100 points" can be
     * a few hundred metres along a route that is twelve kilometres long.
     */
    private fun pointAlong(coords: List<LngLat>, fraction: Double): LngLat? {
        if (coords.size < 2) return coords.firstOrNull()
        var total = 0.0
        for (i in 1 until coords.size) {
            total += dev.vector.geo.RouteGeometry.haversineM(
                coords[i - 1].lng, coords[i - 1].lat, coords[i].lng, coords[i].lat,
            )
        }
        if (total <= 0.0) return coords.first()
        val want = total * fraction.coerceIn(0.0, 1.0)
        var run = 0.0
        for (i in 1 until coords.size) {
            val seg = dev.vector.geo.RouteGeometry.haversineM(
                coords[i - 1].lng, coords[i - 1].lat, coords[i].lng, coords[i].lat,
            )
            if (run + seg >= want) {
                val t = if (seg <= 0.0) 0.0 else (want - run) / seg
                val a = coords[i - 1]
                val b = coords[i]
                return LngLat(a.lng + (b.lng - a.lng) * t, a.lat + (b.lat - a.lat) * t)
            }
            run += seg
        }
        return coords.last()
    }

    /**
     * Put a route on the map, and the direction marks that sit on top of it.
     *
     * The chevrons are computed here rather than in the frame loop because they
     * describe the ROUTE, not the vehicle: nothing about them changes as the
     * driver moves along it, so a journey pays for them once. A 14 km Doha
     * route is about 640 marks and roughly a millisecond, which is why marking
     * the whole thing is affordable and a sliding window around the car — which
     * would have to be rebuilt every few seconds — is not.
     */
    private fun drawRoute(
        coords: List<LngLat>,
        /**
         * The maneuvers to hang callouts on.
         *
         * A parameter rather than a read of `ui.maneuvers`, because both
         * callers set the route BEFORE they set the state — `showRoute` and the
         * reroute handler each call this and then `ui.copy(maneuvers = ...)` —
         * so reading the field here would draw every journey's warnings one
         * route late. The default is right for the third caller, the style
         * reload, where the state is already current.
         */
        maneuvers: List<Maneuver> = ui.maneuvers,
        /**
         * The signals of THIS route (V7 Stage 5), built once into a profile
         * for the same reason the chevrons are: they describe the route, not
         * the vehicle, so nothing in them changes as the driver moves.
         */
        signals: List<VectorApi.Signal> = emptyList(),
        /**
         * The cameras of THIS route (V7.3), built once into a profile for
         * the same reason as the signals.
         */
        cameras: List<VectorApi.Camera> = emptyList(),
    ) {
        lastRouteCoords = coords
        lastRouteSignals = signals
        lastRouteCameras = cameras
        // A new route is a new journey for the camera voice: the count the
        // telemetry reports is per-route, matching the session's dedup.
        cameraVoices = 0
        drawEndpoints(coords)
        val index = dev.vector.geo.RouteGeometry.index(coords)
        // The route's lateral profile, computed once per route for the same
        // reason the chevrons are: it describes the ROUTE, not the vehicle, so
        // nothing in it changes as the driver moves along it.
        val lanes = lateralPlan(maneuvers)
        val routeJson = VectorStyle.routeGeoJson(index, lanes)
        source(SRC_ROUTE)?.setGeoJson(routeJson)
        if (BuildConfig.DEBUG) {
            camRouteFeatures = routeJson.split("\"type\":\"Feature\"").size - 1
        }
        source(SRC_CHEVRONS)?.setGeoJson(VectorStyle.chevronGeoJson(index, lanes))
        // The puck reads the same profile, from inside the frame loop. Handing
        // it over HERE rather than at each of `showRoute`/reroute is deliberate:
        // this function is the one place all three route-change paths converge,
        // so the map and the vehicle cannot end up on different profiles.
        session.setLanes(lanes)
        // The signals, to the loop that decides what to tell the driver.
        // Same convergence argument as the lanes: the profile must describe
        // the SAME route the puck is on.
        val profile = signalProfileFor(index, signals)
        lastSignalProfile = profile
        session.setSignals(profile)
        // The cameras, to the same loop. Same convergence argument: the
        // profile must describe the SAME route the puck is on.
        val camProfile = cameraProfileFor(index, cameras)
        lastCameraProfile = camProfile
        session.setCameras(camProfile)
        drawCallouts(index, maneuvers)  // cameras ride via lastCameraProfile
    }

    /**
     * The route's lateral profile, from the lane facts the maneuvers carry.
     *
     * The mapping is deliberately thin — `RouteLanes` owns every decision and
     * this owns none of them — but two things about it are worth stating.
     *
     * `Maneuver.cumulativeM` is the distance to the maneuver, and an
     * `Approach` describes the leg that ENDS there, which is the same thing the
     * backend means: `_lane_fields` reads the segment BEFORE the maneuver
     * vertex, so a maneuver's lane data is a fact about its approach and not
     * about the road beyond it.
     *
     * `Maneuver.lanes` is already `LaneGuidance.usefulLanes` — empty when the
     * lane string narrows nothing — so a band can only ever be claimed where
     * the lane strip is telling the driver the same thing.
     */
    private fun lateralPlan(maneuvers: List<Maneuver>): dev.vector.geo.RouteLanes.Plan =
        dev.vector.geo.RouteLanes.plan(
            maneuvers.map {
                dev.vector.geo.RouteLanes.Approach(
                    atM = it.cumulativeM,
                    forwardLanes = it.forwardLanes,
                    totalLanes = it.approachLanes,
                    lanes = it.lanes,
                    // Which way this maneuver turns, so a turn on a road with
                    // no `turn:lanes` is placed in the lane it is made from
                    // rather than on the middle of the road.
                    turn = dev.vector.geo.RouteLanes.Turn.of(it.type),
                )
            }
        )

    private fun signalProfileFor(
        index: dev.vector.geo.RouteIndex?,
        signals: List<VectorApi.Signal>,
    ): dev.vector.geo.signal.SignalProfile {
        if (index == null || signals.isEmpty()) return dev.vector.geo.signal.SignalProfile.EMPTY
        // Wire entries that the backend already projected ride as projected;
        // entries from an older answer (bare points, no along_m) are matched by
        // the client itself. `SignalMatcher.profile` handles both, sorted and
        // deduped, in route order.
        return dev.vector.geo.signal.SignalMatcher.profile(
            index,
            signals.map {
                dev.vector.geo.signal.SignalMatcher.Entry(
                    ref = dev.vector.geo.signal.SignalRef(
                        id = it.id,
                        position = it.position,
                        source = "osm:node:${it.id}",
                    ),
                    alongM = it.alongM,
                    approachBearingDeg = it.approachBearingDeg,
                )
            },
        )
    }

    private fun cameraProfileFor(
        index: dev.vector.geo.RouteIndex?,
        cameras: List<VectorApi.Camera>,
    ): dev.vector.geo.camera.CameraProfile {
        if (index == null || cameras.isEmpty()) return dev.vector.geo.camera.CameraProfile.EMPTY
        // Same two-half doctrine as the signals: wire entries the backend
        // already projected ride as projected; bare points from an older
        // answer are matched by the client. Two gates run inside CameraMatcher
        // whichever half produced the entry: the TYPE gate (a camera whose
        // source states no type is never announced) and the direction gate (a
        // sane OSM direction tag provably pointing away suppresses).
        return dev.vector.geo.camera.CameraMatcher.profile(
            index,
            cameras.map {
                dev.vector.geo.camera.CameraMatcher.Entry(
                    ref = dev.vector.geo.camera.CameraRef(
                        id = it.id,
                        position = it.position,
                        type = it.type,
                        maxspeedTag = it.maxspeed,
                        directionTag = it.direction,
                    ),
                    alongM = it.alongM,
                    approachBearingDeg = it.approachBearingDeg,
                )
            },
        )
    }

    /**
     * Put the world-space warnings on the map.
     *
     * Same argument as the chevrons: these describe the ROUTE — where its
     * maneuvers are, where it bends — so they are computed once when a route
     * arrives and never on a frame. The one moving part is the look-ahead speed
     * limit, which arrives later and asynchronously, and which re-enters here
     * rather than getting a source of its own so that a pill can never be drawn
     * for a route that has already been replaced.
     */
    private fun drawCallouts(
        index: dev.vector.geo.RouteIndex?,
        maneuvers: List<Maneuver>,
    ) {
        if (index == null) {
            source(SRC_CALLOUTS)?.setGeoJson(VectorStyle.EMPTY_FEATURES)
            return
        }
        val all = dev.vector.geo.Callouts.build(
            index,
            maneuvers.map {
                dev.vector.geo.Callouts.Junction(
                    cumulativeM = it.cumulativeM,
                    type = it.type,
                    exitRef = it.exitRef,
                    road = it.road,
                )
            },
            // Only ever a SURVEYED limit; see `refreshSpeedAhead`.
            speedPill.pill,
            // V7 Stage 5: the route's signals, straight from the profile the
            // puck and the HUD tick read — one list, so the pill on the map
            // and the signal the loop announces can never be different
            // junctions. The pill carries the location fact only.
            signals = lastSignalProfile?.signals ?: emptyList(),
            // V7.3: the cameras, same one-list rule.
            cameras = lastCameraProfile?.cameras ?: emptyList(),
        )
        val from = calloutWindowFromM()
        lastCalloutWindowFromM = from
        val shown = all.filter {
            it.alongM >= from - CALLOUT_BEHIND_M && it.alongM <= from + CALLOUT_AHEAD_M
        }
        lastSignalPills = shown.count { it.kind == dev.vector.geo.Callouts.Kind.SIGNAL }
        lastCameraPills = shown.count { it.kind == dev.vector.geo.Callouts.Kind.CAMERA }
        source(SRC_CALLOUTS)?.setGeoJson(VectorStyle.calloutGeoJson(shown))
        // One line per rebuild, which is a handful per journey. Worth having:
        // a warning that does not appear is otherwise indistinguishable from a
        // warning that was never generated, and those have different causes.
        Log.i(
            NAV_TAG,
            "callouts ${shown.size}/${all.size} from=${from.toInt()}m " +
                shown.joinToString(",") { "${it.kind.name.lowercase()}@${it.alongM.toInt()}" },
        )
    }

    /**
     * Where the visible span of warnings starts, in metres along the route.
     *
     * ## Why there is a window at all
     *
     * A pill is a `symbol` and MapLibre draws symbols at a constant size on
     * screen however far away they are — that is the whole point of making them
     * face the viewer, and it is what stops a 60-degree camera destroying the
     * glyphs. The consequence is that a warning three kilometres ahead is drawn
     * exactly as large as one two hundred metres ahead, so without a window a
     * long journey stacks every maneuver it has into a wall of full-size pills
     * along the horizon, each one describing a decision the driver will not
     * make for ten minutes.
     *
     * A warning is about what happens next. [CALLOUT_AHEAD_M] is roughly a
     * minute and a half at arterial speed, and [CALLOUT_BEHIND_M] keeps the one
     * just passed on screen long enough not to blink out of the mirror.
     *
     * ## Why this does not run on a frame
     *
     * It moves with the MANEUVER, not with the car: `maybeRedrawCallouts` only
     * rebuilds when the window has slid far enough to change what is in it,
     * which on a Doha journey is every thirty to sixty seconds. Rebuilding on
     * every fix would re-serialise and re-upload a source at the GPS rate for a
     * set of pills that is usually identical.
     */
    private fun calloutWindowFromM(): Double =
        if (ui.phase != Phase.NAVIGATING) 0.0
        else (ui.routeDistanceM - ui.remainingM).coerceAtLeast(0.0)

    /**
     * Slide the window, when it has moved far enough to be worth it.
     *
     * The threshold is a third of the span ahead, so a rebuild happens about
     * three times between one maneuver and the next rather than once a second.
     */
    private fun maybeRedrawCallouts() {
        if (ui.phase != Phase.NAVIGATING) return
        val now = calloutWindowFromM()
        if (kotlin.math.abs(now - lastCalloutWindowFromM) < CALLOUT_AHEAD_M / 3) return
        drawCallouts(dev.vector.geo.RouteGeometry.index(lastRouteCoords), ui.maneuvers)
    }

    private fun drawPuck(p: LngLat, bearing: Double) {
        source(SRC_PUCK)?.setGeoJson(
            JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject().put("bearing", bearing))
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(p.lng).put(p.lat)))
                .toString()
        )
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun onStart() { super.onStart(); mapView.onStart() }
    /**
     * A second `am start` while already running.
     *
     * The device harness launches the app, waits for it to settle, and only
     * then starts a drive — so the trace arrives on an intent delivered to a
     * live Activity rather than at creation. Without this the second launch is
     * silently ignored and the harness measures a stationary phone, which is
     * the whole thing V5 is trying to stop doing.
     */
    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        readMockIntent(newIntent)
    }

    /**
     * Pick up a replayed drive from a launch intent.
     *
     * Debug builds only, and the OS has to agree as well — see [MockDrive]'s
     * KDoc for the two gates. Reads three extras:
     *
     * ```
     * --es vectorTrace   /sdcard/Download/vector-traces/scenario-a.csv
     * --es vectorDest    25.320731,51.498490
     * --es vectorSpeedup 1.0
     * --ei vectorCamComplexity 3     (optional; camera validation only)
     * ```
     *
     * `vectorCamComplexity` pins [ManeuverCamera.complexityPin] so a validation
     * run can frame the same junction from the same distance at each of the
     * four complexity scores — see there for why that comparison is the only
     * one that settles whether the framing is right.
     *
     * `vectorDest` is here because a device scenario has to drive the SAME
     * route the trace was generated against. The alternative was to reach the
     * destination by tapping through search, which is what `verify_on_device.sh`
     * does and is right for testing the search flow — but it picks whatever the
     * geocoder ranks first, so the route under the wheels would not be the one
     * the trace was built from and every assertion about deviation and arrival
     * would be measuring a different journey.
     */
    private fun readMockIntent(from: android.content.Intent?) {
        if (!BuildConfig.DEBUG || from == null) return
        val trace = from.getStringExtra("vectorTrace") ?: return
        mockSpeedup = from.getStringExtra("vectorSpeedup")?.toDoubleOrNull() ?: 1.0
        maneuverCamera.complexityPin =
            from.getIntExtra("vectorCamComplexity", -1).takeIf { it >= 0 }
        mockDest = from.getStringExtra("vectorDest")?.split(',')?.let { p ->
            if (p.size == 2) {
                val la = p[0].trim().toDoubleOrNull()
                val lo = p[1].trim().toDoubleOrNull()
                if (la != null && lo != null) LngLat(lo, la) else null
            } else null
        }
        // A validation run must not inherit a journey from a previous one. The
        // batch force-stops the app between scenarios, which is a process death
        // as far as `Journey` is concerned, so without this every scenario
        // after the first would race a resume against its own trace.
        Journey.clear(prefs)
        val m = mock ?: MockDrive(fused) { Log.i(MockDrive.TAG, "drive finished") }.also { mock = it }
        if (m.load(trace) == 0) return
        if (!m.start(mockSpeedup)) {
            ui = ui.copy(error = "Mock location refused — see logcat")
            return
        }
        // The route is requested once a position exists, not now: `pickPoint`
        // refuses without a fix, and the first replayed fix is the one that
        // supplies it.
        mockDest?.let { dest ->
            mockRouteWhenReady(dest)
        }
    }

    /**
     * Ask for the trace's route as soon as the first replayed fix lands, then
     * start navigating.
     *
     * Polls rather than hooking the location callback, because the callback is
     * production code and threading a debug-only continuation through it is
     * exactly the "test-only behaviour in production code" V5 §20 forbids. A
     * 250 ms poll costs nothing and touches nothing.
     */
    /**
     * Put a journey the process died in the middle of back on the screen.
     *
     * Waits for a fix for the same reason [pickPoint] refuses without one, and
     * polls for it rather than hooking the location callback: the callback is
     * the hottest path in the app and threading a one-shot continuation through
     * it would cost every later fix a branch. Gives up after 30 s — a driver
     * who has been staring at "Searching for GPS" for half a minute has moved
     * on, and silently routing somewhere at that point would be a surprise.
     *
     * The route is REQUESTED again rather than restored, so it starts from
     * where the driver is now. See [Journey].
     */
    private fun resumeJourneyWhenReady(saved: Journey.Saved, attempt: Int = 0) {
        if (attempt > 120) {
            Log.i(NAV_TAG, "journey resume abandoned: no fix")
            return
        }
        if (lastFix == null) {
            main.postDelayed({ resumeJourneyWhenReady(saved, attempt + 1) }, 250)
            return
        }
        // Not if the driver has already started doing something else. Restoring
        // over the top of a destination they have just chosen would take the
        // app away from them.
        if (ui.phase != Phase.EXPLORE || ui.destination != null || ui.searching) return
        Log.i(NAV_TAG, "journey resumed")
        val name = saved.name.ifBlank { "Previous destination" }
        pickPoint(LngLat(saved.lng, saved.lat), name, remember = false)
        ui = ui.copy(status = "Resumed: $name")
    }

    private fun mockRouteWhenReady(dest: LngLat, attempt: Int = 0) {
        // Two minutes of patience, not thirty seconds.
        //
        // Measured on the S24: nine scenarios of a twelve-scenario batch went
        // from mock start to a route in about four seconds, and one —
        // `scenario-j-highway` — received no accepted fix for SIXTY. The old
        // 30 s ceiling turned that into a scenario that ran for twelve minutes
        // and measured nothing, which is the worst outcome available: the
        // harness reported a result rather than a problem.
        //
        // The cause of the sixty seconds is not established. It is the trace
        // whose start is furthest from the handset (15 km, Al Wakrah), so the
        // plausibility gate's rejection run is longest, but four rejections is
        // four seconds and not sixty. `MockDrive.injected` was added to tell
        // the difference on the next occurrence: if it lags the scheduled count
        // the provider is refusing the injections, and if it does not, they are
        // arriving and something downstream is dropping them.
        if (attempt > 480) { Log.w(MockDrive.TAG, "no replayed fix arrived in 120 s; not routing"); return }
        // Say so while waiting, every five seconds, so a slow start is visible
        // in the log rather than only its giving up.
        if (attempt > 0 && attempt % 20 == 0) {
            Log.i(MockDrive.TAG, "waiting for a replayed fix (${attempt / 4}s, " +
                "${mock?.injected ?: 0} injected)")
        }
        // Wait for a fix that came from the TRACE, not merely for any fix.
        //
        // `lastFix != null` was the condition, and it is satisfied by the
        // phone's real position — which on the first device run was 2.2 km from
        // the trace's start. So the route was planned from the building the
        // handset was sitting in, and the replayed drive was 2 km off it from
        // its first fix. Every assertion about deviation and arrival would have
        // been measuring a journey nobody simulated.
        val start = mock?.firstPosition
        val here = lastFix
        val ready = here != null && start != null &&
            dev.vector.geo.RouteGeometry.haversineM(start.lng, start.lat, here.lng, here.lat) < 250.0
        if (!ready) {
            main.postDelayed({ mockRouteWhenReady(dest, attempt + 1) }, 250)
            return
        }
        Log.i(MockDrive.TAG, "routing to ${"%.5f".format(dest.lat)},${"%.5f".format(dest.lng)}")
        pickPoint(dest, "Trace destination", remember = false)
        // Give the router time to answer and the preview to settle, then
        // drive. This waits for the ANSWER rather than for a fixed clock: the
        // alternatives request is a router round trip whose cost grows with
        // route length (measured 2.7 s for a 13 km corridor on a warm host,
        // 6.4 s for this stage's 24 km one), so any fixed window is a race
        // that gets lost on exactly the long routes a camera trace needs. A
        // 10 s ceiling stays as the give-up, not as the trigger.
        var waited = 0
        fun startWhenReady() {
            if (ui.alternatives.isNotEmpty() && ui.phase == Phase.PREVIEW) {
                Log.i(MockDrive.TAG, "trace route ready after ${waited * 250} ms; driving")
                startNavigation()
                return
            }
            waited += 1
            if (waited > 120) {   // 30 s
                Log.w(MockDrive.TAG, "no route to drive (phase=${ui.phase})")
                return
            }
            main.postDelayed({ startWhenReady() }, 250)
        }
        main.postDelayed({ startWhenReady() }, 250)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // Re-asserted rather than left to the `ui` setter alone: a recreated
        // activity (rotation, or a restore after the process was reclaimed)
        // gets a NEW window, and window flags do not survive it. If the phase
        // is restored without passing through a transition the setter never
        // fires, and the drive would resume with the screen free to sleep.
        applyKeepScreenOn()

        // V7.7, AC-19 D1: ask again. A release swapped underneath a running
        // app used to be invisible until the process restarted, which for a
        // navigation app can be days.
        //
        // Pending FIRST. A release found during a drive that has since ended
        // should be adopted now, not one round trip from now — and if the
        // check below finds the same release again it will read as unchanged,
        // which is correct rather than a second restyle.
        //
        // Note what this is NOT: a blanket "restyle on resume". Nothing here
        // rebuilds a style by itself. `applyKeepScreenOn` above is what a
        // lifecycle callback is allowed to do unconditionally; touching the
        // style is not, and the guard on that lives in ReleaseWatch.
        applyPendingRelease()
        checkRelease()
    }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        // BEFORE mapView.onDestroy(), and before anything else that could let a
        // frame or a callback run: after this line nothing may touch a native
        // MapLibre object. See `mapAlive` for the crash.
        mapAlive = false
        // Drop the sources as well as stopping the loop. The frame loop is the
        // path that crashed, but it is not the only thing that draws — the
        // location callback and four async replies do too — and a reference to
        // a destroyed native peer is a loaded gun. Null references make every
        // `?.setGeoJson` a no-op, which is the correct behaviour during
        // teardown.
        maplibre = null
        // The engine is going away; the car must show "not running" rather
        // than the last frame of a drive that has ended.
        NavBridge.detach()
        voice.shutdown()
        mock?.stop()
        main.removeCallbacksAndMessages(null)
        searchJob?.cancel()
        routeJob?.cancel()
        // The release check outlives nothing: `apply` re-checks `mapAlive`
        // anyway, but a request in flight during teardown is work nobody will
        // read.
        releaseJob?.cancel()
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
        // Only the SEARCH state. See the restore site in `onCreate` for why
        // `searched` and the results are deliberately not carried across: the
        // flag licenses a claim about the index, and a claim must not survive
        // the process that could support it.
        outState.putString(STATE_QUERY, ui.query)
        outState.putBoolean(STATE_SEARCHING, ui.searching)
    }

    private companion object {
        const val TAG = "Vector"

        /** The open search box's query, across a recreation. */
        const val STATE_QUERY = "vector.query"

        /** Whether the search box was open, across a recreation. */
        const val STATE_SEARCHING = "vector.searching"

        /**
         * Its own tag, so the release trail is one `logcat -s` away.
         *
         * AC-19 had to reconstruct which release each emulator phase was on by
         * correlating requested tile URLs against a pointer it swapped by
         * hand. Every event under this tag is a line that would have answered
         * it directly, and a verification harness can read the whole decision
         * trail without filtering the app's other logging.
         */
        const val RELEASE_TAG = "VectorRelease"

        /**
         * Style source ids, as the style document declares them.
         *
         * Named constants rather than literals at ten call sites, because
         * `source("puck")` and `source("pucks")` differ by a typo that would
         * silently stop drawing the vehicle and throw nothing — a missing
         * source reads as null, which is now a legitimate no-op. See
         * [source].
         */
        const val SRC_ROUTE = "route"
        const val SRC_ROUTE_ALT = "route-alt"
        const val SRC_PUCK = "puck"
        const val SRC_ENDPOINTS = "route-endpoints"
        const val SRC_ROUTE_LABELS = "route-labels"
        const val SRC_TRAFFIC = "traffic"
        const val SRC_WALK = "walk"

        /**
         * The walking NAVIGATION route (V7.4 4C final).
         *
         * Separate from [SRC_WALK], which is the Pro shade overlay: that
         * source's features carry a `shaded` property and its layers are
         * coloured by it, so drawing a navigation route into it would assert a
         * sun claim this stage does not compute.
         */
        const val SRC_WALK_ROUTE = "walk-route"
        const val SRC_CHEVRONS = "route-chevrons"
        const val SRC_CALLOUTS = "callouts"

        /**
         * Logcat tag for frame timings. Grepped by
         * `scripts/verify_on_device.sh`, so it is a contract.
         */
        const val FRAME_TAG = "VectorFrames"

        /**
         * Logcat tag for navigation state transitions. Grepped by
         * `scripts/simulate_drive.sh`, so the wording is a contract.
         */
        const val NAV_TAG = "VectorNav"

        /**
         * Logcat tag for the V7 Stage 3 camera telemetry. Debug builds only;
         * see [camTrace].
         */
        const val CAM_TAG = "VectorCam"

        /**
         * Logcat tag for V7 Stage 5 signal telemetry. Debug builds only;
         * see [signalTrace]. Filtered by `V7-STAGE5-EVIDENCE/drive_capture.py`.
         */
        const val SIG_TAG = "VectorSignals"

        /**
         * Logcat tag for V7.3 camera telemetry. Debug builds only; see
         * [cameraTrace]. Filtered by `V7.3-EVIDENCE/drive_capture.py`.
         *
         * Distinct from [CAM_TAG], which is Stage 3's camera-FSM movement
         * telemetry — two different things called "camera".
         */
        const val CAMERAS_TAG = "VectorCameras"
        const val FRAME_REPORT_MS = 5_000L
        const val DROPPED_PIN = "Dropped pin"

        /**
         * How long after the last keystroke a live search fires.
         *
         * Tuned against TYPING SPEED, not network latency — `/search` answers
         * locally in single-digit milliseconds, so the only thing being
         * smoothed out is the keystroke rate. Long enough that a fluent typist
         * produces one request per word rather than one per letter; short
         * enough that pausing feels like an answer rather than a wait.
         */
        const val SEARCH_DEBOUNCE_MS = 220L

        /**
         * Shortest query worth sending.
         *
         * One letter matches most of Qatar, so the results would be noise and
         * the request would be wasted.
         */
        const val MIN_QUERY_CHARS = 2
        /**
         * An empty source payload.
         *
         * Aliased rather than spelled twice: `VectorStyle` needs the same
         * string for the sources it builds itself, and two copies of a literal
         * that must be byte-identical is how one of them gains a space.
         */
        const val EMPTY_FC = VectorStyle.EMPTY_FEATURES

        /**
         * Marker colours.
         *
         * Held here rather than in [VectorStyle.Palette] because they do not
         * move with the theme: the vehicle is the same blue and the
         * destination the same red at noon and at midnight, for the reason
         * VectorStyle's KDoc already gives about the route line — a driver who
         * has learned what a colour means must not have to relearn it at dusk.
         */
        const val VEHICLE_FILL = 0xFF3B9CFF.toInt()
        const val VEHICLE_STROKE = 0xFFFFFFFF.toInt()
        const val DESTINATION_FILL = 0xFFE5484D.toInt()

        /**
         * The warning pill's fill and rim, matching `VectorStyle`'s
         * `CALLOUT_FILL` / `CALLOUT_RIM`.
         *
         * Held in two places because they have two hosts — the bitmap is drawn
         * with `android.graphics` and the text on it is coloured by the style
         * document — and the same split already exists for the vehicle. Both
         * are theme-independent for the reason §4.4 gives: a driver acts on a
         * warning, so it must not change appearance with the time of day.
         */
        const val CALLOUT_PILL_FILL = 0xFF141A24.toInt()
        const val CALLOUT_PILL_RIM = 0xFF8FA1B8.toInt()

        /**
         * How far past a maneuver to ask what the limit is.
         *
         * Far enough to be on the new road rather than inside the junction,
         * near enough that it is still the road the maneuver joins. A junction
         * on a Doha arterial is 30-40 m across.
         */
        const val SPEED_AHEAD_PROBE_M = 45.0

        /**
         * How far ahead and behind warnings are drawn.
         *
         * 1.2 km is about ninety seconds at arterial speed — far enough to plan
         * the next decision, near enough that the pill is about something the
         * driver is doing rather than something they will do after lunch.
         * 250 m behind keeps the one just passed from blinking out at the
         * moment it becomes the thing in the mirror.
         */
        const val CALLOUT_AHEAD_M = 1_200.0
        const val CALLOUT_BEHIND_M = 250.0
    }
}
