"""Minimal coordinate normalization for vector-ingestion.

Pure, dependency-free domain helper committed ahead of the full ingest
pipeline (adr-0011). Validates and wraps geographic coordinates.
"""


def normalize_lonlat(lon: float, lat: float):
    """Normalize (lon, lat) to valid ranges.

    Longitude is wrapped into [-180, 180). Latitude must be in [-90, 90]
    or ValueError is raised.
    """
    if lat < -90 or lat > 90:
        raise ValueError(f"latitude out of range: {lat}")
    wrapped_lon = ((lon + 180.0) % 360.0) - 180.0
    return (wrapped_lon, float(lat))


if __name__ == "__main__":
    print(normalize_lonlat(0.0, 0.0))
