# ADR-0069 — Imported recorded tracks are a first-class collection tier

- **Status:** Proposed
- **Date:** 2026-08-05
- **Deciders:** D5 Security & Compliance / D3 Architecture / D6 Data
- **Supersedes:** none
- **Superseded by:** none

## Context

ADR-0065 fixed the privacy rules for collected location data and ADR-0068 fixed
what a trip is. Neither addressed the question that turns out to gate everything
downstream: **how does a track get off a phone at all?**

Two findings, both verified rather than assumed:

1. **A PWA cannot record a drive.** Capture is `navigator.geolocation.watchPosition`
   with no wake lock, no persistence and no upload queue. Both iOS Safari and
   Android Chrome stop delivering positions to a backgrounded tab, and there is no
   Background Geolocation API on the web. A real journey today requires the phone
   awake, unlocked, on that tab, with continuous signal. That is a platform
   constraint, not a defect in `index.html`.
2. **Historical tracks were deleted before they were ever aggregated.**
   `run-evolution-cycle.mjs` ran `vacuum → batch`, and the vacuum enforces the
   72 h TTL. An imported track is historical by definition, so it was stored,
   reported as stored, and destroyed before the batch job read it. Verified with
   tracks aged 5 and 30 days: 40 of 60 points deleted, 20 (the live drive) left.

The second finding disabled the only workaround for the first. Phones already
record tracks through OsmAnd, Organic Maps, Strava, Komoot, Garmin Connect and
every built-in recorder, all of which hold real background location permission
because the OS granted it. Import is therefore the one collection path that needs
**no platform cooperation whatsoever**, and it was silently a no-op.

This ADR records the decisions that ticket `19` cannot be built without, and
ratifies the ordering fix from ticket `18`.

## Decision 1 — the cycle aggregates before it vacuums (ratifying ticket 18)

Stage order is **batch → promote → rebake → metrics → vacuum**, and the vacuum
stays unconditional.

**This is not a TTL relaxation, and the distinction is the whole point.** The TTL
governs how long raw location data is *retained*; reordering keeps that promise
exactly, because the data is still deleted in the same run, seconds later. What
changes is that its aggregate is extracted first — and that aggregate is
non-personal by construction (a road's typical speed past a K floor). Extracting
it before deletion costs nothing in privacy and is the entire reason for
collecting.

The vacuum must run even when the batch fails. A batch failure that skipped the
vacuum would convert a transient error into indefinite raw-GPS retention, and the
TTL is a promise rather than a best effort. Verified both halves: a 10-day-old
observation present at cycle start is aggregated into evidence *and* its raw row
is gone afterwards. Half of that test passing is the bug, in either direction.

## Decision 2 — imported observations are accepted up to 365 days old

Older uploads are refused with an explicit reason. Ticket `18` left this open
with three candidates (accept any age; bound it; accept and mark) and the choice
turns on which argument you take seriously.

**The privacy argument for a bound is weak.** A bound does limit how much of one
person's past a single upload can disclose, but everything that actually protects
that person is already elsewhere: a separate consent step (Decision 5), 200 m
endpoint truncation, the 72 h TTL on the raw rows, and a K floor on what
persists. Choosing 90 days over 365 on privacy grounds would be a number chosen
to look careful.

**The validity argument for a bound is strong, and it is about data quality.**
Road speeds are a property of a road *as it is now*. A track from three years ago
was recorded before resurfacing, re-signing, a new junction or a changed speed
limit, and the learning loop has no way to know which. Old evidence is not merely
less useful, it is potentially *wrong* in a way that looks identical to right —
which is the failure mode this project keeps finding and refusing. One year is
where the risk of an unnoticed physical change stops being small.

So: **365 days, justified on validity, and stated as such** so nobody later
"tightens" it to 30 days believing they are improving the privacy posture when
they are only discarding evidence.

Consequences accepted, and recorded rather than hidden:

- Age is **recorded per observation** (an age bucket at ingest, not a raw
  original timestamp beyond what is already stored), so the fraction of learned
  evidence resting on old data is visible instead of inferred.
- **Aggregation does not yet weight by recency.** Recency weighting is the more
  correct answer than any hard cutoff and this ADR does not implement it; a
  360-day-old point and an hour-old point currently count the same within a band.
  That is a known limitation, and the cutoff is the crude stand-in for it.

## Decision 3 — a missing accuracy value is accepted as unknown and never fabricated

GPX rarely carries an accuracy figure. The gate drops points with `a > 25 m`, and
its accuracy check is skipped entirely when the field is absent — so absent
accuracy is *already* accepted today, by omission rather than by decision. This
makes it a decision.

- **Accept the point, record the field as unknown.** Synthesising a conservative
  value above the floor would drop every imported point and return the import
  tier to the no-op it just stopped being. Synthesising a value below the floor
  would invent a measurement.
- **Never convert HDOP into metres.** Several recorders emit `<hdop>`, and the
  conversion to a distance requires the receiver's UERE, which the file does not
  carry. Multiplying HDOP by a guessed constant manufactures a precise-looking
  number out of an unknown, and it would then be compared against a 25 m floor as
  though it meant something. Use an explicit accuracy extension when a file
  provides one; otherwise unknown.
- **Replace the check rather than skip it.** Accuracy was screening out fixes that
  would poison a speed profile. Where it is unknown, screen on what the track
  itself reveals: reject a point whose implied speed from its predecessor exceeds
  200 km/h or whose implied movement is physically impossible, and reject a
  segment sampled too sparsely to map-match honestly. A kinematic check is
  evaluable from the data present; a fabricated accuracy is not.
- **Count it.** `accuracy_unknown` becomes a privacy/quality counter, so the share
  of the corpus whose accuracy was never verified is a number an operator can
  read rather than a property of the file format nobody thinks about.

## Decision 4 — one trip is one recorded segment, split further on gaps

Trip tokens for imports are minted **server-side, one per detected trip**
(ADR-0068 shape, `HMAC(salt, token)` stored). Detection is:

- **Each GPX `<trkseg>` is a separate trip.** That is what a segment means in the
  format: it is where the recorder itself judged the track broke.
- **Split further on any intra-segment time gap over 5 minutes**, or on an implied
  speed above the plausibility ceiling. Some recorders emit a whole day as one
  segment, and at road speed a five-minute gap is kilometres of unknown track that
  map-matching would otherwise interpolate straight through.

Neither degenerate option is available. **One token per file** would make a month
of commuting a single "trip" that never clears K. **One token per point**
manufactures trips and is the exact defect ADR-0068 exists to remove. Note the
directions of failure are opposite, and both are wrong.

## Decision 5 — bulk import gets its own consent, and a dry run before it

A live trip discloses one journey. A file discloses **months of one identifiable
person's movement in a single irreversible act**, and it must not inherit the
live-capture consent from ADR-0068.

- A distinct consent step that states what the file contains, that endpoints are
  truncated, that raw points are deleted after aggregation, and what is kept.
- A **dry-run preview before any ingest**: trips detected, point count, date
  range, and how many points the gate *would* drop — with the ability to cancel
  after seeing it. Uploading a year of movement must not be one click whose
  consequences are only visible afterwards.

## Decision 6 — a re-uploaded track is refused, not counted twice

Found while designing this, and it is the same class of defect as ADR-0068's:
**uploading one file twice manufactures two trips from one journey.** A driver
who is unsure whether the upload worked and clicks again inflates the trip count
on their own route, and one journey uploaded five times clears K=5 alone. The
resulting facts look completely ordinary.

Import is idempotent by content: a truncated `HMAC(trip_salt, gated geometry +
coarsened timestamps)` per detected trip is retained for the import age window,
and a matching digest is refused at the preview stage with a message saying the
trip is already present.

The trade, stated rather than glossed: **the digest is a confirmation oracle.**
It is irreversible and identifies nobody on its own, but someone already holding
an exact track could test whether it was uploaded. It also outlives the 72 h TTL,
which raw location data may not. It is accepted because the alternative is a K
floor that a routine user accident quietly voids, and because a digest of
gate-processed geometry is not raw location data. ADR-0068's "it costs the
attacker their own privacy floor" reasoning does *not* rescue this case: that
argument covers deliberate abuse, and this is an accident that a careful person
will make.

Because the digest is computed over gated output, a change to the gate's
thresholds changes all digests and makes re-upload possible again. That is
acceptable and noted.

## Clarification — K=5 is trip-anonymity, not person-anonymity

Import makes concrete something that has been true since ADR-0065 and that its
wording implies otherwise.

Pseudonyms are per-trip and **deliberately unlinkable**, so the system cannot
distinguish five trips by five people from five trips by one person. One driver's
month of commuting clears K=5 on their own route, alone. This is not a defect —
it follows directly from per-trip pseudonyms being the right choice, and
ADR-0068's own reasoning relies on it ("a single committed driver clears K=5 on
their own commute within days"). The emitted aggregate remains non-personal: a
road's typical speed in a time band is not a fact about a person.

But the floor does not mean "five people agreed to this", and no document should
imply it does. **ADR-0065's body is deliberately left unedited** — it is Accepted,
and ADRs are immutable. This section is the correction of record, and any
user-facing text that describes the floor as five contributors is wrong and
should cite this ADR.

## Correction of record — ADR-0068's truncation note

ADR-0068 records under "Known, not fixed" that endpoint truncation runs per upload
batch, so "a trip uploaded in five batches has ~400 m removed at each batch
boundary rather than only at its true ends". The mechanism is described correctly
and the observed effect is not.

The live client re-sends the **entire accumulated track** on every batch
(`index.html:1570`; `tracePts` is never truncated after a successful upload). Each
payload is therefore the whole trip so far, so the head cut always lands on the
trip's true head, and points withheld by the tail cut become interior points in the
next payload and are stored then. **No interior points are permanently lost today.**
The system over-stores instead — 11x amplification on a ten-minute drive, 83x at
the 5,000-point cap — which is its own defect (ticket `20`) and is why the
truncation loss was inferred rather than observed.

The consequence this matters for is an ordering one: the described waste becomes
real the moment a client sends only new points. So the per-trip truncation fix
(ticket `23`) must land before or with the upload-queue fix (ticket `20`).

ADR-0068's body is **left unedited** — it is Accepted, and ADRs are immutable. This
section is the correction of record, as with the K wording above.

## Consequences

- **Positive:** real driving data becomes obtainable this week, with no install,
  no permission grant and no platform capability — from recorders that already
  have background location.
- **Positive:** historical depth live capture can never recover. An existing
  OsmAnd or Strava archive may already hold months of the same commute, which is
  exactly the repeated same-band coverage K=5 needs and that live capture would
  take weeks to accumulate.
- **Positive:** truncation is *naturally correct* for imports. A whole trip
  arrives in one payload, so the endpoints trimmed are its true ends — unlike live
  capture, which truncates per upload batch (ticket `23`).
- **Negative:** a new parser is a new attack surface and a new source of silent
  wrongness. Untrusted XML, tens of megabytes, and exporters that disagree with
  the format. Size limits, streaming, and a real exported file as a test fixture
  are requirements, not polish.
- **Negative:** the age bound is a crude stand-in for recency weighting, and
  evidence within the window is weighted equally regardless of age.
- **Negative:** the idempotency digest is state derived from location data that
  outlives the raw-data TTL, accepted for the reason given in Decision 6.
- **Operational:** every tier posts the same shape through the same gate. An
  import is not privileged for arriving as a file.

## Alternatives considered

- **Fix live capture first and skip import.** Rejected: ticket `20` can raise the
  ceiling of web capture but cannot pass it, because background execution is what
  browsers withhold. Import needs nothing from the platform and delivers sooner.
- **Wait for the native shell (ADR-0070) instead.** Rejected as a *substitute*,
  accepted as a successor. The shell needs an install, an APK distribution story
  and a battery measurement; import needs a parser. Import also remains the only
  path to a driver's existing archive even after the shell ships.
- **Relax the 72 h TTL so historical uploads survive to the next cycle.**
  Rejected outright — it weakens the ADR-0065 retention promise to fix an
  ordering accident. Reordering the cycle achieves the same result with the
  promise intact.
- **Reject any observation older than the TTL at ingest.** Coherent, and it
  discards the entire value of import. The TTL is a retention bound, not a
  statement about which evidence is admissible.
- **Trust the file's own accuracy or HDOP as metres.** Rejected in Decision 3: it
  invents precision and then tests the invention against a real threshold.
- **Server-side trip inference by clustering the whole file.** Rejected for the
  same reason ADR-0068 rejected it for live capture: it guesses, and it would be
  wrong at exactly the moments that matter. `<trkseg>` is the recorder's own
  judgement and is better evidence than our reconstruction of it.

## References

- ADR-0065 — privacy gate (K, TTL, truncation, accuracy floor); its K wording is
  corrected by the Clarification above, not by editing it
- ADR-0068 — opt-in collection and one-pseudonym-per-trip
- ADR-0070 — native capture shell (tier 3)
- `.scratch/vector-collect/GOALS.md`, tickets `18`, `19`, `22`, `23`
- `.scratch/vector-collect/DECISIONS.md` — the decision register this ADR closes
- `vector-privacy/src/vector_privacy/gate.py`, `trip.py`
- `scripts/run-evolution-cycle.mjs` (stage order and the comment that defends it)
