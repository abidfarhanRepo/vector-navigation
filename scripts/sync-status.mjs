// Vector — sync-status helper (issue 04).
//
// Derives the PROGRESS.md dashboard header from the latest scheduled CI report
// so the dashboard header can never drift from gate truth again:
//   - reads reports/latest.md
//   - re-derives the "Session N / Wave N / status" line
//   - rewrites only the header block (markers below) in PROGRESS.md
//
// The header block in PROGRESS.md is delimited by:
//   <!-- CI-STATUS:BEGIN --> ... <!-- CI-STATUS:END -->
// Anything already inside is replaced; everything else in the file is untouched.
//
// Usage:
//   node scripts/sync-status.mjs            # derive default session numbering
//   node scripts/sync-status.mjs --session 9

import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { existsSync, readFileSync, writeFileSync } from 'fs';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const LATEST = join(ROOT, 'reports', 'latest.md');
const PROGRESS = join(ROOT, 'PROGRESS.md');
const SESSION_LOG = join(ROOT, 'vector-governance', 'docs', 'SESSION_LOG.md');
const BEGIN = '<!-- CI-STATUS:BEGIN -->';
const END = '<!-- CI-STATUS:END -->';

function readLatestStatus() {
  if (!existsSync(LATEST)) return { status: 'NO-REPORT', date: 'unknown', drift: true };
  const text = readFileSync(LATEST, 'utf8');
  const status = (text.match(/- \*\*Status:\*\* ([^\n]+)/)?.[1] ?? 'UNKNOWN').trim();
  const date = (text.match(/- \*\*Run \(UTC\):\*\* ([^\n]+)/)?.[1] ?? 'unknown').trim();
  const stale = /STALE/i.test(text);
  return { status, date, drift: stale };
}

// The session number, read from SESSION_LOG.md's `## Session N` headings.
//
// This banner names SESSION_LOG.md as the live source of truth, so it should read
// from there rather than guess. Two earlier attempts show why guessing does not
// work:
//
//   1. `max(Session N anywhere in PROGRESS.md) + 1` scanned the block this script
//      had generated on its previous run, so it read its own output and
//      incremented every refresh — three runs turned a Session-52 document into
//      "Session 55".
//   2. Stripping the generated block fixed that and broke again immediately,
//      because PROGRESS.md prose legitimately *mentions* session numbers ("three
//      runs took a Session-52 document to 'Session 55'"), and any such sentence
//      poisons a max-scan.
//
// Free text is not a data source. A structured heading in one designated file is.
function currentSessionNumber() {
  if (!existsSync(SESSION_LOG)) return null;
  let out = 0;
  const text = readFileSync(SESSION_LOG, 'utf8');
  // Headings only — not prose that happens to name a session.
  for (const match of text.matchAll(/^#{1,3}\s+Session\s+(\d+)\b/gm)) {
    out = Math.max(out, Number(match[1]));
  }
  return out > 0 ? out : null;
}

function main() {
  const { status, date, drift } = readLatestStatus();
  const progress = readFileSync(PROGRESS, 'utf8');
  const session = currentSessionNumber();
  // No SESSION_LOG heading to read means we do not know, and saying so beats
  // inventing a number for a document whose whole job is to be trustworthy.
  const sessionLabel = session === null ? 'Session unknown' : `Session ${session}`;

  const banner =
    `> **CI status (auto-derived ${date})**: ${status}` +
    (drift ? ' — ⚠ STALE / no recent report' : '') +
    `.\n> ${sessionLabel} header is generated; the live source of truth is ` +
    `vector-governance/docs/SESSION_LOG.md and reports/latest.md. Manual edits here ` +
    `will be overwritten on the next ` + '`node scripts/sync-status.mjs`' + `.`;

  const block = `\n<!-- CI-STATUS:BEGIN -->\n${banner}\n<!-- CI-STATUS:END -->\n`;

  let updated;
  const start = progress.indexOf(BEGIN);
  const end = progress.indexOf(END);
  if (start === -1 || end === -1) {
    // No markers yet — append a header guard block after the H1 line.
    const h1End = progress.search(/\n/);
    updated = progress.slice(0, h1End + 1) + block + progress.slice(h1End + 1);
  } else {
    const replaceFrom = progress.lastIndexOf('\n', start) + 1;
    const replaceEnd = progress.indexOf('\n', end) + 1;
    updated = progress.slice(0, replaceFrom) + block.trimStart() + progress.slice(replaceEnd);
  }

  writeFileSync(PROGRESS, updated, 'utf8');
  console.log(`sync-status: header updated -> status=${status} session=${session} drift=${drift}`);
}

main();