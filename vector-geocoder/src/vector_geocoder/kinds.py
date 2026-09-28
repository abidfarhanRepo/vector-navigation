"""Map Overture POI categories to user-facing along-route kinds.

The places layer carries raw Overture categories (coffee_shop, cafe,
fast_food_restaurant, ...). Users think in Waze terms: fuel, coffee, food.
This mapping is the translation layer; unknown categories fall through to
an exact-kind match so nothing silently disappears.
"""

# user kind -> Overture categories (casefolded)
KIND_MAP = {
    "fuel": ["fuel_station", "gas_station", "petrol_station", "charging_station"],
    "coffee": ["coffee_shop", "cafe", "tea_room", "bubble_tea_shop"],
    "food": ["restaurant", "fast_food_restaurant", "food_court",
             "indian_restaurant", "pizza_restaurant", "burger_restaurant",
             "seafood_restaurant", "chinese_restaurant", "turkish_restaurant",
             "lebanese_restaurant", "asian_restaurant", "italian_restaurant"],
    "parking": ["parking", "parking_lot", "parking_garage"],
    "pharmacy": ["pharmacy", "drugstore", "chemist"],
    "atm": ["atm", "bank"],
    "hotel": ["hotel", "motel", "hostel", "guest_house"],
    "hospital": ["hospital", "clinic", "medical_lab", "medical_center"],
    "shop": ["supermarket", "shopping", "shopping_center", "mall",
             "clothing_store", "grocery_store", "convenience_store"],
}


def resolve_kind(user_kind: str) -> set:
    """Categories to match for a user kind.

    The user kind is ALWAYS included alongside whatever it maps to, and that is
    the whole point of this function rather than a bare dict lookup.

    V5 found the reason. This table translates Overture Places categories, and
    Overture has never been ingested — the index that is actually loaded comes
    from OSM, whose category values are the bare tag values: ``fuel``,
    ``cafe``, ``parking``, ``pharmacy``, ``hospital``, ``hotel``. Six of those
    are also user kinds, so the mapping SHADOWED them: ``kinds=fuel`` resolved
    to ``{fuel_station, gas_station, petrol_station, charging_station}``, none
    of which exists in the index, and the along-route corridor search returned
    zero results for the most obvious query a driver has. The unknown-kind
    fall-through was written to stop exactly this ("nothing silently
    disappears") and did not apply, because ``fuel`` is not unknown — it is
    known, and mapped somewhere else.

    Including the kind itself costs nothing when Overture does arrive: a place
    tagged ``fuel_station`` and one tagged ``fuel`` are both fuel, and a driver
    asking for fuel wants both.
    """
    k = (user_kind or "").strip().casefold()
    if not k:
        return set()
    return set(KIND_MAP.get(k, ())) | {k}


def poi_kind(props: dict) -> str:
    """The effective category of an indexed place.

    ``poi_class`` is checked as well as ``category``, and that is the second
    half of the V5 fix. The two name the same thing at different points in the
    pipeline: vector-ingestion writes ``poi_class`` for all 8,735 of Qatar's
    POIs, and ``GeocodeHit.to_geojson`` renames it to ``category`` on the way
    out so it matches the field Overture places use.

    The corridor filter reads the RAW indexed dict, which is upstream of that
    rename — so it saw no ``category``, fell through to ``kind``, and every POI
    reported the basemap layer name **"poi"**. Not one of them could ever match
    a user kind, so ``/along?kinds=<anything>`` returned zero features for
    every kind, on every route. Fixing ``resolve_kind``'s shadowing was
    necessary and on its own changed nothing, because the value being compared
    was "poi" either way.
    """
    p = props or {}
    # `poi_class == "yes"` is skipped for the same reason `to_geojson` refuses
    # to emit it: `building=yes` and `amenity=yes` are the tag saying a
    # feature exists, not what it is. The two must agree, or a place would be
    # filterable as "yes" and displayed as nothing.
    cls = (p.get("poi_class") or "").strip().casefold()
    if cls == "yes":
        cls = ""
    return ((p.get("category") or "").strip().casefold()
            or cls
            or (p.get("kind") or "").strip().casefold())
