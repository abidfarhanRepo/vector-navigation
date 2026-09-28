package dev.vector.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vector.android.design.VectorBottomSheet
import dev.vector.android.design.VectorDialog
import dev.vector.android.design.VectorEmptyState
import dev.vector.android.design.VectorIcon
import dev.vector.android.design.VectorSearchButton
import dev.vector.android.design.VectorSectionHeader
import dev.vector.android.design.VectorSheetAnchors
import dev.vector.android.design.VectorTheme
import dev.vector.android.design.vectorPressable

/**
 * The discovery surface.
 *
 * ## Why the app grew a sheet
 *
 * Before this, EXPLORE was a map, a search pill and a column of round buttons —
 * and *nothing else*. Everything a driver might want before they have typed
 * anything (the categories, the two saved places, the places they went to last
 * week) lived **behind** the search field: you had to tap into search and see a
 * keyboard before the app would show you a single destination. So the resting
 * state of a navigation app offered exactly one affordance, and the screenshot
 * of it is an empty map.
 *
 * The redesign brief is explicit that the bottom sheet is "the core discovery
 * surface", and that is also the right answer for this product specifically: a
 * driver's real first question is not "what is the name of the place" but
 * "where do I usually go" — Home, Work, the mall they drove to on Tuesday, the
 * nearest petrol station. Those are four taps that used to be five taps and a
 * keyboard.
 *
 * ## Why it is not `dismissible` and has no scrim
 *
 * [VectorBottomSheet] defaults to a modal sheet — scrim, tap-to-dismiss, back
 * to close. This one is *furniture*: it is the resting composition of the
 * screen, it can be dragged down to a peek but never away, and there is nothing
 * behind it to dim because the thing behind it is the map, which is the other
 * half of the same screen. A scrim here would darken the cartography the sheet
 * exists to sit beside.
 *
 * ## The anchors
 *
 * Three. The **peek** is the search row and nothing else — pushed fully down
 * the sheet gets out of the way so the map underneath can be read and panned,
 * which is the whole point of a map-first app and was the thing this sheet took
 * away when it first shipped. The **half** adds the category rail and the first
 * section. The **full** is the scrollable list.
 *
 * The peek is given as a height in dp rather than as a fraction, because it is
 * defined by the content it must show; see the anchors below.
 *
 * While searching the sheet is pinned to the expanded anchor: the keyboard is
 * up, the results need the height, and a draggable surface under a keyboard is
 * a gesture conflict nobody wins.
 */
@Composable
fun ExploreSheet(
    ui: UiState,
    onOpenSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onPick: (VectorApi.Place) -> Unit,
    onPickPlace: (Places.Saved) -> Unit,
    onPickRecent: (Recents.Entry) -> Unit,
    onClearRecents: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * The arrival card or the status line, supplied by the chrome.
     *
     * A slot rather than two more parameters: both of those composables live in
     * `NavUi.kt`, are private to it, and know about `UiState.arrival`,
     * `Units` and the dismiss callback. Passing the rendered content in keeps
     * the sheet free of that and keeps the chrome the single place that decides
     * which of the two is showing.
     */
    arrivalContent: @Composable () -> Unit = {},
) {
    val searching = ui.searching
    // The peek is a HEIGHT, not a percentage.
    //
    // Asked for directly: pushed all the way down the sheet should show "just
    // the first two bubbles with the where we are going and settings icon
    // alone" — the search row, and nothing under it. That is a fixed stack of
    // known parts, so it is expressed as their heights:
    //
    //   the grab handle              48 dp  (VectorTokens.Size.sheetHandleTarget)
    //   the search row               52 dp  (VectorSearchButton's minimum)
    //   the gap under the row        12 dp
    //   the navigation-bar inset    device
    //
    // A fraction cannot express that, and trying to was the bug: 0.60 tuned on
    // a 571 dp emulator became 333 dp of sheet on an 832 dp S24 and clipped the
    // first saved-place card in half. See [VectorSheetAnchors.peekHeight].
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val anchors = remember(navBar) {
        VectorSheetAnchors(
            peekHeight = VectorTokens.Size.sheetHandleTarget + 52.dp +
                VectorTokens.Space.s12 + navBar,
            // Half: the search row, the category rail and the first section
            // header — enough to choose a category without expanding.
            medium = 0.52f,
            expanded = 0.10f,
        )
    }
    VectorBottomSheet(
        visible = true,
        onDismiss = {},
        anchors = anchors,
        // Searching takes the whole surface; at rest the sheet opens at the
        // half stop, from which it can be pushed down to the peek or pulled up.
        initialAnchor = if (searching) anchors.expanded else anchors.medium,
        dismissible = false,
        showScrim = false,
        testTag = "sheet:explore",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = VectorTokens.Space.s16),
        ) {
            ExploreSearchHeader(ui, onOpenSearch, onQueryChange, onSearch, onCloseSearch, onOpenSettings)
            Spacer(Modifier.height(VectorTokens.Space.s12))
            if (searching && ui.query.isNotBlank()) {
                SearchResultsBody(ui, onPick)
            } else {
                DiscoveryBody(
                    ui, onOpenSearch, onQueryChange, onPickPlace, onPickRecent,
                    onClearRecents, onArrivalContent = arrivalContent,
                )
            }
        }
    }
}

/**
 * The search entry, and the one control that is not about searching.
 *
 * Settings moves **into** the sheet from the floating column on the right. That
 * column is the driver's control cluster — zoom, compass, recenter — and every
 * one of those acts on the map. "Settings" acted on the app, so it was the one
 * button in the stack that did something of a different kind, and it was also
 * the one a driver would never press while moving. In the sheet it sits beside
 * the search entry, which is where a preference lives in every product this
 * design is measured against.
 */
@Composable
private fun ExploreSearchHeader(
    ui: UiState,
    onOpenSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
    ) {
        Box(Modifier.weight(1f)) {
            SearchBarSurface(ui, onOpenSearch, onQueryChange, onSearch, onCloseSearch)
        }
        if (!ui.searching) {
            SheetIconButton(
                icon = VectorIcons.glyphIcon(VectorIcons.Glyph.SETTINGS),
                label = "Settings",
                onClick = onOpenSettings,
            )
        }
    }
}

/**
 * A round control sized for the sheet rather than for the map.
 *
 * The map's controls are 48 dp discs with a floating shadow because they sit on
 * arbitrary cartography and have to survive it. Inside the sheet there is a
 * known surface underneath, so the same control is a quiet tinted disc with no
 * shadow — a floating shadow on a control that is not floating is the detail
 * that makes a design system look applied rather than designed.
 */
@Composable
private fun SheetIconButton(icon: VectorIcon, label: String, onClick: () -> Unit) {
    val c = VectorTheme.colors
    Box(
        Modifier
            .size(VectorTokens.Size.control)
            .clip(VectorTheme.shapes.pill)
            .background(c.surfaceSunken)
            .vectorPressable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        icon.render(VectorTokens.Size.controlGlyph, c.inkSecondary)
    }
}

/**
 * Everything the driver can reach without typing.
 *
 * Order is by how often it is the answer: the categories (a decision made in
 * one tap and no keyboard), then the two places they told us about, then where
 * they actually went. Recents last because it is the longest and the sheet
 * scrolls — a long list above a short one buries the short one.
 */
@Composable
private fun DiscoveryBody(
    ui: UiState,
    onOpenSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onPickPlace: (Places.Saved) -> Unit,
    onPickRecent: (Recents.Entry) -> Unit,
    onClearRecents: () -> Unit,
    onArrivalContent: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(VectorTokens.Space.s16),
    ) {
        // What just happened, above what to do next.
        //
        // The arrival summary and the status line used to be drawn in the
        // chrome's bottom group, which the sheet now covers. They are the
        // journey's last word, so they belong at the top of the surface a
        // driver reads after a journey ends — and `arrive()` clears the status
        // when it sets the summary, so these two can never both be present.
        onArrivalContent()

        CategoryRail(onOpenSearch, onQueryChange)

        if (ui.places.isNotEmpty()) {
            Column {
                // "Your Vectors", not "Your corners".
                //
                // The first draft borrowed the reference product's word for a
                // saved place, which is both its brand vocabulary and not this
                // product's — the redesign brief is explicit that Corner's copy
                // is off limits and that Vector keeps its own branding. A
                // vector is a direction and a destination, which is exactly
                // what a saved slot is, and it is the app's own word.
                VectorSectionHeader(title = "Your Vectors")
                Spacer(Modifier.height(VectorTokens.Space.s8))
                Row(horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8)) {
                    ui.places.forEach { saved ->
                        Box(Modifier.weight(1f)) { CornerTile(saved, ui, onPickPlace) }
                    }
                    // One saved place must not stretch to the full width: a
                    // lone tile at 100% reads as a banner rather than as the
                    // first of a pair, and the second slot is the affordance
                    // that says another one can be set.
                    if (ui.places.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (ui.recents.isNotEmpty()) {
            Column {
                var askingClear by remember { mutableStateOf(false) }
                VectorSectionHeader(
                    title = "Recent",
                    actionLabel = "Clear",
                    onAction = { askingClear = true },
                    testTag = "recents:clear",
                )
                if (askingClear) {
                    // The same confirmation the settings sheet's "Clear"
                    // controls got. This one deleted every recent destination
                    // on a single tap, from a control sitting directly above
                    // the list it would empty — and it is reachable in one tap
                    // from the app's resting state, which makes it the easiest
                    // destructive action in the product to hit by accident.
                    VectorDialog(
                        title = "Clear recent destinations?",
                        body = "This removes them from this phone. It cannot be undone.",
                        confirmLabel = "Clear",
                        onConfirm = { askingClear = false; onClearRecents() },
                        dismissLabel = "Keep",
                        onDismiss = { askingClear = false },
                        destructive = true,
                        testTag = "dialog:clear-recents",
                    )
                }
                Spacer(Modifier.height(VectorTokens.Space.s4))
                ui.recents.forEach { entry ->
                    RecentRow(entry, ui, onPickRecent)
                }
            }
        }

        if (ui.places.isEmpty() && ui.recents.isEmpty()) {
            VectorEmptyState(
                title = "Nowhere yet",
                body = "Search for somewhere, or pick a category above. " +
                    "The places you drive to will collect here.",
                icon = VectorIcons.glyphIcon(VectorIcons.Extra.PIN),
                testTag = "empty:discovery",
            )
        }
        // The end of the scrolling list sits the same distance above the
        // navigation bar as every other bottom-anchored surface. See
        // [VectorTokens.Size.bottomGap].
        Spacer(Modifier.height(VectorTokens.Size.bottomGap))
    }
}

/**
 * The category rail.
 *
 * Each chip carries its family's own accent rather than the one selected
 * colour every chip used to share, which is what makes the row scannable
 * without reading: fuel is coral, food is butter, parking is blue, health is
 * green. See [PlaceFamily] for why the colour is a role and not a hex.
 *
 * `contentPadding` restores the sheet's own 16 dp gutter at both ends so the
 * first chip lines up with the search field above it and the last one is cut by
 * the screen edge rather than by a margin — the cue that the row scrolls.
 */
@Composable
private fun CategoryRail(onOpenSearch: () -> Unit, onQueryChange: (String) -> Unit) {
    val c = VectorTheme.colors
    Box(Modifier.fillMaxWidth()) {
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s8),
            contentPadding = PaddingValues(end = VectorTokens.Space.s24),
        ) {
            items(SEARCH_CATEGORIES) { (label, term) ->
                val family = PlaceFamily.of(term)
                CategoryChip(label, family) {
                    // BOTH, and the order matters.
                    //
                    // The chip used to call `onQueryChange(term)` alone. That
                    // really did run the search — `MainActivity.onQueryChanged`
                    // debounces and fetches — and the results landed in
                    // `ui.results` where **nothing rendered them**, because the
                    // sheet only composes the results body while
                    // `ui.searching` is true and tapping a chip never set it.
                    // So the chips looked completely dead while quietly doing
                    // the work: reported as "Fuel food parking buttons in the
                    // swipeable card doesn't work".
                    //
                    // `onOpenSearch` first so the results surface exists by the
                    // time the query lands on it.
                    onOpenSearch()
                    onQueryChange(term)
                }
            }
        }
        // A fade, not a hard cut.
        //
        // The rail is meant to run off the edge so the row reads as scrollable,
        // but a chip sliced vertically through its own glyph reads as a broken
        // layout instead — which is what the first screenshot of this showed,
        // and what the old build did too. Eight dp of the sheet's own surface
        // fading in over the last chip keeps the "there is more" cue and loses
        // the guillotine.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(VectorTokens.Space.s24)
                .height(VectorTokens.Size.control)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.Transparent, c.surfaceFloating),
                    ),
                ),
        )
    }
}

/**
 * One category, as a tactile two-tone chip.
 *
 * Not [VectorChip]: that component is a *selectable* chip and its whole visual
 * contract is selected-versus-not. A category here is not a selection, it is a
 * shortcut that runs a search and closes — a chip drawn in the unselected style
 * would be quiet, and a chip drawn in the selected style would claim a state it
 * does not have. So this one is always "on" in its family's colour, which is
 * the expressive treatment the brief asks for and is honest about the
 * interaction.
 */
@Composable
private fun CategoryChip(label: String, family: PlaceFamily, onClick: () -> Unit) {
    val c = VectorTheme.colors
    val shape = VectorTheme.shapes.pill
    Row(
        Modifier
            .heightIn(min = VectorTokens.Size.control)
            .clip(shape)
            .background(family.container())
            .vectorPressable(onClickLabel = "Search for $label", onClick = onClick)
            .padding(horizontal = VectorTokens.Space.s16, vertical = VectorTokens.Space.s8)
            .semantics(mergeDescendants = true) { contentDescription = "Search for $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VectorTokens.Space.s6),
    ) {
        family.icon.render(18.dp, family.onContainer())
        androidx.compose.material3.Text(
            label,
            style = VectorTheme.typography.chip,
            color = family.onContainer(),
            maxLines = 1,
        )
    }
}

/**
 * A saved place, as a tile rather than a row.
 *
 * "Home" and "Work" are the two destinations a driver re-uses most, and a
 * full-width list row spends the whole width of the screen on a word. Two
 * tiles side by side put both of them in the space one row used, which is the
 * editorial density the brief is asking for and also just a better use of a
 * sheet that has to share the screen with a map.
 */
@Composable
private fun CornerTile(saved: Places.Saved, ui: UiState, onPick: (Places.Saved) -> Unit) {
    val c = VectorTheme.colors
    // The SLOT's own glyph, not the place's family.
    //
    // A saved slot is answering "where is home", and a house is what a person
    // looks for — drawing the destination's category instead meant Home and
    // Work both showed a shopping bag when both happened to point at a mall,
    // which is true and useless. The family still supplies the colour, so the
    // tile is not the only thing in the sheet with no tint of its own.
    val family = PlaceFamily.of(saved.name)
    val slotIcon = VectorIcons.glyphIcon(
        if (saved.slot == Places.Slot.HOME) VectorIcons.Glyph.HOME
        else VectorIcons.Glyph.WORK,
    )
    val shape = VectorTheme.shapes.continuous(VectorTokens.Radius.r20)
    val away = ui.myLocation?.let {
        dev.vector.geo.RouteGeometry.haversineM(it.lng, it.lat, saved.lng, saved.lat)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceSunken)
            .vectorPressable(
                pressScale = 0.97f,
                onClickLabel = "Navigate to ${saved.slot.label}",
                onClick = { onPick(saved) },
            )
            .padding(VectorTokens.Space.s12)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Navigate to ${saved.slot.label}, ${titleCase(saved.name)}")
                    away?.let { append(", ${shortDistance(it, ui.units)} away") }
                }
            },
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(VectorTheme.shapes.pill)
                .background(family.container()),
            contentAlignment = Alignment.Center,
        ) {
            slotIcon.render(18.dp, family.onContainer())
        }
        Spacer(Modifier.height(VectorTokens.Space.s8))
        androidx.compose.material3.Text(
            saved.slot.label,
            style = VectorTheme.typography.cardTitle,
            color = c.ink,
            maxLines = 1,
        )
        androidx.compose.material3.Text(
            titleCase(saved.name),
            style = VectorTheme.typography.metadata,
            color = c.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        away?.let {
            Spacer(Modifier.height(VectorTokens.Space.s2))
            androidx.compose.material3.Text(
                shortDistance(it, ui.units),
                style = VectorTheme.typography.caption,
                color = family.onContainer(),
                maxLines = 1,
            )
        }
    }
}

/**
 * A place the driver has already been.
 *
 * The clock glyph in a flat blue disc is gone: a recent is still a *place*, so
 * it carries its family's symbol and colour exactly as a search result does,
 * and the fact that it is recent is carried by the section it is in. Two
 * different visual languages for the same noun was the thing that made the old
 * list read as two unrelated lists.
 */
@Composable
private fun RecentRow(entry: Recents.Entry, ui: UiState, onPick: (Recents.Entry) -> Unit) {
    val c = VectorTheme.colors
    val family = PlaceFamily.of(entry.name)
    val away = ui.myLocation?.let {
        dev.vector.geo.RouteGeometry.haversineM(it.lng, it.lat, entry.lng, entry.lat)
    }
    PlaceRowShell(
        family = family,
        title = titleCase(entry.name),
        // The family, not the word "Recent".
        //
        // Every row under a heading that says "Recent" also said "Recent",
        // which is a subtitle that repeats its own section header and tells the
        // driver nothing — and it made the rows read differently from the
        // identical rows in the results list one tap away. The family label is
        // the same thing a search result shows, so the two lists are one list.
        subtitle = family.label,
        trailing = away?.let { shortDistance(it, ui.units) },
        contentDescription = buildString {
            append("Navigate to ${titleCase(entry.name)}")
            away?.let { append(", ${shortDistance(it, ui.units)} away") }
        },
        onClick = { onPick(entry) },
    )
}

/**
 * The one row shape every place in this app is drawn with.
 *
 * Search results, recents and saved places were three different rows with three
 * different leading glyphs, three paddings and two different distance
 * treatments. They are all "a place you can navigate to", so they are one
 * component — which is what makes the list read as one system, and is the
 * reason the brief's "no old layout survives unchanged" is satisfied by
 * building this rather than by restyling three things.
 *
 * No divider. The rows are separated by their own height and the leading tile's
 * vertical rhythm; a hairline between every row is the stock list look the
 * brief rules out, and with a coloured tile on the left the eye already has an
 * edge to scan.
 */
@Composable
internal fun PlaceRowShell(
    family: PlaceFamily,
    title: String,
    subtitle: String?,
    trailing: String?,
    contentDescription: String,
    onClick: () -> Unit,
    titleOverride: (@Composable () -> Unit)? = null,
) {
    val c = VectorTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = VectorTokens.Size.row)
            .clip(VectorTheme.shapes.continuous(VectorTokens.Radius.r16))
            .vectorPressable(pressScale = 0.985f, onClickLabel = null, onClick = onClick)
            .padding(vertical = VectorTokens.Space.s8)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(VectorTokens.Size.control)
                .clip(VectorTheme.shapes.continuous(VectorTokens.Radius.r12))
                .background(family.container()),
            contentAlignment = Alignment.Center,
        ) {
            family.icon.render(20.dp, family.onContainer())
        }
        Spacer(Modifier.width(VectorTokens.Space.s12))
        Column(Modifier.weight(1f)) {
            if (titleOverride != null) titleOverride() else {
                androidx.compose.material3.Text(
                    title,
                    style = VectorTheme.typography.bodyStrong,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            subtitle?.let {
                Spacer(Modifier.height(VectorTokens.Space.s2))
                androidx.compose.material3.Text(
                    it,
                    style = VectorTheme.typography.metadata,
                    color = c.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(VectorTokens.Space.s8))
            androidx.compose.material3.Text(
                it,
                style = VectorTheme.typography.metadata,
                color = c.inkSecondary,
                maxLines = 1,
            )
        }
    }
}
