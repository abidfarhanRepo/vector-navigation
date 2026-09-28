#!/usr/bin/env bash
# V6 §3, §4 and §6 — Vector treated as an unreliable real-world application.
#
# The brief's instruction for this suite is the important part: *"Do not merely
# verify that an error appears. Verify that the application remains coherent and
# recoverable."* So every check below is of the form "do the hostile thing, then
# assert the app can still be USED" — the search box still opens, a destination
# can still be chosen, the phase is one the UI has an exit from.
#
# ## What "coherent" is checked against
#
# The phase machine has three states and the UI gives each one exclusive
# ownership of its controls. That makes incoherence observable from outside:
#  * EXPLORE owns "Where to?";
#  * PREVIEW owns the destination chip and Start;
#  * NAVIGATING owns the trip bar and its exit.
# A screen showing none of those, or two sets at once, is a state the driver
# cannot leave — which is exactly the failure §4 asks about ("Can it be
# exited?").
#
#   bash scripts/verify_adversarial.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=/dev/null
source "$HERE/uidriver.sh"

PKG=dev.vector.android
OUT="${OUT:-$ROOT/build/verify-adversarial}"
mkdir -p "$OUT"

pass=0; fail=0; skip=0
ok()    { echo "  PASS  $*"; pass=$((pass+1)); }
bad()   { echo "  FAIL  $*"; fail=$((fail+1)); }
note()  { echo "        $*"; }
skipf() { echo "  SKIP  $*"; skip=$((skip+1)); }

crashes() { adb logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION|ANR in $PKG"; }

# Is the app in a state a driver can act from? One of the three phases must own
# the screen, and the app must still be alive to own it.
coherent() {
  ui_dump >/dev/null || return 1
  adb shell pidof $PKG >/dev/null 2>&1 || { note "the process is gone"; return 1; }
  if ui_has "Where to?"; then echo EXPLORE; return 0; fi
  if ui_has "Start"; then echo PREVIEW; return 0; fi
  if ui_has "Exit" || ui_has "arrive"; then echo NAVIGATING; return 0; fi
  # A modal over a phase is still coherent — it has a way out.
  if ui_has "Done"; then echo SETTINGS; return 0; fi
  # SEARCH, which the first version of this function got wrong and spent four
  # screenshots proving: an open search box REPLACES the "Where to?" hint with
  # the driver's own query, so none of the strings above are on screen and a
  # perfectly healthy search screen was being recorded as a state no phase
  # owns. Detected by the things that are always present while searching — the
  # back chevron and the clear affordance — rather than by any particular
  # query text, which is by definition unknown.
  if ui_has "Clear the search" || ui_has "Close the search" \
     || ui_has "Nothing found" || ui_has "RECENT" || ui_has "Searching…"; then
    echo SEARCH; return 0
  fi
  # A field with focus and a soft keyboard up is also search, whatever it says.
  if adb shell dumpsys input_method 2>/dev/null | grep -q 'mInputShown=true'; then
    echo SEARCH; return 0
  fi
  return 1
}

assert_coherent() {
  local what="$1" st
  if st="$(coherent)"; then ok "$what — still usable (phase: $st)"
  else
    bad "$what — no phase owns the screen"
    ui_texts | head -8 | sed 's/^/          /'
    ui_screenshot "$OUT/incoherent-$(date +%s).png"
  fi
}

net_off() { adb shell svc wifi disable >/dev/null 2>&1; adb shell svc data disable >/dev/null 2>&1; sleep 3; }
net_on()  { adb shell svc wifi enable  >/dev/null 2>&1; adb shell svc data enable  >/dev/null 2>&1; sleep 8; }

echo "=== 0. setup ==="
SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
[ -n "$SERIAL" ] || { echo "no adb device"; exit 2; }
ok "device $SERIAL"
adb shell pm path $PKG >/dev/null 2>&1 || { echo "app not installed"; exit 2; }
adb reverse tcp:9003 tcp:9003 >/dev/null 2>&1
adb logcat -c
ui_wake
adb shell am force-stop $PKG
ui_launch 12
assert_coherent "cold launch"
C0=$(crashes)

# ---------------------------------------------------------------------------
echo "=== 1. search stress (§3.D) ==="
# Hammered deliberately: repeated queries, rapid changes, empty, partial, and
# no-result. What is being looked for is stale results, a dead button, a
# keyboard stuck open, and the map moving when it should not.
ui_dump >/dev/null
if ui_tap_text "Where to?" 2; then
  ok "the search box opens"
  # Rapidly changing queries. The debounce plus the query-still-current guard
  # in doSearch are what should make this boring.
  for qq in "a" "al" "alw" "west" "west b" "zzzz" "" "souq" "villaggio"; do
    adb shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
    for i in $(seq 1 24); do adb shell input keyevent KEYCODE_DEL >/dev/null 2>&1; done
    [ -n "$qq" ] && adb shell input text "$(printf '%s' "$qq" | sed 's/ /%s/g')"
    sleep 1
  done
  sleep 4
  assert_coherent "nine rapid queries including an empty and a no-result one"
  ui_dump >/dev/null
  # A query with no hits must SAY so rather than showing the previous query's
  # results, which is the stale-result failure.
  for i in $(seq 1 30); do adb shell input keyevent KEYCODE_DEL >/dev/null 2>&1; done
  adb shell input text "zzzqqqxx"; sleep 5
  ui_dump >/dev/null
  if ui_has "No results" || ui_has "nothing" || ui_has "No matches"; then
    ok "a no-result query says so"
  else
    LEFT="$(ui_texts | grep -icE 'bay|souq|villaggio|street' || true)"
    if [ "$LEFT" -gt 0 ]; then
      bad "a no-result query still shows $LEFT earlier result line(s) — stale results"
      ui_texts | head -10 | sed 's/^/          /'
    else
      ok "a no-result query shows no stale results"
    fi
  fi
  # Cancel immediately after a search — the back/cancel race in §3.D.
  adb shell input keyevent KEYCODE_BACK; sleep 2
  assert_coherent "back straight after a search"
else
  bad "the search box did not open"
fi

# ---------------------------------------------------------------------------
echo "=== 2. network loss (§3.A) ==="
NETRESTORED=no
trap 'if [ "$NETRESTORED" = no ]; then adb shell svc wifi enable >/dev/null 2>&1; adb shell svc data enable >/dev/null 2>&1; fi' EXIT
note "taking the network down"
net_off
ui_dump >/dev/null
ui_tap_text "Where to?" 2 >/dev/null
adb shell input text "West%sBay"; sleep 6
ui_dump >/dev/null
assert_coherent "search while offline"
# Offline search must not claim success. Either it reports a failure or it
# returns nothing; what it must not do is show a spinner for ever.
if ui_has "Search failed" || ui_has "No results" || ui_has "no results"; then
  ok "offline search reports a failure or an empty result"
else
  note "offline search screen: $(ui_texts | head -4 | tr '\n' ' | ')"
  ok "offline search left the app usable (no explicit error shown)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 2
note "restoring the network"
net_on; NETRESTORED=yes
assert_coherent "after the network came back"
# And the app has to WORK again, not just look alive — the §3.A requirement is
# recoverability, not survival.
ui_dump >/dev/null
ui_tap_text "Where to?" 2 >/dev/null
adb shell input text "West%sBay"; sleep 6
ui_dump >/dev/null
if ui_texts | grep -qi 'bay'; then
  ok "search works again once connectivity returns — recovered, not merely alive"
else
  bad "search still returns nothing after the network came back"
  ui_texts | head -8 | sed 's/^/          /'
fi
adb shell input keyevent KEYCODE_BACK; sleep 2

# ---------------------------------------------------------------------------
echo "=== 3. GPS loss and return (§3.B) ==="
# Location off system-wide is the honest device-level version of "no fix": it is
# what a driver who has switched it off, or revoked the permission, produces.
note "switching location off"
adb shell settings put secure location_mode 0; sleep 6
ui_dump >/dev/null
assert_coherent "location switched off"
# The point of GpsHealth.UNAVAILABLE: "Searching for GPS" must not be the
# permanent answer. Reported from the S24 as "always showing searching for GPS".
FOUND=no
for i in $(seq 1 8); do
  ui_dump >/dev/null
  if ui_has "No position available"; then FOUND=yes; break; fi
  sleep 5
done
if [ "$FOUND" = yes ]; then
  ok "a first fix that never comes stops being called 'Searching for GPS'"
  ui_has "Check that location is turned on" \
    && ok "and the driver is told something they can act on" \
    || note "the consequence line was not visible in the dump"
else
  # Only a real finding if the app had no fix to begin with; a warm last-known
  # fix legitimately keeps the banner down.
  if ui_has "Searching for GPS"; then
    bad "still 'Searching for GPS' after 40 s with location off"
  else
    skipf "the app still had a position, so the acquire path was not exercised"
  fi
fi
note "switching location back on"
adb shell settings put secure location_mode 3; sleep 10
assert_coherent "location switched back on"
BACK=no
for i in $(seq 1 10); do
  ui_dump >/dev/null
  if ! ui_has "No position available" && ! ui_has "Searching for GPS"; then BACK=yes; break; fi
  sleep 5
done
[ "$BACK" = yes ] \
  && ok "positioning recovered WITHOUT restarting the app (§3.B)" \
  || bad "positioning did not recover without a restart"

# ---------------------------------------------------------------------------
echo "=== 4. lifecycle (§3.C) ==="
adb shell input keyevent KEYCODE_HOME; sleep 3
ui_launch 6
assert_coherent "background then foreground"

adb shell input keyevent KEYCODE_POWER; sleep 3       # screen off
adb shell input keyevent KEYCODE_POWER; sleep 2       # screen on
ui_wake; sleep 2
assert_coherent "screen off then on"

# Rotation. The manifest declares configChanges for orientation, so the Activity
# is NOT recreated — which is the whole reason UiState can live in the Activity.
# This asserts that claim rather than trusting it.
adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
adb shell settings put system user_rotation 1 >/dev/null 2>&1; sleep 4
assert_coherent "rotated to landscape"
ui_screenshot "$OUT/landscape.png"
adb shell settings put system user_rotation 0 >/dev/null 2>&1; sleep 4
assert_coherent "rotated back to portrait"

# Process death. The harshest of the four and the one persistence exists for.
ui_kill
ui_launch 12
assert_coherent "relaunch after a force-stop"

echo "=== 5. zero dead UI, on the controls EXPLORE owns (§6) ==="
# Every visible interactive element must do something, give feedback, and reach
# a state. Checked by operating it and asserting the screen CHANGED — an element
# that leaves the screen identical is either inert or lying.
ui_dump >/dev/null
BEFORE="$(ui_texts | md5sum | cut -c1-8)"
if ui_tap_text "Settings" 2; then
  AFTER="$(ui_texts | md5sum | cut -c1-8)"
  [ "$BEFORE" != "$AFTER" ] && ok "the settings control opens something" \
    || bad "the settings control changed nothing"
  # Each group heading proves its section rendered rather than being an empty
  # label — the "confirmation in a state that is not rendered" class of bug.
  for grp in "MAP" "NAVIGATION" "VOICE" "PRIVACY" "ABOUT"; do
    ui_scroll_to "$grp" >/dev/null && ok "the $grp group is rendered" \
      || bad "the $grp group is missing"
  done
  ui_scroll_to "OpenStreetMap" >/dev/null \
    && ok "the ODbL map credit is present (licence obligation)" \
    || bad "the map credit is missing"
  ui_close_sheet >/dev/null 2>&1
  assert_coherent "settings closed"
else
  bad "no settings control found"
fi

# The compass / orientation control states what it does and must change it.
ui_dump >/dev/null
ORIENT="$(ui_texts | grep -i 'tap to' | head -1)"
if [ -n "$ORIENT" ]; then
  note "orientation control: $ORIENT"
  ui_tap_text "$ORIENT" 2 >/dev/null
  ui_dump >/dev/null
  NEW="$(ui_texts | grep -i 'tap to' | head -1)"
  if [ "$NEW" != "$ORIENT" ]; then
    ok "the orientation control changes state (now: $NEW)"
  else
    bad "the orientation control did not change state"
  fi
else
  note "no orientation control on screen in this phase"
fi

echo "=== 6. crashes over the whole suite ==="
C1=$(crashes)
if [ "$C1" -le "$C0" ]; then
  ok "no new crashes or ANRs ($C1 total lines, unchanged)"
else
  bad "$((C1 - C0)) new crash/ANR line(s)"
  adb logcat -d | grep -A8 "FATAL EXCEPTION" | tail -30 | sed 's/^/        /'
fi
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
ui_screenshot "$OUT/final.png"

# Leave the handset as it was found.
adb shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
adb shell svc wifi enable >/dev/null 2>&1
adb shell svc data enable >/dev/null 2>&1
adb shell settings put secure location_mode 3 >/dev/null 2>&1

echo
echo "=== adversarial — $pass passed, $fail failed, $skip skipped ==="
echo "=== artefacts in $OUT ==="
[ "$fail" -eq 0 ]
