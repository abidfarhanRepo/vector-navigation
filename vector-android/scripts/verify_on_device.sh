#!/usr/bin/env bash
# Device/emulator verification for the Vector native client.
#
# Everything here is the stuff a unit test cannot answer: does it launch, does
# the map actually render, does GPS drive the puck, does the UI overlap, does it
# crash, and what does a drive cost in frames and memory.
#
#   scripts/verify_on_device.sh            # uses whatever adb device is attached
#
# Works against an emulator or a real handset. GPS injection differs between the
# two and is handled below.
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/toolchain.sh"

PKG=dev.vector.android
ACT="$PKG/.MainActivity"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/../build/device-verify}"
mkdir -p "$OUT"

pass=0; fail=0
ok()   { echo "  PASS  $*"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail+1)); }
note() { echo "        $*"; }

echo "=== 0. device ==="
SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$SERIAL" ]; then
  echo "no adb device. Start the emulator (emulator -avd vector-test &) or attach"
  echo "a handset with USB debugging."
  exit 2
fi
ok "device $SERIAL"
note "$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r') / API $(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
IS_EMU=0; case "$SERIAL" in emulator-*) IS_EMU=1;; esac

echo "=== 1. install ==="
# Pick the APK matching the device's ABI. An x86_64 emulator cannot install an
# arm64 APK, and "head -1" silently picked the wrong one.
ABI="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
APK="$(ls -1 "$HERE"/../app/build/outputs/apk/debug/app-${ABI}-debug.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || APK="$(ls -1 "$HERE"/../app/build/outputs/apk/debug/app-*-debug.apk 2>/dev/null | head -1)"
note "device ABI $ABI"
[ -n "$APK" ] || { echo "no APK built"; exit 2; }
if adb -s "$SERIAL" install -r -g "$APK" >/dev/null 2>&1; then
  ok "installed $(basename "$APK")"
else
  bad "install failed"; exit 1
fi

# -g grants runtime permissions, but grant explicitly too: a denied location
# permission turns every later check into a false negative about navigation.
adb -s "$SERIAL" shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb -s "$SERIAL" shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null

echo "=== 2. launch ==="
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am force-stop $PKG
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null 2>&1
sleep 12
if adb -s "$SERIAL" shell pidof $PKG >/dev/null 2>&1; then
  ok "process alive after launch"
else
  bad "process died on launch"
fi

echo "=== 3. crashes / ANRs ==="
# Filtered for the same reason as step 9's: the full buffer is device-wide data
# from somebody's phone. These are the tags the steps below actually read.
LPID=$(adb -s "$SERIAL" shell pidof $PKG 2>/dev/null | tr -d '\r' | awk '{print $1}')
{
  adb -s "$SERIAL" logcat -d -s "Vector:*" "VectorFrames:*" "Mbgl:*" "MapLibre:*" \
      "GLSurfaceView:*" "libEGL:*" "AndroidRuntime:E" "ActivityManager:E" 2>/dev/null
  [ -n "$LPID" ] && adb -s "$SERIAL" logcat -d --pid="$LPID" 2>/dev/null
} | sort -u > "$OUT/logcat-launch.txt"
CRASH=$(grep -cE "FATAL EXCEPTION|ANR in $PKG|E AndroidRuntime" "$OUT/logcat-launch.txt" || true)
if [ "$CRASH" -eq 0 ]; then ok "no fatal exceptions or ANRs"
else bad "$CRASH crash/ANR lines — see $OUT/logcat-launch.txt"
     grep -A6 "FATAL EXCEPTION" "$OUT/logcat-launch.txt" | head -20; fi

echo "=== 4. the map actually rendered ==="
# MapLibre logs its GL setup; a blank map usually means no GL context or no tiles.
GL=$(grep -ciE "maplibre|GLSurfaceView|EGL" "$OUT/logcat-launch.txt" || true)
[ "$GL" -gt 0 ] && ok "MapLibre/GL activity in logcat ($GL lines)" || note "no MapLibre log lines (may be normal at default log level)"
# Match MapLibre's OWN wording for a failed response, and nothing else.
#
# Two false positives were found here on the first real-device runs, and both
# are worth remembering because they are the same mistake:
#
#   * a bare `Unable to open` matched Samsung's `Unable to open libpenguin.so`,
#     a graphics library with no connection to map tiles;
#   * a loose `4[0-9]{2}` matched the TILE EPOCH in the query string —
#     `?v=1788888451` ends in "451" — so every successful request looked like a
#     4xx.
#
# MapLibre logs a failure as `[HTTP] Request with response = 404: Not Found`, so
# that is what is matched. It also logs `This request was cancelled ... This is
# expected for tiles that were being prefetched`, which is normal and must not
# count. A harness that cries wolf is worse than no harness.
#
# A real 404 IS worth failing on: it is exactly what a style declaring a zoom
# the bake does not contain looks like, and it renders a blank map while
# everything else appears healthy.
TILE_ERR=$(grep -cE "Failed to load tile|response = (4|5)[0-9][0-9]" "$OUT/logcat-launch.txt" || true)
[ "$TILE_ERR" -eq 0 ] && ok "no tile-loading errors" || {
  bad "$TILE_ERR tile-loading errors"
  grep -E "Failed to load tile|response = (4|5)[0-9][0-9]" "$OUT/logcat-launch.txt" | head -4
}

echo "=== 4b. native libraries are 16 KB page-size ready ==="
# Android 15 introduced 16 KB pages. A library with 4 KB-aligned LOAD segments
# raises a system dialog OVER THE MAP on a debuggable build and fails to load
# outright on a release one. Found exactly this way on the S24: MapLibre 11.5.2
# shipped a 4 KB-aligned libmaplibre.so.
if [ -n "$APK" ] && python3 "$HERE/check_16kb.py" "$APK" >"$OUT/16kb.txt" 2>&1; then
  ok "every native library is 16 KB ready"
else
  bad "a native library is not 16 KB ready — see $OUT/16kb.txt"
  grep FAIL "$OUT/16kb.txt" | head -4
fi

echo "=== 5. GPS drives the puck ==="
# Doha: Souq Waqif -> north-west along the Corniche.
inject() { # lon lat
  if [ "$IS_EMU" = "1" ]; then
    adb -s "$SERIAL" emu geo fix "$1" "$2" >/dev/null 2>&1
  else
    # A handset gets its GPS from `scripts/simulate_drive.sh`, not from here.
    #
    # This used to read "a real handset needs a mock-location provider app; skip
    # rather than pretend. Drive it for real instead." — which was true, and was
    # the reason every number this harness produced came from a parked phone,
    # and the reason V3 and V4 both closed with "needs a moving car".
    #
    # V5 built the missing half: `MockDrive` replays a recorded trace into the
    # device's own fused provider, so the app receives real `Location` objects
    # through the production callback. This step stays emulator-only because a
    # twelve-fix straight line is not a drive; `simulate_drive.sh` is.
    return 1
  fi
}
if inject 51.5310 25.2854; then
  for i in $(seq 1 12); do
    lon=$(python3 -c "print(51.5310 + $i*0.0012)")
    lat=$(python3 -c "print(25.2854 + $i*0.0009)")
    inject "$lon" "$lat"; sleep 1
  done
  ok "injected 13 GPS fixes"
else
  note "SKIP: this step is emulator-only. On a handset use:"
  note "      bash scripts/simulate_drive.sh scenario-a-city"
fi

echo "=== 5a. visual regression: explore ==="
# The cheap half of §31: assert that what must be on screen IS, by reading the
# accessibility tree rather than by comparing pixels.
#
# Pixel diffs were considered and rejected for this harness. The map is live
# data over a live network, so two runs never produce identical pixels, and a
# tolerance loose enough to survive that is loose enough to miss a blank map.
# What has ACTUALLY broken silently in this codebase is structural — a blank
# map (V3: nothing baked below z11), route alternatives drawn nowhere (V3),
# labels rendering nothing (V4: a font stack the server could not serve), a
# doubled vehicle marker (V4) — and a UI tree plus the GL check in step 4
# catches that class.
#
# Phase-aware on purpose. The first version of this ran after step 5b and
# asserted EXPLORE's controls while the app was NAVIGATING, so it failed on
# controls that are correctly absent. A regression check that does not know
# which screen it is looking at is a coin toss.
dump_tree() {
  adb -s "$SERIAL" shell uiautomator dump /sdcard/vector-ui.xml >/dev/null 2>&1
  adb -s "$SERIAL" shell cat /sdcard/vector-ui.xml 2>/dev/null | tr -d '\r'
}
# Scroll a sheet until a label is on screen, then hand back that tree.
#
# `uiautomator dump` answers about what is DISPLAYED, so a row below the fold is
# indistinguishable from a row that does not exist. Vector's settings sheet puts
# VECTOR PRO under MAP, NAVIGATION and VOICE, which is far enough down that the
# Pro stage read "no paid tier offered" off a build that had one.
scroll_to_label() {   # scroll_to_label <needle> [tries]
  local needle="$1" tries="${2:-8}" i tree
  for i in $(seq 1 "$tries"); do
    tree="$(dump_tree)"
    if echo "$tree" | grep -qF "$needle"; then printf '%s' "$tree"; return 0; fi
    adb -s "$SERIAL" shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*30/100)) 300
    sleep 1
  done
  printf '%s' "$(dump_tree)"
  return 1
}
want() {  # want <tree> <needle> <description>
  if echo "$1" | grep -qF "$2"; then ok "$3"; else bad "$3 (looked for \"$2\")"; fi
}
W=$(adb -s "$SERIAL" shell wm size | grep -oP '\d+x\d+' | head -1 | cut -dx -f1)
H=$(adb -s "$SERIAL" shell wm size | grep -oP '\d+x\d+' | head -1 | cut -dx -f2)
: "${W:=1080}" "${H:=2340}"
tap() { adb -s "$SERIAL" shell input tap "$1" "$2"; }

# Tap what you MEAN, not where you guess.
#
# This harness drove the destination flow with taps at hardcoded screen
# fractions, and V5 found what that costs: one of them had toggled the map to
# NORTH_UP. That is a legitimate driver preference, so the app was correctly
# drawing a flat north-up map — and every camera observation this harness made,
# across V4 and V5, was being taken under a setting nobody had chosen. A blind
# tap does not fail, it hits something else.
#
# So: find the node by its label, read its bounds out of the accessibility
# tree, and tap the centre. Returns non-zero when the label is not on screen,
# which is a better outcome than pressing whatever is at 83% by 93%.
# The tree goes through a FILE, not through stdin.
#
# It went through stdin, and could not: `printf '%s' "$xml" | python3 - "$want"
# <<PYEOF` gives python two candidates for stdin and the heredoc wins, so the
# interpreter read the program from the heredoc and `sys.stdin.read()` then
# returned the empty string. Every lookup missed, every call returned 1, and
# the harness fell back to the hardcoded fractions this function exists to
# replace -- silently, because "could not find X on screen" is exactly what a
# genuinely absent control looks like. It reported that for the search bar and
# the Start control on every run while tapping them blind by fraction anyway.
#
# The stage with no fallback is where it finally showed: 5a-pro has no fraction
# to guess at for a settings gear, so the Pro checks simply never ran.
tap_label() {   # tap_label <label> [description]
  local want="$1" what="${2:-$1}" xf="$OUT/.tap-tree.xml" cx cy
  adb -s "$SERIAL" shell uiautomator dump /sdcard/vector-tap.xml >/dev/null 2>&1
  adb -s "$SERIAL" shell cat /sdcard/vector-tap.xml 2>/dev/null | tr -d '\r' > "$xf"
  read -r cx cy < <(python3 - "$xf" "$want" <<'PYEOF'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want = sys.argv[2]
# Every node carrying the label either as text or as a content description.
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    text = re.search(r'text="([^"]*)"', tag)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    hay = (text.group(1) if text else "") + "\x00" + (desc.group(1) if desc else "")
    if want not in hay:
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not b:
        continue
    x1, y1, x2, y2 = (int(g) for g in b.groups())
    if x2 <= x1 or y2 <= y1:
        continue
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PYEOF
)
  if [ -n "${cx:-}" ] && [ -n "${cy:-}" ]; then
    adb -s "$SERIAL" shell input tap "$cx" "$cy"
    note "tapped \"$what\" at $cx,$cy"
    return 0
  fi
  note "could not find \"$what\" on screen"
  return 1
}

TREE="$(dump_tree)"
if [ -z "$TREE" ]; then
  note "SKIP: uiautomator dump produced nothing"
else
  echo "$TREE" > "$OUT/ui-explore.xml"
  # Without this the app has no way to choose a destination at all, which is
  # the state the native client shipped in before V1.
  want "$TREE" "Where to?" "explore offers a search affordance"
  want "$TREE" "Settings"   "settings reachable before driving"
fi

echo "=== 5a-pro. Vector Pro (entitlement + paywall) ==="
# Runs BEFORE the drive, for the same reason 5a does: the paywall is reached
# through Settings, and Settings is an EXPLORE control. The first version of
# this stage ran after 5b and pressed BACK looking for a settings gear that is
# correctly absent while navigating -- so it could never pass, and the BACK
# presses it made to go looking backed the app out to the launcher, which left
# 7c asserting the navigating HUD against the Android home screen. Four
# failures, none of them the app's.
# The one thing no unit test can answer: whether the RevenueCat dashboard on
# the other end is actually configured. `ProAccessTest` covers every status
# resolution and `ProUiTest` every screen, offline and deterministically.
# Neither can tell you that an offering exists, that the entitlement is spelled
# `vector_pro`, or that the public key compiled into this binary belongs to this app.
# Those are facts about a service, and this is the only place they get checked.
#
# A build with NO key is a legitimate release -- see `revenueCatKey` in
# app/build.gradle.kts. `ProStatus.UNCONFIGURED` unlocks every feature and
# compiles no paywall, which is correct for the self-hosted case. That is
# reported as a NOTE and never as a FAIL: failing it would push whoever runs
# this toward pasting a key in to get a green run, which is precisely how a
# self-hosted build acquires a paywall nobody can buy from.

PRO_LOG="$OUT/logcat-pro.txt"
adb -s "$SERIAL" logcat -d -s "VectorPro:*" 2>/dev/null | tr -d '\r' > "$PRO_LOG"

# ProEntitlement warns on exactly four paths, all through `warn()`. A failure
# here is a real configuration fault and is worth naming individually, because
# each one points at a different thing being wrong on the dashboard.
for what in configure refresh offerings purchase restore; do
  if grep -q "pro/$what failed" "$PRO_LOG"; then
    bad "RevenueCat $what failed on device (see $(basename "$PRO_LOG"))"
    note "$(grep -m1 "pro/$what failed" "$PRO_LOG")"
  fi
done

# `vector_pro` is written only by `ProEntitlement.adopt`, which runs only on a
# real CustomerInfo. Its presence is proof the SDK reached RevenueCat and was
# answered; its absence means it never was. run-as needs a debuggable build,
# so a release APK legitimately yields nothing here.
PRO_PREFS="$(adb -s "$SERIAL" shell run-as $PKG cat /data/data/$PKG/shared_prefs/vector_pro.xml 2>/dev/null | tr -d '\r')"
if echo "$PRO_PREFS" | grep -q "ent_checked_at"; then
  ok "RevenueCat answered and the entitlement was cached"
  echo "$PRO_PREFS" > "$OUT/pro-prefs.xml"
  if echo "$PRO_PREFS" | grep -q 'name="ent_active" value="true"'; then
    note "this device holds an ACTIVE entitlement"
  else
    note "this device is on the free tier"
  fi
else
  note "no cached entitlement (release build, no key, or RevenueCat never answered)"
fi

# The settings entry is the honest detector for "a paid tier exists in this
# build AND this driver has not bought it": `ProAccess.offersPro` is true only
# for ProStatus.FREE with a non-empty catalogue, so UNCONFIGURED and PRO both
# correctly render nothing here.
#
# No BACK press before this. There was one, from when this stage ran mid-drive
# and had to leave the HUD first; from EXPLORE the same key backgrounds the app
# and the gear is then not on any screen this harness can see.
if tap_label "Settings" "open settings" >/dev/null 2>&1; then
  sleep 1
  SETTREE="$(scroll_to_label "VECTOR PRO")"
  if echo "$SETTREE" | grep -qF "Vector Pro"; then
    ok "the paid tier is offered in settings"
    echo "$SETTREE" > "$OUT/ui-settings-pro.xml"

    # "See what Pro includes" is the row that opens the paywall. "Vector Pro"
    # matches the section header and the pitch sentence above it, neither of
    # which is a control, so tapping that label lands on static text.
    if tap_label "See what Pro includes" "open the paywall" >/dev/null 2>&1; then
      sleep 1
      PAYTREE="$(dump_tree)"
      echo "$PAYTREE" > "$OUT/ui-paywall.xml"

      # Every feature in the shipped catalogue must be on the paywall. These
      # titles are the ones ProUiTest pins by name; if the catalogue grows,
      # both places are edited in the same commit.
      want "$PAYTREE" "Cooler walking routes" "paywall sells the cooler walking route"
      want "$PAYTREE" "The walk after the car" "paywall sells the walk after the car"
      want "$PAYTREE" "Restore" "restore is reachable"

      # A price proves the OFFERING resolved -- the entitlement being spelled
      # right is necessary but not sufficient, because an app with a correct
      # entitlement and no current offering renders a paywall with nothing to
      # buy. `ProOffer.isEmpty` is the in-app expression of the same fact.
      if echo "$PAYTREE" | grep -qE 'text="[^"]*[0-9]+[.,][0-9]{2}'; then
        ok "a purchasable package resolved from the current offering"
      else
        bad "paywall rendered but no price -- check the CURRENT offering in RevenueCat"
        note "an entitlement named 'vector_pro' with no offering attached looks exactly like this"
        note "on a test_ key the SDK says so outright -- grep logcat for 'no Test Store"
        note "products registered'"
      fi
    else
      note "SKIP: could not open the paywall from settings"
    fi
    adb -s "$SERIAL" shell input keyevent 4 >/dev/null 2>&1
  else
    note "no paid tier offered: this build has no key, or this device already has Pro"
    note "check the build log line 'Vector Pro:' from verifyReleaseConfiguration"
  fi
  adb -s "$SERIAL" shell input keyevent 4 >/dev/null 2>&1
else
  note "SKIP: could not reach settings"
fi

# Back to the foreground however the taps above ended. 5b drives from EXPLORE
# and every stage after it reads a screen, so a stage that navigates the UI
# owes the next one a known state -- `am start` without force-stop resumes the
# existing task rather than restarting it.
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null 2>&1
sleep 2

echo "=== 5b. the destination flow, driven ==="
# The harness reinstalls and relaunches, so the app is in EXPLORE and the frame
# meter has nothing to report — it only measures while NAVIGATING, because
# frame timings from a stationary map are the easiest possible case and quoting
# them would be the unevidenced performance claim §29 warns about.
#
# So drive the flow: search, pick the first result, start. On a handset the
# vehicle then does not move, which is stated in the report rather than hidden;
# what this DOES exercise is a route drawn, a camera following, a HUD
# recomposing and the map compositing, which is most of the per-frame cost.
SEARCH_Q="${SEARCH_Q:-villaggio}"
tap_label "Where to?" "the search bar" || tap $((W/2)) $((H*8/100))
sleep 2
adb -s "$SERIAL" shell input text "$SEARCH_Q"; sleep 3
tap $((W*28/100)) $((H*16/100)); sleep 8           # the first result
NAV_READY=0
if adb -s "$SERIAL" shell uiautomator dump /sdcard/vector-ui.xml >/dev/null 2>&1 \
   && adb -s "$SERIAL" shell cat /sdcard/vector-ui.xml 2>/dev/null | grep -q "Start"; then
  ok "routes offered for \"$SEARCH_Q\""
  # By label, not by position. The blind version of this tap is what flipped a
  # real driver preference; see tap_label.
  tap_label "Start" "the Start control" || tap $((W*83/100)) $((H*93/100))
  sleep 4
  if adb -s "$SERIAL" shell uiautomator dump /sdcard/vector-ui.xml >/dev/null 2>&1 \
     && adb -s "$SERIAL" shell cat /sdcard/vector-ui.xml 2>/dev/null | grep -q "km left"; then
    ok "navigation started"
    NAV_READY=1
  else
    bad "Start did not enter navigation"
  fi
else
  bad "no routes offered for \"$SEARCH_Q\" (is the stack reachable? adb reverse tcp:9003)"
fi
# Let the frame meter fill its window: 600 samples is 5 s at 120 Hz, and it
# reports every 5 s.
[ "$NAV_READY" = "1" ] && sleep 14

echo "=== 6. screenshots (UI overlap check) ==="
adb -s "$SERIAL" shell screencap -p /sdcard/vector-1.png 2>/dev/null
adb -s "$SERIAL" pull /sdcard/vector-1.png "$OUT/screen-explore.png" >/dev/null 2>&1 \
  && ok "captured $OUT/screen-explore.png" || bad "screencap failed"

echo "=== 7. frame timing ==="
# gfxinfo is reported but NOT asserted, and this is the important part.
#
# `dumpsys gfxinfo` measures the frames the Android VIEW HIERARCHY drew.
# MapLibre renders onto its own SurfaceView, composited by SurfaceFlinger
# outside that hierarchy — so gfxinfo faithfully reports the timings of
# Vector's Compose overlays and says NOTHING about the map. V3 recorded this as
# release blocker 2, because ADR-0075's 120 Hz premise had been checked with an
# instrument that cannot see the thing being claimed.
#
# A green jank figure here is evidence that some text was cheap to lay out. It
# is kept because the overlays are worth measuring — the HUD recomposes on
# every frame of a drive — but it is labelled for what it is.
adb -s "$SERIAL" shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
sleep 8
adb -s "$SERIAL" shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>/dev/null
TOTAL=$(grep -oP 'Total frames rendered: \K[0-9]+' "$OUT/gfxinfo.txt" | head -1)
JANKY=$(grep -oP 'Janky frames: \K[0-9]+' "$OUT/gfxinfo.txt" | head -1)
if [ -n "${TOTAL:-}" ] && [ "${TOTAL:-0}" -gt 0 ]; then
  PCT=$(python3 -c "print(round(100*${JANKY:-0}/$TOTAL,1))")
  note "COMPOSE OVERLAYS ONLY: frames=$TOTAL janky=${JANKY:-0} (${PCT}%)"
  note "this does not measure the map — see step 7b"
else
  note "no gfxinfo frame stats (the overlay may not have drawn yet)"
fi

echo "=== 7b. frame timing that can actually see the map ==="
# Two instruments, because neither alone is enough.
#
# (a) The app measures its own Choreographer cadence and logs it — see
#     dev.vector.geo.FrameMeter and MainActivity.reportFramesOccasionally.
#     That is the rate the display is presenting and the rate at which Vector
#     gets to move the vehicle. Only emitted while NAVIGATING, deliberately:
#     frame timings from a stationary map are the easiest possible case.
#
# (b) SurfaceFlinger's per-layer timings, which CAN see a SurfaceView. Awkward
#     — the layer name moves between Android versions and the buffer holds only
#     the last 127 frames — so it is a corroborating snapshot, not the primary.
#
# Neither proves MapLibre's GL work finished inside the budget. Together they
# are what an honest claim needs, and §29's instruction is not to say "120 Hz"
# without evidence.
adb -s "$SERIAL" logcat -d -s VectorFrames > "$OUT/frames-app.txt" 2>/dev/null
APPLINE=$(grep -oP 'hz=\K[0-9.]+' "$OUT/frames-app.txt" | tail -1)
if [ -n "${APPLINE:-}" ]; then
  LAST=$(grep 'hz=' "$OUT/frames-app.txt" | tail -1)
  note "app-measured: $LAST"
  LATEPCT=$(echo "$LAST" | grep -oP 'late=[0-9]+\(\K[0-9.]+')
  if [ "$(python3 -c "print(1 if ${LATEPCT:-100} < 10 else 0)")" = "1" ]; then
    ok "display cadence ${APPLINE} Hz, ${LATEPCT}% late frames"
  else
    bad "${LATEPCT}% of frames late at ${APPLINE} Hz"
  fi
else
  note "SKIP: no frame report yet — the app only measures while NAVIGATING."
  note "      Start a route (and on a handset, drive it) then re-run."
fi

# SurfaceFlinger, best effort.
#
# The layer name is version-dependent AND there are several layers per
# SurfaceView — the first match was "Background for ... SurfaceView", which is
# the opaque backing layer and never presents a frame. So try every candidate
# and keep the first that actually has timings, rather than trusting a name.
SF_DONE=0
adb -s "$SERIAL" shell dumpsys SurfaceFlinger --list 2>/dev/null | tr -d '\r' \
  | grep -i "$PKG" | grep -vi "background" > "$OUT/sf-layers.txt" || true
if [ -s "$OUT/sf-layers.txt" ]; then
  while IFS= read -r cand; do
    [ -z "$cand" ] && continue
    adb -s "$SERIAL" shell dumpsys SurfaceFlinger --latency "'$cand'" \
        > "$OUT/sf-latency.txt" 2>/dev/null
    RESULT=$(python3 - "$OUT/sf-latency.txt" <<'PYEOF'
import sys
try:
    lines = [l.split() for l in open(sys.argv[1]) if l.strip()]
except OSError:
    sys.exit(0)
if not lines:
    sys.exit(0)
try:
    period = int(lines[0][0])
except (ValueError, IndexError):
    sys.exit(0)
present = []
for parts in lines[1:]:
    if len(parts) < 3:
        continue
    try:
        actual = int(parts[1])
    except ValueError:
        continue
    if actual in (0, (1 << 63) - 1):
        continue
    present.append(actual)
if len(present) < 20:
    sys.exit(0)
present.sort()
deltas = sorted(b - a for a, b in zip(present, present[1:]) if 0 < b - a < 250_000_000)
if not deltas:
    sys.exit(0)
mid = deltas[len(deltas) // 2] / 1e6
p95 = deltas[int(len(deltas) * 0.95)] / 1e6
print("layer declares %.2f ms (%.0f Hz); presented p50 %.2f ms (%.1f Hz), "
      "p95 %.2f ms, over %d frames"
      % (period / 1e6, 1e9 / period, mid, 1000.0 / mid, p95, len(deltas)))
PYEOF
)
    if [ -n "$RESULT" ]; then
      note "SurfaceFlinger: $RESULT"
      note "  layer: $(echo "$cand" | cut -c1-90)"
      ok "SurfaceFlinger corroborates the app's own measurement"
      SF_DONE=1
      break
    fi
  done < "$OUT/sf-layers.txt"
fi
if [ "$SF_DONE" = "0" ]; then
  note "SKIP: no SurfaceFlinger layer for $PKG had buffered timings."
  note "      The buffer holds only the last 127 frames and is cleared on"
  note "      resume, so this is a corroborating snapshot, not the primary"
  note "      instrument. The app's own measurement above is."
fi

echo "=== 7c. visual regression: navigating ==="
# The navigating HUD's non-negotiables. Each of these has been absent at some
# point in this client's history, and none of them would fail a unit test:
#
#   * the instruction band — an off-route banner counts, since a parked handset
#     legitimately has no maneuver to give;
#   * the road the vehicle is on (V4; the readout did not exist before, then
#     shipped rendering the word "null");
#   * remaining distance, which is the trip bar existing at all;
#   * the speed unit, which is the speedometer existing at all;
#   * a way OUT of navigation, which was a bare red text button and is now a
#     control.
if [ "${NAV_READY:-0}" != "1" ]; then
  note "SKIP: navigation was not reached, so there is no HUD to check"
else
  TREE="$(dump_tree)"
  if [ -z "$TREE" ]; then
    note "SKIP: uiautomator dump produced nothing"
  elif ! echo "$TREE" | grep -qF "$PKG"; then
    # `uiautomator dump` answers about whatever is on the display, so an app
    # that has been backgrounded yields the launcher's tree and every assertion
    # below fails for a reason that has nothing to do with the HUD. Say the
    # actual fault once instead of four times.
    echo "$TREE" > "$OUT/ui-navigating.xml"
    bad "$PKG is not the foreground app — nothing here is about the HUD"
    note "the tree in $(basename "$OUT")/ui-navigating.xml belongs to something else"
  else
    echo "$TREE" > "$OUT/ui-navigating.xml"
    if echo "$TREE" | grep -qE "Off route|Rerouting|Turn|Continue|Head|Exit |Keep |Arrive"; then
      ok "an instruction band is on screen"
    else
      bad "no instruction band in the HUD"
    fi
    want "$TREE" "km left"        "remaining distance is shown"
    want "$TREE" "km/h"           "the speedometer is shown"
    want "$TREE" "End navigation" "navigation can be ended"
    # The road-you-are-on readout, and the org.json null trap that shipped in
    # it. "null · " on screen is the exact defect a screenshot caught.
    if echo "$TREE" | grep -qF "null · "; then
      bad "the road readout is showing the word \"null\" (org.json optString trap)"
    else
      ok "no \"null\" leaking into the road readout"
    fi
  fi
fi

echo "=== 8. memory ==="
adb -s "$SERIAL" shell dumpsys meminfo $PKG > "$OUT/meminfo.txt" 2>/dev/null
PSS=$(grep -oP 'TOTAL PSS:\s+\K[0-9]+' "$OUT/meminfo.txt" | head -1)
[ -z "$PSS" ] && PSS=$(grep -oP 'TOTAL\s+\K[0-9]+' "$OUT/meminfo.txt" | head -1)
if [ -n "${PSS:-}" ]; then
  MB=$((PSS/1024))
  note "PSS ${MB} MB"
  [ "$MB" -lt 600 ] && ok "memory within budget" || bad "PSS ${MB} MB is high"
else
  note "no meminfo"
fi

echo "=== 9. still alive after the drive ==="
if adb -s "$SERIAL" shell pidof $PKG >/dev/null 2>&1; then ok "process survived"
else bad "process died during the drive"; fi
# Filtered, not the whole buffer.
#
# `adb logcat -d` dumps the entire system log, which on a real handset is
# device-wide: sensor streams, location providers, and every other app that
# happens to be running. That is 2.4 MB of somebody's phone, most of it nothing
# to do with Vector, and it does not belong in an artefacts directory that gets
# read, copied and attached to reports.
#
# Keep the app's own PID, Vector's tags, and the crash/ANR lines this step
# actually asserts on.
VPID=$(adb -s "$SERIAL" shell pidof $PKG 2>/dev/null | tr -d '\r' | awk '{print $1}')
{
  adb -s "$SERIAL" logcat -d -s "Vector:*" "VectorFrames:*" "Mbgl:*" "MapLibre:*" \
      "AndroidRuntime:E" "ActivityManager:E" 2>/dev/null
  [ -n "$VPID" ] && adb -s "$SERIAL" logcat -d --pid="$VPID" 2>/dev/null
} | sort -u > "$OUT/logcat-vector.txt"
LATE=$(grep -cE "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-vector.txt" || true)
[ "$LATE" -eq 0 ] && ok "no crashes during the drive" || bad "$LATE crash(es) during the drive"

echo
echo "=== $pass passed, $fail failed — artefacts in $OUT ==="
[ "$fail" -eq 0 ]
