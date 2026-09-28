package dev.vector.android

import org.junit.Test
import java.io.File

/**
 * Writes the app's REAL style document to disk so it can be rendered by a
 * browser and looked at — the cartography bench.
 *
 * ## Why this exists rather than run.sh's extraction
 *
 * `scripts/native-render/run.sh` re-derives the document by slicing
 * `VectorStyle.kt` as text and substituting `$glyphs`/`$tiles`. That was true
 * when the style was one literal; it now interpolates `$carriageway`,
 * `$buildingsFabric`, `$buildings3d` and `$light` as well, so the text slice is
 * not valid JSON and the harness cannot render what the app actually installs.
 * Calling [VectorStyle.json] is the only way to be sure the thing being looked
 * at is the thing being shipped.
 *
 * ## Inert unless asked
 *
 * Skipped unless `VECTOR_STYLE_DUMP` is set, because it writes files and test
 * runs must not. The bench is:
 *
 * ```text
 * VECTOR_STYLE_DUMP=<dir> ./gradlew :app:testDebugUnitTest --tests '*StyleDumpTest'
 * cd <dir> && python3 -m http.server 3000     # then open index.html?theme=light
 * ```
 */
class StyleDumpTest {

    @Test
    fun `dump both themes for the cartography bench`() {
        val dir = System.getenv("VECTOR_STYLE_DUMP") ?: return
        val out = File(dir)
        out.mkdirs()
        val base = System.getenv("VECTOR_STYLE_BASE") ?: "https://your-host.example.com"
        for ((name, theme) in listOf(
            "dark" to VectorStyle.MapTheme.DARK,
            "light" to VectorStyle.MapTheme.LIGHT,
        )) {
            // 3D on: the extruded layer is part of what is being judged, and
            // omitting it would hide half the cartography.
            val json = VectorStyle.json(
                apiBase = base,
                tileEpoch = 0L,
                minZoom = 6,
                maxZoom = 15,
                theme = theme,
                extruded = true,
            )
            File(out, "style-$name.json").writeText(json)

            // V8 acceptance: the SAME integrated style with the `lanes` layers
            // on, so the candidate release's optional source-layer can be
            // rendered and compared against the basemap-only dump above. This
            // is `lanes = true` explicitly rather than a build flag, because the
            // bench renders one document per file and needs both — it is the
            // real `VectorStyle.json` output, not the bench-only layer injection
            // the first V8 evidence used.
            val lanesJson = VectorStyle.json(
                apiBase = base,
                tileEpoch = 0L,
                minZoom = 6,
                maxZoom = 15,
                theme = theme,
                extruded = true,
                lanes = true,
            )
            File(out, "style-lanes-$name.json").writeText(lanesJson)
        }
    }
}
