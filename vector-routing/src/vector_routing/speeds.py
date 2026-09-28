"""The speed model: what speed does Vector assume for a road, and why.

This module exists because the previous answer was "``maxspeed`` if tagged, else
a flat 30 km/h", and that is wrong in a way that affects **route choice**, not
just ETA. Cost is length / speed, so an untagged trunk road was costed at
30 km/h while a tagged residential street beside it was costed at 50 — and the
router preferred the residential street.

Measured on the Qatar extract (141,283 car-routable ways):

    OVERALL   13,984 / 141,283 = 10% of ways carry a usable maxspeed.

    class            tagged   median   p25   p75
    motorway            745      120   100   120
    trunk             1,215       80    80   100
    trunk_link          442       80    50    80
    primary           1,724       80    60    80
    secondary         1,819       80    60    80
    tertiary          2,261       50    50    60
    unclassified        852       50    50    80
    residential       3,375       50    50    50
    living_street       120       20    15    20
    service             319       40    25    50
    motorway_link       363       60    50    80
    primary_link        456       60    50    80
    secondary_link      169       60    50    80
    tertiary_link       116       50    50    50
    track                 8       70    70    70

So 77% of trunks and 81% of motorway_links were being costed at 30 km/h.

The fix is the standard one: fall back to a per-CLASS default rather than a
single number. The defaults below are the observed medians **from this dataset**,
which makes them regional rather than invented, and each is annotated with the
sample size it came from.

These are FREE-FLOW speeds — the speed limit, essentially. They are not a claim
about real journey times in Doha traffic. Two layers sit on top and are the
right place for reality to enter:

  * the live congestion overlay (``traffic_overlay``), and
  * the learned per-segment speeds promoted from real drives
    (``learned_overlay``, adr-0066), which have consumed zero real trips so far.

Do not "correct" these numbers downward to make ETAs resemble another product.
The honest statement for V1 is: this is a deterministic free-flow baseline, and
it reads optimistic against a traffic-aware reference until real drives exist.
"""

from typing import Any, Dict, Optional

# Per-class free-flow default, km/h. Applied ONLY when the way carries no usable
# maxspeed tag. Sample counts are from the table in the module docstring.
CLASS_DEFAULT_KMH: Dict[str, float] = {
    "motorway": 120.0,        # n=745
    "trunk": 80.0,            # n=1215
    "primary": 80.0,          # n=1724
    "secondary": 80.0,        # n=1819
    "tertiary": 50.0,         # n=2261
    "unclassified": 50.0,     # n=852
    "residential": 50.0,      # n=3375
    "living_street": 20.0,    # n=120
    "service": 40.0,          # n=319
    # Observed median is 70, from EIGHT samples. That is not evidence, and a
    # track is an unsealed desert road. Overridden deliberately; if a track is
    # genuinely fast it will be tagged.
    "track": 30.0,
    # No tagged samples at all in this extract. A conservative mid value rather
    # than an invented one.
    "road": 40.0,
    "motorway_link": 60.0,    # n=363
    "trunk_link": 80.0,       # n=442
    "primary_link": 60.0,     # n=456
    "secondary_link": 60.0,   # n=169
    "tertiary_link": 50.0,    # n=116
}

# Used when the class is unknown too. Deliberately slow: an unknown road should
# not be attractive to the router.
FALLBACK_KMH = 30.0

# `service=*` sub-classes that are markedly slower than a general service road.
# Not yet present in the extract (the converter does not promote `service`), but
# supported so the values take effect on the next bootstrap without a code change.
SERVICE_DEFAULT_KMH: Dict[str, float] = {
    "parking_aisle": 10.0,
    "driveway": 15.0,
    "drive-through": 10.0,
    "alley": 20.0,
}


def parse_maxspeed_kmh(props: Dict[str, Any]) -> Optional[float]:
    """Explicit maxspeed in km/h, or None when absent/unusable.

    Tolerant of the forms OSM actually contains: a bare number, "50",
    "30 mph", "none", "walk", "signals".
    """
    raw = props.get("maxspeed_kmh")
    if raw is None:
        raw = props.get("maxspeed")
    if raw is None:
        return None
    if isinstance(raw, bool):          # guard: bool is an int subclass
        return None
    if isinstance(raw, (int, float)):
        return float(raw) if raw > 0 else None
    if isinstance(raw, str):
        s = raw.strip().lower()
        if s in ("none", "signals", "walk", ""):
            # "none" is the German autobahn "no limit"; treating it as a number
            # would be a lie, and treating it as 0 would make the edge infinite.
            return None
        try:
            v = float(s)
            return v if v > 0 else None
        except ValueError:
            pass
        parts = s.split()
        if len(parts) == 2 and parts[1] in ("mph", "km/h"):
            try:
                n = float(parts[0])
            except ValueError:
                return None
            if n <= 0:
                return None
            return n * 1.60934 if parts[1] == "mph" else n
    return None


def default_kmh(props: Dict[str, Any]) -> float:
    """The class default for a way with no usable maxspeed."""
    highway = (props.get("highway") or "").strip().lower()
    if highway == "service":
        sub = (props.get("service") or "").strip().lower()
        if sub in SERVICE_DEFAULT_KMH:
            return SERVICE_DEFAULT_KMH[sub]
    return CLASS_DEFAULT_KMH.get(highway, FALLBACK_KMH)


def effective_kmh(props: Dict[str, Any]) -> float:
    """The speed Vector assumes for this way. Always > 0.

    Explicit maxspeed wins; otherwise the per-class default. This is the single
    place the speed is decided, so the routing COST and the reported ETA can
    never disagree — which they would if each computed its own fallback.
    """
    explicit = parse_maxspeed_kmh(props)
    if explicit is not None and explicit > 0:
        return explicit
    return default_kmh(props)


def effective_ms(props: Dict[str, Any]) -> float:
    """As :func:`effective_kmh`, in metres per second, floored at 1 m/s."""
    return max(effective_kmh(props) / 3.6, 1.0)


# --- junction delay ---------------------------------------------------------
#
# Free-flow link speeds alone imply a vehicle that never slows for a junction.
# Over a route with a dozen maneuvers that is minutes of missing time, and the
# error grows with the number of turns rather than with distance — so a
# turn-heavy urban route is under-estimated far more than a motorway run.
#
# These are deliberately modest, uniform, and applied to the REPORTED DURATION
# only, not to the routing cost. Putting them in the cost would change which
# route is chosen, and there is currently no evidence that Vector picks
# zigzag routes; changing route selection needs its own evidence. Recorded here
# so the decision is visible rather than implicit.
TURN_DELAY_S: Dict[str, float] = {
    "uturn": 20.0,
    "turn-left": 8.0,     # typically crosses opposing traffic
    "turn-right": 5.0,
    "slight-left": 3.0,
    "slight-right": 3.0,
    "roundabout": 6.0,
    "continue": 0.0,
    "depart": 0.0,
    "arrive": 0.0,
}


def junction_delay_s(step_types) -> float:
    """Total junction delay implied by a sequence of maneuver types."""
    return sum(TURN_DELAY_S.get(t, 0.0) for t in step_types)


# Calibration of the reported driving ETA, applied to FREE-FLOW segments
# only. Measured 2026-09-14 on the first 18 real drives (eta.jsonl):
# observed/predicted duration median 1.25x, mean 1.55x, 14 of 18 samples
# under-predicted. Free-flow defaults (above) are deliberately optimistic;
# the DRIVING ETA gets this conservative factor so the driver is not told
# "4 min" for an eight-minute drive. Deliberately not applied to learned
# (observed) segments — a measured speed already reflects real driving and
# must not be inflated twice — and NOT applied to routing cost, so route
# choice is untouched. Revisit when the ETA log has more trips.
ETA_DRIVE_BIAS = 1.2


# --- the walking model (V7 Phase 2) -----------------------------------------
#
# A pedestrian graph needs its own speed answer, and it is a much simpler one
# than the driving model above: a person walks at roughly one speed, and the
# `maxspeed` tag — which is the whole subject of this module for cars — is a
# statement about vehicles and says nothing about them.
#
# 1.35 m/s is the standard mean walking pace of an unhurried adult on the level
# (~4.9 km/h). It is a MODELLED constant, not a measurement of anyone's walk:
# Vector has observed zero real walks, so there is nothing here to learn from
# yet. When there is, it belongs in an overlay beside `learned_overlay`, not in
# this number.
#
# Deliberately flat across highway classes. A footway, a residential street and
# a shopping arcade are all walked at about the same speed, and inventing a
# per-class table would be inventing data — the exact failure the driving model
# above was written to correct, in reverse.
WALK_SPEED_MS = 1.35

# `highway=steps` is PENALISED, NOT BANNED.
#
# Banning stairs is the tempting simplification and it is wrong in Doha: a
# staircase is frequently the only connection between a footbridge and the
# street, or between a car park deck and the mall entrance beside it. Removing
# them does not produce a longer walk, it produces NO WALK — the two sides of
# the stairs fall into different components of the graph and the router answers
# "no route" for a journey a person makes in thirty seconds.
#
# So steps are slow rather than absent. 0.5 m/s makes a metre of staircase cost
# 2.7x a metre of pavement, which is enough that a ramp within a modest detour
# wins and never enough to make the stairs unreachable: a 20 m flight (40 s)
# still beats a 100 m level detour around it (74 s), while a 40 m detour (30 s)
# wins instead.
#
# The number is CHOSEN, not measured — a stated modelling assumption in the same
# sense as the free-flow defaults above, and the honest thing to say about it is
# that it encodes "stairs are worth avoiding a little", not a claim about how
# fast anyone climbs. Accessibility is a different question entirely and needs
# its own data (`wheelchair`, `incline`, `handrail`); this constant must not be
# mistaken for answering it.
STEPS_SPEED_MS = 0.5


def walk_speed_ms(props: Dict[str, Any]) -> float:
    """The speed Vector assumes a person walks this way at, in m/s.

    The single place a WALKING speed is decided, for the same reason
    :func:`effective_ms` is the single place a driving speed is decided: the
    routing cost and the reported duration must be computed from one number.
    The driving model already paid for that lesson — it optimised against a
    30 km/h fallback and then reported an ETA built from 50.
    """
    highway = (props.get("highway") or "").strip().lower()
    if highway == "steps":
        return STEPS_SPEED_MS
    return WALK_SPEED_MS
