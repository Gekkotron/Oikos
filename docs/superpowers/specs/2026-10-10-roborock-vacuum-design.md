# Roborock vacuum: HA discovery + Oikos Vacuum kind

Date: 2026-10-10. Repos: `Gekkotron/Roborock2Mqtt` (`~/Documents/SourcesPerso/RoborockMqtt`) and `Gekkotron/Oikos`.

## Goal

Show the Roborock S5 in Oikos as a real vacuum: live state and battery on its card, and a control sheet to start, pause, stop, send it to the dock, locate it, and pick the suction power. The bridge announces it with Home Assistant MQTT discovery, so it also appears as a standard `vacuum` in HA.

Out of scope (v1): consumables, room/segment/zone cleaning, maps, widgets, voice commands, automatic room assignment.

## Facts from the real unit (2026-10-10)

- Model `roborock.vacuum.s5`, firmware `3.5.8_002034`.
- `miiocli -o json_pretty roborockvacuum status` returns e.g.
  `{"state": 8, "battery": 100, "fan_power": 104, "error_code": 0, "in_cleaning": 0, "in_returning": 0, ...}`.
- `fan_speed_presets` returns `{"Silent": 101, "Standard": 102, "Medium": 103, "Turbo": 104, "Gentle": 105, "Auto": 106}`.
- `info` returns the miio `token` in clear; the bridge currently republishes it on `roborock/result`.

## Part 1: Roborock2Mqtt bridge

Keep the existing `miiocli` subprocess design and topics; add an HA layer in the Daikin2Mqtt style.

### New pure module `ha_discovery.py` (no MQTT, no subprocess; unit tested)

- `vacuum_state(status: dict, presets: dict) -> dict` builds the HA state JSON:
  `{"state": <ha state>, "battery_level": <int>, "fan_speed": <preset name or null>, "error_code": <int>}`.
  - miio state code to HA state:

    | miio codes | HA state |
    | --- | --- |
    | 5 Cleaning, 11 Spot, 16 Going to target, 17 Zoned, 18 Segment | `cleaning` |
    | 6 Returning home, 15 Docking | `returning` |
    | 8 Charging, 100 Charging complete | `docked` |
    | 10 Paused | `paused` |
    | 9 Charging problem, 12 Error | `error` |
    | anything else (1, 2, 3, 4, 7, 13, 14, unknown) | `idle` |

  - `fan_speed` is the preset name whose code equals `fan_power`; null if none matches.
- `command_for(action: str) -> str | None` maps HA command payloads to miiocli: `start`→`start`, `pause`→`pause`, `stop`→`stop`, `return_to_base`→`home`, `locate`→`find`; anything else → None (logged and ignored).
- `fan_speed_command(name: str, presets: dict) -> str | None`: `Turbo` → `set_fan_speed 104`; unknown → None.
- `discovery_config(prefix, discovery_prefix, info, presets) -> (topic, payload)`:
  - Topic `homeassistant/vacuum/<node_id>/<unique_id>/config`, where `unique_id = roborock_<mac without colons>` (fallback: sanitized `VACUUM_IP`).
  - Payload (HA state schema): `name: null` (entity takes the device name), `unique_id`, `object_id`, `state_topic: <prefix>/ha/state`, `command_topic: <prefix>/ha/command`, `set_fan_speed_topic: <prefix>/ha/fan_speed/set`, `fan_speed_list: list(presets)`, `supported_features: ["start","pause","stop","return_home","locate","fan_speed","battery","status"]`, `availability_topic: <prefix>/alive`, `payload_available: "1"`, `payload_not_available: "0"`, `device: {identifiers, name: "Roborock", manufacturer: "Roborock", model, sw_version}`.
- `redact_info(text: str) -> str`: drops the `token` field from an `info` JSON result (returns the input unchanged if it is not JSON).

### `main.py` changes

- New env: `HA_DISCOVERY_ENABLED` (default `false`), `HA_DISCOVERY_PREFIX` (default `homeassistant`), `HA_STATUS_INTERVAL` (default `30` s). README documents them.
- On connect, when enabled: run `info` and `fan_speed_presets` (retried on every HA tick until both succeed), publish the retained discovery config, then start an HA status loop (started once, not again on reconnect) every `HA_STATUS_INTERVAL` that runs `status` and publishes `vacuum_state(...)` retained on `<prefix>/ha/state`. The existing `<prefix>/status` loop is unchanged.
- Subscribe handling: `<prefix>/ha/command` → `command_for` → miiocli; `<prefix>/ha/fan_speed/set` → `fan_speed_command` → miiocli. After each, refresh HA state immediately and once more ~5 s later.
- The existing `<prefix>/command` path is unchanged except that an `info` result goes through `redact_info` before publishing (applies whether or not discovery is enabled).
- The bridge must ignore its own `<prefix>/ha/state` messages (it subscribes to `<prefix>/#`). Topic routing is exact (`<prefix>/command`, `<prefix>/ha/command`, `<prefix>/ha/fan_speed/set`); the old `endswith("/command")` test would also catch `<prefix>/ha/command`.
- The `alive` heartbeat `1` is published retained, so a retained LWT `0` from an earlier crash cannot leave the vacuum unavailable until the next heartbeat.
- Errors: a failed or non-JSON `status` leaves the last HA state as is and goes to `<prefix>/error` as today.

### Tests

`tests/test_ha_discovery.py` (pytest or unittest, whichever the repo adopts; no hardware): state mapping table, fan speed lookup both ways, command mapping, discovery payload fields, token redaction.

## Part 2: Oikos Vacuum kind

### Model (`core/model`)

- `Kind.VACUUM`.
- `StateValue.Vacuum(state: String?, battery: Int?, fanSpeed: String?)`.
- `Command.VacuumAction(action: String)` (one of `start`, `pause`, `stop`, `return_to_base`, `locate`) and `Command.SetFanSpeed(speed: String)`.
- `Capabilities`: `vacuumActions: List<String> = emptyList()` and `fanSpeeds: List<String> = emptyList()`.

### Discovery (`core/source` DiscoveryParser)

- Component `vacuum` → `Kind.VACUUM`.
- Capabilities from `supported_features` (HA default when missing: `start, stop, return_home, status, battery, clean_spot`): `start`→`start`, `pause`→`pause`, `stop`→`stop`, `return_home`→`return_to_base`, `locate`→`locate` (others ignored). `fanSpeeds` from `fan_speed_list`, only when `fan_speed` is supported and `set_fan_speed_topic` is present.

### State (`core/state` VacuumMapper)

- State topic: `state_topic`. Payload is JSON; read `state` (one of `cleaning, docked, paused, idle, returning, error`, else ignored), `battery_level`, `fan_speed`. A missing key keeps the previous value; an explicit JSON `null` `fan_speed` clears it (the bridge sends null when `fan_power` matches no preset). Non-JSON payload → `Decoded.Error`.
- Encode `VacuumAction` → publish the action's payload on `command_topic`, honouring HA overrides `payload_start`, `payload_pause`, `payload_stop`, `payload_return_to_base`, `payload_locate` (defaults equal the action names). Not in capabilities → `NotSupported`.
- Encode `SetFanSpeed` → publish the speed on `set_fan_speed_topic`; not in `fan_speed_list` → `NotSupported`.
- `TurnOff` → `return_to_base` if supported, else `NotSupported`.
- Predict: `start`→`cleaning`, `pause`→`paused`, `stop`→`idle`, `return_to_base`→`returning`, `locate`→ no change; `SetFanSpeed` → new fan speed. Feedback topic: `state_topic`.
- Register the mapper in `KindMapper`.

### UI (`feature/home`)

- Icon: new `OikosGlyph.VACUUM` in `core/designsystem` icons/StaticIcon.kt (24-unit path, same area+line style: a round robot body seen from above with a bumper arc and a centre button), used by `KindIcon` for `Kind.VACUUM`.
- Card value: localized state + battery, e.g. "Docked · 100 %" / "En charge · 100 %"; unknown → existing "—" behaviour.
- Control sheet `VacuumControls`: state line with battery; action buttons for supported actions: Start (shown as Pause while `cleaning` if pause is supported), Stop, Return to dock, Locate; then a "Suction" chip row from `fanSpeeds` (selected = current fan speed; preset names shown as-is).
- Strings in `values` and `values-fr`.
- HomeUiBuilder / Placement: vacuum is a normal Home device (no Energy/Security routing).
- Every other exhaustive `when (kind)` (widgets TileMapper/TileActions/ConfigViewModel, voice catalog, security builder): vacuum excluded/ignored; no new widget or voice behaviour.

### Demo

`DemoHouse` gets a vacuum with an echo handler (commands update state), fan speeds `Silent, Standard, Medium, Turbo, Gentle, Auto`.

### Tests

VacuumMapper (decode, partial JSON, encode each action, payload overrides, unknown speed rejected, predict), DiscoveryParser capabilities for `vacuum`, card formatting in English.

## Rollout

1. Bridge: commit + push `main` of Roborock2Mqtt; the user sets `HA_DISCOVERY_ENABLED=true` in the Geekom `.env` and redeploys (`git pull && docker compose up -d --build`).
2. App: commit + push Oikos `master`; Firebase distribution builds the new version.
3. Device check: the vacuum card shows "Docked · 100 %", Start/Dock/Locate work, fan speed change is reflected within one poll.
