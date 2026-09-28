"""E2 event-bus client for Vector Python engines (stdlib only).

Two implementations share one client interface:

* ``LocalBus`` -- an in-memory double used for hermetic tests and local-dev
  when ``VECTOR_BUS_URL`` is unset. ``publish`` synchronously dispatches to
  all matching subscribers; ``ack`` records the delivery id.
* ``NetworkBusClient`` -- an HTTP+SSE client for the live ``vector-bus``
  server. ``publish`` POSTs JSON; ``subscribe`` opens the SSE stream in a
  daemon thread and invokes the handler per ``message`` frame.

Use ``create_bus(url=None)`` to obtain the appropriate implementation.

This module is the SINGLE SOURCE OF TRUTH (ADR-0007) for the bus client and is
vendored into consuming engines (vector-routing, vector-traffic, ...) so each
repo stays self-contained for its per-repo CI gate.
"""

import json
import threading
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, Dict, List, Optional, Tuple

AckFn = Callable[..., None]
Handler = Callable[[Dict[str, Any], AckFn], None]


class LocalBus:
    """In-memory bus double for tests and local development."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._subscribers: Dict[str, List[Tuple[str, Handler]]] = {}
        self._acked: set = set()
        self._seq = 0

    def publish(self, channel: str, message: Dict[str, Any], channel_kw: Optional[str] = None) -> str:
        """Deliver ``message`` to every subscriber of ``channel``.

        Returns the generated delivery id. Each subscriber handler is invoked
        synchronously with ``(message, ack_fn)`` where ``ack_fn`` records the
        delivery id when called. An optional ``channel_kw`` mirrors the
        ``NetworkBusClient`` signature so callers can pin the delivery channel
        explicitly (e.g. intent ``NOTIFY`` would otherwise be routed to
        ``#broadcast`` by the bus router).
        """
        if channel_kw is not None:
            channel = channel_kw
        with self._lock:
            subs = list(self._subscribers.get(channel, []))
            self._seq += 1
            delivery_id = "local-%d" % self._seq
        for _consumer_id, handler in subs:
            handler(message, lambda *a, **k: self._record_ack(delivery_id))
        return delivery_id

    def subscribe(self, channel: str, consumer_id: str, handler: Handler) -> None:
        """Register ``handler`` for ``channel`` under ``consumer_id``."""
        with self._lock:
            self._subscribers.setdefault(channel, []).append((consumer_id, handler))

    def ack(self, delivery_id: str, consumer_id: str, channel: Optional[str] = None) -> None:
        """Record that ``delivery_id`` was acknowledged."""
        self._record_ack(delivery_id)

    def acked(self, delivery_id: str) -> bool:
        """Return True if ``delivery_id`` has been acknowledged."""
        with self._lock:
            return delivery_id in self._acked

    def _record_ack(self, delivery_id: str) -> None:
        with self._lock:
            self._acked.add(delivery_id)


class NetworkBusClient:
    """HTTP+SSE client for the live ``vector-bus`` server."""

    def __init__(self, url: str) -> None:
        self.url = url.rstrip("/")
        self._lock = threading.Lock()
        self._threads: List[threading.Thread] = []

    def publish(self, channel: str, message: Dict[str, Any]) -> Any:
        """POST ``{channel, message}`` to ``{url}/publish``; raise on non-2xx."""
        body = json.dumps({"channel": channel, "message": message}).encode("utf-8")
        req = urllib.request.Request(
            self.url + "/publish",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                status = resp.status
                payload = resp.read()
        except urllib.error.HTTPError as e:  # non-2xx response
            raise RuntimeError("bus publish to %s failed: HTTP %d" % (channel, e.code)) from e
        if not (200 <= status < 300):
            raise RuntimeError("bus publish to %s failed: HTTP %d" % (channel, status))
        return payload

    def subscribe(self, channel: str, consumer_id: str, handler: Handler) -> threading.Thread:
        """Open the SSE stream for ``channel`` in a daemon thread."""
        thread = threading.Thread(
            target=self._subscribe_loop,
            args=(channel, consumer_id, handler),
            daemon=True,
        )
        thread.start()
        with self._lock:
            self._threads.append(thread)
        return thread

    def _subscribe_loop(self, channel: str, consumer_id: str, handler: Handler) -> None:
        url = (
            self.url
            + "/subscribe?channel="
            + urllib.parse.quote(channel, safe="")
            + "&consumerId="
            + urllib.parse.quote(consumer_id, safe="")
        )
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                event_type: Optional[str] = None
                last_field: Optional[str] = None
                data_lines: List[str] = []
                for raw in resp:
                    line = raw.decode("utf-8", "replace")
                    if line.endswith("\r\n"):
                        line = line[:-2]
                    elif line.endswith("\n"):
                        line = line[:-1]
                    if line == "":
                        if data_lines:
                            self._dispatch(event_type, "\n".join(data_lines), channel, consumer_id, handler)
                        event_type = None
                        last_field = None
                        data_lines = []
                        continue
                    if line.startswith(":"):
                        continue
                    if ":" in line:
                        field, value = line.split(":", 1)
                        field = field.strip()
                        value = value.lstrip()
                        if field == "event":
                            event_type = value
                            last_field = "event"
                        elif field == "data":
                            data_lines.append(value)
                            last_field = "data"
                        else:
                            last_field = field
                    elif last_field == "data":
                        # continuation line (multi-line data payload)
                        data_lines.append(line)
        except Exception:
            # Stream ended or transport error; a hardened client would
            # reconnect. The daemon thread simply terminates here.
            return

    def _dispatch(self, event_type, data, channel, consumer_id, handler) -> None:
        # SSE default event type is "message".
        kind = event_type or "message"
        if kind != "message":
            # deadletter and any other frame types are ignored (logged only).
            return
        try:
            frame = json.loads(data)
        except Exception:
            return
        message = frame.get("message")
        delivery_id = frame.get("deliveryId")
        delivery_channel = frame.get("channel", channel)

        def ack_fn(*a, **k):
            self.ack(delivery_id, consumer_id, delivery_channel)

        handler(message, ack_fn)

    def ack(self, delivery_id: str, consumer_id: str, channel: Optional[str] = None) -> Optional[int]:
        """POST ``{deliveryId, consumerId, channel?}`` to ``{url}/ack``."""
        payload: Dict[str, Any] = {"deliveryId": delivery_id, "consumerId": consumer_id}
        if channel is not None:
            payload["channel"] = channel
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            self.url + "/ack",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status
        except Exception:
            return None


def create_bus(url: Optional[str] = None):
    """Return ``NetworkBusClient(url)`` when ``url`` is set, else ``LocalBus()``."""
    if url:
        return NetworkBusClient(url)
    return LocalBus()
