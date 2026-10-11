# Water heater: HA `water_heater` kind + Jeedom force-heat contract

## Goal

Show the house water heater in Oikos with a one-tap **Force** control. Force heats the tank at a chosen power until it is hotter than its target, overriding the Jeedom solar router, then hands control back.

As for the alarm (security spec, Appendix A), the app speaks the standard Home Assistant MQTT `water_heater` entity. All the logic lives in Jeedom, behind the contract in Appendix B. An HA user's water heater gets the same card.

Out of scope: a widget tile, voice commands, schedules, and a way to pick `off` by hand (Jeedom owns `off`, see §1).

## 1. Facts and behaviour

- The heater is the user's `WaterHeaterController` (STM32 triac dimmer).
  - It publishes `water-heater/data` about every 4 s, not retained, with `temp_0`, `triac_0_power` (0–100) and more.
  - It accepts `{"power0": 0..100}` on `water-heater/control`.
  - **It drops to 0 % after 15 min without a power command**, so a one-shot command cannot hold force.
- A Jeedom solar router drives `power0` from the solar surplus today.
- The mode is decided in Jeedom from the water temperature `temp_0` and the target:

| Mode | Meaning | Jeedom does |
|---|---|---|
| `eco` | temp ≤ target, not forced | the solar router drives `power0` |
| `performance` | forced | sends `{"power0": <force power>}` now and every 10 min; the solar router is paused |
| `off` | temp > target | nothing heats; the solar router is paused |

- Transitions:
  - **Forced:** while `performance`, once temp > target → `off`.
  - **Not forced:** `eco` → `off` when temp > target, and `off` → `eco` when temp ≤ target.
  - **Leaving force:** the user picking Eco during `performance` → `eco` (or `off` if already hot).
  - Picking Force while `off` is accepted. Jeedom moves to `off` again on its next check if the water is still hotter than the target.

## 2. Model (`:core:model`)

- `Kind` gains `WATER_HEATER`.
- No new state type: a water heater's state is `StateValue.Climate(mode, target, current)` (fan mode unused).
- It reuses the `hvacModes`, `minTemp`, `maxTemp` and `tempStep` capabilities.

## 3. Discovery (`:core:source`, `DiscoveryParser`)

- `"water_heater" to Kind.WATER_HEATER`. No new abbreviations: `curr_temp_t`, `temp_stat_t`, `temp_cmd_t`, `mode_stat_t`, `mode_cmd_t` and their templates are already in `abbreviations.json`.
- Capabilities:
  - `hvacModes = WaterHeaterMapper.modes(b)`;
  - `minTemp = min_temp ?: 43.0` and `maxTemp = max_temp ?: 60.0` (HA's 110–140 °F defaults);
  - `tempStep = precision ?: 1.0`. HA water heaters have no `temp_step`, and HA's own default `precision` of 0.1 is too fine for a stepper.
- Not in `REQUIRES_COMMAND`: like climate, a water heater may be read-only.

## 4. State (`:core:state`)

- The topic/template logic shared with climate (current, target, mode decode; `SetTemperature`/`SetHvacMode` encode; feedback; predict) moves out of `ClimateMapper` into a parameterised helper. `ClimateMapper` keeps its fan-mode handling and defaults, and behaves exactly as it does today.
- `WaterHeaterMapper` uses that helper with HA's water-heater defaults: modes `off, eco, electric, gas, heat_pump, high_demand, performance` and the temperatures in §3.
- `Command.TurnOff` maps to `SetHvacMode("off")` only when `off` is in the modes.
- `KindMapper` routes `Kind.WATER_HEATER` to it.

## 5. UI (`:feature:home`)

### Card
- Glyph: the existing `StaticIcon.WATER_HEATER`.
- Value: the current temperature (`52 °C`), `—` until the first `water-heater/data` message.
- Subtitle by mode:

| Mode | EN | FR | Look |
|---|---|---|---|
| `eco` | Eco · 60 °C target | Éco · cible 60 °C | neutral |
| `performance` | Forced → 60 °C | Forcé → 60 °C | accent tint, like an "on" card |
| `off` | Hot · 60 °C target | Chaud · cible 60 °C | neutral |
| other HA modes | localised mode label · target | | neutral |

- **Force toggle on the card**, only when the modes contain both `performance` and `eco`. Off → sends `SetHvacMode("performance")`. On → sends `SetHvacMode("eco")`.
- Tapping the card opens the sheet (`SHEET_KINDS`, `TapAction.OPEN_SHEET`).
- Commands are optimistic through `predict`, as for climate.

### Sheet
1. **Mode chips**: every discovered mode except `off`. The house shows **Eco / Force** (FR **Éco / Forcé**); other modes get localised labels (Electric, Gas, Heat pump, High demand).
2. **Target stepper**: `minTemp..maxTemp`, step `tempStep`.
3. **Same device's numbers**: every visible `Kind.NUMBER` entity with the same `deviceId`, drawn with the existing number slider. For the house that is the "Force power" slider. They still exist as entities, so the user can hide their standalone cards in Customize, as usual.

### Strings
EN + FR for the subtitles, the chips and the mode labels in §5.

## 6. Demo

The demo house's "Water heater" device (Garage) gains:
- a `water_heater` with modes `[off, eco, performance]`, current temperature 52 °C and target 60 °C;
- a "Force power" `number` (0–100, step 10) on the same device.

The existing `demo_water_heater_target` number is replaced by the `water_heater`'s target.

## 7. Tests

- `WaterHeaterMapperTest`:
  - decodes current, target and mode from separate topics and from one JSON topic;
  - rejects unknown modes;
  - encodes `SetTemperature` within range and `SetHvacMode`;
  - covers `TurnOff` both with and without `off` in the modes;
  - checks `predict`.
- `ClimateMapperTest` stays green, unchanged (refactor guard).
- `DiscoveryParserTest`: an abbreviated `water_heater` config → `Kind.WATER_HEATER` with the §3 capabilities and defaults.
- `CardFormatterTest`: subtitles for `eco`, `performance`, `off`, and a missing current temperature.
- A Robolectric screenshot of the card (eco and forced) and the sheet, as done for the energy flow edges.
- `DemoBrokerTest`: the Force toggle round-trip on the demo heater.

## 8. Rollout

1. Merge the app.
2. The user builds the Jeedom side to Appendix B (jMQTT publishes the retained discovery configs; a scenario runs the §1 rules and the 10-minute resend).
3. Device check: the card shows the live temperature; Force → the heater runs at the force power for longer than 15 min; at the target it goes to `off` and the card reads "Chaud"; it returns to `eco` as the water cools.

---

## Appendix B: Jeedom contract

Discovery configs are **retained**, with `device.identifiers = ["jeedom_water_heater"]`, `device.name = "Chauffe-eau"` and `suggested_area = "Garage"`.

### B.1 `homeassistant/water_heater/jeedom/water_heater/config`

```json
{
  "name": null,
  "unique_id": "jeedom_water_heater",
  "device": {"identifiers": ["jeedom_water_heater"], "name": "Chauffe-eau", "suggested_area": "Garage", "manufacturer": "Gekkotron", "model": "WaterHeaterController"},
  "availability_topic": "jeedom/water_heater/availability",
  "current_temperature_topic": "water-heater/data",
  "current_temperature_template": "{{ value_json.temp_0 }}",
  "mode_state_topic": "jeedom/water_heater/state",
  "mode_state_template": "{{ value_json.mode }}",
  "temperature_state_topic": "jeedom/water_heater/state",
  "temperature_state_template": "{{ value_json.target }}",
  "mode_command_topic": "jeedom/water_heater/mode/set",
  "temperature_command_topic": "jeedom/water_heater/temperature/set",
  "modes": ["off", "eco", "performance"],
  "min_temp": 40, "max_temp": 65, "precision": 1.0,
  "temperature_unit": "C"
}
```

- `jeedom/water_heater/state`: **retained** `{"mode": "eco|performance|off", "target": 60}`. Re-published on every mode or target change, including rejected commands (that confirms the app's pending command).
- `jeedom/water_heater/availability`: retained `online` / `offline`. Jeedom should set an LWT to `offline` where jMQTT allows.
- `…/mode/set`: payload `eco` or `performance` (`off` is never sent by the app).
- `…/temperature/set`: payload a number, such as `58` (or `58.0`).

### B.2 Force power: `homeassistant/number/jeedom/water_heater_force_power/config`

```json
{
  "name": "Force power",
  "unique_id": "jeedom_water_heater_force_power",
  "device": {"identifiers": ["jeedom_water_heater"]},
  "state_topic": "jeedom/water_heater/force_power",
  "command_topic": "jeedom/water_heater/force_power/set",
  "min": 0, "max": 100, "step": 10,
  "unit_of_measurement": "%",
  "availability_topic": "jeedom/water_heater/availability"
}
```

`jeedom/water_heater/force_power` is retained.

### B.3 Heating power (optional): `homeassistant/sensor/jeedom/water_heater_power/config`

`state_topic: water-heater/data`, `value_template: "{{ value_json.triac_0_power }}"`, `unit_of_measurement: "%"`, the same `device`. A W sensor works too, if Jeedom knows the element's wattage. Tag it as an Energy consumer in Customize.

### B.4 Scenario rules

On every `water-heater/data` message (or every minute):

```
t = temp_0
if mode == performance:
    if t > target: mode = off
    elif 10 min since the last send: publish water-heater/control {"power0": force_power}
else:
    mode = off if t > target else eco      # eco → the solar router drives power0
publish jeedom/water_heater/state when mode changes
```

- On `…/mode/set performance`: publish `{"power0": force_power}` immediately, then follow the rules.
- On `…/mode/set eco`: set the mode to `off` if `t > target`, else `eco`, and let the solar router resume.
- In `off`, the solar router must not send `power0 > 0`.
