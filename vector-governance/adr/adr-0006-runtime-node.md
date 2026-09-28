# ADR-0006 — Scaffold runtime is Node.js; Python/Rust installed per tier later

- **Status:** Accepted (amended 2026-07-12, Session 9)
- **Date:** 2026-07-11
- **Deciders:** CTO / D1 Platform
- **Supersedes:** none
- **Superseded by:** none

## Context
The environment has **Node.js 26** and **git**. Python (3.11.15 via `uv`) and Rust (1.97.0 via rustup) were provisioned in Session 4, so the per-tier toolchains named in ADR-0003 are now present; Rust links inside the Linux `act` CI container (host has no MSVC `link.exe`), which is sufficient. The
mixed-by-layer decision (ADR-0003) names Python for research and Rust/Go for engines.

## Decision
For Build Session 1 scaffolding, implement shared libraries and CI validators in **Node.js ESM
JavaScript** (zero runtime dependencies; `yaml` only as a dev dependency for validation). Python and
Rust/Go toolchains were provisioned in Session 4; the per-tier runtimes named in ADR-0003 are now present and exercised by native CI (Python `unittest`, Rust `cargo build`/`cargo test`).
The `vector-common` library targets the Node/TS tier; cross-language truth remains in `vector-contracts`.

## Consequences
+ Everything in Session 1 is buildable and testable locally right now.
+ No blocked tasks waiting on toolchain installs.
- `vector-common` is JS, not directly consumable by future Rust engines; mitigated because shared
  *contracts* (types) live in `vector-contracts`, which is language-neutral.

## Alternatives considered
- **Install Python/Rust now:** adds setup risk; not needed for thin scaffolding; deferred.
- **Use only Bash:** no typed shared lib; rejected for `vector-common`.

## References
- ADR-0003, Bible §17 (dependency policy), §5 P1.

## Amendments
- **2026-07-12 (Session 9):** Context and Decision amended to record that Python 3.11 (via `uv`) and Rust 1.97 (via rustup) were provisioned in Session 4 and are exercised by native CI. The core decision (Node.js ESM scaffold; per-tier Python/Rust) is unchanged; the ADR remains Accepted. This resolves the stale 'not installed / deferred' wording noted in the post-Wave-1 doc-hygiene backlog.
