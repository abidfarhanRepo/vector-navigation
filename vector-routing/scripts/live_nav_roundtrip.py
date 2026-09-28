"""Live navigation request -> result round-trip proof harness for vector-routing.

Purpose
-------
This is a *manual* proof harness (NOT a unit test) that demonstrates the
`vector-routing` engine, when deployed with ``VECTOR_BUS_URL`` set, actually
consumes a navigate task from the real ``vector-bus`` (:8090) and publishes a
``navigation_result`` back to ``#events``.

It does so by:

1. Subscribing an observer to ``#events`` over the live bus (SSE).
2. Publishing a single ``navigate`` request to ``#tasks`` with a unique
   ``correlation_id``.
3. Waiting (up to ``--timeout``) for a ``navigation_result`` whose
   ``correlation_id`` matches, then validating the geojson payload shape.

Preconditions (this script does not start these):
  * A live ``vector-bus`` server reachable at ``--bus-url`` (default
    ``http://localhost:8090``).
  * A ``vector-routing`` container subscribed to ``#tasks`` (i.e. launched with
    ``VECTOR_BUS_URL`` pointing at the same bus).

On success it prints a structured result and exits 0 ("ROUNDTRIP PASS").
On any failure, transport error, or timeout it prints "ROUNDTRIP FAIL" and
exits 1 -- it never hangs or aborts with a raw traceback.

Example invocation (run from the vector-routing package root):

    cd vector-routing && PYTHONPATH=src python scripts/live_nav_roundtrip.py \
        --bus-url http://localhost:8090
"""

import argparse
import json
import os
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# Ensure the package is importable regardless of CWD.
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_DIR = SCRIPT_DIR.parent
SRC_DIR = REPO_DIR / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from vector_routing.bus_client import NetworkBusClient  # noqa: E402
from vector_routing.bus_envelope import (  # noqa: E402
    ROUTING_AGENT_ADDRESS,
    make_navigate_request,
)

DEFAULT_GRAPH = REPO_DIR / "routing-data" / "sample_network.geojson"


def parse_latlon(value: str) -> List[float]:
    """Parse a ``"lat,lon"`` string into a ``[lat, lon]`` float pair."""
    parts = value.split(",")
    if len(parts) != 2:
        raise ValueError("expected 'lat,lon' but got: %r" % value)
    lat, lon = float(parts[0].strip()), float(parts[1].strip())
    return [lat, lon]


def resolve_coords(
    from_arg: Optional[str],
    to_arg: Optional[str],
    graph_path: Path,
) -> Tuple[List[float], List[float]]:
    """Resolve from/to ``[lat, lon]`` pairs.

    If both ``--from`` and ``--to`` are supplied they are parsed directly.
    Otherwise the graph GeoJSON is read and the first LineString (or
    MultiLineString) feature yields two *adjacent* endpoints so the router is
    guaranteed a route. GeoJSON coordinates are ``[lon, lat]`` while the
    envelope expects ``[lat, lon]``, so the components are swapped.
    """
    if from_arg and to_arg:
        return parse_latlon(from_arg), parse_latlon(to_arg)

    with open(graph_path, "r", encoding="utf-8") as fh:
        gj = json.load(fh)

    features = gj.get("features", [])
    coord_list: Optional[List[List[float]]] = None
    for feat in features:
        geom = feat.get("geometry", {})
        gtype = geom.get("type")
        if gtype == "LineString":
            coord_list = geom.get("coordinates", [])
            break
        if gtype == "MultiLineString":
            lines = geom.get("coordinates", [])
            if lines:
                coord_list = lines[0]
                break
    if not coord_list or len(coord_list) < 2:
        raise ValueError("graph %s has no usable LineString with 2+ coords" % graph_path)

    c0 = coord_list[0]
    c1 = coord_list[1]
    # Swap [lon, lat] -> [lat, lon].
    from_ll = [float(c0[1]), float(c0[0])]
    to_ll = [float(c1[1]), float(c1[0])]
    return from_ll, to_ll


def validate_result(msg: Dict[str, Any]) -> Tuple[bool, str]:
    """Validate a navigation_result envelope; return (ok, detail)."""
    payload = msg.get("payload", {})
    if not isinstance(payload, dict):
        return False, "payload missing"
    if not payload.get("ok"):
        return False, "payload.ok is not True (error=%r)" % payload.get("error")
    geojson = payload.get("geojson")
    if not isinstance(geojson, dict):
        return False, "geojson missing or not a dict"
    # Mirror test_nav_bus assertions: features[0].properties has steps + distance_km.
    features = geojson.get("features")
    if not isinstance(features, list) or not features:
        return False, "geojson has no features"
    props = features[0].get("properties", {})
    steps = props.get("steps")
    distance_km = props.get("distance_km")
    if not isinstance(steps, list) or len(steps) == 0:
        return False, "geojson properties.steps not a non-empty list"
    if not isinstance(distance_km, (int, float)) or isinstance(distance_km, bool):
        return False, "geojson properties.distance_km not a number"
    return True, "ok"


def run(args: argparse.Namespace) -> int:
    bus_url = args.bus_url
    graph_path = Path(args.graph).resolve()
    if not graph_path.exists():
        print("ROUNDTRIP FAIL: graph not found: %s" % graph_path)
        return 1

    try:
        from_ll, to_ll = resolve_coords(args.frm, args.to, graph_path)
    except Exception as exc:  # pragma: no cover - defensive
        print("ROUNDTRIP FAIL: coordinate resolution error: %s" % exc)
        return 1

    print("BUS_URL   : %s" % bus_url)
    print("ROUTING   : %s" % ROUTING_AGENT_ADDRESS)
    print("GRAPH     : %s" % graph_path)
    print("FROM_LL   : %s" % from_ll)
    print("TO_LL     : %s" % to_ll)
    print("PROFILE   : %s" % args.profile)

    try:
        client = NetworkBusClient(bus_url)
        results: List[Tuple[Dict[str, Any], Any]] = []

        def on_event(msg: Any, ack: Any) -> None:
            if isinstance(msg, dict):
                kind = msg.get("payload", {}).get("kind")
                if kind == "navigation_result":
                    results.append((msg, ack))

        client.subscribe("#events", "proof-observer", on_event)
        # Let the SSE subscription register on the bus before publishing.
        time.sleep(1.0)

        cid = str(uuid.uuid4())
        req = make_navigate_request(
            "agent://proof-observer",
            from_ll,
            to_ll,
            profile=args.profile,
            correlation_id=cid,
        )
        client.publish("#tasks", req)
        print("PUBLISHED : navigate task cid=%s" % cid)
    except Exception as exc:
        print("ROUNDTRIP FAIL: bus interaction error: %s" % exc)
        return 1

    matched: Optional[Dict[str, Any]] = None
    ack_fn: Any = None
    deadline = time.time() + args.timeout
    try:
        while time.time() < deadline:
            for env, ack in results:
                if env.get("correlation_id") == cid:
                    matched, ack_fn = env, ack
                    break
            if matched is not None:
                break
            time.sleep(0.2)
    except Exception as exc:
        print("ROUNDTRIP FAIL: result polling error: %s" % exc)
        return 1

    if matched is None:
        print("ROUNDTRIP FAIL: no navigation_result for cid=%s within %ss" % (cid, args.timeout))
        return 1

    try:
        if ack_fn is not None:
            ack_fn()
    except Exception:
        # Ack failure is non-fatal for the proof, but note it.
        print("WARN: ack of delivery failed (non-fatal)")

    ok, detail = validate_result(matched)
    payload = matched.get("payload", {})
    props = {}
    features = payload.get("geojson", {}).get("features", [])
    if features:
        props = features[0].get("properties", {})

    step_count = len(props.get("steps", [])) if isinstance(props.get("steps"), list) else 0
    distance_km = props.get("distance_km")

    print("CORRELATION_ID : %s" % cid)
    print("MATCHED        : %s" % (matched is not None))
    print("OK             : %s" % ok)
    print("DETAIL         : %s" % detail)
    print("STEPS_COUNT    : %s" % step_count)
    print("DISTANCE_KM    : %s" % distance_km)

    if ok:
        print("ROUNDTRIP PASS")
        return 0
    print("ROUNDTRIP FAIL")
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Live vector-routing navigate task -> navigation_result proof harness.",
    )
    parser.add_argument(
        "--bus-url",
        default=os.environ.get("VECTOR_BUS_URL", "http://localhost:8090"),
        help="vector-bus base URL (default: $VECTOR_BUS_URL or http://localhost:8090)",
    )
    parser.add_argument(
        "--from",
        dest="frm",
        default=None,
        help='optional "lat,lon" start coordinate',
    )
    parser.add_argument(
        "--to",
        dest="to",
        default=None,
        help='optional "lat,lon" end coordinate',
    )
    parser.add_argument(
        "--graph",
        default=str(DEFAULT_GRAPH),
        help="path to a GeoJSON network (default: <repo>/routing-data/sample_network.geojson)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=20.0,
        help="seconds to wait for the navigation_result (default: 20)",
    )
    parser.add_argument(
        "--profile",
        default="car",
        help="routing profile (default: car)",
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
