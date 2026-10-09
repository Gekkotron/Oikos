# Room Groups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fold the shutters (covers) and the lights of each room into one wide group card on Home, with group controls, a group sheet, and Customize support.

**Architecture:** A pure `RoomGroups` step in `:feature:home` presentation folds sorted `CardUi`s into group `CardUi`s (with `members`) after filtering. `HomeViewModel.sendGroup` sends one command to every capable, available member in parallel through the existing `EntitySource.send`, and merges failures into one event. Settings ride on existing retained documents: `UiOverride.ungrouped` (per device) and `Area.groupNames` (per room and kind).

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlinx.serialization, kotlinx.coroutines, JUnit4 + Robolectric + Compose UI test, Hilt.

**Spec:** `docs/superpowers/specs/2026-10-09-oikos-room-groups-design.md`

## Global Constraints

- Group kinds: `Kind.COVER` and `Kind.LIGHT` only. Kind key in ids and `groupNames`: `kind.name.lowercase()` → `"cover"`, `"light"`.
- Group card id: `"group:<areaId>:<kindKey>"`.
- A group needs **at least 2 members**; `UNASSIGNED_AREA` is never grouped.
- Groups only in the `All` and `Area` filters, never in Favourites, Hidden or search.
- Every new string in `feature/home/src/main/res/values/strings.xml` **and** `values-fr/strings.xml`.
- Commit identity on every commit: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`. Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN
  ```
- Work on branch `feat/room-groups` (created from `master` at the start of Task 1).
- Module test command: `./gradlew :feature:home:testDebugUnitTest` (add `--tests '<FQN>'` to narrow). Full gate: `./gradlew check`.
- Android ICU regex: escape literal `]` and `}` in any `Regex` (none are planned).

## Review Focus

1. **A room whose second shutter becomes hidden or is moved to another room.** The group must dissolve into one single card at once, with no empty or one-member group card. Pinned by Task 1, test `a group dissolves below two members`.
2. **"All to 50 %" on a room where one shutter has no position.** Only the positionable shutters may receive `SetPosition`; the other must get nothing (not `Open`). Pinned by Task 2, test `position goes only to members that support it`.
3. **A group send while one member is offline.** The offline member is skipped and the others still move, with no "Rejected" or "Not confirmed" for the skipped one. Pinned by Task 2, test `unavailable members are skipped`.
4. **Two members time out on the same group send.** One snackbar, not two, and Retry goes to those two only. Pinned by Task 2, test `failures of one group send merge into one event`.
5. **Renaming a group in an implicit room (one that only exists through `suggested_area`).** The rename must declare the room with its name and keep every other room. Pinned by Task 5, test `setGroupName declares an implicit room and keeps the others`.

---

### Task 1: Model fields and `RoomGroups` folding

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/UiOverride.kt` (add field)
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Area.kt`
- Modify: `core/model/src/test/kotlin/com/gekkotron/oikos/model/UiOverrideTest.kt`
- Create: `core/model/src/test/kotlin/com/gekkotron/oikos/model/AreaTest.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/RoomGroups.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt`
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/RoomGroupsTest.kt`
- Modify (fixtures only, if they break): existing tests under `feature/home/src/test/…`

**Interfaces:**
- Produces:
  - `UiOverride.ungrouped: Boolean = false`
  - `Area.groupNames: Map<String, String> = emptyMap()`
  - `CardValue.CoverGroup(open: Int, total: Int, moving: CoverState?, positions: List<Int>)`
  - `CardValue.LightGroup(onCount: Int, total: Int)`
  - `CardUi.members: List<CardUi> = emptyList()`, `CardUi.groupable: Boolean = false`, `CardUi.isGroup: Boolean` (getter)
  - `RoomGroups.GROUP_KINDS: Set<Kind>`, `RoomGroups.kindKey(kind: Kind): String`, `RoomGroups.groupId(areaId: String, kind: Kind): String`, `RoomGroups.fold(cards: List<CardUi>, areas: List<Area>): List<CardUi>`, `RoomGroups.groupableIds(entities: List<Entity>): Set<String>`
  - `HomeUiState.card(id)` also finds group members (and their companions).
  - Group `CardUi.name` is the custom name, or `""` meaning "localized default".

- [ ] **Step 0: Branch**

```bash
git switch -c feat/room-groups master
```

- [ ] **Step 1: Write the failing model tests**

Append to `UiOverrideTest`:

```kotlin
    @Test
    fun `ungrouped round-trips and defaults to false`() {
        assertEquals(UiOverride(ungrouped = true), UiOverrides.decode(UiOverrides.encode(UiOverride(ungrouped = true))))
        assertFalse(UiOverrides.decode("""{"name":"X"}""")!!.ungrouped)
    }
```

Create `AreaTest.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AreaTest {
    @Test
    fun `group names round-trip and an old document decodes with none`() {
        val doc = AreasDocument(listOf(Area("living", "Living room", groupNames = mapOf("cover" to "Volets baie"))))
        val json = OikosJson.encodeToString(AreasDocument.serializer(), doc)
        assertEquals(doc, OikosJson.decodeFromString(AreasDocument.serializer(), json))
        val old = OikosJson.decodeFromString(AreasDocument.serializer(), """{"areas":[{"id":"living","name":"Living room"}]}""")
        assertEquals(emptyMap(), old.areas.single().groupNames)
    }
}
```

- [ ] **Step 2: Run them, expect compile failure**

Run: `./gradlew :core:model:testDebugUnitTest --tests '*UiOverrideTest' --tests '*AreaTest'` (if `:core:model` is a JVM module the task is `:core:model:test`; check with `./gradlew :core:model:tasks --all | grep -i test`).
Expected: FAIL, `ungrouped` / `groupNames` unresolved.

- [ ] **Step 3: Add the fields**

In `UiOverride` after `securityAsk`:

```kotlin
    /** Keeps this cover or light out of its room's group card (room groups spec §6). */
    val ungrouped: Boolean = false,
```

`Area.kt`:

```kotlin
@Serializable
data class Area(
    val id: String,
    val name: String,
    val icon: String? = null,
    val order: Int? = null,
    /** Custom group card names by kind key (`cover`, `light`); missing = the localized default (room groups spec §6). */
    val groupNames: Map<String, String> = emptyMap(),
)
```

- [ ] **Step 4: Run model tests, expect PASS**

Same command as Step 2. Also check `UiOverrideTest.default override encodes to empty object` still passes (defaults are not encoded).

- [ ] **Step 5: Write the failing `RoomGroupsTest`**

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomGroupsTest {
    private val areas = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"))

    private fun build(
        vararg entities: Entity,
        states: Map<String, EntityState> = emptyMap(),
        selected: RoomFilter = RoomFilter.All,
        areaList: List<Area> = areas,
        query: String = "",
    ) = HomeUiBuilder.build(entities.associateBy { it.uniqueId }, states, areaList, selected, LinkState.Connected(TransportMode.DEMO), Locale.US, query = query)

    private fun shutter(id: String, area: String = "living", ui: UiOverride = UiOverride(), position: Boolean = true) =
        testEntity(id, Kind.COVER, id, area = area, ui = ui, capabilities = Capabilities(position = position))

    private fun light(id: String, area: String = "living", ui: UiOverride = UiOverride(), brightness: Boolean = true) =
        testEntity(id, Kind.LIGHT, id, area = area, ui = ui, capabilities = Capabilities(brightness = brightness))

    private fun cover(id: String, state: CoverState, position: Int?) = id to EntityState(id, StateValue.Cover(state, position))

    @Test
    fun `two shutters in a room fold into one wide group card`() {
        val s = build(shutter("bay"), shutter("side"), light("lamp"))
        val group = s.cards.single { it.isGroup }
        assertEquals("group:living:cover", group.id)
        assertEquals(listOf("bay", "side"), group.members.map { it.id })
        assertTrue(group.wide)
        assertEquals(TapAction.OPEN_SHEET, group.tap)
        assertTrue(group.hasSheet)
        assertEquals("", group.name)
        assertEquals(listOf("group:living:cover", "lamp"), s.cards.map { it.id })
    }

    @Test
    fun `a single shutter or an unassigned pair stays as single cards`() {
        val s = build(shutter("bay"), shutter("a", area = "Unassigned"), shutter("b", area = "Unassigned"))
        assertFalse(s.cards.any { it.isGroup })
    }

    @Test
    fun `a group dissolves below two members`() {
        val hidden = build(shutter("bay"), shutter("side", ui = UiOverride(hidden = true)))
        assertEquals(listOf("bay"), hidden.cards.map { it.id })
        val moved = build(shutter("bay"), shutter("side", area = "kitchen"))
        assertEquals(setOf("bay", "side"), moved.cards.map { it.id }.toSet())
        val ungrouped = build(shutter("bay"), shutter("side", ui = UiOverride(ungrouped = true)))
        assertEquals(setOf("bay", "side"), ungrouped.cards.map { it.id }.toSet())
    }

    @Test
    fun `groups show in All and room filters only`() {
        val e = arrayOf(shutter("bay", ui = UiOverride(favorite = true)), shutter("side", ui = UiOverride(favorite = true)))
        assertTrue(build(*e, selected = RoomFilter.Area("living")).cards.single().isGroup)
        assertEquals(listOf("bay", "side"), build(*e, selected = RoomFilter.Favorites).cards.map { it.id })
        // "living" matches both through their room name (SearchMatch also searches the area name).
        assertEquals(listOf("bay", "side"), build(*e, query = "living").cards.map { it.id }.sorted())
    }

    @Test
    fun `the group takes its first member's place in the order`() {
        val s = build(
            testEntity("aaa", Kind.SWITCH, "aaa", area = "living"),
            shutter("zz1"),
            testEntity("mmm", Kind.SWITCH, "mmm", area = "living"),
            shutter("bbb"),
        )
        assertEquals(listOf("aaa", "group:living:cover", "mmm"), s.cards.map { it.id })
    }

    @Test
    fun `a cover group aggregates open count, motion, positions and status`() {
        val s = build(
            shutter("a"), shutter("b"), shutter("c", position = false),
            states = mapOf(
                cover("a", CoverState.OPEN, 100),
                cover("b", CoverState.CLOSING, 40),
                cover("c", CoverState.CLOSED, null),
                "b" to EntityState("b", StateValue.Cover(CoverState.CLOSING, 40), pending = true),
            ),
        )
        val g = s.cards.single()
        assertEquals(CardValue.CoverGroup(open = 1, total = 3, moving = CoverState.CLOSING, positions = listOf(100, 40, 0)), g.value)
        assertTrue(g.on)
        assertTrue(g.pending)
        assertTrue(g.capabilities.position)
    }

    @Test
    fun `opening wins over closing when members move both ways`() {
        val s = build(shutter("a"), shutter("b"), states = mapOf(cover("a", CoverState.OPENING, 10), cover("b", CoverState.CLOSING, 90)))
        assertEquals(CoverState.OPENING, (s.cards.single().value as CardValue.CoverGroup).moving)
    }

    @Test
    fun `a light group toggles on tap and counts lights on`() {
        val s = build(
            light("a"), light("b", brightness = false), light("c"),
            states = mapOf("a" to EntityState("a", StateValue.Light(true, 200)), "c" to EntityState("c", StateValue.Light(false, null))),
        )
        val g = s.cards.single()
        assertEquals("group:living:light", g.id)
        assertEquals(TapAction.TOGGLE, g.tap)
        assertEquals(CardValue.LightGroup(onCount = 1, total = 3), g.value)
        assertTrue(g.on)
        assertTrue(g.capabilities.brightness)
    }

    @Test
    fun `available if any member is, stale if any member is`() {
        val s = build(
            shutter("a"), shutter("b"),
            states = mapOf(
                "a" to EntityState("a", StateValue.Cover(CoverState.OPEN, 100), available = false),
                "b" to EntityState("b", StateValue.Cover(CoverState.OPEN, 100), stale = true),
            ),
        )
        val g = s.cards.single()
        assertTrue(g.available)
        assertTrue(g.stale)
    }

    @Test
    fun `a custom group name comes from the area`() {
        val s = build(shutter("a"), shutter("b"), areaList = listOf(Area("living", "Living room", groupNames = mapOf("cover" to "Baie"))))
        assertEquals("Baie", s.cards.single().name)
    }

    @Test
    fun `members and their companions are found by id`() {
        val s = build(shutter("a"), shutter("b"))
        assertNotNull(s.card("a"))
        assertNotNull(s.card("group:living:cover"))
    }

    @Test
    fun `groupable marks covers and lights that have a peer in their room, even when ungrouped`() {
        val s = build(shutter("a"), shutter("b", ui = UiOverride(ungrouped = true)), light("solo"), selected = RoomFilter.All)
        val byId = (s.cards + s.cards.flatMap { it.members }).associateBy { it.id }
        assertTrue(byId.getValue("a").groupable)
        assertTrue(byId.getValue("b").groupable)
        assertFalse(byId.getValue("solo").groupable)
    }
}
```

> `EntityState` constructor: check `core/model/.../EntityState.kt` for the exact parameter names (`available`, `pending`, `stale`) and adjust the named arguments if they differ.

- [ ] **Step 6: Run, expect compile failure**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*RoomGroupsTest'`
Expected: FAIL, unresolved `isGroup`, `members`, `CardValue.CoverGroup`…

- [ ] **Step 7: Extend `HomeModels.kt`**

Add to `CardValue`:

```kotlin
    /** Room groups spec §3.1. [positions] has one 0..100 entry per member; a member without position counts 100 when open, 0 when closed. */
    data class CoverGroup(val open: Int, val total: Int, val moving: CoverState?, val positions: List<Int>) : CardValue

    /** Room groups spec §3.2. */
    data class LightGroup(val onCount: Int, val total: Int) : CardValue
```

Add to `CardUi` (after `companions`):

```kotlin
    /** A room group's member cards in sort order (room groups spec §3); empty for a single card. */
    val members: List<CardUi> = emptyList(),
    /** A cover or light whose room has another one: its Customize shows the "Group with…" switch. */
    val groupable: Boolean = false,
) {
    val isGroup: Boolean get() = members.isNotEmpty()
}
```

(The class body replaces the closing `)` of `CardUi`.)

Replace `HomeUiState.card`:

```kotlin
    fun card(id: String): CardUi? {
        fun search(cards: List<CardUi>): CardUi? = cards.firstNotNullOfOrNull { c ->
            c.takeIf { it.id == id } ?: search(c.companions) ?: search(c.members)
        }
        return search(cards + listOfNotNull(hero))
    }
```

- [ ] **Step 8: Create `RoomGroups.kt`**

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride

/** Folds the covers and the lights of each room into one group card (room groups spec §2–§3). */
object RoomGroups {
    val GROUP_KINDS = setOf(Kind.COVER, Kind.LIGHT)
    private const val MIN_MEMBERS = 2

    fun kindKey(kind: Kind): String = kind.name.lowercase()

    fun groupId(areaId: String, kind: Kind): String = "group:$areaId:${kindKey(kind)}"

    private fun candidate(areaId: String, kind: Kind) = kind in GROUP_KINDS && areaId != UNASSIGNED_AREA

    /** Ids of Home-visible covers and lights whose room has at least one other, grouped or not. */
    fun groupableIds(entities: List<Entity>): Set<String> = entities
        .filter { candidate(it.area, it.kind) }
        .groupBy { it.area to it.kind }
        .values.filter { it.size >= MIN_MEMBERS }
        .flatMapTo(mutableSetOf()) { group -> group.map { it.uniqueId } }

    /** [cards] are already filtered and sorted; each group takes its first member's place. */
    fun fold(cards: List<CardUi>, areas: List<Area>): List<CardUi> {
        val members = cards
            .filter { candidate(it.areaId, it.kind) && !it.ui.ungrouped }
            .groupBy { it.areaId to it.kind }
            .filterValues { it.size >= MIN_MEMBERS }
        if (members.isEmpty()) return cards
        val memberOf = members.entries.flatMap { (key, list) -> list.map { it.id to key } }.toMap()
        val names = areas.associate { it.id to it.groupNames }
        val emitted = mutableSetOf<Pair<String, Kind>>()
        return buildList {
            for (card in cards) {
                val key = memberOf[card.id]
                if (key == null) { add(card); continue }
                if (emitted.add(key)) add(group(key.first, key.second, members.getValue(key), names[key.first]?.get(kindKey(key.second))))
            }
        }
    }

    private fun group(areaId: String, kind: Kind, members: List<CardUi>, customName: String?): CardUi {
        val first = members.first()
        val value = if (kind == Kind.COVER) coverValue(members) else LightGroupValue(members)
        return CardUi(
            id = groupId(areaId, kind),
            name = customName?.trim().orEmpty(),
            kind = kind,
            deviceClass = null,
            areaName = first.areaName,
            value = value,
            state = StateValue.Unknown,
            capabilities = Capabilities(
                position = members.any { it.capabilities.position },
                brightness = members.any { it.capabilities.brightness },
            ),
            unit = null,
            on = members.any { it.on },
            wide = true,
            available = members.any { it.available },
            pending = members.any { it.pending },
            stale = members.any { it.stale },
            tap = if (kind == Kind.COVER) TapAction.OPEN_SHEET else TapAction.TOGGLE,
            hasSheet = true,
            hasHistory = false,
            areaId = areaId,
            ui = UiOverride(),
            hidden = false,
            members = members,
        )
    }

    private fun coverValue(members: List<CardUi>): CardValue.CoverGroup {
        val states = members.map { (it.state as? StateValue.Cover)?.state }
        val moving = when {
            CoverState.OPENING in states -> CoverState.OPENING
            CoverState.CLOSING in states -> CoverState.CLOSING
            else -> null
        }
        val positions = members.map { m -> (m.state as? StateValue.Cover)?.position?.coerceIn(0, 100) ?: if (m.on) 100 else 0 }
        return CardValue.CoverGroup(open = members.count { it.on }, total = members.size, moving = moving, positions = positions)
    }

    @Suppress("FunctionName")
    private fun LightGroupValue(members: List<CardUi>) = CardValue.LightGroup(onCount = members.count { it.on }, total = members.size)
}
```

> If `Capabilities` has no no-arg-plus-named constructor with `position` / `brightness`, open `core/model/.../Capabilities.kt` and use its real parameter names; every other parameter keeps its default.

- [ ] **Step 9: Wire into `HomeUiBuilder.build`**

In `build`, after `val grid = …`:

```kotlin
        val groupable = RoomGroups.groupableIds(grid)
```

Change the `cards` pipeline so `card(...)` receives `groupable`, then fold only for All/Area without a query:

```kotlin
        val sorted = shown
            .sortedWith(/* unchanged comparator */)
            .map { card(it, names.getValue(it.uniqueId), states[it.uniqueId], areaNames, locale, companionsOf(it), historyEnabled, it.uniqueId in groupable) }
        val folds = query.isBlank() && (effective == RoomFilter.All || effective is RoomFilter.Area)
        val cards = if (folds) RoomGroups.fold(sorted, areas) else sorted
```

Add the parameter to the private `card(...)`: `groupable: Boolean = false`, and pass `groupable = groupable` into the `CardUi(...)` call. The hero and `cardFor` calls keep the default `false`.

- [ ] **Step 10: Run the new tests, expect PASS**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*RoomGroupsTest'`
Expected: PASS.

- [ ] **Step 11: Run the whole module, fix fixtures**

Run: `./gradlew :feature:home:testDebugUnitTest`
Any existing test that now fails because two covers or two lights of one room fold into a group, and grouping is not that test's subject: give one of the two entities `ui = UiOverride(ungrouped = true)` in the fixture. Do not change assertions that test unrelated behaviour. Expected after fixes: PASS.

- [ ] **Step 12: Commit**

```bash
git add core/model feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): fold room covers and lights into group cards

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 2: Sending to a group

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/GroupCommands.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt` (new event)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeEventsEffect.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt` (`HomeRoute` wiring only)
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/GroupCommandsTest.kt`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModelTest.kt`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/HomeEventsEffectTest.kt`

**Interfaces:**
- Consumes: `CardUi.members`, `CardUi.isGroup`, `HomeUiState.card` (Task 1).
- Produces:
  - `GroupCommands.targets(group: CardUi, command: Command): List<String>`
  - `GroupCommands.toggle(group: CardUi): Command`
  - `HomeEvent.GroupNotConfirmed(groupId: String, kind: Kind, failedIds: List<String>, failedNames: List<String>, command: Command)`
  - `HomeViewModel.sendGroup(groupId: String, command: Command, only: List<String>? = null)`
  - `HomeViewModel.tap(card)` toggles a light group.
  - `HomeEventsEffect(events, snackbar, onRetry, onRetryGroup: (String, Command, List<String>) -> Unit = { _, _, _ -> })`

- [ ] **Step 1: Write the failing `GroupCommandsTest`**

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class GroupCommandsTest {
    private fun group(vararg e: com.gekkotron.oikos.model.Entity, states: Map<String, EntityState> = emptyMap()): CardUi =
        HomeUiBuilder.build(e.associateBy { it.uniqueId }, states, listOf(Area("living", "Living room")), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US)
            .cards.single { it.isGroup }

    private val covers = group(
        testEntity("a", Kind.COVER, area = "living", capabilities = Capabilities(position = true)),
        testEntity("b", Kind.COVER, area = "living", capabilities = Capabilities(position = false)),
        testEntity("c", Kind.COVER, area = "living", capabilities = Capabilities(position = true)),
        states = mapOf("c" to EntityState("c", StateValue.Cover(CoverState.OPEN, 100), available = false)),
    )

    @Test
    fun `open, stop and close go to every available member`() {
        assertEquals(listOf("a", "b"), GroupCommands.targets(covers, Command.Open))
        assertEquals(listOf("a", "b"), GroupCommands.targets(covers, Command.Stop))
    }

    @Test
    fun `position goes only to members that support it`() {
        assertEquals(listOf("a"), GroupCommands.targets(covers, Command.SetPosition(50)))
    }

    @Test
    fun `brightness goes only to dimmable lights, plain on and off to all`() {
        val lights = group(
            testEntity("x", Kind.LIGHT, area = "living", capabilities = Capabilities(brightness = true)),
            testEntity("y", Kind.LIGHT, area = "living"),
        )
        assertEquals(listOf("x"), GroupCommands.targets(lights, Command.TurnOn(brightness = 120)))
        assertEquals(listOf("x", "y"), GroupCommands.targets(lights, Command.TurnOn()))
        assertEquals(listOf("x", "y"), GroupCommands.targets(lights, Command.TurnOff))
    }

    @Test
    fun `toggle is absolute - any on turns all off`() {
        val lights = group(
            testEntity("x", Kind.LIGHT, area = "living"), testEntity("y", Kind.LIGHT, area = "living"),
            states = mapOf("x" to EntityState("x", StateValue.Light(true, 255))),
        )
        assertEquals(Command.TurnOff, GroupCommands.toggle(lights))
        val off = group(testEntity("x", Kind.LIGHT, area = "living"), testEntity("y", Kind.LIGHT, area = "living"))
        assertEquals(Command.TurnOn(), GroupCommands.toggle(off))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** (`GroupCommands` unresolved)

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*GroupCommandsTest'`

- [ ] **Step 3: Create `GroupCommands.kt`**

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Command

/** Which members of a group a command goes to (room groups spec §5). */
object GroupCommands {
    fun targets(group: CardUi, command: Command): List<String> = group.members
        .filter { it.available }
        .filter { m ->
            when (command) {
                is Command.SetPosition -> m.capabilities.position
                is Command.TurnOn -> command.brightness == null || m.capabilities.brightness
                else -> true
            }
        }
        .map { it.id }

    /** Absolute, so Retry repeats the intent instead of flipping it back. */
    fun toggle(group: CardUi): Command = if (group.on) Command.TurnOff else Command.TurnOn()
}
```

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: Write the failing `HomeViewModelTest` cases**

Add these fixtures and tests to `HomeViewModelTest` (keep the existing ones; the shared `source` has one lamp and one shutter in the kitchen, so it does not group):

```kotlin
    private fun groupSource() = FakeEntitySource().apply {
        entities.value = mapOf(
            "a" to testEntity("a", Kind.COVER, "Bay", area = "living", capabilities = Capabilities(position = true)),
            "b" to testEntity("b", Kind.COVER, "Side", area = "living", capabilities = Capabilities(position = true)),
            "c" to testEntity("c", Kind.COVER, "Patio", area = "living"),
        )
        areas.value = listOf(Area("living", "Living room"))
    }

    @Test
    fun `a group send reaches every available member`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.sendGroup("group:living:cover", Command.Close)
        assertEquals(setOf("a" to Command.Close, "b" to Command.Close, "c" to Command.Close), src.sent.toSet())
    }

    @Test
    fun `unavailable members are skipped`() = runTest(dispatcher) {
        val src = groupSource().apply { stateMap.value = mapOf("c" to EntityState("c", StateValue.Cover(CoverState.OPEN, null), available = false)) }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.sendGroup("group:living:cover", Command.Open)
        assertEquals(setOf("a", "b"), src.sent.map { it.first }.toSet())
    }

    @Test
    fun `a stale member blocks the whole group send`() = runTest(dispatcher) {
        val src = groupSource().apply { stateMap.value = mapOf("a" to EntityState("a", StateValue.Cover(CoverState.OPEN, 100), stale = true)) }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val event = async { vm.events.first() }
        vm.sendGroup("group:living:cover", Command.Open)
        assertEquals(HomeEvent.WaitingForConnection, event.await())
        assertTrue(src.sent.isEmpty())
    }

    @Test
    fun `only resends to the listed members`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.sendGroup("group:living:cover", Command.Open, only = listOf("b"))
        assertEquals(listOf("b" to Command.Open), src.sent)
    }

    @Test
    fun `failures of one group send merge into one event`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.sendGroup("group:living:cover", Command.Close)
        src.commandFailures.emit(CommandFailure("a", Command.Close, "timeout"))
        src.commandFailures.emit(CommandFailure("c", Command.Close, "timeout"))
        advanceTimeBy(1_100)
        assertEquals(
            listOf(HomeEvent.GroupNotConfirmed("group:living:cover", Kind.COVER, listOf("a", "c"), listOf("Bay", "Patio"), Command.Close)),
            events,
        )
    }

    @Test
    fun `a failure for a member sent on its own stays a single NotConfirmed`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.sendGroup("group:living:cover", Command.Close)
        vm.send("a", Command.Open)
        src.commandFailures.emit(CommandFailure("a", Command.Open, "timeout"))
        advanceTimeBy(1_100)
        assertEquals(listOf<HomeEvent>(HomeEvent.NotConfirmed("a", Command.Open)), events)
    }

    @Test
    fun `tapping a light group toggles every light`() = runTest(dispatcher) {
        val src = FakeEntitySource().apply {
            entities.value = mapOf(
                "x" to testEntity("x", Kind.LIGHT, area = "living"),
                "y" to testEntity("y", Kind.LIGHT, area = "living"),
            )
            areas.value = listOf(Area("living", "Living room"))
            stateMap.value = mapOf("x" to EntityState("x", StateValue.Light(true, 255)))
        }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.tap(vm.state.value.cards.single())
        assertEquals(setOf("x" to Command.TurnOff, "y" to Command.TurnOff), src.sent.toSet())
    }

    @Test
    fun `a rejected group send reports once`() = runTest(dispatcher) {
        val src = groupSource().apply { result = CommandResult.Rejected("read-only") }
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.sendGroup("group:living:cover", Command.Open)
        assertEquals(listOf<HomeEvent>(HomeEvent.Rejected("group:living:cover", "read-only")), events)
    }
```

New imports: `kotlinx.coroutines.async`, `kotlinx.coroutines.test.advanceTimeBy`, `com.gekkotron.oikos.model.Capabilities`, `com.gekkotron.oikos.model.CoverState`.

- [ ] **Step 6: Run, expect FAIL** (`sendGroup`, `GroupNotConfirmed` unresolved)

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeViewModelTest'`

- [ ] **Step 7: Add the event**

In `HomeEvent`:

```kotlin
    /** Members of one group send that were not confirmed, merged (room groups spec §5). */
    data class GroupNotConfirmed(
        val groupId: String,
        val kind: Kind,
        val failedIds: List<String>,
        val failedNames: List<String>,
        val command: Command,
    ) : HomeEvent
```

- [ ] **Step 8: Implement in `HomeViewModel`**

Add fields and replace the `init` block:

```kotlin
    /** Member id → the group send it last took part in; a member's own send removes it. */
    private val groupSends = mutableMapOf<String, GroupSend>()
    private val failedInGroup = mutableMapOf<String, MutableList<String>>()

    private data class GroupSend(val groupId: String, val kind: Kind, val command: Command, val names: Map<String, String>)

    init {
        viewModelScope.launch {
            source.commandFailures.collect { failure ->
                val send = groupSends[failure.uniqueId]?.takeIf { it.command == failure.command }
                if (send == null) return@collect _events.send(HomeEvent.NotConfirmed(failure.uniqueId, failure.command))
                groupSends.remove(failure.uniqueId)
                val bucket = failedInGroup.getOrPut(send.groupId) { mutableListOf() }
                bucket += failure.uniqueId
                // The first failure opens a 1 s window; the rest of the same send joins it.
                if (bucket.size == 1) launch {
                    delay(GROUP_FAILURE_WINDOW_MS)
                    val ids = failedInGroup.remove(send.groupId).orEmpty()
                    _events.send(HomeEvent.GroupNotConfirmed(send.groupId, send.kind, ids, ids.map { send.names[it] ?: it }, send.command))
                }
            }
        }
    }
```

Change `tap` so a light group toggles:

```kotlin
    fun tap(card: CardUi) {
        when (card.tap) {
            TapAction.TOGGLE ->
                if (card.isGroup) sendGroup(card.id, GroupCommands.toggle(card))
                // Absolute, so "Retry" after a late confirmation repeats the intent instead of flipping it back.
                else send(card.id, if (card.on) Command.TurnOff else Command.TurnOn())
            TapAction.PRESS -> send(card.id, Command.Press)
            TapAction.OPEN_SHEET, TapAction.OPEN_HISTORY, TapAction.NONE -> Unit
        }
    }
```

In `send`, first line inside `launch`: `groupSends.remove(id)`.

Add:

```kotlin
    /** Room groups spec §5: one command to every capable, available member, in parallel. [only] narrows it (Retry). */
    fun sendGroup(groupId: String, command: Command, only: List<String>? = null) {
        viewModelScope.launch {
            val group = state.value.card(groupId)?.takeIf { it.isGroup } ?: return@launch
            if (group.members.any { it.stale }) return@launch _events.send(HomeEvent.WaitingForConnection)
            val ids = GroupCommands.targets(group, command).filter { only == null || it in only }
            val names = group.members.associate { it.id to it.name }
            ids.forEach { groupSends[it] = GroupSend(groupId, group.kind, command, names) }
            val results = ids.map { id -> async { source.send(id, command) } }.awaitAll()
            results.firstOrNull { it is CommandResult.Rejected }?.let {
                _events.send(HomeEvent.Rejected(groupId, (it as CommandResult.Rejected).reason))
            }
        }
    }
```

Companion: `const val GROUP_FAILURE_WINDOW_MS = 1_000L`. Imports: `kotlinx.coroutines.async`, `kotlinx.coroutines.awaitAll`, `kotlinx.coroutines.delay`, `com.gekkotron.oikos.model.Kind`.

- [ ] **Step 9: Run, expect PASS**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeViewModelTest' --tests '*GroupCommandsTest'`

- [ ] **Step 10: Strings**

`values/strings.xml`:

```xml
    <string name="home_group_not_confirmed_one">%1$s didn\'t respond</string>
    <plurals name="home_group_not_confirmed_covers">
        <item quantity="one">%1$d shutter didn\'t respond</item>
        <item quantity="other">%1$d shutters didn\'t respond</item>
    </plurals>
    <plurals name="home_group_not_confirmed_lights">
        <item quantity="one">%1$d light didn\'t respond</item>
        <item quantity="other">%1$d lights didn\'t respond</item>
    </plurals>
```

`values-fr/strings.xml`:

```xml
    <string name="home_group_not_confirmed_one">%1$s n\'a pas répondu</string>
    <plurals name="home_group_not_confirmed_covers">
        <item quantity="one">%1$d volet n\'a pas répondu</item>
        <item quantity="other">%1$d volets n\'ont pas répondu</item>
    </plurals>
    <plurals name="home_group_not_confirmed_lights">
        <item quantity="one">%1$d lumière n\'a pas répondu</item>
        <item quantity="other">%1$d lumières n\'ont pas répondu</item>
    </plurals>
```

- [ ] **Step 11: Failing `HomeEventsEffectTest` case**

Read the existing tests in `HomeEventsEffectTest.kt` and add one in the same style:

```kotlin
    @Test
    fun `a merged group failure shows one snackbar and Retry resends to the failed members`() {
        val events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
        val retried = mutableListOf<Triple<String, Command, List<String>>>()
        val snackbar = SnackbarHostState()
        compose.setContent {
            HomeEventsEffect(events, snackbar, onRetry = { _, _ -> }, onRetryGroup = { g, c, ids -> retried += Triple(g, c, ids) })
            SnackbarHost(snackbar)
        }
        events.tryEmit(HomeEvent.GroupNotConfirmed("group:living:cover", Kind.COVER, listOf("a", "c"), listOf("Bay", "Patio"), Command.Close))
        compose.onNodeWithText("2 shutters didn't respond").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("group:living:cover", Command.Close as Command, listOf("a", "c"))), retried)
    }
```

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeEventsEffectTest'` → FAIL.

- [ ] **Step 12: Implement in `HomeEventsEffect`**

New parameter `onRetryGroup: (String, Command, List<String>) -> Unit = { _, _, _ -> }`, `val retryGroup by rememberUpdatedState(onRetryGroup)`, and a branch:

```kotlin
                is HomeEvent.GroupNotConfirmed -> {
                    val message = if (event.failedIds.size == 1) {
                        resources.getString(R.string.home_group_not_confirmed_one, event.failedNames.single())
                    } else {
                        val plural = if (event.kind == Kind.COVER) R.plurals.home_group_not_confirmed_covers else R.plurals.home_group_not_confirmed_lights
                        resources.getQuantityString(plural, event.failedIds.size, event.failedIds.size)
                    }
                    val result = snackbar.showSnackbar(message = message, actionLabel = resources.getString(R.string.home_retry), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) retryGroup(event.groupId, event.command, event.failedIds)
                }
```

In `HomeRoute`: `HomeEventsEffect(viewModel.events, snackbar, onRetry = viewModel::send, onRetryGroup = { g, c, ids -> viewModel.sendGroup(g, c, ids) })`.

- [ ] **Step 13: Run module tests, expect PASS**

Run: `./gradlew :feature:home:testDebugUnitTest`

- [ ] **Step 14: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): send group commands in parallel and merge their failures

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 3: The group card on Home

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/GroupCard.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CardText.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt` (`iconSpec` for groups)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/GroupCardTest.kt`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/IconSpecTest.kt`

**Interfaces:**
- Consumes: `CardValue.CoverGroup`, `CardValue.LightGroup`, `CardUi.isGroup`, `CardUi.members` (Task 1); `HomeViewModel.sendGroup` (Task 2).
- Produces:
  - `@Composable internal fun groupName(card: CardUi): String` (custom name or localized default), in `CardText.kt`
  - `@Composable internal fun GroupCard(card: CardUi, onClick: () -> Unit, onLongClick: () -> Unit, onSendGroup: (String, Command) -> Unit, modifier: Modifier = Modifier)`
  - `HomeScreen(…, onSendGroup: (String, Command) -> Unit = { _, _ -> }, onCustomizeGroup: (String, String, List<String>) -> Unit = { _, _, _ -> })` (the second is wired in Task 5; add the parameter now so Task 4 and 5 don't fight over the signature)
  - Test tags: `card-<groupId>`, `group-open-<groupId>`, `group-stop-<groupId>`, `group-close-<groupId>`

- [ ] **Step 1: Strings**

`values/strings.xml`:

```xml
    <string name="group_default_covers">Shutters</string>
    <string name="group_default_lights">Lights</string>
    <string name="group_badge">×%1$d</string>
    <string name="value_group_open_closed">%1$d open · %2$d closed</string>
    <string name="value_group_all_open">All open</string>
    <string name="value_group_all_closed">All closed</string>
    <string name="value_group_lights_on">%1$d of %2$d on</string>
    <string name="value_group_all_on">All on</string>
    <string name="value_group_all_off">All off</string>
    <string name="action_group_open">Open all shutters</string>
    <string name="action_group_stop">Stop all shutters</string>
    <string name="action_group_close">Close all shutters</string>
```

`values-fr/strings.xml`:

```xml
    <string name="group_default_covers">Volets</string>
    <string name="group_default_lights">Lumières</string>
    <string name="group_badge">×%1$d</string>
    <string name="value_group_open_closed">Ouverts : %1$d · Fermés : %2$d</string>
    <string name="value_group_all_open">Tous ouverts</string>
    <string name="value_group_all_closed">Tous fermés</string>
    <string name="value_group_lights_on">%1$d sur %2$d allumées</string>
    <string name="value_group_all_on">Toutes allumées</string>
    <string name="value_group_all_off">Toutes éteintes</string>
    <string name="action_group_open">Ouvrir tous les volets</string>
    <string name="action_group_stop">Arrêter tous les volets</string>
    <string name="action_group_close">Fermer tous les volets</string>
```

- [ ] **Step 2: Failing tests**

`GroupCardTest.kt` (Robolectric Compose test, same setup as `HomeScreenTest`: `@RunWith(AndroidJUnit4::class)`, `@Config(qualifiers = "w411dp-h914dp")`, `createComposeRule()`):

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class GroupCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val entities = listOf(
        testEntity("bay", Kind.COVER, "Bay window", area = "living", capabilities = Capabilities(position = true)),
        testEntity("side", Kind.COVER, "Side window", area = "living", capabilities = Capabilities(position = true)),
        testEntity("patio", Kind.COVER, "Patio door", area = "living"),
        testEntity("l1", Kind.LIGHT, "Ceiling", area = "living"),
        testEntity("l2", Kind.LIGHT, "Floor lamp", area = "living"),
    ).associateBy { it.uniqueId }
    private val states = mapOf(
        "bay" to EntityState("bay", StateValue.Cover(CoverState.OPEN, 100)),
        "side" to EntityState("side", StateValue.Cover(CoverState.OPEN, 80)),
        "patio" to EntityState("patio", StateValue.Cover(CoverState.CLOSED, null)),
        "l1" to EntityState("l1", StateValue.Light(true, 200)),
    )
    private val sends = mutableListOf<Pair<String, Command>>()
    private val groupSends = mutableListOf<Pair<String, Command>>()
    private val taps = mutableListOf<String>()

    private fun show() = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            HomeScreen(
                state = HomeUiBuilder.build(entities, states, listOf(Area("living", "Living room")), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                onSelect = {},
                onTap = { taps += it.id },
                onSend = { id, c -> sends += id to c },
                onSendGroup = { id, c -> groupSends += id to c },
                now = LocalTime.NOON,
            )
        }
    }

    @Test
    fun `the shutter group card shows its summary and sends from its inline buttons`() {
        show()
        compose.onNodeWithText("Shutters").assertIsDisplayed()
        compose.onNodeWithText("2 open · 1 closed").assertIsDisplayed()
        compose.onNodeWithText("×3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close all shutters").performClick()
        assertEquals(listOf("group:living:cover" to Command.Close as Command), groupSends)
    }

    @Test
    fun `the light group card toggles on tap`() {
        show()
        compose.onNodeWithText("1 of 2 on").assertIsDisplayed()
        compose.onNodeWithTag("card-group:living:light").performClick()
        assertEquals(listOf("group:living:light"), taps)
    }

    @Test
    fun `tapping the shutter group opens its sheet`() {
        show()
        compose.onNodeWithTag("card-group:living:cover").performClick()
        compose.onNodeWithText("Bay window").assertIsDisplayed()
    }
}
```

> The last test needs Task 4's sheet to show members; until then it shows the old single sheet with the group name. **Write it now but mark it `@Ignore("Task 4")`**; Task 4 Step 1 removes the `@Ignore`.

Append to `IconSpecTest` (read the file first for its helper names):

```kotlin
    @Test
    fun `a cover group's shutter icon follows the mean position and motion`() {
        val group = /* a CardUi built like in GroupCardTest with positions 100, 80, 0 and nobody moving */
        assertEquals(IconSpec.Shutter(0.6f, null), iconSpec(group))
    }
```

Build `group` with `HomeUiBuilder.build(...).cards.single { it.isGroup && it.kind == Kind.COVER }` from the same three covers and states. Mean of 100, 80, 0 = 60 → `0.6f`.

- [ ] **Step 3: Run, expect FAIL**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*GroupCardTest' --tests '*IconSpecTest'`

- [ ] **Step 4: Text and icon**

In `CardText.kt`, two new branches in `cardValueText`:

```kotlin
    is CardValue.CoverGroup -> when {
        value.moving == CoverState.OPENING -> stringResource(R.string.value_cover_opening)
        value.moving == CoverState.CLOSING -> stringResource(R.string.value_cover_closing)
        value.open == value.total -> stringResource(R.string.value_group_all_open)
        value.open == 0 -> stringResource(R.string.value_group_all_closed)
        else -> stringResource(R.string.value_group_open_closed, value.open, value.total - value.open)
    }
    is CardValue.LightGroup -> when (value.onCount) {
        value.total -> stringResource(R.string.value_group_all_on)
        0 -> stringResource(R.string.value_group_all_off)
        else -> stringResource(R.string.value_group_lights_on, value.onCount, value.total)
    }
```

And:

```kotlin
/** A group's custom name, else "Shutters" / "Lights" (room groups spec §3). */
@Composable
internal fun groupName(card: CardUi): String = card.name.ifBlank {
    stringResource(if (card.kind == Kind.COVER) R.string.group_default_covers else R.string.group_default_lights)
}
```

(imports `CardUi`, `Kind`).

In `KindIcon.kt` `iconSpec`, change the cover branch:

```kotlin
    Kind.COVER -> (card.value as? CardValue.CoverGroup)?.let { g ->
        IconSpec.Shutter(g.positions.average().toFloat() / 100f, g.moving.takeIf { card.available })
    } ?: shutter(card.value as? CardValue.Cover, card.available)
```

- [ ] **Step 5: `GroupCard.kt`**

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.components.PendingDot
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind

/** Room groups spec §3: one wide card for a room's shutters or lights. */
@Composable
internal fun GroupCard(card: CardUi, onClick: () -> Unit, onLongClick: () -> Unit, onSendGroup: (String, Command) -> Unit, modifier: Modifier = Modifier) {
    val name = groupName(card)
    val valueText = if (card.available) cardValueText(card.value) else stringResource(R.string.value_unavailable)
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val description = stringResource(R.string.card_description, name, valueText, room)
    val onText = stringResource(R.string.value_on)
    val offText = stringResource(R.string.value_off)
    val staleText = stringResource(R.string.card_stale)
    OikosCard(
        modifier = modifier
            .testTag("card-${card.id}")
            .alpha(if (card.stale) 0.6f else 1f)
            .semantics {
                contentDescription = description
                if (card.kind == Kind.LIGHT) stateDescription = if (card.on) onText else offText
                if (card.stale) stateDescription = staleText
            },
        on = card.on,
        accent = false,
        enabled = card.available,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(if (card.kind == Kind.COVER) R.string.action_customize else R.string.action_controls),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindIcon(iconSpec(card))
            Spacer(Modifier.weight(1f))
            if (card.pending) PendingDot()
            Text(
                stringResource(R.string.group_badge, card.members.size),
                style = OikosTheme.type.label,
                color = OikosTheme.colors.warning,
                modifier = Modifier.padding(start = 8.dp)
                    .background(OikosTheme.colors.warningContainer, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = OikosTheme.type.cardName, color = LocalContentColor.current.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(valueText, style = OikosTheme.type.cardValue)
            }
            if (card.kind == Kind.COVER) CoverButtons(card, onSendGroup)
        }
        (card.value as? CardValue.CoverGroup)?.let { MiniBars(it.positions) }
    }
}

@Composable
private fun CoverButtons(card: CardUi, onSendGroup: (String, Command) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GroupButton("▲", stringResource(R.string.action_group_open), "group-open-${card.id}", card.available) { onSendGroup(card.id, Command.Open) }
        GroupButton("■", stringResource(R.string.action_group_stop), "group-stop-${card.id}", card.available) { onSendGroup(card.id, Command.Stop) }
        GroupButton("▼", stringResource(R.string.action_group_close), "group-close-${card.id}", card.available) { onSendGroup(card.id, Command.Close) }
    }
}

@Composable
private fun GroupButton(glyph: String, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(OikosDimens.minTouch).testTag(tag).semantics { contentDescription = label },
    ) { Text(glyph) }
}

/** One bar per member, filled to its position. Decorative: the summary text carries the meaning. */
@Composable
private fun MiniBars(positions: List<Int>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in positions) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(OikosTheme.colors.cardBorder)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(p / 100f).background(OikosTheme.colors.warning.copy(alpha = 0.85f)))
            }
        }
    }
}
```

> `OikosCard`, `KindIcon`, `PendingDot` and the theme tokens are used exactly as in `EntityCard.kt`. If `OikosTheme.type.label` or `colors.warningContainer` don't exist under those names, use what `EntityCard.kt` / `OikosColors.kt` actually expose (`warningContainer` does exist in `OikosColors`). Remove unused imports (`width`) before committing.

- [ ] **Step 6: Render it from `HomeScreen`**

Add parameters `onSendGroup: (String, Command) -> Unit = { _, _ -> }` and `onCustomizeGroup: (String, String, List<String>) -> Unit = { _, _, _ -> }` to `HomeScreen` (after `onOpenHistory`). In the `items(state.cards …)` block:

```kotlin
                items(state.cards, key = { it.id }, span = { GridItemSpan(if (it.wide) minOf(2, maxLineSpan) else 1) }) { card ->
                    val onClick = {
                        when (card.tap) {
                            TapAction.OPEN_SHEET -> sheetId = card.id
                            TapAction.OPEN_HISTORY -> onOpenHistory(card.id)
                            else -> onTap(card)
                        }
                    }
                    if (card.isGroup) {
                        // Shutter group: long-press customizes (tap already opens the sheet). Light group: tap toggles, long-press opens the sheet.
                        GroupCard(card, onClick = onClick, onLongClick = { if (card.kind == Kind.COVER) customizeId = card.id else sheetId = card.id }, onSendGroup = onSendGroup)
                    } else {
                        EntityCard(card = card, onClick = onClick, onLongClick = { if (card.hasSheet) sheetId = card.id else customizeId = card.id })
                    }
                }
```

In `HomeRoute`, pass `onSendGroup = viewModel::sendGroup` (a function reference to a method with a defaulted third parameter does not fit `(String, Command) -> Unit`; use `onSendGroup = { id, c -> viewModel.sendGroup(id, c) }`).

Until Task 5 lands, `customizeId` on a group falls into the existing `CustomizeSheet`. Guard it: render `CustomizeSheet` only when `customizeCard?.isGroup == false` (Task 5 adds the group branch).

- [ ] **Step 7: Run, expect PASS**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*GroupCardTest' --tests '*IconSpecTest' --tests '*HomeScreenTest'`

- [ ] **Step 8: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): group card with inline shutter controls

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 4: Group sheets

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/GroupControls.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/GroupSheetTest.kt`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/GroupCardTest.kt` (remove `@Ignore`)

**Interfaces:**
- Consumes: `GroupCard`, `groupName`, `HomeScreen(onSendGroup, …)` (Task 3); `CardUi.members` (Task 1).
- Produces:
  - `ControlSheet(card, onSend, onCustomize, onDismiss, onOpenHistory = null, onSendGroup: (String, Command) -> Unit = { _, _ -> }, onOpenMember: (String) -> Unit = {})`
  - `@Composable internal fun GroupControls(card: CardUi, onSend: (String, Command) -> Unit, onSendGroup: (String, Command) -> Unit, onOpenMember: (String) -> Unit)`
  - `GroupMath.meanPosition(group: CardUi): Int`, `GroupMath.meanBrightness(group: CardUi): Int` (pure, in `presentation/GroupMath.kt`)
  - Test tags: `member-<id>`, `group-slider-<groupId>`

- [ ] **Step 1: Strings + un-ignore**

Remove `@Ignore("Task 4")` from `GroupCardTest`.

`values/strings.xml`:

```xml
    <plurals name="sheet_group_subtitle_covers">
        <item quantity="one">%1$s · %2$d shutter</item>
        <item quantity="other">%1$s · %2$d shutters</item>
    </plurals>
    <plurals name="sheet_group_subtitle_lights">
        <item quantity="one">%1$s · %2$d light</item>
        <item quantity="other">%1$s · %2$d lights</item>
    </plurals>
    <string name="sheet_all_to">All to</string>
    <string name="sheet_customize_group">Customize group…</string>
```

`values-fr/strings.xml`:

```xml
    <plurals name="sheet_group_subtitle_covers">
        <item quantity="one">%1$s · %2$d volet</item>
        <item quantity="other">%1$s · %2$d volets</item>
    </plurals>
    <plurals name="sheet_group_subtitle_lights">
        <item quantity="one">%1$s · %2$d lumière</item>
        <item quantity="other">%1$s · %2$d lumières</item>
    </plurals>
    <string name="sheet_all_to">Tous à</string>
    <string name="sheet_customize_group">Personnaliser le groupe…</string>
```

- [ ] **Step 2: Failing tests**

`presentation/GroupMathTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class GroupMathTest {
    private fun group(vararg e: Entity, states: Map<String, EntityState>) =
        HomeUiBuilder.build(e.associateBy { it.uniqueId }, states, listOf(Area("living", "Living room")), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US)
            .cards.single { it.isGroup }

    @Test
    fun `mean position counts only members with a known position`() {
        val g = group(
            testEntity("a", Kind.COVER, area = "living", capabilities = Capabilities(position = true)),
            testEntity("b", Kind.COVER, area = "living", capabilities = Capabilities(position = true)),
            testEntity("c", Kind.COVER, area = "living"),
            states = mapOf(
                "a" to EntityState("a", StateValue.Cover(CoverState.OPEN, 100)),
                "b" to EntityState("b", StateValue.Cover(CoverState.OPEN, 41)),
                "c" to EntityState("c", StateValue.Cover(CoverState.CLOSED, null)),
            ),
        )
        assertEquals(71, GroupMath.meanPosition(g))
    }

    @Test
    fun `mean brightness counts dimmable lights that are on, else full`() {
        val g = group(
            testEntity("x", Kind.LIGHT, area = "living", capabilities = Capabilities(brightness = true)),
            testEntity("y", Kind.LIGHT, area = "living", capabilities = Capabilities(brightness = true)),
            states = mapOf("x" to EntityState("x", StateValue.Light(true, 100)), "y" to EntityState("y", StateValue.Light(false, 20))),
        )
        assertEquals(100, GroupMath.meanBrightness(g))
        val off = group(
            testEntity("x", Kind.LIGHT, area = "living", capabilities = Capabilities(brightness = true)),
            testEntity("y", Kind.LIGHT, area = "living"),
            states = emptyMap(),
        )
        assertEquals(255, GroupMath.meanBrightness(off))
    }
}
```

`ui/GroupSheetTest.kt` (same Robolectric setup as `GroupCardTest`, reusing its entities/states; render `HomeScreen` with the same callbacks):

```kotlin
    @Test
    fun `the shutter sheet sends Open to the group and lists every member`() {
        show()
        compose.onNodeWithTag("card-group:living:cover").performClick()
        compose.onNodeWithText("Living room · 3 shutters").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        assertEquals("group:living:cover" to Command.Open as Command, groupSends.last())
        compose.onNodeWithTag("member-patio").assertIsDisplayed()
    }

    @Test
    fun `a member row's own button sends to that member only`() {
        show()
        compose.onNodeWithTag("card-group:living:cover").performClick()
        compose.onNodeWithTag("member-close-side").performClick()
        assertEquals(listOf("side" to Command.Close as Command), sends)
    }

    @Test
    fun `tapping a member opens its own sheet and dismissing returns to the group`() {
        show()
        compose.onNodeWithTag("card-group:living:cover").performClick()
        compose.onNodeWithTag("member-bay").performClick()
        compose.onNodeWithText("Position").assertIsDisplayed()   // the single cover sheet
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Living room · 3 shutters").assertIsDisplayed()
    }

    @Test
    fun `long-pressing the light group opens its sheet with a master switch`() {
        show()
        compose.onNodeWithTag("card-group:living:light").performTouchInput { longClick() }
        compose.onNodeWithText("Living room · 2 lights").assertIsDisplayed()
        compose.onNodeWithTag("group-power-group:living:light").performClick()
        assertEquals("group:living:light" to Command.TurnOff as Command, groupSends.last())
    }
```

> `compose.activityRule` requires `createAndroidComposeRule<ComponentActivity>()`; use that rule in `GroupSheetTest`. If dismissing a `ModalBottomSheet` by back press is flaky under Robolectric, assert instead by calling the sheet's `onDismissRequest` path: tap the scrim is not reliable either, so prefer `Espresso.pressBack()` (`androidx.test.espresso.Espresso`), which other tests in this repo may already use — grep `pressBack` first and follow that pattern.

- [ ] **Step 3: Run, expect FAIL**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*GroupMathTest' --tests '*GroupSheetTest' --tests '*GroupCardTest'`

- [ ] **Step 4: `presentation/GroupMath.kt`**

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.StateValue
import kotlin.math.roundToInt

/** Starting values for a group sheet's sliders (room groups spec §4). */
object GroupMath {
    fun meanPosition(group: CardUi): Int = group.members
        .filter { it.capabilities.position }
        .mapNotNull { (it.state as? StateValue.Cover)?.position }
        .takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0

    fun meanBrightness(group: CardUi): Int = group.members
        .filter { it.capabilities.brightness }
        .mapNotNull { (it.state as? StateValue.Light)?.takeIf { l -> l.on }?.brightness }
        .takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 255
}
```

> Check `StateValue.Light`'s property names (`on`, `brightness`) in `core/model/.../StateValue.kt`; the existing `LightControls` uses `light?.on` and `light?.brightness`.

- [ ] **Step 5: `ui/GroupControls.kt`**

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.GroupCommands
import com.gekkotron.oikos.feature.home.presentation.GroupMath
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.math.roundToInt

/** Room groups spec §4: group controls, then one row per member. */
@Composable
internal fun GroupControls(card: CardUi, onSend: (String, Command) -> Unit, onSendGroup: (String, Command) -> Unit, onOpenMember: (String) -> Unit) {
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val subtitle = if (card.kind == Kind.COVER) R.plurals.sheet_group_subtitle_covers else R.plurals.sheet_group_subtitle_lights
    Text(pluralStringResource(subtitle, card.members.size, room, card.members.size), style = OikosTheme.type.body)
    if (card.kind == Kind.COVER) CoverGroupControls(card, onSendGroup) else LightGroupControls(card, onSendGroup)
    Column {
        for (member in card.members) {
            HorizontalDivider()
            MemberRow(member, onSend, onOpenMember)
        }
    }
}

@Composable
private fun CoverGroupControls(card: CardUi, onSendGroup: (String, Command) -> Unit) {
    val send: (Command) -> Unit = { onSendGroup(card.id, it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { send(Command.Open) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_open)) }
        FilledTonalButton(onClick = { send(Command.Stop) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_stop)) }
        FilledTonalButton(onClick = { send(Command.Close) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_close)) }
    }
    if (card.capabilities.position) {
        val start = GroupMath.meanPosition(card)
        var value by remember(start) { mutableFloatStateOf(start.toFloat()) }
        Row { Text(stringResource(R.string.sheet_all_to), Modifier.weight(1f)); Text("${value.roundToInt()} %") }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 0f..100f,
            steps = 19,
            onValueChangeFinished = { send(Command.SetPosition(value.roundToInt())) },
            modifier = Modifier.testTag("group-slider-${card.id}"),
        )
    }
}

@Composable
private fun LightGroupControls(card: CardUi, onSendGroup: (String, Command) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sheet_power), Modifier.weight(1f))
        Switch(checked = card.on, onCheckedChange = { onSendGroup(card.id, GroupCommands.toggle(card)) }, modifier = Modifier.testTag("group-power-${card.id}"))
    }
    if (card.capabilities.brightness) {
        val start = GroupMath.meanBrightness(card)
        var value by remember(start) { mutableFloatStateOf(start.toFloat()) }
        Text(stringResource(R.string.sheet_brightness))
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..255f,
            onValueChangeFinished = { onSendGroup(card.id, Command.TurnOn(brightness = value.roundToInt())) },
            modifier = Modifier.testTag("group-slider-${card.id}"),
        )
    }
}

@Composable
private fun MemberRow(member: CardUi, onSend: (String, Command) -> Unit, onOpenMember: (String) -> Unit) {
    val valueText = if (member.available) cardValueText(member.value) else stringResource(R.string.value_unavailable)
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable { onOpenMember(member.id) }.testTag("member-${member.id}").padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(member.name, style = OikosTheme.type.body)
            Text(valueText, style = OikosTheme.type.cardName)
        }
        if (member.kind == Kind.COVER) {
            for ((glyph, command, label) in listOf(
                Triple("▲", Command.Open, R.string.sheet_open),
                Triple("■", Command.Stop, R.string.sheet_stop),
                Triple("▼", Command.Close, R.string.sheet_close),
            )) {
                val text = stringResource(label)
                val tag = when (command) { Command.Open -> "open"; Command.Stop -> "stop"; else -> "close" }
                FilledTonalIconButton(
                    onClick = { onSend(member.id, command) },
                    enabled = member.available,
                    modifier = Modifier.size(OikosDimens.minTouch).testTag("member-$tag-${member.id}").semantics { contentDescription = "$text ${member.name}" },
                ) { Text(glyph) }
            }
        } else {
            Switch(
                checked = member.on,
                enabled = member.available,
                onCheckedChange = { onSend(member.id, if (it) Command.TurnOn() else Command.TurnOff) },
            )
        }
    }
}
```

- [ ] **Step 6: Use it in `ControlSheet`**

New parameters `onSendGroup: (String, Command) -> Unit = { _, _ -> }, onOpenMember: (String) -> Unit = {}`. Inside the `Column`, replace the title, value line and `when (card.kind)` with:

```kotlin
            if (card.isGroup) {
                Text(groupName(card), style = OikosTheme.type.display.copy(fontSize = 22.sp))
                GroupControls(card, onSend, onSendGroup, onOpenMember)
            } else {
                Text(card.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
                Text(cardValueText(card.value), style = OikosTheme.type.body)
                when (card.kind) { /* unchanged */ }
            }
```

and the Customize button text becomes `stringResource(if (card.isGroup) R.string.sheet_customize_group else R.string.sheet_customize)`.

- [ ] **Step 7: Member drill-down in `HomeScreen`**

Add `var sheetParent by rememberSaveable { mutableStateOf<String?>(null) }`. The `ControlSheet` call becomes:

```kotlin
        ControlSheet(
            sheetCard,
            onSend = onSend,
            onCustomize = { sheetId = null; sheetParent = null; customizeId = sheetCard.id },
            // A member opened from its group's sheet goes back to the group (room groups spec §4).
            onDismiss = { sheetId = sheetParent; sheetParent = null },
            onOpenHistory = { onOpenHistory(sheetCard.id) },
            onSendGroup = onSendGroup,
            onOpenMember = { member -> sheetParent = sheetCard.id; sheetId = member },
        )
```

> Because `onDismiss` reopens the parent, the history action (`onDismiss(); onOpenHistory()`) from a member sheet would reopen the group under the history screen. In `ControlSheet`'s history button, call `onOpenHistory()` first and keep `onDismiss()`; then in `HomeScreen`'s `onOpenHistory` lambda set `sheetParent = null; sheetId = null` before calling `onOpenHistory(sheetCard.id)`.

- [ ] **Step 8: Run, expect PASS**

Run: `./gradlew :feature:home:testDebugUnitTest`

- [ ] **Step 9: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): group sheets with all-to slider and member rows

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 5: Customize — group sheet, device switch, storage writes

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideEditor.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideDraft.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModel.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/GroupCustomizeSheet.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CustomizeSheet.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/OverrideEditorTest.kt`, `OverrideDraftTest.kt`, `HomeViewModelTest.kt`
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/GroupCustomizeSheetTest.kt`

**Interfaces:**
- Consumes: `UiOverride.ungrouped`, `Area.groupNames`, `RoomGroups.kindKey`, `CardUi.groupable` (Task 1); `HomeScreen(onCustomizeGroup)` (Task 3).
- Produces:
  - `OverrideEditor.setGroupName(areaId: String, kindKey: String, name: String?): Boolean`
  - `OverrideEditor.ungroup(ids: Collection<String>): Boolean`
  - `OverrideDraft.grouped: Boolean = true` (applied as `ungrouped = !grouped`)
  - `HomeViewModel.customizeGroup(groupId: String, name: String, removed: List<String>)`
  - `@Composable internal fun GroupCustomizeSheet(card: CardUi, onSave: (name: String, removed: List<String>) -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Strings**

`values/strings.xml`:

```xml
    <string name="customize_group_title">Customize group</string>
    <string name="customize_group_name">Group name</string>
    <string name="customize_group_name_hint">Leave empty for the default name</string>
    <string name="customize_group_members">Devices in this group</string>
    <string name="customize_ungroup_all">Ungroup all</string>
    <string name="customize_grouped_cover">Group with the other shutters in this room</string>
    <string name="customize_grouped_light">Group with the other lights in this room</string>
```

`values-fr/strings.xml`:

```xml
    <string name="customize_group_title">Personnaliser le groupe</string>
    <string name="customize_group_name">Nom du groupe</string>
    <string name="customize_group_name_hint">Vide = nom par défaut</string>
    <string name="customize_group_members">Appareils du groupe</string>
    <string name="customize_ungroup_all">Tout dégrouper</string>
    <string name="customize_grouped_cover">Grouper avec les autres volets de la pièce</string>
    <string name="customize_grouped_light">Grouper avec les autres lumières de la pièce</string>
```

- [ ] **Step 2: Failing `OverrideEditorTest` cases** (read the file first; it builds an `OverrideEditor(FakeEntitySource())`)

```kotlin
    @Test
    fun `setGroupName declares an implicit room and keeps the others`() = runTest {
        val source = FakeEntitySource().apply {
            areas.value = listOf(Area("kitchen", "Kitchen"), Area("salon", "Salon"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
        }
        assertTrue(OverrideEditor(source).setGroupName("salon", "cover", "Baie"))
        assertEquals(
            listOf(Area("kitchen", "Kitchen"), Area("salon", "Salon", groupNames = mapOf("cover" to "Baie"))),
            source.areaWrites.single(),
        )
    }

    @Test
    fun `a blank group name removes the custom name`() = runTest {
        val source = FakeEntitySource().apply { areas.value = listOf(Area("salon", "Salon", groupNames = mapOf("cover" to "Baie", "light" to "Spots"))) }
        assertTrue(OverrideEditor(source).setGroupName("salon", "cover", "  "))
        assertEquals(mapOf("light" to "Spots"), source.areaWrites.single().single().groupNames)
    }

    @Test
    fun `ungroup flags each device and keeps the rest of its override`() = runTest {
        val source = FakeEntitySource().apply {
            entities.value = mapOf("a" to testEntity("a", Kind.COVER, area = "salon", ui = UiOverride(favorite = true)))
        }
        assertTrue(OverrideEditor(source).ungroup(listOf("a")))
        assertEquals(UiOverride(favorite = true, ungrouped = true), source.overrides["a"])
    }

    @Test
    fun `setGroupName on an unknown room fails without writing`() = runTest {
        val source = FakeEntitySource()
        assertFalse(OverrideEditor(source).setGroupName("nowhere", "cover", "X"))
        assertTrue(source.areaWrites.isEmpty())
    }
```

`OverrideDraftTest`:

```kotlin
    @Test
    fun `turning grouping off sets ungrouped`() {
        val card = /* any CardUi fixture this test file already builds, for a COVER */
        val draft = OverrideDraft.from(card)
        assertTrue(draft.grouped)
        assertTrue(draft.copy(grouped = false).applyTo(card).ungrouped)
    }
```

`HomeViewModelTest`:

```kotlin
    @Test
    fun `customizing a group renames it and ungroups the unticked members`() = runTest(dispatcher) {
        val src = groupSource()
        val vm = HomeViewModel(src, SavedStateHandle(), dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        vm.customizeGroup("group:living:cover", "Baie", removed = listOf("c"))
        assertEquals(mapOf("cover" to "Baie"), src.areas.value.single().groupNames)
        assertTrue(src.overrides.getValue("c").ungrouped)
        assertEquals(listOf("a", "b"), vm.state.value.cards.single { it.isGroup }.members.map { it.id })
    }
```

- [ ] **Step 3: Run, expect FAIL**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*OverrideEditorTest' --tests '*OverrideDraftTest' --tests '*HomeViewModelTest'`

- [ ] **Step 4: `OverrideEditor`**

```kotlin
    /** Room groups spec §6: a custom group name on the room; blank removes it. Publishes the whole areas document. */
    suspend fun setGroupName(areaId: String, kindKey: String, name: String?): Boolean {
        val areas = source.areas.value
        val target = areas.firstOrNull { it.id == areaId && it.id != UNASSIGNED_AREA } ?: return false
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        val names = if (clean == null) target.groupNames - kindKey else target.groupNames + (kindKey to clean)
        if (names == target.groupNames) return true
        return attempt { source.setAreas(areas.filter { it.id != UNASSIGNED_AREA }.map { if (it.id == areaId) it.copy(groupNames = names) else it }) }
    }

    /** Takes each device out of its room's group, keeping the rest of its override. */
    suspend fun ungroup(ids: Collection<String>): Boolean {
        for (id in ids) {
            val entity = source.entities.value[id] ?: continue
            if (entity.ui.ungrouped) continue
            if (!save(id, entity.ui.copy(ungrouped = true))) return false
        }
        return true
    }
```

(import `UNASSIGNED_AREA`).

- [ ] **Step 5: `OverrideDraft`**

Add the field `val grouped: Boolean = true,` at the end of the constructor; in `applyTo` add `ungrouped = !grouped,`; in `from` add `grouped = !card.ui.ungrouped,`.

- [ ] **Step 6: `HomeViewModel.customizeGroup`**

```kotlin
    /** Room groups spec §6: rename, then take [removed] members out. A failed write is a [HomeEvent.SaveFailed]. */
    fun customizeGroup(groupId: String, name: String, removed: List<String>) {
        viewModelScope.launch {
            val group = state.value.card(groupId)?.takeIf { it.isGroup } ?: return@launch
            val renamed = name.trim() == group.name.trim() || editor.setGroupName(group.areaId, RoomGroups.kindKey(group.kind), name)
            if (!renamed || !editor.ungroup(removed)) _events.send(HomeEvent.SaveFailed)
        }
    }
```

- [ ] **Step 7: Run, expect PASS**

Same command as Step 3.

- [ ] **Step 8: Failing `GroupCustomizeSheetTest`**

Robolectric Compose test rendering `GroupCustomizeSheet` directly inside `OikosTheme(ThemeChoice.NIGHT_GLASS)` for a group built like in `GroupCardTest` (three covers):

```kotlin
    @Test
    fun `unticking a member and saving reports it as removed, with the typed name`() {
        var saved: Pair<String, List<String>>? = null
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { GroupCustomizeSheet(group, onSave = { n, r -> saved = n to r }, onDismiss = {}) } }
        compose.onNode(hasSetTextAction()).performTextReplacement("Baie")
        compose.onNodeWithText("Patio door").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals("Baie" to listOf("patio"), saved)
    }

    @Test
    fun `Ungroup all removes every member`() {
        var saved: Pair<String, List<String>>? = null
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { GroupCustomizeSheet(group, onSave = { n, r -> saved = n to r }, onDismiss = {}) } }
        compose.onNodeWithText("Ungroup all").performScrollTo().performClick()
        assertEquals("" to listOf("bay", "side", "patio"), saved)
    }
```

(Check the existing Save label: `R.string.customize_save`; use its English text.)

And in `CustomizeSheetTest` (read it first for its fixture helper):

```kotlin
    @Test
    fun `a groupable cover shows the group switch and saving it off sets ungrouped`() {
        // card = a COVER CardUi with groupable = true (copy an existing fixture and set groupable = true)
        // render CustomizeSheet(card, rooms, onSave = { _, o -> saved = o }, onDismiss = {})
        compose.onNodeWithText("Group with the other shutters in this room").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertTrue(saved!!.ungrouped)
    }
```

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*GroupCustomizeSheetTest' --tests '*CustomizeSheetTest'` → FAIL.

- [ ] **Step 9: `GroupCustomizeSheet.kt`**

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi

/** Room groups spec §6: name, members, Ungroup all. [onSave] gets the typed name and the unticked member ids. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupCustomizeSheet(card: CardUi, onSave: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(card.id) { mutableStateOf(card.name) }
    var kept by rememberSaveable(card.id) { mutableStateOf(card.members.map { it.id }) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.customize_group_title), style = OikosTheme.type.display.copy(fontSize = 22.sp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.customize_group_name)) },
                placeholder = { Text(groupName(card.copy(name = ""))) },
                supportingText = { Text(stringResource(R.string.customize_group_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.customize_group_members), style = OikosTheme.type.label)
            for (member in card.members) {
                val checked = member.id in kept
                Row(
                    Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch)
                        .toggleable(checked, role = Role.Checkbox) { kept = if (it) kept + member.id else kept - member.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(member.name, style = OikosTheme.type.body, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Button(
                onClick = { onSave(name, card.members.map { it.id } - kept.toSet()); onDismiss() },
                modifier = Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch),
            ) { Text(stringResource(R.string.customize_save)) }
            TextButton(onClick = { onSave(card.name, card.members.map { it.id }); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.customize_ungroup_all))
            }
        }
    }
}
```

> The "Ungroup all" test expects the name `""` because the fixture group has no custom name (`card.name == ""`).

- [ ] **Step 10: Device switch in `CustomizeSheet`**

Add `var grouped by rememberSaveable(card.id) { mutableStateOf(start.grouped) }`, pass `grouped = grouped` into the `OverrideDraft(...)` constructor call (named argument, after `securityAsk`), and after the favourite `SwitchRow`:

```kotlin
            if (card.groupable) {
                SwitchRow(stringResource(if (card.kind == Kind.COVER) R.string.customize_grouped_cover else R.string.customize_grouped_light), grouped) { grouped = it }
            }
```

- [ ] **Step 11: Wire the group Customize in `HomeScreen`**

Replace the guarded `CustomizeSheet` block (from Task 3) with:

```kotlin
    val customizeCard = customizeId?.let(state::card)
    if (customizeCard != null) {
        if (customizeCard.isGroup) {
            GroupCustomizeSheet(
                customizeCard,
                onSave = { name, removed -> onCustomizeGroup(customizeCard.id, name, removed) },
                onDismiss = { customizeId = null },
            )
        } else {
            CustomizeSheet(
                customizeCard,
                state.rooms,
                onSave = { newRooms, override -> onCustomize(customizeCard.id, newRooms, override) },
                onDismiss = { customizeId = null },
            )
        }
    }
```

In `HomeRoute`: `onCustomizeGroup = viewModel::customizeGroup`.

- [ ] **Step 12: Run module tests, expect PASS**

Run: `./gradlew :feature:home:testDebugUnitTest`

- [ ] **Step 13: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): customize room groups and regroup devices

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

---

### Task 6: Demo house, screenshots, full check

**Files:**
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`
- Modify: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt` (id list)
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ScreenshotSupport.kt` (copy of `feature/history/src/test/kotlin/com/gekkotron/oikos/feature/history/ScreenshotSupport.kt`, package changed)
- Create: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/GroupScreenshotTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: demo ids `demo_living_shutter` (unchanged), `demo_living_shutter_side`, `demo_living_door_shutter` (no position), `demo_living_ceiling` (RGB), `demo_living_lamp` (unchanged, dimmable), `demo_living_spot` (on/off only).

- [ ] **Step 1: Failing demo test**

In `DemoBrokerTest`, add the new ids to the expected id list at line ~36 (`"demo_living_shutter_side", "demo_living_door_shutter", "demo_living_ceiling", "demo_living_spot"`), keeping the list's existing order convention. Run `./gradlew :core:demo:testDebugUnitTest --tests '*DemoBrokerTest'` → FAIL.

- [ ] **Step 2: Demo entities**

In `DemoHouse.init`, after the existing `cover("demo_living_shutter", …)`:

```kotlin
        cover("demo_living_shutter_side", "Side shutter", "Living room", "demo/living/shutter_side")
        cover("demo_living_door_shutter", "Patio shutter", "Living room", "demo/living/door_shutter", position = false)
        rgbLight("demo_living_ceiling", "Ceiling", "Living room", "demo/living/ceiling")
        plainLight("demo_living_spot", "Spot", "Living room", "demo/living/spot")
```

Give `cover` a `position: Boolean = true` parameter: when false, skip `position_topic`, `position_template`, `set_position_topic`, the `position/set` echo, and publish `obj("state" to "closed")` as the initial doc; the `"OPEN"` / `"CLOSE"` echoes publish only `state`.

Add the two light builders next to `light`:

```kotlin
    private fun rgbLight(id: String, device: String, area: String, topic: String) {
        config("light", id, device, null, area) {
            put("schema", "json")
            put("brightness", true)
            put("brightness_scale", 255)
            putJsonArray("supported_color_modes") { add("xy") }
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        doc(topic, buildJsonObject { put("state", "OFF"); put("brightness", 200); put("color", buildJsonObject { put("x", 0.45); put("y", 0.41) }) })
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }

    private fun plainLight(id: String, device: String, area: String, topic: String) {
        config("light", id, device, null, area) {
            put("schema", "json")
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        doc(topic, obj("state" to "OFF"))
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }
```

Run `./gradlew :core:demo:testDebugUnitTest` → PASS. If `DemoHouseTest` counts entities, update its expected count.

- [ ] **Step 3: Screenshot test**

Copy `ScreenshotSupport.kt` into `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/` with `package com.gekkotron.oikos.feature.home`. Create `GroupScreenshotTest.kt` following `HistoryScreenshotTest` (`@GraphicsMode(NATIVE)`, `@Config(qualifiers = "w411dp-h914dp-xxhdpi")`, `createAndroidComposeRule<ComponentActivity>()`, `OikosTheme(theme) { OikosBackground { … } }`):

```kotlin
    @Test fun `home with groups in Night Glass`() = check(ThemeChoice.NIGHT_GLASS, "groups-home-night", dark = true)
    @Test fun `home with groups in Warm Minimal`() = check(ThemeChoice.WARM_MINIMAL, "groups-home-warm", dark = false)

    private fun check(theme: ThemeChoice, name: String, dark: Boolean) {
        compose.setContent {
            OikosTheme(theme) { OikosBackground { HomeScreen(state = groupState(), onSelect = {}, onTap = {}, onSend = { _, _ -> }, now = LocalTime.NOON) } }
        }
        val bitmap = compose.renderPng(name)
        assertTrue(bitmap.distinctColors() > 8)
        if (dark) assertTrue(bitmap.luminanceAt(4, 4) < 0.35f) else assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }
```

`groupState()` = `HomeUiBuilder.build(...)` with the three covers and two lights from `GroupCardTest`, `RoomFilter.Area("living")`. Then add two more tests that open the shutter sheet and the light sheet (`performClick` on `card-group:living:cover`, `performTouchInput { longClick() }` on `card-group:living:light`) before `renderPng`, named `groups-sheet-covers-night` and `groups-sheet-lights-warm`.

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*GroupScreenshotTest'` → PASS. Open the four PNGs the helper writes (its doc comment says where) and look at them: the group card spans two columns, the badge reads ×3, the mini bars match 100/80/0, nothing overlaps.

- [ ] **Step 4: Full gate**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Known flakes from memory: `DemoAlertSeederTest` (intermittent hang), `DirectConnectionTesterTest`. If one of those fails, rerun once; any other failure is real.

- [ ] **Step 5: Commit**

```bash
git add core/demo feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(demo): living room shutters and lights to show room groups

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NqKbAvvffbsvnsdvtqWtaN"
```

- [ ] **Step 6: Device checklist (the user runs this)**

1. Living room chip: one "Shutters ×N" card; ▲ opens all, ▼ closes all, ■ stops.
2. Sheet: "All to" 50 % moves the positionable shutters only; a member row's ▼ closes that one only; tapping a member opens its sheet, Back returns to the group.
3. Lights card: tap toggles all; long-press → master switch + brightness; tapping a light row opens its hue wheel.
4. Customize group: rename → card shows the new name on a second phone; untick one member → it becomes a single card; its own Customize → "Group with…" on → it rejoins.
5. Pull one shutter's power (or wait for a timeout): one "didn't respond" snackbar, Retry targets it only.
