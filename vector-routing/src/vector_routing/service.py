"""Routing service facade for vector-routing."""

import json
import logging
import os
import time
from typing import Any, Dict, List, Optional, Tuple

from .errors import RouteError
from .graph import RoutingGraph
from .algorithms import build_graph_from_features
from .eta_log import EtaErrorLog
from .foot_graph import FootRouter, build_foot_graph
from .pedestrian_plan import (
    MIN_MANEUVER_SPACING_M,
    SLIGHT_SUPPRESSION_RADIUS_M,
    WIGGLE_CLUSTER_M,
    WIGGLE_NET_DEG,
)
from .speeds import STEPS_SPEED_MS, WALK_SPEED_MS
from .learned_overlay import (
    CompiledLearnedOverlay,
    CompiledLearnedView,
    LearnedSpeedOverlay,
)
from .restrictions import TurnRestrictions
from .router import Router
from .camera_catalog import CameraCatalog
from .signals import SignalCatalog, SignalIndex
from .barriers import BarrierCatalog, apply_barriers
from .pedestrian_maneuvers import CrossingCatalog
from .traffic_overlay import TrafficOverlay, OverlayView
from .bus_envelope import (
    ROUTING_AGENT_ADDRESS,
    is_navigate_request,
    make_navigation_result,
    is_traffic_congestion,
)

logger = logging.getLogger("vector_routing.service")


# V7.4 4B.4: the public /foot contract. The version is a marker of the
# response SHAPE (which fields exist and what they mean); it is bumped only on
# an incompatible change, which this contract is designed never to need — the
# rule here is additive-only, and the existing fields' meanings are treated as
# frozen (see foot_status().contract and the stage doc).
FOOT_CONTRACT_VERSION = 1

#: The ONLY wired pedestrian cost profile (4B.4). ``general`` is a
#: shortest-first walk under the documented physical costs (speeds,
#: pedestrian_cost module). A future profile is a NEW value in this tuple
#: plus its cost model wiring — until then, any other name is rejected, never
#: silently mapped to general. ``with_preferences()`` in pedestrian_cost is
#: tested groundwork, deliberately NOT exposed as a routed profile (no
#: comfort/accessibility product claim).
WALKING_PROFILES = ("general",)


HOURS_PER_WEEK = 168  # kept for backward-compat; new code uses BANDS_PER_WEEK
BANDS_PER_WEEK = 8   # adr-0067: coarsened time-of-week bands, agreed with vector-learning

# Local-time configuration for the band boundaries. MUST match
# vector-learning's aggregate.py — traffic follows local clocks and local working
# weeks, not UTC, and a band exists to group traffic that behaves alike.
# Defaults are Qatar: UTC+3, Friday-Saturday weekend (Monday=0).
UTC_OFFSET_HOURS = float(os.environ.get("VECTOR_WEEK_UTC_OFFSET_H", "3"))
WEEKEND_DAYS = frozenset(
    int(d) for d in os.environ.get("VECTOR_WEEKEND_DAYS", "4,5").split(",") if d.strip()
)


def _endpoint_ll(ep: Any) -> Tuple[float, float]:
    """Return ``(lon, lat)`` for an endpoint (tuple or ``{lon,lat}`` dict)."""
    if isinstance(ep, dict):
        return (float(ep["lon"]), float(ep["lat"]))
    return (float(ep[0]), float(ep[1]))


def time_band(t_ms: Optional[int] = None) -> int:
    """Map a ms timestamp to a 0..``BANDS_PER_WEEK-1`` time-of-week band.

    Must agree bucket-for-bucket with ``vector_learning.aggregate.time_band``
    — the two repos never import each other (ADR-0003), so this is duplicated
    deliberately and pinned by ``TimeBandAgreementTest``. A disagreement would
    silently apply the wrong band's speeds.

    **Bands are LOCAL time.** Evaluated in UTC against Qatar's +3 offset, local
    08:00 — peak morning commute — landed in the band labelled "weekday early /
    overnight", sharing a bucket with local 03:00. Averaging a rush-hour crawl
    with 3 a.m. free-flow yields a speed wrong for both, so the coarsening would
    have made ETAs worse than the OSM defaults exactly when accuracy matters.
    Likewise the weekend: Qatar's is Friday-Saturday, so a Sat-Sun assumption
    filed Friday leisure traffic as a weekday and Sunday commuting as weekend.

    ``VECTOR_WEEK_UTC_OFFSET_H`` and ``VECTOR_WEEKEND_DAYS`` must be set to the
    same values here and in vector-learning; changing them re-keys every
    speed_profile fact and is a migration, not a config tweak.
    """
    stamp = int(time.time() * 1000) if t_ms is None else int(t_ms)
    if stamp <= 0:
        return 0
    secs = stamp // 1000 + int(UTC_OFFSET_HOURS * 3600)
    day_of_week = (secs // 86400) % 7        # epoch 1970-01-01 = Thursday
    mon_first = (day_of_week + 3) % 7         # shift so Monday = 0
    hour = (secs % 86400) // 3600
    is_weekday = mon_first not in WEEKEND_DAYS

    _BANDS = [
        (0,  6,  True),   # 0: weekday early
        (6,  10, True),   # 1: weekday AM peak
        (10, 14, True),   # 2: weekday midday
        (14, 19, True),   # 3: weekday PM peak
        (19, 24, True),   # 4: weekday evening
        (6,  18, False),  # 5: weekend day
        (18, 24, False),  # 6: weekend evening
        (0,  6,  False),  # 7: weekend night
    ]
    for band, (start, end, weekday_only) in enumerate(_BANDS):
        if weekday_only and not is_weekday:
            continue
        if (not weekday_only) and is_weekday:
            continue
        if start <= hour < end:
            return band
    return 0


def read_learned_facts(path: str) -> List[Dict[str, Any]]:
    """Read a ``learned_speed.json`` export written by ``vector-learning``.

    File-based integration (ADR-0003): routing never imports the learning repo.
    A missing or corrupt export yields ``[]`` — the router must start and serve
    correctly when nothing has ever been learned, which is also its state on
    day one.
    """
    if not path or not os.path.exists(path):
        return []
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (OSError, ValueError):
        logger.warning("learned-speed export unreadable, continuing without it: %s", path)
        return []
    if isinstance(doc, list):
        return [f for f in doc if isinstance(f, dict)]
    if isinstance(doc, dict) and isinstance(doc.get("facts"), list):
        return [f for f in doc["facts"] if isinstance(f, dict)]
    return []


class RoutingService:
    """Facade over a `RoutingGraph`: build, route, and health-check."""

    def __init__(self, graph: RoutingGraph | None = None, bus: Any = None,
                 restrictions: Optional[TurnRestrictions] = None) -> None:
        self._graph = graph if graph is not None else RoutingGraph()
        # Turn restrictions come from the graph FILE, not from a separate
        # source, so that the restrictions and the edges they constrain can
        # never be a version apart. A graph baked before restrictions existed
        # simply carries none, and the router is then exactly its old self.
        self._restrictions = restrictions
        self._router = Router(self._graph, restrictions=restrictions)
        self._bus = bus
        self._consumer_id: Optional[str] = None
        # Live traffic overlay (Wave 26c). When set (via set_traffic_overlay or
        # start_traffic_consumer), navigate() penalizes congested edges.
        self._overlay: Optional[TrafficOverlay] = None
        # Learned speed profiles (issue 07). ``_learned_enabled`` is the
        # one-flag rollback the ticket requires: flip it and the router is
        # byte-for-byte the pre-learning router again.
        self._learned: Optional[LearnedSpeedOverlay] = None
        # The same overlay resolved against this graph's node keys. Compiled
        # once per load because the alternative — resolving per relaxed edge —
        # measured +242% on /route over the full Qatar graph.
        self._learned_compiled: Optional[CompiledLearnedOverlay] = None
        self._learned_enabled: bool = True
        self._eta_log: Optional[EtaErrorLog] = None
        # The pedestrian graph (V7 Phase 2). Optional and loaded separately —
        # see load_foot_graph. None means /foot reports why it cannot answer.
        self._foot_graph: Optional[RoutingGraph] = None
        self._foot_router: Optional[FootRouter] = None
        # The baked signal catalog (V7 Stage 5). Optional and empty-capable --
        # a deployment without one routes exactly as before and the /navigate
        # reply simply carries no signals key.
        self._signals: Optional[SignalCatalog] = None
        # The baked camera catalog (V7.3). Same rule as signals: optional and
        # empty-capable; absent means no cameras key on /navigate.
        self._cameras: Optional[CameraCatalog] = None
        # The baked barrier catalog (V7.4 4A). Optional and empty-capable:
        # absent means the walking graph is not severed and every walk is
        # exactly as it was before barriers existed.
        self._barriers: Optional[BarrierCatalog] = None
        # The baked crossings artifact (V7.4 4A.4), loaded since 4B.1 as the
        # type/kerb source behind crossing facts. Optional and empty-capable:
        # absent means cross facts carry only what the walked edges promoted.
        self._crossings: Optional[CrossingCatalog] = None
        self._barrier_stats: Dict[str, Any] = {}

    @classmethod
    def from_geojson(cls, path: str, bus: Any = None) -> "RoutingService":
        """Build a service from a GeoJSON file (FeatureCollection or feature list)."""
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict) and "features" in data:
            features = data["features"]
        else:
            features = data
        graph = build_graph_from_features(features)
        restrictions = TurnRestrictions.from_feature_collection(data)
        # Restrictions are resolved in COORDINATE space, before the graph exists
        # and without knowing which ways survived the car-routability filter.
        # Pruning against the built graph is what stops an `only_*` whose
        # permitted exit was filtered out from closing the junction entirely.
        resolved = len(restrictions)
        pruned = restrictions.prune_to_graph(graph)
        if any(pruned.values()):
            logger.info("turn restrictions pruned to the graph: %s", pruned)
        declared = len(data["turn_restrictions"]) if isinstance(data, dict) and data.get("turn_restrictions") else 0
        if declared and len(restrictions) == 0:
            # The file HAD restrictions and none are enforceable. The two ways
            # that happens need different fixes, so they are reported
            # differently: nothing PARSED points at the converter or coordinate
            # rounding, while everything being PRUNED points at a restriction
            # file and a graph baked from different data. Both are invisible
            # from a route — every turn is simply allowed again.
            if resolved == 0:
                logger.warning(
                    "graph file carries %d turn restrictions but none resolved — "
                    "the restriction layer is inert (converter or key mismatch?)",
                    declared)
            else:
                logger.warning(
                    "graph file carries %d turn restrictions, %d resolved, and all "
                    "were pruned as unrepresentable on this graph — the restriction "
                    "layer is inert (restrictions and graph baked from different data?)",
                    declared, resolved)
        logger.info("turn restrictions: %s", restrictions.summary())
        return cls(graph, bus=bus, restrictions=restrictions)

    def route_alternatives(self, origin: Any, destination: Any, wanted: int = 2):
        """Up to `wanted` distinct routes, best first (see alternatives.py)."""
        return self._router.route_alternatives(origin, destination, wanted=wanted)

    def route(
        self,
        origin: Any,
        destination: Any,
        traffic: bool = False,
        learned: Optional[bool] = None,
        band: Optional[int] = None,
    ):
        """Compute a route between two endpoints (delegates to the internal router).

        When ``traffic`` is true and a congestion overlay is active
        (Wave 26c / Wave 30), the search penalizes congested edges so the
        returned path avoids heavy congestion where an alternative exists.

        When learned speed profiles are loaded they are applied on top, which is
        the composition issue 07 specifies: the learned profile replaces the
        *baseline* assumption for the edge, live congestion then scales that
        baseline, and the two multiply. Pass ``learned=False`` to bypass the
        learned layer for one request.
        """
        if not self._graph.nodes():
            raise RouteError("cannot route: graph has no nodes")

        view = self._search_view(traffic=traffic, learned=learned, band=band)
        if view is None:
            return self._router.route(origin, destination)

        o = _endpoint_ll(origin)
        d = _endpoint_ll(destination)
        src = self._graph.nearest_node(o[0], o[1])
        tgt = self._graph.nearest_node(d[0], d[1])
        return self._router.route_by_node(src, tgt, graph=view)

    def _search_view(
        self,
        *,
        traffic: bool = False,
        learned: Optional[bool] = None,
        band: Optional[int] = None,
    ):
        """Build the layered search view, or ``None`` to use the base graph.

        Layering order is deliberate and is the documented composition rule:
        ``LearnedSpeedView(OverlayView(base, traffic), learned)``. Live traffic
        is the outer measurement of *now*; learned speed sets what the road is
        *typically* like. Neither overwrites the other — their factors multiply,
        so a road that is typically slow and currently jammed is penalized twice,
        which is correct.
        """
        use_traffic = bool(traffic) and self._overlay is not None
        use_learned = (
            (self._learned_enabled if learned is None else bool(learned))
            and self._learned is not None
            and len(self._learned) > 0
        )
        if not use_traffic and not use_learned:
            return None

        view: Any = self._graph
        if use_traffic:
            view = OverlayView(view, self._overlay)
        if use_learned:
            view = CompiledLearnedView(
                view, self._learned_compiled, self._resolve_band(band)
            )
        return view

    def _resolve_band(self, band: Optional[int]) -> int:
        return time_band() if band is None else int(band) % BANDS_PER_WEEK

    # ---- signal catalog (V7 Stage 5) -------------------------------------

    def load_signals(self, path: str) -> int:
        """Load the baked ``<region>_signals.geojson`` catalog.

        Returns the number of signals loaded. Zero is a normal, valid state
        (the file exists and is empty, or did not exist) -- and the router is
        then exactly its pre-Stage-5 self: the ``signals`` key is absent from
        the /navigate reply.
        """
        catalog = SignalCatalog.from_path(path)
        self._signals = catalog
        self._router.set_signals(catalog)
        # V7 traffic lights: the same catalog, indexed against the PEDESTRIAN
        # graph, so a walked crossing can cite the signals standing on its own
        # geometry. Built against the foot graph rather than the car one because
        # a walker crosses footways: a signal that is a car-graph vertex but not
        # a foot-graph one is not something a walk can be said to step over.
        #
        # This is why the load order matters. The service loads the car graph,
        # then the foot graph, then this catalog — so the foot router already
        # exists here and is re-bound rather than rebuilt. A deployment with no
        # foot graph is unaffected: there is nothing to bind.
        if self._foot_router is not None and self._foot_graph is not None:
            self._foot_router.set_signals(
                SignalIndex.from_catalog(catalog, self._foot_graph))
        if len(catalog) > 0:
            logger.info("signal catalog: %d signals from %s", len(catalog), path)
        return len(catalog)

    def signals_status(self) -> Dict[str, Any]:
        """Read-only observability for the signal catalog."""
        out: Dict[str, Any] = {"signals": len(self._signals) if self._signals else 0}
        # V7 traffic lights: the walking association, when a foot graph is
        # loaded. Present only then — a deployment that routes no walks has no
        # walking association, and reporting a zero would read as "matched
        # nothing" rather than "not applicable".
        if self._foot_router is not None:
            out["walking"] = self._foot_router.signal_census()
        return out

    # ---- camera catalog (V7.3) ------------------------------------------

    def load_cameras(self, path: str) -> int:
        """Load the baked ``<region>_cameras.geojson`` catalog.

        Returns the number of cameras loaded. Zero is a normal, valid state
        (the file exists and is empty, or did not exist) -- and the router is
        then exactly its pre-V7.3 self: the ``cameras`` key is absent from
        the /navigate reply.
        """
        catalog = CameraCatalog.from_path(path)
        self._cameras = catalog
        self._router.set_cameras(catalog)
        if len(catalog) > 0:
            # The type census is the defensibility number: it is the count of
            # camera claims the SOURCE stated, by type. An `unknown` count
            # above zero is not a defect here -- it is cameras the client will
            # carry as facts and never announce.
            logger.info("camera catalog: %d cameras from %s (%s)",
                        len(catalog), path, catalog.type_census())
        return len(catalog)

    def cameras_status(self) -> Dict[str, Any]:
        """Read-only observability for the camera catalog."""
        out: Dict[str, Any] = {"cameras": len(self._cameras) if self._cameras else 0}
        if self._cameras is not None and len(self._cameras) > 0:
            out["types"] = self._cameras.type_census()
        return out

    # ---- learned speed profiles (issue 07) ------------------------------

    def set_learned_overlay(self, overlay: Optional[LearnedSpeedOverlay]) -> None:
        """Inject a learned-speed overlay directly (tests / composition roots).

        Compilation against the graph happens here, once, rather than on the
        request path.
        """
        self._learned = overlay
        self._learned_compiled = CompiledLearnedOverlay.compile(self._graph, overlay)
        if overlay is not None and len(overlay) > 0 and len(self._learned_compiled) == 0:
            logger.warning(
                "learned overlay has %d buckets but none resolved onto this graph — "
                "the learned layer will not affect routing",
                len(overlay),
            )

    def set_learned_enabled(self, enabled: bool) -> None:
        """The one-flag rollback. Disabled means the pre-learning router exactly."""
        self._learned_enabled = bool(enabled)

    def load_learned_facts(self, path: str) -> int:
        """Load promoted ``speed_profile`` facts from an export file.

        Returns the number of ``(edge, hour)`` buckets loaded. Zero is a normal,
        valid state — not an error — and is what a fresh deployment reports.
        """
        facts = read_learned_facts(path)
        overlay = LearnedSpeedOverlay.from_facts(facts)
        self.set_learned_overlay(overlay)
        if facts and len(overlay) == 0:
            # Loud, because it is the silent-no-op failure mode: the export
            # existed and had facts, yet not one was placeable on the graph.
            logger.warning(
                "learned-speed export had %d facts but produced 0 overlay edges "
                "(missing payload geometry?) — the learned layer is inert",
                len(facts),
            )
        logger.info("learned speed overlay loaded: %d edge-hour buckets from %s",
                    len(overlay), path)
        return len(overlay)

    def learned_status(self) -> Dict[str, Any]:
        """Read-only observability, mirroring the ``/overlay`` traffic endpoint."""
        overlay = self._learned
        coverage = overlay.coverage() if overlay is not None else {"edges": 0, "edge_bands": 0}
        compiled = self._learned_compiled
        return {
            "has_learned_overlay": overlay is not None,
            "enabled": self._learned_enabled,
            "edge_count": coverage["edges"],
            "edge_band_count": coverage["edge_bands"],
            "time_band": time_band(),
            # "loaded but not resolved onto the graph" is a distinct failure
            # from "nothing loaded", and only these numbers distinguish them.
            "compiled": compiled.stats() if compiled is not None else None,
        }

    def learned_speed_hook(self, band: Optional[int] = None):
        """A ``speed_for_edge`` callable for ``Router.navigate``, or ``None``.

        Returns the observed km/h for an edge so the reported ETA reflects the
        learned speed rather than the OSM limit. ``None`` when the learned layer
        is off or empty, which keeps ``navigate`` on its exact previous path.
        """
        if not self._learned_enabled or self._learned is None or len(self._learned) == 0:
            return None
        overlay = self._learned
        graph = self._graph
        bucket = self._resolve_band(band)

        def hook(node_a: str, node_b: str, _props: Any) -> Optional[float]:
            if not (graph.has_node(node_a) and graph.has_node(node_b)):
                return None
            return overlay.speed_for(graph.node_coord(node_a), graph.node_coord(node_b), bucket)

        return hook

    # ---- ETA error log (issue 07 -> issue 10) ---------------------------

    def set_eta_log(self, log: Optional[EtaErrorLog]) -> None:
        self._eta_log = log

    def eta_log(self) -> Optional[EtaErrorLog]:
        return self._eta_log

    def record_eta(self, predicted_s: float, observed_s: float, coverage: float = 0.0,
                   distance_m: Optional[float] = None):
        """Record a completed navigation's predicted-vs-observed duration.

        Issue 07's last acceptance box and the input to issue 10's falsifiable
        claim. Raises ``RouteError`` when no log is configured, rather than
        accepting the sample and dropping it. ``distance_m`` is optional and
        anonymous (route length, not a location) — it lets ETA calibration
        judge errors per kilometre.
        """
        if self._eta_log is None:
            raise RouteError("no ETA log configured (set VECTOR_ETA_LOG)")
        return self._eta_log.record(predicted_s, observed_s, coverage, distance_m)

    def ingest_traffic(self, segments: List[Dict[str, Any]]) -> int:
        """Populate (or merge into) the live congestion overlay from segments.

        Returns the number of congestion edges ingested. This is the
        HTTP-ingest path used by the web composition root (Wave 30), which is
        decoupled from the bus broadcast used in production (ADR-0003: the web
        proxy forwards traffic to routing over HTTP rather than wiring a broker
        between the two engines in the demo stack).
        """
        incoming = TrafficOverlay.from_segments(segments or [])
        if self._overlay is None:
            self._overlay = incoming
        else:
            self._overlay.merge(incoming)
        return len(self._overlay._by_pair)

    def navigate(self, origin: Any, destination: Any, waypoints=None,
                 learned: Optional[bool] = None, band: Optional[int] = None,
                 lang: Optional[str] = None):
        """Compute a multi-leg turn-by-turn route with ETA (delegates to the router).

        Searches on the BASE graph (fast, deterministic). The experimental
        congestion overlay (Wave 26c) is intentionally NOT applied here: it
        re-wraps every neighbour list on each A* relaxation and makes the
        search explode (46s vs 0.5s on the Doha graph, and worse on Qatar).
        Traffic-aware rerouting remains available via ``/route?traffic=1`` and
        will be re-introduced for ``/navigate`` once the overlay view is
        profiled (see ADR-0027 / issue tracker). Keeping ``/navigate`` on the
        base graph guarantees the endpoint always returns turn-by-turn steps.

        Learned speed profiles (issue 07) are applied **to the ETA only**, via
        ``Router.navigate``'s ``speed_for_edge`` hook — a per-edge dict lookup on
        the chosen path, not a re-wrap of every relaxation. That is what keeps
        this endpoint fast while still quoting an observed travel time: the
        measured cost is a handful of lookups over the final path rather than
        over the whole explored frontier.
        """
        if not self._graph.nodes():
            raise RouteError("cannot navigate: graph has no nodes")
        return self._router.navigate(
            origin, destination, waypoints,
            lang=lang,
            speed_for_edge=(
                self.learned_speed_hook(band)
                if (self._learned_enabled if learned is None else bool(learned))
                else None
            ),
        )

    def navigate_alternatives(self, origin: Any, destination: Any, wanted: int = 3,
                              learned: Optional[bool] = None, band: Optional[int] = None,
                              lang: Optional[str] = None):
        """Several full turn-by-turn routes, best first (delegates to the router).

        Same base-graph reasoning as :meth:`navigate`: no congestion overlay, so
        the search stays fast and every option comes back with steps. Learned
        speeds apply to the reported ETA of each option through the same
        per-edge hook, which matters more here than for a single route — a
        driver comparing "12 min" against "14 min" is comparing exactly the
        numbers the learned layer exists to correct.
        """
        if not self._graph.nodes():
            raise RouteError("cannot navigate: graph has no nodes")
        return self._router.navigate_alternatives(
            origin, destination, wanted=wanted, lang=lang,
            speed_for_edge=(
                self.learned_speed_hook(band)
                if (self._learned_enabled if learned is None else bool(learned))
                else None
            ),
        )

    def set_traffic_overlay(self, overlay: Optional[TrafficOverlay]) -> None:
        """Inject a traffic overlay directly (tests / composition roots)."""
        self._overlay = overlay

    def start_traffic_consumer(self, consumer_id: str = "routing-traffic-01") -> Optional[Any]:
        """Subscribe to #broadcast and absorb TRAFFIC_CONGESTION envelopes.

        No-op (returns None) when no bus is wired in. Each congestion update
        refreshes the live overlay used by navigate().
        """
        if self._bus is None:
            logger.info("no bus wired; skipping traffic consumer")
            return None

        self._traffic_consumer_id = consumer_id

        def handler(env: Dict[str, Any], ack: Any) -> None:
            try:
                if not is_traffic_congestion(env):
                    return
                payload = env.get("payload") or {}
                segments = payload.get("segments") or []
                incoming = TrafficOverlay.from_segments(segments)
                if self._overlay is None:
                    self._overlay = incoming
                else:
                    self._overlay.merge(incoming)
            finally:
                if callable(ack):
                    ack()

        self._bus.subscribe("#broadcast", consumer_id, handler)
        return handler

    def health(self) -> Dict[str, Any]:
        """Return a health payload including node and (directed) edge counts."""
        return {
            "status": "ok",
            "service": "vector-routing",
            "nodes": len(self._graph.nodes()),
            "edges": self._graph.edge_count(),
            "turn_restrictions": len(self._restrictions) if self._restrictions else 0,
        }

    def restrictions_status(self) -> Dict[str, Any]:
        """Read-only observability for the turn-restriction layer.

        The counterpart of ``/overlay`` and ``/learned``: an operator must be
        able to tell "the graph has no restrictions" from "the graph has
        restrictions that did not resolve" without reading logs, because a route
        looks identical in both cases.
        """
        r = self._restrictions
        return {
            "enforced": bool(r is not None and len(r) > 0),
            "summary": r.summary() if r is not None else None,
        }

    def graph(self) -> RoutingGraph:
        """Return the underlying routing graph."""
        return self._graph

    # ---- the pedestrian graph (V7 Phase 2) ------------------------------
    #
    # A second graph beside the car graph, not a second service: see
    # ``foot_graph``. It is OPTIONAL and loaded separately, so a deployment
    # whose bake predates ``<region>_foot.geojson`` starts and serves every
    # existing endpoint exactly as before, and ``/foot`` says why it cannot
    # answer instead of the service failing to come up.

    def load_foot_graph(self, path: str, barriers_path: Optional[str] = None,
                        crossings_path: Optional[str] = None) -> int:
        """Build the pedestrian graph from a foot GeoJSON. Returns the edge count.

        A missing file is not an error here — it is the state of every
        deployment baked before this phase — but it IS reported, because a
        silently absent pedestrian graph and a present one are indistinguishable
        from the outside until someone asks for a walk.

        When ``barriers_path`` is given and the graph loads, the barrier
        catalog (V7.4 4A) is applied BEFORE the ``FootRouter`` is constructed:
        every blocked node's edges are severed, so component labelling, the
        census, snapping and every search all see the walking network a person
        can actually use. A missing/empty catalog severs nothing.

        When ``crossings_path`` is given, the preserved ``<region>_crossings``
        artifact (V7.4 4A.4) is loaded as the type/kerb source for crossing
        facts (V7.4 4B.1). The DRIVABLE graph — this service's own car graph — is
        passed to the ``FootRouter`` as the ``road_graph`` behind the "crossed
        road" fact. Neither changes routing; they enrich facts.
        """
        if not path or not os.path.exists(path):
            logger.info("no pedestrian graph at %s; /foot will report unavailable", path)
            return 0
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            logger.warning("pedestrian graph unreadable, continuing without it: %s", path)
            return 0
        graph = build_foot_graph(data)
        if not graph.nodes():
            # The file existed and produced nothing. That is the silent-no-op
            # failure: almost certainly a bake that wrote the CAR filter into
            # the foot file, or an ingestion pass predating `properties.foot`.
            logger.warning(
                "pedestrian graph file %s produced 0 nodes — /foot will report "
                "unavailable (is this a foot bake, or a car one?)", path)
            return 0
        # V7.4 4A: the barrier catalog, applied before any routing structure is
        # built so every downstream view (components, census, snapping, A*) is
        # the post-barrier network. The stats are kept for /footz and logged
        # here, because "the barrier file was baked but nothing matched this
        # graph" is a re-bake mismatch that is invisible from a route.
        self._barriers = None
        self._barrier_stats = {}
        if barriers_path:
            catalog = BarrierCatalog.from_path(barriers_path)
            self._barriers = catalog
            if len(catalog) > 0:
                stats = dict(catalog.stats)
                stats.update(apply_barriers(graph, catalog))
                self._barrier_stats = stats
                logger.info(
                    "barrier catalog: %d features (%d block / %d pass) from %s; "
                    "severed %d nodes, removed %d edges (%d barrier coords matched "
                    "this graph, %d did not)",
                    len(catalog), stats["block"], stats["pass"], barriers_path,
                    stats["blocked"], stats["edges_removed"],
                    stats["matched"], stats["unmatched"])
            else:
                logger.warning(
                    "barrier catalog at %s loaded but empty / unreadable — "
                    "the walking graph is NOT severed; walks are exactly as "
                    "they were before barriers existed", barriers_path)
        # V7.4 4B.1: the crossings artifact, loaded beside the graph and passed
        # to the router as the type/kerb source for crossing facts.
        self._crossings = (
            CrossingCatalog.from_path(crossings_path) if crossings_path else None)
        if self._crossings is not None and len(self._crossings) > 0:
            logger.info("crossings catalog: %d point facts from %s",
                        len(self._crossings), crossings_path)
        self._foot_graph = graph
        self._foot_router = FootRouter(
            graph,
            crossings=self._crossings,
            # The crossed-road fact reads the SERVICE's car graph (a person is
            # crossing a road a car drives; the foot graph alone cannot name
            # most of Qatar's crossed roads because they are car-only).
            road_graph=self._graph,
            # V7 traffic lights: bound against the graph just built, so the
            # index is right whichever order the caller loaded the two in. A
            # catalog loaded AFTER this is bound by `load_signals`.
            signals=SignalIndex.from_catalog(self._signals, graph),
        )
        logger.info("pedestrian graph loaded: %d nodes, %d edges from %s",
                    len(graph.nodes()), graph.edge_count(), path)
        return graph.edge_count()

    def set_foot_graph(self, graph: Optional[RoutingGraph],
                       crossings: Optional[CrossingCatalog] = None,
                       road_graph: Optional[RoutingGraph] = None,
                       signals: Optional[SignalIndex] = None) -> None:
        """Inject a pedestrian graph directly (tests / composition roots).

        ``crossings`` and ``road_graph`` are the V7.4 4B.1 fact sources; both
        default to None here so a test that only cares about routing gets a
        ``FootRouter`` exactly like the pre-4B.1 one. ``signals`` is the V7
        traffic-lights index; it defaults to the service's own catalog indexed
        against ``graph``, so callers that loaded signals in the usual order
        need to pass nothing, and a caller that wants to pin the association
        explicitly can.
        """
        self._crossings = crossings
        self._foot_graph = graph
        if signals is None and graph is not None and graph.nodes():
            signals = SignalIndex.from_catalog(self._signals, graph)
        self._foot_router = (
            FootRouter(graph, crossings=crossings, road_graph=road_graph,
                       signals=signals)
            if graph is not None and graph.nodes() else None)

    def foot_route(self, origin: Any, destination: Any,
                    profile: Optional[str] = None) -> Dict[str, Any]:
        """Walk from ``origin`` to ``destination`` (both ``(lon, lat)``).

        ``profile`` selects the pedestrian cost profile (V7.4 4B.4 contract):
        absent or ``"general"`` is the only wired profile and the default — a
        shortest-first walk with the documented physical costs (stairs pace,
        Tobler incline, crossing expected-delay). Any OTHER value raises
        ``RouteError`` rather than silently walking as ``general``: an unwired
        profile name must be a loud error, never a quiet lie. The response
        carries ``walking_profile`` (echoing the validated value) and the
        stable ``contract_version`` marker.

        Raises ``RouteError`` when no pedestrian graph is loaded — deliberately
        the same error family ``route`` raises for an empty car graph, so the
        HTTP layer's existing 503 branch covers it without a new code path.
        """
        if profile is None:
            profile = "general"
        if profile not in WALKING_PROFILES:
            raise RouteError(
                f"unknown walking profile {profile!r}: only "
                f"{sorted(WALKING_PROFILES)} are wired (4B.4 contract)"
            )
        if self._foot_router is None:
            raise RouteError(
                "cannot walk: no pedestrian graph is loaded "
                "(bake <region>_foot.geojson, or pass --foot-graph)"
            )
        result = self._foot_router.walk(origin, destination)
        # The public /foot contract (V7.4 4B.4): the profile the walk was
        # chosen under, and the contract version the response shape belongs
        # to. Additive — every field above is untouched.
        result["walking_profile"] = profile
        result["contract_version"] = FOOT_CONTRACT_VERSION
        return result

    def foot_status(self) -> Dict[str, Any]:
        """Read-only observability, mirroring ``/overlay`` and ``/learned``.

        Carries the component census (V7.4) because node and edge counts do NOT
        distinguish a healthy walking network from Qatar's, where the largest
        component holds under a third of the nodes. Both report a large,
        plausible graph; both answer short walks correctly; the difference only
        surfaces later as unexplained 404s in the field. It is one number, it is
        computed once at load, and without it on this endpoint the only way to
        learn it is to write a script against the bake.
        """
        g = self._foot_graph
        out = {
            "available": self._foot_router is not None,
            "nodes": len(g.nodes()) if g is not None else 0,
            "edges": g.edge_count() if g is not None else 0,
            "walk_speed_ms": WALK_SPEED_MS,
            "steps_speed_ms": STEPS_SPEED_MS,
            # V7.4 4A: the barrier view. Node and edge counts cannot tell a
            # graph that was severed at 2,217 locked gates from one that was
            # not — both load, both route short walks — so the numbers that
            # separate them live here: how many barriers were baked, how many
            # of each effect, and how many nodes/edges the walking graph
            # actually lost to the blocked ones. Absent catalog = the walking
            # graph was not severed; that is a valid pre-V7.4 state and being
            # able to SEE it is the point of the field.
            "barriers": {
                "features": self._barrier_stats.get("features", 0),
                "block": self._barrier_stats.get("block", 0),
                "pass": self._barrier_stats.get("pass", 0),
                "blocked_nodes": self._barrier_stats.get("blocked", 0),
                "edges_removed": self._barrier_stats.get("edges_removed", 0),
                "matched": self._barrier_stats.get("matched", 0),
                "unmatched": self._barrier_stats.get("unmatched", 0),
                "loaded": self._barriers is not None and len(self._barriers) > 0,
            },
            # V7.4 4B.1: the crossing-fact sources. Crossing facts are complete
            # without either (a cross fact is sourced from footway=crossing edge
            # props), but the TYPE usually lives on the artifact's nodes and the
            # crossed ROAD usually lives in the car graph — so these numbers are
            # what tell whether a cross fact can say "marked on Al Corniche" or
            # only "crossing".
            "crossings": {
                "loaded": self._crossings is not None and len(self._crossings) > 0,
                "features": len(self._crossings) if self._crossings else 0,
            },
            "crossing_road_source": bool(
                self._foot_router is not None
                and self._foot_router._road_graph is not None
            ),
            # V7 traffic lights: what the signal association achieved on THIS
            # walking network. Three numbers a reader cannot otherwise separate:
            # whether a catalog was loaded at all, how many of its signals stand
            # on a foot-graph node (so a crossing could cite them), and how many
            # of those actually stand on a crossing rather than at a junction a
            # walker walks past. A catalog that matched nothing and a catalog
            # that was never loaded look identical without this.
            "signals": (
                self._foot_router.signal_census()
                if self._foot_router is not None
                else {"associated": 0, "on_crossing": 0, "at_junction": 0}
            ),
            # V7.4 4B.2: the salience layer's constants, on the wire so a
            # consumer can see WHY a maneuver stream is sparse without reading
            # the source (the same doctrine as the snap radii on the census).
            "interpretation": {
                "spacing_m": MIN_MANEUVER_SPACING_M,
                "slight_suppression_radius_m": SLIGHT_SUPPRESSION_RADIUS_M,
                "wiggle_cluster_m": WIGGLE_CLUSTER_M,
                "wiggle_net_deg": WIGGLE_NET_DEG,
            },
            # V7.4 4B.3: the active pedestrian cost model, fully configured —
            # which factors are live, every documented value, and the
            # missing-data rule — so a consumer can validate a route's cost
            # without reading source. ``general`` is the only profile routed;
            # ``legacy`` (a constructor choice, not an endpoint) reproduces
            # the pre-4B.3 router.
            "cost_model": (
                self._foot_router._cost_model.config()
                if self._foot_router is not None else None
            ),
            # V7.4 4B.4: the PUBLIC /foot contract, declared machine-readably
            # so a client never has to infer the ETA/cost distinction, the
            # profile rules, or the maneuver vocabulary from example
            # responses. Additive; the contract itself is backward-compatible
            # (see the stage doc for the rationale behind each decision).
            "contract": {
                "version": FOOT_CONTRACT_VERSION,
                "eta": {
                    # Backward-compatible decision: ``duration_s`` keeps its
                    # pre-4B.3 meaning — pure walking pace time (distance /
                    # walk speed), byte-for-byte equal to ``cost.pace_s``. The
                    # cost-inclusive reading and its parts are reported
                    # separately rather than folded into the ETA.
                    "field": "duration_s",
                    "definition": "pure walking pace time (distance / walk speed); "
                                   "equals cost.pace_s by construction",
                    "cost_inclusive_equivalent": "cost.cost_s",
                    "expected_crossing_delay_s": "cost.crossing.wait_s (subset of cost.penalty_s)",
                },
                "profiles": {
                    "valid": list(WALKING_PROFILES),
                    "default": "general",
                    # An unwired name is a loud 400 listing the valid set —
                    # never silently treated as general.
                    "unknown_behavior": "http 400 with valid_profiles",
                },
                "maneuver_kinds": [
                    "depart", "cross", "stairs", "turn_left", "turn_right",
                    "slight_left", "slight_right", "uturn", "continue", "arrive",
                ],
            },
        }
        if self._foot_router is not None:
            out.update(self._foot_router.component_census())
        return out

    def start_bus_consumer(self, consumer_id: str = "routing-01") -> Optional[Any]:
        """Subscribe to ``#tasks`` and serve navigation requests from the bus.

        No-op (returns ``None``) when no bus is wired in. Each envelope where
        ``is_navigate_request`` is true is processed by ``navigate(...)`` and a
        RESULT envelope is published to ``#events``. All failures produce an
        ``ok:false`` result envelope (the request ``correlation_id`` is echoed).
        """
        if self._bus is None:
            logger.info("no bus wired; skipping bus consumer")
            return None

        self._consumer_id = consumer_id
        logger.info("bus consumer starting (consumer_id=%s, agent=%s)", consumer_id, ROUTING_AGENT_ADDRESS)

        def handler(env: Dict[str, Any], ack: Any) -> None:
            try:
                if not is_navigate_request(env):
                    return
                payload = env.get("payload") or {}
                from_ll = payload.get("from_ll")
                to_ll = payload.get("to_ll")
                via = payload.get("via") or []
                profile = payload.get("profile") or "car"

                ok = True
                geojson = None
                error: Optional[str] = None
                try:
                    result = self.navigate(
                        (from_ll[1], from_ll[0]),
                        (to_ll[1], to_ll[0]),
                        waypoints=[(v[1], v[0]) for v in via],
                    )
                    from .serve import navigate_to_geojson

                    geojson = navigate_to_geojson(
                        result,
                        (from_ll[0], from_ll[1]),
                        (to_ll[0], to_ll[1]),
                        profile,
                    )
                except Exception as exc:  # bad coords / empty graph / unexpected
                    ok = False
                    error = str(exc) or repr(exc)

                result_env = make_navigation_result(env, ok, geojson=geojson, error=error)
                # Channel is pinned positionally; both LocalBus and
                # NetworkBusClient accept (channel, message) without a
                # `channel=` keyword (the keyword would collide and raise
                # TypeError, and NetworkBusClient does not define it).
                self._bus.publish("#events", result_env)
            finally:
                if callable(ack):
                    ack()

        self._bus.subscribe("#tasks", consumer_id, handler)
        return handler
