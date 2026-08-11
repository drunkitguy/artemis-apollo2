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
| Trailing | Overflow `VoidLinkIcons.Overflow` (`MoreVert`) icon button — menu: **"Reset all settings"**, **"Diagnostics"**, and — only once §4.10 is built — "Add to favorites…" |

The overflow menu contains nothing else. In particular there is **no "Unlock high bitrate"**
entry: the 150 Mbps ceiling is enforced in the model and is not user-liftable (§4.8).
"Reset section to defaults" is not offered either — `SettingsRepository.resetToDefaults()`
resets the whole object, and a per-section reset would need field grouping the model does not
express.

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

### 4.7 Component: `PickerRow`

* **`PickerRow`** — label, current value in `accent`, trailing 20 dp `ChevronRight` in
  `onSurfaceTertiary`. Opens an inline or dialog picker for enums with too many options to fit
  a segmented control. Used for the two `GestureAction` rows.
* There is **no `StepperRow` and no `NavigationRow`** — nothing in the shipped settings model
  needs a stepper, and every option set is either a segment row or a `PickerRow`. Do not add
  them speculatively.

### 4.8 Section contents

**These rows are exactly the fields of `data/StreamSettings.kt`, in render order.** The mapping
is one-to-one and must stay that way: a row with no field cannot persist, and a field with no
row cannot be changed. Where a setting is local-only or reaches the wire, see
`02-ARCHITECTURE.md` §6.1.

"Info" summarizes what the help text must cover; the coder writes the full sentence.

#### Video — `VoidLinkIcons.Video`

| Row | Type | Field | Values | Info text must cover |
|---|---|---|---|---|
| Bitrate | Slider | `bitrateKbps` | 0.5–150 Mbps, label `"23.0 Mbps"` | What bitrate costs on Wi‑Fi; that above ~150 Mbps most decoders stall; **that a change takes effect on the next connection, not the current one** |
| Preferred Codec | Segmented | `codec` | **H.264 \| HEVC \| AV1 \| Auto** | What Auto picks and why; that AV1 hardware decode is unreliable on many devices |
| HDR | Toggle | `hdrEnabled` | on/off | Requires a 10-bit codec, an HDR display, and an HDR-capable app; disabled with reason when any is missing |
| YUV 4:4:4 | Toggle | `yuv444Enabled` | on/off | Sharper text; **Sunshine/Apollo only**; costs bandwidth; disabled on GFE and on decoders without a 4:4:4 profile |
| Resolution | Segmented | `resolution` | **720p \| 1080p \| 1440p \| 4K \| Native** | Native means the device's own display size, resolved at launch; explains SOPS clamping on NVIDIA hosts (spec 01 §3.6) |
| FPS | Segmented | `frameRate` | **30 \| 60 \| 90 \| 120** | Options above the display's refresh rate are disabled |
| Optimize Game Settings | Toggle | `optimizeGameSettings` | on/off | The host's `sops` flag: lets the host rewrite in-game graphics settings to match the stream. On by default. Explains the NVIDIA clamp (spec 01 §3.6): on a GFE host at a non-standard resolution we send `sops=0` regardless of this setting |
| Show Stats Overlay | Toggle | `showStatsOverlay` | on/off | Draws the live bitrate/latency chip over the stream |

**Bitrate range is 500–150 000 kbps and there is no "unlock" affordance.** The 500 Mbps ceiling
mentioned in the VoidLink feature list is not a goal: `StreamSettings.BITRATE_MAX_KBPS` is
150 000, `coerced()` enforces it on both read and write, and no UI or menu path raises it.

**Resolution is a segmented control, not a sub-screen.** There is no resolution picker screen,
no "Custom" option, and no file for one.

#### Touch & Controller — `VoidLinkIcons.Touch`

| Row | Type | Field | Values | Info text must cover |
|---|---|---|---|---|
| Touch Mode | Segmented | `touchMode` | **Touchpad \| Native Touch \| Absolute Touch** | Touchpad = relative mouse; Native Touch = real multi-touch passthrough (**Sunshine only**, segment disabled on GFE); Absolute Touch = the screen maps 1:1 to the desktop |
| Enable On-Screen Widget & Peripherals | Toggle | `onScreenWidgetEnabled` | on/off | Master switch for the virtual gamepad and on-screen peripherals |
| Divider Position | Slider | `dividerPositionPercent` | 10–90, label `"\| 50% \| 50% \|"` | Where the screen splits into two independent touch regions; only enabled when the mode uses a split |
| Touch Pointer Velocity | Slider | `touchPointerVelocityPercent` | 25–300%, label `"100%"` | Pointer speed multiplier in Touchpad mode |
| On-Screen Widgets | Segmented | `onScreenWidgets` | **Off \| Simple \| Full \| Custom** | Custom is **disabled in v1** with info text saying the layout editor is not built yet |
| Swap A/B X/Y Buttons | Toggle | `swapFaceButtons` | on/off | Nintendo-style face-button layout |
| Emulated Controller Type | Segmented | `emulatedControllerType` | **Xbox 360 \| DS4 \| Both** | Which pad the host emulates. DS4 enables gyro/touchpad passthrough. **"Both" means "don't force a type" — each connected pad is reported as what it actually is** (`02-ARCHITECTURE.md` §6.2). **Sunshine only**; disabled on GFE |
| Gyro Mode | Segmented | `gyroMode` | **Off \| Auto \| Built-in \| Controller** | Auto prefers a connected controller's gyro and falls back to the device's; requires DS4 emulation on the host |
| Gyro Sensitivity | Slider | `gyroSensitivityPercent` | 25–300%, label `"100%"` | Only enabled when Gyro Mode ≠ Off |
| Rumble | Toggle | `rumbleEnabled` | on/off | Routes the host's force feedback to the controller, or to this device when the controller has no motors |

Slider ranges are 25–300%, not 25–400% — `VELOCITY_MAX_PERCENT` is 300 and `coerced()`
enforces it.

#### Gestures — `VoidLinkIcons.Gestures`

Gestures are **bindings**, not fixed behaviours: two recognizers, each with an on/off toggle
and a bound action.

| Row | Type | Field | Values | Info text must cover |
|---|---|---|---|---|
| Three-Finger Tap | Toggle | `threeFingerTapEnabled` | on/off | Whether the gesture is recognized at all |
| Three-Finger Tap Action | Picker | `threeFingerTapAction` | Nothing \| Toggle Keyboard \| Toggle Settings \| Toggle Widgets \| **Disconnect** | What the gesture does; disabled when the toggle above is off |
| Edge Swipe | Toggle | `edgeSwipeEnabled` | on/off | Swipe in from the screen edge |
| Edge Swipe Action | Picker | `edgeSwipeAction` | same `GestureAction` set | Disabled when the toggle above is off |

**There is no separate "Exit Gesture" or "Exit Swipe Distance" row, and no tap-to-click /
two-finger / three-finger click rows.** Leaving the stream is the `DISCONNECT` action bound to
either recognizer. Touchpad click behaviour (tap = left click, two-finger tap = right click)
is **fixed behaviour of Touchpad mode**, not configurable — see §5.4.

#### Peripherals — `VoidLinkIcons.Peripherals`

| Row | Type | Field | Values | Info text must cover |
|---|---|---|---|---|
| External Display Mode | Segmented | `externalDisplayMode` | **Mirror \| Separate Display** | **Disabled in v1** (`00-OVERVIEW.md` §4.6 non-goal). Info text states plainly that external-display streaming is not implemented yet. The row stays visible so the feature is not silently missing. |
| Capture Mouse | Toggle | `captureMouse` | on/off | Grabs a physical mouse for raw relative input while streaming |
| Forward Keyboard | Toggle | `forwardKeyboard` | on/off | Sends physical keyboard input, including modifier chords, to the host |

*(The iPad reference's "Stage Manager | AirPlay (mirroring)" maps to
"Separate Display | Mirror". Same idea, native terminology.)*

#### Audio — `VoidLinkIcons.Audio`

| Row | Type | Field | Values | Info text must cover |
|---|---|---|---|---|
| Surround Sound | Segmented | `surroundMode` | **Stereo \| 5.1 \| 7.1** | **5.1/7.1 disabled in v1** with info text saying surround decoding is not yet supported (spec 01 §8.5) |
| Mute Host Audio | Toggle | `muteHostAudio` | on/off | Keeps the PC's own speakers silent while streaming. Note the **inverted** sense versus the protocol's `localAudioPlayMode` |

#### Rows that do not exist, and why

These appeared in earlier drafts of this spec with no backing field. They are **not** to be
built:

| Removed row | Resolution |
|---|---|
| Play Audio on PC | Superseded by **Mute Host Audio**, which is the same knob inverted |
| Unlock high bitrate | Does not exist; the ceiling is 150 Mbps, full stop |
| Exit Gesture / Exit Swipe Distance | Replaced by the `DISCONNECT` gesture action |
| Tap to Click / Two-Finger / Three-Finger click | Fixed behaviour of Touchpad mode |
| Frame queue depth | Was never a user setting; the decode queue is fixed at 2 (`02-ARCHITECTURE.md` §3) |

### 4.9 Per-host overrides

**The shipped model is whole-object override** (`KnownHost.settingsOverride: StreamSettings?`),
not per-field. The UI must be honest about what that means.

* Reachable from the host card's long-press menu ("Host settings…") and from the apps-screen
  sidebar toggle when a host is selected.
* Visually identical to the global panel, with a pinned header chip beneath "Settings":
  `surfaceVariant` fill, `radiusMd`, "Using custom settings for **Gaming PC**" in `caption`,
  and a trailing "Use global settings" text button in `accent` that clears the override.
* **Every row is overridden or none is.** Do not draw a per-row "overridden" marker — it would
  imply per-field inheritance that does not exist. Instead, the header chip carries the whole
  story, and its info affordance explains: *once a host has custom settings, later changes to
  the global settings no longer reach it.*
* Editing any row while no override exists **creates** one, seeded from a copy of the current
  effective settings, so it is never half-populated. A confirmation is not required, but the
  header chip must appear immediately so the switch is visible.
* "Use global settings" is destructive (it discards the custom values) and confirms.

### 4.10 Favorites — **not built; deferred**

The reference has "Add most used setting items to favorite". This is **not in the shipped
settings panel** and has no backing field: `StreamSettings` stores no favorites list, and
adding one is a model change, not a UI change.

When it is built, the design is:

* The overflow menu gains "Add to favorites…", which enters a selection mode: every row grows a
  leading 24 dp star outline toggle.
* Favorited rows are duplicated into a **"Favorites"** section pinned to the top of the panel,
  above "Video", with the same row instances (same state, same behaviour).
* The favorites section is hidden entirely when empty.
* Persistence: a `Set<String>` of row keys — **UI state, not stream configuration**, so it
  belongs in its own DataStore key, not inside `StreamSettings` (which per-host overrides copy
  wholesale; favorites must not vary per host).

Until then, the overflow menu omits the entry entirely rather than showing it disabled — an
absent menu item is not a missing feature the user can see.

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

**Stats chip** (shown when `showStatsOverlay` is on — §4.8): top-start, `space4` inset from the
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
"Controller 1 connected", "Stats shown", "Keyboard shown".

**Stats chip visibility is the persisted `showStatsOverlay` setting**, reachable both from the
main settings panel (§4.8, Video) and from the in-stream drawer. Tapping the chip itself also
dismisses it, which writes the setting off — a dismissal the user expects to stick.

All overlay chrome is `pointerInput`-transparent except for actual controls.

### 5.3 In-stream settings drawer

Same components as the main settings panel, but:

* Presented as a **340 dp drawer from the start edge**, over a 30% scrim.
* Fill: `surface` at 96% opacity with a `blur(24.dp)` backdrop on API 31+ (fall back to opaque
  `surface` below 31).
* Opened by: the `TOGGLE_SETTINGS` gesture action bound to the edge swipe or three-finger tap
  (§4.8), or the overlay's settings button (a 40 dp circular `#000000` @ 45% button at the
  top-end corner, visible for 3 s after any overlay interaction, then fading to 25% opacity).
* Contains a reduced row set:
  * **Apply live:** Touch Mode, On-Screen Widgets, Touch Pointer Velocity, Gyro Mode,
    Gyro Sensitivity, Divider Position, Swap A/B X/Y, Rumble, Show Stats Overlay. These only
    change how *we* synthesize packets or draw chrome, so they take effect immediately.
  * **Reconnect required:** **Bitrate**, Resolution, FPS, Preferred Codec, HDR, YUV 4:4:4,
    Surround Sound, Optimize Game Settings.
  * Plus **Show Stats Overlay** and **Rumble**, which apply live and are persisted.
* Pinned at the bottom of the drawer: a full-width `danger`-tinted "Disconnect" button
  (`radiusMd`, `danger` @ 12% fill, `danger` text) and a secondary "Quit game on PC" text
  button, also `danger`.

**Bitrate cannot be changed live, and the UI must not pretend otherwise.** Every bitrate value
is fixed at ANNOUNCE time by the SDP attributes `initialBitrateKbps` /
`bw.minimumBitrateKbps` / `bw.maximumBitrateKbps`, which we deliberately set equal to disable
the host's adaptive bitrate (spec 01 §6.4); there is no client→host bitrate message anywhere in
the control protocol (spec 01 §9.3–9.5). Bitrate therefore carries the same **"Reconnect
required"** chip as Frame Rate.

Rows that cannot apply live are **not hidden** — they show a "Reconnect required" chip
(`radiusFull`, `warning` @ 18% fill, `caption`, `warning` text) and, on change, prompt
"Reconnect now?" with Cancel / Reconnect. The new value is persisted either way; declining the
prompt just means it applies next time.

### 5.4 Touch input surface

Behaviour per `touchMode`:

| Mode | Behaviour |
|---|---|
| **Touchpad** | Single finger drag ⇒ relative mouse move scaled by `touchPointerVelocityPercent`. Tap ⇒ left click. Two-finger drag ⇒ scroll. Two-finger tap ⇒ right click. Drag-after-tap ("tap-and-a-half") ⇒ left button held during the drag. **These are fixed behaviours, not settings.** |
| **Native Touch** | Every pointer maps directly to `InputSink.touch(...)` with normalized coordinates and its stable `pointerId`. Up to **10 simultaneous pointers**. No gesture interpretation at all except the bound gestures below. Sunshine only. |
| **Absolute Touch** | Finger position maps 1:1 to the host cursor via `mouseMoveAbsolute`, using the video dimensions as the reference frame. Down ⇒ move-then-left-down; up ⇒ left-up. |

**Divider position** splits the surface into two independent regions at
`dividerPositionPercent` when the mode is a split touchpad configuration; each side tracks its
own pointer, and the label renders as `| 50% | 50% |` to show both proportions.

**Bound gestures.** There are exactly two recognizers, each independently enabled and each
bound to a `GestureAction` (§4.8): a **three-finger tap** and an **edge swipe** (a drag
starting within 20 dp of the start edge and travelling ≥ 48 dp inward). Leaving the stream is
`DISCONNECT` bound to either one; it shows the disconnect confirmation rather than acting
immediately.

Both must be recognized **before** pointers are forwarded in Native Touch mode: buffer the
first ~80 ms of a three-finger contact or an edge-originating contact, and if it does not
resolve into the gesture, replay the buffered pointers to the host in order. A recognizer whose
toggle is off does no buffering at all — a user who turns both off gets completely unfiltered
touch, which is the point of Native Touch.

**Focus loss** (app backgrounded, dialog opened) ⇒ send `LI_TOUCH_EVENT_CANCEL_ALL` and
release every held mouse/controller button. Stuck-key bugs are unforgivable here.

### 5.5 On-screen controls

Three built presets from `OnScreenWidgetPreset` (the fourth, `CUSTOM`, is disabled — §4.8),
all drawn as vector shapes with `#FFFFFF` @ 22% fill and
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
* The whole keyboard affordance is gated on `forwardKeyboard` (§4.8); when it is off, the
  toggle button is not drawn.

**Rumble** is gated on `rumbleEnabled` (§4.8). When it is on and the host sends a rumble
message (spec 01 §9.6), it plays on the physical controller's motor if it has one, otherwise on
the device vibrator. There is no on-screen indicator.

### 5.7 Orientation and portrait streaming

`StreamActivity` is declared `android:screenOrientation="fullUser"`, so **portrait streaming is
supported** and the device's rotation lock is respected. What rotation does and does not change:

* **The negotiated stream dimensions never change on rotation.** Resolution and frame rate are
  fixed at `/launch` and ANNOUNCE (spec 01 §3.6, §6.4); there is no renegotiation path, and
  building one is out of scope. A 1920×1080 stream stays 1920×1080 whichever way the device is
  held.
* **The surface re-letterboxes.** In portrait, a landscape stream occupies a horizontal band
  centered vertically, with pure black (`#000000`) above and below. `SurfaceView` keeps
  `holder.setFixedSize(streamWidth, streamHeight)`; only the view bounds change.
* **Overlay chrome re-anchors** to the new safe-area insets: the stats chip stays top-start,
  the connection pill top-center, toasts bottom-center.
* **On-screen controls reflow, they do not scale.** In portrait, widgets anchor to the bottom
  band **below** the letterboxed video rather than overlapping it — which is the main reason
  portrait is worth supporting at all, since nothing occludes the game. Widget sizes stay
  identical; only anchors move. This falls out of the data-driven `WidgetSpec` layout (§5.5) by
  supplying a second anchor set, not a second layout engine.
* **Touch coordinate mapping uses the video rectangle, not the view.** Normalized coordinates
  for Native Touch and the reference frame for Absolute Touch are computed against the
  letterboxed video rect; touches in the black bands are outside the stream and are **dropped**
  in Native/Absolute modes, and treated as ordinary touchpad surface in Touchpad mode.
* **Rotation must not restart the session.** `configChanges` already includes `orientation` and
  `screenSize` (`02-ARCHITECTURE.md` §8), so the Activity is not recreated and the decoder is
  never reconfigured.

Portrait is a **layout** feature only. It adds no protocol work and no new settings.

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
