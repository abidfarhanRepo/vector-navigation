# Definition of Ready

> Canonical source: Vector Engineering Bible §9. This local copy is kept in sync by the owning squad.

A task MUST NOT be started until ALL hold:

1. **Owned** — a primary squad owner is registered.
2. **Scoped** — acceptance criteria are explicit and testable.
3. **Contracted** — if it touches an interface, the contract change is declared in `#contracts`.
4. **Designed** — non-trivial design has an ADR or references an existing pattern ID.
5. **Resourced** — required worker type is available or provisioned via COA.
6. **Observed** — required observability hooks are specified.
7. **Risk-classed** — security/infra impact is assessed (normal vs. sensitive).
8. **Documented intent** — the why is captured for the future reader.
