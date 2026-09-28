"""CLI entrypoint: build a tiny sample, run traffic estimation, print a summary."""

import json
import os

from .loaders import build_sample_segments, build_sample_probes
from .traffic import TrafficModel

_DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "traffic-data")
_SEGMENTS_FILE = os.path.join(_DATA_DIR, "sample_segments.geojson")
_PROBES_FILE = os.path.join(_DATA_DIR, "sample_probes.geojson")


def _load_sample():
    try:
        with open(_SEGMENTS_FILE, "r", encoding="utf-8") as fh:
            segments = json.load(fh)
        with open(_PROBES_FILE, "r", encoding="utf-8") as fh:
            probes = json.load(fh)
        from .segments import load_segments
        from .probe import load_probes

        return load_segments(segments), load_probes(probes)
    except (OSError, ValueError):
        return build_sample_segments(), build_sample_probes()


def main() -> dict:
    segments, probes = _load_sample()
    model = TrafficModel()
    results = model.estimate(segments, probes)

    with_traffic = sum(1 for r in results if r.probe_count > 0)
    unknown = sum(1 for r in results if r.congestion == "unknown")
    matched = sum(r.probe_count for r in results)

    summary = {
        "status": "ok",
        "service": "vector-traffic",
        "segments": len(results),
        "with_traffic": with_traffic,
        "unknown": unknown,
        "total_probe_count": len(probes),
        "matched": matched,
    }
    print(summary)
    return summary


if __name__ == "__main__":
    main()
