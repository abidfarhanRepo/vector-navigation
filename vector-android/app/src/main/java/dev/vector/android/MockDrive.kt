package dev.vector.android

import android.annotation.SuppressLint
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import dev.vector.geo.LngLat
import java.io.File

/**
 * Replays a recorded drive into the device's own fused-location provider.
 *
 * ## Why this exists, and why it is not a separate fake
 *
 * V3 and V4 both ended with the same release blocker: every claim about how
 * Vector behaves while moving came from a parked handset. `verify_on_device.sh`
 * said so out loud — *"a real handset needs a mock-location provider app; skip
 * rather than pretend"* — so the device harness measured a stationary map and
 * the moving numbers did not exist.
 *
 * The tempting shortcut is a debug switch that pushes positions straight into
 * [NavSession] and skips Android entirely. V5 §4 rules it out, and rightly:
 * that would test the navigation logic a second time (the JVM scenario suite
 * already does, exhaustively) while testing none of the things only a device
 * can be wrong about — the `LocationCallback` threading, `hasSpeed()` and
 * `hasAccuracy()` on real `Location` objects, MapLibre's renderer keeping up,
 * the frame loop under a live GL surface, thermal behaviour.
 *
 * So this uses Google's own mocking API. [FusedLocationProviderClient.setMockMode]
 * makes the provider deliver locations given to [FusedLocationProviderClient.setMockLocation]
 * instead of the ones the chipset produces. **Everything downstream is
 * unchanged production code**: the same `LocationRequest`, the same
 * `LocationCallback`, the same `Location` objects with the same optional
 * fields, the same plausibility gate, the same probe collection. The only thing
 * replaced is the satellites.
 *
 * ## Two independent gates, because this is a mock-position feature
 *
 * 1. **[BuildConfig.DEBUG].** Nothing here runs in a release build.
 * 2. **The operating system.** `setMockMode` throws `SecurityException` unless
 *    the app holds the `android:mock_location` app-op, which is granted only by
 *    selecting it as the mock location app in Developer Options, or by
 *    `adb shell appops set dev.vector.android android:mock_location allow`. A
 *    handset that has not been deliberately put in that state cannot be driven
 *    by this code even if it were reachable.
 *
 * ## Timing
 *
 * The trace carries its own timestamps and they are honoured, including the
 * gaps. A 25-second hole in the file is 25 seconds during which the provider
 * is handed nothing, which is what a tunnel is — and it is the only way to
 * exercise the stale-fix path, the frozen puck and the GPS-lost banner on real
 * hardware. Replay is therefore real-time by default; [speedup] exists for
 * shortening a 27 km trace, and the report says when it was used, because
 * compressing time hands the app more work per second than a real drive does
 * and a frame number taken under compression is not a frame number for a drive.
 */
class MockDrive(
    private val fused: FusedLocationProviderClient,
    private val onFinished: () -> Unit = {},
) {

    /** One row of a trace file. */
    data class Row(
        val tMs: Long,
        val lng: Double,
        val lat: Double,
        val speedMs: Double?,
        val bearingDeg: Double?,
        val accuracyM: Double,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var rows: List<Row> = emptyList()
    private var index = 0
    private var running = false
    private var setFailures = 0

    /**
     * Fixes handed to the provider so far.
     *
     * Reported in the STOP line, and it is not the same number as `rows.size`:
     * a fix the provider rejected is scheduled and not injected, so the two
     * coming apart is the signal that mock mode is not actually in force.
     */
    var injected = 0
        private set

    /** The first position in the trace, for a caller that needs to wait for it. */
    val firstPosition: LngLat? get() = rows.firstOrNull()?.let { LngLat(it.lng, it.lat) }

    /**
     * Read a trace.
     *
     * CSV, not JSON, and deliberately: the file is produced by a JVM test,
     * pushed by a shell script, and read here, so the format has to be
     * inspectable with `head` at every step. `tMs,lng,lat,speedMs,bearingDeg,accuracyM`,
     * with an EMPTY speed or bearing field meaning the receiver did not report
     * one — which is a distinct case from zero and the one Vector had a defect
     * in, so the format has to be able to express it.
     */
    fun load(path: String): Int = runCatching { loadOrThrow(path) }
        .onFailure {
            // A debug replay feature must never be able to take the app down.
            // It did: the first device run pushed a trace to /sdcard/Download,
            // which scoped storage does not let the app read, and the
            // FileNotFoundException from `readLines` propagated out of
            // onNewIntent and killed the process on launch. The trace now lives
            // in the app's own external files directory — which needs no
            // permission — and this is the belt as well as the braces.
            Log.w(TAG, "could not read $path: ${it.message}")
        }
        .getOrDefault(0)

    private fun loadOrThrow(path: String): Int {
        val f = File(path)
        if (!f.exists() || !f.canRead()) {
            Log.w(TAG, "no readable trace at $path")
            return 0
        }
        rows = f.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("tMs") }
            .mapNotNull { line ->
                val p = line.split(',')
                if (p.size < 6) return@mapNotNull null
                Row(
                    tMs = p[0].toLongOrNull() ?: return@mapNotNull null,
                    lng = p[1].toDoubleOrNull() ?: return@mapNotNull null,
                    lat = p[2].toDoubleOrNull() ?: return@mapNotNull null,
                    speedMs = p[3].takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                    bearingDeg = p[4].takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                    accuracyM = p[5].toDoubleOrNull() ?: 5.0,
                )
            }
            .toList()
        Log.i(TAG, "loaded ${rows.size} fixes from ${f.name}")
        return rows.size
    }

    /**
     * Start replaying, at [speedup] times real time.
     *
     * @return false when the OS refused mock mode, which is the normal answer
     *   on a handset nobody has put into that state. Reported rather than
     *   thrown: a harness that cannot inject should say so and carry on
     *   measuring what it can, not crash the app.
     */
    @SuppressLint("MissingPermission")
    fun start(speedup: Double = 1.0): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (rows.isEmpty()) return false
        return try {
            // AWAITED, not fired and forgotten.
            //
            // `setMockMode` returns a Task and the provider is not in mock mode
            // until it completes. The first version scheduled the trace on the
            // next line, so every fix in the first window — and on the first
            // device run, EVERY fix — was handed to a provider that was still
            // in live mode and silently dropped. The symptom was a drive that
            // reported 466 injected fixes, 21 frame windows, and not one
            // navigation event: the app had a route, was in NAVIGATING, and
            // never received a position.
            fused.setMockMode(true)
                .addOnSuccessListener {
                    running = true
                    index = 0
                    injected = 0
                    setFailures = 0
                    Log.i(TAG, "START fixes=${rows.size} speedup=$speedup " +
                        "duration=${rows.last().tMs / 1000}s")
                    schedule(speedup, SystemClock.uptimeMillis())
                }
                .addOnFailureListener {
                    Log.w(TAG, "setMockMode failed: ${it.message}")
                }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "mock location refused: ${e.message}. " +
                "Run: adb shell appops set ${BuildConfig.APPLICATION_ID} android:mock_location allow")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { fused.setMockMode(false) }
        Log.i(TAG, "STOP after $index scheduled, $injected injected")
    }

    /**
     * Post each fix at its own trace time, measured from one fixed origin.
     *
     * Not `postDelayed(interval)` chained from the previous callback: that
     * accumulates every scheduling delay, so a 12-minute trace drifts and the
     * gap that was supposed to be a 25-second outage becomes 31 seconds. Each
     * row is scheduled against [startedAt] instead, so the errors do not
     * compound and the outage is the length the trace says it is.
     */
    private fun schedule(speedup: Double, startedAt: Long) {
        if (!running || index >= rows.size) {
            if (running) {
                Log.i(TAG, "END after ${rows.size} fixes")
                stop()
                onFinished()
            }
            return
        }
        val row = rows[index]
        val due = startedAt + (row.tMs / speedup).toLong()
        handler.postAtTime({
            if (!running) return@postAtTime
            inject(row)
            index++
            schedule(speedup, startedAt)
        }, due.coerceAtLeast(SystemClock.uptimeMillis()))
    }

    @SuppressLint("MissingPermission")
    private fun inject(row: Row) {
        val loc = Location(PROVIDER).apply {
            longitude = row.lng
            latitude = row.lat
            accuracy = row.accuracyM.toFloat()
            // Real time, not trace time. The app compares fix timestamps
            // against its own clock to decide whether positioning has gone
            // stale, so a trace-relative timestamp would make every fix look
            // decades old and the GPS-lost banner would never come down.
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            // Set only when the trace has them. This is the whole point of the
            // empty-field convention: `hasSpeed()` returning false is a real
            // receiver behaviour and Vector's response to it was wrong in two
            // places until V5.
            row.speedMs?.let { speed = it.toFloat() }
            row.bearingDeg?.let { bearing = it.toFloat() }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                row.speedMs?.let { speedAccuracyMetersPerSecond = 0.5f }
                row.bearingDeg?.let { bearingAccuracyDegrees = 5f }
            }
        }
        // The Task's failure, not just a thrown exception. A rejected
        // setMockLocation fails asynchronously, so `runCatching` around the
        // call sees nothing at all and a whole trace can disappear quietly.
        // Logged for the first few only: one line per fix would be 1 732 lines
        // on the long run and would push everything else out of the buffer.
        runCatching {
            fused.setMockLocation(loc).addOnFailureListener {
                if (setFailures++ < 3) Log.w(TAG, "setMockLocation rejected: ${it.message}")
            }
            injected++
        }.onFailure { Log.w(TAG, "setMockLocation threw: ${it.message}") }
    }

    companion object {
        const val TAG = "VectorMock"

        /**
         * Take the device's fused provider out of mock mode, if it is stuck in it.
         *
         * ## The defect this exists because of, which cost a handset 14 hours
         *
         * Mock mode is a claim held by GMS on behalf of an app, and it is
         * cleared by [stop] — which the app calls from `onDestroy` and at the
         * end of a trace. `simulate_all.sh` **force-stops the app between
         * scenarios**, and a force-stop runs no lifecycle callbacks at all, so
         * `stop()` never ran and the claim was never released. The V5 batch
         * ended by killing the app in the middle of `long-run`.
         *
         * The consequence is not scoped to Vector. GMS's `MockLocationEngine`
         * goes on believing the fused provider is mocked, with nobody feeding
         * it, so **the fused provider stops producing locations for every app
         * on the device**. Measured on the S24 the morning after: both the GNSS
         * and network providers' last fix was 14 h 44 m old, GMS still held
         * `{fused, mock, 25.245259,51.600181, spd=27.78, brg=243.19}` — the
         * final fix of `scenario-j-highway` — and Vector sat on "Searching for
         * GPS" forever, which is what it was reported as. Uninstalling Vector
         * makes it WORSE: the app loses the `android:mock_location` app-op with
         * the claim still outstanding, so nothing on the device can release it
         * short of toggling location off and on and restarting GMS.
         *
         * It also poisons the harness's own measurements, which is the reason
         * to care beyond the inconvenience. Every scenario after the first ran
         * against a provider left in a state by its predecessor, and the V5
         * batch's unexplained results are consistent with it: `j-highway`
         * waited **sixty seconds** for its first accepted fix, which its own
         * handover recorded as "the cause is not established".
         *
         * ## Why the app clears it and not the script
         *
         * Only the app holding the app-op can release the claim — a shell
         * cannot. So the release has to happen the next time the app starts,
         * before it does anything else, and it has to happen whether or not
         * this launch is a replay. That makes a stuck provider self-healing
         * after exactly one launch instead of a device that needs a reboot.
         *
         * Debug-only, like everything else here, and silent about the ordinary
         * failure: on a handset that was never put into mock mode the app does
         * not hold the app-op and this throws, which is the correct answer and
         * not worth a log line on every launch.
         */
        @SuppressLint("MissingPermission")
        fun releaseStaleMockMode(fused: FusedLocationProviderClient) {
            if (!BuildConfig.DEBUG) return
            runCatching {
                fused.setMockMode(false)
                    .addOnSuccessListener { Log.i(TAG, "released a stale mock-mode claim") }
            }
        }

        /**
         * `Location`'s provider string.
         *
         * "fused", matching what the real provider stamps on its own results,
         * so nothing downstream can behave differently for a replayed fix than
         * for a live one. A distinctive name here would be a way for the app to
         * know it is being tested, which V5 §18 rules out.
         */
        const val PROVIDER = "fused"
    }
}
