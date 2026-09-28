#!/usr/bin/env python3
"""How many Vector users, driving how much, before coverage moves? (ticket 14)

A **sensitivity model, not a measurement.** Nothing here is observed; every number
is an assumption you can change on the command line. Read the output as "if the
world looks like this, expect that" — never as a fact about Qatar.

The distinction matters because the first version of this script got it wrong in a
way that reached a governance record. It assumed 2,000 trips/day on a motorway and
concluded that 8 bands took K=5 clearance to 99.99% of the network. But evidence
only accrues from **Vector users' captured traces**, not from all traffic on the
road — and Vector has no users. The model was describing Qatar's traffic while
being read as describing Vector's data. Three smaller errors compounded it:
category assignment by exact float equality on maxspeed (so most segments silently
fell to a default), counting *categories* rather than *segments* as clearing, and a
"coverage" column that was never computed at all.

The honest question:

    Evidence accumulates per (segment, band). A bucket needs K=5 DISTINCT trips
    within the 72 h TTL. Bands recur weekly, so a weekday band gets ~3 of its
    occurrences inside the window and a weekend band ~2:

        trips_per_bucket ~= users x trips_per_user_per_day
                            x P(a trip uses this segment)
                            x band_occurrences_in_window

The dominating unknown is that middle term, and it is wildly uneven: a few
arterials carry most journeys while most residential segments are used only by the
people who live on them. That concentration is exactly why ticket 14 says to drive
a few corridors repeatedly rather than spreading coverage thin.

    python scripts/quantify-bands.py --users 1 5 20 100 --trips-per-user-per-day 4

Output is per *corridor class*, because a network-wide percentage is the least
informative view: it reads ~0% long after the roads people actually drive are well
learned.
"""

from __future__ import annotations

import argparse
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "vector-learning", "src"))
sys.path.insert(0, os.path.join(ROOT, "vector-learning", "vendor"))

from vector_learning.aggregate import BANDS_PER_WEEK, K_ANONYMITY_FLOOR  # noqa: E402

TTL_HOURS = 72

# How many of a band's occurrences fall inside a 72 h TTL window.
WEEKDAY_OCCURRENCES = 3.0
WEEKEND_OCCURRENCES = 2.0

# Band hour-spans, matching _BAND_DEFINITIONS in vector_learning.aggregate.
BAND_SPANS = {
    0: (6, "weekday"), 1: (4, "weekday"), 2: (4, "weekday"),
    3: (5, "weekday"), 4: (5, "weekday"),
    5: (12, "weekend"), 6: (6, "weekend"), 7: (6, "weekend"),
}

# Share of a user's daily trips that touch a segment of each class. These are
# ASSUMPTIONS, written down so they can be argued with. The spread matters far
# more than the absolute values.
SEGMENT_SHARE = {
    "their own residential street": 0.95,
    "the corridor they commute on": 0.90,
    "a main arterial they often use": 0.40,
    "a road they use weekly": 0.10,
    "a random residential street": 0.001,
}


def trips_per_bucket(users: float, trips_per_day: float, share: float, band: int) -> float:
    hours, day_type = BAND_SPANS[band]
    occurrences = WEEKDAY_OCCURRENCES if day_type == "weekday" else WEEKEND_OCCURRENCES
    in_band = trips_per_day * (hours / 24.0)
    return users * in_band * share * occurrences


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="band sensitivity model (ticket 14)")
    ap.add_argument("--users", type=float, nargs="+", default=[1, 5, 20, 100],
                    help="distinct Vector users contributing traces")
    ap.add_argument("--trips-per-user-per-day", type=float, default=4.0)
    ap.add_argument("--band", type=int, default=1,
                    help="band to model (default 1 = weekday AM peak, a 4h commute band)")
    args = ap.parse_args(argv)

    if args.band not in BAND_SPANS:
        print(f"band must be 0..{BANDS_PER_WEEK - 1}", file=sys.stderr)
        return 2

    hours, day_type = BAND_SPANS[args.band]
    occurrences = WEEKDAY_OCCURRENCES if day_type == "weekday" else WEEKEND_OCCURRENCES
    print("MODEL, not measurement. Every input below is an assumption.\n")
    print(f"  band {args.band}: {hours}h {day_type}, ~{occurrences:.0f} occurrences "
          f"inside the {TTL_HOURS}h TTL")
    print(f"  K floor: {K_ANONYMITY_FLOOR} distinct trips    bands: {BANDS_PER_WEEK}")
    print(f"  trips per user per day: {args.trips_per_user_per_day}\n")

    width = max(len(k) for k in SEGMENT_SHARE)
    header = f"  {'segment class':<{width}} " + "".join(f"{int(u):>9}u" for u in args.users)
    print(header)
    print("  " + "-" * (len(header) - 2))
    for label, share in SEGMENT_SHARE.items():
        cells = []
        for users in args.users:
            n = trips_per_bucket(users, args.trips_per_user_per_day, share, args.band)
            cells.append(f"{n:>8.1f}{'*' if n >= K_ANONYMITY_FLOOR else ' '}")
        print(f"  {label:<{width}} " + "".join(cells))

    print(f"\n  * = clears K={K_ANONYMITY_FLOOR}, so this bucket can emit evidence")
    print("\n  Read it as: a handful of committed drivers learn their own commute quickly,")
    print("  while network-wide coverage stays near zero for a very long time regardless.")
    print("  That is why ticket 14 tracks per-corridor progress rather than a percentage")
    print("  of 181,546 segments — the percentage will understate real progress for months.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
