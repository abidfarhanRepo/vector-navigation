#!/usr/bin/env python3
"""Validate that a consuming engine's vendored vector_geo matches the source.

Usage: python3 scripts/check_vendor.py /path/to/consuming/repo

Exits non-zero (CI failure) if vendor/vector_geo differs from
../vector-geo/src/vector_geo. Under per-repo isolation (the act gate, where the
vector-geo sibling is not checked out) the vendor dir is the source of truth,
so this script SKIPS (exit 0) when ../vector-geo is absent.

Run from the consuming engine repo, or pass its root as an argument.
"""

import difflib
import filecmp
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# When run inside vector-geo itself, ROOT is vector-geo; when run inside a
# consuming engine, the canonical source lives at ../vector-geo.
SOURCE = (ROOT.parent / "vector-geo" / "src" / "vector_geo")
VENDOR = ROOT / "vendor" / "vector_geo"


def _walk(p: Path):
    return sorted(
        f.relative_to(p).as_posix()
        for f in p.rglob("*.py")
    )


def main() -> int:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    source = repo.parent / "vector-geo" / "src" / "vector_geo" if (repo / "vendor").exists() else SOURCE
    vendor = repo / "vendor" / "vector_geo" if (repo / "vendor").exists() else VENDOR

    if not source.exists():
        # Per-repo isolation: canonical source not present; vendor is truth.
        print("SKIP: vector-geo source not found (per-repo isolation?) — vendor is source of truth")
        return 0
    if not vendor.exists():
        print("FAIL: vendor/vector_geo missing; run scripts/sync_vendor.py", file=sys.stderr)
        return 1

    src_files = set(_walk(source))
    vnd_files = set(_walk(vendor))
    if src_files != vnd_files:
        missing = src_files - vnd_files
        extra = vnd_files - src_files
        print(f"FAIL: file set mismatch (missing={sorted(missing)}, extra={sorted(extra)})", file=sys.stderr)
        return 1
    for rel in src_files:
        a = source / rel
        b = vendor / rel
        if not filecmp.cmp(a, b, shallow=False):
            diff = difflib.unified_diff(
                a.read_text().splitlines(), b.read_text().splitlines(),
                fromfile=str(a), tofile=str(b),
            )
            print("FAIL: vendor/vector_geo diverges from vector-geo source:", file=sys.stderr)
            print("\n".join(list(diff)[:40]), file=sys.stderr)
            return 1
    print("OK: vendor/vector_geo matches vector-geo source")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
