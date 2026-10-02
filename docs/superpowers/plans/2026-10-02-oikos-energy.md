# Energy Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A live Energy tab that draws where power flows right now (solar, grid, battery, house, any number of consumers, water) from the entities the user tagged with an energy role, for this house and for Home Assistant users.

**Architecture:**
- A new `:feature:energy` module mirrors `:feature:home`.
- A pure `EnergyFlowBuilder` turns `Entity` and `EntityState` maps into an `EnergyUiState`; `EnergyViewModel` combines the `EntitySource` flows into it.
- A Compose `FlowDiagram` (a Canvas for the edges, plus placed composables for the nodes) draws it.
- `:app` gains a two-tab `NavigationBar`.
- Shared strings, `LinkPill` and the new glyphs move to `:core:designsystem`. The roles and the invert flag live in `:core:model`.

**Tech Stack:** Kotlin 2.4, Jetpack Compose (Material 3), Hilt, kotlinx-serialization 1.11, Navigation 3, JUnit 4 + Robolectric + Compose UI test, Kover.

**Spec:** `docs/superpowers/specs/2026-10-02-oikos-energy-design.md`

## Global Constraints

- Nothing house-specific in the app: no hard-coded entity ids, topics or names outside `:core:demo` (parent spec goal 7).
- UI strings in English **and** French (`values/` and `values-fr/`) for every new string.
- Attribution: commits use `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …` and end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Never write the user's real name anywhere.
- JSON role values exactly: `solar`, `grid`, `grid_export`, `battery`, `house`, `consumer`, `water`; legacy `water_heater` decodes as `consumer`.
- Power units: `W` × 1, `kW` × 1000, `MW` × 1 000 000, case-insensitive; anything else is not a power unit.
- Thresholds: edge idle below **10 W**; self-consumption shown only when solar ≥ **50 W**; consumer re-order at most every **5 000 ms**; at most **3** consumer slots on the arc (2 + "+N" when there are 4 or more).
- Self-consumption = (S − max(0, −G)) / S, **rounded down** to a whole percent (this house: 87 %).
- Contrast ≥ 4.5:1 for text: values and labels use `OikosTheme.colors.content` / `contentMuted`, never the line colours.
- `./gradlew check` green at the end of every task.

## Review Focus

1. **Discovery done, no state yet:** every tagged entity is Unknown. Nodes show "—", there is no "Check sign" hint and no crash (Task 3 test `all unknown shows dashes without hint`).
2. **A tagged entity disappears** (role cleared, or discovery removed) while a cached consumer order is still inside its 5 s window. The order is recomputed at once, not kept with a missing id (Task 3 test `order is recomputed when the consumer set changes`).
3. **Night, grid only:** solar is 0 W or absent. House = grid, and self-consumption is hidden instead of "0 %" (Task 3 test `night hides self consumption`).
4. **A consumer meter reports a small negative value** (CT clamp noise). It shows as 0 W, idle, and not as "−3 W" (Task 3 test `negative consumer is clamped`).
5. **French locale:** decimal comma in values and in the TalkBack sentence ("3,2 kilowatts") (Task 5 test `french formatting`).

---

## File Structure

| File | Responsibility |
|---|---|
| `core/model/.../model/UiOverride.kt` (modify) | `EnergyRole` entries, `water_heater` alias, `energyInvert`, `signed` / `power` helpers |
| `core/model/.../model/PowerUnits.kt` (create) | unit → watts factor, shared by Home and Energy |
| `core/designsystem/build.gradle.kts` (modify) | depends on `:core:transport` for `LinkState` |
| `core/designsystem/src/main/res/values{,-fr}/strings.xml` (create) | pill strings, `link_details`, energy role names |
| `core/designsystem/.../components/LinkPill.kt` (create, moved from `:feature:home`) | connection pill, optionally clickable |
| `core/designsystem/.../icons/StaticIcon.kt` (modify) | `SUN`, `HOUSE`, `PYLON`, `BATTERY` glyphs |
| `feature/home/.../presentation/OverrideDraft.kt` (modify) | carries `energyInvert` |
| `feature/home/.../ui/CustomizeSheet.kt` (modify) | all roles, invert switch, unit hint |
| `feature/energy/build.gradle.kts` (create) | module |
| `feature/energy/.../presentation/EnergyModels.kt` (create) | UI state types |
| `feature/energy/.../presentation/EnergyFlowBuilder.kt` (create) | all §2–§4 maths, fold, throttled order |
| `feature/energy/.../presentation/EnergyFormat.kt` (create) | power and value text |
| `feature/energy/.../presentation/EnergyViewModel.kt` (create) | flows → builder |
| `feature/energy/.../ui/FlowLayout.kt` (create) | pure node positions, stroke width, dash speed |
| `feature/energy/.../ui/FlowDiagram.kt` (create) | Canvas edges + node bubbles + "+N" |
| `feature/energy/.../ui/EnergyDescription.kt` (create) | TalkBack sentence |
| `feature/energy/.../ui/EnergyScreen.kt` (create) | route, header, diagram, cards, empty state |
| `feature/energy/.../ui/ConsumersSheet.kt` (create) | the "+N" list |
| `app/.../navigation/OikosNavHost.kt` (modify) | `EnergyKey`, tab scaffold |
| `app/.../navigation/MainTabs.kt` (create) | `MainTabBar`, `selectTab`, `showsTabBar` |
| `feature/home/.../ui/HomeScreen.kt` (modify) | `HomeRoute` forwards `windowInsets` |
| `core/demo/.../DemoHouse.kt` (modify) | water heater and AC consumers |

Paths use `…` for `src/main/kotlin/com/gekkotron/oikos` (core modules: `…/model`, `…/designsystem`; features: `…/feature/<name>`). Test paths mirror them under `src/test/kotlin`.

---

### Task 1: Design system — shared strings, LinkPill, energy glyphs

**Files:**
- Modify: `core/designsystem/build.gradle.kts`
- Create: `core/designsystem/src/main/res/values/strings.xml`, `core/designsystem/src/main/res/values-fr/strings.xml`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/LinkPill.kt`
- Delete: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkPill.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt` (import)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt` (`energyLabel` string refs)
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml` (remove moved keys)
- Modify: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/StaticIcon.kt`
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/components/LinkPillTest.kt`
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/OikosGlyphTest.kt`

**Interfaces:**
- Produces: `com.gekkotron.oikos.designsystem.components.LinkPill(link: LinkState, onClick: (() -> Unit)?, modifier: Modifier = Modifier)`. A null `onClick` means a plain status, with no click action.
- Produces: `com.gekkotron.oikos.designsystem.R.string.{pill_connecting, pill_direct, pill_gateway, pill_demo, pill_reconnecting, pill_offline, link_details, energy_solar, energy_grid, energy_grid_export, energy_battery, energy_house, energy_consumer, energy_water}`.
- Produces: `OikosGlyph.SUN`, `OikosGlyph.HOUSE`, `OikosGlyph.PYLON`, `OikosGlyph.BATTERY`.

- [ ] **Step 1: Write the failing tests**

`core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/components/LinkPillTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class LinkPillTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `clickable pill reports clicks`() {
        var clicks = 0
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LinkPill(LinkState.Connected(TransportMode.GATEWAY), onClick = { clicks++ }) } }
        compose.onNodeWithText("Gateway").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `pill without onClick has no click action`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LinkPill(LinkState.Connecting, onClick = null) } }
        compose.onNodeWithText("Connecting…").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
```

`core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/OikosGlyphTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.icons

import org.junit.Test
import kotlin.test.assertTrue

class OikosGlyphTest {
    @Test
    fun `energy glyphs parse to non empty paths`() {
        for (glyph in listOf(OikosGlyph.SUN, OikosGlyph.HOUSE, OikosGlyph.PYLON, OikosGlyph.BATTERY)) {
            assertTrue(glyph.paths.isNotEmpty(), "$glyph has no parts")
            assertTrue(glyph.paths.all { (path, _) -> !path.isEmpty }, "$glyph has an empty path")
        }
    }
}
```

If `OikosGlyphTest` needs Robolectric because `PathParser.toPath()` touches `android.graphics`, add `@RunWith(AndroidJUnit4::class)` and the `androidx.test.ext.junit.runners.AndroidJUnit4` / `org.junit.runner.RunWith` imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*LinkPillTest' --tests '*OikosGlyphTest'`
Expected: compilation FAILS (`LinkPill`, `LinkState`, `SUN` … unresolved).

- [ ] **Step 3: Add the transport dependency**

`core/designsystem/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
}

android {
    namespace = "com.gekkotron.oikos.designsystem"
}

dependencies {
    // LinkState for the shared connection pill.
    implementation(project(":core:transport"))
}
```

- [ ] **Step 4: Move the strings**

Create `core/designsystem/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="pill_connecting">Connecting…</string>
    <string name="pill_direct">Wi-Fi direct</string>
    <string name="pill_gateway">Gateway</string>
    <string name="pill_demo">Demo</string>
    <string name="pill_reconnecting">Reconnecting…</string>
    <string name="pill_offline">Offline</string>
    <string name="link_details">Connection details</string>
    <string name="energy_solar">Solar</string>
    <string name="energy_grid">Grid</string>
    <string name="energy_grid_export">Grid export</string>
    <string name="energy_battery">Battery</string>
    <string name="energy_house">House</string>
    <string name="energy_consumer">Consumer</string>
    <string name="energy_water">Water</string>
</resources>
```

Create `core/designsystem/src/main/res/values-fr/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="pill_connecting">Connexion…</string>
    <string name="pill_direct">Wi-Fi direct</string>
    <string name="pill_gateway">Passerelle</string>
    <string name="pill_demo">Démo</string>
    <string name="pill_reconnecting">Reconnexion…</string>
    <string name="pill_offline">Hors ligne</string>
    <string name="link_details">Détails de la connexion</string>
    <string name="energy_solar">Solaire</string>
    <string name="energy_grid">Réseau</string>
    <string name="energy_grid_export">Export réseau</string>
    <string name="energy_battery">Batterie</string>
    <string name="energy_house">Maison</string>
    <string name="energy_consumer">Consommateur</string>
    <string name="energy_water">Eau</string>
</resources>
```

In **both** `feature/home/src/main/res/values/strings.xml` and `values-fr/strings.xml`, delete these lines:
- `pill_connecting`, `pill_direct`, `pill_gateway`, `pill_demo`, `pill_reconnecting`, `pill_offline`
- `link_details`
- `energy_solar`, `energy_grid`, `energy_house`, `energy_water`

**Keep `energy_water_heater`** for now; Task 2 removes it.

- [ ] **Step 5: Move LinkPill**

Delete `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkPill.kt` and create `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/LinkPill.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode

/** The broker connection status; [onClick] null shows it as a plain status (the Energy header). */
@Composable
fun LinkPill(link: LinkState, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val (label, tone) = when (link) {
        LinkState.Connecting -> R.string.pill_connecting to PillTone.WARNING
        is LinkState.Connected -> when (link.mode) {
            TransportMode.DIRECT -> R.string.pill_direct
            TransportMode.GATEWAY -> R.string.pill_gateway
            TransportMode.DEMO -> R.string.pill_demo
        } to PillTone.CONNECTED
        is LinkState.Reconnecting -> R.string.pill_reconnecting to PillTone.WARNING
        is LinkState.Offline -> R.string.pill_offline to PillTone.OFFLINE
    }
    val clickLabel = stringResource(R.string.link_details)
    StatusPill(
        stringResource(label),
        tone,
        if (onClick == null) {
            modifier
        } else {
            modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
        },
    )
}
```

In `HomeScreen.kt`, add `import com.gekkotron.oikos.designsystem.components.LinkPill`; the call `LinkPill(state.link, onClick = onPillClick)` stays unchanged.

Run `grep -rn "R.string.pill_\|R.string.link_details" feature/home/src` and point every hit at `com.gekkotron.oikos.designsystem.R`. Use an import alias, `import com.gekkotron.oikos.designsystem.R as DsR`, wherever the file already imports the feature's own `R`.

- [ ] **Step 6: Point CustomizeSheet at the shared role names**

In `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt`, add `import com.gekkotron.oikos.designsystem.R as DsR` and replace `energyLabel`:

```kotlin
@Composable
private fun energyLabel(role: EnergyRole): String = stringResource(
    when (role) {
        EnergyRole.SOLAR -> DsR.string.energy_solar
        EnergyRole.GRID -> DsR.string.energy_grid
        EnergyRole.HOUSE -> DsR.string.energy_house
        EnergyRole.WATER_HEATER -> R.string.energy_water_heater
        EnergyRole.WATER -> DsR.string.energy_water
    },
)
```

- [ ] **Step 7: Add the glyphs**

In `StaticIcon.kt`, add these entries to `OikosGlyph` before `DOT`:

```kotlin
    SUN(listOf(area("M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z"), line("M12 2.5V5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"))),
    HOUSE(listOf(area("M4 11 12 4l8 7v9H4z"), line("M10 20v-5h4v5"))),
    PYLON(listOf(line("M12 3 7 21M12 3l5 18M5 7h14M8.5 12h7M9 7l6 5M15 7l-6 5M7.6 18h8.8"))),
    BATTERY(listOf(area("M5 7h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z"), line("M20.5 10.5v3M8 12h6"))),
```

If `OikosIconsPreview.kt` lists glyphs by hand rather than iterating `OikosGlyph.entries`, add the four new ones to it.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:designsystem:testDebugUnitTest :feature:home:testDebugUnitTest`
Expected: PASS. The existing Home tests that look for "Gateway", "Demo" and so on still find the text, now from the design system resources.

- [ ] **Step 9: Run the full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add core/designsystem feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "refactor(designsystem): shared link pill and energy role names; sun, house, pylon and battery glyphs

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Model roles, invert flag, power units, Customize sheet

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/UiOverride.kt`
- Create: `core/model/src/main/kotlin/com/gekkotron/oikos/model/PowerUnits.kt`
- Modify: `core/model/src/test/kotlin/com/gekkotron/oikos/model/UiOverrideTest.kt`
- Create: `core/model/src/test/kotlin/com/gekkotron/oikos/model/PowerUnitsTest.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraft.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraftTest.kt`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheetTest.kt`

**Interfaces:**
- Consumes: `DsR.string.energy_*` from Task 1.
- Produces: `EnergyRole { SOLAR, GRID, GRID_EXPORT, BATTERY, HOUSE, CONSUMER, WATER }` with `val signed: Boolean` (GRID, BATTERY) and `val power: Boolean` (all but WATER).
- Produces: `UiOverride.energyInvert: Boolean = false`.
- Produces: `PowerUnits.factor(unit: String?): Double?`.
- Produces: `OverrideDraft(name, areaId, favorite, hidden, order, cardStyle, energyRole, energyInvert: Boolean)`.

- [ ] **Step 1: Write the failing model tests**

In `UiOverrideTest.kt`, replace `EnergyRole.WATER_HEATER` with `EnergyRole.CONSUMER` in the existing round-trip test, then add:

```kotlin
    @Test
    fun `legacy water_heater decodes as consumer and re-encodes as consumer`() {
        val decoded = UiOverrides.decode("""{"energyRole":"water_heater"}""")!!
        assertEquals(EnergyRole.CONSUMER, decoded.energyRole)
        assertTrue(UiOverrides.encode(decoded).contains("\"consumer\""))
    }

    @Test
    fun `unknown role decodes as no role and keeps the other fields`() {
        val decoded = UiOverrides.decode("""{"energyRole":"heat_pump","favorite":true}""")!!
        assertEquals(null, decoded.energyRole)
        assertTrue(decoded.favorite)
    }

    @Test
    fun `energyInvert round-trips and is omitted when false`() {
        val inverted = UiOverride(energyRole = EnergyRole.GRID, energyInvert = true)
        assertEquals(inverted, UiOverrides.decode(UiOverrides.encode(inverted)))
        assertFalse(UiOverrides.encode(UiOverride(energyRole = EnergyRole.GRID)).contains("energyInvert"))
    }

    @Test
    fun `new roles use their json names`() {
        assertEquals(EnergyRole.GRID_EXPORT, UiOverrides.decode("""{"energyRole":"grid_export"}""")!!.energyRole)
        assertEquals(EnergyRole.BATTERY, UiOverrides.decode("""{"energyRole":"battery"}""")!!.energyRole)
    }

    @Test
    fun `only grid and battery are signed, only water is not power`() {
        assertEquals(setOf(EnergyRole.GRID, EnergyRole.BATTERY), EnergyRole.entries.filter { it.signed }.toSet())
        assertEquals(setOf(EnergyRole.WATER), EnergyRole.entries.filterNot { it.power }.toSet())
    }
```

Add the `kotlin.test.assertTrue` / `assertFalse` imports if missing.

Create `core/model/src/test/kotlin/com/gekkotron/oikos/model/PowerUnitsTest.kt`:

```kotlin
package com.gekkotron.oikos.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PowerUnitsTest {
    @Test
    fun `power units convert to watts case-insensitively`() {
        assertEquals(1.0, PowerUnits.factor("W"))
        assertEquals(1_000.0, PowerUnits.factor("kW"))
        assertEquals(1_000.0, PowerUnits.factor(" KW "))
        assertEquals(1_000_000.0, PowerUnits.factor("MW"))
    }

    @Test
    fun `other units are not power`() {
        assertNull(PowerUnits.factor(null))
        assertNull(PowerUnits.factor("kWh"))
        assertNull(PowerUnits.factor("°C"))
        assertNull(PowerUnits.factor("VA"))
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:model:test --tests '*UiOverrideTest' --tests '*PowerUnitsTest'`
Expected: compilation FAILS (`CONSUMER`, `energyInvert`, `PowerUnits` unresolved).

- [ ] **Step 3: Implement the model**

In `UiOverride.kt`, add `energyInvert` after `energyRole` and replace the enum:

```kotlin
    val energyRole: EnergyRole? = null,
    /** Flips the sign of a [EnergyRole.signed] entity; meters and HA integrations differ (energy spec §2). */
    val energyInvert: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class EnergyRole {
    @SerialName("solar") SOLAR,
    @SerialName("grid") GRID,
    @SerialName("grid_export") GRID_EXPORT,
    @SerialName("battery") BATTERY,
    @SerialName("house") HOUSE,

    /** `water_heater` is this role's name from before the energy screen (energy spec §2). */
    @SerialName("consumer") @JsonNames("water_heater") CONSUMER,
    @SerialName("water") WATER,
    ;

    /** Its sign means a direction and can be flipped by [UiOverride.energyInvert]. */
    val signed: Boolean get() = this == GRID || this == BATTERY

    /** Its value must be a power ([PowerUnits]). */
    val power: Boolean get() = this != WATER
}
```

Add the imports `kotlinx.serialization.ExperimentalSerializationApi` and `kotlinx.serialization.json.JsonNames`. `:core:model` already uses kotlinx-serialization-json for `OikosJson`.

**Fallback**, only if `legacy water_heater decodes as consumer` still fails because `@JsonNames` is ignored on enum entries:
- Remove `@JsonNames`.
- Annotate the enum with `@Serializable(with = EnergyRoleSerializer::class)`.
- Add a serializer in the same file that encodes `entry.serialName` and decodes by `serialName`, with `"water_heater"` mapping to `CONSUMER` and anything unknown throwing `SerializationException`. `coerceInputValues` then turns the exception into null for the nullable field.

Create `core/model/src/main/kotlin/com/gekkotron/oikos/model/PowerUnits.kt`:

```kotlin
package com.gekkotron.oikos.model

/** The power units the energy screen understands (energy spec §2). */
object PowerUnits {
    /** Watts per one [unit], or null when [unit] is not a power unit. */
    fun factor(unit: String?): Double? = when (unit?.trim()?.lowercase()) {
        "w" -> 1.0
        "kw" -> 1_000.0
        "mw" -> 1_000_000.0
        else -> null
    }
}
```

- [ ] **Step 4: Run the model tests to verify they pass**

Run: `./gradlew :core:model:test`
Expected: PASS.

- [ ] **Step 5: Write the failing Home tests**

In `OverrideDraftTest.kt` add (using the file's existing `sensor` card fixture):

```kotlin
    @Test
    fun `invert is kept for grid and dropped for roles without a sign`() {
        val grid = OverrideDraft.from(sensor).copy(energyRole = EnergyRole.GRID, energyInvert = true).applyTo(sensor)
        assertEquals(true, grid.energyInvert)
        val solar = OverrideDraft.from(sensor).copy(energyRole = EnergyRole.SOLAR, energyInvert = true).applyTo(sensor)
        assertEquals(false, solar.energyInvert)
    }
```

In `CustomizeSheetTest.kt` add:

```kotlin
    @Test
    fun `invert switch shows for grid and battery only and is saved`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power", unit = "W")))
        compose.onNodeWithText("Reset to defaults").performScrollTo()
        compose.onNodeWithText("Solar").performScrollTo().performClick()
        compose.onNodeWithText("Invert sign").assertDoesNotExist()
        compose.onNodeWithText("Battery").performScrollTo().performClick()
        compose.onNodeWithText("Invert sign").assertExists()
        compose.onNodeWithText("Grid").performScrollTo().performClick()
        compose.onNodeWithText("Invert sign").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(energyRole = EnergyRole.GRID, energyInvert = true), saved.single())
    }

    @Test
    fun `power role on a non power unit shows the unit hint`() {
        show(card(testEntity("temp", Kind.SENSOR, deviceClass = "temperature", unit = "°C")))
        compose.onNodeWithText("Reset to defaults").performScrollTo()
        compose.onNodeWithText("Needs a power unit (W, kW)").assertDoesNotExist()
        compose.onNodeWithText("Solar").performScrollTo().performClick()
        compose.onNodeWithText("Needs a power unit (W, kW)").assertExists()
        compose.onNodeWithText("Water").performScrollTo().performClick()
        compose.onNodeWithText("Needs a power unit (W, kW)").assertDoesNotExist()
    }

    @Test
    fun `every role is offered`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power", unit = "W")))
        compose.onNodeWithText("Reset to defaults").performScrollTo()
        for (label in listOf("Solar", "Grid", "Grid export", "Battery", "House", "Consumer", "Water")) {
            compose.onNodeWithText(label).performScrollTo().assertExists()
        }
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*OverrideDraftTest' --tests '*CustomizeSheetTest'`
Expected: compilation FAILS (`energyInvert` is not a parameter of `OverrideDraft`), and `energyLabel` no longer compiles (`WATER_HEATER` is gone).

- [ ] **Step 7: Implement the Home side**

`OverrideDraft.kt`:
- Add the property `val energyInvert: Boolean,` after `energyRole`.
- In `applyTo`, add `energyInvert = energyInvert && energyRole?.signed == true,` after `energyRole = energyRole,`.
- In `from`, add `energyInvert = card.ui.energyInvert,`.

Run `grep -rn "OverrideDraft(" feature/home/src` and add the argument to every positional construction.

`CustomizeSheet.kt`:
- Next to the other draft states, add `var energyInvert by rememberSaveable(card.id) { mutableStateOf(start.energyInvert) }`.
- Build the draft as `OverrideDraft(name, areaId, favorite, hidden, order, cardStyle, energyRole, energyInvert)`.
- Replace the energy `ChipRow { … }` block with:

```kotlin
                ChipRow {
                    FilterChip(selected = energyRole == null, onClick = { energyRole = null }, label = { Text(stringResource(R.string.customize_energy_none)) })
                    EnergyRole.entries.forEach { role ->
                        FilterChip(selected = energyRole == role, onClick = { energyRole = role }, label = { Text(energyLabel(role)) })
                    }
                }
                val role = energyRole
                if (role != null && role.power && PowerUnits.factor(card.unit) == null) {
                    Text(stringResource(R.string.customize_energy_unit_hint), style = OikosTheme.type.label, color = OikosTheme.colors.warning)
                }
                if (role?.signed == true) {
                    SwitchRow(stringResource(R.string.customize_energy_invert), energyInvert) { energyInvert = it }
                }
```

- Replace `energyLabel`:

```kotlin
@Composable
private fun energyLabel(role: EnergyRole): String = stringResource(
    when (role) {
        EnergyRole.SOLAR -> DsR.string.energy_solar
        EnergyRole.GRID -> DsR.string.energy_grid
        EnergyRole.GRID_EXPORT -> DsR.string.energy_grid_export
        EnergyRole.BATTERY -> DsR.string.energy_battery
        EnergyRole.HOUSE -> DsR.string.energy_house
        EnergyRole.CONSUMER -> DsR.string.energy_consumer
        EnergyRole.WATER -> DsR.string.energy_water
    },
)
```

Add `import com.gekkotron.oikos.model.PowerUnits`.

Strings: in `feature/home/src/main/res/values/strings.xml`:
- Remove `energy_water_heater`.
- Add `<string name="customize_energy_invert">Invert sign</string>` and `<string name="customize_energy_unit_hint">Needs a power unit (W, kW)</string>`.

In `values-fr/strings.xml`:
- Remove `energy_water_heater`.
- Add `<string name="customize_energy_invert">Inverser le signe</string>` and `<string name="customize_energy_unit_hint">Nécessite une unité de puissance (W, kW)</string>`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:model:test :feature:home:testDebugUnitTest :core:demo:test`
Expected: PASS.

- [ ] **Step 9: Full check and commit**

Run: `./gradlew check`. Expected: BUILD SUCCESSFUL.

```bash
git add core/model feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(model): generic energy roles (grid export, battery, consumer), invert sign, power units; customize sheet offers them

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `:feature:energy` module and EnergyFlowBuilder

**Files:**
- Modify: `settings.gradle.kts`
- Create: `feature/energy/build.gradle.kts`
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyModels.kt`
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyFlowBuilder.kt`
- Create: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/TestEntities.kt`
- Test: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyFlowBuilderTest.kt`

**Interfaces:**
- Consumes: `EnergyRole` (`signed`, `power`), `UiOverride.energyInvert`, `PowerUnits.factor` from Task 2.
- Produces (package `com.gekkotron.oikos.feature.energy.presentation`):

```kotlin
enum class NodeKind { SOLAR, GRID, BATTERY, HOUSE, CONSUMER, MORE, WATER }
enum class Flow { IN, OUT, IDLE }
enum class NodeStatus { LIVE, STALE, UNAVAILABLE }
data class EnergyNode(val id: String, val kind: NodeKind, val name: String? = null, val value: Double?, val unit: String? = null, val flow: Flow = Flow.IDLE, val status: NodeStatus = NodeStatus.LIVE, val count: Int = 0)
enum class EnergyHint { CHECK_SIGN }
data class ConsumerOrder(val ids: List<String>, val atMillis: Long)
data class EnergyUiState(val loading: Boolean = true, val empty: Boolean = false, val diagram: List<EnergyNode> = emptyList(), val cards: List<EnergyNode> = emptyList(), val consumers: List<EnergyNode> = emptyList(), val selfConsumption: Int? = null, val hint: EnergyHint? = null, val link: LinkState = LinkState.Connecting, val order: ConsumerOrder? = null)
object EnergyFlowBuilder { fun build(entities: Map<String, Entity>, states: Map<String, EntityState>, link: LinkState, nowMillis: Long, previous: ConsumerOrder? = null): EnergyUiState }
```

Role node ids are fixed: `"solar"`, `"grid"`, `"battery"`, `"house"`, `"water"`, `"more"`. Consumer nodes use the entity's `uniqueId`.

- [ ] **Step 1: Create the module**

Append `include(":feature:energy")` to `settings.gradle.kts`, after `include(":feature:onboarding")`.

`feature/energy/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.energy"
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
```

If `feature/home/src/main/AndroidManifest.xml` exists, copy it to `feature/energy/src/main/AndroidManifest.xml` unchanged; otherwise create none.

- [ ] **Step 2: Write the models**

`EnergyModels.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.transport.LinkState

enum class NodeKind { SOLAR, GRID, BATTERY, HOUSE, CONSUMER, MORE, WATER }

/** The direction of a node's edge, relative to the house (energy spec §4). */
enum class Flow { IN, OUT, IDLE }

/** Worst first is the higher ordinal: an aggregate takes the max. */
enum class NodeStatus { LIVE, STALE, UNAVAILABLE }

data class EnergyNode(
    /** "solar", "grid", "battery", "house", "water", "more", or a consumer's unique id. */
    val id: String,
    val kind: NodeKind,
    /** A consumer's name; null for role nodes, which the UI labels with the role name. */
    val name: String? = null,
    /** Watts, always ≥ 0 ([flow] carries the direction); water: its own unit. Null when unknown. */
    val value: Double?,
    /** Water only: the unit as reported. */
    val unit: String? = null,
    val flow: Flow = Flow.IDLE,
    val status: NodeStatus = NodeStatus.LIVE,
    /** [NodeKind.MORE] only: how many consumers it folds. */
    val count: Int = 0,
)

enum class EnergyHint { CHECK_SIGN }

/** The consumer order last shown, and when it was computed (re-ordered at most every 5 s, spec §4). */
data class ConsumerOrder(val ids: List<String>, val atMillis: Long)

data class EnergyUiState(
    /** Nothing discovered yet. */
    val loading: Boolean = true,
    /** Entities exist but none has an energy role. */
    val empty: Boolean = false,
    /** Drawn nodes: roles, up to 3 consumer slots (2 + MORE when folded), water when there is no battery. */
    val diagram: List<EnergyNode> = emptyList(),
    /** The cards under the diagram: role nodes, every consumer, water. */
    val cards: List<EnergyNode> = emptyList(),
    /** Every consumer in display order, for the "+N" sheet. */
    val consumers: List<EnergyNode> = emptyList(),
    /** 0..100, rounded down; null when hidden (spec §3). */
    val selfConsumption: Int? = null,
    val hint: EnergyHint? = null,
    val link: LinkState = LinkState.Connecting,
    val order: ConsumerOrder? = null,
) {
    fun node(id: String): EnergyNode? = diagram.firstOrNull { it.id == id } ?: cards.firstOrNull { it.id == id }
}
```

- [ ] **Step 3: Write the test fixtures**

`feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/TestEntities.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride

object NoBindings : Bindings

fun energyEntity(
    id: String,
    role: EnergyRole?,
    unit: String? = "W",
    name: String = id,
    invert: Boolean = false,
    order: Int? = null,
) = Entity(
    id, null, name, Kind.SENSOR, "power", unit, null, Capabilities(), NoBindings, UNASSIGNED_AREA,
    UiOverride(energyRole = role, energyInvert = invert, order = order),
)

fun numeric(id: String, value: Double, stale: Boolean = false, available: Boolean = true) =
    EntityState(id, StateValue.Numeric(value), available = available, stale = stale)

fun unknown(id: String) = EntityState(id, StateValue.Unknown)
```

If `Entity`'s constructor parameter order differs from `feature/home/src/test/.../TestEntities.kt`, follow that file. It is the reference.

- [ ] **Step 4: Write the failing builder tests**

`EnergyFlowBuilderTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.feature.energy.unknown
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.transport.LinkState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyFlowBuilderTest {
    private fun build(
        entities: List<Entity>,
        states: List<EntityState>,
        now: Long = 0,
        previous: ConsumerOrder? = null,
    ) = EnergyFlowBuilder.build(entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, LinkState.Connecting, now, previous)

    /** This house (spec §1.1): signed grid, solar, two consumers, derived house. */
    private val house = listOf(
        energyEntity("grid", EnergyRole.GRID),
        energyEntity("solar", EnergyRole.SOLAR),
        energyEntity("ac", EnergyRole.CONSUMER, name = "AC"),
        energyEntity("heater", EnergyRole.CONSUMER, name = "Water heater"),
    )
    private val houseStates = listOf(numeric("grid", -400.0), numeric("solar", 3200.0), numeric("ac", 600.0), numeric("heater", 1700.0))

    @Test
    fun `nothing discovered is loading`() {
        val s = build(emptyList(), emptyList())
        assertTrue(s.loading)
        assertEquals(false, s.empty)
    }

    @Test
    fun `no tagged entity is empty`() {
        val s = build(listOf(energyEntity("t", role = null)), emptyList())
        assertEquals(false, s.loading)
        assertTrue(s.empty)
    }

    @Test
    fun `this house derives the house and self consumption`() {
        val s = build(house, houseStates)
        assertEquals(2800.0, s.node("house")!!.value)
        assertEquals(400.0, s.node("grid")!!.value)
        assertEquals(Flow.OUT, s.node("grid")!!.flow)
        assertEquals(Flow.IN, s.node("solar")!!.flow)
        assertEquals(87, s.selfConsumption)
        assertNull(s.hint)
        assertEquals(listOf("solar", "grid", "house", "heater", "ac"), s.diagram.map { it.id })
    }

    @Test
    fun `measured house wins over the derived one`() {
        val s = build(house + energyEntity("load", EnergyRole.HOUSE), houseStates + numeric("load", 2500.0))
        assertEquals(2500.0, s.node("house")!!.value)
    }

    @Test
    fun `invert flips a grid meter`() {
        val s = build(listOf(energyEntity("grid", EnergyRole.GRID, invert = true)), listOf(numeric("grid", 400.0)))
        assertEquals(Flow.OUT, s.node("grid")!!.flow)
    }

    @Test
    fun `invert is ignored on solar`() {
        val s = build(listOf(energyEntity("solar", EnergyRole.SOLAR, invert = true)), listOf(numeric("solar", 1000.0)))
        assertEquals(1000.0, s.node("solar")!!.value)
    }

    @Test
    fun `grid export is subtracted from grid import`() {
        val s = build(
            listOf(energyEntity("imp", EnergyRole.GRID), energyEntity("exp", EnergyRole.GRID_EXPORT), energyEntity("solar", EnergyRole.SOLAR)),
            listOf(numeric("imp", 0.0), numeric("exp", 700.0), numeric("solar", 2000.0)),
        )
        assertEquals(700.0, s.node("grid")!!.value)
        assertEquals(Flow.OUT, s.node("grid")!!.flow)
        assertEquals(1300.0, s.node("house")!!.value)
    }

    @Test
    fun `battery discharge feeds the house and charge leaves it`() {
        val discharge = build(listOf(energyEntity("bat", EnergyRole.BATTERY), energyEntity("grid", EnergyRole.GRID)), listOf(numeric("bat", 500.0), numeric("grid", 100.0)))
        assertEquals(Flow.IN, discharge.node("battery")!!.flow)
        assertEquals(600.0, discharge.node("house")!!.value)
        val charge = build(listOf(energyEntity("bat", EnergyRole.BATTERY), energyEntity("grid", EnergyRole.GRID)), listOf(numeric("bat", -500.0), numeric("grid", 900.0)))
        assertEquals(Flow.OUT, charge.node("battery")!!.flow)
        assertEquals(400.0, charge.node("house")!!.value)
    }

    @Test
    fun `units are converted to watts`() {
        val s = build(listOf(energyEntity("solar", EnergyRole.SOLAR, unit = "kW")), listOf(numeric("solar", 3.2)))
        assertEquals(3200.0, s.node("solar")!!.value)
    }

    @Test
    fun `a bad unit makes the role unknown and the derived house unknown`() {
        val s = build(listOf(energyEntity("solar", EnergyRole.SOLAR, unit = "kWh"), energyEntity("grid", EnergyRole.GRID)), listOf(numeric("solar", 3.2), numeric("grid", 100.0)))
        assertNull(s.node("solar")!!.value)
        assertNull(s.node("house")!!.value)
    }

    @Test
    fun `two entities on one role are summed`() {
        val s = build(listOf(energyEntity("s1", EnergyRole.SOLAR), energyEntity("s2", EnergyRole.SOLAR)), listOf(numeric("s1", 1000.0), numeric("s2", 500.0)))
        assertEquals(1500.0, s.node("solar")!!.value)
        assertEquals(1, s.diagram.count { it.kind == NodeKind.SOLAR })
    }

    @Test
    fun `negative derived house shows zero and the sign hint`() {
        val s = build(listOf(energyEntity("grid", EnergyRole.GRID), energyEntity("solar", EnergyRole.SOLAR)), listOf(numeric("grid", -3000.0), numeric("solar", 1000.0)))
        assertEquals(0.0, s.node("house")!!.value)
        assertEquals(EnergyHint.CHECK_SIGN, s.hint)
    }

    @Test
    fun `all unknown shows dashes without hint`() {
        val s = build(house, house.map { unknown(it.uniqueId) })
        assertTrue(s.diagram.all { it.value == null })
        assertTrue(s.diagram.all { it.flow == Flow.IDLE })
        assertNull(s.hint)
        assertNull(s.selfConsumption)
    }

    @Test
    fun `night hides self consumption`() {
        val s = build(house, listOf(numeric("grid", 900.0), numeric("solar", 0.0), numeric("ac", 300.0), numeric("heater", 0.0)))
        assertEquals(900.0, s.node("house")!!.value)
        assertNull(s.selfConsumption)
        assertEquals(Flow.IDLE, s.node("solar")!!.flow)
    }

    @Test
    fun `self consumption needs at least 50 W of solar`() {
        val s = build(house, listOf(numeric("grid", 300.0), numeric("solar", 49.0), numeric("ac", 0.0), numeric("heater", 0.0)))
        assertNull(s.selfConsumption)
    }

    @Test
    fun `below 10 W an edge is idle`() {
        val s = build(house, listOf(numeric("grid", 5.0), numeric("solar", 3200.0), numeric("ac", 9.0), numeric("heater", 1700.0)))
        assertEquals(Flow.IDLE, s.node("grid")!!.flow)
        assertEquals(Flow.IDLE, s.node("ac")!!.flow)
        assertEquals(Flow.OUT, s.node("heater")!!.flow)
    }

    @Test
    fun `negative consumer is clamped`() {
        val s = build(house, houseStates.filterNot { it.uniqueId == "ac" } + numeric("ac", -3.0))
        assertEquals(0.0, s.node("ac")!!.value)
        assertEquals(Flow.IDLE, s.node("ac")!!.flow)
    }

    @Test
    fun `stale and unavailable are carried and aggregated into the derived house`() {
        val s = build(house, listOf(numeric("grid", -400.0, stale = true), numeric("solar", 3200.0), numeric("ac", 600.0, available = false), numeric("heater", 1700.0)))
        assertEquals(NodeStatus.STALE, s.node("grid")!!.status)
        assertEquals(NodeStatus.STALE, s.node("house")!!.status)
        assertEquals(NodeStatus.UNAVAILABLE, s.node("ac")!!.status)
        assertNull(s.node("ac")!!.value)
    }

    @Test
    fun `four consumers fold into two and more`() {
        val four = house + energyEntity("ev", EnergyRole.CONSUMER, name = "EV") + energyEntity("pump", EnergyRole.CONSUMER, name = "Pump")
        val s = build(four, houseStates + numeric("ev", 3000.0) + numeric("pump", 50.0))
        assertEquals(listOf("ev", "heater", "more"), s.diagram.filter { it.kind == NodeKind.CONSUMER || it.kind == NodeKind.MORE }.map { it.id })
        val more = s.node("more")!!
        assertEquals(2, more.count)
        assertEquals(650.0, more.value)
        assertEquals(listOf("ev", "heater", "ac", "pump"), s.consumers.map { it.id })
        assertEquals(4, s.cards.count { it.kind == NodeKind.CONSUMER })
    }

    @Test
    fun `override order comes before power order`() {
        val ordered = listOf(
            energyEntity("ac", EnergyRole.CONSUMER, order = 1),
            energyEntity("heater", EnergyRole.CONSUMER),
        )
        val s = build(ordered, listOf(numeric("ac", 10.0), numeric("heater", 2000.0)))
        assertEquals(listOf("ac", "heater"), s.consumers.map { it.id })
    }

    @Test
    fun `order is throttled for five seconds`() {
        val first = build(house, houseStates, now = 0)
        assertEquals(listOf("heater", "ac"), first.consumers.map { it.id })
        val swapped = houseStates.filterNot { it.uniqueId == "ac" } + numeric("ac", 2500.0)
        assertEquals(listOf("heater", "ac"), build(house, swapped, now = 4_999, previous = first.order).consumers.map { it.id })
        assertEquals(listOf("ac", "heater"), build(house, swapped, now = 5_000, previous = first.order).consumers.map { it.id })
    }

    @Test
    fun `order is recomputed when the consumer set changes`() {
        val first = build(house, houseStates, now = 0)
        val fewer = house.filterNot { it.uniqueId == "heater" }
        val s = build(fewer, houseStates, now = 1_000, previous = first.order)
        assertEquals(listOf("ac"), s.consumers.map { it.id })
        assertEquals(1_000, s.order!!.atMillis)
    }

    @Test
    fun `water sits bottom left without a battery and only in cards with one`() {
        val water = energyEntity("water", EnergyRole.WATER, unit = "L")
        val alone = build(house + water, houseStates + numeric("water", 312.0))
        assertEquals(312.0, alone.node("water")!!.value)
        assertEquals("L", alone.node("water")!!.unit)
        assertTrue(alone.diagram.any { it.id == "water" })
        val withBattery = build(house + water + energyEntity("bat", EnergyRole.BATTERY), houseStates + numeric("water", 312.0) + numeric("bat", 0.0))
        assertTrue(withBattery.diagram.none { it.id == "water" })
        assertTrue(withBattery.cards.any { it.id == "water" })
    }

    @Test
    fun `consumers alone still show a house node`() {
        val s = build(listOf(energyEntity("ac", EnergyRole.CONSUMER)), listOf(numeric("ac", 300.0)))
        assertNull(s.node("house")!!.value)
        assertTrue(s.diagram.any { it.id == "ac" })
    }
}
```

- [ ] **Step 5: Run them to verify they fail**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests '*EnergyFlowBuilderTest'`
Expected: compilation FAILS (`EnergyFlowBuilder` unresolved).

- [ ] **Step 6: Implement the builder**

`EnergyFlowBuilder.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.PowerUnits
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import kotlin.math.abs
import kotlin.math.max

/** Entities with an energy role → the Energy screen (energy spec §2–§4). Pure; no Android types. */
object EnergyFlowBuilder {
    const val REORDER_MS = 5_000L
    const val IDLE_W = 10.0
    const val SELF_CONSUMPTION_MIN_W = 50.0
    const val ARC_SLOTS = 3

    /** One role's value in watts (null = unknown) and its worst status. */
    private data class Reading(val watts: Double?, val status: NodeStatus)

    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        link: LinkState,
        nowMillis: Long,
        previous: ConsumerOrder? = null,
    ): EnergyUiState {
        if (entities.isEmpty()) return EnergyUiState(loading = true, link = link)
        val byRole = entities.values.filter { it.ui.energyRole != null }.groupBy { it.ui.energyRole!! }
        if (byRole.isEmpty()) return EnergyUiState(loading = false, empty = true, link = link)

        val solar = sum(byRole[EnergyRole.SOLAR], states)?.let { it.copy(watts = it.watts?.coerceAtLeast(0.0)) }
        val grid = grid(sum(byRole[EnergyRole.GRID], states), sum(byRole[EnergyRole.GRID_EXPORT], states))
        val battery = sum(byRole[EnergyRole.BATTERY], states)
        val consumerEntities = byRole[EnergyRole.CONSUMER].orEmpty()

        var hint: EnergyHint? = null
        val measured = sum(byRole[EnergyRole.HOUSE], states)
        val house: Reading? = when {
            measured != null -> measured
            else -> {
                val inputs = listOfNotNull(solar, grid, battery)
                when {
                    inputs.isNotEmpty() -> {
                        val total = if (inputs.any { it.watts == null }) null else inputs.sumOf { it.watts!! }
                        if (total != null && total < 0) hint = EnergyHint.CHECK_SIGN
                        Reading(total?.coerceAtLeast(0.0), worst(inputs.map { it.status }))
                    }
                    consumerEntities.isNotEmpty() -> Reading(null, NodeStatus.LIVE)
                    else -> null
                }
            }
        }

        val consumerNodes = consumerEntities.associate { e ->
            val r = sum(listOf(e), states)!!
            val w = r.watts?.coerceAtLeast(0.0)
            e.uniqueId to EnergyNode(e.uniqueId, NodeKind.CONSUMER, name = e.name, value = w, flow = outward(w), status = r.status)
        }
        val fresh = rank(consumerEntities, consumerNodes)
        val order = if (previous != null && nowMillis - previous.atMillis < REORDER_MS && previous.ids.toSet() == fresh.toSet()) {
            previous
        } else {
            ConsumerOrder(fresh, nowMillis)
        }
        val consumers = order.ids.map(consumerNodes::getValue)
        val arc = if (consumers.size <= ARC_SLOTS) consumers else consumers.take(ARC_SLOTS - 1) + more(consumers.drop(ARC_SLOTS - 1))

        val solarNode = solar?.let { EnergyNode("solar", NodeKind.SOLAR, value = it.watts, flow = inward(it.watts), status = it.status) }
        val gridNode = grid?.let { EnergyNode("grid", NodeKind.GRID, value = it.watts?.let(::abs), flow = signed(it.watts), status = it.status) }
        val batteryNode = battery?.let { EnergyNode("battery", NodeKind.BATTERY, value = it.watts?.let(::abs), flow = signed(it.watts), status = it.status) }
        val houseNode = house?.let { EnergyNode("house", NodeKind.HOUSE, value = it.watts, status = it.status) }
        val waterNode = byRole[EnergyRole.WATER]?.firstOrNull()?.let { e -> water(e, states[e.uniqueId]) }

        val roles = listOfNotNull(solarNode, gridNode, batteryNode, houseNode)
        val s = solar?.watts
        val selfConsumption = if (s != null && s >= SELF_CONSUMPTION_MIN_W && (grid == null || grid.watts != null)) {
            val export = max(0.0, -(grid?.watts ?: 0.0))
            ((s - export) * 100 / s).toInt().coerceIn(0, 100)
        } else {
            null
        }
        return EnergyUiState(
            loading = false,
            empty = false,
            diagram = roles + arc + listOfNotNull(waterNode.takeIf { batteryNode == null }),
            cards = roles + consumers + listOfNotNull(waterNode),
            consumers = consumers,
            selfConsumption = selfConsumption,
            hint = hint,
            link = link,
            order = order,
        )
    }

    /** Summed watts of [entities] (null if any is unknown), or null when the role has no entity. */
    private fun sum(entities: List<Entity>?, states: Map<String, EntityState>): Reading? {
        if (entities.isNullOrEmpty()) return null
        val parts = entities.map { e -> reading(e, states[e.uniqueId]) }
        val watts = if (parts.any { it.watts == null }) null else parts.sumOf { it.watts!! }
        return Reading(watts, worst(parts.map { it.status }))
    }

    private fun reading(e: Entity, state: EntityState?): Reading {
        val status = status(state)
        val numeric = (state?.value as? StateValue.Numeric)?.value
        val factor = PowerUnits.factor(e.unit)
        val sign = if (e.ui.energyInvert && e.ui.energyRole?.signed == true) -1.0 else 1.0
        val watts = if (status == NodeStatus.UNAVAILABLE || numeric == null || factor == null) null else numeric * factor * sign
        return Reading(watts, status)
    }

    private fun status(state: EntityState?): NodeStatus = when {
        state?.available == false -> NodeStatus.UNAVAILABLE
        state?.stale == true -> NodeStatus.STALE
        else -> NodeStatus.LIVE
    }

    private fun worst(statuses: List<NodeStatus>): NodeStatus = statuses.maxByOrNull { it.ordinal } ?: NodeStatus.LIVE

    /** G = Σgrid − Σgrid_export (spec §3). */
    private fun grid(import: Reading?, export: Reading?): Reading? = when {
        import == null && export == null -> null
        export == null -> import
        import == null -> Reading(export.watts?.let { -it }, export.status)
        else -> Reading(
            if (import.watts == null || export.watts == null) null else import.watts - export.watts,
            worst(listOf(import.status, export.status)),
        )
    }

    private fun inward(w: Double?): Flow = if (w != null && w >= IDLE_W) Flow.IN else Flow.IDLE

    private fun outward(w: Double?): Flow = if (w != null && w >= IDLE_W) Flow.OUT else Flow.IDLE

    /** + flows into the house (import, discharge), − flows out (export, charge). */
    private fun signed(w: Double?): Flow = when {
        w == null -> Flow.IDLE
        w >= IDLE_W -> Flow.IN
        w <= -IDLE_W -> Flow.OUT
        else -> Flow.IDLE
    }

    /** `UiOverride.order` first, then power high → low (unknown last), then name. */
    private fun rank(entities: List<Entity>, nodes: Map<String, EnergyNode>): List<String> =
        entities.sortedWith(
            compareBy<Entity>({ it.ui.order ?: Int.MAX_VALUE }, { -(nodes.getValue(it.uniqueId).value ?: -1.0) }, { it.name }),
        ).map { it.uniqueId }

    private fun more(rest: List<EnergyNode>): EnergyNode {
        val total = if (rest.any { it.value == null }) null else rest.sumOf { it.value!! }
        return EnergyNode("more", NodeKind.MORE, value = total, flow = outward(total), status = worst(rest.map { it.status }), count = rest.size)
    }

    private fun water(e: Entity, state: EntityState?): EnergyNode {
        val status = status(state)
        val value = (state?.value as? StateValue.Numeric)?.value.takeIf { status != NodeStatus.UNAVAILABLE }
        return EnergyNode("water", NodeKind.WATER, value = value, unit = e.unit, status = status)
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests '*EnergyFlowBuilderTest'`
Expected: PASS (24 tests).

- [ ] **Step 8: Full check and commit**

Run: `./gradlew check`. Expected: BUILD SUCCESSFUL.

```bash
git add settings.gradle.kts feature/energy
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(energy): module and flow builder - derived house, signs, units, fold and throttled consumer order

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: EnergyViewModel

**Files:**
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyViewModel.kt`
- Create: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/FakeEntitySource.kt`
- Test: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyViewModelTest.kt`

**Interfaces:**
- Consumes: `EnergyFlowBuilder.build(...)`, `EnergyUiState` (Task 3); `EntitySource` (`entities`, `states()`, `link`).
- Produces: `EnergyViewModel` (Hilt), `val state: StateFlow<EnergyUiState>`, and an internal test constructor `EnergyViewModel(source: EntitySource, dispatcher: CoroutineDispatcher, clock: () -> Long)`.

- [ ] **Step 1: Write the fake source**

`feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/FakeEntitySource.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Read-only fake: the Energy screen never sends commands or writes overrides. */
class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val commandFailures = MutableSharedFlow<CommandFailure>()
    val stateMap = MutableStateFlow<Map<String, EntityState>>(emptyMap())

    override fun states() = stateMap
    override suspend fun send(uniqueId: String, command: Command): CommandResult = error("not used")
    override suspend fun setOverride(uniqueId: String, override: UiOverride) = error("not used")
    override suspend fun setAreas(areas: List<Area>) = error("not used")
}
```

- [ ] **Step 2: Write the failing test**

`EnergyViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.presentation

import com.gekkotron.oikos.feature.energy.FakeEntitySource
import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.model.EnergyRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class EnergyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var now = 0L
    private val source = FakeEntitySource().apply {
        entities.value = listOf(
            energyEntity("solar", EnergyRole.SOLAR),
            energyEntity("grid", EnergyRole.GRID),
            energyEntity("ac", EnergyRole.CONSUMER),
            energyEntity("heater", EnergyRole.CONSUMER),
        ).associateBy { it.uniqueId }
        stateMap.value = listOf(numeric("solar", 3200.0), numeric("grid", -400.0), numeric("ac", 600.0), numeric("heater", 1700.0)).associateBy { it.uniqueId }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state reflects the source`() = runTest(dispatcher) {
        val vm = EnergyViewModel(source, dispatcher) { now }
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(2800.0, vm.state.value.node("house")!!.value)
        assertEquals(87, vm.state.value.selfConsumption)
    }

    @Test
    fun `the consumer order is carried between updates and throttled`() = runTest(dispatcher) {
        val vm = EnergyViewModel(source, dispatcher) { now }
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(listOf("heater", "ac"), vm.state.value.consumers.map { it.id })
        now = 1_000
        source.stateMap.value += "ac" to numeric("ac", 2500.0)
        assertEquals(listOf("heater", "ac"), vm.state.value.consumers.map { it.id })
        assertEquals(2500.0, vm.state.value.node("ac")!!.value)
        now = 6_000
        source.stateMap.value += "heater" to numeric("heater", 1600.0)
        assertEquals(listOf("ac", "heater"), vm.state.value.consumers.map { it.id })
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests '*EnergyViewModelTest'`
Expected: compilation FAILS (`EnergyViewModel` unresolved).

- [ ] **Step 4: Implement**

`EnergyViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class EnergyViewModel internal constructor(
    source: EntitySource,
    dispatcher: CoroutineDispatcher,
    clock: () -> Long,
) : ViewModel() {
    @Inject
    constructor(source: EntitySource) : this(source, Dispatchers.Default, System::currentTimeMillis)

    // Like HomeViewModel: conflate() keeps one rebuild per collection turn and flowOn moves the build off
    // Main. scan carries the last consumer order into the next build, so the 5 s throttle (spec §4) survives.
    val state: StateFlow<EnergyUiState> = combine(source.entities, source.states(), source.link, ::Triple)
        .conflate()
        .scan(EnergyUiState()) { last, (entities, states, link) -> EnergyFlowBuilder.build(entities, states, link, clock(), last.order) }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyUiState())
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests '*EnergyViewModelTest'`
Expected: PASS.

- [ ] **Step 6: Full check and commit**

Run: `./gradlew check`. Expected: BUILD SUCCESSFUL.

```bash
git add feature/energy
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(energy): view model over the entity source, carrying the consumer order

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Energy UI — format, layout, diagram, screen, consumers sheet

**Files:**
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyFormat.kt`
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/ui/FlowLayout.kt`
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/ui/EnergyDescription.kt`
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/ui/FlowDiagram.kt`
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/ui/ConsumersSheet.kt`
- Create: `feature/energy/src/main/kotlin/com/gekkotron/oikos/feature/energy/ui/EnergyScreen.kt`
- Create: `feature/energy/src/main/res/values/strings.xml`, `feature/energy/src/main/res/values-fr/strings.xml`
- Test: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/presentation/EnergyFormatTest.kt`
- Test: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/ui/FlowLayoutTest.kt`
- Test: `feature/energy/src/test/kotlin/com/gekkotron/oikos/feature/energy/ui/EnergyScreenTest.kt`

**Interfaces:**
- Consumes: `EnergyUiState`, `EnergyNode`, `NodeKind`, `Flow`, `NodeStatus`, `EnergyHint` (Task 3); `EnergyViewModel` (Task 4); `LinkPill`, `OikosGlyph.{SUN,HOUSE,PYLON,BATTERY,PLUG,DROP}`, `StaticIcon`, `DsR.string.energy_*` (Task 1).
- Produces: `@Composable fun EnergyRoute(onOpenHome: () -> Unit, windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets, viewModel: EnergyViewModel = hiltViewModel())`.
- Produces: `@Composable fun EnergyScreen(state: EnergyUiState, onOpenHome: () -> Unit, windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets, locale: Locale = Locale.getDefault())`.
- Produces: `EnergyFormat.power(watts: Double, locale: Locale): String` and `EnergyFormat.amount(value: Double, unit: String?, locale: Locale): String`.
- Produces (internal): `FlowLayout.positions(diagram: List<EnergyNode>): Map<String, Pair<Float, Float>>`, `FlowLayout.strokeDp(watts: Double?, flow: Flow): Float`, `FlowLayout.speed(watts: Double?): Float`.

- [ ] **Step 1: Write the strings**

`feature/energy/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="energy_title">Energy</string>
    <string name="energy_live">Live</string>
    <string name="energy_live_self">Live · self-consumption %1$d %%</string>
    <string name="energy_empty">Long-press a power sensor on Home and choose an energy role.</string>
    <string name="energy_go_home">Go to Home</string>
    <string name="energy_check_sign">Check the grid or battery sign</string>
    <string name="energy_more">+%1$d</string>
    <string name="energy_more_action">Show all %1$d consumers</string>
    <string name="energy_consumers_title">All consumers</string>
    <string name="energy_unknown">—</string>
    <string name="energy_unavailable">Unavailable</string>
    <string name="energy_stale">Last known value</string>
    <string name="energy_spoken_kw">%1$s kilowatts</string>
    <string name="energy_spoken_w">%1$s watts</string>
    <string name="energy_cd_solar">Solar %1$s to the house.</string>
    <string name="energy_cd_house">House uses %1$s.</string>
    <string name="energy_cd_grid_in">Importing %1$s from the grid.</string>
    <string name="energy_cd_grid_out">Exporting %1$s to the grid.</string>
    <string name="energy_cd_battery_in">Battery supplies %1$s.</string>
    <string name="energy_cd_battery_out">Battery charging at %1$s.</string>
    <string name="energy_cd_consumer">%1$s %2$s.</string>
    <string name="energy_cd_water">Water %1$s.</string>
</resources>
```

`feature/energy/src/main/res/values-fr/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="energy_title">Énergie</string>
    <string name="energy_live">En direct</string>
    <string name="energy_live_self">En direct · autoconsommation %1$d %%</string>
    <string name="energy_empty">Appuyez longuement sur un capteur de puissance dans Accueil et choisissez un rôle énergie.</string>
    <string name="energy_go_home">Aller à l\'accueil</string>
    <string name="energy_check_sign">Vérifiez le signe du réseau ou de la batterie</string>
    <string name="energy_more">+%1$d</string>
    <string name="energy_more_action">Afficher les %1$d consommateurs</string>
    <string name="energy_consumers_title">Tous les consommateurs</string>
    <string name="energy_unknown">—</string>
    <string name="energy_unavailable">Indisponible</string>
    <string name="energy_stale">Dernière valeur connue</string>
    <string name="energy_spoken_kw">%1$s kilowatts</string>
    <string name="energy_spoken_w">%1$s watts</string>
    <string name="energy_cd_solar">Solaire %1$s vers la maison.</string>
    <string name="energy_cd_house">La maison consomme %1$s.</string>
    <string name="energy_cd_grid_in">Import de %1$s depuis le réseau.</string>
    <string name="energy_cd_grid_out">Export de %1$s vers le réseau.</string>
    <string name="energy_cd_battery_in">La batterie fournit %1$s.</string>
    <string name="energy_cd_battery_out">La batterie se charge à %1$s.</string>
    <string name="energy_cd_consumer">%1$s %2$s.</string>
    <string name="energy_cd_water">Eau %1$s.</string>
</resources>
```

- [ ] **Step 2: Write the failing pure tests**

`EnergyFormatTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.presentation

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class EnergyFormatTest {
    @Test
    fun `power switches to kW at 1000 W with one decimal`() {
        assertEquals("3.2 kW", EnergyFormat.power(3200.0, Locale.US))
        assertEquals("1.0 kW", EnergyFormat.power(1000.0, Locale.US))
        assertEquals("640 W", EnergyFormat.power(640.4, Locale.US))
        assertEquals("0 W", EnergyFormat.power(0.0, Locale.US))
    }

    @Test
    fun `french formatting`() {
        assertEquals("3,2 kW", EnergyFormat.power(3200.0, Locale.FRANCE))
        assertEquals("3,2", EnergyFormat.spokenNumber(3200.0, Locale.FRANCE))
    }

    @Test
    fun `amount keeps the reported unit`() {
        assertEquals("312 L", EnergyFormat.amount(312.0, "L", Locale.US))
        assertEquals("1.5 m³", EnergyFormat.amount(1.46, "m³", Locale.US))
        assertEquals("7", EnergyFormat.amount(7.0, null, Locale.US))
    }
}
```

`FlowLayoutTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.ui

import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowLayoutTest {
    private fun node(id: String, kind: NodeKind) = EnergyNode(id, kind, value = 0.0)

    @Test
    fun `fixed slots for the role nodes`() {
        val p = FlowLayout.positions(listOf(node("solar", NodeKind.SOLAR), node("grid", NodeKind.GRID), node("house", NodeKind.HOUSE), node("water", NodeKind.WATER)))
        assertEquals(FlowLayout.SOLAR, p["solar"])
        assertEquals(FlowLayout.GRID, p["grid"])
        assertEquals(FlowLayout.HOUSE, p["house"])
        assertEquals(FlowLayout.BOTTOM_LEFT, p["water"])
    }

    @Test
    fun `battery takes the bottom left slot`() {
        val p = FlowLayout.positions(listOf(node("house", NodeKind.HOUSE), node("battery", NodeKind.BATTERY)))
        assertEquals(FlowLayout.BOTTOM_LEFT, p["battery"])
    }

    @Test
    fun `consumers spread on the arc from top to bottom`() {
        val p = FlowLayout.positions(listOf(node("house", NodeKind.HOUSE), node("a", NodeKind.CONSUMER), node("b", NodeKind.CONSUMER), node("more", NodeKind.MORE)))
        val ys = listOf("a", "b", "more").map { p.getValue(it).second }
        assertEquals(ys.sorted(), ys)
        assertTrue(listOf("a", "b", "more").all { p.getValue(it).first > FlowLayout.HOUSE.first })
    }

    @Test
    fun `stroke grows with power on a log scale and idle is thin`() {
        assertEquals(1f, FlowLayout.strokeDp(5.0, Flow.IDLE))
        assertEquals(1f, FlowLayout.strokeDp(10.0, Flow.IN))
        assertEquals(6f, FlowLayout.strokeDp(10_000.0, Flow.IN))
        assertEquals(6f, FlowLayout.strokeDp(50_000.0, Flow.OUT))
        val mid = FlowLayout.strokeDp(1_000.0, Flow.IN)
        assertTrue(mid > 1f && mid < 6f)
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests '*EnergyFormatTest' --tests '*FlowLayoutTest'`
Expected: compilation FAILS.

- [ ] **Step 4: Implement the format and the layout**

`EnergyFormat.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.presentation

import java.text.NumberFormat
import java.util.Locale

/** Energy screen values: kW with one decimal from 1000 W, whole watts below (spec §4). */
object EnergyFormat {
    fun power(watts: Double, locale: Locale): String =
        if (watts >= 1_000) "${number(watts / 1_000, locale, 1, 1)} kW" else "${number(watts, locale, 0, 0)} W"

    /** The number part of [power], for the spoken "%s kilowatts" / "%s watts" strings. */
    fun spokenNumber(watts: Double, locale: Locale): String =
        if (watts >= 1_000) number(watts / 1_000, locale, 1, 1) else number(watts, locale, 0, 0)

    fun amount(value: Double, unit: String?, locale: Locale): String =
        listOfNotNull(number(value, locale, 0, 1), unit?.takeIf { it.isNotBlank() }).joinToString(" ")

    private fun number(value: Double, locale: Locale, min: Int, max: Int): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = min
            maximumFractionDigits = max
        }.format(value)
}
```

`FlowLayout.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.ui

import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import kotlin.math.log10

/** Layout B (spec §4): node centres as fractions of the diagram box, and the edge look. */
internal object FlowLayout {
    /** Width / height of the diagram box, as in the mockup's 260 × 250 viewBox. */
    const val ASPECT = 260f / 250f

    val SOLAR = 0.5f to 0.12f
    val HOUSE = 0.5f to 0.52f
    val GRID = 0.12f to 0.52f
    val BOTTOM_LEFT = 0.15f to 0.86f

    /** Arc slots right of the house, by how many consumer nodes are drawn (1..3). */
    private val ARC = mapOf(
        1 to listOf(0.86f to 0.52f),
        2 to listOf(0.86f to 0.34f, 0.86f to 0.72f),
        3 to listOf(0.82f to 0.2f, 0.88f to 0.52f, 0.82f to 0.84f),
    )

    fun positions(diagram: List<EnergyNode>): Map<String, Pair<Float, Float>> {
        val arc = diagram.filter { it.kind == NodeKind.CONSUMER || it.kind == NodeKind.MORE }
        val slots = ARC[arc.size.coerceIn(1, 3)].orEmpty()
        return buildMap {
            diagram.forEach { n ->
                when (n.kind) {
                    NodeKind.SOLAR -> put(n.id, SOLAR)
                    NodeKind.GRID -> put(n.id, GRID)
                    NodeKind.HOUSE -> put(n.id, HOUSE)
                    NodeKind.BATTERY, NodeKind.WATER -> put(n.id, BOTTOM_LEFT)
                    NodeKind.CONSUMER, NodeKind.MORE -> Unit
                }
            }
            arc.forEachIndexed { i, n -> slots.getOrNull(i)?.let { put(n.id, it) } }
        }
    }

    /** 0 at 10 W, 1 at 10 kW and above, on a log scale. */
    private fun level(watts: Double?): Float =
        if (watts == null || watts < 10.0) 0f else ((log10(watts) - 1.0) / 3.0).toFloat().coerceIn(0f, 1f)

    fun strokeDp(watts: Double?, flow: Flow): Float = if (flow == Flow.IDLE) 1f else 1f + 5f * level(watts)

    /** Dash cycles per second: 0.5 at 10 W to 2 at 10 kW. */
    fun speed(watts: Double?): Float = 0.5f + 1.5f * level(watts)
}
```

- [ ] **Step 5: Run the pure tests to verify they pass**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests '*EnergyFormatTest' --tests '*FlowLayoutTest'`
Expected: PASS.

- [ ] **Step 6: Write the failing screen test**

`EnergyScreenTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.energy.energyEntity
import com.gekkotron.oikos.feature.energy.numeric
import com.gekkotron.oikos.feature.energy.presentation.EnergyFlowBuilder
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class EnergyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun state(entities: List<Entity>, states: List<EntityState>): EnergyUiState =
        EnergyFlowBuilder.build(entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, LinkState.Connecting, 0)

    private val house = listOf(
        energyEntity("grid", EnergyRole.GRID),
        energyEntity("solar", EnergyRole.SOLAR),
        energyEntity("ac", EnergyRole.CONSUMER, name = "AC"),
        energyEntity("heater", EnergyRole.CONSUMER, name = "Water heater"),
    )
    private val houseStates = listOf(numeric("grid", -400.0), numeric("solar", 3200.0), numeric("ac", 600.0), numeric("heater", 1700.0))

    private fun show(state: EnergyUiState, onOpenHome: () -> Unit = {}) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { EnergyScreen(state, onOpenHome, locale = Locale.US) }
    }

    @Test
    fun `empty state explains and goes home`() {
        var home = 0
        show(EnergyUiState(loading = false, empty = true), onOpenHome = { home++ })
        compose.onNodeWithText("Long-press a power sensor on Home and choose an energy role.").assertExists()
        compose.onNodeWithText("Go to Home").performClick()
        assertEquals(1, home)
    }

    @Test
    fun `subtitle shows self consumption`() {
        show(state(house, houseStates))
        compose.onNodeWithText("Live · self-consumption 87 %").assertExists()
    }

    @Test
    fun `diagram reads as one sentence`() {
        show(state(house, houseStates))
        compose.onNodeWithTag("energy-diagram").assert(
            SemanticsMatcher("sentence") { node ->
                val text = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString(" ")
                listOf(
                    "Solar 3.2 kilowatts to the house.",
                    "House uses 2.8 kilowatts.",
                    "Exporting 400 watts to the grid.",
                    "Water heater 1.7 kilowatts.",
                    "AC 600 watts.",
                ).all(text::contains)
            },
        )
    }

    @Test
    fun `four consumers show more and open the sheet`() {
        val four = house + energyEntity("ev", EnergyRole.CONSUMER, name = "EV") + energyEntity("pump", EnergyRole.CONSUMER, name = "Pump")
        show(state(four, houseStates + numeric("ev", 3000.0) + numeric("pump", 50.0)))
        compose.onNodeWithContentDescription("Show all 4 consumers").performClick()
        compose.onNodeWithText("All consumers").assertExists()
        compose.onNodeWithTag("consumers-sheet-pump").assertExists()
    }

    @Test
    fun `cards list every node`() {
        show(state(house, houseStates))
        for (id in listOf("solar", "grid", "house", "heater", "ac")) {
            compose.onNodeWithTag("energy-card-$id").performScrollTo().assertExists()
        }
    }

    @Test
    fun `sign hint shows`() {
        val s = state(listOf(energyEntity("grid", EnergyRole.GRID), energyEntity("solar", EnergyRole.SOLAR)), listOf(numeric("grid", -3000.0), numeric("solar", 1000.0)))
        show(s)
        compose.onNodeWithText("Check the grid or battery sign").assertExists()
        assertTrue(s.hint != null)
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `./gradlew :feature:energy:testDebugUnitTest --tests '*EnergyScreenTest'`
Expected: compilation FAILS (`EnergyScreen` unresolved).

- [ ] **Step 8: Implement the description**

`EnergyDescription.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyFormat
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import java.util.Locale

/** The diagram as one TalkBack sentence (spec §5); unknown values are left out. */
@Composable
internal fun energyDescription(state: EnergyUiState, locale: Locale): String {
    val kw = stringResource(R.string.energy_spoken_kw)
    val w = stringResource(R.string.energy_spoken_w)
    fun spoken(watts: Double) = (if (watts >= 1_000) kw else w).format(EnergyFormat.spokenNumber(watts, locale))

    val solar = stringResource(R.string.energy_cd_solar)
    val house = stringResource(R.string.energy_cd_house)
    val gridIn = stringResource(R.string.energy_cd_grid_in)
    val gridOut = stringResource(R.string.energy_cd_grid_out)
    val batteryIn = stringResource(R.string.energy_cd_battery_in)
    val batteryOut = stringResource(R.string.energy_cd_battery_out)
    val consumer = stringResource(R.string.energy_cd_consumer)
    val water = stringResource(R.string.energy_cd_water)

    fun part(n: EnergyNode): String? {
        val v = n.value ?: return null
        return when (n.kind) {
            NodeKind.SOLAR -> solar.format(spoken(v))
            NodeKind.HOUSE -> house.format(spoken(v))
            NodeKind.GRID -> when (n.flow) {
                Flow.IN -> gridIn.format(spoken(v))
                Flow.OUT -> gridOut.format(spoken(v))
                Flow.IDLE -> null
            }
            NodeKind.BATTERY -> when (n.flow) {
                Flow.IN -> batteryIn.format(spoken(v))
                Flow.OUT -> batteryOut.format(spoken(v))
                Flow.IDLE -> null
            }
            NodeKind.CONSUMER -> consumer.format(n.name.orEmpty(), spoken(v))
            NodeKind.WATER -> water.format(EnergyFormat.amount(v, n.unit, locale))
            NodeKind.MORE -> null
        }
    }
    // Every consumer is read, including the folded ones, so TalkBack users need no sheet.
    return (state.diagram.filter { it.kind != NodeKind.CONSUMER && it.kind != NodeKind.MORE && it.kind != NodeKind.WATER } +
        state.consumers + listOfNotNull(state.cards.firstOrNull { it.kind == NodeKind.WATER }))
        .mapNotNull(::part)
        .joinToString(" ")
}
```

- [ ] **Step 9: Implement the diagram**

`FlowDiagram.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import com.gekkotron.oikos.feature.energy.presentation.NodeStatus
import java.util.Locale

private val NODE = 44.dp
private val DASH = 6.dp

@Composable
internal fun FlowDiagram(state: EnergyUiState, locale: Locale, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val positions = remember(state.diagram) { FlowLayout.positions(state.diagram) }
    val description = energyDescription(state, locale)
    val animate = animationsEnabled()
    val phase by rememberInfiniteTransition(label = "flow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart), label = "dash",
    )
    val colors = OikosTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(FlowLayout.ASPECT)) {
        val w = maxWidth
        val h = maxHeight
        Box(Modifier.matchParentSize().testTag("energy-diagram").clearAndSetSemantics { contentDescription = description }) {
            Canvas(Modifier.matchParentSize()) {
                val house = positions["house"] ?: return@Canvas
                val hub = Offset(house.first * size.width, house.second * size.height)
                val dash = DASH.toPx()
                state.diagram.filter { it.kind != NodeKind.HOUSE && it.kind != NodeKind.WATER }.forEach { n ->
                    val p = positions[n.id] ?: return@forEach
                    val at = Offset(p.first * size.width, p.second * size.height)
                    // Drawn in the direction energy travels, so the dash moves with it.
                    val (from, to) = if (n.flow == Flow.OUT) hub to at else at to hub
                    val control = Offset((from.x + to.x) / 2, if (n.kind == NodeKind.SOLAR) (from.y + to.y) / 2 else from.y)
                    val path = Path().apply { moveTo(from.x, from.y); quadraticTo(control.x, control.y, to.x, to.y) }
                    val idle = n.flow == Flow.IDLE
                    val offset = if (animate && !idle) -phase * 2 * dash * FlowLayout.speed(n.value) else 0f
                    drawPath(
                        path,
                        color = edgeColor(n.kind, colors).copy(alpha = if (idle) 0.25f else 0.9f),
                        style = Stroke(
                            width = FlowLayout.strokeDp(n.value, n.flow).dp.toPx(),
                            pathEffect = if (idle) null else PathEffect.dashPathEffect(floatArrayOf(dash, dash), offset),
                        ),
                    )
                }
            }
            state.diagram.filter { it.kind != NodeKind.MORE }.forEach { n ->
                val p = positions[n.id] ?: return@forEach
                NodeBubble(n, locale, Modifier.place(p, w, h))
            }
        }
        state.diagram.firstOrNull { it.kind == NodeKind.MORE }?.let { more ->
            val p = positions[more.id] ?: return@let
            val action = stringResource(R.string.energy_more_action, state.consumers.size)
            Box(
                Modifier
                    .place(p, w, h)
                    .size(NODE)
                    .clip(CircleShape)
                    .border(1.dp, colors.heat, CircleShape)
                    .clickable(role = Role.Button, onClick = onMore)
                    .semantics { contentDescription = action; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.energy_more, more.count), style = OikosTheme.type.label, color = colors.content) }
        }
    }
}

/** Centres a [NODE]-sized box on the fractional position [p]. */
private fun Modifier.place(p: Pair<Float, Float>, w: Dp, h: Dp): Modifier =
    offset(x = w * p.first - NODE / 2, y = h * p.second - NODE / 2)

@Composable
private fun NodeBubble(n: EnergyNode, locale: Locale, modifier: Modifier) {
    val colors = OikosTheme.colors
    Column(modifier.alpha(statusAlpha(n.status)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(NODE).background(edgeColor(n.kind, colors).copy(alpha = 0.18f), CircleShape).border(1.dp, edgeColor(n.kind, colors), CircleShape),
            contentAlignment = Alignment.Center,
        ) { StaticIcon(glyph(n.kind), modifier = Modifier.size(22.dp)) }
        Text(nodeValueText(n, locale), style = OikosTheme.type.label, color = colors.content, modifier = Modifier.padding(top = 2.dp))
    }
}

internal fun glyph(kind: NodeKind): OikosGlyph = when (kind) {
    NodeKind.SOLAR -> OikosGlyph.SUN
    NodeKind.GRID -> OikosGlyph.PYLON
    NodeKind.BATTERY -> OikosGlyph.BATTERY
    NodeKind.HOUSE -> OikosGlyph.HOUSE
    NodeKind.CONSUMER, NodeKind.MORE -> OikosGlyph.PLUG
    NodeKind.WATER -> OikosGlyph.DROP
}

internal fun edgeColor(kind: NodeKind, colors: com.gekkotron.oikos.designsystem.OikosColors): Color = when (kind) {
    NodeKind.SOLAR -> colors.solar
    NodeKind.GRID -> colors.grid
    NodeKind.BATTERY -> colors.connected
    NodeKind.HOUSE -> colors.content
    NodeKind.CONSUMER, NodeKind.MORE -> colors.heat
    NodeKind.WATER -> colors.water
}

internal fun statusAlpha(status: NodeStatus): Float = when (status) {
    NodeStatus.LIVE -> 1f
    NodeStatus.STALE -> 0.6f
    NodeStatus.UNAVAILABLE -> 0.38f
}

@Composable
private fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}
```

Add `import androidx.compose.ui.draw.clip`. If the class `OikosColors` lives under a different name or package, use the type of `OikosTheme.colors` and import it.

- [ ] **Step 10: Implement the value text, the sheet and the screen**

`ConsumersSheet.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsumersSheet(consumers: List<EnergyNode>, locale: Locale, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.energy_consumers_title), style = OikosTheme.type.display.copy(fontSize = OikosTheme.type.title.fontSize))
            consumers.forEach { n ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).alpha(statusAlpha(n.status)).testTag("consumers-sheet-${n.id}").semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.name.orEmpty(), style = OikosTheme.type.body, modifier = Modifier.weight(1f))
                    Text(nodeValueText(n, locale), style = OikosTheme.type.body)
                }
            }
        }
    }
}
```

If `OikosTheme.type` has no `title`, use `.copy(fontSize = 22.sp)` as `CustomizeSheet` does (import `androidx.compose.ui.unit.sp`).

`EnergyScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.energy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.LinkPill
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyFormat
import com.gekkotron.oikos.feature.energy.presentation.EnergyHint
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.EnergyViewModel
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import com.gekkotron.oikos.feature.energy.presentation.NodeStatus
import java.util.Locale
import com.gekkotron.oikos.designsystem.R as DsR

@Composable
fun EnergyRoute(
    onOpenHome: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    viewModel: EnergyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EnergyScreen(state, onOpenHome, windowInsets)
}

@Composable
fun EnergyScreen(
    state: EnergyUiState,
    onOpenHome: () -> Unit,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    locale: Locale = Locale.getDefault(),
) {
    val layoutDirection = LocalLayoutDirection.current
    var consumersSheet by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = Color.Transparent, contentColor = OikosTheme.colors.content, contentWindowInsets = windowInsets) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
            verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { EnergyHeader(state) }
            when {
                state.loading -> Unit
                state.empty -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 24.dp)) {
                        Text(stringResource(R.string.energy_empty), style = OikosTheme.type.body)
                        Button(onClick = onOpenHome, modifier = Modifier.heightIn(min = OikosDimens.minTouch)) { Text(stringResource(R.string.energy_go_home)) }
                    }
                }
                else -> {
                    if (state.hint == EnergyHint.CHECK_SIGN) {
                        item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.energy_check_sign), style = OikosTheme.type.label, color = OikosTheme.colors.warning)
                        }
                    }
                    item(key = "diagram", span = { GridItemSpan(maxLineSpan) }) {
                        OikosCard(Modifier.fillMaxWidth()) {
                            FlowDiagram(state, locale, onMore = { consumersSheet = true }, modifier = Modifier.padding(8.dp))
                        }
                    }
                    items(state.cards, key = { "card-${it.id}" }) { node -> EnergyValueCard(node, locale) }
                }
            }
        }
    }
    if (consumersSheet) ConsumersSheet(state.consumers, locale, onDismiss = { consumersSheet = false })
}

@Composable
private fun EnergyHeader(state: EnergyUiState) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.energy_title), style = OikosTheme.type.display)
        Text(
            state.selfConsumption?.let { stringResource(R.string.energy_live_self, it) } ?: stringResource(R.string.energy_live),
            style = OikosTheme.type.body,
            color = OikosTheme.colors.contentMuted,
        )
        Spacer(Modifier.height(8.dp))
        LinkPill(state.link, onClick = null)
    }
}

@Composable
private fun EnergyValueCard(node: EnergyNode, locale: Locale) {
    val staleText = stringResource(R.string.energy_stale)
    OikosCard(
        Modifier
            .fillMaxWidth()
            .testTag("energy-card-${node.id}")
            .alpha(statusAlpha(node.status))
            .semantics(mergeDescendants = true) { if (node.status == NodeStatus.STALE) stateDescription = staleText },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(nodeLabel(node), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            Text(nodeValueText(node, locale), style = OikosTheme.type.body)
        }
    }
}

@Composable
internal fun nodeLabel(node: EnergyNode): String = node.name ?: stringResource(
    when (node.kind) {
        NodeKind.SOLAR -> DsR.string.energy_solar
        NodeKind.GRID -> DsR.string.energy_grid
        NodeKind.BATTERY -> DsR.string.energy_battery
        NodeKind.HOUSE -> DsR.string.energy_house
        NodeKind.WATER -> DsR.string.energy_water
        NodeKind.CONSUMER, NodeKind.MORE -> DsR.string.energy_consumer
    },
)

/** "—", "Unavailable", or the value; grid and battery get ↙ (into the house) or ↗ (out of it). */
@Composable
internal fun nodeValueText(node: EnergyNode, locale: Locale): String {
    if (node.status == NodeStatus.UNAVAILABLE) return stringResource(R.string.energy_unavailable)
    val v = node.value ?: return stringResource(R.string.energy_unknown)
    if (node.kind == NodeKind.WATER) return EnergyFormat.amount(v, node.unit, locale)
    val arrow = if (node.kind == NodeKind.GRID || node.kind == NodeKind.BATTERY) {
        when (node.flow) {
            Flow.IN -> "↙ "
            Flow.OUT -> "↗ "
            Flow.IDLE -> ""
        }
    } else {
        ""
    }
    return arrow + EnergyFormat.power(v, locale)
}
```

Check the signature `OikosCard(modifier: Modifier, …, content: @Composable … )` in `core/designsystem/.../components/OikosCard.kt`. If its content slot is a `ColumnScope`/`BoxScope` lambda, pass the same content. If it has no content slot, wrap the content in a `Box` with `Modifier.background(OikosTheme.colors.card, RoundedCornerShape(OikosDimens.<card radius>)).border(1.dp, OikosTheme.colors.cardBorder, …)` instead. Add `import androidx.compose.ui.semantics.mergeDescendants` if the compiler asks.

- [ ] **Step 11: Run all energy tests to verify they pass**

Run: `./gradlew :feature:energy:testDebugUnitTest`
Expected: PASS. If `four consumers … open the sheet` cannot find the sheet row, add `compose.waitForIdle()` after the click. The bottom sheet animates in.

- [ ] **Step 12: Full check and commit**

Run: `./gradlew check`. Expected: BUILD SUCCESSFUL (lint included: every string has a French translation).

```bash
git add feature/energy
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(energy): flow diagram, value cards, consumers sheet and TalkBack sentence

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: App navigation — Home | Energy tab bar

**Files:**
- Create: `app/src/main/kotlin/com/gekkotron/oikos/navigation/MainTabs.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fr/strings.xml`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt` (`HomeRoute` gains `windowInsets`)
- Test: `app/src/test/kotlin/com/gekkotron/oikos/navigation/MainTabsTest.kt`

**Interfaces:**
- Consumes: `EnergyRoute(onOpenHome, windowInsets)` (Task 5); `OikosGlyph.HOUSE`, `OikosGlyph.BOLT`, `StaticIcon` (Task 1).
- Produces: `EnergyKey : NavKey`; `internal fun selectTab(stack: MutableList<NavKey>, key: NavKey)`; `internal fun showsTabBar(top: NavKey?): Boolean`; `@Composable internal fun MainTabBar(selected: NavKey?, onSelect: (NavKey) -> Unit)`.
- Produces: `HomeRoute(…, windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets, …)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/gekkotron/oikos/navigation/MainTabsTest.kt`:

```kotlin
package com.gekkotron.oikos.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MainTabsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `energy stacks on home and home pops it`() {
        val stack = mutableListOf<NavKey>(HomeKey)
        selectTab(stack, EnergyKey)
        assertEquals(listOf<NavKey>(HomeKey, EnergyKey), stack)
        selectTab(stack, EnergyKey)
        assertEquals(listOf<NavKey>(HomeKey, EnergyKey), stack)
        selectTab(stack, HomeKey)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun `tab bar only on the main tabs`() {
        assertTrue(showsTabBar(HomeKey))
        assertTrue(showsTabBar(EnergyKey))
        assertFalse(showsTabBar(OnboardingKey))
        assertFalse(showsTabBar(RoomsKey))
        assertFalse(showsTabBar(null))
    }

    @Test
    fun `tapping energy selects it`() {
        val picked = mutableListOf<NavKey>()
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = { picked += it }) } }
        compose.onNodeWithText("Energy").performClick()
        assertEquals(listOf<NavKey>(EnergyKey), picked)
    }
}
```

If `app` unit tests need Hilt's test application to run Robolectric (check `app/src/test/.../MainActivityTest.kt` for `@Config(application = HiltTestApplication::class)`), add the same `@Config` to this class.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*MainTabsTest'`
Expected: compilation FAILS (`EnergyKey`, `selectTab` unresolved).

- [ ] **Step 3: Implement the tabs**

`app/build.gradle.kts`: add `implementation(project(":feature:energy"))` after `implementation(project(":feature:home"))`.

App strings:
- `values/strings.xml`: add `<string name="nav_home">Home</string>` and `<string name="nav_energy">Energy</string>`.
- `values-fr/strings.xml`: add `<string name="nav_home">Accueil</string>` and `<string name="nav_energy">Énergie</string>`.

`MainTabs.kt`:

```kotlin
package com.gekkotron.oikos.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.gekkotron.oikos.R
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon

/** Energy sits on top of Home, so back from Energy returns to Home (energy spec §5). */
internal fun selectTab(stack: MutableList<NavKey>, key: NavKey) {
    when (key) {
        HomeKey -> stack.removeAll { it == EnergyKey }
        EnergyKey -> if (stack.lastOrNull() != EnergyKey) stack.add(EnergyKey)
    }
}

internal fun showsTabBar(top: NavKey?): Boolean = top == HomeKey || top == EnergyKey

@Composable
internal fun MainTabBar(selected: NavKey?, onSelect: (NavKey) -> Unit) {
    val colors = OikosTheme.colors
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.content,
        selectedTextColor = colors.content,
        unselectedIconColor = colors.contentMuted,
        unselectedTextColor = colors.contentMuted,
        indicatorColor = colors.chipSelected,
    )
    NavigationBar(containerColor = colors.backgroundTop.copy(alpha = 0.92f)) {
        NavigationBarItem(
            selected = selected == HomeKey,
            onClick = { onSelect(HomeKey) },
            icon = { StaticIcon(OikosGlyph.HOUSE, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_home)) },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selected == EnergyKey,
            onClick = { onSelect(EnergyKey) },
            icon = { StaticIcon(OikosGlyph.BOLT, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_energy)) },
            colors = itemColors,
        )
    }
}
```

`OikosNavHost.kt`:
- Add the key after `RoomsKey`:

```kotlin
@Serializable
data object EnergyKey : NavKey
```

- Replace the `NavDisplay(...)` call with a scaffold around it. Keep the existing `entry<OnboardingKey>` and `entry<RoomsKey>` blocks exactly as they are:

```kotlin
    val top = backStack.lastOrNull()
    val tabs = showsTabBar(top)
    // The bar owns the bottom inset on the main tabs; their own Scaffolds then only pad top and sides.
    val tabInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { if (tabs) MainTabBar(selected = top, onSelect = { selectTab(backStack, it) }) },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<OnboardingKey> { OnboardingRoute() }
                entry<RoomsKey> { /* unchanged */ }
                entry<HomeKey> {
                    HomeRoute(
                        onSetUpAgain = viewModel::setUpAgain,
                        onAssignRooms = { if (backStack.lastOrNull() != RoomsKey) backStack.add(RoomsKey) },
                        route = route,
                        onRouteChange = viewModel::setRoute,
                        windowInsets = tabInsets,
                    )
                }
                entry<EnergyKey> {
                    EnergyRoute(onOpenHome = { selectTab(backStack, HomeKey) }, windowInsets = tabInsets)
                }
            },
        )
    }
```

New imports:
- `androidx.compose.foundation.layout.WindowInsets`, `WindowInsetsSides`, `only`, `padding`
- `androidx.compose.material3.Scaffold`, `ScaffoldDefaults`
- `androidx.compose.ui.Modifier`, `androidx.compose.ui.graphics.Color`
- `com.gekkotron.oikos.feature.energy.ui.EnergyRoute`

`NavBackStack` is a `MutableList<NavKey>`, so `selectTab(backStack, …)` compiles. If it is not, use `selectTab(backStack as MutableList<NavKey>, …)`, or change the parameter type to `NavBackStack<NavKey>` and the test to `NavBackStack(HomeKey)`.

The `LaunchedEffect(root)` already removes `EnergyKey` when the root changes, because it keeps only the root. Leave it as is.

`HomeScreen.kt`, in `HomeRoute`: add the parameter `windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,` before `viewModel` and pass `windowInsets = windowInsets,` to `HomeScreen(...)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest :feature:home:testDebugUnitTest`
Expected: PASS, including the existing `MainActivityTest`.

- [ ] **Step 5: Full check and commit**

Run: `./gradlew check`. Expected: BUILD SUCCESSFUL.

```bash
git add app feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(app): Home and Energy tab bar; back from Energy returns to Home

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Demo house consumers

**Files:**
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`
- Modify: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt`

**Interfaces:**
- Consumes: the `consumer` role JSON from Task 2.
- Produces: the demo entities `demo_water_heater_power` and `demo_ac_power`, tagged `consumer`, published on `demo/energy` fields `water_heater` and `ac`.

- [ ] **Step 1: Write the failing test**

In `DemoBrokerTest.kt`:
- Add `"demo_water_heater_power", "demo_ac_power"` to the `allIds` set.
- In `declared areas and overrides are applied`, add:

```kotlin
        assertEquals(EnergyRole.CONSUMER, e.getValue("demo_water_heater_power").ui.energyRole)
        assertEquals(EnergyRole.CONSUMER, e.getValue("demo_ac_power").ui.energyRole)
```

- Add:

```kotlin
    @Test
    fun `tick keeps the consumers known`() = runTest {
        val h = start()
        h.house.tick()
        val states = h.source.states().first()
        assertTrue(states.getValue("demo_water_heater_power").value is StateValue.Numeric)
        assertTrue(states.getValue("demo_ac_power").value is StateValue.Numeric)
    }
```

If the harness does not expose the `DemoHouse` as `h.house`, call `tick()` through whatever the harness uses for the existing tick test (`grep -n "tick" DemoBrokerTest.kt`). If there is no such path, drop this test and assert instead on `DemoHouse().tick()`, checking that the returned `demo/energy` payload contains `"water_heater"` and `"ac"`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:demo:test`
Expected: FAIL (the new ids are missing from the entities).

- [ ] **Step 3: Implement**

In `DemoHouse.kt`, after the `demo_house_power` sensor line:

```kotlin
        sensor("demo_water_heater_power", "Water heater", null, "Garage", ENERGY, "water_heater", "power", "W")
        sensor("demo_ac_power", "Air conditioner power", null, "Living room", ENERGY, "ac", "power", "W")
```

Replace the `doc(ENERGY, …)` line with:

```kotlin
        doc(ENERGY, obj("solar" to 2140, "grid" to -380, "house" to 1760, "water_heater" to 900, "ac" to 350))
```

After the `demo_house_power` override, add:

```kotlin
        override("demo_water_heater_power", """{"energyRole":"consumer"}""")
        override("demo_ac_power", """{"energyRole":"consumer"}""")
```

In `tick()`, replace the `docs[ENERGY] = …` line with:

```kotlin
        val heater = (energy.number("water_heater") + random.nextInt(-60, 61)).coerceIn(0.0, 1500.0)
        val ac = (energy.number("ac") + random.nextInt(-40, 41)).coerceIn(0.0, 800.0)
        docs[ENERGY] = obj(
            "solar" to solar.roundToInt(), "grid" to (house - solar).roundToInt(), "house" to house.roundToInt(),
            "water_heater" to heater.roundToInt(), "ac" to ac.roundToInt(),
        )
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:demo:test`
Expected: PASS.

- [ ] **Step 5: Full check and commit**

Run: `./gradlew check`. Expected: BUILD SUCCESSFUL.

```bash
git add core/demo
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(demo): water heater and AC consumers for the energy screen

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Spec sync and demo run

**Files:**
- Modify: `docs/superpowers/specs/2026-10-02-oikos-energy-design.md`

- [ ] **Step 1: Bring the spec in line with the code**

In the spec:
- §6, the `EnergyModels.kt` row: replace it with `EnergyUiState(loading, empty, diagram, cards, consumers, selfConsumption, hint, link, order)`, `EnergyNode(id, kind, name, value, unit, flow, status, count)`, and `ConsumerOrder(ids, atMillis)`. Edges are implied: every drawn node except house and water has one edge to the house, in the direction of its `flow`.
- §6, the `EnergyFlowBuilder.kt` row: change it to `build(entities, states, link, nowMillis, previous: ConsumerOrder?)`.
- §6, the `:core:model` row: add `PowerUnits.factor`.
- §6, the `:core:designsystem` row: add "`LinkPill` and the energy role strings move here from `:feature:home`".
- §3, the self-consumption bullet: add "rounded down to a whole percent".
- §4, the value labels sentence: replace "use the existing Home formatting:" with "use the locale's number format:".

- [ ] **Step 2: Run the full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Look at it in the demo**

Use the `run` skill (or install the debug build on a device or emulator), start in demo mode, and open the Energy tab. Check:
- solar is on top, grid on the left, house in the centre, and the two consumers on the arc
- the lines move, and they stop when animations are turned off in developer options
- back returns to Home
- both themes look right

Report what you saw, including anything off.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-10-02-oikos-energy-design.md
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "docs(energy): spec follows the implemented model

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
