/* Vector PWA service worker (ADR-0063 — installable web PWA).
 *
 * Strategy:
 *  - Precache the app shell (index, manifest, icons, vendored MapLibre) on
 *    install so the app opens offline.
 *  - Navigation requests (GET / and /?...): network-first, fall back to the
 *    cached shell when offline. (The shell is served token-free for sub-
 *    resources; the initial page load carries ?token= in the deployed stack.)
 *  - Static assets (/icons, /vendor, /manifest.webmanifest, /sw.js):
 *    cache-first, revalidate in background.
 *  - Map tiles + glyphs (/tiles/..., /glyphs/...): STALE-WHILE-REVALIDATE in a
 *    dedicated tile cache (W53 offline navigation). Tiles are immutable per
 *    tile-epoch (the URL carries ?v=<epoch>), so caching them cannot serve
 *    stale geography — a re-bake changes the epoch, which changes the URL,
 *    which misses the cache. The separate cache has an entry cap with FIFO
 *    eviction so a long session cannot grow unbounded.
 *    Everything else under /route, /navigate, /traffic, ... stays NETWORK-ONLY:
 *    never serve a stale route or traffic snapshot from cache.
 *
 * Versioned cache (CACHE) so a bump invalidates the old shell cleanly.
 */

// Bumped for the versioned-tile-URL shell (issue 08): the cached shell holds the
// tile-epoch logic, so an old shell would keep requesting unversioned tile URLs
// and never see a re-bake.
// BUMP THIS WHENEVER THE INGEST CONTRACT CHANGES (adr-0068 / ticket 24).
// The shell is cached, so an un-bumped version leaves old clients posting under
// the previous contract until they happen to update — which is precisely the
// stale-client window ticket 24 exists to close.
// v9: W53 offline tiles — tiles/glyphs move from network-only to a dedicated
// stale-while-revalidate cache. An old shell never cached them, so nothing to
// invalidate; the bump just makes sure every client picks up the new handler.
// v10: ticket 36 extracts the route geometry into /js/geo.js, which the viewer
// loads as a BLOCKING script. A v9 shell has the old index.html cached and knows
// nothing about /js/, so without this bump an offline load would serve a page
// whose <script src="./js/geo.js"> misses the cache — VectorGeo undefined, and
// the app dies on a ReferenceError before the map initialises. The bump plus the
// SHELL entry below make the module part of the offline shell.
const VERSION = "v10";
const CACHE = `vector-shell-${VERSION}`;
const TILE_CACHE = `vector-tiles-${VERSION}`;
// Entry cap for the tile cache. ~14 KB average per MVT tile observed in this
// stack; 4000 entries ≈ 55 MB, well inside typical storage quotas while covering
// every z8–13 tile of Qatar plus a deep urban working set at z14+.
const TILE_CACHE_MAX = 4000;
const SHELL = [
  "/",
  "/collect",
  "/manifest.webmanifest",
  "/sw.js",
  "/vendor/maplibre-gl.css",
  "/vendor/maplibre-gl.js",
  "/js/geo.js",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/icon-maskable-512.png",
  "/icons/icon.svg",
];

// Paths that must always hit the network (live, token-gated, or dynamic).
function isApiPath(url) {
  const p = url.pathname;
  return (
    p.startsWith("/route") ||
    p.startsWith("/navigate") ||
    p.startsWith("/overlay") ||
    p.startsWith("/traffic") ||
    p.startsWith("/search") ||
    p.startsWith("/reverse") ||
    p.startsWith("/speed") ||
    p.startsWith("/incidents") ||
    p.startsWith("/healthz") ||
    // Issue 07/08: the ETA sink and the evolution dashboard are live data, and
    // /tiles/version (NOT matched here — see isTilePath) is the response that
    // must NEVER be cached: it tells the client its cached tiles are stale.
    p.startsWith("/eta") ||
    p.startsWith("/learned") ||
    p.startsWith("/evolution") ||
    p.startsWith("/privacy-counters")
  );
}

// Immutable-per-epoch map data: tiles and glyph ranges. Safe to cache because
// the tile epoch lives in the query string (?v=N) — a re-bake changes N, which
// changes the URL, so the cache can never answer with old geography.
function isTilePath(url) {
  const p = url.pathname;
  return (
    (p.startsWith("/tiles/") && !p.startsWith("/tiles/version")) ||
    p.startsWith("/glyphs/")
  );
}

async function trimTileCache(cache) {
  const keys = await cache.keys();
  if (keys.length <= TILE_CACHE_MAX) return;
  // FIFO by insertion order: drop oldest-first. Map data access is spatially
  // local, so the newest entries are also the most likely to be re-requested.
  const excess = keys.length - TILE_CACHE_MAX;
  for (let i = 0; i < excess; i++) await cache.delete(keys[i]);
}

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      .catch(() => {}) // don't block install if one asset is temporarily missing
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((k) => k !== CACHE && k !== TILE_CACHE).map((k) => caches.delete(k))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // never proxy cross-origin

  // Live APIs: network-only, never stale.
  if (isApiPath(url)) {
    event.respondWith(fetch(req));
    return;
  }

  // Tiles & glyphs: stale-while-revalidate against the dedicated tile cache.
  // Online -> serve from network immediately and refresh the copy in background
  // is the classic SWR shape; for MAP TILES we invert it: cached copy first
  // (instant render, no tile flicker), network refresh in background. Offline ->
  // cached copy, which is exactly what keeps navigation alive past signal loss.
  if (isTilePath(url)) {
    event.respondWith(
      caches.open(TILE_CACHE).then(async (cache) => {
        const cached = await cache.match(req);
        const network = fetch(req)
          .then((res) => {
            if (res && res.status === 200) {
              const copy = res.clone();
              cache.put(req, copy).then(() => trimTileCache(cache));
            }
            return res;
          })
          .catch(() => null);
        return cached || (await network) || Response.error();
      })
    );
    return;
  }

  // Navigations: network-first, fall back to the cached copy of THAT page.
  //
  // The cache key is the page's own path, not "/". Caching every successful
  // navigation under "/" was harmless while there was one page and became a bug
  // the moment there were two: visiting /collect would overwrite the cached map
  // shell, and the next offline visit to "/" would serve the collect page. The
  // token is stripped from the key, so a fresh token still hits the cached shell
  // instead of accumulating one entry per session.
  if (req.mode === "navigate") {
    const key = url.pathname === "/index.html" ? "/" : url.pathname;
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res && res.status === 200) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put(key, copy));
          }
          return res;
        })
        .catch(() =>
          caches
            .match(key)
            .then((r) => r || caches.match("/").then((s) => s || caches.match("/index.html")))
        )
    );
    return;
  }

  // Static assets: cache-first, background revalidate.
  event.respondWith(
    caches.match(req).then((cached) => {
      const network = fetch(req)
        .then((res) => {
          if (res && res.status === 200) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put(req, copy));
          }
          return res;
        })
        .catch(() => cached);
      return cached || network;
    })
  );
});
