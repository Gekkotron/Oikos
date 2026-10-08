# Oikos Home-Screen Widgets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One generic, resizable "Oikos devices" Glance widget that shows 1–8 chosen entities, refreshes every 15 min, on ↻ and after each tap, and sends commands from the home screen.

**Architecture:** A new `:feature:widget` module holds the Glance UI, two DataStores (config + display snapshot), a pure `TileMapper`, the refresh/command logic (`WidgetRefresher`) and thin WorkManager workers. All connection work sits behind the `WidgetEntities` interface. `:app` implements it with `HeadlessEntities`: the live foreground source when connected, otherwise a short-lived transport built by a private `TransportFactory` plus a fresh `MqttDiscoverySource` restored from the entity cache. The widget renders only from the snapshot store and never connects.

**Tech Stack:** Kotlin 2.4, Jetpack Glance 1.2.0 (`glance-appwidget`, `glance-material3`, `glance-appwidget-testing`), WorkManager 2.12, DataStore 1.2.1, kotlinx-serialization, Hilt in `:app` only, JUnit 4 + Robolectric 4.17 for tests.

**Spec:** `docs/superpowers/specs/2026-10-08-oikos-widgets-design.md`

## Global Constraints

- Every commit uses `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`. Never edit `.git/config`.
- Strings in English **and** French (`values/strings.xml` + `values-fr/strings.xml`) from the first commit that adds them.
- 1–8 entities per widget; periodic refresh 15 min (`NetworkType.CONNECTED`); command confirmation 5 s (the existing `CommandTracker` timeout); refresh wait budget 8 s; connect budget 10 s.
- Pending older than 30 s renders as failed; values older than 30 min render dimmed.
- The snapshot and config stores hold display data only: no alarm code, no credentials. Both live in `noBackupFilesDir`.
- Headless MQTT client id is `clientIdFor(installId) + "-widget"` (a duplicate id makes the broker kick the other client).
- No house-specific values anywhere (hosts, ids, names).
- Kotlin regex literals: escape literal `]` and `}` (Android ICU crashes where the JVM does not).
- `:feature:widget` depends on `:core:model`, `:core:designsystem`, `:core:history`. It does **not** depend on `:core:source`, `:core:transport` or `:core:data`, and does not use Hilt.

## Spec amendment (applied in Task 9)

The spec's `RoutePicker` extraction is dropped: `TransportFactory` (`:app`) already builds Demo, Direct, Gateway and Routed transports (with the TCP probe and fallback). `HeadlessEntities` builds its own `TransportFactory` on a session scope. `OneShotPublisher` is left unchanged.

## Review Focus

1. **A partial journal must never overwrite the entity cache.** A headless session that did not settle (offline, or a short replay) must not call `cache.save`; otherwise the app's cold start loses rooms. Test in Task 3.
2. **A tap while the app is open must not open a second MQTT connection.** The foreground short-circuit must be used whenever the live transport is connected. Test in Task 3.
3. **A config referencing an entity that was never seen or was removed** renders "—"/"Removed" and never crashes the widget. Removal is only declared after a *complete* (settled) discovery. Tests in Tasks 1 and 4.
4. **An alarm tile needing a code** must never send without one, and the code must not reach any DataStore. Tests in Tasks 1 and 4.
5. **Removing the last widget cancels the periodic work, and adding one enqueues it once** (KEEP, not REPLACE, so the period does not restart on every config). Test in Task 4.

---

## File Structure

```
feature/widget/
  build.gradle.kts
  src/main/AndroidManifest.xml                         receiver + 2 activities
  src/main/res/xml/oikos_widget_info.xml               appwidget-provider
  src/main/res/drawable/ic_widget_*.xml                8 vector icons
  src/main/res/values{,-fr}/strings.xml
  src/main/kotlin/com/gekkotron/oikos/feature/widget/
    model/TileModels.kt          TileValue, TileIcon, TileStatus, TileSnapshot, LinkStatus, WidgetSnapshot, WidgetConfig
    model/WidgetCommand.kt       serializable command subset + toCommand(code)
    TileMapper.kt                Entity + EntityState → TileSnapshot
    TileActions.kt               TileSnapshot + config → TileAction (Send / AskCode / OpenHistory / OpenHome)
    WidgetEntities.kt            interface + RefreshResult / SendResult / CatalogResult / Failure
    store/WidgetConfigStore.kt
    store/WidgetSnapshotStore.kt
    WidgetRefresher.kt           refresh() / send() logic over the stores
    WidgetComponent.kt           WidgetComponent, WidgetComponentProvider, WidgetUpdater
    work/WidgetScheduler.kt      WorkManager wrapper
    work/WidgetWorkers.kt        WidgetRefreshWorker, WidgetCommandWorker, WidgetWorkerFactory
    ui/OikosWidget.kt            GlanceAppWidget + receiver + callbacks
    ui/WidgetContent.kt          pure Glance composables over WidgetUiState
    ui/TileText.kt               TileValue → String
    config/WidgetConfigActivity.kt + ConfigViewModel.kt
    code/WidgetCodeActivity.kt
app/
  connection/HeadlessEntities.kt
  connection/WidgetMirror.kt
  connection/EntitySourceSession.kt   + peek()
  AppLinks.kt (renamed from DeepLinks.kt) + AppViewModel / MainActivity / OikosNavHost changes
  OikosApplication.kt, di/WidgetModule.kt, SetUpAgainCleanup.kt
```

---

### Task 1: Module, models, `TileMapper`, `TileActions`

**Files:**
- Modify: `settings.gradle.kts` (add `include(":feature:widget")`), `gradle/libs.versions.toml`
- Create: `feature/widget/build.gradle.kts`, `feature/widget/src/main/AndroidManifest.xml` (empty `<manifest/>` for now)
- Create: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/model/TileModels.kt`, `model/WidgetCommand.kt`, `TileMapper.kt`, `TileActions.kt`
- Test: `feature/widget/src/test/kotlin/com/gekkotron/oikos/feature/widget/TileMapperTest.kt`, `TileActionsTest.kt`

**Interfaces:**
- Produces: `TileSnapshot`, `TileValue`, `TileIcon`, `TileStatus`, `LinkStatus`, `WidgetSnapshot`, `WidgetConfig`, `WidgetCommand`, `TileMapper.map(...)`, `TileActions.forTap(...)`, `TileAction`.

- [ ] **Step 1: Catalog and module**

`gradle/libs.versions.toml`, under `[versions]` add `glance = "1.2.0"`; under `[libraries]` add:

```toml
androidx-glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
androidx-glance-material3 = { module = "androidx.glance:glance-material3", version.ref = "glance" }
androidx-glance-appwidget-testing = { module = "androidx.glance:glance-appwidget-testing", version.ref = "glance" }
```

`feature/widget/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.feature.widget"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:history"))
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.androidx.work.testing)
}
```

`libs.androidx.datastore` is the alias `:core:data` already uses. Add `include(":feature:widget")` after `include(":feature:alerts")` in `settings.gradle.kts`.

- [ ] **Step 2: Models**

`model/TileModels.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget.model

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a tile shows; rendered to text at draw time so the locale is never baked into storage. */
@Serializable
sealed interface TileValue {
    @Serializable @SerialName("none") data object None : TileValue
    @Serializable @SerialName("onoff") data class OnOff(val on: Boolean) : TileValue
    @Serializable @SerialName("light") data class Light(val on: Boolean, val percent: Int?) : TileValue
    @Serializable @SerialName("measure") data class Measure(val value: Double, val unit: String?) : TileValue
    @Serializable @SerialName("text") data class Text(val value: String) : TileValue
    @Serializable @SerialName("contact") data class Contact(val deviceClass: String?, val on: Boolean) : TileValue
    @Serializable @SerialName("cover") data class Cover(val state: CoverState, val position: Int?) : TileValue
    @Serializable @SerialName("climate") data class Climate(val mode: String?, val current: Double?, val target: Double?) : TileValue
    @Serializable @SerialName("alarm") data class Alarm(val state: AlarmState) : TileValue
}

enum class TileIcon { LIGHT, SWITCH, COVER, CLIMATE, TEMPERATURE, ALARM, BUTTON, SENSOR }

enum class TileStatus { OK, PENDING, FAILED, REMOVED }

@Serializable
data class TileSnapshot(
    val uniqueId: String,
    val name: String,
    val kind: Kind,
    val icon: TileIcon,
    val value: TileValue,
    val available: Boolean = true,
    val alarmModes: Set<AlarmMode> = emptySet(),
    val codeArmRequired: Boolean = false,
    val codeDisarmRequired: Boolean = false,
    val codeFormat: CodeFormat = CodeFormat.NUMBER,
    val hvacModes: List<String> = emptyList(),
    /** The last non-"off" climate mode seen, so "on" can restore it. */
    val lastOnMode: String? = null,
    val historyAvailable: Boolean = false,
    val status: TileStatus = TileStatus.OK,
    val pendingSince: Long? = null,
    /** Epoch ms of the last live (non-cached) value; null when only cached values were ever seen. */
    val updatedAt: Long? = null,
) {
    /** A pending tile whose worker died is shown as failed (spec §4). */
    fun effectiveStatus(now: Long): TileStatus =
        if (status == TileStatus.PENDING && pendingSince != null && now - pendingSince > PENDING_LIMIT_MS) TileStatus.FAILED else status

    fun isStale(now: Long): Boolean = updatedAt == null || now - updatedAt > STALE_MS

    companion object {
        const val PENDING_LIMIT_MS = 30_000L
        const val STALE_MS = 30 * 60_000L
    }
}

enum class LinkStatus { UNKNOWN, FRESH, OFFLINE, AUTH_FAILED, NOT_CONFIGURED }

@Serializable
data class WidgetSnapshot(
    val tiles: Map<String, TileSnapshot> = emptyMap(),
    val link: LinkStatus = LinkStatus.UNKNOWN,
    /** Epoch ms of the last successful refresh. */
    val lastFresh: Long? = null,
)

@Serializable
data class WidgetConfig(val uniqueIds: List<String> = emptyList(), val armMode: AlarmMode? = null) {
    init {
        require(uniqueIds.size <= MAX_ENTITIES) { "at most $MAX_ENTITIES entities" }
    }

    companion object {
        const val MAX_ENTITIES = 8
    }
}
```

`model/WidgetCommand.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget.model

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The commands a widget can send; serialisable so a worker's input can carry one. Never holds a code. */
@Serializable
sealed interface WidgetCommand {
    @Serializable @SerialName("toggle") data object Toggle : WidgetCommand
    @Serializable @SerialName("open") data object Open : WidgetCommand
    @Serializable @SerialName("close") data object Close : WidgetCommand
    @Serializable @SerialName("stop") data object Stop : WidgetCommand
    @Serializable @SerialName("press") data object Press : WidgetCommand
    @Serializable @SerialName("hvac") data class Hvac(val mode: String) : WidgetCommand
    @Serializable @SerialName("arm") data class Arm(val mode: AlarmMode) : WidgetCommand
    @Serializable @SerialName("disarm") data object Disarm : WidgetCommand

    fun toCommand(code: String? = null): Command = when (this) {
        Toggle -> Command.Toggle
        Open -> Command.Open
        Close -> Command.Close
        Stop -> Command.Stop
        Press -> Command.Press
        is Hvac -> Command.SetHvacMode(mode)
        is Arm -> Command.Arm(mode, code)
        Disarm -> Command.Disarm(code)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json
        fun decode(text: String): WidgetCommand? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
```

- [ ] **Step 3: Write the failing `TileMapperTest`**

```kotlin
package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TileMapperTest {
    private object NoBindings : Bindings
    private fun entity(id: String, kind: Kind, unit: String? = null, deviceClass: String? = null, caps: Capabilities = Capabilities(), name: String = "Lamp") =
        Entity(id, null, name, kind, deviceClass, unit, null, caps, NoBindings)
    private val t0 = Instant.ofEpochMilli(1_000_000)

    @Test fun light_with_brightness_maps_percent() {
        val tile = TileMapper.map(entity("l", Kind.LIGHT), EntityState("l", StateValue.Light(true, 128), lastUpdate = t0), null, historyEnabled = false, complete = true)!!
        assertEquals(TileValue.Light(true, 50), tile.value)
        assertEquals(TileIcon.LIGHT, tile.icon)
        assertEquals(1_000_000L, tile.updatedAt)
    }

    @Test fun temperature_sensor_gets_temperature_icon() {
        val tile = TileMapper.map(entity("t", Kind.SENSOR, unit = "°C", deviceClass = "temperature"), EntityState("t", StateValue.Numeric(21.4), lastUpdate = t0), null, false, true)!!
        assertEquals(TileValue.Measure(21.4, "°C"), tile.value)
        assertEquals(TileIcon.TEMPERATURE, tile.icon)
    }

    @Test fun stale_cached_state_keeps_previous_updatedAt() {
        val prev = TileMapper.map(entity("s", Kind.SWITCH), EntityState("s", StateValue.OnOff(true), lastUpdate = t0), null, false, true)
        val tile = TileMapper.map(entity("s", Kind.SWITCH), EntityState("s", StateValue.OnOff(false), lastUpdate = t0.plusSeconds(60), stale = true), prev, false, true)!!
        assertEquals(1_000_000L, tile.updatedAt)
    }

    @Test fun climate_remembers_last_non_off_mode() {
        val caps = Capabilities(hvacModes = listOf("off", "heat", "cool"))
        val on = TileMapper.map(entity("c", Kind.CLIMATE, caps = caps), EntityState("c", StateValue.Climate("cool", 22.0, 21.0), lastUpdate = t0), null, false, true)!!
        val off = TileMapper.map(entity("c", Kind.CLIMATE, caps = caps), EntityState("c", StateValue.Climate("off", 22.0, 21.0), lastUpdate = t0), on, false, true)!!
        assertEquals("cool", off.lastOnMode)
        assertEquals(listOf("off", "heat", "cool"), off.hvacModes)
    }

    @Test fun alarm_copies_code_requirements() {
        val caps = Capabilities(alarmModes = setOf(AlarmMode.AWAY), codeDisarmRequired = true, codeFormat = CodeFormat.TEXT)
        val tile = TileMapper.map(entity("a", Kind.ALARM_PANEL, caps = caps), EntityState("a", StateValue.Alarm(AlarmState.ARMED_AWAY), lastUpdate = t0), null, false, true)!!
        assertTrue(tile.codeDisarmRequired)
        assertEquals(CodeFormat.TEXT, tile.codeFormat)
        assertEquals(setOf(AlarmMode.AWAY), tile.alarmModes)
    }

    @Test fun missing_entity_after_complete_discovery_is_removed() {
        val prev = TileMapper.map(entity("s", Kind.SWITCH), EntityState("s", StateValue.OnOff(true), lastUpdate = t0), null, false, true)
        assertEquals(TileStatus.REMOVED, TileMapper.map(null, null, prev, false, complete = true)!!.status)
    }

    @Test fun missing_entity_after_incomplete_discovery_keeps_previous() {
        val prev = TileMapper.map(entity("s", Kind.SWITCH), EntityState("s", StateValue.OnOff(true), lastUpdate = t0), null, false, true)
        assertEquals(prev, TileMapper.map(null, null, prev, false, complete = false))
    }

    @Test fun never_seen_entity_maps_to_null() {
        assertNull(TileMapper.map(null, null, null, false, complete = true))
    }

    @Test fun unknown_state_is_none_and_override_name_wins() {
        val e = entity("s", Kind.SWITCH).copy(ui = UiOverride(name = "Kettle"))
        val tile = TileMapper.map(e, null, null, false, true)!!
        assertEquals(TileValue.None, tile.value)
        assertEquals("Kettle", tile.name)
    }

    @Test fun binary_sensor_is_contact() {
        val tile = TileMapper.map(entity("d", Kind.BINARY_SENSOR, deviceClass = "door"), EntityState("d", StateValue.OnOff(true), lastUpdate = t0), null, false, true)!!
        assertEquals(TileValue.Contact("door", true), tile.value)
    }
}
```

Check how `Entity.name` and `ui.name` combine in `MqttDiscoverySource.publishEntities` (`grep -n "ui.name\|override.name" core/source/src/main -r`). If the source already applies the override to `name`, drop the `ui.name` fallback in the mapper and change the last test to build the entity with `name = "Kettle"`.

- [ ] **Step 4: Run, expect FAIL** — `./gradlew :feature:widget:testDebugUnitTest --tests '*TileMapperTest*'` → compilation error, `TileMapper` unresolved.

- [ ] **Step 5: Implement `TileMapper`**

```kotlin
package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import kotlin.math.roundToInt

/** Entity + state → what the widget stores (spec §3.2). Pure. */
object TileMapper {
    /**
     * [previous] carries memory across refreshes (last climate mode, last live time). [complete] = discovery settled, so a
     * missing entity really is gone. Returns null for an id never seen at all.
     */
    fun map(entity: Entity?, state: EntityState?, previous: TileSnapshot?, historyEnabled: Boolean, complete: Boolean): TileSnapshot? {
        if (entity == null) return when {
            previous == null -> null
            complete -> previous.copy(status = TileStatus.REMOVED, pendingSince = null)
            else -> previous
        }
        val value = state?.value?.let { valueOf(entity, it) } ?: TileValue.None
        val live = state != null && !state.stale && state.lastUpdate != null
        val climateMode = (state?.value as? StateValue.Climate)?.mode
        return TileSnapshot(
            uniqueId = entity.uniqueId,
            name = entity.ui.name ?: entity.name,
            kind = entity.kind,
            icon = iconOf(entity),
            value = value,
            available = state?.available ?: true,
            alarmModes = entity.capabilities.alarmModes,
            codeArmRequired = entity.capabilities.codeArmRequired,
            codeDisarmRequired = entity.capabilities.codeDisarmRequired,
            codeFormat = entity.capabilities.codeFormat,
            hvacModes = entity.capabilities.hvacModes,
            lastOnMode = climateMode?.takeIf { it != "off" } ?: previous?.lastOnMode,
            historyAvailable = historyEnabled && HistoryRefs.of(entity) != null,
            status = TileStatus.OK,
            pendingSince = null,
            updatedAt = if (live) state!!.lastUpdate!!.toEpochMilli() else previous?.updatedAt,
        )
    }

    private fun valueOf(entity: Entity, value: StateValue): TileValue = when (value) {
        StateValue.Unknown, StateValue.Unsupported -> TileValue.None
        is StateValue.OnOff -> if (entity.kind == Kind.BINARY_SENSOR) TileValue.Contact(entity.deviceClass, value.on) else TileValue.OnOff(value.on)
        is StateValue.Light -> TileValue.Light(value.on, value.brightness?.let { (it * 100 / 255.0).roundToInt() })
        is StateValue.Numeric -> TileValue.Measure(value.value, entity.unit)
        is StateValue.Text -> TileValue.Text(value.value)
        is StateValue.Cover -> TileValue.Cover(value.state, value.position)
        is StateValue.Climate -> TileValue.Climate(value.mode, value.current, value.target)
        is StateValue.Alarm -> TileValue.Alarm(value.state)
    }

    private fun iconOf(entity: Entity): TileIcon = when (entity.kind) {
        Kind.LIGHT -> TileIcon.LIGHT
        Kind.SWITCH -> TileIcon.SWITCH
        Kind.COVER -> TileIcon.COVER
        Kind.CLIMATE -> TileIcon.CLIMATE
        Kind.ALARM_PANEL -> TileIcon.ALARM
        Kind.BUTTON -> TileIcon.BUTTON
        Kind.SENSOR -> if (entity.deviceClass == "temperature") TileIcon.TEMPERATURE else TileIcon.SENSOR
        Kind.BINARY_SENSOR, Kind.NUMBER, Kind.SELECT -> TileIcon.SENSOR
    }
}
```

- [ ] **Step 6: Run, expect PASS** — same command.

- [ ] **Step 7: Write the failing `TileActionsTest`**

```kotlin
package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.*
import com.gekkotron.oikos.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TileActionsTest {
    private fun tile(kind: Kind, value: TileValue, f: (TileSnapshot) -> TileSnapshot = { it }) =
        f(TileSnapshot("x", "X", kind, TileIcon.SENSOR, value))

    @Test fun switch_toggles() = assertEquals(TileAction.Send("x", WidgetCommand.Toggle), TileActions.forTap(tile(Kind.SWITCH, TileValue.OnOff(true)), null))

    @Test fun closed_cover_opens_open_cover_closes() {
        assertEquals(TileAction.Send("x", WidgetCommand.Open), TileActions.forTap(tile(Kind.COVER, TileValue.Cover(CoverState.CLOSED, 0)), null))
        assertEquals(TileAction.Send("x", WidgetCommand.Close), TileActions.forTap(tile(Kind.COVER, TileValue.Cover(CoverState.OPEN, 100)), null))
    }

    @Test fun climate_off_turns_on_with_last_mode_else_first_non_off() {
        val off = tile(Kind.CLIMATE, TileValue.Climate("off", null, null)) { it.copy(hvacModes = listOf("off", "heat"), lastOnMode = "cool") }
        assertEquals(TileAction.Send("x", WidgetCommand.Hvac("cool")), TileActions.forTap(off, null))
        assertEquals(TileAction.Send("x", WidgetCommand.Hvac("heat")), TileActions.forTap(off.copy(lastOnMode = null), null))
        val on = off.copy(value = TileValue.Climate("heat", null, null))
        assertEquals(TileAction.Send("x", WidgetCommand.Hvac("off")), TileActions.forTap(on, null))
    }

    @Test fun climate_without_modes_opens_home() {
        assertEquals(TileAction.OpenHome, TileActions.forTap(tile(Kind.CLIMATE, TileValue.Climate("off", null, null)), null))
    }

    @Test fun disarmed_alarm_arms_with_configured_mode_or_away_or_first() {
        val a = tile(Kind.ALARM_PANEL, TileValue.Alarm(AlarmState.DISARMED)) { it.copy(alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY)) }
        assertEquals(TileAction.Send("x", WidgetCommand.Arm(AlarmMode.HOME)), TileActions.forTap(a, AlarmMode.HOME))
        assertEquals(TileAction.Send("x", WidgetCommand.Arm(AlarmMode.AWAY)), TileActions.forTap(a, null))
        assertEquals(TileAction.Send("x", WidgetCommand.Arm(AlarmMode.HOME)), TileActions.forTap(a.copy(alarmModes = setOf(AlarmMode.HOME)), AlarmMode.NIGHT))
    }

    @Test fun armed_alarm_needing_code_asks_for_it() {
        val a = tile(Kind.ALARM_PANEL, TileValue.Alarm(AlarmState.ARMED_AWAY)) { it.copy(codeDisarmRequired = true, codeFormat = CodeFormat.TEXT) }
        assertEquals(TileAction.AskCode("x", WidgetCommand.Disarm, CodeFormat.TEXT), TileActions.forTap(a, null))
    }

    @Test fun sensors_open_history_when_available_else_home() {
        val s = tile(Kind.SENSOR, TileValue.Measure(1.0, "W"))
        assertEquals(TileAction.OpenHome, TileActions.forTap(s, null))
        assertEquals(TileAction.OpenHistory("x"), TileActions.forTap(s.copy(historyAvailable = true), null))
    }

    @Test fun removed_or_unavailable_tiles_open_home() {
        assertEquals(TileAction.OpenHome, TileActions.forTap(tile(Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.REMOVED) }, null))
        assertEquals(TileAction.OpenHome, TileActions.forTap(tile(Kind.SWITCH, TileValue.OnOff(true)) { it.copy(available = false) }, null))
    }

    @Test fun cover_buttons() {
        val c = tile(Kind.COVER, TileValue.Cover(CoverState.OPEN, 100))
        assertEquals(TileAction.Send("x", WidgetCommand.Stop), TileActions.coverButton(c, WidgetCommand.Stop))
    }

    @Test fun widget_command_round_trips_and_never_carries_a_code() {
        val encoded = WidgetCommand.Arm(AlarmMode.NIGHT).encode()
        assertEquals(WidgetCommand.Arm(AlarmMode.NIGHT), WidgetCommand.decode(encoded))
        assertEquals(Command.Disarm("1234"), WidgetCommand.Disarm.toCommand("1234"))
    }
}
```

- [ ] **Step 8: Run, expect FAIL** — `./gradlew :feature:widget:testDebugUnitTest --tests '*TileActionsTest*'`.

- [ ] **Step 9: Implement `TileActions`**

```kotlin
package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind

sealed interface TileAction {
    data class Send(val id: String, val command: WidgetCommand) : TileAction
    data class AskCode(val id: String, val command: WidgetCommand, val format: CodeFormat) : TileAction
    data class OpenHistory(val id: String) : TileAction
    data object OpenHome : TileAction
}

/** What a tap does, per kind (spec §2.3). Pure. */
object TileActions {
    fun forTap(tile: TileSnapshot, armMode: AlarmMode?): TileAction {
        if (tile.status == TileStatus.REMOVED || !tile.available) return TileAction.OpenHome
        val id = tile.uniqueId
        return when (tile.kind) {
            Kind.LIGHT, Kind.SWITCH -> TileAction.Send(id, WidgetCommand.Toggle)
            Kind.BUTTON -> TileAction.Send(id, WidgetCommand.Press)
            Kind.COVER -> {
                val closed = (tile.value as? TileValue.Cover)?.state == CoverState.CLOSED
                TileAction.Send(id, if (closed) WidgetCommand.Open else WidgetCommand.Close)
            }
            Kind.CLIMATE -> climate(tile)
            Kind.ALARM_PANEL -> alarm(tile, armMode)
            Kind.SENSOR, Kind.BINARY_SENSOR, Kind.NUMBER, Kind.SELECT ->
                if (tile.historyAvailable) TileAction.OpenHistory(id) else TileAction.OpenHome
        }
    }

    fun coverButton(tile: TileSnapshot, command: WidgetCommand): TileAction =
        if (tile.status == TileStatus.REMOVED || !tile.available) TileAction.OpenHome else TileAction.Send(tile.uniqueId, command)

    private fun climate(tile: TileSnapshot): TileAction {
        val mode = (tile.value as? TileValue.Climate)?.mode
        val onModes = tile.hvacModes.filter { it != "off" }
        if (onModes.isEmpty()) return TileAction.OpenHome
        if (mode != null && mode != "off") return TileAction.Send(tile.uniqueId, WidgetCommand.Hvac("off"))
        val target = tile.lastOnMode?.takeIf { it in tile.hvacModes || tile.hvacModes.isEmpty() } ?: tile.lastOnMode ?: onModes.first()
        return TileAction.Send(tile.uniqueId, WidgetCommand.Hvac(target))
    }

    private fun alarm(tile: TileSnapshot, armMode: AlarmMode?): TileAction {
        val state = (tile.value as? TileValue.Alarm)?.state ?: return TileAction.OpenHome
        val (command, needsCode) = if (state == AlarmState.DISARMED) {
            val mode = armMode?.takeIf { it in tile.alarmModes }
                ?: AlarmMode.AWAY.takeIf { it in tile.alarmModes }
                ?: tile.alarmModes.firstOrNull()
                ?: return TileAction.OpenHome
            WidgetCommand.Arm(mode) to tile.codeArmRequired
        } else {
            WidgetCommand.Disarm to tile.codeDisarmRequired
        }
        return if (needsCode) TileAction.AskCode(tile.uniqueId, command, tile.codeFormat) else TileAction.Send(tile.uniqueId, command)
    }
}
```

Note on climate: `lastOnMode` wins even if absent from `hvacModes` (a device may stop advertising a mode; the broker will reject it, which shows "!"). Simplify the `target` line to `tile.lastOnMode ?: onModes.first()` if you prefer; the tests pass either way.

- [ ] **Step 10: Run, expect PASS**, then `./gradlew :feature:widget:testDebugUnitTest`.

- [ ] **Step 11: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml feature/widget
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): module, tile models, mapper and tap actions"
```

---

### Task 2: `WidgetConfigStore` and `WidgetSnapshotStore`

**Files:**
- Create: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/store/WidgetConfigStore.kt`, `store/WidgetSnapshotStore.kt`
- Test: `feature/widget/src/test/kotlin/com/gekkotron/oikos/feature/widget/store/WidgetStoresTest.kt`

**Interfaces:**
- Consumes: `WidgetConfig`, `WidgetSnapshot`, `TileSnapshot` (Task 1).
- Produces:
  - `interface WidgetConfigStore { val configs: Flow<Map<Int, WidgetConfig>>; suspend fun get(appWidgetId: Int): WidgetConfig?; suspend fun put(appWidgetId: Int, config: WidgetConfig); suspend fun remove(appWidgetIds: IntArray); suspend fun allIds(): Set<String> }`
  - `interface WidgetSnapshotStore { val snapshot: Flow<WidgetSnapshot>; suspend fun current(): WidgetSnapshot; suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot); suspend fun clear() }`
  - `FileWidgetConfigStore.create(file: File, scope: CoroutineScope)`, `FileWidgetSnapshotStore.create(file: File, scope: CoroutineScope)`, plus `create(context, scope)` overloads using `context.noBackupFilesDir.resolve("widget-config.json" / "widget-snapshot.json")`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gekkotron.oikos.feature.widget.store

import com.gekkotron.oikos.feature.widget.model.*
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WidgetStoresTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun config_round_trip_remove_and_union() = runTest {
        val store = FileWidgetConfigStore.create(tmp.newFile("c.json").also { it.delete() }, backgroundScope)
        store.put(1, WidgetConfig(listOf("a", "b"), AlarmMode.HOME))
        store.put(2, WidgetConfig(listOf("b", "c")))
        assertEquals(WidgetConfig(listOf("a", "b"), AlarmMode.HOME), store.get(1))
        assertEquals(setOf("a", "b", "c"), store.allIds())
        store.remove(intArrayOf(1))
        assertNull(store.get(1))
        assertEquals(setOf("b", "c"), store.allIds())
    }

    @Test fun config_survives_a_new_instance() = runTest {
        val file = tmp.newFile("c2.json").also { it.delete() }
        FileWidgetConfigStore.create(file, backgroundScope).put(7, WidgetConfig(listOf("x")))
        // DataStore allows one instance per file per process: read the file through a second store only after the first is gone.
        assertTrue(file.readText().contains("\"x\""))
    }

    @Test fun snapshot_update_and_clear() = runTest {
        val store = FileWidgetSnapshotStore.create(tmp.newFile("s.json").also { it.delete() }, backgroundScope)
        val tile = TileSnapshot("a", "A", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))
        store.update { it.copy(tiles = mapOf("a" to tile), link = LinkStatus.FRESH, lastFresh = 5) }
        assertEquals(tile, store.current().tiles["a"])
        assertEquals(LinkStatus.FRESH, store.snapshot.first().link)
        store.clear()
        assertEquals(WidgetSnapshot(), store.current())
    }

    @Test fun corrupt_snapshot_file_falls_back_to_empty() = runTest {
        val file = tmp.newFile("bad.json").apply { writeText("{not json") }
        val store = FileWidgetSnapshotStore.create(file, backgroundScope)
        assertEquals(WidgetSnapshot(), store.current())
    }

    @Test fun snapshot_json_never_contains_a_code_field() = runTest {
        val file = tmp.newFile("s2.json").also { it.delete() }
        val store = FileWidgetSnapshotStore.create(file, backgroundScope)
        store.update { it.copy(tiles = mapOf("a" to TileSnapshot("a", "A", Kind.ALARM_PANEL, TileIcon.ALARM, TileValue.None, codeDisarmRequired = true))) }
        assertFalse(Regex("\"code\"\\s*:").containsMatchIn(file.readText()))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :feature:widget:testDebugUnitTest --tests '*WidgetStoresTest*'`.

- [ ] **Step 3: Implement both stores**

Follow `core/data/.../EntityCache.kt`'s DataStore pattern (serializer object + `ReplaceFileCorruptionHandler`). `WidgetConfigStore.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

interface WidgetConfigStore {
    val configs: Flow<Map<Int, WidgetConfig>>
    suspend fun get(appWidgetId: Int): WidgetConfig?
    suspend fun put(appWidgetId: Int, config: WidgetConfig)
    suspend fun remove(appWidgetIds: IntArray)
    /** Every entity id on any widget. */
    suspend fun allIds(): Set<String>
}

@Serializable
internal data class StoredConfigs(val widgets: Map<Int, WidgetConfig> = emptyMap())

class FileWidgetConfigStore private constructor(private val store: DataStore<StoredConfigs>) : WidgetConfigStore {
    override val configs: Flow<Map<Int, WidgetConfig>> = store.data.map { it.widgets }
    override suspend fun get(appWidgetId: Int): WidgetConfig? = configs.first()[appWidgetId]
    override suspend fun put(appWidgetId: Int, config: WidgetConfig) { store.updateData { it.copy(widgets = it.widgets + (appWidgetId to config)) } }
    override suspend fun remove(appWidgetIds: IntArray) { store.updateData { s -> s.copy(widgets = s.widgets - appWidgetIds.toSet()) } }
    override suspend fun allIds(): Set<String> = configs.first().values.flatMapTo(linkedSetOf()) { it.uniqueIds }

    companion object {
        const val FILE = "widget-config.json"
        fun create(context: Context, scope: CoroutineScope) = create(context.noBackupFilesDir.resolve(FILE), scope)
        fun create(file: File, scope: CoroutineScope) = FileWidgetConfigStore(
            DataStoreFactory.create(JsonSerializer(StoredConfigs.serializer(), StoredConfigs()), ReplaceFileCorruptionHandler { StoredConfigs() }, scope = scope, produceFile = { file }),
        )
    }
}

/** A JSON DataStore serializer; a corrupt file throws [CorruptionException] so the handler can replace it. */
internal class JsonSerializer<T>(private val strategy: kotlinx.serialization.KSerializer<T>, override val defaultValue: T) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(strategy, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("widget store", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("widget store", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) = output.write(json.encodeToString(strategy, t).encodeToByteArray())

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
```

`WidgetSnapshotStore.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

/** Display data for every tile, plus the link status for the footer (spec §3.2). No secrets. */
interface WidgetSnapshotStore {
    val snapshot: Flow<WidgetSnapshot>
    suspend fun current(): WidgetSnapshot
    suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot)
    suspend fun clear()
}

class FileWidgetSnapshotStore private constructor(private val store: DataStore<WidgetSnapshot>) : WidgetSnapshotStore {
    override val snapshot: Flow<WidgetSnapshot> = store.data
    override suspend fun current(): WidgetSnapshot = store.data.first()
    override suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot) { store.updateData(transform) }
    override suspend fun clear() { store.updateData { WidgetSnapshot() } }

    companion object {
        const val FILE = "widget-snapshot.json"
        fun create(context: Context, scope: CoroutineScope) = create(context.noBackupFilesDir.resolve(FILE), scope)
        fun create(file: File, scope: CoroutineScope) = FileWidgetSnapshotStore(
            DataStoreFactory.create(JsonSerializer(WidgetSnapshot.serializer(), WidgetSnapshot()), ReplaceFileCorruptionHandler { WidgetSnapshot() }, scope = scope, produceFile = { file }),
        )
    }
}
```

`WidgetConfig.init` throws `IllegalArgumentException` on >8 ids; the serializer maps that to corruption, which resets the file. That is acceptable: only a hand-edited file can hit it.

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Commit** — `git add feature/widget && git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): config and snapshot stores"`

---

### Task 3: `WidgetEntities` contract and `HeadlessEntities` in `:app`

**Files:**
- Create: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/WidgetEntities.kt`
- Modify: `app/build.gradle.kts` (add `implementation(project(":feature:widget"))`)
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/connection/EntitySourceSession.kt` (add `peek()`)
- Create: `app/src/main/kotlin/com/gekkotron/oikos/connection/HeadlessEntities.kt`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/HeadlessEntitiesTest.kt`

**Interfaces:**
- Produces (in `:feature:widget`, package `com.gekkotron.oikos.feature.widget`):

```kotlin
enum class Failure { OFFLINE, AUTH_FAILED, NOT_CONFIGURED }

sealed interface RefreshResult {
    /** [complete]: discovery settled, so an id missing from [entities] is really gone. */
    data class Ok(val entities: Map<String, Entity>, val states: Map<String, EntityState>, val complete: Boolean, val historyEnabled: Boolean) : RefreshResult
    data class Failed(val reason: Failure) : RefreshResult
}

sealed interface SendResult {
    data class Confirmed(val entity: Entity, val state: EntityState, val historyEnabled: Boolean) : SendResult
    data object NotConfirmed : SendResult
    data class Failed(val reason: Failure) : SendResult
}

sealed interface CatalogResult {
    data class Ok(val entities: List<Entity>, val areas: List<Area>) : CatalogResult
    data class Failed(val reason: Failure) : CatalogResult
}

interface WidgetEntities {
    suspend fun refresh(ids: Set<String>): RefreshResult
    suspend fun send(id: String, command: Command): SendResult
    /** Every entity, for the picker: live, else fresh from the broker, else from the cache when offline. */
    suspend fun catalog(): CatalogResult
}
```

- Produces (in `:app`): `EntitySourceSession.peek(): EntitySource?`, and `class HeadlessEntities(...) : WidgetEntities` with this constructor:

```kotlin
class HeadlessEntities(
    private val profile: suspend () -> ProfileState,       // first non-Loading state
    private val route: () -> RoutePreference,
    private val installId: suspend () -> String,
    /** The foreground source when the app's transport is connected, else null. */
    private val live: () -> EntitySource?,
    private val foreground: () -> Boolean,
    private val cache: EntityCache,
    private val transports: (TransportSpec, CoroutineScope) -> ManagedTransport,
    private val sources: (MqttTransport, CoroutineScope, String) -> MqttDiscoverySource =
        { t, s, prefix -> MqttDiscoverySource(t, s, prefix = prefix) },
    private val connectBudget: Duration = 10.seconds,
    private val waitBudget: Duration = 8.seconds,
    private val settleBudget: Duration = 3.seconds,
    private val confirmBudget: Duration = 7.seconds,
    private val context: CoroutineContext = Dispatchers.IO,
) : WidgetEntities
```

- [ ] **Step 1: Write the contract file** `WidgetEntities.kt` with exactly the types above (imports from `com.gekkotron.oikos.model`). No test: it is types only.

- [ ] **Step 2: Add `peek()` to `EntitySourceSession`**

```kotlin
    /** The current source without building one; null before the first [current] or after [reset]. */
    @Synchronized
    fun peek(): EntitySource? = source
```

- [ ] **Step 3: Write the failing `HeadlessEntitiesTest`**

Use `FakeManagedTransport` / `FakeMqttTransport` from `testFixtures(project(":core:transport"))` (already on `app`'s test classpath), and a small in-memory `EntityCache`. Read `FakeMqttTransport.kt` first: `retain(topic, payload)` stores a retained message delivered on subscribe, and `setLink(state)` drives `link`.

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.*
import com.gekkotron.oikos.feature.widget.*
import com.gekkotron.oikos.model.*
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.*
import com.gekkotron.oikos.transport.testing.FakeManagedTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HeadlessEntitiesTest {
    private val switchId = "sw1"
    private val config = """{"command_topic":"sw/1/set","state_topic":"sw/1","unique_id":"$switchId","name":"Kettle"}"""
    private val profile = ConnectionProfile.Direct(LocalBroker("broker.test"))

    private class MemoryCache(var stored: Map<String, String> = emptyMap(), var saves: Int = 0) : EntityCache {
        override suspend fun load(key: String) = stored
        override suspend fun save(key: String, messages: Map<String, String>) { stored = messages; saves++ }
        override suspend fun clear() { stored = emptyMap() }
    }

    private class Harness(val entities: HeadlessEntities, val transports: MutableList<FakeManagedTransport>, val cache: MemoryCache)

    private fun TestScope.harness(
        state: ProfileState = ProfileState.Ready(profile),
        live: EntitySource? = null,
        cache: MemoryCache = MemoryCache(),
        connect: (FakeManagedTransport) -> Unit = { t ->
            t.fake.retain("homeassistant/switch/sw1/config", config)
            t.fake.retain("sw/1", "ON")
            t.fake.setLink(LinkState.Connected(TransportMode.DIRECT))
        },
    ): Harness {
        val made = mutableListOf<FakeManagedTransport>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entities = HeadlessEntities(
            profile = { state }, route = { RoutePreference.AUTO }, installId = { "abcd-ef01-2345" },
            live = { live }, foreground = { false }, cache = cache,
            transports = { spec, _ -> FakeManagedTransport(spec).also { made += it; connect(it) } },
            context = dispatcher,
        )
        return Harness(entities, made, cache)
    }

    @Test fun refresh_connects_reads_and_closes() = runTest {
        val h = harness()
        val result = h.entities.refresh(setOf(switchId)) as RefreshResult.Ok
        assertEquals(StateValue.OnOff(true), result.states[switchId]?.value)
        assertTrue(h.transports.single().closed)
        assertTrue((h.transports.single().spec as TransportSpec.Direct).clientId.endsWith("-widget"))
    }

    @Test fun no_profile_is_not_configured_and_never_connects() = runTest {
        val h = harness(state = ProfileState.None)
        assertEquals(RefreshResult.Failed(Failure.NOT_CONFIGURED), h.entities.refresh(setOf(switchId)))
        assertTrue(h.transports.isEmpty())
    }

    @Test fun rejected_credentials_are_auth_failed() = runTest {
        val h = harness(connect = { it.fake.setLink(LinkState.Offline(LinkState.Offline.REJECTED)) })
        assertEquals(RefreshResult.Failed(Failure.AUTH_FAILED), h.entities.refresh(setOf(switchId)))
        assertTrue(h.transports.single().closed)
    }

    @Test fun silent_broker_is_offline_after_connect_budget() = runTest {
        val h = harness(connect = { it.fake.setLink(LinkState.Connecting) })
        assertEquals(RefreshResult.Failed(Failure.OFFLINE), h.entities.refresh(setOf(switchId)))
    }

    @Test fun foreground_source_is_used_without_a_new_connection() = runTest {
        val live = FakeLiveSource(switchId) // see helper below
        val h = harness(live = live)
        val result = h.entities.refresh(setOf(switchId)) as RefreshResult.Ok
        assertEquals(StateValue.OnOff(true), result.states[switchId]?.value)
        assertTrue(h.transports.isEmpty())
    }

    @Test fun unsettled_session_never_overwrites_the_cache() = runTest {
        // The broker connects but replays nothing and the wanted id never goes live: the session must not save a partial journal.
        val cache = MemoryCache(stored = mapOf("homeassistant/switch/sw1/config" to config, "sw/1" to "OFF"))
        val h = harness(cache = cache, connect = { it.fake.setLink(LinkState.Connected(TransportMode.DIRECT)) })
        h.entities.refresh(setOf(switchId))
        assertEquals(0, cache.saves)
    }

    @Test fun settled_session_saves_the_cache() = runTest {
        val h = harness()
        h.entities.refresh(setOf(switchId))
        assertEquals(1, h.cache.saves)
        assertEquals("ON", h.cache.stored["sw/1"])
    }

    @Test fun send_publishes_and_confirms_on_state_echo() = runTest {
        val h = harness(connect = { t ->
            t.fake.retain("homeassistant/switch/sw1/config", config)
            t.fake.retain("sw/1", "ON")
            t.fake.setLink(LinkState.Connected(TransportMode.DIRECT))
            // Echo any command back as the new state, like a real device.
            backgroundScope.launch { t.fake.published.collect { if (it.topic == "sw/1/set") t.fake.deliver("sw/1", it.payloadString()) } }
        })
        val result = h.entities.send(switchId, Command.TurnOff)
        assertTrue(result is SendResult.Confirmed)
        assertEquals(StateValue.OnOff(false), (result as SendResult.Confirmed).state.value)
    }

    @Test fun send_without_echo_is_not_confirmed() = runTest {
        val h = harness()
        assertEquals(SendResult.NotConfirmed, h.entities.send(switchId, Command.TurnOff))
    }

    @Test fun refresh_and_send_never_overlap() = runTest {
        val h = harness()
        val a = async { h.entities.refresh(setOf(switchId)) }
        val b = async { h.entities.refresh(setOf(switchId)) }
        a.await(); b.await()
        assertEquals(2, h.transports.size)
        assertTrue(h.transports[0].closed)
    }

    @Test fun catalog_offline_falls_back_to_cache() = runTest {
        val cache = MemoryCache(stored = mapOf("homeassistant/switch/sw1/config" to config))
        val h = harness(cache = cache, connect = { it.fake.setLink(LinkState.Offline(LinkState.Offline.FAILED)) })
        val result = h.entities.catalog() as CatalogResult.Ok
        assertEquals(listOf(switchId), result.entities.map { it.uniqueId })
    }
}
```

Before writing the test, check how `FakeMqttTransport` exposes what was published (the plan assumes `published` is a flow or list of `MqttMessage` with `payloadString()`). If it is a plain list, poll it in the echo coroutine (`while (true) { … ; yield() }`) or add a small `SharedFlow` to the fixture in this task. `FakeLiveSource` is a minimal `EntitySource` test double: `entities = MutableStateFlow(mapOf(id to Entity(...Kind.SWITCH...)))`, `states() = flowOf(mapOf(id to EntityState(id, StateValue.OnOff(true), lastUpdate = Instant.EPOCH)))`, `link = MutableStateFlow(LinkState.Connected(TransportMode.DIRECT))`, every other member a no-op. Put it at the bottom of the test file. If `refresh_and_send_never_overlap` cannot observe the overlap with fakes, keep it as a sequencing check (the second session starts after the first closed: assert `h.transports[0].closed` was true when `transports[1]` was created, by recording it in the factory lambda).

- [ ] **Step 4: Run, expect FAIL** — `./gradlew :app:testPersonalDebugUnitTest --tests '*HeadlessEntitiesTest*'` (use the variant name `./gradlew :app:tasks --all | grep UnitTest` shows; the app has flavors).

- [ ] **Step 5: Implement `HeadlessEntities`**

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.EntityCache
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.RoutePreference
import com.gekkotron.oikos.data.effectiveHistoryUrl
import com.gekkotron.oikos.feature.widget.CatalogResult
import com.gekkotron.oikos.feature.widget.Failure
import com.gekkotron.oikos.feature.widget.RefreshResult
import com.gekkotron.oikos.feature.widget.SendResult
import com.gekkotron.oikos.feature.widget.WidgetEntities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.ManagedTransport
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.TransportSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The widget's door to the house (widgets spec §3.3): the live source when the app is connected, otherwise a short
 * session (own transport + fresh discovery source restored from the cache) that is always closed before returning.
 */
class HeadlessEntities(/* constructor exactly as in Interfaces */) : WidgetEntities {
    private val mutex = Mutex()

    private sealed interface Session<out T> {
        data class Ok<T>(val value: T) : Session<T>
        data class Failed(val reason: Failure) : Session<Nothing>
    }

    override suspend fun refresh(ids: Set<String>): RefreshResult {
        live()?.let { source ->
            return RefreshResult.Ok(source.entities.value, source.states().first(), complete = true, historyEnabled = historyEnabled(currentProfile()))
        }
        return when (val s = session(save = true) { source, history ->
            withTimeoutOrNull(waitBudget) { source.states().first { m -> ids.all { m[it]?.let { st -> !st.stale && st.lastUpdate != null } == true } } }
            withTimeoutOrNull(settleBudget) { source.hasSettled.first { it } }
            RefreshResult.Ok(source.entities.value, source.states().first(), complete = source.hasSettled.value, historyEnabled = history)
        }) {
            is Session.Ok -> s.value
            is Session.Failed -> RefreshResult.Failed(s.reason)
        }
    }

    override suspend fun send(id: String, command: Command): SendResult {
        live()?.let { return sendOn(it, id, command, historyEnabled(currentProfile())) }
        return when (val s = session(save = true) { source, history ->
            if (withTimeoutOrNull(waitBudget) { source.entities.first { id in it } } == null) SendResult.NotConfirmed
            else sendOn(source, id, command, history)
        }) {
            is Session.Ok -> s.value
            is Session.Failed -> SendResult.Failed(s.reason)
        }
    }

    override suspend fun catalog(): CatalogResult {
        live()?.let { return CatalogResult.Ok(it.entities.value.values.toList(), it.areas.value) }
        return when (val s = session(save = false, allowOffline = true) { source, _ ->
            withTimeoutOrNull(waitBudget) { source.hasSettled.first { it } }
            CatalogResult.Ok(source.entities.value.values.toList(), source.areas.value)
        }) {
            is Session.Ok -> s.value
            is Session.Failed -> CatalogResult.Failed(s.reason)
        }
    }

    /** Sends, then waits for the tracker to confirm (state echo) or report a failure. */
    private suspend fun sendOn(source: EntitySource, id: String, command: Command, history: Boolean): SendResult = coroutineScope {
        val failed = MutableStateFlow(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { source.commandFailures.first { it.uniqueId == id }; failed.value = true }
        try {
            if (source.send(id, command) is CommandResult.Rejected) return@coroutineScope SendResult.NotConfirmed
            val done = withTimeoutOrNull(confirmBudget) {
                combine(source.states(), failed) { states, f -> f to states[id] }.first { (f, st) -> f || st?.pending == false }
            }
            val state = done?.second
            val entity = source.entities.value[id]
            if (done == null || done.first || state == null || entity == null) SendResult.NotConfirmed
            else SendResult.Confirmed(entity, state, history)
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun <T> session(save: Boolean, allowOffline: Boolean = false, block: suspend (MqttDiscoverySource, Boolean) -> T): Session<T> =
        mutex.withLock {
            withContext(context) {
                val state = profile()
                val ready = state as? ProfileState.Ready ?: return@withContext Session.Failed(Failure.NOT_CONFIGURED)
                val spec = state.toSpec(clientIdFor(installId()) + CLIENT_SUFFIX, route()) ?: return@withContext Session.Failed(Failure.NOT_CONFIGURED)
                val scope = CoroutineScope(SupervisorJob() + context)
                val transport = transports(spec, scope)
                try {
                    // The source subscribes to the transport's messages before it starts (ManagedTransport contract).
                    val source = sources(transport, scope, ready.profile.prefix)
                    val key = (ready.profile as? ConnectionProfile.Direct)?.cacheKey()
                    if (key != null) source.restore(cache.load(key))
                    transport.start()
                    val link = withTimeoutOrNull(connectBudget) {
                        transport.link.first { it is LinkState.Connected || (it is LinkState.Offline && it.reason in AUTH_REASONS) }
                    }
                    val failure = when {
                        link is LinkState.Connected -> null
                        link is LinkState.Offline -> Failure.AUTH_FAILED
                        else -> Failure.OFFLINE
                    }
                    if (failure != null && !(allowOffline && failure == Failure.OFFLINE)) return@withContext Session.Failed(failure)
                    val value = block(source, historyEnabled(ready.profile))
                    // Only a settled journal is complete; a partial one would shrink the app's cold-start house (Review Focus 1).
                    if (save && key != null && failure == null && source.hasSettled.value && !foreground()) {
                        val journal = source.journal()
                        if (journal.isNotEmpty()) cache.save(key, journal)
                    }
                    Session.Ok(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Session.Failed(Failure.OFFLINE)
                } finally {
                    withContext(NonCancellable) {
                        try { transport.close() } catch (e: Exception) { /* close() is contractually quiet */ }
                        scope.cancel()
                    }
                }
            }
        }

    private suspend fun currentProfile(): ConnectionProfile? = (profile() as? ProfileState.Ready)?.profile

    private fun historyEnabled(profile: ConnectionProfile?): Boolean = (profile as? ConnectionProfile.Direct)?.effectiveHistoryUrl() != null

    companion object {
        const val CLIENT_SUFFIX = "-widget"
        private val AUTH_REASONS = setOf(LinkState.Offline.REJECTED, LinkState.Offline.KEY_REJECTED)
    }
}
```

Note: `catalog()` with `allowOffline = true` still builds the source over a transport that never connected; `restore()` fills it from the cache, so the picker works offline (spec §2.1). `effectiveHistoryUrl()` never returns null today. Keep the null-check form anyway, so a future optional URL does not silently break the widget.

- [ ] **Step 6: Run, expect PASS.** If `source.hasSettled` never turns true under the test scheduler, advance virtual time: the source settles after its replay goes quiet (`MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE` and its quiet window). `runTest` skips delays automatically, so this normally needs nothing.

- [ ] **Step 7: Commit** — `git add feature/widget app && git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): WidgetEntities contract and headless sessions in the app"`

---

### Task 4: `WidgetRefresher`, scheduler, workers, component

**Files:**
- Create: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/WidgetRefresher.kt`, `WidgetComponent.kt`, `work/WidgetScheduler.kt`, `work/WidgetWorkers.kt`
- Test: `feature/widget/src/test/kotlin/com/gekkotron/oikos/feature/widget/WidgetRefresherTest.kt`, `work/WidgetSchedulerTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3 types.
- Produces:

```kotlin
fun interface WidgetUpdater { suspend fun updateAll() }

class WidgetRefresher(
    private val entities: WidgetEntities,
    private val configs: WidgetConfigStore,
    private val snapshots: WidgetSnapshotStore,
    private val updater: WidgetUpdater,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh()
    suspend fun send(id: String, command: WidgetCommand, code: String?)
}

class WidgetComponent(
    val entities: WidgetEntities,
    val configs: WidgetConfigStore,
    val snapshots: WidgetSnapshotStore,
    val scheduler: WidgetScheduler,
    val refresher: WidgetRefresher,
)
interface WidgetComponentProvider { fun widgetComponent(): WidgetComponent }

interface WidgetScheduler {
    fun ensurePeriodic()
    fun cancelPeriodic()
    fun refreshNow()
    fun send(id: String, command: WidgetCommand, code: String?)
}
class WorkManagerWidgetScheduler(private val workManager: () -> WorkManager) : WidgetScheduler

class WidgetWorkerFactory(private val refresher: () -> WidgetRefresher) : WorkerFactory()
```

- [ ] **Step 1: Write the failing `WidgetRefresherTest`**

```kotlin
package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.*
import com.gekkotron.oikos.feature.widget.store.*
import com.gekkotron.oikos.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WidgetRefresherTest {
    private object B : Bindings
    private val sw = Entity("s", null, "Kettle", Kind.SWITCH, null, null, null, Capabilities(), B)
    private fun on(v: Boolean, t: Long = 100) = EntityState("s", StateValue.OnOff(v), lastUpdate = Instant.ofEpochMilli(t))

    private class MemConfigs(val map: MutableMap<Int, WidgetConfig> = mutableMapOf()) : WidgetConfigStore {
        override val configs = MutableStateFlow<Map<Int, WidgetConfig>>(map)
        override suspend fun get(appWidgetId: Int) = map[appWidgetId]
        override suspend fun put(appWidgetId: Int, config: WidgetConfig) { map[appWidgetId] = config; configs.value = map.toMap() }
        override suspend fun remove(appWidgetIds: IntArray) { appWidgetIds.forEach(map::remove); configs.value = map.toMap() }
        override suspend fun allIds() = map.values.flatMap { it.uniqueIds }.toSet()
    }
    private class MemSnapshots : WidgetSnapshotStore {
        val flow = MutableStateFlow(WidgetSnapshot())
        override val snapshot: Flow<WidgetSnapshot> = flow
        override suspend fun current() = flow.value
        override suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot) { flow.value = transform(flow.value) }
        override suspend fun clear() { flow.value = WidgetSnapshot() }
    }
    private class FakeEntities(var refresh: RefreshResult = RefreshResult.Failed(Failure.OFFLINE), var send: suspend () -> SendResult = { SendResult.NotConfirmed }) : WidgetEntities {
        val sent = mutableListOf<Pair<String, Command>>()
        override suspend fun refresh(ids: Set<String>) = refresh
        override suspend fun send(id: String, command: Command): SendResult { sent += id to command; return send() }
        override suspend fun catalog() = CatalogResult.Failed(Failure.OFFLINE)
    }

    private var now = 1_000L
    private var updates = 0
    private val configs = MemConfigs(mutableMapOf(1 to WidgetConfig(listOf("s"))))
    private val snaps = MemSnapshots()
    private fun refresher(e: WidgetEntities) = WidgetRefresher(e, configs, snaps, { updates++ }, { now })

    @Test fun refresh_ok_writes_tiles_and_fresh_link() = runTest {
        refresher(FakeEntities(RefreshResult.Ok(mapOf("s" to sw), mapOf("s" to on(true)), complete = true, historyEnabled = false))).refresh()
        assertEquals(TileValue.OnOff(true), snaps.flow.value.tiles["s"]?.value)
        assertEquals(LinkStatus.FRESH, snaps.flow.value.link)
        assertEquals(1_000L, snaps.flow.value.lastFresh)
        assertEquals(1, updates)
    }

    @Test fun refresh_failure_keeps_tiles_and_sets_link() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to TileSnapshot("s", "Kettle", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))), link = LinkStatus.FRESH, lastFresh = 5)
        refresher(FakeEntities(RefreshResult.Failed(Failure.AUTH_FAILED))).refresh()
        assertEquals(TileValue.OnOff(true), snaps.flow.value.tiles["s"]?.value)
        assertEquals(LinkStatus.AUTH_FAILED, snaps.flow.value.link)
        assertEquals(5L, snaps.flow.value.lastFresh)
    }

    @Test fun refresh_with_no_widgets_does_nothing() = runTest {
        configs.map.clear()
        val e = FakeEntities()
        refresher(e).refresh()
        assertEquals(0, updates)
    }

    @Test fun refresh_drops_tiles_no_widget_uses() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("gone" to TileSnapshot("gone", "G", Kind.SWITCH, TileIcon.SWITCH, TileValue.None)))
        refresher(FakeEntities(RefreshResult.Ok(mapOf("s" to sw), mapOf("s" to on(true)), true, false))).refresh()
        assertEquals(setOf("s"), snaps.flow.value.tiles.keys)
    }

    @Test fun send_marks_pending_then_confirmed() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to TileSnapshot("s", "Kettle", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))))
        val gate = CompletableDeferred<Unit>()
        val e = FakeEntities(send = { gate.await(); SendResult.Confirmed(sw, on(false, 2_000), false) })
        val job = async { refresher(e).send("s", WidgetCommand.Toggle, null) }
        testScheduler.runCurrent()
        assertEquals(TileStatus.PENDING, snaps.flow.value.tiles["s"]?.status)
        assertEquals(1_000L, snaps.flow.value.tiles["s"]?.pendingSince)
        gate.complete(Unit); job.await()
        assertEquals(TileStatus.OK, snaps.flow.value.tiles["s"]?.status)
        assertEquals(TileValue.OnOff(false), snaps.flow.value.tiles["s"]?.value)
        assertEquals(listOf("s" to Command.Toggle), e.sent)
    }

    @Test fun send_not_confirmed_marks_failed_and_keeps_value() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to TileSnapshot("s", "Kettle", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))))
        refresher(FakeEntities()).send("s", WidgetCommand.Toggle, null)
        assertEquals(TileStatus.FAILED, snaps.flow.value.tiles["s"]?.status)
        assertEquals(TileValue.OnOff(true), snaps.flow.value.tiles["s"]?.value)
    }

    @Test fun send_failed_offline_also_sets_link() = runTest {
        snaps.flow.value = WidgetSnapshot(tiles = mapOf("s" to TileSnapshot("s", "Kettle", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(true))))
        refresher(FakeEntities(send = { SendResult.Failed(Failure.OFFLINE) })).send("s", WidgetCommand.Toggle, null)
        assertEquals(TileStatus.FAILED, snaps.flow.value.tiles["s"]?.status)
        assertEquals(LinkStatus.OFFLINE, snaps.flow.value.link)
    }

    @Test fun disarm_code_reaches_the_command_but_never_the_snapshot() = runTest {
        val e = FakeEntities()
        refresher(e).send("a", WidgetCommand.Disarm, "1234")
        assertEquals(Command.Disarm("1234"), e.sent.single().second)
        assertFalse(snaps.flow.value.toString().contains("1234"))
    }
}
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement `WidgetRefresher` and `WidgetComponent`**

```kotlin
package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore

fun interface WidgetUpdater {
    suspend fun updateAll()
}

/** The refresh and command logic behind the workers (spec §3.4). Workers stay thin so this is testable without WorkManager. */
class WidgetRefresher(
    private val entities: WidgetEntities,
    private val configs: WidgetConfigStore,
    private val snapshots: WidgetSnapshotStore,
    private val updater: WidgetUpdater,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh() {
        val ids = configs.allIds()
        if (ids.isEmpty()) return
        when (val result = entities.refresh(ids)) {
            is RefreshResult.Ok -> snapshots.update { snap ->
                val tiles = ids.mapNotNull { id ->
                    TileMapper.map(result.entities[id], result.states[id], snap.tiles[id], result.historyEnabled, result.complete)?.let { id to it }
                }.toMap()
                snap.copy(tiles = tiles, link = LinkStatus.FRESH, lastFresh = now())
            }
            is RefreshResult.Failed -> snapshots.update { it.copy(link = result.reason.toLink()) }
        }
        updater.updateAll()
    }

    suspend fun send(id: String, command: WidgetCommand, code: String?) {
        val at = now()
        snapshots.update { snap ->
            val tile = snap.tiles[id] ?: return@update snap
            snap.copy(tiles = snap.tiles + (id to tile.copy(status = TileStatus.PENDING, pendingSince = at)))
        }
        updater.updateAll()
        val result = entities.send(id, command.toCommand(code))
        snapshots.update { snap ->
            val previous = snap.tiles[id]
            when (result) {
                is SendResult.Confirmed -> {
                    val tile = TileMapper.map(result.entity, result.state, previous, result.historyEnabled, complete = false) ?: return@update snap
                    snap.copy(tiles = snap.tiles + (id to tile), link = LinkStatus.FRESH, lastFresh = now())
                }
                SendResult.NotConfirmed -> snap.markFailed(id)
                is SendResult.Failed -> snap.markFailed(id).copy(link = result.reason.toLink())
            }
        }
        updater.updateAll()
    }

    private fun com.gekkotron.oikos.feature.widget.model.WidgetSnapshot.markFailed(id: String) =
        tiles[id]?.let { copy(tiles = tiles + (id to it.copy(status = TileStatus.FAILED, pendingSince = null))) } ?: this

    private fun Failure.toLink() = when (this) {
        Failure.OFFLINE -> LinkStatus.OFFLINE
        Failure.AUTH_FAILED -> LinkStatus.AUTH_FAILED
        Failure.NOT_CONFIGURED -> LinkStatus.NOT_CONFIGURED
    }
}
```

`WidgetComponent.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget

import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import com.gekkotron.oikos.feature.widget.work.WidgetScheduler

/** What the widget's Android entry points need; `OikosApplication` hands it out (same pattern as `PushComponent`). */
class WidgetComponent(
    val entities: WidgetEntities,
    val configs: WidgetConfigStore,
    val snapshots: WidgetSnapshotStore,
    val scheduler: WidgetScheduler,
    val refresher: WidgetRefresher,
)

interface WidgetComponentProvider {
    fun widgetComponent(): WidgetComponent
}
```

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Write the failing `WidgetSchedulerTest`** (Robolectric + `WorkManagerTestInitHelper`)

```kotlin
package com.gekkotron.oikos.feature.widget.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSchedulerTest {
    private lateinit var wm: WorkManager
    private lateinit var scheduler: WorkManagerWidgetScheduler

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        wm = WorkManager.getInstance(context)
        scheduler = WorkManagerWidgetScheduler { wm }
    }

    private fun periodic() = wm.getWorkInfosForUniqueWork(WorkManagerWidgetScheduler.PERIODIC).get()

    @Test fun ensure_periodic_twice_keeps_one_work_with_same_id() {
        scheduler.ensurePeriodic()
        val first = periodic().single().id
        scheduler.ensurePeriodic()
        assertEquals(first, periodic().single().id)
    }

    @Test fun cancel_periodic_cancels() {
        scheduler.ensurePeriodic()
        scheduler.cancelPeriodic()
        assertEquals(WorkInfo.State.CANCELLED, periodic().single().state)
    }

    @Test fun send_input_carries_the_command_and_code() {
        scheduler.send("a", WidgetCommand.Disarm, "1234")
        val info = wm.getWorkInfosByTag(WorkManagerWidgetScheduler.TAG_COMMAND).get().single()
        assertNotNull(info)
    }
}
```

- [ ] **Step 6: Run, expect FAIL.**

- [ ] **Step 7: Implement scheduler and workers**

`work/WidgetScheduler.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import java.util.concurrent.TimeUnit

interface WidgetScheduler {
    fun ensurePeriodic()
    fun cancelPeriodic()
    fun refreshNow()
    fun send(id: String, command: WidgetCommand, code: String?)
}

class WorkManagerWidgetScheduler(private val workManager: () -> WorkManager) : WidgetScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** KEEP: adding a widget must not restart the 15-minute period (Review Focus 5). */
    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        workManager().enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelPeriodic() {
        workManager().cancelUniqueWork(PERIODIC)
    }

    override fun refreshNow() {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(network).build()
        workManager().enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Expedited; the code (if any) lives only in the work input, which WorkManager prunes after completion. */
    override fun send(id: String, command: WidgetCommand, code: String?) {
        val request = OneTimeWorkRequestBuilder<WidgetCommandWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_COMMAND)
            .setInputData(workDataOf(WidgetCommandWorker.KEY_ID to id, WidgetCommandWorker.KEY_COMMAND to command.encode(), WidgetCommandWorker.KEY_CODE to code))
            .build()
        workManager().enqueue(request)
    }

    companion object {
        const val PERIODIC = "oikos-widget-refresh"
        const val NOW = "oikos-widget-refresh-now"
        const val TAG_COMMAND = "oikos-widget-command"
    }
}
```

`work/WidgetWorkers.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.WidgetRefresher
import com.gekkotron.oikos.feature.widget.model.WidgetCommand

class WidgetRefreshWorker(context: Context, params: WorkerParameters, private val refresher: WidgetRefresher) : CoroutineWorker(context, params) {
    /** Always success: an offline house is shown in the footer, and the next period retries (no retry storm). */
    override suspend fun doWork(): Result {
        refresher.refresh()
        return Result.success()
    }
}

class WidgetCommandWorker(context: Context, params: WorkerParameters, private val refresher: WidgetRefresher) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val command = inputData.getString(KEY_COMMAND)?.let(WidgetCommand::decode) ?: return Result.failure()
        refresher.send(id, command, inputData.getString(KEY_CODE))
        return Result.success()
    }

    /** Android 9 to 11 run expedited work as a foreground service and need a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_switch)
            .setContentTitle(applicationContext.getString(R.string.widget_sending))
            .setOngoing(true)
            .build(),
    )

    companion object {
        const val KEY_ID = "id"
        const val KEY_COMMAND = "command"
        const val KEY_CODE = "code"
        private const val NOTIFICATION_ID = 0x0D1C
        /** `AlertChannel.SYSTEM.id` (`:core:alerts`), created by `AlertChannels.ensure()` at app start; a literal to keep this module off `:core:alerts`. */
        const val CHANNEL_ID = "system"
    }
}

class WidgetWorkerFactory(private val refresher: () -> WidgetRefresher) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            WidgetRefreshWorker::class.java.name -> WidgetRefreshWorker(appContext, workerParameters, refresher())
            WidgetCommandWorker::class.java.name -> WidgetCommandWorker(appContext, workerParameters, refresher())
            else -> null
        }
}
```

`R.drawable.ic_widget_switch` and `R.string.widget_sending` arrive in Task 5. To keep this task compiling, add now a minimal `res/values/strings.xml` + `values-fr/strings.xml` with `widget_sending` ("Sending…" / "Envoi…") and `res/drawable/ic_widget_switch.xml` (copy the 24 dp vector from Task 5 Step 1).

- [ ] **Step 8: Run, expect PASS** — `./gradlew :feature:widget:testDebugUnitTest`.

- [ ] **Step 9: Commit** — `git add feature/widget && git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): refresher, scheduler and workers"`

---

### Task 5: Glance widget UI, receiver, resources

**Files:**
- Create: `feature/widget/src/main/res/drawable/ic_widget_{light,switch,cover,climate,temperature,alarm,button,sensor,refresh}.xml`
- Create/extend: `feature/widget/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create: `feature/widget/src/main/res/xml/oikos_widget_info.xml`
- Modify: `feature/widget/src/main/AndroidManifest.xml` (receiver)
- Create: `ui/TileText.kt`, `ui/WidgetContent.kt`, `ui/OikosWidget.kt`
- Test: `feature/widget/src/test/kotlin/com/gekkotron/oikos/feature/widget/ui/WidgetContentTest.kt`, `ui/TileTextTest.kt`

**Interfaces:**
- Consumes: `WidgetComponentProvider`, `WidgetSnapshot`, `WidgetConfig`, `TileActions`.
- Produces:
  - `data class WidgetUiState(val tiles: List<TileSnapshot?>, val ids: List<String>, val armMode: AlarmMode?, val link: LinkStatus, val lastFresh: Long?, val now: Long, val configured: Boolean)`
  - `@Composable fun WidgetContent(state: WidgetUiState, actions: WidgetActions)`, where `interface WidgetActions { fun tap(action: TileAction): Action; fun refresh(): Action; fun reconfigure(): Action; fun openApp(): Action; fun openSettings(): Action }`
  - `class OikosWidget : GlanceAppWidget`, `class OikosWidgetReceiver : GlanceAppWidgetReceiver`, `class SendCallback : ActionCallback`, `class RefreshCallback : ActionCallback`
  - `object AppLinks { const val HISTORY = "oikos://history/"; const val SETTINGS = "oikos://settings" }` in `:feature:widget` (the app parses them in Task 7).

- [ ] **Step 1: Icons and strings**

Each `ic_widget_*.xml` is a 24 dp Material Symbols path with `android:fillColor="#FF000000"` (tinted at draw time with `ColorFilter.tint`). Use the Material Symbols "Outlined" glyphs: `lightbulb`, `toggle_on`, `blinds`, `thermostat`, `device_thermostat`, `shield`, `radio_button_checked`, `sensors`, `refresh`. They are Apache-2.0; NOTICE already covers Material icons (check `NOTICE`, and add a line if not).

`values/strings.xml`:

```xml
<resources>
    <string name="widget_name">Oikos devices</string>
    <string name="widget_description">Your chosen devices at a glance, with one-tap control.</string>
    <string name="widget_sending">Sending…</string>
    <string name="widget_updated">Updated %1$s</string>
    <string name="widget_offline">Offline · %1$s</string>
    <string name="widget_offline_never">Offline</string>
    <string name="widget_sign_in_failed">Sign-in failed</string>
    <string name="widget_set_up">Set up Oikos</string>
    <string name="widget_refresh">Refresh</string>
    <string name="widget_removed">Removed</string>
    <string name="widget_on">On</string>
    <string name="widget_off">Off</string>
    <string name="widget_open">Open</string>
    <string name="widget_closed">Closed</string>
    <string name="widget_opening">Opening</string>
    <string name="widget_closing">Closing</string>
    <string name="widget_stopped">Stopped</string>
    <string name="widget_open_percent">Open %1$d %%</string>
    <string name="widget_detected">Detected</string>
    <string name="widget_clear">Clear</string>
    <string name="widget_cover_open">Open</string>
    <string name="widget_cover_stop">Stop</string>
    <string name="widget_cover_close">Close</string>
    <string name="widget_failed">Not confirmed</string>
    <string name="widget_empty">Long-press to choose devices</string>
    <string name="widget_config_title">Choose devices</string>
    <string name="widget_config_hint">Pick up to %1$d. %2$d fit at the current size.</string>
    <string name="widget_config_save">Done</string>
    <string name="widget_config_arm_mode">Arm mode for %1$s</string>
    <string name="widget_config_offline">Can’t reach your home; showing the devices Oikos last saw.</string>
    <string name="widget_config_search">Search</string>
</resources>
```

`values-fr/strings.xml`: the same keys in French ("Appareils Oikos", "Vos appareils choisis d’un coup d’œil, pilotables d’un geste.", "Envoi…", "Mis à jour %1$s", "Hors ligne · %1$s", "Hors ligne", "Échec de connexion", "Configurer Oikos", "Actualiser", "Supprimé", "Allumé", "Éteint", "Ouvert", "Fermé", "Ouverture", "Fermeture", "Arrêté", "Ouvert à %1$d %%", "Détecté", "RAS", "Ouvrir", "Stop", "Fermer", "Non confirmé", "Appui long pour choisir les appareils", "Choisir les appareils", "Jusqu’à %1$d. %2$d tiennent à cette taille.", "OK", "Mode d’armement pour %1$s", "Maison injoignable ; voici les appareils vus en dernier par Oikos.", "Rechercher").

For alarm state text, reuse `com.gekkotron.oikos.designsystem.alarm.alarmLabel(state)` (designsystem strings, already translated).

- [ ] **Step 2: Provider XML and manifest**

`res/xml/oikos_widget_info.xml`:

```xml
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_description"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:minWidth="110dp"
    android:minHeight="48dp"
    android:minResizeWidth="110dp"
    android:minResizeHeight="48dp"
    android:maxResizeWidth="400dp"
    android:maxResizeHeight="400dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen"
    android:configure="com.gekkotron.oikos.feature.widget.config.WidgetConfigActivity"
    android:widgetFeatures="reconfigurable|configuration_optional" />
```

`updatePeriodMillis="0"`: WorkManager owns refreshes. `configuration_optional` is deliberately **absent** if you want the picker forced on placement. Keep only `reconfigurable` (the spec says placing opens the picker), so the line becomes `android:widgetFeatures="reconfigurable"`.

`AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <receiver
            android:name=".ui.OikosWidgetReceiver"
            android:exported="true"
            android:label="@string/widget_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/oikos_widget_info" />
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 3: Write the failing `TileTextTest`** (Robolectric, for resources)

```kotlin
package com.gekkotron.oikos.feature.widget.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.CoverState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TileTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun measure_uses_one_decimal_and_unit() = assertEquals("21.4 °C", TileText.of(context, TileValue.Measure(21.44, "°C"), Locale.US))
    @Test fun french_decimal_comma() = assertEquals("21,4 °C", TileText.of(context, TileValue.Measure(21.44, "°C"), Locale.FRANCE))
    @Test fun none_is_dash() = assertEquals("—", TileText.of(context, TileValue.None, Locale.US))
    @Test fun light_percent() = assertEquals("50 %", TileText.of(context, TileValue.Light(true, 50), Locale.US))
    @Test fun cover_partial() = assertEquals("Open 60 %", TileText.of(context, TileValue.Cover(CoverState.OPEN, 60), Locale.US))
    @Test fun climate_shows_current_temperature() = assertEquals("20.5°", TileText.of(context, TileValue.Climate("heat", 20.5, 21.0), Locale.US))

    @Config(qualifiers = "fr")
    @Test fun french_on() = assertEquals("Allumé", TileText.of(context, TileValue.OnOff(true), Locale.FRANCE))
}
```

- [ ] **Step 4: Run, expect FAIL.**

- [ ] **Step 5: Implement `TileText`**

```kotlin
package com.gekkotron.oikos.feature.widget.ui

import android.content.Context
import com.gekkotron.oikos.designsystem.alarm.alarmLabel
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.CoverState
import java.text.NumberFormat
import java.util.Locale

/** [TileValue] → the tile's state line. Same number rules as Home's `CardFormatter` (one decimal, locale separators). */
object TileText {
    fun of(context: Context, value: TileValue, locale: Locale): String = when (value) {
        TileValue.None -> "—"
        is TileValue.OnOff -> context.getString(if (value.on) R.string.widget_on else R.string.widget_off)
        is TileValue.Light -> if (value.on && value.percent != null) "${value.percent} %" else context.getString(if (value.on) R.string.widget_on else R.string.widget_off)
        is TileValue.Measure -> listOfNotNull(number(value.value, locale), value.unit?.takeIf { it.isNotBlank() }).joinToString(" ")
        is TileValue.Text -> value.value
        is TileValue.Contact -> context.getString(if (value.on) contactOn(value.deviceClass) else contactOff(value.deviceClass))
        is TileValue.Cover -> cover(context, value)
        is TileValue.Climate -> value.current?.let { number(it, locale) + "°" } ?: (value.mode ?: "—")
        is TileValue.Alarm -> context.getString(alarmLabel(value.state))
    }

    private fun cover(context: Context, value: TileValue.Cover): String = when {
        value.state == CoverState.OPEN && value.position != null && value.position in 1..99 -> context.getString(R.string.widget_open_percent, value.position)
        value.state == CoverState.OPEN -> context.getString(R.string.widget_open)
        value.state == CoverState.CLOSED -> context.getString(R.string.widget_closed)
        value.state == CoverState.OPENING -> context.getString(R.string.widget_opening)
        value.state == CoverState.CLOSING -> context.getString(R.string.widget_closing)
        value.state == CoverState.STOPPED -> value.position?.let { context.getString(R.string.widget_open_percent, it) } ?: context.getString(R.string.widget_stopped)
        else -> "—"
    }

    private fun contactOn(deviceClass: String?) = if (deviceClass in OPENINGS) R.string.widget_open else R.string.widget_detected
    private fun contactOff(deviceClass: String?) = if (deviceClass in OPENINGS) R.string.widget_closed else R.string.widget_clear

    fun number(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 0
    }.format(value)

    private val OPENINGS = setOf("door", "window", "garage_door", "opening")
}
```

- [ ] **Step 6: Run, expect PASS.**

- [ ] **Step 7: Write the failing `WidgetContentTest`** (Glance unit test)

```kotlin
package com.gekkotron.oikos.feature.widget.ui

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasClickAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.model.*
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetContentTest {
    private val now = 10_000_000L
    private fun tile(id: String, name: String, kind: Kind, value: TileValue, f: (TileSnapshot) -> TileSnapshot = { it }) =
        f(TileSnapshot(id, name, kind, TileIcon.SWITCH, value, updatedAt = now))

    private val none: Action = androidx.glance.appwidget.action.actionStartActivity(android.content.ComponentName("test", "test.Noop"))
    private val noop = object : WidgetActions {
        override fun tap(action: TileAction): Action = none
        override fun refresh(): Action = none
        override fun reconfigure(): Action = none
        override fun openApp(): Action = none
        override fun openSettings(): Action = none
    }

    private fun state(vararg tiles: TileSnapshot?, link: LinkStatus = LinkStatus.FRESH, configured: Boolean = true) =
        WidgetUiState(tiles.toList(), tiles.mapIndexed { i, t -> t?.uniqueId ?: "missing$i" }, null, link, now - 60_000, now, configured)

    @Test fun small_size_shows_first_tile_only() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext<Context>())
        setAppWidgetSize(DpSize(110.dp, 48.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), tile("b", "Lamp", Kind.LIGHT, TileValue.Light(false, null))), noop) }
        onNode(hasText("Kettle")).assertExists()
        onNode(hasText("Lamp")).assertDoesNotExist()
    }

    @Test fun large_size_shows_all_tiles_and_footer() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext<Context>())
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), tile("b", "Blind", Kind.COVER, TileValue.Cover(CoverState.OPEN, 60))), noop) }
        onNode(hasText("Kettle")).assertExists()
        onNode(hasText("Open 60 %")).assertExists()
        onNode(hasText("Updated", substring = true)).assertExists()
    }

    @Test fun missing_tile_shows_dash_and_removed_shows_removed() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext<Context>())
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(null, tile("b", "Old", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.REMOVED) }), noop) }
        onNode(hasText("—")).assertExists()
        onNode(hasText("Removed")).assertExists()
    }

    @Test fun not_configured_shows_set_up() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext<Context>())
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), link = LinkStatus.NOT_CONFIGURED), noop) }
        onNode(hasText("Set up Oikos")).assertExists()
    }

    @Test fun empty_config_shows_hint() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext<Context>())
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { WidgetContent(state(configured = false), noop) }
        onNode(hasText("Long-press to choose devices")).assertExists()
    }

    @Test fun failed_tile_shows_marker() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext<Context>())
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.FAILED) }), noop) }
        onNode(hasText("!")).assertExists()
    }
}
```

If `setContext` is not available in the Glance version, drop it: `runGlanceAppWidgetUnitTest` uses the Robolectric application context when run with `AndroidJUnit4`.

- [ ] **Step 8: Run, expect FAIL.**

- [ ] **Step 9: Implement `WidgetContent`**

```kotlin
package com.gekkotron.oikos.feature.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.TileActions
import com.gekkotron.oikos.feature.widget.model.*
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class WidgetUiState(
    /** One entry per configured id, in order; null = never seen. */
    val tiles: List<TileSnapshot?>,
    val ids: List<String>,
    val armMode: AlarmMode?,
    val link: LinkStatus,
    val lastFresh: Long?,
    val now: Long,
    val configured: Boolean,
)

interface WidgetActions {
    fun tap(action: TileAction): Action
    fun refresh(): Action
    fun reconfigure(): Action
    fun openApp(): Action
    fun openSettings(): Action
}

@Composable
fun WidgetContent(state: WidgetUiState, actions: WidgetActions) {
    val size = LocalSize.current
    Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(8.dp)) {
        when {
            state.link == LinkStatus.NOT_CONFIGURED -> Message(LocalContext.current.getString(R.string.widget_set_up), actions.openApp())
            !state.configured -> Message(LocalContext.current.getString(R.string.widget_empty), actions.reconfigure())
            size.height < 100.dp -> state.tiles.firstOrNull().let { Tile(it, state, actions, large = true, modifier = GlanceModifier.fillMaxSize()) }
            else -> {
                Grid(state, actions, columns = if (size.width >= 180.dp) 2 else 1, rows = ((size.height - 28.dp) / 64.dp).toInt().coerceAtLeast(1), modifier = GlanceModifier.defaultWeight())
                Footer(state, actions)
            }
        }
    }
}

@Composable
private fun Grid(state: WidgetUiState, actions: WidgetActions, columns: Int, rows: Int, modifier: GlanceModifier) {
    val shown = state.tiles.take(columns * rows)
    Column(modifier.fillMaxWidth()) {
        shown.chunked(columns).forEach { row ->
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                row.forEach { Tile(it, state, actions, large = false, modifier = GlanceModifier.defaultWeight().padding(end = 4.dp)) }
                repeat(columns - row.size) { Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun Tile(tile: TileSnapshot?, state: WidgetUiState, actions: WidgetActions, large: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val status = tile?.effectiveStatus(state.now)
    val dim = tile == null || tile.isStale(state.now) || !tile.available
    val text = when {
        tile == null -> "—"
        status == TileStatus.REMOVED -> context.getString(R.string.widget_removed)
        status == TileStatus.PENDING -> "…"
        else -> TileText.of(context, tile.value, Locale.getDefault())
    }
    val tap = tile?.let { actions.tap(TileActions.forTap(it, state.armMode)) } ?: actions.openApp()
    val color = if (dim) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface
    Row(
        modifier.background(GlanceTheme.colors.secondaryContainer).cornerRadius(14.dp).padding(8.dp).clickable(tap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(iconRes(tile?.icon)), contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = GlanceModifier.size(if (large) 28.dp else 20.dp))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(tile?.name ?: "—", maxLines = 1, style = TextStyle(color = color, fontSize = if (large) 14.sp else 12.sp))
            Text(text, maxLines = 1, style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = if (large) 18.sp else 14.sp))
        }
        if (status == TileStatus.FAILED) Text("!", style = TextStyle(color = GlanceTheme.colors.error, fontWeight = FontWeight.Bold))
        if (tile != null && tile.kind == Kind.COVER && large && LocalSize.current.width >= 180.dp && status != TileStatus.REMOVED) {
            CoverButtons(tile, actions)
        }
    }
}

@Composable
private fun CoverButtons(tile: TileSnapshot, actions: WidgetActions) {
    val context = LocalContext.current
    listOf(WidgetCommand.Open to R.string.widget_cover_open, WidgetCommand.Stop to R.string.widget_cover_stop, WidgetCommand.Close to R.string.widget_cover_close).forEach { (cmd, label) ->
        Text(
            context.getString(label),
            style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(actions.tap(TileActions.coverButton(tile, cmd))),
        )
    }
}

@Composable
private fun Footer(state: WidgetUiState, actions: WidgetActions) {
    val context = LocalContext.current
    val time = state.lastFresh?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    val (label, onClick) = when (state.link) {
        LinkStatus.OFFLINE -> (time?.let { context.getString(R.string.widget_offline, it) } ?: context.getString(R.string.widget_offline_never)) to actions.refresh()
        LinkStatus.AUTH_FAILED -> context.getString(R.string.widget_sign_in_failed) to actions.openSettings()
        else -> (time?.let { context.getString(R.string.widget_updated, it) } ?: "") to actions.refresh()
    }
    Row(GlanceModifier.fillMaxWidth().height(24.dp).clickable(onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), modifier = GlanceModifier.defaultWeight())
        Image(ImageProvider(R.drawable.ic_widget_refresh), contentDescription = context.getString(R.string.widget_refresh), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant), modifier = GlanceModifier.size(18.dp).clickable(actions.refresh()))
    }
}

@Composable
private fun Message(text: String, onClick: Action) {
    Box(GlanceModifier.fillMaxSize().clickable(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
    }
}

private fun iconRes(icon: TileIcon?): Int = when (icon) {
    TileIcon.LIGHT -> R.drawable.ic_widget_light
    TileIcon.SWITCH -> R.drawable.ic_widget_switch
    TileIcon.COVER -> R.drawable.ic_widget_cover
    TileIcon.CLIMATE -> R.drawable.ic_widget_climate
    TileIcon.TEMPERATURE -> R.drawable.ic_widget_temperature
    TileIcon.ALARM -> R.drawable.ic_widget_alarm
    TileIcon.BUTTON -> R.drawable.ic_widget_button
    TileIcon.SENSOR, null -> R.drawable.ic_widget_sensor
}
```

The single large tile (`size.height < 100.dp`) shows cover buttons when wide enough. In the grid, covers use the single-tap open/close (spec §2.3).

- [ ] **Step 10: Implement `OikosWidget`, receiver and callbacks**

```kotlin
package com.gekkotron.oikos.feature.widget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.WidgetComponentProvider
import com.gekkotron.oikos.feature.widget.code.WidgetCodeActivity
import com.gekkotron.oikos.feature.widget.config.WidgetConfigActivity
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.model.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The links the widget opens in the app; `:app` parses them (widgets spec §2.3). */
object AppLinks {
    const val HISTORY = "oikos://history/"
    const val SETTINGS = "oikos://settings"
}

class OikosWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 48.dp), DpSize(180.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(180.dp, 250.dp), DpSize(250.dp, 250.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val component = (context.applicationContext as WidgetComponentProvider).widgetComponent()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val data = combine(component.configs.configs, component.snapshots.snapshot) { configs, snap -> configs[appWidgetId] to snap }
        provideContent {
            val (config, snap) by data.collectAsState(initial = null to WidgetSnapshot())
            GlanceTheme {
                WidgetContent(uiState(config, snap, System.currentTimeMillis()), Actions(context, appWidgetId))
            }
        }
    }

    private fun uiState(config: WidgetConfig?, snap: WidgetSnapshot, now: Long) = WidgetUiState(
        tiles = config?.uniqueIds.orEmpty().map { snap.tiles[it] },
        ids = config?.uniqueIds.orEmpty(),
        armMode = config?.armMode,
        link = snap.link,
        lastFresh = snap.lastFresh,
        now = now,
        configured = !config?.uniqueIds.isNullOrEmpty(),
    )

    private class Actions(private val context: Context, private val appWidgetId: Int) : WidgetActions {
        override fun tap(action: TileAction): Action = when (action) {
            is TileAction.Send -> actionRunCallback<SendCallback>(actionParametersOf(SendCallback.ID to action.id, SendCallback.COMMAND to action.command.encode()))
            is TileAction.AskCode -> actionStartActivity(WidgetCodeActivity.intent(context, action.id, action.command, action.format))
            is TileAction.OpenHistory -> actionStartActivity(view(AppLinks.HISTORY + Uri.encode(action.id)))
            TileAction.OpenHome -> openApp()
        }

        override fun refresh(): Action = actionRunCallback<RefreshCallback>()
        override fun reconfigure(): Action = actionStartActivity(
            Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        override fun openApp(): Action = actionStartActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
        override fun openSettings(): Action = actionStartActivity(view(AppLinks.SETTINGS))

        private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

class SendCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ID] ?: return
        val command = parameters[COMMAND]?.let(WidgetCommand::decode) ?: return
        (context.applicationContext as WidgetComponentProvider).widgetComponent().scheduler.send(id, command, code = null)
    }

    companion object {
        val ID = ActionParameters.Key<String>("id")
        val COMMAND = ActionParameters.Key<String>("command")
    }
}

class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as WidgetComponentProvider).widgetComponent().scheduler.refreshNow()
    }
}

class OikosWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = OikosWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val component = (context.applicationContext as WidgetComponentProvider).widgetComponent()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { component.configs.remove(appWidgetIds) } finally { pending.finish() }
        }
    }

    /** The last widget is gone: stop the periodic refresh (Review Focus 5). */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        (context.applicationContext as WidgetComponentProvider).widgetComponent().scheduler.cancelPeriodic()
    }
}

/** Redraws every Oikos widget; the app's `WidgetUpdater`. */
suspend fun updateAllOikosWidgets(context: Context) = OikosWidget().updateAll(context)
```

`WidgetCodeActivity` and `WidgetConfigActivity` are referenced here. Create empty `ComponentActivity` stubs in this task (with `intent(...)` returning an explicit `Intent` carrying the extras) so the module compiles. Tasks 6 and 7 fill them in. Stub for the code activity:

```kotlin
package com.gekkotron.oikos.feature.widget.code

class WidgetCodeActivity : androidx.activity.ComponentActivity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_FORMAT = "format"
        fun intent(context: android.content.Context, id: String, command: com.gekkotron.oikos.feature.widget.model.WidgetCommand, format: com.gekkotron.oikos.model.CodeFormat) =
            android.content.Intent(context, WidgetCodeActivity::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_COMMAND, command.encode()).putExtra(EXTRA_FORMAT, format.name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_HISTORY)
    }
}
```

- [ ] **Step 11: Run all widget tests, expect PASS** — `./gradlew :feature:widget:testDebugUnitTest`.

- [ ] **Step 12: Commit** — `git add feature/widget NOTICE && git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): Glance UI, receiver and callbacks"`

---

### Task 6: `WidgetConfigActivity` (entity picker)

**Files:**
- Modify: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/config/WidgetConfigActivity.kt`
- Create: `config/ConfigViewModel.kt`, `config/ConfigScreen.kt`
- Modify: `feature/widget/src/main/AndroidManifest.xml`
- Test: `feature/widget/src/test/kotlin/com/gekkotron/oikos/feature/widget/config/ConfigViewModelTest.kt`, `config/ConfigScreenTest.kt`

**Interfaces:**
- Consumes: `WidgetEntities.catalog()`, `WidgetConfigStore`, `WidgetScheduler`, `WidgetConfig.MAX_ENTITIES`.
- Produces:

```kotlin
data class PickerRow(val id: String, val name: String, val areaName: String, val kind: Kind, val alarmModes: Set<AlarmMode>)
data class ConfigUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val notConfigured: Boolean = false,
    val query: String = "",
    val rows: List<PickerRow> = emptyList(),      // filtered, grouped by area order
    val selected: List<String> = emptyList(),      // in display order on the widget
    val armMode: AlarmMode? = null,
    val fitCount: Int = WidgetConfig.MAX_ENTITIES,
)
class ConfigViewModel(appWidgetId: Int, entities: WidgetEntities, configs: WidgetConfigStore, scheduler: WidgetScheduler, fitCount: Int) : ViewModel() {
    val state: StateFlow<ConfigUiState>
    fun search(query: String)
    fun toggle(id: String)          // no-op past MAX_ENTITIES
    fun move(from: Int, to: Int)
    fun setArmMode(mode: AlarmMode)
    suspend fun save(): Boolean     // false when nothing selected
}
```

- [ ] **Step 1: Write the failing `ConfigViewModelTest`**

```kotlin
package com.gekkotron.oikos.feature.widget.config

import com.gekkotron.oikos.feature.widget.*
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.work.WidgetScheduler
import com.gekkotron.oikos.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {
    private object B : Bindings
    private fun e(id: String, name: String, kind: Kind = Kind.SWITCH, area: String = "living", caps: Capabilities = Capabilities()) =
        Entity(id, null, name, kind, null, null, null, caps, B, area = area)

    private val areas = listOf(Area("living", "Living room"), Area("bed", "Bedroom"))
    private val catalog = CatalogResult.Ok(listOf(e("b", "Bed lamp", area = "bed"), e("a", "Kettle"), e("al", "Alarm", Kind.ALARM_PANEL, caps = Capabilities(alarmModes = setOf(AlarmMode.HOME, AlarmMode.AWAY)))), areas)

    private class Entities(val result: CatalogResult) : WidgetEntities {
        override suspend fun refresh(ids: Set<String>) = RefreshResult.Failed(Failure.OFFLINE)
        override suspend fun send(id: String, command: Command) = SendResult.NotConfirmed
        override suspend fun catalog() = result
    }
    private class Configs : WidgetConfigStore {
        val map = mutableMapOf<Int, WidgetConfig>()
        override val configs = MutableStateFlow<Map<Int, WidgetConfig>>(emptyMap())
        override suspend fun get(appWidgetId: Int) = map[appWidgetId]
        override suspend fun put(appWidgetId: Int, config: WidgetConfig) { map[appWidgetId] = config }
        override suspend fun remove(appWidgetIds: IntArray) {}
        override suspend fun allIds() = map.values.flatMap { it.uniqueIds }.toSet()
    }
    private class Scheduler : WidgetScheduler {
        var periodic = 0; var now = 0
        override fun ensurePeriodic() { periodic++ }
        override fun cancelPeriodic() {}
        override fun refreshNow() { now++ }
        override fun send(id: String, command: WidgetCommand, code: String?) {}
    }

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test fun rows_grouped_by_area_order_and_searchable() = runTest {
        val vm = ConfigViewModel(1, Entities(catalog), Configs(), Scheduler(), fitCount = 4)
        assertEquals(listOf("Alarm", "Kettle", "Bed lamp"), vm.state.value.rows.map { it.name })
        vm.search("lamp")
        assertEquals(listOf("Bed lamp"), vm.state.value.rows.map { it.name })
    }

    @Test fun existing_config_is_preselected() = runTest {
        val configs = Configs().apply { map[1] = WidgetConfig(listOf("b", "a"), AlarmMode.HOME) }
        val vm = ConfigViewModel(1, Entities(catalog), configs, Scheduler(), 4)
        assertEquals(listOf("b", "a"), vm.state.value.selected)
        assertEquals(AlarmMode.HOME, vm.state.value.armMode)
    }

    @Test fun selection_caps_at_eight() = runTest {
        val many = CatalogResult.Ok((1..10).map { e("e$it", "E$it") }, areas)
        val vm = ConfigViewModel(1, Entities(many), Configs(), Scheduler(), 4)
        (1..10).forEach { vm.toggle("e$it") }
        assertEquals(8, vm.state.value.selected.size)
    }

    @Test fun save_writes_config_schedules_and_refreshes() = runTest {
        val configs = Configs(); val scheduler = Scheduler()
        val vm = ConfigViewModel(1, Entities(catalog), configs, scheduler, 4)
        vm.toggle("a"); vm.toggle("al"); vm.setArmMode(AlarmMode.AWAY); vm.move(1, 0)
        assertTrue(vm.save())
        assertEquals(WidgetConfig(listOf("al", "a"), AlarmMode.AWAY), configs.map[1])
        assertEquals(1, scheduler.periodic); assertEquals(1, scheduler.now)
    }

    @Test fun save_with_nothing_selected_is_refused() = runTest {
        val vm = ConfigViewModel(1, Entities(catalog), Configs(), Scheduler(), 4)
        assertFalse(vm.save())
    }

    @Test fun not_configured_and_offline_flags() = runTest {
        assertTrue(ConfigViewModel(1, Entities(CatalogResult.Failed(Failure.NOT_CONFIGURED)), Configs(), Scheduler(), 4).state.value.notConfigured)
        assertTrue(ConfigViewModel(1, Entities(CatalogResult.Failed(Failure.OFFLINE)), Configs(), Scheduler(), 4).state.value.offline)
    }
}
```

An offline `catalog()` already falls back to the cache inside `HeadlessEntities` (Task 3), so `Failed(OFFLINE)` only happens with an empty cache. The VM then shows the offline banner and an empty list.

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement `ConfigViewModel`**

```kotlin
package com.gekkotron.oikos.feature.widget.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.feature.widget.CatalogResult
import com.gekkotron.oikos.feature.widget.Failure
import com.gekkotron.oikos.feature.widget.WidgetEntities
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.work.WidgetScheduler
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PickerRow(val id: String, val name: String, val areaName: String, val kind: Kind, val alarmModes: Set<AlarmMode>)

data class ConfigUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val notConfigured: Boolean = false,
    val query: String = "",
    val rows: List<PickerRow> = emptyList(),
    val selected: List<String> = emptyList(),
    val armMode: AlarmMode? = null,
    val fitCount: Int = WidgetConfig.MAX_ENTITIES,
) {
    val alarmRow: PickerRow? get() = rows.firstOrNull { it.kind == Kind.ALARM_PANEL && it.id in selected }
}

class ConfigViewModel(
    private val appWidgetId: Int,
    private val entities: WidgetEntities,
    private val configs: WidgetConfigStore,
    private val scheduler: WidgetScheduler,
    fitCount: Int,
) : ViewModel() {
    private var all: List<PickerRow> = emptyList()
    private val _state = MutableStateFlow(ConfigUiState(fitCount = fitCount))
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = configs.get(appWidgetId)
            when (val result = entities.catalog()) {
                is CatalogResult.Ok -> {
                    val order = result.areas.mapIndexed { i, a -> a.id to i }.toMap()
                    val names = result.areas.associate { it.id to it.name }
                    all = result.entities
                        .filter { !it.ui.hidden && it.partOf == null }
                        .sortedWith(compareBy({ order[it.area] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                        .map { PickerRow(it.uniqueId, it.name, names[it.area] ?: it.area, it.kind, it.capabilities.alarmModes) }
                    _state.update { it.copy(loading = false, rows = all, selected = existing?.uniqueIds.orEmpty(), armMode = existing?.armMode) }
                }
                is CatalogResult.Failed -> _state.update {
                    it.copy(loading = false, offline = result.reason == Failure.OFFLINE, notConfigured = result.reason == Failure.NOT_CONFIGURED, selected = existing?.uniqueIds.orEmpty())
                }
            }
        }
    }

    fun search(query: String) = _state.update { s ->
        s.copy(query = query, rows = if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) || it.areaName.contains(query, ignoreCase = true) })
    }

    fun toggle(id: String) = _state.update { s ->
        when {
            id in s.selected -> s.copy(selected = s.selected - id)
            s.selected.size >= WidgetConfig.MAX_ENTITIES -> s
            else -> s.copy(selected = s.selected + id)
        }
    }

    fun move(from: Int, to: Int) = _state.update { s ->
        if (from !in s.selected.indices || to !in s.selected.indices) s
        else s.copy(selected = s.selected.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun setArmMode(mode: AlarmMode) = _state.update { it.copy(armMode = mode) }

    suspend fun save(): Boolean {
        val s = _state.value
        if (s.selected.isEmpty()) return false
        configs.put(appWidgetId, WidgetConfig(s.selected, s.armMode))
        scheduler.ensurePeriodic()
        scheduler.refreshNow()
        return true
    }
}
```

Check whether `Entity.name` already carries the override name (see the Task 1 note). If not, use `it.ui.name ?: it.name` here too.

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Implement `ConfigScreen` and the activity**

`ConfigScreen.kt`: a Material 3 `Scaffold` with a `TopAppBar` (title `widget_config_title`, action `widget_config_save`) and an `OutlinedTextField` search. Below it, the hint text `widget_config_hint` (max, fitCount) and the offline banner when `offline`. Then a "Selected" section listing the chosen rows in order, each with ↑/↓ `IconButton`s calling `move`, and, when `alarmRow != null`, a row of `FilterChip`s for `alarmRow.alarmModes` (labels from `designsystem.alarm.armLabel`). Last, the full list grouped by `areaName` (sticky area headers), each row a `ListItem` with a `Checkbox` bound to `toggle`. When `notConfigured`, show only the text `widget_set_up` and a button that opens the app's launch intent. Wrap it in `OikosTheme` from `:core:designsystem` (check its signature with `grep -rn "fun OikosTheme" core/designsystem`).

`WidgetConfigActivity.kt`:

```kotlin
package com.gekkotron.oikos.feature.widget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gekkotron.oikos.feature.widget.WidgetComponentProvider
import kotlinx.coroutines.launch

/** Opened by the launcher when a widget is placed or reconfigured (spec §2.1). */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Cancelled placement unless we say otherwise.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val component = (application as WidgetComponentProvider).widgetComponent()
        val fit = fitCount(AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId))
        val vm = ViewModelProvider(this, viewModelFactory { initializer { ConfigViewModel(appWidgetId, component.entities, component.configs, component.scheduler, fit) } })[ConfigViewModel::class.java]
        setContent {
            ConfigScreen(vm, onDone = {
                lifecycleScope.launch {
                    if (vm.save()) {
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                        finish()
                    }
                }
            })
        }
    }

    /** Tiles that fit at the current size, using the same grid rules as `WidgetContent`. */
    private fun fitCount(options: Bundle): Int {
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
        if (h < 100) return 1
        val columns = if (w >= 180) 2 else 1
        val rows = ((h - 28) / 64).coerceAtLeast(1)
        return (columns * rows).coerceAtMost(8)
    }
}
```

Manifest additions inside `<application>`:

```xml
<activity
    android:name=".config.WidgetConfigActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:theme="@android:style/Theme.Material.Light.NoActionBar">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
    </intent-filter>
</activity>
```

Use the app's own theme if `:core:designsystem` exposes a `Theme.Oikos` style (`grep -rn "Theme.Oikos" app/src/main/res core/designsystem/src/main/res`). Otherwise keep the platform one. The Compose content draws its own background.

- [ ] **Step 6: Write `ConfigScreenTest`** (Robolectric + `createComposeRule`). It renders `ConfigScreen` with a VM over the same fakes as Step 1, clicks the "Kettle" row's checkbox and then "Done", and asserts the fake store holds `["a"]`. Also: with the alarm selected, the HOME/AWAY chips appear. Follow `feature/history/src/test/.../ui/HistoryScreenTest.kt` for setup.

- [ ] **Step 7: Run, expect PASS** — `./gradlew :feature:widget:testDebugUnitTest`.

- [ ] **Step 8: Commit** — `git add feature/widget && git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): entity picker"`

---

### Task 7: `WidgetCodeActivity` and app links (`oikos://history/<id>`, `oikos://settings`)

**Files:**
- Modify: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/code/WidgetCodeActivity.kt`, `feature/widget/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/DeepLinks.kt`, `AppViewModel.kt`, `MainActivity.kt`, `navigation/OikosNavHost.kt`, `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/DeepLinksTest.kt` (extend the existing one if present: `ls app/src/test/kotlin/com/gekkotron/oikos/`), `app/src/test/.../navigation/` test for the link action, `feature/widget/src/test/.../code/WidgetCodeActivityTest.kt`

**Interfaces:**
- Consumes: `CodeKeypadSheet(format: CodeFormat, onSubmit: (String) -> Unit, onDismiss: () -> Unit)` from `com.gekkotron.oikos.designsystem.alarm`; `WidgetScheduler.send`.
- Produces: `sealed interface AppLink { data class Alert(val id: String); data class History(val entityId: String); data object Settings }`, `DeepLinks.linkToOpen(intent: Intent?): AppLink?`, `AppViewModel.pendingLink: StateFlow<AppLink?>`, `AppViewModel.openLink(AppLink)`, `AppViewModel.linkHandled()`.

- [ ] **Step 1: Write the failing `DeepLinksTest` cases**

```kotlin
@Test fun history_link() = assertEquals(AppLink.History("0x01_temp"), DeepLinks.link(Uri.parse("oikos://history/0x01_temp")))
@Test fun history_link_decodes() = assertEquals(AppLink.History("a/b"), DeepLinks.link(Uri.parse("oikos://history/" + Uri.encode("a/b"))))
@Test fun settings_link() = assertEquals(AppLink.Settings, DeepLinks.link(Uri.parse("oikos://settings")))
@Test fun alert_link_still_works() = assertEquals(AppLink.Alert("42"), DeepLinks.link(Uri.parse("oikos://alert/42")))
@Test fun other_links_are_ignored() = assertNull(DeepLinks.link(Uri.parse("https://example.com/history/1")))
```

Keep the existing `alertId`/`alertToOpen` tests passing by keeping those functions as thin wrappers, or migrate them to `link`/`linkToOpen` and update the callers.

- [ ] **Step 2: Run, expect FAIL** (Robolectric, since `Uri` needs Android).

- [ ] **Step 3: Implement**

`DeepLinks.kt`:

```kotlin
/** What a tapped notification or widget asks the app to show (push alerts spec §5.2, widgets spec §2.3). */
sealed interface AppLink {
    data class Alert(val id: String) : AppLink
    data class History(val entityId: String) : AppLink
    data object Settings : AppLink
}

object DeepLinks {
    fun link(uri: Uri?): AppLink? {
        if (uri == null || uri.scheme != "oikos") return null
        val first = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        return when (uri.host) {
            "alert" -> first?.let(AppLink::Alert)
            "history" -> first?.let(AppLink::History) // pathSegments are already decoded
            "settings" -> AppLink.Settings
            else -> null
        }
    }

    /** The link a fresh tap asks for; a relaunch from Recents re-delivers the old launch intent and must not replay it. */
    fun linkToOpen(intent: Intent?): AppLink? {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return link(intent.data)
    }

    fun alertId(uri: Uri?): String? = (link(uri) as? AppLink.Alert)?.id
    fun alertToOpen(intent: Intent?): String? = (linkToOpen(intent) as? AppLink.Alert)?.id
}
```

`AppViewModel`: replace `_pendingAlert: MutableStateFlow<String?>` with `_pendingLink: MutableStateFlow<AppLink?>`. Keep `openAlert(id)` as `openLink(AppLink.Alert(id))` if other code calls it (`grep -rn "openAlert\|pendingAlert\|alertHandled" app/src`). Rename the "handled" function to `linkHandled()`.

`MainActivity.handleIntent`: `DeepLinks.linkToOpen(intent)?.let(appViewModel::openLink)`.

`OikosNavHost`: generalise `alertLinkAction(root, pending: String?)` to `linkAction(root, pending: AppLink?)` (same rules: OPEN only on Home, DROP on onboarding, WAIT otherwise). The OPEN branch pushes:
- `AppLink.Alert` → `AlertDetailKey(id)` (unchanged behaviour)
- `AppLink.History` → `HistoryKey(HistoryTarget.Entity(entityId).encode())`
- `AppLink.Settings` → `SettingsKey`

Update the existing `alertLinkAction` tests to the new name and add one case per new link.

`app/src/main/AndroidManifest.xml`, `MainActivity` intent-filter for the new hosts, next to the existing `alert` one (`grep -n "oikos" app/src/main/AndroidManifest.xml`):

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="oikos" android:host="history" />
    <data android:scheme="oikos" android:host="settings" />
</intent-filter>
```

If the alert link is delivered as an explicit intent (no filter), the widget's `Intent.ACTION_VIEW` + `setPackage` still resolves through this filter.

- [ ] **Step 4: Implement `WidgetCodeActivity`**

```kotlin
package com.gekkotron.oikos.feature.widget.code

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gekkotron.oikos.designsystem.alarm.CodeKeypadSheet
import com.gekkotron.oikos.feature.widget.WidgetComponentProvider
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.model.CodeFormat

/** A transparent host for the code keypad; the code lives only in the sheet's state and the work input (spec §4). */
class WidgetCodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val command = intent.getStringExtra(EXTRA_COMMAND)?.let(WidgetCommand::decode)
        val format = intent.getStringExtra(EXTRA_FORMAT)?.let { runCatching { CodeFormat.valueOf(it) }.getOrNull() } ?: CodeFormat.NUMBER
        if (id == null || command == null) return finish()
        val scheduler = (application as WidgetComponentProvider).widgetComponent().scheduler
        setContent {
            // Same theme wrapper as the config screen.
            CodeKeypadSheet(
                format = format,
                onSubmit = { code ->
                    scheduler.send(id, command, code)
                    finish()
                },
                onDismiss = ::finish,
            )
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_FORMAT = "format"

        fun intent(context: Context, id: String, command: WidgetCommand, format: CodeFormat): Intent =
            Intent(context, WidgetCodeActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_COMMAND, command.encode())
                .putExtra(EXTRA_FORMAT, format.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
```

Manifest:

```xml
<activity
    android:name=".code.WidgetCodeActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:theme="@style/Theme.OikosWidget.Translucent" />
```

and `res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.OikosWidget.Translucent" parent="@android:style/Theme.Translucent.NoTitleBar">
        <item name="android:windowIsFloating">false</item>
        <item name="android:windowBackground">@android:color/transparent</item>
    </style>
</resources>
```

`Intent` extras of a `PendingIntent` are visible only to the app. The code is never put in an extra: only the keypad's `onSubmit` sees it.

- [ ] **Step 5: `WidgetCodeActivityTest`** (Robolectric, `ActivityScenario`): with a fake `WidgetComponentProvider` application (`@Config(application = TestWidgetApp::class)`, where `TestWidgetApp : Application(), WidgetComponentProvider` returns a component whose scheduler records calls), launch with `intent(ctx, "a", WidgetCommand.Disarm, CodeFormat.NUMBER)`, type `1`,`2`,`3`,`4` on the keypad and press OK (find nodes by text as the existing `CodeKeypadSheet` tests do: `grep -rn "CodeKeypadSheet" core/designsystem/src/test`). Assert that the scheduler recorded `("a", Disarm, "1234")` and the activity is finishing. Second case: missing extras → finishes without sending.

- [ ] **Step 6: Run** `./gradlew :feature:widget:testDebugUnitTest :app:testPersonalDebugUnitTest`, expect PASS.

- [ ] **Step 7: Commit** — `git add feature/widget app && git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): code keypad activity and history/settings app links"`

---

### Task 8: App wiring — component, worker factory, mirror, reset

**Files:**
- Create: `app/src/main/kotlin/com/gekkotron/oikos/di/WidgetModule.kt`, `app/src/main/kotlin/com/gekkotron/oikos/connection/WidgetMirror.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/OikosApplication.kt`, `SetUpAgainCleanup.kt`, `di/PushModule.kt` (cleanup provider)
- Test: `app/src/test/kotlin/com/gekkotron/oikos/connection/WidgetMirrorTest.kt`, extend `SetUpAgainCleanupTest`

**Interfaces:**
- Consumes: everything above; `EntitySourceSession.peek()`, `ActiveTransport.link`, `ProfileRepository.state/route/installId()`, `TransportFactory(scope, networkChanges, http)`.
- Produces: `class WidgetMirror(sources: Flow<EntitySource?>, configs: WidgetConfigStore, snapshots: WidgetSnapshotStore, updater: WidgetUpdater, historyEnabled: () -> Boolean, scope: CoroutineScope, debounce: Duration = 1.seconds) { fun start() }`

- [ ] **Step 1: Write the failing `WidgetMirrorTest`**

```kotlin
package com.gekkotron.oikos.connection

// imports: feature.widget.*, feature.widget.model.*, feature.widget.store.*, model.*, coroutines test, MutableStateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetMirrorTest {
    // Reuse in-memory WidgetConfigStore / WidgetSnapshotStore doubles (copy the ones from WidgetRefresherTest into app/src/test).

    @Test fun mirrors_only_configured_ids_debounced() = runTest {
        val source = FakeLiveSource(mapOf("s" to switchEntity, "x" to otherEntity)) // states() is a MutableStateFlow
        val configs = MemConfigs(mutableMapOf(1 to WidgetConfig(listOf("s"))))
        val snaps = MemSnapshots()
        var updates = 0
        WidgetMirror(flowOf(source), configs, snaps, { updates++ }, { false }, backgroundScope).start()
        source.emit("s", StateValue.OnOff(true))
        source.emit("x", StateValue.OnOff(true))
        source.emit("s", StateValue.OnOff(false))
        advanceTimeBy(500); runCurrent()
        assertEquals(0, updates)
        advanceTimeBy(600); runCurrent()
        assertEquals(1, updates)
        assertEquals(setOf("s"), snaps.flow.value.tiles.keys)
        assertEquals(TileValue.OnOff(false), snaps.flow.value.tiles["s"]?.value)
        assertEquals(LinkStatus.FRESH, snaps.flow.value.link)
    }

    @Test fun no_source_means_no_writes() = runTest {
        val snaps = MemSnapshots(); var updates = 0
        WidgetMirror(flowOf(null), MemConfigs(mutableMapOf(1 to WidgetConfig(listOf("s")))), snaps, { updates++ }, { false }, backgroundScope).start()
        advanceUntilIdle()
        assertEquals(0, updates)
    }

    @Test fun no_widgets_means_no_writes() = runTest {
        val source = FakeLiveSource(mapOf("s" to switchEntity))
        val snaps = MemSnapshots(); var updates = 0
        WidgetMirror(flowOf(source), MemConfigs(), snaps, { updates++ }, { false }, backgroundScope).start()
        source.emit("s", StateValue.OnOff(true))
        advanceUntilIdle()
        assertEquals(0, updates)
    }
}
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement `WidgetMirror`**

```kotlin
package com.gekkotron.oikos.connection

import com.gekkotron.oikos.feature.widget.TileMapper
import com.gekkotron.oikos.feature.widget.WidgetUpdater
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.store.WidgetConfigStore
import com.gekkotron.oikos.feature.widget.store.WidgetSnapshotStore
import com.gekkotron.oikos.source.EntitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** While the app is connected, keeps the widgets live from the foreground source at no extra traffic (spec §3.2). */
class WidgetMirror(
    private val sources: Flow<EntitySource?>,
    private val configs: WidgetConfigStore,
    private val snapshots: WidgetSnapshotStore,
    private val updater: WidgetUpdater,
    private val historyEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val debounce: Duration = 1.seconds,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        scope.launch {
            sources.flatMapLatest { source ->
                if (source == null) emptyFlow()
                else combine(source.entities, source.states(), configs.configs) { entities, states, cfg ->
                    val ids = cfg.values.flatMapTo(linkedSetOf()) { it.uniqueIds }
                    Triple(ids, entities.filterKeys { it in ids }, states.filterKeys { it in ids })
                }
            }
                .filter { (ids, _, _) -> ids.isNotEmpty() }
                .distinctUntilChanged()
                .debounce(debounce)
                .collect { (ids, entities, states) ->
                    snapshots.update { snap ->
                        val tiles = ids.mapNotNull { id -> TileMapper.map(entities[id], states[id], snap.tiles[id], historyEnabled(), complete = false)?.let { id to it } }.toMap()
                        snap.copy(tiles = tiles, link = LinkStatus.FRESH, lastFresh = now())
                    }
                    updater.updateAll()
                }
        }
    }
}
```

`complete = false`: the mirror never declares "Removed". Only a settled headless refresh does, which keeps the rule in one place. The mirror also skips `PENDING` tiles owned by a running command worker: `TileMapper.map` returns `status = OK`, which overwrites a pending marker. Accept that: the live state is the truth while the app is open, and the worker writes the final result after it.

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: `WidgetModule` (Hilt)**

```kotlin
package com.gekkotron.oikos.di

// imports …

@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {
    @Provides @Singleton
    fun widgetConfigs(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): WidgetConfigStore = FileWidgetConfigStore.create(context, scope)

    @Provides @Singleton
    fun widgetSnapshots(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): WidgetSnapshotStore = FileWidgetSnapshotStore.create(context, scope)

    @Provides @Singleton
    fun widgetScheduler(@ApplicationContext context: Context): WidgetScheduler = WorkManagerWidgetScheduler { WorkManager.getInstance(context) }

    @Provides @Singleton
    fun widgetUpdater(@ApplicationContext context: Context): WidgetUpdater = WidgetUpdater { updateAllOikosWidgets(context) }

    @Provides @Singleton
    fun widgetEntities(
        @ApplicationContext context: Context,
        profiles: ProfileRepository,
        session: EntitySourceSession,
        active: ActiveTransport,
        cache: EntityCache,
        http: OkHttpClient,
    ): WidgetEntities = HeadlessEntities(
        profile = { profiles.state.first { it !is ProfileState.Loading } },
        route = { profiles.route.value },
        installId = { profiles.installId() },
        live = { if (active.link.value is LinkState.Connected) session.peek() else null },
        foreground = { active.link.value is LinkState.Connected },
        cache = cache,
        transports = { spec, scope -> TransportFactory(scope, emptyFlow(), http).create(spec) },
    )

    @Provides @Singleton
    fun widgetRefresher(entities: WidgetEntities, configs: WidgetConfigStore, snapshots: WidgetSnapshotStore, updater: WidgetUpdater) =
        WidgetRefresher(entities, configs, snapshots, updater)

    @Provides @Singleton
    fun widgetComponent(entities: WidgetEntities, configs: WidgetConfigStore, snapshots: WidgetSnapshotStore, scheduler: WidgetScheduler, refresher: WidgetRefresher) =
        WidgetComponent(entities, configs, snapshots, scheduler, refresher)

    @Provides @Singleton
    fun widgetMirror(
        profiles: ProfileRepository,
        session: EntitySourceSession,
        active: ActiveTransport,
        configs: WidgetConfigStore,
        snapshots: WidgetSnapshotStore,
        updater: WidgetUpdater,
        @ApplicationScope scope: CoroutineScope,
    ): WidgetMirror = WidgetMirror(
        sources = active.link.map { if (it is LinkState.Connected) session.peek() else null }.distinctUntilChanged(),
        configs = configs, snapshots = snapshots, updater = updater,
        historyEnabled = { (profiles.state.value as? ProfileState.Ready)?.profile is ConnectionProfile.Direct },
        scope = scope,
    )
}
```

`networkChanges = emptyFlow()`: a session is too short to care about network switches. `@ApplicationScope` is the existing qualifier (see `AppModule`).

- [ ] **Step 6: `OikosApplication`**

- implement `WidgetComponentProvider` (`@Inject lateinit var widgets: WidgetComponent`, `override fun widgetComponent() = widgets`);
- `@Inject lateinit var widgetMirror: WidgetMirror`, and call `widgetMirror.start()` in `onCreate` after `historyWiring.start()`;
- `workManagerConfiguration`: build a `DelegatingWorkerFactory` holding the push factory and `WidgetWorkerFactory { widgets.refresher }`:

```kotlin
override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(
        DelegatingWorkerFactory().apply {
            addFactory(workerFactory)
            addFactory(WidgetWorkerFactory { widgets.refresher })
        },
    ).build()
```

- [ ] **Step 7: Reset clears the snapshot**

`SetUpAgainCleanup`: add constructor parameters `widgetSnapshots: WidgetSnapshotStore` and `updateWidgets: suspend () -> Unit`, and two steps at the end: `step { widgetSnapshots.clear() }` and `step { updateWidgets() }`. Update its provider in `PushModule` and the existing `SetUpAgainCleanupTest` (`find app/src/test -name "SetUpAgainCleanupTest.kt"`). Add one test: the snapshot is cleared even when an earlier step throws. Config is deliberately kept (spec §4). After a reset the footer says "Set up Oikos" because the next refresh returns `NOT_CONFIGURED`. Clearing the snapshot sets `link = UNKNOWN`, so the tiles show "—" right away.

- [ ] **Step 8: Run the whole suite** — `./gradlew testPersonalDebugUnitTest testDebugUnitTest` (or the repo's usual `./gradlew test`; check `README.md`/CI workflow for the exact command). Expect PASS.

- [ ] **Step 9: Build the app** — `./gradlew :app:assemblePersonalDebug`, expect BUILD SUCCESSFUL. Then check the merged manifest contains the receiver and both activities: `grep -n "OikosWidgetReceiver\|WidgetConfigActivity\|WidgetCodeActivity" app/build/intermediates/merged_manifests/*/process*Manifest/AndroidManifest.xml` (path varies; `find app/build -name AndroidManifest.xml -path "*merged*"`).

- [ ] **Step 10: Commit** — `git add app && git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(widget): app wiring, live mirror and reset"`

---

### Task 9: Regex scan, spec amendment, device checklist

**Files:**
- Modify: `docs/superpowers/specs/2026-10-08-oikos-widgets-design.md` (§3.2 `RoutePicker` row and §3.3 step 1)

- [ ] **Step 1: ICU regex scan** — `grep -rn 'Regex(' feature/widget/src/main app/src/main/kotlin/com/gekkotron/oikos/connection/HeadlessEntities.kt app/src/main/kotlin/com/gekkotron/oikos/connection/WidgetMirror.kt app/src/main/kotlin/com/gekkotron/oikos/DeepLinks.kt`. Every literal `]` or `}` must be escaped. Expected: no main-source regex at all.

- [ ] **Step 2: Amend the spec** — replace the `RoutePicker` row in §3.2 with:

`| (no new transport code) | app | HeadlessEntities builds a private TransportFactory(sessionScope, emptyFlow(), http) per session; the factory already covers Demo, Direct, Gateway and Routed AUTO (probe + fallback). OneShotPublisher is unchanged. |`

Then in §3.3 step 1 change "`RoutePicker` opens a transport" to "a private `TransportFactory` builds the transport for the profile's spec (client id `…-widget`)". Commit: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -am "docs(widgets): spec follows the TransportFactory reuse"`.

- [ ] **Step 3: Device checklist** (user, on a release build; record results in memory):
  1. Place the widget and pick 4 entities (a light, a cover, the alarm, a temperature sensor), with arm mode Away.
  2. Toggle the light: "…", then the new state within ~2 s.
  3. Cover: tap on the grid toggles; resize to the 2×1 large tile and use Open / Stop / Close.
  4. Climate (if present): off, then on, restoring the previous mode.
  5. Alarm: arm away; disarm opens the keypad; a wrong code → "!" or a silent revert; the right code → Disarmed.
  6. Temperature tile opens the history chart.
  7. Force-stop Oikos; wait 15+ min; the footer time advances.
  8. Off home Wi-Fi (gateway path): ↻ updates; a toggle works.
  9. With the app open, a change made elsewhere shows on the widget within ~1 s.
  10. Reconfigure (long-press → Reconfigure) on Android 12+.
  11. Remove the last widget: `adb shell dumpsys jobscheduler | grep oikos-widget-refresh` shows nothing.
  12. "Set up again" in Settings: the widget shows "—", then "Set up Oikos" after ↻.
  13. French locale: strings are translated.
