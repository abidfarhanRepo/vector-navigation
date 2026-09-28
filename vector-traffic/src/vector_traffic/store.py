"""Live traffic state persistence for vector-traffic.

Two interchangeable backends behind one interface, mirroring
vector-map-store (ADR-0025):

* ``MemoryTrafficStateStore`` — in-memory fallback (default; used in CI
  isolation and when no ``VECTOR_PG_DSN`` is set).
* ``PostGISTrafficStateStore`` — production backend: persists per-segment
  congestion (speed / congestion / probe_count) keyed by ``segment_id`` with a
  spatial ``geom`` column and GIST index, and crowdsourced ``incidents``.

``TrafficStateStore(dsn=...)`` is a factory. psycopg is lazy-imported inside the
PostGIS backend so importing this module never requires the driver (keeps the
stateless compute path dependency-free).
"""

import json
import os
import time
from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Optional, Tuple

from .traffic import TrafficSegment

FeatureBBox = Tuple[float, float, float, float]  # (min_lon, min_lat, max_lon, max_lat)
DEFAULT_DSN_ENV = "VECTOR_PG_DSN"


@dataclass
class TrafficState:
    segment_id: str
    geometry: List[Tuple[float, float]]
    free_flow_kmh: float
    mean_speed_kmh: Optional[float]
    probe_count: int
    congestion: str

    def to_segment(self) -> TrafficSegment:
        return TrafficSegment(
            segment_id=self.segment_id,
            geometry=self.geometry,
            free_flow_kmh=self.free_flow_kmh,
            mean_speed_kmh=self.mean_speed_kmh,
            probe_count=self.probe_count,
            congestion=self.congestion,
        )


@dataclass
class IncidentRow:
    id: str
    lon: float
    lat: float
    kind: str
    note: str
    created: float


def _state_from_segment(r: TrafficSegment) -> TrafficState:
    return TrafficState(
        segment_id=r.segment_id,
        geometry=[(float(lon), float(lat)) for lon, lat in r.geometry],
        free_flow_kmh=r.free_flow_kmh,
        mean_speed_kmh=r.mean_speed_kmh,
        probe_count=r.probe_count,
        congestion=r.congestion,
    )


class MemoryTrafficStateStore:
    """In-memory traffic state store (no external services)."""

    def __init__(self) -> None:
        self._states: Dict[str, TrafficState] = {}
        self._incidents: List[IncidentRow] = []
        self._inc_seq = 0

    def upsert(self, segment: TrafficSegment) -> None:
        self._states[segment.segment_id] = _state_from_segment(segment)

    def get(self, segment_id: str) -> Optional[TrafficState]:
        return self._states.get(segment_id)

    def all(self) -> List[TrafficState]:
        return list(self._states.values())

    def query_bbox(self, bbox: FeatureBBox) -> List[TrafficState]:
        min_lon, min_lat, max_lon, max_lat = bbox
        out: List[TrafficState] = []
        for st in self._states.values():
            for lon, lat in st.geometry:
                if min_lon <= lon <= max_lon and min_lat <= lat <= max_lat:
                    out.append(st)
                    break
        return out

    # W42/W43: incidents -----------------------------------------------------
    def add_incident(self, lon: float, lat: float, kind: str, note: str = "") -> IncidentRow:
        self._inc_seq += 1
        row = IncidentRow(id="inc-%d" % self._inc_seq, lon=lon, lat=lat,
                          kind=kind or "hazard", note=note or "",
                          created=time.time())
        self._incidents.append(row)
        return row

    def list_incidents(self) -> List[IncidentRow]:
        return list(self._incidents)


class PostGISTrafficStateStore:
    """Production PostGIS traffic-state store (ADR-0025 pattern)."""

    def __init__(self, dsn: str) -> None:
        try:
            import psycopg  # noqa: F401  (lazy; must be installed for this backend)
        except ImportError as exc:  # pragma: no cover - defensive
            raise RuntimeError(
                "PostGISTrafficStateStore requires 'psycopg' (pip install -r requirements.txt)"
            ) from exc
        self._dsn = dsn
        self._conn = psycopg.connect(dsn, autocommit=True, row_factory=psycopg.rows.dict_row)
        self._ensure_schema()

    def _ensure_schema(self) -> None:
        from .schema import SCHEMA_SQL

        with self._conn.cursor() as cur:
            cur.execute(SCHEMA_SQL)

    @staticmethod
    def _geom_json(segment: TrafficSegment) -> str:
        return json.dumps(
            {"type": "LineString", "coordinates": [[lon, lat] for lon, lat in segment.geometry]}
        )

    def upsert(self, segment: TrafficSegment) -> None:
        geom = self._geom_json(segment)
        with self._conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO traffic_state
                    (segment_id, geom, free_flow_kmh, mean_speed_kmh, probe_count, congestion)
                VALUES (%s, ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326), %s, %s, %s, %s)
                ON CONFLICT (segment_id) DO UPDATE
                  SET geom = EXCLUDED.geom,
                      free_flow_kmh = EXCLUDED.free_flow_kmh,
                      mean_speed_kmh = EXCLUDED.mean_speed_kmh,
                      probe_count = EXCLUDED.probe_count,
                      congestion = EXCLUDED.congestion,
                      updated_at = now()
                """,
                (
                    segment.segment_id,
                    geom,
                    segment.free_flow_kmh,
                    segment.mean_speed_kmh,
                    segment.probe_count,
                    segment.congestion,
                ),
            )

    def _row_to_state(self, row) -> TrafficState:
        coords = row["geometry"]["coordinates"]
        return TrafficState(
            segment_id=row["segment_id"],
            geometry=[(float(lon), float(lat)) for lon, lat in coords],
            free_flow_kmh=float(row["free_flow_kmh"]),
            mean_speed_kmh=(None if row["mean_speed_kmh"] is None else float(row["mean_speed_kmh"])),
            probe_count=int(row["probe_count"]),
            congestion=row["congestion"],
        )

    def get(self, segment_id: str) -> Optional[TrafficState]:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT segment_id,
                       ST_AsGeoJSON(geom)::jsonb AS geometry,
                       free_flow_kmh, mean_speed_kmh, probe_count, congestion
                FROM traffic_state WHERE segment_id = %s
                """,
                (segment_id,),
            )
            row = cur.fetchone()
            return self._row_to_state(row) if row else None

    def all(self) -> List[TrafficState]:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT segment_id,
                       ST_AsGeoJSON(geom)::jsonb AS geometry,
                       free_flow_kmh, mean_speed_kmh, probe_count, congestion
                FROM traffic_state
                """
            )
            return [self._row_to_state(r) for r in cur.fetchall()]

    def query_bbox(self, bbox: FeatureBBox) -> List[TrafficState]:
        min_lon, min_lat, max_lon, max_lat = bbox
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT segment_id,
                       ST_AsGeoJSON(geom)::jsonb AS geometry,
                       free_flow_kmh, mean_speed_kmh, probe_count, congestion
                FROM traffic_state
                WHERE ST_Within(geom, ST_MakeEnvelope(%s, %s, %s, %s, 4326))
                """,
                (min_lon, min_lat, max_lon, max_lat),
            )
            return [self._row_to_state(r) for r in cur.fetchall()]

    # W42/W43: incidents -----------------------------------------------------
    def add_incident(self, lon: float, lat: float, kind: str, note: str = "") -> IncidentRow:
        import uuid

        inc_id = "inc-%s" % uuid.uuid4().hex[:12]
        with self._conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO incidents (id, geom, kind, note)
                VALUES (%s, ST_SetSRID(ST_MakePoint(%s, %s), 4326), %s, %s)
                ON CONFLICT (id) DO UPDATE
                  SET kind = EXCLUDED.kind, note = EXCLUDED.note
                RETURNING id, created
                """,
                (inc_id, lon, lat, kind or "hazard", note or ""),
            )
            row = cur.fetchone()
        return IncidentRow(
            id=row["id"],
            lon=lon,
            lat=lat,
            kind=kind or "hazard",
            note=note or "",
            created=float(row["created"].timestamp()),
        )

    def list_incidents(self) -> List[IncidentRow]:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, ST_X(geom) AS lon, ST_Y(geom) AS lat, kind, note,
                       EXTRACT(EPOCH FROM created) AS created
                FROM incidents ORDER BY created DESC
                """
            )
            return [
                IncidentRow(
                    id=r["id"],
                    lon=float(r["lon"]),
                    lat=float(r["lat"]),
                    kind=r["kind"],
                    note=r["note"] or "",
                    created=float(r["created"]),
                )
                for r in cur.fetchall()
            ]

    def close(self) -> None:
        self._conn.close()


def TrafficStateStore(dsn: Optional[str] = None) -> Any:
    """Factory: PostGIS backend when a DSN is available, else in-memory."""
    resolved = dsn or os.environ.get(DEFAULT_DSN_ENV)
    if resolved:
        return PostGISTrafficStateStore(resolved)
    return MemoryTrafficStateStore()
