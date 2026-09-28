package dev.vector.android.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import dev.vector.android.NavBridge
import dev.vector.android.Places
import dev.vector.android.Recents
import dev.vector.android.Settings
import dev.vector.geo.LngLat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Where a driver chooses where to go, in the car.
 *
 * The list is Home and Work followed by the recent destinations — the same
 * two stores the phone's own destination sheet reads, out of the same
 * `SharedPreferences` file, through the same [Places] and [Recents] objects.
 * Nothing is copied into a car-specific store, so a place saved on the phone
 * is in the car on the next glance and a destination driven from the car
 * appears in the phone's recents.
 *
 * ## Why there is no search box
 *
 * Free-text search in the car needs the host's `SearchTemplate` and, to be
 * useful at the wheel, the voice input behind it. Vector's `/search` endpoint
 * is ready for it and the plumbing is a screen, not an engine change — but a
 * half-built search that makes a driver type at speed is worse than a list
 * they can hit with one tap. It is the first item on the handover's P1 list.
 *
 * ## On colour
 *
 * A [ListTemplate] is the one template with no colour of its own: the list, the
 * rows, their typography and their text colours are all drawn by the host. The
 * library's only hook into it is `Row.Builder.setImage`, and the one thing
 * missing for a Vector-coloured row icon is an icon — this module's set is
 * Compose-drawn (`design/VectorComponents.kt`, `VectorIcon`), which a car host
 * cannot render, and the app ships no non-launcher drawable to hand it instead.
 * Painting one into a bitmap at runtime would be the wrong repair for the
 * reason [CarTrip] already gives for leaving the host's own arrow on a
 * maneuver: it is the one element on the screen that does not belong to the
 * vehicle, and head units scale it badly. So the rows keep the host's neutral
 * treatment, and the palette reaches the car on the surfaces that can carry it
 * — see [CarPalette].
 *
 * ## On the deprecation warnings this file raises
 *
 * `setTitle`/`setHeaderAction` are deprecated in car-app 1.7.0 in favour of
 * `setHeader(Header)`. `Header` is a Car API LEVEL 7 type, and Vector declares
 * `minCarApiLevel="1"` so that older head units are not silently refused. So
 * the deprecated calls are the correct ones here, not an oversight: moving to
 * `Header` means either raising that floor to 7 or guarding every call site
 * with `carContext.carAppApiLevel`, and neither is worth doing before the app
 * has been on a real head unit.
 */
class CarDestinationsScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // The phone may add a recent while the car screen is up.
        lifecycleScope.launch {
            NavBridge.state
                .map { it?.recents?.size to it?.places?.size }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        // Read from prefs rather than from the bridge, so the list is there
        // before the phone app has ever been opened in this session. The
        // bridge only supplies the invalidate signal above.
        val prefs = Settings.prefs(carContext)
        val saved = Places.load(prefs)
        val recents = Recents.load(prefs)

        if (saved.isEmpty() && recents.isEmpty()) {
            return MessageTemplate.Builder(
                "No saved or recent destinations yet. Choose somewhere on your phone " +
                    "and it will be here next time."
            )
                .setTitle("Vector")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val list = ItemList.Builder()
        saved.forEach { p ->
            list.addItem(row(p.name, p.slot.label, LngLat(p.lng, p.lat), p.name))
        }
        // Car hosts cap a list at a small number of rows and simply drop the
        // rest, so the cap is applied here where the choice of WHICH rows
        // survive is deliberate: saved places first, then the newest drives.
        recents.take((MAX_ROWS - saved.size).coerceAtLeast(0)).forEach { r ->
            list.addItem(row(r.name, "Recent", LngLat(r.lng, r.lat), r.name))
        }

        return ListTemplate.Builder()
            .setTitle("Vector")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }

    /**
     * One destination row.
     *
     * A tap is the whole gesture — no preview, no confirm. See
     * `MainActivity.carAutoStart` for why the car skips the phone's PREVIEW
     * step: there is no second screen here on which to press Start.
     */
    private fun row(title: String, subtitle: String, at: LngLat, name: String): Row =
        Row.Builder()
            .setTitle(title.ifBlank { "Destination" })
            .addText(subtitle)
            .setBrowsable(false)
            .setOnClickListener {
                val sent = NavBridge.request(NavBridge.Command.NavigateTo(at, name))
                if (!sent) {
                    // The engine lives in the phone Activity; with it gone
                    // there is nothing to route. Saying so is the honest
                    // answer, and it is the limitation the handover's P0 item
                    // removes.
                    screenManager.push(NotRunningScreen(carContext))
                }
            }
            .build()

    private companion object {
        /**
         * Six rows. The Car App Library's own limit is host-dependent
         * (commonly six while parked and fewer while moving), so this is the
         * conservative floor rather than a guess at the host's number.
         */
        const val MAX_ROWS = 6
    }
}

/**
 * Shown when the car asks for something the phone cannot act on.
 *
 * Two different states reach here and the driver can do the same thing about
 * both, which is why they share one message: no Activity is alive at all, or
 * one is alive but has not finished bringing up the map and so is not yet
 * collecting commands (see the KDoc on [NavBridge.request]). "Not running"
 * was wrong for the second case — the app was plainly on screen — so the
 * wording names the state the driver can actually check, the map being up,
 * rather than the process being alive.
 */
class NotRunningScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(
        "Vector isn't ready on your phone yet. Open it, wait for the map to " +
            "appear, then choose again here."
    )
        .setTitle("Vector")
        .setHeaderAction(Action.BACK)
        .build()
}
