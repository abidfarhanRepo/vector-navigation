# Architecture Decision Records

This folder holds the repository's ADRs. Format follows the Vector Engineering Bible §11 A2:
**Context -> Decision -> Consequences -> Alternatives considered -> Status**.

- ADRs are immutable once Accepted. Reversal requires a NEW ADR that SUPERSEDES the old; the old is marked, never deleted.
- Index new ADRs in this file.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [adr-bus-delivery](adr-bus-delivery.md) | Bus delivery semantics: at-least-once, idempotent consume, dead-letter, backpressure | Accepted |
| [adr-bus-envelope](adr-bus-envelope.md) | Standard envelope validation at the publish boundary | Accepted |
| [adr-bus-channel](adr-bus-channel.md) | Channel router + SLA-driven escalation | Accepted |
