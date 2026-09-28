# Vector Local CI

GitHub Actions is **not available** in this environment, so CI is run **self-hosted on this
machine** using [`act`](https://github.com/nektos/act) (which executes the GitHub Actions
workflows inside Docker). The declared `.github/workflows/ci.yml` in every repo stays the
**single source of truth**; this runner just executes those workflows locally.

## Prerequisites

1. **Docker Desktop** installed, running, and `docker` on `PATH`. Verify with `docker info`.
2. **`act`** installed, e.g. `winget install nektos.act`
   (fallback: download the Windows zip from the `nektos/act` GitHub releases).
3. **Node + npm** (already present) to install the orchestrator's one dependency.

## Setup (one-time)

```powershell
cd C:\Users\PC\Desktop\Vector
npm install            # installs the `yaml` dep used to read the registry
```

## Running CI

From the workspace root:

```powershell
npm run ci                         # run CI for every repo in the registry
node run-ci.mjs --repo vector-ingestion   # run a single repo
node run-ci.mjs --dry-run          # list what would run, without executing
node run-ci.mjs --parallel         # run repos concurrently
node run-ci.mjs --no-pull          # reuse cached act image (faster, after first run)
```

The orchestrator:

- Reads `vector-governance/registry/registry.yaml` for the repo list (data-driven — adding a
  repo to the registry auto-includes it in CI; no orchestrator change needed).
- For each repo, runs `act --defaultbranch main -P ubuntu-latest=catthehacker/ubuntu:act-latest push -W .github/workflows/ci.yml` in that repo's directory. The `-P` flag pins the runner
  image non-interactively (so `act` never prompts), and `--defaultbranch main` satisfies the
  workflow's `branches: [main]` filter. `act` reproduces the declared pipeline:
  `actions/checkout` → `actions/setup-node@20` → `npm install` → `npm run validate` → `npm test`.
- Prints a per-repo `PASS`/`FAIL` summary and exits non-zero if any repo failed.

> The image is cached by Docker after the first run. For direct `act` use outside the
> orchestrator, create a global `~/.actrc` containing
> `-P ubuntu-latest=catthehacker/ubuntu:act-latest` to avoid the interactive image prompt.

## What CI actually validates (current scope)

Toolchains are provisioned (ADR-0006): a real Python 3.11 via `uv` and Rust via `rustup`,
both on PATH. Each repo's declared `ci.yml` is executed by `act` inside Docker:

- **All repos:** `actions/checkout` → `actions/setup-node@20` → `npm install` →
  `npm run validate` (required files, YAML/JSON parseability, markdown hygiene) →
  `npm test` (Node/TS unit suites where present).
- **`vector-ingestion`, `vector-map-store`, `vector-tile-gen` (Python):** additionally
  `actions/setup-python@3.11` then `PYTHONPATH=src python -m unittest discover -s tests -v`
  exercising each service's `health()` payload and `__main__.main()`.
- **`vector-tile-server` (Rust):** additionally `dtolnay/rust-toolchain@stable` + a
  `build-essential` install (linker) then `cargo build` and `cargo test` (the `/healthz`
  handler unit test).
- **`vector-common`:** `node --test` runs 26 domain unit tests (coords/geometry/ids/errors).
- **`vector-contracts`:** `node --test` validates DTO examples against their JSON Schemas
  (ajv), error-code invariants, OpenAPI/Proto/validation-rule artifacts.
- **`vector-playground`:** `node --test` validates the standalone haversine implementation.
- **`vector-governance`:** registry + Knowledge Graph validation.

The orchestrator is registry-driven, so adding a repo's native step to its `ci.yml` is picked
up automatically with **no orchestrator change**.

## Scheduled CI (Windows Task Scheduler)

Optional persistent cadence: run the orchestrator on a daily schedule and write a report. The
task **is registered** as `VectorCI-Daily` (daily 03:00) — created by
`scripts/register-task-scheduler.ps1`. It runs only if Docker + `act` are available at 03:00;
otherwise it fails silently until the next day. Delete it if you no longer want the cadence.

- **Runner:** `scripts/run-scheduled-ci.mjs` runs `node run-ci.mjs` (uses `node` directly, not
  `npm`, because PowerShell blocks npm scripts), then writes `reports/ci-<timestamp>.log` and a
  `reports/latest.md` summary (status + totals). Reports are git-ignored.
- **Register (daily 03:00):**
  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts/register-task-scheduler.ps1
  ```
  This creates a Windows Task Scheduler task `VectorCI-Daily` invoking
  `node scripts/run-scheduled-ci.mjs` from the workspace root.
- **Manage:**
  ```powershell
  schtasks /Query /TN VectorCI-Daily
  schtasks /Delete /TN VectorCI-Daily /F
  ```

No GitHub Actions `schedule:` is used — Actions is unavailable here (adr-0014); the OS-level
Task Scheduler is the local equivalent for a recurring cadence.

## Deploy verification (M1)

The M1 "Map Display" stack can be verified **non-destructively** as part of the scheduled run.
`vector-infra/scripts/verify-deploy-local.mjs`:

- Builds the site (MVT tiles via `build_m1_tiles.py` + the MapLibre viewer) into
  `terraform/docker/site/`, then runs `terraform init` + **`terraform plan`** (never `apply`,
  never `destroy`) inside `hashicorp/terraform:1.9` with the Docker socket mounted.
- Exits non-zero if the build or plan fails. It never mutates the local Docker daemon.

Run it directly (needs Docker + the terraform image):

```powershell
node vector-infra/scripts/verify-deploy-local.mjs
```

The scheduled runner (`scripts/run-scheduled-ci.mjs`) runs it **only when
`VECTOR_DEPLOY_VERIFY=1`** and only **after** the main orchestrator passes. It is **default-OFF**
so the daily cadence stays GREEN even when Docker/Terraform aren't available at 03:00; when
enabled, a plan failure is folded into the scheduled run's overall exit code and recorded in the
report (`deploy_verify=PASS|FAIL|skipped`).

```powershell
$env:VECTOR_DEPLOY_VERIFY=1; node scripts/run-scheduled-ci.mjs
```

> Teardown of the deployed stack (`deploy-local.mjs --destroy`) is robust: the primary
> `terraform destroy` path falls back to a Docker CLI teardown if it fails or hangs. See
> `vector-infra/terraform/README.md`.

## Notes

- The root `package.json` / `run-ci.mjs` / `node_modules` are **CI tooling only** and
  are not product repos; they are intentionally not listed in the governance registry.
- `act` needs network on first run to pull the runner image and the `checkout`/`setup-node`
  actions, and to `npm install` the `yaml` dev dependency inside each repo.
