#!/usr/bin/env bash
# V6 §16 — the persistence regression gate, on the handset.
#
# The requirement is one sentence: *the user changes something, the application
# dies, the user comes back later, and Vector remembers.*
#
# ## Why `am force-stop` is the right gesture
#
# It is the closest thing adb offers to the OS reclaiming a process: no
# onPause, no onStop, and — the point — no `QueuedWork.waitToFinish()`, the
# framework hook that flushes a pending `SharedPreferences.apply()`. So a
# force-stop is what tells an `apply()` apart from a `commit()`, and V6 §14 asks
# about exactly that: "app killed immediately after changing a setting".
#
# Every store in this app used `apply()` before V6. Within one process that is
# indistinguishable from `commit()` — the in-memory map is updated either way —
# which is why four releases of round-trip tests never caught it, and why every
# check below reaches for the FILE and for the RENDERED screen rather than
# asking the app what it thinks it stored.
#
# ## Two kinds of evidence, deliberately
#
#  * the preferences file, read with `run-as` (hence the debug build);
#  * the pixels, because §13.A asks that "the persisted value and the actual
#    rendered map must agree" and a string in a file is only half of that.
#
#   bash scripts/verify_persistence.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=/dev/null
source "$HERE/uidriver.sh"

PKG=dev.vector.android
OUT="${OUT:-$ROOT/build/verify-persistence}"
mkdir -p "$OUT"

pass=0; fail=0; skip=0
ok()    { echo "  PASS  $*"; pass=$((pass+1)); }
bad()   { echo "  FAIL  $*"; fail=$((fail+1)); }
note()  { echo "        $*"; }
skipf() { echo "  SKIP  $*"; skip=$((skip+1)); }

prefs() { adb shell "run-as $PKG cat shared_prefs/vector.xml" 2>/dev/null | tr -d '\r'; }
prefs_has() { prefs | grep -qF -- "$1"; }

# Is the map being drawn dark? Measured, not asserted from the stored string.
#
# The two themes are unambiguous on this panel: DARK reads (48,56,68) and LIGHT
# reads (238,241,243) at 70% screen height, so a luma split at 128 has ~90
# levels of margin either side. Must be called with no sheet open — the settings
# scrim is 60% black and would report any theme as dark, which is a way to fool
# yourself that this script did on its first run.
map_is_dark() {
  local rgb luma
  rgb="$(ui_map_rgb)" || return 2
  [ -n "$rgb" ] || return 2
  luma=$(python3 -c "r,g,b=map(int,'$rgb'.split()); print(int(0.2126*r+0.7152*g+0.0722*b))")
  note "map luma $luma (rgb $rgb)"
  [ "$luma" -lt 128 ]
}

echo "=== 0. device and binary ==="
SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
[ -n "$SERIAL" ] || { echo "no adb device attached"; exit 2; }
ok "device $SERIAL ($(adb shell getprop ro.product.model | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

APK="$ROOT/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
[ -f "$APK" ] || { echo "no debug APK; build it first"; exit 2; }
note "apk $(basename "$APK") $(sha256sum "$APK" | cut -c1-16)"
# Debug and release carry DIFFERENT signatures — release is signed with the key
# from scripts/release_key.sh — so swapping variants needs an uninstall, which
# also wipes the store. Stated because it bounds what a persistence result
# means: it is only valid within one signing identity.
if ! adb install -r -g "$APK" >/dev/null 2>&1; then
  note "install rejected; uninstalling first (signature change between variants)"
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r -g "$APK" >/dev/null 2>&1
fi
adb shell pm path $PKG >/dev/null 2>&1 && ok "installed" || { bad "install failed"; exit 1; }
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb reverse tcp:9003 tcp:9003 >/dev/null 2>&1 && ok "adb reverse 9003" || bad "adb reverse failed"

# From empty, so nothing here can pass on state a previous run left behind.
adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'rm -f shared_prefs/vector.xml'" >/dev/null 2>&1
ui_wake
ui_launch 12
ui_foreground && ok "app in the foreground" || { bad "app not in the foreground"; exit 1; }

# A position is needed for the destination flow. Report it rather than assume it.
FIXED=no
for i in 1 2 3 4 5 6; do
  ui_dump >/dev/null
  if ! ui_has "Searching for GPS" && ! ui_has "No position available"; then FIXED=yes; break; fi
  sleep 5
done
[ "$FIXED" = yes ] && ok "the handset has a position fix" \
  || note "no fix yet — the destination steps will report SKIP rather than fail"

# ---------------------------------------------------------------------------
echo "=== 1. map settings survive process death, and are RENDERED ==="
ui_dump >/dev/null
if ui_tap_text "Settings" 2 && ui_has "Theme"; then
  ok "settings sheet opened"
  ui_tap_text "Dark" 2 >/dev/null
  prefs_has '>DARK<' && ok "theme=DARK on disk before the call returned" \
    || bad "theme was not on disk after the tap"
  ui_close_sheet >/dev/null 2>&1
  # §13.A's own sequence: leave the screen, background, kill, relaunch, look.
  adb shell input keyevent KEYCODE_HOME; sleep 2
  ui_kill
  prefs_has '>DARK<' && ok "theme=DARK survived force-stop" \
    || bad "theme did not survive force-stop"
  ui_launch 12
  ui_screenshot "$OUT/theme-dark-after-kill.png"
  if map_is_dark; then
    ok "the map is actually rendered DARK after the kill and relaunch"
  else
    bad "the stored theme is DARK but the map is not drawn dark"
  fi

  # And back, so the check cannot pass because dark happens to be the default.
  ui_dump >/dev/null
  ui_tap_text "Settings" 2 >/dev/null
  ui_tap_text "Light" 2 >/dev/null
  prefs_has '>LIGHT<' && ok "theme=LIGHT on disk before the call returned" \
    || bad "the light theme did not reach disk"
  ui_close_sheet >/dev/null 2>&1
  ui_kill
  ui_launch 12
  ui_screenshot "$OUT/theme-light-after-kill.png"
  if map_is_dark; then
    bad "the stored theme is LIGHT but the map is still drawn dark"
  else
    ok "the map is actually rendered LIGHT after the kill and relaunch"
  fi
else
  bad "could not open the settings sheet"
fi

# ---------------------------------------------------------------------------
echo "=== 2. voice settings survive process death, and stay SELECTED ==="
# §13.B pays particular attention to VoiceMode.ALERTS, which is the mode with
# real behaviour attached and therefore the one most able to render as enabled
# while not being honoured.
ui_dump >/dev/null
ui_tap_text "Settings" 2 >/dev/null
if ui_scroll_to "Alerts only"; then
  ui_tap_text "Alerts only" 2 >/dev/null
  prefs_has '>ALERTS<' && ok "voice_mode=ALERTS on disk before the call returned" \
    || bad "voice mode was not on disk after the tap"
  ui_close_sheet >/dev/null 2>&1
  ui_kill
  prefs_has '>ALERTS<' && ok "voice_mode=ALERTS survived force-stop" \
    || bad "voice mode did not survive force-stop"
  ui_launch 12
  ui_dump >/dev/null
  ui_tap_text "Settings" 2 >/dev/null
  if ui_scroll_to "Alerts only"; then
    TICK="$(ui_ticked)"
    note "ticked after relaunch: ${TICK:-nothing}"
    # The tick, not the tint: the rows are clickable Surfaces and Compose does
    # not mark them selected="true", and Vector draws a checkmark as well as a
    # colour precisely so the selection is not colour-only.
    if echo "$TICK" | grep -qi 'Silent unless something changes'; then
      ok "the Alerts row is still the ticked one after a process death"
    else
      bad "the voice selection did not come back ticked"
    fi
  else
    bad "could not reach the voice list after relaunch"
  fi
  ui_close_sheet >/dev/null 2>&1
else
  bad "could not reach the voice modes"
fi

# ---------------------------------------------------------------------------
echo "=== 3. recent destinations survive process death ==="
# A destination becomes "recent" only when CHOSEN, which needs a fix to route
# from — so this drives the real search flow rather than writing the key.
DEST_OK=no
ui_dump >/dev/null
if [ "$FIXED" != yes ]; then
  skipf "no position fix — cannot choose a destination"
elif ui_tap_text "Where to?" 2; then
  adb shell input text "West%sBay"
  sleep 5
  ui_dump >/dev/null
  HIT="$(ui_texts | grep -i 'bay' | grep -viE 'where to|^search' | head -1)"
  if [ -n "$HIT" ]; then
    note "search hit: $HIT"
    ui_tap_text "$HIT" 3 >/dev/null
    sleep 8
    ui_dump >/dev/null
    if prefs_has 'recents'; then
      ok "the chosen destination was written to recents before the call returned"
      DEST_OK=yes
      ui_kill
      prefs_has 'recents' && ok "recents survived force-stop" \
        || bad "recents lost on force-stop"
      note "stored: $(prefs | grep -o 'name="recents">[^<]*' | cut -c1-90)"
    else
      bad "choosing a destination did not write a recent"
    fi
  else
    skipf "search returned nothing for 'West Bay'"
    note "(needs the stack on :9003; check adb reverse and the token)"
  fi
else
  bad "could not open the search box"
fi

# ---------------------------------------------------------------------------
echo "=== 4. Home survives process death, and clearing actually clears ==="
# `Places.clear` was written, unit-tested, and reachable from NO control in the
# app until V6 — the dead-persistence case §6 is about.
if [ "$DEST_OK" != yes ]; then
  skipf "no destination was chosen, so PREVIEW cannot be re-entered"
else
  ui_launch 12
  ui_dump >/dev/null
  ui_tap_text "Where to?" 2 >/dev/null
  ui_dump >/dev/null
  RECENT="$(ui_texts | grep -i 'bay' | grep -viE 'where to' | head -1)"
  if [ -n "$RECENT" ]; then
    ok "the recent destination is offered when the search box opens"
    ui_tap_text "$RECENT" 3 >/dev/null
    sleep 8
    ui_dump >/dev/null
    if ui_has "Home"; then
      ui_tap_text "Home" 2 >/dev/null
      if prefs_has 'place_home'; then
        ok "Home written to disk before the call returned"
        HOMEVAL="$(prefs | grep -o 'name="place_home">[^<]*' | head -1)"
        note "stored: $HOMEVAL"
        echo "$HOMEVAL" | grep -qE '[0-9]+\.[0-9]+' \
          && ok "Home kept real coordinates, not a placeholder" \
          || bad "Home has no coordinates"
        ui_kill
        prefs_has 'place_home' && ok "Home survived force-stop" \
          || bad "Home lost on force-stop"
        # Now clear it, from the control that until V6 did not exist.
        ui_launch 12
        ui_dump >/dev/null
        ui_tap_text "Settings" 2 >/dev/null
        if ui_scroll_to "YOUR DATA"; then
          ok "the data section is on the settings sheet"
          CXY="$(ui_row_control "Home" "Clear")"
          if [ -n "$CXY" ]; then
            adb shell input tap $CXY; sleep 2
            prefs_has 'place_home' && bad "Home is still on disk after Clear" \
              || ok "clearing Home removed it from disk"
            ui_dump >/dev/null
            ui_has "Home" && bad "the Home row is still on screen after Clear" \
              || ok "no stale row is left behind after Clear"
            ui_kill
            prefs_has 'place_home' && bad "Home came back after force-stop" \
              || ok "the clear survived force-stop — no resurrection"
          else
            bad "could not find the Clear control on Home's own row"
          fi
        else
          bad "the settings sheet has no data section"
        fi
      else
        bad "setting Home did not write to disk"
      fi
    else
      bad "PREVIEW does not offer a Home slot"
    fi
  else
    bad "the recent destination is not offered on the search sheet"
  fi
fi

# ---------------------------------------------------------------------------
echo "=== 5. a completed drive is remembered ==="
# §13.D. This needs a drive that ARRIVES, which needs motion — so it replays
# the shortest scenario that reaches its destination (scenario-k-dense, 242 s)
# through the real fused provider, exactly as simulate_drive.sh does.
TRACE="$ROOT/app/build/traces/scenario-k-dense.csv"
DEVDIR=/data/local/tmp/vector-traces
if [ ! -f "$TRACE" ]; then
  skipf "no trace for scenario-k-dense; generate with TraceExportTest"
else
  DEST=$(awk -F'\t' '$1=="scenario-k-dense" {print $2}' "$ROOT/app/build/traces/destinations.txt")
  adb shell appops set $PKG android:mock_location allow 2>/dev/null
  adb shell mkdir -p $DEVDIR >/dev/null 2>&1
  adb push "$TRACE" "$DEVDIR/scenario-k-dense.csv" >/dev/null 2>&1
  adb shell chmod 755 "$DEVDIR" >/dev/null 2>&1
  adb shell chmod 644 "$DEVDIR/scenario-k-dense.csv" >/dev/null 2>&1
  adb shell am force-stop $PKG
  adb logcat -c
  ui_launch 12
  adb shell am start -n "$PKG/.MainActivity" \
    --es vectorTrace "$DEVDIR/scenario-k-dense.csv" \
    --es vectorDest "$DEST" --es vectorSpeedup 1.0 >/dev/null 2>&1
  note "driving scenario-k-dense at 1x (~290 s); a drive is only recorded if it arrives"
  for i in $(seq 1 32); do
    sleep 10
    if adb logcat -d -s VectorNav:I 2>/dev/null | grep -q 'drive recorded'; then break; fi
  done
  REC=$(adb logcat -d -s VectorNav:I 2>/dev/null | grep 'drive recorded' | tail -1)
  if [ -n "$REC" ]; then
    ok "a drive was recorded: ${REC#*VectorNav: }"
    prefs_has 'drives' && ok "the drive is on disk" || bad "no drives key on disk"
    ui_kill
    prefs_has 'drives' && ok "the drive survived force-stop" \
      || bad "the drive was lost on force-stop"
    note "stored: $(prefs | grep -o 'name="drives">[^<]*' | cut -c1-200)"
    # And it has to be VISIBLE, not merely stored. The V5 defect class: a value
    # written into a state nothing renders.
    ui_launch 12
    ui_dump >/dev/null
    ui_tap_text "Settings" 2 >/dev/null
    if ui_scroll_to "Past drives"; then
      ok "the drive is listed under Past drives after a process death"
      ui_texts | grep -A1 -i 'past drives' | head -3 | sed 's/^/        /'
    else
      bad "the drive is on disk but not shown anywhere"
    fi
    ui_close_sheet >/dev/null 2>&1
  else
    ARR=$(adb logcat -d -s VectorNav:I 2>/dev/null | grep -c 'arrived')
    bad "no drive was recorded (arrival lines seen: $ARR)"
    note "logcat tail:"
    adb logcat -d -s VectorNav:I VectorMock:I 2>/dev/null | tail -12 | sed 's/^/        /'
  fi
fi

echo
echo "=== persistence — $pass passed, $fail failed, $skip skipped ==="
[ "$fail" -eq 0 ]
