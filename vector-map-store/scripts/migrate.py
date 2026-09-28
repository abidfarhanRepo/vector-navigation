#!/usr/bin/env python3
"""Bootstrap the vector-map-store PostGIS schema.

Usage:
    python3 scripts/migrate.py            # uses VECTOR_PG_DSN
    python3 scripts/migrate.py "postgresql://user:pass@host:5432/vector"

Idempotent: applies schema.sql (CREATE ... IF NOT EXISTS). Exits non-zero on
connection failure so it can gate a deployment.
"""

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    dsn = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("VECTOR_PG_DSN")
    if not dsn:
        print("error: no DSN (pass as argv[1] or set VECTOR_PG_DSN)", file=sys.stderr)
        return 2
    try:
        import psycopg
    except ImportError:
        print("error: psycopg not installed (pip install -r requirements.txt)", file=sys.stderr)
        return 1
    sql = (ROOT / "schema.sql").read_text(encoding="utf-8")
    try:
        with psycopg.connect(dsn, autocommit=True) as conn, conn.cursor() as cur:
            cur.execute(sql)
        print("schema applied: vector_features (GIST + GIN indexes)")
        return 0
    except Exception as exc:  # noqa: BLE001 - surface any connect/apply error
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
