"""Which order to migrate in, decided by running both — not by intuition.

THE QUESTION
------------
Production must end up at ``TILE_DIR=/app/tiles/current`` running the
release-aware (Commit 4) tile server. Two changes are needed — the image and
the path — and they can be done in either order or together:

**Option A** — deploy the new image first, against the *flat legacy tree*;
verify; then prepare the release layout and move ``TILE_DIR``. Two recreates.

**Option B** — prepare the layout, then change image and ``TILE_DIR`` together.
One recreate.

Choosing needs one fact that can only be established by running the code: **is
the release-aware server safe on a flat legacy tree?** If it is not, Option A
is impossible. If it is, Option A's intermediate state is a valid resting place
and the two risks can be separated.

The matrix below answers that, and three neighbouring questions, by running
both server implementations against both layouts:

======================  ====================  ==========================
                        flat legacy tree      releases/ + current
======================  ====================  ==========================
old server (pre-C4)     production today      "migrate first" ordering
release-aware (C4)      **Option A step 2**   the target state
======================  ====================  ==========================

The old server is read out of git at ``f85aa4c^`` rather than reimplemented,
because a hand-copied "old" server would only prove what the copy does.
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
_GEN = os.path.join(REPO, "vector-tile-gen")
for _p in (os.path.join(_GEN, "src"), os.path.join(_GEN, "scripts")):
    if os.path.isdir(_p) and _p not in sys.path:
        sys.path.insert(0, _p)


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


NEW = _load("vector_tileserver_new",
            os.path.join(HERE, "..", "docker", "tileserver.py"))

# The tile server as production runs it today: the commit BEFORE the
# release-aware one. Read from git so this is the real thing.
_OLD_REV = "f85aa4c^:vector-web/docker/tileserver.py"


def _old_server():
    try:
        source = subprocess.run(["git", "show", _OLD_REV], cwd=REPO,
                                capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    if source.returncode != 0 or not source.stdout:
        return None
    path = os.path.join(tempfile.mkdtemp(prefix="vector-old-server-"),
                        "tileserver_old.py")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(source.stdout)
    return _load("vector_tileserver_old", path)


OLD = _old_server()


class _Recorder:
    """Captures what a Handler would put on the wire, without a socket."""

    def __init__(self, path, server):
        self.path = path
        self.server = server
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

    def _serve_file(self, *a, **kw):
        return self.server.Handler._serve_file(self, *a, **kw)


def get(server, tile_dir, url_path):
    rec = _Recorder(url_path, server)
    rec.send_error = lambda *a, **k: None
    prev = server.TILE_DIR
    server.TILE_DIR = tile_dir
    try:
        server.Handler.do_GET(rec)
    finally:
        server.TILE_DIR = prev
    return rec


class MigrationOrderingTest(unittest.TestCase):
    """Both servers, both layouts. Four cells, four verdicts."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="vector-ordering-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

        # A production-shaped flat tree: the same zoom span the live volume
        # has (6..15), with the VERSION.json sidecar that is actually there.
        self.flat = os.path.join(self.tmp, "volume")
        for z in range(6, 16):
            d = os.path.join(self.flat, str(z), "21074")
            os.makedirs(d)
            with open(os.path.join(d, "14003.mvt"), "wb") as fh:
                fh.write(b"\x1a\x05flat" + bytes([z]))
        with open(os.path.join(self.flat, "VERSION.json"), "w") as fh:
            json.dump({"epoch": 1789378960, "reason": "legacy"}, fh)

        self.probe = "/tiles/12/21074/14003.mvt"

    def _import(self):
        """Build releases/ + current in the volume, as the migration will."""
        try:
            from import_legacy_tree import import_legacy_tree
        except ImportError:
            self.skipTest("vector-tile-gen not importable")
        report = import_legacy_tree(self.flat)
        self.assertTrue(report["ok"], report["failures"])
        return report["release_id"]

    # -- cell 1: old server, flat tree = production today ------------------

    def test_old_server_on_the_flat_tree_is_production_today(self):
        if OLD is None:
            self.skipTest("cannot read the pre-Commit-4 server from git")

        tile = get(OLD, self.flat, self.probe)
        version = json.loads(get(OLD, self.flat, "/tiles/version").body)

        self.assertEqual(200, tile.status)
        self.assertEqual(6, version["minzoom"])
        self.assertEqual(15, version["maxzoom"])
        self.assertNotIn("release", version,
                         "the pre-V7.7 server knows nothing about releases")
        self.assertGreater(version["epoch"], 0)

    # -- cell 2: NEW server, flat tree = Option A's intermediate state ------

    def test_the_release_aware_server_is_safe_on_the_flat_legacy_tree(self):
        """**The fact that decides the ordering.**

        If this were not true, Option A could not exist: deploying the new
        image before the layout would break production the moment it started.
        It IS true, and by design — Commit 4 kept the mtime walk as a
        documented fallback for exactly this volume.
        """
        tile = get(NEW, self.flat, self.probe)
        version = json.loads(get(NEW, self.flat, "/tiles/version").body)

        self.assertEqual(200, tile.status)
        self.assertEqual(b"\x1a\x05flat\x0c", tile.body)
        self.assertEqual(6, version["minzoom"])
        self.assertEqual(15, version["maxzoom"])
        self.assertEqual("mtime", version["source"])
        self.assertEqual("", version["release"])

    def test_the_intermediate_state_identifies_itself(self):
        """Option A's step 3 needs to be verifiable, not merely survivable.

        `source: "mtime"` and a present-but-empty `release` prove, from the
        outside, that the NEW image is running and that the volume has not been
        migrated yet. The old server emits neither field, so the two states are
        distinguishable with one curl — which is what makes "verify, then
        proceed" a real step rather than a hope.
        """
        old_version = (json.loads(get(OLD, self.flat, "/tiles/version").body)
                       if OLD else {})
        new_version = json.loads(get(NEW, self.flat, "/tiles/version").body)

        self.assertIn("source", new_version)
        self.assertIn("release", new_version)
        if OLD is not None:
            self.assertNotIn("source", old_version)
            self.assertNotIn("release", old_version)

    def test_both_servers_agree_on_the_bytes_of_the_flat_tree(self):
        """The new image must not change what a tile request returns."""
        if OLD is None:
            self.skipTest("cannot read the pre-Commit-4 server from git")

        for z in range(6, 16):
            path = "/tiles/%d/21074/14003.mvt" % z
            with self.subTest(zoom=z):
                self.assertEqual(get(OLD, self.flat, path).body,
                                 get(NEW, self.flat, path).body)

    # -- cell 3: NEW server, release layout = the target state -------------

    def test_the_release_aware_server_on_a_named_release(self):
        release_id = self._import()
        current = os.path.join(self.flat, "current")

        tile = get(NEW, current, self.probe)
        version = json.loads(get(NEW, current, "/tiles/version").body)

        self.assertEqual(200, tile.status)
        self.assertEqual(release_id, version["release"])
        self.assertEqual("manifest", version["source"])
        self.assertEqual("manifest", version["zoom_source"])
        self.assertEqual(6, version["minzoom"])
        self.assertEqual(15, version["maxzoom"])
        self.assertEqual(release_id, tile.headers.get("X-Vector-Release"))

    # -- cell 4: OLD server, release layout --------------------------------

    def test_the_old_server_also_works_through_current(self):
        """So "migrate the layout first" is survivable too.

        `current` is an ordinary directory as far as the old server is
        concerned; it walks it for mtimes and infers zooms from its directory
        names. It reports no release — it cannot — but it serves every tile.

        This is what makes the ordering a genuine choice rather than a forced
        one: neither order can leave production unable to serve.
        """
        if OLD is None:
            self.skipTest("cannot read the pre-Commit-4 server from git")
        self._import()
        current = os.path.join(self.flat, "current")

        tile = get(OLD, current, self.probe)
        version = json.loads(get(OLD, current, "/tiles/version").body)

        self.assertEqual(200, tile.status)
        self.assertEqual(6, version["minzoom"])
        self.assertEqual(15, version["maxzoom"])
        self.assertNotIn("release", version)

    # -- the rollback, from either ordering --------------------------------

    def test_reverting_TILE_DIR_restores_service_with_either_image(self):
        """The migration's rollback, exercised.

        The importer COPIES, so the flat tree is still there. Pointing
        `TILE_DIR` back at it serves the same bytes again — under the new image
        or the old one, which is what makes "revert one compose line" a
        complete rollback rather than a partial one.
        """
        self._import()
        before = get(NEW, self.flat, self.probe).body

        servers = [NEW] + ([OLD] if OLD else [])
        for server in servers:
            with self.subTest(server=server.__name__):
                rolled_back = get(server, self.flat, self.probe)
                self.assertEqual(200, rolled_back.status)
                self.assertEqual(before, rolled_back.body)

    def test_the_flat_tree_survives_the_import_byte_for_byte(self):
        """No data loss to the thing production is currently serving.

        The digest is **identical** afterwards, and that is worth more than
        "no file changed". `iter_tile_paths` descends only all-digit zoom
        directories, so `releases/` is invisible to a census of the volume
        root — the copy is not merely harmless to the flat tree, it is not
        part of it.

        The same rule is why the old server keeps serving throughout
        preparation: its zoom census reads all-digit directory names, and
        `releases` and `current` are neither. (Its `/tiles/version` mtime
        fallback is a separate function, a plain `os.walk`, and that one DOES
        see the copy — which is why the epoch changes and the endpoint slows.)
        """
        try:
            from vector_tile_gen.release import scan_tree, tree_digest
        except ImportError:
            self.skipTest("vector-tile-gen not importable")

        before = tree_digest(scan_tree(self.flat, census=False)["entries"])
        self._import()

        self.assertEqual(
            before, tree_digest(scan_tree(self.flat, census=False)["entries"]),
            "the import perturbed the tree production is serving")
        for z in range(6, 16):
            with open(os.path.join(self.flat, str(z), "21074",
                                   "14003.mvt"), "rb") as fh:
                self.assertEqual(b"\x1a\x05flat" + bytes([z]), fh.read())

    def test_an_unmigrated_volume_under_the_new_TILE_DIR_fails_visibly(self):
        """The failure Part C's compose comment warns about.

        `TILE_DIR=/app/tiles/current` against a volume with no `current` is a
        blank map. It must at least fail in a way that is greppable rather than
        returning plausible-looking empty answers.
        """
        missing = os.path.join(self.flat, "current")  # not imported

        tile = get(NEW, missing, self.probe)
        version = json.loads(get(NEW, missing, "/tiles/version").body)

        self.assertEqual(404, tile.status)
        self.assertEqual("", version["release"])
        self.assertEqual("unknown", version["zoom_source"])
        self.assertNotIn("minzoom", version,
                         "a server with no tiles must not advertise a range")


if __name__ == "__main__":
    unittest.main()
