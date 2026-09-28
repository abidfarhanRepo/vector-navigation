"""HTTP service for the self-hosted geocoder.

Serves ``GET /search?q=<query>&limit=<n>`` returning a GeoJSON
FeatureCollection of matching places/roads, and ``GET /healthz``. Auth is the
shared dev-anonymous / prod-enforced broker (ADR-0050) — same as the other
Vector engines.
"""

from __future__ import annotations

import json
import os
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple

from .index import GeocodeIndex, _hit_distance_m
from .learned_poi import LEARNED_KIND, LearnedPoiIndex, pois_from_facts

# Learned POIs surface within this radius of a reverse-geocode point unless the
# caller overrides it. Deliberately tight: an unnamed inferred place is only
# useful as "there is something right here", and a wide radius would put
# low-information markers on top of named OSM features.
LEARNED_NEAR_RADIUS_M = 250.0


def read_learned_facts(path: Optional[str]) -> List[Dict[str, Any]]:
    """Read a ``learned_pois.json`` export written by ``vector-learning``.

    File-based integration (ADR-0003) — the geocoder never imports the learning
    repo. A missing or unreadable export yields ``[]``: search must work on a
    deployment that has learned nothing, which is every deployment on day one.
    """
    if not path or not os.path.exists(path):
        return []
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (OSError, ValueError):
        return []
    if isinstance(doc, list):
        return [f for f in doc if isinstance(f, dict)]
    if isinstance(doc, dict) and isinstance(doc.get("facts"), list):
        return [f for f in doc["facts"] if isinstance(f, dict)]
    return []


class GeocodeService:
    """Wraps a :class:`GeocodeIndex` with a tiny HTTP server.

    The learned layer (issue 09) is held in a **separate** index, not merged
    into the OSM one. That is what makes the no-regression guarantee structural:
    ``search()`` cannot start returning inferred places, because the search path
    never sees them. They reach a client only through proximity lookup
    (``reverse``) and the explicit ``/learned`` endpoint, and only join name
    search if a name arrives from a non-trace source.
    """

    def __init__(self, index: GeocodeIndex,
                 learned: Optional[LearnedPoiIndex] = None) -> None:
        self._index = index
        self._learned = learned if learned is not None else LearnedPoiIndex()
        self._learned_enabled = True
        self._places_loaded = 0
        self.auth = _NoAuth()

    @classmethod
    def from_geojson(cls, path: str, learned_facts_path: Optional[str] = None,
                     places_path: Optional[str] = None) -> "GeocodeService":
        with open(path, "r", encoding="utf-8") as fh:
            fc = json.load(fh)
        service = cls(GeocodeIndex.from_geojson(fc))
        if places_path:
            service.load_places(places_path)
        if learned_facts_path:
            service.load_learned_pois(learned_facts_path)
        return service

    # ---- POI layer (Overture places) ------------------------------------

    def load_places(self, path: str) -> int:
        """Merge an extra POI FeatureCollection into the search index.

        This is the fix for "half the names in Google Maps don't come up": the
        OSM basemap carries a few thousand named POIs for Qatar, Overture
        carries an order of magnitude more. They are merged into the SAME index
        (unlike the learned layer, which is deliberately held apart) because
        Overture places are surveyed third-party data, not inferences from user
        movement — there is no privacy reason to segregate them, and search
        ranking can only compare them against OSM names if it can see both.

        A missing or unreadable file returns 0 and changes nothing: a
        deployment that has not run the ingest script must keep working exactly
        as it does today. Returns how many entries were added.
        """
        if not path or not os.path.exists(path):
            return 0
        try:
            with open(path, "r", encoding="utf-8") as fh:
                fc = json.load(fh)
        except (OSError, ValueError):
            return 0
        if not isinstance(fc, dict) or not isinstance(fc.get("features"), list):
            return 0
        added = self._index.extend_from_geojson(fc)
        self._places_loaded += added
        return added

    # ---- learned layer (issue 09) ---------------------------------------

    def load_learned_pois(self, path: str) -> int:
        """Load promoted ``poi_candidate`` facts. Returns how many were indexed."""
        facts = read_learned_facts(path)
        self._learned = LearnedPoiIndex(pois_from_facts(facts))
        return len(self._learned)

    def set_learned_enabled(self, enabled: bool) -> None:
        """One-flag rollback: off means the pre-learning geocoder exactly."""
        self._learned_enabled = bool(enabled)

    def withdraw_learned(self, fact_key: str) -> bool:
        """Remove one promoted POI and rebuild without it (issue 09 rollback)."""
        return self._learned.withdraw(fact_key)

    def learned_layer(self) -> Dict[str, Any]:
        """The learned layer as GeoJSON, for display and audit."""
        doc = self._learned.to_geojson()
        doc["properties"] = {
            "count": len(self._learned),
            "enabled": self._learned_enabled,
            "named": sum(1 for p in self._learned.all() if p.searchable),
        }
        return doc

    def name_learned_poi(self, fact_key: str, name: str) -> bool:
        """Attach a name from a non-trace source; the POI then joins search."""
        return self._learned.name_poi(fact_key, name)

    # ---- query surface ---------------------------------------------------

    def search(self, query: str, limit: int = 20,
               near: "Optional[Tuple[float, float]]" = None,
               lang: Optional[str] = None) -> Dict[str, Any]:
        """Name search over the OSM index, plus any *named* learned POIs.

        Unnamed learned places never appear here — there is nothing to match a
        query against, and inventing a placeholder name would pollute the index.

        ``near`` is the driver's position, and it is a RANKING signal, not a
        filter: a place outside the radius of interest still appears, just
        below the ones they could actually drive to. ``lang`` picks the label
        language, the same way ``/speed`` and ``/navigate`` already do.
        """
        hits = self._index.search(query, limit, near=near)
        features = [h.to_geojson(lang=lang) for h in hits]
        if self._learned_enabled:
            needle = query.strip().casefold()
            if needle:
                for poi in self._learned.all():
                    if poi.searchable and needle in poi.name.casefold():
                        features.append(poi.to_geojson())
        return {
            "type": "FeatureCollection",
            "features": features[:limit] if limit > 0 else features,
        }

    def reverse(self, lat: float, lon: float, limit: int = 3,
                learned_radius_m: float = LEARNED_NEAR_RADIUS_M,
                lang: Optional[str] = None) -> Dict[str, Any]:
        """Nearest named OSM features, then any learned places at this spot.

        Learned entries are appended rather than interleaved, and carry
        ``learned: true`` plus ``kind: learned_poi``, so a client can style them
        apart from OSM data — and so a wrong promotion is visible rather than
        indistinguishable from surveyed ground truth.

        ``lang="en"`` resolves each label through ``name:en`` exactly as
        ``/search``, ``/speed`` and ``/navigate`` already do. This endpoint was
        the one place that did not, and it is the one the client reads aloud for
        a dropped pin: measured on 2026-09-21, the nearest way to a point in
        Mansoura is tagged ``name=ابن درهم`` with a Latin ``name:en`` in OSM,
        and ``/reverse`` returned the Arabic — so a pin dropped by an English
        device put an Arabic street name into the destination chip, the Recents
        list and the arrival announcement, which is the same
        product-disagreeing-with-itself defect `to_geojson` documents for
        search. The native name is still returned as ``name_local`` whenever it
        differs, so nothing is lost.

        Each feature also carries ``distance_m``: the distance it was RANKED on,
        which for a way is measured to its polyline, not to the single vertex its
        ``geometry`` reports. The client bounds a dropped pin's name by distance
        ("only name it after something that is actually here"), and computing
        that bound from the vertex would reject the very road the pin is on — a
        way can pass 5 m from the pin while its first node is 350 m away. The
        learned-POI path below has carried ``distance_m`` since it was added;
        this brings the OSM hits alongside it.
        """
        hits = self._index.reverse(lat, lon, limit)
        features = []
        for h in hits:
            feature = h.to_geojson(lang=lang)
            feature["properties"]["distance_m"] = round(_hit_distance_m(lat, lon, h), 1)
            features.append(feature)
        if self._learned_enabled and len(self._learned):
            for poi, distance_m in self._learned.near(lat, lon, learned_radius_m, limit):
                feature = poi.to_geojson()
                feature["properties"]["distance_m"] = round(distance_m, 1)
                features.append(feature)
        return {"type": "FeatureCollection", "features": features}

    def speed(self, lat: float, lon: float,
              lang: "str | None" = None) -> Dict[str, Any]:
        """Nearest drivable road's limit, and its name in the asked-for language.

        ``lang="en"`` resolves ``name`` the same way ``/navigate?lang=en`` does
        (see ``vector_routing.router.display_name``): prefer OSM's ``name:en``,
        fall back to the local name. Consistency between the two endpoints is
        the point — the maneuver banner and the road-you-are-on readout sit
        four millimetres apart on the same screen, and having one say "Al
        Urouba Street" while the other says "شارع العروبة" is worse than
        either language used throughout.

        ``name_en`` is always returned unresolved as well, so a caller can make
        its own choice without a second request.
        """
        out = self._index.speed(lat, lon)
        if lang == "en" and out.get("name_en"):
            out = dict(out, name=out["name_en"])
        return out

    def along(self, line, radius_m: float = 400.0,
              kinds=(), limit: int = 20) -> Dict[str, Any]:
        """Named places within ``radius_m`` of a route polyline (W53).

        ``line`` is ``[[lon, lat], ...]`` — the GeoJSON coordinate order the
        routing engine already emits. Reuses the same index as /search.
        """
        from .corridor import corridor_hits
        feats = corridor_hits(self._index, line, radius_m=radius_m,
                              kinds=kinds, limit=limit)
        return {"type": "FeatureCollection", "features": feats}

    def health(self) -> Dict[str, Any]:
        return {"status": "ok", "service": "vector-geocoder",
                "entries": self._index.size(),
                "poi_entries": self._places_loaded,
                "learned_pois": len(self._learned),
                "learned_enabled": self._learned_enabled}


class _NoAuth:
    """Placeholder auth; replaced by the shared broker in composed deploys.

    Kept dependency-free so the engine runs standalone in the gate. In
    production vector-web enforces the bearer token at its edge and proxies
    /search without re-checking (MapLibre fetches tiles via XHR that drop
    auth headers, but /search is a normal fetch with the Authorization header).
    """

    def enforce(self, handler: BaseHTTPRequestHandler) -> bool:  # noqa: D401
        return True

    def cors_headers(self) -> Dict[str, str]:
        return {"Access-Control-Allow-Origin": "*"}


#: Whether to write one line per query to stdout. On by default.
#:
#: This service logged NOTHING. Two drives produced four reproducible search
#: failures and the containers had zero and one line of output between them,
#: so every one of them had to be reconstructed by re-running the queries by
#: hand against production days later. A search that answers `0` is the single
#: most diagnostic event this service can emit and it was being discarded.
#:
#: Set ``VECTOR_LOG_QUERIES=0`` to silence it.
LOG_QUERIES = os.environ.get("VECTOR_LOG_QUERIES", "1") not in ("0", "false", "no")

#: Longest query text written to the log. A search box is free text; this
#: bounds what one request can put in a log line.
_LOG_Q_MAX = 120


class _Handler(BaseHTTPRequestHandler):
    service: Optional[GeocodeService] = None

    def log_message(self, *args) -> None:  # silence default request logging
        return

    def log_query(self, endpoint: str, query: str, results: int,
                  started: float, **extra: Any) -> None:
        """One line describing a query and what it answered.

        **No position is recorded, ever.** `lat`/`lon` reach this service on
        every search and they are exactly the location data ADR-0066 keeps out
        of any store — accuracy floor, coordinate precision and temporal
        coarsening all apply before location may be persisted, and a request
        log satisfies none of them. Whether a position was SENT is recorded,
        because "did the client supply one" is the question a proximity bug
        needs answered, and that is not itself location.

        The query text is recorded: it is what the driver chose to make public
        to the service in order to be answered at all, and without it a zero
        result is unattributable.
        """
        if not LOG_QUERIES:
            return
        q = query if len(query) <= _LOG_Q_MAX else query[:_LOG_Q_MAX] + "..."
        bits = " ".join(f"{k}={v}" for k, v in extra.items())
        print(f'[query] {endpoint} q="{q}" results={results} '
              f'{bits} ms={(time.time() - started) * 1000:.0f}',
              flush=True)

    def _send_json(self, code: int, obj: Any) -> None:
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        for k, v in self.service.auth.cors_headers().items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/healthz":
            self._send_json(200, self.service.health())
            return
        if parsed.path == "/search":
            q = urllib.parse.parse_qs(parsed.query)
            query = q.get("q", [""])[0]
            try:
                limit = int(q.get("limit", ["20"])[0])
            except ValueError:
                limit = 20
            if not query.strip():
                self._send_json(400, {"error": "missing q parameter"})
                return
            # The client has been sending `lat`/`lon` on every search since V4
            # and this handler read neither, so results were ordered by name
            # length and a driver in Doha was offered a filling station in
            # Dukhan. A malformed pair is ignored rather than rejected: a
            # search that still answers is better than one that 400s because
            # the fix had no position yet.
            near = None
            try:
                near = (float(q["lat"][0]), float(q["lon"][0]))
            except (KeyError, IndexError, TypeError, ValueError):
                near = None
            lang = (q.get("lang", [""])[0] or "").lower()[:2] or None
            started = time.time()
            result = self.service.search(query, limit, near=near, lang=lang)
            self.log_query("search", query, len(result["features"]), started,
                           near=("y" if near else "n"), lang=lang or "-")
            self._send_json(200, result)
            return
        if parsed.path == "/reverse":
            q = urllib.parse.parse_qs(parsed.query)
            try:
                lat = float(q.get("lat", ["0"])[0])
                lon = float(q.get("lon", ["0"])[0])
                limit = int(q.get("limit", ["3"])[0])
            except (ValueError, KeyError):
                self._send_json(400, {"error": "lat/lon/limit required"})
                return
            # Parsed exactly as `/speed` parses it, so the two endpoints cannot
            # disagree about what `lang=en` means.
            lang = (q.get("lang", [""])[0] or "").lower()[:2] or None
            self._send_json(200, self.service.reverse(lat, lon, limit, lang=lang))
            return

        if parsed.path == "/learned":
            # The learned POI layer (issue 09), for display and audit. Kept a
            # separate endpoint so a client opts in to inferred places rather
            # than receiving them mixed into search results.
            self._send_json(200, self.service.learned_layer())
            return
        if parsed.path == "/speed":
            q = urllib.parse.parse_qs(parsed.query)
            try:
                lat = float(q.get("lat", ["0"])[0])
                lon = float(q.get("lon", ["0"])[0])
            except (ValueError, KeyError):
                self._send_json(400, {"error": "lat/lon required"})
                return
            lang = (q.get("lang", [""])[0] or "").lower()[:2] or None
            self._send_json(200, self.service.speed(lat, lon, lang=lang))
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except (TypeError, ValueError):
            length = 0
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            self._send_json(400, {"error": "invalid JSON body"})
            return
        if not isinstance(body, dict):
            self._send_json(400, {"error": "body must be a JSON object"})
            return

        if parsed.path == "/along":
            # W53: POIs along a route corridor. POST body carries the route
            # LineString coordinates (too long for a query string on long
            # routes); radius/kinds/limit travel in the query string.
            q = urllib.parse.parse_qs(parsed.query)
            try:
                radius = float(q.get("radius", ["400"])[0])
                limit = int(q.get("limit", ["20"])[0])
            except ValueError:
                self._send_json(400, {"error": "radius/limit must be numeric"})
                return
            kinds_s = q.get("kinds", [""])[0]
            kinds = [k for k in kinds_s.split(",") if k.strip()]
            coords = body.get("coordinates")
            if not isinstance(coords, list) or len(coords) < 2:
                self._send_json(400, {"error": 'body must be {"coordinates": [[lon,lat], ...]} with >= 2 points'})
                return
            self._send_json(200, self.service.along(
                coords, radius_m=radius, kinds=kinds, limit=limit))
            return

        if parsed.path == "/learned/withdraw":
            # Issue 09's rollback path: one call removes a promoted POI and the
            # index is rebuilt without it.
            fact_key = body.get("fact_key")
            if not isinstance(fact_key, str) or not fact_key.strip():
                self._send_json(400, {"error": "fact_key required"})
                return
            removed = self.service.withdraw_learned(fact_key)
            self._send_json(200 if removed else 404,
                            {"status": "withdrawn" if removed else "not found",
                             "fact_key": fact_key})
            return

        if parsed.path == "/learned/name":
            # Naming comes from a NON-TRACE source only. Movement can show that
            # a place exists; it must never be used to infer what it is.
            fact_key = body.get("fact_key")
            name = body.get("name")
            if not isinstance(fact_key, str) or not isinstance(name, str) or not name.strip():
                self._send_json(400, {"error": "fact_key and non-empty name required"})
                return
            ok = self.service.name_learned_poi(fact_key, name)
            self._send_json(200 if ok else 404,
                            {"status": "named" if ok else "not found", "fact_key": fact_key})
            return

        self._send_json(404, {"error": "not found"})


def default_places_path(index_path: str) -> Optional[str]:
    """Convention: ``<region>.geojson`` is accompanied by ``<region>_places.geojson``.

    ``bootstrap.sh`` copies ``$WORK/*.geojson`` into the basemap volume, so the
    Overture output lands next to the basemap with no extra plumbing. Returning
    None when it is absent is what makes the feature opt-in-by-presence.
    """
    if not index_path:
        return None
    base, ext = os.path.splitext(index_path)
    candidate = f"{base}_places{ext or '.geojson'}"
    return candidate if os.path.exists(candidate) else None


def make_server(port: int = 8085, index_path: Optional[str] = None,
                service: Optional[GeocodeService] = None,
                host: str = "0.0.0.0",
                places_path: Optional[str] = None) -> ThreadingHTTPServer:
    if service is None:
        if index_path is None:
            raise ValueError("provide index_path or service")
        service = GeocodeService.from_geojson(
            index_path, places_path=places_path or default_places_path(index_path))
    _Handler.service = service
    return ThreadingHTTPServer((host, port), _Handler)


def main(argv: Optional[List[str]] = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="vector-geocoder server")
    parser.add_argument("--port", type=int, default=8085)
    parser.add_argument("--index", default=None,
                        help="path to a basemap GeoJSON to index")
    parser.add_argument("--learned-pois", default=None,
                        help="path to learned_pois.json from vector-learning (issue 09)")
    parser.add_argument("--places", default=None,
                        help="path to an Overture places GeoJSON (see "
                             "scripts/fetch_overture_places.py). Defaults to "
                             "<index>_places.geojson when that file exists.")
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args(argv)
    if not args.index:
        print("error: --index <basemap.geojson> is required", file=__import__("sys").stderr)
        return 2
    learned_path = args.learned_pois or os.environ.get("VECTOR_LEARNED_POIS")
    places_path = (args.places or os.environ.get("VECTOR_PLACES_INDEX")
                   or default_places_path(args.index))
    service = GeocodeService.from_geojson(args.index, learned_facts_path=learned_path,
                                          places_path=places_path)
    if os.environ.get("VECTOR_LEARNED_ENABLED", "1") not in ("1", "true", "yes"):
        service.set_learned_enabled(False)
        print("[geocoder] learned POI layer DISABLED by VECTOR_LEARNED_ENABLED")
    health = service.health()
    if places_path:
        print(f"[geocoder] places layer: {places_path} "
              f"(+{health['poi_entries']} entries)")
    else:
        print("[geocoder] no places layer found; serving basemap names only")
    print(f"vector-geocoder on :{args.port} (entries={health['entries']}, "
          f"poi_entries={health['poi_entries']}, "
          f"learned_pois={health['learned_pois']})")
    # NOTE: this used to reference an undefined `srv`, so `python -m
    # vector_geocoder.serve` raised NameError before it ever bound a port --
    # the docker-compose entrypoint could not have worked.
    srv = make_server(port=args.port, service=service, host=args.host)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
