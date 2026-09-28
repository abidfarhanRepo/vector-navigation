# Vector — System Architecture (Foundation v1)

> Owner: Chief Systems Architect
> Audience: Every future AI engineering agent, human principals, infra/security leads.
> Status: DRAFT — to be refined after M0 knowledge-graph research.
> Companion docs: `Vector_Engineering_Bible.md`, `Vector_Master_Roadmap.md`

---

## 1. Executive Summary

Vector must own its core navigation stack end-to-end while bootstrapping from
**open data and open-source tooling** (never proprietary APIs from Google/Mapbox/HERE).
The only sanctioned external *data* dependency at launch is **OpenStreetMap (OSM)**,
which is open data under ODbL — not a proprietary service. Everything built on top
(tile server, routing graph, traffic models, CV reconstruction, HD maps, SDK) is
Vector-owned and Vector-licensed.

The architecture is **event-driven, offline-first, API-first, and cloud-native**,
decomposed into independently deployable services with strict bounded contexts so
that specialized AI agents can own one area without colliding.

---

## 2. Assumptions (explicit, challenge in M0)

1. OSM planet + regional extracts remain freely available under ODbL and are the
   legal baseline map source for v1.
2. Vector will operate its own cloud (or portable cloud-native infra) — K8s,
   object storage, managed Postgres/PostGIS.
3. Initial user base is small → **traffic has a cold-start problem**; we must
   design for sparse-probe inference, not assume dense telemetry.
4. Clients are the web app (vector-web, MapLibre GL) and
   must work offline.
5. Compute for ML/CV training is available (GPU fleet, possibly burst/spot).
6. Licensing: we will keep a clean separation between OSM-derived data and
   Vector-proprietary derivative data to manage ODbL "share-alike" obligations.

---

## 3. Technology-Independence Strategy

| Layer | Proprietary-avoidance rule | Acceptable open building block |
|-------|---------------------------|--------------------------------|
| Base map data | Never license from a vendor | OSM (ODbL) |
| Tile renderer (client) | Vendor renderer OK if OSS | MapLibre GL (BSD) |
| Tile schema | **Vector-owned** | Study OpenMapTiles, design `vector://schema` |
| Tile pipeline | **Vector-owned** | Study Planetiler, build Vector pipeline |
| Routing | **Vector-owned** | Self-hosted routing (vector-routing A* + OSRM adapter in vector-web) |
| Geocoding | **Vector-owned** | Study Pelias/Nominatim internals only |
| CV/SLAM | **Vector-owned** | Study ORB-SLAM3/OpenVSLAM/COLMAP, extend |
| Storage | Open formats | PostGIS, object storage (S3-compatible) |

**Hard rule:** open-source is studied and *learned from*, never shipped as the
product's core. Where we reuse (MapLibre renderer, GDAL, PROJ), it is at the
boundary, not in the owned navigation brain.

---

## 4. High-Level System Context (C4 Level 1–2)

```
                  ┌─────────────────────────────────────────────┐
                  │                 Vector Cloud                 │
                  │                                              │
  OSM Planet ──▶  │  Ingestion → MapStore(PostGIS) → TileGen    │
  Crowd probes ─▶ │                         │            │       │
                  │                         ▼            ▼       │
                  │                    TileStore    RoutingGraph │
                  │                         │            │       │
                  │                    TileServer   RoutingSvc   │
                  │                         │            │       │
                  │              API Gateway / Auth / Search     │
                  │                         │                    │
                  │              Traffic+CV+HDMap pipelines      │
                  └─────────────────────────┬────────────────────┘
                                            │ gRPC/REST/Vector Tile
              ┌──────────────┬──────────────┼──────────────┬──────────────┐
              ▼              ▼              ▼              ▼              ▼
           Web Client     Web(ML)       SDK/API        Fleet/Robot
           (offline)     viewer        consumers      telemetry
```

Event backbone (Kafka/NATS) connects: ingestion → reconstruction → map edits →
tile regeneration → cache invalidation → client sync.

---

## 5. Service Decomposition (Bounded Contexts)

| # | Service | Responsibility | Owning agent |
|---|---------|----------------|--------------|
| 1 | `ingestion` | OSM diffs, regional extracts, crowd probe intake | Backend/GIS |
| 2 | `map-store` | PostGIS authoring DB, Vector schema, edit API | Database/GIS |
| 3 | `tile-gen` | Vector-tile pipeline (Planetiler-style, Vector-specific) | Map Engine |
| 4 | `tile-store` | Object storage + tile index/cache | Infra |
| 5 | `tile-server` | Serves `vector://` MVT, CDN-edge capable | Map Engine |
| 6 | `routing` | Proprietary graph, CH/Hub labeling, profiles | Routing |
| 7 | `search` | Geocoding + POI + reverse geocode | GIS/Backend |
| 8 | `traffic-ingest` | Probe normalization, map-matching | Backend/ML |
| 9 | `traffic-pred` | Spatio-temporal ML forecasting | ML |
|10 | `reconstruction` | CV→map-change proposals, review queue | CV |
|11 | `hdmap` | Lane-level geometry, HD layers | Map Engine/GIS |
|12 | `identity` | AuthN/Z, API keys, per-user privacy | Security |
|13 | `gateway` | API gateway, rate limit, routing to services | Backend/Infra |
|14 | `fleet` | Fleet/robotics telemetry & command | Backend |
|15 | `observability` | OTel/Prometheus/Grafana, SLOs | Infra/Perf |

---

## 6. Data Architecture

- **Authoring store:** PostGIS with a **Vector-owned schema** (not raw OSM tags).
  OSM import maps tags → Vector feature model.
- **Tile format:** Mapbox Vector Tiles (MVT) over a Vector-owned layer schema;
  offline packaging via PMTiles/MBTiles-style bundles (study, then own the packer).
- **Routing graph:** Custom directed multigraph, separate build artifact from
  tiles, versioned, regenerated on map edits.
- **Probe/telemetry store:** Time-series (Timescale/object-parquet) + map-matched
  links. Privacy-anonymized at ingest.
- **Event log:** Immutable append of map-change events → replayable for rebuilds.

---

## 7. Client & Offline-First Architecture

- **vector-web** as the primary web client; **web agents** own
  native plugins (location, sensor, on-device ML, offline tile IO).
- Offline: pre-baked regional tile bundles + on-device routing graph subset.
- On-device ML: ONNX Runtime for traffic/CV models; models shipped as assets.
- Sync protocol: delta map updates + probe upload when connectivity returns.

---

## 8. ML / CV Architecture

- **Training (cloud):** PyTorch → ONNX export → on-device + server inference.
- **Traffic:** graph-neural / spatio-temporal models on sparse probes + context
  (weather, time, events). Cold-start fallback: historical priors.
- **Reconstruction:** smartphone camera+IMU → visual-inertial SLAM → lane/geometry
  proposals → human-in-loop review before merge to `map-store`.
- **HD maps:** fused reconstruction + possible survey data; separate precision layer.

---

## 9. Infrastructure

- K8s (portable: EKS/GKE/self-managed), Helm/Kustomize.
- Object storage (S3-compatible) for tiles, bundles, training data.
- PostGIS (+ read replicas), Redis (cache), Kafka/NATS (events).
- GPU node pools for training & heavy CV; CPU autoscale for serving.
- CDN edge for tiles. IaC in `vector-infra` (Terraform).
- Observability: OpenTelemetry, Prometheus, Grafana, tracing, SLO dashboards.

---

## 10. Security & Privacy (first-class)

- Location data is **privacy-critical**: anonymize/aggregate at ingest, minimize
  retention, user-controlled deletion, GDPR/CCPA compliance by design.
- API keys scoped per tenant; mTLS between services.
- ODbL compliance program: track OSM-derived vs Vector-derived data lineage.
- Threat model per service before M1 build start (Security agent).

---

## 11. Risk & Unknown Register (RAS)

| ID | Risk / Unknown | Severity | Mitigation / Research |
|----|----------------|----------|------------------------|
| R1 | ODbL share-alike on derivative DB | High | Legal analysis M0; clean data lineage split |
| R2 | Traffic cold-start (few users) | High | Sparse-inference models; partnerships/data buy |
| R3 | Routing quality vs mature engines | Med | Benchmark vs mature engines; iterate profiles |
| R4 | CV reconstruction accuracy | Med | Human review queue; confidence thresholds |
| R5 | Offline bundle size vs coverage | Med | Tiered regions; differential updates |
| R6 | Tile-serving cost at scale | Med | CDN + edge cache + PMTiles; cost model |
| R7 | Mobile perf / battery (CV) | Med | On-device throttle; server-assisted |
| U1 | Best on-device routing tradeoff | Open | Prototype server vs on-device in M2 |
| U2 | HD-map sourcing strategy | Open | Research survey+reconstruction hybrid |
| U3 | Regulatory on location data | Open | Privacy-by-design + legal review |

---

## 12. Milestones

- **M0 — Foundation & Knowledge Graph:** clone/analyze source projects, license
  review, finalize schema & RAS. *Prerequisite for all builds.*
- **M1 — Map Display:** ingestion + PostGIS schema + tile-gen + tile-server;
  web viewer renders Vector tiles.
- **M2 — Client Offline:** web offline bundles + MVP
  on-device/online routing.
- **M3 — Routing Engine v1:** proprietary graph, car/bike/pedestrian profiles,
  benchmark vs mature engines.
- **M4 — Search/Geocoding v1.**
- **M5 — Traffic v1:** probe ingest + map-match + prediction model.
- **M6 — Crowdsourced Reconstruction v1:** CV proposals + review queue.
- **M7 — HD Maps v1.**
- **M8 — Fleet/Robotics.**
- **M9 — Global Scale & SDK/API GA.**

---

## 13. Repository Map (recommendation)

Polyrepo with a shared contracts monorepo to avoid agent collisions:

- `vector-contracts` — protobuf/OpenAPI, shared schema, event specs (monorepo core)
- `vector-infra` — Terraform/K8s/IaC
- `vector-ingestion` · `vector-map-store` · `vector-tile-gen`
- `vector-tile-server` · `vector-routing` · `vector-search`
- `vector-traffic` · `vector-reconstruction` · `vector-hdmap`
- `vector-gateway` · `vector-identity` · `vector-fleet`
- `vector-web` (MapLibre web client)
- `vector-sdk` · `vector-web` · `vector-ml` (training pipelines)
- `vector-docs` + `vector-knowledge-graph` (research outputs)

Convention commits, CI per repo, required AI+human review, Definition-of-Done
from the Engineering Bible enforced in CI.

---

## 14. Agent Assignment Matrix

| Agent | Primary services / docs |
|-------|-------------------------|
| Backend | gateway, ingestion, fleet, identity |
| Web | vector-web shell + offline UI |
| Clients | web app (vector-web, MapLibre) |
| Routing | vector-routing graph + profiles |
| GIS | map-store schema, search, hdmap geometry |
| Map Engine | tile-gen, tile-server, hdmap serving |
| CV | reconstruction SLAM → map proposals |
| Infra | vector-infra, deploy, observability |
| Database | PostGIS schema, time-series, migrations |
| Security | threat models, privacy, ODbL lineage |
| ML | traffic-pred, model training/export |
| Performance | latency/cost/battery budgets, benchmarks |
| Testing | per-service test strategy, integration |
| Docs | maintain Vector docs + knowledge graph |
| Research | M0 analysis, license review, benchmarks |

---

## 15. Research Backlog (M0)

1. OSM ODbL legal analysis for a commercial derivative database.
2. Planetiler internals → design Vector tile-gen pipeline.
3. Routing engine evaluation: graph model, speedup, profile system.
4. OpenMapTiles schema → Vector-owned layer schema design.
5. PMTiles/MBTiles → Vector offline packer design.
6. ORB-SLAM3 / OpenVSLAM feasibility on smartphone IMU+camera.
7. Sparse-probe traffic forecasting models (cold-start).
8. MapLibre GL integration patterns for offline + custom schema.
9. Privacy-preserving probe aggregation (DP/aggregation).
10. Cost model for global tile serving (CDN + edge).
