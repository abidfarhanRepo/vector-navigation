"""Tile release identity: what a published tile set *is*, stated by itself.

THE FAILURE THIS CLOSES
-----------------------
Vector has shipped a basemap for months with no release identity at all. The
number production reports as its tile version is a **filesystem mtime**:
``GET /tiles/version`` does not read ``VERSION.json``, it ``os.walk``s the tree
and returns ``max(mtime)`` (``vector-web/docker/tileserver.py:241-262``; the
same file inside the running production container greps ``VERSION`` zero
times). Three consequences, all measured in V7.7 reconnaissance:

1. ``cp -r`` does not preserve mtimes, so the published number is the moment
   files were **copied**, not the moment they were **baked**. It is not a
   function of the content. Two copies of identical bytes produce two different
   versions; one copy of two different bakes produces one version.
2. Because it is ``max(mtime)``, **any** partial write advances it. A transfer
   that dies after 3,000 of 18,311 tiles leaves a union of two bakes announcing
   a brand-new version.
3. The ``?v=`` built from it binds nothing — the tile server strips the query
   before path resolution, and production returns byte-identical responses for
   ``?v=1`` and ``?v=2``. **A 200 at ``?v=N`` is not evidence that release N is
   being served.**

So this module does not strengthen a weak identity. It creates the first one.

WHAT A MANIFEST HAS TO BE ABLE TO ANSWER
----------------------------------------
Standing alone, with no other artifact and no access to the machine that baked
it: *what is this, where did it come from, and is it intact?* That is why the
manifest carries the source snapshot's checksum rather than its name, the
generator scripts' own hashes rather than a version string, and a Merkle digest
over every tile rather than a count.

The counts matter too, and for a specific reason. ``coverage.minzoom/maxzoom``
are **declared** here so the tile server can stop inferring them from directory
names — an inference under which a partial copy landing only z6..z13 silently
re-advertises ``maxzoom: 13``, MapLibre overzooms z13 for a camera at 16.5, and
the driving view degrades with nothing reporting a failure. That class of
defect has shipped before (``bootstrap.sh:1140-1160``).

COST, MEASURED
--------------
On the real 18,273-tile Qatar bake: hashing the whole tree takes **0.3 s**,
decoding the whole tree for the layer census takes **16.3 s**. Both are
affordable once per bake, which is why this module asserts what is in the tiles
instead of trusting the baker's own count.

TOTALITY, AND WHERE IT STOPS
----------------------------
``read_manifest`` is deliberately total — it never raises, because a served
tile set must not be taken down over a malformed version file, exactly as
``tile_version.read_version`` reasoned. ``verify_manifest`` is the opposite: it
is a **gate**, and it fails loudly and specifically. Being lenient in a gate is
how a partial release becomes a production incident.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple

RELEASE_FILENAME = "RELEASE.json"
DIGESTS_FILENAME = "TILE_DIGESTS.tsv"

# Bumped only for a breaking change to the manifest's shape. A reader that does
# not recognise the schema must refuse the release rather than guess at it.
SCHEMA_VERSION = 1

# The single canonical layer name the live MapLibre style filters on. A tile
# without it is invisible. The whole rule — basemap, optionally plus the V8
# `lanes` layer, nothing else — is `vector_tile_gen.layers`, which every gate
# shares rather than copies.
from .layers import (  # noqa: E402
    BASEMAP_LAYER as EXPECTED_LAYER,
    CLIP_BUFFER,
    LANES_LAYER,
    OPTIONAL_LAYERS,
    check_layer_names,
    geometry_out_of_range,
    raw_layers,
)

# Files that live inside a release directory but are NOT tiles, and so are not
# covered by the tree digest. RELEASE.json cannot cover itself (it contains the
# digest), and TILE_DIGESTS.tsv is derived from the digest it would perturb.
NON_TILE_FILES = frozenset({RELEASE_FILENAME, DIGESTS_FILENAME})

# The layout the publisher, the importer and the migration preflight all build
# and inspect. Defined once here so the three cannot disagree about where a
# release lives — a disagreement that would surface as "the release was
# published and the server cannot see it".
RELEASES_SUBDIR = "releases"

RELEASE_ID_PREFIX = "vector-tiles"

# vector-tiles-2026-09-19T2135Z-8f4f2b4[-dirty]
RELEASE_ID_RE = re.compile(
    r"^" + re.escape(RELEASE_ID_PREFIX)
    + r"-(?P<stamp>\d{4}-\d{2}-\d{2}T\d{4}Z)"
    + r"-(?P<sha>[0-9a-f]{7,40})"
    + r"(?P<dirty>-dirty)?$"
)

DIRTY_SUFFIX = "-dirty"

# ---------------------------------------------------------------------------
# The imported legacy snapshot
# ---------------------------------------------------------------------------
#
# A tile tree that predates V7.7 has NO PROVENANCE. Production's was `cp -r`'d
# into a Docker volume from an unknown commit at an unknown time, and its
# mtimes name the copy rather than the bake — the defect that started V7.7. A
# normal release id embeds the commit it was baked from, and for such a tree
# there is no honest value to put there.
#
# Inventing one would be the worst available option. A release id reading
# `vector-tiles-<stamp>-8f4f2b4` asserts "this was baked from 8f4f2b4", and for
# an imported tree that assertion is false — permanently, in a string that will
# be copied into deploy logs, screenshots and bug reports long after anyone
# remembers it was a guess.
#
# So an import gets its own grammar, and the grammar itself says what it is:
#
#     legacy-import-20260920T041515Z-ca07394aeab5d58a
#     ^^^^^^^^^^^^^                  ^^^^^^^^^^^^^^^^
#     not a bake                     tree_digest[:16]
#
# The identity component is the tree's own Merkle digest, which is the only
# thing about an unprovenanced tree that IS knowable, is stable across copies
# (unlike mtimes), and collides only if the content is identical — in which
# case sharing an id is correct rather than dangerous. Sixteen hex characters
# is 64 bits; the birthday bound on that is ~4 billion releases, against a
# domain that will see hundreds.
#
# `[A-Za-z0-9-]` only — the `T` and `Z` come from the timestamp, as they do in
# the bake grammar. Every character is RFC 3986 *unreserved*, so the id is
# URL-safe with no escaping, which is the property that makes it usable
# verbatim as the `?v=` cache token. A digest with padding would not be.
LEGACY_PREFIX = "legacy-import"

# legacy-import-20260920T041515Z-ca07394aeab5d58a
LEGACY_ID_RE = re.compile(
    r"^" + re.escape(LEGACY_PREFIX)
    + r"-(?P<stamp>\d{8}T\d{6}Z)"
    + r"-(?P<digest>[0-9a-f]{16,64})$"
)

# How much of the tree digest goes into the id.
LEGACY_DIGEST_CHARS = 16


# ---------------------------------------------------------------------------
# Release identity
# ---------------------------------------------------------------------------

def format_release_id(*, now_s: float, git_sha: str, dirty: bool = False) -> str:
    """Build a release id from a UTC instant and the generating commit.

    Minute resolution, not second: the id appears in directory names, log
    lines, ssh commands and screenshots, and a 14-character timestamp that a
    human can read back is worth more than a collision domain nobody will ever
    exhaust. **Uniqueness is never assumed from the timestamp** — the publisher
    enforces it by refusing to write into an existing ``releases/<id>``, which
    is the only check that actually holds under a re-run.

    A dirty tree is recorded in the id itself rather than hidden in a field,
    because the id is what gets copied into a deploy log by hand.
    """
    sha = (git_sha or "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
        raise ValueError(f"git sha must be 7-40 hex chars, got {git_sha!r}")
    stamp = time.strftime("%Y-%m-%dT%H%MZ", time.gmtime(now_s))
    return f"{RELEASE_ID_PREFIX}-{stamp}-{sha}{DIRTY_SUFFIX if dirty else ''}"


def format_legacy_id(*, now_s: float, tree_digest: str) -> str:
    """Name an imported tree by the only thing that is true about it.

    Second resolution, not minute: an import is a one-off operation that can
    legitimately be retried within a minute (a refused import, a corrected
    argument), and two attempts against the same tree would otherwise collide
    on both the stamp AND the digest, which is the one case the publisher's
    "refuse an existing directory" rule turns into a confusing failure rather
    than a clear one.
    """
    digest = (tree_digest or "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{%d,64}" % LEGACY_DIGEST_CHARS, digest):
        raise ValueError(
            f"tree digest must be at least {LEGACY_DIGEST_CHARS} hex chars, "
            f"got {tree_digest!r}")
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(now_s))
    return f"{LEGACY_PREFIX}-{stamp}-{digest[:LEGACY_DIGEST_CHARS]}"


def parse_release_id(release_id: str) -> Dict[str, Any]:
    """Split a release id into its parts. Raises ValueError if malformed.

    Strict on purpose: this is what stops a hand-typed directory name from
    being treated as a release.

    ``kind`` distinguishes the two grammars, and every caller that cares about
    provenance must branch on it rather than on the shape of the string. A
    ``bake`` names the commit it came from; a ``legacy-import`` names its own
    content and claims nothing about where it was made.
    """
    m = RELEASE_ID_RE.match(release_id or "")
    if m:
        return {
            "release_id": release_id,
            "kind": "bake",
            "stamp": m.group("stamp"),
            "git_sha": m.group("sha"),
            "dirty": bool(m.group("dirty")),
            "provenance": "commit",
        }
    m = LEGACY_ID_RE.match(release_id or "")
    if m:
        return {
            "release_id": release_id,
            "kind": "legacy-import",
            "stamp": m.group("stamp"),
            "git_sha": "",
            "dirty": False,
            "tree_digest_prefix": m.group("digest"),
            "provenance": "imported",
        }
    raise ValueError(f"not a valid release id: {release_id!r}")


def is_valid_release_id(release_id: str) -> bool:
    return bool(RELEASE_ID_RE.match(release_id or "")
                or LEGACY_ID_RE.match(release_id or ""))


def is_legacy_release_id(release_id: str) -> bool:
    return bool(LEGACY_ID_RE.match(release_id or ""))


def is_publishable_release_id(release_id: str) -> bool:
    """A release built from an uncommitted tree must not reach production.

    It is perfectly fine for local work and for the emulator — it is simply not
    reproducible, and a production basemap that cannot be rebuilt from a commit
    is not auditable.

    An imported legacy tree IS publishable, which looks like an exception and
    is not. The rule being enforced is "a release must not silently claim a
    provenance it does not have". A ``-dirty`` bake breaks that rule by naming
    a commit whose tree it was not built from; a ``legacy-import`` keeps it by
    naming no commit at all. The first lies, the second declines to answer —
    and production has been serving exactly such a tree for months already.
    """
    if not is_valid_release_id(release_id):
        return False
    return not parse_release_id(release_id)["dirty"]


def git_head(repo_root: str) -> Dict[str, Any]:
    """Short sha + dirty flag for ``repo_root``. Never raises.

    Returns ``{"sha": "", "dirty": True}`` when git is unavailable or the path
    is not a work tree — conservative in both fields, so a caller that cannot
    identify the generator cannot accidentally produce a publishable id.
    """
    def _run(args: Sequence[str]) -> Optional[str]:
        try:
            out = subprocess.run(
                list(args), cwd=repo_root, capture_output=True, text=True, timeout=30,
            )
        except (OSError, subprocess.SubprocessError):
            return None
        if out.returncode != 0:
            return None
        return out.stdout.strip()

    sha = _run(["git", "rev-parse", "--short=7", "HEAD"]) or ""
    if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
        return {"sha": "", "dirty": True}
    status = _run(["git", "status", "--porcelain"])
    return {"sha": sha, "dirty": status is None or bool(status.strip())}


# ---------------------------------------------------------------------------
# Canonical serialisation and hashing
# ---------------------------------------------------------------------------

def canonical_json(doc: Any) -> bytes:
    """Byte-stable JSON: sorted keys, no incidental whitespace, UTF-8.

    Two manifests describing the same release must hash identically regardless
    of the order a dict happened to be built in, or the digest is a measure of
    Python's insertion order rather than of the release.
    """
    return json.dumps(
        doc, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def manifest_digest(manifest: Dict[str, Any]) -> str:
    """Digest of a manifest with its own integrity block removed.

    A document cannot contain its own hash, so the ``integrity`` block is
    excluded. Everything else — identity, provenance, config, coverage, layer
    inventory — is covered, which is what makes tampering with the *claims*
    detectable and not merely tampering with the tiles.
    """
    doc = {k: v for k, v in manifest.items() if k != "integrity"}
    return sha256_bytes(canonical_json(doc))


def tree_digest(entries: Sequence[Tuple[str, str, int]]) -> str:
    """Merkle-style root over every tile: sha256 of sorted ``path\\tsha\\tbytes``.

    Order-independent by construction (the input is sorted here, not by the
    caller's filesystem walk), so the same tree on two machines digests the
    same. Sensitive to addition, removal, truncation and mutation — which is
    precisely the set of things a half-finished ``cp -r`` does, and precisely
    what ``max(mtime)`` could not see.
    """
    h = hashlib.sha256()
    for relpath, digest, nbytes in sorted(entries, key=lambda e: e[0]):
        h.update(f"{relpath}\t{digest}\t{nbytes}\n".encode("utf-8"))
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Scanning a baked tree
# ---------------------------------------------------------------------------

def iter_tile_paths(tiles_dir: str):
    """Every ``*.mvt`` under ``<tiles_dir>/<z>/<x>/<y>.mvt``, sorted.

    Only all-digit zoom directories are descended, matching the tile server's
    own rule, so a stray file or a ``releases``-adjacent directory can never be
    mistaken for a zoom.
    """
    try:
        zooms = sorted(
            (e for e in os.listdir(tiles_dir)
             if e.isdigit() and os.path.isdir(os.path.join(tiles_dir, e))),
            key=int,
        )
    except OSError:
        return
    for z in zooms:
        zp = os.path.join(tiles_dir, z)
        try:
            xs = sorted((e for e in os.listdir(zp) if e.isdigit()), key=int)
        except OSError:
            continue
        for x in xs:
            xp = os.path.join(zp, x)
            if not os.path.isdir(xp):
                continue
            try:
                ys = sorted(e for e in os.listdir(xp) if e.endswith(".mvt"))
            except OSError:
                continue
            for fn in ys:
                yield f"{z}/{x}/{fn}"


def scan_tree(tiles_dir: str, *, census: bool = True,
              geometry_range: bool = False) -> Dict[str, Any]:
    """One pass over the tree: hash every tile, optionally decode it too.

    Deliberately a single pass. Each tile is read once and both hashed and
    decoded from the same bytes, because reading 18,273 files three times to
    answer three questions is how a validation step becomes something people
    skip. Measured on the real Qatar bake: 0.3 s to hash, 16.3 s to hash and
    decode.

    ``census=False`` skips decoding, which is the right choice only when the
    caller genuinely does not need layer claims — a manifest built without a
    census cannot assert what is in its tiles, and ``build_manifest`` refuses
    to produce one.

    ``geometry_range=True`` also walks every feature's geometry commands and
    records any vertex outside ``[-CLIP_BUFFER, extent + CLIP_BUFFER]`` (the
    ``geometry_range`` block). It is independent of ``census`` because it is
    stdlib-only (:func:`layers.geometry_out_of_range`): the production gate
    runs ``--no-census`` in a container with no MVT library, and this is the
    one check that must still run there. About 13 s on the 18,314-tile tree.
    """
    entries: List[Tuple[str, str, int]] = []
    zooms: Dict[int, int] = {}
    bytes_total = 0
    empty_tiles = 0
    kinds: Dict[str, int] = {}
    layer_names: Dict[str, int] = {}
    building_features = 0
    building_features_with_height = 0
    building_ids: set = set()
    undecodable: List[Tuple[str, str]] = []
    no_layer_tiles: List[str] = []
    bad_layer_tiles: List[Tuple[str, str]] = []
    # V8 lanes census. Lane features are tile INSTANCES like buildings, but
    # the length is clipped to each tile's own square, so summing it over the
    # tree counts every metre of marking once however many tiles a run spans.
    lane_tiles_by_zoom: Dict[int, int] = {}
    lane_features = 0
    lane_layer_bytes = 0
    lane_length_m = 0.0
    # Out-of-tile geometry (see layers.CLIP_BUFFER).
    geo_tiles = 0
    geo_features = 0
    geo_by_layer: Dict[str, int] = {}
    geo_worst: Optional[Dict[str, Any]] = None
    geo_unreadable: List[Tuple[str, str]] = []

    decode = None
    if census:
        from vector_tile_gen.encode import decode_tile  # local: keeps import cheap
        decode = decode_tile

    for relpath in iter_tile_paths(tiles_dir):
        full = os.path.join(tiles_dir, relpath)
        try:
            with open(full, "rb") as fh:
                data = fh.read()
        except OSError as exc:
            undecodable.append((relpath, f"unreadable: {exc}"))
            continue

        entries.append((relpath, sha256_bytes(data), len(data)))
        bytes_total += len(data)
        z = int(relpath.split("/", 1)[0])
        zooms[z] = zooms.get(z, 0) + 1

        if not data:
            # A zero-byte tile is legitimately empty ocean or desert — the bake
            # writes nothing where there is nothing. Counted, never a failure.
            empty_tiles += 1
            continue

        if geometry_range:
            try:
                offending = geometry_out_of_range(data)
            except (ValueError, UnicodeDecodeError) as exc:
                geo_unreadable.append((relpath, str(exc)))
                offending = []
            if offending:
                geo_tiles += 1
            for lname, nbad, worst in offending:
                key = str(lname)
                geo_features += nbad
                geo_by_layer[key] = geo_by_layer.get(key, 0) + nbad
                if geo_worst is None or abs(worst) > abs(geo_worst["value"]):
                    geo_worst = {"tile": relpath, "layer": key, "value": worst}

        if decode is None:
            continue
        try:
            dec = decode(data)
        except Exception as exc:  # noqa: BLE001 - any decoder failure is a finding
            undecodable.append((relpath, f"decode error: {exc}"))
            continue

        tile_layers = dec.get("layers", [])
        if not tile_layers:
            # A tile that has BYTES but decodes to no layers renders as nothing
            # and is invisible to every check that only inspects the layers it
            # finds. `validate_tiles._classify` has always called this BAD
            # ("no layers"); the census missed it until a 4-byte unknown-field
            # payload was fed through both and only one of them objected.
            #
            # This is the Session 50 "streets vanish" class exactly: on disk,
            # counted, digested, consistent with its own manifest — and blank.
            no_layer_tiles.append(relpath)
            continue

        try:
            raw = raw_layers(data)
        except ValueError as exc:
            undecodable.append((relpath, f"decode error: {exc}"))
            continue
        ok_layers, why = check_layer_names([n for n, _ in raw])
        if not ok_layers:
            bad_layer_tiles.append((relpath, why))
        lane_layer_bytes += sum(len(body) for n, body in raw if n == LANES_LAYER)

        for layer in tile_layers:
            layer_names[layer.get("name")] = layer_names.get(layer.get("name"), 0) + 1
            if layer.get("name") == LANES_LAYER:
                feats = layer.get("features", [])
                lane_features += len(feats)
                lane_tiles_by_zoom[z] = lane_tiles_by_zoom.get(z, 0) + 1
                x_t, y_t = (int(v) for v in relpath[:-len(".mvt")].split("/")[1:3])
                lane_length_m += _clipped_length_m(
                    feats, z, x_t, y_t, int(layer.get("extent") or 4096))
                continue
            if layer.get("name") != EXPECTED_LAYER:
                # Counted in layer_names (and failed by bad_layer_tiles); its
                # features are not basemap kinds and must not be tallied as such.
                continue
            for feat in layer.get("features", []):
                props = feat.get("properties") or {}
                kind = props.get("kind")
                kinds[kind] = kinds.get(kind, 0) + 1
                if kind == "building":
                    # COUNTS TILE FEATURE INSTANCES, not buildings in Qatar.
                    # A footprint is emitted into every tile it touches and at
                    # every zoom it is visible at, so one building can be
                    # counted many times. Measured on a real bake: 1,382
                    # instances for 798 distinct ids.
                    #
                    # The name matters because V7.6 reports SOURCE-level counts
                    # ("975 heights in, 975 out", 189,866 footprints). Calling
                    # this `buildings_total` invites a reader to compare the two
                    # and conclude the pipeline lost or invented buildings when
                    # it did neither.
                    building_features += 1
                    fid = feat.get("id")
                    if fid is not None:
                        building_ids.add(fid)
                    if props.get("height_m") not in (None, ""):
                        building_features_with_height += 1

    return {
        "entries": entries,
        "tiles_total": len(entries),
        "bytes_total": bytes_total,
        "empty_tiles": empty_tiles,
        "zooms": zooms,
        "censused": census,
        "kinds": kinds,
        "layer_names": layer_names,
        "building_features": building_features,
        "building_features_with_height_m": building_features_with_height,
        "distinct_building_ids": len(building_ids),
        "undecodable": undecodable,
        "no_layer_tiles": no_layer_tiles,
        "bad_layer_tiles": bad_layer_tiles,
        "lanes": {
            "tiles_with_lanes": sum(lane_tiles_by_zoom.values()),
            "tiles_with_lanes_by_zoom": {str(k): v for k, v in sorted(lane_tiles_by_zoom.items())},
            "total_lane_features": lane_features,
            "total_lane_geometry_length_m": round(lane_length_m, 1),
            "lane_layer_bytes": lane_layer_bytes,
        },
        "geometry_range": {
            "checked": geometry_range,
            "buffer": CLIP_BUFFER,
            "tiles_out_of_range": geo_tiles,
            "features_out_of_range": geo_features,
            "features_out_of_range_by_layer": dict(sorted(geo_by_layer.items())),
            "worst": geo_worst,
            "unreadable": geo_unreadable,
        },
    }


def _clipped_length_m(features, z: int, x: int, y: int, extent: int) -> float:
    """Metres of line geometry inside ONE tile's square.

    Lane runs are written whole into every tile they touch (as roads are), so
    an unclipped sum would count a run once per tile. Clipping each segment to
    ``[0, extent]`` on both axes before measuring is what makes the tree total a
    real length. Tile-local units are converted at the tile centre's latitude;
    across a z15 tile (~1.1 km) the scale varies by well under 0.1%.
    """
    import math
    n = 2 ** z
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 0.5) / n))))
    m_per_unit = 40075016.686 * math.cos(math.radians(lat)) / n / extent
    total = 0.0
    for feat in features:
        for line in feat.get("geometry") or []:
            for (x0, y0), (x1, y1) in zip(line, line[1:]):
                seg = _clip_segment(x0, y0, x1, y1, 0.0, float(extent))
                if seg:
                    total += math.hypot(seg[2] - seg[0], seg[3] - seg[1])
    return total * m_per_unit


def _clip_segment(x0, y0, x1, y1, lo, hi):
    """Liang-Barsky clip of one segment to the square [lo, hi]^2, or None."""
    t0, t1 = 0.0, 1.0
    dx, dy = x1 - x0, y1 - y0
    for p, q in ((-dx, x0 - lo), (dx, hi - x0), (-dy, y0 - lo), (dy, hi - y0)):
        if p == 0:
            if q < 0:
                return None
            continue
        r = q / p
        if p < 0:
            if r > t1:
                return None
            t0 = max(t0, r)
        else:
            if r < t0:
                return None
            t1 = min(t1, r)
    return (x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy)


# ---------------------------------------------------------------------------
# The deterministic probe sample
# ---------------------------------------------------------------------------

def choose_sample(entries: Sequence[Tuple[str, str, int]],
                  *, per_zoom: int = 2) -> List[Dict[str, Any]]:
    """Pick tiles to probe through the public edge after activation.

    Two properties are load-bearing:

    * **Deterministic.** Selection is ordered by ``sha256(relpath)``, never by
      ``random`` and never by Python's ``hash()`` (which is salted per process,
      so it would pick a different sample on every run and make the manifest
      unreproducible). The same tree always yields the same sample, on any
      machine, in any interpreter.
    * **Spans every zoom.** Up to ``per_zoom`` tiles from each zoom present, so
      probing the sample catches a release that dropped an entire zoom — the
      silent ``maxzoom`` regression this whole module exists to make
      impossible.

    Zero-byte tiles are excluded: a 204 proves nothing about which release is
    serving it, so an empty tile is useless as a provenance probe.
    """
    by_zoom: Dict[int, List[Tuple[str, str, int]]] = {}
    for relpath, digest, nbytes in entries:
        if nbytes == 0:
            continue
        z = int(relpath.split("/", 1)[0])
        by_zoom.setdefault(z, []).append((relpath, digest, nbytes))

    sample: List[Dict[str, Any]] = []
    for z in sorted(by_zoom):
        ordered = sorted(
            by_zoom[z],
            key=lambda e: (hashlib.sha256(e[0].encode("utf-8")).hexdigest(), e[0]),
        )
        for relpath, digest, nbytes in ordered[:max(0, per_zoom)]:
            sample.append({"path": relpath, "sha256": digest, "bytes": nbytes})
    return sample


# ---------------------------------------------------------------------------
# Building, writing and reading the manifest
# ---------------------------------------------------------------------------

def build_manifest(
    tiles_dir: str,
    *,
    release_id: str,
    source: Dict[str, Any],
    generator: Dict[str, Any],
    input_config: Dict[str, Any],
    bake_started_at: Optional[str] = None,
    bake_finished_at: Optional[str] = None,
    compat: Optional[Dict[str, Any]] = None,
    sample_per_zoom: int = 2,
    scan: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Describe a baked tile tree completely enough to verify it later.

    Refuses to describe an empty tree, and refuses to produce a manifest
    without a layer census: a manifest that claims layers it never looked at is
    worse than no manifest, because it is a claim that a gate will later trust.
    """
    if not is_valid_release_id(release_id):
        raise ValueError(f"not a valid release id: {release_id!r}")

    sc = scan if scan is not None else scan_tree(tiles_dir, census=True)
    if not sc.get("censused"):
        raise ValueError("a manifest requires a layer census; scan with census=True")
    if sc["tiles_total"] == 0:
        raise ValueError(f"no tiles found under {tiles_dir!r}")

    zooms = sc["zooms"]
    entries = sc["entries"]
    # ONE instant, two representations. `epoch` and `bake.manifested_at` are
    # the same moment rendered for two different readers, and deriving both
    # from a single `now_s` is what stops them ever disagreeing by a second
    # across a clock tick — a disagreement nobody would notice until it was
    # being used to explain which of two releases is older.
    now_s = int(time.time())
    now_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now_s))

    manifest: Dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "release_id": release_id,
        # The integer the OLD client reads.
        #
        # `release_id` is the identity this manifest exists to establish, and
        # the tile URL contract is `?v=<release_id>`. But a client that predates
        # that contract reads `epoch` from `GET /tiles/version` and busts its
        # cache on the integer alone, so the integer has to keep changing per
        # release or those clients stop re-fetching. Today it does change,
        # accidentally: production derives it from `max(mtime)`, which is not a
        # function of the content and advances on a partial write. Stating it
        # here makes it a property of the RELEASE instead.
        #
        # Monotonic because releases are produced in time order. Unique for the
        # same reason `releases/<id>` is: the publisher refuses to write into an
        # existing release directory, and the id is minute-resolution, so two
        # distinct releases cannot share a second without first having failed to
        # share a directory name. Uniqueness is therefore enforced by the
        # publisher, never assumed from the clock — the rule `format_release_id`
        # already states.
        "epoch": now_s,
        "source": dict(source),
        "generator": dict(generator),
        "input_config": dict(input_config),
        "bake": {
            "started_at": bake_started_at or "",
            "finished_at": bake_finished_at or now_iso,
            "manifested_at": now_iso,
        },
        "coverage": {
            # DECLARED, not inferred. This is what lets the tile server stop
            # deriving its advertised zoom range from directory names.
            "minzoom": min(zooms),
            "maxzoom": max(zooms),
            "tiles_per_zoom": {str(z): zooms[z] for z in sorted(zooms)},
            "tiles_total": sc["tiles_total"],
            "empty_tiles": sc["empty_tiles"],
            "bytes_total": sc["bytes_total"],
        },
        "layers": {
            "source_layer": EXPECTED_LAYER,
            "layer_names": dict(sc["layer_names"]),
            "kinds": {str(k): v for k, v in sorted(sc["kinds"].items(),
                                                   key=lambda kv: str(kv[0]))},
            # Tile feature INSTANCES (see scan_tree). Not comparable to V7.6's
            # source-level footprint and height counts.
            "building_features": sc["building_features"],
            "building_features_with_height_m": sc["building_features_with_height_m"],
            "distinct_building_ids": sc["distinct_building_ids"],
            # V8: the optional companion layer, measured from the tiles like
            # everything above. `lane_zooms` is declared because the layer is
            # deliberately partial (z15 only): a reader must not infer from
            # `coverage` that every zoom carries it.
            "lanes": _lanes_block(sc),
        },
        "compat": dict(compat or {"style_schema": "vector-basemap-1"}),
    }

    manifest["integrity"] = {
        "algorithm": "sha256",
        "tree_digest": tree_digest(entries),
        "tile_digests_file": DIGESTS_FILENAME,
        "sample": choose_sample(entries, per_zoom=sample_per_zoom),
        "manifest_sha256": manifest_digest(manifest),
    }
    return manifest


def _lanes_block(sc: Dict[str, Any]) -> Dict[str, Any]:
    ln = sc.get("lanes") or {}
    by_zoom = ln.get("tiles_with_lanes_by_zoom") or {}
    return {
        "lane_enabled": bool(ln.get("tiles_with_lanes")),
        "lane_zooms": sorted(int(z) for z in by_zoom),
        "tiles_with_lanes": int(ln.get("tiles_with_lanes") or 0),
        "tiles_with_lanes_by_zoom": dict(by_zoom),
        "total_lane_features": int(ln.get("total_lane_features") or 0),
        "total_lane_geometry_length_m": float(ln.get("total_lane_geometry_length_m") or 0.0),
        "lane_layer_bytes": int(ln.get("lane_layer_bytes") or 0),
    }


def release_path(tiles_dir: str) -> str:
    return os.path.join(tiles_dir, RELEASE_FILENAME)


def digests_path(tiles_dir: str) -> str:
    return os.path.join(tiles_dir, DIGESTS_FILENAME)


def write_digests(tiles_dir: str, entries: Sequence[Tuple[str, str, int]]) -> str:
    """Write the per-tile digest table, atomically.

    Separate from the manifest because it is large (18,273 lines on the real
    bake) and because the manifest should stay readable by a person under
    pressure. It is what turns "the tree digest does not match" into "these
    four tiles are wrong".
    """
    path = digests_path(tiles_dir)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        for relpath, digest, nbytes in sorted(entries, key=lambda e: e[0]):
            fh.write(f"{relpath}\t{digest}\t{nbytes}\n")
    os.replace(tmp, path)
    return path


def write_manifest(tiles_dir: str, manifest: Dict[str, Any]) -> str:
    """Write ``RELEASE.json`` atomically, as the last act of a bake.

    Ordering is the same rule ``tile_version.bump_version`` follows and for the
    same reason: the manifest is a promise about bytes that are already on
    disk. Written first, it would be a fresh identity pinned to an unfinished
    tree — the original defect with extra ceremony.
    """
    os.makedirs(tiles_dir, exist_ok=True)
    path = release_path(tiles_dir)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, sort_keys=True, indent=2, ensure_ascii=False)
        fh.write("\n")
    os.replace(tmp, path)
    return path


def read_manifest(tiles_dir: str) -> Optional[Dict[str, Any]]:
    """Read ``RELEASE.json``. Returns None if absent, unreadable or malformed.

    Total by design. A tile server must start and serve tiles from a release
    directory whose manifest is missing or corrupt — it will fall back to the
    old inferred behaviour and say so — because taking the basemap down over a
    version file is a worse outcome than serving it unlabelled.

    Callers that need a *guarantee* about the release must use
    ``verify_manifest``, which is a gate and fails loudly.
    """
    path = release_path(tiles_dir)
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (OSError, ValueError):
        return None
    if not isinstance(doc, dict):
        return None
    if not is_valid_release_id(str(doc.get("release_id", ""))):
        return None
    return doc


# ---------------------------------------------------------------------------
# Verification — the gate
# ---------------------------------------------------------------------------

def _fail(failures: List[Dict[str, str]], code: str, detail: str) -> None:
    failures.append({"code": code, "detail": detail})


def verify_manifest(
    tiles_dir: str,
    manifest: Optional[Dict[str, Any]] = None,
    *,
    census: bool = True,
    scan: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Check a tree against its manifest. Machine-readable, and strict.

    Every failure carries a stable ``code`` so a publisher can branch on it and
    a human can grep for it. The codes, and the real failure each one catches:

    ``manifest_missing``      the release has no identity at all
    ``schema_unsupported``    a manifest this code cannot honestly interpret
    ``release_id_invalid``    a hand-made directory posing as a release
    ``release_id_mismatch``   the manifest describes a different release
    ``no_tiles``              an empty tree with a manifest on top
    ``tree_digest_mismatch``  addition, removal, truncation or mutation
    ``tile_missing``          named in the manifest, absent on disk
    ``tile_extra``            on disk, absent from the manifest
    ``tile_digest_mismatch``  same path, different bytes
    ``zoom_range_mismatch``   declared range is not what is on disk
    ``zoom_count_mismatch``   a zoom lost or gained tiles — the partial copy
    ``tiles_total_mismatch``  the headline count is not the tree
    ``layer_unexpected``      a tile whose layers break the shared rule
                              (vector_tile_gen.layers): no basemap, an unknown
                              layer, or a duplicate
    ``lanes_mismatch``        the V8 lanes claim is not what the tiles contain
    ``buildings_mismatch``    a 3D claim the tiles do not support
    ``undecodable_tile``      corrupt bytes that would render as blank streets
    ``tile_no_layers``        decodable bytes, zero layers — a blank tile
    ``sample_invalid``        a probe target that cannot prove anything

    ``census=False`` skips the decode pass and, with it, every layer and
    building assertion. The report records that it did, so a caller can never
    mistake a cheap check for a complete one.
    """
    failures: List[Dict[str, str]] = []

    if manifest is None:
        manifest = read_manifest(tiles_dir)
    if manifest is None:
        return {
            "ok": False,
            "tiles_dir": tiles_dir,
            "release_id": None,
            "census": census,
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "failures": [{"code": "manifest_missing",
                          "detail": f"no readable {RELEASE_FILENAME} in {tiles_dir}"}],
            "observed": {},
        }

    release_id = str(manifest.get("release_id", ""))

    if manifest.get("schema_version") != SCHEMA_VERSION:
        _fail(failures, "schema_unsupported",
              f"manifest schema_version={manifest.get('schema_version')!r}, "
              f"this code understands {SCHEMA_VERSION}")
    if not is_valid_release_id(release_id):
        _fail(failures, "release_id_invalid", f"{release_id!r}")

    # A manifest carried into the wrong directory is a real deploy accident,
    # and the directory name is the only independent witness available.
    dirname = os.path.basename(os.path.normpath(tiles_dir))
    if is_valid_release_id(dirname) and release_id and dirname != release_id:
        _fail(failures, "release_id_mismatch",
              f"directory {dirname!r} holds manifest for {release_id!r}")

    sc = scan if scan is not None else scan_tree(tiles_dir, census=census)
    observed_entries = sc["entries"]

    if sc["tiles_total"] == 0:
        _fail(failures, "no_tiles", f"no .mvt files under {tiles_dir}")

    integrity = manifest.get("integrity") or {}
    coverage = manifest.get("coverage") or {}
    layers = manifest.get("layers") or {}

    # --- integrity -------------------------------------------------------
    observed_tree = tree_digest(observed_entries)
    declared_tree = str(integrity.get("tree_digest", ""))
    if declared_tree and observed_tree != declared_tree:
        _fail(failures, "tree_digest_mismatch",
              f"declared {declared_tree[:16]}…, observed {observed_tree[:16]}…")

        # The digest only says "something is wrong". Say WHAT, using the
        # per-tile table when it is present, because "these four tiles" is
        # actionable at 2am and "the hashes differ" is not.
        declared_map = _read_digests(tiles_dir)
        if declared_map is not None:
            observed_map = {p: (d, n) for p, d, n in observed_entries}
            missing = sorted(set(declared_map) - set(observed_map))
            extra = sorted(set(observed_map) - set(declared_map))
            for p in missing[:20]:
                _fail(failures, "tile_missing", p)
            if len(missing) > 20:
                _fail(failures, "tile_missing", f"… and {len(missing) - 20} more")
            for p in extra[:20]:
                _fail(failures, "tile_extra", p)
            if len(extra) > 20:
                _fail(failures, "tile_extra", f"… and {len(extra) - 20} more")
            changed = [p for p in sorted(set(declared_map) & set(observed_map))
                       if declared_map[p][0] != observed_map[p][0]]
            for p in changed[:20]:
                _fail(failures, "tile_digest_mismatch", p)
            if len(changed) > 20:
                _fail(failures, "tile_digest_mismatch",
                      f"… and {len(changed) - 20} more")

    # --- coverage --------------------------------------------------------
    observed_zooms = sc["zooms"]
    if observed_zooms:
        if coverage.get("minzoom") != min(observed_zooms) or \
                coverage.get("maxzoom") != max(observed_zooms):
            _fail(failures, "zoom_range_mismatch",
                  f"declared {coverage.get('minzoom')}-{coverage.get('maxzoom')}, "
                  f"on disk {min(observed_zooms)}-{max(observed_zooms)}")
        declared_per_zoom = {str(k): int(v)
                             for k, v in (coverage.get("tiles_per_zoom") or {}).items()}
        observed_per_zoom = {str(z): n for z, n in observed_zooms.items()}
        for z in sorted(set(declared_per_zoom) | set(observed_per_zoom), key=int):
            d, o = declared_per_zoom.get(z), observed_per_zoom.get(z)
            if d != o:
                _fail(failures, "zoom_count_mismatch",
                      f"z{z}: declared {d}, on disk {o}")
    if coverage.get("tiles_total") != sc["tiles_total"]:
        _fail(failures, "tiles_total_mismatch",
              f"declared {coverage.get('tiles_total')}, on disk {sc['tiles_total']}")

    # --- layers and the 3D claim ----------------------------------------
    if census:
        for relpath, reason in sc["undecodable"]:
            _fail(failures, "undecodable_tile", f"{relpath}: {reason}")
        for relpath in sc["no_layer_tiles"][:20]:
            _fail(failures, "tile_no_layers", relpath)
        if len(sc["no_layer_tiles"]) > 20:
            _fail(failures, "tile_no_layers",
                  f"… and {len(sc['no_layer_tiles']) - 20} more")
        # Per TILE, with the shared rule: an aggregate over the tree would let
        # a `lanes`-only tile or a duplicated layer through, because every
        # NAME it contains is individually allowed.
        for relpath, why in sc.get("bad_layer_tiles", [])[:20]:
            _fail(failures, "layer_unexpected", f"{relpath}: {why}")
        if len(sc.get("bad_layer_tiles", [])) > 20:
            _fail(failures, "layer_unexpected",
                  f"… and {len(sc['bad_layer_tiles']) - 20} more")
        for name in sc["layer_names"]:
            if name != EXPECTED_LAYER and name not in OPTIONAL_LAYERS:
                _fail(failures, "layer_unexpected",
                      f"tiles carry layer {name!r}, style filters on "
                      f"{EXPECTED_LAYER!r} and would render nothing")
        # The lanes claim. A manifest from before V8 has no block, and is
        # honest only for a tree with no lanes layer in it.
        declared_lanes = layers.get("lanes")
        observed_lanes = _lanes_block(sc)
        if declared_lanes is None:
            if observed_lanes["lane_enabled"]:
                _fail(failures, "lanes_mismatch",
                      f"tiles carry {observed_lanes['total_lane_features']} lane "
                      f"features the manifest does not declare")
        else:
            for field in ("lane_enabled", "lane_zooms", "tiles_with_lanes",
                          "total_lane_features", "lane_layer_bytes"):
                if declared_lanes.get(field) != observed_lanes[field]:
                    _fail(failures, "lanes_mismatch",
                          f"{field}: manifest claims {declared_lanes.get(field)!r}, "
                          f"tiles contain {observed_lanes[field]!r}")
            dl = float(declared_lanes.get("total_lane_geometry_length_m") or 0.0)
            if abs(dl - observed_lanes["total_lane_geometry_length_m"]) > 1.0:
                _fail(failures, "lanes_mismatch",
                      f"total_lane_geometry_length_m: manifest claims {dl}, "
                      f"tiles contain {observed_lanes['total_lane_geometry_length_m']}")
        for field in ("building_features", "building_features_with_height_m",
                      "distinct_building_ids"):
            declared = layers.get(field)
            observed = sc[field]
            if declared is not None and int(declared) != int(observed):
                _fail(failures, "buildings_mismatch",
                      f"{field}: manifest claims {declared}, tiles contain {observed}")

    # --- the probe sample ------------------------------------------------
    observed_map = {p: (d, n) for p, d, n in observed_entries}
    for item in integrity.get("sample") or []:
        path = str(item.get("path", ""))
        if path not in observed_map:
            _fail(failures, "sample_invalid", f"{path}: not in the tree")
            continue
        digest, nbytes = observed_map[path]
        if item.get("sha256") != digest:
            _fail(failures, "sample_invalid", f"{path}: sha256 differs from the tile")
        elif item.get("bytes") != nbytes:
            _fail(failures, "sample_invalid", f"{path}: byte count differs")
        elif nbytes == 0:
            _fail(failures, "sample_invalid",
                  f"{path}: zero-byte tile cannot prove which release served it")

    return {
        "ok": not failures,
        "tiles_dir": tiles_dir,
        "release_id": release_id,
        "census": census,
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "failures": failures,
        "observed": {
            "tiles_total": sc["tiles_total"],
            "bytes_total": sc["bytes_total"],
            "empty_tiles": sc["empty_tiles"],
            "zooms": {str(z): n for z, n in sorted(sc["zooms"].items())},
            "tree_digest": observed_tree,
            "layer_names": dict(sc["layer_names"]),
            "building_features": sc["building_features"],
            "building_features_with_height_m": sc["building_features_with_height_m"],
            "distinct_building_ids": sc["distinct_building_ids"],
            "no_layer_tiles": len(sc["no_layer_tiles"]),
            "undecodable_tiles": len(sc["undecodable"]),
            "bad_layer_tiles": len(sc.get("bad_layer_tiles", [])),
            "lanes": _lanes_block(sc) if census else None,
        },
    }


def _read_digests(tiles_dir: str) -> Optional[Dict[str, Tuple[str, int]]]:
    """Parse ``TILE_DIGESTS.tsv`` if present. None when absent or unparseable."""
    path = digests_path(tiles_dir)
    try:
        with open(path, encoding="utf-8") as fh:
            out: Dict[str, Tuple[str, int]] = {}
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 3:
                    return None
                out[parts[0]] = (parts[1], int(parts[2]))
            return out
    except (OSError, ValueError):
        return None
