"""Persistent GPS-trace store for vector-web (Wave 47d -> issues 01/02).

Captured device locations are the raw material for improving the routing
algorithms and the underlying map data. They arrive over ``POST /traces``
(gated by the web token AND the vector_privacy gate) and are written to a
SQLite database (``traces.db``) with an R\*Tree spatial index and day
partitioning.

This is the "data collected and **properly sorted**" deliverable, and it
retires the O(N) spatial scan of the old flat ``traces.jsonl`` (the next
performance wall flagged by the Jul-16 assessment). It is also the **72 h
TTL quarantine**: raw GPS lives here only briefly and is *never* what
persists. What persists (issue 05) is per-segment evidence that has already
cleared a k-anonymity floor.

Privacy is applied by ``vector_privacy`` BEFORE this store is reached (the
client is never trusted to have done its half). This module only persists what
already survived the gate.

Two ``kind`` values are accepted (bipartite by design):

* ``probe`` — low-frequency, interval-style fixes. Fed into the traffic engine.
* ``track`` — high-frequency GPS breadcrumbs captured while navigating.

Stdlib-only (``sqlite3`` + the built-in R\*Tree module).
"""

from __future__ import annotations

import os
import sqlite3
import threading
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Sequence

from vector_privacy.gate import apply_gate, mint_pseudonym, haversine_m

# ---- ingest validation -------------------------------------------------

_VALID_KINDS = ("probe", "track")
_MAX_POINTS_PER_POST = 20000      # guard against a runaway client payload
_MAX_POINT_AGE_DAYS = 5 * 365     # reject obviously bogus timestamps
_MIN_LNG, _MAX_LNG = -180.0, 180.0
_MIN_LAT, _MAX_LAT = -90.0, 90.0

# Default quarantine TTL, in hours (adr-0065). The scheduling/vacuum job
# decides WHEN to call expire(); the threshold lives here so callers never bake
# a magic number.
TTL_HOURS = 72

# How long an import digest is retained (adr-0069 §2/§6). It is deliberately far
# longer than TTL_HOURS: the digest exists to refuse a re-upload of a track that
# may itself be a year old, so a 72 h memory would not do the job. Both retention
# windows live here so the difference is visible rather than discovered.
IMPORT_MAX_AGE_DAYS = 365


def _is_num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _iso_day(ms: int) -> str:
    """Return a ``YYYY-MM-DD`` partition key for a millisecond timestamp."""
    dt = datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)
    return dt.strftime("%Y-%m-%d")


# Provenance (ticket 22 / adr-0070 §5). A closed set of three, validated at the
# ingest boundary. It exists to answer "which tier produced this?" and to roll one
# tier back — it is NOT a trust boundary, the gate is. It must never grow into a
# client id, device string or app version: that would be the per-device identifier
# adr-0065 refuses, arriving through a side door.
SOURCES = ("live", "import", "native")
DEFAULT_SOURCE = "live"


def normalize_source(value: Any) -> str:
    """Coerce a declared source to the closed enum. Anything else is ``live``.

    An unknown value must not reach the column, and refusing the whole payload
    over a provenance label would discard real evidence for a debugging aid.
    """
    return value if value in SOURCES else DEFAULT_SOURCE


_SCHEMA = """
CREATE TABLE IF NOT EXISTS observation(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  trip_pseudonym TEXT NOT NULL,
  kind TEXT NOT NULL,
  lng REAL NOT NULL,
  lat REAL NOT NULL,
  t INTEGER NOT NULL,
  day TEXT NOT NULL,
  speed REAL,
  accuracy REAL,
  heading TEXT,
  source TEXT NOT NULL DEFAULT 'live'
);
CREATE TABLE IF NOT EXISTS import_digest(
  digest TEXT PRIMARY KEY,
  seen_ms INTEGER NOT NULL
);
CREATE VIRTUAL TABLE IF NOT EXISTS observation_rt USING rtree(
  oid, minx, maxx, miny, maxy
);
CREATE INDEX IF NOT EXISTS idx_observation_day ON observation(day);
CREATE INDEX IF NOT EXISTS idx_observation_trip_t ON observation(trip_pseudonym, t);
CREATE INDEX IF NOT EXISTS idx_observation_kind_t ON observation(kind, t);
CREATE INDEX IF NOT EXISTS idx_import_digest_seen ON import_digest(seen_ms);
"""

# Columns added after the first release, applied by ``_migrate``. ``_SCHEMA`` uses
# CREATE TABLE IF NOT EXISTS, so an existing ``traces.db`` never picks a new column
# up from it — and because the store is constructed on first use, a missing
# migration surfaces as a read error far from its cause.
_ADDED_COLUMNS = (
    ("source", "TEXT NOT NULL DEFAULT 'live'"),
)


def validate_trace(payload: Any) -> tuple[Optional[List[Dict[str, Any]]], Optional[str]]:
    """Validate a ``POST /traces`` body.

    Returns ``(points, error)`` where at most one is non-None. Structural
    validation only — privacy rules are applied separately by
    ``vector_privacy.apply_gate`` in the web handler (issue 01).
    """
    if not isinstance(payload, dict):
        return None, "payload must be a JSON object"
    kind = payload.get("kind")
    if kind not in _VALID_KINDS:
        return None, "kind must be 'probe' or 'track'"
    raw_points = payload.get("points")
    if not isinstance(raw_points, list) or not raw_points:
        return None, "points must be a non-empty array"
    return [p for p in raw_points if isinstance(p, dict)], None


class TraceStore:
    """SQLite + R\\*Tree quarantine sink: sorted, indexed, TTL'd.

    Preserves the feature surface ``append`` / ``recent`` / ``count`` so the
    web edge does not churn, and adds ``nearby`` (the spatial primitive issue 05
    map-matches against) and ``expire`` (the 72 h TTL vacuum).
    """

    def __init__(self, path: str) -> None:
        self.path = path
        self._lock = threading.Lock()
        parent = os.path.dirname(os.path.abspath(path))
        os.makedirs(parent, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.executescript(_SCHEMA)
            self._migrate()
            self._conn.commit()

    def _migrate(self) -> None:
        """Add columns an existing database predates. Caller holds the lock."""
        existing = {r[1] for r in self._conn.execute(
            "PRAGMA table_info(observation)").fetchall()}
        for name, decl in _ADDED_COLUMNS:
            if name not in existing:
                self._conn.execute(f"ALTER TABLE observation ADD COLUMN {name} {decl}")

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # -- write -------------------------------------------------------------------
    def append(self, kind: str, points: List[Dict[str, Any]],
               pseudonym: Optional[str] = None,
               meta: Optional[Dict[str, Any]] = None,
               source: str = DEFAULT_SOURCE) -> int:
        """Persist one batch of gated points under a single trip pseudonym.

        ``pseudonym`` is minted here if the caller did not supply one — it is a
        per-*trip* opaque ID; the caller should pass the same pseudonym for every
        POST that belongs to one trip (issue 01). ``source`` records which
        collection tier produced the batch (ticket 22) and is coerced to the closed
        enum here as well as at the edge, so no caller can widen it. Returns the
        stored count.
        """
        if not points:
            return 0
        pseudo = pseudonym or mint_pseudonym()
        src = normalize_source(source)
        rows = []
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        for p in points:
            t = int(p.get("t", now_ms))
            day = _iso_day(t)
            rows.append(
                (pseudo, kind, float(p["lng"]), float(p["lat"]), t, day,
                 float(p["s"]) if _is_num(p.get("s")) else None,
                 float(p["a"]) if _is_num(p.get("a")) else None,
                 p.get("h"), src)
            )
        with self._lock:
            self._conn.executemany(
                "INSERT INTO observation(trip_pseudonym, kind, lng, lat, t, day, speed, accuracy, heading, source) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows)
            if rows:
                last = self._conn.execute("SELECT last_insert_rowid()").fetchone()[0]
                first = last - len(rows) + 1
                for i, (lng, lat) in enumerate(((r[2], r[3]) for r in rows)):
                    self._conn.execute(
                        "INSERT OR REPLACE INTO observation_rt(oid, minx, maxx, miny, maxy) "
                        "VALUES (?, ?, ?, ?, ?)", (first + i, lng, lng, lat, lat))
            self._conn.commit()
        return len(rows)

    def recent(self, limit: int = 100) -> List[Dict[str, Any]]:
        """Return the most recent stored observations (bounded)."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM observation ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
            return [dict(r) for r in rows]

    def count(self) -> int:
        with self._lock:
            return self._conn.execute("SELECT COUNT(*) FROM observation").fetchone()[0]

    def nearby(self, lng: float, lat: float, radius_m: float,
               limit: int = 500) -> List[Dict[str, Any]]:
        """Return observations within ``radius_m`` of ``(lng, lat)``.

        Candidate set is produced by the R\*Tree bbox lookup (NOT a full scan),
        then filtered to the true haversine circle. This is the primitive issue 05
        map-matches tracks against.
        """
        # Approximate a degree->metre factor for the bbox padding.
        cos = _lat_cos(lat)
        pad = radius_m / (111_320.0 * max(0.01, cos))
        minx, maxx = lng - pad, lng + pad
        miny, maxy = lat - pad, lat + pad
        sql = (
            "SELECT o.* FROM observation_rt r JOIN observation o ON o.id = r.oid "
            "WHERE r.minx <= ? AND r.maxx >= ? AND r.miny <= ? AND r.maxy >= ? "
            "ORDER BY o.id DESC LIMIT ?"
        )
        with self._lock:
            rows = self._conn.execute(sql, (maxx, minx, maxy, miny, limit)).fetchall()
        out = []
        for r in rows:
            if haversine_m(lng, lat, r["lng"], r["lat"]) <= radius_m:
                out.append(dict(r))
        return out

    def counts_by_source(self) -> Dict[str, int]:
        """Stored observations per collection tier (ticket 22)."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT source, COUNT(*) AS n FROM observation GROUP BY source").fetchall()
        return {r["source"] or DEFAULT_SOURCE: int(r["n"]) for r in rows}

    def trips_by_source(self) -> Dict[str, int]:
        """Distinct trip pseudonyms per tier — the figure K is counted over."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT source, COUNT(DISTINCT trip_pseudonym) AS n "
                "FROM observation GROUP BY source").fetchall()
        return {r["source"] or DEFAULT_SOURCE: int(r["n"]) for r in rows}

    # -- import idempotency (adr-0069 §6) ----------------------------------------
    #
    # A digest of *gated* geometry per detected trip. It is retained for the import
    # age window rather than the 72 h raw-data TTL, and that asymmetry is the
    # accepted trade in the ADR: the digest is a confirmation oracle (someone
    # already holding an exact track could test whether it was uploaded) and it is
    # accepted because the alternative is a K floor that a routine user accident —
    # clicking upload twice — quietly voids.

    def digest_seen(self, digest: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT 1 FROM import_digest WHERE digest = ?", (digest,)).fetchone()
        return row is not None

    def digests_seen(self, digests: Sequence[str]) -> set:
        """Which of these digests are already present. One query, not N."""
        if not digests:
            return set()
        marks = ",".join("?" for _ in digests)
        with self._lock:
            rows = self._conn.execute(
                f"SELECT digest FROM import_digest WHERE digest IN ({marks})",
                tuple(digests)).fetchall()
        return {r["digest"] for r in rows}

    def record_digest(self, digest: str, seen_ms: Optional[int] = None) -> None:
        if seen_ms is None:
            seen_ms = _now_ms()
        with self._lock:
            self._conn.execute(
                "INSERT OR REPLACE INTO import_digest(digest, seen_ms) VALUES (?, ?)",
                (digest, int(seen_ms)))
            self._conn.commit()

    def expire_digests(self, older_than_ms: Optional[int] = None) -> int:
        """Forget digests past the import age window, so the oracle is bounded."""
        if older_than_ms is None:
            older_than_ms = _now_ms() - IMPORT_MAX_AGE_DAYS * 86_400_000
        with self._lock:
            cur = self._conn.execute(
                "DELETE FROM import_digest WHERE seen_ms < ?", (int(older_than_ms),))
            self._conn.commit()
        return cur.rowcount

    def expire(self, older_than_ms: Optional[int] = None) -> int:
        """Delete observations older than the TTL and ``VACUUM``.

        The vacuum ensures the raw GPS is actually gone from disk, not just
        unlinked — the S2 quarantine must not leave recoverable data behind.
        """
        if older_than_ms is None:
            older_than_ms = int(datetime.now(timezone.utc).timestamp() * 1000) - TTL_HOURS * 3600 * 1000
        with self._lock:
            cur = self._conn.execute("DELETE FROM observation WHERE t < ?", (older_than_ms,))
            deleted = cur.rowcount
            self._conn.execute("DELETE FROM observation_rt "
                               "WHERE oid NOT IN (SELECT id FROM observation)")
            self._conn.commit()
            self._conn.isolation_level = None
            self._conn.execute("VACUUM")
            self._conn.isolation_level = ""
        return deleted


def _lat_cos(lat: float) -> float:
    import math
    return max(0.01, math.cos(math.radians(lat)))


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


_STORE: Optional["TraceStore"] = None


def _make_store(db_path: Optional[str] = None) -> "TraceStore":
    """Process-wide singleton TraceStore (lazy; instance is thread-safe)."""
    global _STORE
    if db_path is not None:
        return TraceStore(db_path)
    if _STORE is None:
        path = os.environ.get("VECTOR_TRACE_DB") or os.environ.get("VECTOR_TRACE_FILE")
        if not path:
            from vector_web import DEFAULTS  # type: ignore
            path = DEFAULTS.get("VECTOR_TRACE_DB") or DEFAULTS.get("VECTOR_TRACE_FILE")
        _STORE = TraceStore(path)
    return _STORE