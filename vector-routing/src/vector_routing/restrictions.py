"""Turn restrictions: no-left-turn, no-U-turn, only-straight-on.

Until now these did not exist anywhere in the stack. `osm_to_geojson.py` skipped
relations entirely, so a `type=restriction` relation never reached the graph and
the router would happily drive through a no-left-turn. That is the kind of error
a driver notices immediately and cannot forgive, because obeying it is the whole
reason they are looking at the screen.

## The model

OSM expresses a restriction as a relation with three members:

    from  (way)   the approach
    via   (node)  the junction
    to    (way)   the movement being restricted

and a `restriction` tag: `no_left_turn`, `no_right_turn`, `no_straight_on`,
`no_u_turn`, `only_left_turn`, `only_right_turn`, `only_straight_on`.

`no_*` forbids that one movement. `only_*` forbids **every other** movement from
`from` at `via` — which is the more powerful and more dangerous form, because
getting it backwards silently deletes every other exit from a junction. It is
also the common case: 2,502 of Qatar's 3,360 usable restrictions are
`only_straight_on`.

## Coordinates, not ids

The relation names its via by NODE ID; the routing graph is keyed by
COORDINATE. That join can only be made where the OSM node table still exists,
which is the converter — so `osm_to_geojson.py` emits each restriction already
resolved into coordinates, and this module only has to round them into node
keys. An earlier version emitted the bare node id, which nothing downstream
could resolve; the restrictions were therefore present in the file and inert in
the router, which is the worst of both.

## What is deliberately not supported

**via-way restrictions.** On grade-separated junctions the `via` member is a way
rather than a node, and honouring it requires the search to remember more than
one preceding edge. The router tracks a single predecessor, so those relations
are counted and skipped at ingestion rather than half-enforced. Measured on
Qatar: 436 of 3,802 relations (11%).

**Conditional and modal restrictions** (`restriction:conditional`,
`restriction:hgv`) are ignored. Applying a time-limited or lorry-only rule to a
car at all hours would make Vector refuse legal movements for most of the day.
"""

from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

# Movements that forbid exactly one turn.
NO_TURN = frozenset({
    "no_left_turn", "no_right_turn", "no_straight_on", "no_u_turn",
    "no_entry", "no_exit",
})

# Movements that forbid everything EXCEPT one turn.
ONLY_TURN = frozenset({"only_left_turn", "only_right_turn", "only_straight_on"})


def _key(lon: float, lat: float) -> str:
    """The graph node key for a coordinate.

    Deliberately duplicates ``RoutingGraph.node_key``'s 7-decimal rounding
    rather than importing it, because this module is also used to *inspect* a
    restriction file with no graph loaded. The two are pinned together by
    ``NodeKeyAgreementTest`` — if they ever diverge, every restriction silently
    lands on a key no edge uses and the whole layer goes inert while reporting a
    healthy count.
    """
    return f"{round(float(lon), 7):.7f},{round(float(lat), 7):.7f}"


class TurnRestrictions:
    """Look-up of forbidden (from_node, via_node, to_node) movements.

    OSM restrictions are expressed over WAYS; the graph is over NODES. The
    conversion needs the node immediately before the junction on the `from` way
    and immediately after it on the `to` way, which is what
    :meth:`from_relations` resolves using the way geometries.
    """

    def __init__(self) -> None:
        # Explicit bans: (from_node, via_node, to_node)
        self._banned: Set[Tuple[str, str, str]] = set()
        # only_* : (from_node, via_node) -> the permitted to_nodes
        self._only: Dict[Tuple[str, str], Set[str]] = {}
        # Every node that is the via of at least one restriction. The search
        # consults this first, so the 99.8% of junctions with no restriction
        # cost one set lookup per relaxed edge rather than two dict lookups.
        self._via_nodes: Set[str] = set()
        # Why relations were dropped. Reported, because "loaded but inert" and
        # "nothing loaded" look identical from a route.
        self.stats: Dict[str, int] = {
            "relations": 0, "banned": 0, "only": 0,
            "unknown_kind": 0, "unresolved": 0, "via_interior": 0,
        }

    def __len__(self) -> int:
        return len(self._banned) + len(self._only)

    @property
    def banned_count(self) -> int:
        return len(self._banned)

    @property
    def only_count(self) -> int:
        return len(self._only)

    @property
    def via_node_count(self) -> int:
        return len(self._via_nodes)

    @property
    def via_nodes(self) -> Set[str]:
        """Every node a restriction can ban at.

        ``astar`` needs this to keep the exact (node, approach) search state at
        those junctions and only those. Returned live rather than copied: it is
        read once per relaxed edge on the hot path, and a copy per request over
        a 2,179-entry set would be pure waste.
        """
        return self._via_nodes

    def ban(self, from_node: str, via_node: str, to_node: str) -> None:
        self._banned.add((from_node, via_node, to_node))
        self._via_nodes.add(via_node)

    def require(self, from_node: str, via_node: str, to_node: str) -> None:
        """Permit ``to_node`` from this approach, forbidding every other exit.

        Accumulates rather than overwrites: a junction may carry more than one
        `only_*` relation for the same approach (and a via that is interior to
        the to-way has two legitimate exits). Union is the lenient reading, and
        lenient is the correct direction to fail here — over-enforcing an
        `only_*` deletes legal exits from a junction, which strands the driver.
        """
        self._only.setdefault((from_node, via_node), set()).add(to_node)
        self._via_nodes.add(via_node)

    def is_banned(self, from_node: Optional[str], via_node: str, to_node: str) -> bool:
        """Is moving `from_node -> via_node -> to_node` forbidden?

        `from_node` is None at the very start of a search, where no approach
        exists yet and therefore no restriction can apply.
        """
        if from_node is None or via_node not in self._via_nodes:
            return False
        if (from_node, via_node, to_node) in self._banned:
            return True
        required = self._only.get((from_node, via_node))
        if required is not None and to_node not in required:
            return True
        return False

    def as_predicate(self):
        """A `banned_turn` callable for :func:`vector_geo.algorithms.astar`."""
        return self.is_banned

    def prune_to_graph(self, graph: Any) -> Dict[str, int]:
        """Drop movements the graph cannot express, and say how many.

        An `only_*` names the ONE exit that stays legal, so if that exit is not
        a node in the driving graph the requirement forbids everything and
        closes the junction to that approach entirely. That is not a
        hypothetical: restriction 10319594 at شارع مركز المؤتمرات names a to-way
        the car graph does not contain — the pedestrian/routability filter
        removed it — and the effect was that a vehicle arriving from the south
        could not leave the junction in ANY direction.

        Over-enforcing an `only_*` strands a driver, which is worse than not
        enforcing it, so a requirement with no surviving exit is DROPPED rather
        than kept. Bans are pruned too, purely so the reported counts describe
        what is actually enforced.

        Returns the counts, which the caller logs: a restriction file that
        mostly does not fit its graph is a re-bake mismatch, and it is invisible
        from a route.
        """
        has = graph.has_node
        dropped_bans = 0
        for trip in list(self._banned):
            frm, via, to = trip
            if not (has(frm) and has(via) and has(to)):
                self._banned.discard(trip)
                dropped_bans += 1
        dropped_only = trimmed_only = 0
        for k in list(self._only):
            frm, via = k
            if not (has(frm) and has(via)):
                del self._only[k]
                dropped_only += 1
                continue
            live = {to for to in self._only[k] if has(to)}
            if not live:
                # Every permitted exit is missing: honouring this would close
                # the junction. Drop it.
                del self._only[k]
                dropped_only += 1
            elif live != self._only[k]:
                self._only[k] = live
                trimmed_only += 1
        # Rebuild the via set so the search only pays for junctions that still
        # carry a rule.
        self._via_nodes = {via for _f, via, _t in self._banned} | {v for _f, v in self._only}
        self.stats["pruned_bans"] = dropped_bans
        self.stats["pruned_only"] = dropped_only
        self.stats["trimmed_only"] = trimmed_only
        return {"bans": dropped_bans, "only": dropped_only, "trimmed": trimmed_only}

    def summary(self) -> Dict[str, Any]:
        """Operator-readable state. Zero counts are a valid state, not an error."""
        return dict(
            self.stats,
            banned=len(self._banned),
            only=len(self._only),
            via_nodes=len(self._via_nodes),
        )

    # -- construction --------------------------------------------------------

    @classmethod
    def from_relations(
        cls,
        relations: Iterable[Dict[str, Any]],
        way_nodes: Dict[str, List[str]],
    ) -> "TurnRestrictions":
        """Build from parsed relations plus a way-id -> node-key list mapping.

        Skips silently (and counts nothing) when a relation references a way we
        did not keep — which happens legitimately, because a restriction may
        reference a pedestrian way that the car graph excluded.
        """
        out = cls()
        for rel in relations:
            kind = str(rel.get("restriction") or "").strip().lower()
            if kind not in NO_TURN and kind not in ONLY_TURN:
                continue
            frm = way_nodes.get(str(rel.get("from_way")))
            to = way_nodes.get(str(rel.get("to_way")))
            via = rel.get("via_node_key")
            if not frm or not to or not via:
                continue
            out._apply(kind, frm, to, via)
        return out

    @classmethod
    def from_records(cls, records: Iterable[Dict[str, Any]]) -> "TurnRestrictions":
        """Build from the converter's ``turn_restrictions`` records.

        Each record is ``{"restriction": kind, "via": [lon, lat],
        "from_nodes": [[lon, lat], ...], "to_nodes": [[lon, lat], ...]}`` where
        the node lists are the via node and its immediate neighbours on that
        way — all the geometry a via-node restriction can use.
        """
        out = cls()
        for rec in records or ():
            out.stats["relations"] += 1
            kind = str(rec.get("restriction") or "").strip().lower()
            if kind not in NO_TURN and kind not in ONLY_TURN:
                out.stats["unknown_kind"] += 1
                continue
            via_ll = rec.get("via")
            frm = rec.get("from_nodes")
            to = rec.get("to_nodes")
            if not via_ll or not frm or not to:
                out.stats["unresolved"] += 1
                continue
            try:
                via = _key(via_ll[0], via_ll[1])
                frm_keys = [_key(c[0], c[1]) for c in frm]
                to_keys = [_key(c[0], c[1]) for c in to]
            except (TypeError, ValueError, IndexError):
                out.stats["unresolved"] += 1
                continue
            if not out._apply(kind, frm_keys, to_keys, via):
                out.stats["unresolved"] += 1
        return out

    @classmethod
    def from_feature_collection(cls, fc: Any) -> "TurnRestrictions":
        """Build from a GeoJSON FeatureCollection's ``turn_restrictions`` key."""
        recs = fc.get("turn_restrictions") if isinstance(fc, dict) else None
        return cls.from_records(recs or ())

    # -- internals -----------------------------------------------------------

    def _apply(self, kind: str, frm: Sequence[str], to: Sequence[str], via: str) -> bool:
        """Register one relation. Returns False when it could not be placed."""
        approaches = _neighbours_on_way(frm, via)
        exits = _neighbours_on_way(to, via)
        if not approaches or not exits:
            return False
        if len(approaches) > 1 or len(exits) > 1:
            self.stats["via_interior"] += 1
        for a in approaches:
            if kind in ONLY_TURN:
                for e in exits:
                    self.require(a, via, e)
                self.stats["only"] += 1
            else:
                for e in exits:
                    self.ban(a, via, e)
                self.stats["banned"] += 1
        return True


def _neighbours_on_way(nodes: Sequence[str], via: str) -> List[str]:
    """Every node adjacent to `via` on this way.

    A restriction's `via` node is normally an ENDPOINT of both the from-way and
    the to-way — that is what makes it the junction — in which case there is
    exactly one neighbour and no ambiguity. Measured on the Qatar extract, that
    is true of **all 3,360** usable restrictions.

    When `via` is INTERIOR to the way, both sides are legitimate: the relation
    says "while you are on this way and reach this node", and a two-way street
    reaches it from either direction. Returning both is therefore the faithful
    reading rather than a guess, and returning only one would under-enforce a
    `no_*` — which is the direction that puts a driver through a banned turn.
    """
    out: List[str] = []
    for i, n in enumerate(nodes):
        if n != via:
            continue
        if i > 0 and nodes[i - 1] not in out:
            out.append(nodes[i - 1])
        if i + 1 < len(nodes) and nodes[i + 1] not in out:
            out.append(nodes[i + 1])
    return out
