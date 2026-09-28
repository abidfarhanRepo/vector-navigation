"""Pedestrian maneuver INTERPRETATION + salience: facts -> sparse planning maneuvers.

4B.2 of V7.4, consuming the 4B.1 fact layer (:mod:`vector_routing.pedestrian_maneuvers`)
as its source of truth. The recon's division is kept strict: source fact -> graph
fact -> measured behavior (4B.1) -> interpretation (4B.2) -> presentation (4B.3+).
This module never re-derives a crossing from geometry, never invents OSM
semantics, and never emits human-facing prose — it decides which facts a person
would actually act on, and how they order.

What the layer does:

* **Turns become sparse.** 4B.1 faithfully emits every real windowed bend
  (~2.3/km on the Qatar bake). This module merges, suppresses and ranks them so
  a dense cluster of trivial bends reads as the one maneuver it is, while a lone
  meaningful corner survives.
* **Crossing and stair events always survive.** They are anchors: a bearing
  change inside a crossing's approach..leave span (or a stairs run's begin..end)
  is the crossing's own geometry, not a separate maneuver.
* **Transitions are filtered by meaning.** "Leave the walkway onto the road" is
  guidance-worthy; a footway becoming a differently-named footway is an OSM way
  split, and produces nothing.
* **Provenance is preserved.** The 4B.1 way/catalog/catalog-node/unknown
  crossing-type provenance and the uncertain crossed-road attribution ride
  through untouched: an unknown crossed road stays unknown, never converted into
  a confident claim.

The precedence rules (documented, tested):

1. A candidate (turn or transition) whose position falls inside a crossing's
   ``[approach_index .. leave_index]`` or a stairs run's ``[begin_index .. end]``
   is dropped — the crossing/stairs event IS the maneuver there.
2. A transition is a maneuver only when the pedestrian-way class changes between
   the WALKWAY bucket and the ROADWAY bucket. Same-bucket changes (footway ->
   path, residential -> service, a name change on the same walkway) are way
   segmentation, not guidance.
3. Candidates within ``MIN_MANEUVER_SPACING_M`` of the previous kept candidate
   collapse: the higher-salience one survives, ties keep the earlier.
4. A run of consecutive slight bends whose gaps fit in ``WIGGLE_CLUSTER_M`` and
   whose signed deltas sum to less than ``WIGGLE_NET_DEG`` is the path bending
   and returning — a serpentine — and drops entirely (the continuity rule).
5. A ``slight_left``/``slight_right`` within ``SLIGHT_SUPPRESSION_RADIUS_M`` of
   any other kept turn is bearing noise and drops; a slight that stands alone on
   an otherwise straight stretch is a genuine bend and is kept.
6. At the same position the rank order is: depart > cross > stairs > arrive >
   uturn > turn > slight > continue (4B.1 already makes co-located facts
   exclusive, so this is a documented guard, not a hot path).

Each emitted maneuver carries ``kind`` (a normalized vocabulary: depart, cross,
stairs, uturn, turn_left, turn_right, slight_left, slight_right, continue,
arrive), its position (``index``/``distance_m``), ``distance_to_next_m``, the
``road``/path most relevant to it (the crossed road for a cross — uncertain when
the fact was uncertain; the way travelled on for a turn/continue), kind-specific
attributes (``crossing``, ``stairs``, ``turn``, ``from``/``to``), and
``source_facts`` — the 4B.1 fact(s) that produced it, for full traceability.
"""

from typing import Any, Dict, List, Optional, Tuple

#: How close two separately-announced maneuvers may be before they are one.
#: The same 15 m the driving merger uses: shorter than the pace a person can
#: act on two instructions in, long enough that a genuine corner spread over a
#: few geometry vertices is one maneuver. The 4B.1 windowed-bearing layer has
#: already collapsed one physical corner into one fact; this collapses two
#: independent corners that happen to sit a few metres apart (a chained gate
#: chicane, a path jogging twice), keeping the one a person would point at.
MIN_MANEUVER_SPACING_M = 15.0

#: A slight (20-45 deg windowed) bend this close to any other turn is bearing
#: noise, not guidance: on a winding path the bends come every few tens of
#: metres and none of them is a decision. A slight that stands alone in a long
#: straight stretch IS information (the path genuinely forks or bends), and is
#: kept. 40 m is roughly the width of one large Qatari block: two corners
#: further apart than that belong to different places.
SLIGHT_SUPPRESSION_RADIUS_M = 40.0

#: The continuity window for wiggle cancellation (R5): consecutive turns whose
#: gaps fit in this window form one local cluster. 60 m is a generous grid
#: block width — genuine block corners at 80-150 m spacing are NOT clustered —
#: while a path that bends three times inside 60 m is describable in one breath.
WIGGLE_CLUSTER_M = 60.0

#: The net-direction threshold of a wiggle cluster: if the signed deltas of an
#: all-slight cluster sum to less than this, the path bent and RETURNED — it
#: never changed where it is going, and no turn inside it is guidance.
WIGGLE_NET_DEG = 45.0

#: Salience rank, higher = more actionable. Cross and stairs outrank every turn
#: so a genuine footway=crossing can never be absorbed into a nearby bearing
#: change; depart/arrive are fixed terminals. Turns beat transitions (a
#: continue is by definition the road changing under you — less for the walker
#: to do than executing a corner).
MANEUVER_RANK = {
    "depart": 9,
    "cross": 8,
    "stairs": 7,
    "arrive": 6,
    "uturn": 5,
    "turn_left": 4,
    "turn_right": 4,
    "slight_left": 2,
    "slight_right": 2,
    "continue": 1,
}

#: The 4B.1 turn-classification vocabulary -> normalized maneuver kinds.
_TURN_KIND = {
    "turn-left": "turn_left",
    "turn-right": "turn_right",
    "slight-left": "slight_left",
    "slight-right": "slight_right",
    "uturn": "uturn",
}

# What a person is actually walking on, for transition meaningfulness. A
# WALKWAY is pedestrian infrastructure; a ROADWAY is a carriageway (even when
# walkable — a residential street you share with cars is a different thing from
# a pavement, and crossing that boundary is guidance). Everything else (steps
# are their own fact; unknown classes) is neither, so a transition between two
# unknowns is not meaningful.
WALKWAY_CLASSES = frozenset({
    "footway", "path", "pedestrian", "corridor", "bridleway", "cycleway", "steps",
})
ROADWAY_CLASSES = frozenset({
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "service", "living_street", "road", "track",
})


def _anchor_kind(fact: Dict[str, Any]) -> str:
    return fact.get("type", "continue")


def _class_bucket(identity: Dict[str, Any]) -> str:
    hw = (identity or {}).get("highway")
    if hw in WALKWAY_CLASSES:
        return "walkway"
    if hw in ROADWAY_CLASSES:
        return "roadway"
    return "other"


def _meaningful_transition(fact: Dict[str, Any]) -> bool:
    """Is this 4B.1 transition something a person would act on?

    ``from``/``to`` carry the raw way identities. A change between the walkway
    and the roadway bucket is a real boundary ("leave the pavement for the
    road", "step off the road onto the footway"). Anything else — footway with
    a different name becoming footway, residential becoming service, sidewalk
    becoming footway — is OSM way segmentation: the person is walking on the
    same kind of surface and no guidance is gained by announcing it.
    """
    fro = _class_bucket(fact.get("from"))
    to = _class_bucket(fact.get("to"))
    return (fro == "walkway" and to == "roadway") or (fro == "roadway" and to == "walkway")


def _span_of(fact: Dict[str, Any]) -> Optional[Tuple[Optional[int], int]]:
    """The vertex span a run event claims, for dominance suppression.

    Both run kinds include their APPROACH vertex (the last bend before the
    run): a turn that bends directly onto a crossing or a staircase is that
    event's own geometry — "cross the road" covers turning onto it, and so
    does "take the stairs". The leave vertex is also claimed: the corner at
    the far side of a crossing is the crossing junction, not a separate act.
    """
    if fact["type"] == "cross":
        lo = fact.get("approach_index")
        if lo is None:
            lo = fact["enter_index"]
        return (lo, fact["leave_index"])
    if fact["type"] == "stairs":
        lo = fact["begin_index"] - 1
        return (lo if lo >= 0 else None, fact["end_index"])
    return None


def _candidate_rank(fact: Dict[str, Any], kind: str) -> int:
    return MANEUVER_RANK.get(kind, 0)


def _cross_maneuver(fact: Dict[str, Any]) -> Dict[str, Any]:
    """Interpret a cross fact. Provenance rides through untouched."""
    crossing: Dict[str, Any] = {
        "type": fact.get("crossing"),
        "type_source": fact.get("crossing_type_source"),
        "markings": fact.get("crossing_markings"),
        "kerb": fact.get("kerb"),
        "tactile_paving": fact.get("tactile_paving"),
        "distance_m": fact["crossing_distance_m"],
        "distance_to_crossing_m": fact["distance_to_crossing_m"],
        "approach_index": fact.get("approach_index"),
        "approach_distance_m": fact.get("approach_distance_m", 0.0),
        "enter_index": fact["enter_index"],
        "leave_index": fact["leave_index"],
        # V7 traffic lights: the surveyed signals standing ON this crossing,
        # copied from the fact unchanged — location and provenance, never a
        # phase. Keyed inside ``crossing`` because that is the surface a client
        # consults for "what kind of crossing is this?"; the list is empty
        # whenever the map places no signal on it.
        "signals": fact.get("signals") or [],
    }
    return {
        "kind": "cross",
        "index": fact["index"],
        "distance_m": fact["distance_m"],
        # The road/path most relevant to this maneuver is the one BEING crossed.
        # Its attribution is the 4B.1 fact's own: `None` stays None — an
        # uncertain crossed road is never promoted to a confident one here.
        "road": fact.get("road"),
        "crossed_road_source": fact.get("road_source"),
        "crossing": crossing,
        "source_facts": [fact],
    }


def _stairs_maneuver(fact: Dict[str, Any]) -> Dict[str, Any]:
    stairs: Dict[str, Any] = {"distance_m": fact["stairs_distance_m"]}
    for key in ("step_count", "handrail", "incline"):
        if fact.get(key):
            stairs[key] = fact[key]
    return {
        "kind": "stairs",
        "index": fact["index"],
        "distance_m": fact["distance_m"],
        "road": None,
        "stairs": stairs,
        "source_facts": [fact],
    }


def _turn_maneuver(fact: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "kind": _TURN_KIND.get(fact["turn"], "continue"),
        "index": fact["index"],
        "distance_m": fact["distance_m"],
        "road": fact.get("road"),
        "turn": {
            "classification": fact["turn"],
            "delta_deg": fact["delta_deg"],
        },
        "source_facts": [fact],
    }


def _continue_maneuver(fact: Dict[str, Any]) -> Dict[str, Any]:
    """A meaningful transition, interpreted: "the way changed under you"."""
    return {
        "kind": "continue",
        "index": fact["index"],
        "distance_m": fact["distance_m"],
        # The way the walk continues on.
        "road": fact.get("to"),
        "from": fact.get("from"),
        "to": fact.get("to"),
        "source_facts": [fact],
    }


def build_pedestrian_plan(facts: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Interpret the 4B.1 fact stream into a sparse, ordered planning plan.

    ``facts`` must be the ordered ``maneuvers`` list of a single walk (from
    ``FootRouter.walk`` / ``build_pedestrian_maneuvers``). The plan is a pure
    function of the facts — no graph, no geometry re-derivation — so the source
    semantics stay exclusively in :mod:`pedestrian_maneuvers`.

    Returns an ordered list of maneuvers, each with ``kind``, ``index``,
    ``distance_m``, ``distance_to_next_m``, ``road``, ``source_facts`` and
    kind-specific attributes (``crossing``/``stairs``/``turn``/``from``/``to``).
    Empty input yields an empty plan. depart is always first and arrive always
    last; cross and stairs always survive; everything else is salience-filtered
    per the module docstring's precedence rules.
    """
    if not facts:
        return []

    anchors: List[Dict[str, Any]] = []    # depart / arrive / cross / stairs
    # Candidates carry their normalized kind alongside, WITHOUT mutating the
    # 4B.1 facts (they are what the /foot wire ships as `maneuvers`).
    candidates: List[Tuple[Dict[str, Any], str]] = []  # (fact, kind)

    spans: List[Tuple[Optional[int], int]] = []
    for f in facts:
        t = f.get("type")
        if t in ("depart", "arrive", "cross", "stairs"):
            anchors.append(f)
            span = _span_of(f)
            if span is not None:
                spans.append(span)
        elif t == "turn":
            candidates.append((f, _TURN_KIND.get(f["turn"], "continue")))
        elif t == "transition":
            candidates.append((f, "continue"))

    def inside_span(idx: int) -> bool:
        for lo, hi in spans:
            if lo is not None and lo <= idx <= hi:
                return True
        return False

    # R1 + R2: drop candidates claimed by run spans, and transitions that are
    # mere way segmentation.
    kept: List[Tuple[Dict[str, Any], str]] = []
    for f, kind in candidates:
        if inside_span(f["index"]):
            continue
        if f["type"] == "turn" or _meaningful_transition(f):
            kept.append((f, kind))

    # R3: spacing suppression among candidates, keeping the sharper.
    sparse: List[Tuple[Dict[str, Any], str]] = []
    for f, kind in kept:
        if (sparse
                and f["distance_m"] - sparse[-1][0]["distance_m"] < MIN_MANEUVER_SPACING_M
                and _candidate_rank(f, kind) > _candidate_rank(*sparse[-1])):
            sparse[-1] = (f, kind)
        elif (sparse
              and f["distance_m"] - sparse[-1][0]["distance_m"] < MIN_MANEUVER_SPACING_M):
            continue                       # lower-or-equal rank: the earlier wins
        else:
            sparse.append((f, kind))

    # R5: wiggle cancellation. A run of consecutive kept candidates whose gaps
    # fit in WIGGLE_CLUSTER_M and that are ALL slight bends with a small NET
    # direction change (the signed deltas sum to less than WIGGLE_NET_DEG) is
    # the path bending and returning — a serpentine — not guidance, so the
    # whole cluster is dropped and NOTHING inside it is announced. A cluster
    # containing a real turn is a real corner with approach/exit geometry,
    # which R4 handles instead.
    i = 0
    wiggle_ok: List[Tuple[Dict[str, Any], str]] = []
    while i < len(sparse):
        j = i
        while (j + 1 < len(sparse)
               and sparse[j + 1][0]["distance_m"] - sparse[j][0]["distance_m"]
               <= WIGGLE_CLUSTER_M):
            j += 1
        run = sparse[i:j + 1]
        if (len(run) >= 2
                and all(f["type"] == "turn" and k.startswith("slight_") for f, k in run)
                and abs(sum(f["delta_deg"] for f, _k in run)) < WIGGLE_NET_DEG):
            i = j + 1                       # the whole serpentine is noise
            continue
        wiggle_ok.extend(run)               # a real corner: keep every member
        i = j + 1
    sparse = wiggle_ok

    # R4: a slight bend beside ANY other kept turn is bearing noise; a lone
    # slight on a straight stretch is a real bend.
    turn_positions = [f["distance_m"] for f, _k in sparse if f["type"] == "turn"]
    final: List[Tuple[Dict[str, Any], str]] = []
    for f, kind in sparse:
        if f["type"] == "turn" and kind.startswith("slight_"):
            others = [d for d in turn_positions if abs(d - f["distance_m"]) > 1e-6]
            if any(abs(d - f["distance_m"]) <= SLIGHT_SUPPRESSION_RADIUS_M
                   for d in others):
                continue
        final.append((f, kind))

    # Precedence ordering: position first, then salience (a documented guard —
    # 4B.1 already makes co-located facts exclusive).
    events: List[Tuple[Dict[str, Any], str]] = [(f, _anchor_kind(f)) for f in anchors]
    events += final
    events.sort(key=lambda pair: (pair[0]["index"], -_candidate_rank(*pair)))

    plan: List[Dict[str, Any]] = []
    for f, _kind in events:
        t = f["type"]
        if t == "depart":
            plan.append({
                "kind": "depart", "index": f["index"], "distance_m": f["distance_m"],
                "road": f.get("road"), "source_facts": [f],
            })
        elif t == "arrive":
            plan.append({
                "kind": "arrive", "index": f["index"], "distance_m": f["distance_m"],
                "road": f.get("road"), "source_facts": [f],
            })
        elif t == "cross":
            plan.append(_cross_maneuver(f))
        elif t == "stairs":
            plan.append(_stairs_maneuver(f))
        elif t == "turn":
            plan.append(_turn_maneuver(f))
        else:  # transition -> continue
            plan.append(_continue_maneuver(f))

    for idx, m in enumerate(plan):
        nxt = plan[idx + 1] if idx + 1 < len(plan) else None
        m["distance_to_next_m"] = (
            round(nxt["distance_m"] - m["distance_m"], 1) if nxt is not None else 0.0)
    return plan