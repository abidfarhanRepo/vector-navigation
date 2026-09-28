"""Exclusion accounting + the audit report.

Every record that does not become a canonical POI carries exactly one
EXCLUDED_* reason (see poi/model.py). The audit report reconciles the ledger:

    total raw = canonical + excluded(by reason) + unknown retained

and is written as JSON beside the outputs so the numbers can be argued with
rather than asserted.

The `map_layer` section (added by `pipeline.run_pipeline` from
`visibility.assign_map_visibility`) is a SECOND, orthogonal ledger: every
canonical POI is searchable, and `map_layer` counts how many of them earn a
visible MAP LABEL and what each policy rule costs. An excluded record is not a
POI at all; a map-hidden record is a POI the map declines to draw.
"""

from __future__ import annotations

from collections import Counter
from typing import Any, Dict, List


def build_audit(records: List[Dict[str, Any]],
                canonical: List[Dict[str, Any]],
                excluded: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Aggregate the excluded ledger + canonical summary.

    ``records`` is every raw record (pre-dedup), ``canonical`` the surviving
    canonical POI dicts, ``excluded`` the excluded records (with a single
    ``exclusion`` reason each).
    """
    total = Counter(r["source"] for r in records)
    excl_by_reason = Counter(r["exclusion"] for r in excluded if r["exclusion"])
    excl_by_source = Counter(
        (r["source"], r["exclusion"])
        for r in excluded if r["exclusion"]
    )
    fam = Counter(r["family"] for r in canonical)
    source_canon = Counter(r["source"] for r in canonical)
    cross = sum(1 for r in canonical if r.get("cross_source"))
    unknown_retained = sum(1 for r in canonical if r["family"] == "UNKNOWN")

    return {
        "total_raw": {
            "osm": total.get("osm", 0),
            "overture": total.get("overture", 0),
            "total": sum(total.values()),
        },
        "canonical": {
            "total": len(canonical),
            "by_source": dict(source_canon),
            "by_family": dict(fam),
            "cross_source_reconciled": cross,
            "unknown_retained": unknown_retained,
        },
        "excluded": {
            "total": len(excluded),
            "by_reason": dict(excl_by_reason),
            "by_reason_and_source": {
                f"{src}:{reason}": n for (src, reason), n in sorted(excl_by_source.items())
            },
        },
        "note": (
            "All numbers are computed from the actual source data by the "
            "pipeline run; they are not estimated. `confidence` is Overture "
            "conflation confidence and is NOT treated as freshness."
        ),
    }


def format_report(audit: Dict[str, Any]) -> str:
    a = audit
    t = a["total_raw"]
    c = a["canonical"]
    e = a["excluded"]
    lines = [
        "POI quality pipeline audit",
        "=========================",
        f"Total raw POIs:        {t['total']:>6d}  (OSM {t['osm']}, Overture {t['overture']})",
        f"Canonical POIs:        {c['total']:>6d}  "
        f"(of which {c['cross_source_reconciled']} cross-source reconciled)",
        f"  unknown retained:    {c['unknown_retained']:>6d}",
    ]
    for reason, n in sorted(e["by_reason"].items(), key=lambda kv: -kv[1]):
        lines.append(f"Excluded {reason:32s} {n:>6d}")
    m = a.get("map_layer")
    if m:
        by_src = ", ".join(f"{k} {v}" for k, v in sorted(
            (m.get("map_visible_by_source") or {}).items()))
        lines += [
            "",
            "Map / search split  (OSM -> map labels; Overture -> search only)",
            f"Searchable POIs:       {m.get('searchable', 0):>6d}  (every canonical record)",
            f"Map-visible POIs:      {m.get('map_visible', 0):>6d}  ({by_src})",
        ]
        for reason, n in sorted((m.get("map_hidden_by_first_reason") or {}).items(),
                                key=lambda kv: -kv[1]):
            lines.append(f"  hidden {reason:34s} {n:>6d}")
        cost = m.get("map_hidden_quality_cost_over_osm_primary") or {}
        if cost:
            lines.append("  what each quality rule costs the OSM-primary map layer:")
            for reason, n in sorted(cost.items(), key=lambda kv: -kv[1]):
                lines.append(f"    {reason:36s} {n:>6d}")
    return "\n".join(lines)