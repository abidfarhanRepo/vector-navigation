#!/usr/bin/env python3
"""Vendor drift check for the SOURCE repo (vector-bus-client).

This repository is the CANONICAL source of vector_bus_client (ADR-0007). It has
no vendored copy of itself; the vendored copies live in consuming engines
(vector-routing, vector-traffic, ...). Those engines run their own
check_vendor.py to confirm they stay in sync with THIS source.

So here the check is a no-op: the source is always consistent with itself.
Consuming-engine drift is validated by their gates.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    vendor = ROOT / "vendor"
    if not vendor.exists():
        print("SKIP: vector-bus-client is the canonical source; no vendored copy to check")
        return 0
    # If a vendor/ was injected for some reason, still report it is the source.
    print("OK: vector-bus-client is the canonical source (no self-vendor drift to check)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
