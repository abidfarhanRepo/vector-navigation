#!/usr/bin/env bash
# Capture the evidence that says whether the head unit ACCEPTED Vector.
#
# This exists because the 2026-09-13 car session produced no logs: the phone was
# unplugged before anything could be pulled, and the single most valuable piece
# of evidence was lost. The commands are not hard; remembering them in a car
# park is. So they live here instead.
#
#   bash scripts/capture_car_session.sh before   # run BEFORE you drive
#   bash scripts/capture_car_session.sh after    # run AFTER, phone plugged back in
#
# `after` writes to .scratch/vector-android-auto/car-session-<timestamp>/ and
# prints a verdict. The verdict distinguishes the two failures that look
# identical from the driver's seat:
#
#   host REJECTED the app   -> Vector never entered the car's app list.
#                              Distribution or manifest. Look at H1/H3.
#   host ACCEPTED the app   -> the car knows about Vector and still did not
#                              draw it. That is a launcher/rendering problem
#                              and a completely different hunt.
set -euo pipefail

PKG=dev.vector.android
GEARHEAD=com.google.android.projection.gearhead
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTROOT="$(cd "$HERE/../.." && pwd)/.scratch/vector-android-auto"

adb get-state >/dev/null 2>&1 || { echo "no device. plug the phone in and allow USB debugging."; exit 1; }

case "${1:-}" in
  before)
    echo "Device: $(adb shell getprop ro.product.model | tr -d '\r')"
    echo
    echo "Installed $PKG:"
    adb shell dumpsys package "$PKG" 2>/dev/null \
      | grep -E "versionCode|installerPackageName" | sed 's/^/  /' || echo "  NOT INSTALLED"
    echo
    echo "installerPackageName must be com.android.vending for a production head"
    echo "unit. Anything else (null, com.android.shell) is a sideload, and a"
    echo "sideloaded Car App Library app is not eligible -- see"
    echo "developer.android.com/training/cars/testing, 'Allow unknown sources'."
    echo
    echo "Now, on the phone, BEFORE you drive:"
    echo "  Android Auto settings -> About -> tap Version 10x -> developer settings"
    echo "  -> turn ON 'Force debug logging'  (and 'Unknown sources' if sideloaded)"
    echo
    read -r -p "press enter once Force debug logging is ON, to clear the log buffer... " _
    adb logcat -b all -c 2>/dev/null || adb logcat -c
    echo "buffer cleared. Drive, connect the car, look for Vector, then run:"
    echo "  bash scripts/capture_car_session.sh after"
    ;;

  after)
    STAMP="$(date +%Y%m%d-%H%M%S)"
    OUT="$OUTROOT/car-session-$STAMP"
    mkdir -p "$OUT"

    echo "capturing to $OUT"
    adb logcat -b all -d > "$OUT/car-session.log" 2>/dev/null || adb logcat -d > "$OUT/car-session.log"
    adb shell dumpsys activity service "$GEARHEAD" > "$OUT/gearhead-dumpsys.txt" 2>&1 || true
    adb shell dumpsys package "$PKG" > "$OUT/package-dumpsys.txt" 2>&1 || true

    grep -iE "vector|CarApp|GH\.|NavClientManager|APP_WHITELIST" "$OUT/car-session.log" \
      > "$OUT/car-session.filtered.log" 2>/dev/null || true
    grep -i vector "$OUT/gearhead-dumpsys.txt" > "$OUT/gearhead-vector.txt" 2>/dev/null || true

    echo
    echo "  car-session.log           $(wc -l < "$OUT/car-session.log") lines"
    echo "  car-session.filtered.log  $(wc -l < "$OUT/car-session.filtered.log") lines"
    echo "  gearhead-vector.txt       $(wc -l < "$OUT/gearhead-vector.txt") lines"
    echo
    echo "--- verdict -------------------------------------------------------"

    WHITELISTED=no
    grep -q "APP_WHITELIST.*$PKG" "$OUT/car-session.log" 2>/dev/null && WHITELISTED=yes
    grep -q "$PKG" "$OUT/gearhead-vector.txt" 2>/dev/null && WHITELISTED=yes

    NAVDECL=no
    grep -qi "declared as a navigation app" "$OUT/car-session.log" 2>/dev/null && NAVDECL=yes

    echo "  in the host's app whitelist : $WHITELISTED"
    echo "  declared as a navigation app: $NAVDECL"
    echo
    if [[ "$WHITELISTED" == yes ]]; then
      echo "  The host ACCEPTED Vector. If it still was not on the car screen,"
      echo "  this is a launcher/rendering problem, NOT distribution."
    else
      echo "  The host REJECTED Vector -- it never reached the car's app list."
      echo "  Check installerPackageName in package-dumpsys.txt. If it is not"
      echo "  com.android.vending, the app did not come from Play and a Car App"
      echo "  Library app cannot appear on a production head unit."
    fi
    echo "-------------------------------------------------------------------"
    echo
    echo "grep the raw log yourself with:"
    echo "  grep -iE 'vector|CarApp|GH\\.|NavClientManager|APP_WHITELIST' $OUT/car-session.log"
    ;;

  *)
    echo "usage: bash scripts/capture_car_session.sh {before|after}"
    exit 2
    ;;
esac
