"""E2 event-bus envelope helpers for vector-routing (stdlib only).

Implements the canonical envelope required by ``vector-bus`` (see ADR-0043 /
``vector-bus/src/envelope.js``). Coordinates are ``[lat, lon]`` float pairs.

This module is intentionally dependency-free beyond the Python standard
library so the routing engine can act as a first-class bus consumer without
importing any sibling repository.
"""

import math
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional

# Canonical routing-agent address on the bus.
ROUTING_AGENT_ADDRESS = "agent://routing.vector-01"

INTENTS = ("TASK", "ASSIGN", "REVIEW", "ESCALATE", "NOTIFY", "CONTRACT")
PRIORITIES = ("P0", "P1", "P2", "P3")
CHANNELS = ("#tasks", "#events", "#contracts", "#escalations", "#broadcast")

REQUIRED_FIELDS = (
    "id",
    "correlation_id",
    "from",
    "to",
    "intent",
    "priority",
    "sla_ms",
    "timestamp",
)


def _now_iso() -> str:
    """Return a current ISO-8601 timestamp string (UTC, non-empty)."""
    return datetime.now(timezone.utc).isoformat()


def _coord(pair: Any) -> list:
    """Coerce a ``[lat, lon]`` pair into a ``[float(lat), float(lon)]`` list."""
    return [float(pair[0]), float(pair[1])]


def _via_list(via: Any) -> list:
    if not via:
        return []
    return [_coord(v) for v in via]


def make_navigate_request(
    requester: str,
    from_ll: Any,
    to_ll: Any,
    via: Any = None,
    profile: str = "car",
    correlation_id: Optional[str] = None,
) -> Dict[str, Any]:
    """Build a valid navigation REQUEST envelope addressed to the routing agent.

    Published to channel ``#tasks``. ``from_ll``/``to_ll``/``via`` entries are
    ``[lat, lon]`` float pairs. ``reply_to`` defaults to ``requester``.
    """
    cid = correlation_id or str(uuid.uuid4())
    return {
        "id": str(uuid.uuid4()),
        "correlation_id": cid,
        "from": requester,
        "to": ROUTING_AGENT_ADDRESS,
        "intent": "TASK",
        "priority": "P2",
        "sla_ms": 5000,
        "timestamp": _now_iso(),
        "payload": {
            "kind": "navigate",
            "from_ll": _coord(from_ll),
            "to_ll": _coord(to_ll),
            "via": _via_list(via),
            "profile": profile,
            "reply_to": requester,
        },
    }


def make_navigation_result(
    request_env: Dict[str, Any],
    ok: bool,
    geojson: Any = None,
    error: Optional[str] = None,
) -> Dict[str, Any]:
    """Build a navigation RESULT envelope (published to ``#events``).

    Echoes the request ``correlation_id`` and routes the reply to
    ``payload.reply_to`` (falling back to the request ``from``).
    """
    req_cid = request_env.get("correlation_id") or request_env.get("id")
    reply_to = None
    payload = request_env.get("payload")
    if isinstance(payload, dict):
        reply_to = payload.get("reply_to")
    if not reply_to:
        reply_to = request_env.get("from", "")
    return {
        "id": str(uuid.uuid4()),
        "correlation_id": req_cid,
        "from": ROUTING_AGENT_ADDRESS,
        "to": reply_to,
        "intent": "NOTIFY",
        "priority": "P2",
        "sla_ms": 5000,
        "timestamp": _now_iso(),
        "payload": {
            "kind": "navigation_result",
            "ok": bool(ok),
            "geojson": geojson,
            "error": error,
        },
    }


def is_valid_envelope(env: Any) -> bool:
    """Validate an envelope against the vector-bus required-field schema."""
    if not isinstance(env, dict) or isinstance(env, list):
        return False
    for field in REQUIRED_FIELDS:
        if field not in env:
            return False
    if not isinstance(env["id"], str):
        return False
    if not isinstance(env["correlation_id"], str):
        return False
    if not isinstance(env["from"], str):
        return False
    if not isinstance(env["to"], str):
        return False
    if env["intent"] not in INTENTS:
        return False
    if env["priority"] not in PRIORITIES:
        return False
    sla = env["sla_ms"]
    if isinstance(sla, bool) or not isinstance(sla, (int, float)) or not math.isfinite(sla):
        return False
    ts = env["timestamp"]
    if not isinstance(ts, str) or len(ts) == 0:
        return False
    return True


def is_navigate_request(env: Any) -> bool:
    """True iff ``env`` is a navigation request for this routing agent."""
    if not isinstance(env, dict):
        return False
    if env.get("to") != ROUTING_AGENT_ADDRESS:
        return False
    payload = env.get("payload")
    if not isinstance(payload, dict):
        return False
    return payload.get("kind") == "navigate"


def is_traffic_congestion(env: Any) -> bool:
    """True iff ``env`` is a traffic congestion update we should consume (Wave 26c)."""
    if not isinstance(env, dict):
        return False
    payload = env.get("payload")
    if not isinstance(payload, dict):
        return False
    return payload.get("kind") == "traffic_congestion"
