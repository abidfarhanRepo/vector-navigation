# vector-contracts

> **Owner squad:** D3 (Architecture & Standards)
> **Purpose:** Language-neutral shared contracts for all Vector services (ADR-0001, ADR-0003).
> **Canonical standards:** vector-governance.

This repository is the **single home for cross-cutting contracts**. Product repositories import or
generate from these definitions; they do not redefine shared types (Bible §7 D6). It contains no
runtime code — only specifications.

## Catalog

| Folder | Contents | Format |
|--------|----------|--------|
| `api/` | REST/gRPC API surface | OpenAPI 3 |
| `proto/` | Wire-format messages & services | Protobuf (proto3) |
| `schemas/` | JSON Schemas for shared types | JSON Schema 2020-12 |
| `events/` | Event contracts + standard envelope | YAML |
| `dtos/` | Shared DTO examples (canonical shapes) | JSON |
| `errors/` | Centralized error codes | JSON |
| `validation/` | Validation rules binding types to schemas | YAML |

## Contract lifecycle
- Producers own versioning (Bible §14 V2). Breaking changes -> MAJOR + deprecation window (V3).
- Changes are proposed via `#contracts` and reviewed by D3 + impacted SLAs.
- CI (lightweight) parses all YAML/JSON; semantic linting (Spectral/Protoc) is a future gate.

## Consuming
- **Node/TS tier:** generate types from `proto/` and `schemas/` into `vector-common`.
- **Other tiers:** generate from the same sources when their toolchains are installed (ADR-0006).
