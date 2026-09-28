#!/usr/bin/env python3
"""Capture a route fixture from the real router, for the JVM drive suite.

## Why this is a script in the tree and not a curl command in a commit message

`app/src/test/resources/routes/*.json` are the ground truth the whole drive
suite stands on, and until V7 Stage 4 there was no recorded way to make one.
That is a real gap rather than an untidiness: the eight existing fixtures were
captured on 2026-09-09 against a live stack, and when Stage 4 needed a fixture
carrying `lane_data.forward_lanes` there was nothing to say HOW to produce one
in the same shape. The options were to hand-edit lane fields into an existing
file — inventing data, which is the one thing V7 Stage 4 exists not to do — or
to work out the capture again from scratch.

So: this loads the deployed Qatar graph straight into `vector_routing`, calls
the same `navigate`/`navigate_alternatives` the HTTP handler calls, and writes
`navigate_feature`'s output. The serialiser is the production one, so a fixture
cannot drift from the wire format without the app's own parser noticing.

## Why it does not re-capture the existing eight

It can, and it must not be used to without saying so. Two things have changed
in the router since they were taken:

  * `4e933b6` calibrated the driving ETA against 18 real drives, so free-flow
    durations are ~15% higher than the fixtures record (445.7 s -> 516.2 s on
    `city-souq-westbay` route 0);
  * the graph has been re-baked, which moves a handful of shape points on five
    of the eight routes.

Geometry is otherwise reproduced EXACTLY — `slip-split-corniche`,
`reroute-missed-turn` and `reroute-wrong-road` come back byte-identical, which
is what establishes that this script really is the thing that made them. But a
blanket re-capture would quietly move numbers that Stage 1-3's camera and
maneuver assertions were tuned against, and \"the fixtures changed\" is not a
finding anyone can act on. Re-capture deliberately, one fixture at a time, with
the diff read.

## Usage

    python3 vector-android/scripts/capture_route_fixture.py \\
        --name two-way-rabia \\
        --from 51.5031181,25.3219320 --to 51.5202887,25.3229374

Coordinates are `lng,lat`, the order GeoJSON uses and the order the router's
own API takes. (The `from`/`to` PROPERTIES it writes are `lat,lng`, because
that is what the HTTP endpoint echoes and what the existing fixtures carry.)
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
ROUTING = REPO / "vector-routing"
sys.path.insert(0, str(ROUTING / "src"))
sys.path.insert(0, str(ROUTING / "vendor"))

DEFAULT_GRAPH = pathlib.Path.home() / ".cache" / "vector-deploy" / "qatar_roads.geojson"
DEFAULT_OUT = REPO / "vector-android" / "app" / "src" / "test" / "resources" / "routes"


def lnglat(s: str) -> tuple[float, float]:
    lng, lat = (float(x) for x in s.split(","))
    return (lng, lat)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--name", required=True, help="fixture basename, without .json")
    ap.add_argument("--from", dest="frm", required=True, type=lnglat, help="lng,lat")
    ap.add_argument("--to", required=True, type=lnglat, help="lng,lat")
    ap.add_argument("--alternatives", type=int, default=0,
                    help="how many routes to ask for; 0 means a single /navigate")
    ap.add_argument("--graph", default=os.environ.get("ROUTING_GRAPH", str(DEFAULT_GRAPH)))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    # English, because the fixtures carry English labels and `DriveHarness`
    # asserts on road names. Omitting it silently produces Arabic ones, which
    # reads as a fixture regression rather than as a missing argument.
    ap.add_argument("--lang", default="en")
    ap.add_argument("--force", action="store_true",
                    help="overwrite an existing fixture (see the module KDoc first)")
    args = ap.parse_args(argv)

    from vector_routing.service import RoutingService
    from vector_routing.serve import navigate_feature

    out = pathlib.Path(args.out) / f"{args.name}.json"
    if out.exists() and not args.force:
        print(f"refusing to overwrite {out} — re-read this script's KDoc, then pass --force",
              file=sys.stderr)
        return 2

    print(f"loading {args.graph} …", file=sys.stderr)
    svc = RoutingService.from_geojson(args.graph)
    print(f"  {len(svc._graph.nodes())} nodes, {svc._graph.edge_count()} edges", file=sys.stderr)

    if args.alternatives > 0:
        results = svc.navigate_alternatives(args.frm, args.to,
                                            wanted=args.alternatives, lang=args.lang)
    else:
        results = [svc.navigate(args.frm, args.to, lang=args.lang)]

    feats = [navigate_feature(r, args.frm, args.to, "car") for r in results]
    doc = {"type": "FeatureCollection", "features": feats}

    # Report what the fixture actually PROVES, so a capture that reaches none of
    # the cases it was taken for is visible here rather than three commits later
    # in a test that passes against a world with no lanes in it.
    for i, f in enumerate(feats):
        p = f["properties"]
        tw = ow = unk = rbt = 0
        for s in p["steps"]:
            ld = s.get("lane_data") or {}
            fwd, ap_ = ld.get("forward_lanes"), s.get("approach_lanes")
            if s.get("type") == "roundabout":
                rbt += 1
            if fwd is None:
                unk += 1
            elif ap_ and fwd * 2 == ap_:
                tw += 1
            elif ap_ and fwd == ap_:
                ow += 1
        print(f"  route{i}: {p['distance_km']:.2f} km, {len(p['steps'])} steps — "
              f"two-way {tw}, one-way {ow}, unknown {unk}, roundabout {rbt}",
              file=sys.stderr)

    # `ensure_ascii=True`, no indent, default separators — byte-for-byte the
    # shape the existing eight are in. Verified by round-tripping
    # `dense-msheireb.json` through `json.dumps`: these settings reproduce it
    # exactly, `ensure_ascii=False` does not (it un-escapes the Arabic road
    # names). A fixture that differs from its neighbours only in whitespace and
    # escaping makes every future `git diff` over this directory unreadable,
    # which is a small cost paid every time and a real one.
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc), encoding="utf-8")
    print(f"wrote {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
