#!/usr/bin/env bash
# Render the Android style through MapLibre Native's C++ core, headlessly.
#
#   scripts/native-render/run.sh            # needs the Vector stack on :9003
#
# WHY THIS EXISTS. The Android emulator SIGSEGVs on this host, so the app cannot
# be run. But `@maplibre/maplibre-gl-native` is a Node binding to the SAME C++
# renderer the Android app uses — different bindings, identical engine. So the
# one thing a Compose/Robolectric test cannot reach (does the native renderer
# parse this style, fetch these tiles, resolve these fonts, and draw pixels)
# becomes answerable without a handset.
#
# It has already earned itself: the first run HUNG, which led to the discovery
# that the style asked for a fontstack the server does not have. A missing
# fontstack is answered with HTTP 200 and a 2-byte body, so every label layer
# would have rendered blank on the device with no error anywhere.
#
# The container is Ubuntu 24.04 (ICU 74 + libjpeg8, which Fedora 44 lacks) with
# Node 22 from NodeSource, because the published prebuilt is ABI 127 and
# Ubuntu's own Node 18 is ABI 109.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${VECTOR_BASE:-http://localhost:9003}"
STYLE_KT="$HERE/../../app/src/main/java/dev/vector/android/VectorStyle.kt"

EPOCH="$(curl -sS "$BASE/tiles/version" | python3 -c 'import json,sys;print(json.load(sys.stdin)["epoch"])')"
python3 - "$STYLE_KT" "$BASE" "$EPOCH" > "$HERE/style.json" <<'PY'
import sys, json, pathlib
kt, base, epoch = sys.argv[1], sys.argv[2], sys.argv[3]
src = pathlib.Path(kt).read_text()
body = src[src.index('return """') + len('return """'): src.rindex('""".trimIndent()')]
body = body.replace("$glyphs", f"{base}/glyphs/{{fontstack}}/{{range}}.pbf")
body = body.replace("$tiles", f"{base}/tiles/{{z}}/{{x}}/{{y}}.mvt?v={epoch}")
json.loads(body)          # fail loudly if the Kotlin template is not valid JSON
print(body)
PY

docker build -q -t vector-mlnative "$HERE" >/dev/null
docker run --rm --network host -v "$HERE:/work" vector-mlnative bash -c \
  'cd /app && cp /work/render.js /work/style.json . && xvfb-run -a node render.js'
