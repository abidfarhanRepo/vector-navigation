// Governance validator: registry integrity + KG index integrity.
// Run: node scripts/validate-registry-kg.mjs
import { readFileSync } from 'fs';
import { join } from 'path';
import YAML from 'yaml';
const { parse: parseYaml } = YAML;
import { fileURLToPath } from 'url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const ROOT = join(__dirname, '..');

let errors = 0;
let warnings = 0;
const fail = (m) => { errors++; console.error('ERROR: ' + m); };
const warn = (m) => { warnings++; console.warn('WARN:  ' + m); };

// ---------- Registry ----------
const regPath = join(ROOT, 'registry', 'registry.yaml');
let reg;
try { reg = parseYaml(readFileSync(regPath, 'utf8')); }
catch (e) { fail('registry parse error: ' + e.message); process.exit(1); }

const squadIds = new Set((reg.squads || []).map((s) => s.id));
const repoIds = new Set((reg.repos || []).map((r) => r.id));

if (!reg.repos || reg.repos.length === 0) fail('registry has no repos');
for (const r of reg.repos || []) {
  if (!squadIds.has(r.owner_squad)) fail(`repo ${r.id} owner_squad ${r.owner_squad} not in squads`);
}
for (const d of reg.dependencies || []) {
  if (!repoIds.has(d.from)) fail(`dependency.from ${d.from} not a registered repo`);
  if (!repoIds.has(d.to)) fail(`dependency.to ${d.to} not a registered repo`);
}
// Orphan detection: every repo should be referenced by some dependency OR be foundational.
const referenced = new Set();
for (const d of reg.dependencies || []) { referenced.add(d.from); referenced.add(d.to); }
for (const r of reg.repos || []) {
  if (!referenced.has(r.id)) warn(`repo ${r.id} has no dependency edges (possible orphan)`);
}

// ---------- KG index ----------
const kgPath = join(ROOT, 'kg', 'index.json');
let kg;
try { kg = JSON.parse(readFileSync(kgPath, 'utf8')); }
catch (e) { fail('kg/index.json parse error: ' + e.message); process.exit(1); }

const nodeIds = new Set((kg.nodes || []).map((n) => n.id));
const requiredNodeFields = ['id', 'type', 'version', 'created_at', 'provenance', 'confidence', 'status', 'payload'];
for (const n of kg.nodes || []) {
  for (const f of requiredNodeFields) if (!(f in n)) fail(`kg node ${n.id || '?'} missing ${f}`);
  if (typeof n.confidence !== 'number' || n.confidence < 0 || n.confidence > 1)
    fail(`kg node ${n.id} confidence out of [0,1]`);
}
for (const e of kg.edges || []) {
  if (!nodeIds.has(e.from)) fail(`kg edge.from ${e.from} not a node`);
  if (!nodeIds.has(e.to)) fail(`kg edge.to ${e.to} not a node`);
  if (!e.type) fail(`kg edge missing type`);
}
// Orphan nodes (no edges) -> warn
const connected = new Set();
for (const e of kg.edges || []) { connected.add(e.from); connected.add(e.to); }
for (const n of kg.nodes || []) {
  if (!connected.has(n.id)) warn(`kg node ${n.id} has no edges (possible orphan)`);
}

console.log(`\nRegistry+KG validation: ${errors} error(s), ${warnings} warning(s).`);
if (errors > 0) { console.error('FAILED'); process.exit(1); }
console.log('PASSED');
