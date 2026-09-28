# ADR-0014 — Self-hosted local CI via `act` + Docker (no GitHub Actions)

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** D9-Ops / D1-Platform
- **Supersedes:** none
- **Superseded by:** none

## Context
GitHub Actions is **not available** in this environment. All nine product repos carry an
identical `.github/workflows/ci.yml`, but the service that would execute it does not run here. We
still need CI that (a) proves repo structure/metadata in the current scaffolding scope and (b) will
later prove native builds once Python/Rust toolchains are installed (ADR-0006). CI must be
reproducible, faithful to the declared workflows, and require no external service.

## Decision
Self-host CI **on this machine** using [`act`](https://github.com/nektos/act) (nektos/act), which
executes the existing GitHub Actions workflows inside Docker (Docker Desktop). The declared
`ci.yml` stays the **single source of truth** — nothing is duplicated.

- Each repo's `.github/workflows/ci.yml` is annotated as the canonical CI spec, executed locally by
  `act` (no GitHub Actions service).
- A workspace-root orchestrator `run-ci.mjs` reads `vector-governance/registry/registry.yaml` and,
  for every registered repo, runs:
  `act --defaultbranch main -P ubuntu-latest=catthehacker/ubuntu:act-latest push -W .github/workflows/ci.yml`
  The `-P` flag pins the runner image non-interactively; `--defaultbranch main` satisfies the
  workflow's `branches: [main]` filter. `act` reproduces `checkout → setup-node@20 → npm install →
  validate → test`.
- Root tooling (`package.json` with `ci`/`ci:dry` scripts, `.gitignore`, `CI.md`) is **CI-only** and
  is intentionally **not** a product repo and **not** listed in the registry/KG.

## Consequences
+ No dependency on GitHub; CI runs locally and is faithful to the declared workflows.
+ Adding a repo to the registry auto-includes it in CI (data-driven); no orchestrator change.
+ When native toolchains arrive, extending each `ci.yml` (add `setup-python` / `cargo` steps) is
  picked up automatically — the orchestrator needs no edit.
+ `-P` image pin removes `act`'s interactive image prompt, so runs are non-interactive/scriptable.
- One-time setup: Docker Desktop running + `act` installed (winget) and both on PATH.
- First run pulls the runner image + actions (cached afterward); runs are container-per-repo.

## Alternatives considered
- **Native Node orchestrator mirroring the steps (no Docker):** rejected — duplicates `ci.yml` step
  logic; `act` keeps `ci.yml` as the single source of truth (chosen "Keep as declarative spec").
- **Git pre-commit hooks:** rejected as the primary runner — no cross-repo view, not a full CI run.
- **Scheduled daemon (Windows Task Scheduler):** deferred — manual `npm run ci` from the root
  satisfies the current need; can be added later for a persistent cadence.
- **Leave `ci.yml` as dead files:** rejected — misleading about what actually runs.

## References
- ADR-0006 (deferred Python/Rust toolchains)
- `run-ci.mjs`, `CI.md` at the workspace root (`C:\Users\PC\Desktop\Vector`)
- Bible §11 A2 (ADR format), §8 R6/R7 (registry/KG)
