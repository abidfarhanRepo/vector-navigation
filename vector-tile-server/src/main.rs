//! vector-tile-server — Rust serving engine (adr-0009, adr-0015 M1 slice).
//!
//! Serves Vector MVT tiles produced by the Python tile-gen pipeline from a
//! tile directory (`VECTOR_TILE_DIR`, default `./tiles`) laid out as
//! `{z}/{x}/{y}.mvt`. Also serves self-hosted SDF glyph PBFs from a glyph
//! directory (`VECTOR_GLYPH_DIR`, default `./glyphs`) at
//! `/glyphs/{fontstack}/{range}.pbf` so the viewers need NO third-party font
//! service (adr-0059). Also serves a MapLibre GL viewer at `/` and a
//! `/healthz` probe. This is the serving half of the M1 "Map Display" milestone.

use axum::{
    extract::{Path, State},
    http::{header, StatusCode},
    response::{Html, IntoResponse},
    routing::get,
    Router,
};
use std::net::SocketAddr;
use std::path::PathBuf;

#[derive(Clone)]
struct AppState {
    tile_dir: PathBuf,
    glyph_dir: PathBuf,
}

async fn health() -> &'static str {
    "ok"
}

const VIEWER_HTML: &str = include_str!("../static/index.html");

async fn viewer() -> Html<&'static str> {
    Html(VIEWER_HTML)
}

/// GET /tiles/{z}/{x}/{y}.mvt — serve a vector tile as protobuf.
async fn get_tile(
    State(state): State<AppState>,
    Path((z, x, yext)): Path<(u32, u32, String)>,
) -> impl IntoResponse {
    // yext is "{y}.mvt" or "{y}.pbf"
    let y = yext
        .split('.')
        .next()
        .and_then(|s| s.parse::<u32>().ok());
    let y = match y {
        Some(v) => v,
        None => return (StatusCode::BAD_REQUEST, "invalid tile y").into_response(),
    };
    let path = state
        .tile_dir
        .join(z.to_string())
        .join(x.to_string())
        .join(format!("{y}.mvt"));
    match tokio::fs::read(&path).await {
        Ok(bytes) => (
            StatusCode::OK,
            [
                (header::CONTENT_TYPE, "application/x-protobuf"),
                (header::ACCESS_CONTROL_ALLOW_ORIGIN, "*"),
                // A tile is immutable *for its epoch*. A re-bake changes the
                // URL (clients append `?v=<epoch>`, see /tiles/version), never
                // the bytes behind an unchanged URL — so caching hard here is
                // correct and is what keeps the map fast.
                (header::CACHE_CONTROL, "public, max-age=86400"),
            ],
            bytes,
        )
            .into_response(),
        Err(_) => (StatusCode::NOT_FOUND, "tile not found").into_response(),
    }
}

/// GET /tiles/version — the tile-set epoch (issue 08 cache invalidation).
///
/// Read from `VERSION.json` in the tile directory, which the bake and re-bake
/// scripts bump *after* new tiles land on disk. Clients fetch this once and put
/// the epoch in their tile URLs, so a promoted road cannot be masked by a stale
/// browser, CDN or MapLibre cache entry — the Session 50 failure mode.
///
/// `no-store` is not optional. This is the response that tells a client its
/// cached tiles are stale; cache it and the whole scheme silently stops working.
/// A missing or unreadable file is epoch 0, not an error: a tile set that has
/// never been re-baked is a normal state.
async fn get_tile_version(State(state): State<AppState>) -> impl IntoResponse {
    let path = state.tile_dir.join("VERSION.json");
    let body = match tokio::fs::read_to_string(&path).await {
        Ok(text) => {
            let epoch = parse_epoch(&text);
            format!("{{\"epoch\":{epoch}}}")
        }
        Err(_) => "{\"epoch\":0,\"reason\":\"never versioned\"}".to_string(),
    };
    (
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "application/json"),
            (header::ACCESS_CONTROL_ALLOW_ORIGIN, "*"),
            (header::CACHE_CONTROL, "no-store"),
        ],
        body,
    )
        .into_response()
}

/// Extract `"epoch": N` from the version file without pulling in a JSON crate.
///
/// The file is written by our own tooling and holds one integer we care about,
/// so a dependency would be more surface than substance. Anything unparseable
/// is epoch 0 — a served tile set is never worth failing over a version string.
fn parse_epoch(text: &str) -> u64 {
    let Some(rest) = text.split("\"epoch\"").nth(1) else {
        return 0;
    };
    let digits: String = rest
        .trim_start()
        .trim_start_matches(':')
        .trim_start()
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    digits.parse().unwrap_or(0)
}

/// GET /glyphs/{fontstack}/{range}.pbf — serve a self-hosted SDF glyph PBF.
///
/// `fontstack` is a URL-encoded font name (e.g. "Open%20Sans%20Regular");
/// `range` is "{start}-{end}" (e.g. "32-126"). No third-party font CDN is
/// contacted — the PBF is committed under VECTOR_GLYPH_DIR (see
/// scripts/gen_glyphs.py, adr-0059).
async fn get_glyph(
    State(state): State<AppState>,
    Path((fontstack, range)): Path<(String, String)>,
) -> impl IntoResponse {
    // Decode the percent-encoded fontstack (map "Open%20Sans%20Regular" -> "Open Sans Regular").
    let decoded = percent_encoding_decode(&fontstack);
    let stack_dir = state.glyph_dir.join(&decoded);
    // Unknown font stack -> 404 (we only serve self-hosted stacks committed
    // under VECTOR_GLYPH_DIR; see scripts/gen_glyphs.py, adr-0059).
    if !stack_dir.is_dir() {
        return (StatusCode::NOT_FOUND, "glyph stack not found").into_response();
    }
    let file = format!("{range}.pbf");
    let path = stack_dir.join(&file);
    match tokio::fs::read(&path).await {
        Ok(bytes) => (
            StatusCode::OK,
            [
                (header::CONTENT_TYPE, "application/x-protobuf"),
                (header::ACCESS_CONTROL_ALLOW_ORIGIN, "*"),
                (header::CACHE_CONTROL, "public, max-age=86400"),
            ],
            bytes,
        )
            .into_response(),
        // Missing range: return an EMPTY but valid glyph stack PBF so MapLibre
        // does not hard-fail the style on ranges it probes but we do not ship
        // (e.g. PUA 65024-65279). 0x1a = field 3 (stacks) len 0 -> empty stack.
        Err(_) => (
            StatusCode::OK,
            [
                (header::CONTENT_TYPE, "application/x-protobuf"),
                (header::ACCESS_CONTROL_ALLOW_ORIGIN, "*"),
                (header::CACHE_CONTROL, "public, max-age=86400"),
            ],
            Vec::from(b"\x1a\x00".as_slice()),
        )
            .into_response(),
    }
}

/// Minimal percent-decoder (handles %XX and '+'). Enough for fontstack names.
fn percent_encoding_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hi = (bytes[i + 1] as char).to_digit(16);
                let lo = (bytes[i + 2] as char).to_digit(16);
                if let (Some(h), Some(l)) = (hi, lo) {
                    out.push((h * 16 + l) as u8);
                    i += 3;
                } else {
                    out.push(bytes[i]);
                    i += 1;
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn app(state: AppState) -> Router {
    Router::new()
        .route("/", get(viewer))
        .route("/healthz", get(health))
        // Two segments, so it cannot shadow the three-segment tile route below.
        .route("/tiles/version", get(get_tile_version))
        .route("/tiles/:z/:x/:y", get(get_tile))
        .route("/glyphs/:fontstack/:range", get(get_glyph))
        .with_state(state)
}

#[tokio::main]
async fn main() {
    let tile_dir = std::env::var("VECTOR_TILE_DIR").unwrap_or_else(|_| "./tiles".to_string());
    let glyph_dir = std::env::var("VECTOR_GLYPH_DIR").unwrap_or_else(|_| "./glyphs".to_string());
    let state = AppState {
        tile_dir: PathBuf::from(tile_dir),
        glyph_dir: PathBuf::from(glyph_dir),
    };
    let app = app(state);
    let addr = SocketAddr::from(([127, 0, 0, 1], 3000));
    println!("vector-tile-server listening on http://{addr}");
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    fn test_state() -> AppState {
        AppState {
            tile_dir: PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/tiles")),
            glyph_dir: PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/glyphs")),
        }
    }

    #[tokio::test]
    async fn glyph_route_serves_pbf() {
        // Commit a tiny valid placeholder glyph PBF so the self-hosted
        // /glyphs endpoint is exercised without a full font bake.
        let dir = PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/glyphs"));
        let stack = dir.join("Open Sans Regular");
        std::fs::create_dir_all(&stack).ok();
        let pbf = stack.join("32-126.pbf");
        if !pbf.exists() {
            std::fs::write(&pbf, b"\x1a\x00").ok(); // minimal protobuf placeholder
        }
        let resp = app(test_state())
            .oneshot(
                Request::builder()
                    .uri("/glyphs/Open%20Sans%20Regular/32-126.pbf")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK, "glyph must serve");
        let ct = resp
            .headers()
            .get(header::CONTENT_TYPE)
            .unwrap()
            .to_str()
            .unwrap()
            .to_string();
        assert_eq!(ct, "application/x-protobuf");
    }

    #[tokio::test]
    async fn glyph_route_missing_returns_404() {
        let resp = app(test_state())
            .oneshot(
                Request::builder()
                    .uri("/glyphs/No%20Such%20Font/0-255.pbf")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn health_handler_returns_ok() {
        assert_eq!(health().await, "ok");
    }

    #[tokio::test]
    async fn healthz_route_returns_ok() {
        let resp = app(test_state())
            .oneshot(Request::builder().uri("/healthz").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let body = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        assert_eq!(&body[..], b"ok");
    }

    #[tokio::test]
    async fn tile_route_serves_mvt_bytes() {
        let resp = app(test_state())
            .oneshot(
                Request::builder()
                    .uri("/tiles/12/2200/1343.mvt")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let ct = resp
            .headers()
            .get(header::CONTENT_TYPE)
            .unwrap()
            .to_str()
            .unwrap()
            .to_string();
        assert_eq!(ct, "application/x-protobuf");
        let body = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        assert!(!body.is_empty(), "tile body must be non-empty");
        // MVT 2.1 spec: `repeated Layer layers = 3;` => field 3, wire type 2 => 0x1A.
        assert_eq!(body[0], 0x1A, "expected MVT layers (field 3) tag 0x1A as first byte");
    }

    #[tokio::test]
    async fn missing_tile_returns_404() {
        let resp = app(test_state())
            .oneshot(
                Request::builder()
                    .uri("/tiles/12/9999/9999.mvt")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn tile_route_serves_all_fixture_tiles() {
        // Prove the server correctly serves EVERY real committed fixture tile
        // through the M1 serve path (GET /tiles/{z}/{x}/{y}.mvt).
        let root = PathBuf::from(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/fixtures/tiles"
        ));

        // Walk the directory tree and collect every *.mvt file.
        fn walk(dir: &std::path::Path, out: &mut Vec<PathBuf>) {
            if let Ok(entries) = std::fs::read_dir(dir) {
                for entry in entries.flatten() {
                    let path = entry.path();
                    if path.is_dir() {
                        walk(&path, out);
                    } else if path.extension().and_then(|e| e.to_str()) == Some("mvt") {
                        out.push(path);
                    }
                }
            }
        }
        let mut mvt_files: Vec<PathBuf> = Vec::new();
        walk(&root, &mut mvt_files);
        assert!(
            !mvt_files.is_empty(),
            "expected committed .mvt fixtures under tests/fixtures/tiles"
        );

        for path in mvt_files {
            // Layout: {root}/{z}/{x}/{y}.mvt
            let rel = path
                .strip_prefix(&root)
                .expect("fixture path is under root");
            let comps: Vec<_> = rel.components().collect();
            assert_eq!(
                comps.len(),
                3,
                "unexpected fixture layout (expected z/x/y.mvt): {:?}",
                path
            );
            let z = comps[0]
                .as_os_str()
                .to_str()
                .unwrap()
                .parse::<u32>()
                .unwrap_or_else(|_| panic!("bad z component in {:?}", path));
            let x = comps[1]
                .as_os_str()
                .to_str()
                .unwrap()
                .parse::<u32>()
                .unwrap_or_else(|_| panic!("bad x component in {:?}", path));
            let y = comps[2]
                .as_os_str()
                .to_str()
                .unwrap()
                .trim_end_matches(".mvt")
                .parse::<u32>()
                .unwrap_or_else(|_| panic!("bad y component in {:?}", path));

            let uri = format!("/tiles/{z}/{x}/{y}.mvt");
            let resp = app(test_state())
                .oneshot(Request::builder().uri(uri.clone()).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert_eq!(resp.status(), StatusCode::OK, "uri={uri}");
            let ct = resp
                .headers()
                .get(header::CONTENT_TYPE)
                .unwrap()
                .to_str()
                .unwrap()
                .to_string();
            assert_eq!(ct, "application/x-protobuf", "content-type uri={uri}");
            let body = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
            assert!(!body.is_empty(), "non-empty body uri={uri}");
            // MVT 2.1 spec: `repeated Layer layers = 3;` => field 3, wire type 2 => 0x1A.
            assert_eq!(body[0], 0x1A, "expected MVT layers (field 3) tag 0x1A as first byte uri={uri}");
        }
    }

    #[tokio::test]
    async fn viewer_route_serves_html() {
        let resp = app(test_state())
            .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let body = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        assert!(body.windows(8).any(|w| w == b"maplibre"), "viewer must load maplibre");
    }

    // ---- tile epoch (issue 08 cache invalidation) -----------------------

    #[test]
    fn parse_epoch_reads_the_field() {
        assert_eq!(parse_epoch(r#"{"epoch": 7, "reason": "promote:3"}"#), 7);
        assert_eq!(parse_epoch(r#"{"reason":"x","epoch":42}"#), 42);
    }

    #[test]
    fn parse_epoch_is_total_on_garbage() {
        assert_eq!(parse_epoch(""), 0);
        assert_eq!(parse_epoch("not json at all"), 0);
        assert_eq!(parse_epoch(r#"{"epoch": "seven"}"#), 0);
        assert_eq!(parse_epoch(r#"{"epochs": 9}"#), 0);
    }

    #[tokio::test]
    async fn version_route_reports_an_epoch_and_is_never_cached() {
        let resp = app(test_state())
            .oneshot(
                Request::builder()
                    .uri("/tiles/version")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        // no-store is the whole point: this response is what tells a client its
        // cached tiles are stale.
        assert_eq!(
            resp.headers().get(header::CACHE_CONTROL).unwrap().to_str().unwrap(),
            "no-store"
        );
        let body = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert!(text.contains("\"epoch\""), "body was {text}");
    }

    #[tokio::test]
    async fn version_route_does_not_shadow_the_tile_route() {
        let resp = app(test_state())
            .oneshot(
                Request::builder()
                    .uri("/tiles/12/2623/1738.mvt")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        // Either the fixture tile is served or it is absent — but it must not be
        // routed to the version handler.
        let ct = resp.headers().get(header::CONTENT_TYPE).map(|v| v.to_str().unwrap().to_string());
        assert_ne!(ct.as_deref(), Some("application/json"));
    }

    #[tokio::test]
    async fn tiles_are_cacheable() {
        let dir = PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/tiles/1/1"));
        std::fs::create_dir_all(&dir).ok();
        let tile = dir.join("1.mvt");
        if !tile.exists() {
            std::fs::write(&tile, b"\x1a\x00").ok();
        }
        let resp = app(test_state())
            .oneshot(
                Request::builder()
                    .uri("/tiles/1/1/1.mvt")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let cc = resp.headers().get(header::CACHE_CONTROL).unwrap().to_str().unwrap();
        assert!(cc.contains("max-age"), "tiles should cache hard; got {cc}");
    }
}
