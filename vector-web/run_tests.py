#!/usr/bin/env python3
"""Portable test runner for vector-web.

The npm ``test``/``validate`` scripts must work on both POSIX CI (bash,
``python3``) and Windows dev boxes (cmd.exe, where ``PYTHONPATH=src:vendor
python3 ...`` is not valid). This shim injects ``src``/``vendor`` onto
``sys.path`` and runs the suite under the current interpreter — no env vars,
no inline-shell syntax, no extra dependencies.
"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.abspath(__file__))
for p in ("src", "vendor"):
    path = os.path.join(ROOT, p)
    if path not in sys.path:
        sys.path.insert(0, path)


def main() -> int:
    loader = unittest.TestLoader()
    suite = loader.discover(os.path.join(ROOT, "tests"), pattern="test_*.py")
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
