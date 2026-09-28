// CI validator for vector-auth (shared library package).
// Validates: required package structure, Python/JSON/YAML parseability, markdown hygiene.
// This is a library consumed via vendoring, so it does not require the full
// engine doc/ADR scaffold that vector-* engines carry.
import { readdirSync, statSync, readFileSync, existsSync } from 'fs';
import { join, extname, relative } from 'path';

const ROOT = process.cwd();

const REQUIRED_FILES = [
  'README.md',
  'package.json',
  'src/vector_auth/__init__.py',
  'src/vector_auth/auth.py',
  'scripts/sync_vendor.py',
  'scripts/check_vendor.py',
  'tests/test_auth.py',
  '.github/workflows/ci.yml',
];

let errors = 0;
let warnings = 0;
const fail = (m) => { errors++; console.error('ERROR: ' + m); };
const warn = (m) => { warnings++; console.warn('WARN:  ' + m); };

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    if (e === 'node_modules' || e === '.git' || e === 'vendor') continue;
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

let yamlParse = null;
try {
  const mod = await import('yaml');
  yamlParse = (mod.default || mod).parse;
} catch { yamlParse = null; }

for (const f of REQUIRED_FILES) {
  if (!existsSync(join(ROOT, f))) fail('missing required file: ' + f);
}

const files = walk(ROOT);
for (const f of files) {
  const rel = relative(ROOT, f).replace(/\\/g, '/');
  const ext = extname(f).toLowerCase();
  try {
    if (ext === '.yaml' || ext === '.yml') {
      if (yamlParse) yamlParse(readFileSync(f, 'utf8'));
      else warn('yaml not installed; skipped parse of ' + rel);
    } else if (ext === '.json') {
      JSON.parse(readFileSync(f, 'utf8'));
    } else if (ext === '.md') {
      const text = readFileSync(f, 'utf8');
      if (text.length && !text.endsWith('\n')) warn('no trailing newline: ' + rel);
      if (/[ \t]+\n/.test(text)) warn('trailing whitespace: ' + rel);
    }
  } catch (e) {
    fail('parse error in ' + rel + ': ' + e.message);
  }
}

console.log(`\nValidation complete: ${errors} error(s), ${warnings} warning(s).`);
if (errors > 0) { console.error('CI validation FAILED'); process.exit(1); }
console.log('CI validation PASSED');
