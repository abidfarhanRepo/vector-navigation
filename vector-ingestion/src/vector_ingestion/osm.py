"""OSM XML parser for vector-ingestion (Wave 27a road MVP, Wave 29 basemap).

Parses a real OpenStreetMap ``.osm`` extract (Overpass ``out geom;`` form,
where each ``<way>`` carries ``<nd ref lat lon>`` geometry nodes) into a
GeoJSON FeatureCollection.

From Wave 29 the parser emits *basemap* features, not just roads:

  * Roads (``highway=*``) become LineString features used by the routing
    engine and the tile pipeline, carrying OSM routing semantics
    (maxspeed, oneway, name, lanes). Tagged ``kind="road"``.
  * Buildings (``building=*``), landuse, leisure, natural, water, waterway
    become Polygon/MultiLineString features tagged with a ``kind``
    (building / landuse / park / water / ...) used by the basemap renderer.
  * Place labels (``place=*`` nodes) become Point features tagged
    ``kind="label"`` with a ``name`` for map labels.

Design notes:
  * Dependency-free (stdlib ``xml.etree``) so the gate stays offline-safe once
    the extract is committed.
  * Only ``highway=*`` ways become routable edges; the routing converter
    filters on that. Basemap features are tagged so the tile pipeline keeps
    them separate from roads.
"""

from typing import Any, Dict, Iterable, List, Optional, Tuple

import xml.etree.ElementTree as ET

# Highway values that are routable road links (used to filter noise like
# historic/raceway/offshore pipelines). Anything with a ``highway`` tag whose
# value is in this set (or starts with one of these prefixes) is kept.
_ROUTABLE_PREFIXES = (
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "living_street", "service",
    "track", "road", "pedestrian", "footway", "cycleway",
    "path", "steps", "bridleway", "corridor",
)

# OSM keys that, with a present value, map to a basemap feature kind.
# Order matters: the first matching key wins.
_BASEMAP_KIND = {
    "building": "building",
    "landuse": "landuse",
    "leisure": "park",
    "natural": "natural",
    "water": "water",
    "waterway": "water",
    "place": "label",
}


# Of the routable classes above, the ones a CAR may legally drive on.
#
# The distinction matters because ``_ROUTABLE_PREFIXES`` serves two masters: it
# decides what renders as a road on the basemap (where a footpath belongs) AND
# what goes into the driving graph (where it does not). Conflating them meant
# the car router happily used footways, cycleways and staircases — a real Doha
# route came back beginning "Head northwest on footway road", and the shortest
# path through a pedestrian area will always beat the road that goes around it.
#
# Kept deliberately: ``service`` (car parks, drive-throughs, access roads) and
# ``track`` (unsealed but drivable, and common in Qatar outside the city).
_CAR_PREFIXES = (
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "living_street", "service",
    "track", "road",
)


def _is_routable(highway: Optional[str]) -> bool:
    if not highway:
        return False
    return any(highway == p or highway.startswith(p + "_") for p in _ROUTABLE_PREFIXES)


def is_car_routable(highway: Optional[str]) -> bool:
    """True when a car may drive this highway class.

    Separate from :func:`_is_routable` on purpose — see ``_CAR_PREFIXES``. A
    pedestrian way must still be DRAWN, so it stays a ``kind="road"`` basemap
    feature; it just must not be an edge in the driving graph.
    """
    if not highway:
        return False
    return any(highway == p or highway.startswith(p + "_") for p in _CAR_PREFIXES)


def _basemap_kind(props: Dict[str, Any]) -> Optional[str]:
    # `natural=coastline` is a LINE, and it must be classified before the
    # generic `natural` mapping below, which would call it an area.
    #
    # This matters for two separate reasons. First, geometry: coastline ways are
    # OPEN, so forcing one into the `natural` area branch closes the ring and
    # produces a polygon spanning whatever the two loose ends happen to be —
    # a large spurious fill across the map rather than a shoreline.
    #
    # Second, and the reason it was worth adding at all: **Qatar is a
    # peninsula**, and without a coastline it has no shape. Verified on an S24
    # at country zoom — the road network rendered correctly and floated in a
    # black void, because the sea and the land are both just the style's
    # background colour. A country-scale map with no land/sea edge does not read
    # as a map of anywhere.
    if props.get("natural") == "coastline":
        return "coastline"
    for k, kind in _BASEMAP_KIND.items():
        if props.get(k) is not None:
            return kind
    return None


def parse_osm(xml_text: str) -> Dict[str, Any]:
    """Parse OSM XML text into a GeoJSON FeatureCollection of features.

    Every ``<way>`` with >= 2 geometry ``<nd>`` nodes becomes one feature
    (LineString for roads/waterways, Polygon for areas). Every ``<node>`` with
    a ``place`` tag becomes one Point feature (label). Each feature carries a
    ``kind`` property (``road`` / ``building`` / ``landuse`` / ``park`` /
    ``natural`` / ``water`` / ``label``) plus relevant OSM tags.

    Road features additionally carry routing semantics:
      ``highway``, ``name``, ``maxspeed`` (km/h), ``oneway`` (bool), ``lanes``.
    """
    root = ET.fromstring(xml_text)
    features: List[Dict[str, Any]] = []

    # OSM XML comes in two shapes and this parser only ever handled one of them.
    #
    #   Overpass `out geom`:  <nd lat=".." lon=".."/>      -- geometry inline
    #   Standard OSM XML:     <nd ref="123"/> + <node id="123" lat lon/>
    #
    # bootstrap.sh requests the SECOND form ("out body; >; out skel qt;"), so
    # against the extract this project actually downloads, every way here
    # resolved to zero coordinates and the parser silently produced nothing.
    # It went unnoticed because the production bake uses a different parser
    # (vector-tile-gen/scripts/osm_to_geojson.py) and this one's fixtures were
    # missing, so its tests never ran.
    node_xy: Dict[int, List[float]] = {}
    for node in root.iter("node"):
        nid = node.get("id")
        lat = node.get("lat")
        lon = node.get("lon")
        if nid is None or lat is None or lon is None:
            continue
        try:
            node_xy[int(nid)] = [float(lon), float(lat)]
        except ValueError:
            continue

    for way in root.iter("way"):
        props: Dict[str, Any] = {"id": int(way.get("id", 0))}
        highway = None

        for tag in way.findall("tag"):
            k = tag.get("k")
            v = tag.get("v")
            if k == "highway":
                highway = v
            elif k == "name":
                props["name"] = v
            elif k == "maxspeed":
                if v is not None:
                    props["maxspeed"] = _parse_speed(v)
            elif k == "oneway":
                props["oneway"] = v in ("yes", "true", "1", "-1")
            elif k == "lanes":
                try:
                    props["lanes"] = int(v.split(";")[0])
                except ValueError:
                    pass
            elif k in _BASEMAP_KIND:
                props[k] = v

        if highway:
            props["highway"] = highway

        # Determine the feature kind and whether it is emittable.
        kind = "road" if _is_routable(highway) else _basemap_kind(props)
        if kind is None:
            continue
        props["kind"] = kind
        if kind == "road":
            # Carried through to the tiles and, crucially, used by bootstrap.sh
            # to select which roads become edges in the DRIVING graph.
            props["car"] = is_car_routable(highway)

        # Geometry from <nd> nodes (Overpass `geom` form carries lat/lon).
        coords: List[List[float]] = []
        for nd in way.findall("nd"):
            lat = nd.get("lat")
            lon = nd.get("lon")
            if lat is not None and lon is not None:
                coords.append([float(lon), float(lat)])
                continue
            # Standard OSM XML: resolve the reference against the node table.
            ref = nd.get("ref")
            if ref is None:
                continue
            try:
                xy = node_xy.get(int(ref))
            except ValueError:
                continue
            if xy is not None:
                coords.append(list(xy))

        if len(coords) < 2:
            continue

        # Areas (buildings/landuse/leisure/natural/water) are closed polygons;
        # roads/waterways are line strings. Waterways can also be areas but are
        # emitted as lines when open.
        is_area = kind in ("building", "landuse", "park", "natural", "water") and kind != "road"
        if is_area:
            # Ensure the ring is closed for a valid polygon.
            if coords[0] != coords[-1]:
                coords = coords + [coords[0]]
            geometry = {"type": "Polygon", "coordinates": [coords]}
        else:
            geometry = {"type": "LineString", "coordinates": coords}

        features.append({
            "type": "Feature",
            "geometry": geometry,
            "properties": props,
        })

    # Place labels: <node> with a place tag (Point features).
    for node in root.iter("node"):
        nprops: Dict[str, Any] = {"id": int(node.get("id", 0))}
        has_place = False
        for tag in node.findall("tag"):
            k = tag.get("k")
            v = tag.get("v")
            if k == "place":
                nprops["place"] = v
                has_place = True
            elif k == "name":
                nprops["name"] = v
        if not has_place:
            continue
        nprops["kind"] = "label"
        try:
            lat = float(node.get("lat") or "")
            lon = float(node.get("lon") or "")
        except (TypeError, ValueError):
            continue
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": nprops,
        })

    return {"type": "FeatureCollection", "features": features}


def parse_osm_file(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as fh:
        return parse_osm(fh.read())


def _parse_speed(v: str) -> Optional[int]:
    """Parse an OSM maxspeed value (e.g. '50', '30 mph', 'none') to km/h int."""
    if not v:
        return None
    v = v.strip().lower()
    if v in ("none", "signals", "walk", ""):
        return None
    try:
        return int(v)
    except ValueError:
        pass
    # Try "<n> mph"
    parts = v.split()
    if len(parts) == 2 and parts[1] in ("mph", "km/h"):
        try:
            n = int(parts[0])
            return int(n * 1.60934) if parts[1] == "mph" else n
        except ValueError:
            return None
    return None


def iter_way_ids(fc: Dict[str, Any]) -> Iterable[int]:
    for f in fc.get("features", []):
        yield f["properties"]["id"]
