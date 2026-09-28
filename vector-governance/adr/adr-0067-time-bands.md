# ADR-0067 - Time-of-week bands for learned speed profiles

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** vector-learning, vector-routing (ADR-0065, ADR-0066)

## Context

ADR-0065 specifies that learned `speed_profile` facts must carry a time-of-week
key so routing can select the right speed factor for the current moment. The
original schema used a 168-valued `hour_of_week` field (0..167), one bucket per
hour of the week.

The privacy gate (ADR-0065) enforces a **72-hour TTL** on quarantined
observations: raw traces are deleted three days after ingestion. But
`hour_of_week` buckets recur weekly. A segment driven at 08:00 on Monday and
again at 08:00 the following Monday lands in the **same** bucket — yet the
first week's observations were already deleted. Only trips within the *current*
72-hour window count toward a bucket's K-anonymity floor.

With 168 buckets and real-world trip volume, most buckets never reach K=5 before
the TTL expires.

**How much this helps is not yet measurable, and this ADR does not claim to know.**
There is no real trip data in the system — the only traces are synthetic ones from
`scripts/seed-demo-traces.py` — so no per-segment K=5 clearance rate can be
established for any scheme. The band count is therefore a **judgement call**, made
on the following reasoning rather than on measurement:

- 168 buckets is a temporal resolution no plausible early-stage trip volume can
  fill, since a bucket recurs weekly and its evidence is deleted after 72 h.
- Collapsing to a single bucket would fill easily and discard the peak/off-peak
  variation that is the entire reason a speed profile is time-keyed at all.
- 8 bands is the coarsest scheme found that still separates weekday AM peak, PM
  peak, midday, evening and night, plus weekend day/night — so it maximises
  evidence per bucket subject to keeping the distinction the router needs.

**The real number is an output of issue `14`, not an input to this ADR.** Once
collection is live, `covered` vs `groups_below_k` on the dashboard answers it
directly: if `groups_below_k` keeps climbing while `covered` stays flat, 8 bands is
still too fine and this decision should be revisited by a new ADR with actual
figures.

## Decision

Replace the 168-valued `hour_of_week` field with an 8-valued `band` field using
`BANDS_PER_WEEK = 8`, defined identically in `vector-learning` and
`vector-routing`:

| Index | Definition | Example |
|---|---|---|
| 0–5 | Weekday (Mon–Fri) × {early, AM peak, midday, PM peak, evening, night} | 08:00 → band 1 (AM peak) |
| 6–7 | Weekend (Sat–Sun) × {day, night} | Saturday noon → band 6 (weekend day) |

Key invariants, all **unchanged** from ADR-0065:

- **K = 5** distinct-trip floor per `(segment, band)` bucket
- **72-hour TTL** on quarantined observations
- **Endpoint truncation** to 200 m
- **Accuracy floor** of 25 m
- **Per-trip pseudonyms** (not device IDs)
- **5-second time rounding**
- **3-hour aggregation window** (`sample_span_h`)

The coarsening of buckets is *strictly* an anonymity improvement: every band
contains ≥ as many trips as the finest hour bucket it subsumes, so K-anonymity
is preserved or strengthened. No new identity-bearing data is collected.

The producer (`vector-learning`) and consumer (`vector-routing`) each implement
`time_band(epoch_ms)` independently (ADR-0003 forbids cross-repo imports). A
pin test — `TimeBandAgreementTest` in
`vector-routing/tests/test_learned_wiring.py` — asserts both implementations
agree on known timestamps, including epoch (1970-01-01 Thursday) and a Monday
midnight in 2026.

## Consequences

- **Positive:** each `(segment, band)` bucket now accumulates the trips that
  previously scattered across up to 21 hourly buckets, which is the only way a
  bucket can reach K=5 inside a 72 h window. Whether that is *enough* is issue
  `14`'s question.
- **Positive:** ETA accuracy during rush hour is preserved (peak/off-peak still
  distinct).
- **Negative:** Off-peak night driving is coarsened further (6 nightly bands →
  2). This is acceptable: night-time traffic is the least variable period.
- **Negative:** the band definition is duplicated across `vector-learning` and
  `vector-routing` (adr-0003 forbids the import), so `VECTOR_WEEK_UTC_OFFSET_H`
  and `VECTOR_WEEKEND_DAYS` must be set identically in both. A drift silently
  applies the wrong time-of-week's speeds, which is why
  `scripts/verify-evolution-loop.py` sweeps all 168 hours of a week and asserts
  the two agree at every one.
- **Migration:** old `speed_profile` facts keyed by `hour_of_week` are
  incompatible with the new band key and **must be retired explicitly** —
  `FactStore.retire_stale_schema()`, exposed as
  `python -m vector_learning retire-stale-facts`. Retiring rather than re-keying
  is deliberate: a `speed_profile` is a 72 h-window aggregate, so every retired
  fact regenerates within one cycle, whereas re-keying would have to invent which
  band an expired hour belonged to. Rows are marked `superseded`, never deleted,
  so the audit trail of what was once promoted survives.

## Correction (2026-08-04, same day)

Two claims in the first version of this ADR were wrong. Recorded rather than
quietly edited, because both are the kind of mistake that reads as authoritative:

1. **A quantification table asserting that 8 bands took K=5 clearance from 7,172
   to 181,543 of 181,546 segments.** It could not have been measured — there is no
   real trip data — and it was internally incoherent: its "segments clearing K=5"
   and "Coverage" columns are the same quantity by definition yet differed by three
   orders of magnitude, and coverage *fell* as more segments cleared K. Replaced
   above with the reasoning actually used, and an explicit statement that the
   number is unknown until collection runs.
2. **"The 72-hour TTL means all such facts expire naturally, so no explicit
   migration is required."** The TTL governs `traces.db` observations, not this
   store. `facts.db` is durable and append-only by design — that is what S4 *is* —
   and confidence decays at read time without rows ever being deleted. The stale
   facts did not expire: 16 of them stayed `active`, kept being promoted, and were
   silently skipped by the routing overlay, so `metrics_export.coverage()` reported
   15 covered segments while the router could use **none** of them. The metric
   intended to reveal a decorative loop was concealing one.

Guards added so neither can recur silently: `promote()` now rejects a
`speed_profile` whose payload has no valid band (rejections are reported, unlike
inert exports), and `coverage()` counts only usable facts and warns on any that
are not.

3. **The bands were computed in UTC, so they did not describe the traffic they
   named.** Qatar is UTC+3: local 08:00 — peak morning commute — fell into the
   band labelled *"weekday early / overnight"*, sharing a bucket with local 03:00,
   while the band actually named *"AM peak"* covered local 09:00–13:00. Averaging
   a rush-hour crawl with 3 a.m. free-flow yields a speed wrong for both, so the
   coarsening would have made ETAs **worse** than the OSM defaults exactly when
   accuracy matters — defeating the stated reason for choosing 8 bands over 1.
   Independently, the weekend was taken as Saturday–Sunday; **Qatar's weekend is
   Friday–Saturday**, so Friday leisure traffic was filed as a weekday and Sunday
   commuting as weekend.

   Bands are now evaluated in local civil time via `VECTOR_WEEK_UTC_OFFSET_H`
   (default 3) and `VECTOR_WEEKEND_DAYS` (default `4,5`, Monday=0). Verified:
   local Mon 08:00 → band 1 (AM peak), Mon 18:00 → band 3 (PM peak), Mon 03:00 →
   band 0, Fri/Sat noon → band 5 (weekend day), Sun noon → band 2 (weekday
   midday).

   This correction landed while the fact store held **zero** usable
   `speed_profile` facts — the retirement in (2) had just emptied it — so the
   re-keying it implies cost nothing. Had it been found after collection began it
   would have invalidated every learned profile.

## Addendum — endpoint-learning constraints (Status: Proposed)

> Introduced by ADR-0074 (destination-precision learning). **Proposed**; becomes live only when
> ADR-0074 is ratified. It narrows nothing about speed-profile bands — it adds constraints that
> apply *only* to learned endpoint/destination facts.

The 8 time bands defined here remain the temporal key for learned facts. For **endpoint /
destination facts** (parking, entrance, arrival) the bands carry additional constraints:

- **8 time bands remain** — no change to `BANDS_PER_WEEK`, the band definitions, or the local-time
  correction (UTC+3, weekend Fri–Sat). Endpoint facts use the same `band` (0–7) key.
- **Destination facts may require additional temporal diversity** — beyond merely landing in a band,
  the K contributing trips should span multiple distinct days and should not all fall in a single
  band (band diversity). A destination cleared only by "5 arrivals in one afternoon, one band" is
  insufficient: it is easy for one actor to manufacture and it enables mobility-pattern inference at
  sensitive POIs. ADR-0074 §4.4 sets the threshold (N ≥ 3 days + band diversity; value ratified with
  ADR-0074).
- **Endpoint facts are different from ordinary segment speed evidence** — a speed profile averages
  motion *along a road* (low inference value per point); an endpoint fact locates *where a journey
  ended* (high inference value). The same K=5 floor is necessary but not sufficient for endpoints;
  ADR-0074 adds spatial dispersion, temporal dispersion, concentrated-cluster suppression, and a
  contributor-diversity proxy on top of this band key.
- **K = 5 remains the floor** — unchanged and non-configurable below 5, inherited from ADR-0065.
- **Sensitive / low-dispersion patterns may be suppressed** — a destination whose K endpoints form a
  tight cluster, or whose associated POI is in the sensitive set (ADR-0074 §6/§8), may be suppressed
  from precise publication (higher K, stronger dispersion, or non-publication) rather than released
  as a pinpointing centroid.

## Related

- ADR-0065: privacy gate (K, TTL, truncation, accuracy floor)
- ADR-0066: promotion thresholds and tile invalidation
- Issue 11: replace `hour_of_week` with `time_band` (the change this ADR records)
- Issue 10: measure `delta_p50` via the evolution loop
