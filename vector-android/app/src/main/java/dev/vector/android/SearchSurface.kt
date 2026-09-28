package dev.vector.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.vector.android.design.VectorEmptyState
import dev.vector.android.design.VectorSearchButton
import dev.vector.android.design.VectorSearchField
import dev.vector.android.design.VectorSkeleton
import dev.vector.android.design.VectorTheme

/**
 * The search entry, resting and active.
 *
 * Lifted out of `NavUi.kt` unchanged in behaviour and moved into the sheet:
 * the field belongs with the results it filters, and while it floated over the
 * map as a separate pill the two were on opposite sides of a 400 dp gap. The
 * focus dance is the original's and is load-bearing — `requestFocus` throws if
 * the node is not attached yet, and a race there must not crash the app on a
 * search tap.
 */
@Composable
internal fun SearchBarSurface(
    ui: UiState,
    onOpen: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Focus — and therefore the keyboard — only when search is opened EMPTY.
    //
    // Tapping the search row means "I am going to type", so the keyboard should
    // be waiting. Tapping a category chip means "show me the fuel stations",
    // and it now opens the same search surface with the query pre-filled; if
    // that also raised the keyboard it would cover the results the tap was for.
    // The query being non-blank at the moment search opens is exactly the
    // signal that the user did not ask to type.
    LaunchedEffect(ui.searching) {
        if (ui.searching && ui.query.isBlank()) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else if (!ui.searching) {
            keyboard?.hide()
        }
    }

    if (!ui.searching) {
        VectorSearchButton(
            // Conversational rather than a label on a box. "Where to?" is a
            // form field's placeholder; this is the sentence the product would
            // say if it spoke, which is what the brief means by search feeling
            // like discovery rather than a toolbar.
            label = "Where are we going?",
            onClick = onOpen,
            icon = VectorIcons.glyphIcon(VectorIcons.Glyph.SEARCH),
            filled = true,
            testTag = "search:rest",
        )
    } else {
        VectorSearchField(
            value = ui.query,
            onValueChange = onQueryChange,
            placeholder = "Search Doha",
            onSearch = onSearch,
            onClose = onClose,
            onClear = { onQueryChange("") },
            loading = ui.searchInFlight,
            closeIcon = VectorIcons.glyphIcon(VectorIcons.Glyph.BACK),
            clearIcon = VectorIcons.glyphIcon(VectorIcons.Glyph.CLOSE),
            focusRequester = focus,
            filled = true,
            testTag = "search:field",
        )
    }
}

/**
 * What the sheet shows once something has been typed.
 *
 * Four states, and each one is designed rather than defaulted:
 *
 *  * **in flight with nothing yet** — three skeleton rows in the shape of the
 *    rows that are coming, so the list does not jump when they land. The old
 *    build drew an empty panel here, which is indistinguishable from "no
 *    results" and is the single most common way a search feels broken;
 *  * **results** — [PlaceRowShell], the same row the recents use;
 *  * **searched and found nothing** — a real empty state that says what was
 *    searched for and offers the way out;
 *  * **typed but not yet searched** — nothing, because the request is about to
 *    answer and a flash of "no results" before it does is a lie.
 */
@Composable
internal fun SearchResultsBody(ui: UiState, onPick: (VectorApi.Place) -> Unit) {
    when {
        ui.results.isNotEmpty() -> {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(ui.results) { p ->
                    val away = ui.myLocation?.let {
                        dev.vector.geo.RouteGeometry.haversineM(
                            it.lng, it.lat, p.position.lng, p.position.lat,
                        )
                    }
                    val family = PlaceFamily.of(p)
                    val name = titleCase(p.name)
                    PlaceRowShell(
                        family = family,
                        title = name,
                        // The category the place actually has, and the family's
                        // own label when OSM gave none — never `kind`, which is
                        // a basemap layer name and read "poi" for every POI in
                        // the country.
                        subtitle = p.categoryLabel ?: family.label,
                        trailing = away?.let { shortDistance(it, ui.units) },
                        contentDescription = buildString {
                            append("Navigate to $name")
                            p.categoryLabel?.let { append(", $it") }
                            away?.let { append(", ${shortDistance(it, ui.units)} away") }
                        },
                        onClick = { onPick(p) },
                        titleOverride = {
                            androidx.compose.material3.Text(
                                searchHighlight(name, ui.query),
                                style = VectorTheme.typography.bodyStrong,
                                color = VectorTheme.colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        ui.searchInFlight -> SearchSkeleton()

        ui.searched && !ui.searchInFlight -> VectorEmptyState(
            title = "No match for “${ui.query.trim()}”",
            body = "Try a shorter name, or pick a category. Vector searches the " +
                "places in the offline map, so a brand-new shop may not be in it yet.",
            icon = VectorIcons.glyphIcon(VectorIcons.Glyph.SEARCH),
            testTag = "empty:no-results",
        )

        else -> Spacer(Modifier.height(VectorTokens.Space.s8))
    }
}

/**
 * Three rows of the shape that is loading.
 *
 * Deliberately three: enough to read as a list, few enough that the real
 * results replacing two of them is not a visible collapse. The tile, the two
 * text lines and the trailing distance are all present, so the skeleton is the
 * row's silhouette rather than a generic grey bar.
 */
@Composable
private fun SearchSkeleton() {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
    ) {
        repeat(3) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = VectorTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VectorSkeleton(
                    Modifier.size(VectorTokens.Size.control),
                    shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r12),
                )
                Spacer(Modifier.width(VectorTokens.Space.s12))
                Column(Modifier.weight(1f)) {
                    VectorSkeleton(
                        Modifier.fillMaxWidth(0.55f).height(14.dp),
                        shape = VectorTheme.shapes.pill,
                    )
                    Spacer(Modifier.height(VectorTokens.Space.s6))
                    VectorSkeleton(
                        Modifier.fillMaxWidth(0.3f).height(11.dp),
                        shape = VectorTheme.shapes.pill,
                    )
                }
                Spacer(Modifier.width(VectorTokens.Space.s8))
                VectorSkeleton(
                    Modifier.width(38.dp).height(11.dp),
                    shape = VectorTheme.shapes.pill,
                )
            }
        }
    }
}

/**
 * Dim the part of a result the driver typed; keep the rest bright.
 *
 * The same rule the old results list used, kept because it is right and moved
 * here because two surfaces now need it. You already know what you typed, so
 * highlighting it tells you nothing — what you are scanning for is the
 * disambiguator, which is why the *match* is the quiet part and the remainder
 * is the bold one.
 *
 * Case-insensitive, first occurrence only. Falls back to the plain name when
 * there is no match, which is the case for a result matched on its
 * transliteration rather than on its literal text.
 */
@Composable
internal fun searchHighlight(
    name: String,
    query: String,
): androidx.compose.ui.text.AnnotatedString {
    val c = VectorTheme.colors
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else name.indexOf(q, ignoreCase = true)
    return androidx.compose.ui.text.buildAnnotatedString {
        if (at < 0) {
            append(name)
            return@buildAnnotatedString
        }
        append(name.substring(0, at))
        withStyle(
            androidx.compose.ui.text.SpanStyle(
                color = c.inkMuted,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            ),
        ) {
            append(name.substring(at, at + q.length))
        }
        append(name.substring(at + q.length))
    }
}
