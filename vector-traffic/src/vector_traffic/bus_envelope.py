"""Event-bus envelope helpers for vector-traffic (stdlib only).

Mirrors vector-routing/src/vector_routing/bus_envelope.py so the traffic
engine can publish congestion updates to the shared vector-bus without
importing any sibling repository (ADR-0003). Coordinates are [lat, lon]
float pairs to match the bus envelope convention.

A TRAFFIC_CONGESTION envelope (intent=NOTIFY -> #broadcast) carries the live
per-segment congestion state so downstream consumers (e.g. the routing engine)
can avoid congested corridors.
"""

import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

# Canonical traffic-agent address on the bus.
TRAFFIC_AGENT_ADDRESS = "agent://traffic.vector-01"

INTENTS = ("TASK", "ASSIGN", "REVIEW", "ESCALATE", "NOTIFY", "CONTRACT")
CHANNEL_BROADCAST = "#broadcast"


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def make_traffic_congestion(
    segments: List[Any],
    from_addr: str = TRAFFIC_AGENT_ADDRESS,
    correlation_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Build a valid TRAFFIC_CONGESTION envelope (published to #broadcast).

    ``segments`` are ``TrafficSegment`` (or duck-typed) objects exposing
    ``segment_id``, ``geometry`` (list of (lon, lat) tuples), ``free_flow_kmh``,
    ``mean_speed_kmh``, ``probe_count`` and ``congestion``.
    """
    cid = correlation_id or str(uuid.uuid4())
    payload_segments = []
    for s in segments:
        payload_segments.append(
            {
                "segment_id": s.segment_id,
                "geometry": [[lat, lon] for lon, lat in s.geometry],
                "free_flow_kmh": s.free_flow_kmh,
                "mean_speed_kmh": s.mean_speed_kmh,
                "probe_count": s.probe_count,
                "congestion": s.congestion,
            }
        )
    return {
        "id": str(uuid.uuid4()),
        "correlation_id": cid,
        "from": from_addr,
        "to": CHANNEL_BROADCAST,
        "intent": "NOTIFY",
        "priority": "P2",
        "sla_ms": 30000,
        "timestamp": _now_iso(),
        "payload": {
            "kind": "traffic_congestion",
            "segments": payload_segments,
        },
    }


def is_traffic_congestion(env: Any) -> bool:
    """True iff ``env`` is a traffic congestion update we should consume."""
    if not isinstance(env, dict):
        return False
    payload = env.get("payload")
    if not isinstance(payload, dict):
        return False
    return payload.get("kind") == "traffic_congestion"
