# Evidence

The README makes quantitative claims. These files let you check them rather
than trust them. Everything here is impersonal: public map tiles, test records
and the commands that reproduce them. No location traces, no device
identifiers.

## `verify_tiles.py` — the 3D basemap claim and the lane-marking claim

Two tiles covering West Bay in Doha, fetched from the live deployment on
2026-09-24 while it reported release `vector-tiles-2026-09-24T1733Z-e039746`
(`GET /tiles/version`), from `GET /tiles/{z}/{x}/{y}.mvt`:

| file | bytes | sha256 |
|---|---|---|
| `tiles/westbay-z15-21074-14000.mvt` | 61,007 | `24be4eecfeae4f0b3d6c9e4a3a0d1ec6c791dda465afa84602d9674bbd7b7b87` |
| `tiles/westbay-z14-10537-7000.mvt` | 45,776 | `50a5bec560b3adfd2ebe84f12bda326dcb0f7348a4f44ec8110ae9b13c2cf808` |

```bash
pip install mapbox-vector-tile
python3 docs/evidence/verify_tiles.py
```

To capture the same two tiles from your own deployment and compare them byte for
byte, use the same user-agent. It matters: Cloudflare answers `403` to the
Python default UA (`Python-urllib/3.14`, what a bare `urllib.request` call
sends), and `200` to a browser UA — as it does to `curl`'s own default.

```bash
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128 Safari/537.36'
curl -A "$UA" -o z15.mvt 'https://<your-host>/tiles/15/21074/14000.mvt'
curl -A "$UA" -o z14.mvt 'https://<your-host>/tiles/14/10537/7000.mvt'
sha256sum z15.mvt z14.mvt   # compare with the table above
```

Output:

```
=== West Bay, Doha — z15  15/21074/14000 (61,007 bytes) ===
  sha256   : 24be4eecfeae4f0b3d6c9e4a3a0d1ec6c791dda465afa84602d9674bbd7b7b87
  layers   : basemap 866, lanes 218
  features : 866  {'road': 718, 'poi': 66, 'building': 58, 'park': 12, 'coastline': 7, 'label': 3, 'water': 2}
  buildings: 58
  with an explicit height_m: 20
  tallest: [200.0, 209.0, 215.0, 241.0, 245.0, 245.0] m
  with building_levels     : 25, up to 58 floors
  taper    : 238 of 718 road features carry a fractional `lw`
  lanes    : 218 dividers {'lane': 178, 'solid': 32, 'centre': 8}, marking [2, 3, 4, 6] lanes

=== West Bay, Doha — z14  14/10537/7000 (45,776 bytes) ===
  sha256   : 50a5bec560b3adfd2ebe84f12bda326dcb0f7348a4f44ec8110ae9b13c2cf808
  layers   : basemap 677
  features : 677  {'road': 474, 'building': 91, 'poi': 69, 'coastline': 24, 'park': 12, 'label': 4, 'water': 3}
  buildings: 91
  with an explicit height_m: 22
  tallest: [200.0, 209.0, 215.0, 241.0, 245.0, 245.0] m
  with building_levels     : 29, up to 58 floors
  taper    : 272 of 474 road features carry a fractional `lw`
  lanes    : none in this tile
```

**The 3D claim, and its limit.** The z15 tile holds 58 buildings, 20 of them
carrying an explicit `height_m`, the tallest at 245 m, plus 25 with
`building_levels` up to 58 floors. That is what backs "a 3D basemap with real
tower heights" — and equally what backs the limitation stated beside it.
Nationally only **1,929 of 228,872** building instances (0.84%) carry an
explicit height. West Bay is the dense exception. This is a 3D basemap, not a
height survey.

**The lane-marking claim.** The z15 tile carries a second MVT layer, `lanes`:
218 pre-offset lane dividers on roads whose OSM `lanes` tag states an integer
count, each carrying a lane count `n`, its position `i` among them, the OSM way
id, and `cls` — the layer's only styling decision, one of `lane` (178 here),
`solid` (32) or `centre` (8). The layer is written only at z15 (`LANES_ZOOM`),
it is **visual only** (the style draws it; nothing else reads it, and the tile
generator's own docstring says it carries no routing or guidance meaning), and a
way that does not state a usable integer lane count draws no dividers at all
rather than a guess.

**The taper.** 238 of the z15 tile's 718 road features, and 272 of the z14
tile's 474, carry a fractional `lw` count: the last stretch of a wider way at a
lane-count step, cut into nested pieces so the painted carriageway narrows
across the step instead of jumping in one square notch. `lw` is written at z14
and above.

Both tiles are therefore larger than the fixtures first committed on
2026-09-20 (41,273 and 31,780 bytes): those predate the `lanes` layer and the
taper, and carry no `lw` feature at all. The committed bytes are the bytes the
deployment served on 2026-09-24; `verify_tiles.py` prints each file's sha256 so
you can compare them with what your own deployment serves for the same z/x/y.

## `tests/` — the suite counts

Both files are records, not raw console transcripts: the per-class breakdown
and the full log are not reproduced here.

- `android-suite.txt` — the two JVM suites, **:app 1,115** and **:core-geo
  675** tests, 0 failures, re-run on 2026-09-28 over this tree.
- `routing-suite.txt` — the Python `vector-routing` suite: **625 tests, OK
  (skipped=1)**, run 2026-09-24.

Reproduce them with:

```bash
cd vector-android && ./gradlew :app:testDebugUnitTest          # 1,115, no device, no emulator
cd vector-android && ./gradlew :core-geo:test                  # 675
cd vector-routing && PYTHONPATH="$PWD/src:$PWD/vendor" \
    python3 -m unittest discover -s tests                      # 625
```

## What is deliberately not here

**Device screenshots.** The handset sessions were run where the developer
actually lives, and a map screenshot centred on someone's home is location data
regardless of what else it demonstrates. The demo video shows the app running on
the device; that is the right place for it.

**Production internals.** Vector runs on a box shared with an unrelated system
owned by someone else. Nothing describing that host, its network or its other
tenants belongs in a public repository.
