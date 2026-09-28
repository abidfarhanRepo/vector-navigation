# Reference audit — Corner (iOS) → Vector (Android)

Status: **complete**. This document was written **before** any production code was
changed, as required. It records what was inspected, how each value was obtained,
and what Vector takes from the reference versus what it deliberately does not.

## 0. Method and provenance

* Reference screens were pulled from Mobbin's Corner iOS app library through the
  Mobbin MCP tools, **at full resolution, and used to sample colours and measure
  geometry**. Each screen below is cited by its Mobbin screen id so any claim can
  be re-checked.
* **The images themselves are deliberately NOT committed.** They are a third
  party's screenshots of a third party's product, and a design audit is not a
  licence to redistribute them. Re-fetch any screen by its id through the Mobbin
  MCP tools (`search_screens`, then the screen's `image_url`) if you need to
  re-derive a value.
* Colours are **sampled, not guessed**: ImageMagick `-colors N -format %c
  histogram:info:-` for dominant palettes, and raw RGBA dumps (`-depth 8 rgba:`)
  parsed in Python for per-pixel probes and geometry.
* Geometry is measured in reference pixels and converted at the reference
  device's 3× scale (1179 px ≈ 393 dp wide).
* Where a value is inferred rather than measured it is marked `[INFERENCE]`.

Reference screens used (Mobbin ids, abbreviated): `f7a968bc`, `104a5a32` (map +
selected place sheet), `12b6f113`, `5a7065a2` (settings), `ba152bba` (profile
edit), `b29f00e1`, `8b540dc1` (profile), `753b3842` (place detail), `cce76543`
(activity/notifications), `91311eb5` (onboarding), `2b758b3e` (search), plus the
place-detail and list screens in `1af2ee0f`, `17d7f2fc`, `d4cce363`, `42b42782`.

## 1. Screen inventory and user flows (reference)

| Surface | What it contains | Reference |
|---|---|---|
| Home / map | Full-bleed light map, floating black search pill at the top, a white announcement card that can be dismissed, a horizontal row of category glyphs connected by a hairline with a `?` terminator, small circular pins with a bold name label and a muted meta line, a floating bottom pill bar (list · map · search) plus a separate circular `+` button | `f7a968bc`, `104a5a32` |
| Selected place (map) | Pin lifts; a bottom sheet opens over the map: emoji + heavy uppercase title, a black save-count pill, meta line, a status line (green when open, coral when not), a 2-up photo grid, a rotated "sticker" caption on a photo, a wrapped row of white action chips (share, directions, site, ig) | `104a5a32`, `753b3842` |
| Place detail (full) | Distance + duration line, heavy uppercase display title, meta line, full-bleed hero photo, white sheet with labelled rows, action chips, one black full-width CTA, a "go back" text button | `753b3842`, `1af2ee0f` |
| Profile | Heavy display name, handle, follower/following counts (bold number, muted label), a row of four equal stat cards (icon + bold number + tiny label), a horizontally scrolling chip row with an inverted selected state, 2–3 up photo grids with a centred caption, grey placeholder tiles for missing lists | `b29f00e1`, `8b540dc1`, `1de6a9ec` |
| Lists / collections | Cover tiles with a dark translucent strip carrying a lock icon and a count, bold list title, muted "N places", empty-state blocks in flat light grey | `1e69ca8b`, `c830db36`, `4d3c6b2d` |
| Search | Search field, chips, result rows with a small thumbnail, bold name, muted address | `2b758b3e`, `7927b2f6`, `a4400c99` |
| Activity | A "what you missed" section header, a large white card with an avatar + name + action + time, a quoted note, an embedded place card with a photo and meta, reaction affordances (comment, a coral heart, a pink reply pill) | `cce76543` |
| Settings | Title + close `X`, white cards holding centred bold text rows, a black pill `save` CTA, a segmented radio group (filled dot / empty ring), a switch, a red destructive text action at the bottom, grouped white cards with generous vertical gaps | `12b6f113`, `5a7065a2` |
| Notification settings | One card containing eight rows: glyph + bold title + two-line muted description + switch | `cce76543` sibling |
| Onboarding | Full-bleed photo or illustration, large display headline, one-sentence support copy, a single black pill CTA pinned near the bottom | `91311eb5`, `49ed62c9` |

**Flows observed:** first-launch → onboarding pages → permission/education →
map home; map → pin tap → place sheet → save to list; search → result → place
detail → directions; profile → list → place; settings → toggles → destructive
confirm.

## 2. Colour, sampled

Corner's own values, measured:

| Role | Measured | Where |
|---|---|---|
| Page background | `#F4F4F4` (settings), `#EEEFEE` (profile) | `12b6f113`, `b29f00e1` |
| Card surface | `#F8F8F8` | `12b6f113` |
| Sheet surface | `#F1F3F6` | `753b3842` |
| Primary ink | `#1D1D1D`, `#181617`, `#232222` | settings, detail, map |
| Muted ink | `#A0A0A0`, `#A1A1A0`, `#666460` | settings, profile |
| Destructive / accent red | `#F93335`, `#F23B43`, `#EE5A5E` | settings |
| Map base | `#F6F7F6` | `104a5a32` |
| Map water | `#BEDCF4`, `#C5DCF0`, `#CFE5F5` | `104a5a32` |
| Map park / green field | `#E8EDD5`, `#D7E8D1` | `104a5a32` |
| Map road casing / fill | `#A3A5A8`, `#D3D6D3` | `104a5a32` |
| Pin accents | green `#65EC70`, blue `#1D6EE4` / `#126BE5`, cerulean `#5CA1DA`, lilac `#C3ABCE` / `#C0A4DD`, mint `#99F6AE`, pink `#FAA9D6`, gold `#B59B5B` | map + profile |

**Reading of the system:** a neutral, very light field (`#EEF4–#F8`), cards a
*shade lighter than the page* and separated by hairlines plus a soft shadow
rather than by contrast; near-black ink that is never pure black; saturated
colour reserved almost entirely for **map pins, status, and destructive
actions**. Water is the only large blue area, and it is a soft sky blue, not a
primary blue.

## 3. Typography

* Display and titles: a tight, heavy grotesk. Place names are set in **uppercase**
  with tight tracking; profile names are sentence case and very heavy. `[INFERENCE]`
  the family is a licensed neo-grotesk (Söhne/Neue-Montreal class); it cannot be
  legally verified, so it is not reproduced.
* Body/UI text: the same family at 400–600, generous line height, no all-caps
  except the place-name display style.
* Muted metadata sits one step down in size and colour, never both at once to the
  point of failing contrast.

Vector's decision: **Manrope** (SIL OFL) for display/hero/titles and **Inter**
(SIL OFL) for UI/body — both bundled as static TTFs in `res/font`, no runtime
download. See `01-design-system.md` §3.

## 4. Layout, spacing, radius, elevation

Measured on `12b6f113` at 3× (1179 px wide):

| Property | Measured | Notes |
|---|---|---|
| Page side margin | 48 px = **16 dp** | identical left and right |
| Card corner radius | arc spans ~109 px ⇒ **≈ 30–36 dp** | large, continuous, "squircle-like"; JPEG edge blur makes this an upper bound |
| Card-to-card vertical gap | 40–48 px = **13–16 dp** | plus a hairline |
| Card elevation | soft, wide, very low-opacity shadow; no visible hard edge | separation is done by shadow + hairline, not by contrast |
| Chip / control heights | 44–56 px in reference previews; pill CTAs read as ~56 dp | `[INFERENCE]` from preview scale |

Vector's decision: a 4 dp grid with an editorial rhythm of 4/8/12/16/20/24/32,
card radius 16/20/24 dp, sheet radius 28 dp, continuous (squircle-like) corners on
large surfaces. See `01-design-system.md` §4.

## 5. Iconography

* One rounded-outline family, consistent stroke, no filled/sharp mixture.
* Category glyphs appear as small **circular badges with a tinted fill** on the
  map and in the chip row, and are recognisable by shape without colour.
* Icon-only controls are circular white buttons with a hairline border and a soft
  shadow; top-right utility actions cluster in a row.

Vector's decision: keep the existing hand-drawn Compose `Canvas` icon set (it is
already one family, already tokenised) and normalise it to a single 24 dp
viewport, two stroke weights and one corner language. No emoji in UI chrome.

## 6. Pins and markers

* Pins are **small circles** (not teardrops), white fill, hairline border, a
  coloured ring or tinted glyph, plus a soft drop shadow.
* A selected pin is lifted and paired with a name label; labels are bold name +
  muted meta on two lines.
* Labels sit under/beside the pin and are readable against the map without a
  pill background. `[INFERENCE]` clustering is not directly observable in the
  static references.

Vector's decision: circular badge pins with a tinted category ring and a
charcoal label plate; the vehicle puck, origin dot and destination pin are
restyled to the same language. See `01-design-system.md` §6.

## 7. Image treatment

* Large continuous corner radius, 2-up and 3-up grids with tight (4–8 dp)
  gutters, images bleed to the edge of their group.
* A playful rotated caption "sticker" is overlaid on one photo.
* Photos are the only place warm browns/tans appear; the interface stays neutral.

Vector's decision: image tiles take the card radius, gutters on the 4 dp grid,
no rotated stickers (the product has no editorial photo content), and no image
placeholder may flash — tiles render a skeleton in the final layout.

## 8. Motion vocabulary

Static references cannot show timing, so this is an inference from the
interaction model plus what the reference products are known to do `[INFERENCE]`:

* Sheets and place cards **slide from the marker into place** — a shared-element
  style continuity between the pin, the card and the sheet.
* Chips and pins use a short spring (a slight overshoot, no bounce loop).
* Selected states cross-fade and scale within ~150–250 ms.
* Section content enters with a small stagger; nothing loops.

Vector's decision: 180–260 ms microinteractions, 300–450 ms sheet/scene
transitions, spring curves for cards/chips/pins/sheets/saved states, a stagger
only where it aids comprehension, and a **reduced-motion** path that collapses
every one of these to a cut. See `01-design-system.md` §7.

## 9. State handling (reference)

| State | Reference behaviour |
|---|---|
| Loading | Skeleton blocks in the final layout; no spinner over the content area |
| Empty | Flat light-grey blocks/placeholders with a short muted sentence and one action |
| Selected | Inverted fill (dark chip, white text), lifted pin, elevated card |
| Error | Inline card with a coral icon, a plain sentence, one retry action |
| Offline | Persistent, quiet banner rather than a modal |
| Keyboard | Content scrolls above the IME; the primary action stays reachable |
| Permission denied | A card that explains what is lost and offers a settings path |

Vector's decision: every one of these is implemented explicitly; see
`01-design-system.md` §9 and the state matrix in `03-visual-qa.md`.

## 10. What Vector takes, and what it refuses

**Takes:** the light, warm, low-contrast surface system; the large continuous
radius; the pill vocabulary; the inverted selected chip; the editorial spacing
rhythm; the circular-badge pin language; the soft sky-blue map water; the
reserved use of saturated colour; the single-CTA-per-screen discipline.

**Refuses:** Corner's logo, name, proprietary illustrations, user-generated
images, copy, exact branded assets, its exact font (unlicensed), its
emoji-as-iconography, and its place/social product model. Vector is a
**navigation** product: its map is the primary field and its guidance surfaces
must remain legible in sunlight at speed, so the driving HUD keeps a
high-contrast treatment that a pure Corner transplant would not have.
