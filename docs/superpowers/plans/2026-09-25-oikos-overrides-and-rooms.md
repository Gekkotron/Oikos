# Oikos Overrides + Room Assignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user customize every card from Home and put their devices in rooms. Long-pressing a card opens an override sheet: rename, room, favourite, hide, position, card style, energy role. A "Rooms" step shown once after a new connection is set up assigns a room to each device. Both write the retained `oikos/ui/…` overrides that `MqttDiscoverySource` already reads and publishes.

**Architecture:**
- The publisher already exists: `EntitySource.setOverride` / `setAreas` (spec §4.3). This plan hardens it:
  - a default override clears its retained topic;
  - a failed publish rolls back the local change.
- `:feature:home` gets one shared writer, `OverrideEditor`, plus pure helpers:
  - `AreaNames`, which turns a typed room name into an `Area`, reusing an existing room with the same name;
  - `OverrideDraft`, the sheet's fields, converted to a `UiOverride`.
- These helpers are used by two screens, both in `:feature:home` because both need `EntitySource`:
  - the Home override sheet (`CustomizeSheet`);
  - the new Rooms screen (`rooms/`).
- The two screens share one `RoomPicker` composable.
- Discovery reads rooms from hierarchical zigbee2mqtt names (`rooms/julien/lampe` → room `julien`, name `lampe`) when a device sends no `suggested_area`. So the Rooms step starts mostly filled in on a Z2M house.
- `:core:data` persists a `roomsPending` flag, set when a direct profile is saved. `:app` shows Rooms instead of Home while that flag is set. Rooms can also be opened again from the link sheet ("Assign rooms").

**Tech Stack:** Unchanged. Kotlin 2.4.20, AGP 9.4.1, Compose BOM (Material 3), Navigation 3, Hilt 2.60.1 + KSP, kotlinx.serialization, DataStore 1.2.1. Tests: JUnit 4 + Robolectric (SDK 34) for Android modules, `kotlin.test` for JVM modules.

**Spec:** `docs/superpowers/specs/2026-09-24-oikos-design.md`
- §1 goal 3 (override layer);
- §3.D (zigbee2mqtt names, amended by Task 1);
- §4.2 (`UiOverride`);
- §4.3 point 4 (override topics, effective area);
- §5.2 (Onboarding step 5 "Room assignment, pre-filled from `suggested_area`, which can be skipped"; Home "Long-press: override sheet with rename, area, hide, favourite, order, card style and energy role");
- §5.3 (accessibility).

It also covers the row "Room-assignment onboarding step → Override plan" in the deferred table of `docs/superpowers/specs/2026-09-24-oikos-direct-connection-design.md`.

**Decisions taken with the user (2026-09-25):**
1. Long-press on a card that has controls (dimmable light, cover, climate, number, select, alarm) opens the `ControlSheet`, which gains a "Customize…" button. Long-press on any other card opens the override sheet directly.
2. Room assignment:
   - is a Rooms screen with one row per device, shown on the live connection after a direct profile is saved, before Home;
   - can be skipped;
   - can be reopened from the link sheet.
   - Assigning a device sets the area on all of its entities.
3. Room management is limited to creating rooms inline ("New room…"). Renaming, reordering and deleting rooms wait for the Settings plan.
4. Hidden cards come back through a trailing "Hidden (N)" chip on Home. It lists them dimmed, and a long-press there can unhide them.
5. Rooms from zigbee2mqtt names come after `suggested_area` in priority, the HA-standard field, so HA users' data wins. A device name with 3+ `/` segments gives its room (second-to-last segment) and its display name (last segment). Shorter names are untouched. This amends parent spec §3.D, which Task 1 updates.

## Global Constraints

- Package root `com.gekkotron.oikos`. minSdk 28, targetSdk/compileSdk 36, JVM 17.
- AGP 9 compiles Kotlin itself. Never apply `org.jetbrains.kotlin.android`.
- Robolectric runs at **SDK 34** (`src/test/resources/robolectric.properties` already exists in every Android module).
- Release unit-test tasks are disabled. Run Android tests with `testDebugUnitTest` or `check`.
- Module dependency rule (spec §4.1):
  - `:feature:home → :core:source, :core:designsystem` only; it never depends on `:core:data`.
  - `:feature:onboarding` is **not** touched by this plan.
  - `:core:data` depends on no other Oikos module.
  - Only `:app` wires modules together.
- Override topics and payloads are exactly spec §4.3:
  - `oikos/ui/<unique_id>` holds a retained JSON `UiOverride`;
  - `oikos/ui/_areas` holds a retained `{ "areas": [{ "id", "name", "icon", "order" }] }`;
  - the effective area is `override.area ?: device.suggestedArea ?: "Unassigned"`.
- "No room" is written as `area = "Unassigned"` (`UNASSIGNED_AREA`). A `null` area would fall back to `suggested_area`, so it cannot express "no room".
- Nothing house-specific in code. Room names in tests are generic ("Kitchen", "Living room", "Séjour").
- UI strings exist in English (`values/strings.xml`) and French (`values-fr/strings.xml`) **in the same commit** that adds them.
- Colours and fonts come only from `OikosTheme` tokens.
- Accessibility (spec §5.3):
  - touch targets ≥ 48 dp;
  - sheets and screens scroll, so 200 % font scale never clips;
  - long-press has an accessibility label (`onLongClickLabel`), so TalkBack users can reach it as an action;
  - errors are snackbars.
- Attribution: every commit uses exactly this form (do not edit `.git/config`):
  ```bash
  git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
  ```

## Review Focus

These five cases are implied by the spec but no task's main tests exercise them. Each has a pinning test in the task named.
1. **Offline edit.** A save while the broker is unreachable must not leave the phone showing a change the broker never got; after reconnect, the retained value would silently undo it. The edit rolls back and a "Couldn't save" snackbar appears. Pinned in Task 2 (`a failed override publish restores the previous override`) and Task 5 (`customize failure emits SaveFailed`).
2. **Unavailable device.** An offline plug must still be long-pressable, so it can be hidden or renamed. Tapping it must still do nothing. Pinned in Task 3 (`disabled card ignores clicks but still reports a long click`) and Task 6 (`unavailable card ignores taps and can still be customized`).
3. **Typing an existing room name** ("kitchen" when "Kitchen" exists, or "Séjour" twice) must reuse that room, not create a duplicate chip. Pinned in Task 5 (`AreaNamesTest`).
4. **Suggested areas.** Opening the sheet and saving without touching the room must not freeze the device's `suggested_area` into an explicit override. Likewise, "Reset" must clear the retained topic rather than publish `{}`. Pinned in Task 5 (`untouched fields keep the original override`) and Task 2 (`a default override clears the retained topic`).
5. **Rooms step on relaunch.** Killing the app on the Rooms step must bring the user back to Rooms. After "Skip" or "Done", the step must never come back. The demo never shows it. Pinned in Task 4 (`ProfileRepositoryTest`) and Task 8 (`MainActivityTest`).

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `core/model/src/main/kotlin/com/gekkotron/oikos/model/Device.kt` | Modify | `pathArea`; `name` is the short name |
| `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DevicePath.kt` | Create | `a/b/room/device` → room + short name |
| `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt` | Modify | Use `DevicePath` for devices and entity names |
| `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt` | Modify | Area fallback to `pathArea`, capitalized implicit names, case-insensitive name match; clear the retained topic on a default override; roll back on `PublishFailed` |
| `docs/superpowers/specs/2026-09-24-oikos-design.md` | Modify | §3.D and §4.3 point 4 |
| `core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt` | Modify | Tests for the above |
| `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/OikosCard.kt` | Modify | Long-press-only cards (sensors, unavailable devices) + `onLongClickLabel` |
| `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/OikosCardTest.kt` | Modify | Tests for the above |
| `core/data/src/main/kotlin/com/gekkotron/oikos/data/StoredProfile.kt` | Modify | `roomsPending` field |
| `core/data/src/main/kotlin/com/gekkotron/oikos/data/ConnectionProfile.kt` | Modify | `ProfileState.Ready.roomsPending` |
| `core/data/src/main/kotlin/com/gekkotron/oikos/data/ProfileRepository.kt` | Modify | Set on direct save, `finishRooms()` |
| `core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt` | Modify | Tests |
| `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/OnboardingViewModelTest.kt` | Modify | Expected `Ready(…, roomsPending = true)` for direct saves (test-only change) |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/AreaNames.kt` | Create | Room name → `Area` (reuse by name, slug id, uniqueness) |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraft.kt` | Create | Sheet fields ↔ `UiOverride` |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideEditor.kt` | Create | The single feature-side writer of `oikos/ui/…` |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt` | Modify | `RoomFilter.Hidden`, `CardUi.areaId/ui/hidden`, `HomeUiState.rooms/hiddenCount`, `HomeEvent.SaveFailed` |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt` | Modify | Hidden chip and view, room options |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModel.kt` | Modify | `customize`, `createRoom` |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/RoomPicker.kt` | Create | Room radio list + "New room" field (shared) |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt` | Create | The override sheet |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt` | Modify | "Customize…" button |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt` | Modify | Long-press label, hidden dimming, tap only when available |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt` | Modify | Long-press routing, Hidden chip, new callbacks |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheet.kt` | Modify | "Assign rooms" button |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeEventsEffect.kt` | Modify | `SaveFailed` snackbar |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsModels.kt` | Create | `RoomRowUi`, `RoomsUiState`, `RoomsEvent` |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsUiBuilder.kt` | Create | Entities + devices → rows |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsViewModel.kt` | Create | Assign, create room, changed flag |
| `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsScreen.kt` | Create | `RoomsRoute`, `RoomsScreen` |
| `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml` | Modify | New strings |
| `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/FakeEntitySource.kt` | Modify | Record overrides and areas, apply them, optional failure |
| `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/TestEntities.kt` | Modify | `deviceId` parameter |
| `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt` | Modify | `RoomsKey`, root choice, push from Home |
| `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt` | Modify | `finishRooms()` |
| `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt` | Modify | Rooms step end to end |

---

### Task 1: Rooms and short names from hierarchical device names

zigbee2mqtt users often name devices as paths (`rooms/julien/lampe`, `rdc/salon/plafond`). Z2M passes that `friendly_name` unchanged as the discovery `device.name`, and it sends no `suggested_area`. So today every card reads `rooms/julien/lampe` and every device starts with no room.

This task adds a generic rule, with nothing house-specific. A device name with **three or more** non-empty `/` segments:
- gets its **suggested room** from the second-to-last segment;
- gets its **display name** from the last segment.

Names with fewer segments (`AC/DC plug`) are left alone. The effective area becomes `override.area ?: suggested_area ?: pathArea ?: Unassigned`, so HA's own `suggested_area` still wins.

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Device.kt`
- Create: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DevicePath.kt`
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt` (`build`, `displayName`, `parseDevice`)
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt` (`publishEntities`, `resolveArea`, `computeAreas`)
- Modify: `docs/superpowers/specs/2026-09-24-oikos-design.md` (§3.D, §4.3 point 4)
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`, `core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt`

**Interfaces:**
- Produces:
  - `Device(id, name, manufacturer, model, suggestedArea, pathArea: String? = null)`. `name` is now the short name.
  - `internal object DevicePath { data class Parts(val name: String, val area: String?); fun split(name: String): Parts }`.
  - Implicit areas (from `suggested_area` or a path) are listed as `Area(id = raw, name = raw with `_` → space and the first letter upper-cased)`. For example `julien` → `Julien`.
  - A declared area matches a raw area by id, or by name **ignoring case**.
- Later tasks rely on this: the Rooms step (Task 7) starts pre-filled, and `AreaNames` (Task 5) reuses `Julien` when the user types `julien`.

- [ ] **Step 1: Write the failing tests**

In `DiscoveryParserTest`, change the expected names:
- `"rooms/salon/lampe"` → `"lampe"`
- `"rooms/entree/porte Door"` → `"porte Door"`
- `"rooms/salon/capteur Temperature"` → `"capteur Temperature"`

Keep the `state_topic` assertion (`zigbee2mqtt/rooms/salon/lampe`) unchanged. In `z2m json light`, add `assertEquals("salon", d.device?.pathArea)`. Then add:

```kotlin
    @Test
    fun `hierarchical device names split into a room and a short name, short paths are left alone`() {
        assertEquals(DevicePath.Parts("lampe", "julien"), DevicePath.split("rooms/julien/lampe"))
        assertEquals(DevicePath.Parts("plafond", "salon"), DevicePath.split("maison/rdc/salon/plafond"))
        assertEquals(DevicePath.Parts("AC/DC plug", null), DevicePath.split("AC/DC plug"))
        assertEquals(DevicePath.Parts("a//b", null), DevicePath.split("a//b"))
        assertEquals(DevicePath.Parts("Lampe", null), DevicePath.split("Lampe"))
    }

    @Test
    fun `an entity name that repeats the full device path is shortened too`() {
        val config = """{"command_topic":"z/p/set","state_topic":"z/p","unique_id":"p_power","name":"rooms/julien/prise Power",
            "device":{"identifiers":["p"],"name":"rooms/julien/prise"}}"""
        assertEquals("prise Power", single("homeassistant/switch/p/power/config", config).entity.name)
    }
```

In `MqttDiscoverySourceTest`, add:

```kotlin
    private fun z2mSwitch(id: String, deviceName: String, extraDevice: String = "") =
        """{"command_topic":"z/$id/set","state_topic":"z/$id","unique_id":"$id","name":null,"device":{"identifiers":["$id"],"name":"$deviceName"$extraDevice}}"""

    @Test
    fun `a hierarchical device name gives the room when there is no suggested_area, and suggested_area wins`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        h.transport.deliver("homeassistant/switch/q/config", z2mSwitch("q", "rooms/julien/radio", ""","suggested_area":"Bureau""""))
        runCurrent()
        val p = h.source.entities.value.getValue("p")
        assertEquals("prise", p.name)
        assertEquals("julien", p.area)
        assertEquals("Bureau", h.source.entities.value.getValue("q").area)
        assertEquals(Area("julien", "Julien"), h.source.areas.value.first { it.id == "julien" })
        assertEquals("prise", h.source.devices.value.getValue("p").name)
    }

    @Test
    fun `a declared area matches a path room by name ignoring case`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("bedroom_j", "Julien")))
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        runCurrent()
        assertEquals("bedroom_j", h.source.entities.value.getValue("p").area)
        assertEquals(listOf("bedroom_j"), h.source.areas.value.map { it.id })
    }

    @Test
    fun `an override still beats the path room`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        runCurrent()
        h.source.setOverride("p", UiOverride(area = "kitchen"))
        assertEquals("kitchen", h.source.entities.value.getValue("p").area)
    }
```

The existing test `areas resolve by id or name and are ordered declared, implicit, unassigned` asserts only ids (`listOf("kitchen", "garage", "Salon", UNASSIGNED_AREA)`), so it stays green. `Salon` is already capitalized.

- [ ] **Step 2: Run the tests and check that they fail**

Run: `./gradlew :core:source:test`
Expected: compile error (`DevicePath`, `pathArea` do not exist).

- [ ] **Step 3: Implement**

In `Device.kt`:

```kotlin
data class Device(
    val id: String,
    /** Display name: the last segment of a hierarchical name such as `rooms/julien/lampe`. */
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val suggestedArea: String?,
    /** Room taken from a hierarchical name (second-to-last segment); used only without [suggestedArea]. */
    val pathArea: String? = null,
)
```

Create `DevicePath.kt`:

```kotlin
package com.gekkotron.oikos.source.discovery

/**
 * zigbee2mqtt users often name devices as paths ("rooms/julien/lampe", "rdc/salon/plafond"), and HA
 * discovery carries that name unchanged. With three or more non-empty segments, the last is the device
 * and the second-to-last its room. Shorter names ("AC/DC plug") are ordinary names.
 */
internal object DevicePath {
    data class Parts(val name: String, val area: String?)

    fun split(name: String): Parts {
        val segments = name.split('/').map { it.trim() }
        if (segments.size < 3 || segments.any { it.isEmpty() }) return Parts(name, null)
        return Parts(segments.last(), segments[segments.size - 2])
    }
}
```

In `DiscoveryParser.kt`, change `parseDevice`'s return to:

```kotlin
        val path = DevicePath.split(d.str("name") ?: id)
        return Device(id, path.name, d.str("manufacturer"), d.str("model"), d.str("suggested_area"), pathArea = path.area)
```

In `build`, compute the raw device name and pass it to `displayName`:

```kotlin
        val device = parseDevice(config["device"])
        val rawDeviceName = (config["device"] as? JsonObject)?.let { MqttBindings("device", it).str("name") }
```

Use `name = displayName(b.str("name"), device, rawDeviceName, objectId),` and replace `displayName`:

```kotlin
    private fun displayName(entityName: String?, device: Device?, rawDeviceName: String?, objectId: String): String {
        // An entity name that repeats the full device path gets the short device name instead.
        val name = if (entityName != null && device != null && rawDeviceName != null && rawDeviceName != device.name && entityName.startsWith(rawDeviceName)) {
            device.name + entityName.removePrefix(rawDeviceName)
        } else {
            entityName
        }
        return when {
            name == null -> device?.name ?: objectId
            device != null && !name.startsWith(device.name) -> "${device.name} $name"
            else -> name
        }
    }
```

In `MqttDiscoverySource.publishEntities`, change the area line:

```kotlin
                val area = resolveArea(ui.area ?: d.device?.suggestedArea ?: d.device?.pathArea ?: UNASSIGNED_AREA, declared)
```

Change `resolveArea` and the `implicit` line of `computeAreas`:

```kotlin
    private fun resolveArea(raw: String, declared: List<Area>): String =
        declared.firstOrNull { it.id == raw || it.name.equals(raw, ignoreCase = true) }?.id ?: raw
```

```kotlin
        val implicit = (used - declaredIds - UNASSIGNED_AREA).sorted().map { Area(it, implicitName(it)) }
```

Add this private helper next to `resolveArea`:

```kotlin
    /** "julien" → "Julien", "salle_de_bain" → "Salle de bain"; the id stays raw. */
    private fun implicitName(raw: String): String =
        raw.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
```

Add `import java.util.Locale`.

- [ ] **Step 4: Update the spec**

In `docs/superpowers/specs/2026-09-24-oikos-design.md` §3.D, replace the `friendly_name` bullet with:

```markdown
- `friendly_name` values stay `rooms/<room>/<device>`. Any device name with three or more `/` segments gives a suggested room (the second-to-last segment) and a display name (the last segment). This applies only when the device sends no `suggested_area`, so HA's own field still wins. Shorter names are left as they are.
```

In §4.3 point 4, change the effective-area bullet to:

```markdown
   - The effective area is `override.area ?: device.suggestedArea ?: device.pathArea ?: "Unassigned"`, where `pathArea` comes from a hierarchical device name (§3.D). A declared area matches a raw area by id, or by name ignoring case.
```

- [ ] **Step 5: Run the tests and check that they pass**

Run: `./gradlew :core:source:test :app:testDebugUnitTest :feature:onboarding:testDebugUnitTest`
Expected: PASS. The onboarding tester lists device names, which are now short. If an `:app` test asserts on a full `rooms/…` device name, update the expected value to the short name.

- [ ] **Step 6: Commit**

```bash
git add core/model core/source docs/superpowers/specs/2026-09-24-oikos-design.md
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(source): hierarchical device names (rooms/<room>/<device>) give a suggested room and a short name" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: The override publisher clears defaults and rolls back failed publishes

**Files:**
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt` (`setOverride`, `setAreas`)
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt`

**Interfaces:**
- Consumes: `FakeMqttTransport.failPublishes`, `PublishFailed` (existing).
- Produces:
  - `EntitySource.setOverride(uniqueId, UiOverride())` publishes an **empty retained payload** (it removes the override);
  - `setOverride` and `setAreas` throw `PublishFailed` after restoring the previous local value. Tasks 4 and 6 rely on this.

- [ ] **Step 1: Write the failing tests**

Add these tests to `MqttDiscoverySourceTest`, after `setAreas publishes the areas document`. Add `import com.gekkotron.oikos.transport.PublishFailed` to the imports.

```kotlin
    @Test
    fun `a default override clears the retained topic instead of publishing an empty object`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        val discoveredName = h.source.entities.value.getValue(lightId).name
        h.source.setOverride(lightId, UiOverride(name = "X"))
        h.source.setOverride(lightId, UiOverride())
        assertEquals(MqttMessage("oikos/ui/$lightId", ByteArray(0), retained = true), h.transport.published.last())
        assertEquals(discoveredName, h.source.entities.value.getValue(lightId).name)
        assertEquals(UiOverride(), h.source.entities.value.getValue(lightId).ui)
    }

    @Test
    fun `a failed override publish restores the previous override and rethrows`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.source.setOverride(lightId, UiOverride(name = "X"))
        h.transport.failPublishes = true
        assertFailsWith<PublishFailed> { h.source.setOverride(lightId, UiOverride(name = "Y")) }
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
    }

    @Test
    fun `a failed first override publish leaves no override behind`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.failPublishes = true
        assertFailsWith<PublishFailed> { h.source.setOverride(lightId, UiOverride(hidden = true)) }
        assertFalse(h.source.entities.value.getValue(lightId).ui.hidden)
    }

    @Test
    fun `a failed areas publish restores the previous areas and rethrows`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("salon", "Salon")))
        h.transport.failPublishes = true
        assertFailsWith<PublishFailed> { h.source.setAreas(listOf(Area("salon", "Salon"), Area("kitchen", "Kitchen"))) }
        assertEquals(listOf("salon"), h.source.areas.value.map { it.id })
    }
```

- [ ] **Step 2: Run the tests and check that they fail**

Run: `./gradlew :core:source:test --tests '*MqttDiscoverySourceTest*'`
Expected: the four new tests FAIL. The first gets `{}` instead of an empty payload; the others keep `Y` or the new areas, or get `hidden = true`.

- [ ] **Step 3: Implement**

In `MqttDiscoverySource.kt`, replace `setOverride` and `setAreas` with:

```kotlin
    /**
     * Applies locally, then publishes retained. A default override removes the retained message (an
     * empty payload) instead of leaving `{}` behind. If the broker does not take it, the local change is
     * rolled back, unless something newer replaced it meanwhile, and [PublishFailed] is rethrown:
     * otherwise the phone would show an edit that the retained value undoes at the next reconnect.
     */
    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        require(uniqueId.isNotEmpty() && uniqueId != AREAS_ID && isValidPublishTopic(UI_PREFIX + uniqueId)) {
            "unique_id '$uniqueId' cannot be used in an override topic"
        }
        val cleared = override == UiOverride()
        val previous = synchronized(lock) { if (cleared) overrides.remove(uniqueId) else overrides.put(uniqueId, override) }
        publishEntities()
        val payload = if (cleared) ByteArray(0) else UiOverrides.encode(override).encodeToByteArray()
        try {
            transport.publish(UI_PREFIX + uniqueId, payload, qos = 1, retain = true)
        } catch (e: PublishFailed) {
            synchronized(lock) {
                if (overrides[uniqueId] == override.takeUnless { cleared }) {
                    if (previous == null) overrides.remove(uniqueId) else overrides[uniqueId] = previous
                }
            }
            publishEntities()
            throw e
        }
    }

    override suspend fun setAreas(areas: List<Area>) {
        val document = AreasDocument(areas)
        val previous = synchronized(lock) { areasDocument.also { areasDocument = document } }
        publishEntities()
        val json = OikosJson.encodeToString(AreasDocument.serializer(), document)
        try {
            transport.publish(AREAS_TOPIC, json.encodeToByteArray(), qos = 1, retain = true)
        } catch (e: PublishFailed) {
            synchronized(lock) { if (areasDocument == document) areasDocument = previous }
            publishEntities()
            throw e
        }
    }
```

- [ ] **Step 4: Run the module tests and check that they pass**

Run: `./gradlew :core:source:test`
Expected: PASS, including the existing `setOverride applies locally and publishes retained JSON` and `setAreas publishes the areas document`.

- [ ] **Step 5: Commit**

```bash
git add core/source
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "fix(source): a default override clears its retained topic; failed override and areas publishes roll back" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `OikosCard` supports long-press-only cards and a long-press label

**Files:**
- Modify: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/OikosCard.kt`
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/OikosCardTest.kt`

**Interfaces:**
- Produces: `OikosCard(modifier, on, enabled, onClick, onLongClick, onLongClickLabel: String? = null, content)`, with these semantics:
  - `enabled = false` disables **only** `onClick`;
  - `onLongClick` always works;
  - a card with `onLongClick` but no usable `onClick` has no click action, only a long-click action.

  Task 6 relies on this.

- [ ] **Step 1: Write the failing tests**

Replace the test `disabled card ignores clicks` in `OikosCardTest` with the tests below. Change `show` to take `onClick` and `label`. Add the imports `androidx.compose.ui.semantics.SemanticsActions`, `androidx.compose.ui.test.SemanticsMatcher`, `androidx.compose.ui.test.assert` and `androidx.compose.ui.test.onNodeWithTag`, and `androidx.compose.ui.platform.testTag`.

```kotlin
    private fun show(enabled: Boolean = true, onClick: (() -> Unit)? = { events += "click" }, label: String? = null) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            OikosCard(
                modifier = Modifier.testTag("card"),
                enabled = enabled,
                onClick = onClick,
                onLongClick = { events += "long" },
                onLongClickLabel = label,
            ) { Text("Lamp") }
        }
    }

    @Test
    fun `disabled card ignores clicks but still reports a long click`() {
        show(enabled = false)
        compose.onNodeWithTag("card").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Lamp").performClick()
        compose.onNodeWithText("Lamp").performTouchInput { longClick() }
        assertEquals(listOf("long"), events)
    }

    @Test
    fun `long-press-only card exposes a labelled long-click action`() {
        show(onClick = null, label = "Customize")
        val node = compose.onNodeWithTag("card").fetchSemanticsNode()
        assertEquals("Customize", node.config[SemanticsActions.OnLongClick].label)
        compose.onNodeWithTag("card").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Lamp").performTouchInput { longClick() }
        assertEquals(listOf("long"), events)
    }

    @Test
    fun `clickable card carries the long-click label too`() {
        show(label = "Controls")
        assertEquals("Controls", compose.onNodeWithTag("card").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    }

    @Test
    fun `disabled card without long click is not enabled`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                OikosCard(modifier = Modifier.testTag("card"), enabled = false, onClick = { events += "click" }) { Text("Lamp") }
            }
        }
        compose.onNodeWithTag("card").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), events)
    }
```

Also add `import androidx.compose.ui.Modifier`.

- [ ] **Step 2: Run the tests and check that they fail**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*OikosCardTest*'`
Expected: compile error (`onLongClickLabel` does not exist).

- [ ] **Step 3: Implement**

In `OikosCard.kt`, add the parameter and replace the `clicks` computation:

```kotlin
@Composable
fun OikosCard(
    modifier: Modifier = Modifier,
    on: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** Read by TalkBack as the long-press action ("Customize", "Controls"). */
    onLongClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OikosTheme.colors
    val shape = RoundedCornerShape(OikosDimens.cardRadius)
    val background = if (on) Brush.linearGradient(listOf(c.cardOnStart, c.cardOnEnd)) else SolidColor(c.card)
    val haptics = LocalHapticFeedback.current
    val longClick by rememberUpdatedState(onLongClick)
    // `enabled` gates the tap only: an unavailable device must still be long-pressable, to hide or rename it.
    val clicks = when {
        onClick != null && enabled ->
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
        onLongClick != null -> Modifier
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick?.invoke()
                })
            }
            // `this.` picks the semantics action, not the nullable `onLongClick` parameter of the same name.
            .semantics { this.onLongClick(label = onLongClickLabel) { longClick?.invoke(); true } }
        onClick != null -> Modifier.combinedClickable(enabled = false, onClick = onClick)
        else -> Modifier
    }
```

The rest of the function body stays unchanged. Add the imports:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
```

- [ ] **Step 4: Run the module tests and check that they pass**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: PASS (`click and long click are reported` is unchanged).

- [ ] **Step 5: Commit**

```bash
git add core/designsystem
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(designsystem): long-press-only cards and a long-press accessibility label; disabled gates the tap only" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `roomsPending` in the saved profile

**Files:**
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/StoredProfile.kt`
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ConnectionProfile.kt` (`ProfileState.Ready`)
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/ProfileRepository.kt`
- Test: `core/data/src/test/kotlin/com/gekkotron/oikos/data/ProfileRepositoryTest.kt`
- Modify (test only): `feature/onboarding/src/test/kotlin/com/gekkotron/oikos/feature/onboarding/OnboardingViewModelTest.kt`

**Interfaces:**
- Produces:
  - `ProfileState.Ready(profile: ConnectionProfile, roomsPending: Boolean = false)`;
  - `ProfileRepository.save(profile)` sets `roomsPending = profile is ConnectionProfile.Direct`;
  - `suspend fun ProfileRepository.finishRooms()` clears it.

  Task 8 relies on these.
- The flag is one field of the same `StoredProfile`, so `state` never shows a `Ready` without its flag. (Two separate flows could show the demo's Rooms step for one frame.)
- Profiles saved before this change decode with `roomsPending = false`, so existing installs go straight to Home.

- [ ] **Step 1: Write the failing tests**

In `ProfileRepositoryTest`:
- Change the expected values at lines 42, 57 and 102: `ProfileState.Ready(direct)` → `ProfileState.Ready(direct, roomsPending = true)`, `ProfileState.Ready(profile)` → `ProfileState.Ready(profile, roomsPending = true)`, and `ProfileState.Ready(plain)` → `ProfileState.Ready(plain, roomsPending = true)`.
- Keep line 49 (`Ready(ConnectionProfile.Demo)`) as it is: the demo never asks for rooms.
- Then add:

```kotlin
    @Test
    fun `a direct profile asks for rooms until finishRooms, and that survives a restart`() = runTest {
        val f = file()
        val r = repo(f)
        r.save(direct)
        assertEquals(ProfileState.Ready(direct, roomsPending = true), r.state.first { it is ProfileState.Ready })
        r.finishRooms()
        assertEquals(ProfileState.Ready(direct), r.state.first { it == ProfileState.Ready(direct) })
        val copy = tmp.root.resolve("copy.json")
        f.copyTo(copy)
        assertEquals(ProfileState.Ready(direct), repo(copy).settled())
    }

    @Test
    fun `saving a new direct profile asks for rooms again`() = runTest {
        val r = repo()
        r.save(direct)
        r.finishRooms()
        r.state.first { it == ProfileState.Ready(direct) }
        r.clear()
        r.save(direct)
        assertEquals(ProfileState.Ready(direct, roomsPending = true), r.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `a profile file written before the flag existed does not ask for rooms`() = runTest {
        val f = file()
        repo(f).apply { save(ConnectionProfile.Direct(LocalBroker("broker.local"))); state.first { it is ProfileState.Ready } }
        val old = tmp.root.resolve("old.json")
        old.writeText(f.readText().replace(Regex(""","?"roomsPending":(true|false)"""), ""))
        assertEquals(ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("broker.local"))), repo(old).settled())
    }
```

In `OnboardingViewModelTest`, change the expected values at lines 161 and 178 from `ProfileState.Ready(qrProfile)` to `ProfileState.Ready(qrProfile, roomsPending = true)`. Leave line 230 (the demo) as it is.

- [ ] **Step 2: Run the tests and check that they fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*ProfileRepositoryTest*'`
Expected: compile error (`roomsPending`, `finishRooms` do not exist).

- [ ] **Step 3: Implement**

In `StoredProfile.kt`, add the last field:

```kotlin
    val pushTokenTopic: String? = null,
    /** A direct profile was just set up and the Rooms step has not been finished or skipped (spec §5.2 step 5). */
    val roomsPending: Boolean = false,
)
```

In `ConnectionProfile.kt`, change `Ready`:

```kotlin
    /** [roomsPending]: show the Rooms step before Home (a direct profile that was just set up). */
    data class Ready(val profile: ConnectionProfile, val roomsPending: Boolean = false) : ProfileState
```

In `ProfileRepository.kt`:
- In `toState`, pass the flag: `StoredMode.DEMO -> ProfileState.Ready(ConnectionProfile.Demo)` stays as it is, and `s.toDirect()?.let { ProfileState.Ready(it, s.roomsPending) }`.
- In `toStored`, the `is ConnectionProfile.Direct` branch gets `roomsPending = true,` after `pushTokenTopic = p.pushTokenTopic,`.
- Add after `clear()`:

```kotlin
    /** The Rooms step was finished or skipped; it is not shown again for this profile. */
    suspend fun finishRooms() {
        store.updateData { it.copy(roomsPending = false) }
    }
```

- [ ] **Step 4: Run the tests and check that they pass**

Run: `./gradlew :core:data:testDebugUnitTest :feature:onboarding:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. `:app` still compiles, since `Ready(profile)` construction and `.profile` reads are unchanged. `ActiveTransport` maps `Ready` to a spec with `distinctUntilChanged`, so `finishRooms()` does not reconnect.

- [ ] **Step 5: Commit**

```bash
git add core/data feature/onboarding/src/test
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(data): remember that a new direct profile still needs its Rooms step" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Home presentation: `AreaNames`, `OverrideDraft`, `OverrideEditor`, the Hidden view and ViewModel actions

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/AreaNames.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraft.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideEditor.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt` (the `HomeChips` `when` only, so it compiles)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeEventsEffect.kt` + strings (the `SaveFailed` branch, so it compiles)
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/FakeEntitySource.kt`, `TestEntities.kt`
- Test: create `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/AreaNamesTest.kt`, `OverrideDraftTest.kt` and `OverrideEditorTest.kt`; modify `HomeUiBuilderTest.kt` and `HomeViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 (`setOverride`/`setAreas` throw after rolling back).
- Produces (Tasks 5 and 6 use these exact names):
  - `object AreaNames { data class Resolved(val area: Area, val isNew: Boolean); fun resolve(name: String, existing: List<Area>): Resolved?; fun slug(name: String): String; fun withArea(existing: List<Area>, area: Area): List<Area> }`
  - `data class OverrideDraft(name: String, areaId: String, favorite: Boolean, hidden: Boolean, order: String, cardStyle: String?, energyRole: EnergyRole?)` with `val orderValid: Boolean`, `fun applyTo(card: CardUi): UiOverride`, `companion fun from(card: CardUi)`, `const val MAX_ORDER = 9999`
  - `class OverrideEditor(source: EntitySource)` with `suspend fun save(id: String, override: UiOverride): Boolean`, `suspend fun assignArea(ids: Collection<String>, areaId: String): Boolean`, `suspend fun addRoom(area: Area): Boolean`
  - `RoomFilter.Hidden` (key `"hidden"`)
  - `CardUi` gains `areaId: String`, `ui: UiOverride` and `hidden: Boolean`
  - `HomeUiState` gains `rooms: List<Area>` (every area except Unassigned) and `hiddenCount: Int`
  - `HomeEvent.SaveFailed` (a `data object`)
  - `HomeViewModel.customize(id: String, override: UiOverride)` and `HomeViewModel.createRoom(name: String): String?`
  - test helper `testEntity(…, deviceId: String? = null)`; `FakeEntitySource.overrides: MutableMap<String, UiOverride>`, `areaWrites: MutableList<List<Area>>`, `var failWrites: Boolean`

- [ ] **Step 1: Update the test fakes**

Replace `FakeEntitySource.kt` with a version that applies writes the way the real source does, so ViewModels observe them:

```kotlin
package com.gekkotron.oikos.feature.home

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
    override val commandFailures = MutableSharedFlow<CommandFailure>(extraBufferCapacity = 8)
    val stateMap = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val sent = mutableListOf<Pair<String, Command>>()
    var result: CommandResult = CommandResult.Accepted

    /** Every override written, last one per id. */
    val overrides = mutableMapOf<String, UiOverride>()
    val areaWrites = mutableListOf<List<Area>>()

    /** Makes every override and areas write fail like an offline broker. */
    var failWrites = false

    override fun states() = stateMap

    override suspend fun send(uniqueId: String, command: Command): CommandResult {
        sent += uniqueId to command
        return result
    }

    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        if (failWrites) throw PublishFailed("offline")
        overrides[uniqueId] = override
        entities.value[uniqueId]?.let { e ->
            entities.value += uniqueId to e.copy(ui = override, area = override.area ?: e.area, name = override.name ?: e.name)
        }
    }

    override suspend fun setAreas(areas: List<Area>) {
        if (failWrites) throw PublishFailed("offline")
        areaWrites += areas
        this.areas.value = areas
    }
}
```

In `TestEntities.kt`, add a `deviceId` parameter:

```kotlin
fun testEntity(
    id: String,
    kind: Kind,
    name: String = id,
    area: String = UNASSIGNED_AREA,
    deviceClass: String? = null,
    unit: String? = null,
    ui: UiOverride = UiOverride(),
    capabilities: Capabilities = Capabilities(),
    deviceId: String? = null,
) = Entity(id, deviceId, name, kind, deviceClass, unit, null, capabilities, NoBindings, area, ui)
```

`:feature:home` already sees `PublishFailed` through `:core:source`'s `api` dependency on `:core:transport`, the same way it sees `LinkState`. If the import does not resolve, add `testImplementation(project(":core:transport"))` to `feature/home/build.gradle.kts`.

- [ ] **Step 2: Write the failing tests for `AreaNames`**

Create `AreaNamesTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AreaNamesTest {
    private val existing = listOf(Area("kitchen", "Kitchen"), Area("Salon", "Salon"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))

    @Test
    fun `a new name gets a lowercase ascii id`() {
        assertEquals(AreaNames.Resolved(Area("living_room", "Living room"), isNew = true), AreaNames.resolve("  Living   room ", existing))
        assertEquals("sejour", AreaNames.slug("Séjour"))
        assertEquals("salle_de_bain_2", AreaNames.slug("Salle de bain #2"))
    }

    @Test
    fun `a name matching an existing room ignoring case reuses it`() {
        assertEquals(AreaNames.Resolved(Area("kitchen", "Kitchen"), isNew = false), AreaNames.resolve("kitchen", existing))
        assertEquals(AreaNames.Resolved(Area("Salon", "Salon"), isNew = false), AreaNames.resolve("SALON", existing))
    }

    @Test
    fun `ids stay unique`() {
        val taken = listOf(Area("office", "Bureau"), Area("office_2", "Study"))
        assertEquals("office_3", AreaNames.resolve("Office", taken)?.area?.id)
    }

    @Test
    fun `names without letters or digits still get an id`() {
        assertEquals("room", AreaNames.resolve("客厅", emptyList())?.area?.id)
        assertEquals("room_2", AreaNames.resolve("🛋", listOf(Area("room", "客厅")))?.area?.id)
    }

    @Test
    fun `blank and reserved names are refused`() {
        assertNull(AreaNames.resolve("   ", existing))
        assertNull(AreaNames.resolve("unassigned", existing))
    }

    @Test
    fun `withArea drops Unassigned and appends`() {
        assertEquals(
            listOf(Area("kitchen", "Kitchen"), Area("Salon", "Salon"), Area("office", "Office")),
            AreaNames.withArea(existing, Area("office", "Office")),
        )
    }
}
```

- [ ] **Step 3: Write the failing tests for `OverrideDraft`**

Create `OverrideDraftTest.kt`. `card()` builds a `CardUi` through the builder, so the test follows the real mapping:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverrideDraftTest {
    private fun card(entity: Entity): CardUi =
        HomeUiBuilder.build(mapOf(entity.uniqueId to entity), emptyMap(), listOf(Area("kitchen", "Kitchen")), RoomFilter.All, LinkState.Connecting, Locale.US)
            .card(entity.uniqueId)!!

    // Area comes from suggested_area: no explicit override.
    private val suggested = card(testEntity("lamp", Kind.LIGHT, "Lamp", area = "kitchen"))

    @Test
    fun `untouched fields keep the original override, so a suggested area is not frozen`() {
        assertEquals(UiOverride(), OverrideDraft.from(suggested).applyTo(suggested))
    }

    @Test
    fun `changed fields are written`() {
        val draft = OverrideDraft.from(suggested).copy(name = " Ceiling ", areaId = "living", favorite = true, hidden = true, order = "3")
        assertEquals(UiOverride(name = "Ceiling", area = "living", favorite = true, hidden = true, order = 3), draft.applyTo(suggested))
    }

    @Test
    fun `a blank name falls back to the discovered name`() {
        val renamed = card(testEntity("lamp", Kind.LIGHT, "Ceiling", ui = UiOverride(name = "Ceiling")))
        assertEquals(UiOverride(), OverrideDraft.from(renamed).copy(name = "  ").applyTo(renamed))
    }

    @Test
    fun `a blank position means automatic`() {
        val ordered = card(testEntity("lamp", Kind.LIGHT, ui = UiOverride(order = 4)))
        assertEquals("4", OverrideDraft.from(ordered).order)
        assertEquals(UiOverride(), OverrideDraft.from(ordered).copy(order = "").applyTo(ordered))
    }

    @Test
    fun `position must be a whole number from 0 to 9999`() {
        val d = OverrideDraft.from(suggested)
        assertTrue(d.copy(order = "").orderValid)
        assertTrue(d.copy(order = " 12 ").orderValid)
        assertFalse(d.copy(order = "-1").orderValid)
        assertFalse(d.copy(order = "10000").orderValid)
        assertFalse(d.copy(order = "1.5").orderValid)
    }

    @Test
    fun `sensor style and energy role are written, and other kinds keep theirs`() {
        val sensor = card(testEntity("power", Kind.SENSOR, deviceClass = "power"))
        assertEquals(
            UiOverride(cardStyle = HomeUiBuilder.HERO_WEATHER, energyRole = EnergyRole.GRID),
            OverrideDraft.from(sensor).copy(cardStyle = HomeUiBuilder.HERO_WEATHER, energyRole = EnergyRole.GRID).applyTo(sensor),
        )
        val styled = card(testEntity("sw", Kind.SWITCH, ui = UiOverride(cardStyle = "future_style")))
        assertEquals(UiOverride(cardStyle = "future_style", favorite = true), OverrideDraft.from(styled).copy(favorite = true).applyTo(styled))
    }
}
```

- [ ] **Step 4: Write the failing tests for `OverrideEditor`**

Create `OverrideEditorTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.FakeEntitySource
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverrideEditorTest {
    private val source = FakeEntitySource().apply {
        entities.value = listOf(
            testEntity("a", Kind.LIGHT, ui = UiOverride(favorite = true)),
            testEntity("b", Kind.SWITCH, area = "kitchen"),
            testEntity("c", Kind.SENSOR),
        ).associateBy { it.uniqueId }
        areas.value = listOf(Area("kitchen", "Kitchen"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
    }
    private val editor = OverrideEditor(source)

    @Test
    fun `assignArea keeps the rest of each override and skips entities already there`() = runTest {
        assertTrue(editor.assignArea(listOf("a", "b", "missing"), "kitchen"))
        assertEquals(mapOf("a" to UiOverride(favorite = true, area = "kitchen")), source.overrides)
    }

    @Test
    fun `no room is written as Unassigned`() = runTest {
        assertTrue(editor.assignArea(listOf("b"), UNASSIGNED_AREA))
        assertEquals(UiOverride(area = UNASSIGNED_AREA), source.overrides["b"])
    }

    @Test
    fun `addRoom writes the current areas plus the new one, without Unassigned`() = runTest {
        assertTrue(editor.addRoom(Area("office", "Office")))
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("office", "Office"))), source.areaWrites)
    }

    @Test
    fun `failures are reported as false, never thrown`() = runTest {
        source.failWrites = true
        assertFalse(editor.save("a", UiOverride(hidden = true)))
        assertFalse(editor.assignArea(listOf("a"), "kitchen"))
        assertFalse(editor.addRoom(Area("office", "Office")))
    }

    @Test
    fun `an id that cannot be a topic is a failure, not a crash`() = runTest {
        val strict = OverrideEditor(object : com.gekkotron.oikos.source.EntitySource by source {
            override suspend fun setOverride(uniqueId: String, override: UiOverride) = throw IllegalArgumentException("bad id")
        })
        assertFalse(strict.save("a#b", UiOverride()))
    }
}
```

- [ ] **Step 5: Write the failing builder and ViewModel tests**

Add to `HomeUiBuilderTest`:

```kotlin
    @Test
    fun `hidden entities get a trailing Hidden chip that lists only them`() {
        val lamp = testEntity("lamp", Kind.LIGHT, area = "living")
        val secret = testEntity("secret", Kind.SWITCH, area = "kitchen", ui = UiOverride(hidden = true))
        val all = build(lamp, secret)
        assertEquals(RoomFilter.Hidden, all.chips.last().filter)
        assertEquals(1, all.hiddenCount)
        assertEquals(listOf("lamp"), all.cards.map { it.id })
        val hidden = build(lamp, secret, selected = RoomFilter.Hidden)
        assertEquals(RoomFilter.Hidden, hidden.selected)
        assertEquals(listOf("secret"), hidden.cards.map { it.id })
        assertTrue(hidden.cards.single().hidden)
    }

    @Test
    fun `Hidden selection falls back to All when nothing is hidden any more`() {
        val s = build(testEntity("lamp", Kind.LIGHT), selected = RoomFilter.Hidden)
        assertEquals(RoomFilter.All, s.selected)
        assertEquals(0, s.hiddenCount)
        assertFalse(s.chips.any { it.filter == RoomFilter.Hidden })
    }

    @Test
    fun `cards carry their area id and override, and rooms exclude Unassigned`() {
        val s = build(
            testEntity("lamp", Kind.LIGHT, area = "living", ui = UiOverride(favorite = true)),
            testEntity("x", Kind.SWITCH),
            areaList = areas + Area(UNASSIGNED_AREA, UNASSIGNED_AREA),
        )
        val lamp = s.card("lamp")!!
        assertEquals("living", lamp.areaId)
        assertEquals(UiOverride(favorite = true), lamp.ui)
        assertEquals(UNASSIGNED_AREA, s.card("x")!!.areaId)
        assertEquals(areas, s.rooms)
    }

    @Test
    fun `hidden filter key round-trips`() {
        assertEquals(RoomFilter.Hidden, RoomFilter.fromKey(RoomFilter.Hidden.key))
    }
```

Add to `HomeViewModelTest` (add the imports `com.gekkotron.oikos.model.UiOverride` and `kotlin.test.assertNull`):

```kotlin
    @Test
    fun `customize writes the override`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        vm.customize("lamp", UiOverride(name = "Ceiling"))
        assertEquals(UiOverride(name = "Ceiling"), source.overrides["lamp"])
    }

    @Test
    fun `customize failure emits SaveFailed`() = runTest(dispatcher) {
        source.failWrites = true
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        vm.customize("lamp", UiOverride(hidden = true))
        assertEquals(HomeEvent.SaveFailed, vm.events.first())
    }

    @Test
    fun `createRoom returns an existing room's id without writing, or a new id it writes`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle(), dispatcher)
        assertEquals("kitchen", vm.createRoom("KITCHEN"))
        assertEquals(emptyList(), source.areaWrites)
        assertEquals("living_room", vm.createRoom("Living room"))
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("living_room", "Living room"))), source.areaWrites)
        assertNull(vm.createRoom("  "))
    }
```

- [ ] **Step 6: Run the tests and check that they fail**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: compile errors (`AreaNames`, `OverrideDraft`, `OverrideEditor`, `RoomFilter.Hidden`, `CardUi.areaId`, … do not exist).

- [ ] **Step 7: Implement `AreaNames`**

Create `AreaNames.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import java.text.Normalizer
import java.util.Locale

/** A typed room name → the `Area` it means: an existing room with that name, or a new one with a fresh id. */
object AreaNames {
    data class Resolved(val area: Area, val isNew: Boolean)

    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val NON_ID = Regex("[^a-z0-9]+")

    /** @return null for a blank name, or for "Unassigned", which is reserved (it is what "No room" writes). */
    fun resolve(name: String, existing: List<Area>): Resolved? {
        val clean = name.trim().replace(SPACES, " ")
        if (clean.isEmpty() || clean.equals(UNASSIGNED_AREA, ignoreCase = true)) return null
        existing.firstOrNull { it.id != UNASSIGNED_AREA && it.name.equals(clean, ignoreCase = true) }
            ?.let { return Resolved(it, isNew = false) }
        val ids = existing.mapTo(mutableSetOf()) { it.id }
        val base = slug(clean)
        val id = if (base !in ids) base else generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it !in ids }
        return Resolved(Area(id, clean), isNew = true)
    }

    fun slug(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "")
            .lowercase(Locale.ROOT).replace(NON_ID, "_").trim('_')
            .ifEmpty { "room" }

    /** The `_areas` document after adding [area]. Implicit areas (from suggested_area) become declared ones. */
    fun withArea(existing: List<Area>, area: Area): List<Area> = existing.filter { it.id != UNASSIGNED_AREA } + area
}
```

- [ ] **Step 8: Implement the model changes and `OverrideDraft`**

In `HomeModels.kt`:
- Add to `RoomFilter`, after `Favorites`:

```kotlin
    /** Hidden entities only, so they can be found and unhidden (Settings takes this over later). */
    data object Hidden : RoomFilter {
        override val key = "hidden"
    }
```

- Extend `fromKey`: `key == Hidden.key -> Hidden` before the area branch.
- In `CardUi`, add after `hasSheet`:

```kotlin
    /** Effective area id; [UNASSIGNED_AREA] when the entity has no room. */
    val areaId: String,
    /** The override as it is now, for the override sheet. */
    val ui: UiOverride,
    val hidden: Boolean,
```

- In `HomeUiState`, add after `cards`:

```kotlin
    /** Rooms the override sheet can pick from: every area except Unassigned. */
    val rooms: List<Area> = emptyList(),
    val hiddenCount: Int = 0,
```

- Add `data object SaveFailed : HomeEvent` to `HomeEvent`.
- Add the imports `com.gekkotron.oikos.model.Area` and `com.gekkotron.oikos.model.UiOverride`. The `Area` data class name clashes with `RoomFilter.Area`. Inside `RoomFilter`, `Area` means the nested class. `HomeUiState.rooms` sits outside `RoomFilter`, so the imported model `Area` applies there. Keep it that way.

Create `OverrideDraft.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.UiOverride

/**
 * The override sheet's fields (spec §5.2). Only the fields the user changed replace the card's
 * override. So opening and saving never turns a suggested_area into an explicit area, and a
 * `cardStyle` this app does not know survives on kinds whose sheet does not show it.
 */
data class OverrideDraft(
    val name: String,
    val areaId: String,
    val favorite: Boolean,
    val hidden: Boolean,
    /** Text, blank = automatic. */
    val order: String,
    val cardStyle: String?,
    val energyRole: EnergyRole?,
) {
    val orderValid: Boolean
        get() = order.isBlank() || order.trim().let { t -> t.all(Char::isDigit) && t.toIntOrNull()?.let { it in 0..MAX_ORDER } == true }

    fun applyTo(card: CardUi): UiOverride {
        require(orderValid) { "invalid position '$order'" }
        val start = from(card)
        val ui = card.ui
        val typed = name.trim()
        return ui.copy(
            name = when {
                typed == start.name -> ui.name
                typed.isEmpty() -> null
                else -> typed
            },
            area = if (areaId == start.areaId) ui.area else areaId,
            favorite = favorite,
            hidden = hidden,
            order = if (order.trim() == start.order) ui.order else order.trim().takeIf { it.isNotEmpty() }?.toInt(),
            cardStyle = cardStyle,
            energyRole = energyRole,
        )
    }

    companion object {
        const val MAX_ORDER = 9999

        fun from(card: CardUi) = OverrideDraft(
            name = card.name,
            areaId = card.areaId,
            favorite = card.ui.favorite,
            hidden = card.ui.hidden,
            order = card.ui.order?.toString() ?: "",
            cardStyle = card.ui.cardStyle,
            energyRole = card.ui.energyRole,
        )
    }
}
```

- [ ] **Step 9: Implement `OverrideEditor`**

Create `OverrideEditor.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import kotlinx.coroutines.CancellationException

/**
 * The one feature-side writer of `oikos/ui/…` (spec §4.3), shared by the override sheet and the Rooms
 * step. Every write reports success as a Boolean: offline (PublishFailed, after the source rolled back)
 * and an id that cannot be a topic (IllegalArgumentException) both become false, never a crash.
 */
class OverrideEditor(private val source: EntitySource) {
    suspend fun save(id: String, override: UiOverride): Boolean = attempt { source.setOverride(id, override) }

    /** Moves each entity to [areaId] (an area id, or UNASSIGNED_AREA for "No room"), keeping the rest of its override. */
    suspend fun assignArea(ids: Collection<String>, areaId: String): Boolean {
        for (id in ids) {
            val entity = source.entities.value[id] ?: continue
            if (entity.area == areaId) continue
            if (!save(id, entity.ui.copy(area = areaId))) return false
        }
        return true
    }

    suspend fun addRoom(area: Area): Boolean = attempt { source.setAreas(AreaNames.withArea(source.areas.value, area)) }

    private suspend fun attempt(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
```

- [ ] **Step 10: Implement the builder changes**

In `HomeUiBuilder.build`, replace the body from `val visible = …` down to the `return`:

```kotlin
        val visible = entities.values.filterNot { it.ui.hidden }
        val hidden = entities.values.filter { it.ui.hidden }
        val hero = visible.filter { it.ui.cardStyle == HERO_WEATHER }.minByOrNull { it.uniqueId }
        val grid = visible.filter { it.uniqueId != hero?.uniqueId }

        val used = grid.mapTo(mutableSetOf()) { it.area }
        val chips = buildList {
            add(ChipUi(RoomFilter.All, null))
            if (grid.any { it.ui.favorite }) add(ChipUi(RoomFilter.Favorites, null))
            for (area in areas) {
                if (area.id in used) add(ChipUi(RoomFilter.Area(area.id), area.name.takeUnless { area.id == UNASSIGNED_AREA }))
            }
            if (hidden.isNotEmpty()) add(ChipUi(RoomFilter.Hidden, null))
        }
        val effective = selected.takeIf { s -> chips.any { it.filter == s } } ?: RoomFilter.All

        val shown = if (effective == RoomFilter.Hidden) hidden else grid.filter { matches(it, effective) }
        val cards = shown
            .sortedWith(
                compareBy<Entity>(
                    { it.ui.order ?: Int.MAX_VALUE },
                    { areaIndex[it.area] ?: Int.MAX_VALUE },
                    { it.name.lowercase(locale) },
                    { it.uniqueId },
                ),
            )
            .map { card(it, states[it.uniqueId], areaNames, locale) }

        return HomeUiState(
            loading = entities.isEmpty(),
            link = link,
            hero = hero?.let { card(it, states[it.uniqueId], areaNames, locale) },
            chips = chips,
            selected = effective,
            cards = cards,
            rooms = areas.filter { it.id != UNASSIGNED_AREA },
            hiddenCount = hidden.size,
        )
```

In `matches`, add `RoomFilter.Hidden -> false`. In `card(...)`, add at the end of the `CardUi(...)` call:

```kotlin
            areaId = entity.area,
            ui = entity.ui,
            hidden = entity.ui.hidden,
```

- [ ] **Step 11: Implement the ViewModel actions**

In `HomeViewModel`, add a field and two functions (import `com.gekkotron.oikos.model.UiOverride`):

```kotlin
    private val editor = OverrideEditor(source)

    fun customize(id: String, override: UiOverride) {
        viewModelScope.launch { if (!editor.save(id, override)) _events.send(HomeEvent.SaveFailed) }
    }

    /**
     * @return the id to select for a typed room name: an existing room's (no write), or a new one's,
     * written in the background. It is returned at once, so the sheet can select it before the source
     * has echoed it. The source applies the areas locally first, so a second call sees the first room.
     */
    fun createRoom(name: String): String? {
        val resolved = AreaNames.resolve(name, source.areas.value) ?: return null
        if (resolved.isNew) viewModelScope.launch { if (!editor.addRoom(resolved.area)) _events.send(HomeEvent.SaveFailed) }
        return resolved.area.id
    }
```

- [ ] **Step 12: Keep the UI compiling**

- In `HomeScreen.kt` `HomeChips`, the `when (chip.filter)` needs a branch: `RoomFilter.Hidden -> hidden`, with `val hidden = stringResource(R.string.chip_hidden, state.hiddenCount)` declared next to `all`.
- In `HomeEventsEffect.kt`, add `HomeEvent.SaveFailed -> snackbar.showSnackbar(resources.getString(R.string.home_save_failed))`.
- Add these strings:
  - `values/strings.xml`:
    ```xml
        <string name="chip_hidden">Hidden (%1$d)</string>
        <string name="home_save_failed">Couldn\'t save your changes. Check the connection and try again.</string>
    ```
  - `values-fr/strings.xml`:
    ```xml
        <string name="chip_hidden">Masqués (%1$d)</string>
        <string name="home_save_failed">Impossible d\'enregistrer vos modifications. Vérifiez la connexion et réessayez.</string>
    ```

- [ ] **Step 13: Run the module tests and check that they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS: the new tests and all existing Home tests, including `HomeScreenTest`, which builds its state through `HomeUiBuilder`.

- [ ] **Step 14: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): override editor, room naming, override draft and a Hidden view (EN/FR)" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: The override sheet, long-press routing, and "Assign rooms" in the link sheet

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/RoomPicker.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheet.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Test: create `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheetTest.kt`; modify `HomeScreenTest.kt` and `LinkSheetTest.kt`

**Interfaces:**
- Consumes:
  - Task 3's `OikosCard(onLongClickLabel)`;
  - Task 5's `OverrideDraft`, `CardUi.areaId/ui/hidden`, `HomeUiState.rooms/hiddenCount`, `HomeViewModel.customize/createRoom`.
- Produces:
  - `@Composable fun RoomPicker(rooms: List<Area>, selectedId: String?, onSelect: (String) -> Unit, onCreate: (String) -> String?, modifier: Modifier = Modifier)`. `selectedId` is `UNASSIGNED_AREA` for "No room", or `null` when nothing is selected (the Rooms step's "several rooms" row). `onCreate` returns the new id or null. **Task 7 uses it.**
  - `HomeScreen(…, onCustomize: (String, UiOverride) -> Unit = { _, _ -> }, onCreateRoom: (String) -> String? = { null }, onAssignRooms: () -> Unit = {})`.
  - `HomeRoute(onSetUpAgain: () -> Unit = {}, onAssignRooms: () -> Unit = {}, viewModel)`. Task 8 passes `onAssignRooms`.

- [ ] **Step 1: Add the strings (EN + FR)**

In `values/strings.xml`, before `</resources>`:

```xml
    <string name="card_description_hidden">%1$s, %2$s, %3$s, hidden</string>
    <string name="action_customize">Customize</string>
    <string name="action_controls">Controls</string>
    <string name="sheet_customize">Customize…</string>
    <string name="customize_title">Customize</string>
    <string name="customize_name">Name</string>
    <string name="customize_name_hint">Leave empty to use the device\'s own name.</string>
    <string name="customize_room">Room</string>
    <string name="customize_favorite">Favourite</string>
    <string name="customize_hidden">Hide from Home</string>
    <string name="customize_order">Position</string>
    <string name="customize_order_hint">Lower numbers come first. Leave empty for automatic.</string>
    <string name="customize_order_error">Enter a whole number from 0 to 9999.</string>
    <string name="customize_style">Card style</string>
    <string name="customize_style_standard">Standard</string>
    <string name="customize_style_hero">Header temperature</string>
    <string name="customize_energy">Energy role</string>
    <string name="customize_energy_none">None</string>
    <string name="energy_solar">Solar</string>
    <string name="energy_grid">Grid</string>
    <string name="energy_house">House</string>
    <string name="energy_water_heater">Water heater</string>
    <string name="energy_water">Water</string>
    <string name="customize_save">Save</string>
    <string name="customize_reset">Reset to defaults</string>
    <string name="room_none">No room</string>
    <string name="room_new">New room</string>
    <string name="room_add">Add</string>
    <string name="link_assign_rooms">Assign rooms</string>
```

In `values-fr/strings.xml`, before `</resources>`:

```xml
    <string name="card_description_hidden">%1$s, %2$s, %3$s, masqué</string>
    <string name="action_customize">Personnaliser</string>
    <string name="action_controls">Commandes</string>
    <string name="sheet_customize">Personnaliser…</string>
    <string name="customize_title">Personnaliser</string>
    <string name="customize_name">Nom</string>
    <string name="customize_name_hint">Laissez vide pour utiliser le nom de l\'appareil.</string>
    <string name="customize_room">Pièce</string>
    <string name="customize_favorite">Favori</string>
    <string name="customize_hidden">Masquer de l\'accueil</string>
    <string name="customize_order">Position</string>
    <string name="customize_order_hint">Les plus petits nombres passent en premier. Laissez vide pour l\'ordre automatique.</string>
    <string name="customize_order_error">Saisissez un nombre entier de 0 à 9999.</string>
    <string name="customize_style">Style de carte</string>
    <string name="customize_style_standard">Standard</string>
    <string name="customize_style_hero">Température en en-tête</string>
    <string name="customize_energy">Rôle énergie</string>
    <string name="customize_energy_none">Aucun</string>
    <string name="energy_solar">Solaire</string>
    <string name="energy_grid">Réseau</string>
    <string name="energy_house">Maison</string>
    <string name="energy_water_heater">Chauffe-eau</string>
    <string name="energy_water">Eau</string>
    <string name="customize_save">Enregistrer</string>
    <string name="customize_reset">Rétablir les valeurs par défaut</string>
    <string name="room_none">Aucune pièce</string>
    <string name="room_new">Nouvelle pièce</string>
    <string name="room_add">Ajouter</string>
    <string name="link_assign_rooms">Attribuer les pièces</string>
```

- [ ] **Step 2: Write the failing UI tests**

Create `CustomizeSheetTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
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
class CustomizeSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val rooms = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"))
    private val saved = mutableListOf<UiOverride>()
    private val created = mutableListOf<String>()

    private fun card(entity: Entity): CardUi =
        HomeUiBuilder.build(mapOf(entity.uniqueId to entity), emptyMap(), rooms, RoomFilter.All, LinkState.Connecting, Locale.US).card(entity.uniqueId)!!

    private fun show(card: CardUi, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                CustomizeSheet(card, rooms, onSave = { saved += it }, onCreateRoom = { created += it; "office" }, onDismiss = {})
            }
        }
    }

    @Test
    fun `rename, move and favourite are saved together`() {
        show(card(testEntity("lamp", Kind.LIGHT, "Lamp", area = "living")))
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Ceiling")
        compose.onNodeWithText("Kitchen").performScrollTo().performClick()
        compose.onNodeWithText("Favourite").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf(UiOverride(name = "Ceiling", area = "kitchen", favorite = true)), saved)
    }

    @Test
    fun `no room writes Unassigned`() {
        show(card(testEntity("lamp", Kind.LIGHT, area = "living")))
        compose.onNodeWithText("No room").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UNASSIGNED_AREA, saved.single().area)
    }

    @Test
    fun `a new room is created and selected`() {
        show(card(testEntity("lamp", Kind.LIGHT)))
        compose.onNode(hasSetTextAction() and hasText("New room")).performScrollTo().performTextInput("Office")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("Office"), created)
        assertEquals("office", saved.single().area)
    }

    @Test
    fun `an invalid position blocks saving and says why`() {
        show(card(testEntity("lamp", Kind.LIGHT)))
        compose.onNode(hasSetTextAction() and hasText("Position")).performScrollTo().performTextInput("-3")
        compose.onNodeWithText("Enter a whole number from 0 to 9999.").assertExists()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `sensors offer card style and energy role, switches do not`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power")))
        compose.onNodeWithText("Grid").performScrollTo().performClick()
        compose.onNodeWithText("Header temperature").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(UiOverride(energyRole = EnergyRole.GRID, cardStyle = HomeUiBuilder.HERO_WEATHER), saved.single())
    }

    @Test
    fun `switch sheet has no energy role`() {
        show(card(testEntity("sw", Kind.SWITCH)))
        compose.onNodeWithText("Energy role").assertDoesNotExist()
    }

    @Test
    fun `reset saves the default override`() {
        show(card(testEntity("lamp", Kind.LIGHT, ui = UiOverride(name = "X", hidden = true))))
        compose.onNodeWithText("Reset to defaults").performScrollTo().performClick()
        assertEquals(listOf(UiOverride()), saved)
    }

    @Test
    @Config(qualifiers = "w411dp-h500dp")
    fun `every field stays reachable at 2x font scale`() {
        show(card(testEntity("power", Kind.SENSOR, deviceClass = "power")), fontScale = 2f)
        compose.onNodeWithText("Reset to defaults").performScrollTo().assertExists()
    }
}
```

In `HomeScreenTest`:
- Change `show()` so the extra callbacks are recorded. Add the fields `private val customized = mutableListOf<Pair<String, UiOverride>>()`, and pass `onCustomize = { id, o -> customized += id to o }` to `HomeScreen`.
- Add the imports `androidx.compose.ui.test.longClick`, `androidx.compose.ui.test.performTouchInput`, `androidx.compose.ui.test.hasSetTextAction`, `androidx.compose.ui.test.hasText`, `androidx.compose.ui.test.performTextReplacement`, `com.gekkotron.oikos.model.UiOverride`, `androidx.compose.ui.semantics.SemanticsActions`, `androidx.compose.ui.test.SemanticsMatcher` and `androidx.compose.ui.test.assert`.
- Replace `unavailable card is disabled and says so` with the tests below, and add a sensor entity `testEntity("temp", Kind.SENSOR, "Temperature", area = "living", unit = "°C")` to `entities`:

```kotlin
    @Test
    fun `unavailable card ignores taps and can still be customized`() {
        show()
        compose.onNodeWithTag("card-plug").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).performClick()
        compose.onNodeWithContentDescription("Garage plug, Unavailable, Garage").assertExists()
        assertEquals(emptyList(), taps)
        compose.onNodeWithTag("card-plug").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Hide from Home").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("plug" to UiOverride(hidden = true)), customized)
    }

    @Test
    fun `long-press on a sensor opens the override sheet and saves a rename`() {
        show()
        compose.onNodeWithTag("card-temp").performScrollTo().performTouchInput { longClick() }
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Living temperature")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("temp" to UiOverride(name = "Living temperature")), customized)
    }

    @Test
    fun `long-press on a dimmable light opens its controls, and Customize opens the override sheet`() {
        show()
        compose.onNodeWithTag("card-lamp").performTouchInput { longClick() }
        compose.onNodeWithText("Brightness").assertExists()
        compose.onNodeWithText("Customize…").performScrollTo().performClick()
        compose.onNodeWithText("Hide from Home").assertExists()
        compose.onNodeWithText("Brightness").assertDoesNotExist()
    }

    @Test
    fun `long-press actions are labelled for accessibility`() {
        show()
        assertEquals("Controls", compose.onNodeWithTag("card-lamp").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        compose.onNodeWithTag("card-temp").performScrollTo()
        assertEquals("Customize", compose.onNodeWithTag("card-temp").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    }
```

Then add a Hidden view test with its own state:

```kotlin
    @Test
    fun `hidden chip lists hidden cards, described as hidden`() {
        val withHidden = entities + ("secret" to testEntity("secret", Kind.SWITCH, "Secret switch", area = "kitchen", ui = UiOverride(hidden = true)))
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = HomeUiBuilder.build(withHidden, states, areas, RoomFilter.Hidden, LinkState.Connected(TransportMode.DEMO), Locale.US),
                    onSelect = { selects += it }, onTap = {}, onSend = { _, _ -> }, now = LocalTime.NOON,
                )
            }
        }
        compose.onNodeWithText("Hidden (1)").assertExists()
        // No state was given for "secret", so its value is "—"; only the name and the hidden suffix matter here.
        compose.onNodeWithContentDescription("Secret switch, —, Kitchen, hidden").assertExists()
    }

    @Test
    fun `long-pressing the header temperature opens its override sheet, so the hero style can be undone`() {
        val withHero = entities + ("outdoor" to testEntity("outdoor", Kind.SENSOR, "Outdoor", unit = "°C", ui = UiOverride(cardStyle = HomeUiBuilder.HERO_WEATHER)))
        val customizedHere = mutableListOf<Pair<String, UiOverride>>()
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = HomeUiBuilder.build(withHero, states + ("outdoor" to EntityState("outdoor", StateValue.Numeric(12.0))), areas, RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                    onSelect = {}, onTap = {}, onSend = { _, _ -> }, now = LocalTime.NOON,
                    onCustomize = { id, o -> customizedHere += id to o },
                )
            }
        }
        compose.onNodeWithTag("hero").performTouchInput { longClick() }
        compose.onNodeWithText("Standard").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(listOf("outdoor" to UiOverride()), customizedHere)
    }
```

The pre-existing test `card describes name, state and room` still passes. Check that the temperature sensor's description does not collide with any other assertion in the file. The other tests key on the lamp, shutter, ac, coffee and plug.

Add to `LinkSheetTest`:

```kotlin
    @Test
    fun `assign rooms calls back and closes the sheet`() {
        var called = false
        compose.setContent {
            OikosTheme { HomeScreen(HomeUiState(loading = false, link = LinkState.Connected(TransportMode.DIRECT)), {}, {}, { _, _ -> }, onAssignRooms = { called = true }) }
        }
        compose.onNodeWithText("Wi-Fi direct").performClick()
        compose.onNodeWithText("Assign rooms").performClick()
        assertTrue(called)
        compose.onNodeWithText("Connected directly to your broker.").assertDoesNotExist()
    }
```

- [ ] **Step 3: Run the tests and check that they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*CustomizeSheetTest*' --tests '*HomeScreenTest*' --tests '*LinkSheetTest*'`
Expected: compile errors (`CustomizeSheet`, `onCustomize`, `onAssignRooms` do not exist).

- [ ] **Step 4: Implement `RoomPicker`**

Create `RoomPicker.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA

/**
 * Rooms as radio rows, then "No room", then a "New room" field (spec §5.2). Shared by the override
 * sheet and the Rooms step. [onCreate] returns the id of the room to select, or null if the name was refused.
 */
@Composable
fun RoomPicker(rooms: List<Area>, selectedId: String?, onSelect: (String) -> Unit, onCreate: (String) -> String?, modifier: Modifier = Modifier) {
    var newName by rememberSaveable { mutableStateOf("") }
    val add = {
        onCreate(newName)?.let {
            newName = ""
            onSelect(it)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.selectableGroup()) {
            rooms.forEach { room -> RoomRow(room.name, room.id == selectedId) { onSelect(room.id) } }
            RoomRow(stringResource(R.string.room_none), selectedId == UNASSIGNED_AREA) { onSelect(UNASSIGNED_AREA) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.room_new)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { add() }, enabled = newName.isNotBlank(), modifier = Modifier.heightIn(min = OikosDimens.minTouch)) {
                Text(stringResource(R.string.room_add))
            }
        }
    }
}

@Composable
private fun RoomRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = OikosTheme.type.body)
    }
}
```

- [ ] **Step 5: Implement `CustomizeSheet`**

Create `CustomizeSheet.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.OverrideDraft
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride

/** Long-press override sheet (spec §5.2): rename, room, favourite, hide, position, card style, energy role. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomizeSheet(
    card: CardUi,
    rooms: List<Area>,
    onSave: (UiOverride) -> Unit,
    onCreateRoom: (String) -> String?,
    onDismiss: () -> Unit,
) {
    val start = OverrideDraft.from(card)
    var name by rememberSaveable(card.id) { mutableStateOf(start.name) }
    var areaId by rememberSaveable(card.id) { mutableStateOf(start.areaId) }
    var favorite by rememberSaveable(card.id) { mutableStateOf(start.favorite) }
    var hidden by rememberSaveable(card.id) { mutableStateOf(start.hidden) }
    var order by rememberSaveable(card.id) { mutableStateOf(start.order) }
    var cardStyle by rememberSaveable(card.id) { mutableStateOf(start.cardStyle) }
    var energyRole by rememberSaveable(card.id) { mutableStateOf(start.energyRole) }
    val draft = OverrideDraft(name, areaId, favorite, hidden, order, cardStyle, energyRole)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.customize_title), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.customize_name)) },
                supportingText = { Text(stringResource(R.string.customize_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.customize_room), style = OikosTheme.type.label)
            RoomPicker(rooms, areaId, onSelect = { areaId = it }, onCreate = onCreateRoom)
            SwitchRow(stringResource(R.string.customize_favorite), favorite) { favorite = it }
            SwitchRow(stringResource(R.string.customize_hidden), hidden) { hidden = it }
            OutlinedTextField(
                value = order,
                onValueChange = { order = it },
                label = { Text(stringResource(R.string.customize_order)) },
                supportingText = {
                    Text(stringResource(if (draft.orderValid) R.string.customize_order_hint else R.string.customize_order_error))
                },
                isError = !draft.orderValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (card.kind == Kind.SENSOR) {
                Text(stringResource(R.string.customize_style), style = OikosTheme.type.label)
                ChipRow {
                    FilterChip(selected = cardStyle != HomeUiBuilder.HERO_WEATHER, onClick = { cardStyle = null }, label = { Text(stringResource(R.string.customize_style_standard)) })
                    FilterChip(selected = cardStyle == HomeUiBuilder.HERO_WEATHER, onClick = { cardStyle = HomeUiBuilder.HERO_WEATHER }, label = { Text(stringResource(R.string.customize_style_hero)) })
                }
                Text(stringResource(R.string.customize_energy), style = OikosTheme.type.label)
                ChipRow {
                    FilterChip(selected = energyRole == null, onClick = { energyRole = null }, label = { Text(stringResource(R.string.customize_energy_none)) })
                    EnergyRole.entries.forEach { role ->
                        FilterChip(selected = energyRole == role, onClick = { energyRole = role }, label = { Text(energyLabel(role)) })
                    }
                }
            }
            Button(
                onClick = { onSave(draft.applyTo(card)); onDismiss() },
                enabled = draft.orderValid,
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.customize_save)) }
            TextButton(onClick = { onSave(UiOverride()); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.customize_reset))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OikosTheme.type.body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun energyLabel(role: EnergyRole): String = stringResource(
    when (role) {
        EnergyRole.SOLAR -> R.string.energy_solar
        EnergyRole.GRID -> R.string.energy_grid
        EnergyRole.HOUSE -> R.string.energy_house
        EnergyRole.WATER_HEATER -> R.string.energy_water_heater
        EnergyRole.WATER -> R.string.energy_water
    },
)
```

`EnergyRole` is a Kotlin enum, so it is `java.io.Serializable`. `rememberSaveable` can store it and a nullable `String` without a custom `Saver`.

- [ ] **Step 6: Add "Customize…" to `ControlSheet`**

Change the signature to `internal fun ControlSheet(card: CardUi, onSend: (Command) -> Unit, onCustomize: () -> Unit, onDismiss: () -> Unit)`. After the `when (card.kind) { … }` block inside the `Column`, add:

```kotlin
            TextButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sheet_customize)) }
```

Add `import androidx.compose.material3.TextButton`.

- [ ] **Step 7: Update `EntityCard`**

Replace the function with the version below. The tap runs only when the device is available and the card has a tap action. The long-press is always there, with a label.

```kotlin
@Composable
internal fun EntityCard(card: CardUi, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val valueText = if (card.available) cardValueText(card.value) else stringResource(R.string.value_unavailable)
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val description = stringResource(if (card.hidden) R.string.card_description_hidden else R.string.card_description, card.name, valueText, room)
    val onText = stringResource(R.string.value_on)
    val offText = stringResource(R.string.value_off)
    val toggle = card.tap == TapAction.TOGGLE
    OikosCard(
        modifier = modifier
            .testTag("card-${card.id}")
            .alpha(if (card.hidden) 0.6f else 1f)
            .semantics {
                contentDescription = description
                if (toggle) stateDescription = if (card.on) onText else offText
            },
        on = card.on,
        enabled = card.available,
        onClick = if (card.tap != TapAction.NONE) onClick else null,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(if (card.hasSheet) R.string.action_controls else R.string.action_customize),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kindGlyph(card.kind, card.deviceClass), fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics { })
            Spacer(Modifier.weight(1f))
            if (card.pending) PendingDot()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            card.name,
            style = OikosTheme.type.cardName,
            color = LocalContentColor.current.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(valueText, style = OikosTheme.type.cardValue)
    }
}
```

Add `import androidx.compose.ui.draw.alpha`.

- [ ] **Step 8: Update `HomeScreen`, `HomeRoute` and `LinkSheet`**

In `HomeRoute`:

```kotlin
@Composable
fun HomeRoute(onSetUpAgain: () -> Unit = {}, onAssignRooms: () -> Unit = {}, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    HomeEventsEffect(viewModel.events, snackbar, onRetry = viewModel::send)
    HomeScreen(
        state, viewModel::select, viewModel::tap, viewModel::send, snackbar,
        onSetUpAgain = onSetUpAgain,
        onCustomize = viewModel::customize,
        onCreateRoom = viewModel::createRoom,
        onAssignRooms = onAssignRooms,
    )
}
```

In `HomeScreen`:
- Add the parameters after `onSetUpAgain`: `onCustomize: (String, UiOverride) -> Unit = { _, _ -> }`, `onCreateRoom: (String) -> String? = { null }`, `onAssignRooms: () -> Unit = {}`.
- Add the state `var customizeId by rememberSaveable { mutableStateOf<String?>(null) }` next to `sheetId`.
- Change the card item:

```kotlin
                    EntityCard(
                        card = card,
                        onClick = { if (card.tap == TapAction.OPEN_SHEET) sheetId = card.id else onTap(card) },
                        onLongClick = { if (card.hasSheet) sheetId = card.id else customizeId = card.id },
                    )
```

- Replace the sheet block at the end:

```kotlin
    val sheetCard = sheetId?.let(state::card)
    if (sheetCard != null) {
        ControlSheet(
            sheetCard,
            onSend = { onSend(sheetCard.id, it) },
            onCustomize = { sheetId = null; customizeId = sheetCard.id },
            onDismiss = { sheetId = null },
        )
    }
    val customizeCard = customizeId?.let(state::card)
    if (customizeCard != null) {
        CustomizeSheet(
            customizeCard,
            state.rooms,
            onSave = { onCustomize(customizeCard.id, it) },
            onCreateRoom = onCreateRoom,
            onDismiss = { customizeId = null },
        )
    }
    if (linkSheet) {
        LinkSheet(
            state.link,
            onSetUpAgain = { linkSheet = false; onSetUpAgain() },
            onAssignRooms = { linkSheet = false; onAssignRooms() },
            onDismiss = { linkSheet = false },
        )
    }
```

The hero (header temperature) is not in the grid, so without a long-press there, its `hero_weather` style could never be undone. Give `HomeHeader` an `onHeroLongClick: () -> Unit` parameter and pass `onHeroLongClick = { state.hero?.let { customizeId = it.id } }` from the header item. Replace the hero text with:

```kotlin
            state.hero?.let { hero ->
                Text(
                    cardValueText(hero.value),
                    style = OikosTheme.type.display,
                    modifier = Modifier
                        .testTag("hero")
                        .combinedClickable(onClick = {}, onLongClick = onHeroLongClick, onLongClickLabel = stringResource(R.string.action_customize)),
                )
            }
```

Add `import com.gekkotron.oikos.model.UiOverride` and `import androidx.compose.foundation.combinedClickable`.

Note: `state.card(id)` looks in `cards`, then `hero`. After "Hide from Home" is saved, the card leaves `cards`. `customizeCard` then becomes null and the sheet closes by itself, which is the intended behaviour.

In `LinkSheet`, add the `onAssignRooms: () -> Unit` parameter before `onDismiss`. Add a second button above "Set up connection again":

```kotlin
            OutlinedButton(onClick = onAssignRooms, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.link_assign_rooms)) }
```

- [ ] **Step 9: Run the module tests and check that they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS. If `control sheet scrolls to its last controls at 2x font scale` now fails because "Customize…" is the last node, that test still asserts on its own last control. Leave it as it is, because the sheet scrolls.

- [ ] **Step 10: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): long-press override sheet with rooms, hide, favourite, position, style and energy role; Assign rooms (EN/FR)" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: The Rooms screen

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsModels.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsUiBuilder.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsViewModel.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsScreen.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Test: create `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/rooms/RoomsUiBuilderTest.kt`, `RoomsViewModelTest.kt` and `RoomsScreenTest.kt`

**Interfaces:**
- Consumes: Task 5's `OverrideEditor`, `AreaNames`, `DefaultDispatcher`; Task 6's `RoomPicker`.
- Produces: `@Composable fun RoomsRoute(onDone: () -> Unit, viewModel: RoomsViewModel = hiltViewModel())`. Task 8 uses it.

- [ ] **Step 1: Add the strings (EN + FR)**

In `values/strings.xml`:

```xml
    <string name="rooms_title">Which room is each device in?</string>
    <string name="rooms_subtitle">Rooms come from the devices where they say so. You can change any of this later by long-pressing a card.</string>
    <string name="rooms_progress">%1$d of %2$d devices have a room</string>
    <string name="rooms_several">Several rooms</string>
    <string name="rooms_entities">%1$d entities</string>
    <string name="rooms_skip">Skip</string>
    <string name="rooms_done">Done</string>
    <string name="rooms_pick_title">Room for %1$s</string>
```

In `values-fr/strings.xml`:

```xml
    <string name="rooms_title">Dans quelle pièce est chaque appareil ?</string>
    <string name="rooms_subtitle">Les pièces viennent des appareils qui les indiquent. Vous pourrez tout modifier plus tard par un appui long sur une carte.</string>
    <string name="rooms_progress">%1$d appareils sur %2$d ont une pièce</string>
    <string name="rooms_several">Plusieurs pièces</string>
    <string name="rooms_entities">%1$d entités</string>
    <string name="rooms_skip">Passer</string>
    <string name="rooms_done">Terminé</string>
    <string name="rooms_pick_title">Pièce de %1$s</string>
```

- [ ] **Step 2: Write the failing builder tests**

Create `RoomsUiBuilderTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.rooms

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomsUiBuilderTest {
    private val areas = listOf(Area("kitchen", "Kitchen"), Area("living", "Living room"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
    private fun device(id: String, name: String) = Device(id, name, null, null, null)

    @Test
    fun `one row per device, unassigned first, then by name`() {
        val s = RoomsUiBuilder.build(
            entities = listOf(
                testEntity("kettle_power", Kind.SENSOR, deviceId = "kettle", area = "kitchen"),
                testEntity("kettle_switch", Kind.SWITCH, deviceId = "kettle", area = "kitchen"),
                testEntity("plug", Kind.SWITCH, deviceId = "plug"),
                testEntity("aqara", Kind.BINARY_SENSOR, deviceId = "aqara", ui = UiOverride(hidden = true)),
            ).associateBy { it.uniqueId },
            devices = listOf(device("kettle", "Kettle"), device("plug", "Plug"), device("aqara", "Aqara motion")).associateBy { it.id },
            areas = areas,
            locale = Locale.US,
        )
        assertEquals(listOf("aqara", "plug", "kettle"), s.rows.map { it.id })
        assertEquals(listOf("kettle_power", "kettle_switch"), s.rows.last().entityIds)
        assertEquals("kitchen", s.rows.last().areaId)
        assertEquals("Kitchen", s.rows.last().areaName)
        assertEquals(1, s.assigned)
        assertEquals(listOf("kitchen", "living"), s.rooms.map { it.id })
    }

    @Test
    fun `a device split across rooms is marked as several rooms and counts as assigned`() {
        val s = RoomsUiBuilder.build(
            mapOf(
                "a" to testEntity("a", Kind.SWITCH, deviceId = "d", area = "kitchen"),
                "b" to testEntity("b", Kind.SWITCH, deviceId = "d", area = "living"),
            ),
            mapOf("d" to device("d", "Dual relay")),
            areas, Locale.US,
        )
        assertEquals(null, s.rows.single().areaId)
        assertEquals(1, s.assigned)
    }

    @Test
    fun `entities without a known device get their own row`() {
        val s = RoomsUiBuilder.build(
            mapOf("x" to testEntity("x", Kind.SWITCH, "Loose switch"), "y" to testEntity("y", Kind.SWITCH, deviceId = "gone")),
            emptyMap(), areas, Locale.US,
        )
        assertEquals(setOf("entity:x", "entity:y"), s.rows.map { it.id }.toSet())
        assertTrue(s.rows.any { it.name == "Loose switch" })
    }

    @Test
    fun `no entities is loading`() {
        assertTrue(RoomsUiBuilder.build(emptyMap(), emptyMap(), emptyList(), Locale.US).loading)
    }
}
```

- [ ] **Step 3: Write the failing ViewModel tests**

Create `RoomsViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.rooms

import androidx.lifecycle.SavedStateHandle
import com.gekkotron.oikos.feature.home.FakeEntitySource
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Kind
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        entities.value = listOf(
            testEntity("k1", Kind.SWITCH, deviceId = "kettle"),
            testEntity("k2", Kind.SENSOR, deviceId = "kettle", ui = UiOverride(favorite = true)),
        ).associateBy { it.uniqueId }
        devices.value = mapOf("kettle" to Device("kettle", "Kettle", null, null, null))
        areas.value = listOf(Area("kitchen", "Kitchen"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `assigning a row writes every entity of the device and marks the screen changed`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = RoomsViewModel(source, handle, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.changed)
        vm.assign("kettle", "kitchen")
        assertEquals(mapOf("k1" to UiOverride(area = "kitchen"), "k2" to UiOverride(favorite = true, area = "kitchen")), source.overrides)
        assertTrue(vm.state.value.changed)
        val recreated = RoomsViewModel(source, handle, dispatcher)
        backgroundScope.launch { recreated.state.collect {} }
        assertTrue(recreated.state.value.changed)
    }

    @Test
    fun `a failed assignment emits SaveFailed and does not mark the screen changed`() = runTest(dispatcher) {
        source.failWrites = true
        val vm = RoomsViewModel(source, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.assign("kettle", "kitchen")
        assertEquals(RoomsEvent.SaveFailed, vm.events.first())
        assertFalse(vm.state.value.changed)
    }

    @Test
    fun `createRoom reuses or writes like Home`() = runTest(dispatcher) {
        val vm = RoomsViewModel(source, SavedStateHandle(), dispatcher)
        assertEquals("kitchen", vm.createRoom("Kitchen"))
        assertEquals("garage", vm.createRoom("Garage"))
        assertEquals(listOf(listOf(Area("kitchen", "Kitchen"), Area("garage", "Garage"))), source.areaWrites)
    }
}
```

- [ ] **Step 4: Write the failing screen tests**

Create `RoomsScreenTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.rooms

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RoomsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val assigned = mutableListOf<Pair<String, String>>()
    private var done = false

    private fun state(changed: Boolean = false) = RoomsUiState(
        loading = false,
        rows = listOf(
            RoomRowUi("plug", "Plug", listOf("p"), UNASSIGNED_AREA, null),
            RoomRowUi("relay", "Dual relay", listOf("a", "b"), null, null),
            RoomRowUi("kettle", "Kettle", listOf("k"), "kitchen", "Kitchen"),
        ),
        rooms = listOf(Area("kitchen", "Kitchen")),
        assigned = 2,
        changed = changed,
    )

    private fun show(s: RoomsUiState, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                RoomsScreen(s, onAssign = { id, area -> assigned += id to area }, onCreateRoom = { null }, onDone = { done = true })
            }
        }
    }

    @Test
    fun `rows show their room, and Skip finishes when nothing changed`() {
        show(state())
        compose.onNodeWithText("2 of 3 devices have a room").assertExists()
        compose.onNodeWithText("No room").assertExists()
        compose.onNodeWithText("Several rooms").assertExists()
        compose.onNodeWithText("Skip").performScrollTo().performClick()
        assertTrue(done)
    }

    @Test
    fun `choosing a room in the picker assigns the row`() {
        show(state())
        compose.onNodeWithText("Plug").performClick()
        compose.onNodeWithText("Room for Plug").assertExists()
        // "Kitchen" is both the kettle row's room and a picker option; the sheet is composed last.
        compose.onAllNodesWithText("Kitchen").onLast().performClick()
        assertEquals(listOf("plug" to "kitchen"), assigned)
        compose.onNodeWithText("Room for Plug").assertDoesNotExist()
    }

    @Test
    fun `after a change the button says Done`() {
        show(state(changed = true))
        compose.onNodeWithText("Done").assertExists()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h914dp")
    fun `the screen is translated`() {
        show(state())
        compose.onNodeWithText("Dans quelle pièce est chaque appareil ?").assertExists()
        compose.onNodeWithText("Passer").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h500dp")
    fun `the finish button stays reachable at 2x font scale`() {
        show(state(), fontScale = 2f)
        // A lazy item may not be composed yet, so scroll the list to it rather than performScrollTo.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Skip"))
        compose.onNodeWithText("Skip").assertExists()
    }
}
```

Add the imports `androidx.compose.ui.test.onAllNodesWithText`, `androidx.compose.ui.test.onLast`, `androidx.compose.ui.test.hasScrollAction`, `androidx.compose.ui.test.hasText` and `androidx.compose.ui.test.performScrollToNode`.

- [ ] **Step 5: Run the tests and check that they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*rooms*'`
Expected: compile errors (the `rooms` package does not exist).

- [ ] **Step 6: Implement the models and the builder**

Create `RoomsModels.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.rooms

import com.gekkotron.oikos.model.Area

/**
 * One device (or one entity without a known device).
 * [areaId]: the room its entities share; UNASSIGNED_AREA for none; null when they are split across rooms.
 * [areaName]: the room's display name, or null when there is none to show.
 */
data class RoomRowUi(
    val id: String,
    val name: String,
    val entityIds: List<String>,
    val areaId: String?,
    val areaName: String?,
)

data class RoomsUiState(
    val loading: Boolean = true,
    val rows: List<RoomRowUi> = emptyList(),
    /** Rooms the picker offers: every area except Unassigned. */
    val rooms: List<Area> = emptyList(),
    /** Rows that have a room (split rows count). */
    val assigned: Int = 0,
    /** Something was assigned on this screen: the button says Done rather than Skip. */
    val changed: Boolean = false,
)

sealed interface RoomsEvent {
    data object SaveFailed : RoomsEvent
}
```

Create `RoomsUiBuilder.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.rooms

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import java.util.Locale

/** Entities + devices + areas → one row per device, the ones without a room first (spec §5.2 step 5). */
object RoomsUiBuilder {
    private const val ENTITY_ROW = "entity:"

    fun build(entities: Map<String, Entity>, devices: Map<String, Device>, areas: List<Area>, locale: Locale): RoomsUiState {
        val names = areas.associate { it.id to it.name }
        val rows = entities.values
            .groupBy { e -> e.deviceId?.takeIf { it in devices } ?: (ENTITY_ROW + e.uniqueId) }
            .map { (rowId, members) ->
                val sorted = members.sortedBy { it.uniqueId }
                val rooms = sorted.mapTo(linkedSetOf()) { it.area }
                val areaId = rooms.singleOrNull()
                RoomRowUi(
                    id = rowId,
                    name = devices[rowId]?.name ?: sorted.first().name,
                    entityIds = sorted.map { it.uniqueId },
                    areaId = areaId,
                    areaName = areaId?.takeIf { it != UNASSIGNED_AREA }?.let { names[it] ?: it },
                )
            }
            .sortedWith(compareBy<RoomRowUi>({ it.areaId != UNASSIGNED_AREA }, { it.name.lowercase(locale) }, { it.id }))
        return RoomsUiState(
            loading = entities.isEmpty(),
            rows = rows,
            rooms = areas.filter { it.id != UNASSIGNED_AREA },
            assigned = rows.count { it.areaId != UNASSIGNED_AREA },
        )
    }
}
```

- [ ] **Step 7: Implement the ViewModel**

Create `RoomsViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.rooms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.feature.home.presentation.AreaNames
import com.gekkotron.oikos.feature.home.presentation.DefaultDispatcher
import com.gekkotron.oikos.feature.home.presentation.OverrideEditor
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.util.Locale
import javax.inject.Inject

/** The Rooms step (spec §5.2 step 5): runs on the live source, so every choice is written at once. */
@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val source: EntitySource,
    private val savedState: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val editor = OverrideEditor(source)

    val state: StateFlow<RoomsUiState> = combine(
        source.entities,
        source.devices,
        source.areas,
        savedState.getStateFlow(KEY_CHANGED, false),
    ) { entities, devices, areas, changed ->
        RoomsUiBuilder.build(entities, devices, areas, Locale.getDefault()).copy(changed = changed)
    }.conflate().flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoomsUiState())

    private val _events = Channel<RoomsEvent>(Channel.BUFFERED)
    val events: Flow<RoomsEvent> = _events.receiveAsFlow()

    /** [areaId] is an area id, or UNASSIGNED_AREA for "No room". */
    fun assign(rowId: String, areaId: String) {
        val ids = state.value.rows.firstOrNull { it.id == rowId }?.entityIds ?: return
        viewModelScope.launch {
            if (editor.assignArea(ids, areaId)) savedState[KEY_CHANGED] = true else _events.send(RoomsEvent.SaveFailed)
        }
    }

    fun createRoom(name: String): String? {
        val resolved = AreaNames.resolve(name, source.areas.value) ?: return null
        if (resolved.isNew) viewModelScope.launch { if (!editor.addRoom(resolved.area)) _events.send(RoomsEvent.SaveFailed) }
        return resolved.area.id
    }

    private companion object {
        const val KEY_CHANGED = "rooms.changed"
    }
}
```

- [ ] **Step 8: Implement the screen**

Create `RoomsScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.ui.RoomPicker
import com.gekkotron.oikos.model.UNASSIGNED_AREA

@Composable
fun RoomsRoute(onDone: () -> Unit, viewModel: RoomsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { snackbar.showSnackbar(resources.getString(R.string.home_save_failed)) }
    }
    RoomsScreen(state, viewModel::assign, viewModel::createRoom, onDone, snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    state: RoomsUiState,
    onAssign: (String, String) -> Unit,
    onCreateRoom: (String) -> String?,
    onDone: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var pickingId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rooms_title), style = OikosTheme.type.display.copy(fontSize = 26.sp))
                    Text(stringResource(R.string.rooms_subtitle), style = OikosTheme.type.body)
                    if (state.loading) {
                        Text(stringResource(R.string.home_discovering), style = OikosTheme.type.body)
                    } else {
                        Text(stringResource(R.string.rooms_progress, state.assigned, state.rows.size), style = OikosTheme.type.label)
                    }
                }
            }
            items(state.rows, key = { it.id }) { row -> RoomRow(row) { pickingId = row.id } }
            item(key = "finish") {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = OikosDimens.minTouch)) {
                    Text(stringResource(if (state.changed) R.string.rooms_done else R.string.rooms_skip))
                }
            }
        }
    }
    val picking = pickingId?.let { id -> state.rows.firstOrNull { it.id == id } }
    if (picking != null) {
        ModalBottomSheet(onDismissRequest = { pickingId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.rooms_pick_title, picking.name), style = OikosTheme.type.display.copy(fontSize = 22.sp))
                RoomPicker(
                    rooms = state.rooms,
                    selectedId = picking.areaId,
                    onSelect = { onAssign(picking.id, it); pickingId = null },
                    onCreate = onCreateRoom,
                )
            }
        }
    }
}

@Composable
private fun RoomRow(row: RoomRowUi, onClick: () -> Unit) {
    val room = when (row.areaId) {
        null -> stringResource(R.string.rooms_several)
        UNASSIGNED_AREA -> stringResource(R.string.room_none)
        else -> row.areaName ?: row.areaId
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = OikosTheme.type.body)
            if (row.entityIds.size > 1) Text(stringResource(R.string.rooms_entities, row.entityIds.size), style = OikosTheme.type.label)
        }
        Spacer(Modifier.width(12.dp))
        Text(room, style = OikosTheme.type.label)
    }
}
```

- [ ] **Step 9: Run the module tests and check that they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): Rooms step, one row per device, pre-filled from suggested_area, skippable (EN/FR)" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `:app` wiring: Rooms before Home after a new connection, and from the link sheet

**Files:**
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt`

**Interfaces:**
- Consumes:
  - Task 4's `ProfileState.Ready.roomsPending` and `ProfileRepository.finishRooms()`;
  - Task 6's `HomeRoute(onAssignRooms)`;
  - Task 7's `RoomsRoute(onDone)`.

- [ ] **Step 1: Write the failing end-to-end tests**

In `MainActivityTest`, add the imports `androidx.compose.ui.test.hasScrollAction` and `androidx.compose.ui.test.performScrollToNode`. Add a helper that starts a broker with one retained config and walks onboarding up to Continue, from the Welcome screen:

```kotlin
    private val kettle = """{"name":"Kettle","unique_id":"kettle","command_topic":"k/set","state_topic":"k/state",
        "device":{"identifiers":["kettle"],"name":"Kitchen kettle"}}"""

    private fun connectToBroker(config: String) {
        val broker = EmbeddedBroker().also { it.start(); brokers += it }
        val peer = broker.peer()
        peer.publishWith().topic("homeassistant/switch/kettle/config").payload(config.encodeToByteArray())
            .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
        peer.disconnect()
        compose.waitUntilAtLeastOneExists(hasText("Enter manually"), timeoutMillis = 10_000)
        compose.onNodeWithText("Enter manually").performClick()
        compose.onNode(hasSetTextAction() and hasText("Host")).performTextInput("127.0.0.1")
        compose.onNode(hasSetTextAction() and hasText("Port")).performTextReplacement(broker.port.toString())
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Kitchen kettle"), timeoutMillis = 10_000)
        compose.onNodeWithText("Continue").performScrollTo().performClick()
    }

    /** The Rooms list is lazy: its last item may not be composed until the list scrolls to it. */
    private fun finishRooms(label: String) {
        compose.waitUntilAtLeastOneExists(hasText("Which room is each device in?"), timeoutMillis = 10_000)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }
```

Replace the test `after the demo, a direct broker shows only its own house` with a version that uses the helper and passes through the Rooms step:

```kotlin
    @Test
    fun `after the demo, a direct broker shows only its own house`() {
        openDemo()
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Set up connection again").performClick()
        connectToBroker(kettle)
        finishRooms("Skip")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Kettle", substring = true), timeoutMillis = 10_000)
        compose.onAllNodes(hasContentDescription("Floor lamp", substring = true)).assertCountEquals(0)
    }
```

Add:

```kotlin
    @Test
    fun `a new connection asks for rooms, and a room created there shows on Home`() {
        connectToBroker(kettle)
        compose.waitUntilAtLeastOneExists(hasText("Which room is each device in?"), timeoutMillis = 10_000)
        compose.onNodeWithText("Kitchen kettle").performClick()
        compose.onNode(hasSetTextAction() and hasText("New room")).performTextInput("Kitchen")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Done"), timeoutMillis = 10_000)
        finishRooms("Done")
        compose.waitUntilAtLeastOneExists(
            hasContentDescription("Kettle", substring = true) and hasContentDescription("Kitchen", substring = true),
            timeoutMillis = 10_000,
        )
        compose.onNodeWithText("Kitchen").assertExists()
    }

    @Test
    fun `the demo goes straight to Home, and Assign rooms opens the step and returns`() {
        openDemo()
        compose.onNodeWithText("Which room is each device in?").assertDoesNotExist()
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("Assign rooms").performClick()
        finishRooms("Skip")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Floor lamp", substring = true), timeoutMillis = 10_000)
    }
```

- [ ] **Step 2: Run the tests and check that they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*MainActivityTest*'`
Expected: the direct-broker tests FAIL because the Rooms screen is not shown. The demo test fails on "Assign rooms" having no effect.

- [ ] **Step 3: Implement**

In `AppViewModel`, add:

```kotlin
    /** The Rooms step was finished or skipped (a no-op when it was opened from Home). */
    fun finishRooms() {
        viewModelScope.launch { profiles.finishRooms() }
    }
```

In `OikosNavHost.kt`, add the key:

```kotlin
@Serializable
data object RoomsKey : NavKey
```

Then change the root choice and the entries:

```kotlin
    val root: NavKey = when (val p = profile) {
        ProfileState.Loading -> return // a few milliseconds at launch; the background is already drawn
        // A direct profile that was just set up first gets the Rooms step (spec §5.2 step 5), on the live source.
        is ProfileState.Ready -> if (p.roomsPending) RoomsKey else HomeKey
        ProfileState.None, ProfileState.Locked -> OnboardingKey
    }
```

```kotlin
        entryProvider = entryProvider {
            entry<OnboardingKey> { OnboardingRoute() }
            entry<RoomsKey> {
                RoomsRoute(onDone = {
                    // First run: finishing flips the root to Home, which replaces this entry. From Home: pop back.
                    viewModel.finishRooms()
                    if (backStack.size > 1) backStack.removeLastOrNull()
                })
            }
            entry<HomeKey> {
                HomeRoute(
                    onSetUpAgain = viewModel::setUpAgain,
                    onAssignRooms = { if (backStack.lastOrNull() != RoomsKey) backStack.add(RoomsKey) },
                )
            }
        },
```

Add `import com.gekkotron.oikos.feature.home.rooms.RoomsRoute`.

The existing `LaunchedEffect(root)` replaces the stack only when `root` changes, so a `RoomsKey` pushed over Home stays until it pops itself. On first run the stack is `[RoomsKey]`. `finishRooms()` then makes the root `HomeKey`, and the effect replaces `[RoomsKey]` with `[HomeKey]`, which clears the Rooms ViewModel. Rooms and Home both call the unscoped `EntitySource` provider. `EntitySourceSession.current` keys on `profile` alone, not on `roomsPending`, so both screens get the same source and nothing is rediscovered.

- [ ] **Step 4: Run the whole build**

Run: `./gradlew check :app:assembleDebug`
Expected: PASS: every module's tests, lint and the debug APK.

- [ ] **Step 5: Check on the real house (manual, with the user)**

Install the debug build on the phone that already has the real profile: `./gradlew :app:installDebug`. That profile predates `roomsPending`, so it opens on Home. Then check:
1. Home cards show short names (`lampe`, not `rooms/julien/lampe`), and the room chips come from the Z2M paths (`Julien`, …) before anything is assigned.
2. Link pill → "Assign rooms" → most rows are already filled in from the paths; rows without a room come first. Assign one device to a new room, then tap Done. Home shows the new chip.
3. `mosquitto_sub -h <broker> -t 'oikos/ui/#' -v -C 5` shows the retained override(s) and `oikos/ui/_areas`.
4. Long-press a sensor, rename it and Save. The card updates. Open the sheet again and tap "Reset to defaults". The retained topic disappears (`mosquitto_sub` no longer shows it at the next subscribe).
5. Long-press a dimmable light. The brightness sheet opens; "Customize…" leads to the override sheet.
6. Hide a device. The "Hidden (1)" chip appears; unhide the device from there.
7. Turn Wi-Fi off, then rename something. A "Couldn't save" snackbar appears and the old name stays.

- [ ] **Step 6: Commit**

```bash
git add app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(app): show the Rooms step after a new connection and from the link sheet" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Out of scope (goes to later plans)

- Renaming, reordering, re-iconing and deleting rooms (Settings plan).
- A Settings "Hidden entities" list; the Hidden chip is the stand-in until then.
- Choosing which entity feeds which energy node beyond the per-entity role. If two entities share a role, the Energy plan decides (spec §5.2 Energy).
- Room icons (`Area.icon`) in the picker.
