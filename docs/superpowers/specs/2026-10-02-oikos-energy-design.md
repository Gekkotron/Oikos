# Oikos — Energy screen: live power flow for any house

Status: approved design, 2026-10-02. Parent spec: `2026-09-24-oikos-design.md` §5.2 (Energy) and §11 (phase 1). It builds the `:feature:energy` module that the parent spec lists, and generalises its fixed roles so the screen works for Home Assistant users too, not only for this house. The layout is option **B** in `docs/design/mockups/energy-dynamic-layout.html`: the approved `energy-layout.html` option A, with consumers placed on an arc.

## 1. Goal

A live **Energy** tab shows where power flows right now:

- **Solar** is at the top, the **house** in the centre and the **grid** on the left.
- **Consumers** sit on an arc to the right of the house.
- A **battery** or **water** takes the bottom-left slot.

Lines get thicker and faster with power, and their direction follows import or export. Values are live only.

The screen must fit any mix of entities: one meter or several, a signed grid sensor or an import/export pair, with or without a battery, and any number of consumers. Nothing about this house is hard-coded (parent spec goal 7).

Out of scope:
- History, and "today" kWh totals (phase 2, the gateway `/history` endpoint).
- Extra values linked to a node, such as the water heater temperature (phase 2, the detail screen).
- Auto-tagging roles from Home Assistant's `energy/get_prefs` (phase 4, with `HomeAssistantSource`).
- Widgets (phase 3).
- Security and Settings tabs (their own sub-projects).

### 1.1 This house, as the reference case

| Entity | Role | Notes |
|---|---|---|
| `powers/grid_solar` PowerA | `grid` | signed: + import, − export |
| `powers/grid_solar` PowerB | `solar` | |
| `powers/ac_water_heater` PowerA | `consumer` | AC devices |
| `powers/ac_water_heater` PowerB | `consumer` | water heater |
| — | `house` derived | grid + solar; AC and the water heater are already inside it |

The EnergyA and EnergyB counters are not used in this sub-project.

## 2. Roles and the model

`EnergyRole` lives in `UiOverride.energyRole`, which is retained on `oikos/ui/<unique_id>` and so shared by every phone.

| Role (JSON) | Count | Meaning |
|---|---|---|
| `solar` | 0..n, summed | production; a negative value is clamped to 0 |
| `grid` | 0..n, summed | signed: + import, − export |
| `grid_export` | 0..n, summed | positive export power; if present, grid = grid − grid_export |
| `battery` | 0..n, summed | signed: + discharge (feeds the house), − charge |
| `house` | 0..n, summed | measured total load; if absent, it is derived (§3) |
| `consumer` | any, one node each | a branch out of the house |
| `water` | 0..1 | flow or volume, shown with its own unit; not part of the power maths |

Rules:

- **`UiOverride.energyInvert: Boolean = false`** (new) flips the sign of a `grid` or `battery` entity, because meters and HA integrations differ. The Customize sheet shows the switch only for those two roles. It is ignored for every other role.
- **Migration.** The JSON value `water_heater` decodes as `consumer` (a `@JsonNames` alias on the enum entry). Its `@SerialName` is `consumer`, so a re-saved override writes the new value. If the alias does not work on enum entries in the pinned kotlinx-serialization, a custom enum serializer does the same mapping.
- **Older apps.** `OikosJson` already sets `coerceInputValues`. An older app that reads `consumer`, `battery` or `grid_export` treats the entity as having no role and does not crash.
- **Several entities on one summed role.** Two `solar` inverters, for example, are added up into one node labelled with the role name ("Solar"), not with an entity name.
- **Eligible entities** are `Kind.SENSOR` with `StateValue.Numeric`.
  - **Power units** come from `Entity.unit`: `W` × 1, `kW` × 1000, `MW` × 1 000 000, case-insensitive.
  - **Other units** make a power role count as **unknown** (§3), and the Customize sheet shows "Needs a power unit (W, kW)".
  - **`water`** accepts any unit and shows it as is.
- **Consumer labels.** A consumer node shows the entity's effective name (override name, then entity name) and a generic duotone "plug" glyph.

## 3. The arithmetic

Notation: all values are in watts, after inversion and unit conversion. **S** is solar, **G** is grid (G = Σgrid − Σgrid_export), **B** is battery and **H** is house.

- **Inputs.** A role with no entity contributes nothing, and its node is hidden. A role whose entities include one that is unknown, unsupported, unavailable or has a bad unit is **unknown**. A stale value still counts as known (it is dimmed, §5).
- **House.**
  - If any `house` entity exists, H = Σhouse.
  - Otherwise, **H = S + G + B**, computed only when every present input among S, G and B is known; else H is unknown.
  - If at least one of S, G or B is present, the house node is shown. If none is, and there is no `house` entity, the house node still shows if consumers exist, with value "—"; otherwise the screen is empty (§5).
- **Negative derived house.** A derived H < 0 (a sign mistake, for example) is shown as 0, with a one-line hint "Check the grid or battery sign".
- **Self-consumption** = (S − export) / S, where export = max(0, −G). It appears in the subtitle only when S ≥ 50 W and both S and G are known. If there is no grid entity, export is 0.
  - Example (this house): S = 3200, G = −400 → H = 2800, self-consumption (3200 − 400) / 3200 = 87 %.
- **Consumers** are not used to compute H. If Σconsumers > H, nothing is corrected; each consumer simply shows its own value.

## 4. Flows and the diagram

**Edges** form a star through the house:

| Edge | Direction |
|---|---|
| solar → house | always inward |
| grid ↔ house | inward when G > 0, outward when G < 0 |
| battery ↔ house | inward when B > 0 (discharge), outward when B < 0 (charge) |
| house → consumer | always outward |

Water has no edge. Its node is drawn with a dashed outline.

**Line look.**
- Thickness and dash speed scale with |P| on a log scale: 10 W → 1 dp, 10 kW and above → 6 dp. The speed scales the same way.
- Below 10 W, or when the edge's value is unknown, the line is drawn as a **static faint** path (alpha 0.25) with no animation.
- Reduced motion (the system animator scale, the same check as the device icons) keeps thickness and stops the dash.

**Positions (layout B).**
- Solar is at the top centre, the house in the centre and the grid at the centre left.
- Consumers sit on an arc to the right of the house, evenly spaced from upper right to lower right.
- The bottom-left slot holds the battery if present, otherwise water. When both exist, water moves to the cards only.
- Nodes are placed by rules, so a missing role leaves an empty space and nothing shifts strangely.

**Consumer overflow.**
- With up to 3 consumers, all are drawn.
- With 4 or more, the arc shows the 2 largest, then a **"+N" node** whose value is the sum of the rest. Tapping it opens `ConsumersSheet`, which lists every consumer with its value.
- **Order:** `UiOverride.order` first when set, then power from high to low. The power order is recomputed **at most every 5 s**, so nodes do not jump around with every message. Values update live; only the order is throttled.

**Value labels** use the existing Home formatting: kW with one decimal at ≥ 1000 W, otherwise W. The grid label adds ↙ for import and ↗ for export.

## 5. Screen, states and navigation

- **Header:** the title "Energy", the `LinkPill`, and the subtitle "Live · self-consumption 87 %". Only "Live" shows when self-consumption is hidden.
- **Diagram:** `FlowDiagram`, a Canvas, in a glass surface as in the mockup.
- **Cards** under the diagram: one small card per drawn node (Solar, Grid, Battery, House, each consumer, Water), with its live value. They use the same per-entity states as Home.
- **Entity states (as on Home):**
  - unknown → "—" and the edge is static faint
  - `stale` → node and card dimmed
  - `available = false` → greyed out with "Unavailable"
- **Empty state:** if no entity has an energy role, the screen shows "Long-press a power sensor on Home and choose an energy role", with a button that switches to the Home tab.
- **Link down:** the same `LinkPill` states as Home. Cached values are shown dimmed.

**Navigation (`:app`).**
- A Material 3 `NavigationBar` with **Home** and **Energy** only. Security and Settings appear when they ship; nothing is shown disabled.
- The bar shows on the main tabs, not on Onboarding or Rooms.
- **`EnergyKey`** is added next to `HomeKey`.
  - Selecting a tab **replaces** the top tab entry rather than stacking it.
  - Each tab keeps its own saved state and ViewModel.
  - Back from Energy returns to Home; back from Home leaves the app.
- The bar handles the bottom insets, so screens keep their snackbar above it.

**Customize sheet (`:feature:home`).**
- The role chips become: None, Solar, Grid, Grid export, Battery, House, Consumer, Water.
- An **"Invert sign"** switch appears when the role is Grid or Battery.
- The unit hint from §2 shows under the chips.

**Accessibility.**
- The diagram has one merged content description read as a sentence, for example: "Solar 3.2 kilowatts to the house. House uses 2.8 kilowatts. Exporting 0.4 kilowatts to the grid. Water heater 1.7 kilowatts. AC 0.6 kilowatts."
- The cards are focusable for TalkBack and D-pad.
- Colour is never the only cue: every node has a glyph and a value, and the grid value has ↙/↗.
- Contrast is ≥ 4.5:1 in both themes.

**Strings** are in English and French.

## 6. Architecture

New module **`:feature:energy`**, laid out like `:feature:home`:

| File | Role |
|---|---|
| `presentation/EnergyModels.kt` | `EnergyUiState(nodes, edges, selfConsumption, hint, empty, link)`, `EnergyNode(id, role, label, watts, display, status)`, `EnergyEdge(from, to, watts, direction)` |
| `presentation/EnergyFlowBuilder.kt` | pure object: `build(entities, states, link, locale, now) → EnergyUiState`; §2–§4 maths, the overflow fold and the throttled order (the last order and its time are passed in and returned) |
| `presentation/EnergyViewModel.kt` | `combine(source.entities, source.states(), source.link)` → builder, `conflate()`, `flowOn(Default)`, `stateIn(WhileSubscribed(5 s))`, the same as `HomeViewModel` |
| `ui/FlowDiagram.kt` | Canvas: node positions, edges with an animated dash, glyphs, labels, reduced motion |
| `ui/EnergyScreen.kt` | `EnergyRoute`, header, diagram, cards, empty state |
| `ui/ConsumersSheet.kt` | the "+N" list |

Other changes:

| Module | Change |
|---|---|
| `:core:model` | the new `EnergyRole` entries and the `water_heater` alias; `UiOverride.energyInvert` |
| `:core:designsystem` | a "plug" duotone glyph if no existing one fits; the solar, grid, battery, house and water glyphs in the same style |
| `:core:demo` | tagged demo entities (solar, signed grid, two consumers, water) so previews and the demo mode show a full diagram |
| `:feature:home` | the Customize sheet roles, the invert switch and the unit hint; `OverrideDraft` carries `energyInvert` |
| `:app` | `EnergyKey`, the `NavigationBar` scaffold, the module dependency |

The builder only uses `:core:model` types, so it can move to a core module unchanged when widgets need it (phase 3).

## 7. Testing

The tools are those of `:feature:home`: JUnit and Robolectric Compose tests, with no screenshot library. Kover is unchanged. `./gradlew check` must stay green.

- **`EnergyFlowBuilderTest`** (the bulk):
  - inversion for grid and battery
  - grid − grid_export
  - battery charge and discharge directions
  - measured versus derived house
  - house unknown when an input is unknown
  - negative derived house → 0 + hint
  - W/kW/MW conversion and a bad unit → unknown
  - two entities summed on one role
  - the self-consumption threshold and its formula (this house → 87 %)
  - the "+N" fold with 4+ consumers
  - order by `UiOverride.order`, then power, throttled to 5 s
  - stale and unavailable states
  - the empty state
- **`UiOverride` decoding:** `energyInvert` round-trips; `water_heater` → `consumer`; an unknown role → null.
- **`EnergyViewModelTest`** with a fake `EntitySource`, following `HomeViewModelTest`.
- **`EnergyScreenTest`:** the empty state and its button; the content description sentence; the "+N" node opens the sheet.
- **`CustomizeSheetTest`:** the new role chips; the invert switch only for Grid and Battery; the unit hint.
- **Navigation:** the bar switches tabs; back from Energy returns to Home; the bar is hidden on Onboarding and Rooms.

## 8. Risks

- **Derived house accuracy** depends on every input being tagged with the right sign. The negative-house hint catches the common mistake; a wrong but positive value cannot be detected.
- **The consumer order throttle** makes the arc lag behind a sudden change by up to 5 s. Values are always live; only positions lag.
- **The JSON alias on enum entries** must be checked against the pinned kotlinx-serialization version; §2 gives the fallback.
