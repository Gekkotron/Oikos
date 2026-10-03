# Security Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user opt any switch or button onto the Security tab as an "action" (e.g. ring the siren), with a per-action "Ask before turning on" confirmation.

**Architecture:** Two new `UiOverride` flags (`securityAction`, `securityAsk`) and one `Placement` rule make an opted-in switch/button "on Security"; the existing `onHome`/`elsewhere` rules then take it off Home unless pinned. `SecurityUiBuilder` builds `ActionRow`s; `SecurityScreen` shows an Actions section of tiles with a confirm dialog and a long-press `ActionSheet`. Home's `CustomizeSheet` gets the same two toggles.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlinx.serialization, Hilt, JUnit + Robolectric Compose tests, Gradle 9.

**Spec:** `docs/superpowers/specs/2026-10-03-oikos-security-actions-design.md`

## Global Constraints

- Git identity on every commit: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`; end every message with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `UiOverride()` must still encode to `{}` (defaults are not encoded); `securityAsk` defaults to `true`, `securityAction` to `false`.
- Only `Kind.SWITCH` and `Kind.BUTTON` can be actions; `securityAction` on any other kind does nothing.
- Turning a switch **off** never asks. Turning on / pressing asks only when `securityAsk`.
- Every new user-facing string has an English (`values/strings.xml`) and French (`values-fr/strings.xml`) entry.
- Match the surrounding code: KDoc one-liners citing the spec section, no new libraries.

## Review Focus

1. **The entity changes while the confirm dialog is open** (it leaves Security, goes unavailable, or vanishes): confirming must not send; the dialog closes. → Task 3, test `the confirm dialog closes when its action goes unavailable`.
2. **Tap on a stale (cached, not yet live) action**: nothing is sent, "Waiting for the connection…" shows. → Task 2, test `a stale action waits for the connection`.
3. **An unavailable siren**: tapping does nothing, but long-press still opens the sheet so it can be removed. → Task 3, test `an unavailable action is not tappable but its sheet opens`.
4. **`securityAction` on a light or sensor** (hand-edited retained JSON): it must not appear on Security nor leave Home. → Task 1, test `securityAction only applies to switches and buttons`.
5. **Turning "Show on Security" off from Home's customise sheet** puts the device back on Home and hides "Ask before turning on" and "Keep on Home". → Task 4, test `an opted-in switch offers Keep on Home and Ask before; turning Show on Security off hides both`.

---

### Task 1: Model — override flags and placement

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/UiOverride.kt` (after `homePinned`)
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Placement.kt`
- Test: `core/model/src/test/kotlin/com/gekkotron/oikos/model/PlacementTest.kt`, `core/model/src/test/kotlin/com/gekkotron/oikos/model/UiOverrideTest.kt`

**Interfaces:**
- Produces: `UiOverride.securityAction: Boolean = false`, `UiOverride.securityAsk: Boolean = true`, `Placement.actionKind(kind: Kind): Boolean`, `Placement.onSecurity(kind, deviceClass, ui)` now also true for opted-in actions.

- [ ] **Step 1: Write the failing tests**

Append to `PlacementTest`:

```kotlin
    @Test
    fun `switches and buttons can be actions`() {
        assertTrue(Placement.actionKind(Kind.SWITCH))
        assertTrue(Placement.actionKind(Kind.BUTTON))
        assertFalse(Placement.actionKind(Kind.LIGHT))
        assertFalse(Placement.actionKind(Kind.BINARY_SENSOR))
    }

    @Test
    fun `an opted-in action is on Security and leaves Home unless pinned`() {
        assertFalse(Placement.onSecurity(Kind.SWITCH, null, UiOverride()))
        assertTrue(Placement.onSecurity(Kind.SWITCH, null, UiOverride(securityAction = true)))
        assertTrue(Placement.onSecurity(Kind.BUTTON, null, UiOverride(securityAction = true)))
        assertTrue(Placement.onHome(Kind.SWITCH, null, UiOverride()))
        assertFalse(Placement.onHome(Kind.SWITCH, null, UiOverride(securityAction = true)))
        assertTrue(Placement.onHome(Kind.SWITCH, null, UiOverride(securityAction = true, homePinned = true)))
    }

    @Test
    fun `securityAction only applies to switches and buttons`() {
        assertFalse(Placement.onSecurity(Kind.LIGHT, null, UiOverride(securityAction = true)))
        assertTrue(Placement.onHome(Kind.LIGHT, null, UiOverride(securityAction = true)))
        // A sensor's own rule is unchanged: securityHidden still wins over a stray securityAction.
        assertFalse(Placement.onSecurity(Kind.BINARY_SENSOR, "door", UiOverride(securityHidden = true, securityAction = true)))
    }
```

Append to `UiOverrideTest`:

```kotlin
    @Test
    fun `action flags round-trip and only non-defaults are encoded`() {
        val o = UiOverride(securityAction = true, securityAsk = false)
        val json = UiOverrides.encode(o)
        assertEquals(o, UiOverrides.decode(json))
        assertEquals("""{"securityAction":true,"securityAsk":false}""", json)
        assertEquals(true, UiOverrides.decode("{}").securityAsk)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:model:test`
Expected: compilation FAIL (`securityAction`, `actionKind` unresolved).

- [ ] **Step 3: Implement**

In `UiOverride`, after `homePinned`:

```kotlin
    /** A SWITCH or BUTTON shown in Security's Actions section; opt-in (security actions spec §2). */
    val securityAction: Boolean = false,
    /** Ask before turning this action on or pressing it; only read when [securityAction]. */
    val securityAsk: Boolean = true,
```

In `Placement`, add `actionKind` and change `onSecurity`:

```kotlin
    /** A kind the user can opt into Security as an action (security actions spec §2). */
    fun actionKind(kind: Kind): Boolean = kind == Kind.SWITCH || kind == Kind.BUTTON

    fun onSecurity(kind: Kind, deviceClass: String?, ui: UiOverride): Boolean =
        (!ui.securityHidden && securityKind(kind, deviceClass)) || (ui.securityAction && actionKind(kind))
```

Update `securityKind`'s KDoc to: `/** Shown on Security by default: alarm panels and binary sensors of a security class. */`

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:model:test`
Expected: PASS (all, including existing `UiOverrideTest` `default override encodes to empty object`).

- [ ] **Step 5: Commit**

```bash
git add core/model
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(model): switches and buttons can be opted onto Security as actions

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Security presentation — action rows, tap rule, view model

**Files:**
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityModels.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilder.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityViewModel.kt`
- Modify (test fixture): `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/TestEntities.kt`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/presentation/SecurityUiBuilderTest.kt`, `.../presentation/SecurityViewModelTest.kt`, new `.../presentation/ActionTapTest.kt`

**Interfaces:**
- Consumes: Task 1's `UiOverride.securityAction/securityAsk`, `Placement.actionKind`, `Placement.onSecurity`.
- Produces:
  - `data class ActionRow(id: String, name: String, areaName: String?, kind: Kind, on: Boolean?, available: Boolean, stale: Boolean, ask: Boolean)`
  - `data class ActionTap(val command: Command, val confirm: Boolean)` and `fun ActionRow.tap(): ActionTap`
  - `SecurityUi.actions: List<ActionRow>`, `SecurityUi.action(id: String): ActionRow?`, `SecurityUi.isEmpty: Boolean` (no panels, sensors or actions)
  - `SecurityViewModel.setAction(id: String, show: Boolean, ask: Boolean)`
  - Test fixtures `action(id, kind = Kind.SWITCH, name = id, area = UNASSIGNED_AREA, ui = UiOverride(securityAction = true))`

- [ ] **Step 1: Add the test fixture**

Append to `TestEntities.kt`:

```kotlin
/** A switch (or button) opted onto Security as an action. */
fun action(id: String, kind: Kind = Kind.SWITCH, name: String = id, area: String = UNASSIGNED_AREA, ui: UiOverride = UiOverride(securityAction = true)) =
    Entity(id, null, name, kind, null, null, null, Capabilities(), NoBindings, area, ui)
```

- [ ] **Step 2: Write the failing tests**

New `ActionTapTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.security.presentation

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionTapTest {
    private fun row(kind: Kind, on: Boolean?, ask: Boolean) = ActionRow("x", "X", null, kind, on, available = true, stale = false, ask = ask)

    @Test
    fun `turning a switch off never asks`() {
        assertEquals(ActionTap(Command.TurnOff, confirm = false), row(Kind.SWITCH, on = true, ask = true).tap())
    }

    @Test
    fun `turning a switch on asks only when set`() {
        assertEquals(ActionTap(Command.TurnOn(), confirm = true), row(Kind.SWITCH, on = false, ask = true).tap())
        assertEquals(ActionTap(Command.TurnOn(), confirm = false), row(Kind.SWITCH, on = false, ask = false).tap())
        // No state yet: treat as off, so the user can still ring it.
        assertEquals(ActionTap(Command.TurnOn(), confirm = true), row(Kind.SWITCH, on = null, ask = true).tap())
    }

    @Test
    fun `pressing a button asks only when set`() {
        assertEquals(ActionTap(Command.Press, confirm = true), row(Kind.BUTTON, on = null, ask = true).tap())
        assertEquals(ActionTap(Command.Press, confirm = false), row(Kind.BUTTON, on = null, ask = false).tap())
    }
}
```

Append to `SecurityUiBuilderTest` (add imports `com.gekkotron.oikos.feature.security.action`):

```kotlin
    @Test
    fun `opted-in switches and buttons are actions, sorted by area then name`() {
        val ui = build(
            listOf(
                action("siren", name = "Siren", area = "living"),
                action("flood", name = "Floodlight", area = "garage"),
                action("panic", Kind.BUTTON, name = "Panic"),
                action("coffee", name = "Coffee", ui = UiOverride()),
                action("hidden_sw", name = "Hidden", ui = UiOverride(securityAction = true, hidden = true)),
            ),
            listOf(on("siren", true), on("flood", false, available = false)),
        )
        assertEquals(listOf("flood", "siren", "hidden_sw", "panic"), ui.actions.map { it.id })
        val siren = ui.action("siren")!!
        assertEquals(ActionRow("siren", "Siren", "Living room", Kind.SWITCH, on = true, available = true, stale = false, ask = true), siren)
        assertNull(ui.action("panic")!!.on)
        assertFalse(ui.action("flood")!!.available)
        assertTrue(ui.sensors.isEmpty())
    }

    @Test
    fun `ask follows the override and an action alone is not empty`() {
        val ui = build(listOf(action("panic", Kind.BUTTON, ui = UiOverride(securityAction = true, securityAsk = false))))
        assertFalse(ui.action("panic")!!.ask)
        assertFalse(ui.isEmpty)
        assertTrue(build(emptyList()).isEmpty)
    }
```

Note on the sort: Unassigned sorts last, as for sensors (`{ it.areaName == null }` first key): Garage < Living room < (Unassigned: Hidden < Panic).

Append to `SecurityViewModelTest` (add import `com.gekkotron.oikos.feature.security.action`, `com.gekkotron.oikos.model.Kind`):

```kotlin
    @Test
    fun `a stale action waits for the connection`() = runTest(dispatcher) {
        source.entities.value += "siren" to action("siren")
        source.stateMap.value += "siren" to on("siren", false, stale = true)
        val vm = SecurityViewModel(source, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.send("siren", Command.TurnOn())
        assertTrue(source.sent.isEmpty())
        assertEquals(listOf<SecurityEvent>(SecurityEvent.WaitingForConnection), events)
    }

    @Test
    fun `setAction saves both flags, keeps the rest, and a failed write is an event`() = runTest(dispatcher) {
        source.entities.value += "siren" to action("siren", ui = UiOverride(favorite = true, securityAction = true))
        val vm = SecurityViewModel(source, dispatcher)
        val events = mutableListOf<SecurityEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.setAction("siren", show = true, ask = false)
        assertEquals(UiOverride(favorite = true, securityAction = true, securityAsk = false), source.overrides["siren"])
        source.failWrites = true
        vm.setAction("siren", show = false, ask = false)
        assertEquals(listOf<SecurityEvent>(SecurityEvent.SaveFailed), events)
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: compilation FAIL (`ActionRow`, `tap`, `actions`, `setAction` unresolved).

- [ ] **Step 4: Implement the models**

In `SecurityModels.kt`, add imports `com.gekkotron.oikos.model.Kind`, and after `SectionUi`:

```kotlin
/** A switch or button opted onto Security (security actions spec §3.1). [on] null: a button, or no state yet. [areaName] null: Unassigned. */
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

/** What a tap on an action does, and whether it asks first (security actions spec §3.3). */
data class ActionTap(val command: Command, val confirm: Boolean)

fun ActionRow.tap(): ActionTap = when {
    kind == Kind.SWITCH && on == true -> ActionTap(Command.TurnOff, confirm = false)
    kind == Kind.SWITCH -> ActionTap(Command.TurnOn(), confirm = ask)
    else -> ActionTap(Command.Press, confirm = ask)
}
```

Change `SecurityUi` to:

```kotlin
/** [loading] is true only before the first build, so the empty state never flashes. */
data class SecurityUi(
    val link: LinkState = LinkState.Connecting,
    val panels: List<PanelUi> = emptyList(),
    val summary: List<GroupCount> = emptyList(),
    val sensors: List<SensorRow> = emptyList(),
    val sections: List<SectionUi> = emptyList(),
    val actions: List<ActionRow> = emptyList(),
    val loading: Boolean = true,
) {
    /** Nothing at all to show: one empty state instead of the sections. */
    val isEmpty: Boolean get() = panels.isEmpty() && sensors.isEmpty() && actions.isEmpty()

    fun panel(id: String): PanelUi? = panels.firstOrNull { it.id == id }

    fun action(id: String): ActionRow? = actions.firstOrNull { it.id == id }
}
```

- [ ] **Step 5: Implement the builder**

In `SecurityUiBuilder.build`, after the `sensors` val and before `counts`, add (import `com.gekkotron.oikos.model.Placement` is already there):

```kotlin
        val actions = visible.filter { Placement.actionKind(it.kind) }
            .map { e ->
                val s = states[e.uniqueId]
                ActionRow(
                    e.uniqueId, e.name,
                    areaName = if (e.area == UNASSIGNED_AREA) null else areaNames[e.area] ?: e.area,
                    kind = e.kind,
                    on = (s?.value as? StateValue.OnOff)?.on,
                    available = s?.available ?: true,
                    stale = s?.stale == true,
                    ask = e.ui.securityAsk,
                )
            }
            .sortedWith(compareBy<ActionRow>({ it.areaName == null }, { it.areaName?.lowercase() }, { it.name.lowercase() }, { it.id }))
```

and change the return to `return SecurityUi(link, panels, summary, sensors, sections, actions, loading = false)`.

- [ ] **Step 6: Implement the view model**

In `SecurityViewModel.send`, replace the stale check with:

```kotlin
            // A cached panel or action has no live link yet: sending would be dropped, so say so (like Home).
            val ui = state.value
            if (ui.panel(id)?.stale == true || ui.action(id)?.stale == true) return@launch _events.send(SecurityEvent.WaitingForConnection)
```

Add after `setSecurityHidden`, and make both share one save helper:

```kotlin
    fun setSecurityHidden(id: String, hidden: Boolean) = save(id) { it.copy(securityHidden = hidden) }

    /** Security actions spec §3.4. */
    fun setAction(id: String, show: Boolean, ask: Boolean) = save(id) { it.copy(securityAction = show, securityAsk = ask) }

    private fun save(id: String, change: (UiOverride) -> UiOverride) {
        val entity = source.entities.value[id] ?: return
        viewModelScope.launch {
            try {
                source.setOverride(id, change(entity.ui))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SecurityEvent.SaveFailed)
            }
        }
    }
```

(import `com.gekkotron.oikos.model.UiOverride`; delete the old `setSecurityHidden` body.)

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: PASS. `SecurityScreen.kt` still compiles (it doesn't use the new fields yet).

- [ ] **Step 8: Commit**

```bash
git add feature/security
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(security): action rows, tap rule and setAction

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Security UI — Actions section, confirm dialog, action sheet

**Files:**
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/ActionTile.kt`
- Create: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/ActionSheet.kt`
- Modify: `feature/security/src/main/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreen.kt`
- Modify: `feature/security/src/main/res/values/strings.xml`, `feature/security/src/main/res/values-fr/strings.xml`
- Test: `feature/security/src/test/kotlin/com/gekkotron/oikos/feature/security/ui/SecurityScreenTest.kt`

**Interfaces:**
- Consumes: Task 2's `ActionRow`, `ActionRow.tap()`, `SecurityUi.actions/action(id)/isEmpty`, `SecurityViewModel.setAction`.
- Produces: `SecurityScreen(state, windowInsets, onSend, onSetHidden, onSetAction: (String, Boolean, Boolean) -> Unit = { _, _, _ -> }, snackbarHostState)`.

- [ ] **Step 1: Add strings**

`values/strings.xml`, before `</resources>`:

```xml
    <string name="security_section_actions">Actions</string>
    <string name="security_action_on">On</string>
    <string name="security_action_off">Off</string>
    <string name="security_action_confirm_on">Turn on %1$s?</string>
    <string name="security_action_confirm_press">Press %1$s?</string>
    <string name="security_action_turn_on">Turn on</string>
    <string name="security_action_press">Press</string>
    <string name="security_action_ask">Ask before turning on</string>
    <string name="security_action_settings">Settings</string>
    <string name="security_cancel">Cancel</string>
```

`values-fr/strings.xml`, before `</resources>`:

```xml
    <string name="security_section_actions">Actions</string>
    <string name="security_action_on">Allumé</string>
    <string name="security_action_off">Éteint</string>
    <string name="security_action_confirm_on">Allumer %1$s ?</string>
    <string name="security_action_confirm_press">Déclencher %1$s ?</string>
    <string name="security_action_turn_on">Allumer</string>
    <string name="security_action_press">Déclencher</string>
    <string name="security_action_ask">Demander avant d\'allumer</string>
    <string name="security_action_settings">Réglages</string>
    <string name="security_cancel">Annuler</string>
```

- [ ] **Step 2: Write the failing tests**

In `SecurityScreenTest`, add imports `com.gekkotron.oikos.feature.security.action`, `com.gekkotron.oikos.model.Command`, `com.gekkotron.oikos.model.Kind`, `com.gekkotron.oikos.model.UiOverride`, `androidx.compose.ui.test.performTouchInput`, `androidx.compose.ui.test.longClick`, `androidx.compose.ui.test.onNodeWithTag`; add fields and change `show`:

```kotlin
    private val sent = mutableListOf<Pair<String, Command>>()
    private val actionSettings = mutableListOf<Triple<String, Boolean, Boolean>>()

    private fun show(state: SecurityUi) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SecurityScreen(
                state,
                onSend = { id, c -> sent += id to c },
                onSetHidden = { id, h -> hidden += id to h },
                onSetAction = { id, s, a -> actionSettings += Triple(id, s, a) },
            )
        }
    }
```

To test the dialog closing when the state changes, add a stateful variant:

```kotlin
    private fun showLive(initial: SecurityUi): androidx.compose.runtime.MutableState<SecurityUi> {
        val holder = androidx.compose.runtime.mutableStateOf(initial)
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { SecurityScreen(holder.value, onSend = { id, c -> sent += id to c }) }
        }
        return holder
    }
```

Tests:

```kotlin
    @Test
    fun `actions sit in their own section and an action alone is not the empty state`() {
        show(ui(listOf(action("siren", name = "Siren", area = "garage")), listOf(on("siren", false))))
        compose.onNodeWithText("Actions").assertExists()
        compose.onNode(hasText("Siren") and hasText("Garage") and hasText("Off")).assertExists()
        compose.onNodeWithText("No alarm panel or security sensor yet. Publish an MQTT alarm_control_panel, or pair door, motion, smoke or leak sensors.").assertDoesNotExist()
    }

    @Test
    fun `turning an action on asks first and sends only on confirm`() {
        show(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false))))
        compose.onNodeWithTag("action-siren").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithText("Turn on Siren?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(listOf<Pair<String, Command>>("siren" to Command.TurnOn()), sent)
        compose.onNodeWithText("Turn on Siren?").assertDoesNotExist()
    }

    @Test
    fun `turning an action off is immediate, and ask off fires at once`() {
        show(
            ui(
                listOf(action("siren", name = "Siren"), action("panic", Kind.BUTTON, name = "Panic", ui = UiOverride(securityAction = true, securityAsk = false))),
                listOf(on("siren", true)),
            ),
        )
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithTag("action-panic").performClick()
        assertEquals(listOf<Pair<String, Command>>("siren" to Command.TurnOff, "panic" to Command.Press), sent)
    }

    @Test
    fun `the confirm dialog closes when its action goes unavailable`() {
        val entities = listOf(action("siren", name = "Siren"))
        val live = showLive(ui(entities, listOf(on("siren", false))))
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithText("Turn on Siren?").assertExists()
        live.value = ui(entities, listOf(on("siren", false, available = false)))
        compose.onNodeWithText("Turn on Siren?").assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an unavailable action is not tappable but its sheet opens`() {
        show(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false, available = false))))
        compose.onNodeWithTag("action-siren").performClick()
        compose.onNodeWithText("Turn on Siren?").assertDoesNotExist()
        compose.onNodeWithTag("action-siren").performTouchInput { longClick() }
        compose.onNodeWithText("Ask before turning on").assertExists()
    }

    @Test
    fun `the action sheet saves ask and removes from Security`() {
        show(ui(listOf(action("siren", name = "Siren")), listOf(on("siren", false))))
        compose.onNodeWithTag("action-siren").performTouchInput { longClick() }
        compose.onNodeWithText("Ask before turning on").performClick()
        compose.onNodeWithText("Show on Security").performClick()
        assertEquals(listOf(Triple("siren", true, false), Triple("siren", false, true)), actionSettings)
    }
```

(add `import kotlin.test.assertTrue`). The last test's second triple keeps `ask = true` because `show` doesn't feed the saved override back into `state`; the sheet reads `row.ask` from the state.

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :feature:security:testDebugUnitTest --tests '*SecurityScreenTest*'`
Expected: compilation FAIL (`onSetAction` unknown).

- [ ] **Step 4: Create `ActionTile.kt`**

```kotlin
package com.gekkotron.oikos.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.security.R
import com.gekkotron.oikos.feature.security.presentation.ActionRow
import com.gekkotron.oikos.model.Kind

/** A half-width action tile, laid out like [SensorTile]: glyph and name, then room and state (security actions spec §3.2). */
@Composable
internal fun ActionTile(row: ActionRow, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = row.available && row.on == true
    val c = OikosTheme.colors
    // Like Home's switch: gradient card, amber glyph in dark themes; OikosCard sets the text colour of an "on" card.
    val glyphTint = if (on && c.isDark) c.warning else null
    val staleText = stringResource(R.string.security_stale)
    OikosCard(
        modifier
            .testTag("action-${row.id}")
            .alpha(if (!row.available) 0.55f else if (row.stale) 0.6f else 1f)
            .then(if (row.stale) Modifier.semantics { stateDescription = staleText } else Modifier),
        on = on,
        enabled = row.available,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.security_action_settings),
        minHeight = 0.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StaticIcon(if (row.kind == Kind.BUTTON) OikosGlyph.PLAY else OikosGlyph.PLUG, glyphTint, Modifier.size(22.dp))
            Text(row.name, style = OikosTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.areaName ?: stringResource(R.string.security_unassigned),
                style = OikosTheme.type.label,
                color = if (on) Color.Unspecified else c.contentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actionStateWord(row)?.let { Text(it, style = OikosTheme.type.body, maxLines = 1) }
        }
    }
}

/** "On" / "Off" / "Unavailable"; null for an available button (it has no state). */
@Composable
internal fun actionStateWord(row: ActionRow): String? = when {
    !row.available -> stringResource(R.string.security_unavailable)
    row.kind == Kind.BUTTON -> null
    row.on == true -> stringResource(R.string.security_action_on)
    row.on == false -> stringResource(R.string.security_action_off)
    else -> stringResource(R.string.security_unknown)
}
```

`OikosCard(on = true)` already provides `contentOnCardOn` as `LocalContentColor`, so on-state texts pass no colour (`Color.Unspecified` falls back to it).

- [ ] **Step 5: Create `ActionSheet.kt`**

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
import com.gekkotron.oikos.feature.security.presentation.ActionRow

/** Name, room and state, and the two settings an action has here (security actions spec §3.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(row: ActionRow, onSet: (show: Boolean, ask: Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(
                listOfNotNull(row.areaName ?: stringResource(R.string.security_unassigned), actionStateWord(row)).joinToString(" · "),
                style = OikosTheme.type.body,
            )
            SheetSwitch(stringResource(R.string.security_show), true) { onSet(false, row.ask) }
            SheetSwitch(stringResource(R.string.security_action_ask), row.ask) { onSet(true, it) }
            Text(stringResource(R.string.security_hide_hint), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
    }
}

@Composable
private fun SheetSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = OikosDimens.minTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
```

- [ ] **Step 6: Wire the screen**

In `SecurityScreen.kt`:

1. `SecurityRoute`: pass `onSetAction = viewModel::setAction` to `SecurityScreen`.
2. Signature: add `onSetAction: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },` after `onSetHidden`.
3. Below `var sheetId …` add:

```kotlin
    var actionSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
```

4. Replace `state.panels.isEmpty() && state.sensors.isEmpty() -> item(key = "empty")` with `state.isEmpty -> item(key = "empty")`.
5. After `items(state.panels, …) { … }` and before `item(key = "summary")`, add:

```kotlin
                    if (state.actions.isNotEmpty()) {
                        item(key = "section-actions") {
                            Text(
                                stringResource(R.string.security_section_actions),
                                style = OikosTheme.type.label,
                                color = OikosTheme.colors.contentMuted,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                            )
                        }
                        items(state.actions.chunked(2), key = { "actions-${it.first().id}" }) { pair ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap)) {
                                pair.forEach { row ->
                                    ActionTile(
                                        row,
                                        onClick = {
                                            val tap = row.tap()
                                            if (tap.confirm) confirmId = row.id else onSend(row.id, tap.command)
                                        },
                                        onLongClick = { actionSheetId = row.id },
                                        Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
```

6. After the existing `SensorSheet` block at the end of `SecurityScreen`, add:

```kotlin
    // An action that left Security closes its sheet.
    val actionRow = actionSheetId?.let { state.action(it) }
    if (actionRow != null) {
        ActionSheet(
            actionRow,
            onSet = { show, ask -> onSetAction(actionRow.id, show, ask); if (!show) actionSheetId = null },
            onDismiss = { actionSheetId = null },
        )
    }
    // Asked at tap time; an action that left Security or went unavailable meanwhile closes the dialog unsent.
    val confirmRow = confirmId?.let { state.action(it) }?.takeIf { it.available }
    if (confirmId != null && confirmRow == null) LaunchedEffect(confirmId) { confirmId = null }
    if (confirmRow != null) {
        val button = confirmRow.kind == Kind.BUTTON
        AlertDialog(
            onDismissRequest = { confirmId = null },
            title = { Text(stringResource(if (button) R.string.security_action_confirm_press else R.string.security_action_confirm_on, confirmRow.name)) },
            confirmButton = {
                TextButton(onClick = { confirmId = null; onSend(confirmRow.id, if (button) Command.Press else Command.TurnOn()) }) {
                    Text(stringResource(if (button) R.string.security_action_press else R.string.security_action_turn_on))
                }
            },
            dismissButton = { TextButton(onClick = { confirmId = null }) { Text(stringResource(R.string.security_cancel)) } },
        )
    }
```

Imports to add: `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, `com.gekkotron.oikos.feature.security.presentation.tap`, `com.gekkotron.oikos.model.Kind`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :feature:security:testDebugUnitTest`
Expected: PASS (new and existing `SecurityScreenTest` cases).

- [ ] **Step 8: Commit**

```bash
git add feature/security
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(security): Actions section with confirm dialog and action sheet

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Home customise sheet — Show on Security and Ask before for switches and buttons

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraft.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt:60-112`
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraftTest.kt`, `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheetTest.kt`

**Interfaces:**
- Consumes: Task 1's `UiOverride.securityAction/securityAsk`, `Placement.actionKind`, `Placement.onSecurity`.
- Produces: `OverrideDraft.securityAction: Boolean = false`, `OverrideDraft.securityAsk: Boolean = true` (appended after `homePinned`).

- [ ] **Step 1: Add the string**

`values/strings.xml`, next to `customize_show_on_security`: `<string name="customize_ask_before">Ask before turning on</string>`
`values-fr/strings.xml`, same place: `<string name="customize_ask_before">Demander avant d\'allumer</string>`

- [ ] **Step 2: Write the failing tests**

Append to `OverrideDraftTest`:

```kotlin
    @Test
    fun `action flags are read from and written back to the override`() {
        val sw = card(testEntity("siren", Kind.SWITCH, "Siren", ui = UiOverride(securityAction = true, securityAsk = false)))
        val draft = OverrideDraft.from(sw)
        assertTrue(draft.securityAction)
        assertFalse(draft.securityAsk)
        assertEquals(UiOverride(securityAction = true, securityAsk = false), draft.applyTo(sw))
        assertEquals(UiOverride(), draft.copy(securityAction = false, securityAsk = true).applyTo(sw))
    }
```

Append to `CustomizeSheetTest`:

```kotlin
    @Test
    fun `a switch can be put on Security, asking by default`() {
        show(card(testEntity("siren", Kind.SWITCH, "Siren")))
        compose.onNodeWithText("Ask before turning on").assertDoesNotExist()
        compose.onNodeWithText("Show on Security").performScrollTo().performClick()
        compose.onNodeWithText("Ask before turning on").performScrollTo().assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(securityAction = true), saved.single())
    }

    @Test
    fun `an opted-in switch offers Keep on Home and Ask before; turning Show on Security off hides both`() {
        show(card(testEntity("siren", Kind.SWITCH, "Siren", ui = UiOverride(securityAction = true))))
        compose.onNodeWithText("Show on Home").performScrollTo().assertExists()
        compose.onNodeWithText("Ask before turning on").performScrollTo().performClick()
        compose.onNodeWithText("Show on Security").performScrollTo().performClick()
        compose.onNodeWithText("Ask before turning on").assertDoesNotExist()
        compose.onNodeWithText("Show on Home").assertDoesNotExist()
        compose.onNodeWithText("Hide from Home").performScrollTo().assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(securityAsk = false), saved.single())
    }

    @Test
    fun `lights offer no Security switch`() {
        show(card(testEntity("lamp", Kind.LIGHT)))
        compose.onNodeWithText("Show on Security").assertDoesNotExist()
    }
```

Check `testEntity` accepts `ui = …` (it does: `CustomizeSheetTest` already calls `testEntity("lamp", Kind.LIGHT, ui = UiOverride(name = "X", hidden = true))`).

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*OverrideDraftTest*' --tests '*CustomizeSheetTest*'`
Expected: compilation FAIL (`securityAction` not a draft parameter).

- [ ] **Step 4: Implement `OverrideDraft`**

Add after `homePinned`:

```kotlin
    val securityAction: Boolean = false,
    val securityAsk: Boolean = true,
```

In `applyTo`'s `ui.copy(…)` add `securityAction = securityAction, securityAsk = securityAsk,`; in `from(card)` add `securityAction = card.ui.securityAction, securityAsk = card.ui.securityAsk,`.

- [ ] **Step 5: Implement `CustomizeSheet`**

1. After `var homePinned …` add:

```kotlin
    var securityAction by rememberSaveable(card.id) { mutableStateOf(start.securityAction) }
    var securityAsk by rememberSaveable(card.id) { mutableStateOf(start.securityAsk) }
```

2. Change the draft line to name the new arguments:

```kotlin
    val draft = OverrideDraft(name, areaId, favorite, hidden, order, cardStyle, energyRole, energyInvert, securityHidden, homePinned, securityAction, securityAsk)
```

3. Replace the `elsewhere` line and the `if (Placement.securityKind(…))` block with:

```kotlin
            // Energy or Security already shows it, so Home leaves it out unless pinned back.
            val onSecurity = Placement.onSecurity(card.kind, card.deviceClass, UiOverride(securityHidden = securityHidden, securityAction = securityAction))
            val elsewhere = energyRole != null || onSecurity
            if (elsewhere) {
                SwitchRow(stringResource(R.string.customize_show_on_home), homePinned && !hidden) { homePinned = it; if (it) hidden = false }
            } else {
                SwitchRow(stringResource(R.string.customize_hidden), hidden) { hidden = it }
            }
            if (Placement.securityKind(card.kind, card.deviceClass)) {
                SwitchRow(stringResource(R.string.customize_show_on_security), !securityHidden) { securityHidden = !it }
            }
            if (Placement.actionKind(card.kind)) {
                SwitchRow(stringResource(R.string.customize_show_on_security), securityAction) { securityAction = it }
                if (securityAction) SwitchRow(stringResource(R.string.customize_ask_before), securityAsk) { securityAsk = it }
            }
```

(`UiOverride` is already imported in `CustomizeSheet.kt`.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS (new and existing, including `switch sheet has no energy role` and `reset saves the default override`).

- [ ] **Step 7: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): customise sheet puts switches and buttons on Security

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Demo house — a siren and the intrusion button on Security, then full check

**Files:**
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt:35-80`
- Test: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt`

**Interfaces:**
- Consumes: Task 1's override fields (decoded from the retained JSON by the source).

- [ ] **Step 1: Write the failing test**

In `DemoBrokerTest`, add `"demo_siren"` to `allIds` (after `"demo_garage_plug"`), and append to `declared areas and overrides are applied`:

```kotlin
        assertTrue(e.getValue("demo_siren").ui.securityAction)
        assertTrue(e.getValue("demo_siren").ui.securityAsk)
        assertTrue(e.getValue("demo_alarm_intrusion").ui.securityAction)
        assertFalse(e.getValue("demo_alarm_intrusion").ui.securityAsk)
```

(add `import kotlin.test.assertFalse` if missing.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:demo:test --tests '*DemoBrokerTest*'`
Expected: FAIL (`demo_siren` missing from entities).

- [ ] **Step 3: Implement**

In `DemoHouse.init`, after the `demo_garage_plug` switch line:

```kotlin
        switch("demo_siren", "Siren", "Hallway", "demo/hallway/siren", on = false, available = true)
```

and after the last `override(…)` line:

```kotlin
        override("demo_siren", """{"securityAction":true}""")
        override("demo_alarm_intrusion", """{"securityAction":true,"securityAsk":false}""")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:demo:test`
Expected: PASS (`every stateful entity has a known state after settling` covers the siren's `OnOff(false)`).

- [ ] **Step 5: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. (Known flake: `OnboardingViewModelTest` can fail under a full `check` and passes alone — rerun `./gradlew :feature:onboarding:testDebugUnitTest` to confirm before treating it as a failure.)

- [ ] **Step 6: Commit**

```bash
git add core/demo
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(demo): siren and intrusion button on Security

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
