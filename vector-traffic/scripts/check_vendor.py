#!/usr/bin/env python3
"""Validate that every vendored package under vendor/ matches its canonical source.

Self-contained (no dependency on the source repos' own scripts). For each
subdirectory of vendor/ (e.g. vector_auth, vector_geo, vector_bus_client) we
compare against ../<name>/src/<name> when that sibling source repo is present.

Under per-repo isolation (the act gate, where the sibling source repos are NOT
checked out) the vendored copy is the source of truth, so a missing sibling is
SKIPPED (exit 0) for that package.
"""

import difflib
import filecmp
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VENDOR_ROOT = ROOT / "vendor"

# Map vendored package name -> canonical source repo name (sibling at ../<repo>).
# By convention each shared package lives at ../<repo>/src/<pkg>.
KNOWN = {
    "vector_auth": "vector-auth",
    "vector_geo": "vector-geo",
    "vector_bus_client": "vector-bus-client",
}


def _walk(p: Path):
    return sorted(f.relative_to(p).as_posix() for f in p.rglob("*.py"))


def check_one(pkg: str, repo: str) -> int:
    source = ROOT.parent / repo / "src" / pkg
    vendor = VENDOR_ROOT / pkg
    if not source.exists():
        # Per-repo isolation: sibling source not checked out -> vendor is truth.
        print(f"SKIP: {pkg} source ({repo}) not found — vendor is source of truth")
        return 0
    if not vendor.exists():
        print(f"FAIL: vendor/{pkg} missing; run {repo}/scripts/sync_vendor.py", file=sys.stderr)
        return 1
    src_files = set(_walk(source))
    vnd_files = set(_walk(vendor))
    if src_files != vnd_files:
        print(
            f"FAIL: {pkg} file set mismatch (missing={sorted(src_files - vnd_files)}, extra={sorted(vnd_files - src_files)})",
            file=sys.stderr,
        )
        return 1
    for rel in src_files:
        a, b = source / rel, vendor / rel
        if not filecmp.cmp(a, b, shallow=False):
            diff = difflib.unified_diff(
                a.read_text().splitlines(), b.read_text().splitlines(),
                fromfile=str(a), tofile=str(b),
            )
            print(f"FAIL: vendor/{pkg} diverges from {repo} source:", file=sys.stderr)
            print("\n".join(list(diff)[:40]), file=sys.stderr)
            return 1
    print(f"OK: vendor/{pkg} matches {repo} source")
    return 0


def main() -> int:
    if not VENDOR_ROOT.exists():
        print("SKIP: no vendor/ directory")
        return 0
    # Auto-discover any vendored package, mapping known names; unknown ones are
    # checked against a sibling repo of the same name (best effort).
    failures = 0
    for pkg_dir in sorted(p for p in VENDOR_ROOT.iterdir() if p.is_dir()):
        pkg = pkg_dir.name
        repo = KNOWN.get(pkg, pkg)
        # If not a known mapping, fall back to a sibling repo with the same name.
        if repo == pkg and not (ROOT.parent / pkg).exists():
            repo = pkg.replace("_", "-")
        failures += check_one(pkg, repo)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
