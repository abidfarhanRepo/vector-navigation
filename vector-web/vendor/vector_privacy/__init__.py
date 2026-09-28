"""vector-privacy — stdlib privacy-gate library for location data (issue 01).

Exposes a single pure function ``apply_gate`` that enforces every privacy rule
(accuracy floor, endpoint truncation, temporal coarsening, coordinate
precision) and the per-trip pseudonym mint. Vendored into vector-web (S1
ingest gate) and vector-learning (S3) so ingest and aggregation cannot drift.
See adr-0065 for the binding thresholds and rationale.
"""

from .gate import (
    ACCURACY_FLOOR_M,
    COORD_PRECISION,
    TIME_ROUND_S,
    TRUNCATE_DISTANCE_M,
    REASON_MALFORMED,
    REASON_BOUNDS,
    REASON_ACCURACY,
    REASON_TRUNCATED,
    REASON_NO_TIME,
    apply_gate,
    haversine_m,
    mint_pseudonym,
)
from .trip import (
    REASON_NO_TRIP_TOKEN,
    load_or_create_salt,
    mint_client_token,
    trip_pseudonym,
    valid_client_token,
)

__all__ = [
    "valid_client_token",
    "trip_pseudonym",
    "mint_client_token",
    "load_or_create_salt",
    "REASON_NO_TRIP_TOKEN",
    "apply_gate",
    "mint_pseudonym",
    "haversine_m",
    "TRUNCATE_DISTANCE_M",
    "ACCURACY_FLOOR_M",
    "TIME_ROUND_S",
    "COORD_PRECISION",
    "REASON_MALFORMED",
    "REASON_BOUNDS",
    "REASON_ACCURACY",
    "REASON_TRUNCATED",
    "REASON_NO_TIME",
]

__version__ = "0.1.0"