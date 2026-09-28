# Definition of Done — vector-privacy

A change to this repo is done when:
- `python -m py_compile` is clean on every source file.
- `PYTHONPATH=src python -m unittest discover -s tests -v` passes.
- `npm run validate` reports 0 errors.
- Any new privacy rule has at least one negative test (the violating payload is
  dropped) and one positive test (the compliant payload survives).
- `apply_gate` remains pure and total (never raises on malformed input).
- No device identifier, IP, or user ID is ever produced or stored.
