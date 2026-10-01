# Oikos — Device icons: drawn, state-aware, animated

Status: approved design, 2026-10-01. Parent spec: `2026-09-24-oikos-design.md` §5.1 (design system) and §5.2 (Home cards). It replaces the emoji glyphs of `KindGlyphs.kt` ("until the real icon set lands"). The visuals were chosen in the brainstorm companion; the final motion mockup is `docs/design/mockups/device-icons-motion.html`.

## 1. Goal

Every Home card shows a drawn icon in one consistent **duotone** style (an outline plus a soft tinted fill) instead of an emoji. Three kinds show their real state and animate:

1. **Shutters** (`Kind.COVER`): a roller box over a slat curtain whose level is the real position.
2. **Doors** (binary sensors `door`, `opening`): the leaf swings open on its hinge, with its lever handle attached.
3. **Windows** (binary sensor `window`): the left sash swings **inward**, toward the viewer, like a French window.

Garage doors (`garage_door`) reuse the shutter curtain inside a garage outline. Every other kind gets a static duotone icon.

Out of scope: the `mdi:` icon override (parent spec §4.3, `UiOverride.icon`), icons in the control sheet, tilt for covers, and any change to models, state mapping or transport.

## 2. Visual rules

All icons are drawn on a **24 × 24 grid** and rendered at 24 dp on cards.

| Token | Value |
|---|---|
| Stroke | 1.6 units, round caps and joins |
| Surface fill | the stroke color at alpha 0.22 |
| Roller box fill | alpha 0.35 |
| Fixed (non-moving) glass | alpha 0.10 |
| Neutral color | `LocalContentColor` (so on-cards, dimmed cards and both themes work unchanged) |
| Open accent | `OikosTheme.colors.warning` (amber in Night Glass, `0xFF8A5A00` in Warm Minimal) |
| Closed accent | `OikosTheme.colors.connected` (mint / `0xFF3F6B3A`) |

- **Doors, windows, garage doors:** the moving part is filled with the open accent when open and the closed accent when closed. Unknown state (no value yet) uses the neutral color, drawn closed.
- **Shutters:** no accent. The curtain level is the state, and the card keeps its existing "on" style when open (`HomeUiBuilder.isOn`).
- **Lights and switches:** the bulb or plug surface fills with the open accent when on; neutral when off.
- **Card border for open contacts:** `OikosCard` gets an `accent: Boolean` parameter that draws the border in `warning` at alpha 0.35 (the "on" border without the on gradient). `EntityCard` sets it for door, window and garage-door contacts that are open.

### 2.1 Shutter geometry

- Frame: rounded rect `(3.5, 3)–(20.5, 21)`, radius 2.
- Roller box: the top band `y 3–6.5`, filled, drawn **above** the curtain so a raised curtain disappears into it.
- Opening: `(3.5, 6.5)–(20.5, 21)`, 14.5 high. The curtain is clipped to it.
- Curtain: drawn fully closed (fill over the whole opening, slat lines every 2.5 units at `y 9, 11.5, 14, 16.5, 19`, stroke 1, alpha 0.75, and a bottom rail at `y 20.6`, stroke 1.8), then **translated up** by `14.5 × openFraction`.
- `openFraction = position / 100`. Without a position: 1 for OPEN, 0 for CLOSED, the last drawn value for STOPPED/UNKNOWN (0 on first draw).

### 2.2 Door geometry

- Frame `M6 21 V3 h12 v18`, sill `M4 21 h16` (neutral).
- Leaf, four corners interpolated linearly by `openness` (0 closed → 1 open):
  closed `(6,3) (18,3) (18,21) (6,21)` → open `(6,3) (13.5,5.5) (13.5,21.5) (6,21)`.
- Lever handle, stroke 1.9, neutral, two points interpolated with the same `openness`:
  closed `(15.6,12.9)→(13.6,12.9)` → open `(12,14)→(10.75,13.77)`. Both ends are fixed affine positions on the leaf (80 % across, 55 % down), so the handle never leaves the leaf mid-swing (the bug of the first mockup).

### 2.3 Window geometry

- Frame: rect `(4,3)–(20,21)`, radius 1.5. Right pane: fixed glass `(12,3)–(20,21)` with a transom `M12 12 h8`.
- Left sash, interpolated by `openness`:
  closed `(4,3) (12,3) (12,21) (4,21)` → open `(4,3) (8.5,1) (8.5,23) (4,21)`. The free edge comes toward the viewer, so it grows past the frame (allowed: the grid has room above and below).

### 2.4 Garage door

- Outline `M3 21 V9 L12 4 L21 9 V21` (neutral). Opening `(6,11)–(18,21)`, a curtain like the shutter's (slats every 2.5 units, bottom rail), translated up by `10 × openness`, with a filled header band `y 9.5–11` drawn above it.
- Fully open or fully closed only (binary sensor), with the open/closed accent on the curtain.

### 2.5 Static icons

Same grid, stroke and fill rules: bulb (light), plug (switch), snowflake (climate), thermometer (temperature), drop (humidity, moisture), bolt (power, energy), trend line (other sensor), walking figure (motion, occupancy, presence), flame (smoke), shield (alarm panel), slider (number), list (select), play (button), dot (fallback, any unknown device class). The kind → icon table is the one `kindGlyph()` uses today; only the drawing changes.

## 3. Motion

| Change | Animation |
|---|---|
| Door / window / garage open ↔ closed | `openness` tween 500 ms, `CubicBezier(0.3, 0.7, 0.2, 1)`; the accent color crossfades over the same 500 ms |
| Shutter new position | `openFraction` tween 600 ms, `CubicBezier(0.45, 0, 0.55, 1)` |
| Shutter OPENING / CLOSING | drift toward 1 (opening) or 0 (closing) at a constant rate of a full travel in 8 s (linear), starting from the current drawn value. A position update while moving retargets with the 600 ms tween, then the drift resumes from there while the state is still moving. Reaching the end stop stops the drift |
| First composition | no animation: the icon draws its current state at once (no swing every time Home opens) |

- **Reduced motion:** when the system animator duration scale is 0 (`animationsEnabled()`, kept from `KindGlyphs.kt`), every value snaps to its target and the drift is off.
- Animations run in the icon's own composition (`animateFloatAsState` / `Animatable`). They never touch the card's layout or semantics, so a moving shutter does not re-measure the grid.

## 4. Components

### 4.1 `:core:designsystem` — `icons/`

- One file per drawing, each a pure composable of numbers and colors, drawn with `Canvas` on the 24-unit grid scaled to the given size:
  - `ShutterIcon(openFraction: Float, modifier)`
  - `DoorIcon(openness: Float, accent: Color?, modifier)`
  - `WindowIcon(openness: Float, accent: Color?, modifier)`
  - `GarageIcon(openness: Float, accent: Color?, modifier)`
  - `StaticIcon(glyph: OikosGlyph, accent: Color?, modifier)` where `OikosGlyph` is an enum of the §2.5 drawings.
- Shared helpers: `lerp` of points, the stroke/fill style, the 24-unit scale. No state, no animation, no knowledge of `Kind`.
- `@Preview` sheet `OikosIconsPreview.kt`: every icon at 0, 0.5 and 1, in both themes, plus an on-card row.

### 4.2 `:core:designsystem` — `OikosCard`

- New `accent: Boolean = false` parameter: border in `warning` at alpha 0.35 when true and `on` is false.

### 4.3 `:feature:home` — `ui/KindIcon.kt` (replaces `KindGlyphs.kt`)

- `iconSpec(card: CardUi): IconSpec`, pure and unit-tested. `IconSpec` is a sealed type:
  - `Shutter(target: Float?, moving: CoverState?)` (`target` null = no position and not OPEN/CLOSED),
  - `Swing(shape: SwingShape /* DOOR, WINDOW, GARAGE */, open: Boolean?)`,
  - `Static(glyph: OikosGlyph, on: Boolean)`.
  The device-class table moves here from `kindGlyph()`.
- `@Composable KindIcon(card, modifier)`: computes the spec, runs the §3 animations through `rememberIconProgress` (§6), calls the designsystem icon, and stays hidden from TalkBack (`clearAndSetSemantics {}`), as the glyph is today.
- `EntityCard` calls `KindIcon(card)` in place of `KindGlyph(...)` and passes `accent` to `OikosCard` (§4.2). The `moving` computation moves into `iconSpec`.

## 5. Error handling

- Out-of-range positions (below 0, above 100) are clamped before drawing.
- `null`, unknown and unavailable values draw the neutral closed state; an unavailable card keeps its existing dimming.
- An unknown device class falls back to the dot glyph (today's ⚪), never a crash.

## 6. Testing

- **Unit (`:feature:home`, `KindIconTest`):** `iconSpec` for each kind and device class; shutter targets for position set / null with OPEN, CLOSED, STOPPED, UNKNOWN; OPENING/CLOSING carried as `moving`; door, window, garage open/closed/unknown; clamping; fallback glyph.
- **Unit (`:core:designsystem`):** point interpolation at 0, 0.5, 1 for the leaf, handle and sash (the handle stays inside the leaf polygon at every step 0.1).
- **Compose UI (`:feature:home`, Robolectric):** the card's content description is unchanged and the icon adds no semantics node.
- **Animation driver:** the §3 logic lives in `rememberIconProgress(spec, animate: Boolean): State<Float>`, tested with the Compose test clock (`mainClock.autoAdvance = false`) by reading the returned value: first composition snaps, door tween ends at 500 ms, shutter drift covers 0.125 per second, a position update retargets, and `animate = false` snaps every change.
- **Previews:** the §4.1 sheet, checked by eye in both themes before merging.
