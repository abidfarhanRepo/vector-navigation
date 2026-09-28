// Extract the inline <script> bodies from static/index.html and syntax-check them.
//
// Two fixes, both of which meant this script reported a failure no matter what
// the code said — so it was a guard guarding nothing:
//
//   1. HTML COMMENTS ARE STRIPPED FIRST. One of the viewer's comments contains
//      the literal text `<script>`, so the very first regex match began inside
//      the comment and `node --check` choked on prose ("keeps working. -->").
//   2. The tag pattern allows ATTRIBUTES. `<script>` alone misses
//      `<script defer>`, `<script type=...>` and every other form, so real
//      script bodies were skipped while the comment was checked.
//
// It already exits non-zero on failure; what it lacked was the ability to fail
// for a reason that had anything to do with the code.
const re = /<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g;
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.dirname(path.dirname(path.resolve(__filename)));
const raw = fs.readFileSync(path.join(root, 'static', 'index.html'), 'utf-8');
// Replace comments with equal-length whitespace so reported offsets stay honest.
const html = raw.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));

let ok = true, i = 0, m;
while ((m = re.exec(html)) !== null) {
  const tmp = path.join(require('os').tmpdir(), 'inline_' + (i++) + '.js');
  fs.writeFileSync(tmp, m[1]);
  try {
    execFileSync('node', ['--check', tmp], { stdio: 'pipe' });
    console.log('script ' + (i - 1) + ': OK (' + m[1].length + ' chars)');
  } catch (e) {
    ok = false;
    console.error('script ' + (i - 1) + ': SYNTAX ERROR\n' + e.stderr.slice(0, 3000));
  }
  fs.unlinkSync(tmp);
}
if (i === 0) { console.error('NO INLINE SCRIPT FOUND'); process.exit(1); }
process.exit(ok ? 0 : 1);
