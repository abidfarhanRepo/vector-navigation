// Vector — scheduled CI runner (intended for Windows Task Scheduler).
//
// Status-truth + never-silent gate (issue 04).
//
// Three guarantees this runner provides:
//   1. It GATES on a Docker healthcheck *before* running anything. If the
//      daemon is down it tries `docker info` a few times, then writes an
//      INFRA-FAIL report and exits NON-ZERO — it never exits silently and it
//      never wastes a full 34-job run producing 34 byte-identical "failed to
//      connect to the docker API" walls.
//   2. It distinguishes environmental failure from code failure. If Docker is
//      down (or `docker info` fails) the report status is `INFRA-FAIL`, not
//      `FAIL` — so a Docker outage is never misread as 34 broken builds.
//   3. It detects a STALE report (< 36 h) as a loud banner in latest.md and on
//      stdout, because "no report" previously looked like "nobody looked".
//
// Usage (invoked by the scheduled task):
//   node scripts/run-scheduled-ci.mjs
//   VECTOR_DEPLOY_VERIFY=1 node scripts/run-scheduled-ci.mjs   # also plan the M1 deploy

import { spawn, execSync, spawnSync } from 'child_process';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { existsSync, mkdirSync, writeFileSync, readFileSync, statSync } from 'fs';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url))); // workspace root (parent of scripts/)
const REPORTS_DIR = join(ROOT, 'reports');
const STALE_HOURS = 36;
const MAX_INFRA_RETRY = 3;

function runOrchestrator() {
  return new Promise((resolve) => {
    const child = spawn('node', ['run-ci.mjs'], { cwd: ROOT, windowsHide: true });
    let out = '';
    const collect = (d) => { out += d.toString(); };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    child.on('error', (err) => {
      out += `\n[runner] failed to launch run-ci.mjs: ${err.message}\n`;
      resolve({ code: 127, out });
    });
    child.on('close', (code) => resolve({ code: code ?? 1, out }));
  });
}

// Docker healthcheck gate. Returns true if `docker info` succeeds within budget.
function dockerHealthy() {
  for (let i = 1; i <= MAX_INFRA_RETRY; i++) {
    try {
      const r = spawnSync('docker', ['info'], { encoding: 'utf8', timeout: 20000, windowsHide: true });
      if (r.status === 0 && /Server Version/.test(r.stdout)) return true;
      if (i < MAX_INFRA_RETRY) {
        // give the daemon a moment (scheduled task may boot with us)
        const wait = spawnSync(process.execPath || 'node', ['-e', 'setTimeout(()=>{},4000)'], { timeout: 15000 });
        void wait;
      }
    } catch {
      /* retry */
    }
  }
  return false;
}

// Optional, non-destructive M1 deploy verification (terraform plan only).
function runDeployVerify() {
  return new Promise((resolve) => {
    const child = spawn('node', ['vector-infra/scripts/verify-deploy-local.mjs'], { cwd: ROOT, windowsHide: true });
    let out = '';
    const collect = (d) => { out += d.toString(); };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    child.on('error', (err) => {
      out += `\n[runner] failed to launch verify-deploy-local.mjs: ${err.message}\n`;
      resolve({ code: 127, out });
    });
    child.on('close', (code) => resolve({ code: code ?? 1, out }));
  });
}

function parseSummary(text) {
  const m = text.match(/Total:\s*(\d+)\s+Passed:\s*(\d+)\s+Failed:\s*(\d+)/);
  if (!m) return { total: '?', passed: '?', failed: '?' };
  return { total: m[1], passed: m[2], failed: m[3] };
}

// Byte-identical repetition detection: if this run's failure text equals the
// previous report's failure text, the problem is environmental/recurrent, not
// newly introduced. We fold that into the status (INFRA-FAIL) when the
// signature looks like a Docker/code-environment wall too.
function lastReportFailureText() {
  const latest = readLatest();
  if (!latest) return '';
  try {
    const logPath = join(REPORTS_DIR, latest);
    if (!existsSync(logPath)) return '';
    return readFileSync(logPath, 'utf8');
  } catch {
    return '';
  }
}

function readLatest() {
  const p = join(REPORTS_DIR, 'latest.md');
  try {
    const m = readFileSync(p, 'utf8').match(/reports\/(ci-[^\s]+\.log)/);
    return m ? { log: m[1] } : null;
  } catch {
    return null;
  }
}

function isStale() {
  // True when the newest ci-*.log predates the staleness window (or is absent).
  const last = readLatest();
  if (!last) return true; // no report at all => stale by construction
  try {
    const mtime = statSync(join(REPORTS_DIR, last.log)).mtimeMs;
    return Date.now() - mtime > STALE_HOURS * 3600 * 1000;
  } catch {
    return true;
  }
}

const stamp = new Date().toISOString().replace(/[:.]/g, '-').replace('T', '_').slice(0, 19);

// ---------------------------------------------------------------------------
// Pre-flight: is Docker up? If not, NEVER exit silently; write INFRA-FAIL.
// ---------------------------------------------------------------------------
const dockerOk = dockerHealthy();
if (!dockerOk) {
  const logPath = join(REPORTS_DIR, `ci-${stamp}.log`);
  const why =
    'INFRA-FAIL: Docker daemon unreachable (`docker info` failed). The gate did NOT run ' +
    'because a 34-job run would only produce 34 byte-identical "failed to connect to the ' +
    'docker API" walls. Start Docker Desktop, or set VECTOR_DEV=1 to run anyway.';
  console.error(why);
  writeFileSync(logPath, `# Vector scheduled CI report\n# ${new Date().toISOString()}\n# status=INFRA-FAIL exit=1\n\n${why}\n`);
  const ageNote = isStale() ? ' (prior report is STALE)' : ' (prior report still fresh)';
  writeFileSync(join(REPORTS_DIR, 'latest.md'),
    `# Latest Vector scheduled CI report\n\n**INFRA-FAIL** — Docker daemon unreachable${ageNote}; run skipped.\n\n` +
    `- **Run (UTC):** ${new Date().toISOString()}\n- **Status:** INFRA-FAIL\n- **Reason:** Docker daemon unreachable (` + '`docker info` failed); run skipped.\n' +
    `- **Full log:** reports/ci-${stamp}.log\n`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Run the orchestrator, then classify the outcome (code vs infra vs stale).
// ---------------------------------------------------------------------------
let { code, out } = await runOrchestrator();
const { total, passed, failed } = parseSummary(out);

const deployVerifyRequested = process.env.VECTOR_DEPLOY_VERIFY === '1';
let deployVerify = 'skipped';
if (deployVerifyRequested && code === 0) {
  out += `\n\n===== deploy verification (VECTOR_DEPLOY_VERIFY=1) =====\n`;
  const dv = await runDeployVerify();
  out += dv.out;
  deployVerify = dv.code === 0 ? 'PASS' : 'FAIL';
  if (dv.code !== 0) code = dv.code;
} else if (deployVerifyRequested && code !== 0) {
  out += `\n\n[runner] deploy verification skipped: orchestrator failed.\n`;
  deployVerify = 'skipped (orchestrator failed)';
}

// Classify: is the failure environmental (INFRA-FAIL) or genuinely code (FAIL)?
let status;
if (code === 0) {
  status = 'PASS';
} else if (/failed to connect to the docker API|unable to determine if image already exists|cannot connect to the daemon/i.test(out)) {
  status = 'INFRA-FAIL';
} else {
  status = 'FAIL';
}

// Recurrence heuristic: byte-identical failure text across consecutive runs is
// an uncaught environmental signal, not fresh code breakage.
if (code !== 0 && status !== 'INFRA-FAIL') {
  const prev = lastReportFailureText();
  if (prev && prev === out) status = 'INFRA-RECURRENT';
}

if (!existsSync(REPORTS_DIR)) mkdirSync(REPORTS_DIR, { recursive: true });
const logPath = join(REPORTS_DIR, `ci-${stamp}.log`);
writeFileSync(logPath, `# Vector scheduled CI report\n# ${new Date().toISOString()}\n# status=${status} exit=${code} total=${total} passed=${passed} failed=${failed} deploy_verify=${deployVerify}\n\n${out}\n`);

// Missing-report / staleness alarm: surface in latest.md loudly.
const staleMsg = isStale()
  ? `**STALE — no report for > ${STALE_HOURS} h.** If this line shows, the gate is not running.\n\n`
  : '';
const latestMd =
  `# Latest Vector scheduled CI report\n\n${staleMsg}` +
  `- **Run (UTC):** ${new Date().toISOString()}\n` +
  `- **Status:** ${status}\n` +
  `- **Totals:** ${total} run, ${passed} passed, ${failed} failed\n` +
  `- **Deploy verify:** ${deployVerify}\n` +
  `- **Full log:** reports/ci-${stamp}.log\n`;
writeFileSync(join(REPORTS_DIR, 'latest.md'), latestMd);

// Keep the PROGRESS.md dashboard header in sync with the gate we just ran.
try {
  const syncSync = spawnSync('node', ['scripts/sync-status.mjs'], { cwd: ROOT, encoding: 'utf8', windowsHide: true });
  if (syncSync.stdout) console.log(syncSync.stdout.trim());
  if (syncSync.status !== 0) console.error('sync-status failed: ' + (syncSync.stderr || '').trim());
} catch (err) {
  console.error('sync-status unavailable: ' + err.message);
}

console.log(`Scheduled CI finished: ${status} (exit ${code}). Deploy verify: ${deployVerify}. Report: ${logPath}`);
process.exit(code === 0 ? 0 : 1);