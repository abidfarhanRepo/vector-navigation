/*
 * geo.js — route geometry primitives (ticket 36).
 *
 * Extracted from index.html because the navigation math is the part of the
 * client that MUST be correct and was the part with no executable coverage:
 * vector-web's 187 tests assert against index.html as *text*, so a threshold
 * that was wrong by 31x sat next to a comment claiming it was right and no
 * test could see it. Everything here is pure and unit-tested in
 * tests/geo.test.mjs.
 *
 * Loaded as a BLOCKING classic script before the app script — never `defer`,
 * never `type="module"`. Both of those run after the document is parsed, i.e.
 * after the inline script that uses these functions, which is exactly the
 * failure the Shipaton config.js tag hit (window.__VECTOR_API_BASE__ undefined,
 * silent fallback to location.origin).
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === "object" && module && module.exports) module.exports = api;
  else root.VectorGeo = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var EARTH_R_M = 6371000;
  var DEG = Math.PI / 180;
  // Metres per degree of latitude. Longitude degrees shrink by cos(lat): at
  // Doha's 25 degN that is a ~9.4% difference, so any "distance" computed as
  // raw sqrt(dlng^2 + dlat^2) is anisotropic and direction-dependent.
  //
  // DERIVED from EARTH_R_M rather than hardcoded to the usual 111320. The
  // cumulative index is built with haversine on a sphere of EARTH_R_M, and the
  // projection frame below must measure on the SAME sphere or the two disagree
  // by 0.11% -- about a metre per kilometre. That is harmless for an off-route
  // test and not harmless for dead reckoning, where it accumulates as drift
  // between the snapped puck and its own route position.
  var M_PER_DEG_LAT = EARTH_R_M * DEG;

  /** Great-circle distance in metres. Argument order is (lng, lat, lng, lat). */
  function haversineM(lng1, lat1, lng2, lat2) {
    var dLat = (lat2 - lat1) * DEG;
    var dLng = (lng2 - lng1) * DEG;
    var a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(lat1 * DEG) * Math.cos(lat2 * DEG) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 2 * EARTH_R_M * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  /** Signed smallest angle from `b` to `a`, in (-180, 180]. */
  function angDiffDeg(a, b) {
    var d = ((a - b + 540) % 360) - 180;
    return d;
  }

  /**
   * Build the cumulative-distance index a route needs for projection.
   * Returns null for a degenerate geometry rather than a half-built index.
   */
  function indexRoute(coords) {
    if (!Array.isArray(coords) || coords.length < 2) return null;
    var cum = new Array(coords.length);
    cum[0] = 0;
    for (var i = 1; i < coords.length; i++) {
      cum[i] = cum[i - 1] + haversineM(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1]);
    }
    return { coords: coords, cum: cum, totalM: cum[cum.length - 1] };
  }

  /**
   * Perpendicular projection of a point onto a route polyline.
   *
   * This replaces nearest-VERTEX search, which was wrong in two ways that both
   * showed up as user-visible navigation defects:
   *
   *   - Off-route distance: on a highway with ~200 m between shape points you
   *     can sit dead-centre in the lane and still be 100 m from the nearest
   *     vertex. Point-to-segment is the only measure that means "off the road".
   *   - Progress: snapping travelled distance to `cum[nearestVertex]` makes the
   *     turn countdown hold and then jump by the vertex spacing instead of
   *     counting down smoothly.
   *
   * Returns { alongM, offsetM, segIdx, t, lng, lat } where alongM is distance
   * travelled along the route to the projected point, offsetM is perpendicular
   * distance from it (both metres), and lng/lat is the snapped position.
   * Returns null for a degenerate index.
   */
  function projectToRoute(lng, lat, index) {
    if (!index || !index.coords || index.coords.length < 2) return null;
    var cs = index.coords;
    var cum = index.cum;
    // Local equirectangular frame centred on the query latitude. Over the few
    // hundred metres that matter for snapping this is accurate to well under a
    // metre, and unlike raw degrees it is isotropic.
    var kx = Math.cos(lat * DEG) * M_PER_DEG_LAT;
    var ky = M_PER_DEG_LAT;
    var px = lng * kx;
    var py = lat * ky;

    var bestOffset = Infinity;
    var best = null;

    for (var i = 0; i < cs.length - 1; i++) {
      var ax = cs[i][0] * kx, ay = cs[i][1] * ky;
      var bx = cs[i + 1][0] * kx, by = cs[i + 1][1] * ky;
      var vx = bx - ax, vy = by - ay;
      var len2 = vx * vx + vy * vy;

      var t = 0;
      if (len2 > 0) {
        t = ((px - ax) * vx + (py - ay) * vy) / len2;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
      }

      var qx = ax + t * vx, qy = ay + t * vy;
      var dx = px - qx, dy = py - qy;
      var offsetM = Math.sqrt(dx * dx + dy * dy);

      if (offsetM < bestOffset) {
        bestOffset = offsetM;
        // Segment length is taken from the cumulative index, not recomputed in
        // the local frame, so alongM stays exactly consistent with cum/totalM.
        var segLenM = cum[i + 1] - cum[i];
        best = {
          offsetM: offsetM,
          alongM: cum[i] + t * segLenM,
          segIdx: i,
          t: t,
          lng: cs[i][0] + (cs[i + 1][0] - cs[i][0]) * t,
          lat: cs[i][1] + (cs[i + 1][1] - cs[i][1]) * t,
        };
      }
    }
    return best;
  }

  /**
   * Position of a point `alongM` metres into the route, plus the bearing of the
   * segment it lands on. This is what lets the puck be dead-reckoned forward
   * between GPS fixes instead of easing to raw 1 Hz positions (ticket 37).
   * Clamps to both ends rather than returning null past the end.
   */
  function pointAtDistance(alongM, index) {
    if (!index || !index.coords || index.coords.length < 2) return null;
    var cs = index.coords;
    var cum = index.cum;
    var d = alongM;
    if (!(d > 0)) d = 0;
    if (d >= index.totalM) {
      var n = cs.length - 1;
      return { lng: cs[n][0], lat: cs[n][1], bearing: bearingDeg(cs[n - 1], cs[n]), segIdx: n - 1, t: 1 };
    }
    // Binary search for the segment containing d.
    var lo = 0, hi = cs.length - 1;
    while (lo < hi - 1) {
      var mid = (lo + hi) >> 1;
      if (cum[mid] <= d) lo = mid;
      else hi = mid;
    }
    var segLenM = cum[lo + 1] - cum[lo];
    var t = segLenM > 0 ? (d - cum[lo]) / segLenM : 0;
    return {
      lng: cs[lo][0] + (cs[lo + 1][0] - cs[lo][0]) * t,
      lat: cs[lo][1] + (cs[lo + 1][1] - cs[lo][1]) * t,
      bearing: bearingDeg(cs[lo], cs[lo + 1]),
      segIdx: lo,
      t: t,
    };
  }

  /** Initial bearing in degrees (0 = north, clockwise) from a to b. */
  function bearingDeg(a, b) {
    var y = Math.sin((b[0] - a[0]) * DEG) * Math.cos(b[1] * DEG);
    var x =
      Math.cos(a[1] * DEG) * Math.sin(b[1] * DEG) -
      Math.sin(a[1] * DEG) * Math.cos(b[1] * DEG) * Math.cos((b[0] - a[0]) * DEG);
    return (Math.atan2(y, x) / DEG + 360) % 360;
  }

  return {
    haversineM: haversineM,
    angDiffDeg: angDiffDeg,
    indexRoute: indexRoute,
    projectToRoute: projectToRoute,
    pointAtDistance: pointAtDistance,
    bearingDeg: bearingDeg,
    M_PER_DEG_LAT: M_PER_DEG_LAT,
  };
});
