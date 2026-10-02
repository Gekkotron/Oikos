# Security Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A third tab, Security, showing every MQTT `alarm_control_panel` as a hero card (state colours, arm modes, disarm, code keypad) and the safety/intrusion binary sensors (active first), with a placeholder for push alerts. The demo house exercises it end to end.

**Architecture:**
- `core:model` gains the alarm code capabilities (`codeDisarmRequired`, `codeFormat`), `UiOverride.securityHidden` and `SensorGroup`, which maps a device class to a security group.
- `core:source` fills the capabilities.
- `core:designsystem` takes over the alarm UI from `feature:home`: the pure `AlarmActions`, plus `AlarmControls`, `AlarmHero` and `CodeKeypadSheet`. Both Home's sheet and the new `:feature:security` use it.
- `:feature:security` follows `:feature:energy`: a pure `SecurityUiBuilder`, a Hilt `SecurityViewModel` and the `SecurityScreen`.
- `:app` adds the tab.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Navigation 3, kotlinx.serialization, Robolectric Compose tests, kotlin.test / JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-02-oikos-security-design.md`

## Global Constraints

- Nothing house-specific: no code may test for `jeedom`, a topic name or an entity id. Fixtures and the demo may contain them (parent spec goal 7).
- Alarm codes are never persisted. Don't use `rememberSaveable` for a code, don't put one in the entity cache (`MqttDiscoverySource.redact` stays as is), and never log one.
- `REMOTE_CODE` / `REMOTE_CODE_TEXT` → the code is sent as typed. A literal code is checked locally by `AlarmPanelMapper` (unchanged).
- Feature modules depend on `:core:source` and `:core:designsystem` only, never on each other or on `:core:transport` directly (parent §4.1).
- Every new UI string has an `en` (`values/`) and a `fr` (`values-fr/`) entry. English uses US spelling, except existing keys keep their text.
- Minimum touch target `OikosDimens.minTouch` (48 dp); keypad keys are 72 dp.
- Colours by alarm state:
  - `connected` (mint): disarmed
  - `grid` (violet): armed, arming
  - `warning` (amber): pending
  - `heat` (rose): triggered
  - `contentMuted`: unavailable or unknown
- Gradle must run with the sandbox disabled, or the `~/.gradle` writes fail.
- Test commands:
  - JVM modules: `./gradlew :core:<m>:test`
  - Android modules: `./gradlew :<module>:testDebugUnitTest`
  - Everything: `./gradlew check`
- `DirectConnectionTesterTest` (`:app`) is known flaky; a failure there alone is not a regression.
- Commits: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`, with the message ending in `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Never write the user's real name anywhere.
- Work on the branch `feat/security` from `master` (Task 1 creates it).

## Review Focus

1. **Arming without a code through a `command_template`** must send `"code":""`, not `"code":"None"`. The template engine renders null as `None` the way HA does, and the bridge contract (spec Appendix A.3) promises an empty string. Pinned in Task 2 (`arming without a code sends an empty code`).
2. **An alarm panel with no state yet** (cache cold, or a bridge that never published) must render "—" with disabled buttons, never crash or offer live buttons. Pinned in Task 3 (`unknown state offers disabled buttons`).
3. **A sensor whose state hasn't arrived yet** (`StateValue.Unknown`) must sort with the inactive rows and show "—", not count as active or clear. Pinned in Task 5 (`unknown sensors sort with inactive and are not counted`).
4. **A folded duplicate (`partOf != null`) or a Home-hidden entity** must not appear on Security, even if its class qualifies. Pinned in Task 5 (`hidden, security-hidden and folded entities are left out`).
5. **The code must not survive a send:** after OK, reopening the keypad starts empty. Pinned in Task 3 (`the keypad starts empty after a send`).

---

### Task 1: Model — code capabilities, `securityHidden`, `SensorGroup`

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Entity.kt` (`Capabilities`)
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/UiOverride.kt`
- Create: `core/model/src/main/kotlin/com/gekkotron/oikos/model/SensorGroup.kt`
- Test: `core/model/src/test/kotlin/com/gekkotron/oikos/model/UiOverrideTest.kt`, `core/model/src/test/kotlin/com/gekkotron/oikos/model/SensorGroupTest.kt`

**Interfaces:**
- Produces:
  - `enum class CodeFormat { NUMBER, TEXT }`
  - `Capabilities.codeDisarmRequired: Boolean = false`
  - `Capabilities.codeFormat: CodeFormat = CodeFormat.NUMBER`
  - `UiOverride.securityHidden: Boolean = false` (JSON key `securityHidden`, omitted when false)
  - `enum class SensorGroup(val danger: Boolean)` with entries `SMOKE, CO, GAS, LEAK, SOS, OPENING, MOTION, VIBRATION, TAMPER` (in that order), and `SensorGroup.of(deviceClass: String?): SensorGroup?`

- [ ] **Step 1: Create the branch**

```bash
git switch -c feat/security
```

- [ ] **Step 2: Write the failing tests**

Append to `UiOverrideTest`:

```kotlin
    @Test
    fun `securityHidden round-trips and is omitted when false`() {
        val o = UiOverride(securityHidden = true)
        val json = UiOverrides.encode(o)
        assertEquals("""{"securityHidden":true}""", json)
        assertEquals(o, UiOverrides.decode(json))
        assertEquals("{}", UiOverrides.encode(UiOverride(securityHidden = false)))
    }
```

Create `SensorGroupTest.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorGroupTest {
    @Test
    fun `device classes map to their group`() {
        for (c in listOf("door", "window", "garage_door", "opening")) assertEquals(SensorGroup.OPENING, SensorGroup.of(c))
        for (c in listOf("motion", "occupancy", "presence")) assertEquals(SensorGroup.MOTION, SensorGroup.of(c))
        assertEquals(SensorGroup.SMOKE, SensorGroup.of("smoke"))
        assertEquals(SensorGroup.CO, SensorGroup.of("carbon_monoxide"))
        assertEquals(SensorGroup.GAS, SensorGroup.of("gas"))
        assertEquals(SensorGroup.LEAK, SensorGroup.of("moisture"))
        assertEquals(SensorGroup.SOS, SensorGroup.of("safety"))
        assertEquals(SensorGroup.VIBRATION, SensorGroup.of("vibration"))
        assertEquals(SensorGroup.TAMPER, SensorGroup.of("tamper"))
    }

    @Test
    fun `other classes are not security sensors`() {
        assertNull(SensorGroup.of(null))
        assertNull(SensorGroup.of("problem"))
        assertNull(SensorGroup.of("battery"))
    }

    @Test
    fun `danger groups come first`() {
        assertTrue(SensorGroup.entries.takeWhile { it.danger }.size == 5)
        assertFalse(SensorGroup.OPENING.danger)
        assertTrue(SensorGroup.LEAK.danger)
    }
}
```

- [ ] **Step 3: Run the tests to check they fail**

Run: `./gradlew :core:model:test --tests '*UiOverrideTest*' --tests '*SensorGroupTest*'`
Expected: compilation FAILS (`securityHidden`, `SensorGroup` unresolved).

- [ ] **Step 4: Implement**

In `UiOverride.kt`, add after `energyInvert`:

```kotlin
    /** Left out of the Security tab only; Home still shows it (security spec §2). */
    val securityHidden: Boolean = false,
```

In `Entity.kt`, in `Capabilities`, after `codeArmRequired`:

```kotlin
    val codeDisarmRequired: Boolean = false,
    /** Keypad (digits) or text field for the alarm code (security spec §3.1). */
    val codeFormat: CodeFormat = CodeFormat.NUMBER,
```

and below `Capabilities`:

```kotlin
enum class CodeFormat { NUMBER, TEXT }
```

Create `SensorGroup.kt`:

```kotlin
package com.gekkotron.oikos.model

/**
 * What a security-relevant binary sensor watches (security spec §3.2). Danger groups come first, and the
 * enum order is the summary order.
 */
enum class SensorGroup(val danger: Boolean) {
    SMOKE(true), CO(true), GAS(true), LEAK(true), SOS(true),
    OPENING(false), MOTION(false), VIBRATION(false), TAMPER(false),
    ;

    companion object {
        /** Null: not shown on the Security tab. */
        fun of(deviceClass: String?): SensorGroup? = when (deviceClass) {
            "door", "window", "garage_door", "opening" -> OPENING
            "motion", "occupancy", "presence" -> MOTION
            "smoke" -> SMOKE
            "carbon_monoxide" -> CO
            "gas" -> GAS
            "moisture" -> LEAK
            "safety" -> SOS
            "vibration" -> VIBRATION
            "tamper" -> TAMPER
            else -> null
        }
    }
}
```

- [ ] **Step 5: Run the tests to check they pass**

Run: `./gradlew :core:model:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/model
git commit -m "feat(model): alarm disarm-code and code-format capabilities, securityHidden override, security sensor groups"
```

---

### Task 2: Discovery fills the code capabilities; empty code in templates

**Files:**
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt` (the `Kind.ALARM_PANEL -> Capabilities(` branch, around line 199)
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/AlarmPanelMapper.kt` (`encode`, the `publish(...)` line)
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`, `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/AlarmPanelMapperTest.kt`

**Interfaces:**
- Consumes: `Capabilities.codeDisarmRequired`, `Capabilities.codeFormat`, `CodeFormat` (Task 1).
- Produces: alarm entities whose capabilities carry both code flags and the format; `AlarmPanelMapper.encode` renders a missing code as `""`.

- [ ] **Step 1: Write the failing tests**

Append to `DiscoveryParserTest` (it already has `single(topic, payload)`):

```kotlin
    @Test
    fun `alarm code flags and format`() {
        fun caps(json: String) = single("homeassistant/alarm_control_panel/a/config", json).entity.capabilities
        val remote = caps("""{"cmd_t":"c","code":"REMOTE_CODE","code_arm_required":false}""")
        assertEquals(false, remote.codeArmRequired)
        assertEquals(true, remote.codeDisarmRequired)
        assertEquals(CodeFormat.NUMBER, remote.codeFormat)
        assertEquals(CodeFormat.TEXT, caps("""{"cmd_t":"c","code":"REMOTE_CODE_TEXT"}""").codeFormat)
        assertEquals(CodeFormat.NUMBER, caps("""{"cmd_t":"c","code":"0420"}""").codeFormat)
        assertEquals(CodeFormat.TEXT, caps("""{"cmd_t":"c","code":"s3cret"}""").codeFormat)
        val none = caps("""{"cmd_t":"c"}""")
        assertEquals(false, none.codeArmRequired)
        assertEquals(false, none.codeDisarmRequired)
    }
```

Add `import com.gekkotron.oikos.model.CodeFormat` to the test imports.

Append to `AlarmPanelMapperTest` (it has `bindings(...)`, `publishes(...)`, `Unknown`):

```kotlin
    @Test
    fun `arming without a code sends an empty code`() {
        val b = bindings(
            "alarm_control_panel",
            """{"command_topic":"a/set","code":"REMOTE_CODE","code_arm_required":false,"command_template":"{\"action\":\"{{ action }}\",\"code\":\"{{ code }}\"}"}""",
        )
        assertEquals(publishes("""{"action":"ARM_AWAY","code":""}"""), AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.AWAY), Unknown))
    }
```

- [ ] **Step 2: Run the tests to check they fail**

Run: `./gradlew :core:source:test --tests '*DiscoveryParserTest*' :core:state:test --tests '*AlarmPanelMapperTest*'`
Expected: FAIL. `codeDisarmRequired` is false for the remote panel, the formats are all NUMBER, and the mapper renders `"code":"None"`.

- [ ] **Step 3: Implement**

In `DiscoveryParser`, replace the alarm branch:

```kotlin
        Kind.ALARM_PANEL -> Capabilities(
            alarmModes = AlarmPanelMapper.supportedModes(b),
            codeArmRequired = AlarmPanelMapper.codeRequired(b, arming = true),
            codeDisarmRequired = AlarmPanelMapper.codeRequired(b, arming = false),
            codeFormat = codeFormat(b.str("code")),
        )
```

and add, next to the other private helpers in `DiscoveryParser`:

```kotlin
    /** `REMOTE_CODE_TEXT` or a literal code with a non-digit gets a text field; anything else the keypad. */
    private fun codeFormat(code: String?): CodeFormat = when {
        code == null || code == "REMOTE_CODE" -> CodeFormat.NUMBER
        code == "REMOTE_CODE_TEXT" -> CodeFormat.TEXT
        code.all(Char::isDigit) -> CodeFormat.NUMBER
        else -> CodeFormat.TEXT
    }
```

with `import com.gekkotron.oikos.model.CodeFormat`.

In `AlarmPanelMapper.encode`, change the last line so a missing code renders empty (spec Appendix A.3):

```kotlin
        return publish(b, "command_topic", "command_template", action, mapOf("action" to action, "code" to code.orEmpty()))
```

- [ ] **Step 4: Run the tests to check they pass**

Run: `./gradlew :core:state:test :core:source:test`
Expected: PASS. The existing `remote code is required and passed through the command template` test still passes.

- [ ] **Step 5: Commit**

```bash
git add core/source core/state
git commit -m "feat(discovery): alarm disarm-code flag and code format; a missing code renders empty"
```

---

### Task 3: Shared alarm UI in `:core:designsystem`

**Files:**
- Modify: `core/designsystem/build.gradle.kts`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/alarm/AlarmActions.kt`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/alarm/AlarmText.kt`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/alarm/CodeKeypadSheet.kt`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/alarm/AlarmControls.kt`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/alarm/AlarmHero.kt`
- Modify: `core/designsystem/src/main/res/values/strings.xml`, `core/designsystem/src/main/res/values-fr/strings.xml`
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/alarm/AlarmActionsTest.kt`, `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/alarm/AlarmControlsTest.kt`

**Interfaces:**
- Consumes: `AlarmState`, `AlarmMode`, `Capabilities` (with `codeArmRequired`, `codeDisarmRequired`, `codeFormat`), `CodeFormat`, `Command.Arm(mode, code)`, `Command.Disarm(code)` from `:core:model`.
- Produces (all `public`, package `com.gekkotron.oikos.designsystem.alarm`):
  - `data class AlarmActions(val arm: List<AlarmMode>, val current: AlarmMode?, val disarm: Boolean, val enabled: Boolean)`, with `companion fun of(state: AlarmState?, available: Boolean, caps: Capabilities): AlarmActions` and `companion fun needsCode(caps: Capabilities, command: Command): Boolean`
  - `@StringRes fun alarmLabel(state: AlarmState): Int`, `@StringRes fun armLabel(mode: AlarmMode): Int`, `@StringRes fun alarmHeroLabel(state: AlarmState): Int`
  - `@Composable fun CodeKeypadSheet(format: CodeFormat, onSubmit: (String) -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun AlarmControls(state: AlarmState?, available: Boolean, caps: Capabilities, onSend: (Command) -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun AlarmHero(name: String, state: AlarmState?, available: Boolean, pending: Boolean, caps: Capabilities, onSend: (Command) -> Unit, modifier: Modifier = Modifier)`
  - String resources in `com.gekkotron.oikos.designsystem.R`:
    - `alarm_disarmed`, `alarm_armed_home`, `alarm_armed_away`, `alarm_armed_night`, `alarm_armed_vacation`, `alarm_armed_custom`
    - `alarm_pending`, `alarm_arming`, `alarm_disarming`, `alarm_triggered`
    - `alarm_arm_home`, `alarm_arm_away`, `alarm_arm_night`, `alarm_arm_vacation`, `alarm_arm_custom`
    - `alarm_disarm`, `alarm_code`, `alarm_code_ok`, `alarm_code_delete`, `alarm_code_entered`
    - `alarm_exit_delay`, `alarm_entry_delay`, `alarm_unavailable`, `alarm_unknown`

- [ ] **Step 1: Add the model dependency**

In `core/designsystem/build.gradle.kts`, add to `dependencies`:

```kotlin
    // The alarm UI takes AlarmState, Capabilities and Command (security spec §4.1).
    api(project(":core:model"))
```

- [ ] **Step 2: Write the failing `AlarmActions` test**

Create `AlarmActionsTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.alarm

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmActionsTest {
    private val caps = Capabilities(alarmModes = setOf(AlarmMode.NIGHT, AlarmMode.HOME, AlarmMode.AWAY))
    private val modes = listOf(AlarmMode.HOME, AlarmMode.AWAY, AlarmMode.NIGHT)

    @Test
    fun `disarmed offers the supported modes in enum order and no disarm`() {
        assertEquals(AlarmActions(modes, null, disarm = false, enabled = true), AlarmActions.of(AlarmState.DISARMED, true, caps))
    }

    @Test
    fun `armed highlights its mode and offers disarm`() {
        assertEquals(AlarmActions(modes, AlarmMode.AWAY, disarm = true, enabled = true), AlarmActions.of(AlarmState.ARMED_AWAY, true, caps))
    }

    @Test
    fun `delays and triggered offer only disarm`() {
        for (s in listOf(AlarmState.ARMING, AlarmState.PENDING, AlarmState.TRIGGERED)) {
            assertEquals(AlarmActions(emptyList(), null, disarm = true, enabled = true), AlarmActions.of(s, true, caps), "$s")
        }
    }

    @Test
    fun `disarming offers nothing`() {
        assertEquals(AlarmActions(emptyList(), null, disarm = false, enabled = true), AlarmActions.of(AlarmState.DISARMING, true, caps))
    }

    @Test
    fun `unknown state offers disabled buttons`() {
        assertEquals(AlarmActions(modes, null, disarm = true, enabled = false), AlarmActions.of(null, true, caps))
    }

    @Test
    fun `unavailable disables everything but keeps the layout of its state`() {
        assertEquals(AlarmActions(modes, AlarmMode.HOME, disarm = true, enabled = false), AlarmActions.of(AlarmState.ARMED_HOME, false, caps))
    }

    @Test
    fun `code need follows the action`() {
        val c = Capabilities(codeArmRequired = false, codeDisarmRequired = true)
        assertFalse(AlarmActions.needsCode(c, Command.Arm(AlarmMode.AWAY)))
        assertTrue(AlarmActions.needsCode(c, Command.Disarm()))
        assertFalse(AlarmActions.needsCode(c, Command.TurnOff))
    }
}
```

- [ ] **Step 3: Run it to check it fails**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*AlarmActionsTest*'`
Expected: compilation FAILS (`AlarmActions` unresolved).

- [ ] **Step 4: Implement `AlarmActions` and `AlarmText`**

`AlarmActions.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.alarm

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command

/** Which alarm buttons to draw for a state (security spec §4.2). [current] is highlighted, not re-sent. */
data class AlarmActions(val arm: List<AlarmMode>, val current: AlarmMode?, val disarm: Boolean, val enabled: Boolean) {
    companion object {
        fun of(state: AlarmState?, available: Boolean, caps: Capabilities): AlarmActions {
            val modes = AlarmMode.entries.filter { it in caps.alarmModes }
            val enabled = available && state != null
            return when (state) {
                null -> AlarmActions(modes, null, disarm = true, enabled = false)
                AlarmState.DISARMED -> AlarmActions(modes, null, disarm = false, enabled = enabled)
                AlarmState.ARMED_HOME, AlarmState.ARMED_AWAY, AlarmState.ARMED_NIGHT,
                AlarmState.ARMED_VACATION, AlarmState.ARMED_CUSTOM_BYPASS ->
                    AlarmActions(modes, armedMode(state), disarm = true, enabled = enabled)
                AlarmState.ARMING, AlarmState.PENDING, AlarmState.TRIGGERED -> AlarmActions(emptyList(), null, disarm = true, enabled = enabled)
                AlarmState.DISARMING -> AlarmActions(emptyList(), null, disarm = false, enabled = enabled)
            }
        }

        fun needsCode(caps: Capabilities, command: Command): Boolean = when (command) {
            is Command.Arm -> caps.codeArmRequired
            is Command.Disarm -> caps.codeDisarmRequired
            else -> false
        }

        private fun armedMode(state: AlarmState): AlarmMode? = when (state) {
            AlarmState.ARMED_HOME -> AlarmMode.HOME
            AlarmState.ARMED_AWAY -> AlarmMode.AWAY
            AlarmState.ARMED_NIGHT -> AlarmMode.NIGHT
            AlarmState.ARMED_VACATION -> AlarmMode.VACATION
            AlarmState.ARMED_CUSTOM_BYPASS -> AlarmMode.CUSTOM_BYPASS
            else -> null
        }
    }
}
```

`AlarmText.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.alarm

import androidx.annotation.StringRes
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState

/** The short state, for cards ("Armed · Away"). */
@StringRes
fun alarmLabel(state: AlarmState): Int = when (state) {
    AlarmState.DISARMED -> R.string.alarm_disarmed
    AlarmState.ARMED_HOME -> R.string.alarm_armed_home
    AlarmState.ARMED_AWAY -> R.string.alarm_armed_away
    AlarmState.ARMED_NIGHT -> R.string.alarm_armed_night
    AlarmState.ARMED_VACATION -> R.string.alarm_armed_vacation
    AlarmState.ARMED_CUSTOM_BYPASS -> R.string.alarm_armed_custom
    AlarmState.PENDING -> R.string.alarm_pending
    AlarmState.ARMING -> R.string.alarm_arming
    AlarmState.DISARMING -> R.string.alarm_disarming
    AlarmState.TRIGGERED -> R.string.alarm_triggered
}

/** The hero's state line: the delays get their meaning spelled out (security spec §4.2). */
@StringRes
fun alarmHeroLabel(state: AlarmState): Int = when (state) {
    AlarmState.ARMING -> R.string.alarm_exit_delay
    AlarmState.PENDING -> R.string.alarm_entry_delay
    else -> alarmLabel(state)
}

@StringRes
fun armLabel(mode: AlarmMode): Int = when (mode) {
    AlarmMode.HOME -> R.string.alarm_arm_home
    AlarmMode.AWAY -> R.string.alarm_arm_away
    AlarmMode.NIGHT -> R.string.alarm_arm_night
    AlarmMode.VACATION -> R.string.alarm_arm_vacation
    AlarmMode.CUSTOM_BYPASS -> R.string.alarm_arm_custom
}
```

Add to `core/designsystem/src/main/res/values/strings.xml`, before `</resources>`:

```xml
    <string name="alarm_disarmed">Disarmed</string>
    <string name="alarm_armed_home">Armed · Home</string>
    <string name="alarm_armed_away">Armed · Away</string>
    <string name="alarm_armed_night">Armed · Night</string>
    <string name="alarm_armed_vacation">Armed · Vacation</string>
    <string name="alarm_armed_custom">Armed · Custom</string>
    <string name="alarm_pending">Pending</string>
    <string name="alarm_arming">Arming…</string>
    <string name="alarm_disarming">Disarming…</string>
    <string name="alarm_triggered">Triggered!</string>
    <string name="alarm_exit_delay">Exit delay…</string>
    <string name="alarm_entry_delay">Entry delay…</string>
    <string name="alarm_unavailable">Unavailable</string>
    <string name="alarm_unknown">—</string>
    <string name="alarm_arm_home">Home</string>
    <string name="alarm_arm_away">Away</string>
    <string name="alarm_arm_night">Night</string>
    <string name="alarm_arm_vacation">Vacation</string>
    <string name="alarm_arm_custom">Custom</string>
    <string name="alarm_disarm">Disarm</string>
    <string name="alarm_code">Code</string>
    <string name="alarm_code_ok">OK</string>
    <string name="alarm_code_delete">Delete</string>
    <string name="alarm_code_entered">%1$d entered</string>
```

and to `values-fr/strings.xml`:

```xml
    <string name="alarm_disarmed">Désarmée</string>
    <string name="alarm_armed_home">Armée · Maison</string>
    <string name="alarm_armed_away">Armée · Absent</string>
    <string name="alarm_armed_night">Armée · Nuit</string>
    <string name="alarm_armed_vacation">Armée · Vacances</string>
    <string name="alarm_armed_custom">Armée · Perso</string>
    <string name="alarm_pending">En attente</string>
    <string name="alarm_arming">Armement…</string>
    <string name="alarm_disarming">Désarmement…</string>
    <string name="alarm_triggered">Déclenchée !</string>
    <string name="alarm_exit_delay">Délai de sortie…</string>
    <string name="alarm_entry_delay">Délai d\'entrée…</string>
    <string name="alarm_unavailable">Indisponible</string>
    <string name="alarm_unknown">—</string>
    <string name="alarm_arm_home">Maison</string>
    <string name="alarm_arm_away">Absent</string>
    <string name="alarm_arm_night">Nuit</string>
    <string name="alarm_arm_vacation">Vacances</string>
    <string name="alarm_arm_custom">Perso</string>
    <string name="alarm_disarm">Désarmer</string>
    <string name="alarm_code">Code</string>
    <string name="alarm_code_ok">OK</string>
    <string name="alarm_code_delete">Effacer</string>
    <string name="alarm_code_entered">%1$d saisis</string>
```

- [ ] **Step 5: Run the `AlarmActions` test to check it passes**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*AlarmActionsTest*'`
Expected: PASS.

- [ ] **Step 6: Write the failing Compose test**

Create `AlarmControlsTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.alarm

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlarmControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<Command>()
    private val caps = Capabilities(
        alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY, AlarmMode.NIGHT),
        codeArmRequired = false,
        codeDisarmRequired = true,
    )

    private fun hero(state: AlarmState?, available: Boolean = true) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { AlarmHero("Alarm", state, available, pending = false, caps = caps, onSend = { sent += it }) }
    }

    @Test
    fun `arming without a required code sends at once`() {
        hero(AlarmState.DISARMED)
        compose.onNodeWithText("Away").performClick()
        assertEquals(listOf<Command>(Command.Arm(AlarmMode.AWAY)), sent)
    }

    @Test
    fun `disarm asks for the code and sends it`() {
        hero(AlarmState.ARMED_AWAY)
        compose.onNodeWithText("Disarm").performClick()
        compose.onNodeWithText("1").performClick()
        compose.onNodeWithText("2").performClick()
        compose.onNodeWithText("3").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("4").performClick()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf<Command>(Command.Disarm("124")), sent)
    }

    @Test
    fun `the keypad starts empty after a send`() {
        hero(AlarmState.ARMED_AWAY)
        compose.onNodeWithText("Disarm").performClick()
        compose.onNodeWithText("7").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Disarm").performClick()
        compose.onNodeWithTag("keypad-dots").assertTextEquals("")
        compose.onNodeWithText("OK").assertIsNotEnabled()
    }

    @Test
    fun `triggered shows the alarm and only disarm`() {
        hero(AlarmState.TRIGGERED)
        compose.onNodeWithText("Triggered!").assertExists()
        compose.onNodeWithText("Disarm").assertExists()
        compose.onNodeWithText("Away").assertDoesNotExist()
    }

    @Test
    fun `delays are spelled out`() {
        hero(AlarmState.PENDING)
        compose.onNodeWithText("Entry delay…").assertExists()
    }

    @Test
    fun `unknown state offers disabled buttons`() {
        hero(null)
        compose.onNodeWithText("—").assertExists()
        compose.onNodeWithText("Away").assertIsNotEnabled()
        compose.onNodeWithText("Disarm").assertIsNotEnabled()
    }

    @Test
    fun `unavailable shows it and disables the buttons`() {
        hero(AlarmState.DISARMED, available = false)
        compose.onNodeWithText("Unavailable").assertExists()
        compose.onNodeWithText("Home").assertIsNotEnabled()
    }
}
```

- [ ] **Step 7: Run it to check it fails**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*AlarmControlsTest*'`
Expected: compilation FAILS (`AlarmHero` unresolved).

- [ ] **Step 8: Implement `CodeKeypadSheet`**

```kotlin
package com.gekkotron.oikos.designsystem.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.model.CodeFormat

private const val DELETE = "⌫"
private val KEYS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(DELETE, "0"))

/**
 * Asks for the alarm code (security spec §4.2). The code lives in plain `remember`, never saved state, so it
 * is gone once the sheet leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeKeypadSheet(format: CodeFormat, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.alarm_code), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            when (format) {
                CodeFormat.NUMBER -> {
                    val entered = stringResource(R.string.alarm_code_entered, code.length)
                    Text(
                        "•".repeat(code.length),
                        style = OikosTheme.type.display.copy(fontSize = 28.sp),
                        modifier = Modifier.testTag("keypad-dots").heightIn(min = 40.dp).semantics { stateDescription = entered },
                    )
                    for (row in KEYS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (key in row) {
                                val deleteLabel = stringResource(R.string.alarm_code_delete)
                                FilledTonalButton(
                                    onClick = { code = if (key == DELETE) code.dropLast(1) else code + key },
                                    modifier = Modifier.size(72.dp).then(
                                        if (key == DELETE) Modifier.semantics { contentDescription = deleteLabel } else Modifier,
                                    ),
                                ) { Text(key, fontSize = 22.sp) }
                            }
                        }
                    }
                }
                CodeFormat.TEXT -> OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.alarm_code)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("keypad-dots"),
                )
            }
            Button(
                onClick = { val typed = code; code = ""; onSubmit(typed) },
                enabled = code.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.alarm_code_ok)) }
        }
    }
}
```

- [ ] **Step 9: Implement `AlarmControls`**

```kotlin
package com.gekkotron.oikos.designsystem.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command

/** Arm modes and Disarm; an action that needs a code goes through [CodeKeypadSheet] first (security spec §4.2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmControls(state: AlarmState?, available: Boolean, caps: Capabilities, onSend: (Command) -> Unit, modifier: Modifier = Modifier) {
    val actions = AlarmActions.of(state, available, caps)
    var asking by remember { mutableStateOf<Command?>(null) }
    val request: (Command) -> Unit = { if (AlarmActions.needsCode(caps, it)) asking = it else onSend(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.arm.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in actions.arm) {
                    FilterChip(
                        selected = mode == actions.current,
                        onClick = { if (mode != actions.current) request(Command.Arm(mode)) },
                        enabled = actions.enabled,
                        label = { Text(stringResource(armLabel(mode))) },
                        modifier = Modifier.heightIn(min = OikosDimens.minTouch),
                    )
                }
            }
        }
        if (actions.disarm) {
            Button(
                onClick = { request(Command.Disarm()) },
                enabled = actions.enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.alarm_disarm)) }
        }
    }
    asking?.let { command ->
        CodeKeypadSheet(
            caps.codeFormat,
            onSubmit = { code ->
                asking = null
                onSend(
                    when (command) {
                        is Command.Arm -> command.copy(code = code)
                        is Command.Disarm -> command.copy(code = code)
                        else -> command
                    },
                )
            },
            onDismiss = { asking = null },
        )
    }
}
```

- [ ] **Step 10: Implement `AlarmHero`**

```kotlin
package com.gekkotron.oikos.designsystem.alarm

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosColors
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.components.PendingDot
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command

/** The Security tab's alarm card: colour and text by state, then [AlarmControls] (security spec §4.2). */
@Composable
fun AlarmHero(
    name: String,
    state: AlarmState?,
    available: Boolean,
    pending: Boolean,
    caps: Capabilities,
    onSend: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OikosTheme.colors
    val accent = accentFor(state, available, c)
    val triggered = available && state == AlarmState.TRIGGERED
    val pulses = available && (state == AlarmState.ARMING || state == AlarmState.PENDING || state == AlarmState.DISARMING)
    val alpha = if (pulses && animationsEnabled()) {
        val pulse by rememberInfiniteTransition(label = "alarm").animateFloat(
            initialValue = 0.10f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "alarm-pulse",
        )
        pulse
    } else {
        if (triggered) 0.35f else 0.12f
    }
    val stateText = when {
        !available -> stringResource(R.string.alarm_unavailable)
        state == null -> stringResource(R.string.alarm_unknown)
        else -> stringResource(alarmHeroLabel(state))
    }
    OikosCard(modifier.fillMaxWidth().testTag("alarm-hero")) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = alpha), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) {
                    liveRegion = if (triggered) LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StaticIcon(OikosGlyph.SHIELD, accent, Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = OikosTheme.type.label, color = c.contentMuted)
                    Text(stateText, style = OikosTheme.type.display.copy(fontSize = 22.sp))
                }
                if (pending) PendingDot()
            }
            AlarmControls(state, available, caps, onSend)
        }
    }
}

private fun accentFor(state: AlarmState?, available: Boolean, c: OikosColors): Color = when {
    !available || state == null -> c.contentMuted
    state == AlarmState.DISARMED || state == AlarmState.DISARMING -> c.connected
    state == AlarmState.PENDING -> c.warning
    state == AlarmState.TRIGGERED -> c.heat
    else -> c.grid
}

@Composable
private fun animationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return true
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}
```

If `OikosColors` lives in a different package than `com.gekkotron.oikos.designsystem`, adjust the import (it's declared in `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/OikosColors.kt`). If `PendingDot` triggers an infinite animation that blocks `compose.waitForIdle`, the tests don't set `pending = true`, so nothing changes.

- [ ] **Step 11: Run the designsystem tests**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: PASS, the existing `ContrastTest`, `OikosCardTest`, `RoomChipsTest` and `ThemeTest` included.

If `the keypad starts empty after a send` fails because the first sheet is still animating out, add `compose.waitForIdle()` before the second `Disarm` click. Don't make the code survive.

- [ ] **Step 12: Commit**

```bash
git add core/designsystem
git commit -m "feat(designsystem): shared alarm UI - actions by state, code keypad, alarm hero"
```

---

### Task 4: Home uses the shared alarm UI; "Show on Security" in the customise sheet

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt` (line 79 branch; delete the private `AlarmControls`, lines ~225-247)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CardText.kt` (delete `alarmLabel` and `armLabel`; the `CardValue.Alarm` branch uses the designsystem `alarmLabel`)
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraft.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraftTest.kt`, `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheetTest.kt`

**Interfaces:**
- Consumes: `AlarmControls`, `alarmLabel` (Task 3); `SensorGroup.of`, `UiOverride.securityHidden` (Task 1).
- Produces: `OverrideDraft.securityHidden: Boolean` (a new last constructor parameter), and Home string `customize_show_on_security`.

- [ ] **Step 1: Write the failing tests**

Append to `OverrideDraftTest`:

```kotlin
    @Test
    fun `securityHidden is kept and written`() {
        val door = card(testEntity("door", Kind.BINARY_SENSOR, "Door", deviceClass = "door", ui = UiOverride(securityHidden = true)))
        assertTrue(OverrideDraft.from(door).securityHidden)
        assertEquals(UiOverride(securityHidden = true), OverrideDraft.from(door).applyTo(door))
        assertEquals(UiOverride(), OverrideDraft.from(door).copy(securityHidden = false).applyTo(door))
    }
```

Append to `CustomizeSheetTest`:

```kotlin
    @Test
    fun `security sensors and alarms get the Show on Security switch`() {
        show(card(testEntity("mailbox", Kind.BINARY_SENSOR, "Mailbox", deviceClass = "door")))
        compose.onNodeWithText("Show on Security").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(true, saved.single().securityHidden)
    }

    @Test
    fun `other entities have no Show on Security switch`() {
        show(card(testEntity("lamp", Kind.LIGHT, "Lamp")))
        compose.onNodeWithText("Show on Security").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run them to check they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*OverrideDraftTest*' --tests '*CustomizeSheetTest*'`
Expected: compilation FAILS (`securityHidden` not a draft parameter).

- [ ] **Step 3: Implement the draft and sheet**

In `OverrideDraft`:
- Add the last constructor parameter `val securityHidden: Boolean = false,`.
- In `applyTo`, add `securityHidden = securityHidden,` inside `ui.copy(…)`.
- In `from(card)`, add `securityHidden = card.ui.securityHidden,`.

In `CustomizeSheet`:
- Add `var securityHidden by rememberSaveable(card.id) { mutableStateOf(start.securityHidden) }` next to the other states.
- Change the draft line to `val draft = OverrideDraft(name, areaId, favorite, hidden, order, cardStyle, energyRole, energyInvert, securityHidden)`.
- Right after the `SwitchRow(stringResource(R.string.customize_hidden), hidden) { hidden = it }` line, add:

```kotlin
            if (card.kind == Kind.ALARM_PANEL || (card.kind == Kind.BINARY_SENSOR && SensorGroup.of(card.deviceClass) != null)) {
                SwitchRow(stringResource(R.string.customize_show_on_security), !securityHidden) { securityHidden = !it }
            }
```

with `import com.gekkotron.oikos.model.SensorGroup`.

Add the Home strings:
- `values/strings.xml`: `<string name="customize_show_on_security">Show on Security</string>`
- `values-fr/strings.xml`: `<string name="customize_show_on_security">Afficher dans Sécurité</string>`

- [ ] **Step 4: Switch Home to the shared alarm UI**

In `ControlSheet.kt`:
- Replace `Kind.ALARM_PANEL -> AlarmControls(card, send)` with:

```kotlin
                Kind.ALARM_PANEL -> AlarmControls((card.state as? StateValue.Alarm)?.state, card.available, card.capabilities, send)
```

- Delete the private `AlarmControls(card: CardUi, onSend: …)` function.
- Add `import com.gekkotron.oikos.designsystem.alarm.AlarmControls`.
- Remove the imports that become unused: `AlarmMode`, `PasswordVisualTransformation`, and `KeyboardType` / `KeyboardOptions` if nothing else in the file uses them. Check with `grep -n "KeyboardOptions\|KeyboardType\|PasswordVisualTransformation\|AlarmMode" ControlSheet.kt`.

In `CardText.kt`:
- Delete `internal fun alarmLabel(...)` and `internal fun armLabel(...)`, and the `AlarmMode` / `AlarmState` imports.
- Add `import com.gekkotron.oikos.designsystem.alarm.alarmLabel`. The line `is CardValue.Alarm -> stringResource(alarmLabel(value.state))` then compiles unchanged.

In both Home `strings.xml` files, delete these keys, since they now live in the designsystem:
- `alarm_disarmed`, `alarm_armed_home`, `alarm_armed_away`, `alarm_armed_night`, `alarm_armed_vacation`, `alarm_armed_custom`
- `alarm_pending`, `alarm_arming`, `alarm_disarming`, `alarm_triggered`
- `sheet_code`, `sheet_arm_home`, `sheet_arm_away`, `sheet_arm_night`, `sheet_arm_vacation`, `sheet_arm_custom`, `sheet_disarm`

Keep `entity_label_alarm`.

Then check that nothing still references them:

Run: `grep -rn "R.string.alarm_\|R.string.sheet_arm\|R.string.sheet_disarm\|R.string.sheet_code\|armLabel" feature/home/src`
Expected: no output.

- [ ] **Step 5: Run the Home tests**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS (all existing Home tests plus the three new ones).

- [ ] **Step 6: Commit**

```bash
git add feature/home
git commit -m "feat(home): alarm sheet uses the shared alarm controls; Show on Security switch"
```

---

### Task 5: `:feature:security` module and `SecurityUiBuilder`

**Files:**
- Modify: `settings.gradle.kts` (add `include(":feature:security")` after `include(":feature:energy")`)
- Create: `feature/security/build.gradle.kts`
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityModels.kt`
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilder.kt`
- Create: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/TestEntities.kt`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilderTest.kt`

**Interfaces:**
- Consumes: `SensorGroup` (Task 1), `EntitySource` types (`Entity`, `EntityState`, `Area`, `LinkState`).
- Produces (package `com.gekkotron.oikos.feature.security.presentation`):
  - `data class PanelUi(val id: String, val name: String, val state: AlarmState?, val available: Boolean, val pending: Boolean, val stale: Boolean, val capabilities: Capabilities)`
  - `data class SensorRow(val id: String, val name: String, val areaName: String?, val deviceClass: String?, val group: SensorGroup, val active: Boolean?, val available: Boolean)`
  - `data class GroupCount(val group: SensorGroup, val count: Int)`
  - `data class SecurityUi(val link: LinkState = LinkState.Connecting, val panels: List<PanelUi> = emptyList(), val summary: List<GroupCount> = emptyList(), val sensors: List<SensorRow> = emptyList(), val loading: Boolean = true)`
  - `object SecurityUiBuilder { fun build(entities: Map<String, Entity>, states: Map<String, EntityState>, areas: List<Area>, link: LinkState): SecurityUi }`

- [ ] **Step 1: Create the module**

`settings.gradle.kts`: add `include(":feature:security")` after `include(":feature:energy")`.

`feature/security/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.security"
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
```

`TestEntities.kt`:

```kotlin
package com.gekkotron.oikos.feature.security

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride

object NoBindings : Bindings

fun sensor(id: String, deviceClass: String?, name: String = id, area: String = UNASSIGNED_AREA, ui: UiOverride = UiOverride(), partOf: String? = null) =
    Entity(id, null, name, Kind.BINARY_SENSOR, deviceClass, null, null, Capabilities(), NoBindings, area, ui, partOf)

fun panel(id: String = "alarm", name: String = "Alarm", order: Int? = null, ui: UiOverride = UiOverride(order = order)) = Entity(
    id, null, name, Kind.ALARM_PANEL, null, null, null,
    Capabilities(alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY), codeDisarmRequired = true), NoBindings, UNASSIGNED_AREA, ui,
)

fun on(id: String, on: Boolean, available: Boolean = true) = EntityState(id, StateValue.OnOff(on), available = available)

fun alarm(id: String, state: AlarmState, stale: Boolean = false) = EntityState(id, StateValue.Alarm(state), stale = stale)
```

- [ ] **Step 2: Write the failing builder test**

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityUiBuilderTest {
    private val areas = listOf(Area("garage", "Garage"), Area("living", "Living room"))

    private fun build(entities: List<Entity>, states: List<EntityState> = emptyList()) =
        SecurityUiBuilder.build(entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, areas, LinkState.Connecting)

    @Test
    fun `only security classes are listed`() {
        val ui = build(listOf(sensor("door", "door"), sensor("fault", null), sensor("problem", "problem"), sensor("smoke", "smoke")))
        assertEquals(setOf("door", "smoke"), ui.sensors.map { it.id }.toSet())
        assertFalse(ui.loading)
    }

    @Test
    fun `hidden, security-hidden and folded entities are left out`() {
        val ui = build(
            listOf(
                sensor("a", "door", ui = UiOverride(hidden = true)),
                sensor("b", "door", ui = UiOverride(securityHidden = true)),
                sensor("c", "door", partOf = "cover"),
                panel("p", ui = UiOverride(securityHidden = true)),
                sensor("d", "door"),
            ),
        )
        assertEquals(listOf("d"), ui.sensors.map { it.id })
        assertTrue(ui.panels.isEmpty())
    }

    @Test
    fun `active danger, active intrusion, inactive, unavailable; then area and name`() {
        val ui = build(
            listOf(
                sensor("door_living", "door", "Door", area = "living"),
                sensor("door_garage", "door", "Door", area = "garage"),
                sensor("motion", "motion", "Motion", area = "living"),
                sensor("smoke", "smoke", "Smoke", area = "living"),
                sensor("leak", "moisture", "Leak", area = "garage"),
                sensor("window", "window", "Window"),
            ),
            listOf(
                on("door_living", true), on("door_garage", true), on("motion", false), on("smoke", true),
                on("leak", false, available = false), on("window", false),
            ),
        )
        assertEquals(listOf("smoke", "door_garage", "door_living", "motion", "window", "leak"), ui.sensors.map { it.id })
        assertEquals("Garage", ui.sensors[1].areaName)
        assertNull(ui.sensors.single { it.id == "window" }.areaName)
    }

    @Test
    fun `summary counts active available sensors by group in enum order`() {
        val ui = build(
            listOf(sensor("d1", "door"), sensor("d2", "window"), sensor("m", "occupancy"), sensor("s", "smoke"), sensor("x", "door")),
            listOf(on("d1", true), on("d2", true), on("m", true), on("s", true), on("x", true, available = false)),
        )
        assertEquals(listOf(GroupCount(SensorGroup.SMOKE, 1), GroupCount(SensorGroup.OPENING, 2), GroupCount(SensorGroup.MOTION, 1)), ui.summary)
    }

    @Test
    fun `unknown sensors sort with inactive and are not counted`() {
        val ui = build(
            listOf(sensor("a", "door", "A"), sensor("b", "door", "B"), sensor("c", "door", "C")),
            listOf(on("b", true), EntityState("c", StateValue.Unknown)),
        )
        assertEquals(listOf("b", "a", "c"), ui.sensors.map { it.id })
        assertNull(ui.sensors.single { it.id == "c" }.active)
        assertNull(ui.sensors.single { it.id == "a" }.active)
        assertEquals(listOf(GroupCount(SensorGroup.OPENING, 1)), ui.summary)
    }

    @Test
    fun `panels carry state, capabilities and user order`() {
        val ui = build(
            listOf(panel("b", "B", order = 1), panel("a", "A")),
            listOf(alarm("b", AlarmState.ARMED_AWAY, stale = true)),
        )
        assertEquals(listOf("b", "a"), ui.panels.map { it.id })
        assertEquals(AlarmState.ARMED_AWAY, ui.panels[0].state)
        assertTrue(ui.panels[0].stale)
        assertNull(ui.panels[1].state)
        assertTrue(ui.panels[1].capabilities.codeDisarmRequired)
    }

    @Test
    fun `nothing yields empty lists`() {
        val ui = build(listOf(sensor("lamp", null).copy(kind = Kind.LIGHT)))
        assertTrue(ui.panels.isEmpty() && ui.sensors.isEmpty() && ui.summary.isEmpty())
    }
}
```

- [ ] **Step 3: Run it to check it fails**

Run: `./gradlew :feature:security:testDebugUnitTest --tests '*SecurityUiBuilderTest*'`
Expected: compilation FAILS (`SecurityUiBuilder` unresolved).

- [ ] **Step 4: Implement**

`SecurityModels.kt`:

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.transport.LinkState

/** [state] null: nothing received yet. */
data class PanelUi(
    val id: String,
    val name: String,
    val state: AlarmState?,
    val available: Boolean,
    val pending: Boolean,
    val stale: Boolean,
    val capabilities: Capabilities,
)

/** [active] null: no state yet. [areaName] null: Unassigned (localized by the UI). */
data class SensorRow(
    val id: String,
    val name: String,
    val areaName: String?,
    val deviceClass: String?,
    val group: SensorGroup,
    val active: Boolean?,
    val available: Boolean,
)

data class GroupCount(val group: SensorGroup, val count: Int)

/** [loading] is true only before the first build, so the empty state never flashes. */
data class SecurityUi(
    val link: LinkState = LinkState.Connecting,
    val panels: List<PanelUi> = emptyList(),
    val summary: List<GroupCount> = emptyList(),
    val sensors: List<SensorRow> = emptyList(),
    val loading: Boolean = true,
) {
    fun panel(id: String): PanelUi? = panels.firstOrNull { it.id == id }
}

sealed interface SecurityEvent {
    data class NotConfirmed(val id: String, val command: Command) : SecurityEvent
    data class Rejected(val reason: String) : SecurityEvent
    data object SaveFailed : SecurityEvent
    data object WaitingForConnection : SecurityEvent
}
```

`SecurityUiBuilder.kt`:

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.SensorGroup
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState

/** Entities → the Security tab (security spec §4.3). Pure. */
object SecurityUiBuilder {
    fun build(entities: Map<String, Entity>, states: Map<String, EntityState>, areas: List<Area>, link: LinkState): SecurityUi {
        val areaNames = areas.associate { it.id to it.name }
        val visible = entities.values.filter { !it.ui.hidden && !it.ui.securityHidden && it.partOf == null }
        val panels = visible.filter { it.kind == Kind.ALARM_PANEL }
            .sortedWith(compareBy<Entity>({ it.ui.order ?: Int.MAX_VALUE }, { it.name.lowercase() }, { it.uniqueId }))
            .map { e ->
                val s = states[e.uniqueId]
                PanelUi(
                    e.uniqueId, e.name, (s?.value as? StateValue.Alarm)?.state,
                    available = s?.available ?: true, pending = s?.pending == true, stale = s?.stale == true, capabilities = e.capabilities,
                )
            }
        val sensors = visible.filter { it.kind == Kind.BINARY_SENSOR }
            .mapNotNull { e ->
                val group = SensorGroup.of(e.deviceClass) ?: return@mapNotNull null
                val s = states[e.uniqueId]
                SensorRow(
                    e.uniqueId, e.name,
                    areaName = if (e.area == UNASSIGNED_AREA) null else areaNames[e.area] ?: e.area,
                    deviceClass = e.deviceClass, group = group,
                    active = (s?.value as? StateValue.OnOff)?.on,
                    available = s?.available ?: true,
                )
            }
            .sortedWith(compareBy<SensorRow>({ rank(it) }, { it.areaName == null }, { it.areaName?.lowercase() }, { it.name.lowercase() }, { it.id }))
        val counts = sensors.filter { it.available && it.active == true }.groupingBy { it.group }.eachCount()
        val summary = SensorGroup.entries.mapNotNull { g -> counts[g]?.let { GroupCount(g, it) } }
        return SecurityUi(link, panels, summary, sensors, loading = false)
    }

    /** 0 active danger, 1 active intrusion, 2 inactive or unknown, 3 unavailable. */
    private fun rank(row: SensorRow): Int = when {
        !row.available -> 3
        row.active == true && row.group.danger -> 0
        row.active == true -> 1
        else -> 2
    }
}
```

- [ ] **Step 5: Run the tests to check they pass**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts feature/security
git commit -m "feat(security): module and UI builder - panels, security sensors sorted active first, summary"
```

---

### Task 6: `SecurityViewModel`

**Files:**
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityViewModel.kt`
- Create: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/FakeEntitySource.kt`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityViewModelTest.kt`

**Interfaces:**
- Consumes: `SecurityUiBuilder`, `SecurityUi`, `SecurityEvent` (Task 5); `EntitySource`.
- Produces: `class SecurityViewModel` (Hilt), with `val state: StateFlow<SecurityUi>`, `val events: Flow<SecurityEvent>`, `fun send(id: String, command: Command)` and `fun setSecurityHidden(id: String, hidden: Boolean)`. There's an `internal constructor(source: EntitySource, dispatcher: CoroutineDispatcher)` for tests.

- [ ] **Step 1: Write the fake and the failing test**

`FakeEntitySource.kt`:

```kotlin
package com.gekkotron.oikos.feature.security

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
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val commandFailures = MutableSharedFlow<CommandFailure>()
    val stateMap = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val sent = mutableListOf<Pair<String, Command>>()
    var result: CommandResult = CommandResult.Accepted
    val overrides = mutableMapOf<String, UiOverride>()
    var failWrites = false

    override fun states() = stateMap
    override suspend fun send(uniqueId: String, command: Command): CommandResult {
        sent += uniqueId to command
        return result
    }
    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        if (failWrites) throw PublishFailed("offline")
        overrides[uniqueId] = override
    }
    override suspend fun setAreas(areas: List<Area>) = error("not used")
}
```

`SecurityViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.feature.security.FakeEntitySource
import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        entities.value = listOf(panel("alarm"), sensor("door", "door", ui = UiOverride(favorite = true))).associateBy { it.uniqueId }
        stateMap.value = listOf(alarm("alarm", AlarmState.DISARMED), on("door", true)).associateBy { it.uniqueId }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state reflects the source`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(AlarmState.DISARMED, vm.state.value.panels.single().state)
        assertEquals(listOf("door"), vm.state.value.sensors.map { it.id })
    }

    @Test
    fun `send forwards the command and a rejection becomes an event`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        source.result = CommandResult.Rejected("wrong code")
        vm.send("alarm", Command.Disarm("1"))
        assertEquals(listOf("alarm" to Command.Disarm("1")), source.sent)
        assertEquals(SecurityEvent.Rejected("wrong code"), vm.events.first())
    }

    @Test
    fun `a stale panel waits for the connection`() = runTest(dispatcher) {
        source.stateMap.value += "alarm" to alarm("alarm", AlarmState.DISARMED, stale = true)
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.send("alarm", Command.Arm(AlarmMode.AWAY))
        assertTrue(source.sent.isEmpty())
        assertEquals(SecurityEvent.WaitingForConnection, vm.events.first())
    }

    @Test
    fun `only failures of commands sent here are reported`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        source.commandFailures.emit(CommandFailure("lamp", Command.TurnOff, "timeout"))
        vm.send("alarm", Command.Arm(AlarmMode.AWAY))
        source.commandFailures.emit(CommandFailure("alarm", Command.Arm(AlarmMode.AWAY), "timeout"))
        assertEquals(SecurityEvent.NotConfirmed("alarm", Command.Arm(AlarmMode.AWAY)), vm.events.first())
    }

    @Test
    fun `hiding keeps the rest of the override; a failed write is an event`() = runTest(dispatcher) {
        val vm = SecurityViewModel(source, dispatcher)
        vm.setSecurityHidden("door", true)
        assertEquals(UiOverride(favorite = true, securityHidden = true), source.overrides["door"])
        source.failWrites = true
        vm.setSecurityHidden("door", false)
        assertEquals(SecurityEvent.SaveFailed, vm.events.first())
    }
}
```

- [ ] **Step 2: Run it to check it fails**

Run: `./gradlew :feature:security:testDebugUnitTest --tests '*SecurityViewModelTest*'`
Expected: compilation FAILS (`SecurityViewModel` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel internal constructor(
    private val source: EntitySource,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(source: EntitySource) : this(source, Dispatchers.Default)

    // Like EnergyViewModel: one rebuild per collection turn, built off Main.
    val state: StateFlow<SecurityUi> = combine(source.entities, source.states(), source.areas, source.link, SecurityUiBuilder::build)
        .conflate()
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUi())

    private val _events = Channel<SecurityEvent>(Channel.BUFFERED)
    val events: Flow<SecurityEvent> = _events.receiveAsFlow()

    /** Home's view model reports its own failures; this one only those of commands sent from Security. */
    private val sentHere = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            source.commandFailures.collect { if (it.uniqueId in sentHere) _events.send(SecurityEvent.NotConfirmed(it.uniqueId, it.command)) }
        }
    }

    fun send(id: String, command: Command) {
        viewModelScope.launch {
            // A cached panel has no live link yet: sending would be dropped, so say so (like Home).
            if (state.value.panel(id)?.stale == true) return@launch _events.send(SecurityEvent.WaitingForConnection)
            sentHere += id
            val result = source.send(id, command)
            if (result is CommandResult.Rejected) _events.send(SecurityEvent.Rejected(result.reason))
        }
    }

    fun setSecurityHidden(id: String, hidden: Boolean) {
        val entity = source.entities.value[id] ?: return
        viewModelScope.launch {
            try {
                source.setOverride(id, entity.ui.copy(securityHidden = hidden))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SecurityEvent.SaveFailed)
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to check they pass**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/security
git commit -m "feat(security): view model - state, commands with their own failures, hide from Security"
```

---

### Task 7: `SecurityScreen`, sensor sheet, strings

**Files:**
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreen.kt`
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SensorRowUi.kt`
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SensorSheet.kt`
- Create: `feature/security/src/main/res/values/strings.xml`, `feature/security/src/main/res/values-fr/strings.xml`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreenTest.kt`

**Interfaces:**
- Consumes: `SecurityUi`, `SensorRow`, `GroupCount`, `SecurityEvent`, `SecurityViewModel` (Tasks 5–6); `AlarmHero` (Task 3); `LinkPill`, `OikosCard`, `DoorIcon`, `WindowIcon`, `GarageIcon`, `StaticIcon`, `OikosGlyph` (designsystem).
- Produces:
  - `@Composable fun SecurityRoute(windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets, viewModel: SecurityViewModel = hiltViewModel())`
  - `@Composable fun SecurityScreen(state: SecurityUi, windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets, onSend: (String, Command) -> Unit = { _, _ -> }, onSetHidden: (String, Boolean) -> Unit = { _, _ -> }, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() })`

- [ ] **Step 1: Add the strings**

`feature/security/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="security_title">Security</string>
    <string name="security_all_clear">All clear</string>
    <string name="security_no_panel">No alarm panel. Publish an MQTT alarm_control_panel to see it here.</string>
    <string name="security_no_sensors">No security sensors.</string>
    <string name="security_empty">No alarm panel or security sensor yet. Publish an MQTT alarm_control_panel, or pair door, motion, smoke or leak sensors.</string>
    <string name="security_alerts">Recent alerts</string>
    <string name="security_alerts_empty">Alerts will appear here once push notifications are set up.</string>
    <string name="security_show">Show on Security</string>
    <string name="security_hide_hint">To bring it back later, use Customize on Home.</string>
    <string name="security_unassigned">Unassigned</string>
    <string name="security_unavailable">Unavailable</string>
    <string name="security_unknown">—</string>
    <string name="security_open">Open</string>
    <string name="security_closed">Closed</string>
    <string name="security_motion">Motion</string>
    <string name="security_clear">Clear</string>
    <string name="security_detected">Detected</string>
    <string name="security_leak">Leak</string>
    <string name="security_dry">Dry</string>
    <string name="security_sos">SOS</string>
    <string name="security_ok">OK</string>
    <string name="security_vibration">Vibration</string>
    <string name="security_still">Still</string>
    <string name="security_tampered">Tampered</string>
    <string name="security_not_confirmed">Not confirmed</string>
    <string name="security_retry">Retry</string>
    <string name="security_rejected">Refused: %1$s</string>
    <string name="security_save_failed">Could not save. Check the connection.</string>
    <string name="security_waiting">Waiting for the connection…</string>
    <plurals name="security_count_opening"><item quantity="one">%1$d open</item><item quantity="other">%1$d open</item></plurals>
    <plurals name="security_count_motion"><item quantity="one">%1$d motion</item><item quantity="other">%1$d motion</item></plurals>
    <plurals name="security_count_smoke"><item quantity="one">%1$d smoke</item><item quantity="other">%1$d smoke</item></plurals>
    <plurals name="security_count_co"><item quantity="one">%1$d CO</item><item quantity="other">%1$d CO</item></plurals>
    <plurals name="security_count_gas"><item quantity="one">%1$d gas</item><item quantity="other">%1$d gas</item></plurals>
    <plurals name="security_count_leak"><item quantity="one">%1$d leak</item><item quantity="other">%1$d leaks</item></plurals>
    <plurals name="security_count_sos"><item quantity="one">%1$d SOS</item><item quantity="other">%1$d SOS</item></plurals>
    <plurals name="security_count_vibration"><item quantity="one">%1$d vibration</item><item quantity="other">%1$d vibrations</item></plurals>
    <plurals name="security_count_tamper"><item quantity="one">%1$d tamper</item><item quantity="other">%1$d tampers</item></plurals>
</resources>
```

`values-fr/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="security_title">Sécurité</string>
    <string name="security_all_clear">Tout est calme</string>
    <string name="security_no_panel">Aucune centrale d\'alarme. Publiez un alarm_control_panel MQTT pour la voir ici.</string>
    <string name="security_no_sensors">Aucun capteur de sécurité.</string>
    <string name="security_empty">Ni centrale d\'alarme ni capteur de sécurité pour l\'instant. Publiez un alarm_control_panel MQTT, ou appairez des capteurs de porte, de mouvement, de fumée ou de fuite.</string>
    <string name="security_alerts">Alertes récentes</string>
    <string name="security_alerts_empty">Les alertes apparaîtront ici une fois les notifications push configurées.</string>
    <string name="security_show">Afficher dans Sécurité</string>
    <string name="security_hide_hint">Pour le réafficher, passez par Personnaliser sur l\'Accueil.</string>
    <string name="security_unassigned">Sans pièce</string>
    <string name="security_unavailable">Indisponible</string>
    <string name="security_unknown">—</string>
    <string name="security_open">Ouvert</string>
    <string name="security_closed">Fermé</string>
    <string name="security_motion">Mouvement</string>
    <string name="security_clear">RAS</string>
    <string name="security_detected">Détecté</string>
    <string name="security_leak">Fuite</string>
    <string name="security_dry">Sec</string>
    <string name="security_sos">SOS</string>
    <string name="security_ok">OK</string>
    <string name="security_vibration">Vibration</string>
    <string name="security_still">Immobile</string>
    <string name="security_tampered">Sabotage</string>
    <string name="security_not_confirmed">Non confirmé</string>
    <string name="security_retry">Réessayer</string>
    <string name="security_rejected">Refusé : %1$s</string>
    <string name="security_save_failed">Enregistrement impossible. Vérifiez la connexion.</string>
    <string name="security_waiting">En attente de la connexion…</string>
    <plurals name="security_count_opening"><item quantity="one">%1$d ouvert</item><item quantity="other">%1$d ouverts</item></plurals>
    <plurals name="security_count_motion"><item quantity="one">%1$d mouvement</item><item quantity="other">%1$d mouvements</item></plurals>
    <plurals name="security_count_smoke"><item quantity="one">%1$d fumée</item><item quantity="other">%1$d fumées</item></plurals>
    <plurals name="security_count_co"><item quantity="one">%1$d CO</item><item quantity="other">%1$d CO</item></plurals>
    <plurals name="security_count_gas"><item quantity="one">%1$d gaz</item><item quantity="other">%1$d gaz</item></plurals>
    <plurals name="security_count_leak"><item quantity="one">%1$d fuite</item><item quantity="other">%1$d fuites</item></plurals>
    <plurals name="security_count_sos"><item quantity="one">%1$d SOS</item><item quantity="other">%1$d SOS</item></plurals>
    <plurals name="security_count_vibration"><item quantity="one">%1$d vibration</item><item quantity="other">%1$d vibrations</item></plurals>
    <plurals name="security_count_tamper"><item quantity="one">%1$d sabotage</item><item quantity="other">%1$d sabotages</item></plurals>
</resources>
```

- [ ] **Step 2: Write the failing screen test**

```kotlin
package com.gekkotron.oikos.feature.security.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.security.alarm
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.panel
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.feature.security.presentation.SecurityUi
import com.gekkotron.oikos.feature.security.presentation.SecurityUiBuilder
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class SecurityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val hidden = mutableListOf<Pair<String, Boolean>>()

    private fun ui(entities: List<Entity>, states: List<EntityState> = emptyList()): SecurityUi = SecurityUiBuilder.build(
        entities.associateBy { it.uniqueId }, states.associateBy { it.uniqueId }, listOf(Area("garage", "Garage")), LinkState.Connecting,
    )

    private fun show(state: SecurityUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { SecurityScreen(state, onSetHidden = { id, h -> hidden += id to h }) }
    }

    @Test
    fun `panel, summary and sensors`() {
        show(
            ui(
                listOf(panel(), sensor("garage_door", "garage_door", "Garage door", area = "garage"), sensor("motion", "motion", "Corridor"), sensor("door", "door", "Entrance")),
                listOf(alarm("alarm", AlarmState.ARMED_AWAY), on("garage_door", true), on("motion", true), on("door", false)),
            ),
        )
        compose.onNodeWithText("Armed · Away").assertExists()
        compose.onNodeWithText("1 open · 1 motion").assertExists()
        compose.onNodeWithText("Garage door").assertExists()
        compose.onNodeWithText("Closed").performScrollTo().assertExists()
        compose.onNodeWithText("Recent alerts").performScrollTo().assertExists()
    }

    @Test
    fun `all clear and the no-panel card`() {
        show(ui(listOf(sensor("door", "door", "Entrance")), listOf(on("door", false))))
        compose.onNodeWithText("All clear").assertExists()
        compose.onNodeWithText("No alarm panel. Publish an MQTT alarm_control_panel to see it here.").assertExists()
    }

    @Test
    fun `nothing at all shows one empty state`() {
        show(ui(emptyList()))
        compose.onNodeWithText("No alarm panel or security sensor yet. Publish an MQTT alarm_control_panel, or pair door, motion, smoke or leak sensors.").assertExists()
        compose.onNodeWithText("All clear").assertDoesNotExist()
    }

    @Test
    fun `nothing is drawn while loading`() {
        show(SecurityUi())
        compose.onNodeWithText("All clear").assertDoesNotExist()
        compose.onNodeWithText("Recent alerts").assertDoesNotExist()
    }

    @Test
    fun `tapping a row lets it leave Security`() {
        show(ui(listOf(sensor("mailbox", "door", "Mailbox")), listOf(on("mailbox", false))))
        compose.onNodeWithText("Mailbox").performClick()
        compose.onNodeWithText("Show on Security").performClick()
        assertEquals(listOf("mailbox" to true), hidden)
    }

    @Test
    fun `unavailable and unknown sensors say so`() {
        show(ui(listOf(sensor("a", "smoke", "Kitchen smoke"), sensor("b", "moisture", "Pool")), listOf(on("a", false, available = false))))
        compose.onNodeWithText("Unavailable").assertExists()
        compose.onNodeWithText("—").assertExists()
    }
}
```

- [ ] **Step 3: Run it to check it fails**

Run: `./gradlew :feature:security:testDebugUnitTest --tests '*SecurityScreenTest*'`
Expected: compilation FAILS (`SecurityScreen` unresolved).

- [ ] **Step 4: Implement `SensorRowUi.kt` (row, icon, words)**

```kotlin
package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.icons.DoorIcon
import com.gekkotron.oikos.designsystem.icons.GarageIcon
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.designsystem.icons.WindowIcon
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.GroupCount
import com.gekkotron.oikos.feature.security.presentation.SensorRow
import com.gekkotron.oikos.model.SensorGroup

@Composable
internal fun SensorRowCard(row: SensorRow, onClick: () -> Unit) {
    val accent = activeColor(row)
    OikosCard(
        Modifier.fillMaxWidth().testTag("sensor-${row.id}").alpha(if (row.available) 1f else 0.55f),
        accent = row.available && row.active == true,
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SensorIcon(row, accent)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = OikosTheme.type.body)
                Text(row.areaName ?: stringResource(R.string.security_unassigned), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
            }
            Text(stateWord(row), style = OikosTheme.type.body, color = accent ?: OikosTheme.colors.content)
        }
    }
}

/** Rose for danger, amber for intrusion; null when calm, unknown or unavailable (security spec §4.3). */
@Composable
private fun activeColor(row: SensorRow): Color? {
    if (!row.available || row.active != true) return null
    return if (row.group.danger) OikosTheme.colors.heat else OikosTheme.colors.warning
}

@Composable
private fun SensorIcon(row: SensorRow, accent: Color?) {
    val open = if (row.active == true) 1f else 0f
    val m = Modifier.size(28.dp)
    when (row.deviceClass) {
        "window" -> WindowIcon(open, accent, m)
        "garage_door" -> GarageIcon(open, accent, m)
        "door", "opening" -> DoorIcon(open, accent, m)
        else -> StaticIcon(glyph(row.group), accent, m)
    }
}

private fun glyph(group: SensorGroup): OikosGlyph = when (group) {
    SensorGroup.MOTION -> OikosGlyph.WALKER
    SensorGroup.SMOKE, SensorGroup.CO, SensorGroup.GAS -> OikosGlyph.FLAME
    SensorGroup.LEAK -> OikosGlyph.DROP
    SensorGroup.SOS -> OikosGlyph.SHIELD
    SensorGroup.OPENING, SensorGroup.VIBRATION, SensorGroup.TAMPER -> OikosGlyph.DOT
}

@Composable
internal fun stateWord(row: SensorRow): String {
    if (!row.available) return stringResource(R.string.security_unavailable)
    val active = row.active ?: return stringResource(R.string.security_unknown)
    return stringResource(
        when (row.group) {
            SensorGroup.OPENING -> if (active) R.string.security_open else R.string.security_closed
            SensorGroup.MOTION -> if (active) R.string.security_motion else R.string.security_clear
            SensorGroup.SMOKE, SensorGroup.CO, SensorGroup.GAS -> if (active) R.string.security_detected else R.string.security_clear
            SensorGroup.LEAK -> if (active) R.string.security_leak else R.string.security_dry
            SensorGroup.SOS -> if (active) R.string.security_sos else R.string.security_ok
            SensorGroup.VIBRATION -> if (active) R.string.security_vibration else R.string.security_still
            SensorGroup.TAMPER -> if (active) R.string.security_tampered else R.string.security_ok
        },
    )
}

/** "2 open · 1 motion", or "All clear". */
@Composable
internal fun summaryText(summary: List<GroupCount>): String {
    if (summary.isEmpty()) return stringResource(R.string.security_all_clear)
    return summary.map { (group, n) ->
        pluralStringResource(
            when (group) {
                SensorGroup.OPENING -> R.plurals.security_count_opening
                SensorGroup.MOTION -> R.plurals.security_count_motion
                SensorGroup.SMOKE -> R.plurals.security_count_smoke
                SensorGroup.CO -> R.plurals.security_count_co
                SensorGroup.GAS -> R.plurals.security_count_gas
                SensorGroup.LEAK -> R.plurals.security_count_leak
                SensorGroup.SOS -> R.plurals.security_count_sos
                SensorGroup.VIBRATION -> R.plurals.security_count_vibration
                SensorGroup.TAMPER -> R.plurals.security_count_tamper
            },
            n, n,
        )
    }.joinToString(" · ")
}
```

`OikosCard`'s `accent` draws a warm border. If the existing parameter only takes the amber tone, that's acceptable for danger rows too, since the state word carries the rose colour.

- [ ] **Step 5: Implement `SensorSheet.kt`**

```kotlin
package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.SensorRow

/** Name, room and state, and the one setting Security owns: whether the sensor is listed here (security spec §4.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SensorSheet(row: SensorRow, onHide: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(
                listOf(row.areaName ?: stringResource(R.string.security_unassigned), stateWord(row)).joinToString(" · "),
                style = OikosTheme.type.body,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = OikosDimens.minTouch)
                    .toggleable(value = true, role = Role.Switch, onValueChange = { if (!it) onHide() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.security_show), Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = null)
            }
            Text(stringResource(R.string.security_hide_hint), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
    }
}
```

- [ ] **Step 6: Implement `SecurityScreen.kt`**

```kotlin
package com.gekkotron.oikos.feature.security.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.alarm.AlarmHero
import com.gekkotron.oikos.designsystem.components.LinkPill
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.SecurityEvent
import com.gekkotron.oikos.feature.security.presentation.SecurityUi
import com.gekkotron.oikos.feature.security.presentation.SecurityViewModel
import com.gekkotron.oikos.model.Command

@Composable
fun SecurityRoute(
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            when (event) {
                is SecurityEvent.NotConfirmed -> {
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.security_not_confirmed),
                        actionLabel = resources.getString(R.string.security_retry),
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.send(event.id, event.command)
                }
                is SecurityEvent.Rejected -> snackbar.showSnackbar(resources.getString(R.string.security_rejected, event.reason))
                SecurityEvent.SaveFailed -> snackbar.showSnackbar(resources.getString(R.string.security_save_failed))
                SecurityEvent.WaitingForConnection -> snackbar.showSnackbar(resources.getString(R.string.security_waiting))
            }
        }
    }
    SecurityScreen(state, windowInsets, onSend = viewModel::send, onSetHidden = viewModel::setSecurityHidden, snackbarHostState = snackbar)
}

@Composable
fun SecurityScreen(
    state: SecurityUi,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onSend: (String, Command) -> Unit = { _, _ -> },
    onSetHidden: (String, Boolean) -> Unit = { _, _ -> },
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val layoutDirection = LocalLayoutDirection.current
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = windowInsets,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
        ) {
            item(key = "header") {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.security_title), style = OikosTheme.type.display)
                    Spacer(Modifier.height(8.dp))
                    LinkPill(state.link, onClick = null)
                }
            }
            when {
                state.loading -> Unit
                state.panels.isEmpty() && state.sensors.isEmpty() -> item(key = "empty") {
                    Text(stringResource(R.string.security_empty), style = OikosTheme.type.body, modifier = Modifier.padding(vertical = 24.dp))
                }
                else -> {
                    if (state.panels.isEmpty()) {
                        item(key = "no-panel") {
                            OikosCard(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.security_no_panel), style = OikosTheme.type.body, modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                    items(state.panels, key = { "panel-${it.id}" }) { p ->
                        AlarmHero(p.name, p.state, p.available, p.pending, p.capabilities, onSend = { onSend(p.id, it) })
                    }
                    item(key = "summary") {
                        Text(
                            summaryText(state.summary),
                            style = OikosTheme.type.body,
                            color = OikosTheme.colors.contentMuted,
                            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (state.sensors.isEmpty()) {
                        item(key = "no-sensors") { Text(stringResource(R.string.security_no_sensors), style = OikosTheme.type.body) }
                    }
                    items(state.sensors, key = { "sensor-${it.id}" }) { row -> SensorRowCard(row, onClick = { sheetId = row.id }) }
                    item(key = "alerts") {
                        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.security_alerts), style = OikosTheme.type.label)
                            Text(stringResource(R.string.security_alerts_empty), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
                        }
                    }
                }
            }
        }
    }
    // A sensor that left Security (hidden here or elsewhere) closes its sheet.
    val sheetRow = sheetId?.let { id -> state.sensors.firstOrNull { it.id == id } }
    if (sheetRow != null) {
        SensorSheet(sheetRow, onHide = { onSetHidden(sheetRow.id, true); sheetId = null }, onDismiss = { sheetId = null })
    }
}
```

If `OikosDimens.gridGap` doesn't exist under that name, use the gap `EnergyScreen` uses (it imports `OikosDimens.gridGap`, so it should).

- [ ] **Step 7: Run the tests to check they pass**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add feature/security
git commit -m "feat(security): screen - alarm hero, summary, sensor rows and sheet, alerts placeholder"
```

---

### Task 8: App — Security tab

**Files:**
- Modify: `app/build.gradle.kts` (add `implementation(project(":feature:security"))` after `:feature:energy`)
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/MainTabs.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fr/strings.xml`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/navigation/MainTabsTest.kt`

**Interfaces:**
- Consumes: `SecurityRoute(windowInsets)` (Task 7).
- Produces: `SecurityKey : NavKey`, plus a `selectTab` / `showsTabBar` that cover three tabs.

- [ ] **Step 1: Write the failing tests**

Append to `MainTabsTest`:

```kotlin
    @Test
    fun `side tabs replace each other on top of home`() {
        val stack = mutableListOf<NavKey>(HomeKey)
        selectTab(stack, EnergyKey)
        selectTab(stack, SecurityKey)
        assertEquals(listOf<NavKey>(HomeKey, SecurityKey), stack)
        selectTab(stack, SecurityKey)
        assertEquals(listOf<NavKey>(HomeKey, SecurityKey), stack)
        selectTab(stack, HomeKey)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun `tapping security selects it`() {
        val picked = mutableListOf<NavKey>()
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = { picked += it }) } }
        compose.onNodeWithText("Security").performClick()
        assertEquals(listOf<NavKey>(SecurityKey), picked)
    }
```

and in `tab bar only on the main tabs`, add `assertTrue(showsTabBar(SecurityKey))`.

- [ ] **Step 2: Run them to check they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*MainTabsTest*'`
Expected: compilation FAILS (`SecurityKey` unresolved).

- [ ] **Step 3: Implement**

`app/build.gradle.kts`: `implementation(project(":feature:security"))`.

`OikosNavHost.kt`: next to `EnergyKey`, add

```kotlin
@Serializable
data object SecurityKey : NavKey
```

and in `entryProvider`, after the `EnergyKey` entry:

```kotlin
                entry<SecurityKey> { SecurityRoute(windowInsets = tabInsets) }
```

with `import com.gekkotron.oikos.feature.security.ui.SecurityRoute`.

`MainTabs.kt`: replace `selectTab` and `showsTabBar`:

```kotlin
/** Energy and Security sit on top of Home and replace each other, so back always returns to Home (security spec §5). */
private val SIDE_TABS: Set<NavKey> = setOf(EnergyKey, SecurityKey)

internal fun selectTab(stack: MutableList<NavKey>, key: NavKey) {
    if (key == HomeKey) {
        stack.removeAll { it in SIDE_TABS }
        return
    }
    if (stack.lastOrNull() == key) return
    stack.removeAll { it in SIDE_TABS }
    stack.add(key)
}

internal fun showsTabBar(top: NavKey?): Boolean = top == HomeKey || (top != null && top in SIDE_TABS)
```

and in `MainTabBar`, after the Energy item:

```kotlin
        NavigationBarItem(
            selected = selected == SecurityKey,
            onClick = { onSelect(SecurityKey) },
            icon = { StaticIcon(OikosGlyph.SHIELD, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_security)) },
            colors = itemColors,
        )
```

Strings:
- `values/strings.xml`: `<string name="nav_security">Security</string>`
- `values-fr/strings.xml`: `<string name="nav_security">Sécurité</string>`

- [ ] **Step 4: Run the app tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (apart from the known-flaky `DirectConnectionTesterTest`).

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat(app): Security tab - stacks on Home and replaces Energy"
```

---

### Task 9: Demo house — remote-code alarm with delays, intrusion button, sensor classes

**Files:**
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`
- Test: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoHouseTest.kt`

**Interfaces:**
- Consumes: the existing `config`, `doc`, `echo`, `obj`, `binarySensor` and `tick` in `DemoHouse`.
- Produces:
  - Demo alarm on `demo/alarm`, with `code: REMOTE_CODE`, disarm code `1234`, arm with no code.
  - `ARM_*` → `arming`, and the next `tick()` → the armed state.
  - Button `demo_alarm_intrusion` on `demo/alarm/intrude`: only while armed, it publishes `pending`, then the next `tick()` publishes `triggered`.
  - Sensors `demo_bedroom_window` (window, open), `demo_kitchen_smoke` (smoke), `demo_garage_leak` (moisture).

- [ ] **Step 1: Write the failing tests**

Append to `DemoHouseTest` (add `import kotlinx.serialization.json.contentOrNull` and `import kotlin.test.assertNull`):

```kotlin
    private fun alarmState(house: DemoHouse): String? = house.retainedMessages()
        .single { it.topic == "demo/alarm" }
        .let { Json.parseToJsonElement(it.payload).jsonObject["state"]?.jsonPrimitive?.contentOrNull }

    @Test
    fun `the alarm takes a remote code for disarming only`() {
        val config = DemoHouse().retainedMessages().single { it.topic == "homeassistant/alarm_control_panel/oikos_demo/demo_alarm/config" }.payload
        assertTrue(""""code":"REMOTE_CODE"""" in config, config)
        assertTrue(""""code_arm_required":false""" in config, config)
        assertTrue(""""code_disarm_required":true""" in config, config)
    }

    @Test
    fun `arming goes through the exit delay`() {
        val house = DemoHouse()
        assertNotNull(house.onCommand("demo/alarm/set", """{"action":"ARM_AWAY","code":""}"""))
        assertEquals("arming", alarmState(house))
        assertTrue(house.tick().any { it.topic == "demo/alarm" })
        assertEquals("armed_away", alarmState(house))
    }

    @Test
    fun `disarm needs the right code, a wrong one republishes the state`() {
        val house = DemoHouse()
        house.onCommand("demo/alarm/set", """{"action":"ARM_HOME","code":""}""")
        house.tick()
        val wrong = assertNotNull(house.onCommand("demo/alarm/set", """{"action":"DISARM","code":"0000"}"""))
        assertEquals("armed_home", Json.parseToJsonElement(wrong.payload).jsonObject.getValue("state").jsonPrimitive.contentOrNull)
        house.onCommand("demo/alarm/set", """{"action":"DISARM","code":"1234"}""")
        assertEquals("disarmed", alarmState(house))
    }

    @Test
    fun `an intrusion only trips an armed alarm`() {
        val house = DemoHouse()
        assertNull(house.onCommand("demo/alarm/intrude", "PRESS"))
        assertEquals("disarmed", alarmState(house))
        house.onCommand("demo/alarm/set", """{"action":"ARM_NIGHT","code":""}""")
        house.tick()
        house.onCommand("demo/alarm/intrude", "PRESS")
        assertEquals("pending", alarmState(house))
        house.tick()
        assertEquals("triggered", alarmState(house))
    }

    @Test
    fun `the security tab has one sensor per main class`() {
        val configs = DemoHouse().retainedMessages().filter { it.topic.startsWith("homeassistant/binary_sensor/") }.map { it.payload }
        for (c in listOf("door", "window", "motion", "smoke", "moisture")) {
            assertTrue(configs.any { """"device_class":"$c"""" in it }, c)
        }
    }
```

- [ ] **Step 2: Run them to check they fail**

Run: `./gradlew :core:demo:test --tests '*DemoHouseTest*'`
Expected: FAIL (no `REMOTE_CODE`, no `arming`, no intrusion topic, missing classes).

- [ ] **Step 3: Implement**

In `DemoHouse.init`, after the `demo_hallway_motion` line:

```kotlin
        binarySensor("demo_bedroom_window", "Bedroom window", "Bedroom", "demo/bedroom/window", "window", "contact", onValue = false, initial = false)
        binarySensor("demo_kitchen_smoke", "Kitchen smoke", "Kitchen", "demo/kitchen/smoke", "smoke", "smoke", onValue = true, initial = false)
        binarySensor("demo_garage_leak", "Garage leak", "Garage", "demo/garage/leak", "moisture", "water_leak", onValue = true, initial = false)
```

and change the alarm line to `alarm("demo_alarm", "Alarm", "Hallway", ALARM)`.

Replace `private fun alarm(...)` with:

```kotlin
    /** A remote-code panel like the spec's bridge (security spec Appendix A); delays advance on [tick]. */
    private fun alarm(id: String, device: String, area: String, topic: String) {
        config("alarm_control_panel", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.state }}")
            put("command_topic", "$topic/set")
            put("code", "REMOTE_CODE")
            put("code_arm_required", false)
            put("code_disarm_required", true)
            put("command_template", """{"action":"{{ action }}","code":"{{ code }}"}""")
            putJsonArray("supported_features") { listOf("arm_home", "arm_away", "arm_night").forEach { add(it) } }
        }
        doc(topic, obj("state" to "disarmed"))
        echo("$topic/set", topic) { p -> alarmCommand(topic, p) }
        config("button", "demo_alarm_intrusion", device, "Simulate intrusion", area) { put("command_topic", "$topic/intrude") }
        echo("$topic/intrude", topic) { if (docs.getValue(topic).text("state").startsWith("armed_")) obj("state" to "pending") else null }
    }

    /** Arming goes through `arming`; disarming needs [DEMO_CODE], and a wrong code republishes the current state. */
    private fun alarmCommand(topic: String, payload: String): JsonObject? {
        val command = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val action = command.text("action")
        return when {
            action == "DISARM" -> if (command.text("code") == DEMO_CODE) obj("state" to "disarmed") else obj("state" to docs.getValue(topic).text("state"))
            action in ARMED -> obj("state" to "arming", "target" to ARMED.getValue(action))
            else -> null
        }
    }
```

In `tick()`, before `return`, advance the alarm and include it in the returned list:

```kotlin
        val alarm = docs.getValue(ALARM)
        val next = when (alarm.text("state")) {
            "arming" -> alarm.text("target")
            "pending" -> "triggered"
            else -> null
        }
        val alarmMessages = if (next != null) {
            docs[ALARM] = JsonObject(alarm + ("state" to JsonPrimitive(next)))
            listOf(DemoMessage(ALARM, docs.getValue(ALARM).toString()))
        } else {
            emptyList()
        }
```

and change the final line to:

```kotlin
        return listOf(DemoMessage(ENERGY, docs.getValue(ENERGY).toString()), DemoMessage(OUTDOOR, docs.getValue(OUTDOOR).toString())) + alarmMessages
```

In the `companion object`, add:

```kotlin
        const val ALARM = "demo/alarm"
        const val DEMO_CODE = "1234"
        val ARMED = mapOf("ARM_HOME" to "armed_home", "ARM_AWAY" to "armed_away", "ARM_NIGHT" to "armed_night")

        fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
```

with `import kotlinx.serialization.json.contentOrNull`.

Check: `binarySensor` publishes `payload_on`/`payload_off` as JSON booleans and the state doc field as a boolean. The window uses `contact` with `onValue = false` (contact false = open) and `initial = false`, so it starts **open**. That's the "one starting active" sensor the spec asks for.

- [ ] **Step 4: Run the demo tests, then the broker tests**

Run: `./gradlew :core:demo:test`
Expected: PASS, including the existing `DemoBrokerTest`. If a `DemoBrokerTest` counts the retained messages or the `tick()` output exactly, update its expected number to include the new configs and docs. Don't remove the new demo entities.

- [ ] **Step 5: Commit**

```bash
git add core/demo
git commit -m "feat(demo): remote-code alarm with exit and entry delays, intrusion button, window, smoke and leak sensors"
```

---

### Task 10: Full check, spec touch-up, device run

**Files:**
- Modify: `docs/superpowers/specs/2026-10-02-oikos-security-design.md` (only if the implementation deviated)

- [ ] **Step 1: Run the whole build**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (apart from the known-flaky `DirectConnectionTesterTest`, which you re-run once if it fails). Fix any lint or detekt findings in the new code.

- [ ] **Step 2: Check for leftovers**

Run: `grep -rn "jeedom" app core feature --include=*.kt`
Expected: no output (nothing house-specific).

Run: `grep -rn "rememberSaveable" core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/alarm`
Expected: no output (codes are never saved).

- [ ] **Step 3: Install and try it against the demo**

Run: `./gradlew :app:installDebug` (Pixel 6a over USB, serial `28281JEGR02735`).

Then, in demo mode, check:
1. The Security tab appears, with the "Alarm" hero (Disarmed, mint).
2. Away → the hero shows violet "Exit delay…", then "Armed · Away" a few seconds later.
3. On Home, press "Simulate intrusion" → back on Security the hero shows amber "Entry delay…", then rose "Triggered!" with only Disarm.
4. Disarm → the keypad appears; type 0000 → "Not confirmed" doesn't appear, and the state stays triggered (the demo re-publishes). Type 1234 → Disarmed.
5. The bedroom window is open and listed first among the intrusion rows. The summary reads "1 open".
6. Tap a row and turn off "Show on Security" → it disappears. Bring it back with Home → Customize → "Show on Security".
7. Back from Security returns to Home.

- [ ] **Step 4: Update the spec if anything deviated, and commit**

```bash
git add -A docs
git commit -m "docs(security): spec follows the implementation"
```

(Skip the commit if nothing changed.)
