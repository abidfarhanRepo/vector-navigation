package dev.vector.android.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.vector.android.VectorTokens

/**
 * Vector's component library.
 *
 * ## The rules this file enforces so screens do not have to
 *
 * 1. **Every interactive component is at least 48 × 48 dp**, including ones
 *    that *look* smaller. [VectorIconButton] at `Small` is a 36 dp circle inside
 *    a 48 dp hit area — the visual is small, the target is not. The brief
 *    requires this and the accessibility suite asserts it.
 * 2. **No component clips text.** Heights are `heightIn(min = …)` and text is
 *    allowed to grow, so a 2.0× font scale reflows rather than truncates.
 * 3. **Every component takes a `testTag`** and every icon-only component takes
 *    a `contentDescription`. There is no icon-only control in the product
 *    without a spoken label.
 * 4. **Press feedback is a spring**, not a ripple: a scale-down that returns
 *    with velocity. It is the cheapest way to make a control feel physical and
 *    it costs no time before the action fires.
 * 5. **Colour comes from [VectorTheme.colors] only.** A component never takes a
 *    raw hex; variants map to roles.
 *
 * ## Icons are injected, not imported
 *
 * A component takes `icon: VectorIcon?` — a key and a composable slot — rather
 * than a member of `VectorIcons.Glyph`. That keeps this package free of a
 * dependency on the icon set (so the component library can be screenshot-tested
 * without the app's icon file), lets a screen pass a bespoke glyph where one
 * genuinely helps scanability, and means the library has no opinion about how a
 * glyph is drawn. See [VectorIcon] for why the slot is a composable.
 */
/**
 * An icon, as a slot rather than as a picture.
 *
 * ## Why this is a composable and not a paint lambda
 *
 * A `DrawScope.(Color) -> Unit` looks simpler until you have to bridge to an
 * icon set that already exists: `VectorIcons` draws through a private
 * `DrawScope.drawGlyph` inside its own `Canvas`, and a paint lambda cannot reach
 * it. The choice was then to re-expose that private drawing, to duplicate the
 * paths, or to make the slot a composable.
 *
 * A composable slot is what Compose actually wants here: the caller supplies
 * whatever renders the glyph, tinting arrives as a parameter, and an icon can be
 * anything — a `Canvas`, a `Text` glyph, an `ImageVector` — without this package
 * knowing. [VectorIcons] is then consumed by a three-line adapter at the call
 * site instead of by a change to the icon file.
 *
 * [key] is kept for stable test tags and screenshot names: an icon that renders
 * correctly but is the wrong icon is a bug this catches.
 */
@Immutable
class VectorIcon(
    val key: String,
    val render: @Composable (size: Dp, tint: Color) -> Unit,
)

/**
 * An icon that is pure geometry, drawn into a `Canvas` of the requested size.
 *
 * For glyphs this package owns or for tests that need a deterministic picture.
 * The real icon set goes through `VectorIcon(key) { size, tint -> … }` directly.
 */
fun drawIcon(key: String, paint: DrawScope.(Color) -> Unit): VectorIcon =
    VectorIcon(key) { size, tint ->
        androidx.compose.foundation.Canvas(Modifier.size(size)) { paint(tint) }
    }

// ---------------------------------------------------------------------------
// Interaction
// ---------------------------------------------------------------------------

/**
 * Vector's press behaviour: a spring-scaled acknowledgement with no ripple.
 *
 * `pressScale` is 0.94 for a control and 0.97 for a large surface — a card that
 * shrinks as much as a chip looks like it is being crushed.
 */
@Composable
internal fun Modifier.vectorPressable(
    enabled: Boolean = true,
    pressScale: Float = 0.94f,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = VectorTheme.motion
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = motion.springControl,
        label = "vectorPress",
    )
    return this
        .scale(scale)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

enum class VectorButtonVariant {
    /** The one primary action on a surface. Brand fill, white label. */
    Primary,
    /** A secondary action beside a primary one. Outlined, no fill. */
    Secondary,
    /** A quiet action on a tinted surface. Brand-tinted container. */
    Tonal,
    /** A text-only action: "See all", "Go back". */
    Ghost,
    /** Destructive: leaves the account or deletes data. Coral fill. */
    Destructive,
}

enum class VectorButtonSize { Large, Medium, Small }

/**
 * The button.
 *
 * Exactly one [VectorButtonVariant.Primary] per surface is the intent; the
 * screen-level review checks it. `loading` replaces the label with a progress
 * row **at the same size**, so a submitting button does not resize the layout
 * under the user's finger.
 */
@Composable
fun VectorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: VectorButtonVariant = VectorButtonVariant.Primary,
    size: VectorButtonSize = VectorButtonSize.Large,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: VectorIcon? = null,
    trailingIcon: VectorIcon? = null,
    fillWidth: Boolean = true,
    /**
     * Overrides the variant's content colour.
     *
     * For a Ghost button whose action is destructive: Ghost is text-only and
     * tinted `primaryText`, which is the brand blue, so a quiet "Clear" read as
     * a link to somewhere rather than as something that deletes. Passing
     * `dangerText` keeps the quiet container and restores the meaning.
     */
    contentTint: Color? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shapes = VectorTheme.shapes
    val (container, defaultContent) = buttonColors(variant, c, enabled)
    val content = contentTint?.takeIf { enabled } ?: defaultContent
    val padding = when (size) {
        VectorButtonSize.Large -> PaddingValues(horizontal = 24.dp, vertical = 16.dp)
        VectorButtonSize.Medium -> PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        VectorButtonSize.Small -> PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    }
    val minHeight = when (size) {
        VectorButtonSize.Large -> 52.dp
        VectorButtonSize.Medium -> 44.dp
        VectorButtonSize.Small -> 36.dp
    }
    val glyph = when (size) {
        VectorButtonSize.Large -> 20.dp
        VectorButtonSize.Medium -> 18.dp
        VectorButtonSize.Small -> 16.dp
    }
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = maxOf(minHeight, 48.dp))
            .clip(shapes.pill)
            .background(container)
            .then(
                if (variant == VectorButtonVariant.Secondary) {
                    // `controlBorder`, not `border`: an outlined button's outline is
                    // its only boundary. See the role.
                    Modifier.border(1.dp, c.controlBorder, shapes.pill)
                } else {
                    Modifier
                },
            )
            .vectorPressable(enabled = enabled && !loading, pressScale = 0.96f, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(padding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            VectorSpinner(size = glyph, color = content)
            Spacer(Modifier.width(10.dp))
        } else if (leadingIcon != null) {
            VectorIconView(leadingIcon, glyph, content)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = t.button,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(10.dp))
            VectorIconView(trailingIcon, glyph, content)
        }
    }
}

@Composable
private fun buttonColors(
    variant: VectorButtonVariant,
    c: VectorColors,
    enabled: Boolean,
): Pair<Color, Color> {
    val base = when (variant) {
        VectorButtonVariant.Primary -> c.primary to c.onPrimary
        VectorButtonVariant.Secondary -> Color.Transparent to c.ink
        VectorButtonVariant.Tonal -> c.primaryContainer to c.onPrimaryContainer
        VectorButtonVariant.Ghost -> Color.Transparent to c.primaryText
        VectorButtonVariant.Destructive -> c.danger to c.onAccent
    }
    return if (enabled) base else base.first.copy(alpha = 0.38f) to base.second.copy(alpha = 0.55f)
}

/**
 * A circular icon control.
 *
 * [VectorButtonSize.Small] draws a 36 dp circle inside a 48 dp hit area — the
 * control reads as compact on the map without becoming a 36 dp target.
 */
@Composable
fun VectorIconButton(
    icon: VectorIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: VectorButtonVariant = VectorButtonVariant.Secondary,
    size: VectorButtonSize = VectorButtonSize.Medium,
    enabled: Boolean = true,
    selected: Boolean = false,
    /**
     * Overrides the variant's content colour.
     *
     * For the handful of icon buttons that are *furniture* rather than actions
     * — the back arrow and the clear cross inside the search field — where the
     * Ghost variant's brand-blue glyph made two navigational affordances the
     * brightest things on the surface. See [VectorSearchField].
     */
    contentTint: Color? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val shapes = VectorTheme.shapes
    val visual = when (size) {
        VectorButtonSize.Large -> 52.dp
        VectorButtonSize.Medium -> 44.dp
        VectorButtonSize.Small -> 36.dp
    }
    val glyph = when (size) {
        VectorButtonSize.Large -> 24.dp
        VectorButtonSize.Medium -> 20.dp
        VectorButtonSize.Small -> 18.dp
    }
    val container = when {
        selected -> c.primary
        variant == VectorButtonVariant.Primary -> c.primary
        variant == VectorButtonVariant.Tonal -> c.primaryContainer
        variant == VectorButtonVariant.Ghost -> Color.Transparent
        else -> c.surfaceFloating
    }
    val content = contentTint ?: when {
        selected -> c.onPrimary
        variant == VectorButtonVariant.Primary -> c.onPrimary
        variant == VectorButtonVariant.Tonal -> c.onPrimaryContainer
        variant == VectorButtonVariant.Ghost -> c.primaryText
        else -> c.ink
    }
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            // `mergeDescendants`, so the 48 dp target and the visual disc
            // inside it are ONE node to accessibility and to tests.
            //
            // Without it the outer box carried the name and the inner box
            // carried the click, so the node you find by content description is
            // not the node that can be activated — a screen reader lands on a
            // control it cannot operate, and `NavUiTest` hit the same wall
            // trying to press the delete control on a drive row by its name.
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
                if (selected) stateDescription = "Selected"
                // The action, on the same node as the name.
                //
                // Merging combines *properties* — text, content description,
                // state — but Compose deliberately does not merge child
                // ACTIONS upward. So the node a screen reader (or a test)
                // finds by name had `Role.Button` and no `OnClick`: something
                // that says it is a button and cannot be pressed. The press
                // itself still lives on the visual disc below, because that is
                // what should scale under a finger; this makes the accessible
                // node able to fire the same callback.
                if (enabled) onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(visual)
                .clip(shapes.pill)
                .background(if (enabled) container else container.copy(alpha = 0.5f))
                .then(
                    if (variant == VectorButtonVariant.Secondary && !selected) {
                        Modifier.border(1.dp, c.controlBorder, shapes.pill)
                    } else {
                        Modifier
                    },
                )
                .vectorPressable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            VectorIconView(icon, glyph, if (enabled) content else content.copy(alpha = 0.5f))
        }
    }
}

// ---------------------------------------------------------------------------
// Chips
// ---------------------------------------------------------------------------

/**
 * A filter chip with an inverted selected state.
 *
 * The reference's selected chip is a **filled dark pill with light text** while
 * its neighbours stay light — the inversion is the selection signal, not a tint
 * (audit §9). Vector keeps that: `selected` swaps to the brand fill with white
 * text, which is unambiguous at a glance and survives a colour-vision
 * deficiency because the luminance flips too.
 */
@Composable
fun VectorChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: VectorIcon? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shapes = VectorTheme.shapes
    // Selected is CHARCOAL, not the brand blue.
    //
    // Every selected chip in the app being `c.primary` put a saturated
    // cerulean pill on the settings sheet that was the same colour as the
    // route ribbon on the map and the same colour as the Start button — so
    // "this is the current value of a preference", "this is the road you will
    // drive" and "press this to go" were all one colour, and the settings
    // screen read as a Material filter-chip row because a saturated fill on a
    // pill is exactly what that is.
    //
    // Splitting it gives the palette a job per colour: **blue is an action or
    // a route**, **charcoal is a selection**. Charcoal-on-cream is also the
    // editorial pairing the reference uses for its own selected states, it is
    // the highest-contrast fill available (15.9:1 for the label, against
    // 5.3:1), and it stays obviously selected in greyscale, which a same-value
    // blue does not.
    val container by animateColorAsState(
        targetValue = if (selected) c.ink else c.surfaceFloating,
        animationSpec = VectorTheme.motion.micro(),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) c.inkInverse else c.inkSecondary,
        animationSpec = VectorTheme.motion.micro(),
        label = "chipContent",
    )
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shapes.pill)
            .background(container)
            .then(
                if (selected) Modifier else Modifier.border(1.dp, c.controlBorder, shapes.pill),
            )
            .vectorPressable(enabled = enabled, pressScale = 0.95f, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics {
                role = Role.Button
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            // 18 dp, not 16. The glyph set draws controls at a stroke weight
            // proportional to the viewport, so a 16 dp chip icon renders a
            // hairline that reads as a ghost beside 13 sp semibold text. 18 dp
            // is where the stroke and the label carry the same weight at a
            // glance — checked on the emulator at 1080x2400.
            VectorIconView(leadingIcon, 18.dp, content)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = t.chip, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A chip that performs an action rather than expressing a filter.
 *
 * Same geometry, no selected state — used for the wrapped action row on a place
 * card (share, directions, call).
 */
@Composable
fun VectorActionChip(
    label: String,
    onClick: () -> Unit,
    icon: VectorIcon,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shapes = VectorTheme.shapes
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shapes.pill)
            .background(c.surface)
            .border(1.dp, c.controlBorder, shapes.pill)
            .vectorPressable(enabled = enabled, pressScale = 0.95f, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VectorIconView(icon, 18.dp, c.inkSecondary)
        Spacer(Modifier.width(8.dp))
        Text(label, style = t.chip, color = c.ink, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Badges and pills
// ---------------------------------------------------------------------------

/**
 * A small informational pill.
 *
 * The reference's "948 saves" is this: a filled dark pill with light, tight
 * text. [tint] defaults to the ink role so the default badge is the dark one.
 */
@Composable
fun VectorBadge(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onTint: Color? = null,
    icon: VectorIcon? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val bg = tint ?: c.ink
    val fg = onTint ?: c.inkInverse
    Row(
        modifier = modifier
            .clip(VectorTheme.shapes.pill)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            VectorIconView(icon, 12.dp, fg)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = t.eyebrow, color = fg, maxLines = 1)
    }
}

/** A status dot with a label: `● Open · 7am–6pm` in the reference. */
@Composable
fun VectorStatusLine(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            VectorIconView(icon, 14.dp, tone)
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(VectorTheme.shapes.pill)
                    .background(tone),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(text, style = t.metadata, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/**
 * A card.
 *
 * [VectorShapes.continuous] is used at `Lg` and above so large surfaces have the
 * reference's uninterrupted corner. The hairline border is part of the
 * component, not optional: on a warm cloud field a soft shadow alone is not
 * enough to separate two adjacent cards, which the audit measured directly
 * (`#F4F4F4` page against `#F8F8F8` card is a 4-level difference).
 */
@Composable
fun VectorCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = VectorTheme.shapes.continuous(20.dp),
    color: Color = VectorTheme.colors.surface,
    elevation: Dp = VectorTheme.elevation.card,
    border: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    val c = VectorTheme.colors
    Box(
        modifier = modifier
            .vectorShadow(elevation, shape, VectorTheme.elevation)
            .clip(shape)
            .background(color)
            .then(if (border) Modifier.border(1.dp, c.border, shape) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.vectorPressable(pressScale = 0.98f, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(contentPadding),
    ) { content() }
}

/**
 * A tappable row.
 *
 * `heightIn(min = 56.dp)`, never a fixed height: at a 2.0× font scale a
 * two-line row must grow. `trailing` is a slot rather than a colour so a row can
 * carry a switch, a chevron or a value.
 */
@Composable
fun VectorListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /**
     * What a screen reader says for the whole row, when that differs from the
     * text on it.
     *
     * A row reading "Home / Msheireb" announces as two fragments and says
     * nothing about what tapping it does. A caller that knows the row's verb
     * passes one — "Navigate to Home, Msheireb" — and the row becomes a single
     * statement. `null` leaves the visible text as the label, which is right for
     * a row that is only a statement.
     */
    contentDescription: String? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) {
                    Modifier.vectorPressable(enabled = enabled, pressScale = 0.99f, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = t.bodyStrong,
                color = if (enabled) c.ink else c.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = t.metadata,
                    color = c.inkMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A section header: a title, an optional eyebrow, an optional trailing action. */
@Composable
fun VectorSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow, style = t.eyebrow, color = c.inkMuted)
                Spacer(Modifier.height(3.dp))
            }
            Text(title, style = t.sectionTitle, color = c.ink, maxLines = 2)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                actionLabel,
                style = t.bodyStrong,
                // Secondary ink, not brand blue.
                //
                // A section's action is a *secondary* control sitting beside
                // the heading it belongs to. In brand blue it was the only
                // saturated thing on the discovery sheet and pulled the eye
                // away from the section title and the list underneath — and on
                // the recents section the blue word was "Clear", so the loudest
                // element on the surface was the destructive one.
                color = c.inkSecondary,
                modifier = Modifier
                    .clip(VectorTheme.shapes.pill)
                    .vectorPressable(pressScale = 0.96f, onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * A stat tile: an icon, a bold value and a tiny label.
 *
 * The reference's four-up row on a profile (`4 all places`, `3 want to try`).
 * Equal weight, so a row of these divides the width evenly.
 */
@Composable
fun VectorStatTile(
    value: String,
    label: String,
    icon: VectorIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    VectorCard(
        modifier = modifier.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        onClick = onClick,
        shape = VectorTheme.shapes.continuous(16.dp),
        elevation = VectorTheme.elevation.none,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            VectorIconView(icon, 18.dp, tint)
            Spacer(Modifier.height(8.dp))
            Text(value, style = t.sectionTitle, color = c.ink, maxLines = 1)
            Text(label, style = t.metadata, color = c.inkMuted, maxLines = 2)
        }
    }
}

/**
 * An avatar.
 *
 * Vector has no user accounts in this product, so this renders **initials on a
 * deterministic tint derived from the name** rather than a photo. Deterministic
 * matters: the same place must get the same colour on every launch and in every
 * screenshot baseline.
 */
@Composable
fun VectorAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val palette = listOf(c.primary, c.coral, c.leaf, c.lilac, c.deepBlue, c.sunny)
    val tint = palette[(name.hashCode().let { if (it < 0) -it else it }) % palette.size]
    val initials = name.trim().split(' ')
        .filter { it.isNotEmpty() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
    Box(
        modifier = modifier
            .size(size)
            .clip(VectorTheme.shapes.pill)
            .background(tint)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = t.chip,
            color = if (tint == c.sunny) c.onSunny else c.onAccent,
            maxLines = 1,
        )
    }
}

/**
 * A segmented control.
 *
 * Used for genuinely binary-or-ternary choices that must stay visible (map
 * perspective, units). It is *not* a tab bar: the selected segment is the brand
 * fill, and the control is a single card with a sliding indicator rather than
 * three buttons.
 */
@Composable
fun VectorSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shapes = VectorTheme.shapes
    Row(
        modifier = modifier
            .clip(shapes.pill)
            .background(c.surfaceSunken)
            .padding(3.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(shapes.pill)
                    .background(if (selected) c.primary else Color.Transparent)
                    .vectorPressable(pressScale = 0.97f) { onSelect(index) }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (selected) "Selected" else "Not selected"
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = t.chip,
                    color = if (selected) c.onPrimary else c.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

/**
 * The resting search affordance: a pill that *looks* like a field and opens one.
 *
 * Two components rather than one mode-switching field, because they are two
 * different controls: this one is a button (tap it and something else happens)
 * and [VectorSearchField] is a text input. A single component with a `mode` flag
 * is the shape that ends up with a text field that cannot be focused, or a
 * button that swallows a caret.
 *
 * It is deliberately indistinguishable from the field at rest — same height,
 * same radius, same leading glyph — so opening the search does not move anything
 * on screen. That continuity is the whole reason both reference products do it
 * this way.
 */
@Composable
fun VectorSearchButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    elevation: Dp = VectorTheme.elevation.floating,
    /** True when the button sits on a known surface rather than over the map. */
    filled: Boolean = false,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shape = VectorTheme.shapes.pill
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            // A control that is not floating is not drawn as though it were.
            //
            // `filled` is what the search entry became once it moved off the map
            // and into the discovery sheet. Over cartography the button needs a
            // shadow and a hairline to separate it from arbitrary content
            // underneath; on a known sheet surface both are false signals, and
            // the outline in particular is what made it read as a form field
            // rather than as the sentence the product is asking. Filled: a quiet
            // sunken well, no border, no shadow.
            .then(
                if (filled) Modifier
                    .clip(shape)
                    .background(c.surfaceSunken)
                else Modifier
                    .vectorShadow(elevation, shape, VectorTheme.elevation)
                    .clip(shape)
                    .background(c.surfaceFloating)
                    .border(1.dp, c.controlBorder, shape),
            )
            .vectorPressable(pressScale = 0.99f, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = VectorTokens.Space.s16, vertical = VectorTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            VectorIconView(icon, 20.dp, c.inkMuted)
            Spacer(Modifier.width(VectorTokens.Space.s12))
        }
        Text(label, style = t.body, color = c.inkSecondary, maxLines = 1)
    }
}

/**
 * The active search field.
 *
 * A plain `BasicTextField` inside Vector's own pill rather than Material's
 * `TextField`: Material's field brings a container, a focus indicator, a label
 * slot and its own height, and every one of those has to be switched off to get
 * here (the old code switched off four colours and an indicator to make it look
 * like nothing). Owning the decoration means the field is exactly the pill the
 * resting button is, and the caret is the only Material-ish thing left.
 *
 * [onClose] renders a leading close control; [loading] replaces the clear
 * affordance with a spinner at the same size so the row does not reflow.
 */
@Composable
fun VectorSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    loading: Boolean = false,
    closeIcon: VectorIcon? = null,
    clearIcon: VectorIcon? = null,
    /** True when the field sits on a known surface rather than over the map. */
    filled: Boolean = false,
    focusRequester: FocusRequester? = null,
    testTag: String? = null,
) {
    val c = VectorTheme.colors
    val t = VectorTheme.typography
    val shape = VectorTheme.shapes.pill
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            // Matches [VectorSearchButton] exactly, including this branch, so
            // that tapping the resting entry and getting the live field moves
            // nothing on screen — same height, same radius, same fill.
            .then(
                if (filled) Modifier
                    .clip(shape)
                    .background(c.surfaceSunken)
                else Modifier
                    .vectorShadow(VectorTheme.elevation.floating, shape, VectorTheme.elevation)
                    .clip(shape)
                    .background(c.surfaceFloating)
                    .border(1.dp, c.controlBorder, shape),
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = VectorTokens.Space.s8, vertical = VectorTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose != null && closeIcon != null) {
            VectorIconButton(
                icon = closeIcon,
                contentDescription = "Close the search",
                onClick = onClose,
                variant = VectorButtonVariant.Ghost,
                size = VectorButtonSize.Small,
                contentTint = c.inkSecondary,
                testTag = "search:close",
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .padding(horizontal = VectorTokens.Space.s8),
            singleLine = true,
            textStyle = t.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = t.body, color = c.inkMuted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        when {
            loading -> Box(
                Modifier
                    .size(48.dp)
                    .testTag("search:loading"),
                contentAlignment = Alignment.Center,
            ) { VectorSpinner(size = 18.dp, color = c.primary) }
            value.isNotEmpty() && onClear != null && clearIcon != null -> VectorIconButton(
                icon = clearIcon,
                contentDescription = "Clear the search",
                onClick = onClear,
                variant = VectorButtonVariant.Ghost,
                size = VectorButtonSize.Small,
                contentTint = c.inkSecondary,
                testTag = "search:clear",
            )
            else -> Spacer(Modifier.width(VectorTokens.Space.s8))
        }
    }
}

// ---------------------------------------------------------------------------
// Internals shared by the library
// ---------------------------------------------------------------------------

/** Draw a [VectorIcon] at a fixed size, tinted. */
@Composable
internal fun VectorIconView(icon: VectorIcon, size: Dp, tint: Color) {
    Box(Modifier.testTag("icon:${icon.key}")) {
        with(icon) { render(size, tint) }
    }
}

/**
 * A determinate-free progress indicator.
 *
 * Hand-drawn rather than Material's `CircularProgressIndicator` so its stroke
 * weight matches [VectorIcon]'s and so it stops turning under reduced motion —
 * a spinner that keeps spinning is exactly the animation someone who asked for
 * reduced motion does not want. Under reduced motion it renders a static
 * three-quarter ring instead.
 */
@Composable
fun VectorSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = VectorTheme.colors.primary,
    strokeWidth: Dp = 2.dp,
) {
    val motion = VectorTheme.motion
    val angle by animateFloatAsState(
        targetValue = if (motion.reduced) 270f else 360f,
        animationSpec = if (motion.reduced) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 900,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            )
        },
        label = "spinner",
    )
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = angle,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
            size = androidx.compose.ui.geometry.Size(
                this.size.width - stroke,
                this.size.height - stroke,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
        )
    }
}

/** Provide an explicit content colour to a subtree that draws its own glyphs. */
@Composable
internal fun WithContentColor(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color, content = content)
}

/** A 1 dp hairline, used inside rows where a full divider would be too heavy. */
@Composable
internal fun VectorHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(VectorTheme.colors.border),
    )
}

/** A rounded rectangle used for skeletons and empty placeholders. */
internal fun placeholderShape(radius: Dp = 16.dp): Shape = RoundedCornerShape(radius)

// ---------------------------------------------------------------------------
// Toggle
// ---------------------------------------------------------------------------

/**
 * Vector's switch.
 *
 * ## Why this exists at all
 *
 * The settings sheet drew Material 3's `Switch` with two of its colours
 * overridden. That is the single most recognisable stock Android control there
 * is: the tall stadium track, the thumb that grows when it is checked, the
 * ripple. Two overridden colours do not change any of that — the screenshot of
 * the old settings sheet is a Material screen with Vector's blue in it, which is
 * exactly the "token-only" change the redesign brief rules out, and it is the
 * one control a reviewer would name first.
 *
 * ## What is different, beyond not being Material's
 *
 *  * **A shorter, wider track** (52x30 rather than 52x32 with M3's padding), so
 *    it sits on the same optical baseline as the label beside it rather than
 *    towering over it.
 *  * **The thumb does not change size.** M3 grows the thumb on check, which
 *    reads as the control inflating. Here the thumb is one size and only
 *    travels; the state is carried by the track's fill and by the thumb's
 *    position, both of which survive being photographed in greyscale.
 *  * **Off is a real state, not an absence.** The unchecked track is the sunken
 *    surface with the control border, so it reads as an empty well the thumb
 *    could move into. M3's unchecked track is an outline that reads as disabled.
 *  * **No ripple.** The whole row is the target (see the caller) and it
 *    acknowledges with the same spring scale every other Vector control uses.
 *
 * ## Accessibility
 *
 * The switch itself is decorative: `Role.Switch` and the on/off
 * `stateDescription` live on the ROW, which is the 48 dp target, so a screen
 * reader gets one node that says what it is, what it does and what state it is
 * in. A second focusable node here would make every preference two stops.
 *
 * Under reduced motion the travel is a cut — `springControl` collapses to
 * `snap()` — and the control is still completely legible, because position and
 * fill are both static properties.
 */
@Composable
fun VectorToggle(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = VectorTheme.colors
    val motion = VectorTheme.motion
    val trackW = 52.dp
    val trackH = 30.dp
    val thumb = 24.dp
    val inset = 3.dp

    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = motion.springControl,
        label = "toggle",
    )
    val track by animateColorAsState(
        targetValue = when {
            !enabled -> c.surfaceSunken
            // Charcoal, for the same reason [VectorChip] is: on this sheet a
            // toggle and a chip are both "the current value of a preference",
            // and one of them being brand blue while the other is charcoal is
            // two answers to one question. Blue stays for actions and for the
            // route. It is also what stops this reading as Material's switch,
            // which is blue by default and was the single most recognisable
            // stock control in the product.
            checked -> c.ink
            else -> c.surfaceSunken
        },
        animationSpec = motion.standard(),
        label = "toggleTrack",
    )
    Box(
        modifier
            .size(trackW, trackH)
            .clip(VectorTheme.shapes.pill)
            .background(track)
            .then(
                if (checked) Modifier
                else Modifier.border(1.dp, c.controlBorder, VectorTheme.shapes.pill),
            )
            .alpha(if (enabled) 1f else 0.5f)
            // Decorative: the row that owns this carries the semantics.
            .clearAndSetSemantics { },
    ) {
        val travel = trackW - thumb - inset * 2
        Box(
            Modifier
                .padding(start = inset + travel * progress, top = inset)
                .size(thumb)
                .clip(VectorTheme.shapes.pill)
                .background(if (checked) c.inkInverse else c.surface),
        )
    }
}
