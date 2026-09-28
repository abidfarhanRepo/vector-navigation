# Definition of Done

> Canonical source: Vector Engineering Bible §10. This local copy is kept in sync by the owning squad.

A change is DONE only when ALL hold:

1. **Built** — compiles/lints/passes static analysis in CI.
2. **Tested** — unit + integration + contract tests pass.
3. **Reviewed** — required approvals obtained per Bible §12 and Blueprint §8.
4. **Secure** — all security gates green; no secrets; least privilege verified.
5. **Observable** — metrics, logs, and traces added per Bible §15.
6. **Documented** — docs updated in the same PR; runbook updated if behavior changed.
7. **Versioned** — semver bump and tag applied if released.
8. **Merged** — squash-merged to `main`; branch deleted.
9. **Traceable** — linked to task ID, ADR (if any), and decision log.
10. **No regressions** — existing tests, performance budgets, and coverage MUST NOT regress.
