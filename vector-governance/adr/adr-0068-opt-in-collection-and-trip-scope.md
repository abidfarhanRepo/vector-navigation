# ADR-0068 — Trace collection is opt-in, and a pseudonym is exactly one trip

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** D5 Security & Compliance / D3 Architecture / D6 Data
- **Supersedes:** none
- **Superseded by:** none

## Context

ADR-0065 established the rule that makes Vector's learning loop defensible —
*learn from aggregates over road segments, never from individual traces* — and
fixed the thresholds: K=5 distinct trip pseudonyms, a 72 h quarantine TTL, 200 m
endpoint truncation, a 25 m accuracy floor, per-trip pseudonyms.

Ticket 13 required two things that ADR left open, because they only arise when
collection actually starts:

1. **Is collection on by default, or opt-in?** ADR-0065 never said.
2. **What, exactly, is a trip?** ADR-0065 says "per-trip pseudonym, rotated on
   trip end", but nothing implemented trip boundaries.

The second turned out not to be a gap but a live defect, and it invalidated the
central guarantee.

## Decision 1 — collection is opt-in

Trace capture is **off until the person explicitly turns it on**, behind a
blocking consent sheet that states what is stored and what is not, and offers an
off switch that stops collection immediately.

The reasoning, because the ticket rightly refused to let this happen by default:

- **ADR-0065's argument only holds if the subject can verify it.** Its whole claim
  is that Vector learns *without* holding personal data. A silent default-on would
  be technically compliant with every threshold in that ADR and a betrayal of its
  reasoning. A privacy property nobody can inspect or refuse is a marketing claim.
- **The asymmetry is decisive.** Opt-in under-collects: recoverable, costs time.
  Default-on collects location data from someone who never agreed: not
  recoverable, and cannot be retroactively consented. When one error is reversible
  and the other is not, the reversible one is the default.
- **Self-hosting cuts both ways.** The operator may be the only subject (where
  default-on would be harmless) or may be deploying for other people (where it
  would not). Opt-in is correct in both cases; default-on is correct in only one.
- **It costs almost nothing here.** Because K counts distinct *trips*, a single
  committed driver clears K=5 on their own commute within days (see
  `scripts/quantify-bands.py`). So the people who want the map to learn are
  precisely the population whose data it uses — which is also the population whose
  consent is most meaningful.

Consequence accepted: coverage grows more slowly than it would with default-on,
and the growth rate now depends on persuading people rather than on defaults.

## Decision 2 — a stored pseudonym is exactly one trip, derived server-side

**The defect.** The web edge minted a fresh pseudonym on every `POST /traces`,
while the client uploads a batch every 30 fixes. One continuous drive therefore
arrived as several "distinct trips". Demonstrated: 150 fixes, one journey, one
segment → one `segment_evidence` row emitted, recording `n_trips=5`.

**One person's one journey satisfied the k-anonymity floor.** The number K counts
was not the number ADR-0065 claims it counts.

Note this is the *opposite* of the failure ticket 13 anticipated (a pseudonym
outliving a trip, linking journeys). Both are wrong, and they fail in opposite
directions: too long links a person's journeys and defeats endpoint truncation;
too short manufactures fake trips and defeats the floor.

**The decision.** Only the client knows where a trip starts and ends, so it mints
an opaque token at trip start and sends it with every batch of that trip,
discarding it at trip end. The server does not trust it and does not store it: the
stored pseudonym is `HMAC-SHA256(server_salt, client_token)`, truncated. This
gives:

- stability across every batch of one trip — the property K actually needs;
- unlinkability between the stored pseudonym and the token the client holds;
- no way for one client to collide with or impersonate another's pseudonym;
- unlinkability across a salt rotation.

The salt is persisted (`VECTOR_TRIP_SALT`, else a file beside the store) rather
than per-process, because a salt that changed on restart would re-split in-flight
trips — reintroducing the same bug intermittently, which is harder to catch than
always.

**Threat model, stated rather than implied.** A client that invents a new token per
batch can still inflate its own apparent trip count. This is a self-hosted map
where the client is the user's own browser, so that costs the attacker their own
privacy floor and gains them nothing. Guessing trip boundaries server-side would
be worse: it would be wrong sometimes, silently. What matters is that the honest
path is correct and that a request with no token is **counted** (a
`no_trip_token` privacy counter) rather than accepted as a fresh trip.

## Consequences

- **Positive:** K=5 now means five distinct journeys. Verified both ways: one
  batched drive yields one pseudonym and emits nothing; five separate trips yield
  five and emit one evidence row with `n_trips=5`.
- **Positive:** the privacy claims on the consent sheet are checkable, not just
  stated — it links `/privacy-counters` and `/evolution`, both live.
- **Negative:** coverage grows more slowly than default-on would achieve, and
  depends on people choosing to contribute.
- **Negative:** the `no_trip_token` fallback still exists for clients that do not
  send one. It is counted and visible, but a deployment serving an old cached
  client would silently inflate trip counts until that client updates. The service
  worker's shell cache makes this a real window, not a theoretical one.
- **Known, not fixed:** endpoint truncation runs per *upload batch*, so a trip
  uploaded in five batches has ~400 m removed at each batch boundary rather than
  only at its true ends. That is over-truncation — privacy-safe, data-lossy — and
  it wastes usable evidence in the middle of trips. Worth fixing by truncating
  per trip once the trip is known to be complete; recorded here so it is not
  mistaken for correct behaviour.

## Alternatives considered

- **Default-on with a prominent off switch** — rejected on the asymmetry argument
  above. "You could have turned it off" is not consent.
- **Server-side trip inference from a time gap between batches** — rejected: it
  guesses, it would be wrong at exactly the moments that matter (a traffic jam
  looks like a trip end), and being wrong silently is the failure mode this ADR
  exists to remove.
- **Storing the client token directly** — rejected: it would put a client-chosen
  identifier in the quarantine store, and a careless client build could put
  something identifying there. Hashing costs nothing and removes the class.
- **Per-device pseudonym** — already rejected by ADR-0065, and this decision
  reinforces why: a device's motion pattern links its trips.

## Addendum — token-minting and destination learning (Status: Proposed)

> Introduced by ADR-0074 (destination-precision learning). **Proposed**; becomes live only when
> ADR-0074 is ratified.

This ADR already records (Decision 2, "Threat model") that a client which invents a new token per
batch can inflate its own apparent trip count, and judged that self-harming for *speed* facts. The
**destination-learning** context changes that calculus and must be recorded explicitly:

- **A client can potentially create multiple tokens** — per-batch token minting is not blocked; the
  server hashes and stores whatever token it is given.
- **Therefore token uniqueness cannot be treated as proof of human / contributor uniqueness.** Five
  distinct trip pseudonyms clearing K = 5 is **not** five distinct people. For endpoint facts this
  matters: one actor minting five tokens to the same destination could publish *their own* (or a
  targeted) precise location, defeating the anonymity the K floor is meant to provide.
- **The destination-learning system must compensate** using additional aggregation/privacy controls
  — ADR-0074 requires a **contributor-diversity proxy** (§4.2), **spatial dispersion** (§4.3),
  **temporal dispersion + band diversity** (§4.4), and **concentrated-cluster suppression** (§4.5) so
  that token uniqueness alone cannot satisfy the gate. We do **not** pretend to have perfect
  contributor identity.
- The `no_trip_token` counter (Decision 2) remains the detection signal for token-less clients; a
  *parallel* signal for implausible token churn at a single endpoint is required by ADR-0074's
  aggregation controls.

## References

- ADR-0065 — privacy gate (K, TTL, truncation, accuracy floor)
- ADR-0067 — time bands for learned speed profiles
- Ticket 13: `.scratch/vector-prove/issues/13-consent-and-collection.md`
- `vector-privacy/src/vector_privacy/trip.py` and `tests/test_trip.py`
