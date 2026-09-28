"""HTTP route service for vector-routing.

Exposes the existing routing engine over HTTP using only the Python standard
library so a web map can draw routes. The service loads a GeoJSON road network
and answers route requests with a GeoJSON ``FeatureCollection`` containing a
single ``LineString`` feature.

Runtime is available via the provisioned Python toolchain (adr-0006).
"""

import argparse
import json
import math
import os
import pathlib
import socket
import urllib.parse

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from vector_auth import Auth

from .errors import EndpointTooFarError, NoRouteError, RouteError
from .eta_log import EtaErrorLog
from .foot_graph import PedestrianNetworkSplitError
from .service import RoutingService
from .speeds import WALK_SPEED_MS


DEFAULT_GRAPH_PATH = pathlib.Path(__file__).resolve().parents[2] / "routing-data" / "sample_network.geojson"


def parse_endpoint(s: str):
    """Parse a ``"LAT,LON"`` string into a ``(lat, lon)`` tuple.

    Raises ``ValueError`` when ``s`` is empty, has the wrong arity, contains
    non-numeric parts, or names a point that cannot exist.

    The last check is not pedantry. ``float()`` happily accepts ``inf`` and
    ``nan``, and both then travelled all the way into the spatial index, where
    ``int(lon / cell)`` raises ``OverflowError`` / ``ValueError`` — neither of
    which the handler catches. Measured against the live service: ``inf`` made
    the server **close the connection with no response at all**, so a client saw
    a network failure rather than "you sent a bad coordinate".

    Range is checked for the same reason and one more: latitude 95 is not a
    place, but the router treated it as one and spent **~800 ms** snapping
    across the whole graph before answering 422. Rejecting it here costs
    microseconds and gives the client an answer it can act on.
    """
    if not s:
        raise ValueError("missing 'from' or 'to' parameter")
    parts = s.split(",")
    if len(parts) != 2:
        raise ValueError("endpoint must be 'LAT,LON' with exactly two numeric parts")
    try:
        lat = float(parts[0])
        lon = float(parts[1])
    except (TypeError, ValueError):
        raise ValueError("endpoint parts must be numeric: expected 'LAT,LON'")
    if not (math.isfinite(lat) and math.isfinite(lon)):
        raise ValueError("endpoint parts must be finite numbers: expected 'LAT,LON'")
    if not (-90.0 <= lat <= 90.0):
        raise ValueError(f"latitude {lat} is out of range: expected -90..90")
    if not (-180.0 <= lon <= 180.0):
        raise ValueError(f"longitude {lon} is out of range: expected -180..180")
    return (lat, lon)


def route_to_geojson(route, from_ll, to_ll, profile) -> dict:
    """Serialize a ``Route`` to a GeoJSON ``FeatureCollection``.

    ``from_ll`` and ``to_ll`` are ``(lat, lon)`` tuples. The output geometry
    uses ``[lon, lat]`` coordinate ordering per GeoJSON conventions.
    """
    coords = [[lon, lat] for (lon, lat) in route.path]
    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": coords},
                "properties": {
                    "profile": profile,
                    "distance_km": round(route.distance_m / 1000.0, 3),
                    "duration_s": round(route.duration_s, 1) if route.duration_s is not None else None,
                    "nodes": len(route.node_keys),
                    "from": [from_ll[0], from_ll[1]],
                    "to": [to_ll[0], to_ll[1]],
                },
            }
        ],
    }


def foot_to_geojson(result, from_ll, to_ll) -> dict:
    """Serialize a ``foot_route`` result to a GeoJSON ``FeatureCollection``.

    Mirrors :func:`route_to_geojson` — one ``LineString`` feature, ``[lon, lat]``
    ordering — with three deliberate differences.

    ``distance_m`` rather than ``distance_km``
        The driving endpoints report kilometres because a drive is kilometres.
        A walk is not: "0.384 km" is a silly way to say 384 metres, and the
        client would divide it straight back. The unit follows the journey.

    ``steps_m``
        How much of the walk is ``highway=steps``. The walking model penalises
        stairs rather than banning them (see ``speeds.STEPS_SPEED_MS``), and
        that claim is only checkable from the outside if the answer says when it
        took them. It is also what a Last Mile UI needs in order to warn someone
        with a suitcase.

    ``snap`` / ``snap_max_m``
        Carried for the reason ``/navigate`` already carries them, only more so:
        a walk begins at a parking space or a mall door, and a 300 m snap is the
        difference between "your walk starts at the car" and "your walk starts
        somewhere else entirely".
    """
    route = result["route"]
    coords = [[lon, lat] for (lon, lat) in route.path]
    seg_tags = result.get("segment_tags") or []
    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": coords},
                "properties": {
                    "profile": "foot",
                    # V7.4 4B.4: the public-contract markers. ``contract_version``
                    # names the response SHAPE this payload belongs to (bumped
                    # only by an incompatible change; this contract is additive),
                    # and ``walking_profile`` states the pedestrian cost profile
                    # the walk was chosen under — distinct from ``profile``
                    # above, which is the MODE ("foot") and keeps its meaning.
                    "contract_version": result.get("contract_version", 1),
                    "walking_profile": result.get("walking_profile", "general"),
                    "distance_m": round(route.distance_m, 1),
                    "duration_s": round(route.duration_s, 1) if route.duration_s is not None else None,
                    "steps_m": round(result.get("steps_m", 0.0), 1),
                    # V7.4 4A.4: metres walked ON crossings, the routing layer
                    # exposing the recon's rule ("cross the road" needs the
                    # map to establish a crossing first). Mirrors steps_m:
                    # a number, not an instruction.
                    "crossing_m": round(result.get("crossing_m", 0.0), 1),
                    "nodes": len(route.node_keys),
                    # What each segment of the walk IS, one entry per pair of
                    # coordinates, so the client's shade model can assume the
                    # right facade instead of one class for the whole route.
                    # See FootRouter.segment_tags.
                    "classes": [t["highway"] for t in seg_tags],
                    # V7.4 4A.4: the raw pedestrian facts of each segment are
                    # exposed per-segment so the client can tell a crossing
                    # from a pavement without parsing anything. One entry per
                    # pair of coordinates, aligned with `classes`.
                    "footway": [t.get("footway") for t in seg_tags],
                    "crossing": [t.get("crossing") for t in seg_tags],
                    "lit": [t.get("lit") for t in seg_tags],
                    "enclosed": [t["enclosed"] for t in seg_tags],
                    "area": [t["area"] for t in seg_tags],
                    # V7.4 4B.1: the structured maneuver FACTS of the walk —
                    # depart / cross / stairs / turn / transition / arrive, each
                    # sourced to the graph geometry or promoted OSM data that
                    # established it, each carrying ``index`` (a vertex index on
                    # the geometry above) and ``distance_m``. No instruction
                    # prose: this is the factual layer 4B.2 turns into maneuvers
                    # and 4C into UX. Cross facts carry crossing type, kerb and
                    # (when the car graph is wired) the road crossed.
                    #
                    # V7 traffic lights adds ONE key inside a cross fact's
                    # ``crossing`` block: ``signals``, the surveyed signals
                    # standing ON that crossing by graph node identity, each
                    # ``{id, source, node, index}`` — ``index`` being the
                    # signal's own vertex on the geometry above, so a map can
                    # place it exactly. It is additive — a client that does not
                    # read it sees exactly the pre-V7 shape — and it carries no
                    # phase, cycle or state, because the source has no timing
                    # (see ``vector_routing.signals``). The list is empty for
                    # every crossing with no signal on it, which on the real
                    # Qatar bake is all but a small minority.
                    "maneuvers": result.get("maneuvers", []),
                    # V7.4 4B.2: the sparse, ordered interpretation of
                    # ``maneuvers`` — depart / cross / stairs / turn_* /
                    # continue / arrive, with dense bearing noise merged away,
                    # crossing/stair events guaranteed to survive, and the 4B.1
                    # provenance (crossing type source, crossed-road
                    # attribution) preserved. Additive: nothing above changes.
                    # V7 traffic lights: a cross maneuver's ``crossing`` block
                    # also carries ``signals`` — see the ``maneuvers`` note.
                    "maneuver_plan": result.get("maneuver_plan", []),
                    # V7.4 4B.3: the cost model's route-level summary (weighted
                    # cost, pace time, per-factor penalties, and the
                    # attribute-exposure totals: stairs/step_count, incline,
                    # crossing, poor surface, unlit, narrow width, no-sidewalk)
                    # and one weighted cost per geometry pair, aligned with
                    # ``classes``/``footway``/``crossing``/``lit`` so a client
                    # can see which segments are penalised and why. Additive:
                    # nothing above changes.
                    "cost": result.get("cost", {}),
                    "segment_cost_s": result.get("segment_cost_s", []),
                    "snap": result.get("snap", []),
                    "snap_max_m": result.get("snap_max_m", 0.0),
                    # V7.4. `snap_max_m` alone cannot be read without a
                    # threshold, and the threshold is a pedestrian decision the
                    # client should not have to re-derive: a walk whose
                    # endpoints moved 600 m is not wrong, but it does not start
                    # where the user tapped, and a UI saying "6 min walk" needs
                    # to be able to say so.
                    "snap_within_preferred": result.get("snap_within_preferred", True),
                    # Which walking network this route lives on, and its size.
                    # A route inside a 12-node component is a route around one
                    # car park; Qatar's foot graph has 2,811 components.
                    "component": result.get("component"),
                    "component_nodes": result.get("component_nodes"),
                    # Straight-line distance and the ratio to it. Phase 2 noted
                    # that a long walk and a missing crossing are
                    # indistinguishable from the distance alone; this is what
                    # separates them. Reported, never acted on -- a high ratio
                    # is frequently correct (walls, compounds, car parks).
                    #
                    # V7.4 4A: ``detour_ratio`` is now measured between the
                    # route and the STRAIGHT LINE BETWEEN THE SNAPPED points
                    # (``snap_straight_m``), because the route connects the
                    # snapped points, not the requested ones -- the old
                    # denominator made the ratio go below 1.0 whenever snapping
                    # pulled the two endpoints closer together (Festival City
                    # reported 0.68). Measured snap-to-snap it cannot read below
                    # 1.0: a path between two points is never shorter than the
                    # straight line between them. The requested-point distance
                    # is still reported (``requested_straight_m``;
                    # ``straight_m`` is kept as its alias for continuity) so
                    # nothing a client already reads loses its meaning.
                    "straight_m": result.get("straight_m"),
                    "requested_straight_m": result.get("requested_straight_m"),
                    "snap_straight_m": result.get("snap_straight_m"),
                    "route_m": result.get("route_m"),
                    "detour_ratio": result.get("detour_ratio"),
                    # The modelled pace this duration was computed from, on the
                    # wire rather than in the source. A client that wants to
                    # show "6 min walk" should be able to see what assumption
                    # produced the six, and Phase 3's shade work will want it.
                    "walk_speed_ms": WALK_SPEED_MS,
                    "from": [from_ll[0], from_ll[1]],
                    "to": [to_ll[0], to_ll[1]],
                },
            }
        ],
    }


def navigate_to_geojson(result, from_ll, to_ll, profile) -> dict:
    """Serialize a ``navigate`` result to a GeoJSON ``FeatureCollection``.

    Mirrors ``route_to_geojson``: one ``LineString`` feature whose coordinates
    are the full concatenated route in ``[lon, lat]`` order. The ``properties``
    object carries the turn-by-turn ``steps`` plus scalar ETA aggregates.
    """
    return {
        "type": "FeatureCollection",
        "features": [navigate_feature(result, from_ll, to_ll, profile)],
    }


def navigate_feature(result, from_ll, to_ll, profile) -> dict:
    """One ``navigate`` answer as a GeoJSON Feature.

    Split out of :func:`navigate_to_geojson` so that ``/navigate`` and
    ``/navigate?alternatives=1`` emit IDENTICAL per-route properties. A second
    serialiser for alternatives is how the two shapes drift, and a client that
    has to branch on which endpoint produced a route is a client that will get
    one of the branches wrong.
    """
    coords = [[lon, lat] for (lon, lat) in result["path"]]
    return (
            {
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": coords},
                "properties": {
                    "type": "route",
                    "distance_km": round(result["distance_m"] / 1000.0, 3),
                    "duration_s": round(result["duration_s"], 1),
                    "from": f"{from_ll[0]},{from_ll[1]}",
                    "to": f"{to_ll[0]},{to_ll[1]}",
                    "profile": profile,
                    # Endpoint snapping, made visible. A tap inside an airport
                    # terminal snaps ~136 m to the nearest service road, which is
                    # correct; a tap that snaps 700 m is a coverage gap. Without
                    # these fields the two are indistinguishable to the client,
                    # and a route "from somewhere else" looks like a routing bug.
                    "snap": result.get("snap", []),
                    "snap_max_m": result.get("snap_max_m", 0.0),
                    # ETA transparency: free-flow link time and junction delay
                    # reported separately, so "why is the ETA that number?" is
                    # answerable from the response instead of from the source.
                    "link_duration_s": round(result.get("link_duration_s", 0.0), 1),
                    "junction_delay_s": round(result.get("junction_delay_s", 0.0), 1),
                    "steps": result["steps"],
                    # Passed through because the client needs it: it posts this
                    # back to /eta on arrival so the ETA distribution can be
                    # split learned-vs-unlearned. Without it on the wire the
                    # split cannot be made and issue 07 stays unfalsifiable.
                    "learned_coverage": round(float(result.get("learned_coverage", 0.0)), 4),
                    "learned_segments": int(result.get("learned_segments", 0)),
                    # Present only for an alternatives request. `route_id` is
                    # what a client sends back to navigate the option it chose,
                    # which is precisely what was missing: alternatives used to
                    # be display-only because nothing identified them.
                    **({"route_id": result["route_id"]} if "route_id" in result else {}),
                    **({"primary": result["primary"]} if "primary" in result else {}),
                    **({"duration_delta_s": result["duration_delta_s"]}
                       if "duration_delta_s" in result else {}),
                    **({"label": result["label"]} if "label" in result else {}),
                    # V7 Stage 5: the signals this route passes, present only
                    # when a signal catalog was baked -- an old client and an
                    # old backend see exactly the shape they always saw. No
                    # timing field exists on the wire: the extract has none.
                    **({"signals": result["signals"]} if result.get("signals") else {}),
                    # V7.3: the cameras this route passes, same conditional
                    # key. A camera entry carries location + provenance only
                    # (maxspeed/direction); nothing on the wire claims the
                    # camera is active or that anyone will be fined.
                    **({"cameras": result["cameras"]} if result.get("cameras") else {}),
                },
            }
    )


class RouteRequestHandler(BaseHTTPRequestHandler):
    """HTTP handler that answers routing and health requests."""

    routing_service = None

    def log_message(self, *args) -> None:  # type: ignore[override]
        """Silence the default request logging."""
        return

    def _send(self, code: int, ctype: str, body) -> None:
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        for _k, _v in self.auth.cors_headers().items():
            self.send_header(_k, _v)
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, code: int, obj) -> None:
        body = json.dumps(obj).encode("utf-8")
        self._send(code, "application/json", body)

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path == "/healthz":
            self._send(200, "text/plain", b"ok")
            return

        if not self.auth.enforce(self):
            return

        if parsed.path == "/route":
            q = urllib.parse.parse_qs(parsed.query)
            from_s = q.get("from", [""])[0]
            to_s = q.get("to", [""])[0]
            # The router relaxes on TRAVEL TIME (_edge_time_weight, seconds) with an
            # admissible time-scaled heuristic, so the default label was simply
            # wrong: it advertised "shortest" for a fastest-path search. The label
            # is purely informational -- it has never selected an algorithm -- but
            # it is the first thing a client or a reviewer reads.
            profile = q.get("profile", ["fastest"])[0]
            use_traffic = q.get("traffic", ["0"])[0] in ("1", "true", "yes")
            learned = _flag(q.get("learned", [None])[0])
            band = _int_or_none(q.get("band", [None])[0])
            try:
                from_ll = parse_endpoint(from_s)
                to_ll = parse_endpoint(to_s)
                if self.routing_service is None:
                    self._send_json(503, {"error": "routing graph unavailable"})
                    return
                want_alts = q.get("alternatives", ["0"])[0] in ("1", "true", "yes")
                if want_alts:
                    # Previously this parameter was accepted and IGNORED: the
                    # engine always returned one path, so a client asking for
                    # choices silently got none.
                    from .alternatives import describe
                    n = _int_or_none(q.get("count", [None])[0]) or 3
                    routes = self.routing_service.route_alternatives(
                        (from_ll[1], from_ll[0]), (to_ll[1], to_ll[0]),
                        wanted=max(1, min(n, 4)),
                    )
                    summaries = describe(routes)
                    features = []
                    for r, s in zip(routes, summaries):
                        payload = route_to_geojson(r, from_ll, to_ll, profile)
                        feat = payload["features"][0]
                        feat["properties"].update(s)
                        features.append(feat)
                    self._send_json(200, {"type": "FeatureCollection", "features": features})
                    return
                route = self.routing_service.route(
                    (from_ll[1], from_ll[0]), (to_ll[1], to_ll[0]),
                    traffic=use_traffic, learned=learned, band=band,
                )
                self._send_json(200, route_to_geojson(route, from_ll, to_ll, profile))
            except (ValueError, KeyError) as e:
                self._send_json(400, {"error": str(e)})
            except EndpointTooFarError as e:
                # 422, not 404 or 503: the request is well-formed and the service
                # is healthy — the POINT is outside the routable area. Returning
                # the distance and the limit lets a client say something useful
                # instead of "routing failed".
                self._send_json(422, {
                    "error": "endpoint outside routable area",
                    "message": str(e),
                    "lat": e.lat, "lon": e.lon,
                    "distance_m": round(e.distance_m, 1),
                    "limit_m": e.limit_m,
                })
            except NoRouteError:
                self._send_json(404, {"error": "no route found between the requested points"})
            except RouteError as e:
                self._send_json(503, {"error": str(e)})
            return

        if parsed.path == "/navigate":
            q = urllib.parse.parse_qs(parsed.query)
            from_s = q.get("from", [""])[0]
            to_s = q.get("to", [""])[0]
            via_s = q.get("via", [""])[0]
            # The router relaxes on TRAVEL TIME (_edge_time_weight, seconds) with an
            # admissible time-scaled heuristic, so the default label was simply
            # wrong: it advertised "shortest" for a fastest-path search. The label
            # is purely informational -- it has never selected an algorithm -- but
            # it is the first thing a client or a reviewer reads.
            profile = q.get("profile", ["fastest"])[0]
            # Which language road names come back in.
            #
            # `lang=en` prefers OSM's `name:en`, which 99.5% of Qatar's named
            # roads carry and which nothing read: every instruction and route
            # label was built from `name` alone, so a driver whose phone is in
            # English was told "Continue on شارع الكورنيش" and offered a route
            # "via العروبة". Qatar's road signs are bilingual; the data always
            # was.
            #
            # The CLIENT decides, because the server has no business guessing a
            # user's reading language from their coordinates. Omitting it keeps
            # the previous behaviour exactly.
            lang = (q.get("lang", [""])[0] or "").lower()[:2] or None
            try:
                from_ll = parse_endpoint(from_s)
                to_ll = parse_endpoint(to_s)
                waypoints = []
                if via_s:
                    for part in via_s.split(";"):
                        part = part.strip()
                        if part:
                            waypoints.append(parse_endpoint(part))
                if self.routing_service is None:
                    self._send_json(503, {"error": "routing graph unavailable"})
                    return
                # Alternatives WITH steps. `/route?alternatives=1` already
                # returned several geometries, and none of them carried steps —
                # so a client could draw a choice it could not then drive. See
                # Router.navigate_alternatives.
                #
                # Waypoints are refused rather than ignored: silently dropping a
                # `via` the caller asked for would route them somewhere else and
                # look like a routing defect.
                if q.get("alternatives", ["0"])[0] in ("1", "true", "yes"):
                    if waypoints:
                        self._send_json(400, {
                            "error": "alternatives and via are mutually exclusive",
                            "message": "alternatives are not computed for multi-leg routes",
                        })
                        return
                    n = _int_or_none(q.get("count", [None])[0]) or 3
                    results = self.routing_service.navigate_alternatives(
                        (from_ll[1], from_ll[0]),
                        (to_ll[1], to_ll[0]),
                        wanted=max(1, min(n, 4)),
                        learned=_flag(q.get("learned", [None])[0]),
                        band=_int_or_none(q.get("band", [None])[0]),
                        lang=lang,
                    )
                    self._send_json(200, {
                        "type": "FeatureCollection",
                        "features": [
                            navigate_feature(r, from_ll, to_ll, profile) for r in results
                        ],
                    })
                    return
                result = self.routing_service.navigate(
                    (from_ll[1], from_ll[0]),
                    (to_ll[1], to_ll[0]),
                    waypoints=[(w[1], w[0]) for w in waypoints],
                    learned=_flag(q.get("learned", [None])[0]),
                    band=_int_or_none(q.get("band", [None])[0]),
                    lang=lang,
                )
                self._send_json(200, navigate_to_geojson(result, from_ll, to_ll, profile))
            except (ValueError, KeyError) as e:
                self._send_json(400, {"error": str(e)})
            except EndpointTooFarError as e:
                # 422, not 404 or 503: the request is well-formed and the service
                # is healthy — the POINT is outside the routable area. Returning
                # the distance and the limit lets a client say something useful
                # instead of "routing failed".
                self._send_json(422, {
                    "error": "endpoint outside routable area",
                    "message": str(e),
                    "lat": e.lat, "lon": e.lon,
                    "distance_m": round(e.distance_m, 1),
                    "limit_m": e.limit_m,
                })
            except NoRouteError:
                self._send_json(404, {"error": "no route found between the requested points"})
            except RouteError as e:
                self._send_json(503, {"error": str(e)})
            return

        if parsed.path == "/foot":
            # The pedestrian endpoint (V7 Phase 2). Same query shape as /route
            # — `from`/`to` as "LAT,LON" — because it is the same question over
            # a different edge set, and a client that has to learn a second
            # coordinate convention will get one of them wrong.
            #
            # No `traffic`, `learned` or `alternatives`: congestion and learned
            # vehicle speeds are statements about traffic, and offering a
            # parameter that is silently ignored is how
            # `/route?alternatives=1` spent a release returning one route.
            #
            # V7.4 4B.4: the one NEW query parameter is `profile` — the
            # pedestrian cost profile. Only "general" is wired; an unknown
            # name is a loud 400 listing the valid set, never silently
            # treated as general (a future profile is an additive value, and
            # a client being told it is unavailable beats a client believing
            # it asked for something general).
            q = urllib.parse.parse_qs(parsed.query)
            profile = (q.get("profile") or ["general"])[0].strip().lower() or "general"
            if profile != "general":
                self._send_json(400, {
                    "error": "unknown walking profile",
                    "requested": profile,
                    "valid_profiles": ["general"],
                    "message": "only the 'general' walking profile is wired "
                                "(V7.4 4B.4); an unwired profile is rejected, "
                                "never silently treated as general",
                })
                return
            try:
                from_ll = parse_endpoint(q.get("from", [""])[0])
                to_ll = parse_endpoint(q.get("to", [""])[0])
                if self.routing_service is None:
                    self._send_json(503, {"error": "routing graph unavailable"})
                    return
                result = self.routing_service.foot_route(
                    (from_ll[1], from_ll[0]), (to_ll[1], to_ll[0]),
                    profile=profile,
                )
                self._send_json(200, foot_to_geojson(result, from_ll, to_ll))
            except (ValueError, KeyError) as e:
                self._send_json(400, {"error": str(e)})
            except EndpointTooFarError as e:
                self._send_json(422, {
                    "error": "endpoint outside routable area",
                    "message": str(e),
                    "lat": e.lat, "lon": e.lon,
                    "distance_m": round(e.distance_m, 1),
                    "limit_m": e.limit_m,
                })
            except PedestrianNetworkSplitError as e:
                # Still a 404 -- there is genuinely no walk -- but carrying WHY
                # (V7.4). Qatar's pedestrian graph is 2,811 components with the
                # largest holding 32% of nodes, so "these are two separate
                # walking networks" is the common case rather than the exotic
                # one, and it is a statement about the map rather than about the
                # router. A client that cannot tell the two apart shows the same
                # "something went wrong" for both.
                self._send_json(404, {
                    "error": "no walking route found between the requested points",
                    "reason": "pedestrian_network_split",
                    "message": str(e),
                    "origin_snap_m": round(e.origin_snap_m, 1),
                    "destination_snap_m": round(e.destination_snap_m, 1),
                    "radius_m": e.radius_m,
                })
            except NoRouteError:
                # A real answer for a pedestrian graph: the footway network is
                # genuinely disconnected in places (a walled compound, a
                # motorway with no crossing), and saying so beats inventing a
                # path along a road nobody may walk on.
                self._send_json(404, {"error": "no walking route found between the requested points"})
            except RouteError as e:
                # Includes "no pedestrian graph loaded" — a deployment whose
                # bake predates this phase. 503 + the reason, not a 404 that
                # would read as "these two points are not connected".
                self._send_json(503, {"error": str(e)})
            return

        if parsed.path == "/footz":
            # The read-only counterpart of /overlay, /learned and /restrictions.
            # "no pedestrian graph baked" and "a graph that loaded but is empty"
            # both make every walk fail identically from outside; these numbers
            # are what separate them.
            if self.routing_service is None:
                self._send_json(503, {"error": "routing graph unavailable"})
                return
            self._send_json(200, self.routing_service.foot_status())
            return

        if parsed.path == "/overlay":
            # Read-only observability (Wave 30): how many congestion edges the
            # routing overlay currently holds. Confirms live traffic is actually
            # reaching the engine (e.g. via the web proxy fan-out).
            overlay = self.routing_service._overlay
            self._send_json(200, {
                "has_overlay": overlay is not None,
                "edge_count": len(overlay.lookup) if overlay is not None else 0,
            })
            return

        if parsed.path == "/learned":
            # The learned-speed counterpart of /overlay (issue 07). An operator
            # needs to be able to tell "no profiles loaded" from "profiles
            # loaded but inert" without reading logs.
            if self.routing_service is None:
                self._send_json(503, {"error": "routing graph unavailable"})
                return
            self._send_json(200, self.routing_service.learned_status())
            return

        if parsed.path == "/restrictions":
            # Turn restrictions, made visible. A banned turn that is silently
            # not enforced produces a route that looks completely normal, so
            # "how many are loaded and how many resolved" is the only way to
            # tell the layer is alive without driving the junction.
            if self.routing_service is None:
                self._send_json(503, {"error": "routing graph unavailable"})
                return
            self._send_json(200, self.routing_service.restrictions_status())
            return

        if parsed.path == "/eta":
            # The ETA-error distribution (issue 07 -> issue 10). This is the
            # endpoint the evolution-metrics collector reads.
            if self.routing_service is None:
                self._send_json(503, {"error": "routing graph unavailable"})
                return
            log = self.routing_service.eta_log()
            if log is None:
                self._send_json(200, {"available": False, "reason": "no ETA log configured"})
                return
            self._send_json(200, dict(log.summary(), available=True))
            return

        self._send(404, "text/plain", b"not found")

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if not self.auth.enforce(self):
            return

        if parsed.path == "/traffic":
            try:
                length = int(self.headers.get("Content-Length", "0") or "0")
                raw = self.rfile.read(length) if length else b"{}"
                body = json.loads(raw.decode("utf-8") or "{}")
                segments = body.get("segments") or []
                if not isinstance(segments, list):
                    self._send_json(400, {"error": "segments must be a list"})
                    return
                ingested = self.routing_service.ingest_traffic(segments)
                self._send_json(200, {
                    "status": "ok",
                    "ingested_edges": ingested,
                    "has_overlay": self.routing_service._overlay is not None,
                })
            except (ValueError, KeyError) as e:
                self._send_json(400, {"error": str(e)})
            except Exception as e:  # pragma: no cover - defensive
                self._send_json(500, {"error": str(e)})
            return

        if parsed.path == "/eta":
            # POST /eta {"predicted_s":..., "observed_s":..., "coverage":...}
            # Called on navigation completion. Issue 07's last box: without this
            # the whole effort is unfalsifiable — there is no way to tell whether
            # learned routes predict better than unlearned ones.
            try:
                length = int(self.headers.get("Content-Length", "0") or "0")
                raw = self.rfile.read(length) if length else b"{}"
                body = json.loads(raw.decode("utf-8") or "{}")
                predicted = body.get("predicted_s")
                observed = body.get("observed_s")
                if not isinstance(predicted, (int, float)) or not isinstance(observed, (int, float)):
                    self._send_json(400, {"error": "predicted_s and observed_s must be numbers"})
                    return
                if float(predicted) <= 0 or float(observed) <= 0:
                    self._send_json(400, {"error": "predicted_s and observed_s must be positive"})
                    return
                coverage = body.get("coverage", 0.0)
                coverage = float(coverage) if isinstance(coverage, (int, float)) else 0.0
                distance = body.get("distance_m")
                distance = float(distance) if isinstance(distance, (int, float)) else None
                sample = self.routing_service.record_eta(
                    float(predicted), float(observed), coverage, distance)
                self._send_json(200, {
                    "status": "ok",
                    "abs_pct_error": round(sample.abs_pct_error, 3),
                    "is_learned": sample.is_learned,
                })
            except RouteError as e:
                self._send_json(503, {"error": str(e)})
            except (ValueError, KeyError) as e:
                self._send_json(400, {"error": str(e)})
            except Exception as e:  # pragma: no cover - defensive
                self._send_json(500, {"error": str(e)})
            return

        self._send(404, "text/plain", b"not found")


def _flag(raw):
    """Parse a tri-state query flag: ``None`` means "use the server default"."""
    if raw is None or raw == "":
        return None
    return str(raw).lower() in ("1", "true", "yes", "on")


def _int_or_none(raw):
    try:
        return int(raw) if raw not in (None, "") else None
    except (TypeError, ValueError):
        return None


def default_signals_path(graph_path: str):
    """The signal catalog that sits beside a car graph, or ``None``.

    ``bootstrap.sh`` bakes ``<region>_signals.geojson`` into the same volume
    as the roads network (V7 Stage 5), and docker-compose passes only the
    roads file as ``--graph``. Deriving the signals path from it means the
    catalog is picked up by the existing deployment with NO compose change and
    no new environment variable to forget -- the same trick
    :func:`default_foot_graph_path` uses.

    Returns None when the sibling is absent, which is every deployment baked
    before this phase and is a normal state, not an error: routing carries no
    ``signals`` key and is byte-for-byte its old self.
    """
    if not graph_path or not graph_path.endswith("_roads.geojson"):
        return None
    sibling = graph_path[: -len("_roads.geojson")] + "_signals.geojson"
    return sibling if os.path.exists(sibling) else None


def default_cameras_path(graph_path: str):
    """The camera catalog that sits beside a car graph, or ``None``.

    ``bootstrap.sh`` bakes ``<region>_cameras.geojson`` into the same volume
    as the roads network (V7.3), and docker-compose passes only the roads
    file as ``--graph`` -- the same convention as :func:`default_signals_path`,
    so a deployment picks the cameras up with no compose change.

    Returns None when the sibling is absent, which is every deployment baked
    before V7.3 and is a normal state: routing carries no ``cameras`` key.
    """
    if not graph_path or not graph_path.endswith("_roads.geojson"):
        return None
    sibling = graph_path[: -len("_roads.geojson")] + "_cameras.geojson"
    return sibling if os.path.exists(sibling) else None


def default_foot_graph_path(graph_path: str):
    """The pedestrian graph that sits beside a car graph, or ``None``.

    ``bootstrap.sh`` bakes ``<region>_roads.geojson`` and ``<region>_foot.geojson``
    into the same volume, and docker-compose passes only the first as
    ``--graph``. Deriving the second from it means the pedestrian graph is
    picked up by the existing deployment with NO compose change and no new
    environment variable to forget — the same trick the geocoder already uses to
    find ``<index>_places.geojson`` beside its index.

    Returns None when the sibling is absent, which is every deployment baked
    before this phase and is a normal state, not an error.
    """
    if not graph_path or not graph_path.endswith("_roads.geojson"):
        return None
    sibling = graph_path[: -len("_roads.geojson")] + "_foot.geojson"
    return sibling if os.path.exists(sibling) else None


def default_barriers_path(foot_graph_path: str):
    """The barrier catalog that sits beside a foot graph, or ``None``.

    ``bootstrap.sh`` bakes ``<region>_barriers.geojson`` into the same volume
    as the road and foot networks (V7.4 4A), and docker-compose passes only
    the roads file as ``--graph``. It is derived from the FOOT graph path, not
    the roads path, because a barrier is a statement about the walking network
    (car routing never reads it) — tying it to the foot graph means the two
    can never be a bake apart, which is the same pairing the restrictions
    enjoy with the roads file they constrain.

    Returns None when the sibling is absent, which is every deployment baked
    before this phase and is a normal state: the walking graph is not severed
    and every walk is byte-for-byte its old self.
    """
    if not foot_graph_path or not foot_graph_path.endswith("_foot.geojson"):
        return None
    sibling = foot_graph_path[: -len("_foot.geojson")] + "_barriers.geojson"
    return sibling if os.path.exists(sibling) else None


def default_crossings_path(foot_graph_path: str):
    """The crossings artifact that sits beside a foot graph, or ``None``.

    ``bootstrap.sh`` bakes ``<region>_crossings.geojson`` into the same volume
    as the road and foot networks (V7.4 4A.4), and docker-compose passes only
    the roads file as ``--graph``. Like ``default_barriers_path`` it is derived
    from the FOOT graph path so the two can never be a bake apart.

    Returns None when the sibling is absent, which is every deployment baked
    before 4A.4 and is a normal state: ``FootRouter`` then builds crossing
    facts from the walked edges' own ``footway=crossing``/``crossing=*`` props,
    with no type/kerb enrichment.
    """
    if not foot_graph_path or not foot_graph_path.endswith("_foot.geojson"):
        return None
    sibling = foot_graph_path[: -len("_foot.geojson")] + "_crossings.geojson"
    return sibling if os.path.exists(sibling) else None


def make_server(port: int, service, host: str = "0.0.0.0") -> ThreadingHTTPServer:
    """Create a threaded HTTP server bound to ``host:port`` with ``service`` injected."""
    RouteRequestHandler.routing_service = service
    RouteRequestHandler.auth = Auth()
    return ThreadingHTTPServer((host, port), RouteRequestHandler)


def main(argv=None) -> None:
    """CLI entrypoint: load a graph and serve routes over HTTP."""
    parser = argparse.ArgumentParser(description="vector-routing HTTP route service")
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--graph", default=None, help="path to a GeoJSON road network")
    parser.add_argument("--learned-facts", default=None,
                        help="path to learned_speed.json from vector-learning (issue 07)")
    parser.add_argument("--eta-log", default=None,
                        help="path to the ETA-error log (issue 07 -> issue 10)")
    parser.add_argument("--foot-graph", default=None,
                        help="path to a pedestrian GeoJSON network for /foot "
                             "(default: <region>_foot.geojson beside --graph)")
    parser.add_argument("--barriers", default=None,
                        help="path to the baked <region>_barriers.geojson catalog "
                             "(V7.4; default: <region>_barriers.geojson beside "
                             "--foot-graph)")
    parser.add_argument("--crossings", default=None,
                        help="path to the baked <region>_crossings.geojson artifact "
                             "(V7.4 4A.4; default: <region>_crossings.geojson beside "
                             "--foot-graph) — the type/kerb source for crossing "
                             "maneuver facts (V7.4 4B.1)")
    parser.add_argument("--signals", default=None,
                        help="path to the baked <region>_signals.geojson catalog "
                             "(V7 Stage 5; default: <region>_signals.geojson "
                             "beside --graph)")
    parser.add_argument("--cameras", default=None,
                        help="path to the baked <region>_cameras.geojson catalog "
                             "(V7.3; default: <region>_cameras.geojson beside --graph)")
    args = parser.parse_args(argv)

    graph_path = args.graph or os.environ.get("ROUTING_GRAPH") or str(DEFAULT_GRAPH_PATH)

    bus = None
    bus_url = os.environ.get("VECTOR_BUS_URL")
    if bus_url:
        from vector_bus_client import NetworkBusClient

        bus = NetworkBusClient(bus_url)
    else:
        from vector_bus_client import LocalBus

        bus = LocalBus()

    service = RoutingService.from_geojson(graph_path, bus=bus)
    if bus is not None:
        # Navigate requests: routing answers tasks on #tasks.
        service.start_bus_consumer()
        # Live traffic: routing consumes TRAFFIC_CONGESTION on #broadcast and
        # reroutes around congestion (Wave 26c/26d).
        service.start_traffic_consumer()

    # Learned speed profiles (issue 07). Both are opt-in by environment so a
    # deployment that has learned nothing yet behaves exactly as before.
    learned_path = args.learned_facts or os.environ.get("VECTOR_LEARNED_FACTS")
    if learned_path:
        loaded = service.load_learned_facts(learned_path)
        print(f"[routing] learned speed profiles: {loaded} edge-hour buckets "
              f"from {learned_path}", flush=True)
    if os.environ.get("VECTOR_LEARNED_ENABLED", "1") not in ("1", "true", "yes"):
        service.set_learned_enabled(False)
        print("[routing] learned layer DISABLED by VECTOR_LEARNED_ENABLED", flush=True)

    # The pedestrian graph (V7 Phase 2). Explicit flag, then environment, then
    # the sibling of the car graph. Absent at all three -> /foot answers 503
    # with the reason and every other endpoint is untouched.
    foot_path = (args.foot_graph
                 or os.environ.get("VECTOR_FOOT_GRAPH")
                 or default_foot_graph_path(graph_path))
    if foot_path:
        # The barrier catalog (V7.4 4A): explicit flag, then environment, then
        # the sibling of the foot graph -- the same ladder, scoped to the
        # pedestrian network it constrains. Absent at all three -> the walking
        # graph is loaded unsevered and walks are exactly as they were before
        # barriers existed, which is a valid state.
        barriers_path = (args.barriers
                         or os.environ.get("VECTOR_BARRIERS")
                         or default_barriers_path(foot_path))
        crossings_path = (args.crossings
                          or os.environ.get("VECTOR_CROSSINGS")
                          or default_crossings_path(foot_path))
        edges = service.load_foot_graph(foot_path, barriers_path=barriers_path,
                                        crossings_path=crossings_path)
        print(f"[routing] pedestrian graph: {edges} edges from {foot_path};", flush=True)
        print(f"[routing]   crossings artifact: "
              f"{service.foot_status()['crossings']['features']} point facts",
              flush=True)
    else:
        print("[routing] no pedestrian graph found; /foot will report unavailable",
              flush=True)

    # The signal catalog (V7 Stage 5). Explicit flag, then environment, then
    # the sibling of the car graph -- the same ladder the foot graph climbs.
    # Absent at all three -> no signals key on /navigate, exactly the old
    # backend, and that is a valid state.
    signals_path = (args.signals
                    or os.environ.get("VECTOR_SIGNALS")
                    or default_signals_path(graph_path))
    if signals_path:
        n = service.load_signals(signals_path)
        print(f"[routing] signal catalog: {n} signals from {signals_path}", flush=True)
    else:
        print("[routing] no signal catalog; /navigate will carry no signals key",
              flush=True)

    # The camera catalog (V7.3). Same ladder, same rule.
    cameras_path = (args.cameras
                    or os.environ.get("VECTOR_CAMERAS")
                    or default_cameras_path(graph_path))
    if cameras_path:
        n = service.load_cameras(cameras_path)
        print(f"[routing] camera catalog: {n} cameras from {cameras_path}", flush=True)
    else:
        print("[routing] no camera catalog; /navigate will carry no cameras key",
              flush=True)

    eta_path = args.eta_log or os.environ.get("VECTOR_ETA_LOG")
    if eta_path:
        service.set_eta_log(EtaErrorLog(eta_path))
        print(f"[routing] ETA error log at {eta_path}", flush=True)

    server = make_server(args.port, service)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
