#!/usr/bin/env python3
"""Explain a route: why this path, and why this long?

    python explain_route.py --graph qatar_roads.geojson \
        --from 25.2854,51.5310 --to 25.2900,51.5350

Vector's requirement is not that it agrees with any other router. It is that it
can EXPLAIN its own answer. This prints everything needed to decide whether a
detour is legitimate topology or a defect:

  * requested vs snapped endpoints, and the snap distance for each;
  * whether each snapped node was actually routable (had outgoing edges);
  * the path aggregated into road runs, with class, name, oneway and length;
  * every one-way edge traversed;
  * the crow distance, the road distance and the detour ratio;
  * a connectivity probe around each endpoint: what else was nearby, and would
    the route have been shorter had a different node been chosen.

Reads the graph directly rather than going through HTTP, so it can see the
snapping decision itself, which the API response cannot express.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

_HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (os.path.join(_HERE, "..", "src"), os.path.join(_HERE, "..", "vendor")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from vector_geo.graph import RoutingGraph  # noqa: E402
from vector_geo.haversine import haversine_meters  # noqa: E402
from vector_routing.service import RoutingService  # noqa: E402
from vector_routing.router import _parse_kmh  # noqa: E402


def parse_ll(s: str) -> Tuple[float, float]:
    """Parse a "lat,lon" string into (lon, lat) — the order the engine uses."""
    lat, lon = (float(x) for x in s.split(","))
    return (lon, lat)


def load_graph(path: str) -> RoutingGraph:
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    feats = data["features"] if isinstance(data, dict) else data
    g = RoutingGraph()
    for f in feats:
        geom = f.get("geometry") or {}
        if geom.get("type") != "LineString":
            continue
        coords = [(c[0], c[1]) for c in geom.get("coordinates") or []]
        if len(coords) >= 2:
            g.add_way(coords, dict(f.get("properties") or {}))
    return g


def edge_between(g: RoutingGraph, a: str, b: str) -> Optional[Dict[str, Any]]:
    for to, w, p in g.neighbors(a):
        if to == b:
            return {"w": w, "props": p}
    return None


def is_oneway(props: Dict[str, Any]) -> bool:
    return props.get("oneway") in (True, "yes", "1", 1)


def describe_endpoint(g: RoutingGraph, label: str, want: Tuple[float, float],
                      snapped: Optional[str]) -> Dict[str, Any]:
    out: Dict[str, Any] = {"label": label, "requested": want}
    if snapped is None:
        out["error"] = "no node snapped"
        return out
    coord = g.node_coord(snapped)
    out["snapped"] = coord
    out["snap_m"] = haversine_meters(want, coord)
    adj = g.neighbors(snapped)
    out["out_edges"] = len(adj)
    out["classes"] = sorted({(e[2].get("highway") or "?") for e in adj})
    out["names"] = sorted({e[2].get("name") for e in adj if e[2].get("name")})
    # Was there anything closer that we did NOT pick? A large gap between the
    # nearest node overall and the nearest ROUTABLE node is the fingerprint of a
    # snapping problem.
    best_any, best_any_d = None, None
    for k, c in g.nodes().items():
        d = haversine_meters(want, c)
        if best_any_d is None or d < best_any_d:
            best_any_d, best_any = d, k
    out["nearest_any_m"] = best_any_d
    out["nearest_any_is_routable"] = bool(g.neighbors(best_any)) if best_any else False
    return out


def road_runs(g: RoutingGraph, keys: List[str]) -> List[Dict[str, Any]]:
    """Collapse the node path into consecutive runs on the same road."""
    runs: List[Dict[str, Any]] = []
    for a, b in zip(keys, keys[1:]):
        e = edge_between(g, a, b)
        if e is None:
            runs.append({"name": "<GAP>", "highway": "?", "length_m": 0.0,
                         "oneway": False, "maxspeed": None, "edges": 1})
            continue
        p = e["props"]
        ident = (p.get("name"), p.get("highway"))
        if runs and (runs[-1]["name"], runs[-1]["highway"]) == ident:
            runs[-1]["length_m"] += e["w"]
            runs[-1]["edges"] += 1
        else:
            runs.append({
                "name": p.get("name"),
                "highway": p.get("highway"),
                "length_m": e["w"],
                "oneway": is_oneway(p),
                "maxspeed": _parse_kmh(p),
                "edges": 1,
            })
    return runs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--graph", required=True)
    ap.add_argument("--from", dest="src", required=True, help="lat,lon")
    ap.add_argument("--to", dest="dst", required=True, help="lat,lon")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--top", type=int, default=14, help="road runs to print")
    args = ap.parse_args()

    origin = parse_ll(args.src)
    dest = parse_ll(args.dst)

    g = load_graph(args.graph)
    svc = RoutingService(g)
    router = svc._router  # noqa: SLF001 — diagnostics deliberately look inside

    src_key = router._snap_routable(origin[0], origin[1])   # noqa: SLF001
    dst_key = router._snap_routable(dest[0], dest[1])       # noqa: SLF001

    res = svc.navigate(origin, dest)
    keys = None
    try:
        r = router.route_by_node(src_key, dst_key)
        keys = r.node_keys
    except Exception as exc:  # pragma: no cover - diagnostics
        print("route_by_node failed:", exc)

    crow = haversine_meters(origin, dest)
    report: Dict[str, Any] = {
        "origin": describe_endpoint(g, "origin", origin, src_key),
        "destination": describe_endpoint(g, "destination", dest, dst_key),
        "crow_m": crow,
        "route_m": res["distance_m"],
        "duration_s": res["duration_s"],
        "detour": (res["distance_m"] / crow) if crow > 0 else None,
        "implied_kmh": (res["distance_m"] / 1000) / (res["duration_s"] / 3600)
        if res["duration_s"] else None,
        "steps": len(res["steps"]),
        "nodes": len(keys) if keys else None,
    }
    runs = road_runs(g, keys) if keys else []
    report["runs"] = runs
    report["oneway_m"] = sum(r["length_m"] for r in runs if r["oneway"])
    report["unnamed_m"] = sum(r["length_m"] for r in runs if not r["name"])
    by_class: Dict[str, float] = {}
    for r in runs:
        by_class[r["highway"] or "?"] = by_class.get(r["highway"] or "?", 0.0) + r["length_m"]
    report["by_class_m"] = dict(sorted(by_class.items(), key=lambda kv: -kv[1]))

    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 0

    def ep(d: Dict[str, Any]) -> None:
        print(f"  {d['label']:<12} requested {d['requested'][1]:.5f},{d['requested'][0]:.5f}")
        if "error" in d:
            print(f"               ** {d['error']} **")
            return
        print(f"               snapped   {d['snapped'][1]:.5f},{d['snapped'][0]:.5f}"
              f"   ({d['snap_m']:.0f} m away)")
        print(f"               out-edges {d['out_edges']}  classes={','.join(d['classes'])}")
        if d["names"]:
            print(f"               on        {', '.join(d['names'][:3])}")
        if d["nearest_any_m"] is not None and d["snap_m"] - d["nearest_any_m"] > 5:
            flag = "" if d["nearest_any_is_routable"] else "  (that node has no out-edges)"
            print(f"               NOTE: nearest node of ANY kind was "
                  f"{d['nearest_any_m']:.0f} m away{flag}")

    print("\n=== endpoints ===")
    ep(report["origin"])
    ep(report["destination"])

    print("\n=== route ===")
    print(f"  crow          {crow/1000:8.2f} km")
    print(f"  road          {report['route_m']/1000:8.2f} km"
          f"   detour {report['detour']:.2f}x")
    print(f"  duration      {report['duration_s']/60:8.1f} min"
          f"   implied {report['implied_kmh']:.0f} km/h")
    print(f"  maneuvers     {report['steps']:8d}   nodes {report['nodes']}")
    print(f"  one-way       {report['oneway_m']/1000:8.2f} km")
    print(f"  unnamed       {report['unnamed_m']/1000:8.2f} km")

    print("\n=== distance by road class ===")
    for cls, m in report["by_class_m"].items():
        print(f"  {cls:<18} {m/1000:7.2f} km")

    print(f"\n=== road runs (longest {args.top}) ===")
    for r in sorted(runs, key=lambda x: -x["length_m"])[:args.top]:
        ow = " ONEWAY" if r["oneway"] else ""
        ms = f" {r['maxspeed']:.0f}km/h" if r["maxspeed"] else " (no maxspeed)"
        print(f"  {r['length_m']:7.0f} m  {r['highway'] or '?':<14} "
              f"{(r['name'] or '<unnamed>')[:40]:<42}{ms}{ow}")

    print(f"\n=== path order (first {args.top}) ===")
    for r in runs[:args.top]:
        ow = " ONEWAY" if r["oneway"] else ""
        print(f"  {r['length_m']:7.0f} m  {r['highway'] or '?':<14} "
              f"{(r['name'] or '<unnamed>')[:40]}{ow}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
