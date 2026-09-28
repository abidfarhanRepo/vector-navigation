"""The barrier catalog and the pedestrian-graph surgery it drives (V7.4 4A).

Bootstrap bakes ``<region>_barriers.geojson`` beside the road and foot
networks — a FeatureCollection of ``kind=barrier`` points carrying the OSM
node id (feature ``id`` = ``n<osm>``), the ingestion decision
``pedestrian_effect`` (``"block"`` | ``"pass"``) and the raw OSM provenance
tags (``barrier``/``access``/``foot``/``locked``). This module loads THAT
artifact and turns it into one statement about the walking graph: **a person
may not pass through a blocked barrier node.**

Why the decision is made upstream (``vector_ingestion.classify.
barrier_pedestrian_effect``), not here: it is the same arrangement as the
``car``/``foot`` booleans — the routability decision is made once, at
ingestion, by the classifier that owns the OSM access model, and the engine
reads the result rather than re-deriving it. A second classifier living here
is how the map and the router would come to disagree about whether a gate is
locked.

How a node gets blocked, and why severing beats checking per search step:

    OSM models a gate as a node ON a way (the way's node list includes it), so
    the routing graph — keyed by 7-decimal-rounded coordinates — already has a
    node at the gate's position, with the way's edges running through it. A
    locked gate means the walking network does NOT run through that point, so
    the honest graph is one with every edge incident to that node removed. The
    graph is severed ONCE at load, for three reasons:

    * ``label_components()`` then labels the post-blocking graph, so the
      component census, the ``component`` a walk reports, and
      ``PedestrianNetworkSplitError`` all describe the network a person can
      actually use rather than the one OSM drew;
    * endpoint snapping (``FootRouter._candidates``) only offers DEPARTABLE
      nodes, so a blocked node can never be walked to or from;
    * the per-request A* never pays for the check.

    A blocked node keeps its coordinate in ``_nodes`` (the node count stays
    honest: "N nodes, M of them behind locked gates") but has empty adjacency,
    so it is invisible to routing and to component labelling.

## What is deliberately NOT here

``effect == "pass"`` barriers are loaded and counted, never applied. Keeping
the full catalog in the router's view is what lets ``/footz`` answer "how many
of the 8,715 mapped barriers are actually enforced?" instead of the reader
having to guess from the file.

A missing catalog is a valid state (a deployment baked before this step, or an
Overpass-mode bake before barrier nodes were fetched): the graph is not severed
at all and every walk is exactly as it was. The absence is reported loudly on
``/footz``, the same way an absent foot graph is.
"""

import json
import logging
import math
import os
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from .graph import RoutingGraph

logger = logging.getLogger("vector_routing.barriers")

Coord = Tuple[float, float]

#: The effect values the artifact may carry. Absent is tolerated (treated as
#: pass) but counted, because "the file says nothing about this barrier" and
#: "this barrier was surveyed as passable" have different causes and the
#: operator should be able to tell them apart.
BLOCK = "block"
PASS = "pass"
_EFFECTS = (BLOCK, PASS)


class BarrierCatalog:
    """The baked barrier points, loaded defensively and never mutated.

    ``__len__`` of an absent or unreadable file is 0 — a missing catalog is
    the normal state of a pre-V7.4 deployment, not an error.
    """

    def __init__(self, barriers: List[Dict[str, Any]], stats: Dict[str, int]) -> None:
        self._barriers = barriers
        self.stats = stats

    def __len__(self) -> int:
        return len(self._barriers)

    def __iter__(self):
        return iter(self._barriers)

    @classmethod
    def from_feature_collection(cls, fc: Dict[str, Any]) -> "BarrierCatalog":
        out: List[Dict[str, Any]] = []
        stats: Dict[str, int] = {"features": 0, "block": 0, "pass": 0,
                                 "degraded": 0, "skipped": 0}
        for f in fc.get("features", []) or []:
            props = f.get("properties") or {}
            if props.get("kind") != "barrier":
                stats["skipped"] += 1
                continue
            geo = f.get("geometry") or {}
            coords = geo.get("coordinates")
            if not coords or len(coords) != 2:
                stats["skipped"] += 1
                continue
            lon = lat = None
            try:
                lon, lat = float(coords[0]), float(coords[1])
            except (TypeError, ValueError):
                stats["skipped"] += 1
                continue
            if not (math.isfinite(lon) and math.isfinite(lat)):
                stats["skipped"] += 1
                continue
            effect = str(props.get("pedestrian_effect") or "").strip().lower()
            if effect not in _EFFECTS:
                # Absent/unknown effect degrades to PASS, not BLOCK. Blocking
                # on a mis-baked artifact severs legitimate crossings; passing
                # on one reproduces the known pre-barrier behaviour and is
                # reported in the stats so it cannot stay silent.
                effect = PASS
                stats["degraded"] += 1
            stats["features"] += 1
            stats[effect] += 1
            out.append({
                # The feature id is the stable OSM identity (`n<id>`) and is
                # what operators use to find the gate in OSM.
                "id": f.get("id") or f"barrier-{len(out)}",
                "lon": lon,
                "lat": lat,
                "effect": effect,
                # Provenance, kept alive for /footz reporting and logs. NEVER a
                # second classification authority: the engine does not decide
                # from these, it reports them.
                "barrier": props.get("barrier"),
                "access": props.get("access"),
                "foot": props.get("foot"),
                "locked": props.get("locked"),
            })
        return cls(out, stats)

    @classmethod
    def from_path(cls, path: Optional[str]) -> "BarrierCatalog":
        if not path or not os.path.exists(path):
            return cls([], {"features": 0, "block": 0, "pass": 0,
                            "degraded": 0, "skipped": 0})
        try:
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)
            if isinstance(doc, dict):
                return cls.from_feature_collection(doc)
            return cls([], {})
        except (OSError, ValueError):
            logger.warning("barrier catalog unreadable, continuing without it: %s", path)
            return cls([], {})

    def describe(self) -> Dict[str, Any]:
        return dict(self.stats)


def blocked_node_keys(
    graph: RoutingGraph, catalog: BarrierCatalog
) -> Tuple[Set[str], Dict[str, int]]:
    """The graph node keys a pedestrian may not pass, plus why some did not apply.

    A barrier is applied by coordinate: its (lon, lat) is rounded to a graph
    node key, and the node with that key is blocked — which only works when the
    barrier actually sits ON a foot-routable way (the way's node list carried
    it into the graph). The recon measured 8,573 of 8,715 Qatar barrier nodes
    on foot ways; the rest are on car-only roads, where they concern drivers,
    not walkers, and are reported as unmatched rather than silently dropped.

    Only ``effect=block`` barriers sever anything. ``pass`` barriers are kept
    for the stats but never applied.

    ``stats`` carries: ``features``, ``block``, ``pass`` (from the catalog),
    ``matched`` (block coords that landed on a graph node), ``unmatched``
    (block coords with no graph node — off the walking network), and
    ``inert`` (matched but with no outgoing edges — already severed or a
    dead-end, so there was nothing to remove).
    """
    if graph is None or len(catalog) == 0:
        return set(), {"matched": 0, "unmatched": 0, "inert": 0}
    blocked: Set[str] = set()
    matched = 0
    unmatched = 0
    for b in catalog._barriers:
        if b["effect"] != BLOCK:
            continue
        key = RoutingGraph.node_key(b["lon"], b["lat"])
        if key not in graph._nodes:
            unmatched += 1
            continue
        blocked.add(key)
        matched += 1
    inert = sum(1 for k in blocked if not graph._adjacency.get(k))
    stats = {
        "matched": matched,
        "unmatched": unmatched,
        "inert": inert,
    }
    return blocked, stats


def sever_blocked_nodes(graph: RoutingGraph, blocked: Set[str]) -> int:
    """Remove every edge incident to a blocked node; return edges removed.

    Mutates the graph in place. The graph is loaded once and severed once;
    afterwards the blocked nodes have empty adjacency, so they cannot be
    snapped to, labelled, or routed through. Editing the adjacency lists in
    place (``adj.clear()`` / ``adj[:] = kept``) matters: the service holds the
    same ``RoutingGraph`` object it will hand to ``FootRouter``, and a
    replacement would desynchronise the two.
    """
    removed = 0
    for src, adj in graph._adjacency.items():
        if src in blocked:
            removed += len(adj)
            adj.clear()
            continue
        kept = [(to, w, p) for to, w, p in adj if to not in blocked]
        removed += len(adj) - len(kept)
        adj[:] = kept
    return removed


def apply_barriers(
    graph: RoutingGraph, catalog: BarrierCatalog
) -> Dict[str, Any]:
    """Sever every blocked node out of ``graph``; return the full stats.

    The one call the service makes. Everything the wire reports is derived
    from the returned dict, so an operator reading ``/footz`` and an operator
    reading the startup log see the same numbers.
    """
    blocked, stats = blocked_node_keys(graph, catalog)
    stats["edges_removed"] = sever_blocked_nodes(graph, blocked)
    stats["blocked"] = len(blocked)
    return stats