"""Generate a Berlin place index for the geocoder from routing/traffic sample data.

Usage:
    python berlin_index.py [--output berlin_places.geojson]

If no --output is given, prints the GeoJSON to stdout.
"""

import json
import os
import sys

ROADS = [
    {
        "name": "Friedrichstraße",
        "coords": [[13.38, 52.51], [13.39, 52.51], [13.40, 52.51], [13.41, 52.51]],
    },
    {
        "name": "Unter den Linden",
        "coords": [[13.38, 52.52], [13.39, 52.52], [13.40, 52.52], [13.41, 52.52]],
    },
    {
        "name": "Karl-Marx-Allee",
        "coords": [[13.38, 52.53], [13.39, 52.53], [13.40, 52.53], [13.41, 52.53]],
    },
    {
        "name": "Torstraße",
        "coords": [[13.38, 52.54], [13.39, 52.54], [13.40, 52.54], [13.41, 52.54]],
    },
    {
        "name": "Wilhelmstraße",
        "coords": [[13.38, 52.51], [13.38, 52.52], [13.38, 52.53], [13.38, 52.54]],
    },
    {
        "name": "Oranienburger Straße",
        "coords": [[13.39, 52.51], [13.39, 52.52], [13.39, 52.53], [13.39, 52.54]],
    },
    {
        "name": "Rosa-Luxemburg-Straße",
        "coords": [[13.40, 52.51], [13.40, 52.52], [13.40, 52.53], [13.40, 52.54]],
    },
    {
        "name": "Greifswalder Straße",
        "coords": [[13.41, 52.51], [13.41, 52.52], [13.41, 52.53], [13.41, 52.54]],
    },
]

POIS = [
    {"name": "Brandenburger Tor", "kind": "attraction", "lon": 13.3775, "lat": 52.5163},
    {"name": "Reichstagsgebäude", "kind": "government", "lon": 13.3753, "lat": 52.5186},
    {"name": "Berlin Hauptbahnhof", "kind": "station", "lon": 13.3696, "lat": 52.5258},
    {"name": "Alexanderplatz", "kind": "square", "lon": 13.4134, "lat": 52.5219},
    {"name": "Checkpoint Charlie", "kind": "attraction", "lon": 13.3903, "lat": 52.5076},
    {"name": "Potsdamer Platz", "kind": "square", "lon": 13.3757, "lat": 52.5094},
    {"name": "East Side Gallery", "kind": "attraction", "lon": 13.4419, "lat": 52.5048},
    {"name": "Tiergarten", "kind": "park", "lon": 13.3537, "lat": 52.5146},
    {"name": "Tempelhofer Feld", "kind": "park", "lon": 13.4019, "lat": 52.4739},
    {"name": "Berliner Dom", "kind": "attraction", "lon": 13.4007, "lat": 52.5192},
    {"name": "Museum Island", "kind": "museum", "lon": 13.3981, "lat": 52.5194},
    {"name": "Gendarmenmarkt", "kind": "square", "lon": 13.3925, "lat": 52.5137},
]


def build_index():
    features = []

    for r in ROADS:
        features.append({
            "type": "Feature",
            "geometry": {"type": "LineString", "coordinates": r["coords"]},
            "properties": {"name": r["name"], "kind": "road", "highway": "primary", "maxspeed_kmh": 50},
        })

    for p in POIS:
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [p["lon"], p["lat"]]},
            "properties": {"name": p["name"], "kind": p["kind"]},
        })

    return {"type": "FeatureCollection", "features": features}


def main():
    output = None
    if len(sys.argv) > 1 and sys.argv[1] == "--output" and len(sys.argv) > 2:
        output = sys.argv[2]

    index = build_index()
    text = json.dumps(index, indent=2, ensure_ascii=False)

    if output:
        os.makedirs(os.path.dirname(output) or ".", exist_ok=True)
        with open(output, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"[berlin_index] wrote {len(index['features'])} features to {output}")
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
