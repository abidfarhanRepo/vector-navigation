"""Tile stand-in container: serves real generated MVT tiles from /app/tiles.

Stands in for the Rust vector-tile-server (no cargo in this env). The bytes are
genuine MVT tiles produced by vector-tile-gen from real Doha OSM data.

Also serves self-hosted SDF glyph PBFs from /app/glyphs (adr-0059) at
/glyphs/{fontstack}/{range}.pbf so the viewer needs no third-party font CDN.

V7.7: `GET /tiles/version` reports the RELEASE the tile set declares about
itself (RELEASE.json) rather than a number inferred from the filesystem. See
`release_info` for what that replaces and why the old behaviour is still here.
"""

import calendar
import json
import os
import re
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

TILE_DIR = os.environ.get("TILE_DIR", "/app/tiles")
GLYPH_DIR = os.environ.get("GLYPH_DIR", "/app/glyphs")
PORT = int(os.environ.get("PORT", "3000"))


# ---------------------------------------------------------------------------
# Composite font stacks
# ---------------------------------------------------------------------------
#
# MapLibre asks for a comma-joined STACK of fonts and expects the server to
# resolve each codepoint against them in order. That is the only mechanism a
# vector style has for script fallback, and this server treated the whole
# joined string as a directory name -- so a composite request resolved to a
# path that does not exist and fell through to the empty-stack response below.
#
# What it cost, concretely: every font in /app/glyphs is single-script.
#
#     Open Sans Regular   ranges 0-255, 32-126           -- Latin only
#     Noto Kufi Arabic    ranges 1536-1791, 1872-1919,
#                         2208-2303, 64336+              -- Arabic only
#
# So Vector's style had to pin one font per label layer, and in a country where
# 99.5% of named roads carry BOTH an Arabic `name` and an English `name:en`:
#
#   * a Latin road name could not be drawn at all -- the road label layer's
#     only reachable font has no Latin glyphs;
#   * an Arabic place or POI name could not be drawn at all, for the mirror
#     reason;
#   * and switching road labels to `name:en` -- which the tiles have carried
#     all along -- would have produced a map with NO road labels whatsoever.
#
# It could not be fixed in the style. It had to be fixed here.
#
# NOTE: this is a deliberate duplicate of
# `vector-tile-server/src/vector_tile_server/glyphs.py`, which is the tested
# reference implementation. This file is a standalone, dependency-free
# stand-in copied into its image with no package around it (see the module
# docstring), so it cannot import that module. `tests/test_tileserver_glyphs.py`
# pins both to the same behaviour.

_GLYPH_CACHE = {}


def _gv_read(buf, i):
    """Read a varint. Returns (value, next index)."""
    result = 0
    shift = 0
    while True:
        if i >= len(buf):
            raise ValueError("truncated varint")
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def _gv(value):
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _g_fields(buf):
    """Yield (field number, wire type, payload) over a protobuf message."""
    i = 0
    n = len(buf)
    while i < n:
        tag, i = _gv_read(buf, i)
        fnum, wtype = tag >> 3, tag & 7
        if wtype == 0:
            start = i
            _, i = _gv_read(buf, i)
            yield fnum, wtype, buf[start:i]
        elif wtype == 2:
            ln, i = _gv_read(buf, i)
            yield fnum, wtype, buf[i:i + ln]
            i += ln
        elif wtype == 5:
            yield fnum, wtype, buf[i:i + 4]
            i += 4
        elif wtype == 1:
            yield fnum, wtype, buf[i:i + 8]
            i += 8
        else:
            raise ValueError("unsupported wire type %d" % wtype)


def _g_delim(fnum, payload):
    return _gv((fnum << 3) | 2) + _gv(len(payload)) + payload


def _g_id(glyph):
    for fnum, wtype, payload in _g_fields(glyph):
        if fnum == 1 and wtype == 0:
            return _gv_read(payload, 0)[0]
    return None


def _g_glyphs(pbf):
    """Every glyph submessage across every fontstack in a glyphs protobuf.

    glyphs    { repeated fontstack stacks = 1 }
    fontstack { string name = 1; string range = 2; repeated glyph glyphs = 3 }
    glyph     { uint32 id = 1; bytes bitmap = 2; ... }
    """
    for fnum, wtype, stack in _g_fields(pbf):
        if fnum != 1 or wtype != 2:
            continue
        for sfnum, swtype, payload in _g_fields(stack):
            if sfnum == 3 and swtype == 2:
                yield payload


def glyph_stack(glyph_dir, fontstack, rng):
    """Merged glyph range for `fontstack`, or None if no font supplies it.

    Glyphs are copied verbatim -- no bitmap is decoded -- so a merged range is
    bit-identical to the source range for every glyph it took. The FIRST font
    in the stack wins a shared codepoint, which is what makes the order in the
    style mean something: ["Open Sans Regular", "Noto Kufi Arabic"] has to mean
    "Latin from Open Sans, anything it lacks from Noto Kufi".
    """
    key = (fontstack, rng)
    if key in _GLYPH_CACHE:
        return _GLYPH_CACHE[key]

    names = [n.strip() for n in fontstack.split(",") if n.strip()]
    if not names:
        _GLYPH_CACHE[key] = None
        return None

    # Single font: straight off disk, byte for byte. The common case, and it
    # must behave exactly as it did before this function existed.
    if len(names) == 1:
        path = os.path.join(glyph_dir, names[0], "%s.pbf" % rng)
        try:
            with open(path, "rb") as fh:
                data = fh.read()
        except (IOError, OSError):
            data = None
        _GLYPH_CACHE[key] = data
        return data

    seen = set()
    merged = []
    found_any = False
    for name in names:
        path = os.path.join(glyph_dir, name, "%s.pbf" % rng)
        try:
            with open(path, "rb") as fh:
                pbf = fh.read()
        except (IOError, OSError):
            continue
        found_any = True
        try:
            for glyph in _g_glyphs(pbf):
                gid = _g_id(glyph)
                if gid is None or gid in seen:
                    continue
                seen.add(gid)
                merged.append(glyph)
        except ValueError:
            # A corrupt file must not take out the fonts after it in the stack.
            continue

    if not found_any:
        _GLYPH_CACHE[key] = None
        return None

    stack = (
        _g_delim(1, fontstack.encode("utf-8"))
        + _g_delim(2, rng.encode("utf-8"))
        + b"".join(_g_delim(3, g) for g in merged)
    )
    out = _g_delim(1, stack)
    _GLYPH_CACHE[key] = out
    return out


# ---------------------------------------------------------------------------
# Release identity
# ---------------------------------------------------------------------------
#
# What this replaces, and why it is not a refinement of it.
#
# `GET /tiles/version` used to answer with `max(mtime)` over an `os.walk` of the
# whole tree. Three measured consequences, all of them V7.7 reconnaissance:
#
#   * `cp -r` does not preserve mtimes, so the number published was the moment
#     the files were COPIED, not the moment they were BAKED. It was never a
#     function of the content: two copies of identical bytes produced two
#     versions, and one copy of two different bakes produced one.
#   * Being a maximum, ANY partial write advanced it. A transfer dying after
#     3,000 of 18,311 tiles left a union of two bakes announcing a brand-new
#     version, and nothing downstream could tell.
#   * It cost 0.39-0.46 s per request on production's 18,311 files — a tree
#     walk on the one endpoint that must answer instantly, because it is the
#     endpoint a client asks BEFORE it can build a style. That price is the
#     reason it was only ever affordable to ask once per process, which is
#     exactly the behaviour V7.7 needs to change.
#
# So the manifest is not a better epoch. It is the first identity the tile set
# has ever had, and reading it is a `stat` plus a small `json.load` instead of
# a walk of the tree.
#
# THE OLD BEHAVIOUR IS STILL HERE, deliberately. A volume with no RELEASE.json
# is not an error — production is one today, and `TILE_DIR` has not been
# migrated to a release directory yet. Such a volume gets exactly the answer it
# got before this change, and the response SAYS which path produced it
# (`source`), so "the server is release-aware but this volume is not" is never
# something anyone has to infer.

RELEASE_FILENAME = "RELEASE.json"

# A deliberate duplicate of `vector_tile_gen.release.RELEASE_ID_RE`. This file
# is a standalone, dependency-free stand-in copied into its image with no
# package around it (see the module docstring and the same note on
# `glyph_stack`), so it cannot import that module.
# `tests/test_tile_release_version.py` pins the two to the same behaviour.
#
# TWO grammars, because a release has two honest origins. A bake names the
# commit it came from. An imported pre-V7.7 tree has no commit to name, so it
# names its own content digest instead and says `legacy-import` out loud rather
# than borrowing a sha it was not built from. The server accepts both and
# treats them identically: what it needs from an id is that it be well-formed
# and unique, which both are.
_RELEASE_ID_RE = re.compile(
    r"^(?:vector-tiles-\d{4}-\d{2}-\d{2}T\d{4}Z-[0-9a-f]{7,40}(-dirty)?"
    r"|legacy-import-\d{8}T\d{6}Z-[0-9a-f]{16,64})$")

# One slot, written as a whole tuple.
#
# `ThreadingHTTPServer` serves every request on its own thread, so a cache made
# of separately-assigned fields could be read torn — a path from one release
# next to a document from another, which is precisely the class of confusion
# this endpoint exists to end. Rebinding a single tuple is atomic under the
# GIL, and readers take one local reference before looking inside it.
_RELEASE_CACHE = (None, None, None)  # (path, stat key, manifest or None)


def _stat_key(path):
    """Identity of the file at `path`, or None if there is no file there.

    Includes device and inode, not just mtime and size, because the whole point
    of the Option A layout is that `current` is a SYMLINK swapped by rename(2).
    A swap replaces the file this path resolves to; the replacement can easily
    have the same size and, on a fast publish, the same mtime to the second.
    `st_mtime_ns` closes most of that and the inode closes the rest.
    """
    try:
        st = os.stat(path)
    except OSError:
        return None
    return (st.st_dev, st.st_ino, st.st_mtime_ns, st.st_size)


def release_info(tile_dir):
    """The manifest this tile set declares about itself, or None.

    Total, and for the same reason `vector_tile_gen.release.read_manifest` is:
    a served tile set must not be taken down over a version file. Missing,
    unreadable, not JSON, not an object, or carrying an id this server cannot
    recognise all read as None, and None means "fall back and say so".

    Validating the release id here rather than trusting the field is what stops
    a hand-made directory — or a half-written file that happens to parse — from
    presenting itself as a release to every client on the road.

    Cost is one `stat` per call in the steady state. The document is parsed
    again only when the file it came from is no longer the file on disk, which
    is what makes a pointer swap visible to the very next request without
    re-reading JSON on all of them.
    """
    global _RELEASE_CACHE
    path = os.path.join(tile_dir, RELEASE_FILENAME)
    key = _stat_key(path)

    cached_path, cached_key, cached_doc = _RELEASE_CACHE
    if cached_path == path and cached_key == key:
        return cached_doc

    doc = None
    if key is not None:
        try:
            with open(path, encoding="utf-8") as fh:
                parsed = json.load(fh)
        except (OSError, ValueError):
            parsed = None
        if isinstance(parsed, dict) and \
                _RELEASE_ID_RE.match(str(parsed.get("release_id", ""))):
            doc = parsed

    _RELEASE_CACHE = (path, key, doc)
    return doc


def release_id(tile_dir):
    """The active release's id, or "" when the volume declares none."""
    doc = release_info(tile_dir)
    return str(doc.get("release_id", "")) if doc else ""


def _int_field(value):
    """`value` as an int, or None. `True` is not 1 here."""
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def _declared_epoch(doc):
    """The integer an old client busts its cache on. Returns (epoch, source).

    `?v=<release_id>` is the contract from V7.7 onward, but a client built
    before it reads `epoch` and nothing else, so the integer must still change
    per release or those clients keep serving themselves stale tiles out of a
    cache that is behaving perfectly.

    Two honest sources, in order, and no third:

      * `epoch` — stated by the manifest. Every release baked after V7.7.
      * `bake.manifested_at` — the same instant in ISO, which every manifest
        this code has ever written carries. This is a DERIVATION, not a guess:
        it reads a field the release declares about itself, and it exists so
        the two releases baked before `epoch` was added do not have to be
        re-baked to be served correctly.

    A manifest with neither returns None, and the caller falls back to the
    filesystem rather than inventing a number. An invented epoch is worse than
    the old bad one: it would be wrong in a way that looks authoritative.
    """
    epoch = _int_field(doc.get("epoch"))
    if epoch is not None and epoch >= 0:
        return epoch, "manifest"

    bake = doc.get("bake")
    if isinstance(bake, dict):
        for field in ("manifested_at", "finished_at"):
            stamp = bake.get(field)
            if not isinstance(stamp, str) or not stamp:
                continue
            try:
                return (calendar.timegm(time.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ")),
                        "manifest_bake_time")
            except ValueError:
                continue

    return None, ""


def _declared_zooms(doc):
    """The zoom range the release DECLARES, or (None, None).

    Declared rather than inferred from directory names, which is the whole
    reason `coverage` carries it. Under the old inference a partial copy
    landing only z6..z13 of a z6..z15 release silently re-advertised
    `maxzoom: 13`; MapLibre only overzooms ABOVE the declared maximum, so the
    camera at 16.5 that navigation sets got z13 tiles stretched over the
    screen, and the driving view degraded with nothing reporting a failure.

    Declaring the real range makes that same partial copy answer 204/404 for
    the zooms it is missing — a loud, greppable symptom instead of a quiet
    blur. That is a deliberate trade: the gate (`verify_manifest`) is what
    stops a partial copy being served at all, and this endpoint's job is to
    describe the release, not to paper over it.
    """
    coverage = doc.get("coverage")
    if not isinstance(coverage, dict):
        return None, None
    lo = _int_field(coverage.get("minzoom"))
    hi = _int_field(coverage.get("maxzoom"))
    if lo is None or hi is None or lo < 0 or hi < lo:
        return None, None
    return lo, hi


def _inferred_version(tile_dir):
    """The pre-V7.7 answer: newest mtime, and zooms from directory names.

    Kept verbatim in behaviour because an un-migrated volume must not notice
    that this server learned about releases. It is still a full tree walk and
    still costs ~0.4 s on production's tree, which is now a reason to migrate
    rather than a cost every release pays forever.
    """
    epoch = 0
    zooms = []
    try:
        for entry in os.listdir(tile_dir):
            if entry.isdigit() and os.path.isdir(os.path.join(tile_dir, entry)):
                zooms.append(int(entry))
    except OSError:
        pass
    try:
        for root, _dirs, files in os.walk(tile_dir):
            for f in files:
                try:
                    m = os.path.getmtime(os.path.join(root, f))
                    epoch = max(epoch, int(m))
                except OSError:
                    continue
    except Exception:
        pass
    return epoch, (min(zooms) if zooms else None), (max(zooms) if zooms else None)


def version_body(tile_dir):
    """The `/tiles/version` document.

    `release` is the client-facing token: from V7.7 the style puts it in the
    tile URL as `?v=<release_id>`, which is what partitions one release's
    cache entries from another's. It is present and EMPTY on a volume with no
    manifest, rather than absent, so that a reader can tell a server too old to
    know about releases (no field) from a release-aware server on a volume that
    declares none (empty field). Commit 6 needs that distinction; nobody can
    recover it after the fact.

    `epoch` is retained unconditionally and still changes per release, because
    a client that predates the contract reads only that.

    `source`, `epoch_source` and `zoom_source` make the response self-describing.
    The endpoint that exists to say what is being served should not itself
    require a guess about where its own numbers came from — AC-19 cost five
    emulator phases to establish facts a field like this would have stated.
    """
    doc = release_info(tile_dir)

    if doc is None:
        epoch, lo, hi = _inferred_version(tile_dir)
        rid, source, epoch_source = "", "mtime", "mtime"
        zoom_source = "directories" if lo is not None else "unknown"
    else:
        rid, source = str(doc.get("release_id", "")), "manifest"
        epoch, epoch_source = _declared_epoch(doc)
        lo, hi = _declared_zooms(doc)
        zoom_source = "manifest"

        if epoch is None or lo is None:
            # A manifest that cannot supply one of the two things this endpoint
            # must answer. Fall back for THAT answer only — the release id is
            # still known and still correct, and dropping it because a zoom
            # range is malformed would throw away the good half of a document
            # over the bad half.
            inferred_epoch, inferred_lo, inferred_hi = _inferred_version(tile_dir)
            if epoch is None:
                epoch, epoch_source = inferred_epoch, "mtime"
            if lo is None:
                lo, hi = inferred_lo, inferred_hi
                zoom_source = "directories" if lo is not None else "unknown"

    body = {
        "release": rid,
        "epoch": epoch,
        "source": source,
        "epoch_source": epoch_source,
        "zoom_source": zoom_source,
    }
    if lo is not None and hi is not None:
        body["minzoom"] = lo
        body["maxzoom"] = hi
    return body


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        return

    def do_GET(self):
        # Strip the query string BEFORE any path checks. The viewer requests
        # /tiles/{z}/{x}/{y}.mvt?v=<epoch> (Session-50 stale-tile cache
        # busting); keeping ?v= in rel made endswith('.mvt') fail and 404'd
        # EVERY versioned tile request while unversioned curls looked fine.
        import urllib.parse
        rel = urlsplit(self.path).path.lstrip("/")
        if rel in ("", "healthz", "health") or rel.startswith("healthz"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            return
        if rel == "tiles/version":
            # WHAT IS BEING SERVED — the release, and the epoch that predates it.
            #
            # V7.7 moved the answer off the filesystem and onto the tile set's
            # own manifest; `version_body` documents the three defects that
            # closes and the un-migrated volume it stays compatible with.
            #
            # The comment below is the pre-V7.7 rationale, kept because the
            # zoom-range half of it is still exactly why this endpoint exists —
            # only the SOURCE of the range changed, from directory names to
            # what the release declares.
            #
            # ALSO the zoom range the tile set actually contains, because a
            # style that declares a range the bake does not have is a blank map
            # and nothing detects it. Measured on the S24 Ultra: the bake covers
            # z11-13, the Android style declared `maxzoom: 14` and the web style
            # `maxzoom: 18`, so MapLibre — which only overzooms ABOVE the
            # declared maximum — kept requesting z14 tiles that 404. Zooming in
            # past 13 produced a completely black screen with the location puck
            # on it, and navigation sets the camera to zoom 16.5. A driver would
            # have navigated on a blank map.
            #
            # Reporting the range here rather than hardcoding it in two clients
            # is what stops it drifting again: `--zooms` is a bootstrap
            # argument, and the tile set is the only thing that knows what was
            # actually baked.
            body = version_body(TILE_DIR)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            # The one response that must NEVER be cached: it is the response
            # that invalidates all the others.
            self.send_header("Cache-Control", "no-store")
            if body.get("release"):
                self.send_header("X-Vector-Release", body["release"])
            self.end_headers()
            self.wfile.write(json.dumps(body).encode())
            return
        if rel.startswith("glyphs/"):
            # /glyphs/{fontstack}/{range}.pbf -> GLYPH_DIR/{fontstack}/{range}.pbf
            # Decode %20 etc. (MapLibre requests "Open%20Sans%20Regular").
            decoded = urllib.parse.unquote(rel[len("glyphs/"):])
            # Split "{fontstack}/{range}.pbf" rather than joining the whole
            # thing onto GLYPH_DIR: the fontstack may be a comma-joined STACK
            # of fonts, which is not a directory. See glyph_stack().
            fontstack, _, rng_file = decoded.rpartition("/")
            rng = rng_file[:-4] if rng_file.endswith(".pbf") else rng_file
            body = glyph_stack(GLYPH_DIR, fontstack, rng) if fontstack else None
            if body is not None:
                self.send_response(200)
                self.send_header("Content-Type", "application/x-protobuf")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "public, max-age=86400")
                self.end_headers()
                self.wfile.write(body)
            else:
                # Missing range: return an EMPTY but valid glyph stack PBF
                # (a stack with no glyphs) so MapLibre does not hard-fail the
                # style on ranges it probes but we do not ship (e.g. PUA
                # 65024-65279). 0x1a = field 3 (stacks) len 0 -> empty stack.
                self.send_response(200)
                self.send_header("Content-Type", "application/x-protobuf")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "public, max-age=86400")
                self.end_headers()
                self.wfile.write(b"\x1a\x00")
            return
        if not rel.startswith("tiles/") or not rel.endswith(".mvt"):
            self.send_response(404)
            self.end_headers()
            return
        path = os.path.join(TILE_DIR, rel[len("tiles/"):])
        if os.path.isfile(path):
            # A tile that EXISTS is the one worth caching, and until now it was
            # the only response here that carried no caching directive at all:
            # the empty-tile 204 and the glyph ranges below both send
            # max-age=86400, so the cheap answers were cacheable and the 48 KB
            # ones were not. Measured on the 2026-09-14 Qatar drives, that
            # inversion made 87% of all tile traffic a re-download -- 1,127
            # requests for 150 distinct tiles, the same tile fetched again a
            # median of 33 s later, ~48 MB of a mobile data plan spent
            # re-fetching bytes the phone had just thrown away.
            #
            # Caching is already safe by construction: the client puts the
            # release token in the query string (`?v=<release_id>` from V7.7,
            # `?v=<epoch>` before it; see VectorApi.TileSet), so a new release
            # changes every URL and no stale tile can survive one. The header
            # was simply never sent. `immutable` is therefore honest -- this
            # exact URL's bytes never change -- and it stops MapLibre
            # revalidating on the move, which is the part that hurts in a car.
            #
            # `X-Vector-Release` names the release this process would serve the
            # tile FROM. It is a convenience for operators -- `curl -I` beats
            # decoding an MVT -- and it is explicitly NOT proof that a given
            # release's bytes are on the wire. Two reasons, both real:
            #
            #   * this response is `immutable, max-age=604800`, so an
            #     intermediary may replay the header with the body long after
            #     the pointer moved. Within one release's URL space that is
            #     consistent; for an unversioned request it can be stale.
            #   * it is read from the manifest, not from the bytes being sent.
            #
            # Verification stays what Commit 1 §6 defined: compare the decoded
            # response against the manifest's own sample digests.
            headers = {"Cache-Control": "public, max-age=604800, immutable"}
            rid = release_id(TILE_DIR)
            if rid:
                headers["X-Vector-Release"] = rid
            self._serve_file(path, "application/x-protobuf",
                             extra_headers=headers)
            return
        # A missing tile has two completely different meanings, and answering
        # 404 to both is what let the original defect hide.
        #
        #   * The zoom IS baked and this tile simply has nothing in it — open
        #     sea, or desert outside the region bbox. The bake writes no file
        #     where there are no features, so Qatar's low zooms legitimately
        #     have holes: measured on an S24 at country zoom, tiles 6/40/27,
        #     6/41/26, 7/81/54, 7/81/55 and 7/82/53 are all empty water.
        #     **204 No Content**: there is nothing here, and that is the answer.
        #
        #   * The zoom is NOT baked at all. That is a coverage gap, and it is
        #     precisely the failure that made the map blank from z4 to z10 while
        #     every request in the log looked like a normal 404. **404**.
        #
        # MapLibre treats both as an empty tile and renders correctly either
        # way, so this changes no pixel. What it changes is diagnosability: a
        # harness, a log grep or a person can now tell "the sea is empty" from
        # "half the zoom range does not exist", which no amount of staring at
        # 404s could.
        z = None
        parts = rel[len("tiles/"):-len(".mvt")].split("/")
        if len(parts) == 3 and parts[0].isdigit():
            z = int(parts[0])
        if z is not None and os.path.isdir(os.path.join(TILE_DIR, str(z))):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "public, max-age=86400")
            # Named here as well as on the 200. "This zoom exists and this tile
            # is empty" is a claim about a particular release's coverage, and
            # an operator comparing two releases' holes needs to know whose
            # hole they are looking at.
            rid = release_id(TILE_DIR)
            if rid:
                self.send_header("X-Vector-Release", rid)
            self.end_headers()
            return
        self.send_response(404)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

    def _serve_file(self, path, content_type, extra_headers=None):
        if os.path.isfile(path):
            with open(path, "rb") as fh:
                body = fh.read()
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Access-Control-Allow-Origin", "*")
            for k, v in (extra_headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()


if __name__ == "__main__":
    print("tiles serving %s, glyphs %s on :%d" % (TILE_DIR, GLYPH_DIR, PORT))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
