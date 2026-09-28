// Vector — local CI orchestrator (self-hosted, no GitHub Actions).
// Drives `act` (Docker) across every repo listed in the governance registry,
// executing each repo's declared .github/workflows/ci.yml locally.
//
// Usage (from the workspace root):
//   npm run ci                      # full gate: all repos, serial, no re-pull (cached image) ~6min
//   node run-ci.mjs --changed       # ONLY repos whose HEAD moved since last baseline (~25s for a wave) — FAST iterative path
//   node run-ci.mjs --repo vector-ingestion        # a single repo
//   node run-ci.mjs --repo a --repo b              # a few repos
//   node run-ci.mjs --parallel       # experimental: concurrent (needs isolated cache; slower due to re-downloads, opt-in only)
//   node run-ci.mjs --dry-run                       # list what would run
//
// Speed-ups (safe, verified):
//   * --changed — run only repos whose committed HEAD moved since .ci-baseline.json.
//     For a wave touching 2-3 repos this is ~25s vs ~6min (proven). Use it during
//     wave iteration; run the full serial gate (or --full) before committing.
//   * --no-pull (default) — reuse the cached catthehacker/ubuntu:act-latest image
//     instead of re-pulling it 29x per run.
//   * --parallel is NOT default: `act` shares an action cache (~/.cache/act) that is
//     not concurrency-safe — concurrent runs corrupt it (Cannot find module /
//     invalid checksum). Isolated per-job caches avoid corruption but force full
//     action re-downloads, making parallel SLOWER than serial here. Serial + no-pull
//     is the correct, fast-enough default.
//
// Prerequisites: Docker running and `act` installed. See CI.md.

import { readFileSync, existsSync, writeFileSync, unlinkSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join, resolve } from 'path';
import { spawn, execSync } from 'child_process';
import YAML from 'yaml';

// On Windows, Node's spawn() does not append `.exe` the way a shell does.
function resolveAct() {
  if (process.platform !== 'win32') return 'act';
  const exts = (process.env.PATHEXT || '.EXE;.CMD;.BAT;.COM').split(';');
  const dirs = (process.env.PATH || '').split(';');
  for (const dir of dirs) {
    for (const ext of exts) {
      const p = join(dir, 'act' + ext);
      if (existsSync(p)) return p;
    }
  }
  return 'act';
}
const ACT_BIN = resolveAct();

const ROOT = dirname(fileURLToPath(import.meta.url));
const REGISTRY_PATH = join(ROOT, 'vector-governance', 'registry', 'registry.yaml');
const ACT_EVENT = 'push';
const CI_WORKFLOW = '.github/workflows/ci.yml';
const BASELINE_PATH = join(ROOT, '.ci-baseline.json');

function parseArgs(argv) {
  const opts = {
    repos: [],
    dryRun: false,
    parallel: false, // default serial — `act` action cache is not concurrency-safe (shared cache collides; isolated cache re-downloads + is slower). Safe + correct.
    pull: false, // default OFF — reuse cached runner image (no 29x re-pull)
    concurrency: 6, // used only by --parallel: bounds simultaneous act jobs
    changed: false,
    full: false,
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--baseline-init') {
      const b = {};
      for (const r of loadRepoIds()) {
        const h = gitHead(r.id);
        if (h) b[r.id] = h;
      }
      saveBaseline(b);
      console.log('Baseline initialized with ' + Object.keys(b).length + ' repo HEADs.');
      process.exit(0);
    }
    else if (a === '--dry-run') opts.dryRun = true;
    else if (a === '--parallel') opts.parallel = true;
    else if (a === '--serial') opts.parallel = false;
    else if (a === '--pull') opts.pull = true;
    else if (a === '--no-pull') opts.pull = false;
    else if (a === '--changed') opts.changed = true;
    else if (a === '--concurrency') opts.concurrency = parseInt(argv[++i], 10) || 6;
    else if (a === '--full') opts.full = true;
    else if (a === '--repo') opts.repos.push(argv[++i]);
    else if (a.startsWith('--repo=')) opts.repos.push(a.slice('--repo='.length));
    else if (a === '--help' || a === '-h') {
      console.log('Usage: node run-ci.mjs [--changed] [--repo <id> ...] [--full] [--serial] [--pull] [--dry-run]');
      process.exit(0);
    } else {
      console.error('Unknown option: ' + a);
      process.exit(2);
    }
  }
  return opts;
}

function loadRepoIds() {
  if (!existsSync(REGISTRY_PATH)) {
    console.error('ERROR: registry not found at ' + REGISTRY_PATH);
    process.exit(1);
  }
  const reg = YAML.parse(readFileSync(REGISTRY_PATH, 'utf8'));
  if (!reg || !Array.isArray(reg.repos)) {
    console.error('ERROR: registry has no `repos` list');
    process.exit(1);
  }
  return reg.repos.map((r) => ({
    id: r.id,
    lang: r.primary_lang || 'unknown',
    purpose: r.purpose || '',
  }));
}

function gitHead(repoId) {
  try {
    return execSync('git rev-parse HEAD', { cwd: join(ROOT, repoId), stdio: ['ignore', 'pipe', 'ignore'] })
      .toString()
      .trim();
  } catch {
    return null;
  }
}

function gitDirty(repoId) {
  try {
    const out = execSync('git status --porcelain', {
      cwd: join(ROOT, repoId),
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .toString()
      .trim();
    return out.length > 0;
  } catch {
    return false;
  }
}

function loadBaseline() {
  try {
    return JSON.parse(readFileSync(BASELINE_PATH, 'utf8'));
  } catch {
    return {};
  }
}

function saveBaseline(baseline) {
  try {
    writeFileSync(BASELINE_PATH, JSON.stringify(baseline, null, 2) + '\n');
  } catch {
    /* best-effort */
  }
}

// `act` names its container from the job name + workflow content. When many
// repos run in parallel they all share the identical `validate` job name, so
// their container names collide ("already in use"). To make parallel safe we
// stage a TEMP workflow per repo whose job name is unique (validate-<repoid>),
// run act against it, then delete it. Serial mode uses the original ci.yml
// (no file mutation needed).
function stageTempWorkflow(repoId) {
  const src = join(ROOT, repoId, CI_WORKFLOW);
  if (!existsSync(src)) return null;
  try {
    let txt = readFileSync(src, 'utf8');
    const unique = 'validate-' + repoId.replace(/[^a-z0-9]/gi, '');
    // All Vector repos use `  validate:` as the single job key.
    if (!txt.includes('\n  validate:')) return null;
    txt = txt.replace('\n  validate:', `\n  ${unique}:`);
    const tmp = join(ROOT, repoId, '.github', 'workflows', `ci-${unique}.yml`);
    writeFileSync(tmp, txt);
    return tmp;
  } catch {
    return null;
  }
}

function runAct(repo, workflowPath, cacheDir) {
  const cwd = join(ROOT, repo.id);
  const wf = workflowPath || CI_WORKFLOW;
  const args = [
    '--defaultbranch', 'main',
    '-P', 'ubuntu-latest=catthehacker/ubuntu:act-latest',
    ACT_EVENT, '-W', wf,
  ];
  if (!opts.pull) args.push('--pull=false');
  // Isolate `act`'s action cache per process. `act` caches downloaded GitHub
  // Actions (actions/setup-node, setup-python, ...) under XDG_CACHE_HOME/act and
  // is NOT concurrency-safe — concurrent clones corrupt each other. A private
  // cache dir per job eliminates the race.
  const env = { ...process.env };
  if (cacheDir) env.XDG_CACHE_HOME = cacheDir;
  return new Promise((resolve) => {
    const child = spawn(ACT_BIN, args, { cwd, windowsHide: true, env });
    let out = '';
    const collect = (d) => { out += d.toString(); };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    child.on('error', (err) => {
      out += `\n[orchestrator] failed to launch act: ${err.message}\n`;
      resolve({ repo, code: 127, out });
    });
    child.on('close', (code) => resolve({ repo, code: code ?? 1, out }));
  });
}

// Bounded-concurrency runner: resolves all repo jobs with at most `limit`
// act processes live at once (bounds Docker/CPU/network + act action-cache load).
async function runActBounded(staged, limit) {
  const results = [];
  let i = 0;
  async function worker() {
    while (i < staged.length) {
      const s = staged[i++];
      const res = await runAct(s.repo, s.wf || undefined, s.cacheDir);
      results.push(res);
    }
  }
  const n = Math.max(1, Math.min(limit, staged.length));
  await Promise.all(Array.from({ length: n }, worker));
  return results;
}

function prefixLines(text, tag) {
  return text.split('\n').map((l) => (l.length ? `${tag} ${l}` : l)).join('\n');
}

const opts = parseArgs(process.argv);

console.log('Vector local CI — registry-driven act runner');
console.log('Root: ' + ROOT);
console.log(`Mode: ${opts.parallel ? 'parallel' : 'serial'} | pull=${opts.pull ? 'yes' : 'no (cached image)'}`);
console.log('');

const allRepos = loadRepoIds();

// Resolve the target set.
let repos;
if (opts.repos.length > 0) {
  repos = allRepos.filter((r) => opts.repos.includes(r.id));
} else if (opts.changed && !opts.full) {
  const baseline = loadBaseline();
  // A repo is "changed" when its committed HEAD has moved since the last
  // baseline (a wave = commits => HEAD moves). Pre-existing uncommitted work in
  // other repos is intentionally ignored; verify those with --repo or the full
  // parallel gate. If nothing changed, fall back to a full run so the gate is
  // never silently empty.
  repos = allRepos.filter((r) => {
    const head = gitHead(r.id);
    return head !== null && head !== baseline[r.id];
  });
} else {
  repos = allRepos;
}

const unknown = opts.repos.filter((id) => !allRepos.some((r) => r.id === id));
if (unknown.length > 0) {
  console.error(`ERROR: repo(s) not found in registry: ${unknown.join(', ')}`);
  process.exit(1);
}

console.log('Repos to process (' + repos.length + (opts.changed ? ', --changed' : '') + '):');
for (const r of repos) {
  const onDisk = existsSync(join(ROOT, r.id, CI_WORKFLOW));
  const dirty = gitDirty(r.id);
  console.log(`  - ${r.id} [${r.lang}]${onDisk ? '' : '  (no ci.yml — will skip)'}${dirty ? '  (dirty)' : ''}`);
}
console.log('');

if (opts.dryRun) {
  console.log('Dry run — no jobs executed. Run without --dry-run to execute.');
  process.exit(0);
}

const results = [];

// A repo with no workflow is announced above as "will skip" — so skip it. Running
// `act` against a missing file made it exit 1, and the summary counted that as a
// code failure indistinguishable from a real one. vector-osrm (a shell repo with
// no ci.yml) was permanently red for this reason alone. A repo that has no tests
// to run is not a repo that failed its tests; issue 04 exists to keep those two
// signals apart.
const SKIP_CODE = -1;
const runnable = [];
for (const r of repos) {
  if (existsSync(join(ROOT, r.id, CI_WORKFLOW))) {
    runnable.push(r);
  } else {
    results.push({ repo: r, code: SKIP_CODE, out: `[orchestrator] skipped: no ${CI_WORKFLOW}\n` });
  }
}

if (opts.parallel) {
  // Stage a unique-named temp workflow + a private action-cache dir per repo to
  // avoid `act` container-name collisions and action-cache races, run them with
  // bounded concurrency, then clean up.
  const staged = runnable.map((r) => ({
    repo: r,
    wf: stageTempWorkflow(r.id),
    cacheDir: join(ROOT, '.ci-cache', r.id),
  }));
  const settled = await runActBounded(staged, opts.concurrency);
  for (const s of staged) {
    if (s.wf) { try { unlinkSync(s.wf); } catch { /* ignore */ } }
  }
  for (const res of settled) results.push(res);
} else {
  for (const r of runnable) {
    const res = await runAct(r);
    results.push(res);
  }
}

console.log('\n========================================');
console.log('CI SUMMARY');
console.log('========================================');
let failed = 0;
let skipped = 0;
for (const { repo, code, out } of results) {
  const tag = `[${repo.id}]`;
  // SKIP is its own outcome. Folding it into FAIL is what made vector-osrm look
  // permanently broken when it simply has no workflow to run (issue 04).
  const status = code === SKIP_CODE ? 'SKIP' : code === 0 ? 'PASS' : 'FAIL';
  if (code === SKIP_CODE) skipped++;
  else if (code !== 0) failed++;
  console.log(`\n--- ${repo.id}: ${status} (exit ${code === SKIP_CODE ? 'n/a' : code}) ---`);
  const text = prefixLines(out, tag);
  process.stdout.write(text.endsWith('\n') ? text : text + '\n');
}

console.log('\n----------------------------------------');
console.log(`Total: ${results.length}  Passed: ${results.length - failed - skipped}  Failed: ${failed}  Skipped: ${skipped}`);
console.log('----------------------------------------');

// Record baselines for repos that passed (so --changed stays incremental).
if (failed === 0) {
  const baseline = loadBaseline();
  for (const { repo, code } of results) {
    if (code === 0) {
      const head = gitHead(repo.id);
      if (head) baseline[repo.id] = head;
    }
  }
  saveBaseline(baseline);
}

process.exit(failed === 0 ? 0 : 1);
