package dev.vector.android

import android.content.Context
import android.content.SharedPreferences
import dev.vector.geo.Units

/**
 * Everything the driver has chosen, and nothing else.
 *
 * The app had no settings at all before this. Theme was a compile-time
 * constant, the camera was four hardcoded ternaries, units were metric by
 * construction, and voice was a pill that reset to on at every launch. So
 * every preference a driver expressed lasted exactly as long as the process
 * did.
 *
 * ## What is deliberately NOT here
 *
 * The V3 brief warns against a settings screen assembled from whatever could
 * be made configurable. Each field below changes something a driver would
 * actually go looking for; these were considered and rejected:
 *
 * * **Voice volume** — the platform already owns it, on the hardware buttons,
 *   with a media-stream slider the driver already knows. A second volume in the
 *   app would be a second thing to be wrong.
 * * **Voice language** — [VoiceGuide] already picks Arabic first because Doha
 *   street names are Arabic, and falls back to the device locale. A manual
 *   override would let a driver choose a voice that cannot pronounce the names
 *   in the instructions.
 * * **Route preferences and avoidances** (tolls, motorways, ferries) — the
 *   router has no avoidance parameters, so the control would be inert. That is
 *   the trap this codebase has hit repeatedly: a UI wired to nothing, passing
 *   its own tests forever.
 * * **Map detail / label density** — the tile bake decides this, per zoom, and
 *   a client-side override would fight the zoom tiers rather than add to them.
 *
 * ## Persistence
 *
 * One [SharedPreferences] file, read once at launch and written on every
 * change. `contributing` lives here too, moved out of `MainActivity`'s ad-hoc
 * read of the same file, so there is one place that knows the storage keys.
 * Its default stays **off** (adr-0068 makes collection opt-in, and a default-on
 * switch is not consent).
 */
/**
 * How much Vector says out loud.
 *
 * ## Why four values and not a boolean
 *
 * Vector had `voiceEnabled: Boolean`, which offers a driver two settings —
 * "narrate everything" and "silence" — and the second one is what most people
 * pick because the first one is exhausting on a route they half know.
 *
 * Waze exposes exactly these four under "Spoken directions", with icons that
 * encode the amount of sound rather than decorating the row: three waves, one
 * wave, an exclamation, a slash (`v4-evidence/waze/13-voice-sound.png`). §27
 * of the V4 brief proposes the same four, and running the reference confirmed
 * the shape rather than suggesting it.
 *
 * ## Each one has to MEAN something
 *
 * The trap here is the one `Settings`' own KDoc names: a control wired to
 * nothing, passing its own tests forever. So each mode maps onto behaviour that
 * already exists in [dev.vector.geo.ManeuverAnnouncer], which announces each
 * maneuver at decreasing distances:
 *
 * * [FULL] — every announcement tier. What the boolean's `true` did.
 * * [BRIEF] — the near tiers only. The far "in two kilometres, keep left" is
 *   the one most drivers find chatty, and dropping it does not cost safety:
 *   the maneuver is still announced in time to act on.
 * * [ALERTS] — no maneuver speech at all, but rerouting, going off route and
 *   arrival still speak. This is the mode for a commute you know by heart,
 *   where the only thing worth interrupting you for is that something has
 *   changed.
 * * [OFF] — silence.
 */
enum class VoiceMode(
    val label: String,
    val glyph: VectorIcons.Glyph,
) {
    FULL("Full guidance", VectorIcons.Glyph.VOICE_FULL),
    BRIEF("Brief", VectorIcons.Glyph.VOICE_BRIEF),
    ALERTS("Alerts only", VectorIcons.Glyph.VOICE_ALERTS),
    OFF("Off", VectorIcons.Glyph.VOICE_OFF),
    ;

    /** True when maneuver instructions are spoken at all. */
    val speaksManeuvers: Boolean get() = this == FULL || this == BRIEF

    /**
     * True when a change of state is spoken — rerouting, off route, arrival,
     * and a camera warning.
     *
     * Everything except [OFF]. "Alerts only" exists precisely for this and
     * nothing else.
     */
    val speaksAlerts: Boolean get() = this != OFF

    /**
     * True when the earliest announcement stage is spoken.
     *
     * This is the whole difference between [FULL] and [BRIEF], and it is a
     * behaviour rather than a label. `ManeuverAnnouncer.Stage.PREPARE` is the
     * one that scales with speed and fires up to 1500 m out — "in one
     * kilometre, keep left" — which is the announcement drivers describe as
     * chatty. `TURN` and `NOW` still fire in [BRIEF], so the maneuver is always
     * announced in time to act on: this trades warning for quiet, not safety.
     */
    val speaksPrepareStage: Boolean get() = this == FULL

    /** Cycle, for the one-tap HUD control. */
    fun next(): VoiceMode = entries[(ordinal + 1) % entries.size]
}

data class Settings(
    val theme: VectorStyle.MapTheme = VectorStyle.MapTheme.SYSTEM,
    val orientation: MapOrientation = MapOrientation.HEADING_UP,
    val perspective: MapPerspective = MapPerspective.TILTED,
    val units: Units = Units.METRIC,
    /**
     * How much is spoken. Replaces `voiceEnabled: Boolean` — see [VoiceMode].
     *
     * Defaults to [VoiceMode.FULL], which is what `voiceEnabled = true` meant,
     * so a driver who never opens settings hears exactly what they heard before.
     */
    val voice: VoiceMode = VoiceMode.FULL,
    /**
     * Draw congestion while exploring, not only while navigating.
     *
     * Default ON, unlike everything else that costs a request: traffic before
     * you set off is the reason to look at a map at all in a city, and the
     * previous behaviour — traffic only after tapping Start — meant the layer
     * was invisible at the one moment it could change the driver's plan.
     */
    val trafficInExplore: Boolean = true,

    /**
     * Let the map zoom itself out at speed and back in for junctions.
     *
     * Default ON, matching Waze, which ships `Auto zoom` enabled
     * (`v4-evidence/waze/12-map-display.png`). Vector had a single hardcoded
     * navigation zoom of 16.5 for every situation — a reasonable city zoom that
     * shows about eight seconds of road at 120 km/h.
     *
     * Offered as a setting rather than imposed because it is the one camera
     * behaviour that takes a decision away from the driver, and V3's whole
     * camera rework was about stopping the camera from fighting them. The
     * invariant it must not break is that **zoom is still asserted only at
     * discrete transitions**, never on a followed frame — see
     * [MapCamera.transitionZoom].
     */
    val autoZoom: Boolean = true,
    val contributing: Boolean = false,
) {
    /**
     * The theme to actually render, given whether the system is in dark mode.
     *
     * [VectorStyle.MapTheme.SYSTEM] is a preference, not a palette; resolving
     * it needs the device configuration, which is why this takes the answer as
     * an argument rather than reaching for a Context.
     */
    fun resolvedTheme(systemInDark: Boolean): VectorStyle.MapTheme = when (theme) {
        VectorStyle.MapTheme.SYSTEM ->
            if (systemInDark) VectorStyle.MapTheme.DARK else VectorStyle.MapTheme.LIGHT
        else -> theme
    }

    companion object {
        private const val FILE = "vector"
        private const val K_THEME = "theme"
        private const val K_ORIENTATION = "orientation"
        private const val K_PERSPECTIVE = "perspective"
        private const val K_UNITS = "units"
        /** The old boolean. Still READ, for migration. See [load]. */
        private const val K_VOICE_ENABLED = "voice"
        private const val K_VOICE_MODE = "voice_mode"
        private const val K_TRAFFIC_EXPLORE = "traffic_explore"
        private const val K_AUTO_ZOOM = "auto_zoom"
        private const val K_CONTRIBUTING = "contributing"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        /**
         * Read the stored settings.
         *
         * Every field falls back to its default rather than throwing. A
         * preferences file written by an older build — or corrupted, or holding
         * an enum name that has since been renamed — must not stop the app
         * launching, because a navigator that will not start is a worse failure
         * than one that starts with the wrong theme.
         */
        fun load(p: SharedPreferences): Settings {
            val d = Settings()
            return Settings(
                theme = enumOr(p.getString(K_THEME, null), d.theme),
                orientation = enumOr(p.getString(K_ORIENTATION, null), d.orientation),
                perspective = enumOr(p.getString(K_PERSPECTIVE, null), d.perspective),
                units = enumOr(p.getString(K_UNITS, null), d.units),
                // Migration, not a fresh default.
                //
                // Every existing install has `voice: Boolean` in its
                // preferences file and no `voice_mode`. Reading the new key
                // with a plain default would silently reset to FULL every
                // driver who had muted the app — which is the exact class of
                // bug V3 fixed when voice used to reset to ON at every launch,
                // and it would be embarrassing to reintroduce it while adding
                // the setting that replaces it.
                //
                // So: use the new key when it is there, otherwise translate the
                // old boolean, otherwise the default.
                voice = p.getString(K_VOICE_MODE, null)
                    ?.let { runCatching { enumValueOf<VoiceMode>(it) }.getOrNull() }
                    ?: if (p.contains(K_VOICE_ENABLED)) {
                        if (p.getBoolean(K_VOICE_ENABLED, true)) VoiceMode.FULL else VoiceMode.OFF
                    } else d.voice,
                trafficInExplore = p.getBoolean(K_TRAFFIC_EXPLORE, d.trafficInExplore),
                autoZoom = p.getBoolean(K_AUTO_ZOOM, d.autoZoom),
                contributing = p.getBoolean(K_CONTRIBUTING, d.contributing),
            )
        }

        /**
         * Write the settings.
         *
         * ## Why `commit()` and not `apply()`
         *
         * `apply()` updates the in-memory map immediately and writes the file
         * on a background thread. Within one process that is invisible — every
         * subsequent `load` sees the new value whether the file has been
         * written or not — which is exactly why the gap is easy to miss and why
         * every store in this file used to use it.
         *
         * The gap is real when the process **dies before the write lands**.
         * Android flushes pending `apply()` work in `onPause`/`onStop`
         * (`QueuedWork.waitToFinish`), so an ordinary backgrounding is safe;
         * a force-stop, a crash, or the OS reclaiming the process while the app
         * is still in the foreground is not. V6 §14 asks for precisely that
         * case — *"app killed immediately after changing a setting"* — and the
         * requirement it sets is that Vector must never claim to have saved
         * something it then forgets.
         *
         * The cost is a synchronous write of a preferences file that holds
         * about a dozen short values, on a path that runs when a driver taps a
         * settings row. It is not on the fix path, not on the frame path, and
         * not in a loop. Correctness is worth more than the microseconds here,
         * and every other store below makes the same trade for the same
         * reason.
         */
        fun save(p: SharedPreferences, s: Settings) {
            p.edit()
                .putString(K_THEME, s.theme.name)
                .putString(K_ORIENTATION, s.orientation.name)
                .putString(K_PERSPECTIVE, s.perspective.name)
                .putString(K_UNITS, s.units.name)
                .putString(K_VOICE_MODE, s.voice.name)
                // The old key is kept in step so that downgrading to a V3 build
                // does not un-mute a driver who muted in V4.
                .putBoolean(K_VOICE_ENABLED, s.voice != VoiceMode.OFF)
                .putBoolean(K_TRAFFIC_EXPLORE, s.trafficInExplore)
                .putBoolean(K_AUTO_ZOOM, s.autoZoom)
                .putBoolean(K_CONTRIBUTING, s.contributing)
                .commit()
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            if (name == null) fallback
            else runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)
    }
}

/**
 * Destinations the driver has been to, most recent first.
 *
 * A navigator with no memory makes the driver retype their commute every
 * morning; Android had none. Deliberately the smallest version of the feature
 * that is worth having — a bounded list, no editing, no sync, no "home" and
 * "work" slots — because the next step up (saved places with names and icons)
 * is a screen of its own and this is not.
 *
 * Stored in the same preferences file as one delimited string. A [Recents] list
 * is at most [LIMIT] entries, so the alternative (a database, a JSON blob, a
 * migration) would cost more than it can possibly save.
 *
 * Coordinates are kept at 5 decimal places, about a metre. That is enough to
 * re-route to the same door and, unlike full precision, it does not make the
 * list a higher-fidelity location history than the privacy gate would ever
 * accept from a probe upload (adr-0065). This list never leaves the device.
 */
object Recents {

    const val LIMIT = 8
    private const val KEY = "recents"

    /** A record separator that cannot occur in an OSM name. */
    private const val RS = "\u001E"
    private const val US = "\u001F"

    data class Entry(val name: String, val lng: Double, val lat: Double)

    fun load(p: SharedPreferences): List<Entry> {
        val raw = p.getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(RS).mapNotNull { rec ->
            val parts = rec.split(US)
            if (parts.size != 3) return@mapNotNull null
            val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            Entry(parts[0], lng, lat)
        }.take(LIMIT)
    }

    /**
     * Put a destination at the front.
     *
     * De-duplicated by POSITION rather than by name, rounded to the stored
     * precision: the same place reached once from a search result and once from
     * a dropped pin has two different names ("Villaggio Mall", "Dropped pin")
     * and is one destination. Keeping the newer name means a pin that later
     * gets reverse-geocoded replaces its own placeholder.
     */
    fun add(p: SharedPreferences, entry: Entry): List<Entry> {
        val rounded = entry.copy(
            lng = round5(entry.lng),
            lat = round5(entry.lat),
        )
        val kept = load(p).filterNot {
            round5(it.lng) == rounded.lng && round5(it.lat) == rounded.lat
        }
        val next = (listOf(rounded) + kept).take(LIMIT)
        // commit(), not apply() — see Settings.save. §14: "app killed
        // immediately after selecting a destination".
        p.edit().putString(
            KEY,
            next.joinToString(RS) { "${it.name.replace(RS, " ").replace(US, " ")}$US${it.lng}$US${it.lat}" },
        ).commit()
        return next
    }

    fun clear(p: SharedPreferences) {
        p.edit().remove(KEY).commit()
    }

    private fun round5(v: Double): Double = Math.round(v * 100_000.0) / 100_000.0
}

/**
 * The journey in progress, so it survives the process dying.
 *
 * ## Why this is a robustness feature and not a convenience one
 *
 * Android kills backgrounded processes, and a navigation app is backgrounded
 * every time the driver takes a call. Vector held the whole journey — the
 * destination, the route, the maneuvers — in `UiState` and nowhere else, so a
 * process death mid-drive lost it completely: the app came back to an empty map
 * and the driver, at speed, had to search for their destination again. It was
 * on V4's own list of known limitations ("restore navigation after process
 * death") and it is what V5 §16's "navigation state restored" asks for.
 *
 * ## What is stored, and what deliberately is not
 *
 * Only the DESTINATION and its name. Not the route geometry, and that is the
 * design rather than a shortcut: by the time the app is back the driver has
 * moved, so a stored route would begin somewhere they no longer are and its
 * first instructions would be wrong. Asking the router again from the current
 * position costs one request and produces a route that is actually correct —
 * and it reuses the ordinary destination path, so there is no second, less
 * tested way to enter a journey.
 *
 * ## Why it resumes into PREVIEW rather than into NAVIGATING
 *
 * Coming back into a locked follow camera, a 45-degree tilt and a voice giving
 * instructions, without the driver having touched anything, is a startling
 * thing to do to somebody in a moving car. The route is restored, drawn and
 * named; resuming the drive is one tap. Google Maps makes the same call.
 *
 * Privacy: a destination is already persisted by [Recents], under the same
 * consent, and this holds exactly one of them. Cleared the moment the journey
 * ends for any reason — arrival, cancellation, or the window expiring.
 */
object Journey {

    private const val KEY_LNG = "journey_lng"
    private const val KEY_LAT = "journey_lat"
    private const val KEY_NAME = "journey_name"
    private const val KEY_AT = "journey_at"

    /**
     * How long a journey is worth offering to resume.
     *
     * Half an hour. Long enough to cover a call, a petrol stop, or the app
     * being killed and the phone put down for a while; short enough that
     * yesterday's commute is not waiting on the screen this morning.
     */
    const val RESUME_WINDOW_MS = 30 * 60 * 1000L

    data class Saved(val name: String, val lng: Double, val lat: Double, val atMs: Long)

    fun save(p: SharedPreferences, name: String, lng: Double, lat: Double, nowMs: Long) {
        p.edit()
            .putFloat(KEY_LNG, lng.toFloat())
            .putFloat(KEY_LAT, lat.toFloat())
            .putString(KEY_NAME, name)
            .putLong(KEY_AT, nowMs)
            // commit(), not apply(). This store exists ONLY to survive the
            // process dying, so an asynchronous write is the one thing it
            // cannot afford: a process killed in the second after Start is
            // exactly the case the feature is for. See Settings.save.
            .commit()
    }

    /**
     * The journey to resume, or null.
     *
     * Floats, not doubles: `SharedPreferences` has no double, and a float holds
     * a coordinate to about a metre at Doha's latitude — far finer than the
     * router's own endpoint snapping, which moves destinations tens of metres.
     * Storing a stringified double would be more precise and less honest about
     * what that precision is worth.
     */
    fun load(p: SharedPreferences, nowMs: Long): Saved? {
        val at = p.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        // Also rejects a clock that has gone backwards, which happens across a
        // reboot and would otherwise leave a journey that can never expire.
        if (nowMs - at !in 0..RESUME_WINDOW_MS) {
            clear(p)
            return null
        }
        val lng = p.getFloat(KEY_LNG, Float.NaN)
        val lat = p.getFloat(KEY_LAT, Float.NaN)
        if (lng.isNaN() || lat.isNaN()) return null
        return Saved(
            name = p.getString(KEY_NAME, "") ?: "",
            lng = lng.toDouble(),
            lat = lat.toDouble(),
            atMs = at,
        )
    }

    fun clear(p: SharedPreferences) {
        p.edit().remove(KEY_LNG).remove(KEY_LAT).remove(KEY_NAME).remove(KEY_AT).commit()
    }
}

/**
 * Home and Work.
 *
 * ## Why only two, and why they are not "favourites"
 *
 * V4's handover called this "the cheapest large win in the product" and put it
 * on the list of three features with working backends and no client. It is
 * cheap because the backend involved is the one Vector already has —
 * `pickPoint` routes to a coordinate — and large because of what a driver
 * actually does: the two destinations they go to repeatedly are home and work,
 * and every other saved place is a search away.
 *
 * A general "favourites" list was considered and refused. It needs naming,
 * editing, ordering and deleting — four screens for a list [Recents] already
 * covers most of, since a place driven to last week is in it. Two named slots
 * need none of that: a slot is set by pointing at a destination and saying
 * which one, and re-set the same way.
 *
 * ## Where they are set
 *
 * From PREVIEW, on the destination chip, once a destination exists — which is
 * the only moment the app knows a coordinate the driver has deliberately
 * chosen. Setting them from a settings screen would need a place picker, and a
 * place picker is a second search UI.
 *
 * Stored the same way as [Recents] and under the same consent: a destination is
 * already persisted there, and this holds two of them by name.
 */
object Places {

    /** The two slots. Deliberately not extensible; see the class KDoc. */
    enum class Slot(val label: String) {
        HOME("Home"),
        WORK("Work"),
    }

    data class Saved(val slot: Slot, val name: String, val lng: Double, val lat: Double)

    private const val US = "\u001F"

    private fun key(slot: Slot) = "place_${slot.name.lowercase()}"

    /**
     * Both slots, in enum order, omitting the unset ones.
     *
     * Enum order rather than insertion order so the two rows do not swap places
     * when one is re-set — a list whose items move is a list a driver has to
     * read before tapping.
     */
    fun load(p: SharedPreferences): List<Saved> = Slot.entries.mapNotNull { slot ->
        val raw = p.getString(key(slot), null) ?: return@mapNotNull null
        val parts = raw.split(US)
        if (parts.size != 3) return@mapNotNull null
        val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        val lat = parts[2].toDoubleOrNull() ?: return@mapNotNull null
        Saved(slot, parts[0], lng, lat)
    }

    fun get(p: SharedPreferences, slot: Slot): Saved? = load(p).firstOrNull { it.slot == slot }

    /**
     * Set a slot, replacing whatever was there.
     *
     * No confirmation, and the control that calls this shows which slots are
     * already set — so "overwrite Home" is a decision the driver makes before
     * tapping rather than a dialog after it. The same reasoning as [Recents]
     * de-duplicating silently.
     */
    fun save(p: SharedPreferences, slot: Slot, name: String, lng: Double, lat: Double): List<Saved> {
        val clean = name.replace(US, " ").ifBlank { slot.label }
        // commit(), not apply() — see Settings.save. §14 names this case
        // directly: "app killed immediately after saving Home/Work".
        p.edit().putString(key(slot), "$clean$US$lng$US$lat").commit()
        return load(p)
    }

    fun clear(p: SharedPreferences, slot: Slot): List<Saved> {
        p.edit().remove(key(slot)).commit()
        return load(p)
    }
}
