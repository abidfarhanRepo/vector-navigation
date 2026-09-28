# ADR-0009 — vector-tile-server uses Rust engine

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** CTO / D2 (Product Engineering)
- **Supersedes:** none
- **Superseded by:** none

## Context
The serving engine for generated vector tiles needs predictable low-latency throughput and safe
concurrency. The mixed-by-layer stack (adr-0003) reserves engines for Rust/Go. We must pick one and
prove the toolchain in S0.

## Decision
Use **Rust** (stable) for `vector-tile-server`, with `axum` + `tokio` for the HTTP surface. A trivial
`GET /healthz` endpoint proves the engine toolchain. The choice is recorded so downstream tiers stop
debating it.

## Consequences
- Strong performance and memory safety at the serving boundary.
- Slightly heavier CI image and a compile step vs Go; acceptable for an engine repo.
- Native compile/run deferred in S0 (adr-0006) until the Rust toolchain is provisioned; source is committed now.

## Alternatives considered
- **Go:** faster cold CI and stdlib `net/http`; rejected to keep a single systems language with the
  future storage/compute engines and best-in-class perf ceiling.
- **Node/TS:** simpler CI now but weaker for a hot serving engine; rejected per adr-0003.

## References
- adr-0003 (mixed-by-layer), adr-0006 (runtime install), Blueprint §11, Bible §7 D6.
