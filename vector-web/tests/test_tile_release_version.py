"""`GET /tiles/version` must report the RELEASE, not the filesystem.

What this pins, and why each part of it is load-bearing.

Production has served a basemap for months whose reported version is
`max(mtime)` over an `os.walk` of the tile tree. V7.7 reconnaissance measured
what that costs: `cp -r` does not preserve mtimes, so the number names the
moment files were COPIED rather than baked; being a maximum, any partial write
advances it; and it costs 0.39-0.46 s per request on 18,311 files, which is why
the client could only ever afford to ask once per process.

Commit 4 moves the answer onto `RELEASE.json`. These tests hold three
properties that are easy to break and expensive to notice:

1.  **The release is named.** `release` is the token the style puts in
    `?v=<release_id>`, and it is what partitions one release's cache entries
    from another's. AC-19 measured that MapLibre's ambient cache is correct
    *provided distinct releases never share a token* — so a regression that
    made two releases report one token would corrupt a cache that is working
    perfectly, silently, and only on drivers' phones.

2.  **An un-migrated volume is untouched.** Production's `TILE_DIR` has no
    manifest and that migration is deliberately deferred. A volume without one
    must get byte-for-byte the answer it got before, or this commit is a
    production outage scheduled for whenever it deploys.

3.  **A pointer swap is visible to the very next request.** The Option A
    layout swaps a `current` symlink with `rename(2)`. A cache that misses that
    swap would pin the server to a release the operator has already rolled
    back from — the exact failure V7.7 exists to end, reintroduced one layer
    down.
"""
import calendar
import importlib.util
import json
import os
import shutil
import tempfile
import time
import unittest

HERE = os.path.dirname(__file__)
_SPEC = importlib.util.spec_from_file_location(
    "vector_tileserver_release_standin",
    os.path.join(HERE, "..", "docker", "tileserver.py"),
)
ts = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ts)


class _Recorder:
    """Captures what a Handler would put on the wire, without a socket."""

    def __init__(self, path):
        self.path = path
        self.status = None
        self.headers = {}
        self.body = b""

    def send_response(self, code):
        self.status = code

    def send_header(self, k, v):
        self.headers[k] = v

    def end_headers(self):
        pass

    @property
    def wfile(self):
        return self

    def write(self, b):
        self.body += b

    def log_message(self, *a):
        pass

    _serve_file = ts.Handler._serve_file


def _get(tile_dir, url_path):
    """Run tileserver's do_GET against `tile_dir` and record the reply."""
    rec = _Recorder(url_path)
    rec.send_error = lambda *a, **k: None
    prev = ts.TILE_DIR
    ts.TILE_DIR = tile_dir
    try:
        ts.Handler.do_GET(rec)
    finally:
        ts.TILE_DIR = prev
    return rec


def _version(tile_dir):
    rec = _get(tile_dir, "/tiles/version")
    return rec, json.loads(rec.body.decode())


# A manifest carrying only the fields this endpoint reads. Deliberately NOT a
# real `build_manifest` output: the server must cope with whatever is on the
# volume, and a test that only ever feeds it a perfect document proves nothing
# about the half-written one.
def _manifest(release_id, *, epoch=1789000000, minzoom=11, maxzoom=15,
              manifested_at="2026-09-19T20:04:00Z", omit_epoch=False):
    doc = {
        "schema_version": 1,
        "release_id": release_id,
        "bake": {"manifested_at": manifested_at,
                 "finished_at": manifested_at},
        "coverage": {"minzoom": minzoom, "maxzoom": maxzoom},
    }
    if not omit_epoch:
        doc["epoch"] = epoch
    return doc


REL_A = "vector-tiles-2026-09-19T2004Z-4d627cd"
REL_B = "vector-tiles-2026-09-19T2104Z-9757c54"


class ReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        # The cache is a module global and would otherwise leak between tests,
        # which would make every result here depend on test ordering.
        ts._RELEASE_CACHE = (None, None, None)

    # -- helpers ----------------------------------------------------------

    def _tiles(self, root, zooms=(11, 12, 13)):
        for z in zooms:
            d = os.path.join(root, str(z), "21074")
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, "14003.mvt"), "wb") as fh:
                fh.write(b"\x1a\x0512345")
        return root

    def _write_manifest(self, root, doc):
        os.makedirs(root, exist_ok=True)
        path = os.path.join(root, "RELEASE.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(doc, fh)
        return path

    # -- 1. the release is named ------------------------------------------

    def test_the_release_names_itself(self):
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(REL_A))

        _, body = _version(root)
        self.assertEqual(REL_A, body["release"])
        self.assertEqual("manifest", body["source"])

    def test_the_epoch_is_the_releases_own_not_the_filesystems(self):
        """The whole defect, in one assertion.

        The tiles here are touched to a mtime far away from the manifest's
        epoch. Under the old behaviour the answer was the mtime; if this ever
        reports it again, the version has gone back to naming a `cp -r`.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(REL_A, epoch=1789000000))
        touched = 1700000000
        for dirpath, _dirs, files in os.walk(root):
            for f in files:
                os.utime(os.path.join(dirpath, f), (touched, touched))

        _, body = _version(root)
        self.assertEqual(1789000000, body["epoch"])
        self.assertEqual("manifest", body["epoch_source"])
        self.assertNotEqual(touched, body["epoch"])

    def test_two_releases_do_not_share_an_epoch(self):
        """The integer must keep changing, for clients that read only it.

        `?v=<release_id>` is the contract from V7.7, but a client built before
        it busts its cache on `epoch` alone. If two releases reported one
        epoch, those clients would serve themselves the old release's tiles out
        of a cache behaving exactly as HTTP requires.
        """
        a = self._tiles(os.path.join(self.tmp, "a"))
        b = self._tiles(os.path.join(self.tmp, "b"))
        self._write_manifest(a, _manifest(REL_A, epoch=1789000000))
        self._write_manifest(b, _manifest(REL_B, epoch=1789003600))

        _, body_a = _version(a)
        ts._RELEASE_CACHE = (None, None, None)
        _, body_b = _version(b)
        self.assertNotEqual(body_a["epoch"], body_b["epoch"])
        self.assertNotEqual(body_a["release"], body_b["release"])

    # -- 2. the declared zoom range ---------------------------------------

    def test_the_declared_zoom_range_beats_the_directories(self):
        """A partial copy must not silently re-advertise a smaller maximum.

        This is the S24 failure, reached by a different route: the release
        declares z11-15, but only z11-13 arrived. Inferring from directory
        names would answer `maxzoom: 13`, MapLibre would overzoom z13 for the
        camera at 16.5 that navigation sets, and the driving view would degrade
        with nothing reporting a failure.

        Answering 15 makes the missing zooms 204/404 instead — a symptom
        somebody can grep for. The gate (`verify_manifest`) is what stops a
        partial copy being served at all; this endpoint's job is to describe
        the release honestly, not to paper over it.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"), zooms=(11, 12, 13))
        self._write_manifest(root, _manifest(REL_A, minzoom=11, maxzoom=15))

        _, body = _version(root)
        self.assertEqual(11, body["minzoom"])
        self.assertEqual(15, body["maxzoom"])
        self.assertEqual("manifest", body["zoom_source"])

    def test_a_malformed_zoom_range_falls_back_without_losing_the_release(self):
        """Half a bad document must not cost the good half.

        The release id is still known and still correct. Dropping it because
        `coverage` is unusable would take the one fact this commit exists to
        establish and throw it away over a field the server can recover by
        other means.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"), zooms=(11, 12, 13))
        doc = _manifest(REL_A)
        doc["coverage"] = {"minzoom": "eleven", "maxzoom": None}
        self._write_manifest(root, doc)

        _, body = _version(root)
        self.assertEqual(REL_A, body["release"])
        self.assertEqual(11, body["minzoom"])
        self.assertEqual(13, body["maxzoom"])
        self.assertEqual("directories", body["zoom_source"])

    # -- 3. the un-migrated volume ----------------------------------------

    def test_a_volume_with_no_manifest_answers_exactly_as_before(self):
        """Production today. This must not change under it."""
        root = self._tiles(os.path.join(self.tmp, "tiles"), zooms=(11, 12, 13))
        touched = 1700000000
        for dirpath, _dirs, files in os.walk(root):
            for f in files:
                os.utime(os.path.join(dirpath, f), (touched, touched))

        rec, body = _version(root)
        self.assertEqual(200, rec.status)
        self.assertEqual(touched, body["epoch"])
        self.assertEqual(11, body["minzoom"])
        self.assertEqual(13, body["maxzoom"])
        self.assertEqual("mtime", body["source"])

    def test_an_un_migrated_volume_says_so_rather_than_staying_silent(self):
        """Present and empty, not absent.

        A reader has to be able to tell a server too old to know about releases
        (no `release` field at all) from a release-aware server on a volume
        that declares none (the field, empty). Commit 6 needs that distinction
        and nobody can recover it after the fact.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        _, body = _version(root)
        self.assertIn("release", body)
        self.assertEqual("", body["release"])

    def test_a_corrupt_manifest_does_not_take_the_basemap_down(self):
        """Serving tiles unlabelled beats not serving tiles."""
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        with open(os.path.join(root, "RELEASE.json"), "w") as fh:
            fh.write('{"release_id": "vector-tiles-2026-09-19T20')  # truncated

        rec, body = _version(root)
        self.assertEqual(200, rec.status)
        self.assertEqual("", body["release"])
        self.assertEqual("mtime", body["source"])

    def test_a_hand_made_directory_cannot_pose_as_a_release(self):
        """The id is validated, not trusted.

        Otherwise anything that parses as JSON with a `release_id` key could
        present itself as a release to every client on the road.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest("todays-tiles-final-v2"))

        _, body = _version(root)
        self.assertEqual("", body["release"])
        self.assertEqual("mtime", body["source"])

    def test_the_release_id_rule_agrees_with_vector_tile_gen(self):
        """`tileserver.py` carries a COPY of the id regex. Pin it.

        The file is a standalone, dependency-free stand-in copied into its
        image with no package around it, so it cannot import the reference —
        the same constraint `glyph_stack` documents. A copy that drifts is
        worse than no copy: a release would be recognised by the publisher and
        rejected by the server, or the reverse, and nothing would fail loudly.
        """
        import sys
        ref_src = os.path.join(HERE, "..", "..", "vector-tile-gen", "src")
        sys.path.insert(0, os.path.abspath(ref_src))
        try:
            from vector_tile_gen import release as ref
        except ImportError:
            self.skipTest("vector_tile_gen not importable")
        finally:
            sys.path.pop(0)

        cases = [
            REL_A,
            REL_B,
            "vector-tiles-2026-09-19T2004Z-4d627cd-dirty",
            "vector-tiles-2026-09-19T2004Z-4d627cdc920c7b16e98bd69b1ce3f6739ac3ab2b",
            "vector-tiles-2026-09-19T2004Z-4d627c",        # sha too short
            "vector-tiles-2026-09-19T2004Z-4D627CD",       # uppercase sha
            "vector-tiles-2026-09-19T2004Z-4d627cd-clean",  # unknown suffix
            "vector-tiles-2026-09-19-4d627cd",             # no time
            "vector-tiles-2026-09-19T2004Z",               # no sha
            "todays-tiles-final-v2",
            "",
        ]
        for case in cases:
            self.assertEqual(
                ref.is_valid_release_id(case),
                bool(ts._RELEASE_ID_RE.match(case)),
                "tileserver's copy of the release-id rule disagrees with "
                "vector_tile_gen.release for %r" % case)

    # -- the epoch is derived, never invented ------------------------------

    def test_a_pre_epoch_manifest_derives_the_epoch_from_its_bake_time(self):
        """The two releases baked before `epoch` existed must still work.

        This is a derivation from a field the release declares about itself,
        not a guess, and it is what saves those releases from having to be
        re-baked to be served correctly.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(
            REL_A, omit_epoch=True, manifested_at="2026-09-19T20:04:00Z"))

        _, body = _version(root)
        self.assertEqual("manifest_bake_time", body["epoch_source"])
        # UTC, explicitly. The manifest's stamp is a `Z` time and the server
        # runs wherever it runs; reading it through local time would make this
        # endpoint's answer depend on the container's timezone.
        self.assertEqual(
            calendar.timegm(time.strptime("2026-09-19T20:04:00Z",
                                          "%Y-%m-%dT%H:%M:%SZ")),
            body["epoch"])

    def test_an_epoch_that_cannot_be_derived_is_not_invented(self):
        """Falls back to the filesystem and SAYS it did.

        An invented epoch would be worse than the old bad one: wrong in a way
        that looks authoritative.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        doc = _manifest(REL_A, omit_epoch=True)
        doc["bake"] = {"manifested_at": "not a timestamp", "finished_at": ""}
        self._write_manifest(root, doc)
        # Stamped AFTER the manifest is written, and over the whole tree
        # including `RELEASE.json` — the fallback is the old `max(mtime)` walk
        # and that walk sees every file under TILE_DIR, the manifest among
        # them. Touching only the tiles would leave the manifest's own write
        # time as the maximum and this assertion would be testing the clock.
        touched = 1700000000
        for dirpath, _dirs, files in os.walk(root):
            for f in files:
                os.utime(os.path.join(dirpath, f), (touched, touched))

        _, body = _version(root)
        self.assertEqual(REL_A, body["release"])
        self.assertEqual("mtime", body["epoch_source"])
        self.assertEqual(touched, body["epoch"])

    def test_a_boolean_is_not_an_epoch(self):
        """`True` is 1 in Python and would sail straight through an int check."""
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        doc = _manifest(REL_A)
        doc["epoch"] = True
        self._write_manifest(root, doc)

        _, body = _version(root)
        self.assertNotEqual(True, body["epoch"])
        self.assertNotEqual(1, body["epoch"])
        self.assertEqual("manifest_bake_time", body["epoch_source"])

    # -- 3. the pointer swap ----------------------------------------------

    def test_a_pointer_swap_is_visible_to_the_very_next_request(self):
        """Option A activation, against the real serving path.

        Two release directories and a `current` symlink swapped the way the
        publisher will swap it — a temporary symlink renamed over the target,
        which is `rename(2)` and therefore atomic. A cache that missed this
        would pin the server to a release the operator has already rolled back
        from.
        """
        releases = os.path.join(self.tmp, "releases")
        a = self._tiles(os.path.join(releases, REL_A))
        b = self._tiles(os.path.join(releases, REL_B))
        self._write_manifest(a, _manifest(REL_A, epoch=1789000000))
        self._write_manifest(b, _manifest(REL_B, epoch=1789003600))

        current = os.path.join(self.tmp, "current")
        os.symlink(a, current)
        _, before = _version(current)
        self.assertEqual(REL_A, before["release"])

        tmp_link = os.path.join(self.tmp, ".current.%s.tmp" % REL_B)
        os.symlink(b, tmp_link)
        os.rename(tmp_link, current)

        _, after = _version(current)
        self.assertEqual(REL_B, after["release"])
        self.assertEqual(1789003600, after["epoch"])

    def test_a_swap_is_seen_even_when_mtime_and_size_match(self):
        """Why `_stat_key` carries the inode.

        Two manifests of identical size, stamped to the identical mtime — which
        a fast publish can genuinely produce. Keying the cache on (mtime, size)
        alone would serve release A's identity for release B indefinitely, and
        the symptom would be an endpoint confidently naming the wrong release.
        """
        releases = os.path.join(self.tmp, "releases")
        a = self._tiles(os.path.join(releases, REL_A))
        b = self._tiles(os.path.join(releases, REL_B))
        # Same-length ids, so the two documents are byte-identical in length.
        self._write_manifest(a, _manifest(REL_A, epoch=1789000000))
        self._write_manifest(b, _manifest(REL_B, epoch=1789000001))
        stamp = 1789000500
        ma, mb = (os.path.join(a, "RELEASE.json"), os.path.join(b, "RELEASE.json"))
        os.utime(ma, (stamp, stamp))
        os.utime(mb, (stamp, stamp))
        self.assertEqual(os.path.getsize(ma), os.path.getsize(mb),
                         "fixture must pin size equal for this to prove anything")

        current = os.path.join(self.tmp, "current")
        os.symlink(a, current)
        _, before = _version(current)
        self.assertEqual(REL_A, before["release"])

        tmp_link = os.path.join(self.tmp, ".current.tmp")
        os.symlink(b, tmp_link)
        os.rename(tmp_link, current)

        _, after = _version(current)
        self.assertEqual(REL_B, after["release"])

    def test_the_manifest_is_not_re_parsed_on_every_request(self):
        """A `stat` in the steady state, not a parse.

        The old endpoint cost 0.39-0.46 s per request because it walked 18,311
        files, and that price is the reason the client could only afford to ask
        once per process. Commit 5 makes the client ask on every resume, which
        is only reasonable while this stays cheap.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(REL_A))

        parses = []
        real_load = ts.json.load

        def counting_load(fh, *a, **k):
            parses.append(1)
            return real_load(fh, *a, **k)

        ts.json.load = counting_load
        try:
            for _ in range(5):
                _version(root)
        finally:
            ts.json.load = real_load

        self.assertEqual(1, len(parses),
                         "RELEASE.json was parsed %d times for 5 requests"
                         % len(parses))

    # -- headers -----------------------------------------------------------

    def test_the_version_response_is_still_never_cached(self):
        """It is the response that invalidates all the others."""
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(REL_A))
        rec, _ = _version(root)
        self.assertEqual("no-store", rec.headers.get("Cache-Control"))

    def test_a_served_tile_names_its_release(self):
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(REL_A))
        rec = _get(root, "/tiles/11/21074/14003.mvt?v=%s" % REL_A)
        self.assertEqual(200, rec.status)
        self.assertEqual(REL_A, rec.headers.get("X-Vector-Release"))

    def test_an_empty_tile_names_its_release_too(self):
        """Whose coverage hole is this? An operator comparing two needs to know."""
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(REL_A))
        rec = _get(root, "/tiles/11/99999/99999.mvt?v=%s" % REL_A)
        self.assertEqual(204, rec.status)
        self.assertEqual(REL_A, rec.headers.get("X-Vector-Release"))

    def test_no_release_header_is_sent_when_there_is_no_release(self):
        """Absent beats empty here: a header is a claim, and there is none."""
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        rec = _get(root, "/tiles/11/21074/14003.mvt?v=1789000000")
        self.assertEqual(200, rec.status)
        self.assertNotIn("X-Vector-Release", rec.headers)

    def test_the_server_still_ignores_the_query_parameter(self):
        """`?v=` partitions the CLIENT's cache. It must not route anything.

        AC-19 measured this on production and the contract depends on it: the
        parameter's only job is to give each release its own URL space.
        """
        root = self._tiles(os.path.join(self.tmp, "tiles"))
        self._write_manifest(root, _manifest(REL_A))
        one = _get(root, "/tiles/11/21074/14003.mvt?v=1")
        two = _get(root, "/tiles/11/21074/14003.mvt?v=%s" % REL_B)
        self.assertEqual(200, one.status)
        self.assertEqual(one.body, two.body)


if __name__ == "__main__":
    unittest.main()
