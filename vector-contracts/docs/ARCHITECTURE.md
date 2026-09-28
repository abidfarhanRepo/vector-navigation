# Architecture Overview — vector-contracts

## Bounded context
`vector-contracts` owns **specification, not implementation**. It is the contract boundary layer of
Project Vector: every service that exposes or consumes data does so against these definitions.

## Why a separate contracts repo
- Avoids type drift across polyrepos (ADR-0001, Bible §7 D6).
- Lets each language tier generate its own bindings from one source (ADR-0003).
- Keeps contract evolution independent of service deployment (Bible §14 V4).

## Internal structure
- `proto/vector.proto` — geometry, tile, and map-change messages + `TileService` (gRPC reference).
- `schemas/*.schema.json` — formal JSON Schemas mirroring the proto messages for JSON/HTTP tiers.
- `api/vector-api.openapi.yaml` — bootstrap HTTP surface referencing the schemas and error codes.
- `events/event-contracts.yaml` — standard envelope + channel taxonomy + event catalog (Blueprint §6).
- `dtos/dto.examples.json` — canonical example shapes.
- `errors/error-codes.json` — centralized `VEC-####` codes (Bible §16).
- `validation/validation-rules.yaml` — which types validate against which schema.

## Evolution
When the E3/KG and CI platforms mature, add: OpenAPI lint (Spectral), Protobuf compile (protoc),
schema registry publish, and contract-consumer impact analysis in CI.
