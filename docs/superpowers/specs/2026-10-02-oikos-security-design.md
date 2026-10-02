# Oikos — Security tab: alarm panel and safety sensors

Status: approved design, 2026-10-02. Parent spec: `2026-09-24-oikos-design.md` §5.2 (Security) and §11 (phase 1). It is the phase 1 sub-project after LED control. The parent's "recent alerts" part (push notifications, Room history, media playback) is **not** here: it is the next sub-project, "Push alerts", and needs the FCM pipeline of parent §4.7 first.

Nothing is house-specific (parent goal 7). The alarm is the standard Home Assistant MQTT `alarm_control_panel`, so an HA user (Alarmo, manual alarm over MQTT…) and this house (Jeedom, through a small bridge, Appendix A) get the same screen.

## 1. Goal

A third tab, **Security**, showing:

1. **The alarm panel** as a hero card: its state, arm modes from `supported_features`, Disarm, and a code keypad when a code is required.
2. **The safety and intrusion sensors**: binary sensors of the classes in §3.2, active ones first.
3. A **Recent alerts** placeholder, filled by the Push alerts sub-project.

Out of scope:
- Push alerts (next sub-project).
- Sirens on the tab; the house's Tuya siren stays a switch on Home. Driving it on `triggered` is the alarm system's job.
- A "needs attention" strip (unavailable sensors, `problem` class, low battery).
- Arming / entry-delay countdowns: the standard contract carries no delay value.
- A "last changed" time on sensor rows: Z2M re-publishes the whole state on every link-quality update, so `lastUpdate` is not a state-change time.
- A panic / `trigger` button.

### 1.1 This house, as announced today

No `alarm_control_panel` is announced; the alarm logic lives in Jeedom and is not on MQTT. Appendix A is the contract the user's Jeedom bridge publishes.

Binary sensors (Z2M 2.14.1): 10 `door`-class contacts (doors, windows, garage, mailbox, stove reservoir), 7 `occupancy`, 5 `smoke`, 1 `carbon_monoxide`, 2 `moisture`, 1 `safety` (SOS), 1 `vibration`, 1 class-less "Device fault". Z2M labels every contact `door`, windows included, so the mailbox and the stove reservoir appear until the user hides them (§3.3).

## 2. Model (`:core:model`)

- `Capabilities` gains:
  - `codeDisarmRequired: Boolean = false`;
  - `codeFormat: CodeFormat = CodeFormat.NUMBER`, with `enum class CodeFormat { NUMBER, TEXT }`.
- `UiOverride` gains `securityHidden: Boolean = false`: "not on Security". It does not hide the entity on Home. It inherits the known issue that an older app on a second phone re-saving an override drops new fields.

## 3. Source and state

### 3.1 Discovery (`DiscoveryParser`, alarm panel)

- `codeArmRequired = AlarmPanelMapper.codeRequired(b, arming = true)` (as today) and `codeDisarmRequired = AlarmPanelMapper.codeRequired(b, arming = false)`.
- `codeFormat`: `TEXT` when `code` is `REMOTE_CODE_TEXT`, or a literal code containing a non-digit; otherwise `NUMBER`. No code → `NUMBER` (unused).
- The PIN stays out of the cache (`MqttDiscoverySource.redact`, unchanged). `REMOTE_CODE*` is the recommended setup: the PIN is never on the broker, and the alarm system checks it.
- `AlarmPanelMapper` is unchanged: it already sends `{{ action }}` / `{{ code }}` through `command_template`, checks literal codes locally and passes remote codes through.

### 3.2 Security sensor classes

`SensorGroup` (in `:core:model`, so Home's customise sheet and Security share it) maps device classes to groups:
- `door, window, garage_door, opening` → OPENING;
- `motion, occupancy, presence` → MOTION;
- `smoke` → SMOKE, `carbon_monoxide` → CO, `gas` → GAS, `moisture` → LEAK, `safety` → SOS;
- `vibration` → VIBRATION, `tamper` → TAMPER.

- `danger` subset (rose accent): `smoke, carbon_monoxide, gas, moisture, safety`.
- `intrusion` subset (amber accent): every other class.
- Class-less binary sensors and `problem` are not on the tab.

### 3.3 Hiding

`securityHidden` is toggled from:
- the Security row's sheet (§4.4);
- Home's `CustomizeSheet`, a "Show on Security" switch shown only for binary sensors in `SECURITY_CLASSES` and for alarm panels.

## 4. UI

### 4.1 Shared alarm UI moves to `:core:designsystem`

The alarm controls are now private to `:feature:home` (`ControlSheet.AlarmControls`, `CardText.alarmLabel/armLabel`). Feature modules cannot depend on each other, so these move:

- `AlarmActions.of(state, available, caps)` (pure): which arm modes, whether Disarm is offered, and which buttons are enabled.
- `AlarmControls(state, available, caps, onSend: (Command) -> Unit)`: the buttons plus the code flow, used inside Home's sheet.
- `AlarmHero(name, state, available, pending, caps, onSend)`: the Security card, with a colour by state, the state text and `AlarmControls`.
- `CodeKeypadSheet(format, onSubmit(code), onDismiss)`: a 3×4 digit grid with ⌫ and OK for `NUMBER`, or a password field for `TEXT`. The code lives only in the sheet's state, never `rememberSaveable`, and is cleared on dismiss.
- The alarm state and arm-mode strings (EN + FR), moved from `feature:home`.
- `:core:designsystem` gains an `api` dependency on `:core:model`, since these composables take `AlarmState`, `AlarmMode`, `Capabilities` and `Command`.

`KindIcon` / `IconSpec` stay in `feature:home`. Security rows draw the designsystem icons directly (`DoorIcon`/`WindowIcon`/`GarageIcon` at openness 0 or 1, otherwise `StaticIcon`), without the swing animation.

Home keeps its alarm card. Its sheet uses the shared `AlarmControls`, which brings it the disarm-code fix and the keypad.

### 4.2 Hero card (`AlarmHero`)

| State | Look | Text | Buttons |
|---|---|---|---|
| `disarmed` | mint (`connected`) tint | Disarmed | supported arm modes |
| `armed_*` | violet | Armed · Away (etc.) | other arm modes, current one highlighted; Disarm |
| `arming` | violet, pulsing | Exit delay… | Disarm |
| `pending` | amber, pulsing | Entry delay… | Disarm |
| `triggered` | rose, filled card | Triggered! | Disarm only, full width |
| `disarming` | mint (`connected`) tint, pulsing | Disarming… | none |
| unavailable / no state yet | greyed | Unavailable / — | disabled |

- Arm modes are listed in `AlarmMode` order, filtered by `caps.alarmModes`.
- A stale panel (a cached value not yet confirmed by live data) is dimmed and announces "Last known value".
- A pending optimistic command shows the existing `PendingDot`.
- **Code flow:** tapping an action that needs a code (`codeArmRequired` for arm, `codeDisarmRequired` for Disarm) opens `CodeKeypadSheet`. OK sends the command with the code and closes the sheet. Without a code requirement, the command is sent at once.
- A rejected code is handled by the alarm system re-publishing its state (Appendix A §A.3). The command tracker confirms a pending command on any state message, so the re-published state confirms it: the hero silently returns to the previous state and no rollback snackbar appears, and there is no dedicated "wrong code" message. The 5 s "Not confirmed" snackbar with "Retry" only appears when the bridge publishes nothing at all.
- Pulses respect the system "remove animations" setting.

### 4.3 Screen (`:feature:security`)

Top to bottom: the status pill (as on Home and Energy), the hero card(s), a summary line, the sensor list, then Recent alerts.

- **Panels:** every visible (`!hidden && !securityHidden`) alarm panel, in user order.
- **Summary:** counts of active sensors by group, ordered danger groups first, then intrusion, for example "1 smoke · 2 open · 1 motion", or "All clear". "Open" covers contact classes; "motion" covers motion, occupancy and presence; the danger classes are named one by one.
- **Sections:** sensors are grouped under headers in this order: Openings, Motion, Fire (smoke, CO, gas), Water (leak), Other (SOS, vibration, tamper). Empty sections are left out; each section keeps the order below.
- **Sensor tiles:** two per row, the same height, so many statuses fit at a glance. Line 1: icon and name; line 2: area and a state word. Long names and areas are cut with an ellipsis, never the state word. A stale row (cached value) is dimmed and announces "Last known value"; it still sorts and counts by its cached value. An active danger row has a rose border and word, and an active intrusion row amber:
  - Open / Closed (contacts);
  - Motion / Clear (motion, occupancy, presence);
  - Smoke / CO / Gas: Detected / Clear;
  - Leak / Dry (moisture);
  - SOS / OK (safety);
  - Vibration / Still (vibration);
  - Tampered / OK (tamper).

  Active rows get the danger (rose) or intrusion (amber) accent.
- **Order:**
  1. Active danger rows, then active intrusion rows.
  2. Inactive rows.
  3. Unavailable rows, greyed, with "Unavailable".

  Within a group, rows are sorted by area display name, then entity name. Tiles fill left to right, so the first active sensor of a section is top-left.
- **Recent alerts:** a header and the empty state "Alerts will appear here once push notifications are set up."
- **Empty cases:**
  - No panel: a slim one-line card (no card minimum height) reading "No alarm panel. Publish an MQTT `alarm_control_panel` to see it here."
  - No sensors: "No security sensors."
  - Neither: one combined empty state.
- **Accessibility:**
  - The hero header (name and state) is one TalkBack node, for example "Alarm, armed away", and is a live region (polite; assertive when triggered). The buttons stay individually focusable.
  - The summary line is a polite live region.
  - Rows read as "Garage door, garage, open".
  - The keypad's digit buttons are at least 48 dp.

### 4.4 Sensor row sheet

Tapping a row opens a small sheet in `:feature:security`: the name, area and state, and the "Show on Security" switch. Turning the switch off saves `securityHidden = true` through the same override path Energy uses (`EnergyEdit`-style), and the row disappears. Renaming and the room stay in Home's customise sheet; this sheet does not duplicate them.

### 4.5 Presentation layer

- `SecurityUiBuilder` (pure, `presentation/`): entities + states + areas → `SecurityUi(panels, summary, sensors, empty)`. All filtering, grouping, sorting and summary counting happens here.
- `SecurityViewModel`: over the entity source, like `EnergyViewModel`. It exposes `StateFlow<SecurityUi>`, `send(uniqueId, Command)` and `setSecurityHidden(uniqueId, Boolean)`.

## 5. App

- A third tab, **Security**, with the `SHIELD` glyph and EN/FR "Security / Sécurité".
- `selectTab` generalises: every non-Home tab sits on top of Home. Selecting a tab replaces any other non-Home tab on the stack, so back from Energy or Security always returns to Home. `showsTabBar` covers the three keys.
- `OikosNavHost` gains the `SecurityKey` entry.

## 6. Demo house

- The demo alarm panel:
  - `code: REMOTE_CODE`, `code_arm_required: false`, `code_disarm_required: true`; the demo accepts the code `1234`;
  - supported modes: home, away, night;
  - `ARM_*` publishes `arming`, then the armed state after 3 s;
  - `DISARM` with the right code publishes `disarmed`; with a wrong code it re-publishes the current state.
- A demo button entity, "Simulate intrusion", publishes `pending` and then `triggered` after 3 s, but only while the alarm is armed.
- Binary sensors covering the classes on screen: at least a door, a window, a motion sensor, a smoke detector and a leak sensor, with one starting active.

## 7. Architecture summary

```
:core:model        Capabilities.codeDisarmRequired, codeFormat; UiOverride.securityHidden; SensorGroup
:core:source       DiscoveryParser fills the two new capabilities
:core:designsystem AlarmActions, AlarmControls, AlarmHero, CodeKeypadSheet, alarm strings (api → :core:model)
:feature:home      sheet uses AlarmControls; CustomizeSheet "Show on Security"
:feature:security  SecurityUiBuilder, SecurityViewModel, SecurityScreen, SensorSheet   (new)
:core:demo         remote-code alarm with delays, intrusion button, sensor classes
:app               third tab, generalised selectTab
```

Dependencies follow parent §4.1: `:feature:security → :core:source, :core:designsystem`.

## 8. Testing

- **Unit:**
  - `SecurityUiBuilder`: class filter, `hidden` vs `securityHidden`, danger/intrusion grouping, order (active danger → active intrusion → inactive → unavailable; area, then name), summary counts, multiple panels, the three empty cases.
  - `DiscoveryParser`: `codeDisarmRequired` and `codeFormat` for `REMOTE_CODE`, `REMOTE_CODE_TEXT`, a digit code, a non-digit code and no code.
  - `UiOverride` serialisation round-trip with `securityHidden`.
  - `selectTab` / `showsTabBar` with three tabs.
  - Demo: arm → arming → armed; disarm with right and wrong code; intrusion only when armed.
- **Compose UI:**
  - `AlarmActions` per state and capabilities (unit); `AlarmHero` triggered layout and `AlarmControls` code flow (Compose).
  - `CodeKeypadSheet`: digits, ⌫, OK sends the entered code, dismiss clears it.
  - Security screen empty states.
- `./gradlew check` green; manual run on the Pixel 6a against the demo, then against the Jeedom bridge once it exists.

## 9. Risks

- **Code in transit.** With `REMOTE_CODE`, the PIN travels in the command payload. In direct mode that is the LAN broker (TLS when configured); away, it goes through the encrypted gateway envelope. It is never logged or cached; the logs viewer must not print alarm command payloads.
- **Bridge latency.** A bridge slower than 5 s triggers a false rollback snackbar. Appendix A asks for a state publish within 5 s.
- **Moving the alarm UI** touches `feature:home` imports and strings; the existing Home tests guard against regressions.
- **Code kept in memory.** The typed code stays in memory inside a not-confirmed command until its Retry snackbar is dismissed, in both view models. It is never persisted.
- **Code requirement after a cache restore.** The cache strips `code`, so a panel's code requirement is unknown after a restore until the retained config replays. Sends are blocked while the panel is stale, so this is harmless with `REMOTE_CODE`.
- **JSON-breaking characters.** In `TEXT` format, a `"` or `\` in the code produces invalid JSON through the command template, as in Home Assistant.
- **Two view models collect `commandFailures`.** Home's view model, still alive under Security on the back stack, also buffers a "not confirmed" event, which then shows when the user returns to Home. This is a known minor.

## Appendix A — Jeedom bridge contract

The standard HA MQTT alarm panel. An HA user with Alarmo publishes the equivalent, and Oikos treats both the same.

### A.1 Discovery (retained, published once)

Topic `homeassistant/alarm_control_panel/jeedom/house_alarm/config`. To remove the panel, publish an empty retained payload on the same topic.

```json
{
  "name": null,
  "unique_id": "jeedom_house_alarm",
  "state_topic": "jeedom/alarm/state",
  "command_topic": "jeedom/alarm/set",
  "availability_topic": "jeedom/alarm/availability",
  "payload_available": "online",
  "payload_not_available": "offline",
  "supported_features": ["arm_home", "arm_away", "arm_night"],
  "code": "REMOTE_CODE",
  "code_arm_required": false,
  "code_disarm_required": true,
  "command_template": "{\"action\":\"{{ action }}\",\"code\":\"{{ code }}\"}",
  "device": {
    "identifiers": ["jeedom_alarm"],
    "name": "Alarm",
    "manufacturer": "Jeedom",
    "model": "Alarm bridge",
    "suggested_area": "Hallway"
  }
}
```

- `REMOTE_CODE`: the app sends what the user types, and Jeedom checks it. Never put the real PIN in `code`, because the config is retained in clear. `REMOTE_CODE_TEXT` gives a text field instead of the keypad.
- `supported_features`: only the modes Jeedom has (`arm_vacation`, `arm_custom_bypass` also exist). `trigger` is ignored.

### A.2 State (retained, on every change)

Topic `jeedom/alarm/state`, a plain string, one of:
- `disarmed`;
- `armed_home`, `armed_away`, `armed_night` (`armed_vacation`, `armed_custom_bypass`);
- `arming`: exit delay;
- `pending`: entry delay;
- `triggered`;
- `disarming` (optional).

### A.3 Commands (subscribe; not retained)

Topic `jeedom/alarm/set`, payload `{"action":"ARM_AWAY","code":""}` or `{"action":"DISARM","code":"1234"}`. The actions are `ARM_HOME`, `ARM_AWAY`, `ARM_NIGHT` (plus `ARM_VACATION`, `ARM_CUSTOM_BYPASS` if announced) and `DISARM`.

The bridge:
1. Validates `code` when the action requires one.
2. Applies the mode and publishes the resulting state (`arming`, then the armed state).
3. On a rejected code, **re-publishes the current state**.
4. Publishes some state **within 5 s** in every case, so the app's optimistic update is confirmed or cleanly rolled back.

Codes containing `"` or `\` break the JSON command template, so use digits or letters only.

### A.4 Availability (retained)

Topic `jeedom/alarm/availability`, `online` / `offline`. Publish `online` at bridge start, and set the MQTT last will to a retained `offline` on that topic.
