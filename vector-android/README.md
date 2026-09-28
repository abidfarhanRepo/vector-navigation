# vector-android — native Android client

Kotlin + MapLibre Native. **Not** a WebView, not a Capacitor shell. See
[ADR-0075](../vector-governance/adr/adr-0075-native-android-map-client.md) for
why, including the measurements that made the case.

It consumes the existing Vector service contracts unchanged — `/tiles`,
`/glyphs`, `/navigate`, `/search` — so the whole backend is shared with the web
client and nothing here forks the map data.

## Layout

| module | what it is | tested by |
|---|---|---|
| `core-geo` | Pure-JVM navigation maths: projection onto route segments, along-route distance, dead reckoning. No Android types. | `./gradlew :core-geo:test` — 27 tests, ~1 s, no device |
| `app` | MapLibre `MapView`, Compose chrome, fused location, the frame loop. | on-device |

`core-geo` is separate on purpose. The web client's entire navigation surface was
asserted by grepping `index.html` as text, which is how an off-route threshold
wrong by **31×** shipped next to a comment stating the correct value. The maths
that decides whether the app reroutes now runs in a test.

## Build

The toolchain lives outside the system package manager (Fedora 44 ships only
OpenJDK 25, which Gradle 8.14 rejects):

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # 21 LTS; Gradle 8.14 rejects 25
export ANDROID_HOME=~/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Both are already exported from `~/.bashrc`.

```bash
./gradlew :core-geo:test                       # the maths, no device needed
./gradlew :app:assembleDebug \
  -PvectorBase=http://192.168.10.14:9003 \
  -PvectorToken="$(grep '^VECTOR_WEB_TOKEN=' ../.env | cut -d= -f2)"
```

`vectorBase` / `vectorToken` are build properties, not committed source, so the
backend can be repointed without touching a file. Output is split per ABI:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (~22 MB) — every modern phone
- `app-armeabi-v7a-debug.apk` — older 32-bit devices

A universal APK is 52 MB because MapLibre ships a renderer per ABI; splitting is
why it is not.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb logcat -s Vector:V MapLibre:V AndroidRuntime:E
```

## Talking to a LAN backend

`network_security_config.xml` permits cleartext to loopback and private-range
hosts only; everything else must be HTTPS.

This matters more than it looks. A **native** app has no secure-context rule, so
plain HTTP to a self-hosted box on the LAN works and real GPS still works. The
Capacitor build could not do that — in a WebView, `http://192.168.x.x` is an
insecure origin, so `navigator.geolocation` and service-worker registration are
both unavailable, and testing navigation needed an `adb reverse` tunnel to
`localhost` just to obtain a secure context. Pointing this app at your own box
over HTTP is the product working as intended, not a debug hack.

## What is deliberately not here yet

- **iOS.** Out of scope; needs its own ADR.
- **Offline routing.** Tiles can be cached; the graph cannot, yet.
- **Background location.** Excluded by ADR-0075's compliance section.
- **Collection / trace upload.** The privacy invariants (200 m truncation, k=5)
  are server-side and MUST stay that way; a client path that bypasses them is
  forbidden, so this lands only once the server contract is wired.
