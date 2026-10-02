# Oikos — LED control: effects, speed, brightness and colour for any light

Status: approved design, 2026-10-02. Parent spec: `2026-09-24-oikos-design.md` §5.1 (Home, the `ControlSheet`) and §11 (phase 1). It is the phase 1 sub-project after Energy and before Security. The reference device is the bedroom strip `rooms/julien/leds`, a **ZigLED-1** (Gekkotron's Zigbee WS28xx controller on an ESP32-C6, repo `Gekkotron/ZigLED`) behind zigbee2mqtt 2.14.1. Nothing about that device is hard-coded: any Home Assistant–style MQTT light that announces effects or a colour mode gets the same controls (parent spec goal 7).

## 1. Goal

Tapping a light's card opens one sheet with everything the light can do:

- power and brightness (as today);
- the **effect**, picked from the light's `effect_list`;
- the **device's other controls** (speed, intensity, palette…), folded in from the number and select entities of the same device;
- the **colour**, on a hue wheel, whenever the light supports a colour mode.

Out of scope:
- Colour temperature (`color_temp` mode).
- Showing the effect or the colour on the card face.
- Translated effect names (they are tidied, not translated, §4.2).
- `occupancy_timeout` and other `entity_category: config` entities (still hidden, as today).
- Scenes, presets, saved favourites.

### 1.1 The reference device, as announced today

| Discovery topic | Kind | What Oikos does today |
|---|---|---|
| `light/0x1051…/light` | light, `schema: json`, `brightness_scale: 254`, `supported_color_modes: ["xy"]`, `effect: true`, `effect_list` of 9 | on/off + brightness only; effect and colour state ignored; colour sent as `{r,g,b}` |
| `number/…/effect_intensity` | number 0..255 | its own Home card |
| `number/…/palette` | number 0..7 (0 = commanded colour) | its own Home card |
| `number/…/active_count` | number 0..65535, `LEDs` | its own Home card |
| `binary_sensor/…/occupancy` | binary sensor | its own Home card (unchanged) |
| `effect_speed` | — | **not announced** (§1.2) |

State payload on `zigbee2mqtt/rooms/julien/leds`: `{"state":"OFF","brightness":0,"color":{"x":0.196,"y":0.203},"color_mode":"xy","effect":"fire_1d","effect_speed":38,"effect_intensity":39,"palette":1,"active_count":96,…}`.

Effects: `solid, breathe, comet, wipe, sparkle, rainbow, candle, fire_1d, plasma_2d`. In the firmware every effect samples the palette, and palette 0 returns the commanded colour, so colour matters for every effect, not only `solid`.

### 1.2 The missing speed: a converter fix, not an Oikos special case

The ZigLED converter (`z2m/gekkotron_zigled.mjs`) does declare `numeric({name: 'effect_speed', …})`. Z2M's Home Assistant extension has a built-in `NUMERIC_DISCOVERY_LOOKUP` entry for that name: `effect_speed: {enabled_by_default: false, entity_category: "config"}`. Oikos drops `config` and disabled-by-default entities (`DiscoveryParser.build`), so speed never appears.

Fix, in the ZigLED repo: the definition gains `meta.overrideHaDiscoveryPayload`, which, for the `effect_speed` number payload, deletes `entity_category` and sets `enabled_by_default: true`. The name, the firmware and the state payload are unchanged. After the user reloads the converter in Z2M, speed is announced like `effect_intensity` and Oikos treats it like any number. The converter change is committed in ZigLED; pushing and deploying it are the user's.

## 2. Model

All in `core:model`.

| Type | Change |
|---|---|
| `Capabilities` | `+ effects: List<String> = emptyList()` — the `effect_list`, in published order; empty when the light has no effects. |
| `StateValue.Light` | `+ effect: String? = null`, `+ color: Hs? = null` |
| `Hs` (new) | `data class Hs(val hue: Double, val saturation: Double)`; hue 0..<360, saturation 0..1, both checked in `init`. |
| `Command.TurnOn` | `+ effect: String? = null`; `color` becomes `Hs?` (was `Rgb?`). `Rgb` stays as the wire type inside `core:state`. |

Colour is carried as hue + saturation because that is what the wheel edits and what survives the round trip through any wire mode; brightness stays a separate channel (`brightness`), so the wheel never changes it.

## 3. MQTT mapping

### 3.1 Discovery (`DiscoveryParser`, light)

- JSON schema: `effects = effect_list` when `effect` is true, else empty.
- Default schema: `effects = effect_list` when `effect_command_topic` is set.
- `colorModes` unchanged (JSON: `supported_color_modes`; default: `rgb` when `rgb_command_topic` is set).

### 3.2 `ColorMath` (new, `core:state`, pure)

- `hsToRgb(Hs): Rgb` at full value; `rgbToHs(Rgb): Hs` (value dropped).
- `rgbToXy(Rgb): Pair<Double, Double>` and `xyToRgb(x, y): Rgb` with the sRGB D65 matrices and gamma, xy rounded to 4 decimals. Brightness is never folded in (Y = 1 when going back to RGB, then normalised to the largest channel).
- Round trips (hs → xy → hs) land within 1° of hue and 0.01 of saturation for saturations ≥ 0.2; below that the xy rounding dominates and hue is not asserted. The xy path works on doubles; only the rgb wire mode rounds to 0..255.

### 3.3 `LightMapper`, JSON schema

Decode, in addition to `state` and `brightness`:
- `effect`: a string, kept as is (even if it is not in `effects`; §4.2 handles that).
- `color`: `{x,y}` → `xyToRgb` → `rgbToHs`; `{h,s}` (s in 0..100) → `Hs`; `{r,g,b}` → `rgbToHs`. The first present form wins in that order. A payload without `color` keeps the previous colour.
- A payload with only `effect` or only `color` is a state update, no longer `Ignored`.

Encode `TurnOn`:
- `effect` → `"effect": "<name>"`.
- `color` in the light's mode, picked from `colorModes` in this order: `xy` → `{"x","y"}`, `hs` → `{"h": deg, "s": 0..100}`, `rgb`/`rgbw`/`rgbww` → `{"r","g","b"}`. No colour mode → the colour is dropped (the UI never offers it).
- `brightness` as today.

`predict` keeps the previous light and overlays what the command sets: `on`, `brightness`, `effect`, `color`.

### 3.4 `LightMapper`, default schema

- Effect: `effect_state_topic` (+ `effect_value_template`) decodes `effect`; `TurnOn(effect)` publishes the name on `effect_command_topic` (+ `effect_command_template`).
- Colour: `rgb_state_topic` (+ `rgb_value_template`), payload `r,g,b`, decodes `color`; `TurnOn(color)` publishes `r,g,b` on `rgb_command_topic` as today, from `hsToRgb`.
- `stateTopics` adds the two state topics when set.

## 4. Home

### 4.1 Folding a device's controls into its light (`LightAttachment`)

A new `LightAttachment.compute(entities)` in `core:source`, next to `SensorAttachment` and applied in the same place (`MqttDiscoverySource`, when entities are published):

- For each device with **exactly one** `LIGHT` entity, every `NUMBER` and `SELECT` entity of that device gets `partOf = <light uniqueId>`.
- Devices with two or more lights are left alone: which light a number belongs to is ambiguous.
- Other kinds (sensors, binary sensors, switches) are never folded; the strip's occupancy sensor keeps its card.
- If an entity would be claimed by both attachments, `SensorAttachment` wins (it only claims sensors, so this cannot happen today; the rule keeps the merge order-independent).

Entities with `partOf` are already excluded from the grid and search (`HomeUiBuilder.standalone`). The builder now also gives each light `CardUi.companions: List<CardUi>`: its `partOf` children of kind `NUMBER` or `SELECT`, sorted by display name, built like any card (state, availability, stale, pending). A companion's own `hidden` override is ignored: it shows in the sheet regardless. `HomeUiState.card(id)` also finds companions, so `send(id, …)` and the stale check work for them unchanged.

### 4.2 The light sheet

`LightControls` in `ControlSheet.kt`, top to bottom; a section only appears when the light has it:

1. **Brightness** — the existing slider (`capabilities.brightness`).
2. **Effect** (`capabilities.effects` not empty) — a wrapping row of filter chips, one per effect, the current `state.effect` selected. Labels are tidied: `_` → space, first letter upper-cased (`fire_1d` → "Fire 1d"). A state effect that is not in the list selects nothing. Tap → `TurnOn(effect = name)`.
3. **Companions** — each companion's existing control, reusing `NumberControls` (slider, sends `SetNumber` on release) and `SelectControls`, each under its display name and sent to the companion's id.
4. **Colour** (`capabilities.colorModes` not empty) — the hue wheel (§4.3). Release → `TurnOn(color = hs)`.

A section header uses the existing sheet label style. Availability is handled as today: the sheet does not disable controls, and the view model refuses sends on stale cards.

### 4.3 The hue wheel (`HueWheel`, `feature:home`)

- A square `Canvas`: a sweep gradient over the hue circle, under a radial gradient from white at the centre to transparent at the rim, so the centre is saturation 0 and the rim saturation 1.
- Hue 0° is at the top and increases clockwise; saturation is the distance from the centre over the radius, clamped to 1.
- A ring marker sits at the current colour (`state.color`, or the centre when null). A tap anywhere, or a drag that starts within 32 dp of the marker, moves it locally; the command is sent once, on release. A drag that starts elsewhere is left to the sheet's scroll, so scrolling across the wheel never picks a colour.
- The geometry is a pure `WheelMath`: `hsAt(point, size): Hs` and `pointOf(hs, size): Offset`, unit-tested without Compose.
- Semantics: content description "Color" (the app's English strings use US spelling) and a state description such as "Hue 210°, saturation 80 %". Adjusting the colour with TalkBack gestures is out of scope.
- Size: the sheet width minus padding, capped at 280 dp.

## 5. Demo house

`DemoHouse` gains a bedroom "LED strip": a JSON-schema light with `brightness`, `supported_color_modes: ["xy"]`, `effect: true`, `effect_list: [solid, breathe, rainbow, candle]`, plus two numbers on the same device, "Effect speed" and "Effect intensity" (0..255). The demo broker echoes state like its other lights, including `effect` and `color`, so demo mode shows the full sheet and the folded numbers disappear from the grid.

## 6. Architecture summary

| Module | Files |
|---|---|
| `core:model` | `Command.kt` (`TurnOn.effect`, `TurnOn.color: Hs?`), `StateValue.kt` (`Light.effect`, `Light.color`), `Hs.kt`, `Entity.kt` (`Capabilities.effects`) |
| `core:state` | `kinds/LightMapper.kt`, `ColorMath.kt` |
| `core:source` | `discovery/DiscoveryParser.kt` (effects), `discovery/LightAttachment.kt`, `MqttDiscoverySource.kt` (apply it) |
| `core:demo` | `DemoHouse.kt` (LED strip) |
| `feature:home` | `HomeModels.kt` (`CardUi.companions`, `card(id)`), `HomeUiBuilder.kt`, `ui/ControlSheet.kt` (`LightControls`), `ui/HueWheel.kt`, `ui/WheelMath.kt`, strings (en + fr) |
| ZigLED repo | `z2m/gekkotron_zigled.mjs` (`overrideHaDiscoveryPayload`) |

Every caller of `Command.TurnOn(color = Rgb)` moves to `Hs` (only `LightMapper` and its tests today).

## 7. Testing

- `ColorMath`: round trips (§3.2), known points (pure red ↔ xy 0.6401/0.33, white ↔ 0.3127/0.329, the strip's `{x:0.196,y:0.203}` decodes to hue ≈ 208°, saturation ≈ 0.78).
- `LightMapper`: decode the captured ZigLED state payload (effect `fire_1d`, colour from xy, brightness 0 on 254 scale, off); effect-only and colour-only payloads; `{h,s}` and `{r,g,b}` forms; encode `TurnOn(effect)` and `TurnOn(color)` in xy, hs and rgb modes; default-schema effect and rgb topics; `predict` overlays effect and colour.
- `DiscoveryParser`: the captured ZigLED light config yields `effects` of 9 and `colorModes = {xy}`; `effect: false` yields none.
- `LightAttachment`: numbers and selects fold into a device's single light; nothing folds when the device has two lights; sensors never fold; devices without a light are untouched.
- `HomeUiBuilder`: companions attached, sorted, absent from the grid and from search, found by `card(id)`.
- `WheelMath`: centre → saturation 0; top rim → hue 0, saturation 1; right rim → hue 90; `pointOf(hsAt(p)) ≈ p`.
- Compose (`HomeScreenTest` / a `ControlSheetTest`): the strip's sheet shows effect chips with the current one selected, tapping "Rainbow" sends `TurnOn(effect = "rainbow")`, companion sliders appear under their names, the wheel is present only with a colour mode.
- On device: the user's Pixel 6a against the real strip — pick an effect, change speed (after the converter fix), drag the wheel, check Z2M's state follows.

## 8. Risks

- **Converter not yet deployed:** until the user reloads the ZigLED converter, speed simply is not there; everything else works.
- **xy precision:** Z2M and the firmware quantise xy (to u16); a colour read back may sit a degree or so off what was sent. The wheel follows the state, so the marker may move slightly after release. Acceptable.
- **Folding too much:** a device with one light and unrelated numbers (e.g. a light-plus-sensor combo with a config number that is not hidden) would see those numbers move into the sheet. That is still where they belong (same device); they remain controllable.
- **Older app versions** sending `TurnOn(color = Rgb)` do not exist outside this repo; the type change is internal.
