package dev.vector.android

/**
 * The Vector basemap style, built for the native renderer (ADR-0075).
 *
 * A MapLibre style document — the same specification the web client uses,
 * pointed at the same self-hosted endpoints: `/tiles/{z}/{x}/{y}.mvt`
 * (source-layer `basemap`) and `/glyphs/{fontstack}/{range}.pbf`. Nothing here
 * talks to a third-party map provider, which is the whole point of the stack.
 *
 * ## Two themes, one document
 *
 * Every colour comes from a [Palette] and there are two: [DARK] for night
 * driving and [LIGHT] for daylight. This is deliberately NOT an inversion of
 * one palette — a navigation map inverted is unreadable, because the
 * relationship between road fill, road casing and background is different in
 * the two regimes. Dark cartography draws roads LIGHTER than the ground they
 * sit on; light cartography draws them WHITE with a darker casing, and the
 * casing is what carries the hierarchy. Inverting the dark theme would give
 * dark roads on a light ground with light casings, which reads as a negative
 * of a map rather than as a map.
 *
 * That paragraph predates the surface layers and was then contradicted by
 * them. §4.1's carriageway, skirt and lane markings were built and tuned on a
 * device in DARK, and their light values were filled in by asking what the
 * daylight version of a lit asphalt deck is — which yields a dark deck with
 * white paint on a pale ground, the negative described above, drawn over the
 * older class-ramp layers that were still obeying the rule. It shipped, and
 * the suite was green throughout, because every assertion in it was about the
 * class ramp and none was about the surface that replaces it at driving zoom.
 * `VectorStyleTest` now pins the relationship itself — magnitude and sign, per
 * theme — so the prose cannot be the only thing holding the rule up.
 *
 * What is held constant across both is everything the driver acts on: the
 * route is the same blue, the vehicle is the same blue with a white ring, and
 * the traffic ramp is the same red/orange/yellow. Those must not change
 * meaning with the time of day.
 *
 * ## The zoom hierarchy
 *
 * Road classes are separated by `highway` tag rather than lumped into
 * minor/major, because the tile pipeline emits them progressively by zoom
 * (`build_qatar_tiles._HW_TIERS`: motorway and trunk from z0, primary z9,
 * links z11, secondary z12, tertiary z13, residential z14, service z15). Each
 * layer below declares the `minzoom` its class is actually baked at — not as
 * an optimisation, but so the document states the contract it depends on. A
 * layer asking for a class the bake does not carry at that zoom renders
 * nothing and reports nothing.
 *
 * **Width stops start at z6**, which is the zoom the country fits on a phone
 * screen at. They used to start at z8, and MapLibre clamps an `interpolate`
 * below its first stop, so z6 and z7 drew the z8 width — the country view had
 * hairline roads. z6-z10 tiles did not exist at all until the V3 pass baked
 * them, so this was invisible.
 *
 * **Place labels are ranked by `place`.** The extract carries `place=` on all
 * 685 labels (1 country, 9 state, 3 city, 20 town, 87 suburb, 320 locality,
 * 115 hamlet) and the style used to ignore it, so a hamlet was drawn at the
 * same size as Doha and MapLibre's symbol collision decided which survived by
 * feature order. Size and weight now follow the rank, and the pipeline only
 * emits each rank from the zoom where it belongs.
 *
 * ## Font stacks are not free choices
 *
 * The glyph store serves exactly two: `Noto Kufi Arabic` (Arabic ranges) and
 * `Open Sans Regular` (Latin). This style originally asked for
 * `Noto Sans Regular`, which does not exist — and the tile server answers a
 * missing stack with a 2-byte body and HTTP **200**, not a 404, so nothing
 * anywhere would have reported an error and every label layer would simply
 * have rendered blank on the device. Road labels use the Arabic face because
 * Doha road names are Arabic; place labels use the Latin one.
 *
 * ## Layer order
 *
 * Traffic is drawn ABOVE the basemap roads but BELOW the route line, so the
 * road being followed stays the most legible thing on screen. Only congested
 * segments are painted — colouring free-flowing roads green covers the map in
 * information the driver already assumes.
 *
 * **POIs** are deliberately quieter than place labels: a POI is context while
 * driving, not the thing the driver is looking for, so it is a dot from z14
 * and only gains a name later — z16 if it is a landmark or an emergency, z17
 * if it is a shopfront. How many names a screenful is allowed to carry, and
 * which ones, is the longest comment in this file; see [poiLabelFilter].
 *
 * That intent used to be stated here and contradicted by the numbers.
 * `poiLabel` was `#b9c6d6` against `roadLabel`'s `#9aa6b8` — BRIGHTER than the
 * road names on a dark ground — so on a real S24 screenshot at street zoom the
 * POI names were the most prominent text on the map and the street names sat
 * behind them. A driver looking for a road read shop names first.
 * `VectorStyleTest` now pins the ordering, so the prose and the palette cannot
 * drift apart again.
 *
 * ## NOTE FOR EDITORS
 *
 * The style below is a JSON document inside a raw string. **JSON has no
 * comments.** A `//` line in there does not annotate the style, it makes
 * MapLibre fail to parse the whole document and render a blank screen with no
 * error anywhere in the app. Explain things here, in the KDoc, instead — and
 * see `VectorStyleTest`, which parses the real string with a strict parser for
 * exactly this reason.
 */
object VectorStyle {

    /**
     * Which cartography to draw.
     *
     * [SYSTEM] is not a value this object can render: it is the user's
     * preference, and it resolves to [LIGHT] or [DARK] against the device's
     * current configuration. Resolution happens at the call site
     * ([MapTheme.resolve]) so the style builder never needs a Context and
     * stays a pure function of its arguments.
     */
    enum class MapTheme { LIGHT, DARK, SYSTEM }

    /**
     * Every colour the basemap uses, as a hex string MapLibre can parse.
     *
     * Named by ROLE, not by appearance — `roadCasing` rather than `darkBlue` —
     * so that the light palette can invert the relationship without the names
     * becoming lies.
     */
    data class Palette(
        val background: String,
        val landuse: String,
        val park: String,
        val natural: String,
        val water: String,
        /**
         * The land/sea edge.
         *
         * A separate role from [water] because it is a LINE over the land
         * colour rather than a fill: Qatar's surrounding sea is not a water
         * polygon (it lies outside the coastline), so there is nothing to fill
         * and the edge has to be drawn.
         */
        val coastline: String,
        /**
         * The ground UNDER THE CAR, at driving zoom.
         *
         * A second ground colour, not a replacement for [background], because
         * the two zooms want opposite things and V4 and V8 each measured one of
         * them on a real device.
         *
         * V4 lifted the night ground off `#0c0e14` because at country zoom the
         * map was grey hairlines in a void: with no surface to look at, the
         * ground has to be light enough to read AS a surface, and the six-tier
         * road ramp has to sit on top of it.
         *
         * At driving zoom there IS a surface — the carriageway (§4.1) — and the
         * contrast budget belongs to it. A ground at L\* 12 makes asphalt read
         * as a lit deck; the same ground at z8 makes the country disappear.
         *
         * So the ground interpolates between the two between z13 and z16, and
         * neither finding has to be thrown away to honour the other. Every
         * assertion about the road ramp is made against [background], which is
         * still the colour at the zoom the ramp was measured at.
         *
         * Both themes move this ground in the SAME direction — DOWN — and that
         * is not obvious, because the two themes disagree about almost
         * everything else. It follows from the one thing they agree on: the
         * deck is LIGHTER than the ground in both cartographies, night because
         * roads are drawn lighter than what they sit on and daylight because
         * roads are drawn white. A ground that steps back is what lets the
         * surface come forward, and it has to step back the same way in both.
         * The light theme used to step UP, which was the correct move for the
         * deck it had and the wrong deck — see [LIGHT]'s [carriageway] note.
         */
        val backgroundDriving: String,
        val building: String,
        /**
         * The seam between one footprint and the next.
         *
         * It must be DARKER than [building] in both themes, and until V7.6 it
         * was darker in only one of them. That went unnoticed for three
         * milestones because the layer it paints had no features: the extract
         * kept only named and height-bearing buildings, so
         * `fill-outline-color` was a value the style carried and never drew.
         *
         * V7.6 carries all 189,866 Qatar footprints, and at that density the
         * dark theme's old `#2f3849` was a defect rather than a preference.
         * Measured: L\* 23.4, against a carriageway of 21.1 — so every
         * building edge in Doha would have been drawn BRIGHTER than the road
         * surface, and a dense district would have read as a luminous mesh
         * with the streets as the dark gaps between it. That is the map
         * upside down.
         *
         * The light theme already had this right (85.4 fill, 79.3 seam — 6.1
         * below), and it is right for the reason a seam is a seam: buildings
         * meet along a shadow line, not a highlight. The dark value now takes
         * the same relationship rather than a separately invented one.
         */
        val buildingOutline: String,
        /**
         * The extruded building's side-face colour (V7 3D).
         *
         * Deliberately NOT [building]. The 2D fill is a FLAT top-down surface
         * seen through the ground layer's relationship; an extrusion is a
         * VOLUME whose faces catch the light, and reusing the fill colour made
         * the skyline read as one grey mass at pitch — the V8 §12 "wall of
         * giant anonymous blocks" failure. This is a step off [building] in the
         * same direction the palette's other vertical surfaces take, so the
         * masses separate from the ground without becoming a landmark.
         */
        val building3d: String,
        /**
         * The driving surface. One colour for every road the car can drive on.
         *
         * NOT a class colour. §4.1's rule is that the hierarchy WITHIN the
         * surface — markings over asphalt — has to dominate the hierarchy
         * BETWEEN surfaces, because the only road that matters to a driver is
         * the one under the car. The class ramp above still exists and still
         * carries the overview; it fades out as this fades in.
         */
        val carriageway: String,
        /**
         * The vertical face of the deck.
         *
         * Drawn as a wider line under [carriageway], so that at pitch the
         * carriageway reads as something with thickness rather than as a shape
         * painted on the ground. It is doing the job [roadCasing] does at
         * overview zoom, one zoom band later and for a different reason.
         */
        val carriagewaySkirt: String,
        /**
         * Lane dividers and carriageway edges.
         *
         * This is the object the driver is being asked to look at, and §4.1
         * puts it above the surface it sits on and below only the route.
         *
         * What that buys is NOT "the brightest thing on the map", which is
         * what this role claimed until the light theme was looked at on a
         * device. It is the highest-CONTRAST thing against [carriageway], and
         * the DIRECTION of that contrast belongs to the theme rather than to
         * the role. At night the deck is dark and the paint is near-white,
         * which is also what a headlamp does to it. In daylight the deck is
         * white — that is the whole of light cartography — and white paint on
         * a white carriageway is not a line, it is nothing. So the daylight
         * marking comes DOWN off the surface instead of up off it.
         *
         * The rule that survives both is the only one worth stating: a marking
         * is legible against the deck it is painted on. Stating one theme's
         * sign as though it were a law is how the light theme ended up with
         * `#ffffff` markings on a charcoal deck, so `VectorStyleTest` pins the
         * magnitude and the sign separately, per theme.
         */
        val laneMarking: String,
        val roadService: String,
        val roadMinor: String,
        val roadTertiary: String,
        val roadSecondary: String,
        val roadPrimary: String,
        val roadMotorway: String,
        /** Drawn under the major classes; this is what carries the hierarchy. */
        val roadCasing: String,
        val roadLabel: String,
        val placeLabel: String,
        val poiDot: String,
        val poiLabel: String,
        /** Behind label text. Must be the ground colour, not a tint of it. */
        val halo: String,
        /**
         * A route the driver has NOT chosen.
         *
         * Muted rather than absent. Alternatives used to be described by chips
         * and never drawn, so the driver was asked to pick between three routes
         * they could not see — the chip said "via شارع حالول" and gave no way to
         * find out where that goes. Drawn under the chosen route, in a colour
         * that reads as "an option" rather than as "the route", because two
         * equally blue lines is worse than one.
         *
         * A DESATURATED BLUE, not a grey. The first attempt used #5c6b80,
         * which on the dark theme sits between `roadSecondary` (#4a5462) and
         * `roadPrimary` (#6b7688) — so an unchosen route was drawn and read as
         * an ordinary road, which is very nearly as useless as not drawing it.
         * Hue carries "this is a route"; saturation and lightness carry
         * "you have not picked it".
         */
        val routeAlt: String,
    )

    /** Night driving. Roads read lighter than the ground. */
    /**
     * Night cartography.
     *
     * ## What V4 changed, and how it was measured
     *
     * The ground was `#0c0e14` — L\* ≈ 4, effectively black — and the four
     * lowest road tiers ran `#232a36`, `#2b3340`, `#39424f`, `#4a5462`, steps
     * of about six L\* units apart. On a real S24 at street zoom the result
     * (`v4-evidence/vector/before-01-explore.png`) reads as **grey hairlines
     * on black with no mid-tones**: it looks like a wireframe drawing of a city
     * rather than a map of one, and the tier hierarchy the ramp encodes is
     * invisible because the steps are below the threshold at which a sunlit
     * phone can separate them.
     *
     * Both reference products, sampled from the same device:
     *
     * | role | Waze | Google Maps | Vector (was) | Vector (now) |
     * |---|---|---|---|---|
     * | ground | `#272E3A` | `#1C3B5A` | `#0C0E14` | `#1A1F2B` |
     * | building | `#2D3E53` | `#264B6E` | `#1B212C` | `#242B39` |
     * | road | `#384F66` (one tone) | `#657AA2` (one tone) | 6-tier ramp | 6-tier ramp, widened |
     *
     * Neither reference sits its ground near black; both put it around
     * L\* 18–24 so the map reads as a *surface*, and both then make roads a
     * clear step lighter. What they do NOT do is grade their roads by hue at
     * all — Waze uses **one** road colour and varies only width, because at
     * speed you distinguish a trunk road from a residential street by how fat
     * it is, not by a 6% lightness difference.
     *
     * Vector keeps its ramp, because the ramp is what makes the z6–z10 country
     * view legible (V3 baked those specifically), but the **span is widened**:
     * the bottom is lifted clear of the ground and the top is brightened, so
     * consecutive tiers differ by roughly twice what they did. Six values that
     * can actually be told apart, rather than six values that exist.
     *
     * The route line and the vehicle are unchanged in hue and are handled
     * separately — see [ROUTE]. Meaning must not move with the time of day.
     */
    val DARK = Palette(
        // Lifted from #0c0e14. Still clearly night, no longer a void.
        background = "#1a1f2b",
        landuse = "#1f2531",
        park = "#1b2e22",
        natural = "#1d2721",
        water = "#132a42",
        coastline = "#2b4a6e",
        // L* 6.3, against the overview ground's 12.4. The value V4 rejected,
        // reinstated only at the zoom where there is a carriageway to look at.
        backgroundDriving = "#0d1017",
        // L* 12.6, and the number is the point. It was `#242b39` (L* 17.5),
        // which sat ΔL* 3.6 under a carriageway of 21.1 — measured on a real
        // render, the fabric and the road were the same tone, so the 29% of the
        // screen the blocks occupy carried no information and the streets read
        // as gaps between them rather than as pavement. A city at night is
        // darker than its lit asphalt; this is that, at ΔL* 15.9.
        building = "#1b212c",
        // L* 12.1, a seam 5.4 below the fabric it separates — the light
        // theme's own relationship (6.1 below), not a second invention. Was
        // `#2f3849` (L* 23.4), which sat ABOVE the carriageway's 21.1; see
        // [Palette.buildingOutline] for why that only became wrong at V7.6.
        buildingOutline = "#1a202b",
        // V7 3D. A step up from `building` (L* 17.8 -> 23.6): the extrusion
        // reads as a lit volume over the driving ground (L* 6.3) without
        // competing with the carriageway (L* 21.1) or its markings (92.5).
        building3d = "#333c50",
        // L* 28.5 on a ground of 4.7, with markings at 82.4. See the fabric note
        // below for why the band was respanned a second time.
        //
        // The first correction was §4.2 -> `#2b3341`, from the emulator reading
        // "roads as outlined shapes rather than as surfaces". Measured off a
        // real render of the shipped style over production tiles at z17.6,
        // pitch 55 (`shot-junction-01.png`), that value still lost to the ink:
        // carriageway L* 21.1 at 10.5% of pixels against `building` L* 17.5 at
        // 29% — ΔL* 3.6 between the two largest surfaces — and a marking at
        // L* 93.6, ΔL* 72 over the deck it is painted on. Two-thirds of the
        // screen was one flat tone and the only thing with contrast was the
        // line-work, which is why the map read as a wiring diagram.
        //
        // So the deck comes up (`#3a4453`) rather than the ink coming down
        // alone. Asphalt at night under a street light is the LIT surface on
        // the screen, and this is what that costs.
        carriageway = "#3a4453",
        // A step BETWEEN the ground and the deck, not below both.
        //
        // §4.2 puts the night skirt at `#0a0d13`, under the ground — which on a
        // near-black ground is invisible, so the skirt contributed nothing at
        // all and the deck had no edge. Sitting it between the two makes the
        // lip read as a vertical face catching less light than the deck, which
        // is also what a kerb actually does. The light theme keeps the skirt
        // below both, because there a dark rim on a pale ground is a shadow and
        // reads immediately.
        //
        // Follows the deck up: the kerb has to stay a visible step off the
        // ground (4.7) without competing with the surface it edges (28.5).
        carriagewaySkirt = "#232a37",
        laneMarking = "#c3cedd",
        // The ramp, respanned. Bottom lifted off the ground, top brightened,
        // so the steps are roughly twice as far apart as they were.
        roadService = "#333c4c",
        roadMinor = "#3e4859",
        roadTertiary = "#4e5a6e",
        roadSecondary = "#647388",
        roadPrimary = "#8391a6",
        roadMotorway = "#d9b23a",
        // Darker than the ground, so a casing reads as a gap between
        // carriageways rather than as another road.
        roadCasing = "#10141d",
        roadLabel = "#c3cedd",
        placeLabel = "#eef3f9",
        poiDot = "#6d7a8c",
        // Deliberately DIMMER than roadLabel (#c3cedd). V3 found these
        // inverted — POI names were brighter than road names on a dark
        // ground, so a driver looking for a street read shop names first.
        poiLabel = "#8b95a6",
        halo = "#12161f",
        // Raised from #2f6296 (L* 40.5), which its own KDoc already admitted
        // sat "between roadSecondary and roadPrimary" in luminance — so an
        // unchosen route was drawn and read as an ordinary road. V4's brighter
        // road ramp made that strictly worse: #2f6296 became DARKER than every
        // road above tertiary.
        //
        // #5aa0e0 is L* 63.9 against a brightest-road L* of 59.7 and the
        // route's 70.5, so it is measurably brighter than any road and
        // measurably duller and darker than the route it is an alternative to.
        // The old test asserted hue and saturation and never asserted
        // LUMINANCE, which is exactly why the defect survived a passing suite —
        // `VectorStyleTest` now pins all three.
        routeAlt = "#5aa0e0",
    )

    /**
     * Daylight. Roads are white and the CASING carries the hierarchy — the
     * standard light-cartography relationship, and the reason this is a second
     * palette rather than an inversion of the first.
     */
    /**
     * Daylight cartography.
     *
     * ## The mirror of the dark theme's defect, found the same way
     *
     * V4 lifted the dark ground off black because roads were invisible against
     * it. The light theme had the same fault in the other direction and it
     * survived the first pass: the ground was `#f2f4f7` at L\* 96.1 and every
     * road tier was `#ffffff` at L\* 100, so **road-against-ground was ΔL\* 3.9**
     * where the dark theme now runs 13.3 to 48.0. On the S24
     * (`v4-evidence/vector/after-06-light-explore.png`) the residential grid was
     * a suggestion; only the wide roads read, and they read because of their
     * casing rather than their fill.
     *
     * Two changes, and the second is the one that was missing entirely:
     *
     * 1. **The ground comes down** to L\* 89.6, a shade below Google Maps'
     *    own light ground (`#E8EAED`, L\* 92.6). 92 was the first attempt and
     *    the test rejected it: it clears the floor for a white primary road
     *    but leaves the SERVICE tier at ΔL\* 4.5, and a car-park aisle nobody
     *    can see is still a road nobody can see. 89.6 buys ΔL\* 6.8 at the
     *    bottom of the ramp and 10.4 at the top.
     * 2. **The fills get a hierarchy.** Every tier being the same white meant
     *    the light theme expressed road importance *only* through casing
     *    width, so the six-tier ramp the dark theme uses to make the country
     *    view legible had no light-theme counterpart at all. The lower tiers
     *    are now progressively off-white.
     *
     * `VectorStyleTest."roads are legible against the ground they are drawn on"`
     * pins a minimum for both themes, which is the assertion whose absence let
     * this ship twice.
     *
     * ## And a third time, on the surface rather than on the ramp
     *
     * Both fixes above are about the CLASS RAMP, and the class ramp stops
     * being the map at z16.5 ([CLASS_RAMP_FADE]). What replaces it — the
     * lane-true carriageway, its kerb and its markings — was added later, was
     * tuned on a device in dark, and was given daylight values that reproduced
     * the night relationship on a pale ground: a charcoal deck, a near-black
     * rim, white paint. So the light theme went on passing every assertion in
     * the suite while rendering, at exactly the zoom a driver navigates at,
     * the negative the file KDoc opens by forbidding.
     *
     * The corrected relationship is stated on [carriageway],
     * [carriagewaySkirt] and [laneMarking] below, and is pinned by four tests
     * that assert the SIGN of each contrast per theme rather than only its
     * magnitude — sign being the half that a palette can get backwards while
     * every distance in it still looks healthy.
     */
    val LIGHT = Palette(
        // Warmer and lighter than the old #dce2ea — but not as light as the
        // reference's own base, and that difference is a measured constraint
        // rather than a preference.
        //
        // The audit sampled Corner's daylight map at #F6F7F6 (L* 97), with its
        // roads drawn DARKER than the ground. Vector draws roads LIGHTER than
        // the ground — white carriageways, the Google Maps convention V4's
        // measurements argue for — so the ground has to stay low enough for the
        // ramp to climb through it. The first attempt at this palette lifted the
        // ground to L* 95 and `VectorStyleTest` failed twice, correctly: the
        // lowest road tier came within ΔL* 2.1 of the ground and the carriageway
        // within ΔL* 7.1, which is a residential grid nobody can find on a
        // sunlit phone.
        //
        // The ground is therefore L* 89.6, which clears the suite's floors
        // (every road tier ΔL* >= 6; the deck ΔL* >= 10 above the driving
        // ground) while still being a warmer, lighter map than the one it
        // replaces. Adopting the reference's near-white base would mean adopting
        // its road convention too — that is a different cartography, not a
        // palette.
        //
        // Every relationship the rest of this palette asserts is preserved:
        // roads lighter than the ground, [laneMarking] darker than the deck,
        // [buildingOutline] darker than [building], and [backgroundDriving]
        // below [background] so the deck comes forward at driving zoom.
        background = "#e4e1db",
        landuse = "#dfdcd6",
        park = "#dae3d5",
        natural = "#dee4da",
        water = "#c4dcef",
        coastline = "#8fbeda",
        // Down from #e7ebf1 (L* 92.9), which was a shade LIGHTER than the
        // overview ground. That was the right move for a charcoal deck — get
        // the ground out of a dark object's way — and it is the wrong move for
        // the deck below, which is white. L* 88.3 under a 100 deck is ΔL* 11.7;
        // Google Maps' own light ground gives its white roads 7.4, so this is
        // not a timid number.
        //
        // Not lower, and the constraint that sets the floor is not taste:
        // the fabric must stay clear of it, or the blocks sink into the ground
        // exactly where the pitched camera is looking at them. The separation
        // the deck needs comes from the skirt, which is where light
        // cartography puts it anyway.
        //
        // V8 §4.7 rebalanced the band that was left here. Measured off a real
        // render of this style over production tiles at z17.6, pitch 55
        // (`shot-light-junction.png`): ground L* 88.3 (43% of pixels),
        // `building` 85.4 (28%) — **ΔL* 2.9 between the two largest surfaces on
        // screen**, with the lane ink at 18.3 spending ΔL* 81 on 0.26% of it.
        // The result read as a grey wireframe: the fabric was invisible and the
        // markings were the only thing with any contrast.
        //
        // So the fabric steps DOWN off the ground instead of being a tint of
        // it. #c3cad7 is L* 81.2, ΔL* 5.2 under the ground and 18.8 under the
        // deck: enough for a block to read as a block at a glance, which is
        // what Amap's daylight palette does and what this did not.
        backgroundDriving = "#dcd9d3",
        building = "#d2cfc9",
        buildingOutline = "#c7c3bc",
        // V7 3D. Steps DOWN off `building` (L* 85.5 -> 79.9) rather than up:
        // on a daylight ground an extruded mass has to come forward by being
        // darker than the roof plane, which is the same direction the light
        // theme's carriageway note describes for the deck.
        building3d = "#ccc9c3",
        // This was `#3a4048` — L* 26.8, annotated "the darkest thing on a
        // daylight screen" — under a near-black skirt and #ffffff markings.
        // Which is the NIGHT relationship, item for item: dark deck, darker
        // rim, light paint. Laid on a pale ground it is the photographic
        // negative this file's KDoc forbids in its third paragraph, and a
        // device screenshot of the light theme under navigation is that
        // paragraph read back verbatim — heavy charcoal ribbons carrying white
        // lane lines across a grey ground, visibly heavier than the night
        // theme it exists to be the daylight alternative to.
        //
        // The deck is therefore WHITE, which is what light cartography draws a
        // road as, and the contrast moves to the casing — [carriagewaySkirt]
        // below, which is the casing's job one zoom band later.
        //
        // White and not an off-white, for a reason that is about motion rather
        // than about colour: `roadSecondary` and `roadPrimary` are already
        // #ffffff, and the carriageway is what replaces them as the class ramp
        // fades out. Anything else here would make an arterial road visibly
        // change colour while the driver zoomed into it.
        carriageway = "#ffffff",
        // L* 66.6 — below the ground (88.3) AND below the deck (100), which is
        // what this role's KDoc already said the light theme wanted, and which
        // `#2a2f36` (L* 19.2) overshot by nearly fifty units. A shadow under a
        // white surface reads as a kerb; a near-black rim reads as a hole.
        //
        // Deliberately a shade darker than `roadCasing` (L* 72.2) rather than
        // equal to it. The two are the same idea at two zooms, and the face of
        // a kerb catches less light than the road edge it belongs to, so the
        // handover from casing to skirt is continuous without being flat.
        carriagewaySkirt = "#a9a49c",
        // ΔL* 42.5 below the white deck it is painted on, and ΔL* 9 below the
        // skirt so that a carriageway edge still reads as a line where it
        // crosses the shadow instead of dissolving into it.
        //
        // Grey and not black. A marking is 0.4 px at z15 ([MARKING_WIDTH]) and
        // a near-black dash at that width aliases into a dotted smear in the
        // far field at 60° of pitch — the same failure MARKING_WIDTH documents
        // from the other end, reached by making the ink too strong instead of
        // too thin.
        laneMarking = "#7c848e",
        // A ramp, not five identical whites. Ascending to pure white for the
        // roads that matter most, so the tier is in the fill as well as in the
        // width.
        roadService = "#f1f4f7",
        roadMinor = "#f5f8fb",
        roadTertiary = "#f8fafe",
        roadSecondary = "#fafdff",
        roadPrimary = "#fdffff",
        roadMotorway = "#f0bd4e",
        // Darker than it was (#b6bfcd), because on a light map the casing is
        // doing most of the work of separating a road from the ground.
        roadCasing = "#c0bcb5",
        roadLabel = "#4d5766",
        placeLabel = "#39414d",
        poiDot = "#a2aab6",
        poiLabel = "#848d9a",
        halo = "#ffffff",
        // Darker than the route here, not lighter: the light theme's roads are
        // white (L* 100), so an alternative has to come DOWN from them, and
        // #8fb8e0 (L* 73.2) was brighter than the route itself (L* 70.5) —
        // the unchosen line out-shouting the chosen one.
        routeAlt = "#5f92c8",
    )

    // ---- constants held across both themes ---------------------------------
    //
    // These encode MEANING rather than appearance, so they must not move with
    // the time of day. A driver who learns that blue is the route and red is a
    // jam must not have to relearn it at dusk.

    /**
     * The font stack every label layer uses.
     *
     * ## Why this is a stack and why it is this order
     *
     * Every font in the glyph directory is single-script:
     * `Open Sans Regular` ships Latin (`0-255`, `32-126`) and
     * `Noto Kufi Arabic` ships Arabic (`1536-1791` and friends). Before V4 the
     * style pinned **one font per layer** — road labels to Noto Kufi, place and
     * POI labels to Open Sans — which meant a Latin road name and an Arabic
     * place name were each unrenderable in the layer that owned them.
     *
     * MapLibre's answer to that is a comma-joined stack, resolved per
     * codepoint. **The tile server did not implement it**: it joined the whole
     * stack onto the glyph directory as a path, found nothing, and answered
     * 200 with an empty protobuf — which a renderer cannot distinguish from a
     * range that is genuinely empty. So the style could not use a stack, and
     * the reason it could not was invisible. Fixed in
     * `vector-web/docker/tileserver.py` (`glyph_stack`) and in
     * `vector-tile-server`'s reference implementation, both pinned by tests.
     *
     * Latin first: `name:en` is what English devices ask for, so the common
     * case should not walk the stack. Arabic falls in behind it for the 0.5%
     * of roads with no translation, and for every Arabic-locale device.
     */
    const val FONT_STACK = "Open Sans Regular,Noto Kufi Arabic"

    /**
     * Why there are two `route-labels` layers.
     *
     * `text-allow-overlap` and `text-ignore-placement` are layout properties
     * that the style spec does **not** permit to be data-driven. A first
     * version used `["get", "chosen"]` for both, which does not fall back to a
     * default — MapLibre fails to parse the layer and draws none of the
     * labels. Verified on the S24 across two deploys: the one label that had
     * been rendering disappeared, with no error logged anywhere
     * (`v4-evidence/vector/after-03-preview.png`). So per-role behaviour has
     * to mean per-layer, split by a `filter` on `chosen`.
     *
     * `text-allow-overlap: true` so every route label **always** draws — with
     * `false` the collision detector was eating two of three labels in central
     * Doha, and a route the card describes but the map does not label is the
     * exact defect these layers exist to fix.
     *
     * `text-ignore-placement: false`, though, which is the opposite of the
     * first attempt and the right way round. With `true` the route labels
     * reserved no space, so place labels were drawn straight through them —
     * "10 min" came out on top of "Fereej Al Soudan". With `false` a route
     * label holds its box and the district name moves aside instead. That is
     * the correct priority for the one screen these appear on: while choosing
     * a route, what the routes cost outranks what the neighbourhoods are
     * called. These layers are before `place-labels`, so they win.
     *
     * `text-offset` lifts the label a line clear of the stroke it belongs to,
     * so the text is not read through a 22 px route line.
     *
     * A second trap, worth recording because it cost a build: **this file's
     * style document is a raw string, so `//` inside it is JSON, not a Kotlin
     * comment.** The explanation above started life inside the layer array and
     * made the whole style unparseable. `VectorStyleTest."both themes are
     * valid JSON"` caught it immediately, which is exactly what that test is
     * for.
     */
    internal const val ROUTE_LABEL_LAYERS = 2

    /**
     * The route line. The visual protagonist, by construction.
     *
     * Not "the brightest thing on the map", which is what this said and which
     * is only true at night. Daylight cartography draws its roads white, so on
     * the light theme an arterial road and the whole driving surface are at
     * L\* 100 and the route at 70.5 is DARKER than what it runs over. It is
     * still the protagonist, because it is the only saturated thing in a field
     * of neutrals — which is the property that actually survives both themes,
     * and the one [VectorStyleTest] asserts.
     *
     * Raised from `#3b9cff` (L\* ≈ 62). With the road ramp respanned in V4 the
     * brightest road is now `#8391a6` at L\* ≈ 60, so the old route colour was
     * within two units of an ordinary primary road — it would still have read
     * as the route because of its saturation, but §11's rule is that the route
     * is "the visual protagonist" and two L\* units is not a protagonist.
     *
     * Both references reserve their most luminous value for this line and
     * nothing else: Waze `#2CDDFF`, Google Maps `#00E8FF`. Vector stays in its
     * own blue rather than adopting their cyan — this is the colour a driver
     * has learned means "the route" since V1, and §4 is explicit that Vector
     * should look like Vector.
     */
    /**
     * The congestion ramp, and where the layer that uses it sits.
     *
     * ## Why these are constants and the rationale lives here
     *
     * The style is emitted as a JSON string, and **JSON has no comments** — a
     * `//` line inside it is a parse error, which is exactly how this was first
     * shipped: MapLibre answered `Failed to parse style: Invalid value at
     * offset 54214` and drew a blank beige screen with no map at all. Anything
     * that needs explaining therefore has to be a named Kotlin value the string
     * interpolates, which is the pattern the rest of this file already uses for
     * every colour.
     *
     * ## Why the traffic layer moved above the route
     *
     * It used to be drawn before `route-alt`, which put it underneath
     * `route-casing` and `route`. The route ribbon is opaque and it follows
     * exactly the roads the driver is about to drive — so the one stretch of
     * congestion that could change a decision, the one ON the route, was the
     * one stretch the route covered up. Traffic was visible before setting off
     * and then vanished at the moment it mattered. Reported directly: *"traffic
     * colour should be visible while driving"*, and the layer order was the
     * whole reason.
     *
     * It now sits above `route` and below `route-chevrons`, so congestion
     * paints over the ribbon while the direction arrows stay legible through
     * it — a driver who can see the jam but not which way the route runs
     * through it has been given half a fact.
     *
     * ## Why the line is narrower than the ribbon
     *
     * [TRAFFIC_W_Z18] is 10 against the route's own width, so the route's blue
     * still reads as an edge on both sides of the congestion colour. A traffic
     * line as wide as the route would replace the route rather than annotate
     * it, and the driver would lose the line they are following.
     */
    private const val JAM_JAMMED = "#e0342b"
    private const val JAM_HEAVY = "#f07c1e"
    private const val JAM_SLOW = "#e8c53a"
    private const val JAM_UNKNOWN = "#7d8896"

    /** Congestion line width at z10. See [JAM_JAMMED]. */
    private const val TRAFFIC_W_Z10 = 2.0

    /** Congestion line width at z18 — deliberately under the ribbon's. */
    private const val TRAFFIC_W_Z18 = 10.0

    /**
     * Why the road network is drawn as ONE casing pass and then ONE fill pass.
     *
     * ## The defect
     *
     * A turn lane leaving C Ring Road appeared to start **from the middle lanes
     * of the carriageway**, and roads at that junction "overlapped randomly" on
     * zoom. Both come from the same cause and neither is a data problem.
     *
     * Road classes used to be drawn class by class, each one's casing
     * immediately followed by its own fill: service, minor, tertiary,
     * secondary, links, then primary's casing and fill, then motorway's. That
     * interleaving has two consequences at every junction:
     *
     *  * **a casing can land on top of a fill.** Primary's casing is drawn
     *    after secondary's fill, so a grey edge cuts straight across a white
     *    ribbon wherever the two cross — which is the "overlapping" the report
     *    describes;
     *  * **fills cannot merge.** Two roads meeting should read as one
     *    continuous surface. Drawn in separate passes with a casing between
     *    them they read as two ribbons laid over each other, and the seam
     *    appears wherever their centrelines meet — which for a link joining a
     *    dual carriageway is the middle of the carriageway.
     *
     * ## The fix, and why it addresses the "middle lanes" complaint
     *
     * Every casing first, in ascending class order, then every fill in the
     * same order. Now **no casing is ever drawn after any fill**, so nothing
     * cuts across anything; and all the fills composite into one continuous
     * white surface, so a link joining a carriageway has no seam of its own.
     * The merged shape's only visible boundary is the carriageway's casing —
     * so the link reads as emerging from the **edge** of the road, which is
     * what it does in the world.
     *
     * The underlying geometry has not changed and cannot: OSM ways are
     * centrelines and the link really does attach to a node on one. What
     * changes is that the attachment is no longer *drawn*, because it is
     * inside a surface rather than between two overlapping ones. This is the
     * technique every serious basemap style uses for exactly this reason.
     *
     * ## What this also fixed
     *
     * `service`, `minor`, `tertiary` and `secondary` had **no casing at all** —
     * they were bare lines with no edge, so two of them crossing produced one
     * undifferentiated blob. They all have casings now, at the same ~1.3x
     * casing-to-fill ratio the primary pair already used.
     *
     * A road rendered as a single line has no edge, so where two of them cross
     * — a slip road over a service road, a U-turn link over its own
     * carriageway — the eye gets one continuous white shape and cannot tell
     * which way either road runs. Reported from a real junction on C Ring Road
     * as roads "overlapping randomly" on zoom.
     *
     */
    private const val ROUTE = "#4cb4ff"
    private const val ROUTE_CASING = "#06253f"

    /**
     * The world-space warning pill, held constant across both themes.
     *
     * §4.4's doctrine: what the driver ACTS on must not change meaning with the
     * time of day. A warning about a bend is exactly that, so unlike every
     * other surface in this file the pill is one object with one appearance —
     * dark with a light rim, which separates from a near-black night ground and
     * from a pale daylight one without either palette having an opinion about
     * it.
     */
    private const val CALLOUT_FILL = "#141a24"
    private const val CALLOUT_RIM = "#8fa1b8"
    private const val CALLOUT_TEXT = "#eef3f9"

    /**
     * Where warnings start appearing.
     *
     * The same zoom the lane markings start at, and for the same reason: below
     * it the driver is reading the map rather than the road, and a pill stuck
     * to a place they are not near yet is clutter over the thing they are
     * actually looking at.
     */
    private const val CALLOUT_MINZOOM = 15

    /**
     * How opaque an extruded building is (V7 3D).
     *
     * Not 1.0, and the reason is specific to what is underneath: a Doha tile
     * at z15 can hold a tower whose footprint covers most of the viewport at
     * 60 degrees of pitch, and at full opacity it hides the road and the
     * route behind it as the camera swings. Slightly transparent keeps the
     * mass readable as a volume while the carriageway and the ribbon stay
     * visible through it, which is the acceptance condition this stage is
     * judged on — 3D buildings must not obscure navigation.
     */
    private const val BUILDINGS_3D_OPACITY = 0.85

    /**
     * The direction chevrons embossed along the ribbon.
     *
     * ## Why this is the character `>` and not an arrow
     *
     * There is no sprite in this document and the glyph store serves exactly
     * one range: `Open Sans Regular/0-255` is 76 kB and every other range
     * — 8192-8447, 8448-8703, 9472-9727, 10240-10495, the ones that hold →, ▶,
     * ❯ — answers **2 bytes with HTTP 200**, which is the tile server's way of
     * saying a stack has no glyphs there and is the same silent failure the
     * file KDoc records for a missing font name.
     *
     * So the chevron has to come out of Latin-1, and `>` is the one character
     * in it that is already a chevron. Laid along the line with
     * `text-rotation-alignment: map` it points the way the route goes.
     *
     * `text-keep-upright` must be **false**, and that is not a style
     * preference. It defaults to TRUE for line placement, which flips a glyph
     * end-for-end whenever the line runs right-to-left across the screen so
     * that text stays readable — correct for a street name and catastrophic
     * for an arrow, which would then point backwards down every westbound leg.
     *
     * White rather than a tint, at 0.6: the chevron is a modulation of the
     * route and not a second meaning, and §4.4 holds the route's own blue
     * constant across both themes, so the mark on top of it is constant too.
     */
    private const val CHEVRON = "#ffffff"

    /**
     * The walk, in two colours (V7 Phase 4).
     *
     * Amber where the model puts the walker in direct sun, teal where it puts
     * them in shade. Two colours rather than a gradient because the underlying
     * estimate is not precise enough to justify a continuum — a gradient would
     * read as a measurement, and this is a model with an assumed facade. The
     * split is at [WALK_SHADE_SPLIT], and the legend says "estimated".
     *
     * Deliberately NOT the route blue. A driver has been taught since V1 that
     * blue means "the line you drive", and the walk is the part you cannot.
     */
    private const val WALK_EXPOSED = "#f5a524"
    private const val WALK_SHADED = "#2ec4b6"

    /**
     * The walking NAVIGATION route (V7.4 4C final).
     *
     * ## Why this is a separate source from the walk overlay above
     *
     * [WALK_EXPOSED] and [WALK_SHADED] carry a MEANING — amber is "the model
     * puts you in direct sun". The route someone is actually navigating is
     * drawn into its own `walk-route` source, which carries no `shaded`
     * property at all, so it cannot inherit a sun claim by being drawn through
     * the overlay's filters.
     *
     * Teal rather than the route blue, for the reason the overlay gives: blue
     * has meant "the line you drive" since V1, and this is the part you walk.
     * Solid rather than dashed, because dashes are how the overlay distinguishes
     * an ESTIMATE from a measurement, and this geometry is the backend's own.
     *
     * ## One correction, measured rather than assumed (V7.4 shade)
     *
     * This comment used to claim the colour was "deliberately a separate colour
     * from ... `WALK_SHADED`". It is not: the two constants hold the SAME
     * `#2ec4b6`. The sources are separate and the layers are separate, so
     * nothing draws a wrong shade today — but the colour values do collide, and
     * a shade overlay drawn on top of the navigated route would be invisible
     * against it. See below for what that ruled out.
     *
     * ## Why there is still no sun/shade overlay on the navigated route
     *
     * The V7.4 shade stage added a real, provenance-tagged route-level shade
     * fact ([dev.vector.geo.walk.WalkShade]) and deliberately did NOT put it on
     * this map. Three measured reasons, each of which would have produced a
     * misleading picture:
     *
     *  1. **The two-state overlay is invisible.** A "modelled shade" band would
     *     have to be [WALK_SHADED], which is this line's own colour: the overlay
     *     would read as nothing at all on the one surface a walker is looking
     *     at. A distinct shade colour is a new entry in the colour vocabulary,
     *     which is a map-style change and not a shade-stage change.
     *  2. **The one-sided overlay claims by absence.** Drawing only the modelled
     *     EXPOSED segments in amber would leave every segment the model
     *     DECLINED (an open plaza, an unknown class, a backend with no
     *     `classes`) looking exactly like a modelled-shaded one. "No amber" is
     *     not shade — and `WalkShade.noEvidenceM` exists precisely because that
     *     distinction is real on this data. §10: unknown must have no
     *     misleading shade overlay.
     *  3. **It could not be checked.** There is no device and no working
     *     emulator on this host, so a new rendering would ship unverified — the
     *     same limitation 4C-final recorded as `REAL-DEVICE VALIDATION: NONE`.
     *
     * The route-level fact is presented where the brief puts it: the walking
     * detail strip ([dev.vector.geo.walk.WalkShade.stripLine]) — and the
     * per-segment facts behind it are on `WalkShade.segments`, one line away
     * from any future overlay that can satisfy the three conditions above.
     */
    private const val WALK_ROUTE = "#2ec4b6"

    private const val PUCK = "#3b9cff"
    private const val PUCK_RING = "#ffffff"
    private const val LEARNED = "#34c759"

    /**
     * The palette for a theme.
     *
     * [MapTheme.SYSTEM] should be resolved before it gets here. It maps to
     * [DARK] rather than throwing, deliberately: an unresolved preference
     * reaching this point is a programming mistake, and rendering the night
     * theme is a far better failure mid-drive than an exception that leaves the
     * driver with no map at all.
     */
    /**
     * Where the walk overlay stops calling a segment shaded.
     *
     * A threshold rather than a gradient, for the reason [WALK_EXPOSED]
     * documents: the estimate is not precise enough to justify a continuum.
     * Half is the honest split — below it the model has more of the walker in
     * shade than in sun.
     *
     * **The value lives on the model, not here.** It is
     * [dev.vector.geo.walk.WalkShade.SHADE_SPLIT], because the same half is
     * what decides whether a whole walking route reads "Mostly shaded" or
     * "Mostly exposed" on the navigation detail strip. Two literals named
     * "shaded" that can drift apart is exactly how the map and the words above
     * it come to disagree about one walk, so there is one number and this is a
     * reference to it.
     */
    const val WALK_SHADE_SPLIT = dev.vector.geo.walk.WalkShade.SHADE_SPLIT

    // ---- the carriageway, in metres ----------------------------------------
    //
    // Everything below turns a real-world width into a MapLibre `line-width`,
    // and it is the only place in the app that does. A literal pixel width for
    // a road anywhere else is a bug: the whole argument for the 3D road (§5.1)
    // is that the surface is drawn at TRUE metric width, which is what lets a
    // driver match the screen to the windscreen instead of translating.

    /**
     * One lane, in metres.
     *
     * 3.5 m is the safe default and the figure the geometry below is derived
     * from. Qatar's highway design manual may specify 3.65 m for arterials; the
     * difference is 4%, which is invisible on screen — but it is invisible
     * ONCE, here, rather than in nine layers, which is the only reason this is
     * a named constant rather than a literal.
     *
     * Explicit `width` exists on 224 ways in the entire country extract, so
     * this is synthesised from `lanes` (92% of the arterial network) and there
     * is no version of this that reads a real width off the tile.
     */
    const val LANE_WIDTH_M = 3.5

    /** Kerb-to-carriageway margin, per side. Gives the skirt its overhang. */
    private const val CURB_M = 0.6

    /** The same, for a bridge deck, which reads as thicker because it is. */
    private const val DECK_CURB_M = 1.2

    /**
     * How far down the SCREEN the skirt is drawn from the deck it carries.
     *
     * The one place in this file that is measured in screen pixels rather than
     * in metres, and the reason is that it is modelling a height rather than a
     * distance on the ground.
     *
     * MapLibre `line` layers are flat: there is no geometry here standing up
     * off the map, and a wider dark line under a narrower one is only an
     * outline. `line-translate` with `line-translate-anchor: viewport` shifts
     * the skirt a constant few pixels down the screen instead — so it peeks out
     * further on the near side of the deck than on the far side, which is
     * exactly what the visible face of something with thickness does.
     *
     * Constant in screen pixels, deliberately: a real kerb is the same height
     * whatever the zoom, and scaling this with the road would make a six-lane
     * carriageway look like a cliff.
     */
    private const val SKIRT_LIFT_PX = 2.5

    /**
     * Latitude the metric width is calibrated at.
     *
     * Web Mercator's scale is latitude-dependent, so a "3.5 m" line is only
     * 3.5 m at one parallel. Doha's is the one the app is driven at; Qatar
     * spans 24.5°–26.2°, across which the error is ±1%, which is smaller than
     * the lane-width assumption above.
     */
    private const val CALIBRATION_LAT_DEG = 25.2854

    /**
     * Metres per logical pixel at zoom 0, at [CALIBRATION_LAT_DEG].
     *
     * ## P4 — the probe this whole file rests on
     *
     * MapLibre's zoom is defined against 512-logical-pixel tiles, so
     *
     * ```text
     *   metres per logical pixel = 40_075_016.686 x cos(lat) / 512 / 2^z
     * ```
     *
     * which is 70,789 / 2^z at Doha. This is NOT a guess: it is the arithmetic
     * inside `Projection.getMetersPerPixelAtLatitude` in the MapLibre Android
     * SDK (`GeometryConstants.RADIUS_EARTH_METERS = 6378137`, tile size 512),
     * and `simplify.py:39-40` states the same convention from the bake side.
     *
     * If it were wrong it would be wrong by exactly 2x — every road half or
     * double its true width, plausible-looking either way — which is why it is
     * derived here from named constants instead of being pasted in as two
     * magic numbers, and why [laneWidthPx] is public so a test can check it
     * against the table in V8 §5.3.
     */
    private val METRES_PER_PX_Z0: Double =
        40_075_016.686 * kotlin.math.cos(Math.toRadians(CALIBRATION_LAT_DEG)) / 512.0

    /** Logical pixels covering [metres] on the ground at [zoom]. */
    fun metresPx(metres: Double, zoom: Double): Double =
        metres * Math.pow(2.0, zoom) / METRES_PER_PX_Z0

    /** Logical pixels across one lane at [zoom]. */
    fun laneWidthPx(zoom: Double): Double = metresPx(LANE_WIDTH_M, zoom)

    /**
     * The two zooms the width expressions interpolate between.
     *
     * Any two would do. Web Mercator's scale doubles per zoom step, so an
     * `["exponential", 2]` interpolation through two correct stops is exact at
     * every zoom between and beyond them — these are endpoints of a derivation,
     * not tuning knobs, and moving them changes nothing that renders.
     */
    private const val W_Z0 = 14.0
    private const val W_Z1 = 20.0

    /**
     * How many lanes to draw a way as.
     *
     * `lanes` is read where it exists — 92% of the arterial network, 99% on
     * every high class (V8 §3.1) — and everything else falls back by class
     * rather than to one number, because a 7 m parking aisle is as wrong as a
     * 3.5 m arterial.
     *
     * `case`/`has` rather than `coalesce` on `to-number`: a missing tag is a
     * MISSING TAG, and relying on `coalesce` to swallow a conversion error is
     * relying on undefined behaviour in the expression evaluator.
     *
     * On a two-way street `lanes` counts both directions, and on a one-way
     * carriageway it counts that direction's — which is the same thing for
     * this purpose, and the reason the 96% `oneway` rate in §3.1 means the
     * carriageways are already mapped separately and need no splitting.
     *
     * `lw` is read before either: the bake's width taper
     * (`vector_tile_gen/taper.py`, `--taper`). Where one way continues into
     * another with a different lane count, the last stretch of the wider way
     * arrives as short pieces whose `lw` steps from its own width down to the
     * narrower one, so the surface, kerb and skirt narrow over ~30 m per lane
     * instead of jumping at the node — the notch measured on the S24 Ultra
     * (V8 native acceptance §9.4). `lanes` on those pieces is untouched; `lw`
     * is paint only. A release without tapers has no `lw` anywhere, and this
     * then reads exactly as before.
     */
    private const val LANES =
        """["case", ["has", "lw"], ["to-number", ["get", "lw"]],
            ["has", "lanes"], ["to-number", ["get", "lanes"]],
            ["match", ["get", "highway"],
              ["service", "track"], 1,
              ["motorway", "trunk", "primary"], 3,
              2]]"""

    /**
     * Every road the car can actually drive on, which is what gets a surface.
     *
     * Not a `taper_carrier`. Where the bake tapers a way's width (`--taper`,
     * `vector_tile_gen/taper.py`) it also keeps the whole, untapered way as a
     * CARRIER — the only copy with the road's name, so labels are placed on
     * exactly the geometry they always were — and this style draws the body
     * and taper parts instead. A build older than this one draws the carrier
     * too, at full width, which is the original road with the parts inside it.
     */
    private const val DRIVABLE =
        """["all", ["==", ["get", "kind"], "road"], ["==", ["get", "car"], true],
            ["!", ["has", "taper_carrier"]]]"""

    /**
     * A dedicated TURN POCKET: a `*_link` whose every lane is a turn.
     *
     * OSM maps the left-turn and U-turn bays of a big signalised junction as
     * their own ways. At C Ring × Rawdat Al Khail they are `trunk_link`,
     * `lanes=3`, `turn:lanes=reverse;left|left|left`, and 243–370 m long, laid
     * down the MEDIAN between the two carriageways. Drawn like any other road
     * — a full-width deck with its own kerb — each read as a separate highway
     * running out of the middle of the junction, and its kerb rim showed
     * through the surface as stray white lines (field report 2026-09-24).
     *
     * A pocket is part of the road it leaves, so it is drawn as that road's
     * surface: the deck stays, the kerb casing and the skirt do not (see
     * [carriagewayLayers]), and it fuses with the carriageway beside it.
     *
     * Every lane must be a turn. A `through` or `none` lane, a `merge_*` lane,
     * or an EMPTY lane (`left||`, `|left`, `left|`, which OSM uses for an
     * unmarked through lane) makes it an ordinary link. The empty-lane test
     * wraps the value in `|` so a leading, trailing or middle empty lane all
     * show up as `||`. `index-of` rather than `in`: it is the substring
     * operator already used on device ([POI_NAME_HAYSTACK]) and the one
     * `StyleProbe` evaluates.
     */
    private const val TURN_POCKET =
        """["all",
            ["match", ["get", "highway"],
              ["motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"], true, false],
            ["has", "turn:lanes"],
            ["==", ["index-of", "through", ["get", "turn:lanes"]], -1],
            ["==", ["index-of", "none", ["get", "turn:lanes"]], -1],
            ["==", ["index-of", "merge", ["get", "turn:lanes"]], -1],
            ["==", ["index-of", "||", ["concat", "|", ["get", "turn:lanes"], "|"]], -1]]"""

    private const val NOT_TURN_POCKET = """["!", $TURN_POCKET]"""

    /**
     * A `line-width`/`line-offset` expression for a width of
     * `lanes x laneWidth x [lanes] + [flat] metres`.
     *
     * ## Why the interpolate is on the OUTSIDE
     *
     * The obvious way to write this is
     * `["*", lanes, ["interpolate", ..., ["zoom"], ...]]`, and V8 §5.3 proposes
     * exactly that. **MapLibre rejects it**, with
     * *"`zoom` expression may only be used as input to a top-level `step` or
     * `interpolate` expression"* — the string is in `libmaplibre.so` and the
     * failure is a style that does not parse, which renders a blank map and
     * reports nothing (the same class of silent failure as the missing font
     * stack recorded in the file KDoc).
     *
     * So the zoom interpolation is the outer expression and the per-feature
     * arithmetic happens in its STOP OUTPUTS, which is legal and, because the
     * scale doubles per zoom step under `["exponential", 2]`, exactly
     * equivalent.
     */
    private fun metricWidth(lanes: Double, flat: Double = 0.0): String {
        fun stop(z: Double): String {
            val per = laneWidthPx(z) * lanes
            val add = metresPx(flat, z)
            val perStr = "%.4f".format(per)
            return if (flat == 0.0) """["*", $LANES, $perStr]"""
            else """["+", ["*", $LANES, $perStr], ${"%.4f".format(add)}]"""
        }
        return """["interpolate", ["exponential", 2], ["zoom"],
                   $W_Z0, ${stop(W_Z0)}, $W_Z1, ${stop(W_Z1)}]"""
    }

    /**
     * `line-offset` for divider [index] of a road with exactly [lanes] lanes.
     *
     * Divider 0 sits one lane in from the left kerb, so its offset from the
     * centreline is `(index + 1 - lanes/2)` lanes. Negative is left in
     * MapLibre, which is why the near-side dividers come out negative.
     *
     * ## Why the lane count is a Kotlin parameter and not a `["get"]`
     *
     * This is the one place in the file where the geometry is baked per lane
     * count rather than read from the feature, and it costs 15 layers where 5
     * would do. **`line-dasharray` and a data-driven `line-offset` cannot be
     * used on the same layer in MapLibre Native 11.13.5: the layer renders
     * nothing at all** — no warning, no log line, no error. Everything else
     * about the road draws correctly and the lane markings are simply absent,
     * which reads as "this road has no lanes tagged" rather than as a bug.
     *
     * Established by bisection on the emulator against the real style: the
     * dividers were missing with the dash and present without it, every other
     * property held constant. The edges are proof the two halves work apart —
     * they carry a data-driven offset and no dash, and they have drawn from
     * the first build.
     *
     * Making the offset a pure zoom curve is what buys the dash back, and the
     * dash is worth 10 layers: a solid line down a lane boundary is what a road
     * paints where crossing it is forbidden, and Vector has no marking data at
     * all (V8 §7.5). Drawing every divider solid would be stating a rule about
     * every road in Qatar on no evidence. Dashed states nothing.
     *
     * The layers are narrow — each matches one exact lane count — so a tile
     * whose roads are all 2-lane skips 14 of them entirely.
     */
    private fun dividerOffset(lanes: Int, index: Int): String {
        fun stop(z: Double) =
            "%.4f".format(laneWidthPx(z) * (index + 1 - lanes / 2.0))
        return """["interpolate", ["exponential", 2], ["zoom"],
                   $W_Z0, ${stop(W_Z0)}, $W_Z1, ${stop(W_Z1)}]"""
    }

    /**
     * The same offset as [dividerOffset], read from the FEATURE instead of
     * baked per lane count — which is legal here and nowhere else.
     *
     * ## Why this exists, and why it is not how every divider is drawn
     *
     * [dividerOffset] costs 15 layers where 5 would do, and its KDoc explains
     * the reason exactly: `line-dasharray` and a data-driven `line-offset` on
     * the same layer render NOTHING in MapLibre Native 11.13.5. That bug is
     * about the pair. A marking with no dash is not half of the pair, and the
     * proof is already in this file — `carriageway-edge-left`/`-right` have
     * carried a data-driven offset since the first build and have always drawn.
     *
     * So the SOLID markings — [approachDividers], where `turn:lanes` says the
     * lanes are assigned and crossing between them is forbidden — collapse
     * from fifteen layers to five: one per divider INDEX, with the lane count
     * coming off the feature. The index still has to be per-layer, because a
     * layer draws one line and index is which line it is.
     *
     * The arithmetic is `(index + 1 - lanes / 2)` lanes from the centreline,
     * identical to [dividerOffset]; the only difference is that `lanes` is an
     * expression rather than a number. Halving is written as `["*", 0.5, ...]`
     * rather than `["/", ..., 2]` because the two are the same number and only
     * one of them is in `StyleProbe`'s interpreter — and an expression the
     * tests cannot evaluate is an expression nothing checks. Interpolate on the OUTSIDE and the
     * per-feature arithmetic in the stop outputs, for the reason [metricWidth]
     * documents at length: a `zoom` expression may only be the input to a
     * top-level `step` or `interpolate`, and violating that is a style that
     * does not parse and a map that renders blank without saying why.
     */
    private fun liveDividerOffset(index: Int): String {
        fun stop(z: Double) =
            """["*", ["-", ${index + 1}, ["*", 0.5, $LANES]], ${"%.4f".format(laneWidthPx(z))}]"""
        return """["interpolate", ["exponential", 2], ["zoom"],
                   $W_Z0, ${stop(W_Z0)}, $W_Z1, ${stop(W_Z1)}]"""
    }

    /**
     * The dash a LANE divider is painted with, in multiples of its own width.
     *
     * ## What this pattern does and does not claim
     *
     * Dashed states nothing about whether the line may be crossed, which is
     * the whole reason [dividerOffset]'s KDoc gives for dashing every divider:
     * Vector has no marking data, and a solid line is a rule. Two markings in
     * this file are now allowed to say more than that, and both of them say it
     * from a tag rather than from an assumption — see [CENTRELINE_DASH] and
     * [approachDividers].
     *
     * ## The one thing that is wrong with this and is not being fixed here
     *
     * `line-dasharray` is in multiples of `line-width`, and [MARKING_WIDTH] is
     * deliberately NOT metric — it runs 0.4 px at z15 to 4.4 px at z20, where
     * a metric width would run over a factor of 32. So the dash PERIOD on the
     * ground is 6.5 x width, which works out at **5.6 m at z15 and 1.9 m at
     * z20**: the closer the driver looks, the shorter the dashes get, in a
     * file whose entire thesis is that road geometry is drawn at true metric
     * width. A real Qatari lane line is about 3 m of paint in a 12 m cycle and
     * does not change length when you walk towards it.
     *
     * Making it metric means a zoom-stepped `line-dasharray` — legal, since
     * the property is zoom-dependent — with the multiple computed per stop as
     * `metresPx(3.0, z) / MARKING_WIDTH(z)`. It is NOT done here because it
     * could not be VERIFIED here: a dasharray MapLibre rejects renders the
     * layer as nothing at all with no error (the same silent class as the
     * missing fontstack in the file KDoc and the offset/dash pair above), this
     * host has no Docker for `scripts/native-render` and its emulator will not
     * take the camera past z16 without a live route. Shipping an unverifiable
     * change to the property with that failure mode is how the lane markings
     * disappear. It is the next thing to do on a device.
     */
    private const val LANE_DASH = "[2.5, 4]"

    /**
     * The dash the line between OPPOSING streams is painted with.
     *
     * A two-way road's middle line is not a lane divider. It divides traffic
     * coming at you from traffic going with you, and no cartography anywhere
     * draws those two boundaries the same — the file drew them identically
     * until this pass, because the divider layers were keyed on lane count
     * alone and `oneway` was never consulted.
     *
     * More paint and less gap, rather than solid. Solid would claim crossing
     * is forbidden, which is the claim [dividerOffset] refuses to make on no
     * evidence and which stays refused: what `oneway` supports is "these are
     * opposing streams", not "you may not overtake". A longer mark reads as a
     * firmer boundary without asserting a rule.
     *
     * Which divider this is falls out of the lane count: on a road with an
     * even number of lanes the divider at index `lanes / 2 - 1` sits at offset
     * zero, which is the centreline. Odd lane counts have no divider on the
     * centreline and so have no centreline marking, which is correct — a
     * three-lane two-way road has its middle lane shared or tidal and Vector
     * has nothing that says which.
     *
     * In 49 z15 tiles over central Doha this is `lanes=2` and almost nothing
     * else: 1,854 two-way ways carry `lanes=2`, 3 carry `lanes=4` and none
     * carries `lanes=6`. So the generality below costs two layers that will
     * almost never match, and is written generally anyway because the rule is
     * about the geometry and not about Doha.
     */
    private const val CENTRELINE_DASH = "[6, 4]"

    /**
     * The widest road that gets its lanes marked.
     *
     * Six covers 98%+ of the Qatari arterial network. A seven-lane carriageway
     * is still drawn at its true width with its edges, and simply has no
     * interior markings — a missing line, not a wrong road. Raising this is
     * five more layers per step and no new data, so it is a cost decision
     * rather than a capability one.
     */
    internal const val MAX_MARKED_LANES = 6

    /**
     * A zoom-only `line-width` for something [metres] wide, but never thinner
     * than [floorPx] logical pixels.
     *
     * [metricWidth] above is for the basemap, where the lane count comes from
     * the feature. This is for the GeoJSON sources — the route, the walk, the
     * callout leaders — where there is no feature to read and the width is a
     * plain function of zoom.
     *
     * ## Why there is a floor at all
     *
     * A 5.6 m ribbon is 0.04 logical pixels at z10, which is the zoom the route
     * preview frames a 14 km journey at. Metric truth and a visible line are
     * different requirements at different zooms and both are real: on the
     * driving screen the ribbon has to occupy lanes, and in the preview it has
     * to be findable on a map of the whole city.
     *
     * The floor is expressed as a stop rather than a `max`, because a `max`
     * containing a zoom curve is exactly the nesting MapLibre refuses (see
     * [metricWidth]). Below the crossover zoom the two stops carry the same
     * value, which interpolates flat under any base; above it the curve passes
     * through two exactly-metric endpoints under `["exponential", 2]`, which is
     * exactly metric everywhere between.
     */
    private fun floored(metres: Double, floorPx: Double): String {
        // Where the metric width overtakes the floor: metres x 2^z / mpp = floor.
        val cross = kotlin.math.log2(floorPx * METRES_PER_PX_Z0 / metres)
        return """["interpolate", ["exponential", 2], ["zoom"],
                   6, ${"%.3f".format(floorPx)},
                   ${"%.3f".format(cross)}, ${"%.3f".format(floorPx)},
                   $W_Z1, ${"%.3f".format(metresPx(metres, W_Z1))}]"""
    }

    /**
     * The metres a route feature carries in [prop], or [fallbackM] when it does
     * not carry one.
     *
     * `case`/`has` rather than `coalesce`, for the reason [LANES] gives at
     * length: a missing property is a MISSING PROPERTY, and leaning on
     * `coalesce` to swallow a conversion error is leaning on undefined
     * behaviour in the expression evaluator. The fallback is what makes a
     * pre-Stage-4 feature — one LineString with no properties at all — render
     * exactly as it used to.
     */
    private fun featureM(prop: String, fallbackM: Double): String =
        """["case", ["has", "$prop"], ["to-number", ["get", "$prop"]], $fallbackM]"""

    /**
     * [floored], but the metres come from the FEATURE — which is how the route
     * ribbon became lane-true in V7 Stage 4.
     *
     * ## Why a data-driven offset is allowed here and not on the lane dividers
     *
     * [dividerOffset] spends fifteen layers avoiding exactly this, and its KDoc
     * is emphatic about why: `line-dasharray` and a data-driven `line-offset`
     * on the same layer render NOTHING in MapLibre Native 11.13.5 — no warning,
     * no log line. That bug is about the PAIR. The route ribbon and its casing
     * carry no dash, and `carriageway-edge-left`/`-right` have carried a
     * data-driven offset since the first build and have always drawn.
     *
     * `RouteRibbonTest` asserts the no-dash half rather than trusting this
     * comment, because the failure is silent in the worst way: the route would
     * simply be absent, which reads as "no route" rather than as "the style is
     * wrong".
     *
     * ## Why the floor is an expression and not the literal [floorPx]
     *
     * [floored] writes the literal at both ends of the plateau, and copying
     * that here produced a ribbon that got NARROWER as the driver zoomed in.
     *
     * The plateau runs from z6 to the crossover, and the crossover stop has to
     * be the per-feature expression — it is the zoom at which the metric curve
     * takes over, so the two must meet there or the width steps mid-pinch. For
     * a feature of the fallback width that expression evaluates to `floorPx` in
     * exact arithmetic, but not in *printed* arithmetic: the multiplier is
     * rounded to fit the style document, and 5.6 x 0.803580 is 4.499999959,
     * not 4.5. So the plateau ran from a literal 4.5 down to 4.4999999594, and
     * `the width never steps` caught it at z9.45 — forty nanometres of slope,
     * asserted because a width that moves the wrong way is a defect whatever
     * its size, and because the same expression with a real bug in it would
     * have produced a real slope in the same place.
     *
     * Both ends of the plateau are therefore the SAME expression, which is flat
     * by construction for every width rather than only for the fallback one.
     * That is also the more honest shape: the floor is "the width this feature
     * has at the crossover", so a 2.1 m lane ribbon holds a 2.1 m-worth of
     * floor instead of being inflated to the 5.6 m one at preview zoom.
     *
     * The multipliers are printed to fifteen places rather than six for the
     * same reason — nine significant figures is not enough to reconstruct
     * `floorPx` to the 1e-9 the crossover test measures.
     *
     * The zoom interpolation stays the OUTER expression with the per-feature
     * arithmetic in its stop outputs, for the reason [metricWidth] documents: a
     * `zoom` expression may only be the input to a top-level `step` or
     * `interpolate`, and violating that is a style that does not parse and a
     * map that renders blank without saying why.
     */
    private fun flooredFrom(
        prop: String,
        fallbackM: Double,
        floorPx: Double,
        plusM: Double = 0.0,
    ): String {
        val cross = kotlin.math.log2(floorPx * METRES_PER_PX_Z0 / (fallbackM + plusM))
        val m = featureM(prop, fallbackM)
        fun stop(z: Double): String {
            val px = "%.15f".format(metresPx(1.0, z))
            return if (plusM == 0.0) """["*", $m, $px]"""
            else """["+", ["*", $m, $px], ${"%.15f".format(metresPx(plusM, z))}]"""
        }
        return """["interpolate", ["exponential", 2], ["zoom"],
                   6, ${stop(cross)},
                   ${"%.6f".format(cross)}, ${stop(cross)},
                   $W_Z1, ${stop(W_Z1)}]"""
    }

    /**
     * A `line-offset` in metres read from the feature — the ribbon's lateral
     * position, and the visible half of V7 Stage 4.
     *
     * No floor, unlike [flooredFrom]. A width has a legibility argument for
     * staying visible when the map is zoomed out; an offset has the opposite —
     * one that stopped shrinking with the map would slide the route off the
     * road at preview zoom, where the whole carriageway is a third of a pixel.
     *
     * Positive is right of travel in MapLibre, the same convention `RouteLanes`
     * computes in and the same one [dividerOffset] relies on for its near-side
     * dividers coming out negative.
     */
    private fun metricOffset(prop: String): String {
        val m = featureM(prop, 0.0)
        fun stop(z: Double) = """["*", $m, ${"%.15f".format(metresPx(1.0, z))}]"""
        return """["interpolate", ["exponential", 2], ["zoom"],
                   $W_Z0, ${stop(W_Z0)}, $W_Z1, ${stop(W_Z1)}]"""
    }


    /**
     * The route ribbon, in metres, and the margin that keeps it off the kerbs.
     *
     * ## Why this is two lanes and not the whole carriageway
     *
     * V8 §5.6 says "full carriageway width less a small margin". That is not
     * available and would be wrong if it were.
     *
     * Not available, because the route arrives as a bare `LineString` from
     * `/navigate` with no `lanes` on it — the tiles carry the lane count and
     * the route does not, and there is no join between them on the client.
     *
     * Wrong if it were, because a ribbon at full carriageway width covers the
     * lane markings, which are the thing this entire phase exists to draw. The
     * reference agrees with the narrower reading: §2.2's fourth principle is
     * "one to two lanes wide, inset", and what makes it read as a route rather
     * than as a stripe painted over the road is precisely that the carriageway
     * is still visible either side of it.
     *
     * So the ribbon occupies a bit over a lane and a half, at true scale, and
     * says nothing about WHICH lanes. §5.6's lane-level narrowing at
     * `turn:lanes` maneuvers is a separate claim and is not made here.
     *
     * ## Why 1.6 lanes and not 2
     *
     * Two lanes is 7.0 m, which is exactly a two-lane carriageway, so a
     * two-lane ribbon plus any casing at all is wider than the narrowest road
     * that carries lane markings — the route would cover both edge lines of
     * every residential street it ran down. `RouteRibbonTest."the ribbon does
     * not bury the markings underneath it"` is the assertion that caught it,
     * and it caught it at z16.5 where the overflow is half a pixel.
     *
     * At 1.6 lanes the ribbon and its casing both fit inside a two-lane road
     * with the markings showing, and on a six-lane arterial the ribbon reads as
     * occupying lanes rather than as a stripe over the whole surface.
     *
     * A single-lane service road is 3.5 m and the ribbon is wider than it. That
     * is accepted: it is the last fifty metres of a journey, the overflow reads
     * as "the route goes down here", and the alternative is a ribbon too thin
     * to see on every road above it.
     */
    internal const val ROUTE_RIBBON_M = LANE_WIDTH_M * 1.6

    /** The ribbon's casing: just enough to lift it off the asphalt. */
    private const val ROUTE_CASING_M = ROUTE_RIBBON_M + 0.7

    /**
     * How much narrower than its carriageway the ribbon is drawn, in metres.
     *
     * ## Why the ribbon has to narrow at all (V7 Stage 4)
     *
     * Moving the ribbon onto the driven carriageway is only half the
     * correction. A two-way `lanes=2` residential street — 5,728 of them in
     * Qatar — is ONE 3.5 m lane each way, and the default 5.6 m ribbon centred
     * on that lane still spills 1.05 m over the centre line. Better than the
     * 2.8 m it covered before Stage 4, and still the same lie: the driver is
     * shown occupying part of the oncoming lane.
     *
     * So where `RouteLanes` knows the carriageway or lane band the route is in,
     * the ribbon is that width less this margin, and never wider than
     * [ROUTE_RIBBON_M].
     *
     * 1.4 m is chosen so the case the default was TUNED for comes out
     * unchanged: a two-lane carriageway is 7.0 m and 7.0 - 1.4 is exactly
     * 5.6 m. The margin is a generalisation of the existing ribbon rather than
     * a new number competing with it, which is why `the ribbon is the same
     * number of lanes wide at every driving zoom` still measures 1.6 lanes on
     * the road it always measured.
     *
     * It also keeps the CASING inside the carriageway, which is the assertion
     * that catches this class of error: on a single 3.5 m lane, 2.1 m of
     * ribbon plus 0.7 m of casing is 2.8 m, with both lane lines showing.
     */
    private const val ROUTE_LANE_MARGIN_M = LANE_WIDTH_M * 0.4

    /** Below this the ribbon stops being a ribbon, whatever the road says. */
    private const val ROUTE_MIN_RIBBON_M = 1.0

    /**
     * The ribbon's width inside a carriageway or lane band [widthM] across.
     *
     * Kotlin rather than a style expression because it is a decision about what
     * to CLAIM, and it is taken once per route slice rather than per frame.
     * Null — the model declining to place the route — keeps the tuned default.
     */
    fun ribbonWidthIn(widthM: Double?): Double {
        if (widthM == null) return ROUTE_RIBBON_M
        return (widthM - ROUTE_LANE_MARGIN_M).coerceIn(ROUTE_MIN_RIBBON_M, ROUTE_RIBBON_M)
    }

    /** An alternative is drawn narrower as well as duller. */
    private const val ROUTE_ALT_M = LANE_WIDTH_M * 1.3
    private const val ROUTE_ALT_CASING_M = ROUTE_ALT_M + 0.7

    /**
     * How far apart the direction chevrons sit along the ribbon, in metres.
     *
     * A distance on the ground rather than on the screen, so they hold their
     * spacing relative to the road as the camera moves — about three car
     * lengths, which is close enough to read as a flow and far enough not to
     * become a dotted line.
     */
    const val CHEVRON_SPACING_M = 22.0

    /** How far a chevron's apex sits ahead of its wings. */
    const val CHEVRON_LENGTH_M = 3.0

    /**
     * How far each wing sits from the centreline.
     *
     * Derived from [ROUTE_RIBBON_M], so the mark is about two thirds of the
     * ribbon across and cannot overhang it whatever either is set to.
     */
    const val CHEVRON_HALF_WIDTH_M = ROUTE_RIBBON_M * 0.32

    /** The stroke the mark is painted with — half a metre of it. */
    private const val CHEVRON_STROKE_M = 0.55


    /**
     * The marking ink ramp, in logical pixels, as `(zoom, px)` stops.
     *
     * One source of truth for two consumers: [MARKING_WIDTH] is this list
     * rendered as a MapLibre expression, and [casingWidth] needs the same
     * numbers as ARITHMETIC to add a marking's width to each side of a
     * carriageway. Writing the ramp twice — once as an expression and once as a
     * Kotlin function — is how the two drift, and the drift would be a casing
     * rim that is not the width of the marking it is supposed to be.
     */
    private val MARKING_STOPS =
        listOf(15.0 to 0.4, 16.0 to 0.7, 17.0 to 1.1, 18.0 to 1.7, 20.0 to 4.4)

    /**
     * The width of a painted marking, which is NOT metric, and deliberately.
     *
     * A lane line is 0.10-0.15 m of paint. At z18 that is 0.55 logical pixels,
     * which antialiases into a grey suggestion of a line — the marking would be
     * geometrically true and functionally absent. Everything about WHERE a
     * marking sits is metric ([dividerOffset], [metricWidth]); only how much
     * ink it gets is tuned, and it is tuned up.
     *
     * The ramp is also why the dash pattern below is specified in multiples of
     * the line width rather than in metres: dashes scale with the line, so they
     * hold their proportions instead of aliasing into a dotted smear in the far
     * field at 60 degrees of pitch.
     */
    private val MARKING_WIDTH =
        """["interpolate", ["linear"], ["zoom"], ${
            MARKING_STOPS.joinToString(", ") { (z, px) -> "${"%.1f".format(z)}, $px" }
        }]"""

    /** [MARKING_WIDTH] evaluated at [z], clamped outside the ramp's own stops. */
    private fun markingPx(z: Double): Double =
        MARKING_STOPS.let { s ->
            when {
                z <= s.first().first -> s.first().second
                z >= s.last().first -> s.last().second
                else -> {
                    val i = s.indexOfFirst { it.first >= z }
                    val (z0, v0) = s[i - 1]
                    val (z1, v1) = s[i]
                    v0 + (v1 - v0) * (z - z0) / (z1 - z0)
                }
            }
        }

    /**
     * `line-width` for a carriageway's casing: the deck, plus one marking's
     * width on each side.
     *
     * ## Why the stops are half a zoom apart
     *
     * The deck term must stay under `["exponential", 2]` — that is what makes
     * the metric width exact at EVERY zoom outside the stops, not just at them
     * ([metricWidth]). The rim term is [MARKING_STOPS], which is a LINEAR ramp,
     * and a single exponential curve through the sum of a linear ramp and a
     * doubling one cannot be right between stops: measured on the first version
     * of this function, which had [MARKING_STOPS] alone as its stops, the rim
     * was 6.1 px where it should have been 5.2 at z19 — a whole marking's
     * difference, because the marking addend grows 2.6x across z18-20 while the
     * exponential curve wants 4x.
     *
     * So the stops are every half zoom across the ramp's range. The deck is
     * still exactly exponential (it is, at every stop, by construction), and
     * the rim now lands within a tenth of a pixel of its true width anywhere
     * between them — asserted in `CarriagewayTest`.
     */
    private fun casingWidth(): String {
        val zooms = buildList {
            add(W_Z0)
            var z = 15.0
            while (z <= 20.0) { add(z); z += 0.5 }
        }
        val body = zooms.joinToString(", ") { z ->
            "${"%.1f".format(z)}, " + """["+", ["*", $LANES, ${
                "%.4f".format(laneWidthPx(z))
            }], ${"%.4f".format(2 * markingPx(z))}]"""
        }
        return """["interpolate", ["exponential", 2], ["zoom"], $body]"""
    }

    /**
     * The carriageway fades UP as the class ramp fades down, over one zoom step.
     *
     * Both are needed and neither is needed twice. Below z15.5 the class ramp
     * is the map — it is what makes the country view legible and it is ranked
     * by `highway`, which is correct for a map being read AS a map. Above z16.5
     * the driver is inside one road and the class of the others has stopped
     * mattering, which is the whole of §4.1.
     *
     * The carriageway itself is not faded — it is drawn at full opacity from
     * [CARRIAGEWAY_MINZOOM] and is simply too narrow to see until the zoom
     * arithmetic makes it wide. Fading a wide line in by opacity would darken
     * every junction where two roads overlap, for a transition nobody watches.
     */
    private const val CLASS_RAMP_FADE =
        """["interpolate", ["linear"], ["zoom"], 15.5, 1, 16.5, 0]"""

    /**
     * Where the surface starts existing.
     *
     * z14 is the first zoom at which residential roads are baked
     * (`build_qatar_tiles._HW_TIERS`), and one lane is 0.81 px there — so this
     * costs almost nothing to draw and means the surface is already in place,
     * at its true width, before the class ramp starts getting out of its way.
     */
    private const val CARRIAGEWAY_MINZOOM = 14

    /**
     * The warnings, as GeoJSON for the `callouts` source.
     *
     * Three features per callout, because a pill is three objects: the dot on
     * the road that says WHERE, the leader that ties the two together, and the
     * pill itself that says WHAT. They share a source and are separated by a
     * `part` property, so one `setGeoJson` moves all three and they can never
     * disagree about where they are.
     *
     * `rank` drives `symbol-sort-key`, and lower wins placement in MapLibre.
     * A speed limit outranks a maneuver, which outranks a bend — the order of
     * how irreversible the mistake is if the driver misses it.
     */
    fun calloutGeoJson(callouts: List<dev.vector.geo.Callouts.Callout>): String {
        if (callouts.isEmpty()) return EMPTY_FEATURES
        val b = StringBuilder(callouts.size * 420)
        for (c in callouts) {
            val rank = when (c.kind) {
                dev.vector.geo.Callouts.Kind.SPEED -> 0
                dev.vector.geo.Callouts.Kind.JUNCTION -> 1
                dev.vector.geo.Callouts.Kind.BEND -> 2
                // V7 Stage 5. Last, deliberately: the signal pill is the
                // weakest claim of the four — a location the driver will
                // reach, not an instruction — so it yields placement to every
                // other pill on a collision.
                dev.vector.geo.Callouts.Kind.SIGNAL -> 3
                // V7.3. Below the signal: a camera pill is the same class of
                // claim (a location ahead), and when both exist at one place
                // the junction is the stronger fact.
                dev.vector.geo.Callouts.Kind.CAMERA -> 4
            }
            if (b.isNotEmpty()) b.append(',')
            b.append("""{"type":"Feature","properties":{"part":"anchor"""")
            // The glyph rides on the ANCHOR, not on the pill: the picture is
            // the thing itself, and it belongs where the thing is. The leader
            // then runs from the picture to the words, which is the same
            // arrangement the dot had — see VectorMarkers.warnSignal.
            c.icon?.let { b.append(""","icon":${quote(it)}""") }
            b.append("""},"geometry":""")
                .append("""{"type":"Point","coordinates":[${c.anchor.lng},${c.anchor.lat}]}},""")
            b.append("""{"type":"Feature","properties":{"part":"leader"},"geometry":""")
                .append("""{"type":"LineString","coordinates":[""")
                .append("[${c.anchor.lng},${c.anchor.lat}],[${c.label.lng},${c.label.lat}]]}},")
            b.append("""{"type":"Feature","properties":{"part":"label","rank":$rank,""")
                .append(""""kind":"${c.kind.name.lowercase()}","source":${quote(c.source)},""")
                .append(""""text":${quote(c.text)}},"geometry":""")
                .append("""{"type":"Point","coordinates":[${c.label.lng},${c.label.lat}]}}""")
        }
        return """{"type":"FeatureCollection","features":[$b]}"""
    }

    /**
     * A JSON string literal.
     *
     * Road names and exit refs come from OSM and reach this file unedited, so
     * a quote or a backslash in one would produce a document MapLibre cannot
     * parse — which renders a blank map and reports nothing, the failure mode
     * this file's KDoc opens with.
     */
    private fun quote(v: String): String {
        val out = StringBuilder(v.length + 2).append('"')
        for (ch in v) when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch < ' ' -> out.append("\\u%04x".format(ch.code))
            else -> out.append(ch)
        }
        return out.append('"').toString()
    }

    /**
     * The direction marks, as GeoJSON for the `route-chevrons` source.
     *
     * Built once when a route arrives rather than per frame. The marks are
     * facts about the route's shape, not about where the car is, so there is
     * nothing to update as the driver moves — which is also why this can afford
     * to mark the whole route instead of a window around the vehicle. A 14 km
     * Doha journey is about 640 marks and roughly a millisecond
     * (`RouteChevronsTest`).
     *
     * Empty for a route too short to carry one, which is a real case: the last
     * leg of a park-and-walk journey can be fifty metres.
     */
    fun chevronGeoJson(
        route: dev.vector.geo.RouteIndex?,
        /**
         * The route's lateral profile, so the marks sit on the carriageway the
         * ribbon sits on (V7 Stage 4). Null — the default — is the centreline,
         * which is what every caller did before Stage 4 and what a route with
         * no lane data still gets.
         */
        lanes: dev.vector.geo.RouteLanes.Plan? = null,
    ): String {
        val marks = route?.let {
            dev.vector.geo.RouteChevrons.marks(
                it, CHEVRON_SPACING_M, CHEVRON_LENGTH_M, CHEVRON_HALF_WIDTH_M,
                lateralM = if (lanes == null) ({ 0.0 }) else ({ d -> lanes.offsetAt(d) }),
            )
        }.orEmpty()
        if (marks.isEmpty()) return EMPTY_FEATURES
        val feats = StringBuilder(marks.size * 140)
        for ((i, mk) in marks.withIndex()) {
            if (i > 0) feats.append(',')
            feats.append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
            for ((j, p) in mk.withIndex()) {
                if (j > 0) feats.append(',')
                feats.append('[').append(p.lng).append(',').append(p.lat).append(']')
            }
            feats.append("]}}")
        }
        return """{"type":"FeatureCollection","features":[$feats]}"""
    }

    /** An empty source payload, so callers do not each spell one. */
    const val EMPTY_FEATURES = """{"type":"FeatureCollection","features":[]}"""

    /**
     * The route, as GeoJSON for the `route` source — one feature per run of
     * constant lateral offset (V7 Stage 4).
     *
     * ## Why the route is several features now
     *
     * `line-offset` is a PAINT property and MapLibre evaluates it once per
     * FEATURE, so a ribbon whose offset varies along the route — which is what
     * a lane change is — cannot be one LineString.
     * `RouteLanes.Plan.slices` cuts the profile into pieces no more than a
     * quarter of a metre from the true ramp, and each piece becomes a feature
     * carrying its own `offset` and `width` in metres.
     *
     * The geometry is cut with `RouteIndex.between`, which interpolates the
     * endpoints rather than snapping them to the nearest vertex: OSM motorway
     * geometry runs 200 m between shape points, and a snapped cut would leave
     * up to 200 m of ribbon either missing or drawn twice at two different
     * offsets.
     *
     * ## Why a route with no lane data is still ONE feature
     *
     * Not an optimisation — a statement. When [lanes] is null or has declined,
     * the output is the single unadorned LineString this source carried before
     * Stage 4, with no `offset` and no `width` property, so [featureM]'s
     * fallbacks give the tuned defaults. "No lane data" renders exactly as it
     * always did, and `RouteRibbonTest` asserts that rather than assuming it.
     */
    fun routeGeoJson(
        route: dev.vector.geo.RouteIndex?,
        lanes: dev.vector.geo.RouteLanes.Plan? = null,
    ): String {
        if (route == null || route.coords.size < 2) return EMPTY_FEATURES
        val plain = listOf(route.coords to "")
        val slices = lanes?.slices().orEmpty()
        if (slices.isEmpty()) return routeFeatures(plain)
        val parts = ArrayList<Pair<List<dev.vector.geo.LngLat>, String>>(slices.size)
        for ((i, s) in slices.withIndex()) {
            // The profile is clamped to the maneuvers; the RIBBON has to cover
            // the whole route.
            //
            // The last maneuver is at the last maneuver's distance, which is
            // not the route's length — on a 1,005 m fixture whose arrival is
            // recorded at 1,000 m, the final five metres had no ribbon on them:
            // a gap at the destination pin, which is the one place a driver is
            // looking hardest. The ends are extended rather than a tail feature
            // appended, because `Plan.lateralAt` already clamps to the first and
            // last control point — so this draws exactly what the model says is
            // there, and does not invent a lateral position for it.
            val from = if (i == 0) 0.0 else s.fromM
            val to = if (i == slices.size - 1) maxOf(s.toM, route.totalM) else s.toM
            val geom = route.between(from, to)
            if (geom.size < 2) continue
            parts.add(
                geom to "\"offset\":${"%.4f".format(s.offsetM)}" +
                    ",\"width\":${"%.4f".format(ribbonWidthIn(s.widthM))}"
            )
        }
        // A profile that produced no drawable piece must still draw the route.
        // Losing the ribbon entirely is far worse than losing its lane truth.
        return routeFeatures(if (parts.isEmpty()) plain else parts)
    }

    /** Line features with pre-rendered property bodies. */
    private fun routeFeatures(
        parts: List<Pair<List<dev.vector.geo.LngLat>, String>>,
    ): String {
        val out = StringBuilder(parts.sumOf { it.first.size } * 26 + 64)
        out.append("""{"type":"FeatureCollection","features":[""")
        for ((i, part) in parts.withIndex()) {
            if (i > 0) out.append(',')
            out.append("""{"type":"Feature","properties":{""").append(part.second)
                .append("""},"geometry":{"type":"LineString","coordinates":[""")
            for ((j, p) in part.first.withIndex()) {
                if (j > 0) out.append(',')
                out.append('[').append(p.lng).append(',').append(p.lat).append(']')
            }
            out.append("]}}")
        }
        return out.append("]}").toString()
    }

    /**
     * The walk, as GeoJSON for the `walk` source.
     *
     * One feature per RUN of consecutive segments that agree about shade,
     * rather than one per segment. A 300 m walk is often 200 segments and
     * MapLibre would draw 200 separate dashes with 200 round caps, which looks
     * like a dotted line rather than a path; runs also keep the feature count
     * down on a source that is rebuilt every time the sun slider moves.
     *
     * Index-aligned with [shade]: [dev.vector.geo.sun.ShadeEstimator.route]
     * scores every segment it is given, in order, including degenerate ones, so
     * the Nth exposure belongs to the Nth segment. A mismatch means the two
     * were computed from different walks and the overlay is not drawn at all.
     */
    /**
     * The walking navigation route, as GeoJSON for the `walk-route` source.
     *
     * One plain LineString, with no properties at all. That is deliberate:
     * every property this style can filter on encodes a CLAIM (shade, lane
     * offset, congestion), and the walking route asserts none of them — it is
     * the geometry the backend returned and nothing more.
     *
     * Returns an empty collection for fewer than two points, so a degenerate
     * or refused walk draws nothing rather than a dot or a straight line
     * between two points the pedestrian network could not connect.
     */
    fun walkRouteGeoJson(coords: List<dev.vector.geo.LngLat>): String {
        if (coords.size < 2) return EMPTY_STYLE_FC
        val out = StringBuilder(coords.size * 26 + 96)
        out.append(
            """{"type":"FeatureCollection","features":[{"type":"Feature",""" +
                """"properties":{},"geometry":{"type":"LineString","coordinates":["""
        )
        for ((i, p) in coords.withIndex()) {
            if (i > 0) out.append(',')
            out.append('[').append(p.lng).append(',').append(p.lat).append(']')
        }
        return out.append("]}}]}").toString()
    }

    fun walkGeoJson(
        walk: dev.vector.geo.journey.WalkLeg,
        shade: dev.vector.geo.sun.RouteShade,
    ): String {
        val segs = walk.segments
        if (segs.isEmpty() || segs.size != shade.segments.size) return EMPTY_STYLE_FC
        val features = StringBuilder()
        var runStart = 0
        fun shadedAt(i: Int) = shade.segments[i].exposure < WALK_SHADE_SPLIT
        for (i in segs.indices) {
            val last = i == segs.lastIndex
            if (last || shadedAt(i) != shadedAt(i + 1)) {
                if (features.isNotEmpty()) features.append(',')
                val coords = (runStart..i).joinToString(",") { j ->
                    "[${segs[j].from.lng},${segs[j].from.lat}]"
                } + ",[${segs[i].to.lng},${segs[i].to.lat}]"
                features.append(
                    """{"type":"Feature","properties":{"shaded":${shadedAt(i)}},""" +
                        """"geometry":{"type":"LineString","coordinates":[$coords]}}"""
                )
                runStart = i + 1
            }
        }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }

    internal const val EMPTY_STYLE_FC = """{"type":"FeatureCollection","features":[]}"""

    fun palette(theme: MapTheme): Palette = when (theme) {
        MapTheme.LIGHT -> LIGHT
        MapTheme.DARK, MapTheme.SYSTEM -> DARK
    }

    /** Tiles and glyphs are served without auth, so the style needs no token. */
    /**
     * The label expression for a given language.
     *
     * `lang == "en"` prefers OSM's `name:en` and falls back to the local name.
     * **The tiles have carried `name:en` all along** — a z13 tile over Doha
     * lists `name`, `name:en` and `ref` among its property keys — and the
     * style read `["get", "name"]` only, so the map was Arabic-only while V3's
     * router happily returned "via Al Urouba Street".
     *
     * That inconsistency was worse than either language used throughout: the
     * maneuver banner named a road in English and the map underneath it named
     * the same road in Arabic, four millimetres apart. Same rule as
     * `vector_routing.router.display_name`, so the two agree by construction.
     *
     * `coalesce` rather than a `match` on a locale property: the fallback has
     * to be per-FEATURE, since coverage is 99.5% and not 100%.
     */
    fun nameExpr(lang: String?): String =
        if (lang == "en") """["coalesce", ["get", "name:en"], ["get", "name"]]"""
        else """["get", "name"]"""

    // -----------------------------------------------------------------------
    // Which POIs a driver is shown
    // -----------------------------------------------------------------------
    //
    // Reported from the S24 on 2026-09-13, driving Rawdat Al Khail: *"too many
    // points of interest are shown while driving making it messy"*, and then,
    // separately and correctly, *"there are a lot of POIs that don't exist
    // right now in real life"*.
    //
    // Both are true and they have different causes.
    //
    // **The density** is ours. `build_qatar_tiles._POI_FLOOR_BY_ZOOM` raises the
    // POI share of a tile's budget from 6% to 35% at z14-16 — the fix for
    // "Green Tea Garden Restaurant is right in front of my destination and not
    // on the map", which worked. A decoded production tile over Doha now
    // carries 525 POIs of 1,500 features at both z14 and z15. Nothing then
    // chose between them: `poi-labels` filtered on `kind == "poi"` and had no
    // `symbol-sort-key`, so which forty of five hundred won a label was decided
    // by draw order, and the driver got whichever forty that was.
    //
    // **The non-existence** is Overture's. The names on the screenshots —
    // "Truth Group", "CHM Global", "Darkocean SPC LLC", "New International
    // Technology Company WLL", "Giant Migration Qatar" — are business-registry
    // records: a company's registered address inside an office tower, with no
    // shopfront to see from a car and no guarantee it is still trading. They
    // are not invented, and they are not navigable either.
    //
    // So: registry categories are refused a label outright, low-confidence
    // records are refused one, and everything that remains is RANKED, which
    // lets MapLibre's own collision culling spend the labels that do fit on the
    // things a driver might be looking for. Ranking rather than hiding is the
    // important half — it degrades gracefully as the camera zooms in, and it
    // cannot make a place the driver is navigating TO disappear.
    //
    // `poi-labels` also moves from `minzoom: 15` to `minzoom: 16`, which is a
    // statement about SPEED rather than about scale. `MapCamera.ZOOM_BANDS`
    // zooms out as the vehicle goes faster — 16.5 on an urban arterial, 15.8 on
    // a ring road, 14.6 on a motorway — so a floor of 16 means shopfront names
    // stop being drawn at exactly the point the driver stopped being able to
    // read them or turn into them. The dots stay from z14, so the map still
    // shows that there is something there.

    /**
     * Overture `category` values that never earn a place on a driving map.
     *
     * Derived by censusing all 23,199 Overture places for Qatar and grouping
     * their 781 categories into families, not by picking names that sounded
     * wrong. The families and their sizes:
     *
     * ```text
     *   b2b services         807   3.5%   advertising, printing, IT, cleaning
     *   office / registry    723   3.1%   professional_services, corporate_office
     *   real estate          561   2.4%   agents, developers, property management
     *   industrial / trade   440   1.9%   manufacturers, wholesalers, plant
     *   construction         407   1.8%   contractors, building supply
     *   residential          160   0.7%   accommodation, apartments
     *   logistics            158   0.7%   freight, movers, shipping
     *   finance / insurance   80   0.3%   brokers, agencies
     * ```
     *
     * Not a quality judgement about the business — a judgement about whether a
     * driver can see it from the road and might pull in. Four categories the
     * family regexes caught were put back by hand because they are real
     * destinations: `plastic_surgeon`, `dry_cleaning`, `equestrian_facility`
     * and `assisted_living_facility`.
     *
     * A denylist and not an allowlist, with 781 categories in play: an
     * unrecognised category keeps its label and simply ranks last. Being wrong
     * here must cost a POI its PRIORITY, never its existence.
     */
    private val POI_DENY_CATEGORY = listOf(
        // residential — "accommodation" in Qatar is worker housing, not a
        // hotel. All 150 sampled: "Qatar airways accommodation-Al Kuwari",
        // "Toyota accomodation", "Jaidah Group Accommodation", "Al Nasr
        // Building", "Ezdan EB 18 A".
        "accommodation", "apartments", "service_apartments", "housing_authorities",
        // NOT landmarks, whatever the name says. All 583 sampled: "Beverly
        // Hills Tower", "Viva Bahriya Tower 22", "Ezdan Village 5", "Ezdan
        // Building 18B", "Barwa City, Phase 1", "Bilal Executive Suites" —
        // residential towers and compounds, plus bare place-names ("Doha",
        // "Al Nasraniya", "Street 50, Industrial Area"). It is this dataset's
        // dumping ground, and the first version of this list promoted it to
        // the tier ABOVE the default.
        "landmark_and_historical_building",
        // real estate
        "real_estate_service", "real_estate_agent", "property_management",
        "home_developer", "commercial_real_estate", "land_surveying",
        // office and registry
        "professional_services", "corporate_office", "business_to_business",
        "business_advertising", "business_management_services",
        "business_consulting", "business_manufacturing_and_supply",
        "business_signage", "business_storage_and_transportation",
        "accountant", "legal_services", "notary_public", "marketing_consultant",
        "food_consultant",
        // industrial and trade
        "industrial_company", "industrial_equipment", "commercial_industrial",
        "wholesale_store", "wholesaler", "wholesale_grocer", "meat_wholesaler",
        "computer_wholesaler", "chemical_plant", "plastic_manufacturer",
        "plastic_company", "metal_fabricator", "metal_supplier",
        "steel_fabricators", "machine_shop", "glass_manufacturer",
        "appliance_manufacturer", "aircraft_manufacturer",
        "jewelry_and_watches_manufacturer", "auto_manufacturers_and_distributors",
        "geological_services", "oil_and_gas", "oil_and_gas_exploration_and_development",
        "oil_and_gas_field_equipment_and_services", "b2b_energy_and_mining",
        "logging_contractor",
        // construction
        "construction_services", "contractor", "building_supply_store",
        "carpenter", "electrician", "plumbing", "masonry_concrete",
        "paving_contractor", "flooring_contractors",
        // logistics
        "freight_and_cargo_service", "freight_forwarding_agency", "movers",
        "shipping_center", "railroad_freight", "storage_facility",
        // b2b services
        "advertising_agency", "marketing_agency", "internet_marketing_service",
        "printing_services", "screen_printing_t_shirt_printing",
        "graphic_designer", "web_designer", "software_development",
        "information_technology_company", "it_service_and_computer_repair",
        "telecommunications", "telecommunications_company",
        "home_cleaning", "cleaning_services", "carpet_cleaning", "pool_cleaning",
        "cleaning_products_supplier", "pest_control_service", "hvac_services",
        "security_services", "food_delivery_service",
        // finance
        "financial_service", "insurance_agency", "installment_loans",
    )

    /**
     * OSM `poi_class` values that are map furniture, not destinations.
     *
     * The basemap carries 15,816 OSM POIs across 312 classes, and a third of
     * every POI in a shipped tile comes from here rather than from Overture —
     * so leaving this side unfiltered left a third of the map unreachable by
     * any of the rules above. `poi_class` IS in the tiles; it simply had no
     * reader.
     *
     * The signature of furniture is a class whose members are overwhelmingly
     * UNNAMED, because nobody names a bollard:
     *
     * ```text
     *   shelter            1,238    0% named
     *   parking_entrance     511    1%
     *   camp_pitch           189    1%
     *   level_crossing       171    0%
     *   bench                124    0%
     *   gate                 107    0%
     *   toilets               96    1%
     * ```
     *
     * Three classes share that signature and are NOT furniture, so they are
     * deliberately absent: `parking` (1,818, 5% named) is what a driver is
     * looking for at the end of the trip, `place_of_worship` (717, 16%) is
     * 717 mosques in Qatar, and `atm` (132, 15%) is a reason to stop. Low
     * named-ratio is evidence, not a rule.
     */
    private val POI_DENY_CLASS = listOf(
        "shelter", "parking_entrance", "parking_position", "parking_space",
        "camp_pitch", "level_crossing", "tram_level_crossing", "tram_crossing",
        "crossing", "holding_position", "stop_position", "subway_entrance",
        "bench", "gate", "toilets", "shower", "fountain", "drinking_water",
        "watering_place", "waste_basket", "waste_disposal", "bbq", "telephone",
        "post_box", "clock", "smoking_area", "bicycle_parking",
        "motorcycle_parking", "weighbridge", "designated", "surveillance",
        "artwork", "ruins",
        // residential and registry, the OSM-side counterparts of the above
        "apartment", "company", "estate_agent",
    )

    /**
     * Name fragments that mark a building nobody navigates to.
     *
     * A category filter cannot reach these. Within 250 m of one flat in Al
     * Mansoura the map offered "Af Vincent Accommodation", "af vibin
     * accommodation", "Accommodation Of Carrefour In Mansura", "Al Ahli
     * Hospital Accommodation" and "Qatar Airways Simex Ladies Accommodation" —
     * some categorised, some carrying no category at all, all of them somebody
     * else's home.
     *
     * Both spellings, because the data contains both (29 OSM names alone, and
     * "Toyota accomodation" in Overture) and a driver is not served by our
     * being right about orthography.
     *
     * This stays SHORT on purpose. The word "accommodation" does not appear in
     * any business a driver looks for, which is what makes matching on it safe
     * where matching on "house", "villa" or "building" would not be: "Turkish
     * Grill House" is a restaurant and nothing in the data distinguishes it
     * from "ralph house". Those remain, and the fix for them is upstream — 319
     * OSM POIs are named towers and villas that reached the map only because
     * `fetch_qatar_pbf.py` promotes every named `building=*` way to a POI
     * point.
     */
    private val POI_DENY_NAME = listOf(
        // 84 features across both sources, every one of them worker housing:
        // "Barwa Al Baraha Workers Accommodation", "mowasalat accommodation",
        // "Toyota accomodation", "QP Junior Bachelor Accomodation".
        "accommodation", "accomodation",
        // 25 features, all of them buildings: "Barwa Bldg 42", "A2 Bldg,
        // Barwa City", "Bldg. 12 Flat, Najma", "QP MIC Bldg", "Sks 108 Bldg.".
        // Counted before adding it — no restaurant, shop or clinic in Qatar
        // has "bldg" in its name, which is the test every term here has to
        // pass.
        "bldg",
        "labour camp", "labor camp", "staff housing",
    )

    // -----------------------------------------------------------------------
    // How many POIs a driver is shown
    // -----------------------------------------------------------------------
    //
    // Ranking (above) decided WHICH labels win. It did not decide HOW MANY, and
    // nothing else did either: the only thing limiting the count was MapLibre's
    // collision detection, which draws every label that physically fits.
    //
    // Measured, not estimated. Production tile `15/21074/14005` and its three
    // neighbours decoded, a dense residential point in Al Mansoura put at the
    // centre of a 480 x 1040 dp screen, and the
    // real compiled style — filter and `symbol-sort-key` taken from this file
    // and evaluated by MapLibre's own style-spec — run through the same greedy
    // placement MapLibre uses:
    //
    // ```text
    //   125 POIs within 400 m of the driver
    //   z16    124 on screen ->  66 labels drawn
    //   z16.5   64 on screen ->  38 labels drawn   <- MapCamera.NAV_ZOOM
    //   z17     17 on screen ->  15 labels drawn
    // ```
    //
    // Sixty-six names on one screenful at the zoom the app actually navigates
    // at. The survivors were London Beauty Saloon, Goan Art Tailors, hand made
    // aquarium, Al Rayes Laundry (80 - branch) Zezenya 3.
    //
    // Three things were wrong and each needed a different fix.
    //
    // **The sort key was a three-way tie.** `["match", category, ESSENTIAL, 1,
    // USEFUL, 2, 3]` gives every feature in a tier the SAME key, and MapLibre
    // breaks ties by the order features happen to sit in the tile. So within a
    // tier the winner was still draw-order luck — and worse, it was luck that
    // changes as the camera pans, because the per-tile order of a bucket is not
    // stable across the tiles that come and go around the vehicle. Labels
    // popping in and out while moving is worse than a few too many of them.
    // `quality_score` (0..1, on every POI in the shipped tiles since the
    // canonical bake) is now the minor key, so the order is total, decided in
    // the data, and identical on every frame. Being a MINOR key matters: it
    // orders within a tier and can never promote a laundry over a pharmacy.
    //
    // **The tiers were too coarse to cap anything.** Tier 2 held 44 of the 124
    // on-screen candidates, because "restaurant", "cafe" and "hotel" sat in the
    // same bucket as "hospital" and "shopping_center". Splitting it into
    // [POI_MAJOR] — things you can see from the road and plan a trip around —
    // and [POI_EVERYDAY] — shopfronts — gives a rank with enough resolution to
    // gate on.
    //
    // **Nothing was gated by zoom.** The cap is now a rank ceiling that opens
    // with the camera: ranks 1-2 earn a label from z16, ranks 3-4 from z17.
    // See [poiLabelFilter] for why that is one clause in one layer's filter
    // rather than two layers with two `minzoom`s.
    //
    // The result, same measurement, same screen:
    //
    // ```text
    //   z16    66 -> 23 labels      z16.5  38 -> 13      z17  15 -> 15
    // ```
    //
    // and the z16 survivors are Fanar Souq Mosque, Somerset Al Mansoura,
    // Royal Mansoura Residence Hotel, Family Food Centre, Al Munsura park,
    // Valligio Mall Qatar — places a driver could be going to. z17 keeps its
    // count and changes its contents: Green Tea Garden Restaurant and Shater
    // Abbas Restaurant arrive, Insight Computers and Snow White Qatar drop
    // behind them.
    //
    // **`text-padding` was measured and rejected.** Raising it from 2 to 6 and
    // to 10 moved the z16 count by one or two labels, because at this density
    // the labels are not merely touching, there are simply too many candidates.
    // A knob that does not move the number does not earn a line in the style.

    /**
     * What a driver is most likely to be looking for, best first.
     *
     * Read as the integer part of a `symbol-sort-key`: MapLibre places symbols
     * in ascending order and drops the ones that collide, so a lower number
     * wins a contested slot. Rank 1 is what you look for on a running tank or
     * in an emergency; rank 2 is a landmark you can see from the road and might
     * be driving to; rank 3 is a shopfront; rank 4 is everything else, which
     * still renders whenever there is room.
     *
     * Both vocabularies, because both are in the tiles: Overture's `category`
     * and OSM's `poi_class` name the same things differently (`gas_station`
     * against `fuel`, `bank_credit_union` against `bank`).
     */
    private val POI_ESSENTIAL = listOf(
        // Overture
        "gas_station", "pharmacy", "hospital", "medical_center", "atms",
        "police_department", "car_wash", "automotive_repair",
        "automotive_services_and_repair", "car_rental_agency", "mosque",
        // OSM
        "fuel", "clinic", "atm", "police", "car_repair", "car_rental",
        "parking", "place_of_worship", "charging_station", "fire_station",
    )

    /**
     * Big enough to see from the road, and a trip in its own right.
     *
     * The half of the old `POI_USEFUL` that survives at driving zoom. The test
     * for membership is whether a driver would set this as a destination and
     * recognise it through a windscreen at 60 km/h: a mall, a hotel, a
     * supermarket, a school, a park. Nine hotels and four grocery stores inside
     * 400 m of Al Mansoura is dense, but each of them is somewhere a car stops.
     */
    private val POI_MAJOR = listOf(
        // Overture
        "shopping_center", "department_store", "hotel", "resort",
        "grocery_store", "bank_credit_union", "currency_exchange", "school",
        "college_university", "airport", "park", "beach", "stadium_arena",
        "embassy",
        // OSM
        "supermarket", "mall", "bank", "bureau_de_change", "government",
        "diplomatic", "attraction", "educational_institution", "post_office",
        "guest_house", "hostel", "kindergarten",
    )

    /**
     * Shopfronts: real destinations, at a scale you only read when slowing.
     *
     * The other half of the old `POI_USEFUL`. These are not junk — this is
     * where "Green Tea Garden Restaurant is right in front of my destination
     * and not on the map" lives, and that complaint was correct. They are held
     * back to z17 rather than removed, and `MapCamera.ZOOM_BANDS` puts the
     * camera at 17.0 below 25 km/h — which is the arrival, the exact moment a
     * shopfront name becomes the thing the driver is looking for.
     */
    private val POI_EVERYDAY = listOf(
        // Overture
        "restaurant", "cafe", "coffee_shop", "fast_food_restaurant", "bakery",
        "convenience_store", "playground",
        // OSM
        "fast_food", "convenience", "dentist", "travel_agency", "car_parts",
        "tyres",
    )

    /**
     * The rank for a category neither list names, taken from its family.
     *
     * The canonical POI pipeline puts every record in one of ~20 semantic
     * families and ships it as `poi_family`, which closes the hole a
     * hand-written category list cannot: `asian_restaurant`,
     * `filipino_restaurant`, `turkish_restaurant` and `sri_lankan_restaurant`
     * are all FOOD and all ranked last today purely because nobody thought to
     * type them out. 781 Overture categories and 312 OSM classes cannot be
     * enumerated by hand, and the families can.
     *
     * Listed families become rank 3, the shopfront rank, and reach the map at
     * z17. Everything else — SERVICES, RESIDENTIAL, OFFICE, INDUSTRY, UNKNOWN —
     * falls to rank 4 and reaches it at z17 too, behind them in the queue.
     * A missing `poi_family` also lands on rank 4, so a tile baked before the
     * canonical pipeline still renders; it simply ranks everything it cannot
     * describe last, which is the same rule the category lists follow.
     */
    private val POI_FAMILY_EVERYDAY = listOf(
        "FOOD", "SHOPPING", "HEALTHCARE", "SPORT", "EDUCATION", "LODGING",
        "ATTRACTION", "FINANCE", "TRANSPORT", "GOVERNMENT", "RELIGIOUS",
    )

    /** A JSON array literal of quoted strings, for embedding in an expression. */
    private fun quoted(values: List<String>): String =
        values.joinToString(", ") { "\"$it\"" }

    /**
     * The rank 1-4 of a feature: which zoom it earns a label at, and in what
     * order it competes for space there.
     *
     * One `coalesce` over both vocabularies rather than two `match` clauses,
     * because a rank is a single number and the two fields name the same thing.
     * The canonical bake emits `category` and `poi_class` with the same value;
     * older records carry one or the other.
     */
    private val poiRank: String = """
        ["match", ["coalesce", ["get", "category"], ["get", "poi_class"], ""],
          [${quoted(POI_ESSENTIAL)}], 1,
          [${quoted(POI_MAJOR)}], 2,
          [${quoted(POI_EVERYDAY)}], 3,
          ["match", ["coalesce", ["get", "poi_family"], ""],
            [${quoted(POI_FAMILY_EVERYDAY)}], 3,
            4]
        ]
    """.trimIndent().replace("\n", " ").replace(Regex(" +"), " ")

    /**
     * The `symbol-sort-key`: rank first, then data quality inside the rank.
     *
     * `rank + (1 - quality_score)` lands in `[1, 5)` and never lets the minor
     * key cross a rank boundary, so a 0.99-quality laundry still loses to a
     * 0.42-quality pharmacy. Within a rank it is a total order fixed by the
     * data, which is the whole point: MapLibre's tie-break is the order
     * features sit in a tile, and that order changes as tiles load and unload
     * around a moving vehicle. Ties are what make labels flicker.
     *
     * `has`-guarded rather than defaulted through `to-number`, for the reason
     * the confidence clause is: `["to-number", null]` is 0, which would rank
     * every record from a pre-canonical bake WORST inside its rank rather than
     * in the middle. 0.5 is the neutral score the ingestion pipeline itself
     * uses for a missing signal.
     */
    private val poiSortKey: String = """
        ["+", $poiRank,
          ["-", 1, ["case", ["has", "quality_score"],
                            ["to-number", ["get", "quality_score"]], 0.5]]]
    """.trimIndent().replace("\n", " ").replace(Regex(" +"), " ")

    /**
     * The filter both POI layers share.
     *
     * The confidence clause is `has`-guarded rather than `coalesce`-defaulted
     * because the absence of the field is meaningful: OSM features and learned
     * places carry no `confidence` and must not be demoted for lacking one —
     * the same rule `GeocodeIndex` applies when it treats a missing confidence
     * as 1.0. Only Overture ships the field, and only Overture's weakly
     * conflated records are being excluded here — 4,105 of Qatar's 23,199.
     *
     * The floor is 0.4 and was 0.5, which cut "Shater Abbas Restaurant" at
     * 0.49. That is a real chain with branches across Doha, and losing it is a
     * worse error than keeping a doubtful office, so the floor moved to where
     * it stops cutting places people actually name.
     *
     * Be clear about what this number is NOT. Overture's `confidence` measures
     * CONFLATION — how sure they are the record describes one real place —
     * not freshness. "Snow White Qatar" is a salon that has closed and it
     * carries 0.76. Nothing in this data says a business has shut, so no
     * threshold here can remove one, and raising the floor to chase that would
     * only cost more Shater Abbases. Closed-business detection needs either
     * fresher upstream data or the learned-POI pipeline, which has consumed
     * zero trips.
     *
     * Two `match` clauses and not one, because the tiles carry two independent
     * vocabularies: `category` from Overture and `poi_class` from OSM. A
     * feature has one or the other, never both, and `match` falls through to
     * its default — pass — when the field it reads is absent, so each clause
     * is a no-op on the side it does not describe.
     *
     * `to-number` around the `get` is not decoration. `["get", k]` is typed
     * `value`, and `>=` wants two operands of one known type, so the bare form
     * is a style-parse error — which in this app means a layer that silently
     * draws nothing, the exact failure mode this file's test KDoc was written
     * about. The `has` clause already guarantees the field is present by the
     * time it is read.
     */
    /**
     * Both names a feature can carry, lowercased, for substring matching.
     *
     * `name` and `name:en` together because either can be the one that says
     * "accommodation" — the label shown depends on the driver's language, and
     * the feature should be refused on the strength of either.
     */
    private const val POI_NAME_HAYSTACK =
        """["downcase", ["concat", ["coalesce", ["get", "name"], ""], " ", """ +
            """["coalesce", ["get", "name:en"], ""]]]"""

    private fun nameDenyClauses(): String =
        POI_DENY_NAME.joinToString(", ") {
            """["==", ["index-of", "$it", $POI_NAME_HAYSTACK], -1]"""
        }

    private val poiFilter: String = """
        ["all",
          ["==", ["get", "kind"], "poi"],
          ["match", ["get", "category"], [${quoted(POI_DENY_CATEGORY)}], false, true],
          ["match", ["get", "poi_class"], [${quoted(POI_DENY_CLASS)}], false, true],
          ${nameDenyClauses()},
          ["any", ["!", ["has", "confidence"]], [">=", ["to-number", ["get", "confidence"]], 0.4]]
        ]
    """.trimIndent().replace("\n", " ").replace(Regex(" +"), " ")

    /**
     * [poiFilter], plus the rank a label has to reach at the current zoom.
     *
     * This is the density cap, and it is one clause: ranks 1-2 are labelled
     * from z16, everything else waits for z17.
     *
     * **A zoom clause inside a `filter` is not the same shape as a zoom clause
     * inside a `layout` property.** MapLibre evaluates a layer filter once per
     * tile, when the tile's symbol layout is built, against that tile's
     * `overscaledZ` — so with this bake stopping at z15, the SAME z15 data is
     * laid out three times, as a z16 tile, a z17 tile and a z18 tile, each with
     * the filter re-run at its own zoom. That is the mechanism Mapbox Streets
     * uses for `filterrank`, and it is why this works over an overzoomed bake
     * at all.
     *
     * Deliberately NOT two layers with two `minzoom`s, which was the first
     * design. MapLibre decides collisions BETWEEN symbol layers by layer order
     * and not by sort key, and this file's own KDoc on the route labels records
     * an on-device observation of that order which does not agree with the
     * renderer source. One layer sidesteps the question entirely: a single
     * [poiSortKey] then ranks every candidate against every other candidate,
     * which is what "deterministic" has to mean.
     *
     * `poi-dots` keeps the unranked filter. A dot says something is there from
     * z14; the rank only schedules when it is worth the driver's reading time
     * to say WHAT.
     *
     * ## The one number to re-measure when the bake changes — measured
     *
     * The ceiling below z17 is the knob, and it interacts with a change that
     * is NOT in this file: V7 demotes Overture Places to search-only
     * (`vector_ingestion/poi/visibility.py`), so the 125 POIs within 400 m of
     * the driver in Al Mansoura become 36 OSM-primary, and 17 after the
     * quality rules. The two changes multiply.
     *
     * A ceiling of `2` was tuned against tiles that still carried Overture.
     * Re-measured against the same four production tiles with the split's
     * visibility policy applied, through MapLibre's own expression engine and
     * the same greedy placement (`scratchpad/density/sweep.mjs`):
     *
     * ```
     *   ceiling          z16   z16.5 (NAV_ZOOM)   z17
     *   ["step",…,2,…]     6          2            5     <- empty, not bounded
     *   ["step",…,3,…]    18          9            5     <- shipped
     *   ["step",…,4,…]    18          9            5     <- rank 4 adds nothing
     * ```
     *
     * So `3`: shopfronts are in at z16, and only the unclassifiable tail is
     * held back. Rank 4 changes nothing below z17 once Overture is gone —
     * almost nothing that survives the split ranks 4 — so the step to 4 at z17
     * is now a statement of intent rather than a live constraint, and is kept
     * for the day the bake's contents change again.
     *
     * Integer stops only — a filter is evaluated at the tile's `overscaledZ`,
     * so a stop at 16.5 would never fire.
     *
     * Re-measure this number, not just the tests, whenever the bake's POI
     * contents change. The tests pin the SHAPE of the rule; only a measurement
     * against real tiles can tell you the map is neither crowded nor empty.
     */
    private val poiLabelFilter: String =
        """["all", $poiFilter, ["<=", $poiRank, ["step", ["zoom"], 3, 17, 4]]]"""

    /**
     * The 3D road: the layers that draw a carriageway instead of a line.
     *
     * ## What makes this three-dimensional
     *
     * Not elevation. There is no DEM, no mesh and no Z anywhere in this stack,
     * and none is needed: decomposing the reference material frame by frame
     * (V8 §5.1) the depth comes from four things, all of which are style —
     *
     *  1. a low, close, steeply pitched camera ([MapCamera.TILT_DEG]);
     *  2. the carriageway drawn at its TRUE metric width, which turns a line
     *     into a surface;
     *  3. a constant-height skirt under it, which gives the surface mass and is
     *     what stops it reading as a ribbon floating on the ground;
     *  4. markings drawn as geometry ON that surface, which is what sells it as
     *     pavement rather than as a wide stroke.
     *
     * The fifth ingredient — real superelevation and terrain — is the only one
     * that needs data Vector does not have, and the reference's own daylight
     * frames show a flat intersection with no measurable banking. Qatar's
     * relief is negligible at driving scale.
     *
     * ## Why no re-bake was needed
     *
     * `lanes`, `bridge` and `tunnel` are whitelisted at ingestion
     * (`osm_to_geojson.py:99-101`), the MVT encoder takes `properties`
     * wholesale, and the bake reaches z15. Decoding 49 live z15 tiles over
     * central Doha — 8,624 drivable ways — finds `lanes` on 4,855 (56%),
     * `oneway` on 5,082 (59%, always the value "yes"), `turn:lanes` on 901
     * (10%), `junction` on 126 (123 `roundabout`, 3 `circular`), `tunnel` on
     * 29 and `bridge` on 7. It is all already on the phone, and four of those
     * six now carry a marking that would otherwise have been a guess.
     *
     * The lane histogram is worth stating because it settles [MAX_MARKED_LANES]
     * with a measurement instead of an estimate: 1 lane on 1,315 ways, 2 on
     * 2,180, 3 on 865, 4 on 434, 5 on 59, 6 on **2**, and nothing above 6 at
     * all. The seven-lane case that limit is written to fail gracefully on does
     * not occur in central Doha.
     *
     * **`layer` is NOT**, which V8 §5.5 assumed when it proposed sorting the
     * decks by it. It is absent from the ingestion whitelist and from all 25
     * production tiles around Doha that were checked. Grade separation below is
     * therefore keyed on `bridge` and `tunnel`, which ARE there, and which
     * answer the only question that matters at pitch: is this deck over the
     * other one, or under it.
     *
     * ## Order
     *
     * Ground skirt, ground deck, ground markings, then the whole thing again
     * for bridges, so a flyover and everything painted on it sits above the
     * road it crosses. Tunnels stay in the ground group at reduced opacity and
     * with no skirt — a tunnel has no deck edge to catch the light.
     *
     * Bridges get no lane dividers. That is a decision and not an oversight:
     * it would double this layer count for the 685 arterial ways that are
     * bridges, most of which are short enough that the edges carry the shape on
     * their own.
     *
     * ## Curves: what was suspected, what was measured, what it turned out to be
     *
     * Every marking here is placed with `line-offset`, MapLibre computes that
     * per vertex, and an offset polyline FOLDS where the curve it is taken
     * around is tighter than the offset — so the standing worry was that
     * roundabouts, slip roads and junction curves were quietly full of loops
     * and spikes, and that the whole scheme needed rebuilding on something
     * else. It is worth recording that this was measured before anything was
     * rebuilt, because the measurement said not to.
     *
     * Over the same 49 tiles, 59,475 interior vertices of drivable ways, with
     * each way's own kerb offset (half its carriageway, the LARGEST offset in
     * this file) and the fold criterion `d x tan(phi / 2) > segment length`:
     *
     * | | p50 | p90 | p99 | folds |
     * |---|---|---|---|---|
     * | all drivable | 0.020 | 0.149 | 0.406 | 48 of 59,475 — 0.081% |
     * | `junction=roundabout` | 0.106 | 0.218 | 0.477 | 3 of 2,069 — 0.145% |
     *
     * A ratio of 1.0 is the fold. The 99th percentile of the tightest geometry
     * in the network is less than half of it, and the reason is a fact about
     * roads rather than about MapLibre: a smooth curve only folds when the
     * offset exceeds twice its radius, and a road does not curve tighter than
     * its own half-width — median local radius is 95.6 m across the network
     * and still 12.1 m on a roundabout, against a half-width of 3.5 m on the
     * commonest carriageway in the country.
     *
     * The 0.08% that DO fold are not curves. Every one of the worst offenders
     * has an adjacent segment of 0.27 m, which is exactly one unit of the z15
     * tile grid at Doha (`40_075_016.686 x cos(25.2854) / 2^15 / 4096`) — they
     * are quantisation zigzags in the bake, and the offset amplifies them into
     * a spike because `tan(phi / 2)` is large when two collinear-ish points
     * land on adjacent integers. That is a defect in what the tile carries and
     * cannot be fixed by anything in this file; it belongs to `simplify.py`,
     * which should drop a vertex whose neighbours are within a unit of it.
     * Filed, not worked around here.
     *
     * The one thing the measurement DID change is the ring. A roundabout
     * carries the tightest radii and the three worst folds in the sample, and
     * separately has no data for the only markings it actually needs, so it
     * keeps its surface and its kerbs and loses its lane guess — see the
     * `notCircular` note on the divider set.
     */
    /**
     * Why the lane markings are built but not drawn.
     *
     * ## What was reported
     *
     * A turn lane leaving C Ring Road appeared to begin in the **middle lanes**
     * of the carriageway, and the Rawdat Al Khail interchange showed a lattice
     * of hairlines ruled across it with stubs ending in mid-air. Both are the
     * same defect, and it is not the centreline geometry — it is the markings.
     *
     * ## The mechanism, which this file has already recorded once
     *
     * Every marking is placed with `line-offset`, and MapLibre evaluates that
     * **per feature, with no knowledge of any other feature**. A way runs from
     * one junction node to the next, so its dividers are drawn all the way into
     * the junction at both ends — and the divider layers were composed *after*
     * every carriageway deck. So each approach painted its lane lines straight
     * across the surface of every road it met.
     *
     * This is the identical failure the kerb had, measured at the time over 24
     * real z15 tiles: **81,526 m of kerb painted over another drivable
     * carriageway**, described then as "every crossroads, every untagged loop,
     * every U-turn had a lattice of white hairlines ruled across its middle".
     * The kerb was fixed by drawing it as a casing UNDER the deck. A marking
     * cannot take that fix: under the deck it is invisible, because the deck is
     * opaque and the marking's whole job is to sit on top of it.
     *
     * ## Why the style cannot fix it
     *
     * A divider needs to be above its OWN deck and below every OTHER deck.
     * MapLibre draws one layer at a time across all features, so that ordering
     * is not expressible — and there is no trim: `line-offset` has no notion of
     * stopping short of a way's end, and this renderer has no `line-trim-offset`.
     * Raising the zoom, thinning the line or dropping opacity would only make a
     * wrong line quieter.
     *
     * ## What was done, and what the real fix is
     *
     * The layers are no longer composed. The precedent is this file's own, made
     * for roundabouts on exactly this reasoning: *"A dashed circle offset off
     * the ring's centreline is a guess drawn in the one place a driver most
     * needs it to be right. The kerbs stay; the guess goes."* That sentence is
     * true at every junction, not only at rings.
     *
     * What survives is everything that is geometrically sound: the carriageway
     * decks at their real surveyed width, the kerbs, the skirts and the
     * casings. Those are what make the map read as surveyed rather than drawn.
     *
     * Markings come back when the **bake** supplies geometry to hang them on:
     * the drivable centreline trimmed back from each junction node by the
     * junction's own radius, emitted as its own feature. Then a divider cannot
     * reach another carriageway because its geometry stops before it. That is a
     * tile-side change, not a style-side one, which is why it is named here
     * rather than attempted here.
     *
     * [dividerOffset] and [liveDividerOffset] are kept and still tested: the
     * offset mathematics is correct and is what the trimmed geometry will be
     * drawn with. Nothing calls them today.
     */
    internal const val MARKINGS_NEED_TRIMMED_GEOMETRY = true

    /**
     * The V8 `lanes` layers, or "" when they are off.
     *
     * ## Why this is drawn from a source-layer and not with `line-offset`
     *
     * [MARKINGS_NEED_TRIMMED_GEOMETRY] records exactly why the in-basemap
     * dividers were pulled: `line-offset` is evaluated per feature with no
     * knowledge of any other feature, so an approach's markings were painted
     * straight across every road it met. The V8 bake solves it on the tile
     * side — the drivable centreline is trimmed back from each junction node
     * and OFFSET INTO the geometry, then written as its own `lanes` source
     * layer (`vector_tile_gen/lanes.py`). So here there is deliberately **no**
     * `line-offset`: the offset is already in the coordinates. That is the
     * "tile-side change, not a style-side one" that comment names.
     *
     * ## Placement
     *
     * Ground markings are returned between the ground/ring decks and the bridge
     * group, so they sit ABOVE their own road surface and BELOW a flyover that
     * passes over them. Bridge markings are returned after the bridge deck, so
     * a flyover's paint is above its own deck. Both start at z15, the only zoom
     * the bake writes lanes at, and below every label layer (labels are emitted
     * later in the document). Casings, skirts, tunnels, bridges, 3D buildings
     * and labels are untouched — this only ADDS layers.
     *
     * ## Feature shape
     *
     * `lanes.py` tags each run with `cls` (`lane` | `centre` | `solid`) and,
     * on a bridge, `brg`. The three classes map to the same dash conventions
     * the in-basemap dividers used: [LANE_DASH], [CENTRELINE_DASH], and solid.
     * The colour and width are the shipped [laneMarking]/[MARKING_WIDTH], so a
     * marking looks the same whether it is drawn from a divider or from the
     * `lanes` layer.
     */
    private fun laneMarkings(c: Palette, lanes: Boolean, bridge: Boolean): String {
        if (!lanes) return ""
        val tag = if (bridge) "bridge" else "ground"
        val where = if (bridge) """["has", "brg"]""" else """["!", ["has", "brg"]]"""
        val classes = listOf("lane" to LANE_DASH, "centre" to CENTRELINE_DASH, "solid" to null)
        return classes.joinToString("") { (cls, dash) ->
            val dashPaint = if (dash != null) ""","line-dasharray": $dash""" else ""
            """
    { "id": "lanes-$tag-$cls", "type": "line", "source": "vector", "source-layer": "lanes",
      "filter": ["all", $where, ["==", ["get", "cls"], "$cls"]],
      "minzoom": 15,
      "layout": { "line-cap": "butt", "line-join": "round" },
      "paint": { "line-color": "${c.laneMarking}", "line-width": $MARKING_WIDTH$dashPaint } },"""
        }
    }

    private fun carriagewayLayers(c: Palette, lanes: Boolean = false): String {
        val notBridge = """["!", ["has", "bridge"]]"""
        val isBridge = """["has", "bridge"]"""
        val notTunnel = """["!", ["has", "tunnel"]]"""
        // A ring, and the only junction in the network whose SHAPE is in the
        // tile. `junction` is on 126 drivable ways in the sample, 123 of them
        // `roundabout` and 3 `circular`; both circulate, so both are rings.
        val isCircular = """["all", ["has", "junction"],
                 ["any", ["==", ["get", "junction"], "roundabout"],
                         ["==", ["get", "junction"], "circular"]]]"""
        val notCircular = """["!", $isCircular]"""
        // `has` first, because the bake emits `oneway` only when it is "yes"
        // (5,082 ways, no other value) and a missing tag is a MISSING TAG —
        // the doctrine [LANES] states. Testing the value as well costs nothing
        // and survives a bake that later starts emitting "no".
        val isOneway = """["all", ["has", "oneway"], ["==", ["get", "oneway"], "yes"]]"""
        val twoWay = """["!", $isOneway]"""
        // A tunnel is drawn, dimmed, under the surface it runs beneath — the
        // one case where the deck gives up its contrast against the ground
        // instead of holding it. It carries no markings and no skirt either
        // (both filter on `notTunnel`), so what is left is a ghost of a road,
        // which is the right amount of a road you cannot currently see.
        val tunnelDim = """["case", ["has", "tunnel"], 0.45, 1]"""
        // Within the ground group, tunnels sort under everything else.
        val groundSort = """["case", ["has", "tunnel"], 0, 1]"""

        // Caps. ROUND on the ground and ring groups: two ways meeting at an
        // angle with square ends leave a wedge of bare ground on the outside
        // of the join, which read as a notch at every merge and diverge on the
        // S24 Ultra (V8 native acceptance §9.4, S09/S10/S12). A round end fills
        // it, and because every casing is drawn before every deck, the rim of
        // a round-capped casing is only ever visible where no carriageway
        // covers it. BUTT on the bridge group: a bridge ends where its
        // approach begins, on the approach's surface, and a round-capped
        // bridge casing would rule a white arc across that surface.
        fun deck(id: String, filter: String, sort: String?, opacity: String, cap: String) = """
    { "id": "$id", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": $filter, "minzoom": $CARRIAGEWAY_MINZOOM,
      "layout": { "line-cap": "$cap", "line-join": "round"${
            if (sort != null) ""","line-sort-key": $sort""" else ""
        } },
      "paint": { "line-color": "${c.carriageway}", "line-opacity": $opacity,
                 "line-width": ${metricWidth(1.0)} } },"""

        fun skirt(id: String, filter: String, curb: Double, cap: String) = """
    { "id": "$id", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": $filter, "minzoom": $CARRIAGEWAY_MINZOOM,
      "layout": { "line-cap": "$cap", "line-join": "round" },
      "paint": { "line-color": "${c.carriagewaySkirt}",
                 "line-translate": [0, $SKIRT_LIFT_PX],
                 "line-translate-anchor": "viewport",
                 "line-width": ${metricWidth(1.0, curb * 2)} } },"""

        // The kerb, as a CASING rather than as two offset lines.
        //
        // ## What was wrong with two offset lines
        //
        // `line-offset` is applied per feature with no knowledge of any other
        // feature, so an approach road's kerb was painted straight across the
        // surface of everything it met. Measured over 24 real z15 tiles by
        // decoding them (`JUNCTION-AND-CURVE-DATA.md`): **81,526 m of kerb
        // painted over another drivable carriageway**, 4.6% of all kerb length
        // — and because the kerb layers sat AFTER the decks in this document,
        // the only fix in the style was the ring group's position, which
        // covers `junction=roundabout` ways (73 of 8,099) and nothing else.
        // Every crossroads, every untagged loop, every U-turn had a lattice of
        // white hairlines ruled across its middle.
        //
        // A casing is drawn UNDER the deck instead of beside it, one layer
        // where there were two, and its width is the deck plus a marking's
        // width on each side — so exactly as much white shows, as a rim, and
        // the fill covers the rest. With every casing drawn before every deck
        // (see the layer order below), a kerb can only ever be visible where no
        // carriageway covers it, which is the invariant the old order broke.
        //
        // Zero cost: no re-bake, no new property, no tile byte. It also makes
        // the ring group's four layers and the tunnel's kerb exception
        // redundant, so the document gets SHORTER.
        fun casing(id: String, filter: String, opacity: String, cap: String) = """
    { "id": "$id", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": $filter, "minzoom": $CARRIAGEWAY_MINZOOM,
      "layout": { "line-cap": "$cap", "line-join": "round" },
      "paint": { "line-color": "${c.laneMarking}", "line-opacity": $opacity,
                 "line-width": ${casingWidth()} } },"""

        val ground = """["all", $DRIVABLE, $notBridge, $notCircular]"""
        val bridge = """["all", $DRIVABLE, $isBridge, $notCircular]"""
        // The ring's DECK is drawn after the ground group's, which is a
        // position rather than a colour and is still the reason a roundabout
        // reads as one surface: the circulating carriageway covers the
        // approaches' fill where they meet it.
        //
        // It used to be asked to do more than that — "painting the ring's own
        // surface on top of them ends each approach's kerb where the ring
        // begins" — and that was true and far too narrow. It fixed the kerbs at
        // a `junction=roundabout` way (73 of 8,099 in the sample) and left
        // every crossroads, every untagged loop and every U-turn ruled through
        // with white lines. The casing order is what fixes those; this ordering
        // is now about the fill alone.
        //
        // Before the bridges, because a flyover over a roundabout is still over
        // it.
        // No `notTunnel` here, and that is a correction rather than an
        // omission: the first version of this group carried one, and since
        // `ground` excludes rings and this excluded tunnels, a ring in a
        // tunnel matched no group at all and was not drawn. It is a rare
        // object and a road vanishing is never a rare enough bug. Tunnels are
        // handled exactly as the ground group handles them — dimmed on the
        // deck, and no skirt or kerb, because a tunnel has no deck edge to
        // catch the light.
        val circular = """["all", $DRIVABLE, $isCircular]"""
        val circularLit = """["all", $circular, $notTunnel]"""

        // One layer per (lane count, divider) pair — see [dividerOffset] for
        // why the lane count is baked in rather than read from the feature.
        //
        // The filter reads `lanes` directly rather than going through [LANES],
        // and that is the honest half of this. The width fallback in [LANES]
        // has to guess, because a road with no `lanes` tag still has to be
        // drawn SOME width and a class-median guess is the least wrong one
        // available. A divider is a different kind of statement: it says
        // "there are exactly this many lanes here", and nothing but the tag
        // supports that. So the surface and its edges are drawn for every road
        // and the markings only where the data is real — 92% of the arterial
        // network, and visibly not on the residential street where Vector is
        // guessing.
        fun divider(id: String, filter: String, dash: String, offset: String) = """
    { "id": "$id", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": $filter,
      "minzoom": 15,
      "layout": { "line-cap": "butt", "line-join": "round" },
      "paint": { "line-color": "${c.laneMarking}", "line-width": $MARKING_WIDTH,
                 "line-dasharray": $dash,
                 "line-offset": $offset } },"""

        // The dashed markings: everywhere the lane count is tagged, the road is
        // not a ring, and `turn:lanes` has not said the lanes are assigned.
        //
        // `notCircular` is a change and it is the one this pass has geometry
        // for. A divider is placed by offsetting the centreline, and offsetting
        // a RING is the only place in the network where that offset approaches
        // the radius it is being taken around: measured over 49 z15 tiles, the
        // median local curve radius on a `junction=roundabout` way is 12.1 m
        // against 95.6 m for the network as a whole, and the three worst
        // offset-folds in the whole sample are on roundabouts. The second
        // reason is the honest one: a roundabout's lanes are concentric and
        // which lane leads to which exit is the entire content of its
        // markings, and Vector has no data for that at all. A dashed circle
        // offset off the ring's centreline is a guess drawn in the one place a
        // driver most needs it to be right. The kerbs stay; the guess goes.
        val dividers = (2..MAX_MARKED_LANES).joinToString("") { lanes ->
            (0 until lanes - 1).joinToString("") { i ->
                val base = """["all", $DRIVABLE, $notBridge, $notTunnel, $notCircular,
                 ["!", ["has", "turn:lanes"]],
                 ["has", "lanes"], ["==", ["to-number", ["get", "lanes"]], $lanes]]"""
                val offset = dividerOffset(lanes, i)
                // On an even lane count the middle divider sits at offset zero,
                // and on a two-way road that is the line between opposing
                // streams rather than between lanes. See [CENTRELINE_DASH].
                if (lanes % 2 == 0 && i == lanes / 2 - 1) {
                    divider("lane-divider-$lanes-$i", """["all", $base, $isOneway]""",
                            LANE_DASH, offset) +
                        divider("centreline-$lanes", """["all", $base, $twoWay]""",
                                CENTRELINE_DASH, offset)
                } else {
                    divider("lane-divider-$lanes-$i", base, LANE_DASH, offset)
                }
            }
        }

        // The solid markings, on the approach to a junction.
        //
        // `turn:lanes` is on 1,095 of the 8,624 drivable ways in the sample —
        // 10.4%, and 98% of those are one-way carriageways, which is exactly
        // the population the request calls "highways and intersections". The
        // tag means the lanes on this way are individually assigned to
        // movements, and a road that assigns its lanes paints the lines
        // between them SOLID, because changing lanes across an assignment is
        // what the marking exists to forbid. That is a rule Vector can state,
        // because the tag is the evidence for it — unlike the blanket solid
        // line [dividerOffset] refuses to draw.
        //
        // Five layers and not fifteen: solid means no `line-dasharray`, which
        // is the half of MapLibre's dash/data-driven-offset bug that does the
        // damage, so the lane count can come off the feature. See
        // [liveDividerOffset]. A per-lane-count fan-out here would have cost
        // fifteen layers at every junction approach in Qatar, which is the
        // kind of cost this style cannot absorb at 60 Hz.
        val approachDividers = (0 until MAX_MARKED_LANES - 1).joinToString("") { i ->
            """
    { "id": "lane-divider-approach-$i", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", $DRIVABLE, $notBridge, $notTunnel, $notCircular,
                 ["has", "lanes"], ["has", "turn:lanes"],
                 [">=", ["to-number", ["get", "lanes"]], ${i + 2}],
                 ["<=", ["to-number", ["get", "lanes"]], $MAX_MARKED_LANES]],
      "minzoom": 15,
      "layout": { "line-cap": "butt", "line-join": "round" },
      "paint": { "line-color": "${c.laneMarking}", "line-width": $MARKING_WIDTH,
                 "line-offset": ${liveDividerOffset(i)} } },"""
        }

        // A turn pocket gets a deck and nothing that marks an edge: no skirt,
        // no kerb casing. See [TURN_POCKET]. Its deck is in the ground (or
        // bridge) deck layer like any road, so it fuses with the carriageway
        // it runs beside instead of reading as a road of its own.
        return skirt("carriageway-skirt", """["all", $ground, $notTunnel, $NOT_TURN_POCKET]""", CURB_M, "round") +
            skirt("carriageway-circular-skirt", circularLit, CURB_M, "round") +
            casing("carriageway-casing", """["all", $ground, $notTunnel, $NOT_TURN_POCKET]""", tunnelDim, "round") +
            casing("carriageway-circular-casing", circularLit, tunnelDim, "round") +
            deck("carriageway", ground, groundSort, tunnelDim, "round") +
            deck("carriageway-circular", circular, null, tunnelDim, "round") +
            // The in-basemap dividers are NOT composed (see
            // [MARKINGS_NEED_TRIMMED_GEOMETRY]). V8 draws the markings from the
            // `lanes` source layer instead, whose geometry is already trimmed
            // and offset. OFF unless the acceptance build opts in. Ground
            // markings go here — above the ground/ring decks, below the bridge
            // group — so a flyover still covers the road it passes over.
            laneMarkings(c, lanes, bridge = false) +
            skirt("carriageway-bridge-skirt", """["all", $bridge, $NOT_TURN_POCKET]""", DECK_CURB_M, "butt") +
            casing("carriageway-bridge-casing", """["all", $bridge, $NOT_TURN_POCKET]""", "1", "butt") +
            deck("carriageway-bridge", bridge, null, "1", "butt") +
            // Bridge markings sit above the bridge deck, so a flyover's paint is
            // on its own surface.
            laneMarkings(c, lanes, bridge = true)
    }

    fun json(
        apiBase: String,
        tileEpoch: Long,
        minZoom: Int = 11,
        maxZoom: Int = 13,
        theme: MapTheme = MapTheme.DARK,
        /**
         * The driver's language, for label text. Null or anything other than
         * "en" keeps the local names, which are what is painted on the signs.
         */
        lang: String? = null,
        /**
         * Whether to emit the extruded building layer (V7 3D).
         *
         * This is the 2D/3D choice the driver already has — `MapPerspective`,
         * which is why there is no second "3D mode" control. [MapPerspective.TILTED]
         * already means "pitch the map to 60 degrees"; a pitched map with no
         * volumes in it is the half-built state this parameter closes.
         *
         * It is a STYLE parameter rather than a runtime layer toggle on
         * purpose: the extrusion is 975 extra features per tile at z15, and
         * omitting the layer means MapLibre never builds the geometry at all
         * — an off state that costs nothing rather than one that renders and
         * then hides.
         */
        extruded: Boolean = false,
        /**
         * The release these tiles come from, or "" to keep using [tileEpoch].
         *
         * V7.7. The `?v=` parameter's only job is to give each release its own
         * URL space in the client's cache — AC-19 measured on production that
         * the server strips the query before resolving a path, so this routes
         * nothing and must never be expected to. What it buys is that two
         * releases cannot collide in the cache, and that a tile URL in a proxy
         * log, a HAR or a bug report names its own release without a lookup
         * table.
         *
         * Last in the parameter list, and defaulted, so that every existing
         * positional caller means exactly what it meant before.
         */
        release: String = "",
        /**
         * V8 lane markings — whether to emit the optional `lanes` layers.
         *
         * OFF by default, and the default is [BuildConfig.LANES], which is
         * `false` in every build except one made with `-PvectorLanes=true`. So:
         *
         *  * every existing caller — MainActivity included — passes nothing and
         *    gets the byte-identical basemap-only style it got before, and
         *  * turning lanes on is a BUILD decision (`-PvectorLanes=true`), not a
         *    settings toggle and not a MainActivity edit. MainActivity's call
         *    is unchanged; the flag reaches it only through this default.
         *
         * When on, the markings are read from `source-layer: "lanes"` on the
         * SAME `vector` source the basemap comes from — the V8 candidate
         * release's optional companion layer. A client that does not name that
         * source-layer (the shipped default style) never requests it and is
         * unaffected, which is what the bake's B-vs-B2 pixel identity proves.
         *
         * The markings are drawn from GEOMETRY that the bake has already
         * trimmed and offset (no `line-offset` here — see [laneMarkings] and
         * [MARKINGS_NEED_TRIMMED_GEOMETRY]); they start at z15, above the road
         * surface and below the labels, and add nothing to any other layer.
         *
         * This is an acceptance path. It must never be enabled in a shipped
         * build.
         */
        lanes: Boolean = BuildConfig.LANES,
    ): String {
        val c = palette(theme)
        val nameField = nameExpr(lang)
        // One rule, shared with `VectorApi.TileSet.token`: the release when
        // there is one, the epoch otherwise. If the URL and the comparison
        // ever used different rules, the app could decide a release had not
        // changed while writing URLs that said it had.
        val v = if (release.isNotEmpty()) release else tileEpoch.toString()
        val tiles = "$apiBase/tiles/{z}/{x}/{y}.mvt?v=$v"
        val glyphs = "$apiBase/glyphs/{fontstack}/{range}.pbf"
        val name = if (theme == MapTheme.LIGHT) "Vector Light" else "Vector Dark"
        val carriageway = carriagewayLayers(c, lanes)
        // V7 3D. MapLibre fills an extrusion's TOP face and its SIDE WALLS with
        // the same `fill-extrusion-color`, so without lighting the solid reads
        // as a flat polygon and the third dimension is invisible — which is
        // exactly what the first three device runs measured: the extrusion was
        // demonstrably present (a diagnostic build coloured it pure red and
        // 3.64% of the screen turned red) and still looked two-dimensional.
        //
        // The `light` block below is what separates the faces, and the
        // direction is chosen for a screen that is read at 60 degrees of pitch
        // rather than for realism:
        //   * `anchor: viewport` keeps the shading steady as the map rotates,
        //     so a building does not change apparent height when the driver
        //     turns a corner;
        //   * the colour is slightly cool (the palette's own cast) so the lit
        //     faces stay in the dark theme's family rather than reading as a
        //     warm sunlight that the rest of the map does not share;
        //   * intensity is well below 1.0: this is a basemap, and the moment
        //     the buildings become the brightest thing on screen they compete
        //     with the route, which the stage's acceptance forbids.
        //
        // Emitted ONLY in 3D. In 2D there is nothing to light, and a `light`
        // block with no extrusion in the style is a promise the style does not
        // keep.
        val light = if (!extruded) "" else """
  "light": { "anchor": "viewport", "color": "#ffffff", "intensity": 0.55 },
"""
        // V7 3D. Placed here, before `$carriageway` in the returned JSON, so
        // every road, lane marking and bridge deck draws OVER the towers
        // rather than under them — the layering is what keeps 3D buildings
        // from obscuring the route, and it is expressed once, in the order of
        // this string, rather than by a runtime hack.
        //
        // This JSON carries no comments of its own: a `//` inside the style
        // string is not valid JSON, and MapLibre rejects the whole document.
        // Everything explanatory therefore lives here, in Kotlin.
        //
        // `height_m` is the ONLY height that reaches the extrusion. The bake
        // emits a building only when its source states one — 975 of Qatar's
        // 189,871 footprints — so there is no default height, no level-derived
        // guess, and nothing here can extrude a building the source did not
        // measure. See `_building_height_m` in the converter for the
        // measurement behind that rule.
        //
        // `fill-extrusion-base` reads `min_height_m`, falling back to 0 — the
        // ground, which is an honest base rather than an invented one; 7 Qatar
        // buildings state a real `min_height`.

        // V7.6. THE CITY FABRIC, and the layer this whole milestone turns
        // on. It is not new — `buildings` has been in this style since V3 —
        // and until V7.6 it had never drawn a single feature, because the
        // extract discarded 98.6% of Qatar's footprints before a tile was
        // ever built. The style was never the problem, so the style barely
        // changes: what changed is that the layer finally has something to
        // draw.
        //
        // Three properties are load-bearing and none is decorative:
        //
        //   * `minzoom: 14` matches `build_qatar_tiles.visible_at_zoom`
        //     exactly. Below street level 170,216 footprints are a grey smear
        //     over the peninsula, and no driver reads a building there.
        //   * NO height is involved. A fill needs none, which is precisely
        //     why the fabric can cover a country whose heights are 99.5%
        //     unknown without inventing a single one.
        //   * it is emitted BEFORE `$buildings3d` and before `$carriageway`,
        //     so a measured tower's volume draws over its own footprint and
        //     every road draws over both. The fabric can never cover the
        //     street network, and the ordering says so in one place rather
        //     than being asserted at runtime.
        //
        // A measured building is therefore drawn twice — as a footprint here
        // and as a volume above. That is deliberate: at 0.85 opacity the fill
        // grounds the volume rather than letting it float over the map.
        //
        // Unlike `buildings3d` this is NOT gated on `extruded`. The fabric is
        // the 2D map's city too, and a driver who has chosen the flat
        // perspective has not asked for an empty one.
        val buildingsFabric = """
    { "id": "buildings", "type": "fill", "source": "vector", "source-layer": "basemap",
      "filter": ["==", ["get", "kind"], "building"], "minzoom": 14,
      "paint": { "fill-color": "${c.building}",
                 "fill-outline-color": "${c.buildingOutline}" } },
"""

        val buildings3d = if (!extruded) "" else """
    { "id": "buildings-3d", "type": "fill-extrusion", "source": "vector",
      "source-layer": "basemap", "minzoom": ${MapCamera.BUILDINGS_3D_MINZOOM.toInt()},
      "filter": ["all", ["==", ["get", "kind"], "building"],
                 ["has", "height_m"]],
      "paint": {
        "fill-extrusion-color": "${c.building3d}",
        "fill-extrusion-height": ["get", "height_m"],
        "fill-extrusion-base": ["coalesce", ["get", "min_height_m"], 0],
        "fill-extrusion-opacity": $BUILDINGS_3D_OPACITY,
        "fill-extrusion-vertical-gradient": true
      } },
"""
        return """
{
  "version": 8,
  "name": "$name",
  "glyphs": "$glyphs",
$light
  "sources": {
    "vector": {
      "type": "vector",
      "tiles": ["$tiles"],
      "minzoom": $minZoom,
      "maxzoom": $maxZoom,
      "attribution": "© OpenStreetMap contributors (ODbL)"
    },
    "route-alt": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "route": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "puck": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "route-endpoints": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "route-labels": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "traffic": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "walk": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "walk-route": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "route-chevrons": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    },
    "callouts": {
      "type": "geojson",
      "data": { "type": "FeatureCollection", "features": [] }
    }
  },
  "layers": [
    { "id": "bg", "type": "background",
      "paint": { "background-color": ["interpolate", ["linear"], ["zoom"],
                   13, "${c.background}", 16, "${c.backgroundDriving}"] } },

    { "id": "landuse", "type": "fill", "source": "vector", "source-layer": "basemap",
      "filter": ["==", ["get", "kind"], "landuse"],
      "paint": { "fill-color": "${c.landuse}", "fill-opacity": 0.7 } },

    { "id": "park", "type": "fill", "source": "vector", "source-layer": "basemap",
      "filter": ["==", ["get", "kind"], "park"],
      "paint": { "fill-color": "${c.park}", "fill-opacity": 0.9 } },

    { "id": "natural", "type": "fill", "source": "vector", "source-layer": "basemap",
      "filter": ["==", ["get", "kind"], "natural"],
      "paint": { "fill-color": "${c.natural}", "fill-opacity": 0.8 } },

    { "id": "water", "type": "fill", "source": "vector", "source-layer": "basemap",
      "filter": ["==", ["get", "kind"], "water"],
      "paint": { "fill-color": "${c.water}" } },

    { "id": "coastline", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["==", ["get", "kind"], "coastline"],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.coastline}",
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 1.0, 10, 1.6, 18, 4.0] } },

$buildingsFabric

$buildings3d
$carriageway
    { "id": "roads-service-casing", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["service","track","living_street"], true, false]],
      "minzoom": 15,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 15, 2.0, 18, 7.0] } },

    { "id": "roads-minor-casing", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["residential","unclassified"], true, false]],
      "minzoom": 14,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 14, 2.4, 18, 10.6] } },

    { "id": "roads-tertiary-casing", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["tertiary","tertiary_link"], true, false]],
      "minzoom": 13,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 13, 2.0, 18, 13.0] } },

    { "id": "roads-secondary-casing", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["secondary","secondary_link"], true, false]],
      "minzoom": 12,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 12, 2.2, 18, 15.4] } },

    { "id": "roads-links-casing", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["motorway_link","trunk_link","primary_link"], true, false],
                 $NOT_TURN_POCKET],
      "minzoom": 11,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 11, 1.6, 18, 12.0] } },

    { "id": "roads-primary-casing", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["primary","trunk"], true, false]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 1.8, 10, 3.2, 18, 18.0] } },

    { "id": "roads-motorway-casing", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["motorway"], true, false]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 2.6, 10, 4.4, 18, 22.0] } },


    { "id": "roads-service", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["service","track","living_street"], true, false]],
      "minzoom": 15,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadService}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 15, 1.0, 18, 5.0] } },

    { "id": "roads-minor", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["residential","unclassified"], true, false]],
      "minzoom": 14,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadMinor}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 14, 1.2, 18, 8.0] } },

    { "id": "roads-tertiary", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["tertiary","tertiary_link"], true, false]],
      "minzoom": 13,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadTertiary}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 13, 0.9, 18, 10.0] } },

    { "id": "roads-secondary", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["secondary","secondary_link"], true, false]],
      "minzoom": 12,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadSecondary}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 12, 1.0, 18, 12.0] } },

    { "id": "roads-links", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["motorway_link","trunk_link","primary_link"], true, false]],
      "minzoom": 11,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadSecondary}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 11, 0.8, 18, 9.0] } },

    { "id": "roads-primary", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["primary","trunk"], true, false]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadPrimary}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 0.9, 10, 1.8, 18, 14.0] } },

    { "id": "roads-motorway", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"],
                 ["match", ["get", "highway"], ["motorway"], true, false]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadMotorway}", "line-opacity": $CLASS_RAMP_FADE,
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 1.4, 10, 2.6, 18, 17.0] } },

    { "id": "roads-learned", "type": "line", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"], ["==", ["get", "learned"], true]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "$LEARNED", "line-dasharray": [2, 2],
                 "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 12, 1.2, 18, 8.0] } },

    { "id": "route-alt-casing", "type": "line", "source": "route-alt",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-width":
        ${floored(ROUTE_ALT_CASING_M, 6.0)} } },

    { "id": "route-alt", "type": "line", "source": "route-alt",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.routeAlt}", "line-width":
        ${floored(ROUTE_ALT_M, 3.5)} } },

    { "id": "route-casing", "type": "line", "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "$ROUTE_CASING", "line-width":
        ${flooredFrom("width", ROUTE_RIBBON_M, 7.0, plusM = ROUTE_CASING_M - ROUTE_RIBBON_M)},
        "line-offset": ${metricOffset("offset")} } },

    { "id": "route", "type": "line", "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "$ROUTE", "line-width":
        ${flooredFrom("width", ROUTE_RIBBON_M, 4.5)},
        "line-offset": ${metricOffset("offset")} } },

    { "id": "traffic", "type": "line", "source": "traffic",
      "filter": ["!=", ["get", "congestion"], "free"],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": {
        "line-color": ["match", ["get", "congestion"],
          "jammed", "$JAM_JAMMED",
          "heavy",  "$JAM_HEAVY",
          "slow",   "$JAM_SLOW",
          "$JAM_UNKNOWN"],
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"],
                       10, $TRAFFIC_W_Z10, 18, $TRAFFIC_W_Z18],
        "line-opacity": 0.95 } },

    { "id": "route-chevrons", "type": "line", "source": "route-chevrons",
      "minzoom": 15,
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "$CHEVRON", "line-opacity": 0.75,
                 "line-width": ${floored(CHEVRON_STROKE_M, 1.2)} } },

    { "id": "walk-casing", "type": "line", "source": "walk",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-width":
        ["interpolate", ["exponential", 1.4], ["zoom"], 12, 5, 18, 14] } },

    { "id": "walk-shaded", "type": "line", "source": "walk",
      "filter": ["==", ["get", "shaded"], true],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "$WALK_SHADED",
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 12, 3, 18, 9],
        "line-dasharray": [1.6, 1.0] } },

    { "id": "walk-exposed", "type": "line", "source": "walk",
      "filter": ["==", ["get", "shaded"], false],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "$WALK_EXPOSED",
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 12, 3, 18, 9],
        "line-dasharray": [1.6, 1.0] } },

    { "id": "walk-route-casing", "type": "line", "source": "walk-route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "${c.roadCasing}", "line-width":
        ["interpolate", ["exponential", 1.4], ["zoom"], 12, 6, 19, 18] } },

    { "id": "walk-route-line", "type": "line", "source": "walk-route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "$WALK_ROUTE",
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 12, 4, 19, 12] } },

    { "id": "callout-leader", "type": "line", "source": "callouts",
      "filter": ["==", ["get", "part"], "leader"], "minzoom": $CALLOUT_MINZOOM,
      "layout": { "line-cap": "round" },
      "paint": { "line-color": "$CALLOUT_RIM", "line-opacity": 0.8,
                 "line-width": ${floored(0.5, 1.0)} } },

    { "id": "callout-anchor", "type": "circle", "source": "callouts",
      "filter": ["all", ["==", ["get", "part"], "anchor"], ["!", ["has", "icon"]]],
      "minzoom": $CALLOUT_MINZOOM,
      "paint": { "circle-color": "$CALLOUT_RIM",
                 "circle-stroke-color": "$CALLOUT_FILL",
                 "circle-stroke-width": 1.5,
                 "circle-radius": ["interpolate", ["linear"], ["zoom"], 15, 2.5, 18, 4.5] } },

    { "id": "callout-icon", "type": "symbol", "source": "callouts",
      "filter": ["all", ["==", ["get", "part"], "anchor"], ["has", "icon"]],
      "minzoom": $CALLOUT_MINZOOM,
      "layout": { "icon-image": ["get", "icon"],
                  "icon-size": 1,
                  "icon-allow-overlap": true,
                  "icon-ignore-placement": true,
                  "icon-pitch-alignment": "viewport",
                  "icon-rotation-alignment": "viewport" } },

    { "id": "route-labels-alt", "type": "symbol", "source": "route-labels",
      "filter": ["!", ["get", "chosen"]],
      "layout": {
        "text-field": ["get", "label"],
        "text-font": ["$FONT_STACK"],
        "text-size": 14,
        "text-max-width": 9,
        "text-allow-overlap": true,
        "text-ignore-placement": false,
        "text-offset": [0, -1.0]
      },
      "paint": {
        "text-color": "${c.routeAlt}",
        "text-halo-color": "${c.roadCasing}",
        "text-halo-width": 2.4,
        "text-halo-blur": 0.2
      } },

    { "id": "route-labels", "type": "symbol", "source": "route-labels",
      "filter": ["get", "chosen"],
      "layout": {
        "text-field": ["get", "label"],
        "text-font": ["$FONT_STACK"],
        "text-size": 15,
        "text-max-width": 9,
        "text-allow-overlap": true,
        "text-ignore-placement": false,
        "text-offset": [0, -1.0]
      },
      "paint": {
        "text-color": "$ROUTE",
        "text-halo-color": "${c.roadCasing}",
        "text-halo-width": 2.6,
        "text-halo-blur": 0.2
      } },

    { "id": "route-endpoints", "type": "symbol", "source": "route-endpoints",
      "layout": {
        "icon-image": ["get", "marker"],
        "icon-size": 1.0,
        "icon-anchor": ["match", ["get", "marker"], ["destination"], "bottom", "center"],
        "icon-allow-overlap": true,
        "icon-ignore-placement": true
      } },

    { "id": "road-labels", "type": "symbol", "source": "vector", "source-layer": "basemap",
      "filter": ["all", ["==", ["get", "kind"], "road"], ["has", "name"]], "minzoom": 13,
      "layout": {
        "symbol-placement": "line",
        "text-field": $nameField,
        "text-font": ["$FONT_STACK"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10, 18, 14],
        "text-max-angle": 35,
        "symbol-spacing": 300,
        "text-pitch-alignment": "viewport"
      },
      "paint": { "text-color": "${c.roadLabel}", "text-halo-color": "${c.halo}", "text-halo-width": 1.4 } },

    { "id": "puck-halo", "type": "circle", "source": "puck",
      "paint": { "circle-radius": ["interpolate", ["linear"], ["zoom"], 12, 12, 18, 26],
                 "circle-color": "$PUCK", "circle-opacity": 0.18 } },

    { "id": "puck", "type": "symbol", "source": "puck",
      "layout": {
        "icon-image": "vehicle",
        "icon-size": ["interpolate", ["linear"], ["zoom"], 12, 0.7, 18, 1.0],
        "icon-rotate": ["get", "bearing"],
        "icon-rotation-alignment": "map",
        "icon-pitch-alignment": "map",
        "icon-allow-overlap": true,
        "icon-ignore-placement": true
      } },

    { "id": "place-labels", "type": "symbol", "source": "vector", "source-layer": "basemap",
      "filter": ["==", ["get", "kind"], "label"], "minzoom": 4,
      "layout": {
        "text-field": $nameField,
        "text-font": ["$FONT_STACK"],
        "text-size": ["interpolate", ["linear"], ["zoom"],
          4,  ["match", ["get", "place"], ["country"], 15, ["state","region","province"], 12, 10],
          8,  ["match", ["get", "place"], ["country"], 19, ["city"], 16, ["state","region","province"], 14, ["town"], 12, 10],
          12, ["match", ["get", "place"], ["city"], 20, ["town"], 16, ["suburb","district","borough","quarter"], 14, 12],
          16, ["match", ["get", "place"], ["city"], 22, ["town"], 18, ["suburb","district","borough","quarter"], 16, 13]
        ],
        "text-transform": ["match", ["get", "place"], ["country","state","region","province"], "uppercase", "none"],
        "text-letter-spacing": ["match", ["get", "place"], ["country","state","region","province"], 0.18, 0.0],
        "text-anchor": "center",
        "text-max-width": 8,
        "symbol-sort-key": ["match", ["get", "place"],
          ["country"], 0, ["state","region","province"], 1, ["city"], 2, ["town"], 3,
          ["suburb","district","borough","quarter"], 4, ["village","neighbourhood"], 5, 6]
      },
      "paint": { "text-color": "${c.placeLabel}", "text-halo-color": "${c.halo}", "text-halo-width": 1.6 } },

    { "id": "poi-dots", "type": "circle", "source": "vector", "source-layer": "basemap",
      "filter": $poiFilter, "minzoom": 14,
      "paint": {
        "circle-radius": ["interpolate", ["exponential", 1.4], ["zoom"], 14, 1.8, 18, 4.0],
        "circle-color": "${c.poiDot}",
        "circle-opacity": 0.9
      } },
    { "id": "poi-labels", "type": "symbol", "source": "vector", "source-layer": "basemap",
      "filter": $poiLabelFilter, "minzoom": 16,
      "layout": {
        "text-field": $nameField,
        "text-font": ["$FONT_STACK"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 16, 10, 18, 12],
        "text-anchor": "top",
        "text-offset": [0, 0.7],
        "text-optional": true,
        "text-max-width": 7,
        "text-line-height": 1.1,
        "symbol-sort-key": $poiSortKey
      },
      "paint": { "text-color": "${c.poiLabel}", "text-halo-color": "${c.halo}", "text-halo-width": 1.4 } },

    { "id": "callout-pill", "type": "symbol", "source": "callouts",
      "filter": ["==", ["get", "part"], "label"], "minzoom": $CALLOUT_MINZOOM,
      "layout": { "icon-image": "${VectorMarkers.CALLOUT_PILL}",
                  "icon-text-fit": "width",
                  "icon-text-fit-padding": [0, 9, 0, 9],
                  "text-field": ["get", "text"],
                  "text-font": ["$FONT_STACK"],
                  "text-size": 12.5,
                  "text-anchor": "center",
                  "text-offset": [0, 0.05],
                  "text-pitch-alignment": "viewport",
                  "text-rotation-alignment": "viewport",
                  "icon-pitch-alignment": "viewport",
                  "icon-rotation-alignment": "viewport",
                  "symbol-sort-key": ["get", "rank"],
                  "symbol-z-order": "source",
                  "icon-optional": false,
                  "text-optional": false },
      "paint": { "text-color": "$CALLOUT_TEXT" } }
  ]
}
        """.trimIndent()
    }
}
