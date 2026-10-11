# Oikos Water Heater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The house water heater appears in Oikos as the standard HA `water_heater` kind: card with current temperature + mode subtitle, on-card Force toggle, sheet with mode chips, target stepper and the heater's number companions. All the heat-vs-off logic lives in Jeedom (spec Appendix B).

**Architecture:** A new `Kind.WATER_HEATER` goes end to end: model enum, discovery routing (reusing the already-known `curr_temp_t` / `temp_stat_t` / `mode_stat_t` abbreviations), a `WaterHeaterMapper` that reuses the climate topic/template plumbing (`ClimateMapper`'s current/target/mode decode and `SetTemperature`/`SetHvacMode` encode are extracted into a parameterised `ThermostatBase` helper so both mappers share them), the existing `StateValue.Climate` as its wire shape, a card + sheet with the `WATER_HEATER` glyph, and a demo heater that round-trips Force. The Jeedom side is built by the user to the spec's Appendix B contract.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, kotlinx.serialization, kotlin.test, JUnit 4, Robolectric (NATIVE graphics), Gradle 8. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-11-oikos-water-heater-design.md`

## Global Constraints

- Repo: `~/Documents/SourcesPerso/Oikos`, feature branch `feat/water-heater`, merged into `master` at the end.
- Every commit uses `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`, ends with:
  ```
  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  ```
  Never write the user's real name anywhere.
- `StateValue.Climate(mode, target, current, fanMode = null)` is the wire shape for a water heater. **No new state type.**
- Modes the app understands (localised): `off`, `eco`, `performance`, `electric`, `gas`, `heat_pump`, `high_demand`. HA's default `water_heater` modes list: `[off, eco, electric, gas, heat_pump, high_demand, performance]`.
- Temperature defaults for a water heater: `minTemp = 43.0`, `maxTemp = 60.0`, `tempStep = 1.0` (HA's 110–140 °F defaults; `precision` → `tempStep`).
- The app never sends `SetHvacMode("off")`; Jeedom owns `off`.
- The on-card Force toggle is shown **only when** `modes` contains both `performance` and `eco`.
- The sheet shows every discovered mode **except** `off` as a chip.
- The number companions in the sheet are **every visible `Kind.NUMBER` entity with the same `deviceId`** as the water heater; they stay as standalone cards unless the user hides them in Customize.
- Strings in both `feature/home/src/main/res/values/strings.xml` and `feature/home/src/main/res/values-fr/strings.xml`.
- No widgets, no voice intent, no schedules.
- Android regex gotcha (from `[[android-icu-regex]]`): escape literal `]` and `}` in any `Regex` (ICU throws at class init). Not expected to bite here, but any new `Regex` must obey this.
- Do not stage `.frigate-pass`, `.env`, or any file under a `secrets/` directory.

## Review Focus

1. **Wrong wire shape for Climate** — a water heater reports its state as `StateValue.Climate(mode, target, current, fanMode = null)`; a mapper that returned a new type or set `fanMode` to anything else would break every climate consumer (`HomeUiBuilder.isOn`, `CardFormatter`, `CardText.cardValueText`). Pinned by `WaterHeaterMapperTest.decode …`.
2. **`SetHvacMode("off")` reaching the broker** — the app must never let the user send `off`, because Jeedom decides `off`. The mapper itself accepts `SetHvacMode("off")` when `off` is in the modes (that's `TurnOff`'s pathway for other kinds), so the guard sits in the UI: `WaterHeaterControls` filters `off` out of the chips (code review focus), and no on-card path produces it. Visually pinned by the sheet screenshot (Task 6 Step 5a) and by code review of `caps.hvacModes.filter { it != "off" }` in `WaterHeaterControls`.
3. **Force toggle visible on a non-Force heater** — a water heater whose discovery modes are `[off, electric, gas]` must not show the on-card Force switch (nothing to toggle to), and it must not show the Force chip in the sheet. Pinned by `HomeUiBuilderTest.force toggle only when both performance and eco`.
4. **ClimateMapper regression** — extracting the shared topic/template helper must not change `ClimateMapper`'s behaviour. Pinned by keeping `ClimateMapperTest` byte-identical and green (`./gradlew :core:state:test --tests ClimateMapperTest`).
5. **A water heater with no companions** — the sheet must open and render fine when no `Kind.NUMBER` shares the heater's `deviceId` (an HA user with no Force Power sensor). Pinned by `HomeUiBuilderTest.water heater sheet without companions`.

---

### Task 1: Extract the climate topic/template helper (no behaviour change)

Pull the current/target/mode decode and the `SetTemperature` / `SetHvacMode` / `TurnOff` encode logic out of `ClimateMapper` into a parameterised `ThermostatBase` helper so `WaterHeaterMapper` (Task 3) can reuse it. `ClimateMapper` keeps its fan-mode handling and defaults and still behaves exactly the same.

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/ThermostatBase.kt`
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/ClimateMapper.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/ClimateMapperTest.kt` (unchanged — refactor guard)

**Interfaces:**
- Produces (used by Task 3's `WaterHeaterMapper`):
  - `object ThermostatBase` with:
    - `fun stateTopics(b: MqttBindings): Set<String>`
    - `fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue, modes: List<String>): Decoded` — fills the `StateValue.Climate` current/target/mode from the standard topics/templates; keeps `fanMode` untouched.
    - `fun encodeTemperature(b: MqttBindings, temperature: Double, min: Double, max: Double): Encoded`
    - `fun encodeMode(b: MqttBindings, mode: String, modes: List<String>): Encoded`
    - `fun feedbackTopics(b: MqttBindings, command: Command): Set<String>` — same mapping `ClimateMapper` has for SetTemperature / SetHvacMode / TurnOff.
    - `fun predict(command: Command, previous: StateValue): StateValue?` — covers SetTemperature, SetHvacMode and TurnOff only.

- [ ] **Step 1: Run the existing ClimateMapperTest to establish the green baseline**

Run: `./gradlew :core:state:test --tests "com.gekkotron.oikos.state.kinds.ClimateMapperTest"`
Expected: PASS (all 7 tests).

- [ ] **Step 2: Create `ThermostatBase.kt`**

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.asPayloadString
import com.gekkotron.oikos.state.template.formatNumber

/**
 * The HA climate topic/template plumbing shared by [ClimateMapper] and [WaterHeaterMapper]:
 * current / target / mode on the standard `*_temperature_*` and `mode_*` keys, with the standard
 * `_template` renderers. Fan-mode handling stays in [ClimateMapper]; this helper never touches it.
 */
internal object ThermostatBase {
    fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(
        b.str("current_temperature_topic"),
        b.str("temperature_state_topic"),
        b.str("mode_state_topic"),
    )

    /** Decodes the three climate topics onto [previous]'s [StateValue.Climate]; keeps `fanMode` as it was. */
    fun decode(
        b: MqttBindings,
        topic: String,
        payload: String,
        previous: StateValue,
        modes: List<String>,
    ): Decoded {
        var climate = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        val parts = PartialDecode()
        if (topic == b.str("current_temperature_topic")) {
            parts.part(renderIncoming(b, "current_temperature_template", payload)) { v ->
                val t = v.asDouble() ?: return@part false
                climate = climate.copy(current = t)
                true
            }
        }
        if (topic == b.str("temperature_state_topic")) {
            parts.part(renderIncoming(b, "temperature_state_template", payload)) { v ->
                val t = v.asDouble() ?: return@part false
                climate = climate.copy(target = t)
                true
            }
        }
        if (topic == b.str("mode_state_topic")) {
            parts.part(renderIncoming(b, "mode_state_template", payload)) { v ->
                val mode = v.asPayloadString()?.takeIf { it in modes } ?: return@part false
                climate = climate.copy(mode = mode)
                true
            }
        }
        return parts.result(climate)
    }

    fun encodeTemperature(b: MqttBindings, temperature: Double, min: Double, max: Double): Encoded =
        if (temperature !in min..max) Encoded.NotSupported("temperature $temperature outside $min..$max")
        else publish(b, "temperature_command_topic", "temperature_command_template", formatNumber(temperature))

    fun encodeMode(b: MqttBindings, mode: String, modes: List<String>): Encoded =
        if (mode !in modes) Encoded.NotSupported("mode '$mode' not supported")
        else publish(b, "mode_command_topic", "mode_command_template", mode)

    fun feedbackTopics(b: MqttBindings, command: Command): Set<String> = when (command) {
        is Command.SetTemperature -> setOfNotNull(b.str("temperature_state_topic"))
        is Command.SetHvacMode, Command.TurnOff -> setOfNotNull(b.str("mode_state_topic"))
        else -> stateTopics(b)
    }

    fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        return when (command) {
            is Command.SetTemperature -> prev.copy(target = command.temperature)
            is Command.SetHvacMode -> prev.copy(mode = command.mode)
            Command.TurnOff -> prev.copy(mode = "off")
            else -> null
        }
    }
}
```

- [ ] **Step 3: Rewrite `ClimateMapper` to delegate to `ThermostatBase`**

Replace the whole body of `ClimateMapper` so the current/target/mode plumbing comes from `ThermostatBase`; keep every public constant and every fan-mode method as they are.

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

object ClimateMapper : KindMapper {
    val DEFAULT_MODES = listOf("auto", "off", "cool", "heat", "dry", "fan_only")
    val DEFAULT_FAN_MODES = listOf("auto", "low", "medium", "high")
    const val DEFAULT_MIN_TEMP = 7.0
    const val DEFAULT_MAX_TEMP = 35.0
    const val DEFAULT_TEMP_STEP = 1.0

    fun modes(b: MqttBindings): List<String> = b.strList("modes") ?: DEFAULT_MODES
    fun fanModes(b: MqttBindings): List<String> = b.strList("fan_modes") ?: DEFAULT_FAN_MODES

    override fun stateTopics(b: MqttBindings): Set<String> =
        ThermostatBase.stateTopics(b) + setOfNotNull(b.str("fan_mode_state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        if (topic == b.str("fan_mode_state_topic")) {
            var climate = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
            val parts = PartialDecode()
            parts.part(renderIncoming(b, "fan_mode_state_template", payload)) { v ->
                val fan = v.asPayloadString()?.takeIf { it in fanModes(b) } ?: return@part false
                climate = climate.copy(fanMode = fan)
                true
            }
            return parts.result(climate)
        }
        return ThermostatBase.decode(b, topic, payload, previous, modes(b))
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.SetTemperature -> ThermostatBase.encodeTemperature(
            b, command.temperature, b.double("min_temp") ?: DEFAULT_MIN_TEMP, b.double("max_temp") ?: DEFAULT_MAX_TEMP,
        )
        is Command.SetHvacMode -> ThermostatBase.encodeMode(b, command.mode, modes(b))
        is Command.SetFanMode ->
            if (command.mode !in fanModes(b)) Encoded.NotSupported("fan mode '${command.mode}' not supported")
            else publish(b, "fan_mode_command_topic", "fan_mode_command_template", command.mode)
        Command.TurnOff -> ThermostatBase.encodeMode(b, "off", modes(b))
        else -> Encoded.NotSupported("climate cannot $command")
    }

    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> = when (command) {
        is Command.SetFanMode -> setOfNotNull(b.str("fan_mode_state_topic"))
        else -> ThermostatBase.feedbackTopics(b, command)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Climate ?: StateValue.Climate(null, null, null)
        return when (command) {
            is Command.SetFanMode -> prev.copy(fanMode = command.mode)
            else -> ThermostatBase.predict(command, previous)
        }
    }
}
```

Add the two imports back if your IDE drops them: `asPayloadString`, no `asDouble` or `formatNumber` are needed anymore.

- [ ] **Step 4: Re-run `ClimateMapperTest` to prove no regression**

Run: `./gradlew :core:state:test --tests "com.gekkotron.oikos.state.kinds.ClimateMapperTest"`
Expected: PASS (same 7 tests). If any test fails, the extraction changed semantics — fix the refactor, do not change the test.

- [ ] **Step 5: Run the whole `:core:state` test module as a safety net**

Run: `./gradlew :core:state:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add \
  core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/ThermostatBase.kt \
  core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/ClimateMapper.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
refactor(state): extract ClimateMapper topic/template plumbing

So the new WaterHeaterMapper can reuse the current/target/mode decode
and the SetTemperature / SetHvacMode / TurnOff encode without copying
them. ClimateMapper keeps its fan-mode handling and defaults; its tests
pass unchanged.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Add `Kind.WATER_HEATER` to the model

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Kind.kt`

**Interfaces:**
- Produces (used by every later task): `Kind.WATER_HEATER` enum value.

- [ ] **Step 1: Add the enum value**

Change the enum declaration to:

```kotlin
enum class Kind { LIGHT, SWITCH, COVER, CLIMATE, SENSOR, BINARY_SENSOR, ALARM_PANEL, NUMBER, SELECT, BUTTON, VACUUM, WATER_HEATER }
```

- [ ] **Step 2: Compile to surface every exhaustive-when the new value breaks**

Run: `./gradlew :core:model:compileKotlin :core:state:compileKotlin :core:source:compileKotlin :feature:home:compileDebugKotlin`
Expected: FAIL with "exhaustive when" warnings/errors on `KindMappers.forKind`, `DiscoveryParser.capabilities`, `HomeUiBuilder.tapFor`, `HomeUiBuilder.isOn`, `iconSpec` (feature/home/ui/KindIcon.kt), and `CardFormatter.format` branches that pattern-match on `Kind`. Leave the compile broken for Task 3 to resolve.

- [ ] **Step 3: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add \
  core/model/src/main/kotlin/com/gekkotron/oikos/model/Kind.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
model: add Kind.WATER_HEATER

Follow-up tasks wire the mapper, discovery, UI and demo.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `WaterHeaterMapper` + `KindMappers` routing

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/WaterHeaterMapper.kt`
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/WaterHeaterMapperTest.kt`

**Interfaces:**
- Consumes: `ThermostatBase` (Task 1); `Kind.WATER_HEATER` (Task 2).
- Produces (used by Tasks 4, 5, 6):
  - `object WaterHeaterMapper : KindMapper` with:
    - `val DEFAULT_MODES = listOf("off", "eco", "electric", "gas", "heat_pump", "high_demand", "performance")`
    - `const val DEFAULT_MIN_TEMP = 43.0`
    - `const val DEFAULT_MAX_TEMP = 60.0`
    - `const val DEFAULT_TEMP_STEP = 1.0`
    - `fun modes(b: MqttBindings): List<String>` — `b.strList("modes") ?: DEFAULT_MODES`.
  - `KindMappers.forKind(Kind.WATER_HEATER)` returns `WaterHeaterMapper`.

- [ ] **Step 1: Write the failing WaterHeaterMapperTest**

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Climate
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WaterHeaterMapperTest {
    // What jMQTT publishes to spec Appendix B.1 for the force-heat heater.
    private val jeedom = bindings(
        "water_heater",
        """{"current_temperature_topic":"water-heater/data",
           "current_temperature_template":"{{ value_json.temp_0 }}",
           "mode_state_topic":"jeedom/water_heater/state",
           "mode_state_template":"{{ value_json.mode }}",
           "temperature_state_topic":"jeedom/water_heater/state",
           "temperature_state_template":"{{ value_json.target }}",
           "mode_command_topic":"jeedom/water_heater/mode/set",
           "temperature_command_topic":"jeedom/water_heater/temperature/set",
           "modes":["off","eco","performance"],
           "min_temp":40,"max_temp":65,"precision":1.0}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `current temperature from its own topic`() {
        assertEquals(
            Decoded.State(Climate(null, null, 52.3)),
            WaterHeaterMapper.decode(jeedom, "water-heater/data", """{"temp_0":52.3,"triac_0_power":30}""", Unknown),
        )
    }

    @Test
    fun `mode and target on one JSON topic`() {
        assertEquals(
            Decoded.State(Climate("performance", 60.0, null)),
            WaterHeaterMapper.decode(jeedom, "jeedom/water_heater/state", """{"mode":"performance","target":60}""", Unknown),
        )
    }

    @Test
    fun `mode must be one of the declared modes`() {
        assertEquals(
            Decoded.State(Climate("eco", 55.0, null)),
            WaterHeaterMapper.decode(jeedom, "jeedom/water_heater/state", """{"mode":"eco","target":55}""", Unknown),
        )
        assertEquals(
            Decoded.Ignored,
            WaterHeaterMapper.decode(jeedom, "jeedom/water_heater/state", """{"mode":"turbo","target":55}""", Unknown),
        )
    }

    @Test
    fun `SetTemperature is range checked and formatted`() {
        assertEquals(
            publishes("jeedom/water_heater/temperature/set", "58"),
            WaterHeaterMapper.encode(jeedom, Command.SetTemperature(58.0), Unknown),
        )
        assertEquals(
            publishes("jeedom/water_heater/temperature/set", "58.5"),
            WaterHeaterMapper.encode(jeedom, Command.SetTemperature(58.5), Unknown),
        )
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(jeedom, Command.SetTemperature(10.0), Unknown))
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(jeedom, Command.SetTemperature(80.0), Unknown))
    }

    @Test
    fun `SetHvacMode publishes known modes only`() {
        assertEquals(
            publishes("jeedom/water_heater/mode/set", "performance"),
            WaterHeaterMapper.encode(jeedom, Command.SetHvacMode("performance"), Unknown),
        )
        assertEquals(
            publishes("jeedom/water_heater/mode/set", "eco"),
            WaterHeaterMapper.encode(jeedom, Command.SetHvacMode("eco"), Unknown),
        )
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(jeedom, Command.SetHvacMode("turbo"), Unknown))
    }

    @Test
    fun `TurnOff is accepted only when off is in the modes`() {
        assertEquals(
            publishes("jeedom/water_heater/mode/set", "off"),
            WaterHeaterMapper.encode(jeedom, Command.TurnOff, Unknown),
        )
        val noOff = bindings(
            "water_heater",
            """{"mode_command_topic":"m/set","modes":["eco","performance"]}""",
        )
        assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(noOff, Command.TurnOff, Unknown))
    }

    @Test
    fun `defaults when the config is sparse`() {
        val sparse = bindings("water_heater", "{}")
        assertEquals(WaterHeaterMapper.DEFAULT_MODES, WaterHeaterMapper.modes(sparse))
        assertEquals(emptySet(), WaterHeaterMapper.stateTopics(sparse))
    }

    @Test
    fun `prediction and feedback topics`() {
        assertEquals(
            Climate("performance", 60.0, 52.0),
            WaterHeaterMapper.predict(Command.SetHvacMode("performance"), Climate("eco", 60.0, 52.0)),
        )
        assertEquals(
            Climate(null, 58.0, null),
            WaterHeaterMapper.predict(Command.SetTemperature(58.0), Unknown),
        )
        assertEquals(
            setOf("jeedom/water_heater/state"),
            WaterHeaterMapper.feedbackTopics(jeedom, Command.SetHvacMode("eco")),
        )
        assertEquals(
            setOf("jeedom/water_heater/state"),
            WaterHeaterMapper.feedbackTopics(jeedom, Command.SetTemperature(58.0)),
        )
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :core:state:test --tests "com.gekkotron.oikos.state.kinds.WaterHeaterMapperTest"`
Expected: FAIL with "Unresolved reference: WaterHeaterMapper".

- [ ] **Step 3: Implement `WaterHeaterMapper.kt`**

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

/**
 * HA `water_heater`. Reuses [ThermostatBase] for current / target / mode and keeps the state as a
 * [StateValue.Climate] with `fanMode = null`. Jeedom owns `off`, so the app never sends it even
 * when `off` is in the modes — Task 5's UI filters it out of the chips.
 */
object WaterHeaterMapper : KindMapper {
    val DEFAULT_MODES = listOf("off", "eco", "electric", "gas", "heat_pump", "high_demand", "performance")
    const val DEFAULT_MIN_TEMP = 43.0
    const val DEFAULT_MAX_TEMP = 60.0
    const val DEFAULT_TEMP_STEP = 1.0

    fun modes(b: MqttBindings): List<String> = b.strList("modes") ?: DEFAULT_MODES

    override fun stateTopics(b: MqttBindings): Set<String> = ThermostatBase.stateTopics(b)

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        ThermostatBase.decode(b, topic, payload, previous, modes(b))

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.SetTemperature -> ThermostatBase.encodeTemperature(
            b, command.temperature, b.double("min_temp") ?: DEFAULT_MIN_TEMP, b.double("max_temp") ?: DEFAULT_MAX_TEMP,
        )
        is Command.SetHvacMode -> ThermostatBase.encodeMode(b, command.mode, modes(b))
        Command.TurnOff -> ThermostatBase.encodeMode(b, "off", modes(b))
        else -> Encoded.NotSupported("water heater cannot $command")
    }

    override fun feedbackTopics(b: MqttBindings, command: Command): Set<String> =
        ThermostatBase.feedbackTopics(b, command)

    override fun predict(command: Command, previous: StateValue): StateValue? =
        ThermostatBase.predict(command, previous)
}
```

- [ ] **Step 4: Add the routing in `KindMapper.kt`**

In `KindMappers.forKind`, add the branch:

```kotlin
Kind.WATER_HEATER -> WaterHeaterMapper
```

- [ ] **Step 5: Re-run the mapper test**

Run: `./gradlew :core:state:test --tests "com.gekkotron.oikos.state.kinds.WaterHeaterMapperTest"`
Expected: PASS (all 8 tests).

- [ ] **Step 6: Run the whole state module to prove nothing else broke**

Run: `./gradlew :core:state:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add \
  core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/WaterHeaterMapper.kt \
  core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt \
  core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/WaterHeaterMapperTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(state): WaterHeaterMapper for the HA water_heater kind

Reuses ThermostatBase for current / target / mode decode and the
SetTemperature / SetHvacMode / TurnOff encode. State stays a
StateValue.Climate so existing climate consumers (CardFormatter,
HomeUiBuilder.isOn, …) handle it without change.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Discovery parser (`water_heater` component, capabilities, test)

**Files:**
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt`
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`

**Interfaces:**
- Consumes: `WaterHeaterMapper` (Task 3); `Kind.WATER_HEATER` (Task 2).
- Produces (used by Task 5's UI builder):
  - Discovery of `homeassistant/water_heater/.../config` builds a `DiscoveredEntity` with:
    - `kind = Kind.WATER_HEATER`
    - `capabilities.hvacModes = WaterHeaterMapper.modes(b)` (unchanged list including `off` if the config has it — the UI filters)
    - `capabilities.minTemp = b.double("min_temp") ?: WaterHeaterMapper.DEFAULT_MIN_TEMP`
    - `capabilities.maxTemp = b.double("max_temp") ?: WaterHeaterMapper.DEFAULT_MAX_TEMP`
    - `capabilities.tempStep = b.double("precision") ?: WaterHeaterMapper.DEFAULT_TEMP_STEP` (water heaters have no `temp_step` in HA; `precision` is their only step hint)
  - Not in `REQUIRES_COMMAND` (a read-only water heater is accepted).

- [ ] **Step 1: Add the failing discovery test**

Append to `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`, keeping the file's existing `single(topic, payload)` helper and `Kind` import. If `Kind` is not already imported, add `import com.gekkotron.oikos.model.Kind` next to the existing imports.

```kotlin
@Test
fun `abbreviated water_heater config becomes a WATER_HEATER entity`() {
    val built = single(
        "homeassistant/water_heater/jeedom/water_heater/config",
        """{"uniq_id":"jeedom_water_heater","modes":["off","eco","performance"],
           "min_temp":40,"max_temp":65,"precision":1.0,
           "curr_temp_t":"water-heater/data","curr_temp_tpl":"{{ value_json.temp_0 }}",
           "mode_stat_t":"jeedom/water_heater/state","mode_stat_tpl":"{{ value_json.mode }}",
           "temp_stat_t":"jeedom/water_heater/state","temp_stat_tpl":"{{ value_json.target }}",
           "mode_cmd_t":"jeedom/water_heater/mode/set",
           "temp_cmd_t":"jeedom/water_heater/temperature/set"}""",
    )
    assertEquals(Kind.WATER_HEATER, built.entity.kind)
    val caps = built.entity.capabilities
    assertEquals(listOf("off", "eco", "performance"), caps.hvacModes)
    assertEquals(40.0, caps.minTemp)
    assertEquals(65.0, caps.maxTemp)
    assertEquals(1.0, caps.tempStep)
}

@Test
fun `water_heater defaults when the config omits ranges`() {
    val built = single(
        "homeassistant/water_heater/x/config",
        """{"mode_cmd_t":"m/set"}""",
    )
    val caps = built.entity.capabilities
    assertEquals(43.0, caps.minTemp)
    assertEquals(60.0, caps.maxTemp)
    assertEquals(1.0, caps.tempStep)
    assertEquals(
        listOf("off", "eco", "electric", "gas", "heat_pump", "high_demand", "performance"),
        caps.hvacModes,
    )
}

@Test
fun `water_heater without a command topic is still discovered`() {
    val built = single(
        "homeassistant/water_heater/read/config",
        """{"curr_temp_t":"t"}""",
    )
    assertEquals(Kind.WATER_HEATER, built.entity.kind)
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :core:source:test --tests "com.gekkotron.oikos.source.discovery.DiscoveryParserTest"`
Expected: FAIL with "unsupported component 'water_heater'".

- [ ] **Step 3: Add the KINDS entry and the capabilities branch**

In `DiscoveryParser.KINDS`, add after the `"vacuum"` line:

```kotlin
"water_heater" to Kind.WATER_HEATER,
```

In `DiscoveryParser.capabilities`, add before the `else` branch, right after the existing `Kind.VACUUM` branch:

```kotlin
Kind.WATER_HEATER -> Capabilities(
    hvacModes = WaterHeaterMapper.modes(b),
    minTemp = b.double("min_temp") ?: WaterHeaterMapper.DEFAULT_MIN_TEMP,
    maxTemp = b.double("max_temp") ?: WaterHeaterMapper.DEFAULT_MAX_TEMP,
    tempStep = b.double("precision") ?: WaterHeaterMapper.DEFAULT_TEMP_STEP,
)
```

Add the matching import at the top of the file:

```kotlin
import com.gekkotron.oikos.state.kinds.WaterHeaterMapper
```

- [ ] **Step 4: Re-run the discovery test**

Run: `./gradlew :core:source:test --tests "com.gekkotron.oikos.source.discovery.DiscoveryParserTest"`
Expected: PASS (new 3 tests + existing ones).

- [ ] **Step 5: Run the whole source module**

Run: `./gradlew :core:source:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add \
  core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt \
  core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(source): discover HA water_heater configs

Component -> Kind.WATER_HEATER, modes/min_temp/max_temp/precision mapped
to the standard Capabilities fields; a read-only water heater is accepted
(not in REQUIRES_COMMAND). The existing climate abbreviations cover the
topic/template keys.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Home UI — card, sheet, Force toggle, strings

Wires `Kind.WATER_HEATER` through `HomeUiBuilder`, `CardFormatter`, `CardText`, `iconSpec`, `ControlSheet`, and `EntityCard`. Adds the on-card Force toggle, the sheet's mode chips (minus `off`), the target stepper and the same-`deviceId` number companions. Adds the EN+FR strings.

**Files:**
- Modify:
  - `core/model/src/main/kotlin/com/gekkotron/oikos/model/Entity.kt` (no change: `Capabilities` already has `hvacModes`, `minTemp`, `maxTemp`, `tempStep`)
  - `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt`
  - `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt`
  - `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/CardFormatter.kt`
  - `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CardText.kt`
  - `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt`
  - `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt`
  - `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt`
  - `feature/home/src/main/res/values/strings.xml`
  - `feature/home/src/main/res/values-fr/strings.xml`
- Test:
  - `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderWaterHeaterTest.kt` (new)

**Interfaces:**
- Consumes: `Kind.WATER_HEATER`, `WaterHeaterMapper`, `StateValue.Climate`.
- Produces (used by Task 6's screenshot):
  - `CardUi.forceToggle: Boolean?` (null = no Force toggle on this card; `true`/`false` = currently in `performance`/not).
  - `HomeUiBuilder.SHEET_KINDS` includes `Kind.WATER_HEATER`.
  - `HomeUiBuilder.tapFor(Kind.WATER_HEATER, _) == TapAction.OPEN_SHEET`.
  - `HomeUiBuilder.isOn(Kind.WATER_HEATER, StateValue.Climate("performance", …)) == true` and `false` for every other mode.
  - `WaterHeaterControls` composable in `ControlSheet.kt`.

- [ ] **Step 1: Write the failing presentation test**

Create `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderWaterHeaterTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeUiBuilderWaterHeaterTest {
    private fun build(
        mode: String?,
        modes: List<String> = listOf("off", "eco", "performance"),
        companions: List<Pair<String, String>> = listOf("force_power" to "wh_device"),
    ): CardUi {
        val wh = testEntity(
            "wh", Kind.WATER_HEATER, "Water heater", area = "garage", deviceId = "wh_device",
            capabilities = Capabilities(hvacModes = modes, minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0),
        )
        val numbers = companions.map { (id, device) ->
            testEntity(id, Kind.NUMBER, "Force power", area = "garage", deviceId = device,
                capabilities = Capabilities(min = 0.0, max = 100.0, step = 10.0))
        }
        val entities = (listOf(wh) + numbers).associateBy { it.uniqueId }
        val states = mapOf("wh" to EntityState("wh", StateValue.Climate(mode, 60.0, 52.0)))
        val ui = HomeUiBuilder.build(
            entities, states, listOf(Area("garage", "Garage")), RoomFilter.All,
            LinkState.Connected(TransportMode.DEMO), Locale.US,
        )
        return ui.card("wh") ?: error("water heater card missing")
    }

    @Test
    fun `tap opens the sheet`() {
        assertEquals(TapAction.OPEN_SHEET, build("eco").tap)
        assertTrue(build("eco").hasSheet)
    }

    @Test
    fun `on is true only in performance`() {
        assertTrue(build("performance").on)
        assertEquals(false, build("eco").on)
        assertEquals(false, build("off").on)
        assertEquals(false, build(null).on)
    }

    @Test
    fun `force toggle only when both performance and eco`() {
        assertEquals(false, build("eco").forceToggle)
        assertEquals(true, build("performance").forceToggle)
        assertNull(build("eco", modes = listOf("off", "electric", "gas")).forceToggle)
        assertNull(build("eco", modes = listOf("eco")).forceToggle)
    }

    @Test
    fun `water heater sheet shows same-device numbers as companions`() {
        val card = build("eco")
        assertEquals(listOf("force_power"), card.companions.map { it.id })
    }

    @Test
    fun `water heater sheet without companions`() {
        val card = build("eco", companions = emptyList())
        assertEquals(emptyList(), card.companions)
    }

    @Test
    fun `companions filter out numbers from other devices`() {
        val card = build("eco", companions = listOf("force_power" to "wh_device", "other" to "another_device"))
        assertEquals(listOf("force_power"), card.companions.map { it.id })
    }
}
```

If `testEntity` does not already accept a `deviceId` parameter, extend its signature in `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/TestFixtures.kt` (or wherever it lives — `grep -rn "fun testEntity" feature/home/src/test` to locate it) to pass through to `Entity.deviceId`. Keep the default `null`.

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.gekkotron.oikos.feature.home.presentation.HomeUiBuilderWaterHeaterTest"`
Expected: FAIL (compile error: `CardUi.forceToggle` unresolved, `Kind.WATER_HEATER` not in exhaustive branches).

- [ ] **Step 3: Add `forceToggle` to `CardUi`**

In `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt`, add after `groupable`:

```kotlin
/** Water heater on-card Force switch (spec §5): null = no toggle, true = currently in `performance`. */
val forceToggle: Boolean? = null,
```

- [ ] **Step 4: Teach `HomeUiBuilder` about `WATER_HEATER`**

In `HomeUiBuilder.kt`:

1. Add `Kind.WATER_HEATER` to the `SHEET_KINDS` set:
   ```kotlin
   private val SHEET_KINDS = setOf(Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL, Kind.VACUUM, Kind.WATER_HEATER)
   ```

2. In `tapFor`, add it to the `OPEN_SHEET` branch:
   ```kotlin
   Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL, Kind.VACUUM, Kind.WATER_HEATER -> TapAction.OPEN_SHEET
   ```

3. In `isOn`, add a WATER_HEATER-specific branch *before* the `StateValue.Climate` branch — a `StateValue.Climate` is used for both kinds, so the branch key is the entity's `kind`, not the state type. Change the function to take `kind` into account only for water heater:
   ```kotlin
   private fun isOn(kind: Kind, v: StateValue) = when (v) {
       is StateValue.Light -> v.on
       is StateValue.OnOff -> kind == Kind.SWITCH && v.on
       is StateValue.Cover -> v.state == CoverState.OPEN || v.state == CoverState.OPENING ||
           (v.state == CoverState.STOPPED && (v.position ?: 0) > 0)
       is StateValue.Climate ->
           if (kind == Kind.WATER_HEATER) v.mode == "performance"
           else v.mode != null && v.mode != "off"
       else -> false
   }
   ```

4. Extend `companions()` so a water heater collects every visible `Kind.NUMBER` entity whose `deviceId` matches, in `uniqueId` order. Add `entities` as a parameter and update both call sites:
   ```kotlin
   private fun companions(
       e: Entity,
       children: Map<String, List<Entity>>,
       entities: Map<String, Entity>,
       states: Map<String, EntityState>,
       areaNames: Map<String, String>,
       locale: Locale,
       labels: LabelText,
       historyEnabled: Boolean,
   ): List<CardUi> {
       val list: List<Entity> = when (e.kind) {
           Kind.LIGHT -> children[e.uniqueId].orEmpty()
           Kind.WATER_HEATER -> {
               val deviceId = e.deviceId
               if (deviceId == null) emptyList()
               else entities.values.filter {
                   it.kind == Kind.NUMBER && it.deviceId == deviceId && !it.ui.hidden && it.uniqueId != e.uniqueId
               }.sortedBy { it.uniqueId }
           }
           else -> emptyList()
       }
       return list
           .map { c -> card(c, companionName(c, labels), states[c.uniqueId], areaNames, locale, emptyList(), historyEnabled) }
           .let { cards -> if (e.kind == Kind.LIGHT) cards.sortedWith(compareBy({ it.name.lowercase(locale) }, { it.id })) else cards }
   }
   ```
   In `build()`, update `fun companionsOf(e: Entity)` to pass `entities`:
   ```kotlin
   fun companionsOf(e: Entity): List<CardUi> = companions(e, children, entities, states, areaNames, locale, labels, historyEnabled)
   ```
   In `cardFor()`, pass `entities` to the direct call:
   ```kotlin
   companions(e, childrenByParent(entities), entities, states, areaNames, locale, labels, historyEnabled)
   ```

5. In the `card()` call site, pass `forceToggle = forceToggleFor(entity, value)`; define the helper near `tapFor`:
   ```kotlin
   private fun forceToggleFor(entity: Entity, value: StateValue): Boolean? {
       if (entity.kind != Kind.WATER_HEATER) return null
       val modes = entity.capabilities.hvacModes
       if ("performance" !in modes || "eco" !in modes) return null
       return (value as? StateValue.Climate)?.mode == "performance"
   }
   ```
   Add `forceToggle = forceToggleFor(entity, value)` to the `CardUi(…)` constructor call.

- [ ] **Step 5: `CardFormatter` keeps the same shape for a water heater — no change**

A water heater is a `StateValue.Climate`, which `CardFormatter.format` already turns into `CardValue.Climate`. No change needed.

- [ ] **Step 6: Subtitle in `CardText.cardValueText`**

The default `CardValue.Climate` branch already renders `"52° · heat"` style. For the water heater we need the spec §5 strings (`Eco · 60 °C target`, `Forced → 60 °C`, `Hot · 60 °C target`). The cleanest path: thread the card's `kind` into `cardValueText` and branch inside the climate case.

Change the signature:

```kotlin
@Composable
internal fun cardValueText(value: CardValue, kind: Kind = Kind.SENSOR): String = when (value) {
    …
    is CardValue.Climate ->
        if (kind == Kind.WATER_HEATER) waterHeaterSubtitle(value)
        else listOfNotNull(value.current?.let { "$it°" }, value.mode?.let { modeLabel(it) })
            .joinToString(" · ")
            .ifEmpty { stringResource(R.string.value_none) }
    …
}

@Composable
private fun waterHeaterSubtitle(value: CardValue.Climate): String {
    val target = value.target
    val mode = value.mode
    return when (mode) {
        "performance" -> if (target != null) stringResource(R.string.water_heater_forced_to, target)
                        else stringResource(R.string.water_heater_forced)
        "eco" -> if (target != null) stringResource(R.string.water_heater_eco_target, target)
                 else stringResource(R.string.water_heater_eco)
        "off" -> if (target != null) stringResource(R.string.water_heater_hot_target, target)
                 else stringResource(R.string.water_heater_hot)
        null -> stringResource(R.string.value_none)
        else -> {
            val label = waterHeaterModeLabel(mode)
            if (target != null) "$label · ${stringResource(R.string.water_heater_target, target)}" else label
        }
    }
}

@Composable
private fun waterHeaterModeLabel(mode: String): String = when (mode) {
    "eco" -> stringResource(R.string.water_heater_mode_eco)
    "performance" -> stringResource(R.string.water_heater_mode_performance)
    "off" -> stringResource(R.string.mode_off)
    "electric" -> stringResource(R.string.water_heater_mode_electric)
    "gas" -> stringResource(R.string.water_heater_mode_gas)
    "heat_pump" -> stringResource(R.string.water_heater_mode_heat_pump)
    "high_demand" -> stringResource(R.string.water_heater_mode_high_demand)
    else -> mode.replaceFirstChar { it.uppercase() }
}
```

Also update the one call site in `EntityCard.kt`:

```kotlin
val valueText = if (card.available) cardValueText(card.value, card.kind) else stringResource(R.string.value_unavailable)
```

- [ ] **Step 7: Card glyph in `KindIcon.iconSpec`**

Add the branch in `iconSpec(card: CardUi)`:

```kotlin
Kind.WATER_HEATER -> IconSpec.Static(OikosGlyph.WATER_HEATER, card.on)
```

(`OikosGlyph.WATER_HEATER` already exists in `StaticIcon.kt`.)

- [ ] **Step 8: On-card Force switch in `EntityCard`**

In `EntityCard.kt`, inside the top `Row` next to the `PendingDot`, render the Force switch when `card.forceToggle != null`:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    KindIcon(icon)
    Spacer(Modifier.weight(1f))
    card.forceToggle?.let { forced ->
        Switch(
            checked = forced,
            onCheckedChange = { onForce() },
            enabled = card.available,
        )
    }
    if (card.pending) PendingDot()
}
```

Add the new callback to the composable signature and pass it from the call site:

```kotlin
@Composable
internal fun EntityCard(
    card: CardUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onForce: () -> Unit = {},
    modifier: Modifier = Modifier,
)
```

Add the import for `androidx.compose.material3.Switch` next to the existing material3 imports.

Thread `onForce` through from the Home screen caller: find the `EntityCard(` call in `HomeScreen.kt` (or wherever cards are instantiated — `grep -rn "EntityCard(" feature/home/src/main/kotlin`) and pass:

```kotlin
onForce = {
    val next = if (card.forceToggle == true) "eco" else "performance"
    onSend(card.id, Command.SetHvacMode(next))
},
```

- [ ] **Step 9: `WaterHeaterControls` sheet composable**

In `ControlSheet.kt`, add the branch to the main `when (card.kind)`:

```kotlin
Kind.WATER_HEATER -> WaterHeaterControls(card, send) { id, cmd -> onSend(id, cmd) }
```

And the composable (adapted from `ClimateControls`, with the water-heater-specific chip filter and companion loop):

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaterHeaterControls(card: CardUi, onSend: (Command) -> Unit, onSendCompanion: (String, Command) -> Unit) {
    val climate = card.state as? StateValue.Climate
    val caps = card.capabilities
    val min = caps.minTemp ?: 43.0
    val max = caps.maxTemp ?: 60.0
    val step = caps.tempStep ?: 1.0
    val target = climate?.target ?: ((min + max) / 2)
    val locale = LocalConfiguration.current.locales[0]
    val decrease = stringResource(R.string.sheet_decrease)
    val increase = stringResource(R.string.sheet_increase)

    // Mode chips: every discovered mode except "off"; "eco" and "performance" get the Force / Éco labels.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in caps.hvacModes.filter { it != "off" }) {
            FilterChip(
                selected = climate?.mode == mode,
                onClick = { onSend(Command.SetHvacMode(mode)) },
                label = { Text(waterHeaterModeLabel(mode)) },
                enabled = card.available,
            )
        }
    }

    Text(stringResource(R.string.sheet_target))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, -step, min, max))) },
            enabled = card.available && target > min,
            modifier = Modifier.semantics { contentDescription = decrease },
        ) { Text("−", fontSize = 22.sp) }
        Text(CardFormatter.number(target, locale) + "°", style = OikosTheme.type.display)
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, step, min, max))) },
            enabled = card.available && target < max,
            modifier = Modifier.semantics { contentDescription = increase },
        ) { Text("+", fontSize = 22.sp) }
    }

    // Same-device Kind.NUMBER companions (e.g. Force power); each still has its own standalone card.
    for (companion in card.companions) {
        if (companion.kind == Kind.NUMBER) {
            Text(companion.name)
            NumberControls(companion) { onSendCompanion(companion.id, it) }
        }
    }
}
```

Add a `waterHeaterModeLabel(mode: String): String` helper in `CardText.kt` (step 6 already defined one; move its visibility to `internal` so `ControlSheet.kt` can call it).

- [ ] **Step 10: Strings — `values/strings.xml` (EN)**

Append under the existing `vacuum_*` block:

```xml
<string name="water_heater_eco">Eco</string>
<string name="water_heater_eco_target">Eco · %1$s°C target</string>
<string name="water_heater_forced">Forced</string>
<string name="water_heater_forced_to">Forced → %1$s°C</string>
<string name="water_heater_hot">Hot</string>
<string name="water_heater_hot_target">Hot · %1$s°C target</string>
<string name="water_heater_target">%1$s°C target</string>
<string name="water_heater_mode_eco">Eco</string>
<string name="water_heater_mode_performance">Force</string>
<string name="water_heater_mode_electric">Electric</string>
<string name="water_heater_mode_gas">Gas</string>
<string name="water_heater_mode_heat_pump">Heat pump</string>
<string name="water_heater_mode_high_demand">High demand</string>
```

The target strings take `%1$s` because `CardValue.Climate.target` is already a locale-formatted string.

- [ ] **Step 11: Strings — `values-fr/strings.xml` (FR)**

Append the matching keys with French labels (spec §5):

```xml
<string name="water_heater_eco">Éco</string>
<string name="water_heater_eco_target">Éco · cible %1$s °C</string>
<string name="water_heater_forced">Forcé</string>
<string name="water_heater_forced_to">Forcé → %1$s °C</string>
<string name="water_heater_hot">Chaud</string>
<string name="water_heater_hot_target">Chaud · cible %1$s °C</string>
<string name="water_heater_target">cible %1$s °C</string>
<string name="water_heater_mode_eco">Éco</string>
<string name="water_heater_mode_performance">Forcé</string>
<string name="water_heater_mode_electric">Électrique</string>
<string name="water_heater_mode_gas">Gaz</string>
<string name="water_heater_mode_heat_pump">Pompe à chaleur</string>
<string name="water_heater_mode_high_demand">Forte demande</string>
```

- [ ] **Step 12: Re-run the presentation test**

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.gekkotron.oikos.feature.home.presentation.HomeUiBuilderWaterHeaterTest"`
Expected: PASS (all 6 tests).

- [ ] **Step 13: Run the whole `:feature:home` test module**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 14: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add \
  feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt \
  feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt \
  feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CardText.kt \
  feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt \
  feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt \
  feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt \
  feature/home/src/main/res/values/strings.xml \
  feature/home/src/main/res/values-fr/strings.xml \
  feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderWaterHeaterTest.kt
# Also add the test fixture file if testEntity gained a deviceId parameter:
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add -u feature/home/src/test
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): water heater card, sheet and on-card Force toggle

Card: WATER_HEATER glyph, 'Eco · cible X °C' / 'Forcé → X °C' / 'Chaud ·
cible X °C' subtitles, an on-card Force switch when the modes contain
both performance and eco.
Sheet: mode chips (all discovered modes except off, with Force/Éco for
performance/eco), target stepper, same-deviceId Kind.NUMBER companions
rendered with the existing number slider.
Strings in EN and FR.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: Demo house, DemoBrokerTest round-trip, Robolectric screenshot

**Files:**
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`
- Modify: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt`
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WaterHeaterScreenshotTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: a demo heater `demo_water_heater` + its `demo_water_heater_force_power` number, both on the `demo_water_heater` device (so Task 5's companion lookup finds the number from the heater). Replaces the old standalone `demo_water_heater_target` number.

- [ ] **Step 1: Write the failing DemoBrokerTest round-trip**

In `DemoBrokerTest.kt`:

1. Update `allIds` — replace `"demo_water_heater_target"` with `"demo_water_heater"` and add `"demo_water_heater_force_power"`.
2. Add a new test:

```kotlin
@Test
fun `forcing the water heater round-trips through the demo broker`() = runTest {
    val h = start()
    assertEquals(CommandResult.Accepted, h.source.send("demo_water_heater", Command.SetHvacMode("performance")))
    settle()
    val forced = assertIs<StateValue.Climate>(h.value("demo_water_heater"))
    assertEquals("performance", forced.mode)

    assertEquals(CommandResult.Accepted, h.source.send("demo_water_heater", Command.SetHvacMode("eco")))
    settle()
    val eco = assertIs<StateValue.Climate>(h.value("demo_water_heater"))
    assertEquals("eco", eco.mode)

    assertEquals(CommandResult.Accepted, h.source.send("demo_water_heater", Command.SetTemperature(58.0)))
    settle()
    val tempered = assertIs<StateValue.Climate>(h.value("demo_water_heater"))
    assertEquals(58.0, tempered.target)
}
```

Also remove the line `h.source.send("demo_water_heater_target", Command.SetNumber(58.0))` and the matching `assertEquals(StateValue.Numeric(58.0), h.value("demo_water_heater_target"))` in the existing `cover, climate, number, select and alarm commands are echoed` test — the heater is no longer a bare `number`.

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :core:demo:test --tests "com.gekkotron.oikos.demo.DemoBrokerTest"`
Expected: FAIL (missing `demo_water_heater` entity, allIds mismatch).

- [ ] **Step 3: Add the demo heater in `DemoHouse.kt`**

Remove the old standalone number from the init block (`number("demo_water_heater_target", "Water heater target", "Garage", "demo/garage/water_heater")`) and the matching `number()` helper if nothing else calls it (grep first: `grep -n 'number(' core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`).

Replace with a dedicated `waterHeater()` factory and its call in `init`:

```kotlin
waterHeater("demo_water_heater", "Water heater", "Garage", "demo/water_heater")
```

```kotlin
/** HA water_heater on the `Water heater` device, with a Force power number companion on the same device. */
private fun waterHeater(id: String, device: String, area: String, topic: String) {
    val modes = listOf("off", "eco", "performance")
    config("water_heater", id, device, null, area) {
        putJsonArray("modes") { modes.forEach { add(it) } }
        put("min_temp", 40)
        put("max_temp", 65)
        put("precision", 1.0)
        put("current_temperature_topic", topic)
        put("current_temperature_template", "{{ value_json.current }}")
        put("mode_state_topic", topic)
        put("mode_state_template", "{{ value_json.mode }}")
        put("temperature_state_topic", topic)
        put("temperature_state_template", "{{ value_json.target }}")
        put("mode_command_topic", "$topic/mode/set")
        put("temperature_command_topic", "$topic/temperature/set")
    }
    config("number", "${id}_force_power", device, "Force power", area) {
        put("state_topic", topic)
        put("value_template", "{{ value_json.force_power }}")
        put("command_topic", "$topic/force_power/set")
        put("min", 0)
        put("max", 100)
        put("step", 10)
        put("unit_of_measurement", "%")
    }
    doc(topic, obj("current" to 52.0, "target" to 60.0, "mode" to "eco", "force_power" to 60))
    echo("$topic/mode/set", topic) { p -> if (p in modes) obj("mode" to p) else null }
    echo("$topic/temperature/set", topic) { p -> p.toDoubleOrNull()?.let { obj("target" to it) } }
    echo("$topic/force_power/set", topic) { p ->
        p.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)?.let { obj("force_power" to it) }
    }
}
```

- [ ] **Step 4: Re-run the DemoBroker test**

Run: `./gradlew :core:demo:test --tests "com.gekkotron.oikos.demo.DemoBrokerTest"`
Expected: PASS.

- [ ] **Step 5: Write the Robolectric screenshot test**

Create `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WaterHeaterScreenshotTest.kt` (one screenshot of the Eco card + one of the Forced card, both themes; one screenshot of the sheet in each theme). Use the rendering trick from `GroupScreenshotTest.kt` and the memory in `[[oikos-energy-flow-edges]]` — `@GraphicsMode(NATIVE)`, `createAndroidComposeRule<ComponentActivity>()`, and `compose.renderPng(name)` which already captures the drawn root.

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.distinctColors
import com.gekkotron.oikos.feature.home.luminanceAt
import com.gekkotron.oikos.feature.home.presentation.CardFormatter
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.feature.home.presentation.TapAction
import com.gekkotron.oikos.feature.home.renderPng
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class WaterHeaterScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val caps = Capabilities(
        hvacModes = listOf("off", "eco", "performance"), minTemp = 40.0, maxTemp = 65.0, tempStep = 1.0,
    )

    private fun card(mode: String): CardUi {
        val value = StateValue.Climate(mode, 60.0, 52.0)
        return CardUi(
            id = "wh", name = "Water heater", kind = Kind.WATER_HEATER, protocolKind = Kind.WATER_HEATER,
            deviceClass = null, areaName = "Garage",
            value = CardFormatter.format(testEntityStub(), value, Locale.US),
            state = value, capabilities = caps, unit = null, on = (mode == "performance"),
            wide = false, available = true, pending = false, tap = TapAction.OPEN_SHEET,
            hasSheet = true, areaId = "garage", ui = UiOverride(), hidden = false,
            forceToggle = if (mode == "performance") true else false,
        )
    }

    private fun testEntityStub() = com.gekkotron.oikos.model.Entity(
        uniqueId = "wh", deviceId = "wh_device", name = "Water heater", kind = Kind.WATER_HEATER,
        deviceClass = null, unit = null, icon = null, capabilities = caps,
        bindings = object : com.gekkotron.oikos.model.Bindings {}, area = "garage",
    )

    private fun render(name: String, theme: ThemeChoice, mode: String) {
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground {
                    Surface(Modifier.padding(16.dp)) {
                        Column { EntityCard(card = card(mode), onClick = {}, onLongClick = {}, onForce = {}) }
                    }
                }
            }
        }
        val bitmap = compose.renderPng(name)
        assertTrue(bitmap.distinctColors() > 4)
        if (theme == ThemeChoice.DARK) assertTrue(bitmap.luminanceAt(4, 4) < 0.4f)
        else assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test fun `card eco light`() = render("water_heater_card_eco_light", ThemeChoice.LIGHT, "eco")
    @Test fun `card eco dark`() = render("water_heater_card_eco_dark", ThemeChoice.DARK, "eco")
    @Test fun `card forced light`() = render("water_heater_card_forced_light", ThemeChoice.LIGHT, "performance")
    @Test fun `card forced dark`() = render("water_heater_card_forced_dark", ThemeChoice.DARK, "performance")

    // Step 5a: a sheet screenshot — chips (without off), target stepper, Force-power companion.
    private fun sheetContent(theme: ThemeChoice) {
        val whCard = card("eco").copy(
            companions = listOf(
                CardUi(
                    id = "wh_force_power", name = "Force power", kind = Kind.NUMBER, protocolKind = Kind.NUMBER,
                    deviceClass = null, areaName = "Garage",
                    value = CardValue.Measurement("60", "%"), state = StateValue.Numeric(60.0),
                    capabilities = Capabilities(min = 0.0, max = 100.0, step = 10.0),
                    unit = "%", on = false, wide = false, available = true, pending = false,
                    tap = TapAction.OPEN_SHEET, hasSheet = true, areaId = "garage",
                    ui = UiOverride(), hidden = false,
                ),
            ),
        )
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground { Surface { Column(Modifier.padding(16.dp)) { WaterHeaterControlsPreview(whCard) } } }
            }
        }
    }

    @Test fun `sheet light`() {
        sheetContent(ThemeChoice.LIGHT)
        val bitmap = compose.renderPng("water_heater_sheet_light")
        assertTrue(bitmap.distinctColors() > 4)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test fun `sheet dark`() {
        sheetContent(ThemeChoice.DARK)
        val bitmap = compose.renderPng("water_heater_sheet_dark")
        assertTrue(bitmap.distinctColors() > 4)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.4f)
    }
}
```

Also expose the private `WaterHeaterControls` from `ControlSheet.kt` as `internal` (or add an `internal fun WaterHeaterControlsPreview(card: CardUi)` wrapper in the same file so the screenshot test can call it without going through the whole modal sheet). The `GroupScreenshotTest` has the same pattern of calling individual composables directly for screenshot purposes.

If `ThemeChoice.DARK` / `ThemeChoice.LIGHT` are not the exact enum names in this codebase, run `grep -rn "enum class ThemeChoice" core/designsystem/src/main` and use whichever names appear (the existing `GroupScreenshotTest.kt` has a working example).

- [ ] **Step 6: Run the screenshot tests**

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.gekkotron.oikos.feature.home.ui.WaterHeaterScreenshotTest"`
Expected: PASS. If a card renders empty (all-background), check the `renderPng` helper's crop and raise the `distinctColors()` threshold back to 8.

- [ ] **Step 7: Full gradle check**

Run: `./gradlew check`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add \
  core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt \
  core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt \
  feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WaterHeaterScreenshotTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(demo): water heater device + Force round-trip, screenshot

Replaces the standalone demo_water_heater_target number with a
water_heater on the Water heater device, plus a Force power number on
the same device. DemoBrokerTest proves the mode and target echoes.
WaterHeaterScreenshotTest renders the Eco and Forced cards in both
themes.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## After the plan is done

1. Merge `feat/water-heater` into `master` locally (no rebase; a merge commit is fine), push both.
2. Watch `gh run list -R Gekkotron/Oikos -L 4` for the two workflows — `CI` and `Build & upload to Firebase App Distribution` — until both finish, then report pass/fail. On failure, follow the CI rule: investigate, fix, commit, push without asking.
3. Update the memory `oikos-water-heater.md` with the merged commit, the pushed SHA and the device-check status (which stays pending until the user builds Appendix B in Jeedom).
