"""HTTP traffic service for vector-traffic.

Exposes the existing traffic engine over HTTP using only the Python standard
library so a web map can draw per-segment speed/congestion overlays. The service
answers traffic requests with a GeoJSON ``FeatureCollection`` of per-segment
traffic ``LineString`` features built from either sparse probe observations or
the bundled sample data.

Runtime is available via the provisioned Python toolchain (adr-0006).
"""

import argparse
import json
import os
import pathlib
import socket
import threading
import urllib.parse

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from vector_auth import Auth

from .aggregate import MIN_PROBES_FOR_CONGESTION
from .errors import InputError, TrafficError
from .probe import Probe
from .segments import load_segments
from .probe import load_probes
from .traffic import TrafficModel, TrafficSegment
from .store import TrafficStateStore
from .bus_envelope import make_traffic_congestion
from .loaders import build_segments_from_graph, build_graph_probes
from dataclasses import dataclass, field
from typing import List, Optional
import time


@dataclass
class Incident:
    """A crowdsourced hazard/incident reported by a user (W42)."""

    id: str
    lon: float
    lat: float
    kind: str  # hazard | crash | police | closure | slowdown
    note: str = ""
    created: float = field(default_factory=time.time)

    def to_geojson(self) -> dict:
        return {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [self.lon, self.lat]},
            "properties": {
                "id": self.id,
                "kind": self.kind,
                "note": self.note,
                "created": self.created,
            },
        }


DEFAULT_DATA_DIR = pathlib.Path(__file__).resolve().parents[2] / "traffic-data"

DEFAULT_SEGMENTS = "sample_segments.geojson"

# Web-Mercator-safe coordinate limits, mirroring
# vector-offline-maps/src/vector_offline_maps/serve.py::parse_bbox. Kept as
# local constants (stdlib-only, no sibling-repo imports per adr-0003).
LON_LIMIT = 180.0

LAT_LIMIT = 85.05112878


def parse_bbox(s: str):
    """Parse a ``MINLON,MINLAT,MAXLON,MAXLAT`` string into floats.

    Returns ``None`` when ``s`` is empty to signal that the caller should not
    scope the request to a region. Raises ``ValueError`` on malformed /
    non-numeric input and on out-of-range or inverted bounds. Mirrors
    ``vector_offline_maps.serve.parse_bbox`` but is defined locally to avoid any
    sibling-repo import (adr-0003).
    """
    if not s:
        return None
    parts = s.split(",")
    if len(parts) != 4:
        raise ValueError("bbox must be 4 comma-separated floats: MINLON,MINLAT,MAXLON,MAXLAT")
    try:
        vals = [float(p) for p in parts]
    except (TypeError, ValueError):
        raise ValueError("bbox must be 4 comma-separated floats: MINLON,MINLAT,MAXLON,MAXLAT")
    minlon, minlat, maxlon, maxlat = vals
    if not (-LON_LIMIT <= minlon <= LON_LIMIT) or not (-LON_LIMIT <= maxlon <= LON_LIMIT):
        raise ValueError("longitude out of range [-180,180]")
    if not (-LAT_LIMIT <= minlat <= LAT_LIMIT) or not (-LAT_LIMIT <= maxlat <= LAT_LIMIT):
        raise ValueError("latitude out of range [-85.05112878,85.05112878]")
    if minlon > maxlon:
        raise ValueError("minlon must be <= maxlon")
    if minlat > maxlat:
        raise ValueError("minlat must be <= maxlat")
    return (minlon, minlat, maxlon, maxlat)


def _segment_intersects_bbox(geometry, bbox) -> bool:
    """Return ``True`` if a segment's AABB intersects the requested bbox.

    ``geometry`` is a list of ``(lon, lat)`` tuples (a GeoJSON ``LineString``);
    ``bbox`` is ``(minlon, minlat, maxlon, maxlat)``. Standard axis-aligned
    bounding-box overlap test on the segment's coordinate extents.
    """
    if not geometry:
        return False
    minlon, minlat, maxlon, maxlat = bbox
    lons = [c[0] for c in geometry]
    lats = [c[1] for c in geometry]
    seg_minlon, seg_maxlon = min(lons), max(lons)
    seg_minlat, seg_maxlat = min(lats), max(lats)
    if seg_maxlon < minlon or seg_minlon > maxlon:
        return False
    if seg_maxlat < minlat or seg_minlat > maxlat:
        return False
    return True


def parse_probes(s: str):
    """Parse a ``"LON,LAT[,SPEED];LON,LAT[,SPEED];..."`` string into ``Probe`` objects.

    Each part must contain exactly 2 or 3 numeric parts (``lon, lat`` with an
    optional ``speed_kmh``). Returns ``None`` when ``s`` is empty to signal that
    the caller should fall back to the default sample probes. Raises
    ``ValueError`` on malformed or non-numeric input.
    """
    if not s:
        return None
    out = []
    for part in s.split(";"):
        coords = part.split(",")
        if len(coords) not in (2, 3):
            raise ValueError("each probe must be 'LON,LAT[,SPEED]' with 2 or 3 numeric parts")
        try:
            lon = float(coords[0])
            lat = float(coords[1])
            speed_kmh = float(coords[2]) if len(coords) == 3 else None
        except (TypeError, ValueError):
            raise ValueError("each probe must be 'LON,LAT[,SPEED]' with 2 or 3 numeric parts")
        out.append(Probe(lon=lon, lat=lat, speed_kmh=speed_kmh))
    return out


def parse_float_param(s, name: str, default: float) -> float:
    """Parse a float query param, returning ``default`` if missing/empty."""
    if s is None or s == "":
        return default
    try:
        return float(s)
    except (TypeError, ValueError):
        raise ValueError("%s must be a number" % (name,))


def traffic_to_geojson(segments, probes) -> dict:
    """Return the GeoJSON ``FeatureCollection`` for segments and probes."""
    return TrafficModel().estimate_geojson(segments, probes)


def apply_evidence_floor(fc: dict, floor: int) -> dict:
    """Withhold congestion that rests on too few probes.

    One probe is one vehicle, and one vehicle can be parked, turning, or a
    phone on a bus. A road called "jammed" on that evidence is a guess
    presented as a fact, and a driver diverted by it has been sent the long way
    round for nothing.

    Segments below the floor are DROPPED rather than relabelled. The two
    alternatives are both worse: marking them ``free`` asserts they flow, and
    marking them ``unknown`` makes both clients draw them in the style's
    fallback grey — a map covered in lines that mean "we do not know", which is
    noise the driver has to learn to ignore.

    The count is reported on the collection so the withholding is legible
    rather than silent. An empty result with ``withheld_low_evidence: 2000``
    says "we have no traffic yet"; an empty result with no explanation looks
    like a broken service, and this codebase has already been bitten twice by
    telemetry that failed invisibly.
    """
    feats = fc.get("features") or []
    kept, withheld = [], 0
    for f in feats:
        props = f.get("properties") or {}
        try:
            n = int(props.get("probe_count") or 0)
        except (TypeError, ValueError):
            n = 0
        if n >= floor:
            kept.append(f)
        else:
            withheld += 1
    out = dict(fc)
    out["features"] = kept
    out["min_probes"] = floor
    out["withheld_low_evidence"] = withheld
    return out


class TrafficService:
    """Builds GeoJSON traffic payloads for the HTTP layer."""

    def __init__(self, data_dir=DEFAULT_DATA_DIR, store=None, bus=None, graph_path=None):
        self.data_dir = pathlib.Path(data_dir)
        # Optional road-network GeoJSON (Wave 30, Doha demo). When set, /traffic
        # emits congestion on the ACTUAL routing graph so the routing engine's
        # overlay matches real edges and can reroute. Otherwise the bundled
        # Berlin sample data is used.
        self.graph_path = graph_path
        # Live congestion store (ADR-0025 / Wave 23.5). The store is the
        # authoritative source of live per-segment state: estimates are
        # persisted (upsert) and the served payload is read back FROM the store
        # so persisted congestion is genuinely consumed (Wave 26b). Falls back
        # to the fresh estimate when the store yields nothing.
        self.store = store if store is not None else TrafficStateStore()
        # Optional event-bus client. When wired, each estimate is published as a
        # TRAFFIC_CONGESTION envelope (Wave 26c) so downstream consumers (e.g.
        # the routing engine) can avoid congested corridors.
        self.bus = bus
        # Traffic-from-graph memo (2026-09-14 mines): the /traffic payload is
        # a pure function of the STATIC graph file, but re-parsing it on every
        # poll measured 7-61 s on the production edge and starved the speed
        # badge. Keyed by (graph_path, cap), invalidated by graph mtime.
        self._graph_memo: Dict[tuple, tuple] = {}
        self._graph_lock = threading.Lock()
        # W42/W43: incidents are persisted through the store (PostGIS or memory),
        # so reported hazards survive restart when a DSN is configured.

    def traffic_from_graph(self, cap: int = 2000) -> dict:
        """Estimate congestion on the configured road-network graph (Wave 30).

        Emits congestion directly on the graph edges (no O(n^2) probe matching)
        so the routing engine's overlay matches *real* Doha edges and can
        reroute. Every 13th edge is jammed (mean_speed = free_flow * 0.12); the
        rest flow near free-flow. Deterministic (no RNG) and fast even on the
        full ~70k-edge network (``cap`` limits how many edges are emitted).

        ``cap`` limits how many graph edges become congestion segments so the
        demo stays fast; the first ``cap`` edges cover the loaded map area.
        """
        if not self.graph_path or not os.path.exists(self.graph_path):
            raise FileNotFoundError("traffic graph not found: " + str(self.graph_path))
        key = (self.graph_path, cap)
        mtime = os.path.getmtime(self.graph_path)
        if key in self._graph_memo and self._graph_memo[key][0] == mtime:
            return self._graph_memo[key][1]
        if self._graph_memo and list(self._graph_memo) != [key]:
            self._graph_memo.clear()  # single-slot cache, one graph per container
        with self._graph_lock:
            if key in self._graph_memo and self._graph_memo[key][0] == mtime:
                return self._graph_memo[key][1]
            with open(self.graph_path, "r", encoding="utf-8") as fh:
                graph = json.load(fh)
            segments = build_segments_from_graph(graph, cap=cap)
            if not segments:
                payload = {"type": "FeatureCollection", "features": []}
                self._graph_memo[key] = (mtime, payload)
                return payload
            results = []
            for i, seg in enumerate(segments):
                if i % 13 == 0:
                    mean = max(3.0, seg.free_flow_kmh * 0.12)
                    congestion = "jammed"
                else:
                    mean = seg.free_flow_kmh * 0.92
                    congestion = "free"
                results.append(TrafficSegment(
                    segment_id=seg.id,
                    geometry=seg.geometry,
                    free_flow_kmh=seg.free_flow_kmh,
                    mean_speed_kmh=mean,
                    probe_count=1,
                    congestion=congestion,
                ))
            # Persist + publish so downstream consumers (store, bus) see it.
            # Runs once per graph file (cache miss) — the payload is a pure
            # function of the static graph, so repeated calls after a rollout
            # produce byte-identical state.
            for r in results:
                self.store.upsert(r)
            if self.bus is not None:
                self.bus.publish("#broadcast", make_traffic_congestion(results))
            source = []
            for r in results:
                st = self.store.get(r.segment_id)
                source.append(st.to_segment() if st is not None else r)
            payload = TrafficModel().to_geojson(source)
            self._graph_memo[key] = (mtime, payload)
            return payload

    def traffic(
        self,
        probes=None,
        segments_file=DEFAULT_SEGMENTS,
        max_match_radius_m=100.0,
        bbox=None,
    ) -> dict:
        seg_path = self.data_dir / segments_file
        if not seg_path.exists():
            raise FileNotFoundError("segments file not found: " + segments_file)
        with open(seg_path, "r", encoding="utf-8") as fh:
            segments = load_segments(json.load(fh))

        if bbox is not None:
            segments = [s for s in segments if _segment_intersects_bbox(s.geometry, bbox)]
            if not segments:
                # No in-region data: return a graceful empty FeatureCollection
                # rather than calling the model (mirrors HD-map link behavior
                # for regions without sample data).
                return {"type": "FeatureCollection", "features": []}

        if probes is None:
            probe_path = self.data_dir / "sample_probes.geojson"
            if not probe_path.exists():
                raise FileNotFoundError("probes sample file not found")
            with open(probe_path, "r", encoding="utf-8") as fh:
                probe_list = load_probes(json.load(fh))
        else:
            probe_list = probes

        estimated = TrafficModel().estimate(
            segments, probe_list, max_match_radius_m=max_match_radius_m
        )

        # Persist the fresh estimates into the live store (Wave 26b).
        for seg in estimated:
            self.store.upsert(seg)

        # Publish live congestion to the bus (Wave 26c) so downstream consumers
        # (e.g. routing) can avoid congested corridors. No-op without a bus.
        if self.bus is not None:
            self.bus.publish("#broadcast", make_traffic_congestion(estimated))

        # CONSUME the store: for each requested segment, prefer the persisted
        # live state (proving the store is read, not just written); fall back
        # to the fresh estimate for segments not yet in the store.
        source = []
        for seg in estimated:
            st = self.store.get(seg.segment_id)
            source.append(st.to_segment() if st is not None else seg)
        return TrafficModel().to_geojson(source)

    # --- W42: crowdsourced probes + incidents ---------------------------------

    def add_probe(self, probe: Probe) -> dict:
        """Ingest a crowdsourced GPS probe and persist its congestion effect.

        When the live store is a real PostGIS/state backend the probe feeds the
        persisted per-segment state; otherwise it is map-matched against the
        configured graph and upserted so the served traffic reflects live speeds.
        Returns a small acknowledgement.
        """
        if self.graph_path and os.path.exists(self.graph_path):
            from .match import match_probe

            try:
                with open(self.graph_path, "r", encoding="utf-8") as fh:
                    graph = json.load(fh)
                segments = build_segments_from_graph(graph, cap=2000)
                res = match_probe(probe, segments, max_match_radius_m=120.0)
                seg = next((s for s in segments if s.id == res.segment_id), None)
                if seg is not None:
                    free = seg.free_flow_kmh
                    self.store.upsert(
                        TrafficSegment(
                            segment_id=res.segment_id,
                            geometry=seg.geometry,
                            free_flow_kmh=free,
                            mean_speed_kmh=probe.speed_kmh or free,
                            probe_count=1,
                            congestion="free" if (probe.speed_kmh or 0) >= free * 0.6
                            else "heavy",
                        )
                    )
            except Exception:
                pass  # best-effort: a probe that can't be matched is dropped
        return {"ok": True, "lon": probe.lon, "lat": probe.lat}

    def add_incident(self, lon: float, lat: float, kind: str, note: str = "") -> Incident:
        row = self.store.add_incident(lon, lat, kind, note)
        return Incident(id=row.id, lon=row.lon, lat=row.lat,
                        kind=row.kind, note=row.note, created=row.created)

    def list_incidents(self) -> dict:
        rows = self.store.list_incidents()
        return {
            "type": "FeatureCollection",
            "features": [
                Incident(id=r.id, lon=r.lon, lat=r.lat, kind=r.kind, note=r.note,
                         created=r.created).to_geojson()
                for r in rows
            ],
        }


class TrafficRequestHandler(BaseHTTPRequestHandler):
    traffic_service = None

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

        if parsed.path == "/traffic":
            q = urllib.parse.parse_qs(parsed.query)
            probes_raw = q.get("probes", [""])[0]
            segments = q.get("segments", [DEFAULT_SEGMENTS])[0]
            max_match_radius_m = parse_float_param(
                q.get("max_match_radius_m", [""])[0], "max_match_radius_m", 100.0
            )
            bbox_raw = q.get("bbox", [""])[0]
            try:
                bbox = parse_bbox(bbox_raw) if bbox_raw else None
                probes_obj = parse_probes(probes_raw) if probes_raw else None
                if self.traffic_service is None:
                    self._send_json(503, {"error": "traffic service unavailable"})
                    return
                if self.traffic_service.graph_path:
                    # Wave 30: emit congestion on the actual routing graph so
                    # the routing engine can reroute around real Doha jams.
                    fc = self.traffic_service.traffic_from_graph()
                else:
                    fc = self.traffic_service.traffic(
                        probes=probes_obj,
                        segments_file=segments,
                        max_match_radius_m=max_match_radius_m,
                        bbox=bbox,
                    )
                # The DISPLAY floor. See aggregate.MIN_PROBES_FOR_CONGESTION:
                # the stack's current traffic is synthetic (one probe per
                # segment, every 13th edge jammed by `i % 13`), so `/traffic`
                # was answering with 154 "jams" over Doha and the native HUD
                # was reporting them to the driver as live congestion.
                #
                # Applied here rather than in `traffic_from_graph`, because the
                # bus publish inside that method feeds the ROUTER's overlay.
                # Which roads the router avoids is a separate decision with its
                # own evidence, and bundling the two would make either change
                # impossible to evaluate. That the router still sees the
                # unfiltered feed is recorded as an open item, not hidden.
                self._send_json(200, apply_evidence_floor(fc, MIN_PROBES_FOR_CONGESTION))
            except ValueError as e:
                self._send_json(400, {"error": str(e)})
            except InputError as e:
                self._send_json(400, {"error": str(e)})
            except FileNotFoundError as e:
                self._send_json(404, {"error": str(e)})
            except TrafficError as e:
                self._send_json(503, {"error": str(e)})
            except Exception as e:  # pragma: no cover - unexpected
                self._send_json(503, {"error": str(e)})
            return

        if parsed.path == "/incidents":
            if self.traffic_service is None:
                self._send_json(503, {"error": "traffic service unavailable"})
                return
            self._send_json(200, self.traffic_service.list_incidents())
            return

        self._send(404, "text/plain", b"not found")

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if not self.auth.enforce(self):
            return
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(length) if length else b"{}"
            body = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception as e:
            self._send_json(400, {"error": "invalid JSON: %s" % e})
            return

        if parsed.path == "/traffic/probe":
            try:
                probe = Probe(
                    lon=float(body["lon"]),
                    lat=float(body["lat"]),
                    speed_kmh=(float(body["speed_kmh"]) if body.get("speed_kmh") is not None else None),
                )
            except (KeyError, TypeError, ValueError) as e:
                self._send_json(400, {"error": "probe needs lon,lat[,speed_kmh]: %s" % e})
                return
            if self.traffic_service is None:
                self._send_json(503, {"error": "traffic service unavailable"})
                return
            self._send_json(200, self.traffic_service.add_probe(probe))
            return

        if parsed.path == "/incidents":
            try:
                lon = float(body["lon"])
                lat = float(body["lat"])
            except (KeyError, TypeError, ValueError) as e:
                self._send_json(400, {"error": "incident needs lon,lat: %s" % e})
                return
            kind = str(body.get("kind", "hazard"))
            note = str(body.get("note", ""))
            if self.traffic_service is None:
                self._send_json(503, {"error": "traffic service unavailable"})
                return
            inc = self.traffic_service.add_incident(lon, lat, kind, note)
            self._send_json(201, inc.to_geojson())
            return

        self._send(404, "text/plain", b"not found")


def make_server(port: int, service, host: str = "0.0.0.0", bus=None) -> ThreadingHTTPServer:
    """Create a threaded HTTP server bound to ``host:port`` with ``service`` injected."""
    TrafficRequestHandler.traffic_service = service
    TrafficRequestHandler.auth = Auth()
    TrafficRequestHandler.bus = bus
    return ThreadingHTTPServer((host, port), TrafficRequestHandler)


def main(argv=None) -> None:
    """CLI entrypoint: load sample data and serve traffic over HTTP."""
    parser = argparse.ArgumentParser(description="vector-traffic HTTP traffic service")
    parser.add_argument("--port", type=int, default=8084)
    parser.add_argument("--data", default=None, help="path to a traffic-data directory")
    args = parser.parse_args(argv)

    data_dir = args.data or os.environ.get("TRAFFIC_DATA") or str(DEFAULT_DATA_DIR)
    graph_path = os.environ.get("TRAFFIC_GRAPH") or None

    bus = None
    bus_url = os.environ.get("VECTOR_BUS_URL")
    if bus_url:
        from vector_bus_client import NetworkBusClient

        bus = NetworkBusClient(bus_url)
    else:
        from vector_bus_client import LocalBus

        bus = LocalBus()

    service = TrafficService(data_dir=data_dir, bus=bus, graph_path=graph_path)
    # Publishing live congestion onto the bus happens inside traffic() whenever
    # a bus is wired (Wave 26c/26d). No separate consumer to start for traffic.

    server = make_server(args.port, service, bus=bus)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
