"""One-shot migration of the legacy ``traces.jsonl`` into the SQLite store.

issue 02: the flat append-only JSONL is replaced by ``traces.db``. Any pre-gate
JSONL data is read, passed through ``vector_privacy.apply_gate`` (adr-0065) and
loaded into the quarantine store; the JSONL is then deleted. Raw pre-gate trace
data must not survive this issue.

Usage:
    VECTOR_TRACE_FILE=data/traces.jsonl VECTOR_TRACE_DB=data/traces.db \
        python scripts/migrate_traces.py

Uses the same env vars as the runtime store. No-op when no JSONL exists.
"""

import json
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in ("src", "vendor"):
    _path = os.path.join(ROOT, p)
    if _path not in sys.path:
        sys.path.insert(0, _path)

from vector_web.trace_store import TraceStore, _make_store  # noqa: E402
from vector_privacy.gate import apply_gate  # noqa: E402


def migrate(jsonl_path, db_path=None, delete=True):
    jsonl_path = jsonl_path or os.environ.get("VECTOR_TRACE_FILE")
    if not jsonl_path or not os.path.exists(jsonl_path):
        print("no traces.jsonl found; nothing to migrate")
        return 0
    store = _make_store(db_path)
    now_ms = int(time.time() * 1000)
    total = dropped = 0
    with open(jsonl_path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            kind = rec.get("kind", "probe")
            # legacy JSONL stored ONE point per line ({"ts":..,"kind":..,lng,lat,t,s,a})
            pts = [rec]
            # gate every existing record — nothing pre-gate survives uncleaned
            kept, drp = apply_gate(kind, pts, now_ms=now_ms)
            if kept:
                total += store.append(kind, kept, pseudonym=rec.get("trip_pseudonym"))
            dropped += sum(drp.values())
    if delete:
        os.remove(jsonl_path)
        print(f"migrated {total} observations (dropped {dropped}); deleted {jsonl_path}")
    else:
        print(f"migrated {total} observations (dropped {dropped}); kept {jsonl_path}")
    return total


if __name__ == "__main__":
    migrate()