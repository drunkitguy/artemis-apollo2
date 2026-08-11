# 03 — UI Specification

Jetpack Compose + Material 3, deliberately tuned away from stock Material toward the calm,
light, heavily-rounded look of the iPad reference: large white cards on a very light gray
field, generous corner radii, soft short-offset shadows, blue as the only accent, and values
rendered in blue on the right side of settings rows.

**Design posture:** use Material 3's `MaterialTheme` for typography plumbing and component
behaviour, but **override the shapes, elevation, and color roles** so nothing reads as
"default Material". No FABs. No filled tonal buttons with pill shapes everywhere. No
`ElevatedCard` default shadows.

---

## 1. Design tokens

Tokens live in `ui/theme/`. They are exposed both through `MaterialTheme.colorScheme` (for
Material components) **and** through a `LocalVoidTokens` `CompositionLocal` carrying the
extended set that Material's scheme has no slot for.

### 1.1 Color — light (primary design)

| Token | Hex | Role |
|---|---|---|
| `background` | `#F2F2F7` | App background (the "very light gray" field) |
| `surface` | `#FFFFFF` | Cards, sheets, settings panel |
| `surfaceVariant` | `#F7F7FA` | Nested/inset areas, segmented-control track |
| `surfaceElevated` | `#FFFFFF` | Dialogs |
| `outline` | `#E3E3E8` | Card borders (1 dp hairline) |
| `divider` | `#E5E5EA` | Row and card-footer dividers |
| `onSurface` | `#1C1C1E` | Primary text |
| `onSurfaceSecondary` | `#6E6E73` | Secondary text, status labels |
| `onSurfaceTertiary` | `#A1A1A6` | Placeholder, disabled text |
| `accent` | `#0A84FF` | Primary blue: values, selected segments, links, active icons |
| `accentPressed` | `#0060DF` | Pressed state of accent surfaces |
| `accentSoft` | `#E8F1FE` | Light-blue fill: online icon tiles, "Pair with PIN" button fill |
| `accentSoftPressed` | `#D6E6FD` | Pressed state of `accentSoft` |
| `success` | `#30D158` | Online wifi glyph |
| `warning` | `#FF9F0A` | Offline warning triangle (see note) |
| `danger` | `#FF3B30` | Destructive actions, fatal errors |
| `neutralTile` | `#EDEDF0` | Offline icon tile fill |
| `neutralTileFg` | `#8E8E93` | Offline monitor glyph, disabled button icon/text |
| `scrim` | `#000000` @ 40% | Modal scrim |
| `overlayScrim` | gradient `#00000000 → #000000B3` | App-card title scrim |
| `shadow` | `#000000` @ 8% | Card shadow color |

**Offline status color note:** the reference renders the offline row in **gray**, not orange —
gray triangle + gray "Offline". Use `onSurfaceTertiary` (`#A1A1A6`) for the offline glyph and
label. `warning` is reserved for genuine warnings (poor connection, capability unavailable).

### 1.2 Color — dark

| Token | Hex |
|---|---|
| `background` | `#000000` |
| `surface` | `#1C1C1E` |
| `surfaceVariant` | `#2C2C2E` |
| `surfaceElevated` | `#2C2C2E` |
| `outline` | `#38383A` |
| `divider` | `#38383A` |
| `onSurface` | `#F2F2F7` |
| `onSurfaceSecondary` | `#98989F` |
| `onSurfaceTertiary` | `#68686E` |
| `accent` | `#0A84FF` |
| `accentPressed` | `#3D9BFF` |
| `accentSoft` | `#0A84FF` @ 18% |
| `accentSoftPressed` | `#0A84FF` @ 28% |
| `success` | `#32D74B` |
| `warning` | `#FFD60A` |
| `danger` | `#FF453A` |
| `neutralTile` | `#2C2C2E` |
| `neutralTileFg` | `#8E8E93` |
| `scrim` | `#000000` @ 60% |
| `shadow` | `#000000` @ 40% |

In dark mode, **cards get no shadow** — they are separated by `surface` vs `background`
contrast and a 1 dp `outline`. Shadows on dark backgrounds look like smudges.

### 1.3 Material 3 `ColorScheme` mapping

```
primary            = accent
onPrimary          = #FFFFFF
primaryContainer   = accentSoft
onPrimaryContainer = accent
background         = background
onBackground       = onSurface
surface            = surface
onSurface          = onSurface
surfaceVariant     = surfaceVariant
onSurfaceVariant   = onSurfaceSecondary
outline            = outline
outlineVariant     = divider
error              = danger
scrim              = scrim
```

Everything else (tertiary, inverse*, surfaceTint) is set to a value that will look obviously
wrong if it ever renders — we want to catch stock-Material leakage in review.
**`surfaceTint` must be `Color.Transparent`**: M3's elevation tint would tint our white cards
purple.

### 1.4 Shape / corner radius

| Token | dp | Used by |
|---|---|---|
| `radiusXs` | 6 | Small chips, badges |
| `radiusSm` | 10 | Segmented control thumb, small buttons |
| `radiusMd` | 14 | Segmented control track, text fields, in-card action button |
| `radiusLg` | 20 | App/box-art cards, settings panel corners |
| `radiusXl` | 26 | **Host cards** — the large radius that defines the look |
| `radiusTile` | 18 | The host icon tile (rounded square) |
| `radiusFull` | 999 | Circular buttons, status pills |

M3 `Shapes`: `extraSmall = radiusXs`, `small = radiusSm`, `medium = radiusMd`,
`large = radiusLg`, `extraLarge = radiusXl`.

### 1.5 Spacing

4 dp base grid.

| Token | dp |
|---|---|
| `space1` | 4 |
| `space2` | 8 |
| `space3` | 12 |
| `space4` | 16 |
| `space5` | 20 |
| `space6` | 24 |
| `space8` | 32 |
| `space10` | 40 |
| `space12` | 48 |

Screen horizontal padding: **`space5` (20 dp)** on compact, **`space6` (24 dp)** on medium+.

### 1.6 Elevation / shadow

| Token | Spec |
|---|---|
| `shadowCard` | y-offset 2 dp, blur 12 dp, `shadow` color, spread 0 |
| `shadowPanel` | y-offset 0, blur 24 dp, `shadow` color |
| `shadowDialog` | y-offset 4 dp, blur 20 dp, `shadow` color |

**There are no popovers or tooltips anywhere in this app.** Help text is an inline expansion
inside its row (§4.3); `shadowDialog` is for genuine modal dialogs only. If a spec sentence
elsewhere says "popover", it means "the row's inline info expansion" and is wrong wording.

Implemented with `Modifier.shadow(elevation, shape, clip = false, ambientColor, spotColor)`.
Do not use `Card`'s default `CardDefaults.cardElevation` — it produces a harder, darker
Material shadow. Use `Surface`/`Box` with an explicit shadow modifier.

### 1.7 Type scale

Font: system default (`FontFamily.Default` → Roboto). Weights: 400 / 500 / 600 / 700.

| Token | Size / line height / weight / tracking | Used by |
|---|---|---|
| `displayTitle` | 34 / 41 / 700 / −0.4 | "Hosts" screen title (large) |
| `navTitle` | 20 / 25 / 600 / −0.2 | Top-bar title when collapsed / app-grid host name |
| `sectionHeader` | 17 / 22 / 600 / −0.1 | Settings section headers ("Video", "Touch & Controller") |
| `cardTitle` | 22 / 28 / 700 / −0.3 | Host name on a host card |
| `rowLabel` | 16 / 21 / 400 / 0 | Settings row label |
| `rowValue` | 16 / 21 / 600 / 0 | Settings row value (blue, right-aligned) |
| `body` | 15 / 20 / 400 / 0 | Body copy, inline info text |
| `caption` | 13 / 17 / 500 / 0 | Status lines ("Online"/"Offline"), helper text |
| `button` | 16 / 21 / 600 / 0 | Card footer action buttons |
| `appTile` | 14 / 18 / 600 / 0 | App name on box art |
| `mono` | 13 / 17 / 500, `FontFamily.Monospace` | Stats overlay, diagnostics |

### 1.8 Iconography

**Shipped mechanism:** `ui/components/VoidLinkIcons.kt` maps every purpose to a named value
backed by `material-icons-extended` (`Icons.Filled.*`), which is in the version catalog.
Screens reference `VoidLinkIcons.Online`, never `Icons.Filled.Wifi` directly — so a glyph swap
is one edit in one file. **Do not bundle custom vector drawables**; the extended icon set
covers everything below.

The full inventory, as shipped:

| Purpose | `VoidLinkIcons` name | Backing glyph |
|---|---|---|
| Host / monitor tile | `Host` | `DesktopWindows` |
| Online | `Online` | `Wifi` |
| Offline | `Offline` | `Warning` |
| Unpaired badge | `Locked` | `Lock` |
| Pair action | `Unlocked` | `LockOpen` |
| Wake-on-LAN | `Power` | `PowerSettingsNew` |
| Connect / launch | `Connect` | `PlayArrow` |
| Quit running app | `Quit` | `Stop` |
| Add host | `Add` | `Add` |
| Refresh | `Refresh` | `Refresh` |
| Sidebar toggle | `Sidebar` | `Menu` |
| Overflow (•••) | `Overflow` | `MoreVert` |
| Settings | `Settings` | `Settings` |
| Rename | `Rename` | `Edit` |
| Delete / forget | `Delete` | `Delete` |
| Close / dismiss | `Close` | `Close` |
| Display | `Display` | `Tv` |
| Video section | `Video` | `Videocam` |
| Touch & Controller section | `Touch` | `TouchApp` |
| Gestures section | `Gestures` | `Gesture` |
| Peripherals section | `Peripherals` | `Keyboard` |
| Audio section | `Audio` | `VolumeUp` |

Deviations from the original plan, now normative: the overflow button is **vertical**
(`MoreVert`), not `more_horiz`; the sidebar toggle is `Menu`; the Touch section uses `TouchApp`
rather than a gamepad glyph; Peripherals uses `Keyboard` rather than `cast`.

**Glyphs used inline without a named entry** — the info circle (`InfoButton` draws its own
circled-i via `InfoToggleGlyph`), the section chevron (`ExpandMore`, rotated 180° when open),
and the row chevron on `PickerRow` (`ChevronRight`). Any *new* icon a screen needs must be
added to `VoidLinkIcons` first; screens must not reach into `Icons.Filled` themselves.

Standard icon sizes: 20 dp inside rows, 24 dp in nav bars, 34 dp inside the host tile.

### 1.9 Motion

| Token | Spec |
|---|---|
| `motionFast` | 120 ms, `FastOutSlowInEasing` — presses, toggles |
| `motionStandard` | 220 ms, `FastOutSlowInEasing` — section expand/collapse, sidebar |
| `motionEmphasis` | 320 ms, `CubicBezierEasing(0.2f, 0f, 0f, 1f)` — screen transitions |
| Segmented thumb | `spring(dampingRatio = 0.85f, stiffness = 400f)` on the offset |
| Press scale | cards scale to `0.985f` over `motionFast` |

Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (accessibility "remove animations") by
collapsing all durations to 0.

### 1.10 Window size classes

| Class | Width | Layout behaviour |
|---|---|---|
| Compact | < 600 dp | 1-column host grid; app grid 2 columns; **settings is a full-screen modal drawer**, not a sidebar |
| Medium | 600–839 dp | 2-column host grid; app grid 4 columns; settings is a 340 dp overlay drawer with scrim |
| Expanded | ≥ 840 dp | 2–3 column host grid; app grid 5–7 columns; **settings is a persistent 340 dp side panel that splits the layout** (content shrinks, does not get covered) |

---

## 2. Screen: Hosts

The entry screen.

### 2.1 Structure

```
┌────────────────────────────────────────────────────────┐
│                                                        │  ← status bar inset
│                        Hosts                           │  displayTitle, CENTERED
│                                                  [+]   │  add-host button, trailing
├────────────────────────────────────────────────────────┤
│                                                        │
│   ┌──────────────────┐   ┌──────────────────┐          │
│   │   HOST CARD      │   │   HOST CARD      │          │
│   └──────────────────┘   └──────────────────┘          │
│                                                        │
│   ┌──────────────────┐                                 │
│   │   HOST CARD      │                                 │
│   └──────────────────┘                                 │
└────────────────────────────────────────────────────────┘
```

* **Title "Hosts" is centered**, matching the reference. Implement as a `TopAppBar` with an
  empty `navigationIcon`, a centered `title`, and the add-button as the single action; or as a
  plain `Box` header if `CenterAlignedTopAppBar` fights us on the trailing action's effect on
  centering (it will — the title must remain optically centered regardless of actions, so
  measure and center manually).
* Background: `background`. The top bar is **the same color as the background** with no
  elevation and no divider until the grid scrolls under it, at which point a 1 dp `divider`
  line fades in (`motionFast`).
* Grid: `LazyVerticalGrid(GridCells.Adaptive(minSize = 320.dp))`, item spacing `space4`
  (16 dp) both axes, content padding = screen horizontal padding + `space4` top,
  `space10` bottom (so the last card clears any bottom inset).
* Pull-to-refresh triggers a discovery + poll sweep.

### 2.2 Component: `HostCard`

```
┌───────────────────────────────────────────────────┐
│                                                   │  ← space5 (20dp) padding
│   ┌────────┐    Gaming PC                         │
│   │  ▣ 🔒  │    ᯤ Online                          │
│   └────────┘                                      │
│                                                   │
│  ─────────────────────────────────────────────────│  ← divider, full-bleed
│                                                   │
│            🔓  Pair with PIN                      │  ← footer action button
│                                                   │
└───────────────────────────────────────────────────┘
```

**Container**

| Property | Value |
|---|---|
| Shape | `RoundedCornerShape(radiusXl)` = 26 dp |
| Background | `surface` |
| Border | 1 dp `outline` (light mode: optional, very subtle; dark mode: required) |
| Shadow | `shadowCard` (light mode only) |
| Min height | 148 dp |
| Content padding | `space5` (20 dp) on all sides of the header block; the divider is full-bleed |

**Header block** (row, `verticalAlignment = CenterVertically`)

*Icon tile:*

| Property | Value |
|---|---|
| Size | 64 × 64 dp |
| Shape | `RoundedCornerShape(radiusTile)` = 18 dp |
| Fill (online **or** paired) | `accentSoft` |
| Fill (offline) | `neutralTile` |
| Glyph | `desktop_windows`, 34 dp, `accent` when online, `neutralTileFg` when offline |
| Unpaired badge | 22 dp circle anchored to the tile's **bottom-end** corner, offset (+4, +4) dp; fill `surface`, 2 dp `surface` ring so it reads as cut out of the tile; contains a 12 dp `lock` glyph in `onSurfaceSecondary`. Shown **only when `!paired`**, regardless of online state. |

*Text block* (start padding `space4` = 16 dp, `weight(1f)`)

| Line | Style |
|---|---|
| Host name | `cardTitle`, `onSurface`, max 1 line, `TextOverflow.Ellipsis` |
| Status row | `space1` top spacing; icon 16 dp + `space1` gap + label in `caption` |

Status row variants:

| State | Icon | Icon color | Label | Label color |
|---|---|---|---|---|
| Online | `wifi` | `success` | "Online" | `success` |
| Offline | `warning` | `onSurfaceTertiary` | "Offline" | `onSurfaceTertiary` |
| Online, app running | `wifi` | `success` | "Online · <app name>" | `success` / name in `onSurfaceSecondary` |
| Checking | 14 dp indeterminate spinner | `onSurfaceTertiary` | "Checking…" | `onSurfaceTertiary` |

**Divider:** 1 dp, `divider`, full card width (no horizontal inset), sits directly above the
footer.

**Footer action button** — a full-width, full-bleed-to-the-card's-inner-edges button occupying
the bottom of the card. Height **52 dp**. Corner radius: bottom corners inherit
`radiusXl − 1dp`; top corners square (so it visually seats against the divider). Content is a
centered row: 20 dp icon + `space2` gap + label in `button` style.

| Card state | Label | Icon | Text/icon color | Fill | Enabled |
|---|---|---|---|---|---|
| Online + unpaired | "Pair with PIN" | `lock_open` | `accent` | `accentSoft` | yes |
| Online + paired | "Connect" | `arrow_forward` | `accent` | `accentSoft` | yes |
| Offline (MAC known) | "Wake-on-LAN" | `power_settings_new` | `neutralTileFg` | `transparent` | yes (looks muted, is tappable) |
| Offline (MAC unknown) | "Wake-on-LAN" | `power_settings_new` | `onSurfaceTertiary` | `transparent` | **no**, with an info affordance explaining that the MAC is unknown because we have never paired over HTTPS |
| Busy (an op in flight) | spinner + current label | — | `onSurfaceTertiary` | as above | no |

**Interaction**

* Tapping the **card body**: online+paired ⇒ navigate to the app grid; online+unpaired ⇒ same
  as "Pair with PIN"; offline ⇒ same as "Wake-on-LAN" (or shows the disabled explanation).
* Tapping the **footer** performs its specific action and does not propagate to the card.
* **Long-press** the card opens a context menu: "Host settings…", "Refresh", "Wake",
  "Unpair", "Forget host" (destructive, `danger`).
* Press feedback: whole card scales to `0.985f`; footer gets `accentSoftPressed` /
  a 6% `onSurface` overlay for the neutral variant. No Material ripple — the reference has
  none. Use `indication = null` plus explicit scale/overlay.

**Accessibility:** the card is one semantics node with a content description of
`"<name>, <status>"`, and the footer is a nested node with its own role and label. Minimum
touch target 48 dp is satisfied by both.

### 2.3 Empty and error states

| Condition | Content |
|---|---|
| No hosts, discovery running | Centered `EmptyState`: 56 dp `wifi_find` glyph in `onSurfaceTertiary`, title "Looking for PCs…", body "Make sure your PC is on the same Wi‑Fi network and the host software is running.", plus a text button "Add manually". |
| No hosts, no Wi-Fi | Title "Not on Wi‑Fi", body explaining discovery needs a local network, button "Add manually". |
| Discovery permission/multicast failed | Title "Can't search the network", body naming the likely cause, button "Add manually". |

### 2.4 Add Host dialog

A centered dialog (`radiusLg`, `surfaceElevated`, `shadowDialog`, max width 400 dp):

* Title "Add PC".
* A single text field, `radiusMd`, `surfaceVariant` fill, no outline, placeholder
  "IP address or hostname". `KeyboardType.Uri`, `imeAction = Done`.
* Optional advanced disclosure revealing a "Port" field defaulting to 47989.
* Buttons: "Cancel" (text, `onSurfaceSecondary`) and "Add" (text, `accent`, bold, disabled
  until the field is non-empty). While probing: "Add" is replaced by a spinner.
* On failure, an inline error line in `danger` beneath the field, with the actual reason
  ("No response on port 47989", "Not a supported host").

### 2.5 Pairing dialog

Sequence, all in one dialog that swaps its content with a crossfade:

1. **PIN display.** Title "Enter this PIN on your PC". The PIN rendered at 48 sp, weight 700,
   letter-spacing 8 sp, in `accent`, centered, with each digit in its own 56 × 68 dp
   `surfaceVariant` rounded box (`radiusMd`). Below: body text naming where to type it
   ("A prompt should appear on your PC, or open the host's web interface."). A "Cancel" text
   button.
2. **Working.** After the host acknowledges, a 28 dp spinner + "Pairing…" + a phase counter
   ("Step 3 of 5") in `caption`.
3. **Result.**
   * Success: 44 dp `check_circle` in `success`, "Paired", auto-dismiss after 900 ms.
   * `PIN_WRONG`: 44 dp `error` in `danger`, "Wrong PIN", body "The PIN didn't match. Try
     again.", buttons "Cancel" / "Try again".
   * `ALREADY_IN_PROGRESS`: "Another device is pairing", body "Wait for it to finish, then
     try again."
   * `FAILED`: "Pairing failed" with the underlying reason in `caption`.

Cancelling at any point must call `/unpair` (spec 01 §4.8) — the dialog cannot be dismissed by
tapping outside while phase 1 is in flight; only the explicit Cancel button works, so the
cleanup always runs.

---

## 3. Screen: App grid

### 3.1 Structure

```
┌────────────────────────────────────────────────────────┐
│  ☰            Gaming PC                        ⛶       │  ← nav bar
├────────────────────────────────────────────────────────┤
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐          │
│  │      │ │      │ │      │ │      │ │      │          │
│  │      │ │      │ │      │ │      │ │      │          │
│  │Desktop│ │ Game │ │ Game │ │ Game │ │ Game │         │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘          │
│  ┌──────┐ ┌──────┐ …                                   │
└────────────────────────────────────────────────────────┘
```

**Nav bar** (56 dp tall, `background` fill, no elevation):

| Slot | Content |
|---|---|
| Leading | Sidebar-toggle icon button (24 dp `side_navigation`). Toggles the settings panel. On compact it opens the modal settings drawer. |
| Center | Host name in `navTitle`, `onSurface`, centered, ellipsized |
| Trailing | Display icon button (24 dp `display_settings`) — opens the display/resolution quick sheet |

A back affordance is provided by the system back gesture and, on medium+, a leading-adjacent
`arrow_back` when the settings panel is closed. **UX rule:** never leave the user without a way
back to Hosts.

**Grid:** `LazyVerticalGrid(GridCells.Adaptive(minSize = 148.dp))`, spacing `space4` both axes.
Tiles are **portrait 3:4** (`aspectRatio(0.75f)`), matching `/appasset` box art.

### 3.2 Component: `AppCard`

| Property | Value |
|---|---|
| Shape | `RoundedCornerShape(radiusLg)` = 20 dp, `clip = true` |
| Aspect | 3:4 portrait |
| Image | box art, `ContentScale.Crop` |
| Fallback | `surfaceVariant` fill + the app's first letter in `displayTitle`, `onSurfaceTertiary`, centered |
| Title scrim | bottom-anchored vertical gradient, height 44% of the tile, `Color.Transparent → #000000B3` |
| Title | `appTile` style, `#FFFFFF`, `space3` padding, max 2 lines, ellipsized, bottom-start aligned |
| Shadow | `shadowCard` (light mode only) |

**States:**

* **Running app** (the host's `currentgame` matches): a 3 dp `accent` inner border ring inside
  the rounded shape, plus a small "Running" pill (`radiusFull`, `accent` fill, white
  `caption` text, 6 dp/2 dp padding) at the top-end corner with `space2` inset.
* **HDR-capable app:** a small "HDR" pill in the top-start corner, `surface` @ 85% fill,
  `onSurface` text — shown only when the effective settings have HDR on.
* **Press:** scale `0.97f`, plus a 10% black overlay.

**Ordering:** an app titled exactly "Desktop" sorts first; everything else alphabetical,
case-insensitive. Favorites (if the user has starred apps) sort above the rest, after Desktop.

**Long-press menu:** "Launch", "Resume" (only when this app is running), "Quit"
(destructive, only when running), "App settings…" (per-app overrides are **not** in v1 — omit
this entry rather than showing it disabled), "Star / Unstar".

### 3.3 Launch flow feedback

Tapping a card pushes a full-screen `LaunchProgress` overlay (not a dialog): a centered column
with the box art at 160 dp wide, the app name in `navTitle`, an indeterminate linear progress
bar 4 dp tall with `radiusFull` in `accent`, and a status line in `caption` driven by
`SessionState` (`"Starting session…"` → `"Negotiating video…"` → `"Connecting…"` →
`"Waiting for first frame…"`). A "Cancel" text button in `danger` is always present.

On failure the overlay converts to an error state with the real message from spec 01 §11.1 and
two buttons: "Back" and "Try again".

---

## 4. Settings

### 4.1 Panel container

| Property | Value |
|---|---|
| Width | **340 dp** on medium/expanded |
| Fill | `surface` |
| Shape | Expanded (persistent, split layout): square outer edge, `radiusLg` on the inner corners. Medium (overlay drawer): `radiusLg` on the trailing corners only. Compact: full-screen, `radiusLg` top corners if presented as a bottom sheet — **prefer a full-screen route on compact**, not a sheet. |
| Shadow | `shadowPanel` when overlaying; none when splitting |
| Scrim | `scrim` behind an overlay drawer, tap-to-dismiss |

**Panel header** (64 dp tall, pinned, `surface` fill, 1 dp bottom `divider` that appears on
scroll):

| Slot | Content |
|---|---|
| Leading | Sidebar-toggle icon button (24 dp) — collapses the panel |
| Center-start | "Settings" in `navTitle` |
| Trailing | Overflow `more_horiz` icon button — menu: "Reset section to defaults", "Reset all settings", "Add to favorites…", "Diagnostics" |

Body is a `LazyColumn` of sections. Bottom content padding `space10` so the last row clears
the navigation bar inset.

### 4.2 Component: `SectionHeader`

A tappable 52 dp row that expands/collapses its section.

```
[glyph 20dp]  Video                                    ⌄
```

| Element | Spec |
|---|---|
| Leading glyph | 20 dp, `accent`, `space4` start padding, `space3` gap |
| Label | `sectionHeader`, `onSurface` |
| Trailing chevron | 20 dp `expand_more`, `onSurfaceTertiary`, rotates 180° over `motionStandard` when open |
| Row padding | `space4` horizontal |
| Divider | 1 dp `divider` above each header except the first |

Expansion uses `AnimatedVisibility` with `expandVertically` + `fadeIn` over `motionStandard`.
Open/closed state persists across app launches per section.

### 4.3 Component: `SettingsRow` (the base scaffold)

Every concrete row type composes this.

```
┌──────────────────────────────────────────────────────────┐
│  Label                                       Value   (i) │   ← header line, min 44dp
│  ────────────────────────────────────────────────────    │   ← optional control line
│  [ control: slider / segmented / switch ]                │
│  Help text (revealed by the (i) button)                  │
└──────────────────────────────────────────────────────────┘
```

| Element | Spec |
|---|---|
| Horizontal padding | `space4` (16 dp) |
| Vertical padding | `space3` (12 dp) top and bottom |
| Min header height | 44 dp |
| Label | `rowLabel`, `onSurface`; `onSurfaceTertiary` when the row is disabled |
| Value | `rowValue`, **`accent`**, right-aligned, immediately left of the info button; `onSurfaceTertiary` when disabled |
| Info button | 20 dp `info` glyph in `onSurfaceTertiary`, 44 dp touch target, `space2` start gap |
| Divider | 1 dp `divider` between rows, inset `space4` from the start edge, flush to the end edge |
| Disabled | The whole row's content alpha drops to 0.45; the control is non-interactive; the info button **stays enabled** and its help text explains *why* it is disabled |

**`InfoButton` behaviour:** tapping toggles an inline expansion beneath the row —
`surfaceVariant` fill, `radiusMd`, `space3` padding, `space2` top gap, text in `body` /
`onSurfaceSecondary`, animated with `expandVertically` over `motionStandard`. **Inline
expansion, not a tooltip or popover** — it is reliably reachable, works with TalkBack, and
does not fight with the drawer's scroll. Only one info expansion is open at a time within a
section.

Every row's help text must be written and must say something real (what the setting does, what
it costs, and when it is unavailable). Placeholder copy is not acceptable.

### 4.4 Component: `SliderRow`

```
  Bitrate                                    23.0 Mbps  (i)
  ●───────────────────────────────────────────────○
```

| Element | Spec |
|---|---|
| Value formatting | Supplied by a `(Float) -> String` formatter. Bitrate: `"%.1f Mbps"`. Percentages: `"100%"`. The divider-position slider: `"| 50% | 50% |"`. |
| Track height | 4 dp, `radiusFull` |
| Active track | `accent` |
| Inactive track | `divider` (light) / `surfaceVariant` (dark) |
| Thumb | 22 dp circle, `#FFFFFF` fill, 1 dp `outline` border, `shadowCard`; grows to 26 dp while dragging (`motionFast`) |
| Steps | Continuous by default; `steps` supported for quantized values |
| Slider padding | `space2` top gap from the header line, `space1` bottom |
| Haptics | `HapticFeedbackType.TextHandleMove` at each step boundary for stepped sliders |

**Live commit rule:** the value label updates continuously while dragging; the setting is
persisted on drag-end. In-stream, sliders that affect the live session (bitrate) apply on
drag-end only, never per-pixel.

### 4.5 Component: `SegmentedRow`

```
  Preferred Codec                                       (i)
  ┌──────────────┬──────────────┐
  │    H.264     │     Auto     │
  └──────────────┴──────────────┘
```

| Element | Spec |
|---|---|
| Track | Full row width, height **36 dp**, `surfaceVariant` fill, `RoundedCornerShape(radiusMd)`, 3 dp inner padding |
| Segment | Equal-width (`weight(1f)`), centered `caption`-sized label at 14 sp weight 600 |
| Selected thumb | `accent` fill, `RoundedCornerShape(radiusSm)`, animated `offset` + `width` with the segmented spring, drawn **behind** the labels |
| Selected label | `#FFFFFF` |
| Unselected label | `onSurfaceSecondary` |
| Disabled segment | label at `onSurfaceTertiary`, non-interactive; used when a specific option is unsupported by the host (e.g. "Native Touch" on an NVIDIA host) |
| Overflow | With more than 4 segments, or when labels do not fit, the control switches to a **horizontally scrollable** row with the same visuals; it never wraps to two lines |
| Haptics | `HapticFeedbackType.ContextClick` on selection change |

If **every** segment would be disabled, disable the whole row and explain in the info text.

### 4.6 Component: `ToggleRow`

```
  HDR                                              ( ●)  (i)
```

| Element | Spec |
|---|---|
| Switch | M3 `Switch` with colors overridden: checked track `accent`, checked thumb `#FFFFFF`, unchecked track `divider` (light) / `surfaceVariant` (dark), unchecked thumb `#FFFFFF`, and **no icon in the thumb** (M3's checkmark thumb is too Material) |
| Placement | Trailing, before the info button |
| Row tap | Tapping anywhere on the row toggles the switch (except the info button) |
| Value text | None — the switch *is* the value |
| Haptics | `HapticFeedbackType.ToggleOn` / `ToggleOff` |

### 4.7 Component: `StepperRow` and `NavigationRow`

* **`StepperRow`** — label, value in `accent`, then a `−`/`+` pair in a 32 dp `surfaceVariant`
  rounded (`radiusSm`) pill. Used for controller slot counts and similar small integers.
* **`NavigationRow`** — label, optional summary value in `accent`, trailing 20 dp
  `chevron_right` in `onSurfaceTertiary`. Opens a sub-screen (Resolution picker, Gesture
  editor, Diagnostics).

### 4.8 Section contents

Rows are listed in the exact order they must render. "Info" column summarizes the required
help text; the coder writes the full sentence.

#### Video — glyph `videocam`

| Row | Type | Values | Notes / info text must cover |
|---|---|---|---|
| Bitrate | Slider | 0.5–150 Mbps default range; **extended to 500 Mbps** when the user enables "Unlock high bitrate" in the overflow menu | What bitrate costs on Wi‑Fi; that above ~150 Mbps most decoders stall |
| Resolution | Navigation | 720p / 1080p / 1440p / 4K / Native / Custom | Explains SOPS clamping on NVIDIA hosts (spec 01 §3.6) |
| Frame Rate | Segmented | 30 / 60 / 90 / 120 | Options above the display's refresh rate are disabled |
| Preferred Codec | Segmented | **H.264 \| Auto** (plus HEVC and AV1 as additional segments when probed OK) | What Auto picks and why; that AV1 hardware decode is unreliable on many devices |
| HDR | Toggle | on/off | Requires a 10-bit codec, an HDR display, and an HDR-capable app; disabled with reason when any is missing |
| YUV 4:4:4 | Toggle | on/off | Sharper text; **Sunshine/Apollo only**; costs bandwidth; disabled on GFE and on decoders without a 4:4:4 profile |
| Optimize Game Settings | Toggle | on/off | The `sops` flag: lets the host change in-game resolution |
| Show Stats Overlay | Toggle | on/off | Draws the live stats chip during streaming |

#### Audio — glyph `volume_up`

| Row | Type | Values | Notes |
|---|---|---|---|
| Channels | Segmented | Stereo \| 5.1 \| 7.1 | **5.1/7.1 disabled in v1** with info text saying surround decoding is not yet supported (spec 01 §8.5) |
| Play Audio on PC | Toggle | on/off | Also plays through the host's speakers |

#### Touch & Controller — glyph `sports_esports`

| Row | Type | Values | Notes |
|---|---|---|---|
| Touch Mode | Segmented | **Touchpad \| Native Touch \| Absolute Touch** | Touchpad = relative mouse; Native Touch = real multi-touch passthrough (**Sunshine only**, segment disabled on GFE); Absolute Touch = the screen maps 1:1 to the desktop |
| Enable On-Screen Widget & Peripherals | Toggle | on/off | Master switch for the virtual gamepad and on-screen keyboard/trackpad buttons |
| Divider Position | Slider | 0–100, label `"\| 50% \| 50% \|"` | Where the screen splits into two independent touch regions (left/right touchpads); only enabled when the mode uses a split |
| Touch Pointer Velocity | Slider | 25–400%, label `"100%"` | Pointer speed multiplier in Touchpad mode |
| On-Screen Widgets | Segmented | **Off \| Simple \| Full \| Custom** | Custom is **disabled in v1** with info text saying the layout editor is coming |
| Swap A/B X/Y Buttons | Toggle | on/off | Nintendo-style face-button layout |
| Emulated Controller Type | Segmented | **Xbox 360 \| DS4 \| Both** | What the host emulates; DS4 enables gyro/touchpad passthrough; **Sunshine only** |
| Gyro Mode | Segmented | **Off \| Auto \| Built-in \| Controller** | Auto prefers a connected controller's gyro and falls back to the device's; requires DS4 emulation on the host |
| Gyro Sensitivity | Slider | 25–400%, label `"100%"` | Only enabled when Gyro Mode ≠ Off |
| Rumble | Toggle | on/off | Routes host rumble to the controller, or the phone if the controller has no motor |

#### Gestures — glyph `gesture`

| Row | Type | Values | Notes |
|---|---|---|---|
| Exit Gesture | Segmented | 3-finger \| 4-finger swipe | Which gesture leaves the stream |
| Exit Swipe Distance | Slider | 40–400 dp | How far the swipe must travel before it counts |
| Tap to Click | Toggle | on/off | Single-finger tap = left click in Touchpad mode |
| Two-Finger Tap = Right Click | Toggle | on/off | |
| Three-Finger Tap = Middle Click | Toggle | on/off | |
| Edge Swipe Opens Settings | Toggle | on/off | Left-edge swipe reveals the in-stream settings drawer |

#### Display — glyph `cast`

| Row | Type | Values | Notes |
|---|---|---|---|
| External Display Mode | Segmented | Mirror \| Presentation | **Entire section disabled in v1** (spec 00 §4 non-goal). Info text states plainly that external-display streaming is not implemented yet. The section is still shown so the user can see it exists and is not hidden. |

*(The iPad reference's "Stage Manager | AirPlay (mirroring)" maps to Android's
"Presentation | Mirror". Same idea, native terminology.)*

### 4.9 Per-host overrides

* Reachable from the host card's long-press menu ("Host settings…") and from the app-grid
  sidebar toggle when a host is selected.
* Visually identical to the global panel, with:
  * A pinned header chip beneath "Settings": `surfaceVariant` fill, `radiusMd`,
    "Overrides for **Gaming PC**" in `caption`, with a trailing "Reset all" text button in
    `accent`.
  * Any row with a per-host override shows a **4 dp `accent` bar** at its start edge (inset
    `space1`, full row height, `radiusFull`) and adds a "Reset" text action inside the row's
    info expansion.
* Changing a row here writes an override; long-pressing a row offers "Reset to global".

### 4.10 Favorites

The reference has "Add most used setting items to favorite". Ours:

* The overflow menu has "Add to favorites…", which enters a selection mode: every row grows a
  leading 24 dp star outline toggle.
* Favorited rows are duplicated into a **"Favorites"** section pinned to the top of the panel,
  above "Video", with the same row instances (same state, same behaviour).
* The favorites section is hidden entirely when empty.

---

## 5. Stream view

### 5.1 Layer stack

```
z0   SurfaceView                       — the decoded video, letterboxed
z1   Touch input surface               — full-bleed, invisible, routes to InputSink
z2   On-screen controls                — virtual gamepad widgets (when enabled)
z3   Overlay chrome                    — stats chip, connection warning, toasts
z4   In-stream settings drawer         — slides in from the start edge
z5   Modal dialogs                     — disconnect confirm, error
```

`SurfaceView` sizing: preserve the stream's aspect ratio, letterbox with **pure black**
(`#000000`) regardless of theme. Set `holder.setFixedSize(streamWidth, streamHeight)`.

### 5.2 Overlay chrome

**Stats chip** (only when "Show Stats Overlay" is on): top-start, `space4` inset from the
safe area, `#000000` @ 55% fill, `radiusMd`, `space3` padding, `mono` text:

```
1920×1080  59.8 fps   24.3 Mbps
decode 3.1 ms   rtt 8 ms   loss 0.0%
```

Updates at 2 Hz, not per frame.

**Connection warning:** when quality is POOR, a top-center pill slides down —
`warning` fill @ 90%, `#1C1C1E` text, `radiusFull`, `space3`/`space2` padding, 16 dp
`warning` glyph + "Poor connection". Auto-hides 3 s after quality recovers.

**Toasts:** bottom-center, `#000000` @ 75%, `radiusMd`, `body` in white, 2 s. Used for
"Controller 1 connected", "Bitrate set to 30 Mbps", "Keyboard shown".

All overlay chrome is `pointerInput`-transparent except for actual controls.

### 5.3 In-stream settings drawer

Same components as the main settings panel, but:

* Presented as a **340 dp drawer from the start edge**, over a 30% scrim.
* Fill: `surface` at 96% opacity with a `blur(24.dp)` backdrop on API 31+ (fall back to opaque
  `surface` below 31).
* Opened by: the edge-swipe gesture (if enabled), a 3-finger tap, or the overlay's settings
  button (a 40 dp circular `#000000` @ 45% button at the top-end corner, visible for 3 s after
  any overlay interaction, then fading to 25% opacity).
* Contains a **reduced** row set — only what is safe to change live:
  Bitrate, Frame Rate (requires reconnect — shown with a "Reconnect required" chip),
  Touch Mode, On-Screen Widgets, Touch Pointer Velocity, Gyro Mode, Gyro Sensitivity,
  Rumble, Show Stats Overlay, plus the Favorites section.
* Pinned at the bottom of the drawer: a full-width `danger`-tinted "Disconnect" button
  (`radiusMd`, `danger` @ 12% fill, `danger` text) and a secondary "Quit game on PC" text
  button, also `danger`.

Rows that cannot apply live are **not hidden** — they show a "Reconnect required" chip
(`radiusFull`, `warning` @ 18% fill, `caption`, `warning` text) and, on change, prompt
"Reconnect now?" with Cancel / Reconnect.

### 5.4 Touch input surface

Behaviour per `touchMode`:

| Mode | Behaviour |
|---|---|
| **Touchpad** | Single finger drag ⇒ relative mouse move scaled by `touchPointerVelocityPercent`. Tap ⇒ left click (if enabled). Two-finger drag ⇒ scroll. Two-finger tap ⇒ right click. Three-finger tap ⇒ middle click. Drag-after-tap ("tap-and-a-half") ⇒ left button held during the drag. |
| **Native Touch** | Every pointer maps directly to `InputSink.touch(...)` with normalized coordinates and its stable `pointerId`. Up to **10 simultaneous pointers**. No gesture interpretation at all except the exit gesture. Sunshine only. |
| **Absolute Touch** | Finger position maps 1:1 to the host cursor via `mouseMoveAbsolute`, using the video dimensions as the reference frame. Down ⇒ move-then-left-down; up ⇒ left-up. |

**Divider position** splits the surface into two independent regions at
`dividerPositionPercent` when the mode is a split touchpad configuration; each side tracks its
own pointer, and the label renders as `| 50% | 50% |` to show both proportions.

**Exit gesture:** N fingers (3 or 4) swiping **down** more than `exitSwipeDistanceDp` shows the
disconnect confirmation. During the swipe, a translucent sheet follows the fingers so the
gesture is discoverable and cancellable by reversing. This gesture must be recognized **before**
pointers are forwarded in Native Touch mode: buffer the first ~80 ms of an N-finger contact,
and if it does not become an exit swipe, replay the buffered pointers to the host.

**Focus loss** (app backgrounded, dialog opened) ⇒ send `LI_TOUCH_EVENT_CANCEL_ALL` and
release every held mouse/controller button. Stuck-key bugs are unforgivable here.

### 5.5 On-screen controls

Three presets (`WidgetSet`), all drawn as vector shapes with `#FFFFFF` @ 22% fill and
`#FFFFFF` @ 55% stroke (1.5 dp), rising to 45% / 90% while pressed, over `motionFast`:

| Preset | Contents |
|---|---|
| **Off** | Nothing drawn |
| **Simple** | Left virtual stick (96 dp active radius), D-pad, A/B/X/Y cluster (56 dp buttons), Start, Select |
| **Full** | Simple, plus right virtual stick, LB/RB (72 × 40 dp), LT/RT (analog, 72 × 48 dp, reporting 0–255 by press travel), Guide, and a keyboard-toggle button |

Layout: anchored to the safe-area insets, mirrored per handedness, positions defined in a
data-driven `OnScreenControlLayout` (list of `WidgetSpec(id, kind, anchor, offsetDp, sizeDp)`).
Making it data-driven now is what makes the v2 layout editor cheap.

* Virtual sticks are **floating**: the stick recenters at the touch-down point within its
  active zone, which is far better than a fixed nub.
* Buttons support multi-touch and slide-off-to-release.
* Widgets fade to 35% opacity after 4 s without interaction and return to full on touch.
* When a **physical controller is connected**, on-screen widgets auto-hide (unless the user
  has explicitly forced them on), and a toast announces it.

### 5.6 Keyboard

A keyboard-toggle button shows the soft keyboard over the stream. When shown:

* The stream **lifts** (translates up) by the IME height so the game is not hidden behind the
  keyboard — this is the "stream view lifting" behaviour the reference is known for.
  Implement with `WindowInsets.ime` and an animated `offset`.
* A slim toolbar (44 dp, `#000000` @ 70%) sits directly above the IME with modifier toggles
  (Ctrl, Alt, Shift, Win), Esc, Tab, and the F-key row behind a chevron. Modifiers are
  **sticky-latching**: tap to latch for the next key, double-tap to lock.
* Characters go through `InputSink.text(...)` where possible and through
  `InputSink.key(vk, ...)` for non-character keys.

---

## 6. Cross-cutting UI rules

1. **No Material ripples anywhere.** The reference has none. Use `indication = null` plus
   explicit scale/overlay press states. Define one `voidClickable()` modifier and use it
   everywhere so this is consistent by construction.
2. **Disabled ≠ hidden.** A capability the host or device lacks is shown, disabled, with the
   info button explaining why. The user should never wonder where a feature went.
3. **Values are blue and right-aligned.** This is the single most identifiable trait of the
   reference's settings; do not let it drift.
4. **Every destructive action confirms**, and confirmation dialogs put the destructive button
   in `danger` on the **end** side.
5. **Insets:** every screen consumes `WindowInsets.safeDrawing`; the stream view consumes
   nothing and draws edge-to-edge, with overlay chrome inset by `safeDrawing` manually.
6. **Text scaling:** all sizes in `sp`; layouts must survive a 1.3× font scale without
   clipping. The settings rows are the risk — they must grow vertically, never truncate the
   label.
7. **RTL:** use `start`/`end`, never `left`/`right`. The on-screen controls mirror
   automatically; the segmented-control thumb animation must respect layout direction.
8. **Minimum touch target 48 dp** on every interactive element, achieved with padding rather
   than by growing the visual.
9. **Accessibility:** every icon-only button has a `contentDescription`. Sliders expose
   `progressBarRangeInfo` and support accessibility drag actions. The stream surface is
   marked `invisibleToUser` for TalkBack (it cannot be meaningfully described), while the
   overlay controls are labeled.
10. **Preview coverage:** every component file ships `@Preview` composables for light and
    dark, plus a disabled variant. Screenshot tests hang off these.
