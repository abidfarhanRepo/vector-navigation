"""TrafficModel facade for vector-traffic (stdlib-only, self-contained)."""

from dataclasses import dataclass
from typing import List, Optional

from .aggregate import aggregate
from .match import MatchResult, match_probes
from .probe import Probe
from .segments import RoadSegment


@dataclass
class TrafficSegment:
    segment_id: str
    geometry: List[tuple]
    free_flow_kmh: float
    mean_speed_kmh: Optional[float]
    probe_count: int
    congestion: str


class TrafficModel:
    """Estimate per-segment speed and congestion from crowdsourced probes."""

    def estimate(
        self,
        segments: List[RoadSegment],
        probes: List[Probe],
        max_match_radius_m: float = 100.0,
    ) -> List[TrafficSegment]:
        matches, _dropped = match_probes(probes, segments, max_match_radius_m)
        agg = aggregate(matches, segments)
        results: List[TrafficSegment] = []
        for seg in segments:
            a = agg.get(seg.id)
            if a is not None:
                results.append(
                    TrafficSegment(
                        segment_id=seg.id,
                        geometry=seg.geometry,
                        free_flow_kmh=seg.free_flow_kmh,
                        mean_speed_kmh=a["mean_speed_kmh"],
                        probe_count=a["probe_count"],
                        congestion=a["congestion"],
                    )
                )
            else:
                results.append(
                    TrafficSegment(
                        segment_id=seg.id,
                        geometry=seg.geometry,
                        free_flow_kmh=seg.free_flow_kmh,
                        mean_speed_kmh=None,
                        probe_count=0,
                        congestion="unknown",
                    )
                )
        return results

    def to_geojson(self, results: List[TrafficSegment]) -> dict:
        features = []
        for r in results:
            features.append(
                {
                    "type": "Feature",
                    "properties": {
                        "segment_id": r.segment_id,
                        "free_flow_kmh": r.free_flow_kmh,
                        "mean_speed_kmh": r.mean_speed_kmh,
                        "probe_count": r.probe_count,
                        "congestion": r.congestion,
                    },
                    "geometry": {
                        "type": "LineString",
                        "coordinates": [[lon, lat] for lon, lat in r.geometry],
                    },
                }
            )
        return {"type": "FeatureCollection", "features": features}

    def estimate_geojson(
        self,
        segments: List[RoadSegment],
        probes: List[Probe],
        max_match_radius_m: float = 100.0,
    ) -> dict:
        return self.to_geojson(self.estimate(segments, probes, max_match_radius_m))


def estimate_traffic(
    segments: List[RoadSegment],
    probes: List[Probe],
    max_match_radius_m: float = 100.0,
) -> List[TrafficSegment]:
    return TrafficModel().estimate(segments, probes, max_match_radius_m)
