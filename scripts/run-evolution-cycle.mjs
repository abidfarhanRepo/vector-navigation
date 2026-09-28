#!/usr/bin/env node
// run-evolution-cycle.mjs — one full turn of the self-evolving loop (issues 05-10).
//
// This is the scheduled job that was missing. Every module existed; nothing ran
// them in order, so no fact was ever persisted, no promotion ever reached a
// service, and the evolution dashboard rendered zeros forever.
//
// Six stages, in dependency order:
//
//   1. vacuum      — enforce the 72 h quarantine TTL (issue 02)
//   2. batch       — map-match + k-anonymous aggregate + persist facts (05, 06)
//   3. promote     — export confident facts to the three consumers (07, 08, 09)
//   4. rebake      — selectively re-bake tiles from learned geometry (08)
//   5. metrics     — write the learning-side metrics snapshot (10)
//   6. dashboard   — collect all sources and render the page (10)
//
// Each stage is skipped, with a reason, when its inputs are absent — a fresh
// install has no traces, and that must produce a clean "nothing to do" run
// rather than a failure. Stage failures are collected and reported at the end
// instead of aborting, because stage 6 is the stage that *tells you* something
// broke, and it must run even when an earlier stage did not.
//
//   node scripts/run-evolution-cycle.mjs [--dry-run] [--verbose]
//
// Paths come from the environment (see .env.example): VECTOR_TRACE_DB,
// VECTOR_FACTS_DB, VECTOR_ROADS_GEOJSON, VECTOR_TILES_DIR, VECTOR_PROMOTED_DIR,
// VECTOR_EVOLUTION_DIR, VECTOR_ROUTING_URL, VECTOR_WEB_URL.

import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  buildSnapshotPayload,
  collectEvolution,
  renderCorridorTable,
  renderSourceBanner,
} from '../vector-observability/src/index.js';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const DRY_RUN = argv.includes('--dry-run');
const VERBOSE = argv.includes('--verbose');

const env = process.env;
const PY = env.VECTOR_PYTHON || guessPython();
const TRACE_DB = env.VECTOR_TRACE_DB || join(ROOT, 'vector-web', 'data', 'traces.db');
const FACTS_DB = env.VECTOR_FACTS_DB || join(ROOT, 'vector-learning', 'learning-data', 'facts.db');
const ROADS = env.VECTOR_ROADS_GEOJSON || join(ROOT, 'vector-osrm', 'data', 'qatar.geojson');
const TILES_DIR = env.VECTOR_TILES_DIR || join(ROOT, 'vector-tile-server', 'tiles');
const PROMOTED_DIR = env.VECTOR_PROMOTED_DIR || join(ROOT, 'vector-learning', 'learning-data', 'promoted');
const WATERMARK = env.VECTOR_WATERMARK || join(ROOT, 'vector-learning', 'learning-data', 'watermark.json');
const EVOLUTION_DIR = env.VECTOR_EVOLUTION_DIR || join(ROOT, 'vector-web', 'data');
const ROUTING_URL = env.VECTOR_ROUTING_URL || 'http://localhost:8081';
const WEB_URL = env.VECTOR_WEB_URL || 'http://localhost:8080';
const REBAKE_ZOOMS = env.VECTOR_REBAKE_ZOOMS || '12,13,14,15,16';
// Keep this many past snapshots. Coverage as a single number says nothing;
// issue 10 is explicit that the SHAPE of the curve is the signal.
const HISTORY_KEEP = Number(env.VECTOR_EVOLUTION_HISTORY || 90);

const NOW = Date.now();
const results = [];

function guessPython() {
  for (const candidate of [
    join(ROOT, '.qatar-venv', 'Scripts', 'python.exe'),
    join(ROOT, '.qatar-venv', 'bin', 'python'),
  ]) {
    if (existsSync(candidate)) return candidate;
  }
  return 'python';
}

function log(...args) {
  console.log(...args);
}

function record(stage, status, detail) {
  results.push({ stage, status, detail });
  const mark = status === 'ok' ? '✓' : status === 'skipped' ? '–' : '✗';
  log(`${mark} ${stage}: ${status}${detail ? ` — ${detail}` : ''}`);
}

/** Run a python module/script with the repo's src dirs on PYTHONPATH. */
function runPython(stage, { cwd, args, pythonPath = [] }) {
  if (DRY_RUN) {
    record(stage, 'skipped', 'dry run');
    return { ok: true, stdout: '' };
  }
  const pathEntries = pythonPath.map((p) => resolve(ROOT, p));
  const proc = spawnSync(PY, args, {
    cwd: cwd ? resolve(ROOT, cwd) : ROOT,
    encoding: 'utf8',
    timeout: 30 * 60 * 1000,
    env: { ...env, PYTHONPATH: pathEntries.join(process.platform === 'win32' ? ';' : ':') },
  });
  if (VERBOSE && proc.stdout) log(proc.stdout.trimEnd());
  if (proc.error) {
    record(stage, 'failed', String(proc.error.message || proc.error));
    return { ok: false, stdout: proc.stdout || '' };
  }
  if (proc.status !== 0) {
    const tail = (proc.stderr || proc.stdout || '').trim().split('\n').slice(-3).join(' | ');
    record(stage, 'failed', `exit ${proc.status}: ${tail}`);
    return { ok: false, stdout: proc.stdout || '' };
  }
  return { ok: true, stdout: proc.stdout || '' };
}

// ---- 1. vacuum the quarantine store (72 h TTL, issue 02) -----------------
function stageVacuum() {
  if (!existsSync(TRACE_DB)) {
    record('vacuum', 'skipped', 'no trace store yet');
    return;
  }
  const script = join(ROOT, 'vector-web', 'scripts', 'vacuum_traces.py');
  if (!existsSync(script)) {
    record('vacuum', 'skipped', 'vacuum_traces.py not present');
    return;
  }
  // vacuum_traces.py takes the db path positionally (argv[0]), not as a flag.
  const res = runPython('vacuum', {
    cwd: 'vector-web',
    args: [script, TRACE_DB],
    pythonPath: ['vector-web/src', 'vector-web/vendor'],
  });
  if (res.ok) record('vacuum', 'ok', 'TTL enforced');
}

// ---- 2. aggregate + persist facts (issues 05, 06) -----------------------
function stageBatch() {
  if (!existsSync(TRACE_DB)) {
    record('batch', 'skipped', 'no trace store yet — nothing has been collected');
    return;
  }
  if (!existsSync(ROADS)) {
    record('batch', 'failed', `road network missing: ${ROADS}`);
    return;
  }
  const res = runPython('batch', {
    cwd: 'vector-learning',
    args: [
      '-m', 'vector_learning', 'batch',
      '--store', TRACE_DB,
      '--network', ROADS,
      '--facts', FACTS_DB,
      // Explicit, so the resumable high-water mark lives next to the data it
      // describes rather than wherever the default happens to resolve.
      '--watermark', WATERMARK,
      '--k-stats-out', join(PROMOTED_DIR, 'k-stats.json'),
    ],
    pythonPath: ['vector-learning/src', 'vector-learning/vendor'],
  });
  if (res.ok) {
    const summary = (res.stdout.trim().split('\n').pop() || '').trim();
    record('batch', 'ok', summary);
  }
}

// ---- 3. promote to the three consumers (issues 07, 08, 09) --------------
function stagePromote() {
  if (!existsSync(FACTS_DB)) {
    record('promote', 'skipped', 'no facts yet');
    return;
  }
  const res = runPython('promote', {
    cwd: 'vector-learning',
    args: [
      '-m', 'vector_learning', 'promote',
      '--facts', FACTS_DB,
      '--network', ROADS,
      '--out', PROMOTED_DIR,
    ],
    pythonPath: ['vector-learning/src', 'vector-learning/vendor'],
  });
  if (res.ok) {
    record('promote', 'ok', (res.stdout.trim().split('\n').pop() || '').trim());
  }
}

// ---- 4. re-bake tiles from learned geometry (issue 08) ------------------
function stageRebake() {
  const exportPath = join(PROMOTED_DIR, 'learned_geometry.json');
  if (!existsSync(exportPath)) {
    record('rebake', 'skipped', 'no learned geometry export');
    return;
  }
  let facts = [];
  try {
    const doc = JSON.parse(readFileSync(exportPath, 'utf8'));
    facts = Array.isArray(doc) ? doc : (doc.facts || []);
  } catch {
    record('rebake', 'failed', 'learned_geometry.json unreadable');
    return;
  }
  if (facts.length === 0) {
    // Not a failure and not worth a bake: no geometry has cleared 0.90 yet.
    record('rebake', 'skipped', 'no geometry facts above the promotion threshold');
    return;
  }
  if (!existsSync(TILES_DIR)) {
    record('rebake', 'skipped', `tiles dir missing: ${TILES_DIR}`);
    return;
  }
  const res = runPython('rebake', {
    cwd: 'vector-tile-gen',
    args: [
      join(ROOT, 'vector-tile-gen', 'scripts', 'rebake_learned.py'),
      '--learned-facts', exportPath,
      '--base-geojson', ROADS,
      '--tiles-dir', TILES_DIR,
      '--zooms', REBAKE_ZOOMS,
    ],
    pythonPath: ['vector-tile-gen/src', 'vector-ingestion/src'],
  });
  if (res.ok) record('rebake', 'ok', `${facts.length} geometry facts baked`);
}

// ---- 5. learning-side metrics snapshot (issue 10) ----------------------
function stageMetrics() {
  if (!existsSync(FACTS_DB)) {
    record('metrics', 'skipped', 'no facts yet');
    return null;
  }
  const out = join(EVOLUTION_DIR, 'evolution-metrics.json');
  const res = runPython('metrics', {
    cwd: 'vector-learning',
    args: [
      '-m', 'vector_learning', 'metrics',
      '--facts', FACTS_DB,
      '--network', ROADS,
      '--out', out,
      '--gate-counters', join(ROOT, 'vector-web', 'data', 'privacy_counters.json'),
      '--k-stats', join(PROMOTED_DIR, 'k-stats.json'),
    ],
    pythonPath: ['vector-learning/src', 'vector-learning/vendor'],
  });
  if (!res.ok) return null;
  record('metrics', 'ok', (res.stdout.trim().split('\n').pop() || '').trim());
  try {
    return JSON.parse(readFileSync(out, 'utf8'));
  } catch {
    return null;
  }
}

// ---- coverage history ---------------------------------------------------
function historyDir() {
  return join(EVOLUTION_DIR, 'evolution-history');
}

function loadHistory() {
  const dir = historyDir();
  if (!existsSync(dir)) return [];
  const files = readdirSync(dir).filter((n) => n.endsWith('.json')).sort();
  const out = [];
  for (const name of files.slice(-HISTORY_KEEP)) {
    try {
      out.push(JSON.parse(readFileSync(join(dir, name), 'utf8')));
    } catch {
      // A corrupt history entry loses one point on a curve; it must not stop
      // the run that is trying to add the next one.
    }
  }
  return out;
}

function saveHistory(snapshot) {
  const dir = historyDir();
  mkdirSync(dir, { recursive: true });
  // Sortable, collision-free filename with no dependency on wall-clock format.
  writeFileSync(join(dir, `${String(NOW).padStart(16, '0')}.json`),
    JSON.stringify(snapshot), 'utf8');
}

// ---- 6. the dashboard (issue 10) ---------------------------------------
async function stageDashboard(learning) {
  const history = loadHistory();
  const { metrics, sources } = await collectEvolution({
    learning,
    etaUrl: `${ROUTING_URL.replace(/\/$/, '')}/eta`,
    privacyUrl: `${WEB_URL.replace(/\/$/, '')}/privacy-counters`,
    history,
    now: NOW,
  });

  const corridors = (learning && learning.corridors) || [];
  const payload = { ...buildSnapshotPayload({ metrics, sources, now: NOW }), corridors };
  const html =
    metrics.renderHtml({ now: NOW }) + renderCorridorTable(corridors) + renderSourceBanner(sources);

  if (DRY_RUN) {
    record('dashboard', 'skipped', 'dry run');
    return payload;
  }
  mkdirSync(EVOLUTION_DIR, { recursive: true });
  writeFileSync(join(EVOLUTION_DIR, 'evolution.html'), html, 'utf8');
  writeFileSync(join(EVOLUTION_DIR, 'evolution.json'), JSON.stringify(payload, null, 2), 'utf8');
  if (learning) saveHistory(learning);

  const verdict = payload.verdict.improving ? 'IMPROVING' : 'not yet improving';
  record('dashboard', 'ok', `${verdict} — ${join(EVOLUTION_DIR, 'evolution.html')}`);
  return payload;
}

async function main() {
  log(`evolution cycle @ ${new Date(NOW).toISOString()}${DRY_RUN ? ' (DRY RUN)' : ''}`);
  log(`  python=${PY}`);
  log(`  traces=${TRACE_DB}`);
  log(`  facts=${FACTS_DB}`);
  log('');

  // Order matters, and the obvious order is wrong. The vacuum runs LAST.
  //
  // With vacuum first, every observation older than the 72 h TTL is deleted before
  // the batch job can read it — so any track that arrives historical is aggregated
  // never. Live capture never noticed, because its data is always fresh. It makes
  // any import path a silent no-op: the upload succeeds, the response says stored,
  // and the next cycle erases it. Verified: tracks from 5 and 30 days ago were
  // stored and destroyed before the batch saw them.
  //
  // This is NOT a TTL relaxation. The TTL governs how long raw location data is
  // RETAINED, and that promise is kept exactly — the data is still deleted in this
  // same run, seconds later. What changes is that its aggregate is extracted first,
  // and that aggregate is non-personal by construction. Extracting it before
  // deletion costs nothing in privacy and is the entire reason for collecting.
  //
  // Do not "tidy" the vacuum back to the top.
  stageBatch();
  stagePromote();
  stageRebake();
  const learning = stageMetrics();
  // Unconditional, and after the stages that read the store: a failing batch must
  // never turn into indefinite raw-GPS retention. The TTL is a promise, not a
  // best effort.
  stageVacuum();
  const payload = await stageDashboard(learning);

  log('');
  const failed = results.filter((r) => r.status === 'failed');
  const skipped = results.filter((r) => r.status === 'skipped');
  log(`summary: ${results.length - failed.length - skipped.length} ok, ` +
      `${skipped.length} skipped, ${failed.length} failed`);

  if (payload && payload.coverage && payload.coverage.latest) {
    const c = payload.coverage.latest;
    log(`coverage: ${c.pct}% (${c.covered}/${c.total} segments with >=K trips)`);
  }
  if (payload && payload.eta) {
    log(`eta: ${payload.eta.verdict || 'no samples recorded yet'}`);
  }
  if (payload && payload.privacy && payload.privacy.warning) {
    log(`privacy: ${payload.privacy.warning}`);
  }

  // Non-zero on a real stage failure so a scheduler notices. Skips are normal
  // on a system that has not collected anything yet and must not alarm.
  return failed.length === 0 ? 0 : 1;
}

// Set exitCode rather than calling process.exit(): the source fetches leave
// sockets mid-close, and exiting under them trips a libuv assertion
// ("!(handle->flags & UV_HANDLE_CLOSING)") that a scheduler would read as a
// crashed run. Letting the loop drain gives the same exit code, quietly.
main().then((code) => { process.exitCode = code; }).catch((err) => {
  console.error('evolution cycle crashed:', err);
  process.exitCode = 1;
});
