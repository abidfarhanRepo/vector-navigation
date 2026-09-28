"""Deterministic inline sample data for vector-traffic demos/tests.

Mirrors ``traffic-data/sample_segments.geojson`` and
``traffic-data/sample_probes.geojson`` so that ``__main__`` produces identical
results whether or not those files are present.
"""

from .probe import Probe
from .segments import RoadSegment


def build_sample_segments() -> list:
    return [
        RoadSegment(
            id="seg-1",
            geometry=[(13.4000, 52.5200), (13.4100, 52.5200)],
            free_flow_kmh=50.0,
        ),
        RoadSegment(
            id="seg-2",
            geometry=[(13.4000, 52.5000), (13.4100, 52.5000)],
            free_flow_kmh=50.0,
        ),
        RoadSegment(
            id="seg-3",
            geometry=[(13.4500, 52.5300), (13.4600, 52.5300)],
            free_flow_kmh=50.0,
        ),
    ]


def build_sample_probes() -> list:
    return [
        Probe(lon=13.4005, lat=52.5200, speed_kmh=45.0),
        Probe(lon=13.4050, lat=52.5200, speed_kmh=40.0),
        Probe(lon=13.4005, lat=52.5000, speed_kmh=10.0),
        Probe(lon=13.3000, lat=52.3000, speed_kmh=30.0),
    ]


def build_segments_from_graph(graph_geojson: dict, cap: "int | None" = None) -> list:
    """Build congestion ``RoadSegment``s from a road-network FeatureCollection.

    Used by the Doha demo (Wave 30): the traffic engine emits live-looking
    congestion on the *actual* routing graph so the routing engine's overlay
    matches real edges and can reroute. Each LineString road edge becomes a
    ``RoadSegment`` whose ``free_flow_kmh`` is taken from the road's
    ``maxspeed``/``maxspeed_kmh`` (default 50). A deterministic subset of edges
    is marked jammed (slow probes) so a real diversion is visible.

    ``cap`` stops collection early (the full Doha network has ~70k edges) so
    the demo stays fast.
    """
    features = graph_geojson.get("features", []) if isinstance(graph_geojson, dict) else graph_geojson
    segments = []
    idx = 0
    for f in features:
        if cap is not None and len(segments) >= cap:
            break
        geom = (f.get("geometry") or {}) if isinstance(f, dict) else {}
        if geom.get("type") != "LineString":
            continue
        coords = geom.get("coordinates") or []
        if len(coords) < 2:
            continue
        props = f.get("properties") or {}
        ff = props.get("maxspeed") or props.get("maxspeed_kmh") or props.get("free_flow_kmh") or 50.0
        try:
            ff = float(ff)
        except (TypeError, ValueError):
            ff = 50.0
        if ff <= 0:
            ff = 50.0
        segments.append(RoadSegment(
            id="doha-%d" % idx,
            geometry=[(float(c[0]), float(c[1])) for c in coords],
            free_flow_kmh=ff,
        ))
        idx += 1
    return segments


def build_graph_probes(segments: list):
    """Synthetic probes on the graph segments; every 13th edge is jammed.

    A probe is placed at each segment's midpoint. Jammed edges get a slow
    probe (free_flow * 0.12) so ``TrafficModel`` classifies them as congested;
    the rest get a near-free-flow probe. Deterministic (no RNG) so the demo is
    reproducible.
    """
    probes = []
    for i, seg in enumerate(segments):
        a, b = seg.geometry[0], seg.geometry[-1]
        mid = ((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0)
        if i % 13 == 0:
            probes.append(Probe(lon=mid[0], lat=mid[1], speed_kmh=max(3.0, seg.free_flow_kmh * 0.12)))
        else:
            probes.append(Probe(lon=mid[0], lat=mid[1], speed_kmh=seg.free_flow_kmh * 0.92))
    return probes
