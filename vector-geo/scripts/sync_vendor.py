#!/usr/bin/env python3
"""Sync the canonical vector_geo package into a consuming engine's vendor dir.

Usage: python3 scripts/sync_vendor.py /path/to/consuming/repo

Copies src/vector_geo/ -> <repo>/vendor/vector_geo/ so the engine stays
self-contained (isolation-safe for the per-repo CI gate) while the single
source of truth remains vector-geo. Run this after editing vector-geo; a
mismatched vendor copy fails the engine's CI validator (see check_vendor.py).
"""

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "vector_geo"


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: sync_vendor.py <consuming-repo-root>", file=sys.stderr)
        return 2
    repo = Path(sys.argv[1]).resolve()
    dest = repo / "vendor" / "vector_geo"
    if not repo.exists():
        print(f"error: repo not found: {repo}", file=sys.stderr)
        return 1
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(SRC, dest)
    print(f"synced {SRC} -> {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
