#!/usr/bin/env python3
"""Validate that vendor/vector_auth matches the canonical vector-auth source.

Self-contained (no dependency on the vector-auth repo's own scripts). Exits
non-zero (CI failure) if the vendored copy diverges. Under per-repo isolation
(the act gate, where the vector-auth sibling is not checked out) the vendor dir
is the source of truth, so this SKIPS (exit 0) when ../vector-auth is absent.
"""

import difflib
import filecmp
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT.parent / "vector-auth" / "src" / "vector_auth"
VENDOR = ROOT / "vendor" / "vector_auth"


def _walk(p: Path):
    return sorted(f.relative_to(p).as_posix() for f in p.rglob("*.py"))


def main() -> int:
    if not SOURCE.exists():
        print("SKIP: vector-auth source not found (per-repo isolation?) — vendor is source of truth")
        return 0
    if not VENDOR.exists():
        print("FAIL: vendor/vector_auth missing; run vector-auth/scripts/sync_vendor.py", file=sys.stderr)
        return 1
    src_files = set(_walk(SOURCE))
    vnd_files = set(_walk(VENDOR))
    if src_files != vnd_files:
        print(
            "FAIL: file set mismatch (missing=%s, extra=%s)"
            % (sorted(src_files - vnd_files), sorted(vnd_files - src_files)),
            file=sys.stderr,
        )
        return 1
    for rel in src_files:
        a, b = SOURCE / rel, VENDOR / rel
        if not filecmp.cmp(a, b, shallow=False):
            diff = difflib.unified_diff(
                a.read_text().splitlines(), b.read_text().splitlines(),
                fromfile=str(a), tofile=str(b),
            )
            print("FAIL: vendor/vector_auth diverges from vector-auth source:", file=sys.stderr)
            print("\n".join(list(diff)[:40]), file=sys.stderr)
            return 1
    print("OK: vendor/vector_auth matches vector-auth source")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
