#!/usr/bin/env python3
"""Measure route latency with and without learned speed profiles (issue 07).

Issue 07 requires: "Loading a profile set must not regress route latency —
measure before/after on the ~95k-road Doha graph and record it." This is that
measurement, reproducible rather than quoted.

    python scripts/bench_learned_latency.py \
      --graph ../vector-osrm/data/qatar.geojson \
      --profiles 20000 --runs 5

Reports median wall-clock per request for four configurations: base,
learned-loaded-but-disabled, learned via /navigate (ETA hook), and learned via
/route (search overlay). The third is the one that matters most — it is the path
the app actually calls.
"""

import argparse
import json
import os
import random
import statistics
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_routing.learned_overlay import LearnedSpeedOverlay  # noqa: E402
from vector_routing.service import RoutingService  # noqa: E402

HOUR = 8


def synth_profiles(service: RoutingService, count: int, seed: int = 7):
    """Build ``count`` speed-profile facts over real edges of the loaded graph.

    Synthetic *values* over real *geometry*: the point of the benchmark is the
    lookup cost at realistic overlay size, and no real corpus of that size
    exists yet.
    """
    rng = random.Random(seed)
    graph = service.graph()
    node_keys = list(graph.nodes().keys())
    facts = []
    seen = set()
    attempts = 0
    while len(facts) < count and attempts < count * 20:
        attempts += 1
        u = node_keys[rng.randrange(len(node_keys))]
        neighbours = graph.neighbors(u)
        if not neighbours:
            continue
        to, _w, _p = neighbours[rng.randrange(len(neighbours))]
        if not graph.has_node(to):
            continue
        key = (u, to)
        if key in seen:
            continue
        seen.add(key)
        a, b = graph.node_coord(u), graph.node_coord(to)
        facts.append({
            "fact_key": f"speed_profile:{u}:{HOUR}",
            "fact_type": "speed_profile",
            "evidence_count": 7,
            "confidence": 0.9,
            "payload": {
                "segment_id": f"{u}->{to}",
                "hour_of_week": HOUR,
                "median_speed_kmh": rng.uniform(18.0, 95.0),
                "geometry": [[a[0], a[1]], [b[0], b[1]]],
            },
        })
    return facts


def pick_pairs(service: RoutingService, n: int, seed: int = 11):
    rng = random.Random(seed)
    coords = list(service.graph().nodes().values())
    pairs = []
    for _ in range(n):
        a = coords[rng.randrange(len(coords))]
        b = coords[rng.randrange(len(coords))]
        pairs.append((a, b))
    return pairs


def timed(fn, pairs):
    times = []
    for (a, b) in pairs:
        start = time.perf_counter()
        try:
            fn(a, b)
        except Exception:
            continue  # an unroutable pair is not a latency measurement
        times.append(time.perf_counter() - start)
    return times


def report(label, times):
    if not times:
        print(f"{label:<34} no successful requests")
        return None
    median = statistics.median(times)
    print(f"{label:<34} n={len(times):<4} median={median*1000:8.2f} ms  "
          f"p90={sorted(times)[int(len(times)*0.9)-1]*1000:8.2f} ms")
    return median


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="learned-overlay latency benchmark")
    ap.add_argument("--graph", required=True)
    ap.add_argument("--profiles", type=int, default=20000)
    ap.add_argument("--runs", type=int, default=12, help="route requests per configuration")
    args = ap.parse_args(argv)

    t0 = time.perf_counter()
    service = RoutingService.from_geojson(args.graph)
    nodes = len(service.graph().nodes())
    print(f"[graph] {nodes} nodes, {service.graph().edge_count()} directed edges "
          f"(loaded in {time.perf_counter()-t0:.1f}s)")

    pairs = pick_pairs(service, args.runs)

    base_nav = report("navigate (no learned layer)",
                      timed(lambda a, b: service.navigate(a, b), pairs))
    base_route = report("route (no learned layer)",
                        timed(lambda a, b: service.route(a, b), pairs))

    t0 = time.perf_counter()
    facts = synth_profiles(service, args.profiles)
    overlay = LearnedSpeedOverlay.from_facts(facts)
    service.set_learned_overlay(overlay)
    print(f"[overlay] {len(overlay)} edge-hour buckets from {len(facts)} facts "
          f"(built in {time.perf_counter()-t0:.1f}s)")

    service.set_learned_enabled(False)
    off_nav = report("navigate (loaded, disabled)",
                     timed(lambda a, b: service.navigate(a, b), pairs))
    service.set_learned_enabled(True)

    on_nav = report("navigate (learned ETA hook)",
                    timed(lambda a, b: service.navigate(a, b, hour=HOUR), pairs))
    on_route = report("route (learned search overlay)",
                      timed(lambda a, b: service.route(a, b, hour=HOUR), pairs))

    print()
    if base_nav and on_nav:
        print(f"navigate overhead: {(on_nav/base_nav - 1)*100:+.1f}%")
    if base_nav and off_nav:
        print(f"disabled overhead: {(off_nav/base_nav - 1)*100:+.1f}%  (must be ~0)")
    if base_route and on_route:
        print(f"route overhead:    {(on_route/base_route - 1)*100:+.1f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
