# Runbook

> Operational guide for this repository. Expand as the service/repo matures.

## Purpose
Crowdsourced Traffic bounded context (Python) that estimates per-segment speed and congestion
from crowdsourced GPS probe points over a road network and emits a GeoJSON FeatureCollection
to the M6 product surface (D2/D6).

## Ownership
See docs/OWNERSHIP.md.

## Local setup
```bash
git clone <repo>
cd <repo>
npm install        # installs dev dependencies (lightweight CI validation only)
npm run validate   # structure / yaml / json / formatting checks
npm test           # repo tests (if any)
```
> NOTE (adr-0006): native runtime (Python/Rust) toolchains are provisioned (Session 4: Python 3.11 via uv). The committed
> native health endpoint / source now runs; CI here proves repo structure + metadata + native build/test.

## Run the Python tests
```bash
PYTHONPATH=src python -m unittest discover -s tests -v
```
`health()` and `main()` run under the provisioned Python toolchain (no file I/O required for `main()`).

## Common tasks
- **Add a doc/ADR:** follow docs/ and adr/ conventions; index it.
- **Change ownership:** update CODEOWNERS AND vector/registry (governance).

## Health & signals
- CI status is the primary health signal.
- `TrafficModel` exposes segment/congestion counts for a quick liveness read.
- Escalations route via the standard envelope to `#escalations`.

## Incident response
1. Capture context in `#escalations`.
2. For security incidents, D5 DMA holds command.
3. Produce a blameless post-mortem within 48h; store as a Lesson in the KG.
