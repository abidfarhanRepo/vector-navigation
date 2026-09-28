# Contributing Guide

This repository follows the Vector Engineering Bible and Organizational Blueprint.

## Before you start
1. Confirm the task meets the Definition of Ready (docs/DEFINITION_OF_READY.md).
2. Retrieve relevant ADRs and the squad README from the Knowledge Graph.
3. Branch from `main`: `<type>/<short-desc>-<taskid>` (e.g. `feat/add-match-1142`).

## During development
- One concern per PR (Bible §12 PR1).
- Write/extend tests for behavior and contracts.
- Update docs in the SAME PR (Bible §7 D1); CI fails on doc staleness.
- Cite any ADR that justifies the change.

## Opening a PR
- Description MUST state: what, why (ADR/task link), how to verify, risk class, rollback plan.
- Required approvals: in-boundary >=1 reviewer; cross-ownership primary+secondary CODEOWNER; sensitive/infra/security = DMA sign-off.
- CI MUST be green (lint, test, security, license, coverage, doc-staleness).

## Merging
- Squash-merge to `main`; delete the branch.
- Tag significant merges; never force-push shared branches.
