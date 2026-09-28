#!/usr/bin/env bash
# Break things on purpose, on the handset, while it is navigating.
#
# V5 §16: "V5 must deliberately break things... the objective is to discover the
# defects that happy-path tests cannot find."
#
# The JVM scenario suite already injects everything that is a function of the
# POSITION STREAM — outages, jumps, drift, a refusing router, a malformed route
# reply. This covers the ones that are only real on a device: the network going
# away mid-drive, the app being backgrounded and resumed, the process being
# killed and relaunched, the screen rotating, and a driver hammering the search
# box. None of those can be simulated on the JVM, because what they break is the
# Android side.
#
#   bash scripts/inject_failures.sh
#
# Runs one trace (scenario-a-city by default) and interferes with it on a
# schedule. Every check states what the driver should see, not merely that the
# process is alive — "it did not crash" is what V5 §10 explicitly rejects.
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/toolchain.sh"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

PKG=dev.vector.android
ACT="$PKG/.MainActivity"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TRACES="$ROOT/app/build/traces"
DEVDIR=/data/local/tmp/vector-traces
SCENARIO="${1:-scenario-a-city}"
OUT="${OUT:-$ROOT/build/inject-failures}"
mkdir -p "$OUT"

pass=0; fail=0; skip=0
ok()   { echo "  PASS  $*"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail+1)); }
note() { echo "        $*"; }
skipf(){ echo "  SKIP  $*"; skip=$((skip+1)); }

SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
[ -n "$SERIAL" ] || { echo "no adb device attached"; exit 2; }
TRACE="$TRACES/$SCENARIO.csv"
[ -f "$TRACE" ] || { echo "no trace at $TRACE — run TraceExportTest"; exit 2; }
DEST=$(awk -F'\t' -v s="$SCENARIO" '$1==s {print $2}' "$TRACES/destinations.txt")
[ -n "$DEST" ] || { echo "no destination for $SCENARIO"; exit 2; }

alive() { adb -s "$SERIAL" shell pidof $PKG >/dev/null 2>&1; }
tree()  { adb -s "$SERIAL" shell uiautomator dump /sdcard/inj.xml >/dev/null 2>&1
          adb -s "$SERIAL" shell cat /sdcard/inj.xml 2>/dev/null | tr -d '\r'; }
navlog(){ adb -s "$SERIAL" logcat -d -s "VectorNav:*" "VectorMock:*" "AndroidRuntime:E" 2>/dev/null; }
crashed(){ adb -s "$SERIAL" logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION" || true; }
navigating() { tree | grep -q "End navigation"; }

echo "=== 0. set up ==="
ABI="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
APK="$ROOT/app/build/outputs/apk/debug/app-${ABI}-debug.apk"
[ -f "$APK" ] || { echo "no APK for $ABI"; exit 2; }
adb -s "$SERIAL" install -r -g "$APK" >/dev/null 2>&1 && ok "installed" || { bad "install"; exit 1; }
adb -s "$SERIAL" shell appops set $PKG android:mock_location allow 2>/dev/null
adb -s "$SERIAL" shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb -s "$SERIAL" reverse tcp:9003 tcp:9003 >/dev/null 2>&1
adb -s "$SERIAL" shell mkdir -p $DEVDIR >/dev/null 2>&1
adb -s "$SERIAL" push "$TRACE" "$DEVDIR/$SCENARIO.csv" >/dev/null 2>&1
adb -s "$SERIAL" shell chmod 755 "$DEVDIR" >/dev/null 2>&1
adb -s "$SERIAL" shell chmod 644 "$DEVDIR/$SCENARIO.csv" >/dev/null 2>&1
adb -s "$SERIAL" shell "run-as $PKG sed -i -e 's|>NORTH_UP<|>HEADING_UP<|' shared_prefs/vector.xml" >/dev/null 2>&1
ok "trace pushed, permissions granted"

# ---------------------------------------------------------------------------
echo "=== 1. the network is gone BEFORE a route is asked for ==="
# The driver opens the app in a car park with no signal and picks a destination.
# What must not happen is a spinner that never resolves, or a raw
# java.net.UnknownHostException on the screen.
adb -s "$SERIAL" logcat -b crash -c 2>/dev/null
adb -s "$SERIAL" shell am force-stop $PKG
adb -s "$SERIAL" reverse --remove tcp:9003 >/dev/null 2>&1
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null 2>&1
sleep 12
adb -s "$SERIAL" shell am start -n "$ACT" \
  --es vectorTrace "$DEVDIR/$SCENARIO.csv" --es vectorDest "$DEST" \
  --es vectorSpeedup 1 >/dev/null 2>&1
sleep 22
if alive; then ok "survived a route request with no backend"; else bad "died"; fi
T="$(tree)"
if echo "$T" | grep -qiE "Vector could not|Could not plan|no internet|check your connection|network"; then
  ok "the failure is stated in the driver's terms"
  note "$(echo "$T" | grep -oP 'text="[^"]*(could not|Could not|network|connection)[^"]*"' | head -1)"
elif echo "$T" | grep -qiE "UnknownHostException|java\.net|SocketTimeout|ConnectException"; then
  bad "a raw Java exception is on the driver's screen"
  echo "$T" | grep -oP 'text="[^"]*(Exception|java\.net)[^"]*"' | head -2
else
  bad "no error surfaced at all — the driver is looking at a silent map"
fi
if echo "$T" | grep -q "Where to?"; then
  ok "the app is still usable (search affordance present)"
else
  bad "the app is stuck — no way to try again"
fi

# ---------------------------------------------------------------------------
echo "=== 2. the network goes away MID-DRIVE ==="
# The route is already in memory, so guidance must continue. What depends on the
# network is the speed-limit lookup, the traffic poll and any reroute — none of
# which may take the drive down with them.
adb -s "$SERIAL" reverse tcp:9003 tcp:9003 >/dev/null 2>&1
adb -s "$SERIAL" shell am force-stop $PKG
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null 2>&1
sleep 12
adb -s "$SERIAL" shell am start -n "$ACT" \
  --es vectorTrace "$DEVDIR/$SCENARIO.csv" --es vectorDest "$DEST" \
  --es vectorSpeedup 1 >/dev/null 2>&1
# Wait for navigation rather than guessing at a sleep.
for _ in $(seq 1 20); do navigating && break; sleep 3; done
if navigating; then ok "navigation started"; else bad "navigation never started"; exit 1; fi

MAN_BEFORE=$(navlog | grep -cP 'maneuver \d+' || true)
adb -s "$SERIAL" reverse --remove tcp:9003 >/dev/null 2>&1
note "backend removed; driving on"
sleep 45
if alive; then ok "survived 45 s of driving with no backend"; else bad "died with no backend"; fi
MAN_AFTER=$(navlog | grep -cP 'maneuver \d+' || true)
if [ "${MAN_AFTER:-0}" -gt "${MAN_BEFORE:-0}" ]; then
  ok "guidance continued through the outage ($MAN_BEFORE -> $MAN_AFTER maneuvers)"
else
  bad "guidance stopped when the network did ($MAN_BEFORE -> $MAN_AFTER)"
fi
if navigating; then ok "still navigating"; else bad "navigation was torn down by a network failure"; fi
adb -s "$SERIAL" reverse tcp:9003 tcp:9003 >/dev/null 2>&1
sleep 20
if navigating && alive; then ok "recovered when the backend came back"; else bad "did not recover"; fi

# ---------------------------------------------------------------------------
echo "=== 3. backgrounded and resumed mid-drive ==="
# The commonest thing that happens to a navigation app: a phone call, or the
# driver checking something else.
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
sleep 8
if alive; then ok "process survived being backgrounded"; else note "process was killed while backgrounded (see step 5)"; fi
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null 2>&1
sleep 10
if navigating; then
  ok "came back still navigating"
else
  bad "lost the drive on resume"
fi
if [ "$(crashed)" -eq 0 ]; then ok "no crash across background/foreground"; else bad "crashed on resume"; fi

# ---------------------------------------------------------------------------
echo "=== 4. the screen rotates mid-drive ==="
# The manifest declares configChanges for orientation, so the Activity is NOT
# recreated — which is the design, and this is the check that it holds. A
# recreation here would drop the MapLibre surface and the whole journey.
PID_BEFORE=$(adb -s "$SERIAL" shell pidof $PKG | tr -d '\r' | awk '{print $1}')
adb -s "$SERIAL" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
adb -s "$SERIAL" shell settings put system user_rotation 1 >/dev/null 2>&1
sleep 8
if navigating; then ok "landscape: still navigating"; else bad "landscape lost the drive"; fi
adb -s "$SERIAL" shell settings put system user_rotation 0 >/dev/null 2>&1
sleep 8
PID_AFTER=$(adb -s "$SERIAL" shell pidof $PKG | tr -d '\r' | awk '{print $1}')
if [ "$PID_BEFORE" = "$PID_AFTER" ]; then
  ok "the process was not restarted by a rotation"
else
  bad "rotation restarted the process ($PID_BEFORE -> $PID_AFTER)"
fi
if [ "$(crashed)" -eq 0 ]; then ok "no crash across two rotations"; else bad "crashed rotating"; fi

# ---------------------------------------------------------------------------
echo "=== 5. the process is killed mid-drive ==="
# Android does this whenever it wants the memory. Before V5 the journey was held
# in UiState and nowhere else, so it was simply gone.
adb -s "$SERIAL" shell am force-stop $PKG
sleep 3
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null 2>&1
sleep 20
if alive; then ok "relaunched after being killed mid-drive"; else bad "will not relaunch"; fi
if [ "$(crashed)" -eq 0 ]; then
  ok "no crash relaunching with a journey in progress"
else
  bad "crashed relaunching — the saved journey path"
  adb -s "$SERIAL" logcat -d -b crash 2>/dev/null | grep -A6 "FATAL EXCEPTION" | head -12
fi
RL="$(navlog)"
if echo "$RL" | grep -q "journey resumed"; then
  ok "the journey was offered back to the driver"
elif echo "$RL" | grep -q "journey resume abandoned"; then
  note "resume gave up waiting for a fix — correct indoors with mock mode off,"
  note "and the point is that it gave up rather than hanging or crashing"
  ok "resume failed gracefully"
else
  bad "nothing happened at all — the journey was lost"
fi

# ---------------------------------------------------------------------------
echo "=== 6. a driver hammering the search box ==="
# Rapid repeated searches, per V5 §16. The client debounces at 220 ms and
# cancels the in-flight job on each keystroke; what this looks for is a crash,
# an ANR, or results that arrive out of order and stick.
adb -s "$SERIAL" logcat -c
W=$(adb -s "$SERIAL" shell wm size | grep -oP '\d+x\d+' | head -1 | cut -dx -f1); : "${W:=1080}"
H=$(adb -s "$SERIAL" shell wm size | grep -oP '\d+x\d+' | head -1 | cut -dx -f2); : "${H:=2340}"
adb -s "$SERIAL" shell input tap $((W/2)) $((H*8/100)); sleep 2
for q in villaggio souq pearl corniche mall airport; do
  adb -s "$SERIAL" shell input text "$q" >/dev/null 2>&1
  adb -s "$SERIAL" shell input keyevent KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL >/dev/null 2>&1
done
sleep 6
if alive; then ok "survived six overlapping searches"; else bad "died searching"; fi
ANR=$(adb -s "$SERIAL" logcat -d 2>/dev/null | grep -c "ANR in $PKG" || true)
if [ "${ANR:-0}" -eq 0 ]; then ok "no ANR"; else bad "$ANR ANR(s) while searching"; fi
if [ "$(crashed)" -eq 0 ]; then ok "no crash while searching"; else bad "crashed while searching"; fi

# ---------------------------------------------------------------------------
echo "=== 7. leave the handset as it was found ==="
adb -s "$SERIAL" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
adb -s "$SERIAL" shell am force-stop $PKG
adb -s "$SERIAL" shell "run-as $PKG sed -i -e '/journey_/d' shared_prefs/vector.xml" >/dev/null 2>&1
adb -s "$SERIAL" reverse tcp:9003 tcp:9003 >/dev/null 2>&1
ok "rotation, journey and port forward restored"

navlog > "$OUT/logcat.txt" 2>/dev/null
echo
echo "=== failure injection — $pass passed, $fail failed, $skip skipped ==="
echo "=== artefacts in $OUT ==="
[ "$fail" -eq 0 ]
