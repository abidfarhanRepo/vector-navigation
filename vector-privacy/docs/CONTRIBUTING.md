# Contributing — vector-privacy

- Follow the Vector Engineering Bible (§3 design rules, §16 validation).
- Keep the module stdlib-only and pure (no I/O, no global state).
- Do not relax a privacy rule without a new ADR decision and a test that the
  previously-dropped payload is now kept (and vice versa).
- Vendor updates into consumers (`vector-web`, `vector-learning`) deliberately;
  never edit the vendored copy in the consumer without a matching upstream
  change here.
