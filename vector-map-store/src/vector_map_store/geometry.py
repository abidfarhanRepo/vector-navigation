"""Minimal geometry helper for vector-map-store.

Pure, dependency-free domain helper committed ahead of the feature
persistence/query pipeline (adr-0012).
"""


def bbox_contains(bbox, lon: float, lat: float) -> bool:
    """Return True if (lon, lat) lies inside the bounding box.

    bbox is (min_lon, min_lat, max_lon, max_lat).
    """
    min_lon, min_lat, max_lon, max_lat = bbox
    return min_lon <= lon <= max_lon and min_lat <= lat <= max_lat


if __name__ == "__main__":
    print(bbox_contains((-10, -10, 10, 10), 0, 0))
