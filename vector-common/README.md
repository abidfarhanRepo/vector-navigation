# vector-common

> **Owner squad:** D3 (Architecture & Standards)
> **Purpose:** Shared domain models, geometry primitives, coordinate types, identifiers, error
> handling, and cross-cutting utilities for the Node/TS tier (ADR-0007).
> **Canonical standards:** vector-governance. **Cross-language truth:** vector-contracts.

This is the single home for shared concepts. **No other repository duplicates these** — they import
from `vector-common`. Plain ESM JavaScript + JSDoc (no build step, zero runtime dependencies).

## What it owns
| Module | Responsibility |
|--------|----------------|
| `src/units.js` | `clamp`, `deg2rad`, `EARTH_RADIUS_M` |
| `src/coords.js` | `Coordinate` (WGS84), haversine distance |
| `src/geometry.js` | `BoundingBox` (contains/intersects/area) |
| `src/ids.js` | `EntityId` (`<type>:<uuid>`) |
| `src/errors.js` | `VectorError` + `errorFromCode` + `ERROR_CODES` (mirror of contracts) |
| `src/validation.js` | `assertCoordinate` / `assertBoundingBox` / `assertRange` |

## Usage
```js
import { Coordinate, BoundingBox, VectorError } from 'vector-common';
const c = new Coordinate(52.52, 13.405);
const b = new BoundingBox(52.3, 13.2, 52.7, 13.6);
console.log(b.contains(c));
```

## Relationship to vector-contracts
The JSON Schemas / Protobuf in `vector-contracts` are the **language-neutral** source of truth. When
the Python/Rust tiers are installed (ADR-0006), their bindings are generated from those contracts;
`vector-common` is the JS implementation of the same shapes. Keep the two aligned.

## Tests
`npm test` runs the Node built-in test runner (`test/*.test.js`).
