# Vector — single-command self-hosted navigation stack

Run the whole stack (web map, routing, traffic, OSRM, tiles, geocoder) on any
machine in five lines:

```
git clone <this-repo> && cd <repo>
cp .env.example .env      # edit VECTOR_REGION / VECTOR_PBF_URL / VECTOR_BBOX if desired
./bootstrap.sh            # downloads OSM, builds graph + tiles + index, generates secrets
docker compose up -d      # web at http://localhost:8088
```

That's it. Nothing else on the machine is assumed to exist — `bootstrap.sh` is
the only thing that touches raw data, and it writes every artifact into named
volumes the compose mounts. Re-running `bootstrap.sh` is safe (idempotent).

## Why this matters

Before this, the stack could not run on any machine except the one it was built
on: three disconnected composes, host-absolute bind mounts into `/tmp`, a
`run_stack.sh` that hardcoded this machine's paths/Python, and demos/secrets
committed in the clear. Now `VECTOR_REGION` is a parameter, so map coverage can
grow past Doha, and every secret is generated randomly at first boot.

## The map learns from use

The stack above is a working self-hosted map. It also improves from being driven,
and that half runs on a schedule rather than on the request path:

```
node scripts/run-evolution-cycle.mjs     # vacuum → aggregate → promote → re-bake → metrics
```

Open **http://localhost:8088/evolution** to see whether it is actually working.
The page answers one question — *is the map improving?* — from four numbers, and
says plainly when the answer is no or when a data source is unreachable.

The one rule everything follows from: **learn from aggregates over road segments,
never from individual traces.** Raw location data lives in a 72-hour quarantine
and is never what persists. What persists is per-segment evidence that has already
cleared a k-anonymity floor of 5 distinct trips — which is not personal data.
Trip endpoints are truncated 200 m on-device *and* re-truncated server-side,
pseudonyms are per-trip and never per-device, and a single person's frequent
destination is unlearnable by construction rather than by policy. The bindings are
in `vector-governance/adr/adr-0065` (ingest) and `adr-0066` (promotion).

On a fresh install every stage of the cycle reports "skipped" and the dashboard
says nothing has been measured yet. That is the correct output for a system that
has collected nothing. Set `VECTOR_LEARNED_ENABLED=0` to switch the learned layer
off entirely; routing and search revert exactly, because neither the road graph
nor the OSM index is ever mutated.

## Layout

- `docker-compose.yml` — the single network/compose (web :8088 published).
- `bootstrap.sh` — data acquisition + build, populates the named volumes.
- `.env.example` — every region parameter and secret (`.env` is gitignored).
- `scripts/run-evolution-cycle.mjs` — the scheduled learning cycle (above).
- `scripts/verify-evolution-loop.py` — cross-repo gate: asserts the seams between
  repos with real modules. Each repo's own suite is green in isolation and stays
  green while the contracts between them are broken, which is exactly what
  happened; this is the check no single repo can make.
- `run_stack.sh` / per-repo composes — **removed**; compose + bootstrap replace them.

## Restrictions

- The stack refuses to start with a default/demo token or password unless
  `VECTOR_DEV=1` is set. See `bootstrap.sh`.
- `VECTOR_REGION` must match the bbox in `.env` (`VECTOR_BBOX`).