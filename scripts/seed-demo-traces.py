#!/usr/bin/env python3
"""Seed the quarantine store with synthetic trips, through the real privacy gate.

Exists so the evolution loop can be exercised end to end before any real driving
has happened: without traces every stage correctly skips, which proves the
plumbing is honest but not that it works.

Trips are synthetic; every rule they pass through is real. Points go through
``vector_privacy.apply_gate`` and ``TraceStore.append`` exactly as a browser POST
would, so what lands in the store is genuinely gated data — truncated endpoints,
accuracy floor, 5 s time rounding, per-trip pseudonyms.

    python scripts/seed-demo-traces.py --roads vector-osrm/data/qatar.geojson \\
        --trips 12 --segments 40

**Not for production.** It writes location-shaped rows into the quarantine store;
they expire under the same 72 h TTL as anything else, but a real deployment
should never have synthetic evidence mixed into its learned facts.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for rel in ("vector-web/src", "vector-web/vendor", "vector-privacy/src",
            "vector-learning/src"):
    path = os.path.join(ROOT, *rel.split("/"))
    if os.path.isdir(path) and path not in sys.path:
        sys.path.insert(0, path)

from vector_learning.aggregate import time_band  # noqa: E402
from vector_privacy.gate import apply_gate, mint_pseudonym  # noqa: E402
from vector_web import privacy_counters  # noqa: E402
from vector_web.trace_store import TraceStore  # noqa: E402


def pick_ways(roads_path: str, count: int, seed: int = 5):
    """Sample road ways long enough to survive 200 m endpoint truncation."""
    with open(roads_path, encoding="utf-8") as fh:
        doc = json.load(fh)
    features = doc.get("features", doc if isinstance(doc, list) else [])
    rng = random.Random(seed)
    candidates = []
    for feature in features:
        geometry = (feature or {}).get("geometry") or {}
        if geometry.get("type") != "LineString":
            continue
        coords = geometry.get("coordinates") or []
        if len(coords) < 8:
            continue  # too short: truncation would leave nothing
        candidates.append(coords)
        if len(candidates) > 20000:
            break
    rng.shuffle(candidates)
    return candidates[:count]


def densify(coords, step_m=25.0):
    """Interpolate a way into ~``step_m`` spaced fixes, like a real GPS stream."""
    out = []
    for (a, b) in zip(coords, coords[1:]):
        dx = (b[0] - a[0]) * 101_000.0   # rough metres/deg lng at 25N
        dy = (b[1] - a[1]) * 111_000.0
        dist = max(1.0, (dx * dx + dy * dy) ** 0.5)
        steps = max(1, int(dist / step_m))
        for i in range(steps):
            t = i / steps
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    out.append((coords[-1][0], coords[-1][1]))
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="seed synthetic gated traces (dev only)")
    ap.add_argument("--roads", required=True, help="roads GeoJSON to drive along")
    ap.add_argument("--db", default=None, help="trace store path (default vector-web/data/traces.db)")
    ap.add_argument("--trips", type=int, default=12,
                    help="distinct trips per segment (must exceed K=5 to clear the floor)")
    ap.add_argument("--segments", type=int, default=40, help="how many roads to cover")
    ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--band", type=int, default=1,
                    help="time band to concentrate trips in (default 1 = weekday AM peak)")
    args = ap.parse_args(argv)

    db = args.db or os.path.join(ROOT, "vector-web", "data", "traces.db")
    ways = pick_ways(args.roads, args.segments, seed=args.seed)
    if not ways:
        print("no usable ways found in the roads file", file=sys.stderr)
        return 1

    rng = random.Random(args.seed)
    now_ms = int(time.time() * 1000)

    # Anchor every trip inside one time band, on the most recent day whose band
    # occurrence is comfortably within the 72 h TTL. Walk back from "now" to the
    # first timestamp whose band matches the target and which is at least 6 h old,
    # so the whole spread below stays inside the window.
    band_anchor_ms = now_ms - 6 * 3_600_000
    for _ in range(24 * 4):
        if time_band(band_anchor_ms) == args.band:
            break
        band_anchor_ms -= 3_600_000
    else:
        print(f"could not find a recent occurrence of band {args.band}", file=sys.stderr)
        return 1
    print(f"anchoring trips in band {args.band} "
          f"({(now_ms - band_anchor_ms) / 3_600_000:.0f} h ago, inside the 72 h TTL)")

    store = TraceStore(db)
    totals = {"stored": 0, "dropped": {}}

    for way_index, coords in enumerate(ways):
        track = densify(coords)
        for trip in range(args.trips):
            # Each trip is a fresh pseudonym — that is the whole point of the
            # K floor counting DISTINCT trips rather than distinct devices.
            #
            # Placement has to satisfy two constraints at once, and getting it
            # wrong silently produces a store that teaches the system nothing:
            #
            #   * all trips for a segment must land in the SAME (segment, band)
            #     bucket, or each is alone below the K=5 floor; and
            #   * all must fall inside the 72 h TTL, or they are deleted before
            #     aggregation runs.
            #
            # A band recurs weekly but occurs ~3 times inside a 72 h window, so
            # spacing trips one day apart caps a bucket at 3 — under K, forever.
            # (That is exactly what the previous version of this script did: it
            # spread trips across days to "exercise multiple bands" and produced
            # zero evidence, unnoticed because verify-evolution-loop.py carries
            # its own inline data.)
            #
            # So: spread trips across the last 3 days *and* within each day, all
            # at a local time inside one band. That models a busy commute — the
            # only traffic pattern that can clear K under these two rules.
            days_back = trip % 3
            within_day = trip // 3
            start_ms = (
                band_anchor_ms
                - days_back * 86_400_000
                + within_day * 20 * 60_000     # 20-min spacing, stays inside the band
                - way_index * 1_000
            )
            speed_ms = rng.uniform(8.0, 25.0)   # 29-90 km/h, stored in m/s
            points = []
            for i, (lng, lat) in enumerate(track):
                points.append({
                    "lng": lng,
                    "lat": lat,
                    "t": start_ms + i * 5_000,
                    "s": round(speed_ms + rng.uniform(-1.5, 1.5), 2),
                    "a": round(rng.uniform(4.0, 18.0), 1),   # inside the 25 m floor
                })
            gated, dropped = apply_gate("track", points, now_ms=now_ms)
            for reason, n in dropped.items():
                totals["dropped"][reason] = totals["dropped"].get(reason, 0) + n
            stored = store.append("track", gated, pseudonym=mint_pseudonym(), meta={})
            totals["stored"] += stored
            # Record the drops exactly as the web edge does. Without this the
            # dashboard's privacy panel would warn that the gate has rejected
            # nothing, while these very trips had their endpoints truncated —
            # a false alarm is as bad as a missed one.
            privacy_counters.record(dropped, stored=stored)

    count = store.count()
    store.close()
    print(f"seeded {totals['stored']} gated points from "
          f"{len(ways)} ways x {args.trips} trips -> {db}")
    print(f"store now holds {count} observations; dropped by the gate: {totals['dropped']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
