# ADR-0070 — A native shell for background capture (supersedes the collection half of ADR-0063)

- **Status:** Proposed
- **Date:** 2026-08-05
- **Deciders:** D3 Architecture / D2 Web-frontend / D5 Security & Compliance / D2 Product
- **Supersedes:** [adr-0063](adr-0063-web-pwa-first.md) — **in part only**: the
  deferral of native clients, and only insofar as it applies to *trace capture*.
  ADR-0063 remains in force for map display and distribution.
- **Superseded by:** none

## Context

ADR-0063 deferred native Android/iOS clients in favour of an installable PWA. It
was right, and it is still right about what it was actually deciding: MapLibre in
a browser is a genuinely sufficient map client, the PWA needs no new runtime
dependency, and no app-store submission stands between a user and the map.

It is wrong about capture, for a reason that has nothing to do with rendering.

**The web cannot record a drive.** Neither iOS Safari nor Android Chrome delivers
geolocation to a backgrounded tab, there is no Background Geolocation API on the
web, and none is on any announced roadmap. Recording therefore requires the phone
awake, unlocked and on that tab for the whole journey. Ticket `20` raises the
ceiling of that arrangement — a screen wake lock, an IndexedDB queue, honest gap
detection — and cannot pass it. Ticket `19` (ADR-0069) steps around it by letting
an app that *does* hold background permission do the recording and importing the
file afterwards.

Both are real answers and neither is the answer a navigation product needs:
recording that continues with the phone in a pocket and the screen off. That
requires a process the OS keeps alive, which is precisely what browsers withhold.

ADR-0063 evaluated "Web + Capacitor wrap" and deferred it as "marginal gain over a
pure PWA" — assessed against *displaying* a map, where that judgement was correct.
Against *recording* one, the gain is the entire capability. This is a reversal on
new grounds, not a re-litigation, and governance requires it be a superseding ADR
rather than a pivot buried in a commit.

## Decision 1 — a native shell exists, for capture only

A thin native application wraps the existing web app and adds a real
background-geolocation capability. Its scope is fenced, deliberately and
narrowly:

- **The PWA remains the map client.** ADR-0063's single-origin contract
  (`/route`, `/navigate`, `/traffic`, `/tiles`, `/glyphs`, `/search`) is untouched
  and the shell consumes it unchanged.
- **The shell adds exactly three things**: background location capture, a
  foreground-service notification while recording, and the permission explainer
  that capture requires. Nothing else.
- **This is not licence to rebuild the UI natively.** A thin wrapper that grows
  native screens becomes a second front end nobody maintains, and the map would
  then drift between two clients. Any native view beyond record controls and the
  permission flow needs its own ADR.

## Decision 2 — Capacitor, hosting the live origin rather than a bundled copy

**Capacitor**, because background geolocation is a mature plugin rather than
something to write, and because it hosts the existing `index.html` — so the trip
token logic, the consent sheet and the client-edge gate rules stay in one
implementation. ADR-0065's "one shared definition" principle applies to the client
edge as much as the server: a native reimplementation of trip tokens is a second
definition, and second definitions drift.

**The shell must load the app shell from the deployment's own origin, not from
assets baked into the APK.** This is a binding constraint, and it comes from a
defect discovered while designing ticket `24`:

> Ticket `24` closes the stale-client window by having an out-of-date client
> *reload itself* when the server refuses its upload. A sideloaded APK with a
> bundled shell cannot heal that way. It would become the permanent version of
> exactly the problem `24` fixes — a client that cannot be updated by the server
> it uploads to, refused forever or, worse, uploading under a superseded ingest
> contract.

So either the WebView loads the live origin (preferred: the server can always
update its own client), or the shell implements a version handshake that refuses
to capture when its ingest-contract version is behind the server's, and says so.
A bundled shell with neither is not acceptable.

## Decision 3 — Android first, sideloaded, and the reason is honest

- **Android**: an APK can be sideloaded or self-hosted with no store review at
  all, which matches a self-hosted product. Google Play requires a written
  justification for `ACCESS_BACKGROUND_LOCATION` and reviews it; avoiding that
  review is a real reduction in scope, not a shortcut.
- **iOS is sequenced second, not treated as equivalent.** Private distribution
  needs a paid developer account and TestFlight even for one user. That is a
  recurring cost and a gatekeeper, and pretending the two platforms are symmetric
  would hide it.

Concrete platform obligations, named so they are not discovered late:

- A **foreground service** with a persistent notification while recording. Android
  requires it, and it is also the honest thing: a visible, tap-to-stop indication
  that location is being collected.
- Android 14+ requires the `FOREGROUND_SERVICE_LOCATION` permission and a declared
  service type; Android 10+ requires the separate "Allow all the time" grant with
  its own dialog, which users decline by default.
- Doze and vendor battery optimisation will kill a poorly-configured service.
  Whether an exemption is requested is a UX decision to be made explicitly, not a
  flag flipped when recording turns out to be unreliable.

## Decision 4 — a declined permission falls back to tier 2, visibly

Background location is the hard part of this tier and it is a UX problem, not a
code problem. If the "all the time" grant is declined, the shell **falls back to
foreground capture with the tier-2 rules and says which mode it is in.** It must
never present a recording control that silently under-delivers — the failure this
whole effort exists to remove is data that looks legitimate and is not.

## Decision 5 — provenance is derived where it can be and declared where it cannot

Ticket `22` requires that provenance be set by the server and "never from a client
claim". Ticket `21` requires that the native shell post the same `POST /traces`
contract so "the server must not learn that a new client exists". **Both cannot
hold.** The server can distinguish an import (it parsed the file) but has no way to
distinguish a native shell from a browser on the same endpoint without being told.

Resolved:

- **`import` is derived.** It is a property of the route that parsed the file, and
  the server knows it for certain.
- **`live` versus `native` is a client declaration**, validated against the closed
  enum and recorded as **declared**, not verified. Metrics name it accordingly, so
  no one reads a debugging aid as a security-grade fact.
- The contract is unchanged by this: a declared source field is not a new
  endpoint and not a new shape. Ticket `21`'s intent — one ingest contract, no
  special-casing per client — survives; only its wording about the server learning
  nothing is amended.
- Provenance must **never** grow into a client id, device string, or app version.
  Three constants, asserted in test. Anything richer is the per-device identifier
  ADR-0065 rejected, arriving through a side door.

Provenance exists to answer "which tier produced this?" when a learned speed
looks wrong, and to roll one tier back. It is not a trust boundary. The gate is
the trust boundary, and it applies identically to all three tiers.

## Decision 6 — acceptance requires a battery number and an update path

This ADR stays **Proposed** until both exist. Ticket `21` is blocked on
acceptance, deliberately: no wrapper work starts before the reversal is agreed.

1. **A measured battery cost** over a real commute on a real device, reported as a
   number. "Background location drains the battery" is the objection this tier will
   face, and a measurement is the only useful answer. If the number is bad, the
   tier is worth less than tier 1 and this ADR should be rejected on that basis.
2. **A distribution and update story before the first APK ships**, per Decision 2.
   A sideloaded app with no update path *is* the stale client.

## Consequences

- **Positive:** the only tier that actually solves the stated problem — collection
  that happens without anyone thinking about it, phone in a pocket, screen off.
- **Positive:** the ingest contract, the gate and the K floor are unchanged, which
  is exactly why a new client is safe to add. ADR-0065's server-side re-application
  of every rule means the shell needs no privileged trust.
- **Negative:** an install is friction, and a sideloaded APK is friction plus a
  security warning. Tier 1 (ADR-0069) needs neither and stays the recommended
  path for most contributors.
- **Negative:** a native build toolchain, a signing key, and a second release
  process — the operational cost ADR-0063 correctly wanted to avoid, now accepted
  for a capability rather than for parity.
- **Negative:** iOS is not served by this decision and its cost is unresolved.
- **Risk, named:** wrapper scope creep into a second front end. Decision 1 is the
  fence; a native view beyond the recording controls needs its own ADR.
- **Governance:** ADR-0063 is *not* deleted, *not* rewritten, and remains Accepted
  for map display and distribution. On acceptance of this ADR its index entry gains
  a partial-supersession note. This mirrors the handling of ADR-0060, which was
  retained as Superseded rather than removed after a working session deleted it —
  the governance violation ADR-0063 itself records.

## Alternatives considered

- **Do nothing; rely on tiers 1 and 2.** The honest fallback, and it is what the
  next few weeks look like regardless. Rejected as an endpoint: import depends on
  the driver remembering to export a file, and tier 2 depends on a screen staying
  on. Neither becomes ambient collection.
- **Keep waiting for the web platform.** Rejected: there is no Background
  Geolocation specification to wait for. Periodic Background Sync does not deliver
  positions, and a service worker is not woken for location.
- **A minimal purpose-built recorder with no WebView** (Kotlin, foreground service,
  posts to `/traces`, no UI beyond start/stop). Genuinely attractive — smaller,
  and structurally incapable of the UI drift Decision 1 fences against. Rejected
  because it must reimplement trip tokens, the consent sheet and the client-edge
  gate rules natively, creating the second definition ADR-0065 forbids. Worth
  revisiting if the Capacitor shell proves heavy.
- **Reinstate Flutter (ADR-0060).** Rejected: highest cross-platform reuse and it
  replaces the map client too, which is the scope explosion Decision 1 exists to
  prevent. ADR-0063's reasoning against it stands untouched.
- **Store distribution via Google Play.** Deferred: background-location review is
  real work for a self-hosted product with no store presence to gain.

## References

- ADR-0063 — web-first PWA (superseded in part, for capture only)
- ADR-0065 — privacy gate; the server re-applies every rule, which is why a new
  client is safe
- ADR-0068 — one pseudonym per trip; the shell must supply a trip token like any
  other client
- ADR-0069 — track import (tier 1), and the tier this one does not replace
- `.scratch/vector-collect/GOALS.md`, tickets `20`, `21`, `22`, `24`
- `.scratch/vector-collect/DECISIONS.md`
