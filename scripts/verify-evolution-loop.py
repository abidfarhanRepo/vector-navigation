#!/usr/bin/env python3
"""Cross-repo gate for the self-evolving loop: S0 -> S2 -> S3/S4 -> S5 -> consumers.

Lives at the workspace root because it is the one check no single repo can make.
Each repo's own suite is green in isolation and stays green while the *contracts
between* them are broken — which is exactly what happened: the speed-profile
producer emitted facts with no geometry and the routing consumer silently
discarded every one of them, with 368 tests passing.

So this asserts the seams, with real modules and no mocks. It writes gated points
through ``vector-web``'s ``TraceStore``, runs the ``vector-learning`` batch over
them, promotes, and then loads the exports with the ACTUAL consumer code from
``vector-routing``, ``vector-tile-gen`` and ``vector-geocoder``. The assertion
that matters most is that the routing overlay comes out **non-empty** — an empty
overlay is the loop being decorative.

    python scripts/verify-evolution-loop.py

Exit 0 means the loop closes. It uses a throwaway temp directory and touches no
real store.
"""
import json
import os
import random
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for rel in [
    ("vector-web", "src"), ("vector-web", "vendor"),
    ("vector-learning", "src"), ("vector-learning", "vendor"),
    ("vector-routing", "src"), ("vector-routing", "vendor"),
    ("vector-tile-gen", "src"), ("vector-geocoder", "src"),
    ("vector-privacy", "src"),
]:
    sys.path.insert(0, os.path.join(ROOT, *rel))

from vector_privacy.gate import apply_gate, mint_pseudonym            # noqa: E402
from vector_web.trace_store import TraceStore                        # noqa: E402
from vector_learning.fact_store import FactStore                     # noqa: E402
from vector_learning.job import run_batch                            # noqa: E402
from vector_learning.metrics_export import build_snapshot            # noqa: E402
from vector_learning.promote import promote, read_export             # noqa: E402
from vector_learning.segments import load_network                    # noqa: E402
from vector_learning.store import QuarantineStore                    # noqa: E402

T0 = 1_754_000_000_000  # fixed epoch-ms; no wall clock anywhere

# A straight road, ~1.1 km long, plus a parallel road nobody drives.
NETWORK = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature", "properties": {"id": "road-A", "maxspeed": 60},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.500, 25.200], [51.510, 25.200]]}},
        {"type": "Feature", "properties": {"id": "road-B", "maxspeed": 60},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.500, 25.300], [51.510, 25.300]]}},
    ],
}


def synth_trip(seq: int):
    """A track along road-A. Long enough to survive 200 m endpoint truncation.

    Deliberately PERFECT: exact 5 s spacing, constant speed, 8 m accuracy on
    every point. Kept as the clean baseline, because when this pass fails the
    cause is the pipeline and not the input. :func:`noisy_trip` is the pass that
    asks the harder question.
    """
    points = []
    for i in range(24):
        points.append({
            "lng": 51.5000 + i * 0.0004,
            "lat": 25.2000,
            "t": T0 + seq * 600_000 + i * 5_000,
            "s": 11.0,          # m/s -> ~40 km/h, well below the 60 limit
            "a": 8.0,           # metres accuracy: passes the 25 m floor
        })
    return points


def noisy_trip(seq: int):
    """The same drive as a real receiver would have recorded it.

    V5 §18: *"do not create tests that only pass because Vector knows the test
    is happening — avoid perfectly clean GPS, perfectly timed updates, zero GPS
    noise."* :func:`synth_trip` is all three, so the learning pipeline had only
    ever been verified against a track no phone produces. That is the same gap
    the Android client had before V5 built ``DriveSimulator``, and it matters
    here for specific reasons: the privacy gate has a 25 m accuracy floor, the
    batch map-matches within 100 m, and the k-anonymity floor counts DISTINCT
    TRIPS — all three are functions of receiver behaviour.

    Modelled with the same shapes ``dev.vector.geo.DriveSimulator`` uses, in the
    stdlib, because this script must stay dependency-free and cannot call into
    Kotlin:

    * **correlated lateral drift**, not white noise. A mean-reverting walk, so
      the error stays on the same side of the road for several fixes — which is
      what multipath does, and what a per-point jitter cannot reproduce.
    * **interval jitter**, so nothing lands on a metronome.
    * **variable accuracy**, including a burst above the gate's 25 m floor that
      SHOULD be dropped.
    * **a stationary period** with the speed field absent, which is what a
      chipset reports at a standstill.

    Seeded per trip and fully deterministic: same seed, same track, every run.
    """
    rnd = random.Random(20260909 + seq)
    points = []
    drift = 0.0
    t = T0 + 5_000_000 + seq * 600_000
    for i in range(30):
        # Ornstein-Uhlenbeck-ish: 0.75 decay per fix keeps the error on one side
        # of the road for roughly four to five fixes at a time.
        drift = drift * 0.75 + rnd.gauss(0.0, 6.0) * 0.66
        stopped = 12 <= i < 16          # a junction
        # ~35 m of travel per fix while moving, 0 while stopped.
        along = min(i, 12) if stopped else (i if i < 12 else i - 4)
        pt = {
            "lng": 51.5000 + along * 0.0004 + rnd.gauss(0.0, 1.5) / 100_000.0,
            # Drift is lateral, across a road that runs east-west.
            "lat": 25.2000 + drift / 111_320.0,
            "t": t,
            # A receiver reports no speed at a standstill on most chipsets, and
            # the pipeline must not read a missing field as zero.
            "a": 45.0 if 20 <= i < 23 else rnd.uniform(4.0, 14.0),
        }
        if not stopped:
            pt["s"] = 11.0 + rnd.gauss(0.0, 1.2)
        points.append(pt)
        t += 5_000 + int(rnd.uniform(-900, 900))
    return points


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="vector-e2e-")
    traces_db = os.path.join(tmp, "traces.db")
    facts_db = os.path.join(tmp, "facts.db")
    network_path = os.path.join(tmp, "roads.geojson")
    out_dir = os.path.join(tmp, "promoted")
    with open(network_path, "w", encoding="utf-8") as fh:
        json.dump(NETWORK, fh)

    # ---- S0/S1 + S2: gate every trip, then store it -----------------------
    store = TraceStore(traces_db)
    total_dropped = {}
    for seq in range(8):  # 8 distinct trips: above the K=5 floor
        gated, dropped = apply_gate("track", synth_trip(seq), now_ms=T0 + 10_000_000)
        for reason, n in dropped.items():
            total_dropped[reason] = total_dropped.get(reason, 0) + n
        store.append("track", gated, pseudonym=mint_pseudonym(), meta={})
    stored = store.count()
    store.close()
    print(f"[S2] stored={stored} dropped={total_dropped}")
    assert stored > 0, "nothing survived the gate"
    assert total_dropped.get("truncated_endpoint", 0) > 0, "truncation did nothing"

    # ---- S3 + S4: aggregate and persist facts ----------------------------
    facts = FactStore(facts_db)
    with QuarantineStore(traces_db) as quarantine:
        index = load_network(network_path)
        result = run_batch(
            quarantine, index,
            watermark_path=os.path.join(tmp, "watermark.json"),
            match_radius_m=100.0,
            fact_store=facts,
            now_ms=T0 + 10_000_000,
        )
    print(f"[S3] processed={result.processed} matched={result.matched} "
          f"evidence={len(result.evidence)} facts={len(result.facts)} "
          f"recorded={len(result.recorded_ids)} k_stats={result.k_stats}")
    assert result.evidence, "no k-anonymous evidence emitted"
    assert result.recorded_ids, "facts were computed but never persisted"

    # ---- S5: promote to the three consumers -----------------------------
    report = promote(facts, now_ms=T0 + 10_000_000, out_dir=out_dir, index=index)
    print(f"[S5] {json.dumps(report.to_dict())}")
    assert report.total_promoted > 0, "nothing was promoted"

    # ---- the consumers, for real ----------------------------------------
    from vector_routing.learned_overlay import LearnedSpeedOverlay
    speed_facts = read_export(os.path.join(out_dir, "learned_speed.json"))
    overlay = LearnedSpeedOverlay.from_facts(speed_facts)
    print(f"[routing] facts={len(speed_facts)} overlay_edges={len(overlay)} "
          f"coverage={overlay.coverage()}")
    assert len(overlay) > 0, "ROUTING OVERLAY LOADED ZERO EDGES — the loop is a no-op"

    # Cross-repo band-agreement check. This is the assertion no per-repo suite can
    # make: two independent implementations of one definition (adr-0003 forbids the
    # import), and a one-band drift silently applies the wrong time-of-week's
    # speeds.
    #
    # Swept across a full week at hourly resolution rather than sampled at one
    # timestamp — a single sample agrees by coincidence for any two functions that
    # both return 0 somewhere, which is exactly the kind of check that passes while
    # the thing it guards is broken.
    from vector_routing.service import time_band as routing_time_band, BANDS_PER_WEEK as BANDS
    from vector_learning.aggregate import time_band as learning_time_band

    week_start = 1_785_715_200_000  # 2026-08-03T00:00:00Z, a Monday
    mismatches = []
    produced = set()
    for hour in range(168):
        ts = week_start + hour * 3_600_000
        lb, rb = learning_time_band(ts), routing_time_band(ts)
        produced.add(lb)
        if lb != rb:
            mismatches.append((hour, lb, rb))
    assert not mismatches, (
        f"band mismatch between vector-learning and vector-routing at "
        f"{len(mismatches)} of 168 hours, first: {mismatches[0]}"
    )
    assert produced == set(range(BANDS)), (
        f"only bands {sorted(produced)} are reachable across a week; "
        f"an unreachable band never accumulates evidence"
    )
    print(f"[bands] {BANDS} bands, 168/168 hours agree across both repos")

    # The overlay must actually change a weight for the road that was driven.
    sample_band = list(overlay._by_pair_hour)[0][1]
    factor = overlay.factor_for((51.500, 25.200), (51.510, 25.200),
                                sample_band, baseline_kmh=60.0)
    print(f"[routing] learned factor on road-A = {factor}")
    assert factor != 1.0, "learned speed did not change the routing weight"

    from vector_tile_gen.learned_layer import affected_tiles, features_from_facts
    geometry_facts = read_export(os.path.join(out_dir, "learned_geometry.json"))
    tile_features = features_from_facts(geometry_facts)
    print(f"[tile-gen] geometry_facts={len(geometry_facts)} "
          f"features={len(tile_features)} tiles={len(affected_tiles(tile_features, [14]))}")

    from vector_geocoder.learned_poi import LearnedPoiIndex, pois_from_facts
    poi_facts = read_export(os.path.join(out_dir, "learned_pois.json"))
    poi_index = LearnedPoiIndex(pois_from_facts(poi_facts))
    print(f"[geocoder] poi_facts={len(poi_facts)} indexed={len(poi_index)}")

    # ---- issue 10 snapshot ----------------------------------------------
    snapshot = build_snapshot(facts, index, now_ms=T0 + 10_000_000)
    print(f"[metrics] coverage={snapshot['coverage']} "
          f"promotions={json.dumps(snapshot['promotions'])}")
    assert snapshot["coverage"]["covered"] > 0, "coverage metric still reads zero"
    facts.close()

    # ---- the same loop, driven realistically (V5) -------------------------
    #
    # Everything above ran on a track with exact 5 s spacing, constant speed and
    # 8 m accuracy on every point, which is a track no phone produces. This pass
    # repeats it with correlated drift, jittered intervals, a stationary period
    # with no speed field, and an accuracy burst that the gate is supposed to
    # drop — and asserts that the loop still closes.
    #
    # It is a second pass rather than a replacement because the two answer
    # different questions: if the clean pass fails the pipeline is broken, and if
    # only this one fails the pipeline cannot survive a real receiver.
    rc = realistic_pass()
    if rc != 0:
        return rc

    print("\nE2E OK — the loop closes end to end, clean and noisy.")
    return 0


def realistic_pass() -> int:
    """Run the whole loop again on realistic tracks. See :func:`noisy_trip`."""
    tmp = tempfile.mkdtemp(prefix="vector-e2e-noisy-")
    traces_db = os.path.join(tmp, "traces.db")
    facts_db = os.path.join(tmp, "facts.db")
    network_path = os.path.join(tmp, "roads.geojson")
    out_dir = os.path.join(tmp, "promoted")
    with open(network_path, "w", encoding="utf-8") as fh:
        json.dump(NETWORK, fh)

    store = TraceStore(traces_db)
    dropped_total = {}
    for seq in range(8):
        gated, dropped = apply_gate("track", noisy_trip(seq), now_ms=T0 + 10_000_000)
        for reason, n in dropped.items():
            dropped_total[reason] = dropped_total.get(reason, 0) + n
        store.append("track", gated, pseudonym=mint_pseudonym(), meta={})
    stored = store.count()
    store.close()
    print(f"[noisy S2] stored={stored} dropped={dropped_total}")
    assert stored > 0, "nothing survived the gate on a realistic track"
    # The gate is supposed to reject the poor-accuracy burst. If it rejects
    # nothing, either the burst is not being produced or the floor is not being
    # applied — and both make this pass meaningless.
    assert dropped_total.get("accuracy", 0) > 0, (
        "the 25 m accuracy floor dropped nothing from a track containing 45 m "
        "fixes — the floor is not being applied"
    )

    facts = FactStore(facts_db)
    with QuarantineStore(traces_db) as quarantine:
        index = load_network(network_path)
        result = run_batch(
            quarantine, index,
            watermark_path=os.path.join(tmp, "watermark.json"),
            match_radius_m=100.0,
            fact_store=facts,
            now_ms=T0 + 10_000_000,
        )
    print(f"[noisy S3] processed={result.processed} matched={result.matched} "
          f"evidence={len(result.evidence)} facts={len(result.facts)} "
          f"k_stats={result.k_stats}")
    assert result.matched > 0, "map matching found nothing under realistic drift"
    assert result.evidence, "no k-anonymous evidence emitted from realistic tracks"

    report = promote(facts, now_ms=T0 + 10_000_000, out_dir=out_dir, index=index)
    print(f"[noisy S5] promoted={report.total_promoted} rejected={report.total_rejected}")
    assert report.total_promoted > 0, "nothing promoted from realistic tracks"

    from vector_routing.learned_overlay import LearnedSpeedOverlay
    speed_facts = read_export(os.path.join(out_dir, "learned_speed.json"))
    overlay = LearnedSpeedOverlay.from_facts(speed_facts)
    print(f"[noisy routing] facts={len(speed_facts)} overlay_edges={len(overlay)}")
    assert len(overlay) > 0, (
        "REALISTIC TRACKS PRODUCED AN EMPTY ROUTING OVERLAY — the loop closes "
        "only for GPS that does not exist"
    )

    # And the drift must not have been learned as a road of its own. road-B is
    # 11 km north and nobody drove it; a fact against it would mean the matcher
    # had accepted noise as evidence.
    matched_ids = {e.get("segment_id") for e in result.evidence if isinstance(e, dict)}
    assert "road-B" not in matched_ids, (
        f"drift was matched onto a road nobody drove: {sorted(matched_ids)}"
    )

    facts.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
