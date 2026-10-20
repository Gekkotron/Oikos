# Water heater view, Oikos-os compatible: design

Date: 2026-10-20 · Author: Gekkotron

## Intent

The Oikos-os hub (`~/superconductor/projects/Oikos-os`, commit `8522fe6`) now shows the
[WaterHeaterController](https://github.com/Gekkotron/WaterHeaterController) on its web
dashboard. That works through one retained device-based discovery message published by hand
(Oikos-os `README.md`, section "Zigbee2MQTT"; repeated in Appendix A below). The app should read
**the same discovery message** and show **the same card**, so the phone and the hub show the
heater identically. Oikos-os is the reference: when this document and Oikos-os disagree on
behaviour, Oikos-os wins.

## Goals

1. The app decodes the Appendix A discovery message fully: tank temperature, mode (`heat`/`off`),
   the `power` and `inlet_temperature` attributes, and the three `Power N` number companions.
2. The Home water heater card shows the Oikos-os view: a tank glyph that glows while heating, the
   tank temperature, an activity chip and the inlet line.
3. The water heater sheet shows controls only when the heater has them.
4. The existing Jeedom water heater (spec `2026-10-11-oikos-water-heater-design.md`) keeps
   working, including its on-card Force switch, and gets the new card too.

## Non-goals

- No change to the Oikos-os repo, the WaterHeaterController firmware or Jeedom.
- No widget, voice or energy-screen changes beyond keeping exhaustive `when`s compiling.
- No new on-card controls: the target and mode controls stay in the sheet, as in the app today.
  (Oikos-os puts them on its web card; the app's pattern is the sheet.)
- No general Jinja work beyond what Appendix A needs (see §1 for the exact list).

## Anti-goals

- The Jeedom heater must not regress: Force switch, eco/performance chips, target stepper and
  the `Force power` companion all keep working.
- A template the engine cannot evaluate must still fail loudly, through the existing
  `UnsupportedTemplate` diagnostic path, never silently show a wrong value.

## Current state (what blocks it today)

Verified in the code on 2026-10-20:

- **Template engine** (`core/state/.../template/TemplateParser.kt`, `Templates.kt`) has no
  arithmetic (`+ - * /`), no ordering comparisons (`> < >= <=`), no dict literals `{k: v}` and
  no `tojson` filter. So all three heater templates fail: tank temperature, mode and attributes.
- **Attributes** (`core/state/src/jvmMain/.../StateStore.kt` `parseAttributes`) parse the raw
  `json_attributes_topic` payload and ignore `json_attributes_template`.
- **Mode with empty `modes`** (`WaterHeaterMapper.modes` + `ThermostatBase.decode`): an explicit
  `modes: []` gives an empty list, and decode drops any mode not in the list, so `heat`/`off`
  never reaches the state.
- **Card** (`feature/home/.../ui/EntityCard.kt`) is the generic tile: icon, name and a
  `waterHeaterSubtitle` text. `isOn` for a water heater is `mode == "performance"`.
- **Sheet** (`ControlSheet.kt` `WaterHeaterControls`) always shows the target stepper, falling
  back to the midpoint of min/max when there is no target.

Discovery itself is fine: `Abbreviations` expands the full HA table, including inside `cmps`, and
`DiscoveryParser.parseDeviceBased` handles device-based messages.

## Design

### 1. Template engine additions

These are needed by Appendix A. Semantics follow Jinja2/HA:

- **Arithmetic:** binary `+ - * /`, with the usual precedence (`* /` above `+ -`), both above
  comparisons and below filters (Jinja: `a + b | round` filters `b` only). `/` is always true
  division, giving a Double. Operands go through the existing loose-number coercion; a
  non-numeric operand raises `TemplateEvalError`. `+` on two strings concatenates.
  - **Tokenizer caveat:** today `-` followed by a digit lexes as a negative number literal. That
    must only happen where an operand is expected (at the start, or after an operator, `(`, `,`,
    `:` or `|`), so that `0.35 - 1` and `x -1` both subtract.
  - Unary minus on a non-literal is not needed.
- **Ordering comparisons:** `> < >= <=` between numbers, at the same level as `==`/`!=`.
- **Dict literals:** `{ "key": expr, ... }` with string-literal keys, evaluating to a map.
  - **Scanner caveat:** `TemplateParser.parse` finds the end of `{{ … }}` with
    `indexOf("}}")`. That works for Appendix A (`… | int} | tojson }}`), but nested dicts ending
    in `}}` would break. The builder either makes the closing scan brace-aware or documents the
    limit with a test. **Builder's call.**
- **`tojson` filter:** serializes maps, lists, strings, numbers, booleans and null to compact
  JSON. `round(1)` results stay Doubles (`17.9`); `int` results stay integers (`43`).
- `round` with no argument already exists and returns a Double. `| round | int` must give a
  Long.

Every addition gets a unit test in the existing template test suite. There must also be one
end-to-end test that renders all of the Appendix A templates against a sample `water-heater/data`
payload (Appendix B).

### 2. `json_attributes_template`

When the bindings carry `json_attributes_template`, render it with `value_json` bound to the
attributes-topic payload. Then parse the rendered string as a JSON object; that object becomes
`EntityState.attributes`. Without the template, keep today's behaviour. If the render fails or
gives a non-object, keep the previous attributes and report once through the existing
`reportOnce` diagnostics. Attributes keep their JSON types: a number stays a number.

### 3. Mode with no declared modes

When a water heater's `modes` list is **empty**, the decoded mode is kept as reported (any
non-blank string). When `modes` is absent, today's `DEFAULT_MODES` behaviour is unchanged. The
`modes` list still decides what can be **sent**: with an empty list, `SetHvacMode`/`TurnOff`
stay `NotSupported`. This matches Oikos-os, which reads the mode for the chip and hides the mode
picker when `modes` is empty.

### 4. Heater view model

Add a presentation-layer `HeaterView` (on `CardUi`, or carried by `CardValue.Climate` for water
heaters; **builder's call**). It is a port of Oikos-os `HeaterView::from_state`:

| Field | Rule |
|---|---|
| `power: Int?` | numeric `power` attribute, finite, clamped 0–100, rounded. Strings and null → null |
| `inlet: Double?` | numeric `inlet_temperature` attribute, finite. Strings and null → null |
| `heating` + chip | `power > 0` → heating, chip "Heating · N %". `power == 0` → not heating, "Idle". No power and mode `off` (any case) → not heating, "Off". No power and another mode → heating, chip = that mode's label. No power and no mode → not heating, no chip |

The mode label reuses `waterHeaterModeLabel` (eco → Éco, performance → Force, and so on). An
unknown mode such as `heat` gets a new "Heating"/"Chauffe" label rather than the raw word.

The card's `on` (the `OikosCard` highlight) becomes `heating` for water heaters. For the Jeedom
heater this means lit in eco and performance and dim when `off`. That is right for its contract,
where `off` means the water is already above the target. `forceToggle` is unchanged.

The Oikos-os unit tests (`water_heater.rs`, `mod tests`) are ported one for one as
`HeaterView` tests.

### 5. Card

A water heater card replaces the generic body:

- **Tank glyph** in the icon slot, drawn in Compose: a rounded vertical tank with a heating
  element inside. While `heating`, the element and the tank glow in the card's accent (a static
  tint is fine; animation is the **builder's call** and must respect reduced motion).
  `contentDescription` is null; the card's own description covers it.
- Top row: the tank glyph on the left; the Force switch (when `forceToggle != null`) and the
  pending dot on the right, as today.
- Name line as today.
- Value line: the tank temperature (`current`), with a small "Tank"/"Ballon" caption as in
  Oikos-os, or the existing "none" text when it is unknown.
- The activity chip, when there is one, styled distinctly while heating.
- "↓ Inlet 17.9 °C" / "↓ Arrivée 17,9 °C" when `inlet != null`; the arrow is decorative.
- The card keeps the standard grid size. If it cannot fit, it may become `wide`
  (**builder's call**, with the screenshot test as evidence).
- The card's accessibility description includes temperature, chip and inlet.
- `waterHeaterSubtitle` (the "Forced to 60°" strings) is no longer used on the card. Remove it,
  and its strings, if nothing else uses them.

### 6. Sheet

`WaterHeaterControls`:

- Mode chips appear only when `capabilities.hvacModes` minus `off` is non-empty (unchanged
  filter, but no empty row and no gap when there are none).
- The target title and stepper appear only when the state has a `target`. This drops the
  min/max midpoint fallback, matching Oikos-os ("the target stepper appears only with a target
  temperature").
- Same-device `Kind.NUMBER` companions stay. For Appendix A that gives Power 1/2/3.
- With no modes, no target and no companions, the sheet shows only its header. That's
  acceptable; tapping still opens it, as for any `SHEET_KINDS` entity.

### 7. Title

Oikos-os also stopped titles that repeat themselves ("Water heater · Water heater"). In the app,
check what Appendix A produces (component `name: null`, device "Water heater") on the card, in
the sheet header and in the companions' labels. If any reads doubled, fix it at the naming site
in `HomeUiBuilder`/`DiscoveryParser`, with a test. If nothing doubles, add a regression test
anyway.

### 8. Strings

English and French, in `feature/home` `strings.xml`: Tank/Ballon, Heating · %d %%/Chauffe ·
%d %%, Idle/Au repos, Off/Arrêt (or reuse `value_off`), Inlet %s/Arrivée %s, the `heat` mode
label (Heating/Chauffe), and the updated card description.

### 9. Demo house and tests

- The demo water heater (`core/demo/.../DemoHouse.kt`) becomes the Appendix A device: same
  discovery, a `water-heater/data` payload that heats at a non-zero power, and the three
  `Power N` numbers. The Jeedom shape stays covered by its existing unit tests and by its own
  screenshot.
- Robolectric screenshot (`WaterHeaterScreenshotTest`): the card heating, the card idle, the
  Jeedom card with its Force switch, and the sheet for Appendix A (no chips, no stepper, three
  power numbers).
- `HomeUiBuilderWaterHeaterTest`: `on`/chip/inlet for both shapes.
- `DiscoveryParserTest`: Appendix A parses into one `WATER_HEATER` plus its sensor, number and
  binary-sensor siblings on one device.

## Decided by Gekkotron (2026-10-20)

- Target: compatibility with Oikos-os, using the same discovery message and the same card.
- The Jeedom heater keeps its Force switch and gets the new card.
- No doubled title.
- EN + FR; the demo house switches to the new heater.
- Accepted on review: the new card style replaces the "Forced to 60°" subtitle; the sheet hides
  the target stepper without a target; `heat` gets its own "Heating"/"Chauffe" label.
- Card highlight = `heating` (§4) is provisional: Oikos-os will change again, so keep the rule in
  one place (`HeaterView`) and easy to retune.

## Left to the builder

The items marked **builder's call** above: brace-aware scanning, where `HeaterView` lives, glow
animation, and a wide card if needed.

## Appendix A: discovery message (from Oikos-os README, verbatim)

Topic `oikos/device/water_heater/config`, retained (the app's discovery prefix must be `oikos`,
as Oikos-os documents). On the default prefix, the same payload goes under
`homeassistant/device/water_heater/config`.

```json
{"dev":{"ids":["water_heater_controller"],"name":"Water heater","mf":"Gekkotron","mdl":"WaterHeaterController"},"stat_t":"water-heater/data","cmps":{"heater":{"p":"water_heater","name":null,"uniq_id":"water_heater","unit_of_meas":"°C","modes":[],"curr_temp_t":"water-heater/data","curr_temp_tpl":"{{ (80 + (0.35 - value_json.adc_result_mv / 1000) * 86.67) | round(1) }}","mode_stat_t":"water-heater/data","mode_stat_tpl":"{{ \"heat\" if (value_json.triac_0_power + value_json.triac_1_power + value_json.triac_2_power) > 0 else \"off\" }}","json_attr_t":"water-heater/data","json_attr_tpl":"{{ {\"inlet_temperature\": value_json.temp_0 | round(1), \"power\": ((value_json.triac_0_power + value_json.triac_1_power + value_json.triac_2_power) / 3) | round | int} | tojson }}"},"tank_temp":{"p":"sensor","name":"Tank temperature","uniq_id":"water_heater_tank_temp","dev_cla":"temperature","stat_cla":"measurement","unit_of_meas":"°C","val_tpl":"{{ (80 + (0.35 - value_json.adc_result_mv / 1000) * 86.67) | round(1) }}"},"power_0":{"p":"number","name":"Power 1","uniq_id":"water_heater_power_0","cmd_t":"water-heater/control","cmd_tpl":"{\"power0\": {{ value | int }}}","val_tpl":"{{ value_json.triac_0_power }}","min":0,"max":100,"step":1,"unit_of_meas":"%"},"power_1":{"p":"number","name":"Power 2","uniq_id":"water_heater_power_1","cmd_t":"water-heater/control","cmd_tpl":"{\"power1\": {{ value | int }}}","val_tpl":"{{ value_json.triac_1_power }}","min":0,"max":100,"step":1,"unit_of_meas":"%"},"power_2":{"p":"number","name":"Power 3","uniq_id":"water_heater_power_2","cmd_t":"water-heater/control","cmd_tpl":"{\"power2\": {{ value | int }}}","val_tpl":"{{ value_json.triac_2_power }}","min":0,"max":100,"step":1,"unit_of_meas":"%"},"temp_0":{"p":"sensor","name":"Cold water inlet temperature","uniq_id":"water_heater_temp_0","dev_cla":"temperature","stat_cla":"measurement","unit_of_meas":"°C","val_tpl":"{{ value_json.temp_0 | round(1) }}"},"temp_1":{"p":"sensor","name":"Triac temperature","uniq_id":"water_heater_temp_1","dev_cla":"temperature","stat_cla":"measurement","unit_of_meas":"°C","val_tpl":"{{ value_json.temp_1 | round(1) }}","ent_cat":"diagnostic"},"frequency":{"p":"sensor","name":"Mains frequency","uniq_id":"water_heater_frequency","dev_cla":"frequency","stat_cla":"measurement","unit_of_meas":"Hz","ent_cat":"diagnostic","val_tpl":"{{ value_json.measuredFrequency | round(1) }}"},"frequency_error":{"p":"binary_sensor","name":"Mains frequency error","uniq_id":"water_heater_frequency_error","dev_cla":"problem","ent_cat":"diagnostic","pl_on":"1","pl_off":"0","val_tpl":"{{ value_json.frequencyError }}"}}}
```

## Appendix B: sample `water-heater/data` payload (for tests)

Field names come from the Appendix A templates; the values are illustrative.

```json
{"adc_result_mv": 604, "triac_0_power": 40, "triac_1_power": 45, "triac_2_power": 44,
 "temp_0": 17.94, "temp_1": 41.2, "measuredFrequency": 50.02, "frequencyError": 0}
```

Expected: tank `(80 + (0.35 − 0.604) × 86.67) | round(1)` = `58.0`. Mode `heat`. Attributes
`{"inlet_temperature": 17.9, "power": 43}`. Card chip "Heating · 43 %", inlet "17.9 °C". With all
three triac powers at 0: mode `off`, power 0, chip "Idle", not heating.
