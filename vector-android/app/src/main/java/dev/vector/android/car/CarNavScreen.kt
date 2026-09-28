package dev.vector.android.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.lifecycle.lifecycleScope
import dev.vector.android.NavBridge
import dev.vector.android.Phase
import dev.vector.android.VoiceMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The drive, on the car screen.
 *
 * A [NavigationTemplate], which is the one template the host renders as a
 * turn card with the app's map behind it — and the only one whose contents
 * the head unit will also mirror into the instrument cluster.
 *
 * Everything on it is read from [NavBridge]. There is no timer here, no
 * distance arithmetic and no idea of what the next turn is: the phone's
 * `NavSession` decided all of that on the same frame the phone HUD drew, and
 * this screen renders the answer.
 *
 * ## What is deliberately missing
 *
 * The background is the template's own colour, not Vector's map. Drawing the
 * map means taking the car's `Surface` and rendering MapLibre into it off the
 * phone's own GL context, which is a genuine piece of work rather than a
 * configuration flag — see the handover. The turn card, the ETA and the
 * distance are all live and correct without it.
 *
 * ## On colour
 *
 * The palette reaches this screen in one place: the template background, amber
 * while the driver is off route (see [CarPalette] for why that role, and for
 * the setter whose own validation rules out a custom colour). Two more are
 * outside Vector's reach rather than overlooked:
 *
 *  * **Type and text colour.** Not one string here can carry Manrope or Inter:
 *    the host sets the family, size and weight of the turn card, the road name,
 *    the ETA and the action labels itself. `ForegroundCarColorSpan` is the
 *    library's only text-colour hook, and it colours a *span* of a text this
 *    screen does not compose.
 *  * **The strip's buttons.** See [actionStrip] — the fills the library offers
 *    are for a primary action and for a destructive one, and this strip has
 *    neither.
 */
class CarNavScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // Redraw only when something a driver could see has changed. The
        // engine publishes at frame rate; see CarTrip.renderKey.
        lifecycleScope.launch {
            NavBridge.state
                .map { it?.let { s -> CarTrip.renderKey(s) } }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val ui = NavBridge.current
            ?: return MessageTemplate.Builder("Open Vector on your phone to start a drive")
                .setTitle("Vector")
                .setHeaderAction(Action.APP_ICON)
                .build()

        if (ui.phase != Phase.NAVIGATING) {
            // The drive ended somewhere else. The session pops this screen, but
            // a frame can be requested in between and a stale turn card is the
            // one thing a navigation screen must never show.
            return NavigationTemplate.Builder()
                .setNavigationInfo(MessageInfo.Builder("Drive ended").build())
                .setActionStrip(actionStrip(ui.voice))
                .build()
        }

        val info = when {
            // The host draws its own "recalculating" treatment from this, which
            // is what a driver expects to see rather than a frozen instruction
            // that is no longer true.
            ui.rerouting -> RoutingInfo.Builder().setLoading(true).build()
            ui.currentManeuver == null ->
                RoutingInfo.Builder().setLoading(true).build()
            else -> RoutingInfo.Builder()
                .setCurrentStep(
                    CarTrip.step(ui.currentManeuver!!),
                    CarTrip.distance(ui.distanceToManeuverM, ui.units),
                )
                .also { b -> ui.nextManeuver?.let { b.setNextStep(CarTrip.step(it)) } }
                .build()
        }

        return NavigationTemplate.Builder()
            .setNavigationInfo(info)
            .setDestinationTravelEstimate(CarTrip.travelEstimate(ui, CarTrip.now()))
            .setActionStrip(actionStrip(ui.voice))
            // Amber for off-route, which is the state where the instruction on
            // screen is known to be stale and the driver should trust the road
            // over the card until the new route lands. It is the phone's own
            // off-route colour and the colour the trip estimate takes during a
            // reroute — see [CarPalette].
            //
            // The other branch stays DEFAULT deliberately. With no map of
            // Vector's own behind the card, the host's field is the correct
            // background; a custom cloud-white one would only fight the host
            // for contrast against its own turn card.
            .setBackgroundColor(if (ui.offRoute) CarPalette.warning else CarColor.DEFAULT)
            .build()
    }

    /**
     * The two controls a driver needs while moving.
     *
     * Two, not more: an action strip is read at a glance at speed, and every
     * extra control is a target to miss. Mute is here rather than buried
     * because it is the one setting a driver changes mid-drive, and it writes
     * through to the same preference the phone shows — there is no separate
     * car mute to get out of step.
     *
     * Neither action carries a colour, and that is the decision rather than an
     * omission. `Action.Builder.setBackgroundColor` is the library's only fill,
     * and the palette spends a fill on exactly two things: the brand CTA and
     * the destructive coral. Neither control is the first — the drive itself is
     * — and the second is argued against for ending navigation in the phone's
     * own trip bar (`NavUi.kt`), in as many words: ending a route is ordinary,
     * not dangerous, and the control for it should read like every other
     * control rather than like a warning. So the host's own button chrome is
     * left to say what the buttons are, and Vector's colour on this screen is
     * spent on the one thing that is a state rather than an action, the
     * background.
     */
    private fun actionStrip(voice: VoiceMode): ActionStrip = ActionStrip.Builder()
        .addAction(
            Action.Builder()
                .setTitle(if (voice == VoiceMode.OFF) "Unmute" else "Mute")
                .setOnClickListener { NavBridge.request(NavBridge.Command.ToggleVoice) }
                .build()
        )
        .addAction(
            Action.Builder()
                .setTitle("End")
                .setOnClickListener {
                    NavBridge.request(NavBridge.Command.Stop)
                    // Not popped here. The screen comes down when the ENGINE
                    // says the drive is over — see VectorCarSession.followPhase
                    // — so a Stop the phone could not honour leaves the driver
                    // looking at a live drive rather than at nothing.
                }
                .build()
        )
        .build()
}
