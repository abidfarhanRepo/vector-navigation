"""Map-matching of probes to road segments for vector-traffic."""

from dataclasses import dataclass
from typing import List, Optional, Tuple

from .errors import MapMatchError
from .geometry import point_to_segment_distance_m
from .probe import Probe
from .segments import RoadSegment


@dataclass
class MatchResult:
    segment_id: str
    distance_m: float
    point: Tuple[float, float]
    speed_kmh: Optional[float]


def _segment_distance(point: Tuple[float, float], geometry: List[Tuple[float, float]]) -> float:
    """Minimum point-to-sub-segment distance over a polyline (earliest wins ties)."""
    best: Optional[float] = None
    for i in range(len(geometry) - 1):
        d = point_to_segment_distance_m(point, geometry[i], geometry[i + 1])
        if best is None or d < best:
            best = d
    assert best is not None
    return best


def match_probe(
    probe: Probe,
    segments: List[RoadSegment],
    max_match_radius_m: float = 100.0,
) -> MatchResult:
    """Match a single probe to its nearest segment.

    Raises ``MapMatchError`` if no segment is within ``max_match_radius_m``.
    Deterministic: ties broken by earliest segment, then earliest sub-segment.
    """
    if not segments:
        raise MapMatchError("no segments available to match")

    best_seg = None
    best_dist: Optional[float] = None
    for seg in segments:
        d = _segment_distance((probe.lon, probe.lat), seg.geometry)
        if best_dist is None or d < best_dist:
            best_dist = d
            best_seg = seg

    assert best_seg is not None and best_dist is not None
    if best_dist > max_match_radius_m:
        raise MapMatchError(
            f"probe ({probe.lon},{probe.lat}) not within match radius {max_match_radius_m}m "
            f"(nearest {best_dist:.1f}m)"
        )
    return MatchResult(
        segment_id=best_seg.id,
        distance_m=best_dist,
        point=(probe.lon, probe.lat),
        speed_kmh=probe.speed_kmh,
    )


def match_probes(
    probes: List[Probe],
    segments: List[RoadSegment],
    max_match_radius_m: float = 100.0,
) -> Tuple[List[MatchResult], int]:
    """Match all probes. Returns ``(matched_results, dropped_count)``.

    Probes that cannot be matched within ``max_match_radius_m`` are dropped and
    counted.
    """
    results: List[MatchResult] = []
    dropped = 0
    for probe in probes:
        try:
            results.append(match_probe(probe, segments, max_match_radius_m))
        except MapMatchError:
            dropped += 1
    return (results, dropped)
