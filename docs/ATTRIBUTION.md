# Attribution and data licensing

Vector's **source code** is MIT-licensed (see `LICENSE`). The **data** it
consumes and produces is not, and the distinction matters if you redistribute
anything this stack builds.

## OpenStreetMap — ODbL

The road graph, the pedestrian graph, the vector tiles, the search index, and
the signal, camera, barrier and crossing catalogues are all derived from
OpenStreetMap.

> Map data © OpenStreetMap contributors, available under the
> [Open Database License](https://www.openstreetmap.org/copyright).

Under ODbL these derived artifacts are **Derivative Databases**. If you make
them publicly available you must attribute OpenStreetMap and offer the derived
database under ODbL. Running the stack privately for yourself carries no such
obligation.

Vector's own attribution surfaces:

- the Android map view carries MapLibre's attribution control;
- `docs/PRIVACY.md` and this file are distributed with the source.

## Overture Maps Foundation

POI/place data (`qatar_places.geojson`) comes from the
[Overture Maps Foundation](https://overturemaps.org/) and is governed by that
project's data licence. 837 of the release's map-visible POIs originate here.

## Software dependencies

| dependency | licence | used for |
|---|---|---|
| [MapLibre GL Native](https://maplibre.org/) (`org.maplibre.gl:android-sdk:11.13.5`) | BSD-2-Clause | map rendering on Android |
| [RevenueCat Purchases](https://www.revenuecat.com/) (`com.revenuecat.purchases:purchases:10.21.1`) | MIT | subscription infrastructure |
| Jetpack Compose, AndroidX | Apache-2.0 | UI toolkit |
| [Robolectric](https://robolectric.org/) | MIT | JVM Android tests |
| `mapbox-vector-tile` (Python) | Apache-2.0 | MVT encode/decode in the bake |
| OSRM | BSD-2-Clause | present in the stack; not on the routing path (see ADR-0072) |

MapLibre is pinned to 11.13.5 specifically: 11.5.2 fails Android 16's 16 KB page
alignment check on a Galaxy S24 Ultra, which is a hard library load failure in a
non-debuggable build.

## Trademarks

"RevenueCat", "OpenStreetMap", "Overture", "MapLibre", "Samsung", "Galaxy" and
"Android" are the marks of their respective owners. Vector is not affiliated
with, endorsed by, or sponsored by any of them.
