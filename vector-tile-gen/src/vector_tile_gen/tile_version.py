"""Tile-set epoch: the cache-invalidation primitive for issue 08.

The Session 50 failure this closes: a re-baked tile is byte-different at the
same URL, and every layer in front of it is allowed to keep the old bytes. The
tile server sends ``Cache-Control: public, max-age=86400``, so a browser can
serve a stale road for a day, and MapLibre additionally holds decoded tiles for
the life of the page. Neither notices that the file changed. A learned road can
therefore be promoted, verified, and written to disk — and still be invisible.

Rather than weaken caching (tiles are large and immutable *between* bakes, so
long caching is right), the fix gives the tile set a **version** and puts it in
the URL:

1. A bake or re-bake bumps the epoch in ``<tiles_dir>/VERSION.json``.
2. The tile server exposes it at ``GET /tiles/version`` with ``no-store`` — the
   one thing that must never be cached.
3. The client reads it before building the style and appends ``?v=<epoch>`` to
   the tile URL template.

A new epoch is a new URL, so the browser cache, any CDN, and MapLibre's own
tile cache all miss and re-fetch. The old entries expire on their own. No
cache-busting header games, no service-worker surgery, and it works for
intermediaries we do not control.
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, Optional

VERSION_FILENAME = "VERSION.json"

# A tile set that has never been versioned reads as epoch 0, which is a valid
# starting state — not an error. It just means no re-bake has happened yet.
INITIAL_EPOCH = 0


def version_path(tiles_dir: str) -> str:
    return os.path.join(tiles_dir, VERSION_FILENAME)


def read_version(tiles_dir: str) -> Dict[str, Any]:
    """Read the current epoch. Missing or corrupt reads as the initial epoch.

    Deliberately total: a tile server must start and serve tiles even if this
    file is absent, and a client must be able to build a style regardless.
    """
    path = version_path(tiles_dir)
    if not os.path.exists(path):
        return {"epoch": INITIAL_EPOCH, "updated_at": 0, "reason": "never versioned"}
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (OSError, ValueError):
        return {"epoch": INITIAL_EPOCH, "updated_at": 0, "reason": "unreadable"}
    if not isinstance(doc, dict):
        return {"epoch": INITIAL_EPOCH, "updated_at": 0, "reason": "malformed"}
    try:
        epoch = int(doc.get("epoch", INITIAL_EPOCH))
    except (TypeError, ValueError):
        epoch = INITIAL_EPOCH
    return {
        "epoch": epoch,
        "updated_at": int(doc.get("updated_at") or 0),
        "reason": str(doc.get("reason") or ""),
        "tiles_changed": int(doc.get("tiles_changed") or 0),
    }


def bump_version(
    tiles_dir: str,
    *,
    now_ms: int,
    reason: str = "",
    tiles_changed: int = 0,
) -> Dict[str, Any]:
    """Increment the epoch and write it atomically. Returns the new record.

    Must be called **after** the new tiles are on disk, never before: the epoch
    is a promise that whoever fetches this version gets the new bytes. Bump
    first and a client can pin a fresh epoch to stale content, which is the
    original bug with extra steps.
    """
    current = read_version(tiles_dir)
    record = {
        "epoch": int(current["epoch"]) + 1,
        "updated_at": int(now_ms),
        "reason": reason,
        "tiles_changed": int(tiles_changed),
    }
    os.makedirs(tiles_dir, exist_ok=True)
    path = version_path(tiles_dir)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(record, fh, sort_keys=True)
    os.replace(tmp, path)
    return record


def tile_url_template(base_url: str, tiles_dir: Optional[str] = None,
                      epoch: Optional[int] = None) -> str:
    """Build the versioned tile URL template a client should use.

    ``?v=<epoch>`` is what actually defeats the caches. Epoch 0 still gets the
    parameter so the URL shape never changes between a fresh install and a
    re-baked one — a template that changes shape is its own cache-miss bug.
    """
    if epoch is None:
        epoch = read_version(tiles_dir)["epoch"] if tiles_dir else INITIAL_EPOCH
    return f"{base_url.rstrip('/')}/tiles/{{z}}/{{x}}/{{y}}.mvt?v={int(epoch)}"
