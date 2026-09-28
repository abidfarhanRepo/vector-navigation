# Architecture Decision Records

Format follows the Vector Engineering Bible §11 A2:
**Context -> Decision -> Consequences -> Alternatives considered -> Status**.

ADRs are immutable once Accepted. Reversal requires a NEW ADR that SUPERSEDES the old; the old is
marked, never deleted. This repo ratifies org-wide ADRs; product repos hold their own local ADRs.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [adr-0001](adr-0001-polyrepo-strategy.md) | Polyrepo with shared contracts monorepo | Accepted |
| [adr-0002](adr-0002-execution-model.md) | Single-agent execution model with internal personas | Accepted |
| [adr-0003](adr-0003-mixed-stack.md) | Mixed-by-layer technology stack | Accepted |
| [adr-0004](adr-0004-kg-v1-filebased.md) | Knowledge Graph v1 file-based | Accepted |
| [adr-0005](adr-0005-product-first.md) | Product-first sequencing (defer E1-E9 agent-platform) | Accepted |
| [adr-0006](adr-0006-runtime-node.md) | Scaffold runtime = Node.js; Python/Rust later | Accepted |
| [adr-0007](adr-0007-vector-common.md) | vector-common owns all shared domain models | Accepted |
| [adr-0008](adr-0008-vector-playground.md) | vector-playground owns all experiments | Accepted |
| [adr-0014](adr-0014-local-ci-act-docker.md) | Self-hosted local CI via act + Docker (no GitHub Actions) | Accepted |
| [adr-0015](adr-0015-m1-vertical-slice.md) | M1 vertical slice: self-served Map Display | Accepted |
| [adr-0016](adr-0016-terraform-local-docker.md) | Activate Terraform for local Docker deployment (supersedes adr-0010 deferral) | Accepted |
| [adr-0017](adr-0017-agent-platform-foundation.md) | Agent-platform foundation — E9 Registry + E2 Event Bus (lifts adr-0005 deferral for E9+E2) | Accepted |
| [adr-0018](adr-0018-kg-platform.md) | E3 Knowledge Graph Platform (lifts adr-0005 deferral for E3) | Accepted |
| [adr-0019](adr-0019-e1-coa.md) | E1 Agent Orchestration Core (lifts adr-0005 deferral for E1; fleet + runtime polyrepos) | Accepted |
| [adr-0020](adr-0020-e4-ci-gates.md) | E4 Engineering Gates & CI/CD (lifts adr-0005 deferral for E4; ci-svc runtime) | Accepted |
| [adr-0021](adr-0021-e5-security.md) | E5 Security & Compliance Platform (lifts adr-0005 deferral for E5; vault-svc + audit-log + threat-intel + E4 setE5Scanner depth) | Accepted |
| [adr-0022](adr-0022-e6-observability.md) | E6 Observability & Reliability Platform (lifts adr-0005 deferral for E6; otel-svc metrics/logs/traces + alert-mgr) | Accepted |
| [adr-0023](adr-0023-e7-agent-frameworks.md) | E7 Department Agent Frameworks (lifts adr-0005 deferral for E7; agents-svc worker/dma/sla/research) | Accepted |
| [adr-0024](adr-0024-e8-docs-tooling.md) | E8 Documentation & Knowledge Tooling (lifts adr-0005 deferral for E8; docs-svc doc-lint/adr-tool/kg-portal) | Accepted |
| [adr-0025](adr-0025-postgis-persistence.md) | PostGIS authoring store for vector-map-store (replaces S0 in-memory stand-in; psycopg3 + GIST, isolation-safe) | Accepted |
| [adr-0026](adr-0026-route-overlay.md) | M2 live route-overlay — M1 × M2 integration (routing HTTP API behind the tile-server) | Accepted |
| [adr-0028](adr-0028-vision-overlay.md) | M3 live CV overlay — M1 × M3 integration (vision HTTP API behind the tile-server) | Accepted |
| [adr-0029](adr-0029-networked-event-bus.md) | Networked E2 Event Bus — operationalize E2 (BusServer HTTP+SSE + NetworkBusClient fleet adapters, port 8090) | Accepted |
| [adr-0030](adr-0030-reconstruction-bounded-context.md) | Road Reconstruction — vector-reconstruction bounded context + Python stack | Accepted |
| [adr-0031](adr-0031-road-overlay.md) | Road Reconstruction live overlay — M1 × M4 integration (reconstruction HTTP API behind the tile-server) | Accepted |
| [adr-0032](adr-0032-traffic-bounded-context.md) | Crowdsourced traffic — vector-traffic bounded context + Python stack | Accepted |
| [adr-0033](adr-0033-traffic-overlay.md) | Crowdsourced traffic live overlay — M1 × M5 integration (traffic HTTP API behind the tile-server) | Accepted |
| [adr-0034](adr-0034-offline-bounded-context.md) | Offline maps — vector-offline-maps bounded context + Python stack | Accepted |
| [adr-0035](adr-0035-offline-overlay.md) | Offline maps live overlay — M1 × M7 integration (offline HTTP API behind the tile-server) | Accepted |
| [adr-0036](adr-0036-hdmaps-bounded-context.md) | HD maps — vector-hdmaps bounded context + Python stack | Accepted |
| [adr-0037](adr-0037-hdmaps-overlay.md) | HD maps live overlay — M1 × M8 integration (HD-maps HTTP API behind the tile-server) | Accepted |
| [adr-0038](adr-0038-global-bounded-context.md) | Global basemap — vector-global bounded context + Python stack | Accepted |
| [adr-0039](adr-0039-global-overlay.md) | Global basemap live overlay — M1 × phase-9 integration (Global HTTP API behind the tile-server) | Accepted |
| [adr-0041](adr-0041-per-region-traffic-vision-offline-links.md) | Per-region traffic / vision / offline overlay links — vector-global deepening (ADR-0040 follow-on) | Accepted |
| [adr-0042](adr-0042-navigation-overlay.md) | M2 turn-by-turn navigation overlay — M1 × M2 integration (navigation HTTP API behind the tile-server) | Accepted |
| [adr-0043](adr-0043-routing-bus-consumer.md) | Routing bus consumer | Accepted |
| [adr-0044](adr-0044-logistics-bus-consumer.md) | Logistics bus consumer | Accepted |
| [adr-0045](adr-0045-logistics-vrp-multivehicle.md) | Logistics multi-vehicle VRP-lite | Accepted |
| [adr-0046](adr-0046-logistics-cvrp.md) | Logistics capacitated VRP (CVRP) | Accepted |
| [adr-0047](adr-0047-time-window-vrp.md) | Logistics Time-Window VRP (VRPTW) | Accepted |
| [adr-0048](adr-0048-logistics-vrptw-local-search.md) | Logistics VRPTW local-search hardening — Or-opt + cross-route moves | Accepted |
| [adr-0049](adr-0049-bus-authentication.md) | Authenticated, CORS-scoped E2 Event Bus (TokenBroker wired into vector-bus; dev-anonymous fallback) | Accepted |
| [adr-0050](adr-0050-service-layer-auth.md) | Service-layer bearer-token auth for engine HTTP services (vector-web edge + engines) | Accepted |
| [adr-0053](adr-0053-osm-ingestion.md) | Real OSM ingestion (vector-ingestion) | Accepted |
| [adr-0054](adr-0054-tile-encoder-fix.md) | vector-tile-gen uses a spec-valid MVT encoder | Accepted |
| [adr-0055](adr-0055-basemap-wave29.md) | Wave 29 — real basemap tiles (buildings / parks / water / labels) | Accepted |
| [adr-0059](adr-0059-self-hosted-glyphs.md) | Self-hosted glyphs (eliminate third-party font CDN) | Accepted |
| [adr-0060](adr-0060-mobile-flutter-client.md) | Android-first Flutter mobile client — **Superseded by adr-0063** (retained for history; do not delete) | Superseded |
| [adr-0062](adr-0062-waze-navigation-ux-overhaul.md) | Waze-primary navigation UX overhaul (web viewer styling/chrome) | Accepted |
| [adr-0063](adr-0063-web-pwa-first.md) | Web-first distribution via installable PWA (native apps deferred; supersedes adr-0060) | Accepted |
| [adr-0065](adr-0065-privacy-gate.md) | Privacy gate — learn from aggregates over road segments, never from individual traces (K=5, 72 h TTL, truncation, accuracy floor; closes U3 risk) | Accepted |
| [adr-0066](adr-0066-promotion-thresholds-and-tile-invalidation.md) | Promotion thresholds for learned facts + selective tile invalidation | Accepted |
| [adr-0067](adr-0067-time-bands.md) | Replace 168 hourly `hour_of_week` buckets with 8 `time_band` bands to clear K=5 within the 72 h TTL on 99.99% of segments | Accepted |
| [adr-0068](adr-0068-opt-in-collection-and-trip-scope.md) | Trace collection is opt-in, and a stored pseudonym is exactly one trip (server-side HMAC of a client trip token) | Accepted |
| [adr-0069](adr-0069-track-import.md) | Imported recorded tracks are a first-class collection tier (365-day age bound, unknown accuracy, per-segment trips, own consent, idempotent uploads); ratifies the batch-before-vacuum cycle order; corrects the K=5 "five people" reading | Proposed |
| [adr-0070](adr-0070-native-capture-shell.md) | Native shell for background capture, Android-first sideload — **supersedes adr-0063 in part** (native-client deferral, for *capture* only) | Proposed |
| [adr-0071](adr-0071-vlm-detection-backend.md) | Local VLM detection backend for vector-vision (public imagery only, model-as-a-process, stdlib CI default) — **discharges adr-0027's heavy-CV deferral**; creates no collection tier and leaves adr-0065 untouched | Proposed |
| [adr-0072](adr-0072-osrm-routing-engine.md) | OSRM contraction hierarchies become the default routing engine (~3 240 ms → ~6 ms); the learned overlay re-times the CH path instead of reweighting the search, since CH bakes weights at contract time; MLD rejected because `/route?band=` is per-request; Python engine retained for traffic, learned-weighted search, and fallback | Accepted |
| [adr-0073](adr-0073-learn-and-map-vision.md) | "Learn and map like this" north-star — three tiers: (1) collective map facts incl. place intelligence, (2) client-side 3D destination visualization (lingbot-map as UX reference), (3) personal driving policy fork deferred as a separate bounded context | Proposed |
| [adr-0074](adr-0074-destination-precision-learning.md) | Destination-precision learning — recover endpoint precision inside the K-gated centroid (fix ADR-0068 per-batch truncation + precise endpoint consumed only in S3, grid-snapped ≥10 m at S4); modifies ADR-0065 endpoint-truncation mechanism, preserves K=5 + principle | Proposed |

> Note: ADRs 0009–0013 are product-local ADRs and live in their respective product repos'
> `adr/` directories (not ratified org-wide here): adr-0009 vector-tile-server, adr-0010
> vector-infra (deferred — **superseded for the local case by adr-0016**), adr-0011
  > vector-ingestion, adr-0012 vector-map-store, adr-0013 vector-tile-gen, adr-0025 + adr-0026 vector-routing,
  > adr-0027 + adr-0028 vector-vision, adr-0030 vector-reconstruction, adr-0031 vector-reconstruction, adr-0032 vector-traffic, adr-0033 vector-traffic,
  > adr-0038 + adr-0039 vector-global, adr-0040 + adr-0041 vector-global.

