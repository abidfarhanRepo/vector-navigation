# vector-privacy

Stdlib-only Python privacy-gate library for location data.

One pure function — `apply_gate(kind, points, *, now_ms)` — enforces every
privacy rule (accuracy floor, endpoint truncation, temporal coarsening,
coordinate precision) in one place, plus `mint_pseudonym()` for per-trip
pseudonym rotation. Vendored into `vector-web` (applied on `POST /traces`
before the sink) and later `vector-learning`, so ingest and aggregation share
one definition of the rules.

Binding thresholds (K floor, TTL, truncation distance, accuracy floor) are an
ADR decision — see `vector-governance/adr/adr-0065-privacy-gate.md` (authority)
and the repo-local `adr/adr-0066-privacy-bounded-context.md`.

## Design rule

> **Learn from aggregates over road segments. Never from individual traces.**

Raw GPS lives in a 72 h quarantine and is never what persists; what persists is
per-segment evidence that has already cleared a k-anonymity floor — which is
not personal data at all. This library is the first stage of that pipeline.

## Usage

```python
from vector_privacy.gate import apply_gate, mint_pseudonym

pseudonym = mint_pseudonym()          # once per trip, rotates on trip end
kept, dropped = apply_gate("track", raw_points, now_ms=now_ms)
# kept   -> sanitized points ready for the (TTL'd) quarantine store
# dropped -> {reason: count} for privacy counters (issue 10)
```

## Run tests

```sh
PYTHONPATH=src python -m unittest discover -s tests -v
```
