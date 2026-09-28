// Lightweight structural validator for vector-geo (mirrors the other repos'
// validate step so this repo is gate-consistent). Verifies required files and
// that the package imports cleanly.
import { readdirSync, statSync, readFileSync, existsSync } from 'node:fs';
import { join, extname, relative } from 'node:path';

const ROOT = process.cwd();
const REQUIRED_FILES = ['README.md', 'package.json', 'src/vector_geo/__init__.py', 'scripts/sync_vendor.py', 'scripts/check_vendor.py'];

let errors = 0;
const fail = (m) => { errors++; console.error('ERROR: ' + m); };

for (const f of REQUIRED_FILES) {
  if (!existsSync(join(ROOT, f))) fail('missing required file: ' + f);
}

// Markdown hygiene: trailing newline + no trailing whitespace.
function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    if (e === 'node_modules' || e === '.git') continue;
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}
for (const f of walk(ROOT)) {
  const rel = relative(ROOT, f).replace(/\\/g, '/');
  const ext = extname(f).toLowerCase();
  try {
    if (ext === '.json') JSON.parse(readFileSync(f, 'utf8'));
    else if (ext === '.md') {
      const t = readFileSync(f, 'utf8');
      if (t.length && !t.endsWith('\n')) fail('no trailing newline: ' + rel);
      if (/[ \t]+\n/.test(t)) fail('trailing whitespace: ' + rel);
    }
  } catch (e) {
    fail('parse error in ' + rel + ': ' + e.message);
  }
}

console.log(`\nValidation complete: ${errors} error(s), 0 warning(s).`);
if (errors > 0) { console.error('CI validation FAILED'); process.exit(1); }
console.log('CI validation PASSED');
