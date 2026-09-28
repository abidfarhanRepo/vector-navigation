#!/usr/bin/env bash
# Every V5 driving scenario, on the device, at real time.
#
# Real time is not a preference here. SPEEDUP divides the interval between
# fixes without changing the distance between them, so a compressed replay
# shows the app a vehicle travelling that many times faster — a different
# drive. Measured at 8x on the S24, the plausibility gate correctly rejected
# 90 of 466 fixes as implying 112 m/s and produced a deviation the 1x run does
# not have. So the whole batch runs at 1x and takes about two hours.
#
#   bash scripts/simulate_all.sh 2>&1 | tee build/simulate-all.log
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SUMMARY="$ROOT/build/simulate-all-summary.txt"
mkdir -p "$ROOT/build"
: > "$SUMMARY"

# Record WHICH BINARY produced these numbers.
#
# `simulate_drive.sh` installs whatever APK is on disk at the moment it runs, so
# a rebuild part-way through a two-hour batch would silently produce a summary
# describing two different builds. The checksum is the cheapest way to make that
# impossible to do by accident.
APK="$(ls -1 "$ROOT"/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk 2>/dev/null | head -1)"
{
  echo "# build: $(cd "$ROOT/.." && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "# apk:   $(basename "${APK:-none}") $(sha256sum "${APK:-/dev/null}" 2>/dev/null | cut -c1-16)"
  echo "# apk mtime: $(date -r "${APK:-/dev/null}" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)"
  echo "# started: $(date '+%Y-%m-%d %H:%M:%S')"
  echo ""
} | tee -a "$SUMMARY"

# Shortest first, so the batch produces evidence early and a failure in the
# harness is found in five minutes rather than in two hours.
SCENARIOS=(
  scenario-k-dense
  scenario-e-uturn
  scenario-h-outage
  scenario-g-jump
  scenario-d-wrong-road
  scenario-c-missed-turn
  scenario-l-arrival
  scenario-f-drift
  scenario-a-city
  scenario-j-highway
  scenario-b-stopgo
  long-run
)

for s in "${SCENARIOS[@]}"; do
  echo "############ $s ############"
  OUT="$ROOT/build/simulate-drive/$s" SPEEDUP=1 bash "$HERE/simulate_drive.sh" "$s" \
    > "$ROOT/build/simulate-drive-$s.log" 2>&1
  rc=$?

  tail -1 "$ROOT/build/simulate-drive-$s.log" >/dev/null
  line=$(grep -E '^=== .* — [0-9]+ passed' "$ROOT/build/simulate-drive-$s.log" | tail -1)
  echo "$line" | tee -a "$SUMMARY"
  grep -E '^\s+(FAIL)' "$ROOT/build/simulate-drive-$s.log" | tee -a "$SUMMARY"
  grep -E 'offroute=|arrived=' "$ROOT/build/simulate-drive-$s.log" | tee -a "$SUMMARY"
  grep -E 'hz    median|p99   median|late frames|worst frame|PSS ' "$ROOT/build/simulate-drive-$s.log" \
    | tee -a "$SUMMARY"
  echo "" | tee -a "$SUMMARY"
done

# Leave the handset as it was found.
adb shell cmd uimode night auto >/dev/null 2>&1
echo "############ done — summary in $SUMMARY ############"
