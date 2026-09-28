package dev.vector.android

import dev.vector.geo.LngLat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one seam between Vector's navigation engine and any second presentation
 * layer — today, Android Auto.
 *
 * ## Why this exists at all
 *
 * Every piece of navigation state Vector has already lives in exactly one
 * place: [UiState], rebuilt by [NavSession] on each fix and each frame and
 * assigned through `MainActivity.ui`'s setter. Routing, location, rerouting,
 * arrival detection, voice and persistence all hang off that one object. None
 * of it needs to be reimplemented for the car, and none of it is reimplemented
 * here.
 *
 * What the car DOES need is a way to read that object, because a
 * `CarAppService` runs in the same process as `MainActivity` but is a
 * different component with no reference to it. So this is a process-wide
 * holder: the Activity pushes each new [UiState] in, the car screens collect
 * it out, and commands travel back the other way.
 *
 * ## What it deliberately is not
 *
 * Not a second navigation engine, not a copy of the state, and not an
 * abstraction over [UiState]. It publishes the real object. A projection
 * ("CarNavState") would be a second definition of what a maneuver is, and the
 * first time the two disagreed the car would be confidently wrong — the one
 * failure mode a driver cannot check.
 *
 * ## The limitation this makes visible
 *
 * [state] is null whenever no Activity is attached, and that is honest rather
 * than incidental: Vector's engine is owned by `MainActivity`, so with the
 * phone UI destroyed there is no location loop, no route and nothing to show.
 * The car screens read the null and say so instead of drawing a frozen HUD.
 * Lifting the engine into a foreground service is the work that removes the
 * null, and it is the next real task — see `HANDOVER-V7-ANDROID-AUTO.md`.
 *
 * Thread-safety: `MutableStateFlow` and `MutableSharedFlow` are safe to touch
 * from any thread, which matters because the publisher is the main thread and
 * a car host callback need not be.
 */
object NavBridge {

    /**
     * The live navigation state, or null when no Activity is attached.
     *
     * Conflated by construction: a `StateFlow` keeps only the newest value, so
     * a car screen that invalidates slower than the 60 Hz frame loop publishes
     * simply misses intermediate states rather than queueing them. That is the
     * right behaviour for a HUD, and it is why this is not a `SharedFlow`.
     */
    private val _state = MutableStateFlow<UiState?>(null)
    val state: StateFlow<UiState?> = _state.asStateFlow()

    /** Shorthand for the current value, for callers that are not collecting. */
    val current: UiState? get() = _state.value

    /** True when a phone Activity is alive and driving the engine. */
    val attached: Boolean get() = _state.value != null

    /**
     * Things the car asks the phone to do.
     *
     * Commands, not state mutations. The car never writes [UiState]: it asks,
     * and the Activity — which owns the routing job, the prefs and the
     * MapLibre sources that all have to move together — decides. That keeps
     * the single-writer property that makes `MainActivity.ui`'s setter a
     * reliable log of every transition.
     */
    sealed interface Command {
        /**
         * Plan a route to [dest] and, once it is planned, start driving it.
         *
         * One command rather than "pick" then "start", because those are two
         * taps on the phone and the car flow has only one: a driver choosing a
         * destination on a car screen has already decided to go there.
         */
        data class NavigateTo(val dest: LngLat, val name: String) : Command

        /** End the current journey, as the phone's Cancel control does. */
        data object Stop : Command

        /**
         * Mute or unmute spoken guidance.
         *
         * A toggle rather than a mode, because the car strip has room for one
         * control and a driver at speed wants "stop talking", not a four-way
         * picker. The Activity resolves it against the real [VoiceMode], and
         * it writes to the same stored setting the phone shows — there is no
         * separate car mute.
         */
        data object ToggleVoice : Command
    }

    /**
     * Buffered so a command issued while the Activity is starting is not lost.
     *
     * `extraBufferCapacity` with the default `SUSPEND` overflow would suspend
     * the emitter; [request] uses `tryEmit`, so instead a full buffer means a
     * dropped command and a `false` return the caller can report. Eight is far
     * more than a driver can generate.
     */
    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    /** Called by the Activity on every state change, and nowhere else. */
    fun publish(ui: UiState) { _state.value = ui }

    /** Called by the Activity when it goes away. */
    fun detach() { _state.value = null }

    /**
     * Ask the phone to do something. False when nobody is listening.
     *
     * The false is not theoretical: it is exactly the "Vector is not open on
     * your phone" case, and the car screen turns it into a message rather than
     * a silent no-op.
     *
     * ## Why the subscriber count is part of the answer
     *
     * [_state] being non-null says an Activity EXISTS. It does not say anything
     * is collecting [commands] — and those are two different moments, because
     * `MainActivity` starts `collectCarCommands` inside the MapLibre
     * `getMapAsync` callback, deliberately (a NavigateTo needs a live map and a
     * fix to be actionable). Between the first `publish` and that callback there
     * is a real window where an Activity is attached and no collector is.
     *
     * A `MutableSharedFlow` with `replay = 0` drops what it emits into that
     * window and `tryEmit` STILL RETURNS TRUE — nothing is queued for a
     * subscriber that has not arrived yet. So the old condition reported success
     * for a command that had already been thrown away, and the car sat on a list
     * that did nothing when tapped: no route, no error, no message. Observed on
     * a head unit, where tapping a destination was simply inert.
     *
     * Checking `subscriptionCount` closes that hole the honest way. It is
     * deliberately NOT closed by giving the flow a replay buffer: a replayed
     * NavigateTo is a drive that starts by itself the moment the map finishes
     * loading, minutes after the driver gave up on the tap, and a navigation app
     * that sets off on its own is worse than one that admits it was not ready.
     *
     * This makes the failure visible, not impossible. The engine only being
     * reachable while an Activity holds it is the P0 in
     * `HANDOVER-V7-ANDROID-AUTO.md`, and hoisting it into a foreground service
     * is what actually removes the window.
     */
    fun request(command: Command): Boolean = when {
        _state.value == null -> false
        _commands.subscriptionCount.value == 0 -> false
        else -> _commands.tryEmit(command)
    }
}
