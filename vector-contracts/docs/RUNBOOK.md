# Runbook

> Operational guide for this repository. Expand as the service/repo matures.

## Purpose
Language-neutral shared contracts (OpenAPI/Protobuf/JSON Schema/events/DTOs/error codes) — the cross-language source of truth all Vector services generate from (D3).

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
