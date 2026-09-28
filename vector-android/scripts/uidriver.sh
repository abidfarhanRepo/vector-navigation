#!/usr/bin/env bash
# Drive the app's UI by what is ON it, not by where things happen to be.
#
# ## Why this exists
#
# `verify_on_device.sh` taps hardcoded screen fractions — "60% down, 50%
# across" — and V5 recorded what that costs: one of those taps landed on the
# orientation control and flipped the map to NORTH_UP, which is a legitimate
# driver preference, so the app was correctly drawing a flat north-up map and
# every camera measurement the harness produced for the rest of the session was
# taken under a setting nobody chose. The harness could not tell, because a
# fraction has no idea what it hit.
#
# Compose publishes its semantics tree to accessibility, so `uiautomator dump`
# can see Vector's own text and content descriptions with their bounds. Tapping
# the centre of the node that carries a given string is therefore both reliable
# and self-describing: when it fails it says "no node matching X", which is a
# finding, rather than tapping something else and reporting a number.
#
# This is what makes the V6 §6 "zero dead UI" sweep and the §13 persistence
# checks possible on a handset at all: both need to operate specific controls
# and then read specific state back.
#
#   source scripts/uidriver.sh
#   ui_dump; ui_has "Settings"; ui_tap_text "Dark"; ui_selected "Dark"
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/toolchain.sh"
UI_PKG="${UI_PKG:-dev.vector.android}"
UI_TMP="${UI_TMP:-/tmp/vector-ui}"
mkdir -p "$UI_TMP"

# ---- the tree -------------------------------------------------------------

# Snapshot the accessibility tree. Retries, because a dump taken while Compose
# is mid-recomposition comes back as "could not get idle state" rather than as
# a tree, and that is a transient rather than a failure.
ui_dump() {
  local tries="${1:-6}" i
  for ((i = 0; i < tries; i++)); do
    if adb shell uiautomator dump /sdcard/vector-ui.xml >/dev/null 2>&1; then
      adb shell cat /sdcard/vector-ui.xml 2>/dev/null > "$UI_TMP/ui.xml"
      # A truncated dump is worse than none: it silently loses the bottom of
      # the screen, which on this app is where every control lives.
      if grep -q '</hierarchy>' "$UI_TMP/ui.xml" 2>/dev/null; then return 0; fi
    fi
    sleep 1
  done
  return 1
}

# Every string the screen is currently showing, one per line.
ui_texts() {
  python3 - "$UI_TMP/ui.xml" <<'PY'
import re, sys
try:
    x = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except OSError:
    sys.exit(0)
seen = set()
for attr in ("text", "content-desc"):
    for m in re.finditer(attr + r'="([^"]*)"', x):
        v = m.group(1).strip()
        if v and v not in seen:
            seen.add(v)
            print(v)
PY
}

# Is this string on screen? Substring match, because Vector composes some rows
# as one merged node whose text is a list ("Villaggio Mall, Today 10:48 · ...").
ui_has() { ui_texts | grep -qiF -- "$1"; }

# The centre of the first node carrying a string, as "x y".
#
# Prefers the SMALLEST matching node. A merged parent and its child can both
# report the same text, and the parent's bounds can span the whole row — so
# tapping the largest match is how you hit the row when you meant the button
# inside it.
ui_center() {
  python3 - "$UI_TMP/ui.xml" "$1" <<'PY'
import re, sys
x = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want = sys.argv[2].lower()
best = None
for m in re.finditer(r'<node[^>]*>', x):
    tag = m.group(0)
    fields = " ".join(
        v for a in ("text", "content-desc")
        for v in re.findall(a + r'="([^"]*)"', tag)
    )
    if want not in fields.lower():
        continue
    b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', tag)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    if x2 <= x1 or y2 <= y1:
        continue          # zero-size node: present in the tree, not on screen
    area = (x2 - x1) * (y2 - y1)
    if best is None or area < best[0]:
        best = (area, (x1 + x2) // 2, (y1 + y2) // 2)
if best:
    print(best[1], best[2])
PY
}

# Tap the node carrying a string. Fails loudly rather than tapping blind.
ui_tap_text() {
  local xy
  xy="$(ui_center "$1")"
  if [ -z "$xy" ]; then
    echo "    ui_tap_text: no node matching '$1'" >&2
    return 1
  fi
  adb shell input tap $xy
  sleep "${2:-1}"
  ui_dump >/dev/null
}

# Scroll the sheet until a string is on screen, then stop.
#
# The settings sheet is taller than the screen by design — the consent
# explanation is the one block on it that has to be read rather than operated,
# so it is not allowed below the fold — which means anything after it needs
# scrolling to before it can be tapped.
ui_scroll_to() {
  local want="$1" i
  for ((i = 0; i < 12; i++)); do
    ui_dump >/dev/null
    if ui_has "$want"; then return 0; fi
    # Inside the sheet, upward, short. A long fling overshoots and the next
    # dump lands past the target.
    adb shell input swipe 540 1900 540 1350 260
    sleep 1
  done
  ui_dump >/dev/null
  ui_has "$want"
}

ui_screenshot() { adb exec-out screencap -p > "$1" 2>/dev/null; }

ui_foreground() {
  adb shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*$UI_PKG" && return 0
  return 1
}

ui_launch() {
  adb shell am start -n "$UI_PKG/.MainActivity" >/dev/null 2>&1
  sleep "${1:-8}"
}

# A process death the app cannot prepare for.
#
# `am force-stop` is the closest thing adb offers to the OS reclaiming a
# process: no onPause, no onStop, and — the point of using it here — no
# `QueuedWork.waitToFinish()`, which is what flushes a pending
# SharedPreferences `apply()`. So this is the gesture that tells an `apply()`
# apart from a `commit()`, and it is exactly the case V6 §14 asks about.
ui_kill() {
  adb shell am force-stop "$UI_PKG"
  sleep 2
}

ui_wake() {
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  sleep 1
}

# ---- richer probes, in scripts/uiprobe.py ---------------------------------
#
# Kept in a Python file rather than inline heredocs: two of them need a PNG
# decoder, and a shell function containing a heredoc containing a heredoc is
# how a harness acquires its own bugs.

UI_PROBE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/uiprobe.py"

# Which option in a radio list carries the tick. See uiprobe.py: Compose does
# NOT set selected="true" on these rows, so the tick is the honest signal.
ui_ticked() { python3 "$UI_PROBE" ticked "$UI_TMP/ui.xml"; }

# The control on the same ROW as a label, as "x y". The data section has one
# "Clear" per row, so "the first Clear" is not "Home's Clear".
ui_row_control() { python3 "$UI_PROBE" nearest "$UI_TMP/ui.xml" "$1" "${2:-Clear}"; }

# The average colour of a band of map, as "r g b". This is what makes
# "the persisted value and the actual rendered map agree" checkable.
ui_map_rgb() {
  ui_screenshot "$UI_TMP/shot.png" || return 1
  python3 "$UI_PROBE" mapcolor "$UI_TMP/shot.png"
}

# Close the settings sheet.
#
# "Done" is at the TOP of a sheet that scrolls, so it has to be scrolled back to
# before it can be tapped — a bare `ui_tap_text "Done"` on a sheet scrolled down
# to the voice list finds no node, silently leaves the sheet open, and every
# later step then fails for the wrong reason. Which is what happened.
ui_close_sheet() {
  local i
  for ((i = 0; i < 10; i++)); do
    ui_dump >/dev/null
    if ui_has "Done"; then ui_tap_text "Done" 1 && return 0; fi
    adb shell input swipe 540 1350 540 1900 260
    sleep 1
  done
  adb shell input tap 540 120   # the scrim, which also closes it
  sleep 1
  ui_dump >/dev/null
  ! ui_has "Done"
}
