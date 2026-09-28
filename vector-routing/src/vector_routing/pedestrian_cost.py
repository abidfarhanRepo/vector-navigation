"""The pedestrian edge-cost model (V7.4 4B.3).

4A.4 promoted the pedestrian facts onto the foot-graph edge props —
``surface``, ``incline``, ``steps``/``step_count``, ``sidewalk``, ``width``,
``lit``, ``footway=crossing``/``crossing=*``/``kerb`` — and 4B.1/4B.2 built
the crossing-aware fact and maneuver layers that READ those props. This module
is what makes them affect route CHOICE: a pure, deterministic edge-cost
function over the same props, consumed by ``FootRouter._edge_weight``.

## What the model is, and what it is not

**It is a decomposable line-item model, not a comfort score.** Every edge cost
is ``pace * multipliers + wait`` where each multiplier/penalty is a named,
documented constant attached to one promoted attribute. Given an edge,
``PedestrianCostModel.lines(u, to, w, props)`` returns the per-factor
breakdown, and the uplifts are CHAINED in a documented order so they sum
EXACTLY to the factor gap in the total — a route's choice is explainable edge
by edge and factor by factor. There is no opaque composite, no learned
weighting, no hidden "pleasantness" number.

**It is stratified by certainty, which is the 4A.4 doctrine applied to
costs.** The model's contract with the source data:

* *forbidden* — barriers and access semantics. These are enforced by the
  graph itself (4A: the barrier catalog severs blocked nodes before the
  router exists; ingestion's ``is_pedestrian_routable``/``foot=no`` decide
  walkability). This module costs nothing here; it documents that the
  enforcement is upstream and untouched.
* *strong routing penalties* — stairs (the existing ``STEPS_SPEED_MS``,
  carried in the base pace exactly as before) and incline (a documented
  physical speed model). Both are real, measurable pedestrian effort; both
  are ON in the default profile.
* *ordinary preferences* — surface, lit, width, sidewalk, crossing type.
  Each has a documented cost in the model (so the model is complete and
  testable), but they are OFF in the default profile: they are
  pleasantness/comfort statements, and default routing is shortest-first
  (see ``PedestrianCostModel.general``).
* *unknown* — an absent attribute costs NOTHING. Missing data is
  distinguishable from a known value: ``lines`` reports ``absent`` and the
  factor contributes 0.0. Absence is never converted into a penalty.

**Units are seconds.** The search cost of an edge is in seconds (the walking
time plus any documented waits), so the A* compares alternatives like with
like: a detour that adds 30 s of walking only wins when it removes more than
30 s of documented penalty. That is the property that keeps tiny attribute
differences from producing pathological detours — a 0.1 s preference can
never justify a 40 s walk around it.

## The stairs factor is the speed model, not a flag

``walk_speed_ms`` (0.5 m/s on ``highway=steps``, 1.35 elsewhere) remains the
single pace authority — "one speed, one answer" — so every profile walks
stairs at the documented penalty. The ``stairs`` line in the diagnostics is
the gap between that pace and the flat pace, reported so the exposure is
visible; it is not a toggle (turning it off would change the pre-existing,
test-pinned behavior, and no profile asks for that).

## Why the reported duration is NOT the weighted cost

``FootRouter._edge_duration_s`` stays pure pace time (``w / speed``) while
``_edge_weight`` returns the full weighted cost. This is the mirror of the
driving model's split (``speeds.TURN_DELAY_S``: junction delay affects the
reported duration but not route choice): the penalties here are expected-
delay/effort models for CHOICE, and putting them in the reported walk time
would change every walk's ETA without changing where it walks. The wire keeps
``duration_s`` = pace time (backwards compatible, and the invariant every
existing test pins), and the gap between the two is exposed as the route's
``cost.penalty_s`` so it cannot hide.

## Profiles and the accessibility boundary

``general`` is the only profile wired into routing. The model also carries
the documented preference factors (and a ``with_preferences`` constructor)
as testable groundwork for future comfort/accessibility profiles; alongside
them, ``step_count``/``handrail``/``width``/``incline`` are routing SIGNALS
(credited to the route diagnostics' exposure totals), never proof that a
route is accessible. The recon's rule stands: no product claim of wheelchair
routing is made here, and an accessibility profile is a future stage's
decision built on its own data evidence, not this module's defaults.
"""

import math
from typing import Any, Dict, Optional

from .speeds import STEPS_SPEED_MS, WALK_SPEED_MS, walk_speed_ms

# ---------------------------------------------------------------------------
# The constants. Every value is a named, documented statement; none is tuned
# against a fixture (the stage doc's "no fixture tuning" rule).
# ---------------------------------------------------------------------------

# --- incline (STRONG, ON in the default profile) ---------------------------
#
# The time multiplier is Tobler's hiking function normalized to 1.0 at level
# ground:  speed(g) = 6 km/h * exp(-3.5 * |g + 0.05|).  Dividing by its value
# at g=0 and inverting gives the TIME multiplier
#
#     exp(3.5 * (|g + 0.05| - 0.05))
#
# which is 1.0 on the level, ~1.42 at a 10% grade and ~1.66 at 15% — a real,
# published relationship, not an invented table. Descending is NOT credited
# with speed: on foot, downhill is only marginally faster than level and the
# energy model (and Tobler itself) say the gain is small; charging the climb
# rate in both directions is the conservative side of that asymmetry, and
# doubly required here because a foot edge carries no direction provenance
# (see below).
INCLINE_TOBLER_B = 3.5
INCLINE_TOBLER_S0 = 0.05

# OSM writes incline as a percent ("10%"), degrees ("30°"), or a bare symbol
# ("up"/"down") — Qatar's extract has only the symbolic forms. A symbol names
# direction, not magnitude, so a documented representative grade is attached
# to it rather than inventing a per-way magnitude: "up"/"down" = 10% (a
# noticeable slope), bare "yes" = 5% (it is sloped, presumably gently).
INCLINE_SYMBOLIC_GRADIENT: Dict[str, float] = {
    "up": 0.10,
    "down": 0.10,
    "yes": 0.05,
}

#: A foot edge carries the way's props for BOTH directions (``oneway`` is
#: stripped before the graph is built), so the cost model cannot tell which
#: way is uphill. The slope is therefore charged at its climb rate on both
#: directions — documented, symmetric, conservative (a downhill traversal is
#: slightly over-charged rather than a climb being invisible). A future
#: ``incline:forward``-style provenance could split it; it does not exist in
#: this data.
INCLINE_DIRECTION = "symmetric_uphill_rate"


def _parse_gradient(incline: Any) -> Optional[float]:
    """The rise/run gradient ``g`` of an ``incline`` tag, or None.

    percent ("10%" / "10"), degrees ("30°"), or the documented symbols.
    Absent/unparseable returns None — an unknown slope costs nothing.
    """
    if incline is None:
        return None
    if isinstance(incline, bool):
        return None
    if isinstance(incline, (int, float)):
        return max(float(incline), 0.0) / 100.0 if incline > 0 else None
    s = str(incline).strip().lower()
    if not s:
        return None
    if s in INCLINE_SYMBOLIC_GRADIENT:
        return INCLINE_SYMBOLIC_GRADIENT[s]
    try:
        if s.endswith("%"):
            return max(float(s[:-1]), 0.0) / 100.0
        if s.endswith("°"):
            return abs(math.tan(math.radians(float(s[:-1]))))
        if s.endswith("deg"):
            return abs(math.tan(math.radians(float(s[:-3]))))
        if s.endswith("degrees"):
            return abs(math.tan(math.radians(float(s[:-7]))))
        return max(float(s), 0.0) / 100.0
    except ValueError:
        return None


def incline_time_multiplier(incline: Any) -> float:
    """Tobler-normalized climb-time multiplier for an ``incline`` tag (1.0 = level)."""
    g = _parse_gradient(incline)
    if g is None or g <= 0:
        return 1.0
    return math.exp(INCLINE_TOBLER_B * (abs(g + INCLINE_TOBLER_S0) - INCLINE_TOBLER_S0))


# --- crossing wait (strong-ish, ON in the default profile) ------------------
#
# A crossing is not assumed bad or good: the edge either IS a crossing
# (``footway=crossing`` or an explicit ``crossing=*``) or it is not, and only
# if it is does it carry a wait. The wait is an EXPECTED DELAY model in
# seconds — the physical cost of negotiating a carriageway — with values that
# follow the crossing type the edge's own tags state:
#
#     traffic_signals : a fixed wait for the pedestrian phase (~15 s expected
#                       on a typical cycle) — the longest, because you wait
#                       even when the road is empty;
#     zebra / marked  : traffic yields, but you wait for a gap (~8 s);
#     uncontrolled /
#     unmarked        : you cross a gap as soon as one exists (~4 s);
#     (untyped)       : a real crossing whose kind the edge does not state —
#                       the moderate middle (6 s), never an invented extreme;
#     crossing=no     : not a crossing — no wait.
#
# The TYPE usually lives on the artifact NODE (4B.1's CrossingCatalog), which
# the per-edge search cannot afford to consult; the edge cost uses only the
# way-promoted ``crossing=*`` (168 of 2,989 Qatari crossing ways carry one).
# Untyped crossing edges get the documented middle value; the node types ride
# on the 4B.1 facts unchanged and are reported in the route diagnostics, so
# nothing is lost — the cost model simply does not invent a kind it was not
# given. All values are modest: a full crossing costs ~6-15 s, less than the
# ~20 m of walking it takes to detour around, so a router only avoids a
# crossing when the alternative really is shorter.
CROSSING_WAIT_S_UNKNOWN = 6.0
CROSSING_WAIT_S: Dict[str, float] = {
    "traffic_signals": 15.0,
    "signal": 15.0,
    "signals": 15.0,
    "zebra": 8.0,
    "marked": 8.0,
    "uncontrolled": 4.0,
    "unmarked": 4.0,
    "unsignalized": 4.0,
    "no": 0.0,
}


def is_crossing_edge(props: Dict[str, Any]) -> bool:
    """Is this edge a crossing a person walks ON (sourced, never inferred)?

    The single definition of "crossing" in the routing package: a crossing is
    ``footway=crossing`` (the way OSM drew) or an explicit ``crossing=*`` type.
    Two roads meeting is NOT a crossing. ``FootRouter.crossings_m`` measures
    with this, the cross-fact builder emits facts with it, and the signal census
    counts crossings with it — three readers, one spelling, because three
    spellings is how a map and a router come to disagree about what a crossing
    is.
    """
    return ((props.get("footway") or "").strip().lower() == "crossing"
            or (props.get("crossing") or "").strip() != "")


def crossing_wait_s(props: Dict[str, Any]) -> float:
    """Expected wait (seconds) for this edge, 0.0 when it is not a crossing.

    The same sourced predicate as ``FootRouter.crossings_m`` — a crossing is
    ``footway=crossing`` or an explicit ``crossing=*`` — and never inferred
    from two roads meeting.
    """
    if not is_crossing_edge(props):
        return 0.0
    raw = (props.get("crossing") or "").strip().lower()
    if not raw:
        return CROSSING_WAIT_S_UNKNOWN
    # Compound values ("traffic_signals;marked") degrade to the longest part.
    parts = [p.strip() for p in raw.replace(";", ",").split(",") if p.strip()]
    waits = [CROSSING_WAIT_S.get(p) for p in parts]
    waits = [v for v in waits if v is not None]
    return max(waits) if waits else CROSSING_WAIT_S_UNKNOWN


# --- surface (PREFERENCE, OFF in the default profile) ----------------------
#
# The walking-speed penalty of rough ground, as a TIME multiplier. Only a
# documented moderately-rough → rough split is made; anything not listed
# (including ABSENT) is neutral. These are preference-profile values: the
# default profile does not use them, but they are part of the model so a
# future "prefer smooth paths" profile has documented, testable numbers
# instead of a new table invented at that stage.
SURFACE_TIME: Dict[str, float] = {
    "asphalt": 1.0, "concrete": 1.0, "paved": 1.0, "paving_stones": 1.0,
    "sett": 1.0, "tartan": 1.0, "compacted": 1.0, "wood": 1.0,
    "ground": 1.15, "soil": 1.15, "dirt": 1.15, "unpaved": 1.15,
    "grass": 1.15, "gravel": 1.20, "fine_gravel": 1.20,
    "cobblestone": 1.20, "pebblestone": 1.20, "mud": 1.30,
    "sand": 1.30, "snow": 1.40, "ice": 1.50,
}

# --- lit (PREFERENCE, OFF in the default profile) --------------------------
#
# A night-walking preference: unlit ground costs a slight comfort penalty.
# Absent and lit=yes are neutral — darkness is only penalised when the map
# actually says it is dark.
LIT_NO_TIME = 1.05

# --- width (PREFERENCE, OFF in the default profile) ------------------------
#
# A narrow-path comfort penalty below documented widths. Absent/unparseable
# is neutral — a missing width must never read as "narrow".
WIDTH_NARROW_M = 1.2
WIDTH_TIGHT_M = 1.8
WIDTH_NARROW_TIME = 1.10
WIDTH_TIGHT_TIME = 1.03


def _parse_width(width: Any) -> Optional[float]:
    if width is None:
        return None
    if isinstance(width, bool):
        return None
    if isinstance(width, (int, float)):
        return float(width) if width > 0 else None
    s = str(width).strip().replace(",", ".")
    if not s:
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    return v if v > 0 else None


def width_time_multiplier(width: Any) -> float:
    m = _parse_width(width)
    if m is None:
        return 1.0
    if m < WIDTH_NARROW_M:
        return WIDTH_NARROW_TIME
    if m < WIDTH_TIGHT_M:
        return WIDTH_TIGHT_TIME
    return 1.0


# --- sidewalk (PREFERENCE, OFF in the default profile) ----------------------
#
# A road that carries ``sidewalk=no`` means the pedestrian shares the
# carriageway; a preference profile may mildly prefer not to. Absent/other
# sidewalk values are neutral (a road WITHOUT the tag has no sidewalk
# statement, which is not the same as ``no``).
SIDEWALK_NO_TIME = 1.15

#: Surface values the model treats as smooth (multiplier 1.0). A surface tag
#: NOT in this set counts as poor-surface EXPOSURE in the route diagnostics
#: (measurement, not cost — the exposure is reported even when the surface
#: preference flag is off).
SMOOTH_SURFACES = frozenset(
    s for s, m in SURFACE_TIME.items() if m <= 1.0)


def is_poor_surface(surface: Any) -> bool:
    """Is a ``surface`` tag a poor walking surface by the model's table?

    Absent/unknown surfaces are NOT poor — absence must never read as a
    quality statement. Only listed rough surfaces are.
    """
    if surface is None:
        return False
    s = str(surface).strip().lower()
    return bool(s) and s not in SMOOTH_SURFACES and s in SURFACE_TIME


def is_narrow_width(width: Any) -> bool:
    """Is a ``width`` tag below the narrow threshold (comfort exposure)?

    Absent/unparseable is not narrow.
    """
    m = _parse_width(width)
    return m is not None and m < WIDTH_NARROW_M

# --- factor identities (stable, wire-visible) ------------------------------
STAIRS = "stairs"                 # pace-based (the 0.5 m/s steps speed), always on
INCLINE = "incline"
CROSSING = "crossing_wait"
SURFACE = "surface"
LIT = "lit"
WIDTH = "width"
SIDEWALK = "sidewalk"

FACTORS = (STAIRS, INCLINE, CROSSING, SURFACE, LIT, WIDTH, SIDEWALK)

#: The multiplicative factors, in the exact CHAIN order the cost applies them
#: (incline compounds the stairs pace, then each preference compounds the
#: result). Documented precedence: stairs (speed) -> incline -> surface ->
#: lit -> width -> sidewalk; the crossing wait is added after, not multiplied.
MULTIPLICATIVE_ORDER = (INCLINE, SURFACE, LIT, WIDTH, SIDEWALK)

#: The default "general" profile: what a shortest-first walker physically
#: pays. Stairs (speed) is always in the pace; incline (Tobler) and crossing
#: waits (expected delay) are ON; comfort preferences (surface/lit/width/
#: sidewalk) are OFF.
GENERAL_FLAGS: Dict[str, bool] = {
    INCLINE: True, CROSSING: True,
    SURFACE: False, LIT: False, WIDTH: False, SIDEWALK: False,
}


class PedestrianCostModel:
    """Deterministic per-edge walking cost, factor by factor.

    ``edge_weight`` is the search cost (seconds) consumed by
    ``FootRouter._edge_weight``; ``lines`` is the same computation decomposed
    per factor so a route choice is explainable. Instances are immutable and
    shareable across threads (no per-request state).
    """

    def __init__(self, profile: str = "general",
                 flags: Optional[Dict[str, bool]] = None) -> None:
        self.profile = profile
        self._flags = dict(GENERAL_FLAGS if flags is None else flags)
        self._legacy = profile == "legacy"
        if self._legacy:
            self._flags = {f: False for f in FACTORS}

    @classmethod
    def general(cls) -> "PedestrianCostModel":
        """The default profile: shortest-first walking with physical costs.

        The only profile wired into routing. The stage contract: default
        walking IS general-purpose walking; pleasant walking is explicitly a
        profile request, not the default.
        """
        return cls("general", dict(GENERAL_FLAGS))

    @classmethod
    def legacy(cls) -> "PedestrianCostModel":
        """The pre-4B.3 pure-time model: ``w / walk_speed`` and nothing more.

        Provided so regression evidence can run the exact pre-cost router
        (byte-identical routes, facts and plans) beside the default one. Not
        wired to any endpoint; a constructor choice only.
        """
        return cls("legacy", {f: False for f in FACTORS})

    @classmethod
    def with_preferences(cls) -> "PedestrianCostModel":
        """General + every documented preference enabled.

        Test-only/future-profile groundwork: there is NO routed endpoint that
        selects this (that is 4B.4's contract decision), and exposing it as a
        product would be a comfort claim the data does not yet support. It
        exists so the preference factors are exercised by the same engine path
        the default uses, and so a future profile inherits numbers that are
        already pinned by tests.
        """
        model = cls("preferences")
        model._flags = {f: True for f in FACTORS}
        return model

    def flag(self, factor: str) -> bool:
        return bool(self._flags.get(factor))

    def selection_factors(self) -> list:
        """The factors that can affect route selection under this profile.

        Stairs are ALWAYS present (the steps speed is the pace itself, not a
        flag); the multiplicative and crossing factors appear only when their
        flag is on. The route diagnostics use this to separate "what changed
        the choice" (``cost.selection_factors`` + the non-zero entries of
        ``cost.factor_s``) from "what the route merely exposes" (the metres/
        counts blocks, which are measured regardless of flags).
        """
        out = [STAIRS]
        for factor in MULTIPLICATIVE_ORDER:
            if self.flag(factor):
                out.append(factor)
        if self.flag(CROSSING):
            out.append(CROSSING)
        return out

    # -- the plural factor multipliers --------------------------------

    def _multipliers(self, props: Dict[str, Any]) -> Dict[str, float]:
        """Per-factor time multipliers for this edge (1.0 when off or absent)."""
        out: Dict[str, float] = {}
        if self.flag(INCLINE):
            out[INCLINE] = incline_time_multiplier(props.get("incline"))
        else:
            out[INCLINE] = 1.0
        if self.flag(SURFACE):
            s = (props.get("surface") or "").strip().lower()
            out[SURFACE] = SURFACE_TIME.get(s, 1.0) if s else 1.0
        else:
            out[SURFACE] = 1.0
        if self.flag(LIT):
            lit = (props.get("lit") or "").strip().lower()
            out[LIT] = LIT_NO_TIME if lit in ("no", "false", "0") else 1.0
        else:
            out[LIT] = 1.0
        if self.flag(WIDTH):
            out[WIDTH] = width_time_multiplier(props.get("width"))
        else:
            out[WIDTH] = 1.0
        if self.flag(SIDEWALK):
            sw = (props.get("sidewalk") or "").strip().lower()
            out[SIDEWALK] = SIDEWALK_NO_TIME if sw == "no" else 1.0
        else:
            out[SIDEWALK] = 1.0
        return out

    # -- the cost ------------------------------------------------------

    def edge_weight(self, u: str, to: str, w: float, props: Dict[str, Any]) -> float:
        """Search cost (seconds) for one directed edge.

        Order of application, which is the documented precedence:

        ``pace = w / walk_speed_ms`` (steps at 0.5 m/s, everything else at
        1.35) — the single pace authority, unchanged from 4B.2;
        ``cost = pace * incline * surface * lit * width * sidewalk`` (each
        factor 1.0 when its flag is off or its source tag is absent);
        ``cost += crossing_wait`` — an event cost added once per crossing
        edge, not length-proportional: a 3 m zebra and a 30 m one both cost
        the expected delay of negotiating the carriageway.
        """
        if self._legacy:
            return w / walk_speed_ms(props)
        cost = w / walk_speed_ms(props)
        multipliers = self._multipliers(props)
        for factor in MULTIPLICATIVE_ORDER:
            cost *= multipliers[factor]
        if self.flag(CROSSING):
            cost += crossing_wait_s(props)
        return cost

    def _breakdown(self, w: float, props: Dict[str, Any]) -> Dict[str, Any]:
        """Exact per-edge pieces: flat pace, pace, wait, chained uplifts.

        The single source the diagnostics use. ``uplifts`` are CHAINED in the
        documented order (stairs first, then incline, then the preferences),
        so ``flat + sum(uplifts) + wait`` equals ``total`` EXACTLY — the
        decomposition is not an approximation.
        """
        flat = w / WALK_SPEED_MS
        pace = w / walk_speed_ms(props)
        stairs_uplift = pace - flat
        multipliers = self._multipliers(props)
        running = pace
        uplifts: Dict[str, float] = {STAIRS: stairs_uplift}
        for factor in MULTIPLICATIVE_ORDER:
            m = multipliers[factor]
            nxt = running * m
            if m != 1.0:
                uplifts[factor] = nxt - running
            else:
                uplifts[factor] = 0.0
            running = nxt
        wait = crossing_wait_s(props) if self.flag(CROSSING) else 0.0
        return {
            "flat": flat,
            "pace": pace,
            "wait": wait,
            "uplifts": uplifts,
            "multipliers": multipliers,
            "total": running + wait,
        }

    # -- the decomposition ---------------------------------------------

    def lines(self, u: str, to: str, w: float, props: Dict[str, Any]) -> Dict[str, Any]:
        """The decomposable per-factor breakdown of this edge's cost.

        Returns ``base_time_s`` (flat-pace time), each factor's uplift in
        seconds with its multiplier and source-tag presence, and the total.
        The uplifts CHAIN in the documented order — stairs (pace gap) then
        incline then surface/lit/width/sidewalk, crossing wait added last —
        so ``base_time_s + sum(uplifts) + crossing_wait_s`` equals
        ``total_s`` EXACTLY (up to float rounding). An absent source reports
        ``absent`` (never a penalty), so the model is inspectable edge by
        edge — the "why was this edge penalized" answer, with no opaque
        composite.
        """
        if self._legacy:
            flat = w / WALK_SPEED_MS
            b = {
                "base_time_s": flat,
                "stairs": {"multiplier": 1.0, "uplift_s": 0.0,
                           "source": (props.get("highway") or "").strip() or "absent"},
                "crossing_wait_s": 0.0,
                "total_s": w / walk_speed_ms(props),
            }
            for factor in MULTIPLICATIVE_ORDER:
                b[factor] = {"multiplier": 1.0, "uplift_s": 0.0, "source": "absent"}
            return b

        b = self._breakdown(w, props)

        def _present(tag: str) -> str:
            v = props.get(tag)
            if v is None or str(v).strip() == "":
                return "absent"
            return str(v).strip()

        out: Dict[str, Any] = {
            "base_time_s": b["flat"],
            "stairs": {
                "multiplier": round(b["pace"] / b["flat"], 3) if b["flat"] > 0 else 1.0,
                "uplift_s": b["uplifts"][STAIRS],
                "source": _present("highway"),
            },
            "crossing_wait_s": b["wait"],
        }
        tag_of = {INCLINE: "incline", SURFACE: "surface", LIT: "lit",
                  WIDTH: "width", SIDEWALK: "sidewalk"}
        for factor in MULTIPLICATIVE_ORDER:
            out[factor] = {
                "multiplier": round(b["multipliers"][factor], 3),
                "uplift_s": b["uplifts"][factor],
                "source": _present(tag_of[factor]),
            }
        out["total_s"] = b["total"]
        return out

    # -- observability --------------------------------------------------

    def config(self) -> Dict[str, Any]:
        """The full documented configuration, for ``/footz`` and tests.

        Everything an operator needs to know WHICH costs are live and WHAT
        they are, without reading source.
        """
        return {
            "profile": self.profile,
            "unit": "seconds",
            "flags": dict(self._flags),
            "factor_order": list(MULTIPLICATIVE_ORDER),
            "speeds": {"walk_ms": WALK_SPEED_MS, "steps_ms": STEPS_SPEED_MS},
            "stairs": {"mode": "speed_ratio_in_pace", "ratio": WALK_SPEED_MS / STEPS_SPEED_MS},
            "incline": {
                "mode": "tobler_normalized_time",
                "b": INCLINE_TOBLER_B,
                "s0": INCLINE_TOBLER_S0,
                "symbolic_gradient": dict(INCLINE_SYMBOLIC_GRADIENT),
                "direction": INCLINE_DIRECTION,
            },
            "crossing_wait_s": {
                "unknown": CROSSING_WAIT_S_UNKNOWN,
                **dict(CROSSING_WAIT_S),
            },
            "surface_time": dict(SURFACE_TIME),
            "lit_no_time": LIT_NO_TIME,
            "width": {
                "narrow_m": WIDTH_NARROW_M,
                "tight_m": WIDTH_TIGHT_M,
                "narrow_time": WIDTH_NARROW_TIME,
                "tight_time": WIDTH_TIGHT_TIME,
            },
            "sidewalk_no_time": SIDEWALK_NO_TIME,
            "missing_data": "absent attributes cost 0; source tags are never invented",
        }