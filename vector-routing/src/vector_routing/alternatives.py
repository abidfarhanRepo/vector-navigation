"""Alternative routes: more than one way to get there.

Every consumer navigator offers two or three options, and Vector offered one.
`/route?alternatives=1` was accepted and ignored — the parameter existed, the
Python engine only ever returned a single path, and a client asking for choices
silently got none.

## The method: iterative edge penalisation

Compute the best route. Multiply the cost of the edges it used by a penalty and
compute again; the search now prefers a different corridor where one exists.
Repeat for each additional route wanted.

This is the standard "plateau-free" approach and it is deliberately NOT the
k-shortest-paths algorithm, which returns near-identical routes differing by a
single junction — technically distinct, useless to a driver. Penalisation
produces genuinely different corridors, which is what "an alternative" means to
the person holding the phone.

## What makes an alternative worth showing

Two filters, both about the driver rather than the graph:

* **Distinct enough.** A route sharing most of its length with the best one is
  the same route. `MAX_OVERLAP` rejects it.
* **Good enough.** A route 80% slower is not an option, it is a mistake.
  `MAX_DETOUR` rejects it.

Both are the reason a naive implementation produces three routes nobody wants.
"""

from typing import Any, Callable, Dict, List, Optional, Set, Tuple

# How much more expensive an already-used edge becomes on the next pass. Large
# enough to push the search into another corridor, small enough that it will
# still reuse a motorway when that genuinely is the only sensible way through.
DEFAULT_PENALTY = 3.0

# Reject an "alternative" that shares more than this fraction of the best
# route's edges — it is the same road with a different junction.
MAX_OVERLAP = 0.7

# Reject an alternative slower than this multiple of the best route's time.
MAX_DETOUR = 1.6


def _edge_set(keys: List[str]) -> Set[Tuple[str, str]]:
    return set(zip(keys, keys[1:]))


def overlap_fraction(a: List[str], b: List[str]) -> float:
    """Fraction of `b`'s edges that also appear in `a` (0.0 = fully distinct)."""
    ea, eb = _edge_set(a), _edge_set(b)
    if not eb:
        return 1.0
    return len(ea & eb) / len(eb)


def find_alternatives(
    route_fn: Callable[[Optional[Callable]], Any],
    wanted: int = 2,
    penalty: float = DEFAULT_PENALTY,
    max_overlap: float = MAX_OVERLAP,
    max_detour: float = MAX_DETOUR,
) -> List[Any]:
    """Return up to `wanted` routes, best first.

    ``route_fn(weight_multiplier)`` must compute a route, applying an optional
    ``multiplier(u, to) -> float`` to edge costs. Passing the search in as a
    callable keeps this module free of any dependency on the graph, the cost
    model, or the overlays — all of which the caller already owns.

    Always returns at least the primary route (or raises whatever ``route_fn``
    raises for an unroutable pair). Fewer than `wanted` results simply means the
    network offers no distinct sensible option, which is common and correct: on
    a peninsula there is often exactly one way.
    """
    primary = route_fn(None)
    out = [primary]
    if wanted <= 1:
        return out

    penalised: Set[Tuple[str, str]] = set()
    best_duration = getattr(primary, "duration_s", 0.0) or 0.0

    for _ in range(wanted - 1):
        penalised |= _edge_set(primary.node_keys if not out else out[-1].node_keys)

        def multiplier(u: str, to: str, _p=penalised) -> float:
            return penalty if (u, to) in _p else 1.0

        try:
            candidate = route_fn(multiplier)
        except Exception:
            break   # unroutable once penalised: no further alternative exists

        if any(overlap_fraction(r.node_keys, candidate.node_keys) > max_overlap for r in out):
            break   # same corridor; further passes will keep finding it
        cand_duration = getattr(candidate, "duration_s", 0.0) or 0.0
        if best_duration > 0 and cand_duration > best_duration * max_detour:
            break   # too much worse to be an option
        out.append(candidate)

    return out


def describe(routes: List[Any]) -> List[Dict[str, Any]]:
    """Per-route summary for the API: id, distance, duration, and the delta.

    The delta is what a driver actually compares — "4 minutes slower" is the
    decision, not "just now, 923 seconds".
    """
    if not routes:
        return []
    base = getattr(routes[0], "duration_s", 0.0) or 0.0
    out = []
    for i, r in enumerate(routes):
        d = getattr(r, "duration_s", 0.0) or 0.0
        out.append({
            "route_id": i,
            "primary": i == 0,
            "distance_m": round(getattr(r, "distance_m", 0.0), 1),
            "duration_s": round(d, 1),
            "duration_delta_s": round(d - base, 1),
        })
    return out
