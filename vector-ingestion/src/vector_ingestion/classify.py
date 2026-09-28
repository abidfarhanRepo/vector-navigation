"""Road classification: renderable, car-routable, pedestrian-routable.

Three different questions that were previously answered by one whitelist, which
is how staircases ended up as edges in the car routing graph — 42,455 pedestrian
ways (15,256 footways, 380 flights of steps, 1,349 roads under construction)
were car-routable, and a real Doha route came back beginning "Head northwest on
footway road".

The three concepts are deliberately independent:

``is_renderable``
    Does this belong on the map? A footpath does. Deleting map features because
    a car cannot use them would make the map wrong in a different way.

``is_car_routable``
    May this be an edge in the CAR graph? A footpath may not. Neither may a
    private driveway, a road under construction, or a way tagged
    ``motor_vehicle=no``.

``is_pedestrian_routable``
    May this be an edge in a future pedestrian graph? A footpath may. A
    motorway may not.

The goal is a correct classification, not "exclude everything unusual".
Legitimate but unusual roads — service roads, parking aisles, tracks,
``access=destination`` streets — stay routable, because refusing them means
being unable to reach real destinations.
"""

from typing import Any, Dict, Optional

# --- highway classes --------------------------------------------------------

# Drivable by a car, subject to the access checks below.
CAR_CLASSES = frozenset({
    "motorway", "motorway_link",
    "trunk", "trunk_link",
    "primary", "primary_link",
    "secondary", "secondary_link",
    "tertiary", "tertiary_link",
    "unclassified",
    "residential",
    "living_street",
    "service",       # car parks, drive-throughs, access roads
    "track",         # unsealed but drivable; common outside Doha
    "road",          # "unknown class" -- drivable until proven otherwise
    "busway",
})

# Walkable. `living_street`/`residential`/`service`/`track` appear in BOTH sets:
# a residential street is legitimately used by cars and pedestrians alike.
PEDESTRIAN_CLASSES = frozenset({
    "footway", "path", "steps", "pedestrian", "corridor", "bridleway",
    "living_street", "residential", "service", "track", "unclassified", "road",
    "cycleway",
})

# On the map but never routable for anyone: a road that does not exist yet, or
# no longer does.
NON_EXISTENT_CLASSES = frozenset({"construction", "proposed", "abandoned", "razed"})

# Anything with a `highway` tag is drawn, including the classes above.

# --- access tags ------------------------------------------------------------

# Access values that deny use. `destination` is deliberately NOT here: it means
# "only if you are going there", which is exactly what a navigation destination
# is, and excluding it makes addresses unreachable.
_DENIED = frozenset({"no", "private", "customers", "permit", "military", "delivery"})

# Access values that permit use even when a broader tag denies it.
_ALLOWED = frozenset({"yes", "designated", "permissive", "destination", "official"})

# --- barrier pedestrian semantics (V7.4 4A) --------------------------------
#
# A ``barrier=*`` NODE on a foot-routable way is either something a person may
# pass or something they may not. OSM's default is permissive: most barriers
# exist to stop VEHICLES (gates, bollards, cycle barriers), and until a gate is
# explicitly tagged ``access=private``/``locked=yes`` or ``foot=no`` a
# pedestrian walking through it is the normal reading, not an error. The
# recon's Qatar measurement is the whole reason this function exists: 2,164
# gate nodes are tagged private/no or locked, the tag was dropped at
# ingestion, and Vector confidently routed people through locked gates.
#
# The rules, in OSM's own specificity order (a more specific tag beats a
# broader one, exactly as ``car_access_denied`` does for ways):
#
#   1. ``foot=yes`` (or designated/permissive/...)
#             -> PASS. A surveyor saying this gate is walkable settles it.
#   2. ``foot=no``/private (or any denied value)
#             -> BLOCK. A surveyor saying this gate is not walkable settles it.
#   3. ``access=no``/private (or any denied value) -> BLOCK. The gate is
#      closed to everyone, and the specific foot tag above would have won.
#   4. ``locked=yes`` -> BLOCK. The most unambiguous statement OSM records
#      that a gate is not passable.
#   5. a SOLID barrier kind (fence/wall/...) -> BLOCK. A wall is a wall; no
#      access tag is needed to know a person cannot pass through it.
#   6. anything else -> PASS. Default-permissive, matching OSM semantics. The
#      alternative -- treating an un-tagged gate as blocked -- deletes 5,692
#      legitimate Qatar gates from the walking network and turns correct
#      routes into false "these points are not connected" refusals.
#
# Barring rule 5, access information wins over the barrier kind: a bollard
# tagged ``access=private`` is private, and a gate tagged ``foot=yes`` is
# walkable. The kind only provides the default for an un-tagged barrier.
#
# Only barrier NODES are classified (a barrier=* way is a linear boundary, not
# a traversal point; its semantics are a different feature).

#: ``barrier=*`` values that stop a person outright, no access tag required.
#: A mapped wall on a walk route is a mapping of reality: the route is
#: genuinely severed. Rare on foot ways (1 in the Qatar recon) but when OSM
#: maps one, blocking is the honest reading.
SOLID_BARRIERS = frozenset({
    "fence", "wall", "hedge", "retaining_wall", "city_wall",
    "cement_block", "concrete_block", "concrete",
})

#: Values of ``locked`` that mean the gate is locked shut. OSM's rare
#: ``locked=rear`` (locked from the rear side) is still not passable without
#: the key, so it counts.
_LOCKED_TRUE = frozenset({"yes", "1", "true", "rear", "private"})


def barrier_pedestrian_effect(tags: Dict[str, Any]) -> str:
    """Is a person blocked by this barrier node? Returns ``"block"`` or ``"pass"``.

    The single decision that turns an OSM ``barrier=*`` node into a walking-
    graph statement. Decided once, at ingestion, exactly like
    :func:`is_pedestrian_routable` decides walkability of a way -- so the
    converter, the <region>_barriers.geojson artifact and (via that artifact)
    the engine all read one answer and cannot disagree.
    """
    foot = _val(tags, "foot")
    if foot in _ALLOWED:
        return "pass"
    if foot in _DENIED:
        return "block"
    access = _val(tags, "access")
    if access in _DENIED:
        return "block"
    if _val(tags, "locked") in _LOCKED_TRUE:
        return "block"
    kind = _val(tags, "barrier") or ""
    if kind in SOLID_BARRIERS:
        return "block"
    return "pass"


def _val(tags: Dict[str, Any], key: str) -> Optional[str]:
    v = tags.get(key)
    if v is None:
        return None
    return str(v).strip().lower()


def _highway(tags: Dict[str, Any]) -> str:
    return (_val(tags, "highway") or "")


def car_access_denied(tags: Dict[str, Any]) -> bool:
    """Is a car explicitly barred from this way?

    Specific tags override general ones, which is how OSM access tagging works:
    ``access=private`` + ``motor_vehicle=yes`` means a car MAY use it.
    """
    for key in ("motorcar", "motor_vehicle", "vehicle", "access"):
        v = _val(tags, key)
        if v is None:
            continue
        if v in _ALLOWED:
            return False        # a specific permission settles it
        if v in _DENIED:
            return True
    return False


def foot_access_denied(tags: Dict[str, Any]) -> bool:
    for key in ("foot", "access"):
        v = _val(tags, key)
        if v is None:
            continue
        if v in _ALLOWED:
            return False
        if v in _DENIED:
            return True
    return False


# --- the three questions ----------------------------------------------------

def is_renderable(tags: Dict[str, Any]) -> bool:
    """Does this way belong on the map? Anything with a highway tag does."""
    return bool(_highway(tags))


def is_car_routable(tags: Dict[str, Any]) -> bool:
    """May this way be an edge in the car routing graph?"""
    hw = _highway(tags)
    if not hw:
        return False
    if hw in NON_EXISTENT_CLASSES:
        return False
    # `construction=*` on an otherwise normal highway tag means the same thing.
    if _val(tags, "construction"):
        return False
    if hw not in CAR_CLASSES:
        return False
    return not car_access_denied(tags)


def is_pedestrian_routable(tags: Dict[str, Any]) -> bool:
    """May this way be an edge in a (future) pedestrian graph?"""
    hw = _highway(tags)
    if not hw:
        return False
    if hw in NON_EXISTENT_CLASSES or _val(tags, "construction"):
        return False
    if hw not in PEDESTRIAN_CLASSES:
        # A motorway is not walkable unless explicitly tagged foot=yes, which
        # does happen on some bridges and causeways.
        return _val(tags, "foot") in ("yes", "designated")
    return not foot_access_denied(tags)


def classify(tags: Dict[str, Any]) -> Dict[str, bool]:
    """All three answers at once, for annotating a feature."""
    return {
        "renderable": is_renderable(tags),
        "car": is_car_routable(tags),
        "foot": is_pedestrian_routable(tags),
    }
