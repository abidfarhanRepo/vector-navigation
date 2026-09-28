"""VECTOR_USE_PBF=1 must build from ONE snapshot and touch no Overpass mirror.

The migration is only worth anything if the supplementary requests actually
stop. `bootstrap.sh` acquires the basemap in five groups — [1] main, [1b]
restrictions, [1c] coastline, [1d] POI areas, [1e] POI nodes — and only [1] is
mirror-sticky, so in Overpass mode the groups can come from backends months
apart. Measured 2026-09-13, three mirrors answered the same query from
2026-09-13, 2026-07-15 and 2026-05-31.

PBF mode replaces all five with one dated Geofabrik snapshot. If even one
supplementary request survives, the build is mixed-vintage again and nothing
downstream can tell — which is exactly the defect being fixed, so it is worth a
test that fails loudly rather than a comment asking people to be careful.

The behavioural tests run the REAL acquisition section of `bootstrap.sh` under
bash with `curl` replaced by a stub that fails if it is called at all.
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_BOOTSTRAP = os.path.join(_ROOT, "bootstrap.sh")

_STUB_CURL = """#!/bin/sh
echo "STUB_CURL_CALLED: $*" >&2
exit 99
"""

# A curl that SUCCEEDS, writing a minimal valid Overpass response to the -o
# path. Needed to test what a *working* fresh bootstrap leaves behind: the
# routing-input bug is invisible when acquisition fails, because the script
# exits before step 4 anyway.
_STUB_CURL_OK = """#!/bin/sh
out=""; prev=""
for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; prev="$a"; done
[ -n "$out" ] || exit 1
cat > "$out" <<'X'
<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="stub"><meta osm_base="2026-09-13T00:00:00Z"/>
<node id="1" lat="25.30" lon="51.50"/><node id="2" lat="25.31" lon="51.51"/>
<way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way>
</osm>
X
exit 0
"""


def _acquisition_snippet():
    """The acquisition region of bootstrap.sh, verbatim.

    Starts at `osm_query()` rather than at 0b because [1] calls `osm_query` and
    `fetch_osm`, and a snippet that omits them would "pass" the Overpass-mode
    test for the wrong reason — the mirror is never contacted because the
    function is missing, not because the path works.
    """
    out, keep = [], False
    with open(_BOOTSTRAP, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("osm_query() {"):
                keep = True
            elif line.startswith("# 2. Convert OSM"):
                break
            if keep:
                out.append(line)
    assert out, "could not find the acquisition region"
    assert "# 0b. OSM SOURCE" in "".join(out), "0b missing from the snippet"
    return "".join(out)


@unittest.skipUnless(shutil.which("bash"), "bash required")
class AcquisitionModeTest(unittest.TestCase):
    def _run(self, pbf_mode, seed_snapshot):
        d = tempfile.mkdtemp()
        try:
            cache = os.path.join(d, "cache")
            os.makedirs(cache)
            bindir = os.path.join(d, "bin")
            os.makedirs(bindir)
            stub = os.path.join(bindir, "curl")
            with open(stub, "w") as fh:
                fh.write(_STUB_CURL)
            os.chmod(stub, 0o755)
            # `fetch_osm` backs off 30s between rounds. Real behaviour, wrong
            # thing to spend a test suite's wall clock on.
            nosleep = os.path.join(bindir, "sleep")
            with open(nosleep, "w") as fh:
                fh.write("#!/bin/sh\nexit 0\n")
            os.chmod(nosleep, 0o755)

            if seed_snapshot:
                with open(os.path.join(cache, "qatar.osm"), "w") as fh:
                    fh.write('<?xml version="1.0"?><osm version="0.6">'
                             '<way id="1"/></osm>')
                with open(os.path.join(cache, "qatar.osm.provenance.json"), "w") as fh:
                    fh.write('{"release": "260912", "pbf_md5": "abc123"}\n')

            script = os.path.join(d, "acq.sh")
            with open(script, "w") as fh:
                fh.write(_acquisition_snippet())
                fh.write('\necho "___OSM_PARTS=$OSM_PARTS"\n')
                fh.write('echo "___REL_ARG=$REL_ARG"\n')

            env = dict(os.environ)
            env.update({
                "PATH": bindir + os.pathsep + env["PATH"],
                "CACHE": cache, "REGION": "qatar", "WORK": d, "ROOT": _ROOT,
                "VECTOR_BBOX": "24.4,50.7,26.2,51.8",
                "VECTOR_BBOX_TILES": "1",
                "OVERPASS_ENDPOINTS": "http://stub.invalid",
                "VECTOR_USE_PBF": "1" if pbf_mode else "0",
            })
            p = subprocess.run(["bash", script], env=env, capture_output=True,
                               text=True, timeout=180)
            blob = p.stdout + p.stderr
            parts = re.search(r"___OSM_PARTS=(.*)", p.stdout)
            rel = re.search(r"___REL_ARG=(.*)", p.stdout)
            return blob, (parts.group(1).strip() if parts else None), \
                (rel.group(1).strip() if rel else None)
        finally:
            shutil.rmtree(d, ignore_errors=True)

    # ---- PBF mode --------------------------------------------------------

    def test_pbf_mode_contacts_no_overpass_mirror(self):
        # THE POINT OF THE MIGRATION. Any curl here is a second vintage.
        blob, _, _ = self._run(pbf_mode=True, seed_snapshot=True)
        self.assertNotIn("STUB_CURL_CALLED", blob,
                         "PBF mode still issued an Overpass request")

    def test_pbf_mode_feeds_the_converter_exactly_one_file(self):
        _, parts, _ = self._run(pbf_mode=True, seed_snapshot=True)
        self.assertIsNotNone(parts)
        self.assertEqual(len(parts.split()), 1,
                         f"expected one snapshot, got: {parts!r}")
        self.assertTrue(parts.endswith("qatar.osm"), parts)

    def test_pbf_mode_appends_no_supplementary_parts(self):
        # The concrete failure mode: the snapshot PLUS four Overpass files.
        _, parts, _ = self._run(pbf_mode=True, seed_snapshot=True)
        for leftover in ("-restrictions.osm", "-coastline.osm",
                         "-poi-areas.osm", "-poi-nodes.osm"):
            self.assertNotIn(leftover, parts)

    def test_pbf_mode_takes_restrictions_from_the_snapshot(self):
        # --relations names a SEPARATE Overpass file; in PBF mode the
        # restrictions are in the same file as the ways, so it must be empty.
        _, _, rel = self._run(pbf_mode=True, seed_snapshot=True)
        self.assertEqual(rel, "")

    def test_pbf_mode_reports_the_snapshot_it_used(self):
        blob, _, _ = self._run(pbf_mode=True, seed_snapshot=True)
        self.assertIn("260912", blob, "the release was not reported")
        self.assertIn("provenance", blob)

    # ---- Overpass mode ---------------------------------------------------

    def test_overpass_mode_is_untouched(self):
        # The migration is opt-in; the existing path must still run.
        blob, _, _ = self._run(pbf_mode=False, seed_snapshot=False)
        self.assertIn("STUB_CURL_CALLED", blob,
                      "Overpass mode should still contact a mirror")


class GateInvariantTest(unittest.TestCase):
    """Static: a future [1f] must not be able to forget the gate."""

    def setUp(self):
        with open(_BOOTSTRAP, encoding="utf-8") as fh:
            self.text = fh.read()

    def test_every_supplementary_download_is_gated(self):
        for var in ("RELS", "COAST", "POIS", "POI_NODES"):
            guard = f'if [ -z "$PBF_MODE" ] && [ ! -s "${var}" ]; then'
            self.assertIn(guard, self.text,
                          f"[{var}] download is not gated on PBF_MODE")

    def test_the_snapshot_lands_where_step_1_and_osrm_look_for_it(self):
        # Step 1 honours $CACHE/$REGION.osm as a whole-region extract and step 4
        # hands that exact path to osrm-extract. Writing anywhere else means the
        # routing graph is built from different data than the tiles.
        self.assertIn('PBF_OUT="$CACHE/${REGION}.osm"', self.text)

    def test_cache_is_defined_before_pbf_mode_uses_it(self):
        # It was not, once: the block was inserted above the CACHE assignment
        # and PBF_OUT resolved to "/qatar.osm".
        self.assertLess(self.text.index('CACHE="$ROOT/.bootstrap-cache"'),
                        self.text.index("# 0b. OSM SOURCE"))

    def test_pbf_mode_is_opt_in(self):
        self.assertIn('VECTOR_USE_PBF="${VECTOR_USE_PBF:-0}"', self.text)

@unittest.skipUnless(shutil.which("bash"), "bash required")
class RoutingInputTest(unittest.TestCase):
    """Step 4 hands $CACHE/$REGION.osm to osrm-extract. It must exist.

    It did not. The main extract is written per bbox tile as
    `$REGION.t<r><c>.osm` and each supplementary group gets its own name, so a
    fully SUCCESSFUL fresh Overpass bootstrap produced qatar.t00.osm,
    qatar-coastline.osm, qatar-poi-areas.osm and qatar-poi-nodes.osm — and no
    qatar.osm. Step 4's `cp '/data/qatar.osm'` then fails and the routing graph
    is never built. It survived because it only reproduces on a FRESH clone:
    anyone carrying a whole-region extract from before tiling existed has the
    file already, and step 1 honours it.

    These run the real acquisition section with a curl that succeeds.
    """

    def _run(self, tiles, pbf_mode=False):
        d = tempfile.mkdtemp()
        try:
            cache = os.path.join(d, "cache")
            os.makedirs(cache)
            bindir = os.path.join(d, "bin")
            os.makedirs(bindir)
            for name, body in (("curl", _STUB_CURL_OK), ("sleep", "#!/bin/sh\nexit 0\n")):
                fp = os.path.join(bindir, name)
                with open(fp, "w") as fh:
                    fh.write(body)
                os.chmod(fp, 0o755)

            if pbf_mode:
                with open(os.path.join(cache, "qatar.osm"), "w") as fh:
                    fh.write('<?xml version="1.0"?><osm version="0.6">'
                             '<way id="99"/></osm>')
                with open(os.path.join(cache, "qatar.osm.provenance.json"), "w") as fh:
                    fh.write('{"release": "260912", "pbf_md5": "abc"}\n')

            script = os.path.join(d, "acq.sh")
            with open(script, "w") as fh:
                fh.write(_acquisition_snippet())

            env = dict(os.environ)
            env.update({
                "PATH": bindir + os.pathsep + env["PATH"],
                "CACHE": cache, "REGION": "qatar", "WORK": d, "ROOT": _ROOT,
                "VECTOR_BBOX": "24.4,50.7,26.2,51.8",
                "VECTOR_BBOX_TILES": str(tiles),
                "OVERPASS_ENDPOINTS": "http://stub.invalid",
                "VECTOR_USE_PBF": "1" if pbf_mode else "0",
            })
            p = subprocess.run(["bash", script], env=env, capture_output=True,
                               text=True, timeout=180)
            routing_input = os.path.join(cache, "qatar.osm")
            content = None
            if os.path.isfile(routing_input):
                with open(routing_input, "rb") as fh:
                    content = fh.read()
            return p.stdout + p.stderr, content
        finally:
            shutil.rmtree(d, ignore_errors=True)

    def test_a_single_tile_bootstrap_produces_the_routing_input(self):
        # THE DEFECT, at the default VECTOR_BBOX_TILES=1.
        _, content = self._run(tiles=1)
        self.assertIsNotNone(content, "step 4's osrm-extract input was not created")
        self.assertIn(b"<way", content)

    def test_a_tiled_bootstrap_merges_into_one_routing_input(self):
        # Several bbox tiles cannot be concatenated textually: that is several
        # <osm> roots and osrm-extract will not read it.
        _, content = self._run(tiles=2)
        self.assertIsNotNone(content)
        root = ET.fromstring(content.decode())
        self.assertEqual(root.tag, "osm")

    def test_the_merge_deduplicates_overlapping_tiles(self):
        # Overpass returns whole ways for a bbox query, so an element on a seam
        # legitimately arrives from more than one tile. Four identical stub
        # tiles must not become four copies of the same way.
        _, content = self._run(tiles=2)
        root = ET.fromstring(content.decode())
        ids = [(e.tag, e.get("id")) for e in root]
        self.assertEqual(len(ids), len(set(ids)), f"duplicate elements: {ids}")
        self.assertEqual(sum(1 for t, _ in ids if t == "way"), 1)

    def test_pbf_mode_keeps_its_own_snapshot_as_the_routing_input(self):
        # PBF mode already wrote this path; the Overpass branch must not
        # overwrite it, or tiles and the routing graph come from different data.
        _, content = self._run(tiles=1, pbf_mode=True)
        self.assertIsNotNone(content)
        self.assertIn(b'id="99"', content, "the PBF snapshot was overwritten")



if __name__ == "__main__":
    unittest.main()
