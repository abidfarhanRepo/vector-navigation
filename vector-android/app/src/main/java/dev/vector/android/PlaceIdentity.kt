package dev.vector.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.vector.android.design.VectorColors
import dev.vector.android.design.VectorIcon
import dev.vector.android.design.VectorTheme

/**
 * What a place *is*, as a colour and a symbol.
 *
 * ## Why this exists
 *
 * Until now every row in a result list, every recent and every marker drew the
 * same pin in a tint derived from the place's **name** — `VectorMediaTile(key =
 * p.name)` hashes the string and picks a ramp entry from it. That is stable
 * (the same place keeps its colour) and it is also meaningless: a hotel, a
 * mall and a petrol station came back purple, blue and purple, so a list of
 * five results was five identical pins in three arbitrary colours. The redesign
 * brief asks for "category symbols" and "playful map markers", and neither is
 * possible while the only thing the tile encodes is a hash.
 *
 * So identity moves from the name to the **category**. A driver scanning a list
 * can now tell fuel from food without reading, the marker on the map matches
 * the row in the sheet, and the category chip that filtered them matches both.
 * One mapping, three surfaces — which is the whole reason it is a file rather
 * than a `when` inside a list row.
 *
 * ## Why the accent is a role and not a hex
 *
 * [tint] resolves against [VectorColors] rather than naming a colour, so the
 * dark theme gets the *designed* accent (lightened for a dark field, see
 * `VectorColor.kt`) rather than the daylight one at 60% opacity. Nothing here
 * knows a hex, which is the rule the palette file states and the one place a
 * category system would otherwise break it.
 *
 * ## Why "family" and not "category"
 *
 * OSM has thousands of `amenity`/`shop`/`tourism` values and they are not a
 * taxonomy anyone designed. [of] collapses them onto the handful a *driver*
 * distinguishes between at a glance. Anything unrecognised lands on
 * [PlaceFamily.PLACE], which is a real answer — a neutral pin — and not a
 * fallback that pretends to know.
 */
@Immutable
enum class PlaceFamily(
    /** What a person would call this family, for a chip or a badge. */
    val label: String,
    private val glyph: VectorIcons.Extra,
) {
    FUEL("Fuel", VectorIcons.Extra.FUEL),
    FOOD("Food", VectorIcons.Extra.COFFEE),
    PARKING("Parking", VectorIcons.Extra.PARKING),
    // PLUS is a medical cross at this stroke weight, which is the one symbol
    // for a pharmacy or a clinic that needs no legend anywhere in the world.
    HEALTH("Health", VectorIcons.Extra.PLUS),
    SHOPPING("Shopping", VectorIcons.Extra.BAG),
    STAY("Stay", VectorIcons.Extra.BED),
    WORSHIP("Worship", VectorIcons.Extra.DOME),
    OUTDOORS("Outdoors", VectorIcons.Extra.TREE),
    LANDMARK("Landmark", VectorIcons.Extra.TOWER),
    PLACE("Place", VectorIcons.Extra.PIN),
    ;

    /** The family's symbol, from the one icon family the app draws. */
    val icon: VectorIcon get() = VectorIcons.glyphIcon(glyph)

    /**
     * The saturated accent, for a selected chip, a marker or an icon on a
     * tinted tile.
     */
    @Composable
    fun tint(): Color = tint(VectorTheme.colors)

    /** The soft container the symbol sits on in a list row. */
    @Composable
    fun container(): Color = container(VectorTheme.colors)

    /** The accent at text contrast, for a label on a light field. */
    @Composable
    fun onContainer(): Color = onContainer(VectorTheme.colors)

    fun tint(c: VectorColors): Color = when (this) {
        FUEL -> c.coral
        FOOD -> c.sunny
        PARKING -> c.primary
        HEALTH -> c.leaf
        SHOPPING -> c.lilac
        STAY -> c.coral
        WORSHIP -> c.lilac
        OUTDOORS -> c.leaf
        LANDMARK -> c.deepBlue
        PLACE -> c.primary
    }

    fun container(c: VectorColors): Color = when (this) {
        FUEL -> c.coralContainer
        FOOD -> c.sunnyContainer
        PARKING -> c.primaryContainer
        HEALTH -> c.leafContainer
        SHOPPING -> c.lilacContainer
        STAY -> c.coralContainer
        WORSHIP -> c.lilacContainer
        OUTDOORS -> c.leafContainer
        LANDMARK -> c.deepBlueContainer
        PLACE -> c.primaryContainer
    }

    fun onContainer(c: VectorColors): Color = when (this) {
        FUEL -> c.coralText
        FOOD -> c.sunnyText
        PARKING -> c.primaryText
        HEALTH -> c.leafText
        SHOPPING -> c.lilacText
        STAY -> c.coralText
        WORSHIP -> c.lilacText
        OUTDOORS -> c.leafText
        LANDMARK -> c.deepBlueText
        PLACE -> c.primaryText
    }

    companion object {
        /**
         * The family for an OSM category value, or [PLACE].
         *
         * Matched on substrings rather than on equality because the extract
         * carries both `fast_food` and `food_court`, both `place_of_worship`
         * and `worship`, and a table of exact values would miss whichever one
         * this week's bake happens to emit. Order matters: `car_wash` must not
         * be caught by the `car` in `carpark`, so the specific tests come
         * first.
         */
        fun of(category: String?): PlaceFamily {
            val k = category?.lowercase()?.replace(' ', '_')?.trim().orEmpty()
            if (k.isEmpty()) return PLACE
            return when {
                k.contains("fuel") || k.contains("petrol") || k.contains("charging") -> FUEL
                k.contains("parking") || k.contains("carpark") -> PARKING
                k.contains("restaurant") || k.contains("cafe") || k.contains("coffee") ||
                    k.contains("food") || k.contains("bakery") || k.contains("bar") -> FOOD
                k.contains("pharmacy") || k.contains("hospital") || k.contains("clinic") ||
                    k.contains("doctor") || k.contains("dentist") -> HEALTH
                k.contains("mall") || k.contains("shop") || k.contains("supermarket") ||
                    k.contains("market") || k.contains("store") ||
                    // Name hints, for the [of] overload that falls back to the
                    // name: Doha's malls are "City Center", "Landmark" and
                    // "Souq Waqif" and none of them contains the word "mall".
                    // Without these the header for a place the LIST drew with a
                    // shopping bag fell back to a plain pin, so the symbol
                    // changed between tapping a row and arriving at the card
                    // for the same place.
                    k.contains("city_center") || k.contains("city_centre") ||
                    k.contains("souq") || k.contains("plaza") -> SHOPPING
                k.contains("hotel") || k.contains("resort") || k.contains("hostel") ||
                    k.contains("guest") -> STAY
                k.contains("worship") || k.contains("mosque") || k.contains("masjid") ||
                    k.contains("church") -> WORSHIP
                k.contains("park") || k.contains("garden") || k.contains("beach") ||
                    k.contains("corniche") -> OUTDOORS
                // Offices, towers and museums are all "a named building you
                // drive to" and there is no useful distinction between them for
                // a driver; they share one symbol so that they are at least
                // distinguishable from an unclassified point.
                k.contains("office") || k.contains("tower") || k.contains("museum") ||
                    k.contains("bank") || k.contains("government") ||
                    k.contains("embassy") -> LANDMARK
                else -> PLACE
            }
        }

        /** The family for a place, using its category and then its name. */
        fun of(place: VectorApi.Place): PlaceFamily {
            val byCategory = of(place.category)
            if (byCategory != PLACE) return byCategory
            // A name is a weaker signal than a tag and is only consulted when
            // the tag said nothing — "Souq Waqif Boutique Hotel" with no
            // category is still obviously a hotel.
            return of(place.name)
        }
    }
}
