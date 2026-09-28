"""Quarantine TTL vacuum job (issue 02 / adr-0065).

Deletes observations older than the 72 h TTL and vacuums the database so the
raw GPS is actually gone from disk. Intended to run on a schedule (the nightly
gate / a cron step). No-op-safe: never raises if the db does not exist yet.

Usage:
    python scripts/vacuum_traces.py [path-to-traces.db]
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in ("src", "vendor"):
    _path = os.path.join(ROOT, p)
    if _path not in sys.path:
        sys.path.insert(0, _path)

from vector_web.trace_store import TraceStore  # noqa: E402


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    path = argv[0] if argv else os.environ.get("VECTOR_TRACE_DB") or os.environ.get("VECTOR_TRACE_FILE")
    if not path or not os.path.exists(path):
        print("no trace db found; nothing to vacuum")
        return 0
    store = TraceStore(path)
    try:
        deleted = store.expire()
        print(f"expired {deleted} observations; remaining {store.count()}")
        return 0
    finally:
        store.close()


if __name__ == "__main__":
    raise SystemExit(main())