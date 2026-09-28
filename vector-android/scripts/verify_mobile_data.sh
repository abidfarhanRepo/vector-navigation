#!/usr/bin/env bash
# Can a user who was HANDED this app use it away from home, on mobile data?
#
# This is the question the LAN and loopback builds cannot answer, and it is the
# one that decides whether the release candidate is shippable to somebody else.
# Everything else in the V6 suite runs against `adb reverse`, which is a USB
# cable pretending to be the internet — a perfectly good instrument for
# measuring navigation, and no evidence at all about reachability.
#
# So: Wi-Fi OFF, mobile data ON, `adb reverse` REMOVED, and a signed release
# build whose `API_BASE` is a real public origin. Then the ordinary product
# flow — a map, a search, a route — with nothing on the phone's side but the
# cellular network.
#
# ## Why the reverse tunnel has to be torn down explicitly
#
# `adb reverse` survives an app reinstall and a reboot of the app, and it is
# bound to the USB connection rather than to Wi-Fi. Leaving it in place would
# let a build configured for `127.0.0.1` keep working with the Wi-Fi off, and
# the run would report a pass that means nothing. `adb reverse --remove-all` is
# therefore load-bearing, and the script asserts the loopback route is gone
# before it believes anything else.
#
#   APK=build/rc/vector-rc-public.apk bash scripts/verify_mobile_data.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=/dev/null
source "$HERE/uidriver.sh"

PKG=dev.vector.android
APK="${APK:-$ROOT/build/rc/vector-rc-public.apk}"
OUT="${OUT:-$ROOT/build/verify-mobile-data}"
mkdir -p "$OUT"

pass=0; fail=0; skip=0
ok()    { echo "  PASS  $*"; pass=$((pass+1)); }
bad()   { echo "  FAIL  $*"; fail=$((fail+1)); }
note()  { echo "        $*"; }
skipf() { echo "  SKIP  $*"; skip=$((skip+1)); }

# Whatever the handset's mobile-data setting was, put it back. This test turns
# it on and pulls a basemap over it, which spends someone's allowance — a few
# MB, but not this script's to spend permanently.
DATA_WAS="$(adb shell settings get global mobile_data 2>/dev/null | tr -d '\r')"

restore() {
  note "restoring Wi-Fi and the mobile-data setting (was: ${DATA_WAS:-unknown})"
  adb shell svc wifi enable >/dev/null 2>&1
  if [ "$DATA_WAS" = "0" ]; then adb shell svc data disable >/dev/null 2>&1; fi
  sleep 6
  adb reverse tcp:9003 tcp:9003 >/dev/null 2>&1
}
trap restore EXIT

echo "=== 0. the binary under test ==="
[ -f "$APK" ] || { echo "no APK at $APK"; exit 2; }
note "apk $(basename "$APK") $(sha256sum "$APK" | cut -c1-16)"
BASE="$(strings "$APK" 2>/dev/null | grep -oE 'https?://[a-zA-Z0-9._-]+(:[0-9]+)?' \
  | grep -viE 'schemas|android|google|w3\.org|apache|maplibre|openstreetmap|github|json|kotlin' \
  | sort -u | head -3 | tr '\n' ' ')"
note "origins compiled in: ${BASE:-none found}"
adb devices | grep -q device$ || { echo "no adb device"; exit 2; }

echo "=== 1. install it the way a user would ==="
# Different signature from the debug build, so the debug install has to go.
adb uninstall $PKG >/dev/null 2>&1
if adb install -r "$APK" >/dev/null 2>&1; then
  ok "the signed release APK installed"
else
  bad "the release APK would not install"; exit 1
fi
# Permissions the way a user grants them: at the prompt. `pm grant` stands in
# for the tap, because the dialog is a system window this harness cannot drive.
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
ok "location permission granted (standing in for the runtime prompt)"

echo "=== 2. take away everything except the cellular network ==="
adb reverse --remove-all >/dev/null 2>&1
# Counted by MATCHING a rule, not by counting lines. `adb reverse --list` emits
# a trailing blank line, so `wc -l` returns 1 for an empty list and 2 for one
# rule — a check written as `-eq 0` can therefore never pass, which is what the
# first run of this script proved. It failed SAFE (refusing to report a
# meaningless pass rather than proceeding), which is the right direction for a
# guard to be wrong in, but it was still wrong.
REV="$(adb reverse --list 2>/dev/null | grep -c 'tcp:')"
[ "$REV" -eq 0 ] && ok "adb reverse removed — no loopback route to the host" \
  || { bad "adb reverse is still up ($REV rule(s)); the result would be meaningless"; exit 1; }
adb shell svc wifi disable >/dev/null 2>&1
adb shell svc data enable >/dev/null 2>&1
sleep 10
WIFI="$(adb shell dumpsys wifi 2>/dev/null | grep -oE 'Wi-Fi is (enabled|disabled)' | head -1)"
note "${WIFI:-wifi state unknown}"
# The phone's own view of what it is connected to.
NET="$(adb shell dumpsys connectivity 2>/dev/null | grep -oE 'type: (MOBILE|WIFI)[^,]*' | head -2 | tr '\n' ' ')"
note "active transport: ${NET:-unknown}"
if echo "$WIFI" | grep -q disabled; then
  ok "Wi-Fi is off"
else
  skipf "could not confirm Wi-Fi is off — treat the result below with suspicion"
fi

echo "=== 3. launch, and see whether the world is reachable ==="
adb logcat -c
ui_wake
adb shell am force-stop $PKG
ui_launch 18
adb shell pidof $PKG >/dev/null 2>&1 && ok "the app is running" || { bad "the app died"; exit 1; }
ui_dump >/dev/null
ui_screenshot "$OUT/mobile-launch.png"

# Tiles are the honest test of reachability: they are fetched by MapLibre over
# the same origin and they are the one thing that cannot be faked from cache on
# a fresh install.
sleep 6
TILES_OK=$(adb logcat -d 2>/dev/null | grep -c 'Request was successful')
TILES_FAIL=$(adb logcat -d 2>/dev/null | grep -ciE 'Mbgl-HttpRequest.*(failed|error)' || true)
note "tile requests: $TILES_OK successful, $TILES_FAIL failed"
if [ "$TILES_OK" -gt 0 ]; then
  ok "the basemap loaded over mobile data ($TILES_OK successful tile/style requests)"
else
  bad "no successful tile request over mobile data"
  adb logcat -d | grep -iE 'Mbgl-HttpRequest' | tail -6 | sed 's/^/          /'
fi

# And the map has to be DRAWN, not merely requested. A blank basemap with a
# happy HTTP log is the failure mode a self-hosted style is most prone to.
RGB="$(ui_map_rgb)"
note "map colour: ${RGB:-unreadable}"
if [ -n "$RGB" ]; then
  VAR=$(python3 -c "
r,g,b='$RGB'.split()
v=[int(r),int(g),int(b)]
print(1 if (max(v)-min(v))>2 or not (0<=max(v)<=8) else 0)")
  [ "$VAR" = 1 ] && ok "the map is rendering actual cartography, not a blank ground" \
    || bad "the map area is a flat near-black — the style or the tiles did not load"
fi

echo "=== 4. the product flow, on mobile data ==="
FIXED=no
for i in $(seq 1 8); do
  ui_dump >/dev/null
  if ! ui_has "Searching for GPS" && ! ui_has "No position available"; then FIXED=yes; break; fi
  sleep 5
done
[ "$FIXED" = yes ] && ok "the handset acquired a position" \
  || note "no fix yet; the search step will report what it can"

ui_dump >/dev/null
if ui_tap_text "Where to?" 2; then
  ok "the search box opens"
  adb shell input text "West%sBay"
  sleep 8
  ui_dump >/dev/null
  ui_screenshot "$OUT/mobile-search.png"
  HIT="$(ui_texts | grep -i 'bay' | grep -viE 'where to' | head -1)"
  if [ -n "$HIT" ]; then
    ok "geocoding works over mobile data — hit: $HIT"
    ui_tap_text "$HIT" 3 >/dev/null
    sleep 10
    ui_dump >/dev/null
    ui_screenshot "$OUT/mobile-preview.png"
    if ui_has "Start"; then
      ok "a route was planned over mobile data — PREVIEW reached with a Start control"
      ETA="$(ui_texts | grep -iE 'min|km' | head -2 | tr '\n' ' ')"
      note "route card: ${ETA:-not read}"
    else
      bad "no route was planned"
      ui_texts | head -8 | sed 's/^/          /'
    fi
  else
    bad "search returned nothing over mobile data"
    ui_texts | head -8 | sed 's/^/          /'
  fi
else
  bad "the search box did not open"
fi

echo "=== 5. crashes ==="
C=$(adb logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION|ANR in $PKG")
[ "$C" -eq 0 ] && ok "no crashes or ANRs" || { bad "$C crash/ANR line(s)"; \
  adb logcat -d | grep -A8 'FATAL EXCEPTION' | tail -20 | sed 's/^/        /'; }
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null

echo
echo "=== mobile data — $pass passed, $fail failed, $skip skipped ==="
echo "=== artefacts in $OUT ==="
[ "$fail" -eq 0 ]
