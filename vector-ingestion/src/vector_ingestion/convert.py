"""OSM -> routing-graph converter (Wave 27a — Real OSM Ingestion MVP).

Turns a real ``.osm`` extract into the GeoJSON LineString FeatureCollection the
routing engine consumes (``build_graph_from_features`` contract), and can
optionally build the ``RoutingGraph`` to prove the data flows end-to-end.

Stdlib-only for the parse/emit path; the optional graph-build step uses the
vendored ``vector_geo`` (single-sourced, ADR-0007).
"""

import json
import os
import sys
from typing import Any, Dict, Optional

from .osm import parse_osm_file


def convert_osm_to_graph(osm_path: str, out_path: str,
                         basemap_path: Optional[str] = None) -> Dict[str, Any]:
    """Parse ``osm_path`` and write a routing-graph GeoJSON to ``out_path``.

    If ``basemap_path`` is provided, every parsed feature (roads AND basemap
    areas/labels) is also written there so the tile pipeline can build a full
    basemap from a single rich extract.

    Returns a stats dict: {ways, features, bbox, out_path, basemap_path}.
    """
    fc = parse_osm_file(osm_path)
    features = fc.get("features", [])

    # Compute bbox over all coordinates (handles Point/LineString/Polygon).
    min_lon = min_lat = float("inf")
    max_lon = max_lat = float("-inf")

    def _walk(c: Any) -> None:
        if isinstance(c, (list, tuple)) and c and isinstance(c[0], (int, float)):
            nonlocal min_lon, min_lat, max_lon, max_lat
            lon, lat = float(c[0]), float(c[1])
            min_lon = min(min_lon, lon)
            min_lat = min(min_lat, lat)
            max_lon = max(max_lon, lon)
            max_lat = max(max_lat, lat)
        elif isinstance(c, (list, tuple)):
            for sub in c:
                _walk(sub)

    for f in features:
        _walk(f["geometry"]["coordinates"])

    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(fc, fh, ensure_ascii=False)

    if basemap_path:
        with open(basemap_path, "w", encoding="utf-8") as fh:
            json.dump(fc, fh, ensure_ascii=False)

    return {
        "ways": len(features),
        "features": len(features),
        "bbox": [min_lon, min_lat, max_lon, max_lat],
        "out_path": out_path,
        "basemap_path": basemap_path,
    }


def build_routing_graph(graph_geojson_path: str):
    """Build a real ``RoutingGraph`` from the converted GeoJSON.

    Uses the vendored ``vector_geo`` so we prove the ingested OSM data flows
    into the exact graph structure routing serves. Raises if import missing.
    """
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "vendor"))
    from vector_geo.algorithms import build_graph_from_features  # type: ignore

    with open(graph_geojson_path, "r", encoding="utf-8") as fh:
        fc = json.load(fh)
    return build_graph_from_features(fc)


def main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="vector-ingestion: OSM -> routing graph")
    parser.add_argument("osm", help="path to .osm extract")
    parser.add_argument("--out", default="graph.geojson", help="output GeoJSON path")
    parser.add_argument(
        "--basemap",
        default=None,
        help="optional second GeoJSON output containing ALL parsed features "
             "(roads + basemap areas/labels) for the tile pipeline",
    )
    parser.add_argument(
        "--validate-graph",
        action="store_true",
        help="build the RoutingGraph via vendored vector_geo to prove end-to-end flow",
    )
    args = parser.parse_args(argv)

    stats = convert_osm_to_graph(args.osm, args.out, args.basemap)
    print("ingested OSM -> %s" % args.out)
    print("  ways/features: %d" % stats["features"])
    print("  bbox: %.5f,%.5f .. %.5f,%.5f" % tuple(stats["bbox"]))
    if stats.get("basemap_path"):
        print("  basemap -> %s" % stats["basemap_path"])

    if args.validate_graph:
        g = build_routing_graph(args.out)
        print("  RoutingGraph nodes=%d edges=%d" % (len(g.nodes()), len(g.edges())))
        if len(g.edges()) == 0:
            print("  ERROR: zero edges built", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
