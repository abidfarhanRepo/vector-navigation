# Runbook

> Operational guide for this repository. Expand as the service/repo matures.

## Purpose
Persistence bounded context (Python) that stores normalized map features and serves feature reads to vector-tile-gen (D2/D1).

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
> NOTE (adr-0006): native runtime (Python/Rust) toolchains are provisioned (Session 4: Python 3.11 via uv, Rust 1.97 via rustup). The committed
> native health endpoint / source now runs; CI here proves repo structure + metadata + native build/test.

## Common tasks
- **Add a doc/ADR:** follow docs/ and adr/ conventions; index it.
- **Change ownership:** update CODEOWNERS AND vector/registry (governance).

## Health & signals
- CI status is the primary health signal.
- Escalations route via the standard envelope to `#escalations`.

## Incident response
1. Capture context in `#escalations`.
2. For security incidents, D5 DMA holds command.
3. Produce a blameless post-mortem within 48h; store as a Lesson in the KG.
