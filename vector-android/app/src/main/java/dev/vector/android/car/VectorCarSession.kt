package dev.vector.android.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.ScreenManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.vector.android.NavBridge
import dev.vector.android.Phase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One connection to a car screen, for as long as the car is connected.
 *
 * The session owns the two things that outlive any single screen: the
 * host's [NavigationManager] — which drives the cluster display, the
 * turn card the head unit shows over other apps, and the host's own idea
 * of whether a navigation app is currently guiding — and the rule about
 * which screen should be on top.
 *
 * It does not own any navigation. Every value it forwards comes from
 * [NavBridge], which is the phone's live [dev.vector.android.UiState].
 */
class VectorCarSession : Session() {

    private val navigationManager: NavigationManager
        get() = carContext.getCarService(NavigationManager::class.java)

    /**
     * Whether we have told the host that a journey is running.
     *
     * Tracked rather than inferred, because `navigationStarted()` and
     * `navigationEnded()` are edge-triggered and the host throws if they are
     * called out of order. The phase, by contrast, is republished continuously.
     */
    private var declaredNavigating = false

    private val screens: ScreenManager
        get() = carContext.getCarService(ScreenManager::class.java)

    override fun onCreateScreen(intent: Intent): Screen {
        // Session is a LifecycleOwner rather than a component with an
        // onDestroy to override, so teardown is registered as an observer.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (declaredNavigating) {
                    declaredNavigating = false
                    runCatching { navigationManager.navigationEnded() }
                }
                // The phone keeps navigating. Unplugging from the car is not a
                // decision to stop driving, and ending the journey here would
                // be the car deleting a drive the driver is still on.
            }
        })

        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            /**
             * The host is taking navigation away — another nav app started, or
             * the driver ended the drive from the car's own controls.
             *
             * Routed through the bridge exactly like a tap on Vector's End
             * button, so the drive is recorded in the history and the journey
             * is cleared from prefs. Anything else would leave the phone
             * navigating a route the car has already forgotten.
             */
            override fun onStopNavigation() {
                NavBridge.request(NavBridge.Command.Stop)
            }

            /**
             * The host's automated test mode. Vector has no simulated-drive
             * path it can hand the car, so this is deliberately a no-op rather
             * than a half-answer — the phone-side mock drive is a debug-only
             * intent and does not belong in a vehicle.
             */
            override fun onAutoDriveEnabled() = Unit
        })

        // Keep the host's trip in step with the engine, for the whole session.
        lifecycleScope.launch { publishTrip() }
        // And keep the right screen on top when navigation starts or ends
        // somewhere else — on the phone, or by arrival.
        lifecycleScope.launch { followPhase() }

        return if (CarTrip.navigating(NavBridge.current)) {
            CarNavScreen(carContext)
        } else {
            CarDestinationsScreen(carContext)
        }
    }

    /**
     * Mirror the drive into the host's navigation manager.
     *
     * Deliberately keyed off [CarTrip.renderKey] rather than off every
     * published state: `updateTrip` is an IPC call, and the engine republishes
     * at frame rate. See the KDoc on that function.
     */
    private suspend fun publishTrip() {
        NavBridge.state
            .map { ui -> ui?.takeIf { it.phase == Phase.NAVIGATING } }
            .distinctUntilChanged { a, b ->
                if (a == null || b == null) a === b else CarTrip.renderKey(a) == CarTrip.renderKey(b)
            }
            .collect { ui ->
                if (ui == null) {
                    if (declaredNavigating) {
                        declaredNavigating = false
                        runCatching { navigationManager.navigationEnded() }
                    }
                    return@collect
                }
                if (!declaredNavigating) {
                    declaredNavigating = true
                    runCatching { navigationManager.navigationStarted() }
                }
                runCatching { navigationManager.updateTrip(CarTrip.trip(ui, CarTrip.now())) }
            }
    }

    /**
     * Push the navigation screen when a drive starts, pop it when one ends.
     *
     * Both transitions can originate on the phone — the driver may start a
     * route in their hand before pulling away — so the car follows the engine
     * rather than assuming its own screens caused the change.
     */
    private suspend fun followPhase() {
        NavBridge.state
            .map { it?.phase }
            .distinctUntilChanged()
            .collect { phase ->
                // ScreenManager.getTop() THROWS on an empty stack, and this
                // collector is launched from onCreateScreen -- so its first
                // emission arrives before the root screen that function
                // returns has been pushed. That raced every single time the
                // host bound the service with a live engine behind it:
                //
                //   NullPointerException
                //     at androidx.car.app.ScreenManager.getTop
                //     at VectorCarSession$followPhase$3.emit
                //
                // which killed the app process and left the car screen black.
                //
                // Skipping until a root exists is correct rather than merely
                // safe: onCreateScreen already picks CarNavScreen or
                // CarDestinationsScreen from the very phase this emission
                // carries, so there is nothing for the follower to fix yet.
                val top = runCatching { screens.top }.getOrNull() ?: return@collect
                if (phase == Phase.NAVIGATING) {
                    if (top !is CarNavScreen) screens.push(CarNavScreen(carContext))
                } else if (top is CarNavScreen) {
                    // popToRoot rather than pop: the driver may have pushed
                    // screens under the nav screen before setting off, and a
                    // finished drive should land them back at the top of the
                    // app, not part-way down a stack they have forgotten.
                    screens.popToRoot()
                }
            }
    }

    override fun onNewIntent(intent: Intent) {
        // "Navigate to X" handed over by the host (an Assistant query, a tap
        // in another car app). Vector cannot geocode a free-text car intent
        // yet — see the handover's P1 list — so the driver is put in front of
        // the list they can act on rather than being shown nothing.
        if (!CarTrip.navigating(NavBridge.current) && screens.top !is CarDestinationsScreen) {
            screens.popToRoot()
        }
    }
}
