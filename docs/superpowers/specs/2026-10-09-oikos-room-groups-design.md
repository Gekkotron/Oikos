# Oikos — Room groups (shutters and lights) design

Status: approved design, 2026-10-09. Mockups: option A of the "Shutter grouping options" artifact. It replaces, for now, the planned card sparklines + event log sub-project, which stays queued.

## 1. Goals and scope

**Goal.** A room with several shutters (or lights) shows one group card that drives them all, instead of one card per device. The everyday case, "open/close every shutter of the living room", is one tap.

**User decisions**
- One wide group card replaces its members on Home (mockup option A).
- Groups are **automatic per room**: no setup is needed.
- Kinds: **covers and lights**.
- A light group card **toggles all on tap**; long-press opens its sheet. A shutter group card opens its sheet on tap and has inline ▲ ■ ▼.
- Approach: grouping is done in the **Home presentation layer** (`HomeUiBuilder`), not as synthetic entities in the source.

**Out of scope**
- Group tiles in widgets (the grouping function is pure, so a later widget phase can reuse it).
- Z2M / Zigbee groups, cross-room groups, hand-made groups.
- Groups of other kinds (switches, climate…).
- A group-level light colour.

## 2. Group membership

A group is keyed by `(areaId, kind)` with `kind ∈ {COVER, LIGHT}`. Its members are the entities that:
1. are standalone (`partOf == null`);
2. have that kind and that area, and the area is not `UNASSIGNED_AREA`;
3. are shown on Home (`Placement.onHome`, so not hidden and not taken by Energy or Security unless `homePinned`);
4. are not the hero;
5. have `ui.ungrouped == false`.

A group exists when it has **at least 2 members**. Otherwise its devices stay as single cards.

### 2.1 Where groups show
| Home view | Groups |
|---|---|
| All | yes |
| Room chip | yes |
| Favourites | no: favourite members show as single cards, as today |
| Search | no: every device is found on its own |
| Hidden | no |

The group card takes the sort position of its first member under the existing order (`ui.order`, area index, name, id). Room chips are computed as today, from the members' areas.

## 3. The group card

- `CardUi.id = "group:<areaId>:<kind>"` (kind lowercase: `cover`, `light`), `kind` = the members' kind, `wide = true`.
- New `CardUi.members: List<CardUi>` (the member cards, in sort order; empty for a single card). `HomeUiState.card(id)` also finds group cards and their members.
- Name: `Area.groupNames[kind]` if set, else the localized default "Shutters" / "Lights" (FR "Volets" / "Lumières"). A `×N` badge shows the member count.
- `available` = any member available; `pending` = any member pending; `stale` = any member stale.
- `hasSheet = true`; `hidden = false`; `hasHistory = false`.

### 3.1 Shutter group
- `value = CardValue.CoverGroup(open, closed, moving: CoverState?, positions: List<Int?>)`.
- Text: "2 open · 1 closed"; "All open" / "All closed" when uniform; "Opening…" / "Closing…" while any member moves (opening wins over closing if both). A member is "open" by the existing single-card `isOn` rule.
- `on` = any member open.
- Inline ▲ ■ ▼ buttons on the card; under them one mini bar per member showing its position (a member without position shows full when open, empty when closed).
- `tap = OPEN_SHEET`.

### 3.2 Light group
- `value = CardValue.LightGroup(onCount, total)`. Text: "2 of 3 on", "All on" or "All off".
- `on` = any member on.
- `tap = TOGGLE` (group toggle, §5); long-press opens the group sheet.

## 4. Group sheets

The existing `ControlSheet` gains a group mode, chosen when `card.members` is not empty.

### 4.1 Shutter group sheet
- Header: group name; subtitle "<room> · N shutters".
- Open / Stop / Close (sent to the group).
- **"All to"** slider, shown when at least one member supports position. Initial value = the rounded mean of the known member positions. On release: `SetPosition` sent to the members that support position only.
- One row per member: name, state text ("Open · 80%"), its own ▲ ■ ▼. Tapping the row swaps the sheet to that member's existing single sheet; Back returns to the group sheet.
- A "Customize group" button.

### 4.2 Light group sheet
- Header as above ("N lights").
- Master on/off switch (sends the group toggle's absolute command).
- **Brightness** slider, shown when at least one member is dimmable; on release `TurnOn(brightness)` goes to dimmable members only.
- One row per member with its own switch; tapping the row opens that light's existing full sheet (hue wheel, effects, White chip, companions); Back returns.
- A "Customize group" button.

## 5. Sending to a group

`HomeViewModel.sendGroup(groupId: String, command: Command)`:
1. If any member is stale → `HomeEvent.WaitingForConnection`, nothing sent (entity cache spec §3.6).
2. For every **available** member, launch `source.send(memberId, command)` **in parallel**, so the motors start together. Unavailable members are skipped.
3. Each send keeps its own optimistic pending state and confirmation, as for a single card.
4. `Rejected` results from one group send become a single `HomeEvent.Rejected`.

Commands per action:
- ▲ / ■ / ▼ → `Open` / `Stop` / `Close`.
- "All to" → `SetPosition(p)` to members with position.
- Light group toggle (card tap or master switch): any member on → `TurnOff`, else `TurnOn()`. It is absolute, so Retry repeats the intent.
- Brightness → `TurnOn(brightness)` to dimmable members.

**Failures.** `source.commandFailures` for members of a group sent within the same send are merged into one `HomeEvent.GroupNotConfirmed(groupId, failedIds, command)`: "Patio door didn't respond" for one member, "2 shutters didn't respond" / "2 lights didn't respond" for several. The merge window is 1 s from the first failure. **Retry** resends the command to the failed members only.

## 6. Customize and storage

Both fields are retained on MQTT and so synced across phones. The shared JSON decoder ignores unknown keys, so older app versions keep working.

- `UiOverride.ungrouped: Boolean = false`: the device leaves its room's group and shows as a single card.
- `Area.groupNames: Map<String, String> = emptyMap()`: a custom group name per kind key (`cover`, `light`), published in `oikos/ui/_areas`. A blank name removes the entry.

**Group Customize** (long-press the shutter group card; "Customize group" in either group sheet):
- name field;
- one checkbox per member (unticking sets `ungrouped = true` on that device and publishes its override);
- "Ungroup all" sets `ungrouped = true` on every member.
- Saving goes through `OverrideEditor` (a new `setGroupName(areaId, kind, name)` that republishes the areas document, plus the existing `save` per member). Any failed publish → `HomeEvent.SaveFailed`.

**A device's own Customize** gains a switch "Group with the other shutters/lights in this room", shown for a cover or light in a real room that has at least one other eligible device. On = `ungrouped = false`.

No separate room-level "groups off" flag: ungrouping every member has the same effect.

## 7. Edge cases
- Hiding a member, or Energy/Security taking it, removes it from the group; with fewer than 2 members left the group dissolves.
- Moving a device to another room makes it join that room's group.
- A member that is unavailable still counts as a member and shows "Unavailable" in its row; it is skipped by group sends.
- Hero card and light companions (LED effect speed, palette…) are unchanged; companions stay in their light's own sheet.
- Widgets, Energy and Security are unchanged.

## 8. Accessibility and strings
- Group card content description: "Shutters, Living room, 2 open, 1 closed" / "Lights, Living room, 2 of 3 on".
- Inline buttons: "Open all shutters", "Stop all shutters", "Close all shutters". Light card state description: on / off, like single toggles.
- Every new string in English and French.

## 9. Demo
`DemoHouse`: the living room gets three shutters (one without position) and three lights (one RGB, one dimmable, one on/off), so previews, screenshots and demo mode show both groups.

## 10. Testing
- **Unit — `RoomGroups`** (pure, in `:feature:home` presentation): 2-member threshold; hidden, Unassigned, `ungrouped`, Energy/Security-placed and hero entities excluded; Favourites / search / Hidden never group; sort position; aggregate value, `on`, `available`, `pending`, `stale`; custom vs default name.
- **Unit — `HomeViewModel`**: parallel fan-out, unavailable members skipped, stale guard, slider/brightness only to capable members, absolute light toggle, failure merge (1 s window) and Retry to failed members only.
- **Unit — Customize**: member checkbox → `ungrouped`; "Ungroup all"; group rename republishes areas; device switch.
- **Robolectric screenshots**: shutter group card, light group card, both group sheets, in Night Glass and Warm Minimal.
- **Device checklist**: real living-room shutters (all open, all close, stop, all to 50 %, one shutter alone from the sheet); the room's lights (toggle all, brightness); ungroup and regroup; rename; the change visible on a second phone.
