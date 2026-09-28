# ADR-0016 — Activate Terraform for local Docker deployment (supersedes ADR-0010 deferral)

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** D9-Ops / D1-Platform
- **Supersedes:** adr-0010 (vector-infra, "Terraform deferred — no infra yet")
- **Superseded by:** none

## Context
ADR-0010 (product-local to vector-infra) deferred all Terraform work with the note that infra
requirements were not yet understood. The user subsequently directed that **Terraform can be fully
set up locally in Docker** and asked to address it now. M1 (ADR-0015) needs a real, deployable
artifact — a locally running tile-server + viewer — and Terraform is the right tool to provision it
reproducibly on a single host without any cloud account.

Constraint: the `hashicorp/terraform` binary is **not installed on the host**, but Docker Desktop is.
The Docker socket is reachable from containers, so Terraform can run *inside* its own official image
with the socket bind-mounted.

## Decision
Activate Terraform for **local, single-host Docker deployments** using the `kreuzwerker/docker`
provider (local state backend, no remote state). The M1 deployment:

- `vector-infra/terraform/main.tf`: `docker_network` + `docker_image` (built from
  `./docker`, an nginx image serving the generated viewer + MVT tiles) + `docker_container`
  publishing `:80 → var.http_port` (default 8080) with a working healthcheck.
- `vector-infra/terraform/variables.tf` / `outputs.tf`: `project`, `image_tag`, `http_port`, and
  `viewer_url` / `tile_url_template` / `healthz_url` / `container_name` outputs.
- `vector-infra/terraform/docker/`: nginx `Dockerfile` + `default.conf` (serves `/tiles/*` with
  `application/x-protobuf` + CORS, `/healthz` probe) and a `site/` build context produced by
  `vector-infra/scripts/deploy-local.mjs`.
- `vector-infra/scripts/deploy-local.mjs`: builds the site (runs `vector-tile-gen`'s
  `build_m1_tiles.py` into `site/tiles`, copies the viewer), then runs `terraform init/apply`
  inside `hashicorp/terraform:1.9` with `/var/run/docker.sock` mounted. Supports `--plan` /
  `--destroy`.

This **supersedes ADR-0010's deferral** for the *local* case. ADR-0010's caution about *cloud*
infrastructure (accounts, remote state, multi-region) remains deferred and untouched.

## Consequences
+ M1 is deployed and destroyed reproducibly with one command; no manual `docker run` sequencing.
+ Terraform runs without a host install (containerized), satisfying the missing-binary constraint.
+ The same pattern extends to more services as M1 grows (network is shared; add containers).
+ Healthcheck uses `127.0.0.1` (nginx listens IPv4-only; busybox `wget localhost` would resolve
  IPv6 and fail) — a known gotcha captured here so it is not re-litigated.
- Local state (`terraform.tfstate`) is machine-specific; not committed (`.gitignore`). Fine for a
  single-host deployment; remote state is a future (cloud) concern.
- `kreuzwerker/docker` build runs through the mounted daemon; the first image build pulls
  `nginx:1.27-alpine`.

## Alternatives considered
- **Install terraform binary on the host:** rejected short-term — not present, and containerizing
  Terraform keeps the host toolchain minimal and matches the "everything via Docker" posture
  (ADR-0014).
- **Plain `docker compose`:** considered — simpler, but the user explicitly asked for Terraform and
  the provider model scales better to multi-service infra later.
- **Keep Terraform docs-only (ADR-0010 as-is):** rejected per explicit user direction to activate it.

## References
- ADR-0010 (deferred; superseded for local), ADR-0014 (local CI/Docker posture), ADR-0015 (M1 slice)
- `vector-infra/terraform/{main,variables,outputs}.tf`, `vector-infra/terraform/docker/`,
  `vector-infra/scripts/deploy-local.mjs`
- Bible §11 A2 (ADR format)
