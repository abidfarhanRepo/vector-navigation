#!/usr/bin/env bash
# Vector — one-command bootstrap (issue 03).
#
# The ONLY thing that touches raw data. Idempotent + resumable: re-running is
# safe. Downloads the OSM extract for $VECTOR_REGION, converts it to GeoJSON,
# builds the roads-only routing network + OSRM graph + MVT tiles + geocoder
# index, and writes them all into the named volumes docker-compose.yml mounts.
# Nothing outside the repo is assumed to exist.
#
#   cp .env.example .env   # then edit region/url/bbox if desired
#   ./bootstrap.sh
#   docker compose up -d   # open http://localhost:8088
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------------------
# 0. .env — generate secrets, refuse demo tokens unless VECTOR_DEV=1.
# ---------------------------------------------------------------------------
if [ ! -f .env ]; then
  echo "[bootstrap] No .env — creating from .env.example."
  cp .env.example .env
fi

# Strip CR before sourcing. A .env that reached the host over a Windows editor,
# a CRLF-checkout, or an scp from such a tree makes `source` bake a trailing
# \r into EVERY value: the region becomes "qatar\r" (so the graph is written to
# a filename nothing later reads), and the tokens get an invisible \r that makes
# the web edge's Authorization header not match the service token -- a 401 on
# every proxied route with two secrets that LOOK identical in any diff. Cheap to
# normalise, expensive to debug. Only rewrites when CRs are actually present.
if grep -qU $'\r' .env 2>/dev/null; then
  echo "[bootstrap] .env had CRLF line endings — normalising to LF."
  sed -i 's/\r$//' .env
fi
set -a; source .env; set +a

rewrite_env () { # var value
  python3 -c '
import sys
var, val, path = sys.argv[1], sys.argv[2], sys.argv[3]
out, found = [], False
for ln in open(path, encoding="utf-8"):
    if ln.startswith(var + "="):
        out.append(var + "=" + val + "\n"); found = True
    else:
        out.append(ln)
if not found:
    out.append(var + "=" + val + "\n")
open(path, "w", encoding="utf-8").writelines(out)
' "$1" "$2" .env
}

MUTATED=0
for var in VECTOR_SERVICE_TOKEN VECTOR_WEB_TOKEN VECTOR_PG_PASSWORD; do
  cur="${!var:-}"
  if [ -z "$cur" ] || [ "$cur" = "__GENERATE__" ]; then
    val="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
    rewrite_env "$var" "$val"
    MUTATED=1
    echo "[bootstrap] Generated random $var."
  fi
done
if [ "$MUTATED" = "1" ]; then set -a; source .env; set +a; fi

if [ "$VECTOR_DEV" != "1" ]; then
  for var in VECTOR_SERVICE_TOKEN VECTOR_WEB_TOKEN VECTOR_PG_PASSWORD; do
    v="${!var:-}"
    case "$v" in
      vector-web-demo|demo|token|password|secret|__GENERATE__|""|vector)
        echo "[bootstrap] ERROR: refusing to boot with default/demo $var. Regenerate (delete .env) or set VECTOR_DEV=1." >&2; exit 1 ;;
    esac
  done
fi

# ADR-0050 two-secret contract: the browser-facing token and the token the web
# edge presents to the internal services MUST be different values. Generated
# secrets are distinct by construction, but a hand-edited .env can paste the
# same value into both -- and that failure is silent and expensive: the web
# token gets forwarded upstream as the service token, every proxied route 401s,
# and it reads like a routing or graph problem. Fail loudly here instead.
if [ "$VECTOR_SERVICE_TOKEN" = "$VECTOR_WEB_TOKEN" ]; then
  echo "[bootstrap] ERROR: VECTOR_SERVICE_TOKEN and VECTOR_WEB_TOKEN are identical." >&2
  echo "[bootstrap]        ADR-0050 requires two DISTINCT secrets. Give them different" >&2
  echo "[bootstrap]        values in .env, or set both to __GENERATE__ and re-run." >&2
  exit 1
fi

REGION="${VECTOR_REGION}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# 1. Download the OSM extract for $REGION (idempotent; skip if re-running).
# ---------------------------------------------------------------------------
# The extract is cached OUTSIDE $WORK. $WORK is an mktemp dir wiped by the EXIT
# trap, so with OSM living there the "skip if re-running" guard below could never
# fire and every re-run re-downloaded ~200 MB from Overpass -- which is both slow
# and rude to a free public API. Caching here is what makes this script's
# "idempotent + resumable" promise true for the expensive step. Delete
# .bootstrap-cache/ to force a fresh pull.
CACHE="$ROOT/.bootstrap-cache"
mkdir -p "$CACHE"
# Defined out here, not inside the download branch: step 1b needs the same
# mirror list, and it runs even when step 1 was served from cache.
#
# $VECTOR_PBF_URL is tried FIRST so an explicit override still wins; the
# mirrors are fallbacks, skipped when the first attempt succeeds. (Geofabrik
# is not an option here: it publishes no standalone Qatar extract -- Qatar only
# exists inside the 252 MB asia/gcc-states file.)
OVERPASS_ENDPOINTS="$VECTOR_PBF_URL
https://overpass.kumi.systems/api/interpreter
https://overpass-api.de/api/interpreter
https://overpass.private.coffee/api/interpreter"
# The extract can be fetched as a GRID OF TILES instead of one request.
#
# DEFAULT IS 1 — a single whole-region query, exactly as before. Raise it when
# Overpass refuses the whole region, which is the single most reliable way to
# fail this script: measured 2026-09-08, the whole-Qatar query was refused by
# all three public mirrors on nine consecutive attempts across three rounds with
# a 30 s backoff, while the IDENTICAL query over one QUADRANT of the same bbox
# returned 25 MB / 16,503 ways in 37 seconds from the first mirror tried. The
# ceiling is the public API's per-query budget, and the way under it is to ask
# for less at a time — not to ask less often.
#
#     VECTOR_BBOX_TILES=2 ./bootstrap.sh     # 2x2 = four quadrants
#
# WHY THIS IS NOT THE DEFAULT. A tiled union SHOULD be complete — `way[...]
# (bbox:)` returns whole ways that intersect the box and `>;` pulls every node
# they touch, so a way straddling a seam arrives intact from whichever tile it
# meets, and duplicates are deduplicated by element id in osm_to_geojson.py.
# That was NOT verifiable against the live API today: three fetches of the same
# Doha bbox 20 minutes apart came back stamped `osm_base` 2026-07-24,
# 2026-06-01 and 2026-05-06 — and two of those three came from the SAME
# hostname, so the mirrors are load-balanced clusters whose backends are months
# apart and the vintage cannot be pinned by pinning the host. Any tiled-versus-
# whole comparison measures that, not the tiling. What IS verified is the merge
# itself (see `MergeMultipleExtractsTest`) and that a quadrant fetch succeeds
# where the whole-region fetch is refused.
#
# Tiles are cached INDIVIDUALLY, so a run interrupted after three of four
# resumes having paid for three. That is what makes the "idempotent + resumable"
# promise true for the expensive step. Delete .bootstrap-cache/ to force a
# fresh pull.
VECTOR_BBOX_TILES="${VECTOR_BBOX_TILES:-1}"       # N x N grid

# The renderer has fill layers for buildings, water, park, landuse and natural;
# this query decides which of them can ever have data. It used to ask for
# highways, POI *nodes*, natural=water, leisure=park and landuse — so the
# `buildings` layer was permanently empty, Qatar (a peninsula) had no coastline,
# and a mall mapped as a tagged polygon was invisible to both the map and
# search. vector-ingestion already classifies every kind below; only the
# download was narrow.
#
# Deliberately NOT here, measured against the public Overpass instances on
# 2026-09-08: `way["building"]`, `way["railway"]`, unrestricted `way["leisure"]`
# and POI *areas* (`way["amenity"|"shop"|"tourism"]`). Buildings are the real
# loss and they need a different pipeline: a Geofabrik PBF (Qatar lives inside
# the 252 MB asia/gcc-states extract; there is no standalone qatar-latest.osm.pbf
# — that URL is a 9 KB error page) filtered with osmium. Worth doing when the
# bake reaches z14+, because `visible_at_zoom` hides buildings below z14 and the
# bake currently stops at z13, so today they would render on precisely zero
# tiles. Tiling may now make some of them reachable over Overpass too; that is
# a measurement to make, not an assumption to act on.
osm_query() {   # $1 = "lat_min,lon_min,lat_max,lon_max"
  printf '%s' '[out:xml][timeout:900];
(
  way["highway"](bbox:'"$1"');
  node["amenity"](bbox:'"$1"');
  node["shop"](bbox:'"$1"');
  node["tourism"](bbox:'"$1"');
  node["place"](bbox:'"$1"');
  node["aeroway"](bbox:'"$1"');
  # Traffic signals (V7 Stage 5): the location evidence for the signal-aware
  # feature. A node-only class like the POI lines above -- no `>;` recursion,
  # because a node carries its own coordinates. The converter writes these to
  # a SEPARATE artifact (<region>_signals.geojson), never into the road graph
  # or the basemap feature count.
  node["highway"="traffic_signals"](bbox:'"$1"');
  # Speed cameras (V7.3): same doctrine, separate artifact
  # (<region>_cameras.geojson). Nodes only.
  node["highway"="speed_camera"](bbox:'"$1"');
  way["natural"="water"](bbox:'"$1"');
  way["leisure"="park"](bbox:'"$1"');
  way["landuse"](bbox:'"$1"');
);
out body;
>;
out skel qt;'
}

# Fetch one Overpass query into $2, trying every mirror twice.
#
# Overpass is the single point of failure for every byte of map data in this
# project, and the public instances are genuinely flaky: measured 2026-09-08,
# overpass-api.de returned 504 on a one-node query and 200 a minute later while
# overpass.kumi.systems served a full quadrant. A single un-retried curl fails
# the whole bootstrap perhaps half the time, and the failure reads as a broken
# script rather than a busy upstream.
#
# Writes to a .part and moves into place only on success — writing straight to
# the cache path would leave a truncated extract behind on a timeout, and the
# "skip if present" guard would then reuse the corrupt file on every later run.
# Which mirror served the last tile. Tried FIRST for the next one, because the
# mirrors do not serve the same data: measured 2026-09-08, fetches of the same
# Doha bbox came back stamped `osm_base` 2026-07-24, 2026-06-01 and 2026-05-06
# — two and a half months apart. A graph stitched from those is inconsistent AT
# THE SEAMS, where two vintages of the same street meet, and nothing downstream
# can tell.
#
# This REDUCES the exposure; it does not remove it. Two of those three vintages
# came from the same hostname, so the public mirrors are load-balanced clusters
# whose backends differ and preferring a host does not pin a snapshot. The
# `osm_base` report below is the part that actually protects you: it makes a
# mixed-vintage extract visible instead of silent.
LAST_EP=""

fetch_osm() {   # $1 = query, $2 = destination path, $3 = label
  _q="$1"; _dest="$2"; _label="$3"
  _tmp="$_dest.part"
  for attempt in 1 2; do
    for ep in $LAST_EP $OVERPASS_ENDPOINTS; do
      [ -n "$ep" ] || continue
      echo "[1]   $_label: trying $ep (attempt $attempt) ..."
      if curl -sL --fail --max-time 1800 -X POST --data-urlencode "data=$_q" "$ep" -o "$_tmp" \
         && [ -s "$_tmp" ]; then
        # An Overpass error page is a 200 with XML that has no <way>. Catch it
        # here rather than three stages later as "0 roads in the graph".
        if head -c 20000 "$_tmp" | grep -q "<way"; then
          mv "$_tmp" "$_dest"
          LAST_EP="$ep"
          echo "[1]   $_label: $(wc -c < "$_dest") bytes from $ep"
          return 0
        fi
        echo "[1]   $_label: no ways returned (error page or empty result)."
      fi
      rm -f "$_tmp"
    done
    [ "$attempt" = "1" ] && { echo "[1]   $_label: all endpoints failed; backing off 30s ..."; sleep 30; }
  done
  return 1
}

# ---------------------------------------------------------------------------
# 0b. OSM SOURCE: one dated snapshot, or five Overpass requests.
# ---------------------------------------------------------------------------
#
# Overpass acquisition is five independent requests ([1], [1b], [1c], [1d],
# [1e]) and only [1] is mirror-sticky via LAST_EP — the other four restart at
# the top of OVERPASS_ENDPOINTS every time. The mirrors do not agree on what
# year it is: measured 2026-09-13 with one identical count query minutes apart,
# overpass-api.de answered from 2026-09-13, private.coffee from 2026-07-15 and
# kumi.systems from 2026-05-31, and kumi.systems then answered 2026-07-15
# twenty minutes later from the SAME hostname. So a bake can contain roads from
# one month and shops from another, and the OSM_VINTAGES check below cannot see
# it because it runs before [1b]-[1e] append.
#
# VECTOR_USE_PBF=1 replaces all five with ONE dated Geofabrik snapshot, which
# has a single timestamp for every byte in it by construction. The extract is
# written to $CACHE/$REGION.osm, which is the path step 1 ALREADY honours as a
# whole-region extract and the path step 4 already hands to osrm-extract — so
# nothing downstream changes: same converter, same GeoJSON, same tile bake,
# same styles.
#
#     VECTOR_USE_PBF=1 ./bootstrap.sh                      # newest snapshot, recorded
#     VECTOR_USE_PBF=1 VECTOR_PBF_RELEASE=260912 ./bootstrap.sh
#     VECTOR_USE_PBF=1 VECTOR_PBF_FILE=/archive/gcc.pbf ./bootstrap.sh
#
# VECTOR_PBF_RELEASE defaults to `latest`, which the script RESOLVES to the
# dated name before downloading and records — so even an unpinned run can say
# afterwards which snapshot it used. Geofabrik keeps only seven days of dated
# releases (verified: 260906-260912 served, 260905 a 404), so reproducing a
# build older than a week needs the archived file, which is what
# VECTOR_PBF_FILE is for.
#
# NOT named VECTOR_PBF_URL: that variable already exists above and means "an
# Overpass endpoint to try first", which is a different thing entirely.
VECTOR_USE_PBF="${VECTOR_USE_PBF:-0}"
PBF_MODE=""
if [ "$VECTOR_USE_PBF" = "1" ]; then
  PBF_MODE=1
  # $VECTOR_BBOX is lat_min,lon_min,lat_max,lon_max (see .env.example); the
  # script takes lon,lat order. Converted here, from the one definition, rather
  # than written out twice and allowed to drift.
  PBF_BBOX=$(awk -v bb="$VECTOR_BBOX" 'BEGIN{split(bb,a,",");printf "%s,%s,%s,%s",a[2],a[1],a[4],a[3]}')
  PBF_OUT="$CACHE/${REGION}.osm"
  if [ -s "$PBF_OUT" ] && [ -s "$PBF_OUT.provenance.json" ]; then
    echo "[0b] PBF mode: reusing $PBF_OUT"
    echo "[0b] $(cat "$PBF_OUT.provenance.json" | tr -d '\n ' | head -c 400)"
  else
    mkdir -p "$CACHE"
    PBF_ARGS="--release ${VECTOR_PBF_RELEASE:-latest} -o $PBF_OUT --cache $CACHE --bbox $PBF_BBOX"
    # $VECTOR_PBF_FILE is OPTIONAL -- it exists to point the extractor at a
    # .pbf that is already on disk -- so it is normally unset, and `set -u`
    # (line 13) makes a bare expansion of it fatal. Unguarded, this line aborted
    # every PBF-mode run before it began:
    #
    #     ./bootstrap.sh: line 285: VECTOR_PBF_FILE: unbound variable
    #
    # which made VECTOR_USE_PBF=1 unusable from a clean checkout. `set -e` is
    # NOT a second hazard here: a failing `[ ... ]` at the head of an && list is
    # exempt, so the old idiom was safe on that count and only ever needed the
    # default-expansion. Written as an `if` regardless, because the guard is the
    # point of the line and should look like it.
    if [ -n "${VECTOR_PBF_FILE:-}" ]; then
      PBF_ARGS="$PBF_ARGS --pbf $VECTOR_PBF_FILE"
    fi
    echo "[0b] PBF mode: building $PBF_OUT from a dated Geofabrik snapshot ..."
    # osmium is borrowed, not vendored — `src/` stays stdlib-only (same
    # arrangement as vector-geocoder's Overture script). Host first, then uv,
    # then a container, mirroring how step 3 falls back for the tile bake.
    # shellcheck disable=SC2086
    if python3 -c 'import osmium' >/dev/null 2>&1; then
      python3 vector-tile-gen/scripts/fetch_qatar_pbf.py $PBF_ARGS
    elif command -v uv >/dev/null 2>&1; then
      uv run --with osmium python vector-tile-gen/scripts/fetch_qatar_pbf.py $PBF_ARGS
    else
      echo "[0b] host python lacks osmium — extracting in a container instead."
      # python:3.11, NOT python:3.11-slim. osmium ships a compiled extension
      # linked against libexpat, and the slim image does not carry
      # libexpat.so.1 -- so `pip install osmium` SUCCEEDS there and the import
      # then dies:
      #
      #     ImportError: libexpat.so.1: cannot open shared object file
      #
      # which surfaced as the script's own "osmium is not installed" message
      # and made this fallback dead code on any host without host-side osmium.
      # apt-get is not an option: the container runs --user non-root precisely
      # so /cache/$REGION.osm comes out owned by the caller. The tile-bake
      # fallback below stays slim -- mapbox-vector-tile is pure Python and was
      # verified to import there.
      docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
        -v "$CACHE:/cache" -v "$ROOT:/repo:ro" python:3.11 sh -c \
        "pip install --quiet --disable-pip-version-check --target /tmp/deps osmium && \
         PYTHONPATH=/tmp/deps python /repo/vector-tile-gen/scripts/fetch_qatar_pbf.py \
           --release ${VECTOR_PBF_RELEASE:-latest} -o /cache/${REGION}.osm \
           --cache /cache --bbox $PBF_BBOX"
    fi
  fi
  PBF_REL=$(sed -n 's/.*"release":[[:space:]]*"\([^"]*\)".*/\1/p' "$PBF_OUT.provenance.json" 2>/dev/null | head -1)
  PBF_SUM=$(sed -n 's/.*"pbf_md5":[[:space:]]*"\([^"]*\)".*/\1/p' "$PBF_OUT.provenance.json" 2>/dev/null | head -1)
  echo "[0b] OSM snapshot: release $PBF_REL  md5 $PBF_SUM"
  echo "[0b] provenance:   $PBF_OUT.provenance.json"
fi

OSM_PARTS=""
if [ -s "$CACHE/${REGION}.osm" ]; then
  # A whole-region extract from before tiling existed. Honour it rather than
  # re-downloading what someone already has.
  echo "[1] using cached whole-region extract $CACHE/${REGION}.osm"
  OSM_PARTS="$CACHE/${REGION}.osm"
else
  echo "[1] downloading OSM extract for '$REGION' (bbox ${VECTOR_BBOX}) as ${VECTOR_BBOX_TILES}x${VECTOR_BBOX_TILES} tiles ..."
  N="$VECTOR_BBOX_TILES"
  r=0
  while [ "$r" -lt "$N" ]; do
    c=0
    while [ "$c" -lt "$N" ]; do
      part="$CACHE/${REGION}.t${r}${c}.osm"
      if [ ! -s "$part" ]; then
        # awk does the arithmetic: POSIX sh has no floating point, and a bbox
        # is degrees.
        bb=$(awk -v bb="$VECTOR_BBOX" -v n="$N" -v r="$r" -v c="$c" 'BEGIN{
          split(bb, a, ",");
          dlat = (a[3]-a[1])/n; dlon = (a[4]-a[2])/n;
          printf "%.6f,%.6f,%.6f,%.6f", a[1]+r*dlat, a[2]+c*dlon, a[1]+(r+1)*dlat, a[2]+(c+1)*dlon;
        }')
        fetch_osm "$(osm_query "$bb")" "$part" "tile ${r}${c} ($bb)" || {
          echo "[bootstrap] ERROR: Overpass failed for tile ${r}${c} ($bb)." >&2
          echo "[bootstrap]        These are free, rate-limited public APIs; retry in a few" >&2
          echo "[bootstrap]        minutes (completed tiles are cached and will not refetch)," >&2
          echo "[bootstrap]        set VECTOR_PBF_URL to an instance you control, or raise" >&2
          echo "[bootstrap]        VECTOR_BBOX_TILES to ask for smaller pieces." >&2
          exit 1
        }
      fi
      OSM_PARTS="$OSM_PARTS $part"
      c=$((c + 1))
    done
    r=$((r + 1))
  done
fi
# shellcheck disable=SC2086
echo "[1] extract bytes: $(cat $OSM_PARTS | wc -c)"

# THE ROUTING INPUT, as one file at the path step 4 actually reads.
#
# Step 4 runs `cp '/data/${REGION}.osm'` and hands that to osrm-extract, but in
# Overpass mode nothing ever created it: the main extract is written as
# `${REGION}.t<r><c>.osm` (one per bbox tile) and each supplementary group gets
# its own name. Confirmed by running the acquisition section with a curl stub
# that SUCCEEDS -- a fully successful fresh bootstrap leaves qatar.t00.osm,
# qatar-coastline.osm, qatar-poi-areas.osm and qatar-poi-nodes.osm, and no
# qatar.osm, so step 4 dies on `cp`. It works today only for someone carrying a
# whole-region extract from before tiling existed, which is why it survived:
# the people who had one never saw it.
#
# PBF mode already writes this path (see 0b), so this only covers Overpass mode.
#
# MAIN PARTS ONLY, deliberately. The supplementary groups are POIs, named
# buildings and coastline, which osrm-extract ignores, and the restrictions file
# is NOT merged in: adding turn restrictions to the routing graph would change
# how it routes, and this is a fix for a missing file, not a routing change.
MAIN_OSM_PARTS="$OSM_PARTS"
if [ -s "$CACHE/${REGION}.osm" ]; then
  : # PBF mode, or a whole-region extract already honoured above.
elif [ "$(echo $MAIN_OSM_PARTS | wc -w)" -eq 1 ]; then
  # The default (VECTOR_BBOX_TILES=1) is a single part, so this is the common
  # case and it needs no parsing. Hard-linked rather than copied: the extract is
  # ~180 MB and the two names are the same bytes by definition.
  echo "[1] routing input: linking $(basename $MAIN_OSM_PARTS) -> ${REGION}.osm"
  # shellcheck disable=SC2086
  ln $MAIN_OSM_PARTS "$CACHE/${REGION}.osm" 2>/dev/null || \
    cp $MAIN_OSM_PARTS "$CACHE/${REGION}.osm"
else
  echo "[1] routing input: merging $(echo $MAIN_OSM_PARTS | wc -w) bbox tiles -> ${REGION}.osm"
  # shellcheck disable=SC2086
  python3 - "$CACHE/${REGION}.osm" $MAIN_OSM_PARTS <<'MERGE_OSM'
import sys
import xml.etree.ElementTree as ET

# Concatenating Overpass responses textually would produce several <osm>
# roots, which is not a document osrm-extract will read. Streamed with
# iterparse and deduplicated by (type, id) because bbox tiles overlap at
# their edges and Overpass returns whole ways, so the same element
# legitimately arrives more than once -- the same reason osm_to_geojson.py
# dedupes. Only ids are held in memory; elements are cleared as written.
dst, srcs = sys.argv[1], sys.argv[2:]
seen = set()
n = 0
with open(dst, "wb") as out:
    out.write(b'<?xml version="1.0" encoding="UTF-8"?>\n'
              b'<osm version="0.6" generator="vector-bootstrap">\n')
    for src in srcs:
        for _event, elem in ET.iterparse(src, events=("end",)):
            if elem.tag not in ("node", "way", "relation"):
                continue
            key = (elem.tag, elem.get("id"))
            if key not in seen:
                seen.add(key)
                out.write(ET.tostring(elem, encoding="utf-8"))
                n += 1
            elem.clear()
    out.write(b"</osm>\n")
print(f"[1] routing input: {n} unique elements", flush=True)
MERGE_OSM
fi

# Overpass stamps every response with the OSM database timestamp it answered
# from. Tiles from different mirrors can be months apart, so report it: a graph
# whose halves are different vintages is a real and otherwise invisible defect,
# and the seam is exactly where a road looks wrong.
OSM_VINTAGES=$(for f in $OSM_PARTS; do head -c 4000 "$f" | sed -n 's/.*osm_base="\([^"]*\)".*/\1/p'; done | sort -u)
if [ -n "$PBF_MODE" ]; then
  # `osm_base` is an OVERPASS attribute; a libosmium-written extract has none,
  # so this check reads empty and would print "OSM snapshot:" with nothing
  # after it — directly under 0b, which just printed the real one. There is
  # also nothing for it to catch: one file cannot disagree with itself, which
  # is the entire reason for PBF mode.
  echo "[1] OSM snapshot: Geofabrik $PBF_REL (see 0b; one snapshot, no mixing)"
elif [ "$(printf '%s\n' "$OSM_VINTAGES" | wc -l)" -gt 1 ]; then
  echo "[1] WARNING: tiles come from DIFFERENT OSM snapshots:" >&2
  printf '[1]          %s\n' $OSM_VINTAGES >&2
  echo "[1]          Roads may disagree where two tiles meet. Delete the odd" >&2
  echo "[1]          tile from .bootstrap-cache/ and re-run to refetch it." >&2
else
  echo "[1] OSM snapshot: $OSM_VINTAGES"
fi

# ---------------------------------------------------------------------------
# 1b. Turn restrictions, fetched as a SECOND Overpass request.
# ---------------------------------------------------------------------------
# Not folded into the query above, and this was measured rather than assumed:
# adding `relation["type"="restriction"]` to the combined query makes all three
# public mirrors reject the whole thing (kumi.systems, overpass-api.de and
# private.coffee, 2026-09-08), exactly as widening it for buildings and
# coastline did. On its own the relation query returns Qatar's 3,802
# restrictions in about three seconds and 3 MB.
#
# The response is self-contained: `>;` recursion brings the member ways with
# their node refs and every node with coordinates, so the converter can resolve
# a restriction to graph coordinates without consulting the main extract.
#
# This step is ALLOWED TO FAIL. Restrictions make routing more correct; their
# absence is the behaviour Vector had until now, and it is better to bootstrap a
# stack that routes through a no-left-turn than to hand a new clone a script
# that dies on a flaky third-party API. The failure is loud.
RELS="$CACHE/${REGION}-restrictions.osm"
if [ -z "$PBF_MODE" ] && [ ! -s "$RELS" ]; then
  echo "[1b] downloading turn restrictions ..."
  REL_QL='[out:xml][timeout:600];
(
  relation["type"="restriction"](bbox:'"$VECTOR_BBOX"');
);
out body;
>;
out skel qt;'
  REL_TMP="$WORK/${REGION}-restrictions.osm.part"
  for ep in $OVERPASS_ENDPOINTS; do
    [ -s "$RELS" ] && break
    [ -n "$ep" ] || continue
    if curl -sL --fail --max-time 600 -X POST --data-urlencode "data=$REL_QL" "$ep" -o "$REL_TMP" \
       && [ -s "$REL_TMP" ] && grep -q "<relation" "$REL_TMP"; then
      mv "$REL_TMP" "$RELS"
      echo "[1b] turn restrictions fetched from $ep ($(grep -c "<relation" "$RELS") relations)"
    else
      rm -f "$REL_TMP"
    fi
  done
fi
if [ -n "$PBF_MODE" ]; then
  # The snapshot already contains type=restriction relations, and the converter
  # reads them from the same file it reads the ways from — verified on the real
  # 260912 extract: 3,898 restrictions in, 3,457 resolved. A second source here
  # would be a second vintage.
  REL_ARG=""
  echo "[1b] restrictions: from the PBF snapshot"
elif [ -s "$RELS" ]; then
  REL_ARG="--relations $RELS"
else
  REL_ARG=""
  echo "[1b] WARNING: no turn-restriction data. Routing will not enforce" >&2
  echo "[1b]          no-left-turn / only-straight-on. Delete .bootstrap-cache" >&2
  echo "[1b]          and re-run to retry." >&2
fi

# ---------------------------------------------------------------------------
# 1c. Coastline, fetched as a THIRD Overpass request.
# ---------------------------------------------------------------------------
# Qatar is a peninsula, and without a coastline it has no shape.
#
# Verified on an S24 Ultra once the low zooms were baked: at country scale the
# motorway network rendered correctly and floated in a black void, because the
# sea and the land are both the style's background colour. A country view with
# no land/sea edge does not read as a map of anywhere.
#
# A THIRD request for the same reason 1b is a second one: the main query already
# sits at the mirrors' size ceiling, and widening it for coastline is one of the
# variants measured to make all three reject the whole thing. On its own this
# returns Qatar's 478 coastline ways and 52,385 nodes in ~4.6 MB, first mirror
# tried.
#
# `natural=coastline` is an OPEN way, and `vector_ingestion.osm._basemap_kind`
# classifies it as its own `coastline` kind BEFORE the generic `natural`
# mapping, which would close it into a polygon spanning its two loose ends.
#
# Like 1b, this step is ALLOWED TO FAIL, loudly: no coastline is the behaviour
# Vector had until now.
COAST="$CACHE/${REGION}-coastline.osm"
if [ -z "$PBF_MODE" ] && [ ! -s "$COAST" ]; then
  echo "[1c] downloading coastline ..."
  COAST_QL='[out:xml][timeout:600];
way["natural"="coastline"](bbox:'"$VECTOR_BBOX"');
(._;>;);
out body;'
  COAST_TMP="$WORK/${REGION}-coastline.osm.part"
  for ep in $OVERPASS_ENDPOINTS; do
    [ -s "$COAST" ] && break
    [ -n "$ep" ] || continue
    if curl -sL --fail --max-time 600 -X POST --data-urlencode "data=$COAST_QL" "$ep" -o "$COAST_TMP" \
       && [ -s "$COAST_TMP" ] && grep -q "<way" "$COAST_TMP"; then
      mv "$COAST_TMP" "$COAST"
      echo "[1c] coastline fetched from $ep ($(grep -c "<way" "$COAST") ways)"
    else
      rm -f "$COAST_TMP"
    fi
  done
fi
if [ -n "$PBF_MODE" ]; then
  echo "[1c] coastline: from the PBF snapshot (478 ways in the 260912 extract)"
elif [ -s "$COAST" ]; then
  # Appended to the extract list the converter merges, which already
  # deduplicates by element id — so a coastline way that the main query happened
  # to return as well appears once.
  OSM_PARTS="$OSM_PARTS $COAST"
else
  echo "[1c] WARNING: no coastline data. Qatar will have no land/sea edge and" >&2
  echo "[1c]          the country view will be roads on a plain background." >&2
  echo "[1c]          Delete .bootstrap-cache and re-run to retry." >&2
fi

# ---------------------------------------------------------------------------
# 1d. POI AREAS and named buildings, as a FOURTH Overpass request.
# ---------------------------------------------------------------------------
# "Names of places are entirely missing compared to Google Maps."
#
# Measured against the live API for this bbox, rather than assumed:
#
#   POI *nodes*  (amenity/shop/tourism/office/healthcare)   8,657   <- fetched
#   POI *ways*   (the same tags on an AREA)                 5,439   <- was not
#   named buildings (`building` + `name`)                   2,077   <- was not
#
# The main query asks for POIs as NODES only. But a mall, a hospital, a school
# or any large shop is normally mapped as the building POLYGON, not as a point
# inside it — so the biggest, most searchable places in Doha were precisely the
# ones missing. Adding these roughly DOUBLES the named places Vector knows,
# from ~8.6k to ~16k, at the cost of one more request.
#
# The converter already handled POI ways (`osm_to_geojson.py` emits them as a
# point at the polygon's centroid); nothing ever asked for them. Named buildings
# needed a branch, because a way tagged only `building=yes` + `name` used to
# return no properties at all and was dropped.
#
# A fourth request, for the same reason 1b and 1c are separate: the main query
# already sits at the mirrors' size ceiling and widening it is measured to make
# all three reject the whole thing.
#
# ALLOWED TO FAIL, loudly. Fewer POIs is the behaviour Vector had until now.
POIS="$CACHE/${REGION}-poi-areas.osm"
if [ -z "$PBF_MODE" ] && [ ! -s "$POIS" ]; then
  echo "[1d] downloading POI areas and named buildings ..."
  POI_QL='[out:xml][timeout:600];
(
  way["amenity"](bbox:'"$VECTOR_BBOX"');
  way["shop"](bbox:'"$VECTOR_BBOX"');
  way["tourism"](bbox:'"$VECTOR_BBOX"');
  way["office"](bbox:'"$VECTOR_BBOX"');
  way["healthcare"](bbox:'"$VECTOR_BBOX"');
  way["craft"](bbox:'"$VECTOR_BBOX"');
  way["historic"](bbox:'"$VECTOR_BBOX"');
  way["building"]["name"](bbox:'"$VECTOR_BBOX"');
);
(._;>;);
out body;'
  POI_TMP="$WORK/${REGION}-poi-areas.osm.part"
  for ep in $OVERPASS_ENDPOINTS; do
    [ -s "$POIS" ] && break
    [ -n "$ep" ] || continue
    if curl -sL --fail --max-time 600 -X POST --data-urlencode "data=$POI_QL" "$ep" -o "$POI_TMP" \
       && [ -s "$POI_TMP" ] && grep -q "<way" "$POI_TMP"; then
      mv "$POI_TMP" "$POIS"
      echo "[1d] POI areas fetched from $ep ($(grep -c "<way" "$POIS") ways)"
    else
      rm -f "$POI_TMP"
    fi
  done
fi
if [ -n "$PBF_MODE" ]; then
  echo "[1d] POI areas and named buildings: from the PBF snapshot"
elif [ -s "$POIS" ]; then
  OSM_PARTS="$OSM_PARTS $POIS"
else
  echo "[1d] WARNING: no POI-area data. Malls, hospitals and schools mapped as" >&2
  echo "[1d]          building polygons will be missing from search and the map." >&2
  echo "[1d]          Delete .bootstrap-cache and re-run to retry." >&2
fi

# ---------------------------------------------------------------------------
# 1e. POI NODES for the classes the main query omits.
# ---------------------------------------------------------------------------
#
# `osm_to_geojson.POI_KEYS` recognises thirteen POI tags, the main query asks
# for three of them as nodes (amenity/shop/tourism), and 1d asks for seven as
# ways. So the node and way sides disagree, and the disagreement is invisible:
# a dentist mapped as a building polygon is found, THE SAME DENTIST mapped as a
# node is not. This closes that gap in the cheap direction, by making the node
# classes match the way classes 1d already fetches.
#
# Measured against the live API for this bbox on 2026-09-13:
#
#   office/healthcare/craft/historic nodes not reachable by any current query
#                                                          573   (506 named)
#   converted through the real osm_to_geojson.py            655 POIs, 87% named
#                                                          (company, government,
#                                                           pharmacy, clinic, ruins)
#
# WHY NOT the other four POI_KEYS — emergency, railway, public_transport,
# information. They are MAP FURNITURE, not destinations. Fetching all eight
# classes was measured at 1,777 nodes of which only 46% carry a name, and the
# largest classes are `stop_position` (241), `level_crossing` (177), `switch`
# (137), `fire_hydrant` (79) and `buffer_stop` (77). Those would become POI
# dots and labels competing for the per-tile POI budget against the shops a
# driver is actually looking for. The four classes here are 87% named because
# they describe places rather than infrastructure.
#
# No `>;` recursion: a node carries its own coordinates, so this is the
# cheapest request in the bootstrap — measured at 213 KB in 29 s.
#
# ALLOWED TO FAIL, loudly, and kept SEPARATE from 1d rather than folded into
# it: 1d is already at the mirrors' size ceiling and it carries the 5,439 POI
# ways. Widening it to save a request would risk trading those away for these.
POI_NODES="$CACHE/${REGION}-poi-nodes.osm"
if [ -z "$PBF_MODE" ] && [ ! -s "$POI_NODES" ]; then
  echo "[1e] downloading POI nodes (office/healthcare/craft/historic) ..."
  POI_NODE_QL='[out:xml][timeout:600];
(
  node["office"](bbox:'"$VECTOR_BBOX"');
  node["healthcare"](bbox:'"$VECTOR_BBOX"');
  node["craft"](bbox:'"$VECTOR_BBOX"');
  node["historic"](bbox:'"$VECTOR_BBOX"');
);
out body;'
  PN_TMP="$WORK/${REGION}-poi-nodes.osm.part"
  for ep in $OVERPASS_ENDPOINTS; do
    [ -s "$POI_NODES" ] && break
    [ -n "$ep" ] || continue
    if curl -sL --fail --max-time 600 -X POST --data-urlencode "data=$POI_NODE_QL" "$ep" -o "$PN_TMP" \
       && [ -s "$PN_TMP" ] && grep -q "<node" "$PN_TMP"; then
      mv "$PN_TMP" "$POI_NODES"
      echo "[1e] POI nodes fetched from $ep ($(grep -c "<node" "$POI_NODES") nodes)"
    else
      rm -f "$PN_TMP"
    fi
  done
fi
if [ -n "$PBF_MODE" ]; then
  echo "[1e] POI nodes: from the PBF snapshot"
elif [ -s "$POI_NODES" ]; then
  OSM_PARTS="$OSM_PARTS $POI_NODES"
else
  echo "[1e] WARNING: no POI-node data. Offices, clinics, pharmacies and" >&2
  echo "[1e]          historic sites mapped as points will be missing." >&2
  echo "[1e]          Delete .bootstrap-cache and re-run to retry." >&2
fi

# ---------------------------------------------------------------------------
# 1f. BARRIER NODES, as a SEPARATE request (V7.4 4A).
# ---------------------------------------------------------------------------
# Walking correctness depends on knowing which gates OSM says a person may not
# pass (the recon: 2,164 Qatar gates tagged access=private/no or locked=yes
# sat on foot ways, and their tag was dropped before the routing graph). The
# main query fetches ways and POI/place nodes; it does not fetch
# barrier=* nodes, and `>;` recursion returns way-nodes WITHOUT their tags, so
# in Overpass mode a gate's access tag would be missing from the extract.
#
# A separate request for the same reason 1b-1e are separate: the main query is
# already at the mirrors' size ceiling and widening it is measured to make all
# three reject the whole thing. Node-only, no recursion — each node carries
# its own coordinates — so this is the cheapest request in the bootstrap
# (~213 KB for 8.7k nodes at the 1e scale).
#
# ALLOWED TO FAIL, loudly. No barrier data is the behaviour Vector had until
# now (walks pass through whatever OSM drew); enforcing nothing is a valid
# state, it just means locked gates are not honoured.
BARRIERS_OSM="$CACHE/${REGION}-barriers.osm"
if [ -z "$PBF_MODE" ] && [ ! -s "$BARRIERS_OSM" ]; then
  echo "[1f] downloading barrier nodes ..."
  BARRIER_QL='[out:xml][timeout:600];
(
  node["barrier"](bbox:'"$VECTOR_BBOX"');
);
out body;'
  BAR_TMP="$WORK/${REGION}-barriers.osm.part"
  for ep in $OVERPASS_ENDPOINTS; do
    [ -s "$BARRIERS_OSM" ] && break
    [ -n "$ep" ] || continue
    if curl -sL --fail --max-time 600 -X POST --data-urlencode "data=$BARRIER_QL" "$ep" -o "$BAR_TMP" \
       && [ -s "$BAR_TMP" ] && grep -q "<node" "$BAR_TMP"; then
      mv "$BAR_TMP" "$BARRIERS_OSM"
      echo "[1f] barrier nodes fetched from $ep ($(grep -c \"<node\" "$BARRIERS_OSM") nodes)"
    else
      rm -f "$BAR_TMP"
    fi
  done
fi
if [ -n "$PBF_MODE" ]; then
  echo "[1f] barrier nodes: from the PBF snapshot (8,711 in the 260912 extract)"
elif [ -s "$BARRIERS_OSM" ]; then
  OSM_PARTS="$OSM_PARTS $BARRIERS_OSM"
else
  echo "[1f] WARNING: no barrier data. The walking graph is NOT severed at" >&2
  echo "[1f]          locked/private gates; walks pass through whatever OSM" >&2
  echo "[1f]          drew. Delete .bootstrap-cache and re-run to retry." >&2
fi

# ---------------------------------------------------------------------------
# 1g. CROSSING / KERB NODES, as a SEPARATE request (V7.4 4A.4).
# ---------------------------------------------------------------------------
# Pedestrian crossing facts: 3,170 of Qatar's 3,519 crossing nodes carry
# ``highway=crossing``, and the way-recursion (`>;`) pulls such nodes only as
# geometry WITHOUT their tags, so in Overpass mode their crossing/kerb/tactile
# tags would be missing even though the ways that carry them arrived. The
# converter classifies them into the <region>_crossings.geojson artifact.
#
# Node-only, no recursion -- each node carries its own coordinates -- so this
# is cheap (~3,200 nodes at the 1e scale). A separate request for the same
# reason 1b-1f are separate: the main query is at the mirrors' size ceiling.
#
# ALLOWED TO FAIL, loudly. No crossing data is the behaviour Vector had until
# now (crossing point facts unavailable); the WAY-level footway=crossing
# promotion in the main extraction is unaffected by this fetch's failure.
CROSSINGS_OSM="$CACHE/${REGION}-crossings.osm"
if [ -z "$PBF_MODE" ] && [ ! -s "$CROSSINGS_OSM" ]; then
  echo "[1g] downloading crossing/kerb nodes ..."
  CROSSINGS_QL='[out:xml][timeout:600];
(
  node["highway"="crossing"](bbox:'"$VECTOR_BBOX"');
  node["kerb"](bbox:'"$VECTOR_BBOX"');
);
out body;'
  CROSS_TMP="$WORK/${REGION}-crossings.osm.part"
  for ep in $OVERPASS_ENDPOINTS; do
    [ -s "$CROSSINGS_OSM" ] && break
    [ -n "$ep" ] || continue
    if curl -sL --fail --max-time 600 -X POST --data-urlencode "data=$CROSSINGS_QL" "$ep" -o "$CROSS_TMP" \
       && [ -s "$CROSS_TMP" ] && grep -q "<node" "$CROSS_TMP"; then
      mv "$CROSS_TMP" "$CROSSINGS_OSM"
      echo "[1g] crossing nodes fetched from $ep ($(grep -c \"<node\" "$CROSSINGS_OSM") nodes)"
    else
      rm -f "$CROSS_TMP"
    fi
  done
fi
if [ -n "$PBF_MODE" ]; then
  echo "[1g] crossing/kerb nodes: from the PBF snapshot (3,170 crossing in the 260912 extract)"
elif [ -s "$CROSSINGS_OSM" ]; then
  OSM_PARTS="$OSM_PARTS $CROSSINGS_OSM"
else
  echo "[1g] WARNING: no crossing-node data. Crossing point facts (marked/zebra/" >&2
  echo "[1g]          kerb) are unavailable to the pedestrian layer; the WAY-level" >&2
  echo "[1g]          footway=crossing promotion is unaffected. Delete .bootstrap-" >&2
  echo "[1g]          cache and re-run to retry." >&2
fi

# ---------------------------------------------------------------------------
# 2. Convert OSM -> basemap GeoJSON (tiles + geocoder) + roads-only network.
# ---------------------------------------------------------------------------
GEO="$WORK/${REGION}.geojson"
ROADS="$WORK/${REGION}_roads.geojson"
# V7 Stage 5: signal nodes are written to their own artifact, separate from
# the basemap and the roads network, so signal ingestion cannot change either
# feature count. Routing picks it up by convention (``default_signals_path``
# derives <region>_signals.geojson from the roads path it is given), the same
# trick the foot graph already uses -- no new compose argument to forget.
SIG_ARG="--signals-out $WORK/${REGION}_signals.geojson"
# V7.3: speed cameras ride the same convention and the same constraint -- a
# SEPARATE artifact (<region>_cameras.geojson) so camera ingestion can never
# move the basemap or the roads network feature counts either. Routing picks
# it up by ``default_cameras_path``, mirroring the signals path.
CAM_ARG="--cameras-out $WORK/${REGION}_cameras.geojson"
# V7.4 4A: barrier nodes ride the same convention -- a SEPARATE artifact
# (<region>_barriers.geojson) so barrier ingestion can never move the basemap
# or the network feature counts either, exactly like signals and cameras.
# Routing picks it up by ``default_barriers_path``, derived from the foot
# graph path (serve.py), the same no-compose-change trick.
BAR_ARG="--barriers-out $WORK/${REGION}_barriers.geojson"
# V7.4 4A.4: crossing/kerb nodes ride the same convention -- a SEPARATE
# artifact (<region>_crossings.geojson) carrying the pedestrian crossing
# point facts, consumed by the walking engine from 4B on. Not part of the
# routing graph; never in the basemap counts.
CROSS_ARG="--crossings-out $WORK/${REGION}_crossings.geojson"
echo "[2] converting OSM -> GeoJSON ..."
# shellcheck disable=SC2086 -- OSM_PARTS is a deliberate word-split list.
PYTHONPATH="vector-ingestion/src" python3 vector-tile-gen/scripts/osm_to_geojson.py $OSM_PARTS -o "$GEO" $REL_ARG $SIG_ARG $CAM_ARG $BAR_ARG $CROSS_ARG

# ---------------------------------------------------------------------------
# 2b. OVERTURE PLACES — the other half of the POI layer.
# ---------------------------------------------------------------------------
#
# OSM carries the roads and the surveyed POIs; Overture carries the shops.
# Measured on the live deployment: the OSM basemap holds 15,231 POIs and the
# Overture layer 23,199 more, and it is the Overture half that answers "Turkish
# Grill House" -- the restaurant that started this whole investigation and that
# a driver could see on Google Maps and not on Vector.
#
# It reaches the stack by TWO routes, and both have to be fed:
#
#   * the GEOCODER auto-detects `<index>_places.geojson` beside its index
#     (see vector_geocoder.serve.default_places_path), so staging the file into
#     $WORK is enough -- step 3's `cp /src/*.geojson` carries it to the basemap
#     volume and the service finds it by convention. No new configuration.
#   * the TILES need it merged into the bake input, because
#     `build_qatar_tiles.py --geojson` takes exactly one file. That merge had
#     no home in this script, which is why a clean bootstrap produced tiles
#     with no Overture in them at all while the live tiles have plenty --
#     measured on live z14 10537/7003: 617 POIs, 509 of them Overture.
#
# $GEO STAYS OSM-ONLY. The merged file is a separate bake input under a
# subdirectory so `cp /src/*.geojson` cannot pick it up: the basemap volume
# should hold the same two files the live one does, and the geocoder indexes
# $GEO plus the places file separately -- that is where the existing
# name+position dedupe lives (`GeocodeIndex._is_duplicate`, ~110 m buckets).
# There is NO dedupe between the two sources in the tile pipeline, by design:
# `build_qatar_tiles._tiebreak_key` reads `overture_id` precisely because both
# sources are expected in one bucket, and it settles them source-neutrally.
#
# ALLOWED TO FAIL, loudly. Fewer POIs is a worse map, not a broken one.
PLACES_SRC="$CACHE/${REGION}_places.geojson"
# The Overture script takes minlon,minlat,maxlon,maxlat; $VECTOR_BBOX is
# lat,lon ordered (see .env.example). Derived here so both modes have it --
# $PBF_BBOX only exists when VECTOR_USE_PBF=1.
PLACES_BBOX=$(awk -v bb="$VECTOR_BBOX" 'BEGIN{split(bb,a,",");printf "%s,%s,%s,%s",a[2],a[1],a[4],a[3]}')
if [ ! -s "$PLACES_SRC" ]; then
  echo "[2b] fetching Overture places (bbox $PLACES_BBOX) ..."
  # The script borrows duckdb rather than adding it to the package (the same
  # arrangement as the PBF extractor's osmium). Host first, then uv.
  if python3 -c 'import duckdb' >/dev/null 2>&1; then
    (cd vector-geocoder && python3 scripts/fetch_overture_places.py \
       --bbox "$PLACES_BBOX" --out "$PLACES_SRC") || true
  elif command -v uv >/dev/null 2>&1; then
    (cd vector-geocoder && uv run --with duckdb python scripts/fetch_overture_places.py \
       --bbox "$PLACES_BBOX" --out "$PLACES_SRC") || true
  else
    echo "[2b] no duckdb and no uv - cannot fetch Overture places." >&2
  fi
fi
BAKE_GEO="$GEO"
# Set only when the V1 pipeline runs; `set -u` is on, so it needs a default.
SEARCH_GEO=""
# ---------------------------------------------------------------------------
# 2b POI QUALITY PIPELINE (V1) — replaces the raw byte-splice merge.
#
# The byte-splice below exists ONLY as a fallback. In the normal path the
# canonical POI pipeline (vector-ingestion/poi) runs instead:
#
#   OSM POIs + Overture places -> normalize -> classify -> score
#      -> cross-source reconciliation -> duplicate detection
#      -> driver-usefulness filtering -> canonical set + audit report
#
# Outputs (all under $WORK):
#   bake-input/$REGION.geojson   OSM basemap (all kinds) + canonical POIs
#   $REGION_places.geojson       canonical POIs in the geocoder's shape
#                                (search now resolves the whole Rosary typo
#                                cluster to ONE church instead of four rows)
#   canonical_pois.geojson       the canonical dataset, provenance retained
#   poi-audit.json               every exclusion count, computed from the data
#
# The audit is staged into $WORK/tiles so the tile volume ships it (the tile
# server only reads all-digit directories, so a JSON file at the top level is
# harmless — it is provenance, like provenance.json).
#
# ALLOWED TO FAIL, loudly: a bootstrap host without the module falls back to
# the pre-V1 behaviour (raw merge) rather than failing the whole bake.
merge_places_raw() {
  # BYTE-SPLICE at the END OF THE FEATURES ARRAY, not at end of file.
  #
  # `osm_to_geojson` writes a FeatureCollection with a THIRD top-level key when
  # there are turn restrictions:
  #
  #     {"type": ..., "features": [...], "turn_restrictions": [...]}
  #
  # so the file's final `]}` closes turn_restrictions, not features. Appending
  # there is valid JSON and silently wrong: it puts 23,199 POIs into the
  # turn-restriction list, where the feature count never changes and nothing
  # downstream complains. Caught by counting Overture features in the output;
  # pinned by a test.
  #
  # Parsing the 87 MB base into Python objects would cost a gigabyte or so for
  # a concatenation that needs no structure, so the splice is anchored on the
  # exact separator `json.dump` emits between the two keys, and the script
  # refuses to guess if that anchor is not unique.
  #
  # turn_restrictions is DROPPED from the bake input on purpose:
  # `build_qatar_tiles` reads `features` only, and $GEO -- which the routing
  # graph and the geocoder read -- still carries them.
  python3 - "$GEO" "$PLACES_SRC" "$BAKE_GEO" <<'MERGE_PLACES'
import json, sys
base_path, places_path, out_path = sys.argv[1:4]
base = open(base_path, 'rb').read().rstrip()
places = json.load(open(places_path, encoding='utf-8'))['features']

ANCHOR = b'], "turn_restrictions": ['
n = base.count(ANCHOR)
if n == 1:
    cut = base.index(ANCHOR)
elif n == 0:
    if not base.endswith(b']}'):
        raise SystemExit('bake merge: base is not a single-line FeatureCollection')
    cut = len(base) - 2
else:
    raise SystemExit(f'bake merge: {n} turn_restrictions anchors, refusing to guess')

head = base[:cut]
empty = head.rstrip().endswith(b'[')
with open(out_path, 'wb') as out:
    out.write(head)
    for i, f in enumerate(places):
        if i or not empty:
            out.write(b',')
        out.write(json.dumps(f, ensure_ascii=False).encode('utf-8'))
    out.write(b']}')
print(f'[2b] bake input = OSM basemap + {len(places)} Overture places', flush=True)
MERGE_PLACES
}

if [ -s "$PLACES_SRC" ]; then
  mkdir -p "$WORK/bake-input"
  BAKE_GEO="$WORK/bake-input/${REGION}.geojson"
  # The geocoder basemap is a SEPARATE merged file from the tile bake input.
  # Since V1.2 the bake input carries only the MAP-VISIBLE POIs (OSM feeds the
  # map; Overture Places feeds search only -- vector_ingestion/poi/visibility.py),
  # so pointing step 2c at it would delete ~18k records from SEARCH as a side
  # effect of a MAP decision. search-input/ holds the same basemap with EVERY
  # canonical POI. It lives in a subdirectory for the same reason bake-input
  # does: `cp /src/*.geojson` at step 3 must not pick it up.
  SEARCH_GEO="$WORK/search-input/${REGION}.geojson"
  if [ -d "vector-ingestion/src/vector_ingestion/poi" ]; then
    echo "[2b] POI quality pipeline V1 (normalize/classify/score/reconcile/dedup) ..."
    if PYTHONPATH="vector-ingestion/src" \
       python3 vector-ingestion/scripts/build_canonical_pois.py \
         --osm "$GEO" --places "$PLACES_SRC" --region "$REGION" --out "$WORK"; then
      mkdir -p "$WORK/tiles"
      cp "$WORK/poi-audit.json" "$WORK/tiles/poi-audit.json"
      echo "[2b] canonical POIs -> $WORK/canonical_pois.geojson; audit -> $WORK/poi-audit.json"
    else
      echo "[2b] WARNING: POI quality pipeline failed; falling back to raw merge." >&2
      cp "$PLACES_SRC" "$WORK/${REGION}_places.geojson"
      merge_places_raw
    fi
  else
    echo "[2b] WARNING: vector-ingestion/poi module absent; using raw merge." >&2
    cp "$PLACES_SRC" "$WORK/${REGION}_places.geojson"
    merge_places_raw
  fi
else
  echo "[2b] WARNING: no Overture places. The map and search will carry OSM POIs" >&2
  echo "[2b]          only - roughly 15k instead of 38k, and the shops a driver" >&2
  echo "[2b]          searches for are mostly in the Overture half." >&2
fi

FOOT="$WORK/${REGION}_foot.geojson"
python3 - "$GEO" "$ROADS" "$FOOT" <<'PY'
import json, sys
src, dst, foot_dst = sys.argv[1], sys.argv[2], sys.argv[3]
fc = json.load(open(src, encoding="utf-8"))
# The DRIVING graph, so pedestrian ways are excluded: a footway or a staircase
# still belongs on the map (kind == "road", it gets drawn) but must never be an
# edge a car can be routed along. Before this filter the router returned Doha
# routes that began "Head northwest on footway road".
#
# `car` is absent on graphs built by an older ingestion pass; treat a missing
# flag as drivable so an old extract degrades to the previous behaviour instead
# of producing an empty graph and a stack that 503s on every route.
roads = [
    f for f in fc.get("features", [])
    if f.get("properties", {}).get("kind") == "road"
    and f.get("properties", {}).get("car", True)
]
# Turn restrictions ride along with the DRIVING graph, not the basemap. They are
# constraints on how car edges join, so they belong in the file the router loads
# -- and shipping them together is what makes it impossible for the graph and
# its restrictions to be a re-bake apart. Dropping them here is what previously
# made the whole feature inert: the converter emitted them and this filter,
# which only ever copied `features`, threw them away.
out = {"type": "FeatureCollection", "features": roads}
restrictions = fc.get("turn_restrictions") or []
if restrictions:
    out["turn_restrictions"] = restrictions
json.dump(out, open(dst, "w", encoding="utf-8"))
print("[2] roads features:", len(roads), "| turn restrictions:", len(restrictions))

# The WALKING graph (V7 Phase 2), baked from the same conversion in the same
# pass so the two networks can never be a re-bake apart.
#
# It is a separate file rather than a flag on the driving one because the two
# overlap but neither contains the other: 40,437 Qatar ways are walkable and not
# drivable (footways, staircases, arcades, the Msheireb pedestrian squares) and
# 24,699 are drivable and not walkable (the motorways and the arterials with no
# pavement). Shipping one file and filtering at load would mean the router
# holding the whole 181k-way road layer in memory to use either half.
#
# NO turn restrictions: they are constraints on traffic, and a person walking
# is not traffic. NO `foot` default either -- unlike `car` above, a missing flag
# means NOT walkable, because there is no previous pedestrian behaviour to
# degrade to and "unknown means walkable" would put motorways in the walking
# graph. An empty foot graph is a loud failure; a foot graph full of motorways
# is a silent one. See vector_routing/foot_graph.py.
foot = [
    f for f in fc.get("features", [])
    if f.get("properties", {}).get("kind") == "road"
    and f.get("properties", {}).get("foot")
]
json.dump({"type": "FeatureCollection", "features": foot},
          open(foot_dst, "w", encoding="utf-8"))
print("[2] foot features:", len(foot),
      "| walk-only:", sum(1 for f in foot if not f["properties"].get("car")))
if not foot:
    print("[2] WARNING: the pedestrian network is EMPTY. /foot will report", file=sys.stderr)
    print("[2]          unavailable. This means the OSM conversion emitted no", file=sys.stderr)
    print("[2]          `foot` property -- check vector_ingestion.classify is", file=sys.stderr)
    print("[2]          importable by osm_to_geojson.py.", file=sys.stderr)
PY

# ---------------------------------------------------------------------------
# 2c. SEARCH INDEX BASEMAP <- CANONICAL POIs.
#
# $GEO is consumed in exactly two places: the routing graph (already built
# above) and the geocoder's base index (copied into vector-data-basemap at
# step 3). Pointing the geocoder at the raw OSM file would leave OSM-side
# worker housing, building ids and map furniture searchable even though the
# canonical tiles no longer show them — "Ramada Staff Accommodation" and
# "سكن عين خالد" must not be search results. So $GEO is replaced by the
# pipeline's canonical SEARCH basemap (roads/labels/water unchanged; POIs =
# the canonical set) once the routing graph no longer needs it.
#
# NOT the bake input. Since V1.2 the bake input is filtered to the MAP-VISIBLE
# POIs, and search must not lose a record because the map declined to draw it:
# an Overture-only business like "Movers & Packers Qatar" stays FINDABLE and
# stays OFF the map. $SEARCH_GEO is the unfiltered merge.
#
# The total no longer reconciles to 81k raw entries on purpose: search finds
# one canonical place instead of four rows of the same church.
if [ -s "$SEARCH_GEO" ] && [ "$SEARCH_GEO" != "$GEO" ]; then
  cp "$SEARCH_GEO" "$GEO"
  echo "[2c] geocoder basemap <- canonical search basemap ($GEO)"
elif [ -s "$BAKE_GEO" ] && [ "$BAKE_GEO" != "$GEO" ]; then
  # Fallback path (raw byte-splice merge): no search basemap was produced.
  cp "$BAKE_GEO" "$GEO"
  echo "[2c] geocoder basemap <- bake input, raw merge fallback ($GEO)"
fi

# ---------------------------------------------------------------------------
# 3. Bake MVT tiles into vector-data-tiles; copy glyphs + geodata into volumes.
# ---------------------------------------------------------------------------
echo "[3] baking MVT tiles ..."
# PYTHONPATH must use the OS-native path separator (':' on POSIX, ';' on Windows).
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
# This is the ONLY host-python step with a third-party dependency
# (mapbox-vector-tile, declared in vector-tile-gen/pyproject.toml). The header of
# this script promises "nothing outside the repo is assumed to exist", so when
# the host interpreter does not have it, fall back to the same python:3.11-slim
# container the volume-population steps below already use, rather than failing
# with a bare RuntimeError. A stock Ubuntu box is PEP-668 managed and may not
# even have `python3 -m venv`, so "just pip install it" is not available there.
# build_qatar_tiles.py APPENDS "tiles" to --out (see its out_dir), so --out must
# be $WORK and NOT $WORK/tiles. Passing $WORK/tiles produced $WORK/tiles/tiles/,
# and the volume copy below (`cp -r /src/tiles/*`) then wrote the inner directory
# to /data/tiles — so every baked tile lived at /data/tiles/12/x/y.mvt while the
# tile server looked under /data/12/x/y.mvt. 17,669 tiles baked, all of them
# 404. The map came up blank with a bootstrap that reported success, and it reads
# as the long-standing "streets vanish when I zoom" complaint rather than as a
# path bug.
# The zoom range is ONE definition, used by both branches below.
#
# It was `11,12,13` in both, hardcoded, and that was stale in two directions at
# once: z14 had been added to the live bake by hand (it carries the POIs and the
# residential street grid), so a fresh bootstrap silently produced a LESS
# detailed map than the running one — and nothing below z11 had ever been baked
# at all, so the country, region and city views were empty. MapLibre does not
# under-zoom: below a source's minzoom it requests nothing and draws the
# background colour, which is exactly the "zoom out and Qatar disappears"
# report. z6 is where the whole country fits on a phone screen.
#
# The low zooms are nearly free — 251 tiles and 2.9 MB for z6-z10 against 46,693
# tiles and 304 MB for z14 alone — because `build_qatar_tiles` only emits
# motorway/trunk (z0), primary (z9) and ranked place labels at that scale, and
# drops everything the tile grid cannot resolve.
#
# z15 IS THE ZOOM A DRIVER ACTUALLY SEES, and it is the top of the range for the
# same reason z14 once was: the bake stopping below it does not blank the map,
# it silently degrades it, so nothing reports the loss.
#
# `select_for_tile` caps a tile at 1,500 features and POIs rank last, so at z14
# over central Doha 3,020 POI candidates compete for the 525 slots the 35% floor
# buys them — about five in six named places are dropped, and which five is
# decided by a hash. The SAME AREA at z15 holds 867 features in total and is not
# truncated at all, because a tile covers a quarter of the ground at each step
# up while the cap stays flat. Every POI survives. That was measured and tested
# in 15bb922, which added the z15 selection behaviour and then left this default
# at 14 — so the fix has been paid for and never shipped.
#
# Without z15 baked, the tile server reports `maxzoom: 14` (it derives the range
# from the directories on disk, see vector-web/docker/tileserver.py) and both
# clients declare it. MapLibre then OVERZOOMS the z14 tile for every zoom above
# 14 — and navigation sets the camera to 16.5 — so the driving view is a
# magnified copy of the most heavily truncated tile in the set.
#
# It is not free the way the low zooms are: z15 is four times as many tiles as
# z14 over the same ground. Set VECTOR_TILE_ZOOMS explicitly to trim it on a
# disk-constrained host.
VECTOR_TILE_ZOOMS="${VECTOR_TILE_ZOOMS:-6,7,8,9,10,11,12,13,14,15}"
echo "[3] zoom range: $VECTOR_TILE_ZOOMS"

if python3 -c 'import mapbox_vector_tile' >/dev/null 2>&1; then
  PYTHONPATH="vector-tile-gen/src${SEP}vector-ingestion/src${SEP}vector-map-store/src" \
    python3 vector-tile-gen/scripts/build_qatar_tiles.py --geojson "$BAKE_GEO" --out "$WORK" --zooms "$VECTOR_TILE_ZOOMS"
else
  echo "[3] host python lacks mapbox-vector-tile — baking in a container instead."
  # --user keeps the emitted tiles owned by the invoking user: without it the
  # container writes root-owned dirs into $WORK and the EXIT trap's `rm -rf`
  # cannot unlink them, leaking a multi-hundred-MB directory into /tmp.
  # pip therefore installs with --target (no root-owned site-packages write).
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -v "$WORK:/work" -v "$ROOT:/repo:ro" python:3.11-slim sh -c \
    "pip install --quiet --disable-pip-version-check --target /tmp/deps 'mapbox-vector-tile>=2.2,<4' && \
     PYTHONPATH=/tmp/deps:/repo/vector-tile-gen/src:/repo/vector-ingestion/src:/repo/vector-map-store/src \
     python /repo/vector-tile-gen/scripts/build_qatar_tiles.py \
       --geojson \"/work/$(realpath --relative-to="$WORK" "$BAKE_GEO")\" --out /work --zooms $VECTOR_TILE_ZOOMS"
fi

# The tile artifact must be able to name the snapshot it was baked from.
#
# Provenance is written next to the extract in $CACHE, which is a build-host
# path: the deployed `vector-data-tiles` volume is copied from $WORK/tiles and
# carried nothing identifying at all, so a tile set in production could not be
# traced back to an OSM snapshot. Staged into the tiles directory here so the
# existing `cp -r /src/tiles/*` below carries it with no change to the copy.
#
# Safe for the tile server: it derives the zoom range from entries that are
# BOTH all-digits AND directories (vector-web/docker/tileserver.py), so a JSON
# file at the top level is skipped rather than read as a zoom.
if [ -n "$PBF_MODE" ] && [ -s "$CACHE/${REGION}.osm.provenance.json" ]; then
  cp "$CACHE/${REGION}.osm.provenance.json" "$WORK/tiles/provenance.json"
  echo "[3] tile provenance: release $PBF_REL staged into the tile set"
fi

echo "[3] populating named data volumes ..."
# Both networks land in the same volume. vector-routing derives the pedestrian
# graph path from the car graph path it is already given (serve.
# default_foot_graph_path), so /foot starts answering on the next deploy with no
# docker-compose change -- there is no second `command:` argument to forget.
# The signal artifact (V7 Stage 5) travels the same way: default_signals_path
# derives <region>_signals.geojson beside the roads file it is already given.
# Same again for the camera artifact (V7.3): default_cameras_path.
# Same again for the barrier artifact (V7.4 4A): default_barriers_path derives
# <region>_barriers.geojson beside the foot graph path.
# The crossings artifact (V7.4 4A.4) travels the same way.
docker run --rm -v vector-data-network:/app/graph -v "$WORK:/src:ro" python:3.11-slim sh -c \
  'cp /src/*_roads.geojson /src/*_foot.geojson /src/*_signals.geojson /src/*_cameras.geojson /src/*_barriers.geojson /src/*_crossings.geojson /app/graph/ && ls /app/graph'
docker run --rm -v vector-data-basemap:/data -v "$WORK:/src:ro" python:3.11-slim sh -c \
  'cp /src/*.geojson /data/ && ls /data'
docker run --rm -v vector-data-tiles:/data -v "$WORK:/src:ro" python:3.11-slim sh -c \
  'cp -r /src/tiles/* /data/ && ls /data'
# V7.7: the flat copy above is not servable on its own any more.
#
# `docker-compose.yml` sets TILE_DIR=/app/tiles/current, and `current` is a
# symlink INSIDE this volume pointing at releases/<release-id>/. Without this
# step a fresh bootstrap would produce a volume the tile server cannot read and
# a completely blank map, with nothing reporting a failure -- the exact class
# of silent defect V7.7 exists to end.
#
# The same script the production migration runs (V7.7-MIGRATION-RUNBOOK.md
# step 2), so what a developer bootstraps is the layout production serves. It
# COPIES rather than moves: the flat tree stays, which is what lets TILE_DIR be
# reverted to /app/tiles as a rollback.
#
# The tree has no bake provenance to claim -- it was just copied here -- so the
# release id says `legacy-import` and carries the tree's own digest rather than
# borrowing a commit sha. See vector_tile_gen.release.LEGACY_ID_RE.
echo "[3] importing the flat tile tree as a named release ..."
docker run --rm \
  -v vector-data-tiles:/data \
  -v "$ROOT/vector-tile-gen:/gen:ro" \
  python:3.11-slim python3 /gen/scripts/import_legacy_tree.py /data
docker run --rm -v vector-data-glyphs:/data -v "$ROOT/vector-tile-server/glyphs:/g:ro" python:3.11-slim sh -c \
  'cp -r /g/* /data/ && ls /data'

# ---------------------------------------------------------------------------
# 4. Build the OSRM routing graph into vector-data-osrm (CH).
# ---------------------------------------------------------------------------
echo "[4] building OSRM graph (osrm-extract + osrm-partition + osrm-customize) ..."
# osrm-extract writes its ~20 output files (.osrm, .osrm.ebg, .osrm.timestamp,
# ...) NEXT TO THE INPUT, not to a --output path. Pointing it at the extract
# inside the read-only $WORK mount therefore fails on the first write with
# "Problem opening file: /data/qatar.osrm.timestamp (possible cause: Read-only
# file system)" -- after it has already spent the full parse. Stage the .osm
# into the writable volume and extract THERE, so every artefact lands in
# vector-data-osrm where osrm-partition/-customize and the osrm service read it.
docker run --rm -v vector-data-osrm:/osm -v "$CACHE:/data:ro" osrm/osrm-backend:latest \
  sh -c "cp '/data/${REGION}.osm' '/osm/${REGION}.osm' && osrm-extract -p /opt/car.lua '/osm/${REGION}.osm' && rm -f '/osm/${REGION}.osm'"
docker run --rm -v vector-data-osrm:/osm osrm/osrm-backend:latest \
  osrm-partition "/osm/${REGION}.osrm"
docker run --rm -v vector-data-osrm:/osm osrm/osrm-backend:latest \
  osrm-customize "/osm/${REGION}.osrm"

echo "[bootstrap] done. Next: docker compose up -d  (web at http://localhost:8088)"