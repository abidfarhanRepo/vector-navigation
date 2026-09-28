# Vector — Privacy Policy

**Last updated: 2026-09-20**

Vector is a navigation app for Qatar. This policy describes what it does with
your information. It is short because Vector collects very little.

## The short version

Vector has no user accounts, no analytics, no advertising, and no third-party
trackers. It does not build a profile of you. Nobody at Vector can see where you
have been, because that information is never sent to us.

## What stays on your device

- **Your location.** Vector reads your device's location to show where you are,
  follow a route, and tell you when to turn. Your location is used on the device
  and is not stored on any server we operate.
- **Your searches and recent destinations.** Saved locally so the app can offer
  them again. You can clear them at any time in Settings.
- **Your drive history.** Kept on the device so the app can show you your own
  past trips. You can delete it in Settings.
- **Your Vector Pro status.** Cached on the device so the app knows you have
  paid without asking the network every time it opens.

## What is sent to a server, and which server

Vector is **self-hosted**. The app talks to a Vector backend, and which one is
compiled into the build you installed. When you search for a place or ask for a
route, the app sends the query and the relevant coordinates to that backend so
it can answer.

That backend keeps operational logs, as any web server does. It does not
associate requests with an identity, because Vector has no identities: there is
no account, no login, and no persistent user id.

If you run your own Vector backend, this traffic goes to your own machine and
nowhere else. That is the point of the project.

## Purchases

Vector Pro is a subscription managed through RevenueCat. When you buy or
restore, RevenueCat and the underlying store process the purchase.
**Vector never sees your payment details.** RevenueCat receives an anonymous
identifier generated on your device; it is not linked to your name, your email,
or anywhere you have driven.

- RevenueCat's privacy policy: https://www.revenuecat.com/privacy

## Permissions, and why each one exists

| permission | why |
|---|---|
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | to show where you are and follow a route |
| `INTERNET` | to reach the Vector backend for search, routes and map tiles |
| `ACCESS_NETWORK_STATE` | to tell "offline" apart from "the server is down" |
| `androidx.car.app.*` | Android Auto navigation templates |

**Vector does not request background location.** It reads your location while
you are using it.

## The one third-party SDK that touches location

Vector uses Google's `play-services-location` for the fused location provider —
the standard Android mechanism for getting an accurate position. It supplies
fixes to the app; it is not an analytics or advertising component. Vector ships
no Firebase, no Crashlytics, and no ad SDK, and the app's complete dependency
list is short enough to read in the build file.

## What Vector does NOT do

- No advertising, and no advertising identifiers.
- No analytics or crash-reporting SDKs.
- No selling or sharing of personal information. There is nothing to sell.
- No background location collection. Vector reads your location while you are
  using it to navigate.
- No social features, no contacts access, no photo access.

## Children

Vector is not directed at children and does not knowingly collect information
from them.

## Your choices

- Clear searches, recent destinations and drive history in **Settings**.
- Revoke location permission at any time in Android settings. Vector will still
  open; it simply cannot follow you.
- Uninstalling removes everything Vector stored on your device.

## Changes

If this policy changes, the date at the top changes with it.

## Contact

Questions about this policy: open an issue on the project repository.
