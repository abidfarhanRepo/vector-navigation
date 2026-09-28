# vector-bus-client

Shared **event-bus client** for Vector Python engines (stdlib-only, zero
runtime dependencies). This package is the **single source of truth** (ADR-0007)
for bus connectivity and is **vendored** into consuming engines
(`vector-routing`, `vector-traffic`, ...) so each repo stays self-contained for
its per-repo CI gate.

## Surface

```python
from vector_bus_client import create_bus

# Dev / tests (no VECTOR_BUS_URL) -> in-memory LocalBus.
# Live (VECTOR_BUS_URL set)         -> NetworkBusClient (HTTP + SSE).
bus = create_bus(os.environ.get("VECTOR_BUS_URL"))
bus.publish("#broadcast", envelope)
bus.subscribe("#tasks", "routing-01", handler)  # handler(env, ack)
```

- `LocalBus` — in-memory double; `publish` synchronously dispatches to all
  matching subscribers; `ack` records the delivery id.
- `NetworkBusClient` — HTTP+SSE client for the live `vector-bus` server
  (`POST /publish`, `GET /subscribe` SSE stream, `POST /ack`).

## Consuming engines

Engines import the **vendored** copy, never this repo directly (ADR-0003):

```python
from vector_bus_client import create_bus
```

To refresh a vendored copy after changing this repo:

```bash
python3 scripts/sync_vendor.py /path/to/consuming/repo
```

The engine CI runs `scripts/check_vendor.py` to fail the build if the vendored
copy diverges from this source.
