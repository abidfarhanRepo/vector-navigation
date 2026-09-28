"""The tile server's configuration, pinned so it cannot regress quietly.

WHAT THIS PROTECTS
------------------
``TILE_DIR=/app/tiles/current`` and the volume mounted at ``/app/tiles`` are a
PAIR. Either one alone is wrong, and both failure modes are silent:

* ``TILE_DIR=/app/tiles`` with a migrated volume serves the ``releases/``
  directory as if it were a zoom tree — no zooms found, a blank map, HTTP 404s
  that look like ordinary missing tiles;
* mounting the volume AT ``/app/tiles/current`` puts the pointer *outside* the
  volume, so a release swap is invisible to the running container and
  activation silently stops working — the tile server would serve whatever the
  pointer named when the container started, forever.

Neither raises. Neither shows up in a log. Both are exactly the kind of thing a
later refactor "tidies up" on the way past, which is why this file asserts on
the configuration rather than trusting a comment.

It also holds the line against the specific way the production migration could
be undone by accident: a source deployment that overwrites the host's compose
file with this repository's copy. `deploy_prod_source.sh` does not do that
today — it reads the remote compose and rebuilds images — and the test below
pins that it stays true.
"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
COMPOSE = os.path.join(REPO, "docker-compose.yml")

EXPECTED_TILE_DIR = "/app/tiles/current"
EXPECTED_MOUNT = "vector-data-tiles:/app/tiles:ro"


def compose_text():
    with open(COMPOSE, encoding="utf-8") as fh:
        return fh.read()


def tiles_service_block():
    """The `tiles:` service, as raw lines.

    Parsed by indentation rather than with a YAML library: this suite must run
    with nothing installed, exactly like the rest of `vector-web/tests`.
    """
    lines = compose_text().splitlines()
    out, grabbing = [], False
    for line in lines:
        if re.match(r"^  [A-Za-z0-9_-]+:\s*$", line):
            grabbing = line.strip().rstrip(":") == "tiles"
            if grabbing:
                continue
        if grabbing:
            out.append(line)
    return out


class ComposeTileConfigTest(unittest.TestCase):

    def setUp(self):
        self.block = tiles_service_block()
        self.assertTrue(self.block, "no `tiles:` service found in docker-compose.yml")

    def test_TILE_DIR_points_at_the_release_pointer(self):
        """The migration, as a configuration fact.

        If this fails with `/app/tiles`, either the migration was rolled back
        deliberately — in which case update this test in the same change — or
        someone has undone it without noticing.
        """
        tile_dirs = [line.split("TILE_DIR:")[1].strip()
                     for line in self.block if "TILE_DIR:" in line]

        self.assertEqual([EXPECTED_TILE_DIR], tile_dirs)

    def test_the_volume_is_mounted_at_the_directory_above_it(self):
        """The pointer must be INSIDE the volume, or swaps stop being visible."""
        mounts = [line.strip().lstrip("- ") for line in self.block
                  if "vector-data-tiles" in line]

        self.assertEqual([EXPECTED_MOUNT], mounts)

    def test_TILE_DIR_is_underneath_the_mount_point(self):
        """Stated as the relationship, not as two strings.

        A future edit that moves both consistently should pass; one that moves
        only one of them cannot.
        """
        tile_dir = next(line.split("TILE_DIR:")[1].strip()
                        for line in self.block if "TILE_DIR:" in line)
        mount_target = EXPECTED_MOUNT.split(":")[1]

        self.assertTrue(tile_dir.startswith(mount_target.rstrip("/") + "/"),
                        f"TILE_DIR {tile_dir!r} is not inside the mounted "
                        f"volume at {mount_target!r}")
        self.assertNotEqual(tile_dir.rstrip("/"), mount_target.rstrip("/"),
                            "TILE_DIR must name the pointer, not the volume root")

    def test_the_tile_volume_stays_read_only(self):
        """Read-only is what guarantees the server cannot alter what it serves."""
        mount = next(line for line in self.block if "vector-data-tiles" in line)
        self.assertTrue(mount.rstrip().endswith(":ro"), mount)

    def test_the_migration_requirement_is_documented_where_it_is_configured(self):
        """A reader who finds `current` in a compose file must find out why.

        The comment is load-bearing: `TILE_DIR=/app/tiles/current` is
        inexplicable and looks like a typo unless the volume layout is
        explained next to it.
        """
        text = compose_text()
        # Anchored to the SERVICE definition, not the first "  tiles:" in the
        # file — the header comment lists internal ports as
        # "... geocoder:8085  tiles:3000 ...", which a naive `index` finds
        # first and which would make this test read the wrong 2 kB entirely.
        match = re.search(r"^  tiles:$", text, re.M)
        self.assertIsNotNone(match, "no `tiles:` service definition")
        start = match.start()
        window = text[max(0, start - 2500):start + 1200]

        for needle in ("releases/", "current", "import_legacy_tree",
                       "MIGRATION"):
            self.assertIn(needle, window,
                          f"the tiles service does not explain {needle!r}")


class LegacyPathRegressionTest(unittest.TestCase):
    """No leftover assumption that the volume is a flat tree."""

    def test_bootstrap_creates_the_release_layout_it_configures(self):
        """The defect this closes is a BLANK MAP on a fresh install.

        `bootstrap.sh` populates the volume with a flat `cp -r`. With
        `TILE_DIR=/app/tiles/current` that volume is unreadable and the map is
        blank, silently. The import step must follow the copy — and must use
        the same script the production migration uses, so what a developer
        bootstraps is the layout production serves.
        """
        with open(os.path.join(REPO, "bootstrap.sh"), encoding="utf-8") as fh:
            text = fh.read()

        self.assertIn("vector-data-tiles:/data", text)
        self.assertIn("import_legacy_tree.py", text,
                      "bootstrap populates the tile volume but never creates "
                      "`current`; a fresh install would render a blank map")

        copy_at = text.index("cp -r /src/tiles/*")
        import_at = text.index("import_legacy_tree.py")
        self.assertLess(copy_at, import_at,
                        "the import must run AFTER the tiles are copied in")

    def test_a_source_deployment_cannot_silently_undo_the_migration(self):
        """`deploy_prod_source.sh` must never push this compose file up.

        The host's compose file is hand-edited by the migration. If a source
        deploy ever copied the repository's copy over it, the migration would
        revert on the next unrelated deployment — and the symptom would be a
        blank map appearing hours later with nothing connecting it to the
        deploy that caused it.

        Today the script reads the REMOTE compose (`docker compose config`)
        and rebuilds images in place. This pins that.
        """
        path = os.path.join(REPO, "scripts", "deploy_prod_source.sh")
        with open(path, encoding="utf-8") as fh:
            text = fh.read()

        for line in text.splitlines():
            if line.strip().startswith("#"):
                continue
            if "docker-compose.yml" in line:
                self.assertNotRegex(
                    line, r"\b(scp|rsync|SCP)\b",
                    "a source deploy that copies docker-compose.yml to the "
                    "host would silently revert the TILE_DIR migration:\n"
                    + line)

    def test_the_repo_and_the_migration_runbook_agree(self):
        """The runbook is the record of what production will be told to do."""
        runbook = os.path.join(REPO, ".scratch", "vector-product",
                               "V7.7-MIGRATION-RUNBOOK.md")
        if not os.path.isfile(runbook):
            self.skipTest("runbook not present")
        with open(runbook, encoding="utf-8") as fh:
            text = fh.read()

        self.assertIn(EXPECTED_TILE_DIR, text)
        self.assertIn("legacy-import", text,
                      "the runbook must name the legacy id convention")


if __name__ == "__main__":
    unittest.main()
