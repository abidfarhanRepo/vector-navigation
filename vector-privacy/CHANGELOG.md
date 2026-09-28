# CHANGELOG — vector-privacy

## 2026-08-05 — Per-trip endpoint truncation (ticket 23)

**New: `truncate.py` / `TripTruncator`.**

`apply_gate` truncates the first and last 200 m of *the payload it is given*. That
is correct for an import, where one payload is a whole trip. It is wrong for live
capture the moment a client sends only new points — and that client is exactly what
ticket 20 was about to write.

How wrong was measured, not estimated: a 24-point payload at 10 m per fix spans
~230 m, which is **less than the two 200 m cuts**, so per-payload truncation stores
**nothing at all**. A 120-point trip in five payloads: 0 points before, 80 after.
The earlier estimate of "~400 m lost per batch boundary" understated it; any batch
shorter than 400 m of road self-annihilates.

The seam avoids needing a whole trip. "Where does the trip end?" is unanswerable
until it ends; "is this point more than 200 m behind the frontier?" is local and
answerable per payload:

* **Head — no buffer.** A running distance accumulator and the previous point are
  enough, so the home end is never written to disk *or held in memory*. That is the
  property the read-time alternative could not offer.
* **Tail — a bounded pending buffer**, released as the frontier advances and
  **discarded** at trip end or idle timeout. The discard *is* the tail truncation.

The invariant: no point within 200 m along-track of a trip's true start or end is
ever committed, under any interleaving of payloads. State is in memory only, so
there is no new table to TTL and nothing new for the vacuum to reach; a restart
over-truncates, which is the safe direction of error.

Counting is split on purpose. `head_truncated` and `out_of_order_refused` are
permanent losses and join the gate's drop reasons; `pending_tail_withheld` is a
**deferral** — usually committed on the next payload — and is counted apart, because
folding it into the drop total would let the privacy panel claim enforcement it never
performed.

**Fixed: `apply_gate`'s `truncate_distance_m` was accepted and ignored.**
`_truncate_endpoints` read the module constant instead, so every caller passing a
different threshold got 200 m and no error. Threaded through, with a test. It is also
why the new switch is `truncate: bool` and not `truncate_distance_m=0` — zero works
by accident (every comparison against it passes) and reads as a threshold change when
it is a behaviour change.

Tests: 16 → 50.

## 2026-08-04 — Timestamp-unit normalization (Session 52, issue 01)

**Fixed: a seconds timestamp read as milliseconds.**

The browser client posted trace timestamps in seconds while every consumer read
milliseconds. Real captures would have landed on 1970-01-21, where the 72 h TTL
deletes them on the next vacuum — accepted, stored, reported as stored, and gone
before anything could aggregate them. A pipeline that learns nothing while every
check passes. Synthetic test data used milliseconds, so nothing exercised it.

Fixed at the source (`vector-web/static/index.html`), **and** here. The gate is
the one place the rules are enforced and the client is never trusted to have done
its half — and unit confusion is the cheapest way for a client to poison the
pipeline without looking malicious. `_normalize_ms` rescales a value that is
unambiguously seconds (between ~1e8 and ~1e11; a millisecond epoch for any
plausible date is above ~1e12). Rescaling rather than rejecting is deliberate: the
data is good, only its unit is wrong, and dropping it would lose real evidence to
a client bug.

Also re-vendored into `vector-web` and `vector-learning`. Their copies had drifted
and their docstring cited **adr-0060** (the mobile Flutter client) instead of
adr-0065 — and because the ingest path imports the *vendored* module, the fix
above would not have reached production. `check_vendor.py` covers `vector_privacy`
and now passes clean.

Tests: 10 → 16.
