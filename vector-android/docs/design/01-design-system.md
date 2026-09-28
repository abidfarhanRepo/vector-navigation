# Vector's design system

The single reference for anyone changing Vector's interface. It documents what
exists, why each value is what it is, and which test will fail if you break it.

Read `00-reference-audit.md` first if you want to know where the visual direction
came from; this file is about the system that came out of it.

## 0. Where everything lives

| what | file | notes |
|---|---|---|
| colour roles, ramps, contrast maths | `design/VectorColor.kt` | the only file that names a hex |
| type families and roles | `design/VectorType.kt` | Manrope (display) + Inter (UI) |
| radii, continuous corners, elevation | `design/VectorShape.kt` | `SquircleShape` lives here |
| theme, motion, reduced motion | `design/VectorTheme.kt` | `VectorTheme { }` is the entry point |
| component library | `design/VectorComponents.kt` | buttons, chips, rows, cards, search |
| surfaces | `design/VectorSurfaces.kt` | sheet, dialog, snackbar, empty/error/offline, skeletons, media tiles |
| numeric ladders | `VectorTokens.kt` | space, radius, size, motion — **numbers only** |
| icon set | `VectorIcons.kt` | 59 marks, two weights |
| map marks | `VectorMarkers.kt` | MapLibre bitmaps: vehicle, origin, destination, callout, warnings |
| cartography | `VectorStyle.kt` | the map's own palette and layers |
| window theme | `res/values/themes.xml`, `values-night/` | the pre-Compose frame |

**Rule:** a screen reads roles. `VectorTheme.colors.ink`, not `Color(0xFF1A1C1E)`.
The only files permitted to name a colour are the ones in the table above.

## 1. How a screen is built

```kotlin
// once, at the root
VectorTheme(darkTheme = resolved == VectorMapTheme.DARK) { … }

// in a screen
val c = VectorTheme.colors
val t = VectorTheme.typography
VectorCard { VectorListRow(title = …, onClick = …) }
```

`VectorTheme` also binds Material 3 to the same families and roles, so an
unreplaced Material component (a `Slider`, a `Tooltip`) cannot introduce a second
typeface or a second blue. Nothing in Vector's own screens reads the Material
scheme.

## 2. Colour

### Ramps

Five families, named for what they are: `Ramp.cloud*` (warm surfaces),
`Ramp.ink*` (charcoal), `Ramp.cerulean*` (brand), `Ramp.coral/sunny/leaf/lilac/
deepBlue*` (accents), `Ramp.night*` (dark surfaces).

### Roles

`VectorColors` is the semantic layer — `field`, `surface`, `ink`, `primary`,
`controlBorder`, `guidanceSurface` … 47 roles, one light instance and one dark.
Screens use roles only.

### The three rules the palette is built on

1. **Cards are lighter than the page.** `surface` sits above `field` in *both*
   themes. This is the reference's own inversion of the usual convention and it
   is what makes a dense screen read as a composition. Asserted by
   `ContrastTest.a card sits lighter than the page`.
2. **Saturated colour is scarce.** Accents appear on pins, chips, status and
   destructive actions; everything else is cloud and charcoal.
3. **A fill and its label are different jobs.** `primary` carries white;
   `primaryText` carries the brand against the page. `sunny` is light, so its
   label is `onSunny` (charcoal) — white on it is 1.68:1.

### Interactive boundaries are not hairlines

`border` is a decorative hairline: the reference's own cards measure ~1.2:1 and
that is correct for separating two surfaces that already differ in lightness.
`controlBorder` is the outline of a control that has nothing else to identify it
(an outlined button, a chip at rest, the search field) and reaches **3:1** against
every surface it is used on, as WCAG 2.1 §1.4.11 requires.

Darkening `border` until it passed would have turned every card edge into a hard
rule and cost the composition its surface language. That distinction exists
because `ContrastTest` failed on the first palette and the failure was right.

### Dark mode is designed, not inverted

The night field is a warm-neutral charcoal (`#14161A`), not a blue-black, so the
chrome does not fight the map's own dark cartography for the same hue. Surfaces
*lift* as they nest, accents are lightened and desaturated, and no role is an
arithmetic inversion of its daylight counterpart.

### The guidance band is dark in both themes

`guidanceSurface` is the maneuver band. It stays dark in daylight because it is
the one element read through a windscreen in direct sunlight, and a light band on
a light map has no edge. Asserted by `ContrastTest.the guidance band is dark in
both themes`.

## 3. Typography

Two **SIL OFL** families, bundled as static TTFs in `res/font` — no runtime
download, no network dependency for basic rendering.

* **Manrope** — display, screen titles, section titles, the driving HUD.
  Geometric-humanist, tall x-height, flat terminals; set tight and heavy it
  produces the reference's editorial confidence without copying it.
* **Inter** — everything else. Unambiguous numerals and distinguishable
  `l`/`I`/`1`, which matters more here than personality: this app is read in a
  car, in sunlight, at up to 2.0× text size.

| role | size / line | family + weight | used for |
|---|---|---|---|
| `display` | 34 / 38 | Manrope ExtraBold, −0.022 em | hero copy, empty states |
| `screenTitle` | 28 / 33 | Manrope ExtraBold, −0.018 em | a screen's own title |
| `sectionTitle` | 21 / 26 | Manrope Bold, −0.012 em | a titled group |
| `cardTitle` | 17 / 23 | Inter SemiBold | a card or place row's title |
| `body` | 15 / 22 | Inter Regular | running text |
| `bodyStrong` | 15 / 22 | Inter SemiBold | emphasis without size change |
| `metadata` | 13 / 18 | Inter Medium | second line: address, distance |
| `chip` | 13 / 17 | Inter SemiBold | chip and badge labels |
| `caption` | 11 / 14 | Inter Medium | unit suffixes, tiny facts |
| `button` | 16 / 20 | Inter SemiBold | button labels |
| `eyebrow` | 11 / 14 | Inter Bold, +0.08 em | **the one all-caps role** |
| `hudPrimary` | 31 / 36 | Manrope ExtraBold | maneuver distance + road name |
| `hudSecondary` | 26 / 30 | Manrope Bold | arrival clock, duration |
| `hudContext` | 18 / 24 | Inter SemiBold | road being driven |
| `hudSupporting` | 15 / 20 | Inter Medium | speed, lane glyphs |
| `dialNumber` / `dialUnit` | 26 / 11 | Manrope ExtraBold / Inter SemiBold | inside a round dial |

`eyebrow` is the only role with tracking and caps intent. Everything else is
sentence case. Asserted by `VectorTypographyTest`.

## 4. Space, radius, size

Numeric ladders, because a named ladder (`xs`, `sm`, `md`, `lg`) has no step to
give a 20 dp gap and ends up either skipping it or inventing a lie.

* **Space** — `s2 s4 s6 s8 s12 s16 s20 s24 s32 s40 s48`. `s2` and `s6` are
  optical only (border insets, glyph-to-label); the rest are the layout grid.
* **Radius** — `r0 r8 r12 r16 r20 r24 r28 r32 pill`. `r0` is used: the maneuver
  band is full-bleed, because a driving instruction is not a card.
* **Size** — component footprints (`control` 48, `row` 56, `bannerMinHeight` 96,
  `speedCurrentDial` 68 …). Anything that renders text takes its type from
  `VectorTypography` and its box from here.

### Continuous corners

`VectorShapes.continuous(radius)` builds a **superellipse** (`n = 4`) rather than
four circular arcs. A circular corner has a curvature discontinuity where the arc
meets the straight edge, visible on a large surface as a subtle pinch; the
reference's cards do not have it. Small controls keep plain rounded rectangles —
below ~12 dp the difference is under a pixel. Asserted by `VectorShapeTest`,
including that the arc stays inside the box and never degenerates to a square.

### Elevation

Soft, wide, low-opacity shadows plus a hairline — not hard edges, and not
contrast. The **spot** colour is darker than the **ambient**, and both are
translucent charcoal rather than black, so a card's shadow on the warm field
stays warm. The dark theme's shadows are stronger: a dark surface on a dark field
needs more separation, not less.

## 5. Motion

Bands, from the brief and from the reference products' own behaviour:

| band | ms | used for |
|---|---|---|
| `instant` | 0 | critical driver information — the maneuver banner cuts |
| `microFast` | 180 | a press acknowledgement, something leaving |
| `micro` | 220 | chip, badge, row state |
| `standard` | 260 | a card or panel arriving |
| `scene` | 380 | a sheet, a scene change, a route redraw |
| `sceneLong` | 450 | the top of the scene band |
| `flight` | 1800 | the camera crossing scales — the only long move |

**Springs, not tweens**, for cards, chips, pins, sheets and saved states: a
spring has velocity, so a control interrupted mid-flight continues from where it
was instead of restarting. Tweens are for opacity and for distances the caller
cannot know.

**Reduced motion is a value, not a branch.** When the system reports animations
off, `VectorTheme.motion` provides an instance whose durations are zero and whose
springs are `snap()`. Call sites use `VectorTheme.motion.springCard` and get an
instant transition — there is no `if (reducedMotion)` at any call site, because
40 branches is 40 chances to forget one, and the forgotten one is always the one
that makes someone motion-sick.

The system setting is re-read on every `ON_RESUME`, because it is changed in a
different app.

## 6. Components

`VectorButton` (Primary/Secondary/Tonal/Ghost/Destructive × Large/Medium/Small),
`VectorIconButton`, `VectorChip`, `VectorActionChip`, `VectorBadge`,
`VectorStatusLine`, `VectorCard`, `VectorListRow`, `VectorSectionHeader`,
`VectorStatTile`, `VectorAvatar`, `VectorSegmented`, `VectorSearchButton`,
`VectorSearchField`, `VectorBottomSheet`, `VectorDialog`, `VectorSnackbar`,
`VectorEmptyState`, `VectorErrorCard`, `VectorOfflineBanner`, `VectorSkeleton`,
`VectorSkeletonLines`, `VectorMediaTile`, `VectorTileRow`,
`VectorPlaceholderBlock`, `VectorSpinner`.

The rules the library enforces so screens do not have to:

1. **Every interactive component is at least 48×48 dp**, including ones that look
   smaller — `VectorIconButton(Small)` is a 36 dp circle inside a 48 dp target.
2. **No component clips text.** Heights are `heightIn(min = …)`, so 2.0× font
   scale reflows rather than truncates.
3. **Every component takes a `testTag`**, and every icon-only component takes a
   `contentDescription`.
4. **Press feedback is a spring scale**, not a ripple: a ripple spreads over
   ~300 ms, which on a control someone stabs at is feedback that arrives after
   they stopped watching.
5. **Colour comes from roles only.**

Icons are injected as a slot (`VectorIcon(key, render)`), not imported. The
library has no dependency on the icon set and no opinion about how a glyph is
drawn; `VectorIcons.glyphIcon(...)` is the adapter.

## 7. Icons

One family, hand-drawn as Compose `Canvas` paths: a 24×24 viewport with ink
inside a **20×20 live area**, rounded caps and joins everywhere, and **two
weights chosen by rendered size** — `STROKE` (0.105) at 20–24 dp, `STROKE_LIGHT`
(0.085) at 16 dp and below, because stroke width is a fraction of the viewport
and one fraction does not serve two sizes.

`Glyph` is the closed map/HUD **control** vocabulary (25 entries). `Extra` is the
general mark set (34 entries) and delegates to `Glyph` where a mark already
exists. `MANEUVER_STROKE` (0.155) is separate and deliberate: it is the weight
measured off Waze's banner arrow, and it is the one mark whose weight came from a
measurement rather than a judgement.

## 8. The map

### Marks

Circular badge pins, not teardrops: a cloud fill, a hairline, a soft shadow, a
category-tinted ring and a short nub so the anchor is unambiguous. The origin is
a small ring; the vehicle keeps its arrowhead (an arrowhead is legible in
peripheral vision where a top-down car is not). The callout pill's stretchable
band is derived from one function so the bitmap, its stretch region and its
content box cannot disagree — a mismatch is a hard crash out of MapLibre's JNI.

### Cartography

The map's palette is `VectorStyle.Palette`, one instance per theme, and it obeys
measured relationships rather than taste:

* roads are drawn **lighter** than the ground in daylight (white carriageways,
  the Google Maps convention) and lighter than the ground at night too;
* `laneMarking` is **darker** than the deck in daylight, because white paint on a
  white carriageway is not a line;
* `buildingOutline` is darker than `building` — a seam is a shadow line;
* `backgroundDriving` steps **below** `background` so the deck comes forward at
  driving zoom;
* every road tier is ΔL\* ≥ 6 from the ground, and the carriageway ΔL\* ≥ 10
  above the driving ground. `VectorStyleTest` asserts all of it.

The ground is L\* 89.6 rather than the reference's near-white L\* 97, and that is
a **constraint, not a preference**: Vector draws white roads, so the ground has to
stay low enough for the ramp to climb through it. Adopting the reference's base
would mean adopting its darker-road convention too, which is a different
cartography.

### Production parity

The same measured tones are used by the production web viewer
(`vector-web/static/index.html`, `buildStyle`) and the tile server's smoke page
(`vector-tile-server/static/index.html`). The two clients keep their own road-ramp
*direction* — the browser surface uses darker roads on a light ground, which is
defensible at desktop viewing distance — but they describe one world, and the
night ground is `#1a1f2b` in all three places for the reason recorded above.

## 9. Accessibility

* Every text pair the UI ships is asserted at **4.5:1** (body) or **3:1** (large
  text, glyphs, control boundaries) by `design/ContrastTest.kt` — 47 roles × both
  themes, with the failing role named in the message.
* Minimum touch target 48×48 dp, asserted by the instrumented accessibility test.
* Every icon-only control has a content description; a row whose spoken form
  differs from its text passes one (`VectorListRow(contentDescription = …)`).
* Live regions on the snackbar (polite) and the error card (assertive).
* Reduced motion honoured system-wide.
* Font scale 0.85–2.0× and RTL are in the device matrix.

## 10. Adding something new

1. New colour? Add a **role** to `VectorColors` (both instances) and add its
   pair to `ContrastTest`'s tables — the test fails if a declared role is missing
   from them, which is the only way an inaccessible colour gets in.
2. New type role? Add it to `VectorTypography` and to `VectorTypographyTest`'s
   table, and keep its line height above its size.
3. New component? Build it from `VectorTheme` roles, give it a `testTag`, keep
   its height a minimum rather than a fixed value, and use
   `Modifier.vectorPressable` for feedback.
4. Never add a raw hex, a bare `sp` literal or a `dp` literal to a screen file.
