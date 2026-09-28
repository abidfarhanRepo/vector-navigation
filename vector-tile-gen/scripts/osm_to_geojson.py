#!/usr/bin/env python3
"""Convert an OpenStreetMap XML extract to the Vector GeoJSON feature schema.

Reads an OSM `.osm` (Overpass or Geofabrik) and emits a single GeoJSON
FeatureCollection whose features use the **same property schema** the
vector-tile-gen pipeline + the MapLibre styles filter on:

  roads:   kind=road, highway=<class>, name?, ref?, maxspeed?, oneway?
  water:   kind=water   (natural=water / waterway)
  park:    kind=park    (leisure=park / landuse=* / boundary)
  poi:     kind=poi,  poi_class=<amenity|shop|tourism|...>, name?
  label:   kind=label, place=<city|town|village>, name?
  signal:  kind=signal (highway=traffic_signals), written to a SEPARATE
           artifact when --signals-out is given (V7 Stage 5)
  camera:  kind=camera (highway=speed_camera), written to a SEPARATE
           artifact when --cameras-out is given (V7.3)
  barrier: kind=barrier (barrier=* NODE), written to a SEPARATE artifact
           when --barriers-out is given (V7.4 4A). A barrier node is a
           traversal fact -- ``access=private``/``locked=yes`` on a gate --
           not a basemap POI, and it must never ride in the main collection
           where it would move the road/POI feature counts.
  crossing: kind=crossing / kind=kerb NODES, written to a SEPARATE artifact
           when --crossings-out is given (V7.4 4A.4). A highway=crossing
           node is where a walk crosses a road; its crossing/kerb/tactile
           tags are the raw evidence 4B needs to phrase a crossing honestly.
           Same doctrine: location facts, never in the main collection.

This is the data half of Wave 46 (wider-Qatar tiles). The schema
mirrors `vector-ingestion` sample data so the EXISTING tile-gen
(`build_m1_tiles.py`) and the mobile/web map styles work unchanged.

Usage:
  python osm_to_geojson.py /tmp/qatar.osm -o /tmp/qatar.geojson

Streams with xml.etree.ElementTree.iterparse so a 300 MB+ extract
does not need to fit in RAM.
"""

import argparse
import json
import math
import sys
import xml.etree.ElementTree as ET


# POI top-level keys we treat as points of interest (not roads/areas).
POI_KEYS = ("amenity", "shop", "tourism", "office", "craft", "historic",
            "leisure", "aeroway", "public_transport", "railway", "emergency",
            "healthcare", "tourism", "information")


# Pedestrian infrastructure tags (V7.4 4A.4), promoted onto road features so
# the walking graph can source crossing/stairs/footway facts honestly. The
# recon is the rule: 4B may only say "cross the road" when the edge it is on
# actually carries ``footway=crossing`` (3,015 Qatar ways), and "take the
# stairs" when ``step_count``/``handrail``/``incline`` say what the stairs
# are. Values are copied RAW -- nothing here invents a semantics the source
# tag does not state; the tags are preserved so later stages decide what they
# mean.
#
# Deliberately not here: ``smoothness``/``wheelchair``/``bicycle``. They exist
# in the extract but are not in the recon's promotion list, and every key in
# this tuple rides onto the edge props of the routing graphs, so it is a
# contract, not a grab-bag.
PEDESTRIAN_KEYS = ("footway", "crossing", "crossing:markings", "sidewalk",
                   "kerb", "surface", "incline", "handrail", "step_count",
                   "width", "lit")

# ``kerb``/``crossing``-adjacent tags on a CROSSING NODE, preserved alongside
# the crossing fact so 4B can tell a marked from an unmarked crossing and a
# lowered from a flush kerb. Subset of PEDESTRIAN_KEYS plus the node-only ones.
CROSSING_NODE_KEYS = ("crossing", "crossing:markings", "kerb",
                      "tactile_paving", "traffic_signals", "lit", "ramp",
                      "width")

# Road-way tags the V8 lane bake reads and the main collection does not carry.
# They go to the ``--lane-attrs-out`` sidecar and NOT into ``_attrs``, because
# every key ``_attrs`` emits rides onto every basemap tile and every routing
# edge -- adding them there would change the V7.6 basemap bytes and the
# routing graph's edge props, which a rendering-only change must not do.
#
#   layer                    elevation, for telling a crossing from a junction
#   lanes:forward/backward   where a two-way road's centre line actually is
#   lanes:both_ways          the shared centre lane, without which a correct
#                            forward+backward+both_ways=lanes split reads as
#                            a conflict
#
# Values are copied RAW; the lane bake decides what is usable.
LANE_SIDECAR_KEYS = ("layer", "lanes:forward", "lanes:backward",
                     "lanes:both_ways")


# Routability classification lives in vector_ingestion.classify (single source of
# truth, tested in vector-ingestion/tests/test_classify.py). This script is run
# standalone inside a slim container during bootstrap, so import defensively and
# degrade to "drivable if it has a highway tag" rather than crashing the bake.
try:
    from vector_ingestion.classify import (
        is_car_routable as _car_routable,
        is_pedestrian_routable as _foot_routable,
        barrier_pedestrian_effect as _barrier_effect,
    )
except ImportError:  # pragma: no cover - container without the package
    _CAR = {"motorway", "motorway_link", "trunk", "trunk_link", "primary",
            "primary_link", "secondary", "secondary_link", "tertiary",
            "tertiary_link", "unclassified", "residential", "living_street",
            "service", "track", "road", "busway"}

    def _car_routable(tags):
        hw = (tags.get("highway") or "").lower()
        if hw not in _CAR or tags.get("construction"):
            return False
        for k in ("motorcar", "motor_vehicle", "vehicle", "access"):
            v = (tags.get(k) or "").lower()
            if v in ("yes", "designated", "permissive", "destination", "official"):
                return True
            if v in ("no", "private", "customers", "permit", "military", "delivery"):
                return False
        return True

    def _foot_routable(tags):
        hw = (tags.get("highway") or "").lower()
        return hw in {"footway", "path", "steps", "pedestrian", "corridor",
                      "bridleway", "living_street", "residential", "service",
                      "track", "unclassified", "road", "cycleway"}

    # Degraded mirror of vector_ingestion.classify.barrier_pedestrian_effect:
    # decided once at ingestion by the real classifier in a normal bake; this
    # fallback exists only so the standalone converter cannot drop barrier
    # data it can now read. Order: foot=allowed, foot=denied, access=denied,
    # locked, a solid kind, else permissive (a gate with no access tag is
    # passable by foot -- OSM's default, and blocking it would delete
    # legitimate gates from the walking network).
    def _barrier_effect(tags):
        allowed = {"yes", "designated", "permissive", "destination", "official"}
        denied = {"no", "private", "customers", "permit", "military", "delivery"}
        locked = {"yes", "1", "true", "rear", "private"}
        solid = {"fence", "wall", "hedge", "retaining_wall", "city_wall",
                 "cement_block", "concrete_block", "concrete"}
        foot = (tags.get("foot") or "").strip().lower()
        if foot in allowed:
            return "pass"
        if foot in denied:
            return "block"
        if (tags.get("access") or "").strip().lower() in denied:
            return "block"
        if (tags.get("locked") or "").strip().lower() in locked:
            return "block"
        if (tags.get("barrier") or "").strip().lower() in solid:
            return "block"
        return "pass"


def _building_height_m(tags: dict) -> float | None:
    """The building's height in metres, from `height` ONLY — or None.

    ## Why `height` and not `building:levels`

    Measured on the full Qatar source (Geofabrik gcc-states 260912, streamed;
    189,871 building ways with a node inside the bbox):

        with `height`          982   (0.5%)
        with `building:levels` 7046   (3.7%)
        with both              535
        with neither         182378   (96.1%)

    6,511 buildings state levels and no height. Turning those into metres
    needs a metres-per-level constant, and there is none to justify:

      * the repository has no documented convention (the shade model's
        heights are per-`highway`-class facade ASSUMPTIONS, explicitly
        labelled an inference, and are not a storey height);
      * on the 519 dual-tagged buildings — the only place a real ratio is
        observable — it is min 2.50, p25 3.97, median 4.29, p75 5.00,
        max 60.00. That spread is not a constant; low-rise `building=yes`
        dominates the sample, and one number would be wrong by a quarter at
        the interquartile edges and by 14x at the top.

    So Vector extrudes what the source STATES and nothing else. A building
    with only levels is not extruded — "no building height, no invented
    building height". The levels count is still carried as provenance
    (`building_levels`) so a future surface that has evidence to convert can.

    ## The accepted grammar

    A plain number, optionally with a metre suffix, because that is what the
    tag means when it is valid:

        "40"    -> 40.0        "40 m"  -> 40.0      "40.5" -> 40.5
        "30'"   -> None        (feet — a different unit, not accepted)
        "0"     -> None        (a building has height)
        "-5"    -> None        ("400+" -> None)      ("abc" -> None)
        ">400"  -> None        (absurd)

    400 m is the rejection bound, not a datum: Qatar's tallest STATED height
    in this extract is 318 m, and a value above 400 is far likelier to be a
    unit mistake ("400 ft") than a building. Rejected rather than clamped,
    because clamping would invent a height the source did not state.
    """
    raw = tags.get("height")
    if raw is None:
        return None
    text = str(raw).strip().lower()
    if text.endswith("m"):
        text = text[:-1].strip()
    try:
        value = float(text)
    except ValueError:
        return None
    if not math.isfinite(value) or value <= 0.0 or value > 400.0:
        return None
    return value


def _building_attrs(tags: dict) -> dict:
    """The properties for a building footprint. Called only when `building` is
    in `tags`; always returns a feature.

    ## The three tiers, and which of them gets a height (V7.6)

    Every footprint is emitted. Whether it carries a `height_m` is a separate
    question from whether it is drawn, and conflating those two was the defect
    this function used to have: it returned None for 96% of Qatar, so the flat
    `buildings` fill layer — which needs no height at all — was starved of the
    only thing it ever wanted.

    Measured on gcc-states 260912 over Vector's bbox (189,866 building ways):

      | tier           | count   | share  | `height_m` | drawn as        |
      |----------------|--------:|-------:|------------|-----------------|
      | measured       |     975 |  0.51% | yes        | extruded volume |
      | levels only    |   6,499 |  3.42% | **no**     | flat fabric     |
      | footprint only | 182,392 | 96.06% | no         | flat fabric     |

    ## Why the middle tier gets no height

    It is the tempting one, and it is refused. Converting `building:levels`
    into metres needs a metres-per-level constant, and on the 519 Qatar
    buildings that state both — the only place the ratio is observable — it is
    min 2.50, p25 3.97, median 4.29, p75 5.00, max 60.00. That is a
    distribution, not a constant. Multiplying by its median would be wrong by
    a quarter at the interquartile edges and by 14x at the top, and the result
    would be indistinguishable in the tile from a height a surveyor stated.

    The V7.5 reconnaissance proposed extruding this tier at 4.29 m/level and
    styling it distinctly. That proposal is declined here, for the reason the
    milestone's own constraint gives: a derived height is not "grounded in
    source data" — the levels are, the constant is not. 6,499 buildings is
    3.4% of the country and not worth the one thing this feature cannot
    afford to lose, which is that every volume on the map is a volume
    somebody measured.

    What the tier gets instead is PROVENANCE: `building_levels` is carried
    into the tile verbatim. The data is therefore already in production if a
    later milestone ever finds evidence for a conversion — the decision is
    reversible without a re-bake of the acquisition, which is the whole point
    of recording it.

    ## min_height

    `min_height` rides along when the source states one (7 Qatar buildings
    do), so a building sitting on a podium is drawn from its own base rather
    than from the ground. Absent or unusable means base 0 — the ground —
    which is the honest default and not an invented figure. It is recorded
    only alongside a real `height_m`: a base with no top is not a volume.
    """
    height = _building_height_m(tags)
    if height is None:
        # Fabric. A block, not a volume: no height, no invented height, and
        # nothing downstream can extrude it — `buildings-3d` filters on
        # `["has", "height_m"]`, so the absence IS the contract.
        out: dict = {"kind": "building"}
        levels = tags.get("building:levels")
        if levels:
            out["building_levels"] = str(levels)
        return out
    out = {"kind": "building", "height_m": round(height, 1)}
    raw_min = tags.get("min_height")
    if raw_min is not None:
        min_text = str(raw_min).strip().lower()
        if min_text.endswith("m"):
            min_text = min_text[:-1].strip()
        try:
            min_value = float(min_text)
        except ValueError:
            min_value = None
        if (min_value is not None and math.isfinite(min_value)
                and 0.0 < min_value < height):
            out["min_height_m"] = round(min_value, 1)
    levels = tags.get("building:levels")
    if levels:
        out["building_levels"] = str(levels)
    return out


def _attrs(tags: dict) -> dict:
    """Promote a few OSM tags to the Vector property schema."""
    out: dict = {}
    # A traffic-signal NODE is a location fact, not a road. It must be
    # classified BEFORE the generic highway branch below, which would
    # otherwise type every ``highway=traffic_signals`` element as a ROAD and
    # then drop the node in phase 3 (only poi/label points are emitted),
    # which is exactly why signals reached nothing before V7 Stage 5.
    #
    # Preserved deliberately:
    #   * the node id (the feature ``id``, ``n<osm>``) is the stable source
    #     identity -- the same reference the /navigate wire contract reuses;
    #   * ``traffic_signals:direction`` is the only direction hint OSM
    #     records, present on a few dozen Qatari nodes. It is NOT the primary
    #     approach definition (the route's own bearing is) -- it is kept as
    #     provenance and for future approach handling.
    #   * ``crossing``/``traffic_signals`` describe what kind of signal this
    #     is (a pedestrian crossing vs a junction signal) and cost nothing.
    #
    # Not a timing source: no OSM tag here is a phase/cycle/offset. The
    # signal model treats every one of these as location-only evidence.
    if tags.get("highway") == "traffic_signals":
        out["kind"] = "signal"
        for key in ("traffic_signals:direction", "crossing", "traffic_signals"):
            if tags.get(key):
                out[key] = tags[key]
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    # A speed-camera NODE is a location fact (V7.3), classified BEFORE the
    # generic highway branch for the same reason the signal branch is: the
    # generic branch would type it ``road`` and phase 3 would drop it.
    # Preserved deliberately:
    #   * ``maxspeed`` -- the zone the camera is associated with (33 of
    #     Qatar's 133 camera nodes), provenance ONLY: the wire and the UI
    #     may show it, NEVER a claim that the camera is enforcing/active;
    #   * ``direction`` -- the mapper-recorded camera bearing on 12 nodes,
    #     provenance ONLY (values include malformed ones like 2460, so the
    #     client's direction gate must validate before using);
    #   * ``highway=speed_camera`` itself and ``enforcement`` — the source's
    #     own statement of WHAT the device is. These are the only
    #     legitimately-sourced camera TYPE evidence in OSM, and the routing
    #     classifier reads them and nothing else; a value it does not
    #     recognise stays unclassified rather than being rounded to the
    #     nearest type. Zero of Qatar's 133 nodes carry ``enforcement``;
    #   * ``name``/``ref`` when a mapper named a camera.
    # No OSM tag here says a camera is active, films, or flashes: nothing in
    # this schema pretends otherwise.
    if tags.get("highway") == "speed_camera":
        out["kind"] = "camera"
        # ``highway`` itself is preserved because it IS the source's statement
        # of what this device is: the tag's own definition is "a fixed
        # road-side or overhead speed camera". Dropping it would leave the
        # artifact unable to justify the type it is classified as, and the
        # routing classifier reads exactly this key (plus ``enforcement``)
        # and nothing else. Provenance, not decoration.
        for key in ("highway", "maxspeed", "direction", "camera:direction",
                    "enforcement"):
            if tags.get(key):
                out[key] = tags[key]
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    # A highway=crossing NODE is a pedestrian crossing FACT (V7.4 4A.4), not a
    # road, and classified BEFORE the generic highway branch for the same
    # reason signals and cameras are: the generic branch types it ``road`` and
    # phase 3 drops it, so 3,170 of Qatar's 3,519 crossing nodes -- every one
    # tagged ``highway=crossing`` -- were discarded along with their
    # ``crossing=marked``/``zebra`` semantics. That is the recon's sentence
    # being impossible: with the node gone, 4B cannot tell a zebra from an
    # unmarked gully at the crossing point.
    #
    # Written to a separate artifact (see --crossings-out) under the same
    # doctrine as signals/cameras/barriers: a crossing point is a location
    # fact, its raw tags preserved as provenance (``crossing`` type,
    # ``kerb``, ``tactile_paving``, ``crossing:markings``), and it must never
    # move the basemap/road/POI feature counts.
    #
    # A signal node keeps its signal role: ``highway=traffic_signals`` is
    # claimed by the signal branch above, and it already preserves
    # ``crossing=*`` (352 Qatari crossing nodes ride there).
    if tags.get("highway") == "crossing" or (
            tags.get("crossing") and not tags.get("highway")):
        out["kind"] = "crossing"
        for key in CROSSING_NODE_KEYS:
            if tags.get(key):
                out[key] = tags[key]
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    # A kerb=* NODE without a crossing role (lowered/flush curb at a path
    # edge). 27 in Qatar; the ones that sit AT a crossing are already carried
    # by the crossing branch above via the ``kerb`` key. Preserved the same
    # way, so accessible-crossing data survives ingestion.
    if tags.get("kerb") and not tags.get("highway"):
        out["kind"] = "kerb"
        out["kerb"] = tags["kerb"]
        if tags.get("tactile_paving"):
            out["tactile_paving"] = tags["tactile_paving"]
        if tags.get("name"):
            out["name"] = tags["name"]
        return out

    highway = tags.get("highway")
    if highway:
        out["kind"] = "road"
        out["highway"] = highway
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        if tags.get("ref"):
            out["ref"] = tags["ref"]
        if tags.get("maxspeed"):
            out["maxspeed"] = tags["maxspeed"]
        if tags.get("oneway") in ("yes", "1", "true"):
            out["oneway"] = "yes"
        elif tags.get("oneway") == "-1":
            # The way is one-way AGAINST its digitisation order. Recorded
            # explicitly so the graph builder can reverse it; collapsing it into
            # a plain "yes" would create edges pointing the wrong way down a
            # street, which is worse than having no one-way data at all.
            out["oneway"] = "-1"
        # Tags the routing graph needs and that were previously dropped:
        #   access/motor_vehicle/vehicle -> private and restricted roads
        #   service                      -> parking aisles and driveways
        #   junction                     -> roundabouts (maneuver generation)
        #   lanes, bridge, tunnel        -> future guidance and rendering
        for key in ("access", "motor_vehicle", "motorcar", "vehicle", "foot",
                    "service", "junction", "lanes", "bridge", "tunnel",
                    "construction",
                    # Shade (V7 Phase 3/4). `covered` and `indoor` mean a walk
                    # is out of the sun whatever the sun is doing; `area` marks
                    # the plazas and surface car parks where the on-device
                    # model has no facade to reason about and is expected to
                    # decline rather than promise shade that is not there.
                    "covered", "indoor", "area",
                    # Lane guidance and exit signage. `turn:lanes` is present on
                    # 3,090 Doha ways, so this is real data, not a placeholder.
                    "turn:lanes", "turn:lanes:forward", "turn:lanes:backward",
                    "destination", "destination:ref", "junction:ref", "exit_to",
                    ) + PEDESTRIAN_KEYS:
            if tags.get(key):
                out[key] = tags[key]
        # Routability, decided once at ingestion by vector_ingestion.classify so
        # the graph builder never has to re-derive it (and cannot disagree).
        out["car"] = _car_routable(tags)
        out["foot"] = _foot_routable(tags)
        return out

    natural = tags.get("natural")

    # COASTLINE IS A LINE, and it must be classified before the water branch.
    #
    # `_attrs` is a second classifier alongside `vector_ingestion.osm.
    # _basemap_kind`, and only that one was ever taught about coastline. This
    # script is what `bootstrap.sh` actually runs, so `natural=coastline`
    # matched no branch here, returned empty props, and phase 2 dropped the way
    # — 478 of them for Qatar, fetched by their own dedicated Overpass request
    # (step 1c) and, in PBF mode, carried in the snapshot. Everything
    # downstream was already waiting for them: `build_qatar_tiles` ranks
    # `coastline` with water and reserves 6% of every tile for it, and both the
    # Android and web styles have a `line` layer filtering on this exact kind.
    # The shoreline was the one missing link in a chain that was otherwise
    # complete.
    #
    # Ordered BEFORE water for the reason `_basemap_kind` documents: a
    # coastline way is OPEN, and letting it reach an area branch closes the
    # ring into a polygon spanning wherever its two loose ends happen to be —
    # a spurious fill across the map instead of a shoreline. Phase 2 only
    # closes rings for `water` and `park`, so a distinct kind is also what
    # keeps the geometry a LineString.
    #
    # Qatar is a peninsula: without this it has no shape, and the country view
    # is roads floating in a void.
    if natural == "coastline":
        out["kind"] = "coastline"
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    waterway = tags.get("waterway")
    if natural == "water" or waterway:
        out["kind"] = "water"
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    landuse = tags.get("landuse")
    leisure = tags.get("leisure")
    boundary = tags.get("boundary")
    if landuse or leisure == "park" or boundary == "national_park":
        out["kind"] = "park"
        if landuse:
            out["landuse"] = landuse
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    place = tags.get("place")
    if place:
        out["kind"] = "label"
        out["place"] = place
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    # POI: first matching POI key wins.
    for k in POI_KEYS:
        if k in tags:
            out["kind"] = "poi"
            out["poi_class"] = tags[k]
            if tags.get("name"):
                out["name"] = tags["name"]
            if tags.get("name:en"):
                out["name:en"] = tags["name:en"]
            return out

    # A NAMED BUILDING is a place, even with no other tag.
    #
    # Qatar has 2,077 of them ("Tornado Tower", "Al Fardan Centre") and they
    # were dropped entirely: there is no `building` branch above, so a way
    # tagged only `building=yes` + `name` returned empty props and never became
    # a feature. That is a straight loss of 2,077 names a driver would search
    # for, and it is a large part of why "names of places are entirely missing
    # compared to Google Maps".
    #
    # Emitted with NO `poi_class`. The tag value is `yes` for most of them,
    # which means "this exists" rather than naming a category — the geocoder
    # already refuses to present that as one, so inventing `poi_class:
    # building` here would only push a useless word onto the driver's screen.
    # A name and a distance is the honest row.
    if tags.get("building") and tags.get("name"):
        out["kind"] = "poi"
        out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    # A barrier=* NODE is a traversal fact (V7.4 4A), classified BEFORE the
    # generic highway branch the way signals and cameras are, and placed AFTER
    # the POI branches so a node that is also a named place stays a place. A
    # gate tagged ``access=private`` or ``locked=yes`` is a statement about
    # whether a person may pass a point; the routing graph is keyed by
    # coordinate, and this is the only stage that still sees the OSM node id
    # and tag set together. The recon's measurement is the reason it exists:
    # 2,164 Qatar gate nodes are tagged private/no or locked, the tag was
    # dropped here, and Vector walked people through locked gates.
    #
    # Overridden by anything the branches ABOVE already claimed: a barrier node
    # that is also a signal, a POI or a named building keeps that role (4 real
    # nodes carry a POI key; each would be lost from the map otherwise). A
    # barrier WAY (a wall line) never reaches routing -- it is a boundary, not
    # a routable edge -- so phase 2 drops kind=barrier line features and only
    # phase 3 turns barrier NODES into the artifact.
    #
    # ``pedestrian_effect`` is the decision of
    # ``vector_ingestion.classify.barrier_pedestrian_effect`` -- the single
    # authority, computed here once so the artifact and the engine can never
    # disagree (it is the walking equivalent of the ``car``/``foot`` flags).
    # The raw tags ride along as provenance for operators and for the engine's
    # reporting.
    if tags.get("barrier") and not tags.get("highway"):
        out["kind"] = "barrier"
        out["barrier"] = tags["barrier"]
        out["pedestrian_effect"] = _barrier_effect(tags)
        for key in ("access", "foot", "locked"):
            if tags.get(key):
                out[key] = tags[key]
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    # bus_stop as a POI even though keyed under highway.
    if tags.get("highway") == "bus_stop":
        out["kind"] = "poi"
        out["poi_class"] = "bus_stop"
        if tags.get("name"):
            out["name"] = tags["name"]
        if tags.get("name:en"):
            out["name:en"] = tags["name:en"]
        return out

    return out


def _ring_centroid(coords):
    """Centre of a polygon ring, or of an open way's vertices.

    Uses the SHOELACE centroid for a closed ring — the centre of AREA rather
    than the mean of the vertices. The difference matters because OSM buildings
    are not evenly vertexed: one long straight wall carries two points while a
    curved facade carries thirty, so the vertex mean is pulled toward whichever
    side happens to be mapped in more detail. The centre of area is not, and it
    is independent of winding order.

    **It is not guaranteed to be inside the shape.** For a strongly concave
    building the centre of area can land in the courtyard — an L of side 3 with
    a 2x2 notch has its centroid at (1.1, 1.1), which is in the notch. Pinned by
    `test_the_centroid_of_an_L_shape_can_fall_outside_it`, because the honest
    limit is worth recording: a guaranteed-interior point needs a
    pole-of-inaccessibility search (polylabel), which is a great deal more work
    for a case that is rare among the shapes POIs are actually mapped as.

    What it replaces is `coords[0]`, an arbitrary corner of the polygon, so even
    the concave case is a large improvement rather than a regression.

    Falls back to the vertex mean when the ring encloses no area — a degenerate
    or open way — because a zero-area shoelace divides by zero, and a POI at
    the mean of two points is still better than no POI.
    """
    pts = [(float(c[0]), float(c[1])) for c in coords if len(c) >= 2]
    if not pts:
        return 0.0, 0.0
    if len(pts) < 3:
        return (sum(p[0] for p in pts) / len(pts),
                sum(p[1] for p in pts) / len(pts))
    ring = pts if pts[0] != pts[-1] else pts[:-1]
    if len(ring) < 3:
        return (sum(p[0] for p in ring) / len(ring),
                sum(p[1] for p in ring) / len(ring))
    a2 = cx = cy = 0.0
    for i in range(len(ring)):
        x0, y0 = ring[i]
        x1, y1 = ring[(i + 1) % len(ring)]
        cross = x0 * y1 - x1 * y0
        a2 += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    if abs(a2) < 1e-12:
        return (sum(p[0] for p in ring) / len(ring),
                sum(p[1] for p in ring) / len(ring))
    return cx / (3.0 * a2), cy / (3.0 * a2)


def _parse_tags(elem) -> dict:
    tags = {}
    for t in elem.findall("tag"):
        k = t.get("k")
        v = t.get("v")
        if k is not None:
            tags[k] = v
    return tags



# Movement kinds we can express against a single via NODE. Kept here (rather
# than imported from vector-routing) because this script runs standalone inside
# a slim bootstrap container with no Vector packages on the path; the routing
# side re-validates every kind it is handed, so this list is a filter, not the
# authority.
_RESTRICTION_KINDS = frozenset({
    "no_left_turn", "no_right_turn", "no_straight_on", "no_u_turn",
    "no_entry", "no_exit",
    "only_left_turn", "only_right_turn", "only_straight_on",
})


def _window_around(nds, via_id, nodes):
    """The via node and its immediate neighbours on a way, as coordinates.

    A via-NODE turn restriction is defined entirely by (approach, via, exit), so
    the only part of a way that carries information is the via node and whatever
    sits either side of it. Emitting the whole way would multiply the output for
    nothing.

    Returns ``None`` when the via is not on this way (it can legitimately be
    off-extract at a bbox edge) or when a neighbour's coordinate is unknown.
    A restriction that cannot be placed must be DROPPED, never guessed: a
    guessed junction bans a movement somewhere the mapper never asked for.
    """
    try:
        i = nds.index(via_id)
    except ValueError:
        return None
    lo = max(0, i - 1)
    hi = min(len(nds), i + 2)
    win = []
    for nid in nds[lo:hi]:
        c = nodes.get(nid)
        if c is None:
            return None
        win.append([c[0], c[1]])
    return win if len(win) >= 2 else None


def resolve_restrictions(relations, way_nds, nodes):
    """Turn ``type=restriction`` relations into coordinate-space movements.

    ``relations`` is ``[(id, tags, members)]``, ``way_nds`` maps way id ->
    ordered node ids, ``nodes`` maps node id -> (lon, lat).

    Each output record carries the via COORDINATE plus a three-node coordinate
    window of the from-way and the to-way. Everything downstream keys on
    coordinates, so this is the last stage that can do the join.

    Only the simple from-way / via-NODE / to-way form is emitted. A via-WAY
    restriction (common on large grade-separated junctions) needs the router to
    track more than one predecessor edge, which it cannot do, so emitting it
    would imply an enforcement that never happens.
    """
    out = []
    skipped = {"via_way": 0, "unresolved": 0, "unknown_kind": 0}
    for rid, rtags, members in relations:
        kind = (rtags.get("restriction") or "").strip().lower()
        if kind not in _RESTRICTION_KINDS:
            # Includes `restriction:hgv` / `restriction:conditional`, which are
            # deliberately not applied to a car profile at all hours.
            if kind:
                skipped["unknown_kind"] += 1
            continue
        frm = [m for m in members if m.get("role") == "from" and m.get("type") == "way"]
        to = [m for m in members if m.get("role") == "to" and m.get("type") == "way"]
        via_n = [m for m in members if m.get("role") == "via" and m.get("type") == "node"]
        via_w = [m for m in members if m.get("role") == "via" and m.get("type") == "way"]
        if via_w and not via_n:
            skipped["via_way"] += 1
            continue
        if len(frm) != 1 or len(to) != 1 or len(via_n) != 1:
            skipped["unresolved"] += 1
            continue
        try:
            via_id = int(via_n[0]["ref"])
            from_nds = way_nds.get(int(frm[0]["ref"]))
            to_nds = way_nds.get(int(to[0]["ref"]))
        except (TypeError, ValueError):
            skipped["unresolved"] += 1
            continue
        via_coord = nodes.get(via_id)
        if via_coord is None or not from_nds or not to_nds:
            skipped["unresolved"] += 1
            continue
        from_win = _window_around(from_nds, via_id, nodes)
        to_win = _window_around(to_nds, via_id, nodes)
        if from_win is None or to_win is None:
            skipped["unresolved"] += 1
            continue
        out.append({
            "id": str(rid),
            "restriction": kind,
            "via": [via_coord[0], via_coord[1]],
            "from_nodes": from_win,
            "to_nodes": to_win,
        })
    return out, skipped


def scan_restriction_extract(path: str):
    """Stream a relation-only ``.osm`` and return ``(relations, way_nds, nodes)``.

    Overpass answers ``relation["type"="restriction"]; out body; >; out skel qt;``
    with the relations, their member ways (nd refs only) and every node those
    ways touch, with coordinates -- so this file is self-contained and needs
    nothing from the main extract.
    """
    relations = []
    way_nds: dict[int, list[int]] = {}
    nodes: dict[int, tuple[float, float]] = {}
    cur = None
    for event, elem in ET.iterparse(path, events=("start", "end")):
        tag = elem.tag
        if event == "start":
            if tag in ("node", "way", "relation"):
                cur = {"id": elem.get("id"), "tags": {}, "nds": [], "members": [],
                       "lon": elem.get("lon"), "lat": elem.get("lat")}
            continue
        if cur is not None and tag == "tag":
            k = elem.get("k")
            if k is not None:
                cur["tags"][k] = elem.get("v")
        elif cur is not None and tag == "nd":
            r = elem.get("ref")
            if r is not None:
                cur["nds"].append(int(r))
        elif cur is not None and tag == "member":
            cur["members"].append({"type": elem.get("type"), "ref": elem.get("ref"),
                                   "role": elem.get("role")})
        elif tag in ("node", "way", "relation"):
            if cur is not None and cur.get("id") is not None:
                if tag == "node" and cur["lon"] and cur["lat"]:
                    nodes[int(cur["id"])] = (float(cur["lon"]), float(cur["lat"]))
                elif tag == "way":
                    way_nds[int(cur["id"])] = cur["nds"]
                elif tag == "relation" and cur["tags"].get("type") == "restriction":
                    relations.append((cur["id"], cur["tags"], cur["members"]))
            cur = None
            elem.clear()
    return relations, way_nds, nodes



def _parse_one(path, nodes, ways, relations, node_features,
               seen_ways, seen_node_feats, seen_rels):
    """Stream one ``.osm`` into the shared id-keyed tables.

    Uses a stateful ``iterparse`` (start+end events) and captures
    ``<tag>``/``<nd>``/``<member>`` attributes at *their own* end-event. That is
    required, not stylistic: ElementTree recycles child element attributes once
    the parent's end-event fires, so reading them via ``parent.findall('tag')``
    afterwards yields empty attributes and would silently drop every road in a
    large extract.

    ``seen_*`` carry across calls so a tiled fetch, whose tiles overlap at their
    edges, contributes each element once.
    """
    from itertools import count as _count

    accs: dict[int, dict] = {}
    elem_stack: list = []
    uid = _count()
    n_nodes = n_ways = 0
    for event, elem in ET.iterparse(path, events=("start", "end")):
        tag = elem.tag
        if event == "start":
            if tag in ("node", "way", "relation"):
                key = next(uid)
                accs[key] = {"id": elem.get("id"), "tags": {}, "nds": [],
                             "lon": elem.get("lon"), "lat": elem.get("lat"),
                             "members": []}
                elem_stack.append((key, elem))
            continue
        if tag == "tag":
            if elem_stack:
                k = elem.get("k")
                if k is not None:
                    accs[elem_stack[-1][0]]["tags"][k] = elem.get("v")
        elif tag == "nd":
            if elem_stack:
                r = elem.get("ref")
                if r is not None:
                    accs[elem_stack[-1][0]]["nds"].append(int(r))
        elif tag == "member":
            # Relation members. Needed for turn restrictions
            # (type=restriction: from-way, via-node, to-way), which were
            # previously discarded entirely -- so a no-left-turn was invisible
            # to the router and would simply be driven through.
            if elem_stack:
                accs[elem_stack[-1][0]]["members"].append({
                    "type": elem.get("type"),
                    "ref": elem.get("ref"),
                    "role": elem.get("role"),
                })
        elif tag in ("node", "way", "relation"):
            if not elem_stack:
                continue
            key, _ = elem_stack.pop()
            acc = accs.pop(key)
            wid = acc["id"]
            if tag == "node":
                n_nodes += 1
                if acc["lon"] is not None and acc["lat"] is not None:
                    lon_f, lat_f = float(acc["lon"]), float(acc["lat"])
                    nodes[int(wid)] = (lon_f, lat_f)
                    # A standalone node carrying its own tags is a POI/label
                    # (mall, landmark, hotel, suburb...). Buffer it so it
                    # becomes a searchable feature — ways alone omit every
                    # named place that OSM models as a point.
                    if acc["tags"] and wid not in seen_node_feats:
                        seen_node_feats.add(wid)
                        node_features.append((wid, acc["tags"], lon_f, lat_f))
                if n_nodes % 500000 == 0:
                    print(f"[osm]   {n_nodes} nodes ...", flush=True)
            elif tag == "way":
                n_ways += 1
                if wid not in seen_ways:
                    seen_ways.add(wid)
                    # Buffer for phase 2 (coords resolved after all nodes seen).
                    ways.append((wid, acc["tags"], acc["nds"]))
                if n_ways % 100000 == 0:
                    print(f"[osm]   {n_ways} ways buffered ...", flush=True)
            elif tag == "relation":
                if acc["tags"].get("type") == "restriction" and wid not in seen_rels:
                    seen_rels.add(wid)
                    relations.append((wid, acc["tags"], acc["members"]))
        elem.clear()
    print(f"[osm]   {path}: {n_nodes} nodes, {n_ways} ways", flush=True)


def convert(path, out_path: str, relations_path: str | None = None,
            signals_out: str | None = None,
            cameras_out: str | None = None,
            barriers_out: str | None = None,
            crossings_out: str | None = None,
            lane_attrs_out: str | None = None) -> int:
    """Return feature count; write GeoJSON to out_path.

    ``lane_attrs_out`` (V8) writes the lane bake's inputs that the main
    collection does not carry -- see ``LANE_SIDECAR_KEYS``. Unlike the
    artifacts above, nothing is MOVED out of the main collection: it is
    byte-identical with or without this argument.

    When ``signals_out`` is given, ``highway=traffic_signals`` node features
    (V7 Stage 5) are written to THAT artifact and excluded from ``out_path``
    entirely -- they are route intelligence, not basemap POIs, and a signal
    riding along in the tile bake or the geocoder index earns nothing. When
    it is None (the old standalone contract) they are merged into the main
    collection so the converter cannot silently drop data it now knows how to
    read.

    ``cameras_out`` (V7.3) and ``barriers_out`` (V7.4 4A) follow the same
    doctrine: a camera is a location fact, a barrier node is a traversal
    fact, and neither belongs in the basemap feature counts or the geocoder
    index.

    ``crossings_out`` (V7.4 4A.4) holds the crossing/kerb NODE facts
    (``highway=crossing`` and kerb-only nodes), the same way: a crossing
    point is route intelligence, not a basemap POI.

    ``path`` is one ``.osm`` file or a list of them. Several are accepted
    because the public Overpass instances will not serve Qatar in one query:
    measured 2026-09-08, the whole-region request was refused by all three
    mirrors on nine consecutive attempts, while the SAME query over one quadrant
    of the same bbox returned 25 MB in 37 seconds. So the extract is fetched as
    a grid of tiles and merged HERE, where the id-keyed tables make deduplication
    free — rather than by a separate XML merge tool over a 200 MB intermediate.

    Overpass returns whole ways for a bbox query and ``>;`` pulls every node they
    touch, including nodes outside the box, so a tiled union is complete: a way
    straddling a tile boundary arrives intact from whichever tile it intersects.

    Uses a stateful ``iterparse`` (start+end events) and captures
    ``<tag>``/``<nd>`` attributes at *their own* end-event. This is
    required because ElementTree recycles child element attributes once
    the parent's end-event fires — reading them via
    ``parent.findall('tag')`` after the fact yields empty attributes,
    which would silently drop every road in a large OSM extract.
    """
    features: list[dict] = []

    def emit_point(lon: float, lat: float, props: dict, fid) -> None:
        if not props:
            return
        features.append({
            "type": "Feature",
            "id": fid,
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": props,
        })

    def emit_linestring(coords, props, fid) -> None:
        if len(coords) < 2 or not props:
            return
        features.append({
            "type": "Feature",
            "id": fid,
            "geometry": {"type": "LineString", "coordinates": coords},
            "properties": props,
        })

    def emit_polygon(coords, props, fid) -> None:
        if len(coords) < 4 or not props:
            return
        features.append({
            "type": "Feature",
            "id": fid,
            "geometry": {"type": "Polygon", "coordinates": [coords]},
            "properties": props,
        })

    lane_attrs: dict[str, dict] | None = {} if lane_attrs_out else None

    paths = [path] if isinstance(path, str) else list(path)
    # Phase 1: stream each input once. Build the node coordinate table and
    # *buffer* ways (their tags + nd refs) instead of resolving immediately. OSM
    # extracts interleave <way> with <node> (ways can reference nodes that
    # appear LATER in the file), so a single-pass resolution would leave most
    # roads with empty coordinate lists.
    nodes: dict[int, tuple[float, float]] = {}
    ways: list[tuple[str, dict, list[int]]] = []  # (id, tags, nds)
    relations: list[tuple[str, dict, list[dict]]] = []  # turn restrictions
    # Standalone tagged nodes (POIs/labels): (id, tags, lon, lat).
    node_features: list[tuple[str, dict, float, float]] = []
    # Tiles overlap at their edges and Overpass returns whole ways, so the same
    # element legitimately arrives more than once. First one wins; a duplicate
    # would otherwise become a duplicate FEATURE, drawn twice and routed over
    # twice.
    seen_ways: set[str] = set()
    seen_node_feats: set[str] = set()
    seen_rels: set[str] = set()
    for _path in paths:
        _parse_one(_path, nodes, ways, relations, node_features,
                   seen_ways, seen_node_feats, seen_rels)
    n_nodes, n_ways = len(nodes), len(ways)
    print(f"[osm] parsed {n_nodes} nodes, {n_ways} ways; resolving {len(ways)} ways ...",
          flush=True)
    # Phase 2: resolve buffered ways against the now-complete node table.
    n_buildings = 0
    n_building_heights = 0
    for wid, tags, nds in ways:
        # V7 3D: a building footprint is a POLYGON, and it is emitted BEFORE
        # the `_attrs` dispatch because that dispatch returns nothing at all
        # for an unnamed building — 96% of Qatar's buildings carry no name, so
        # a building branch placed after it would only ever see the 2,078
        # named ones and none of the tall anonymous towers.
        #
        # V7.6: EVERY footprint is emitted, not only the height-bearing ones.
        # `_building_attrs` decides the TIER (a volume with `height_m`, or
        # flat fabric without); this branch decides only whether the geometry
        # is a usable ring. A building is never a road, so this cannot
        # double-emit a way that the dispatch below also handles (a way with
        # both `highway` and `building` is a tagging error, and both
        # statements are true, so both are emitted).
        #
        # The ring test is the only filter left, and it is a geometry test
        # rather than a policy one: a footprint needs at least 4 coordinates
        # and a closed ring to be a polygon at all. An open way tagged
        # `building` is broken source data, and a polygon cannot be invented
        # from it by joining the ends — that would be fabricating geometry.
        if "building" in tags:
            bcoords = [list(nodes[i]) for i in nds if i in nodes]
            if len(bcoords) >= 4 and bcoords[0] == bcoords[-1]:
                bprops = _building_attrs(tags)
                emit_polygon(bcoords, bprops, f"b{wid}")
                n_buildings += 1
                if "height_m" in bprops:
                    n_building_heights += 1
        props = _attrs(tags)
        if not props:
            continue
        coords = [list(nodes[i]) for i in nds if i in nodes]
        if props["kind"] == "road":
            emit_linestring(coords, props, f"w{wid}")
            if lane_attrs is not None and len(coords) >= 2:
                # Recorded for exactly the ways emit_linestring kept, with the
                # node ids of exactly the coordinates it kept, so position i
                # in `nodes` IS vertex i of the emitted LineString.
                rec = {"nodes": [i for i in nds if i in nodes]}
                for key in LANE_SIDECAR_KEYS:
                    if tags.get(key):
                        rec[key] = tags[key]
                lane_attrs[f"w{wid}"] = rec
        elif props["kind"] in ("water", "park"):
            if coords and coords[0] == coords[-1]:
                emit_polygon(coords, props, f"w{wid}")
            else:
                emit_linestring(coords, props, f"w{wid}")
        elif props["kind"] == "signal":
            # A signal is a NODE concept. A way typed highway=traffic_signals
            # is a tagging mistake and emitting it as a line would place a
            # signal along a whole road; there is no honest point for it.
            continue
        elif props["kind"] == "camera":
            # Same rule as signals (V7.3): a camera is a NODE fact; a way
            # typed highway=speed_camera is a tagging mistake and must not
            # become a line along a road.
            continue
        elif props["kind"] == "barrier":
            # A barrier is a NODE concept (V7.4 4A). A way tagged barrier=*
            # is a linear boundary -- a wall or fence line -- not a traversal
            # point, and emitting it as a line would claim the whole boundary
            # is a blocked crossing. There is no honest point for it.
            continue
        elif props["kind"] in ("crossing", "kerb"):
            # Crossing/kerb are NODE facts (V7.4 4A.4). A way wrongly typed
            # highway=crossing would be a line along the whole crossing and
            # claim the entire road is a crossing; there is no honest point
            # for it.
            continue
        elif props["kind"] == "poi":
            # The CENTROID, not coords[0].
            #
            # A POI mapped as an area — which is how malls, hospitals, schools
            # and most large shops are mapped — was being placed at the first
            # vertex of its polygon, i.e. an arbitrary corner of the building.
            # For Villaggio Mall that is a car-park entrance several hundred
            # metres from the door, and it is the coordinate search returns,
            # routes to, and measures "8.7 km away" from.
            if coords:
                cx, cy = _ring_centroid(coords)
                emit_point(cx, cy, props, f"w{wid}")
        else:
            emit_linestring(coords, props, f"w{wid}")

    print(f"[osm] parsed {n_nodes} nodes, {n_ways} ways", flush=True)
    if n_buildings:
        print(f"[osm] emitted {n_buildings} building footprints, "
              f"{n_building_heights} of them carrying a source-stated height "
              f"({100.0 * n_building_heights / n_buildings:.2f}%). The rest are "
              f"flat fabric and are deliberately given no height (see "
              f"_building_attrs)", flush=True)

    # Phase 3: emit standalone tagged nodes as POI/label points. These carry
    # the named places users actually search for (malls, hotels, landmarks,
    # suburbs) that never appear as ways.
    n_node_feats = 0
    signal_features: list[dict] = []
    camera_features: list[dict] = []
    barrier_features: list[dict] = []
    crossing_features: list[dict] = []
    for nid, tags, lon, lat in node_features:
        props = _attrs(tags)
        if not props:
            continue
        if props["kind"] == "signal":
            # V7 Stage 5: a signal node is a location fact for the routing
            # wire, not a searchable place. Held apart so it can be written to
            # its own artifact (see convert's docstring) -- and also so the
            # basemap/tile/geocoder feature count is untouched by signal
            # ingestion, which is an acceptance condition of this step.
            signal_features.append({
                "type": "Feature",
                "id": f"n{nid}",
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
                "properties": props,
            })
            continue
        if props["kind"] == "camera":
            # V7.3: same doctrine as signals -- a location fact for the
            # routing wire, held apart so camera ingestion cannot move the
            # basemap/road/tile counts either.
            camera_features.append({
                "type": "Feature",
                "id": f"n{nid}",
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
                "properties": props,
            })
            continue
        if props["kind"] == "barrier":
            # V7.4 4A: a barrier node is a traversal fact (a gate is private,
            # locked, or passable), not a searchable place and not basemap
            # geometry. Held apart so barrier ingestion cannot move the
            # basemap/road/POI feature counts either -- the same acceptance
            # condition as signals and cameras.
            barrier_features.append({
                "type": "Feature",
                "id": f"n{nid}",
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
                "properties": props,
            })
            continue
        if props["kind"] in ("crossing", "kerb"):
            # V7.4 4A.4: a crossing/kerb node is a pedestrian crossing fact
            # (its point, its crossing type, its kerb), not a searchable
            # place. Same doctrine and the same acceptance condition as
            # signals/cameras/barriers: held apart so these facts cannot move
            # the basemap counts.
            crossing_features.append({
                "type": "Feature",
                "id": f"n{nid}",
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
                "properties": props,
            })
            continue
        # Only points make sense here; skip road/water/park node-typing.
        if props["kind"] in ("poi", "label"):
            emit_point(lon, lat, props, f"n{nid}")
            n_node_feats += 1
    print(f"[osm] emitted {n_node_feats} POI/label node features, "
          f"{len(signal_features)} signal nodes, "
          f"{len(camera_features)} camera nodes, "
          f"{len(barrier_features)} barrier nodes, "
          f"{len(crossing_features)} crossing/kerb nodes", flush=True)

    # Phase 4: turn restrictions. Carried as a top-level key rather than as
    # features, because they are not geometry -- they are constraints on how two
    # ways may be joined, and the routing graph consumes them separately.
    #
    # Resolution happens HERE, in the converter, because this is the only stage
    # that still holds the OSM node table. A restriction references its via by
    # NODE ID, and the routing graph is keyed by COORDINATE -- so a restriction
    # emitted with a bare node id is unresolvable downstream and the whole
    # feature dies silently at the last joint. That is exactly what happened
    # before: the converter emitted `via_node: "552888172"` and nothing in
    # vector-routing could turn that into a graph key.
    # `ways` holds the id as the raw XML string; the resolver keys on int.
    way_nds = {int(wid): nds for wid, _tags, nds in ways}
    restrictions, skipped = resolve_restrictions(relations, way_nds, nodes)
    if relations:
        print(f"[osm] {len(restrictions)} turn restrictions resolved "
              f"({skipped['via_way']} via-way skipped (unsupported), "
              f"{skipped['unresolved']} unresolvable)", flush=True)

    # An optional SECOND extract carrying only `type=restriction` relations.
    # It exists because the public Overpass instances reject a single query that
    # asks for highways AND relations -- measured 2026-09-08, all three mirrors
    # returned error pages for the combined form while the relation-only query
    # returned 3,802 relations in seconds. So restrictions are fetched
    # separately and merged here rather than the query being widened.
    if relations_path:
        extra_rel, extra_ways, extra_nodes = scan_restriction_extract(relations_path)
        extra, extra_skipped = resolve_restrictions(extra_rel, extra_ways, extra_nodes)
        seen = {r["id"] for r in restrictions}
        merged = [r for r in extra if r["id"] not in seen]
        restrictions.extend(merged)
        print(f"[osm] +{len(merged)} turn restrictions from {relations_path} "
              f"({extra_skipped['via_way']} via-way skipped, "
              f"{extra_skipped['unresolved']} unresolvable)", flush=True)

    fc = {"type": "FeatureCollection", "features": features}
    if restrictions:
        fc["turn_restrictions"] = restrictions
    if signals_out:
        # The signals artifact: a FeatureCollection of kind=signal points,
        # nothing else in it. Absent (a deployment baked before this step) it
        # is simply not part of the stack; routing treats a missing catalog as
        # an empty one, which is a valid state (see vector_routing.signals).
        with open(signals_out, "w", encoding="utf-8") as fh:
            json.dump({"type": "FeatureCollection", "features": signal_features},
                      fh, ensure_ascii=False)
        print(f"[osm] wrote {len(signal_features)} signal features -> {signals_out}",
              flush=True)
    else:
        # Old standalone contract: signals ride in the same collection. This
        # keeps a catalog-less converter run from silently losing them.
        features.extend(signal_features)
    if cameras_out:
        # V7.3: the cameras artifact, same shape and same rule as signals.
        with open(cameras_out, "w", encoding="utf-8") as fh:
            json.dump({"type": "FeatureCollection", "features": camera_features},
                      fh, ensure_ascii=False)
        print(f"[osm] wrote {len(camera_features)} camera features -> {cameras_out}",
              flush=True)
    else:
        features.extend(camera_features)
    if barriers_out:
        # V7.4 4A: the barriers artifact, same shape and same rule as signals
        # and cameras -- a FeatureCollection of kind=barrier points carrying
        # the pedestrian_effect decision plus the raw OSM provenance
        # (barrier/access/foot/locked). Absent (a deployment baked before this
        # step, or in Overpass mode before barrier nodes were fetched) it is
        # not part of the stack: routing treats a missing catalog as an empty
        # one and walks exactly as it did before.
        with open(barriers_out, "w", encoding="utf-8") as fh:
            json.dump({"type": "FeatureCollection", "features": barrier_features},
                      fh, ensure_ascii=False)
        print(f"[osm] wrote {len(barrier_features)} barrier features -> {barriers_out}",
              flush=True)
    else:
        # Old standalone contract: barriers ride in the same collection. This
        # keeps a catalog-less converter run from silently losing them -- and
        # the graph builder ignores kind=barrier point features, so this is
        # provenance rather than a second enforcement mechanism.
        features.extend(barrier_features)
    if crossings_out:
        # V7.4 4A.4: the crossings artifact -- kind=crossing and kind=kerb
        # point features with their raw crossing/kerb/tactile_paving tags.
        # Absent: crossing point facts stay unavailable to the engine, which
        # is the pre-4A.4 state; the WAY-level footway=crossing promotion in
        # the main collection is unaffected.
        with open(crossings_out, "w", encoding="utf-8") as fh:
            json.dump({"type": "FeatureCollection", "features": crossing_features},
                      fh, ensure_ascii=False)
        print(f"[osm] wrote {len(crossing_features)} crossing/kerb features -> {crossings_out}",
              flush=True)
    else:
        # Old standalone contract: crossing/kerb points ride in the same
        # collection rather than being lost. The graph builder ignores
        # non-LineString features, so this is provenance, not routing data.
        features.extend(crossing_features)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(fc, fh, ensure_ascii=False)
    print(f"[osm] wrote {len(features)} features -> {out_path}", flush=True)
    if lane_attrs is not None:
        with open(lane_attrs_out, "w", encoding="utf-8") as fh:
            json.dump({"schema": "vector-lane-attrs-1",
                       "keys": list(LANE_SIDECAR_KEYS), "ways": lane_attrs},
                      fh, ensure_ascii=False, sort_keys=True)
        print(f"[osm] wrote lane attributes for {len(lane_attrs)} road ways "
              f"-> {lane_attrs_out}", flush=True)
    return len(features)


def main(argv=None):
    ap = argparse.ArgumentParser(description="OSM .osm -> Vector GeoJSON")
    ap.add_argument("path", nargs="+",
                    help="input .osm file(s); several are merged (a tiled Overpass fetch)")
    ap.add_argument("-o", "--out", default="/tmp/qatar.geojson")
    ap.add_argument("--signals-out", default=None,
                    help="write highway=traffic_signals nodes to this SEPARATE "
                         "GeoJSON artifact instead of the main collection "
                         "(V7 Stage 5)")
    ap.add_argument("--cameras-out", default=None,
                    help="write highway=speed_camera nodes to this SEPARATE "
                         "GeoJSON artifact instead of the main collection "
                         "(V7.3)")
    ap.add_argument("--barriers-out", default=None,
                    help="write barrier=* nodes to this SEPARATE GeoJSON "
                         "artifact instead of the main collection (V7.4 4A)")
    ap.add_argument("--crossings-out", default=None,
                    help="write highway=crossing / kerb nodes to this SEPARATE "
                         "GeoJSON artifact instead of the main collection "
                         "(V7.4 4A.4)")
    ap.add_argument("--relations", default=None,
                    help="optional second .osm holding type=restriction relations "
                         "(the public Overpass mirrors reject a combined query)")
    ap.add_argument("--lane-attrs-out", default=None,
                    help="write the lane bake's extra road attributes (node ids, "
                         "layer, lanes:forward/backward/both_ways) to this "
                         "SEPARATE JSON artifact; the main collection is "
                         "unchanged (V8)")
    args = ap.parse_args(argv)
    n = convert(args.path, args.out, relations_path=args.relations,
                signals_out=args.signals_out, cameras_out=args.cameras_out,
                barriers_out=args.barriers_out, crossings_out=args.crossings_out,
                lane_attrs_out=args.lane_attrs_out)
    print(f"[summary] {n} features", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
