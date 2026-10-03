# Oikos — Security actions: switches and buttons on the Security tab

Status: approved design, 2026-10-03. Parent spec: `2026-10-02-oikos-security-design.md`. It reverses that spec's "Sirens on the tab" exclusion (§1): a siren the house exposes as a switch can now be put on Security, along with any other switch or button the user chooses.

Nothing is house-specific. This house's siren `alarm/siren_living` (Tuya TS0216) is discovered as a `switch`; other houses may expose sirens, floodlights or scene buttons the same way.

## 1. Goal

From the Security tab, the user fires **actions**: turns opted-in switches on or off and presses opted-in buttons. Example: ring the siren, then stop it.

Decisions:
- **Opt-in, any switch or button.** No entity is an action by default; the user turns "Show on Security" on per entity.
- **Per-action confirmation.** "Ask before turning on" is on by default. Turning a switch on or pressing a button asks first when it is set; turning a switch off is always immediate.
- **Same Home rule as sensors.** An action on Security leaves Home unless "Keep on Home" (`homePinned`) is set.

Out of scope:
- Auto-detecting sirens (the HA MQTT `siren` platform is not discovered today; no `SIREN` kind).
- Siren tone, volume or duration (the TS0216 `warning` composite).
- Ringing the siren automatically on `triggered`: still the alarm system's job.
- Other kinds as actions (lights, covers, selects…).

## 2. Model (`:core:model`)

`UiOverride` gains two fields; defaults are not encoded, so retained `oikos/ui/<unique_id>` JSON written before this change decodes and re-encodes unchanged.

```kotlin
/** A SWITCH or BUTTON shown in Security's Actions section (opt-in). */
val securityAction: Boolean = false,
/** Ask before turning this action on or pressing it; only read when [securityAction]. */
val securityAsk: Boolean = true,
```

`Placement`:

```kotlin
/** A kind that can be opted into Security as an action. */
fun actionKind(kind: Kind): Boolean = kind == Kind.SWITCH || kind == Kind.BUTTON

fun onSecurity(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
    (!ui.securityHidden && securityKind(kind, deviceClass)) || (ui.securityAction && actionKind(kind))
```

`securityKind` keeps meaning "shown on Security by default" (alarm panels and sensors of the security classes). `elsewhere` and `onHome` are unchanged, so an opted-in action leaves Home unless pinned.

## 3. Security tab (`:feature:security`)

### 3.1 UI model

```kotlin
/** [on] null: a button, or a switch with no state yet. [areaName] null: Unassigned. */
data class ActionRow(
    val id: String,
    val name: String,
    val areaName: String?,
    val kind: Kind,
    val on: Boolean?,
    val available: Boolean,
    val stale: Boolean,
    val ask: Boolean,
)
```

`SecurityUi` gains `actions: List<ActionRow>`, plus `action(id)` like `panel(id)`. `SecurityUiBuilder` takes visible entities whose kind is `actionKind` (`partOf == null`, `Placement.onSecurity`), sorted by area (Unassigned last), name, id. A switch's `on` comes from `StateValue.OnOff`; a button's is null.

### 3.2 Screen

Order: header, alarm panels, **Actions**, summary, sensor sections, alerts placeholder. The Actions section shows only when `actions` is not empty, with a section title "Actions" and two-column tiles like the sensor tiles (`OikosCard` with `minHeight`): name, area, and a state line ("On" / "Off" / "Unavailable"; nothing for a button). The glyph is `OikosGlyph.PLUG` for a switch (accent tint when on) and `OikosGlyph.PLAY` for a button, as Home's `KindIcon` does.

The empty state ("nothing on Security") now requires no panels, no sensors **and** no actions. The "no panel" hint stays as it is.

### 3.3 Tap

| Tile | `ask` | Tap does |
|---|---|---|
| Switch, on | any | `Command.TurnOff` |
| Switch, off or unknown | false | `Command.TurnOn()` |
| Switch, off or unknown | true | confirm dialog, then `Command.TurnOn()` |
| Button | false | `Command.Press` |
| Button | true | confirm dialog, then `Command.Press` |

The dialog is an `AlertDialog`: title "Turn on {name}?" or "Press {name}?", confirm "Turn on" / "Press", dismiss "Cancel". It survives rotation (`rememberSaveable` holding the action id). If the entity disappears or becomes unavailable while the dialog is open, the dialog closes without sending.

An unavailable tile is disabled. A stale tile sends nothing and shows the existing `WaitingForConnection` snackbar: `SecurityViewModel.send` checks `state.value.action(id)?.stale` as it does for panels.

`Command.Press` is the command Home already sends for `TapAction.PRESS`.

### 3.4 Long-press: action sheet

Long-press on a tile (and the TalkBack custom action "Settings") opens `ActionSheet`, modelled on `SensorSheet`: name, area, state, then

- **Show on Security** (`securityAction`). Turning it off saves and the tile disappears; the sheet closes.
- **Ask before turning on** (`securityAsk`).

`SecurityViewModel.setAction(id, show: Boolean, ask: Boolean)` saves `entity.ui.copy(securityAction = show, securityAsk = ask)` through `source.setOverride`, reporting failures as `SecurityEvent.SaveFailed`, like `setSecurityHidden`.

## 4. Home customise sheet (`:feature:home`)

For `Placement.actionKind(card.kind)`:
- **Show on Security** switch bound to `securityAction`.
- **Ask before turning on**, shown only while Show on Security is on, bound to `securityAsk`.

`OverrideDraft` carries `securityAction` and `securityAsk` (from the card's `ui`, back into the saved override). The sheet's `elsewhere` expression, today `energyRole != null || (securityKind && !securityHidden)`, becomes `energyRole != null || Placement.onSecurity(kind, deviceClass, draftUi)`, so "Keep on Home" appears for an opted-in action too.

Once an action leaves Home, the Security long-press sheet is where its settings are reached (and Home's customise sheet, if pinned).

## 5. Demo house (`:core:demo`)

- A new switch `demo_siren` "Siren" in the Hallway, retained override `{"securityAction":true}` (asks before ringing).
- `demo_alarm_intrusion` "Simulate intrusion" gets `{"securityAction":true,"securityAsk":false}`.

## 6. Strings

`:feature:security`: `security_section_actions` "Actions", `security_action_on` "On", `security_action_off` "Off", `security_action_confirm_on` "Turn on %1$s?", `security_action_confirm_press` "Press %1$s?", `security_action_turn_on` "Turn on", `security_action_press` "Press", `security_action_ask` "Ask before turning on", `security_action_settings` "Settings", `security_cancel` "Cancel" (as Settings has `settings_cancel`). `security_show` ("Show on Security") and `security_unavailable` already exist and are reused.

`:feature:home`: `customize_ask_before` "Ask before turning on" (reuses `customize_show_on_security`).

French translations alongside, as for the existing strings.

## 7. Testing

- `PlacementTest`: a switch or button is on Security only with `securityAction`; it then leaves Home unless `homePinned`; `securityAction` on a sensor or light does nothing.
- `UiOverrideTest`: the new fields round-trip; `UiOverride()` still encodes to `{}`; `securityAsk = false` is encoded.
- `SecurityUiBuilderTest`: actions built and sorted; buttons have `on = null`; hidden/`partOf` entities left out; empty state counts actions.
- `SecurityViewModelTest`: a stale action sends nothing and emits `WaitingForConnection`; `setAction` saves both fields; a save failure emits `SaveFailed`.
- Compose (`SecurityScreenTest` or a new `ActionTileTest`): with `ask`, tapping an off switch shows the dialog and sends only after confirm; without `ask` it sends directly; tapping an on switch sends `TurnOff` with no dialog; long-press opens the sheet.
- `CustomizeSheetTest`: the toggles appear for switches and buttons only; "Ask before" hides when Show on Security is off; "Keep on Home" appears for an opted-in action.
