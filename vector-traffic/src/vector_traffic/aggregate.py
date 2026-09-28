"""Congestion aggregation for vector-traffic."""

from typing import Dict, List, Optional

from .match import MatchResult
from .segments import RoadSegment


#: Probes a segment needs before its congestion state is offered to a CLIENT.
#:
#: One probe is one vehicle, and one vehicle can be parked, turning, or a phone
#: on a bus. Declaring a road jammed on that evidence is not a measurement, it
#: is a guess presented as a fact — and a driver diverted by it has been sent
#: the long way round for nothing.
#:
#: This threshold is what separates a real jam from the SYNTHETIC demo data the
#: stack currently carries. `loaders.build_graph_probes` places exactly one
#: probe per segment and marks every thirteenth edge jammed, so `/traffic`
#: answered with 2,000 segments, 154 of them "jammed" — and the native HUD
#: dutifully reported "154 jams" over Doha, a number derived from `i % 13`. The
#: V3 brief is explicit that traffic must not be claimed as live if it is
#: decorative; with a floor of 3 those fabricated jams disappear, which is the
#: truthful answer for a system that has consumed zero real drives.
#:
#: It is a DISPLAY floor, applied where congestion is served to clients. The
#: bus/overlay path that feeds traffic-aware routing is deliberately untouched
#: here: changing which roads the router avoids is a separate decision with its
#: own evidence, and bundling the two would make one change impossible to
#: evaluate.
MIN_PROBES_FOR_CONGESTION = 3

def congestion_level(speed_kmh: Optional[float], free_flow_kmh: float) -> str:
    """Map a speed to a congestion bucket relative to free-flow speed."""
    if speed_kmh is None:
        return "unknown"
    ratio = max(0.0, min(1.0, speed_kmh / free_flow_kmh))
    if ratio >= 0.85:
        return "free"
    if ratio >= 0.65:
        return "light"
    if ratio >= 0.45:
        return "moderate"
    if ratio >= 0.25:
        return "heavy"
    return "jammed"


def aggregate(matches: List[MatchResult], segments: List[RoadSegment]) -> Dict[str, dict]:
    """Aggregate matched probes into per-segment traffic stats.

    Returns a dict keyed by ``segment_id`` with ``mean_speed_kmh`` (rounded to 2
    decimals), ``probe_count``, ``congestion`` and ``free_flow_kmh``. Segments
    with no matches are omitted.
    """
    free_flow_by_id = {s.id: s.free_flow_kmh for s in segments}
    groups: Dict[str, List[MatchResult]] = {}
    for m in matches:
        groups.setdefault(m.segment_id, []).append(m)

    out: Dict[str, dict] = {}
    for seg_id, ms in groups.items():
        speeds = [m.speed_kmh for m in ms if m.speed_kmh is not None]
        mean = round(sum(speeds) / len(speeds), 2) if speeds else None
        free_flow = free_flow_by_id.get(seg_id, 50.0)
        out[seg_id] = {
            "mean_speed_kmh": mean,
            "probe_count": len(ms),
            "congestion": congestion_level(mean, free_flow),
            "free_flow_kmh": free_flow,
        }
    return out
