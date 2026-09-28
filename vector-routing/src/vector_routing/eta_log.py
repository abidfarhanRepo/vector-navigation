"""ETA-error recording for vector-routing (issue 07 -> issue 10, stdlib only).

The falsifiable claim of the whole self-evolving effort:

    Routes over learned segments should get measurably better than routes over
    unlearned ones.

Without this log, "self-evolving" is unfalsifiable — you cannot tell a system
that learns from one that merely has a learning pipeline. So every completed
navigation records what was predicted against what was actually observed, and
whether the route had learned speed coverage.

Privacy note: an ETA sample carries **no location and no identity** — only a
predicted duration, an observed duration, and the learned-coverage fraction of
the route. A travel time on its own is not personal data; a travel time plus an
origin/destination pair would be, so neither is stored here.

Issue 10 reads :meth:`EtaErrorLog.summary` for the distribution split.
"""

from __future__ import annotations

import json
import math
import os
import threading
from dataclasses import dataclass
from typing import Dict, List, Optional

# A route is counted as "learned" when at least this fraction of its edges had
# a confident speed profile. Below it, the route is dominated by OSM defaults
# and would pollute the learned bucket.
LEARNED_COVERAGE_THRESHOLD = 0.5


@dataclass
class EtaSample:
    """One completed navigation. No coordinates, no identity, by construction.

    ``distance_m`` is optional and anonymous (route length, not a location):
    it lets future calibration judge errors per kilometre instead of per
    trip, which is what turns 18 trips into a speed-model correction.
    """

    predicted_s: float
    observed_s: float
    coverage: float  # 0.0..1.0 fraction of route edges with a learned profile
    distance_m: Optional[float] = None

    @property
    def is_learned(self) -> bool:
        return self.coverage >= LEARNED_COVERAGE_THRESHOLD

    @property
    def error_s(self) -> float:
        """Signed error: positive means the route took longer than predicted."""
        return self.observed_s - self.predicted_s

    @property
    def abs_pct_error(self) -> float:
        """Absolute percentage error, the scale-free comparison across trips."""
        if self.observed_s <= 0:
            return 0.0
        return abs(self.error_s) / self.observed_s * 100.0

    def to_row(self) -> Dict[str, float]:
        row = {
            "predicted_s": round(self.predicted_s, 2),
            "observed_s": round(self.observed_s, 2),
            "coverage": round(self.coverage, 4),
        }
        if self.distance_m is not None:
            row["distance_m"] = round(self.distance_m, 1)
        return row


def _percentile(sorted_vals: List[float], q: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    rank = q * (len(sorted_vals) - 1)
    lo, hi = int(math.floor(rank)), int(math.ceil(rank))
    if lo == hi:
        return sorted_vals[lo]
    frac = rank - lo
    return sorted_vals[lo] * (1 - frac) + sorted_vals[hi] * frac


class EtaErrorLog:
    """Append-only log of ETA samples, split by learned coverage.

    Tracked as a *distribution*, not a mean (issue 10) — a mean hides the tail,
    and Bible P3 makes tail degradation a defect rather than a footnote.
    """

    def __init__(self, path: Optional[str] = None) -> None:
        self.path = path
        self._lock = threading.Lock()
        self._samples: List[EtaSample] = []
        if path:
            parent = os.path.dirname(os.path.abspath(path))
            if parent:
                os.makedirs(parent, exist_ok=True)
            self._load()

    def _load(self) -> None:
        if not self.path or not os.path.exists(self.path):
            return
        with open(self.path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                    self._samples.append(
                        EtaSample(
                            predicted_s=float(row["predicted_s"]),
                            observed_s=float(row["observed_s"]),
                            coverage=float(row.get("coverage", 0.0)),
                        )
                    )
                except (ValueError, KeyError, TypeError):
                    continue  # a corrupt line must not take the log down

    def record(self, predicted_s: float, observed_s: float, coverage: float = 0.0,
               distance_m: Optional[float] = None) -> EtaSample:
        """Record one completed navigation."""
        sample = EtaSample(
            predicted_s=float(predicted_s),
            observed_s=float(observed_s),
            coverage=max(0.0, min(1.0, float(coverage))),
            distance_m=float(distance_m) if distance_m is not None else None,
        )
        with self._lock:
            self._samples.append(sample)
            if self.path:
                with open(self.path, "a", encoding="utf-8") as fh:
                    fh.write(json.dumps(sample.to_row(), separators=(",", ":")) + "\n")
        return sample

    def summary(self) -> Dict[str, object]:
        """Error distribution overall and split by learned coverage.

        ``delta_p50`` is the headline number: median absolute percentage error
        on unlearned routes minus the same on learned routes. **Positive means
        learning is working** — learned routes predict better. If it sits at or
        below zero once coverage is non-trivial, issue 07 is not delivering and
        the promotion thresholds or the aggregation window need revisiting.
        """
        with self._lock:
            samples = list(self._samples)

        learned = [s for s in samples if s.is_learned]
        unlearned = [s for s in samples if not s.is_learned]
        out: Dict[str, object] = {
            "n": len(samples),
            "n_learned": len(learned),
            "n_unlearned": len(unlearned),
            "overall": _dist([s.abs_pct_error for s in samples]),
            "learned": _dist([s.abs_pct_error for s in learned]),
            "unlearned": _dist([s.abs_pct_error for s in unlearned]),
        }
        if learned and unlearned:
            out["delta_p50"] = round(
                float(out["unlearned"]["p50"]) - float(out["learned"]["p50"]), 3  # type: ignore[index]
            )
        else:
            out["delta_p50"] = None
        return out


def _dist(values: List[float]) -> Dict[str, float]:
    if not values:
        return {"n": 0, "p50": 0.0, "p90": 0.0, "p99": 0.0, "mean": 0.0}
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "p50": round(_percentile(ordered, 0.50), 3),
        "p90": round(_percentile(ordered, 0.90), 3),
        "p99": round(_percentile(ordered, 0.99), 3),
        "mean": round(sum(ordered) / len(ordered), 3),
    }
