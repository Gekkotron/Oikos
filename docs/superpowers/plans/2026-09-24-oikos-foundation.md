# Oikos Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Oikos Gradle build (convention plugins, Kover, CI) and the pure-Kotlin core that turns Home Assistant MQTT Discovery traffic into typed entities, live states and outgoing commands, all proven by JVM unit tests.

**Architecture:** Five plain Kotlin/JVM library modules. None of them needs Android APIs, so they stay JVM-only and fast to test. Later Android modules consume them unchanged. `:core:model` holds the source-agnostic domain. `:core:crypto` holds the gateway envelope. `:core:transport` holds only the `MqttTransport` contract plus a test fake; the real Direct/Gateway transports come in a later plan. `:core:state` holds the Jinja-subset template evaluator, one mapper per `Kind`, the `StateStore` and the optimistic `CommandTracker`. `:core:source` parses discovery configs and wires everything into `MqttDiscoverySource`.

**Tech Stack:** Kotlin 2.4.20 (JVM 17 toolchain), Gradle 9.7.1 with a version catalog and precompiled convention plugins in `build-logic/`, kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.11.0, JUnit Jupiter 6.1.3 with `kotlin.test`, kotlinx-coroutines-test (virtual time), Kover 0.9.9, GitHub Actions and Codecov.

**Spec:** `docs/superpowers/specs/2026-09-24-oikos-design.md`. This plan covers §4.1 (the core modules listed above), §4.2, §4.3, §4.4, the §4.5 interface only, §4.6, and the build and coverage parts of §9, plus the §10 tests for these modules.

**Later plans (not this one):** Direct/Gateway transports and `TransportSelector`; `:core:data`; `:core:designsystem` and the feature screens; `:core:push`; the e2e fixture; `oikos-bridge`; Mqtt2FCMPush token registration.

## Global Constraints

- Package root: `com.gekkotron.oikos` (spec §9).
- JVM 17 toolchain; one Kotlin version (`2.4.20`) for every Kotlin plugin (spec §9).
- Module dependency rule (spec §4.1): `:core:source → :core:state, :core:transport, :core:model`; `:core:state → :core:model`; `:core:crypto` and `:core:transport` depend on no other Oikos module. No module depends on a feature module.
- No secrets, IPs or device lists in the repository (spec §1.7, §8). Test fixtures use fake hosts and ids only.
- The crypto key is never logged or included in exception messages (spec §4.6).
- Attribution: every byline or author field is `Gekkotron`. Every commit uses exactly this form (do not edit `.git/config`):
  ```bash
  git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
  ```
- Work on a feature branch (`feat/foundation`), never directly on `master`/`main`.
- Kover is applied to every module through the convention plugin and merged into the root report `build/reports/kover/report.xml` via `./gradlew koverXmlReport` (spec §9).
- Unsupported input never crashes: invalid discovery → the entity is skipped and logged; unsupported template → state `—` (`StateValue.Unsupported`), logged once per entity (spec §4.3, §4.4, §7).
- The optimistic-command timeout is 5 s; the subscription recompute debounce is 500 ms (spec §4.3, §4.4).

## Review Focus

1. **Boolean payloads from zigbee2mqtt.** Contact sensors publish `"payload_on": false` (a JSON boolean) and the template `{{ value_json.contact }}` renders a boolean. Both must compare as Python strings (`"False"`), so an open door shows as ON. Pinned in Task 5 (`BinarySensorMapperTest.z2m contact sensor with boolean payloads`) and Task 11 (`contact sensor keeps boolean payload_on as Python string`).
2. **Non-JSON payload on a `value_json` template** (for example a device publishing `online` on its state topic). The previous state must be kept, one diagnostic emitted, and nothing thrown. Pinned in Task 9 (`non-JSON payload keeps previous state and reports`).
3. **Malformed discovery payloads** (garbage bytes, a JSON array, a truncated object). Only that config is skipped; every other entity keeps working. Pinned in Task 11 (`invalid JSON and non-object payloads are skipped`) and Task 12 (`malformed config does not disturb other entities`).
4. **A retained config replaced under the same topic with a different `unique_id`** (a device re-paired in zigbee2mqtt). The old entity must disappear, with no ghost card. Pinned in Task 12 (`config replaced under the same topic drops the old entity`).
5. **An entity removed while a command is pending.** The timeout must not crash, emit a failure or resurrect the state. Pinned in Task 10 (`entity removed while pending emits nothing`).

## File Structure

```
settings.gradle.kts                       — root settings; includes build-logic and modules
build.gradle.kts                          — root: applies oikos.root, aggregates Kover
gradle.properties
gradle/libs.versions.toml                 — version catalog (single source of versions)
gradle/wrapper/*, gradlew, gradlew.bat    — Gradle 9.7.1 wrapper (committed)
build-logic/settings.gradle.kts
build-logic/convention/build.gradle.kts
build-logic/convention/src/main/kotlin/oikos.root.gradle.kts           — Kover on root
build-logic/convention/src/main/kotlin/oikos.jvm.library.gradle.kts    — Kotlin JVM + Kover + JUnit
build-logic/convention/src/main/kotlin/oikos.serialization.gradle.kts  — serialization plugin + json
.github/workflows/ci.yml, codecov.yml

core/model/src/main/kotlin/com/gekkotron/oikos/model/
  Kind.kt  Device.kt  Area.kt  Entity.kt  StateValue.kt  EntityState.kt
  Command.kt  UiOverride.kt  OikosJson.kt  Diagnostics.kt
core/crypto/src/main/kotlin/com/gekkotron/oikos/crypto/
  EnvelopeCrypto.kt  EnvelopeException.kt
core/transport/src/main/kotlin/com/gekkotron/oikos/transport/
  MqttTransport.kt  TopicFilter.kt
core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/FakeMqttTransport.kt
core/state/src/main/kotlin/com/gekkotron/oikos/state/
  template/Templates.kt  template/TemplateParser.kt  template/TemplateValues.kt
  mqtt/MqttBindings.kt
  kinds/KindMapper.kt  kinds/Common.kt  kinds/SwitchMapper.kt  kinds/BinarySensorMapper.kt
  kinds/SensorMapper.kt  kinds/ButtonMapper.kt  kinds/LightMapper.kt  kinds/CoverMapper.kt
  kinds/ClimateMapper.kt  kinds/AlarmPanelMapper.kt  kinds/NumberMapper.kt  kinds/SelectMapper.kt
  Availability.kt  StateStore.kt  CommandTracker.kt
core/source/src/main/kotlin/com/gekkotron/oikos/source/
  EntitySource.kt  MqttDiscoverySource.kt
  discovery/Abbreviations.kt  discovery/DiscoveryParser.kt
core/source/src/main/resources/com/gekkotron/oikos/source/abbreviations.json
core/source/src/test/resources/discovery/*.json   — discovery fixtures
```

**Design decisions locked here** (small refinements of spec §4.2, all additive):
- `Entity` gains `area: String` (the effective area id) and `ui: UiOverride` (the applied override), so "entities after overrides are applied" (§4.3) is carried on the type itself.
- `EntityState.lastUpdate` is `Instant?`: `null` means nothing has been received yet.
- `EntitySource` gains `commandFailures: SharedFlow<CommandFailure>` (the §4.4 "non-blocking error") and `setAreas()` (Oikos is the writer of `oikos/ui/_areas`, §4.3.4).
- MQTT bindings are `MqttBindings(component, config: JsonObject)`: the fully expanded discovery config, read by the kind mappers with HA key names. This keeps HA semantics in one place and avoids a hand-written field for every HA option.
- An override's `area` and an entity's `area` hold an **area id**. A raw area string (override or `suggested_area`) resolves to a declared `Area` by id or name; otherwise the string itself is the id.

---
### Task 1: Build scaffold + `:core:model`

**Files:**
- Modify: `.gitignore` (stop ignoring the wrapper files)
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `build-logic/settings.gradle.kts`, `build-logic/convention/build.gradle.kts`, `build-logic/convention/src/main/kotlin/oikos.root.gradle.kts`, `build-logic/convention/src/main/kotlin/oikos.jvm.library.gradle.kts`, `build-logic/convention/src/main/kotlin/oikos.serialization.gradle.kts`
- Create: `.github/workflows/ci.yml`, `codecov.yml`
- Create: `core/model/build.gradle.kts` and every file under `core/model/src/main/kotlin/com/gekkotron/oikos/model/` listed in File Structure
- Test: `core/model/src/test/kotlin/com/gekkotron/oikos/model/UiOverrideTest.kt`, `core/model/src/test/kotlin/com/gekkotron/oikos/model/CommandTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (used by every later task):
  - Convention plugins `oikos.jvm.library` (Kotlin JVM, toolchain 17, Kover, JUnit Jupiter + `kotlin.test` + `kotlinx-coroutines-test` on `testImplementation`) and `oikos.serialization` (serialization plugin + `api` kotlinx-serialization-json). Root `dependencies { kover(project(":x")) }` is where each new module is added to the merged report.
  - `enum class Kind { LIGHT, SWITCH, COVER, CLIMATE, SENSOR, BINARY_SENSOR, ALARM_PANEL, NUMBER, SELECT, BUTTON }`
  - `data class Device(id: String, name: String, manufacturer: String?, model: String?, suggestedArea: String?)`
  - `@Serializable data class Area(id: String, name: String, icon: String? = null, order: Int? = null)`, `@Serializable data class AreasDocument(areas: List<Area> = emptyList())`, `const val UNASSIGNED_AREA = "Unassigned"`
  - `interface Bindings`; `data class Capabilities(...)`; `data class Entity(uniqueId, deviceId, name, kind, deviceClass, unit, icon, capabilities, bindings, area = UNASSIGNED_AREA, ui = UiOverride())`
  - `sealed interface StateValue` (`Unknown`, `Unsupported`, `OnOff(on)`, `Light(on, brightness: Int?)`, `Numeric(value: Double)`, `Text(value)`, `Cover(state: CoverState, position: Int?)`, `Climate(mode: String?, target: Double?, current: Double?)`, `Alarm(state: AlarmState)`); `enum CoverState`, `enum AlarmState`, `enum AlarmMode`
  - `data class EntityState(uniqueId, value, attributes: Map<String, Any?> = emptyMap(), available = true, lastUpdate: Instant? = null, pending = false)`
  - `sealed interface Command` (with `Rgb`), `sealed interface CommandResult { Accepted; Rejected(reason) }`, `data class CommandFailure(uniqueId, command, reason)`
  - `@Serializable data class UiOverride(...)`, `enum class EnergyRole`, `object UiOverrides { decode(payload): UiOverride?; encode(o): String }`, `val OikosJson: Json`
  - `data class Diagnostic(source: String, message: String)`, `fun interface DiagnosticSink { report(d); companion object { val None } }`

- [ ] **Step 1: Create the branch and un-ignore the wrapper**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/Oikos
git switch -c feat/foundation
```

In `.gitignore`, delete these two lines from the `# Tooling` block (the wrapper must be committed for CI):

```
gradle-wrapper.jar
gradle-wrapper.properties
```

- [ ] **Step 2: Write the version catalog**

`gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.4.20"
coroutines = "1.11.0"
serialization = "1.11.0"
kover = "0.9.9"
junit = "6.1.3"

[libraries]
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
kotlin-serialization-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-serialization", version.ref = "kotlin" }
kover-gradle-plugin = { module = "org.jetbrains.kotlinx:kover-gradle-plugin", version.ref = "kover" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
```

- [ ] **Step 3: Write `build-logic`**

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
include(":convention")
```

`build-logic/convention/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
}
```

`build-logic/convention/src/main/kotlin/oikos.root.gradle.kts`:

```kotlin
// Applied to the root project only: hosts the merged Kover report.
plugins {
    id("org.jetbrains.kotlinx.kover")
}
```

`build-logic/convention/src/main/kotlin/oikos.jvm.library.gradle.kts`:

```kotlin
// Plain Kotlin/JVM library with Kover and JUnit Jupiter.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(17)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(kotlin("test"))
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
```

`build-logic/convention/src/main/kotlin/oikos.serialization.gradle.kts`:

```kotlin
// kotlinx-serialization compiler plugin + JSON runtime (exposed as api).
plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(libs.findLibrary("kotlinx-serialization-json").get())
}
```

- [ ] **Step 4: Write the root build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "Oikos"

include(":core:model")
```

`build.gradle.kts`:

```kotlin
plugins {
    id("oikos.root")
}

dependencies {
    kover(project(":core:model"))
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
kotlin.code.style=official
```

- [ ] **Step 5: Install the Gradle 9.7.1 wrapper**

No global `gradle` is installed, so bootstrap from DomoticApp's wrapper and then let it regenerate itself:

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/Oikos
mkdir -p gradle/wrapper
cp ../DomoticApp/gradlew ../DomoticApp/gradlew.bat .
cp ../DomoticApp/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
printf 'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n' > gradle/wrapper/gradle-wrapper.properties
chmod +x gradlew
./gradlew wrapper --gradle-version 9.7.1 --distribution-type bin
./gradlew --version
```

Expected: `Gradle 9.7.1` and a JVM 17 line. (The `wrapper` run may warn that `:core:model` has no build file yet; that is fine.)

- [ ] **Step 6: Write the failing model tests**

`core/model/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.jvm.library")
    id("oikos.serialization")
}
```

`core/model/src/test/kotlin/com/gekkotron/oikos/model/UiOverrideTest.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiOverrideTest {
    @Test
    fun `default override encodes to empty object`() {
        assertEquals("{}", UiOverrides.encode(UiOverride()))
    }

    @Test
    fun `full override round-trips`() {
        val o = UiOverride(
            name = "Lampe salon", area = "salon", hidden = true, order = 3,
            cardStyle = "hero_weather", favorite = true, energyRole = EnergyRole.WATER_HEATER,
        )
        val json = UiOverrides.encode(o)
        assertEquals(o, UiOverrides.decode(json))
        assert("\"energyRole\":\"water_heater\"" in json) { json }
    }

    @Test
    fun `unknown keys are ignored`() {
        assertEquals(UiOverride(name = "X"), UiOverrides.decode("""{"name":"X","future":42}"""))
    }

    @Test
    fun `unknown energy role becomes null instead of failing`() {
        assertEquals(UiOverride(name = "X"), UiOverrides.decode("""{"name":"X","energyRole":"battery"}"""))
    }

    @Test
    fun `invalid payloads decode to null`() {
        assertNull(UiOverrides.decode("not json"))
        assertNull(UiOverrides.decode("[1,2]"))
    }

    @Test
    fun `areas document decodes`() {
        val doc = OikosJson.decodeFromString(
            AreasDocument.serializer(),
            """{"areas":[{"id":"salon","name":"Salon","icon":"sofa","order":1}]}""",
        )
        assertEquals(listOf(Area("salon", "Salon", "sofa", 1)), doc.areas)
    }
}
```

`core/model/src/test/kotlin/com/gekkotron/oikos/model/CommandTest.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CommandTest {
    @Test
    fun `position must be 0 to 100`() {
        Command.SetPosition(0); Command.SetPosition(100)
        assertFailsWith<IllegalArgumentException> { Command.SetPosition(101) }
        assertFailsWith<IllegalArgumentException> { Command.SetPosition(-1) }
    }

    @Test
    fun `brightness must be 0 to 255`() {
        Command.TurnOn(brightness = 255)
        assertFailsWith<IllegalArgumentException> { Command.TurnOn(brightness = 256) }
    }

    @Test
    fun `rgb channels must be 0 to 255`() {
        assertFailsWith<IllegalArgumentException> { Rgb(0, 256, 0) }
    }
}
```

- [ ] **Step 7: Run the tests to verify they fail**

Run: `./gradlew :core:model:test`
Expected: FAIL, with compilation errors (`Unresolved reference: UiOverrides`, `Command`, `Rgb`…).

- [ ] **Step 8: Write the model**

`core/model/src/main/kotlin/com/gekkotron/oikos/model/Kind.kt`:

```kotlin
package com.gekkotron.oikos.model

enum class Kind { LIGHT, SWITCH, COVER, CLIMATE, SENSOR, BINARY_SENSOR, ALARM_PANEL, NUMBER, SELECT, BUTTON }
```

`Device.kt`:

```kotlin
package com.gekkotron.oikos.model

data class Device(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val suggestedArea: String?,
)
```

`Area.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlinx.serialization.Serializable

const val UNASSIGNED_AREA = "Unassigned"

@Serializable
data class Area(val id: String, val name: String, val icon: String? = null, val order: Int? = null)

/** Retained payload of `oikos/ui/_areas`. */
@Serializable
data class AreasDocument(val areas: List<Area> = emptyList())
```

`Entity.kt`:

```kotlin
package com.gekkotron.oikos.model

/** Source-specific wiring (topics, templates…). Opaque to features. */
interface Bindings

data class Capabilities(
    val brightness: Boolean = false,
    val colorModes: Set<String> = emptySet(),
    val position: Boolean = false,
    val hvacModes: List<String> = emptyList(),
    val minTemp: Double? = null,
    val maxTemp: Double? = null,
    val tempStep: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<String> = emptyList(),
    val alarmModes: Set<AlarmMode> = emptySet(),
    val codeArmRequired: Boolean = false,
)

data class Entity(
    val uniqueId: String,
    val deviceId: String?,
    val name: String,
    val kind: Kind,
    val deviceClass: String?,
    val unit: String?,
    val icon: String?,
    val capabilities: Capabilities,
    val bindings: Bindings,
    /** Effective area id: override → device suggested_area → [UNASSIGNED_AREA]. */
    val area: String = UNASSIGNED_AREA,
    /** The override applied to this entity (defaults when none). */
    val ui: UiOverride = UiOverride(),
)
```

`StateValue.kt`:

```kotlin
package com.gekkotron.oikos.model

sealed interface StateValue {
    /** Nothing received yet. UI shows "—". */
    data object Unknown : StateValue

    /** The entity's template is outside the supported subset. UI shows "—". */
    data object Unsupported : StateValue

    data class OnOff(val on: Boolean) : StateValue

    /** [brightness] is normalised to 0..255. */
    data class Light(val on: Boolean, val brightness: Int? = null) : StateValue

    data class Numeric(val value: Double) : StateValue

    data class Text(val value: String) : StateValue

    /** [position] is normalised to 0 (closed) .. 100 (open). */
    data class Cover(val state: CoverState, val position: Int? = null) : StateValue

    data class Climate(val mode: String?, val target: Double?, val current: Double?) : StateValue

    data class Alarm(val state: AlarmState) : StateValue
}

enum class CoverState { OPEN, CLOSED, OPENING, CLOSING, STOPPED, UNKNOWN }

enum class AlarmState {
    DISARMED, ARMED_HOME, ARMED_AWAY, ARMED_NIGHT, ARMED_VACATION, ARMED_CUSTOM_BYPASS,
    PENDING, ARMING, DISARMING, TRIGGERED,
}

enum class AlarmMode { HOME, AWAY, NIGHT, VACATION, CUSTOM_BYPASS }
```

`EntityState.kt`:

```kotlin
package com.gekkotron.oikos.model

import java.time.Instant

data class EntityState(
    val uniqueId: String,
    val value: StateValue,
    val attributes: Map<String, Any?> = emptyMap(),
    val available: Boolean = true,
    /** Time of the last confirmed state; null until the first message. */
    val lastUpdate: Instant? = null,
    /** True while an optimistic command waits for confirmation. */
    val pending: Boolean = false,
)
```

`Command.kt`:

```kotlin
package com.gekkotron.oikos.model

data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB channels must be 0..255" }
    }
}

sealed interface Command {
    /** [brightness] is 0..255. */
    data class TurnOn(val brightness: Int? = null, val color: Rgb? = null) : Command {
        init {
            require(brightness == null || brightness in 0..255) { "brightness must be 0..255" }
        }
    }
    data object TurnOff : Command
    data object Toggle : Command
    data class SetPosition(val position: Int) : Command {
        init {
            require(position in 0..100) { "position must be 0..100" }
        }
    }
    data object Open : Command
    data object Close : Command
    data object Stop : Command
    data class SetTemperature(val temperature: Double) : Command
    data class SetHvacMode(val mode: String) : Command
    data class SetNumber(val value: Double) : Command
    data class SelectOption(val option: String) : Command
    data object Press : Command
    data class Arm(val mode: AlarmMode, val code: String? = null) : Command
    data class Disarm(val code: String? = null) : Command
}

sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Rejected(val reason: String) : CommandResult
}

/** Emitted when an optimistic command was not confirmed in time. */
data class CommandFailure(val uniqueId: String, val command: Command, val reason: String)
```

`UiOverride.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Retained JSON on `oikos/ui/<unique_id>`. */
@Serializable
data class UiOverride(
    val name: String? = null,
    /** Area id (or a raw area name, resolved by the source). */
    val area: String? = null,
    val hidden: Boolean = false,
    val order: Int? = null,
    val cardStyle: String? = null,
    val favorite: Boolean = false,
    val energyRole: EnergyRole? = null,
)

@Serializable
enum class EnergyRole {
    @SerialName("solar") SOLAR,
    @SerialName("grid") GRID,
    @SerialName("house") HOUSE,
    @SerialName("water_heater") WATER_HEATER,
    @SerialName("water") WATER,
}

object UiOverrides {
    fun decode(payload: String): UiOverride? =
        runCatching { OikosJson.decodeFromString(UiOverride.serializer(), payload) }.getOrNull()

    fun encode(override: UiOverride): String = OikosJson.encodeToString(UiOverride.serializer(), override)
}
```

`OikosJson.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlinx.serialization.json.Json

/** Lenient JSON for everything Oikos reads from the broker. */
val OikosJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // Unknown enum values (e.g. a role added by a newer app) fall back to the default (null).
    coerceInputValues = true
}
```

`Diagnostics.kt`:

```kotlin
package com.gekkotron.oikos.model

/** A non-fatal problem worth showing in Settings → Logs. */
data class Diagnostic(val source: String, val message: String)

fun interface DiagnosticSink {
    fun report(diagnostic: Diagnostic)

    companion object {
        val None: DiagnosticSink = DiagnosticSink { }
    }
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :core:model:test`
Expected: PASS (9 tests).

If `unknown energy role becomes null` fails, `coerceInputValues` did not apply to the nullable enum. In that case keep the test and add `@Serializable(with = …)` only as a last resort; first check that `OikosJson` is really the instance used.

- [ ] **Step 10: Add CI and Codecov**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main, master]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v5
      - name: Build, test, coverage
        run: ./gradlew check koverXmlReport --stacktrace
      - uses: codecov/codecov-action@v5
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          files: build/reports/kover/report.xml
          fail_ci_if_error: false
```

`codecov.yml`:

```yaml
coverage:
  status:
    project:
      default:
        target: 80%
        threshold: 2%
    patch:
      default:
        target: 80%

ignore:
  - "**/build/generated/**"
```

- [ ] **Step 11: Verify the merged coverage report**

Run: `./gradlew check koverXmlReport && ls -l build/reports/kover/report.xml`
Expected: BUILD SUCCESSFUL, and the file exists.

- [ ] **Step 12: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat build-logic/ .github/ codecov.yml core/model/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "build: Gradle scaffold, convention plugins, CI and core:model" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 2: `:core:crypto` — gateway envelope

**Files:**
- Modify: `settings.gradle.kts` (add include), `build.gradle.kts` (add kover dependency)
- Create: `core/crypto/build.gradle.kts`, `core/crypto/src/main/kotlin/com/gekkotron/oikos/crypto/EnvelopeCrypto.kt`, `core/crypto/src/main/kotlin/com/gekkotron/oikos/crypto/EnvelopeException.kt`
- Test: `core/crypto/src/test/kotlin/com/gekkotron/oikos/crypto/EnvelopeCryptoTest.kt`

**Interfaces:**
- Consumes: nothing from other modules.
- Produces (used by the later GatewayTransport plan):
  - `class EnvelopeCrypto(key: ByteArray, maxAgeSeconds: Long = 60, nowSeconds: () -> Long = …, nonceSource: () -> ByteArray = …)`
    - `fun seal(fields: JsonObject): String`: adds `timestamp` when absent, returns `base64(nonce‖ct‖tag)`
    - `fun sealBytes(plaintext: ByteArray): String`
    - `fun open(envelope: String): JsonObject`: no freshness check (SSE events)
    - `fun openFresh(envelope: String): JsonObject`: rejects `|now − timestamp| > maxAgeSeconds` (responses)
    - `companion object { fun keyFromHex(hex: String): ByteArray }`
  - `sealed class EnvelopeException : Exception` with `Malformed(reason)`, `AuthenticationFailed`, `MissingTimestamp`, `Expired(skewSeconds: Long)`

The wire format is exactly the gateway's `server/crypto.py`: `AESGCM(key).encrypt(nonce, json, None)`, with the 12-byte nonce prepended and the whole thing base64-encoded. The test vector below was produced with Python `cryptography` using that scheme, key `000102…1f`, nonce `000102…0b`.

- [ ] **Step 1: Register the module**

`settings.gradle.kts`: change `include(":core:model")` to

```kotlin
include(":core:model")
include(":core:crypto")
```

`build.gradle.kts`: add inside `dependencies { … }`

```kotlin
    kover(project(":core:crypto"))
```

`core/crypto/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.jvm.library")
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
```

- [ ] **Step 2: Write the failing tests**

`core/crypto/src/test/kotlin/com/gekkotron/oikos/crypto/EnvelopeCryptoTest.kt`:

```kotlin
package com.gekkotron.oikos.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvelopeCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val fixedNonce = ByteArray(12) { it.toByte() }

    // Produced by the gateway scheme (Python cryptography AESGCM), key 00..1f, nonce 00..0b.
    private val vectorPlaintext = """{"timestamp": 1790241732, "topic": "oikos/test", "message": "hello"}"""
    private val vectorEnvelope =
        "AAECAwQFBgcICQoLPCCicqiAsW/sLOepi8lJWrrmtQDBTGxOFEfH8XIZadEjKo7ewKh59weLC4j78woUznsN6Cmlwr1atRA5OouQgpxT5AeCAOF1ohlJF6GrNozuys49"

    private fun crypto(now: Long = 1790241732L) =
        EnvelopeCrypto(key, maxAgeSeconds = 60, nowSeconds = { now }, nonceSource = { fixedNonce.copyOf() })

    @Test
    fun `sealBytes matches the gateway vector byte for byte`() {
        assertEquals(vectorEnvelope, crypto().sealBytes(vectorPlaintext.encodeToByteArray()))
    }

    @Test
    fun `open decrypts the gateway vector`() {
        val obj = crypto().open(vectorEnvelope)
        assertEquals(1790241732L, obj["timestamp"]!!.jsonPrimitive.long)
        assertEquals("oikos/test", obj["topic"]!!.jsonPrimitive.content)
        assertEquals("hello", obj["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `seal adds timestamp and round-trips`() {
        val c = crypto(now = 1000)
        val obj = c.open(c.seal(JsonObject(mapOf("topic" to JsonPrimitive("a/b")))))
        assertEquals(1000L, obj["timestamp"]!!.jsonPrimitive.long)
        assertEquals("a/b", obj["topic"]!!.jsonPrimitive.content)
    }

    @Test
    fun `seal keeps an explicit timestamp`() {
        val c = crypto(now = 1000)
        val obj = c.open(c.seal(JsonObject(mapOf("timestamp" to JsonPrimitive(5)))))
        assertEquals(5L, obj["timestamp"]!!.jsonPrimitive.long)
    }

    @Test
    fun `random nonces differ between calls`() {
        val c = EnvelopeCrypto(key)
        val a = c.sealBytes("x".encodeToByteArray())
        val b = c.sealBytes("x".encodeToByteArray())
        assert(a != b)
    }

    @Test
    fun `wrong key fails authentication`() {
        val other = EnvelopeCrypto(ByteArray(32) { 7 })
        assertFailsWith<EnvelopeException.AuthenticationFailed> { other.open(vectorEnvelope) }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val raw = Base64.getDecoder().decode(vectorEnvelope)
        raw[20] = (raw[20].toInt() xor 1).toByte()
        assertFailsWith<EnvelopeException.AuthenticationFailed> {
            crypto().open(Base64.getEncoder().encodeToString(raw))
        }
    }

    @Test
    fun `truncated input is malformed`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(27))
        assertFailsWith<EnvelopeException.Malformed> { crypto().open(short) }
    }

    @Test
    fun `non-base64 input is malformed`() {
        assertFailsWith<EnvelopeException.Malformed> { crypto().open("%%% not base64 %%%") }
    }

    @Test
    fun `plaintext that is not a JSON object is malformed`() {
        val c = crypto()
        assertFailsWith<EnvelopeException.Malformed> { c.open(c.sealBytes("[1,2]".encodeToByteArray())) }
        assertFailsWith<EnvelopeException.Malformed> { c.open(c.sealBytes("nope".encodeToByteArray())) }
    }

    @Test
    fun `openFresh accepts the window edges and rejects outside`() {
        crypto(now = 1790241732L + 60).openFresh(vectorEnvelope)
        crypto(now = 1790241732L - 60).openFresh(vectorEnvelope)
        val late = assertFailsWith<EnvelopeException.Expired> { crypto(now = 1790241732L + 61).openFresh(vectorEnvelope) }
        assertEquals(61L, late.skewSeconds)
        assertFailsWith<EnvelopeException.Expired> { crypto(now = 1790241732L - 61).openFresh(vectorEnvelope) }
    }

    @Test
    fun `openFresh requires a timestamp`() {
        val c = crypto()
        val env = c.sealBytes("""{"status":200}""".encodeToByteArray())
        assertFailsWith<EnvelopeException.MissingTimestamp> { c.openFresh(env) }
    }

    @Test
    fun `openFresh accepts a float timestamp`() {
        val c = crypto(now = 100)
        c.openFresh(c.sealBytes("""{"timestamp":100.7}""".encodeToByteArray()))
    }

    @Test
    fun `key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { EnvelopeCrypto(ByteArray(16)) }
    }

    @Test
    fun `keyFromHex parses 64 hex chars and rejects the rest`() {
        assertEquals(key.toList(), EnvelopeCrypto.keyFromHex("000102030405060708090a0b0c0d0e0f101112131415161718191A1B1C1D1E1F").toList())
        assertFailsWith<IllegalArgumentException> { EnvelopeCrypto.keyFromHex("abcd") }
        assertFailsWith<IllegalArgumentException> { EnvelopeCrypto.keyFromHex("zz".repeat(32)) }
    }

    @Test
    fun `exception messages never contain the key`() {
        val hex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val e = assertFailsWith<EnvelopeException> { EnvelopeCrypto(ByteArray(32) { 7 }).open(vectorEnvelope) }
        assert(hex !in (e.message ?: ""))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:crypto:test`
Expected: FAIL, with compilation errors (`Unresolved reference: EnvelopeCrypto`).

- [ ] **Step 4: Implement**

`core/crypto/src/main/kotlin/com/gekkotron/oikos/crypto/EnvelopeException.kt`:

```kotlin
package com.gekkotron.oikos.crypto

sealed class EnvelopeException(message: String) : Exception(message) {
    class Malformed(reason: String) : EnvelopeException("malformed envelope: $reason")
    class AuthenticationFailed : EnvelopeException("authentication failed (wrong key or tampered data)")
    class MissingTimestamp : EnvelopeException("envelope has no timestamp")
    class Expired(val skewSeconds: Long) : EnvelopeException("timestamp outside the accepted window (skew ${skewSeconds}s)")
}
```

`core/crypto/src/main/kotlin/com/gekkotron/oikos/crypto/EnvelopeCrypto.kt`:

```kotlin
package com.gekkotron.oikos.crypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * AES-256-GCM envelope used by Athena-HttpMqttGateway:
 * `base64(nonce[12] ‖ ciphertext ‖ tag[16])` over UTF-8 JSON that carries `timestamp` (epoch seconds).
 */
class EnvelopeCrypto(
    key: ByteArray,
    private val maxAgeSeconds: Long = 60,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nonceSource: () -> ByteArray = ::randomNonce,
) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == KEY_BYTES) { "AES-256 key must be $KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    /** Encrypts [fields], adding `timestamp` when absent. */
    fun seal(fields: JsonObject): String {
        val stamped = if ("timestamp" in fields) fields else JsonObject(fields + ("timestamp" to JsonPrimitive(nowSeconds())))
        return sealBytes(stamped.toString().encodeToByteArray())
    }

    fun sealBytes(plaintext: ByteArray): String {
        val nonce = nonceSource()
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext))
    }

    /** Decrypts without a freshness check (used for SSE events). */
    fun open(envelope: String): JsonObject {
        val raw = try {
            Base64.getDecoder().decode(envelope.trim())
        } catch (e: IllegalArgumentException) {
            throw EnvelopeException.Malformed("not base64")
        }
        if (raw.size < NONCE_BYTES + TAG_BITS / 8) throw EnvelopeException.Malformed("too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES))
        val plaintext = try {
            cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw EnvelopeException.AuthenticationFailed()
        }
        val element = try {
            Json.parseToJsonElement(plaintext.decodeToString())
        } catch (e: SerializationException) {
            throw EnvelopeException.Malformed("plaintext is not JSON")
        }
        return element as? JsonObject ?: throw EnvelopeException.Malformed("plaintext is not a JSON object")
    }

    /** Decrypts and rejects a `timestamp` outside ±maxAgeSeconds (used for responses). */
    fun openFresh(envelope: String): JsonObject {
        val obj = open(envelope)
        val ts = (obj["timestamp"] as? JsonPrimitive)?.doubleOrNull?.toLong()
            ?: throw EnvelopeException.MissingTimestamp()
        val skew = nowSeconds() - ts
        if (abs(skew) > maxAgeSeconds) throw EnvelopeException.Expired(skew)
        return obj
    }

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Parses the 64-character hex key from the onboarding QR payload. */
        fun keyFromHex(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length == KEY_BYTES * 2 && clean.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                "key must be ${KEY_BYTES * 2} hex characters"
            }
            return ByteArray(KEY_BYTES) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}

private fun randomNonce(): ByteArray = ByteArray(EnvelopeCrypto.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:crypto:test`
Expected: PASS (16 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/crypto/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(crypto): AES-256-GCM gateway envelope with gateway test vector" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `:core:transport` — contract, topic matching, fake

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `core/transport/build.gradle.kts`, `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/MqttTransport.kt`, `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/TopicFilter.kt`
- Create: `core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/FakeMqttTransport.kt`
- Test: `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TopicFilterTest.kt`, `core/transport/src/test/kotlin/com/gekkotron/oikos/transport/FakeMqttTransportTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface MqttTransport { val link: StateFlow<LinkState>; fun messages(): SharedFlow<MqttMessage>; suspend fun setSubscriptions(filters: Set<String>); suspend fun publish(topic: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false) }` (spec §4.5)
  - `class MqttMessage(topic: String, payload: ByteArray, retained: Boolean)` with content equality and `fun payloadString(): String`
  - `enum class TransportMode { DIRECT, GATEWAY }`; `sealed interface LinkState { Connecting; Connected(mode); Reconnecting(attempt: Int); Offline(reason: String) }`
  - `class PublishFailed(message: String) : Exception`
  - `fun topicMatches(filter: String, topic: String): Boolean`, `fun isValidPublishTopic(topic: String): Boolean`
  - Test fixture `com.gekkotron.oikos.transport.testing.FakeMqttTransport`, a broker-like fake: it keeps retained messages, replays them to newly added filters, echoes publishes to matching subscriptions, and exposes `subscriptions`, `subscriptionHistory`, `published`, `publishedStrings()`, `failPublishes`, `deliver(topic, payload, retain)` and `setLink(state)`. Consumers add `testImplementation(testFixtures(project(":core:transport")))`.

- [ ] **Step 1: Register the module**

`settings.gradle.kts`: add `include(":core:transport")` after the crypto include.
`build.gradle.kts`: add `kover(project(":core:transport"))` in `dependencies`.

`core/transport/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.jvm.library")
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 2: Write the failing tests**

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/TopicFilterTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicFilterTest {
    @Test
    fun `exact match`() {
        assertTrue(topicMatches("a/b", "a/b"))
        assertFalse(topicMatches("a/b", "a/c"))
        assertFalse(topicMatches("a/b", "a/b/c"))
    }

    @Test
    fun `single-level wildcard`() {
        assertTrue(topicMatches("a/+/c", "a/b/c"))
        assertFalse(topicMatches("a/+", "a/b/c"))
        assertTrue(topicMatches("fcm-push/tokens/+", "fcm-push/tokens/abc"))
    }

    @Test
    fun `multi-level wildcard includes the parent level`() {
        assertTrue(topicMatches("homeassistant/#", "homeassistant/light/x/config"))
        assertTrue(topicMatches("a/#", "a"))
        assertTrue(topicMatches("#", "anything/at/all"))
    }

    @Test
    fun `dollar topics are not matched by leading wildcards`() {
        assertFalse(topicMatches("#", "\$SYS/broker/uptime"))
        assertFalse(topicMatches("+/broker", "\$SYS/broker"))
        assertTrue(topicMatches("\$SYS/#", "\$SYS/broker/uptime"))
    }

    @Test
    fun `publish topics cannot contain wildcards`() {
        assertTrue(isValidPublishTopic("oikos/ui/0x00_light"))
        assertFalse(isValidPublishTopic("oikos/ui/a+b"))
        assertFalse(isValidPublishTopic("oikos/ui/#"))
        assertFalse(isValidPublishTopic(""))
    }
}
```

`core/transport/src/test/kotlin/com/gekkotron/oikos/transport/FakeMqttTransportTest.kt`:

```kotlin
package com.gekkotron.oikos.transport

import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeMqttTransportTest {
    @Test
    fun `retained messages replay only to newly added filters`() = runTest {
        val t = FakeMqttTransport()
        t.deliver("a/1", "x", retain = true)
        t.deliver("b/1", "y", retain = true)
        val got = mutableListOf<MqttMessage>()
        backgroundScope.launch { t.messages().collect { got += it } }
        runCurrent()

        t.setSubscriptions(setOf("a/#"))
        t.setSubscriptions(setOf("a/#", "b/#"))
        runCurrent()

        assertEquals(listOf("a/1" to "x", "b/1" to "y"), got.map { it.topic to it.payloadString() })
        assertEquals(true, got.all { it.retained })
    }

    @Test
    fun `live messages reach matching subscriptions only`() = runTest {
        val t = FakeMqttTransport()
        val got = mutableListOf<String>()
        backgroundScope.launch { t.messages().collect { got += it.topic } }
        runCurrent()
        t.setSubscriptions(setOf("a/+"))
        t.deliver("a/1", "x")
        t.deliver("c/1", "x")
        runCurrent()
        assertEquals(listOf("a/1"), got)
    }

    @Test
    fun `publish is recorded, echoed, and empty retained clears`() = runTest {
        val t = FakeMqttTransport()
        t.setSubscriptions(setOf("oikos/ui/#"))
        t.publish("oikos/ui/x", "{}".encodeToByteArray(), retain = true)
        assertEquals(listOf("oikos/ui/x" to "{}"), t.publishedStrings())

        t.publish("oikos/ui/x", ByteArray(0), retain = true)
        val got = mutableListOf<String>()
        backgroundScope.launch { t.messages().collect { got += it.topic } }
        runCurrent()
        t.setSubscriptions(emptySet())
        t.setSubscriptions(setOf("oikos/ui/#"))
        runCurrent()
        assertEquals(emptyList(), got)
    }

    @Test
    fun `failPublishes throws PublishFailed`() = runTest {
        val t = FakeMqttTransport().apply { failPublishes = true }
        kotlin.test.assertFailsWith<PublishFailed> { t.publish("a", ByteArray(0)) }
    }

    @Test
    fun `message equality compares payload content`() {
        assertEquals(MqttMessage("a", byteArrayOf(1), false), MqttMessage("a", byteArrayOf(1), false))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:transport:test`
Expected: FAIL, with compilation errors (`Unresolved reference: topicMatches`, `FakeMqttTransport`).

- [ ] **Step 4: Implement**

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/MqttTransport.kt`:

```kotlin
package com.gekkotron.oikos.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportMode { DIRECT, GATEWAY }

sealed interface LinkState {
    data object Connecting : LinkState
    data class Connected(val mode: TransportMode) : LinkState
    data class Reconnecting(val attempt: Int) : LinkState
    data class Offline(val reason: String) : LinkState
}

class MqttMessage(val topic: String, val payload: ByteArray, val retained: Boolean) {
    fun payloadString(): String = payload.decodeToString()

    override fun equals(other: Any?): Boolean =
        other is MqttMessage && topic == other.topic && retained == other.retained && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (topic.hashCode() * 31 + payload.contentHashCode()) * 31 + retained.hashCode()

    override fun toString(): String = "MqttMessage($topic, ${payload.size} bytes, retained=$retained)"
}

class PublishFailed(message: String) : Exception(message)

interface MqttTransport {
    val link: StateFlow<LinkState>

    fun messages(): SharedFlow<MqttMessage>

    /** Declarative: the transport diffs against the current set and (re)subscribes. */
    suspend fun setSubscriptions(filters: Set<String>)

    /** @throws PublishFailed when the message could not be delivered to the broker. */
    suspend fun publish(topic: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false)
}
```

`core/transport/src/main/kotlin/com/gekkotron/oikos/transport/TopicFilter.kt`:

```kotlin
package com.gekkotron.oikos.transport

/** MQTT 3.1.1 topic-filter matching, including the `$`-topic rule (§4.7.2). */
fun topicMatches(filter: String, topic: String): Boolean {
    if (topic.startsWith("$") && (filter.startsWith("#") || filter.startsWith("+"))) return false
    val f = filter.split('/')
    val t = topic.split('/')
    for (i in f.indices) {
        when (f[i]) {
            "#" -> return true
            "+" -> if (i >= t.size) return false
            else -> if (i >= t.size || f[i] != t[i]) return false
        }
    }
    return f.size == t.size
}

fun isValidPublishTopic(topic: String): Boolean =
    topic.isNotEmpty() && '+' !in topic && '#' !in topic && '\u0000' !in topic
```

`core/transport/src/testFixtures/kotlin/com/gekkotron/oikos/transport/testing/FakeMqttTransport.kt`:

```kotlin
package com.gekkotron.oikos.transport.testing

import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.transport.isValidPublishTopic
import com.gekkotron.oikos.transport.topicMatches
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory broker + transport for tests. Not thread-safe; use from a single test dispatcher. */
class FakeMqttTransport : MqttTransport {
    private val _link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DIRECT))
    override val link: StateFlow<LinkState> = _link

    private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    private val retainedStore = linkedMapOf<String, ByteArray>()

    var subscriptions: Set<String> = emptySet()
        private set
    val subscriptionHistory = mutableListOf<Set<String>>()
    val published = mutableListOf<MqttMessage>()
    var failPublishes = false

    override fun messages(): SharedFlow<MqttMessage> = _messages

    override suspend fun setSubscriptions(filters: Set<String>) {
        val added = filters - subscriptions
        subscriptions = filters
        subscriptionHistory += filters
        for ((topic, payload) in retainedStore.toList()) {
            if (added.any { topicMatches(it, topic) }) _messages.emit(MqttMessage(topic, payload, retained = true))
        }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (failPublishes) throw PublishFailed("fake publish failure")
        require(isValidPublishTopic(topic)) { "invalid publish topic '$topic'" }
        published += MqttMessage(topic, payload, retain)
        brokerReceive(topic, payload, retain)
    }

    /** Simulates another client publishing to the broker. */
    suspend fun deliver(topic: String, payload: String, retain: Boolean = false) =
        brokerReceive(topic, payload.encodeToByteArray(), retain)

    fun setLink(state: LinkState) {
        _link.value = state
    }

    fun publishedStrings(): List<Pair<String, String>> = published.map { it.topic to it.payloadString() }

    private suspend fun brokerReceive(topic: String, payload: ByteArray, retain: Boolean) {
        if (retain) {
            if (payload.isEmpty()) retainedStore.remove(topic) else retainedStore[topic] = payload
        }
        if (subscriptions.any { topicMatches(it, topic) }) _messages.emit(MqttMessage(topic, payload, retained = false))
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:transport:test`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/transport/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(transport): MqttTransport contract, topic matching and broker-like fake" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 4: `:core:state`: template evaluator (Jinja subset)

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `core/state/build.gradle.kts`
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/template/TemplateParser.kt`, `Templates.kt`, `TemplateValues.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/template/TemplatesTest.kt`

**Interfaces:**
- Consumes: `:core:model` (module dependency only).
- Produces:
  - `sealed interface TemplateResult { data class Value(val value: Any?); data class Unsupported(val reason: String); data class Error(val reason: String) }`. `Value.value` is `String | Long | Double | Boolean | JsonObject | JsonArray | null` (null = undefined or none).
  - `object Templates { fun renderValue(template: String, payload: String): TemplateResult; fun render(template: String, variables: Map<String, Any?>): TemplateResult }`. `renderValue` binds `value` (the raw payload) and `value_json` (the parsed payload).
  - `fun Any?.asPayloadString(): String?` (Python-style: `true` → `"True"`), `fun Any?.asDouble(): Double?`, `fun formatNumber(d: Double): String` (`21.0` → `"21"`, `21.5` → `"21.5"`).

**Supported subset (spec §4.4):** `{{ value }}`, `{{ value_json.a.b }}`, `{{ value_json['key with spaces'] }}`, `[0]` indexes, mixed forms, any variable name (for command templates: `position`, `action`, `code`, `value`), literals, and the filters `int`, `float`, `round(n)`, `lower`, `upper`, `default(x)`. Literal text around `{{ }}` is concatenated. Everything else (`{% %}`, `{# #}`, operators, `if`, calls, other filters) is **Unsupported**. **Error** means the template is supported but this payload cannot be evaluated (for example `value_json` on a non-JSON payload, or `round` on a string). The caller keeps the previous state.

- [ ] **Step 1: Register the module**

`settings.gradle.kts`: add `include(":core:state")`. `build.gradle.kts`: add `kover(project(":core:state"))`.

`core/state/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 2: Write the failing tests**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/template/TemplatesTest.kt`:

```kotlin
package com.gekkotron.oikos.state.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TemplatesTest {
    private fun v(template: String, payload: String): Any? {
        val r = Templates.renderValue(template, payload)
        assertIs<TemplateResult.Value>(r, "expected a value for $template, got $r")
        return r.value
    }

    @Test
    fun `value is the raw payload`() {
        assertEquals("21.5", v("{{ value }}", "21.5"))
        assertEquals("online", v("{{value}}", "online"))
    }

    @Test
    fun `value_json attribute and nested access`() {
        assertEquals(21.5, v("{{ value_json.temperature }}", """{"temperature":21.5}"""))
        assertEquals(3L, v("{{ value_json.a.b }}", """{"a":{"b":3}}"""))
        assertEquals(true, v("{{ value_json.contact }}", """{"contact":true}"""))
    }

    @Test
    fun `bracket access with spaces and unicode, mixed with dots`() {
        assertEquals(22.5, v("{{ value_json['température_int.'] }}", """{"température_int.":22.5}"""))
        assertEquals("x", v("""{{ value_json.data["k k"].v }}""", """{"data":{"k k":{"v":"x"}}}"""))
        assertEquals(20L, v("{{ value_json.list[1] }}", """{"list":[10,20]}"""))
    }

    @Test
    fun `missing keys are undefined and default rescues them`() {
        assertEquals(null, v("{{ value_json.nope }}", """{"a":1}"""))
        assertEquals(null, v("{{ value_json.nope.deeper }}", """{"a":1}"""))
        assertEquals("x", v("{{ value_json.nope | default('x') }}", """{"a":1}"""))
        assertEquals(1L, v("{{ value_json.a | default('x') }}", """{"a":1}"""))
    }

    @Test
    fun `json null stays null and is not rescued by default`() {
        assertEquals(null, v("{{ value_json.a | default('x') }}", """{"a":null}"""))
    }

    @Test
    fun `numeric filters`() {
        assertEquals(21L, v("{{ value | int }}", "21.7"))
        assertEquals(0L, v("{{ value | int }}", "abc"))
        assertEquals(21.0, v("{{ value | float }}", "21"))
        assertEquals(21.5, v("{{ value_json.t | round(1) }}", """{"t":21.456}"""))
        assertEquals(22.0, v("{{ value_json.t | round }}", """{"t":21.5}"""))
        assertEquals(21.5, v("{{ value_json.t | float | round(1) }}", """{"t":"21.46"}"""))
    }

    @Test
    fun `string filters`() {
        assertEquals("on", v("{{ value | lower }}", "ON"))
        assertEquals("OFF", v("{{ value_json.state | upper }}", """{"state":"off"}"""))
        assertEquals("true", v("{{ value_json.b | lower }}", """{"b":true}"""))
    }

    @Test
    fun `literals`() {
        assertEquals("a", v("{{ 'a' }}", "x"))
        assertEquals(-1L, v("{{ -1 }}", "x"))
        assertEquals(null, v("{{ none }}", "x"))
    }

    @Test
    fun `text around expressions is concatenated`() {
        val r = Templates.render("""{ "position": {{ position }} }""", mapOf("position" to 42L))
        assertEquals(TemplateResult.Value("""{ "position": 42 }"""), r)
        assertEquals(TemplateResult.Value("""{"a": 1}"""), Templates.render("""{"a": {{ value }}}""", mapOf("value" to 1L)))
    }

    @Test
    fun `a template without expressions is its own text`() {
        assertEquals("ON", v("ON", "ignored"))
    }

    @Test
    fun `surrounding whitespace keeps the typed value`() {
        assertEquals(21.5, v("  {{ value_json.t }} ", """{"t":21.5}"""))
    }

    @Test
    fun `command variables`() {
        assertEquals(
            TemplateResult.Value("""{"action":"ARM_AWAY","code":"1234"}"""),
            Templates.render("""{"action":"{{ action }}","code":"{{ code }}"}""", mapOf("action" to "ARM_AWAY", "code" to "1234")),
        )
    }

    @Test
    fun `unsupported syntax is reported, not thrown`() {
        listOf(
            "{% if value_json.moving %}MOVING{% endif %}",
            "{# comment #}",
            "{{ value_json.x == 1 }}",
            "{{ now() }}",
            "{{ value | tojson }}",
            "{{ value",
            "{{ 'a' if value else 'b' }}",
            "{{ value_json.x + 1 }}",
            "{{ }}",
        ).forEach { t -> assertIs<TemplateResult.Unsupported>(Templates.renderValue(t, "1"), t) }
    }

    @Test
    fun `evaluation errors are reported, not thrown`() {
        assertIs<TemplateResult.Error>(Templates.renderValue("{{ value_json.state }}", "online"))
        assertIs<TemplateResult.Error>(Templates.renderValue("{{ value | round(1) }}", "abc"))
    }

    @Test
    fun `payload string helpers`() {
        assertEquals("True", true.asPayloadString())
        assertEquals("False", false.asPayloadString())
        assertEquals("21", 21L.asPayloadString())
        assertEquals(null, null.asPayloadString())
        assertEquals(21.5, "21.5".asDouble())
        assertEquals(null, "abc".asDouble())
        assertEquals("21", formatNumber(21.0))
        assertEquals("21.5", formatNumber(21.5))
        assertEquals("-3", formatNumber(-3.0))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:state:test`
Expected: FAIL, with compilation errors (`Unresolved reference: Templates`).

- [ ] **Step 4: Implement the parser**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/template/TemplateParser.kt`:

```kotlin
package com.gekkotron.oikos.state.template

internal class UnsupportedTemplate(message: String) : Exception(message)

internal class TemplateEvalError(message: String) : Exception(message)

/** Jinja "undefined": a missing variable or key. Rescued by `default`, rendered as "" in text. */
internal object Undefined

/** Bound to `value_json` when the payload is not JSON; any use raises an evaluation error. */
internal object JsonFailure

internal sealed interface Expression {
    data class Literal(val value: Any?) : Expression
    data class Variable(val name: String) : Expression
    data class Access(val target: Expression, val key: Any) : Expression
    data class Filtered(val target: Expression, val name: String, val args: List<Any?>) : Expression
}

internal sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Expr(val expression: Expression) : Segment
}

internal sealed interface Token {
    data class Ident(val name: String) : Token
    data class Num(val value: Any) : Token
    data class Str(val value: String) : Token
    data class Sym(val c: Char) : Token
}

internal object TemplateParser {
    val FILTERS = setOf("int", "float", "round", "lower", "upper", "default")

    fun parse(template: String): List<Segment> {
        if ("{%" in template || "{#" in template) throw UnsupportedTemplate("statements and comments are not supported")
        val segments = mutableListOf<Segment>()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf("{{", i)
            if (open < 0) {
                segments += Segment.Text(template.substring(i))
                break
            }
            if (open > i) segments += Segment.Text(template.substring(i, open))
            val close = template.indexOf("}}", open + 2)
            if (close < 0) throw UnsupportedTemplate("unclosed '{{'")
            segments += Segment.Expr(ExpressionParser(tokenize(template.substring(open + 2, close))).parseAll())
            i = close + 2
        }
        return segments
    }

    private fun tokenize(src: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Token.Ident(src.substring(start, i))
                }
                c.isDigit() || (c == '-' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val start = i
                    i++
                    while (i < src.length && src[i].isDigit()) i++
                    var fractional = false
                    if (i + 1 < src.length && src[i] == '.' && src[i + 1].isDigit()) {
                        fractional = true
                        i++
                        while (i < src.length && src[i].isDigit()) i++
                    }
                    val text = src.substring(start, i)
                    out += Token.Num(if (fractional) text.toDouble() else text.toLongOrNull() ?: text.toDouble())
                }
                c == '\'' || c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (true) {
                        if (i >= src.length) throw UnsupportedTemplate("unterminated string")
                        val ch = src[i]
                        if (ch == c) {
                            i++
                            break
                        }
                        if (ch == '\\' && i + 1 < src.length) {
                            sb.append(src[i + 1])
                            i += 2
                        } else {
                            sb.append(ch)
                            i++
                        }
                    }
                    out += Token.Str(sb.toString())
                }
                c in ".[]|()," -> {
                    out += Token.Sym(c)
                    i++
                }
                else -> throw UnsupportedTemplate("unsupported syntax '$c'")
            }
        }
        return out
    }
}

private class ExpressionParser(private val tokens: List<Token>) {
    private var pos = 0

    private fun peek(): Token? = tokens.getOrNull(pos)
    private fun next(): Token = tokens.getOrNull(pos++) ?: throw UnsupportedTemplate("unexpected end of expression")
    private fun isSym(c: Char) = (peek() as? Token.Sym)?.c == c
    private fun expectSym(c: Char) {
        if ((next() as? Token.Sym)?.c != c) throw UnsupportedTemplate("expected '$c'")
    }

    fun parseAll(): Expression {
        if (tokens.isEmpty()) throw UnsupportedTemplate("empty expression")
        var expr = parsePostfix(parsePrimary())
        while (isSym('|')) {
            next()
            val name = (next() as? Token.Ident)?.name ?: throw UnsupportedTemplate("expected a filter name")
            if (name !in TemplateParser.FILTERS) throw UnsupportedTemplate("filter '$name' is not supported")
            val args = mutableListOf<Any?>()
            if (isSym('(')) {
                next()
                if (!isSym(')')) {
                    args += literal(next())
                    while (isSym(',')) {
                        next()
                        args += literal(next())
                    }
                }
                expectSym(')')
            }
            expr = Expression.Filtered(expr, name, args)
        }
        peek()?.let { throw UnsupportedTemplate("unsupported expression near $it") }
        return expr
    }

    private fun parsePrimary(): Expression = when (val t = next()) {
        is Token.Ident -> keywordLiteral(t.name)?.let { Expression.Literal(it.value) } ?: Expression.Variable(t.name)
        is Token.Num -> Expression.Literal(t.value)
        is Token.Str -> Expression.Literal(t.value)
        is Token.Sym -> throw UnsupportedTemplate("unexpected '${t.c}'")
    }

    private fun parsePostfix(start: Expression): Expression {
        var e = start
        while (true) {
            e = when {
                isSym('.') -> {
                    next()
                    when (val k = next()) {
                        is Token.Ident -> Expression.Access(e, k.name)
                        is Token.Num -> Expression.Access(e, k.value)
                        else -> throw UnsupportedTemplate("expected an attribute name")
                    }
                }
                isSym('[') -> {
                    next()
                    val key: Any = when (val k = next()) {
                        is Token.Str -> k.value
                        is Token.Num -> k.value
                        else -> throw UnsupportedTemplate("only literal keys are supported in []")
                    }
                    expectSym(']')
                    Expression.Access(e, key)
                }
                else -> return e
            }
        }
    }

    private fun literal(t: Token): Any? = when (t) {
        is Token.Str -> t.value
        is Token.Num -> t.value
        is Token.Ident -> keywordLiteral(t.name)?.value ?: throw UnsupportedTemplate("filter arguments must be literals")
        is Token.Sym -> throw UnsupportedTemplate("filter arguments must be literals")
    }

    private class Keyword(val value: Any?)

    private fun keywordLiteral(name: String): Keyword? = when (name) {
        "true", "True" -> Keyword(true)
        "false", "False" -> Keyword(false)
        "none", "None" -> Keyword(null)
        else -> null
    }
}
```

- [ ] **Step 5: Implement evaluation and helpers**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/template/TemplateValues.kt`:

```kotlin
package com.gekkotron.oikos.state.template

import kotlin.math.abs
import kotlin.math.floor

/** Python-style string form, used to compare rendered values with HA payload options. */
fun Any?.asPayloadString(): String? = when (this) {
    null -> null
    is Boolean -> if (this) "True" else "False"
    is String -> this
    else -> toString()
}

fun Any?.asDouble(): Double? = when (this) {
    is Number -> toDouble()
    is String -> trim().toDoubleOrNull()
    else -> null
}

/** Formats a number for MQTT payloads: integral values drop the ".0". */
fun formatNumber(d: Double): String =
    if (!d.isInfinite() && !d.isNaN() && d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/template/Templates.kt`:

```kotlin
package com.gekkotron.oikos.state.template

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode

sealed interface TemplateResult {
    /** String, Long, Double, Boolean, JsonObject/JsonArray, or null (undefined / none). */
    data class Value(val value: Any?) : TemplateResult
    data class Unsupported(val reason: String) : TemplateResult
    data class Error(val reason: String) : TemplateResult
}

object Templates {
    /** Evaluates [template] with `value` = [payload] and `value_json` = the parsed payload. */
    fun renderValue(template: String, payload: String): TemplateResult =
        render(template, mapOf("value" to payload, "value_json" to parseJsonOrFailure(payload)))

    fun render(template: String, variables: Map<String, Any?>): TemplateResult {
        val segments = try {
            TemplateParser.parse(template)
        } catch (e: UnsupportedTemplate) {
            return TemplateResult.Unsupported(e.message ?: "unsupported template")
        }
        return try {
            TemplateResult.Value(evaluate(segments, variables))
        } catch (e: TemplateEvalError) {
            TemplateResult.Error(e.message ?: "template error")
        }
    }
}

private fun parseJsonOrFailure(payload: String): Any = try {
    Json.parseToJsonElement(payload)
} catch (e: SerializationException) {
    JsonFailure
}

private fun evaluate(segments: List<Segment>, vars: Map<String, Any?>): Any? {
    val expressions = segments.filterIsInstance<Segment.Expr>()
    if (expressions.isEmpty()) return segments.joinToString("") { (it as Segment.Text).text }
    val onlyWhitespaceAround = segments.all { it is Segment.Expr || (it as Segment.Text).text.isBlank() }
    if (expressions.size == 1 && onlyWhitespaceAround) {
        return eval(expressions.single().expression, vars).let { if (it === Undefined) null else it }
    }
    return segments.joinToString("") { segment ->
        when (segment) {
            is Segment.Text -> segment.text
            is Segment.Expr -> eval(segment.expression, vars).let { if (it === Undefined) "" else it.asPayloadString() ?: "None" }
        }
    }
}

/** JSON primitives become Kotlin values; objects and arrays are kept for further access. */
internal fun normalize(v: Any?): Any? = when (v) {
    is JsonNull -> null
    is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
    else -> v
}

private fun eval(e: Expression, vars: Map<String, Any?>): Any? = when (e) {
    is Expression.Literal -> e.value
    is Expression.Variable -> {
        if (e.name !in vars) {
            Undefined
        } else {
            val value = normalize(vars[e.name])
            if (value === JsonFailure) throw TemplateEvalError("payload is not valid JSON")
            value
        }
    }
    is Expression.Access -> access(eval(e.target, vars), e.key)
    is Expression.Filtered -> applyFilter(e.name, eval(e.target, vars), e.args)
}

private fun access(target: Any?, key: Any): Any? = when {
    target is JsonObject && key is String -> if (key in target) normalize(target[key]) else Undefined
    target is JsonArray && key is Long -> if (key >= 0 && key < target.size) normalize(target[key.toInt()]) else Undefined
    target is Map<*, *> && target.containsKey(key) -> normalize(target[key])
    else -> Undefined
}

private fun looseNumber(v: Any?): Number? = when (v) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v
    is Boolean -> if (v) 1L else 0L
    is String -> v.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull() }
    else -> null
}

private fun applyFilter(name: String, v: Any?, args: List<Any?>): Any? = when (name) {
    "default" -> if (v === Undefined) args.firstOrNull() else v
    "int" -> looseNumber(v)?.toLong() ?: 0L
    "float" -> looseNumber(v)?.toDouble() ?: 0.0
    "round" -> {
        val precision = (args.firstOrNull() as? Long)?.toInt() ?: 0
        val d = when (v) {
            is Long -> v.toDouble()
            is Double -> v
            else -> throw TemplateEvalError("round expects a number")
        }
        if (d.isNaN() || d.isInfinite()) d else BigDecimal.valueOf(d).setScale(precision, RoundingMode.HALF_UP).toDouble()
    }
    "lower" -> if (v === Undefined) "" else (v.asPayloadString() ?: "None").lowercase()
    "upper" -> if (v === Undefined) "" else (v.asPayloadString() ?: "None").uppercase()
    else -> throw TemplateEvalError("unknown filter '$name'")
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (15 tests).

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/state/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): Jinja-subset template evaluator with unsupported/error results" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: MQTT bindings, the mapper contract, and the switch/binary_sensor/sensor/button mappers

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/mqtt/MqttBindings.kt`
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt`, `Common.kt`, `SwitchMapper.kt`, `BinarySensorMapper.kt`, `SensorMapper.kt`, `ButtonMapper.kt`
- Create (test helper): `core/state/src/test/kotlin/com/gekkotron/oikos/state/TestBindings.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/SwitchMapperTest.kt`, `BinarySensorMapperTest.kt`, `SensorMapperTest.kt`, `ButtonMapperTest.kt`

**Interfaces:**
- Consumes: `Templates`, `TemplateResult`, `asPayloadString`, `asDouble` (Task 4); `StateValue`, `Command`, `Kind`, `Bindings` (Task 1).
- Produces:
  - `data class MqttBindings(val component: String, val config: JsonObject) : Bindings` with `str(key): String?` (JSON booleans → `"True"`/`"False"`, like HA's `cv.string`), `str(key, default)`, `double(key)`, `int(key, default)`, `bool(key, default)`, `strList(key)`.
  - `sealed interface Decoded { State(value); Ignored; Unsupported(reason); Error(reason) }`
  - `data class Outgoing(topic: String, payload: String, retain: Boolean, qos: Int)`; `sealed interface Encoded { Publish(messages: List<Outgoing>); NotSupported(reason) }`
  - `interface KindMapper { stateTopics(b): Set<String>; decode(b, topic, payload, previous): Decoded; encode(b, command, previous): Encoded; predict(command, previous): StateValue? }`. `stateTopics` returning empty means there is no state feedback (optimistic).
  - `object KindMappers { fun forKind(kind: Kind): KindMapper }`
  - Internal helpers used by Tasks 6–8, all in `Common.kt`: `renderIncoming`, `decodeWith`, `publish`, `outgoing`, `targetOn`, `isOn`, `onOffFrom`, `PartialDecode`.
  - Test helpers `bindings(component, json)` and `testEntity(id, kind, component, json)` in package `com.gekkotron.oikos.state` (test source set).

HA defaults used here: `payload_on` `"ON"`, `payload_off` `"OFF"`; a switch's `state_on`/`state_off` default to the payloads; `payload_press` `"PRESS"`; `retain` false; `qos` 0.

- [ ] **Step 1: Write the test helper**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/TestBindings.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun bindings(component: String, json: String) = MqttBindings(component, Json.parseToJsonElement(json).jsonObject)

fun testEntity(id: String, kind: Kind, component: String, json: String) = Entity(
    uniqueId = id, deviceId = null, name = id, kind = kind, deviceClass = null, unit = null, icon = null,
    capabilities = Capabilities(), bindings = bindings(component, json),
)
```

- [ ] **Step 2: Write the failing mapper tests**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/SwitchMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.OnOff
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SwitchMapperTest {
    private val plain = bindings("switch", """{"state_topic":"s","command_topic":"c"}""")

    @Test
    fun `default payloads decode`() {
        assertEquals(Decoded.State(OnOff(true)), SwitchMapper.decode(plain, "s", "ON", Unknown))
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(plain, "s", "OFF", Unknown))
        assertEquals(Decoded.Ignored, SwitchMapper.decode(plain, "s", "MAYBE", Unknown))
    }

    @Test
    fun `value_template and state_on override the payloads`() {
        val b = bindings(
            "switch",
            """{"state_topic":"s","command_topic":"c","value_template":"{{ value_json.state }}","payload_on":"1","payload_off":"0","state_on":"on","state_off":"off"}""",
        )
        assertEquals(Decoded.State(OnOff(true)), SwitchMapper.decode(b, "s", """{"state":"on"}""", Unknown))
        assertEquals(Decoded.State(OnOff(false)), SwitchMapper.decode(b, "s", """{"state":"off"}""", Unknown))
    }

    @Test
    fun `commands use payloads, retain and qos`() {
        val b = bindings("switch", """{"command_topic":"c","payload_on":"1","payload_off":"0","retain":true,"qos":1}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "1", true, 1))), SwitchMapper.encode(b, Command.TurnOn(), Unknown))
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "0", true, 1))), SwitchMapper.encode(b, Command.TurnOff, Unknown))
    }

    @Test
    fun `toggle flips the previous state`() {
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "OFF", false, 0))), SwitchMapper.encode(plain, Command.Toggle, OnOff(true)))
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "ON", false, 0))), SwitchMapper.encode(plain, Command.Toggle, Unknown))
    }

    @Test
    fun `command_template renders value`() {
        val b = bindings("switch", """{"command_topic":"c","command_template":"{\"state\": \"{{ value }}\"}"}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", """{"state": "ON"}""", false, 0))), SwitchMapper.encode(b, Command.TurnOn(), Unknown))
    }

    @Test
    fun `unsupported command is rejected`() {
        assertIs<Encoded.NotSupported>(SwitchMapper.encode(plain, Command.SetNumber(1.0), Unknown))
    }

    @Test
    fun `predict and state topics`() {
        assertEquals(OnOff(true), SwitchMapper.predict(Command.TurnOn(), Unknown))
        assertEquals(OnOff(false), SwitchMapper.predict(Command.Toggle, OnOff(true)))
        assertEquals(setOf("s"), SwitchMapper.stateTopics(plain))
        assertEquals(emptySet(), SwitchMapper.stateTopics(bindings("switch", """{"command_topic":"c"}""")))
    }
}
```

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/BinarySensorMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.OnOff
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BinarySensorMapperTest {
    @Test
    fun `default payloads`() {
        val b = bindings("binary_sensor", """{"state_topic":"s"}""")
        assertEquals(Decoded.State(OnOff(true)), BinarySensorMapper.decode(b, "s", "ON", Unknown))
        assertEquals(Decoded.State(OnOff(false)), BinarySensorMapper.decode(b, "s", "OFF", Unknown))
    }

    // Review Focus 1: z2m publishes JSON booleans for payload_on/off and the template renders a boolean.
    @Test
    fun `z2m contact sensor with boolean payloads`() {
        val b = bindings(
            "binary_sensor",
            """{"state_topic":"z","value_template":"{{ value_json.contact }}","payload_on":false,"payload_off":true,"device_class":"door"}""",
        )
        assertEquals(Decoded.State(OnOff(true)), BinarySensorMapper.decode(b, "z", """{"contact":false,"battery":90}""", Unknown))
        assertEquals(Decoded.State(OnOff(false)), BinarySensorMapper.decode(b, "z", """{"contact":true}""", Unknown))
    }

    @Test
    fun `z2m motion sensor`() {
        val b = bindings("binary_sensor", """{"state_topic":"z","value_template":"{{ value_json.occupancy }}","payload_on":true,"payload_off":false}""")
        assertEquals(Decoded.State(OnOff(true)), BinarySensorMapper.decode(b, "z", """{"occupancy":true}""", Unknown))
    }

    @Test
    fun `read-only`() {
        val b = bindings("binary_sensor", """{"state_topic":"s"}""")
        assertIs<Encoded.NotSupported>(BinarySensorMapper.encode(b, Command.TurnOn(), Unknown))
    }
}
```

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/SensorMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SensorMapperTest {
    private val temp = bindings("sensor", """{"state_topic":"z","value_template":"{{ value_json.temperature }}"}""")
    private val raw = bindings("sensor", """{"state_topic":"s"}""")

    @Test
    fun `numeric values`() {
        assertEquals(Decoded.State(StateValue.Numeric(21.5)), SensorMapper.decode(temp, "z", """{"temperature":21.5}""", Unknown))
        assertEquals(Decoded.State(StateValue.Numeric(1234.0)), SensorMapper.decode(raw, "s", "1234", Unknown))
    }

    @Test
    fun `text values`() {
        assertEquals(Decoded.State(StateValue.Text("idle")), SensorMapper.decode(raw, "s", "idle", Unknown))
    }

    @Test
    fun `missing, blank and unknown become Unknown`() {
        assertEquals(Decoded.State(Unknown), SensorMapper.decode(temp, "z", """{"humidity":40}""", Unknown))
        assertEquals(Decoded.State(Unknown), SensorMapper.decode(raw, "s", "", Unknown))
        assertEquals(Decoded.State(Unknown), SensorMapper.decode(raw, "s", "unavailable", Unknown))
    }

    @Test
    fun `errors and unsupported templates are surfaced`() {
        assertIs<Decoded.Error>(SensorMapper.decode(temp, "z", "online", Unknown))
        val weird = bindings("sensor", """{"state_topic":"s","value_template":"{{ value | tojson }}"}""")
        assertIs<Decoded.Unsupported>(SensorMapper.decode(weird, "s", "1", Unknown))
    }
}
```

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/ButtonMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ButtonMapperTest {
    @Test
    fun `press publishes the press payload`() {
        val b = bindings("button", """{"command_topic":"c"}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "PRESS", false, 0))), ButtonMapper.encode(b, Command.Press, Unknown))
        val custom = bindings("button", """{"command_topic":"c","payload_press":"go"}""")
        assertEquals(Encoded.Publish(listOf(Outgoing("c", "go", false, 0))), ButtonMapper.encode(custom, Command.Press, Unknown))
    }

    @Test
    fun `no state and no prediction`() {
        val b = bindings("button", """{"command_topic":"c"}""")
        assertEquals(emptySet(), ButtonMapper.stateTopics(b))
        assertNull(ButtonMapper.predict(Command.Press, Unknown))
        assertIs<Encoded.NotSupported>(ButtonMapper.encode(b, Command.TurnOn(), Unknown))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:state:test`
Expected: FAIL, with compilation errors (`Unresolved reference: MqttBindings`, `SwitchMapper`…).

- [ ] **Step 4: Implement the bindings and contract**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/mqtt/MqttBindings.kt`:

```kotlin
package com.gekkotron.oikos.state.mqtt

import com.gekkotron.oikos.model.Bindings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The fully expanded HA discovery config of one entity (full key names, `~` resolved). */
data class MqttBindings(val component: String, val config: JsonObject) : Bindings {
    /** String option. JSON booleans become "True"/"False", like HA's cv.string. */
    fun str(key: String): String? {
        val p = config[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content
        return p.booleanOrNull?.let { if (it) "True" else "False" } ?: p.content
    }

    fun str(key: String, default: String): String = str(key) ?: default

    fun double(key: String): Double? =
        (config[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()

    fun int(key: String, default: Int): Int = double(key)?.toInt() ?: default

    fun bool(key: String, default: Boolean): Boolean = (config[key] as? JsonPrimitive)?.booleanOrNull ?: default

    fun strList(key: String): List<String>? =
        (config[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

sealed interface Decoded {
    data class State(val value: StateValue) : Decoded
    data object Ignored : Decoded
    data class Unsupported(val reason: String) : Decoded
    data class Error(val reason: String) : Decoded
}

data class Outgoing(val topic: String, val payload: String, val retain: Boolean, val qos: Int)

sealed interface Encoded {
    data class Publish(val messages: List<Outgoing>) : Encoded
    data class NotSupported(val reason: String) : Encoded
}

/** HA payload semantics for one component (spec §4.4). One object per kind, in kinds/. */
interface KindMapper {
    /** Topics whose messages feed [decode]. Empty = no state feedback (optimistic entity). */
    fun stateTopics(b: MqttBindings): Set<String>

    /** [previous] is the last confirmed value; mappers merge partial updates onto it. */
    fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded

    fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded =
        Encoded.NotSupported("read-only entity")

    /** Optimistic value shown while waiting for confirmation; null = nothing to predict. */
    fun predict(command: Command, previous: StateValue): StateValue? = null
}

object KindMappers {
    fun forKind(kind: Kind): KindMapper = when (kind) {
        Kind.SWITCH -> SwitchMapper
        Kind.BINARY_SENSOR -> BinarySensorMapper
        Kind.SENSOR -> SensorMapper
        Kind.BUTTON -> ButtonMapper
        else -> UnmappedKind // replaced by real mappers in Tasks 6–8
    }
}

private object UnmappedKind : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = emptySet()
    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded = Decoded.Ignored
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/Common.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.TemplateResult
import com.gekkotron.oikos.state.template.Templates
import com.gekkotron.oikos.state.template.asPayloadString

/** Renders [templateKey] over [payload], or passes the raw payload through when no template is set. */
internal fun renderIncoming(b: MqttBindings, templateKey: String, payload: String): TemplateResult {
    val template = b.str(templateKey) ?: return TemplateResult.Value(payload)
    return Templates.renderValue(template, payload)
}

internal inline fun TemplateResult.decodeWith(block: (Any?) -> Decoded): Decoded = when (this) {
    is TemplateResult.Value -> block(value)
    is TemplateResult.Unsupported -> Decoded.Unsupported(reason)
    is TemplateResult.Error -> Decoded.Error(reason)
}

/** Builds one publish to [topicKey], rendering [templateKey] (with `value` = [value] + [extraVars]) when set. */
internal fun publish(
    b: MqttBindings,
    topicKey: String,
    templateKey: String?,
    value: String,
    extraVars: Map<String, Any?> = emptyMap(),
): Encoded {
    val topic = b.str(topicKey) ?: return Encoded.NotSupported("no $topicKey configured")
    val template = templateKey?.let { b.str(it) }
    val payload = if (template == null) {
        value
    } else {
        when (val r = Templates.render(template, mapOf("value" to value) + extraVars)) {
            is TemplateResult.Value -> r.value.asPayloadString() ?: ""
            is TemplateResult.Unsupported -> return Encoded.NotSupported("$templateKey unsupported: ${r.reason}")
            is TemplateResult.Error -> return Encoded.NotSupported("$templateKey failed: ${r.reason}")
        }
    }
    return Encoded.Publish(listOf(outgoing(b, topic, payload)))
}

internal fun outgoing(b: MqttBindings, topic: String, payload: String) =
    Outgoing(topic, payload, retain = b.bool("retain", false), qos = b.int("qos", 0))

/** On/off target of a TurnOn/TurnOff/Toggle command, or null for other commands. */
internal fun targetOn(command: Command, previous: StateValue): Boolean? = when (command) {
    is Command.TurnOn -> true
    Command.TurnOff -> false
    Command.Toggle -> !previous.isOn()
    else -> null
}

internal fun StateValue.isOn(): Boolean = when (this) {
    is StateValue.OnOff -> on
    is StateValue.Light -> on
    else -> false
}

internal fun onOffFrom(rendered: String, on: String, off: String): Decoded = when (rendered) {
    on -> Decoded.State(StateValue.OnOff(true))
    off -> Decoded.State(StateValue.OnOff(false))
    else -> Decoded.Ignored
}

/**
 * Accumulates several templated parts of one message (e.g. cover state + position on one topic).
 * Any successful part wins; otherwise the first problem is reported.
 */
internal class PartialDecode {
    private var changed = false
    private var problem: Decoded? = null

    fun part(result: TemplateResult, apply: (Any?) -> Boolean) {
        when (result) {
            is TemplateResult.Value -> if (apply(result.value)) changed = true
            is TemplateResult.Unsupported -> problem = problem ?: Decoded.Unsupported(result.reason)
            is TemplateResult.Error -> problem = problem ?: Decoded.Error(result.reason)
        }
    }

    fun result(value: StateValue): Decoded = if (changed) Decoded.State(value) else problem ?: Decoded.Ignored
}
```

- [ ] **Step 5: Implement the four mappers**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/SwitchMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object SwitchMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            val s = v.asPayloadString() ?: return@decodeWith Decoded.Ignored
            onOffFrom(
                s,
                on = b.str("state_on") ?: b.str("payload_on", "ON"),
                off = b.str("state_off") ?: b.str("payload_off", "OFF"),
            )
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        val on = targetOn(command, previous) ?: return Encoded.NotSupported("switch cannot $command")
        return publish(b, "command_topic", "command_template", if (on) b.str("payload_on", "ON") else b.str("payload_off", "OFF"))
    }

    override fun predict(command: Command, previous: StateValue): StateValue? =
        targetOn(command, previous)?.let { StateValue.OnOff(it) }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/BinarySensorMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object BinarySensorMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            val s = v.asPayloadString() ?: return@decodeWith Decoded.Ignored
            onOffFrom(s, on = b.str("payload_on", "ON"), off = b.str("payload_off", "OFF"))
        }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/SensorMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object SensorMapper : KindMapper {
    private val UNKNOWN_WORDS = setOf("unknown", "unavailable", "None")

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            val value = when (v) {
                null -> StateValue.Unknown
                is Number -> StateValue.Numeric(v.toDouble())
                else -> {
                    val s = v.asPayloadString().orEmpty().trim()
                    when {
                        s.isEmpty() || s in UNKNOWN_WORDS -> StateValue.Unknown
                        else -> s.toDoubleOrNull()?.let { StateValue.Numeric(it) } ?: StateValue.Text(s)
                    }
                }
            }
            Decoded.State(value)
        }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/ButtonMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings

object ButtonMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = emptySet()

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded = Decoded.Ignored

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded =
        if (command == Command.Press) publish(b, "command_topic", "command_template", b.str("payload_press", "PRESS"))
        else Encoded.NotSupported("button can only be pressed")
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (all Task 4 tests + 17 new).

- [ ] **Step 7: Commit**

```bash
git add core/state/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): MQTT bindings, kind mapper contract, switch/binary_sensor/sensor/button" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 6: Light mapper (default and JSON schemas)

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/LightMapper.kt`
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt` (register)
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/LightMapperTest.kt`

**Interfaces:**
- Consumes: `KindMapper`, `Decoded`, `Encoded`, `Outgoing`, `renderIncoming`, `outgoing`, `targetOn`, `isOn`, `PartialDecode` (Task 5); `asPayloadString`, `asDouble` (Task 4).
- Produces: `object LightMapper : KindMapper`. Brightness in `StateValue.Light` and `Command.TurnOn` is always 0..255; the mapper converts to and from `brightness_scale` (default 255; zigbee2mqtt uses 254).

HA semantics:
- **Default schema** (`schema` absent, `default` or `basic`):
  - `state_topic` + `state_value_template` compared with `payload_on`/`payload_off`.
  - `brightness_state_topic` + `brightness_value_template`.
  - `command_topic` receives the on/off payload; `brightness_command_topic` receives the scaled number; `rgb_command_topic` receives `r,g,b`.
  - With `on_command_type: brightness`, a brightness-only turn-on skips the on payload.
- **JSON schema** (`schema: json`, zigbee2mqtt's lights): the state topic carries `{"state":"ON","brightness":n}` and the command topic takes `{"state":"ON","brightness":n,"color":{"r","g","b"}}`.

- [ ] **Step 1: Write the failing tests**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/LightMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Rgb
import com.gekkotron.oikos.model.StateValue.Light
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LightMapperTest {
    private val z2m = bindings(
        "light",
        """{"schema":"json","state_topic":"z/lampe","command_topic":"z/lampe/set","brightness":true,"brightness_scale":254}""",
    )
    private val basic = bindings(
        "light",
        """{"state_topic":"l/state","command_topic":"l/set","brightness_state_topic":"l/bri","brightness_command_topic":"l/bri/set"}""",
    )

    private fun publishes(vararg pairs: Pair<String, String>) =
        Encoded.Publish(pairs.map { Outgoing(it.first, it.second, false, 0) })

    @Test
    fun `json schema decodes state and scales brightness`() {
        assertEquals(
            Decoded.State(Light(true, 128)),
            LightMapper.decode(z2m, "z/lampe", """{"state":"ON","brightness":127,"linkquality":80}""", Unknown),
        )
    }

    @Test
    fun `json schema OFF keeps the previous brightness`() {
        assertEquals(Decoded.State(Light(false, 200)), LightMapper.decode(z2m, "z/lampe", """{"state":"OFF"}""", Light(true, 200)))
    }

    @Test
    fun `json schema ignores payloads without state or brightness and rejects non-JSON`() {
        assertEquals(Decoded.Ignored, LightMapper.decode(z2m, "z/lampe", """{"linkquality":80}""", Unknown))
        assertIs<Decoded.Error>(LightMapper.decode(z2m, "z/lampe", "ON", Unknown))
    }

    @Test
    fun `json schema commands`() {
        assertEquals(publishes("z/lampe/set" to """{"state":"ON","brightness":254}"""), LightMapper.encode(z2m, Command.TurnOn(brightness = 255), Unknown))
        assertEquals(
            publishes("z/lampe/set" to """{"state":"ON","color":{"r":255,"g":0,"b":10}}"""),
            LightMapper.encode(z2m, Command.TurnOn(color = Rgb(255, 0, 10)), Unknown),
        )
        assertEquals(publishes("z/lampe/set" to """{"state":"OFF"}"""), LightMapper.encode(z2m, Command.TurnOff, Light(true, 10)))
    }

    @Test
    fun `default schema decodes state and brightness topics separately`() {
        assertEquals(Decoded.State(Light(true, null)), LightMapper.decode(basic, "l/state", "ON", Unknown))
        assertEquals(Decoded.State(Light(true, 128)), LightMapper.decode(basic, "l/bri", "128", Light(true, null)))
        assertEquals(Decoded.Ignored, LightMapper.decode(basic, "l/state", "DIM", Unknown))
    }

    @Test
    fun `default schema turn on with brightness sends both`() {
        assertEquals(publishes("l/set" to "ON", "l/bri/set" to "128"), LightMapper.encode(basic, Command.TurnOn(brightness = 128), Unknown))
    }

    @Test
    fun `on_command_type brightness sends only brightness`() {
        val b = bindings("light", """{"command_topic":"l/set","brightness_command_topic":"l/bri/set","on_command_type":"brightness"}""")
        assertEquals(publishes("l/bri/set" to "128"), LightMapper.encode(b, Command.TurnOn(brightness = 128), Unknown))
        assertEquals(publishes("l/set" to "ON"), LightMapper.encode(b, Command.TurnOn(), Unknown))
    }

    @Test
    fun `default schema rgb and toggle`() {
        val b = bindings("light", """{"command_topic":"l/set","rgb_command_topic":"l/rgb/set"}""")
        assertEquals(publishes("l/set" to "ON", "l/rgb/set" to "1,2,3"), LightMapper.encode(b, Command.TurnOn(color = Rgb(1, 2, 3)), Unknown))
        assertEquals(publishes("l/set" to "OFF"), LightMapper.encode(b, Command.Toggle, Light(true, null)))
    }

    @Test
    fun `state topics and prediction`() {
        assertEquals(setOf("l/state", "l/bri"), LightMapper.stateTopics(basic))
        assertEquals(setOf("z/lampe"), LightMapper.stateTopics(z2m))
        assertEquals(Light(true, 100), LightMapper.predict(Command.TurnOn(brightness = 100), Unknown))
        assertEquals(Light(false, 100), LightMapper.predict(Command.Toggle, Light(true, 100)))
        assertIs<Encoded.NotSupported>(LightMapper.encode(basic, Command.SetPosition(1), Unknown))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:state:test --tests '*LightMapperTest*'`
Expected: FAIL, with a compilation error (`Unresolved reference: LightMapper`).

- [ ] **Step 3: Implement**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/LightMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.asPayloadString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

object LightMapper : KindMapper {
    private fun isJson(b: MqttBindings) = b.str("schema") == "json"

    private fun scale(b: MqttBindings): Double = b.double("brightness_scale")?.takeIf { it > 0 } ?: 255.0

    private fun toByte(raw: Double, scale: Double): Int = (raw / scale * 255).roundToInt().coerceIn(0, 255)

    private fun fromByte(value: Int, scale: Double): Int = (value / 255.0 * scale).roundToInt()

    private fun StateValue.asLight(): StateValue.Light = this as? StateValue.Light ?: StateValue.Light(on = isOn())

    override fun stateTopics(b: MqttBindings): Set<String> =
        if (isJson(b)) setOfNotNull(b.str("state_topic"))
        else setOfNotNull(b.str("state_topic"), b.str("brightness_state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        if (isJson(b)) decodeJson(b, payload, previous) else decodeDefault(b, topic, payload, previous)

    private fun decodeJson(b: MqttBindings, payload: String, previous: StateValue): Decoded {
        val obj = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return Decoded.Error("JSON light payload is not an object")
        val prev = previous.asLight()
        val stateWord = (obj["state"] as? JsonPrimitive)?.contentOrNull
        val rawBrightness = (obj["brightness"] as? JsonPrimitive)?.doubleOrNull
        if (stateWord == null && rawBrightness == null) return Decoded.Ignored
        val on = when (stateWord) {
            "ON" -> true
            "OFF" -> false
            else -> prev.on
        }
        val brightness = rawBrightness?.let { toByte(it, scale(b)) } ?: prev.brightness
        return Decoded.State(StateValue.Light(on, brightness))
    }

    private fun decodeDefault(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        var light = previous.asLight()
        val parts = PartialDecode()
        if (topic == b.str("state_topic")) {
            parts.part(renderIncoming(b, "state_value_template", payload)) { v ->
                when (v.asPayloadString()) {
                    b.str("payload_on", "ON") -> { light = light.copy(on = true); true }
                    b.str("payload_off", "OFF") -> { light = light.copy(on = false); true }
                    else -> false
                }
            }
        }
        if (topic == b.str("brightness_state_topic")) {
            parts.part(renderIncoming(b, "brightness_value_template", payload)) { v ->
                val raw = v.asDouble() ?: return@part false
                light = light.copy(brightness = toByte(raw, scale(b)))
                true
            }
        }
        return parts.result(light)
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        val on = targetOn(command, previous) ?: return Encoded.NotSupported("light cannot $command")
        val topic = b.str("command_topic") ?: return Encoded.NotSupported("no command_topic configured")
        val turnOn = (command as? Command.TurnOn)?.takeIf { on }
        if (isJson(b)) {
            val body = buildJsonObject {
                put("state", if (on) "ON" else "OFF")
                turnOn?.brightness?.let { put("brightness", fromByte(it, scale(b))) }
                turnOn?.color?.let { c ->
                    put("color", buildJsonObject { put("r", c.r); put("g", c.g); put("b", c.b) })
                }
            }
            return Encoded.Publish(listOf(outgoing(b, topic, body.toString())))
        }
        val messages = mutableListOf<Outgoing>()
        val brightness = turnOn?.brightness
        val brightnessTopic = b.str("brightness_command_topic")
        val brightnessOnly = brightness != null && brightnessTopic != null && b.str("on_command_type") == "brightness"
        if (!brightnessOnly) messages += outgoing(b, topic, if (on) b.str("payload_on", "ON") else b.str("payload_off", "OFF"))
        if (brightness != null && brightnessTopic != null) messages += outgoing(b, brightnessTopic, fromByte(brightness, scale(b)).toString())
        turnOn?.color?.let { c -> b.str("rgb_command_topic")?.let { messages += outgoing(b, it, "${c.r},${c.g},${c.b}") } }
        return Encoded.Publish(messages)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val on = targetOn(command, previous) ?: return null
        val prev = previous.asLight()
        return StateValue.Light(on, (command as? Command.TurnOn)?.brightness ?: prev.brightness)
    }
}
```

In `KindMapper.kt`, add a line to `KindMappers.forKind` just above the `else` branch:

```kotlin
        Kind.LIGHT -> LightMapper
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (all tests, including 9 new ones in `LightMapperTest`).

- [ ] **Step 5: Commit**

```bash
git add core/state/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): light mapper for default and JSON schemas" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Cover and climate mappers

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/CoverMapper.kt`, `ClimateMapper.kt`
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt` (register both)
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/CoverMapperTest.kt`, `ClimateMapperTest.kt`

**Interfaces:**
- Consumes: the Task 5 helpers, `formatNumber` (Task 4).
- Produces:
  - `object CoverMapper : KindMapper`. Position is normalised to 0..100 using `position_open` (default 100) and `position_closed` (default 0).
  - `object ClimateMapper : KindMapper` with `val DEFAULT_MODES: List<String>` (`auto, off, cool, heat, dry, fan_only`), `const val DEFAULT_MIN_TEMP = 7.0`, `const val DEFAULT_MAX_TEMP = 35.0`, `const val DEFAULT_TEMP_STEP = 1.0`, and `fun modes(b: MqttBindings): List<String>`. Task 11 reads these to fill `Capabilities`.

HA semantics:
- **Cover:**
  - `state_topic` + `value_template` is matched against `state_open` (`open`), `state_closed` (`closed`), `state_opening`, `state_closing` and `state_stopped`.
  - `position_topic` + `position_template` sets the position.
  - `command_topic` receives `payload_open` (`OPEN`), `payload_close` (`CLOSE`) or `payload_stop` (`STOP`).
  - `set_position_topic` + `set_position_template` has the variable `position`.
  - When the state is unknown, it is derived from the position.
- **Climate:**
  - `current_temperature_topic`/`_template`, `temperature_state_topic`/`_template` and `mode_state_topic`/`_template`; several may share one topic.
  - `temperature_command_topic`/`_template` and `mode_command_topic`/`_template`.
  - `min_temp`/`max_temp` guard setpoints, and `modes` guards mode changes.

- [ ] **Step 1: Write the failing tests**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/CoverMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.StateValue.Cover
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverMapperTest {
    private val z2m = bindings(
        "cover",
        """{"command_topic":"z/set","state_topic":"z","value_template":"{{ value_json.state }}","state_open":"OPEN","state_closed":"CLOSE",
           "position_topic":"z","position_template":"{{ value_json.position }}",
           "set_position_topic":"z/set","set_position_template":"{ \"position\": {{ position }} }"}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `state and position on one topic`() {
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 42)), CoverMapper.decode(z2m, "z", """{"position":42,"state":"OPEN"}""", Unknown))
    }

    @Test
    fun `statement template on state still tracks position`() {
        // zigbee2mqtt's real cover value_template uses {% if %}, which is outside the subset.
        val b = bindings(
            "cover",
            """{"state_topic":"z","value_template":"{% if value_json.moving %}{{ value_json.moving }}{% else %}STOP{% endif %}",
               "position_topic":"z","position_template":"{{ value_json.position }}"}""",
        )
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 60)), CoverMapper.decode(b, "z", """{"position":60}""", Unknown))
    }

    @Test
    fun `position without state derives open or closed`() {
        val b = bindings("cover", """{"position_topic":"p"}""")
        assertEquals(Decoded.State(Cover(CoverState.CLOSED, 0)), CoverMapper.decode(b, "p", "0", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 60)), CoverMapper.decode(b, "p", "60", Unknown))
    }

    @Test
    fun `inverted position bounds`() {
        val b = bindings("cover", """{"position_topic":"p","set_position_topic":"p/set","position_open":0,"position_closed":100}""")
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 75)), CoverMapper.decode(b, "p", "25", Unknown))
        assertEquals(publishes("p/set", "25"), CoverMapper.encode(b, Command.SetPosition(75), Unknown))
    }

    @Test
    fun `unrecognised state is ignored`() {
        val b = bindings("cover", """{"state_topic":"s"}""")
        assertEquals(Decoded.Ignored, CoverMapper.decode(b, "s", "wobbling", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.CLOSING)), CoverMapper.decode(b, "s", "closing", Unknown))
    }

    @Test
    fun `commands`() {
        assertEquals(publishes("z/set", "OPEN"), CoverMapper.encode(z2m, Command.Open, Unknown))
        assertEquals(publishes("z/set", "CLOSE"), CoverMapper.encode(z2m, Command.Close, Unknown))
        assertEquals(publishes("z/set", "STOP"), CoverMapper.encode(z2m, Command.Stop, Unknown))
        assertEquals(publishes("z/set", """{ "position": 30 }"""), CoverMapper.encode(z2m, Command.SetPosition(30), Unknown))
        assertEquals(publishes("z/set", "OPEN"), CoverMapper.encode(z2m, Command.Toggle, Cover(CoverState.CLOSED, 0)))
        assertEquals(publishes("z/set", "CLOSE"), CoverMapper.encode(z2m, Command.Toggle, Cover(CoverState.OPEN, 100)))
    }

    @Test
    fun `prediction`() {
        assertEquals(Cover(CoverState.OPENING, 0), CoverMapper.predict(Command.Open, Cover(CoverState.CLOSED, 0)))
        assertEquals(Cover(CoverState.OPENING, 70), CoverMapper.predict(Command.SetPosition(70), Cover(CoverState.OPEN, 20)))
        assertEquals(setOf("z"), CoverMapper.stateTopics(z2m))
    }
}
```

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/ClimateMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Climate
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClimateMapperTest {
    // The Jeedom Daikin example from spec §3.B: current and target share one topic.
    private val daikin = bindings(
        "climate",
        """{"current_temperature_topic":"jeedom/data/rooms/living/climatisation",
           "current_temperature_template":"{{ value_json['température_int.'] }}",
           "temperature_state_topic":"jeedom/data/rooms/living/climatisation",
           "temperature_state_template":"{{ value_json.consigne }}",
           "temperature_command_topic":"jeedom/data/rooms/living/climatisation/set",
           "mode_state_topic":"m","mode_command_topic":"m/set",
           "modes":["off","cool","heat","auto","fan_only"]}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `shared topic fills current and target`() {
        assertEquals(
            Decoded.State(Climate(null, 21.0, 22.5)),
            ClimateMapper.decode(daikin, "jeedom/data/rooms/living/climatisation", """{"température_int.":22.5,"consigne":21}""", Unknown),
        )
    }

    @Test
    fun `mode must be one of the modes`() {
        assertEquals(Decoded.State(Climate("heat", 21.0, null)), ClimateMapper.decode(daikin, "m", "heat", Climate(null, 21.0, null)))
        assertEquals(Decoded.Ignored, ClimateMapper.decode(daikin, "m", "turbo", Unknown))
    }

    @Test
    fun `setpoint commands are formatted and range-checked`() {
        assertEquals(publishes("jeedom/data/rooms/living/climatisation/set", "20.5"), ClimateMapper.encode(daikin, Command.SetTemperature(20.5), Unknown))
        assertEquals(publishes("jeedom/data/rooms/living/climatisation/set", "21"), ClimateMapper.encode(daikin, Command.SetTemperature(21.0), Unknown))
        assertIs<Encoded.NotSupported>(ClimateMapper.encode(daikin, Command.SetTemperature(40.0), Unknown))
    }

    @Test
    fun `mode commands`() {
        assertEquals(publishes("m/set", "cool"), ClimateMapper.encode(daikin, Command.SetHvacMode("cool"), Unknown))
        assertEquals(publishes("m/set", "off"), ClimateMapper.encode(daikin, Command.TurnOff, Unknown))
        assertIs<Encoded.NotSupported>(ClimateMapper.encode(daikin, Command.SetHvacMode("dry"), Unknown))
        val noModeTopic = bindings("climate", """{"temperature_command_topic":"t"}""")
        assertIs<Encoded.NotSupported>(ClimateMapper.encode(noModeTopic, Command.SetHvacMode("heat"), Unknown))
    }

    @Test
    fun `prediction and topics`() {
        assertEquals(Climate("off", 21.0, 22.5), ClimateMapper.predict(Command.SetHvacMode("off"), Climate("cool", 21.0, 22.5)))
        assertEquals(Climate(null, 19.0, null), ClimateMapper.predict(Command.SetTemperature(19.0), Unknown))
        assertEquals(setOf("jeedom/data/rooms/living/climatisation", "m"), ClimateMapper.stateTopics(daikin))
        assertEquals(ClimateMapper.DEFAULT_MODES, ClimateMapper.modes(bindings("climate", "{}")))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:state:test --tests '*CoverMapperTest*' --tests '*ClimateMapperTest*'`
Expected: FAIL, with compilation errors (`Unresolved reference: CoverMapper`, `ClimateMapper`).

- [ ] **Step 3: Implement**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/CoverMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.asPayloadString
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object CoverMapper : KindMapper {
    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"), b.str("position_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        var cover = previous as? StateValue.Cover ?: StateValue.Cover(CoverState.UNKNOWN)
        val parts = PartialDecode()
        if (topic == b.str("state_topic")) {
            parts.part(renderIncoming(b, "value_template", payload)) { v ->
                val state = stateFor(b, v.asPayloadString()) ?: return@part false
                cover = cover.copy(state = state)
                true
            }
        }
        if (topic == b.str("position_topic")) {
            parts.part(renderIncoming(b, "position_template", payload)) { v ->
                val pct = v.asDouble()?.let { normalise(b, it) } ?: return@part false
                val derive = b.str("state_topic") == null || cover.state == CoverState.UNKNOWN
                cover = cover.copy(
                    position = pct,
                    state = if (derive) (if (pct == 0) CoverState.CLOSED else CoverState.OPEN) else cover.state,
                )
                true
            }
        }
        return parts.result(cover)
    }

    private fun stateFor(b: MqttBindings, s: String?): CoverState? = when (s) {
        null -> null
        b.str("state_open", "open") -> CoverState.OPEN
        b.str("state_closed", "closed") -> CoverState.CLOSED
        b.str("state_opening", "opening") -> CoverState.OPENING
        b.str("state_closing", "closing") -> CoverState.CLOSING
        b.str("state_stopped", "stopped") -> CoverState.STOPPED
        else -> null
    }

    private fun bounds(b: MqttBindings) = (b.double("position_open") ?: 100.0) to (b.double("position_closed") ?: 0.0)

    private fun normalise(b: MqttBindings, raw: Double): Int? {
        val (open, closed) = bounds(b)
        if (open == closed) return null
        return ((raw - closed) / (open - closed) * 100).roundToInt().coerceIn(0, 100)
    }

    private fun denormalise(b: MqttBindings, pct: Int): Long {
        val (open, closed) = bounds(b)
        return (closed + pct * (open - closed) / 100.0).roundToLong()
    }

    private fun StateValue.isClosedLike() =
        this is StateValue.Cover && (state == CoverState.CLOSED || state == CoverState.CLOSING)

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        Command.Open -> publish(b, "command_topic", null, b.str("payload_open", "OPEN"))
        Command.Close -> publish(b, "command_topic", null, b.str("payload_close", "CLOSE"))
        Command.Stop -> publish(b, "command_topic", null, b.str("payload_stop", "STOP"))
        Command.Toggle -> encode(b, if (previous.isClosedLike()) Command.Open else Command.Close, previous)
        is Command.SetPosition -> {
            val raw = denormalise(b, command.position)
            publish(b, "set_position_topic", "set_position_template", raw.toString(), mapOf("position" to raw))
        }
        else -> Encoded.NotSupported("cover cannot $command")
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Cover ?: StateValue.Cover(CoverState.UNKNOWN)
        return when (command) {
            Command.Open -> prev.copy(state = CoverState.OPENING)
            Command.Close -> prev.copy(state = CoverState.CLOSING)
            Command.Stop -> prev.copy(state = CoverState.STOPPED)
            Command.Toggle -> prev.copy(state = if (previous.isClosedLike()) CoverState.OPENING else CoverState.CLOSING)
            is Command.SetPosition -> StateValue.Cover(
                if (command.position >= (prev.position ?: 0)) CoverState.OPENING else CoverState.CLOSING,
                command.position,
            )
            else -> null
        }
    }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/ClimateMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.asPayloadString
import com.gekkotron.oikos.state.template.formatNumber

object ClimateMapper : KindMapper {
    val DEFAULT_MODES = listOf("auto", "off", "cool", "heat", "dry", "fan_only")
    const val DEFAULT_MIN_TEMP = 7.0
    const val DEFAULT_MAX_TEMP = 35.0
    const val DEFAULT_TEMP_STEP = 1.0

    fun modes(b: MqttBindings): List<String> = b.strList("modes") ?: DEFAULT_MODES

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(
        b.str("current_temperature_topic"),
        b.str("temperature_state_topic"),
        b.str("mode_state_topic"),
    )

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
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
                val mode = v.asPayloadString()?.takeIf { it in modes(b) } ?: return@part false
                climate = climate.copy(mode = mode)
                true
            }
        }
        return parts.result(climate)
    }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        is Command.SetTemperature -> {
            val min = b.double("min_temp") ?: DEFAULT_MIN_TEMP
            val max = b.double("max_temp") ?: DEFAULT_MAX_TEMP
            if (command.temperature !in min..max) {
                Encoded.NotSupported("temperature ${command.temperature} outside $min..$max")
            } else {
                publish(b, "temperature_command_topic", "temperature_command_template", formatNumber(command.temperature))
            }
        }
        is Command.SetHvacMode ->
            if (command.mode !in modes(b)) Encoded.NotSupported("mode '${command.mode}' not supported")
            else publish(b, "mode_command_topic", "mode_command_template", command.mode)
        Command.TurnOff -> encode(b, Command.SetHvacMode("off"), previous)
        else -> Encoded.NotSupported("climate cannot $command")
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
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

In `KindMapper.kt`, add these lines to `KindMappers.forKind` just above the `else` branch:

```kotlin
        Kind.COVER -> CoverMapper
        Kind.CLIMATE -> ClimateMapper
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (all tests, including 7 cover and 5 climate).

- [ ] **Step 5: Commit**

```bash
git add core/state/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): cover and climate mappers" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Alarm panel, number and select mappers (the registry becomes exhaustive)

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/AlarmPanelMapper.kt`, `NumberMapper.kt`, `SelectMapper.kt`
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/KindMapper.kt` (exhaustive registry; delete `UnmappedKind`)
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/AlarmPanelMapperTest.kt`, `NumberSelectMapperTest.kt`, `KindMappersTest.kt`

**Interfaces:**
- Consumes: the Task 5 helpers.
- Produces:
  - `object AlarmPanelMapper : KindMapper` with `fun supportedModes(b: MqttBindings): Set<AlarmMode>` and `fun codeRequired(b: MqttBindings, arming: Boolean): Boolean`
  - `object NumberMapper : KindMapper` with `const val DEFAULT_MIN = 1.0`, `const val DEFAULT_MAX = 100.0`, `const val DEFAULT_STEP = 1.0`
  - `object SelectMapper : KindMapper`
  - `KindMappers.forKind` covers every `Kind` with no `else` branch.

HA semantics:
- **Alarm panel:**
  - States are `disarmed`, `armed_home`, `armed_away`, `armed_night`, `armed_vacation`, `armed_custom_bypass`, `pending`, `arming`, `disarming` and `triggered`.
  - Actions are `payload_arm_<mode>` (default `ARM_<MODE>`) and `payload_disarm` (default `DISARM`).
  - `command_template` gets the variables `action` and `code`.
  - `supported_features` is a list of `arm_home`, `arm_away`, … and defaults to all of them.
  - When `code` is set, `code_arm_required` and `code_disarm_required` (both default true) require a code. A fixed code is checked locally; `REMOTE_CODE` and `REMOTE_CODE_TEXT` are checked by the panel.
- **Number:** `min` defaults to 1, `max` to 100 and `step` to 1.
- **Select:** only listed `options` are accepted.

- [ ] **Step 1: Write the failing tests**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/AlarmPanelMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue.Alarm
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlarmPanelMapperTest {
    private val plain = bindings("alarm_control_panel", """{"state_topic":"a","command_topic":"a/set"}""")

    private fun publishes(payload: String) = Encoded.Publish(listOf(Outgoing("a/set", payload, false, 0)))

    @Test
    fun `decodes standard states`() {
        assertEquals(Decoded.State(Alarm(AlarmState.ARMED_AWAY)), AlarmPanelMapper.decode(plain, "a", "armed_away", Unknown))
        assertEquals(Decoded.State(Alarm(AlarmState.TRIGGERED)), AlarmPanelMapper.decode(plain, "a", "triggered", Unknown))
        assertEquals(Decoded.Ignored, AlarmPanelMapper.decode(plain, "a", "weird", Unknown))
        val templated = bindings("alarm_control_panel", """{"state_topic":"a","command_topic":"a/set","value_template":"{{ value_json.state }}"}""")
        assertEquals(Decoded.State(Alarm(AlarmState.DISARMED)), AlarmPanelMapper.decode(templated, "a", """{"state":"disarmed"}""", Unknown))
    }

    @Test
    fun `default and custom action payloads`() {
        assertEquals(publishes("ARM_AWAY"), AlarmPanelMapper.encode(plain, Command.Arm(AlarmMode.AWAY), Unknown))
        assertEquals(publishes("ARM_CUSTOM_BYPASS"), AlarmPanelMapper.encode(plain, Command.Arm(AlarmMode.CUSTOM_BYPASS), Unknown))
        assertEquals(publishes("DISARM"), AlarmPanelMapper.encode(plain, Command.Disarm(), Unknown))
        val custom = bindings("alarm_control_panel", """{"command_topic":"a/set","payload_arm_night":"NUIT"}""")
        assertEquals(publishes("NUIT"), AlarmPanelMapper.encode(custom, Command.Arm(AlarmMode.NIGHT), Unknown))
    }

    @Test
    fun `supported features limit arm modes`() {
        val b = bindings("alarm_control_panel", """{"command_topic":"a/set","supported_features":["arm_home","arm_away"]}""")
        assertEquals(setOf(AlarmMode.HOME, AlarmMode.AWAY), AlarmPanelMapper.supportedModes(b))
        assertIs<Encoded.NotSupported>(AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.NIGHT), Unknown))
        assertEquals(AlarmMode.entries.toSet(), AlarmPanelMapper.supportedModes(plain))
    }

    @Test
    fun `remote code is required and passed through the command template`() {
        val b = bindings(
            "alarm_control_panel",
            """{"command_topic":"a/set","code":"REMOTE_CODE","command_template":"{\"action\":\"{{ action }}\",\"code\":\"{{ code }}\"}"}""",
        )
        assertIs<Encoded.NotSupported>(AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.AWAY), Unknown))
        assertEquals(
            publishes("""{"action":"ARM_AWAY","code":"1234"}"""),
            AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.AWAY, "1234"), Unknown),
        )
        assertTrue(AlarmPanelMapper.codeRequired(b, arming = true))
    }

    @Test
    fun `fixed code is checked locally and can be optional for arming`() {
        val b = bindings("alarm_control_panel", """{"command_topic":"a/set","code":"0000","code_arm_required":false}""")
        assertEquals(publishes("ARM_HOME"), AlarmPanelMapper.encode(b, Command.Arm(AlarmMode.HOME), Unknown))
        assertIs<Encoded.NotSupported>(AlarmPanelMapper.encode(b, Command.Disarm("9999"), Unknown))
        assertEquals(publishes("DISARM"), AlarmPanelMapper.encode(b, Command.Disarm("0000"), Unknown))
        assertFalse(AlarmPanelMapper.codeRequired(plain, arming = true))
    }

    @Test
    fun `prediction`() {
        assertEquals(Alarm(AlarmState.ARMING), AlarmPanelMapper.predict(Command.Arm(AlarmMode.AWAY), Unknown))
        assertEquals(Alarm(AlarmState.DISARMING), AlarmPanelMapper.predict(Command.Disarm(), Unknown))
    }
}
```

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/NumberSelectMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NumberSelectMapperTest {
    private val number = bindings("number", """{"state_topic":"n","command_topic":"n/set","min":10,"max":60}""")
    private val select = bindings("select", """{"state_topic":"s","command_topic":"s/set","options":["eco","comfort"]}""")

    @Test
    fun `number decodes and range-checks`() {
        assertEquals(Decoded.State(StateValue.Numeric(42.0)), NumberMapper.decode(number, "n", "42", Unknown))
        assertEquals(Decoded.Ignored, NumberMapper.decode(number, "n", "abc", Unknown))
        assertEquals(Encoded.Publish(listOf(Outgoing("n/set", "45", false, 0))), NumberMapper.encode(number, Command.SetNumber(45.0), Unknown))
        assertIs<Encoded.NotSupported>(NumberMapper.encode(number, Command.SetNumber(5.0), Unknown))
        assertEquals(StateValue.Numeric(45.0), NumberMapper.predict(Command.SetNumber(45.0), Unknown))
    }

    @Test
    fun `number defaults are 1 to 100`() {
        val b = bindings("number", """{"command_topic":"n/set"}""")
        assertIs<Encoded.NotSupported>(NumberMapper.encode(b, Command.SetNumber(0.0), Unknown))
        assertIs<Encoded.Publish>(NumberMapper.encode(b, Command.SetNumber(100.0), Unknown))
    }

    @Test
    fun `select accepts only listed options`() {
        assertEquals(Decoded.State(StateValue.Text("eco")), SelectMapper.decode(select, "s", "eco", Unknown))
        assertEquals(Decoded.Ignored, SelectMapper.decode(select, "s", "boost", Unknown))
        assertEquals(Encoded.Publish(listOf(Outgoing("s/set", "comfort", false, 0))), SelectMapper.encode(select, Command.SelectOption("comfort"), Unknown))
        assertIs<Encoded.NotSupported>(SelectMapper.encode(select, Command.SelectOption("boost"), Unknown))
        assertEquals(StateValue.Text("eco"), SelectMapper.predict(Command.SelectOption("eco"), Unknown))
    }
}
```

`core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/KindMappersTest.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class KindMappersTest {
    @Test
    fun `every kind has a dedicated mapper`() {
        val mappers = Kind.entries.map { KindMappers.forKind(it) }
        assertEquals(Kind.entries.size, mappers.toSet().size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:state:test`
Expected: FAIL, with compilation errors (`Unresolved reference: AlarmPanelMapper`, `NumberMapper`, `SelectMapper`).

- [ ] **Step 3: Implement**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/AlarmPanelMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object AlarmPanelMapper : KindMapper {
    private val STATES = mapOf(
        "disarmed" to AlarmState.DISARMED,
        "armed_home" to AlarmState.ARMED_HOME,
        "armed_away" to AlarmState.ARMED_AWAY,
        "armed_night" to AlarmState.ARMED_NIGHT,
        "armed_vacation" to AlarmState.ARMED_VACATION,
        "armed_custom_bypass" to AlarmState.ARMED_CUSTOM_BYPASS,
        "pending" to AlarmState.PENDING,
        "arming" to AlarmState.ARMING,
        "disarming" to AlarmState.DISARMING,
        "triggered" to AlarmState.TRIGGERED,
    )
    private val REMOTE_CODES = setOf("REMOTE_CODE", "REMOTE_CODE_TEXT")

    fun supportedModes(b: MqttBindings): Set<AlarmMode> =
        b.strList("supported_features")
            ?.mapNotNull { feature -> AlarmMode.entries.firstOrNull { "arm_" + it.name.lowercase() == feature } }
            ?.toSet()
            ?: AlarmMode.entries.toSet()

    fun codeRequired(b: MqttBindings, arming: Boolean): Boolean =
        b.str("code") != null && b.bool(if (arming) "code_arm_required" else "code_disarm_required", true)

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            STATES[v.asPayloadString()]?.let { Decoded.State(StateValue.Alarm(it)) } ?: Decoded.Ignored
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        val action: String
        val code: String?
        val arming: Boolean
        when (command) {
            is Command.Arm -> {
                if (command.mode !in supportedModes(b)) return Encoded.NotSupported("arm mode ${command.mode} not supported")
                val suffix = command.mode.name.lowercase()
                action = b.str("payload_arm_$suffix", "ARM_${command.mode.name}")
                code = command.code
                arming = true
            }
            is Command.Disarm -> {
                action = b.str("payload_disarm", "DISARM")
                code = command.code
                arming = false
            }
            else -> return Encoded.NotSupported("alarm panel cannot $command")
        }
        codeProblem(b, code, arming)?.let { return Encoded.NotSupported(it) }
        return publish(b, "command_topic", "command_template", action, mapOf("action" to action, "code" to code))
    }

    private fun codeProblem(b: MqttBindings, given: String?, arming: Boolean): String? {
        val configured = b.str("code") ?: return null
        if (!codeRequired(b, arming)) return null
        if (given.isNullOrEmpty()) return "code required"
        if (configured !in REMOTE_CODES && given != configured) return "wrong code"
        return null
    }

    override fun predict(command: Command, previous: StateValue): StateValue? = when (command) {
        is Command.Arm -> StateValue.Alarm(AlarmState.ARMING)
        is Command.Disarm -> StateValue.Alarm(AlarmState.DISARMING)
        else -> null
    }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/NumberMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.formatNumber

object NumberMapper : KindMapper {
    const val DEFAULT_MIN = 1.0
    const val DEFAULT_MAX = 100.0
    const val DEFAULT_STEP = 1.0

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            v.asDouble()?.let { Decoded.State(StateValue.Numeric(it)) } ?: Decoded.Ignored
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        if (command !is Command.SetNumber) return Encoded.NotSupported("number cannot $command")
        val min = b.double("min") ?: DEFAULT_MIN
        val max = b.double("max") ?: DEFAULT_MAX
        if (command.value !in min..max) return Encoded.NotSupported("value ${command.value} outside $min..$max")
        return publish(b, "command_topic", "command_template", formatNumber(command.value))
    }

    override fun predict(command: Command, previous: StateValue): StateValue? =
        (command as? Command.SetNumber)?.let { StateValue.Numeric(it.value) }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/SelectMapper.kt`:

```kotlin
package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asPayloadString

object SelectMapper : KindMapper {
    private fun options(b: MqttBindings) = b.strList("options").orEmpty()

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded =
        renderIncoming(b, "value_template", payload).decodeWith { v ->
            v.asPayloadString()?.takeIf { it in options(b) }?.let { Decoded.State(StateValue.Text(it)) } ?: Decoded.Ignored
        }

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded {
        if (command !is Command.SelectOption) return Encoded.NotSupported("select cannot $command")
        if (command.option !in options(b)) return Encoded.NotSupported("option '${command.option}' not in options")
        return publish(b, "command_topic", "command_template", command.option)
    }

    override fun predict(command: Command, previous: StateValue): StateValue? =
        (command as? Command.SelectOption)?.let { StateValue.Text(it.option) }
}
```

In `KindMapper.kt`, replace the whole `KindMappers` object and the `UnmappedKind` object with:

```kotlin
object KindMappers {
    fun forKind(kind: Kind): KindMapper = when (kind) {
        Kind.LIGHT -> LightMapper
        Kind.SWITCH -> SwitchMapper
        Kind.COVER -> CoverMapper
        Kind.CLIMATE -> ClimateMapper
        Kind.SENSOR -> SensorMapper
        Kind.BINARY_SENSOR -> BinarySensorMapper
        Kind.ALARM_PANEL -> AlarmPanelMapper
        Kind.NUMBER -> NumberMapper
        Kind.SELECT -> SelectMapper
        Kind.BUTTON -> ButtonMapper
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (all tests, including 10 new: 6 alarm, 3 number/select, 1 registry).

- [ ] **Step 5: Commit**

```bash
git add core/state/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): alarm panel, number and select mappers; exhaustive registry" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 9: `StateStore`: availability, attributes, shared topics

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/Availability.kt`, `core/state/src/main/kotlin/com/gekkotron/oikos/state/StateStore.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/StateStoreTest.kt`

**Interfaces:**
- Consumes: `KindMappers`, `Decoded` (Tasks 5–8); `MqttBindings`; `Templates`, `normalize` (internal, Task 4); `testEntity` (test helper, Task 5).
- Produces:
  - `class StateStore(now: () -> Instant = Instant::now, diagnostics: DiagnosticSink = DiagnosticSink.None)`
    - `val states: StateFlow<Map<String, EntityState>>`
    - `fun setEntities(entities: Collection<Entity>)`: keeps state for entities whose bindings did not change. New or changed entities start `Unknown` and are fed the last payload seen on their topics.
    - `fun subscriptionTopics(): Set<String>`: state, availability and `json_attributes_topic` topics
    - `fun onMessage(topic: String, payload: String): Set<String>`: returns the ids whose value was *confirmed* by this message
    - `fun markPending(uniqueId: String, predicted: StateValue)`, `fun confirmPending(uniqueId: String)`, `fun revert(uniqueId: String): Boolean` (true if it was pending), `fun isPending(uniqueId: String): Boolean`

Rules:
- An entity with no availability config is always available. One with availability starts unavailable until an `online` message arrives, as HA does.
- `availability_mode` can be `latest` (the default), `all` or `any`.
- `Decoded.Unsupported` sets the value to `StateValue.Unsupported` and is reported once per entity. `Decoded.Error` keeps the value and is reported once per entity.
- A decoded state clears `pending`, updates the confirmed value and stamps `lastUpdate`.

- [ ] **Step 1: Write the failing tests**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/StateStoreTest.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateStoreTest {
    private val diagnostics = mutableListOf<Diagnostic>()
    private val clock = Instant.parse("2026-09-24T10:00:00Z")
    private val store = StateStore(now = { clock }, diagnostics = { diagnostics += it })

    private val temperature = testEntity(
        "t", Kind.SENSOR, "sensor", """{"state_topic":"z/capteur","value_template":"{{ value_json.temperature }}"}""",
    )
    private val humidity = testEntity(
        "h", Kind.SENSOR, "sensor", """{"state_topic":"z/capteur","value_template":"{{ value_json.humidity }}"}""",
    )
    private val lamp = testEntity(
        "l", Kind.SWITCH, "switch",
        """{"state_topic":"s","command_topic":"c","availability_topic":"s/avail","json_attributes_topic":"s/attr"}""",
    )

    private fun value(id: String) = store.states.value.getValue(id).value

    @Test
    fun `one shared topic updates every bound entity`() {
        store.setEntities(listOf(temperature, humidity))
        val confirmed = store.onMessage("z/capteur", """{"temperature":21.5,"humidity":48}""")
        assertEquals(setOf("t", "h"), confirmed)
        assertEquals(StateValue.Numeric(21.5), value("t"))
        assertEquals(StateValue.Numeric(48.0), value("h"))
        assertEquals(clock, store.states.value.getValue("t").lastUpdate)
    }

    @Test
    fun `subscription topics include state, availability and attributes`() {
        store.setEntities(listOf(temperature, lamp))
        assertEquals(setOf("z/capteur", "s", "s/avail", "s/attr"), store.subscriptionTopics())
    }

    @Test
    fun `availability defaults and transitions`() {
        store.setEntities(listOf(temperature, lamp))
        assertTrue(store.states.value.getValue("t").available)
        assertFalse(store.states.value.getValue("l").available)
        store.onMessage("s/avail", "online")
        assertTrue(store.states.value.getValue("l").available)
        store.onMessage("s/avail", "offline")
        assertFalse(store.states.value.getValue("l").available)
    }

    @Test
    fun `availability mode all needs every topic online`() {
        val z2m = testEntity(
            "z", Kind.SWITCH, "switch",
            """{"state_topic":"z","command_topic":"z/set","availability_mode":"all","availability":[
                {"topic":"bridge/state","value_template":"{{ value_json.state }}"},
                {"topic":"z/availability","value_template":"{{ value_json.state }}"}]}""",
        )
        store.setEntities(listOf(z2m))
        store.onMessage("bridge/state", """{"state":"online"}""")
        assertFalse(store.states.value.getValue("z").available)
        store.onMessage("z/availability", """{"state":"online"}""")
        assertTrue(store.states.value.getValue("z").available)
        // A legacy plain "offline" payload still counts when the template cannot parse it.
        store.onMessage("bridge/state", "offline")
        assertFalse(store.states.value.getValue("z").available)
    }

    @Test
    fun `unsupported template shows Unsupported and is reported once`() {
        val weird = testEntity("w", Kind.SENSOR, "sensor", """{"state_topic":"w","value_template":"{{ value | tojson }}"}""")
        store.setEntities(listOf(weird))
        store.onMessage("w", "1")
        store.onMessage("w", "2")
        assertEquals(StateValue.Unsupported, value("w"))
        assertEquals(1, diagnostics.size)
    }

    // Review Focus 2
    @Test
    fun `non-JSON payload keeps previous state and reports`() {
        store.setEntities(listOf(temperature))
        store.onMessage("z/capteur", """{"temperature":21}""")
        val confirmed = store.onMessage("z/capteur", "online")
        assertEquals(emptySet(), confirmed)
        assertEquals(StateValue.Numeric(21.0), value("t"))
        store.onMessage("z/capteur", "garbage")
        assertEquals(1, diagnostics.size)
    }

    @Test
    fun `pending lifecycle`() {
        store.setEntities(listOf(lamp))
        store.onMessage("s", "OFF")
        store.markPending("l", StateValue.OnOff(true))
        assertTrue(store.isPending("l"))
        assertEquals(StateValue.OnOff(true), value("l"))

        assertTrue(store.revert("l"))
        assertEquals(StateValue.OnOff(false), value("l"))
        assertFalse(store.revert("l"))

        store.markPending("l", StateValue.OnOff(true))
        store.onMessage("s", "ON")
        assertFalse(store.isPending("l"))
        assertEquals(StateValue.OnOff(true), value("l"))

        store.markPending("l", StateValue.OnOff(false))
        store.confirmPending("l")
        assertFalse(store.isPending("l"))
        assertFalse(store.revert("l"))
        assertEquals(StateValue.OnOff(false), value("l"))
    }

    @Test
    fun `setEntities keeps surviving state and drops removed entities`() {
        store.setEntities(listOf(temperature, humidity))
        store.onMessage("z/capteur", """{"temperature":20,"humidity":50}""")
        store.setEntities(listOf(temperature))
        assertEquals(setOf("t"), store.states.value.keys)
        assertEquals(StateValue.Numeric(20.0), value("t"))
    }

    @Test
    fun `a new entity on an already seen topic gets the last payload`() {
        store.setEntities(listOf(temperature))
        store.onMessage("z/capteur", """{"temperature":20,"humidity":50}""")
        store.setEntities(listOf(temperature, humidity))
        assertEquals(StateValue.Numeric(50.0), value("h"))
    }

    @Test
    fun `json attributes are parsed`() {
        store.setEntities(listOf(lamp))
        store.onMessage("s/attr", """{"linkquality":80,"update":"idle"}""")
        assertEquals(mapOf("linkquality" to 80L, "update" to "idle"), store.states.value.getValue("l").attributes)
    }

    @Test
    fun `messages for unknown topics are ignored`() {
        store.setEntities(listOf(lamp))
        assertEquals(emptySet(), store.onMessage("nobody/listens", "x"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:state:test --tests '*StateStoreTest*'`
Expected: FAIL, with a compilation error (`Unresolved reference: StateStore`).

- [ ] **Step 3: Implement availability**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/Availability.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.TemplateResult
import com.gekkotron.oikos.state.template.Templates
import com.gekkotron.oikos.state.template.asPayloadString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal data class AvailabilityRule(
    val topic: String,
    val template: String?,
    val payloadAvailable: String,
    val payloadNotAvailable: String,
)

internal enum class AvailabilityMode { LATEST, ALL, ANY }

internal data class AvailabilitySpec(val rules: List<AvailabilityRule>, val mode: AvailabilityMode) {
    val topics: Set<String> = rules.mapTo(linkedSetOf()) { it.topic }

    companion object {
        fun of(b: MqttBindings): AvailabilitySpec? {
            val rules = mutableListOf<AvailabilityRule>()
            (b.config["availability"] as? JsonArray)?.forEach { item ->
                val ib = MqttBindings(b.component, item as? JsonObject ?: return@forEach)
                val topic = ib.str("topic") ?: return@forEach
                rules += AvailabilityRule(
                    topic, ib.str("value_template"),
                    ib.str("payload_available", "online"), ib.str("payload_not_available", "offline"),
                )
            }
            b.str("availability_topic")?.let {
                rules += AvailabilityRule(
                    it, b.str("availability_template"),
                    b.str("payload_available", "online"), b.str("payload_not_available", "offline"),
                )
            }
            if (rules.isEmpty()) return null
            val mode = when (b.str("availability_mode")) {
                "all" -> AvailabilityMode.ALL
                "any" -> AvailabilityMode.ANY
                else -> AvailabilityMode.LATEST
            }
            return AvailabilitySpec(rules, mode)
        }
    }
}

internal class AvailabilityTracker(val spec: AvailabilitySpec) {
    private val byTopic = mutableMapOf<String, Boolean>()
    private var latest: Boolean? = null

    /** Returns true when a rule recognised the payload. */
    fun onMessage(topic: String, payload: String): Boolean {
        var recognised = false
        for (rule in spec.rules) {
            if (rule.topic != topic) continue
            // If the template cannot evaluate this payload (e.g. plain "online"), fall back to the raw payload.
            val rendered = rule.template
                ?.let { (Templates.renderValue(it, payload) as? TemplateResult.Value)?.value.asPayloadString() }
                ?: payload
            val available = when (rendered) {
                rule.payloadAvailable -> true
                rule.payloadNotAvailable -> false
                else -> continue
            }
            byTopic[topic] = available
            latest = available
            recognised = true
        }
        return recognised
    }

    val available: Boolean
        get() = when (spec.mode) {
            AvailabilityMode.LATEST -> latest ?: false
            AvailabilityMode.ALL -> spec.topics.all { byTopic[it] == true }
            AvailabilityMode.ANY -> spec.topics.any { byTopic[it] == true }
        }
}
```

- [ ] **Step 4: Implement the store**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/StateStore.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.kinds.Decoded
import com.gekkotron.oikos.state.kinds.KindMapper
import com.gekkotron.oikos.state.kinds.KindMappers
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.normalize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Maps each incoming (topic, payload) onto every entity bound to that topic (spec §4.4). Thread-safe. */
class StateStore(
    private val now: () -> Instant = Instant::now,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
) {
    private class Tracked(
        val bindings: MqttBindings,
        val mapper: KindMapper,
        val availability: AvailabilityTracker?,
    ) {
        val stateTopics: Set<String> = mapper.stateTopics(bindings)
        val attributesTopic: String? = bindings.str("json_attributes_topic")
        val allTopics: Set<String> = stateTopics + availability?.spec?.topics.orEmpty() + setOfNotNull(attributesTopic)
    }

    private val lock = Any()
    private var tracked: Map<String, Tracked> = emptyMap()
    private var topicIndex: Map<String, List<String>> = emptyMap()
    private val confirmed = mutableMapOf<String, StateValue>()
    private val lastPayload = mutableMapOf<String, String>()
    private val reported = mutableSetOf<String>()

    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states.asStateFlow()

    fun setEntities(entities: Collection<Entity>): Unit = synchronized(lock) {
        val old = tracked
        val fresh = mutableSetOf<String>()
        tracked = entities.mapNotNull { e ->
            val b = e.bindings as? MqttBindings ?: return@mapNotNull null
            val kept = old[e.uniqueId]?.takeIf { it.bindings == b }
            val t = kept ?: Tracked(b, KindMappers.forKind(e.kind), AvailabilitySpec.of(b)?.let(::AvailabilityTracker))
                .also { fresh += e.uniqueId }
            e.uniqueId to t
        }.toMap()

        topicIndex = buildMap<String, MutableList<String>> {
            for ((id, t) in tracked) for (topic in t.allTopics) getOrPut(topic) { mutableListOf() } += id
        }
        confirmed.keys.retainAll(tracked.keys - fresh)
        reported.removeAll { it.substringBeforeLast(':') !in tracked.keys - fresh }
        lastPayload.keys.retainAll(topicIndex.keys)

        val current = _states.value
        val next = tracked.mapValuesTo(mutableMapOf()) { (id, t) ->
            if (id in fresh) EntityState(id, StateValue.Unknown, available = t.availability == null)
            else current[id] ?: EntityState(id, StateValue.Unknown, available = t.availability == null)
        }
        for (id in fresh) {
            val t = tracked.getValue(id)
            for (topic in t.allTopics) lastPayload[topic]?.let { apply(id, topic, it, next) }
        }
        _states.value = next
    }

    fun subscriptionTopics(): Set<String> = synchronized(lock) { topicIndex.keys.toSet() }

    /** Returns the ids whose value was confirmed by this message. */
    fun onMessage(topic: String, payload: String): Set<String> = synchronized(lock) {
        val ids = topicIndex[topic] ?: return emptySet()
        lastPayload[topic] = payload
        val next = _states.value.toMutableMap()
        val confirmedIds = ids.filterTo(mutableSetOf()) { apply(it, topic, payload, next) }
        _states.value = next
        confirmedIds
    }

    fun markPending(uniqueId: String, predicted: StateValue): Unit = update(uniqueId) {
        it.copy(value = predicted, pending = true)
    }

    /** For entities without state feedback: the prediction becomes the confirmed value. */
    fun confirmPending(uniqueId: String): Unit = synchronized(lock) {
        val s = _states.value[uniqueId]?.takeIf { it.pending } ?: return
        confirmed[uniqueId] = s.value
        _states.value = _states.value + (uniqueId to s.copy(pending = false, lastUpdate = now()))
    }

    /** Restores the last confirmed value. Returns true if the entity was pending. */
    fun revert(uniqueId: String): Boolean = synchronized(lock) {
        val s = _states.value[uniqueId]?.takeIf { it.pending } ?: return false
        _states.value = _states.value + (uniqueId to s.copy(value = confirmed[uniqueId] ?: StateValue.Unknown, pending = false))
        true
    }

    fun isPending(uniqueId: String): Boolean = _states.value[uniqueId]?.pending == true

    private fun update(uniqueId: String, change: (EntityState) -> EntityState): Unit = synchronized(lock) {
        val s = _states.value[uniqueId] ?: return
        _states.value = _states.value + (uniqueId to change(s))
    }

    /** Applies one message to one entity inside [states]. Returns true if its value was confirmed. */
    private fun apply(id: String, topic: String, payload: String, states: MutableMap<String, EntityState>): Boolean {
        val t = tracked[id] ?: return false
        var s = states[id] ?: return false
        var confirmedNow = false
        if (topic in t.stateTopics) {
            when (val d = t.mapper.decode(t.bindings, topic, payload, confirmed[id] ?: StateValue.Unknown)) {
                is Decoded.State -> {
                    confirmed[id] = d.value
                    s = s.copy(value = d.value, pending = false, lastUpdate = now())
                    confirmedNow = true
                }
                is Decoded.Unsupported -> {
                    reportOnce(id, "unsupported", "template unsupported on $topic: ${d.reason}")
                    if (!s.pending) s = s.copy(value = StateValue.Unsupported)
                }
                is Decoded.Error -> reportOnce(id, "error", "cannot read $topic: ${d.reason}")
                Decoded.Ignored -> Unit
            }
        }
        t.availability?.let { if (topic in it.spec.topics && it.onMessage(topic, payload)) s = s.copy(available = it.available) }
        if (topic == t.attributesTopic) parseAttributes(payload)?.let { s = s.copy(attributes = it) }
        states[id] = s
        return confirmedNow
    }

    private fun parseAttributes(payload: String): Map<String, Any?>? = try {
        (Json.parseToJsonElement(payload) as? JsonObject)?.mapValues { normalize(it.value) }
    } catch (e: SerializationException) {
        null
    }

    private fun reportOnce(id: String, kind: String, message: String) {
        if (reported.add("$id:$kind")) diagnostics.report(Diagnostic("state", "$id: $message"))
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (all tests, including 11 in `StateStoreTest`).

- [ ] **Step 6: Commit**

```bash
git add core/state/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): StateStore with availability, attributes and topic replay" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: `CommandTracker`: optimistic confirm / revert / timeout

**Files:**
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/CommandTracker.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/CommandTrackerTest.kt`

**Interfaces:**
- Consumes: `StateStore.markPending`, `revert`, `confirmPending`, `onMessage` (Task 9); `CommandFailure`, `Command`, `StateValue` (Task 1).
- Produces: `class CommandTracker(store: StateStore, scope: CoroutineScope, timeout: Duration = 5.seconds)` with
  - `val failures: SharedFlow<CommandFailure>`: reason `"Not confirmed"`
  - `fun track(uniqueId: String, command: Command, predicted: StateValue?, hasFeedback: Boolean)`: call **before** publishing. `predicted == null` means nothing is tracked. With `hasFeedback == false`, the entity stays pending until the caller runs `store.confirmPending(id)` after a successful publish.
  - `fun abort(uniqueId: String)`: the publish failed, so drop the prediction silently.

Confirmation is "any state message for that entity" (spec §4.4). `StateStore.onMessage` clears `pending` itself, and the timer only reverts if the entity is still pending when the timeout fires.

- [ ] **Step 1: Write the failing tests**

`core/state/src/test/kotlin/com/gekkotron/oikos/state/CommandTrackerTest.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue.OnOff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandTrackerTest {
    private val sw = testEntity("sw", Kind.SWITCH, "switch", """{"state_topic":"s","command_topic":"c"}""")

    private class Harness(val store: StateStore, val tracker: CommandTracker, val failures: MutableList<CommandFailure>)

    private fun TestScope.harness(): Harness {
        val store = StateStore().apply {
            setEntities(listOf(sw))
            onMessage("s", "OFF")
        }
        val tracker = CommandTracker(store, backgroundScope)
        val failures = mutableListOf<CommandFailure>()
        backgroundScope.launch { tracker.failures.collect { failures += it } }
        runCurrent()
        return Harness(store, tracker, failures)
    }

    private fun Harness.state() = store.states.value.getValue("sw")

    @Test
    fun `confirmation within the timeout clears pending`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        assertTrue(h.state().pending)
        advanceTimeBy(4_000)
        h.store.onMessage("s", "ON")
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(h.state().pending)
        assertEquals(OnOff(true), h.state().value)
        assertEquals(emptyList(), h.failures)
    }

    @Test
    fun `no confirmation reverts after 5 s and reports`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(h.state().pending)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(h.state().pending)
        assertEquals(OnOff(false), h.state().value)
        assertEquals(listOf(CommandFailure("sw", Command.TurnOn(), "Not confirmed")), h.failures)
    }

    @Test
    fun `a new command restarts the timer`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        advanceTimeBy(3_000)
        h.tracker.track("sw", Command.Toggle, OnOff(true), hasFeedback = true)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(emptyList(), h.failures)
        assertTrue(h.state().pending)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, h.failures.size)
        assertEquals(Command.Toggle, h.failures.single().command)
    }

    @Test
    fun `without feedback the caller confirms and nothing times out`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = false)
        h.store.confirmPending("sw")
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(h.state().pending)
        assertEquals(OnOff(true), h.state().value)
        assertEquals(emptyList(), h.failures)
    }

    @Test
    fun `null prediction tracks nothing`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.Press, null, hasFeedback = true)
        assertFalse(h.state().pending)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList(), h.failures)
    }

    @Test
    fun `abort reverts silently`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        h.tracker.abort("sw")
        assertFalse(h.state().pending)
        assertEquals(OnOff(false), h.state().value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList(), h.failures)
    }

    // Review Focus 5
    @Test
    fun `entity removed while pending emits nothing`() = runTest {
        val h = harness()
        h.tracker.track("sw", Command.TurnOn(), OnOff(true), hasFeedback = true)
        h.store.setEntities(emptyList())
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(emptyList(), h.failures)
        assertEquals(emptyMap(), h.store.states.value)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:state:test --tests '*CommandTrackerTest*'`
Expected: FAIL, with a compilation error (`Unresolved reference: CommandTracker`).

- [ ] **Step 3: Implement**

`core/state/src/main/kotlin/com/gekkotron/oikos/state/CommandTracker.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.StateValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Optimistic commands (spec §4.4): pending → confirmed by any state message, or reverted after [timeout]. */
class CommandTracker(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val timeout: Duration = 5.seconds,
) {
    private val _failures = MutableSharedFlow<CommandFailure>(extraBufferCapacity = 64)
    val failures: SharedFlow<CommandFailure> = _failures.asSharedFlow()

    private val timers = ConcurrentHashMap<String, Job>()

    /** Call before publishing. Without feedback, the caller confirms with [StateStore.confirmPending]. */
    fun track(uniqueId: String, command: Command, predicted: StateValue?, hasFeedback: Boolean) {
        timers.remove(uniqueId)?.cancel()
        if (predicted == null) return
        store.markPending(uniqueId, predicted)
        if (!hasFeedback) return
        timers[uniqueId] = scope.launch {
            delay(timeout)
            timers.remove(uniqueId, coroutineContext.job)
            if (store.revert(uniqueId)) _failures.emit(CommandFailure(uniqueId, command, "Not confirmed"))
        }
    }

    /** The publish failed: drop the prediction without reporting a timeout. */
    fun abort(uniqueId: String) {
        timers.remove(uniqueId)?.cancel()
        store.revert(uniqueId)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (all tests, including 7 in `CommandTrackerTest`).

- [ ] **Step 5: Commit**

```bash
git add core/state/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(state): optimistic CommandTracker with 5 s confirm window" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 11: `:core:source`: discovery parsing (abbreviations, `~`, device-based `cmps`)

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `core/source/build.gradle.kts`
- Create: `core/source/src/main/resources/com/gekkotron/oikos/source/abbreviations.json`
- Create: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/Abbreviations.kt`, `DiscoveryParser.kt`
- Create fixtures: `core/source/src/test/resources/discovery/z2m_light.json`, `z2m_cover.json`, `z2m_contact.json`, `z2m_motion.json`, `z2m_temperature.json`, `z2m_humidity.json`, `tasmota_relay.json`, `device_based.json`
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`

**Interfaces:**
- Consumes:
  - `MqttBindings` (Task 5)
  - `ClimateMapper.DEFAULT_MODES`, `DEFAULT_MIN_TEMP`, `DEFAULT_MAX_TEMP`, `DEFAULT_TEMP_STEP` (Task 7)
  - `NumberMapper.DEFAULT_*`, `AlarmPanelMapper.supportedModes`, `codeRequired` (Task 8)
  - Model types (Task 1)
- Produces:
  - `data class DiscoveredEntity(val entity: Entity, val device: Device?)`
  - `sealed interface DiscoveryResult { NotDiscovery; Removed; Skipped(reason); Entities(items: List<DiscoveredEntity>, skipped: List<String> = emptyList()) }`
  - `object DiscoveryParser { fun parse(prefix: String, topic: String, payload: String): DiscoveryResult }`

Rules (spec §4.3):
- **Topics:** `<prefix>/<component>/[<node_id>/]<object_id>/config`, or the device form `<prefix>/device/<object_id>/config` with `cmps`.
- **Expansion:** abbreviations are expanded first (top level, `dev`, `o`, each `avty` item and each `cmps` entry), then `~` is expanded for keys ending in `_topic` and for `availability[].topic`.
- **Device-based discovery:** root options (everything except `components`) are inherited by each component. A component with only `platform` is a removal marker and is skipped silently.
- **Removal:** an empty payload means `Removed`.
- **Failures:** invalid JSON, a non-object payload, an unsupported component, a missing required topic, a select without options or `schema: template` each give `Skipped(reason)`. Nothing is thrown.
- **Naming:**
  - An entity name of null or absent falls back to the device name, then the `object_id`.
  - Otherwise the name is `"<device name> <entity name>"`, unless the entity name already starts with the device name.
  - `unique_id` falls back to `<component>_[<node_id>_]<object_id>`.
- **Device id:** the first `identifiers` entry, else the first connection joined with `:`. A device without either is dropped (the entity is kept without a device).

The fixtures are representative zigbee2mqtt 2.x, Tasmota-style and HA-docs payloads with fake ids. Replace them with real `mosquitto_sub -v -t 'homeassistant/#'` captures once sub-project D enables z2m discovery; keep the file names.

- [ ] **Step 1: Register the module**

`settings.gradle.kts`: add `include(":core:source")`. `build.gradle.kts`: add `kover(project(":core:source"))`.

`core/source/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:state"))
    api(project(":core:transport"))
    testImplementation(testFixtures(project(":core:transport")))
}
```

- [ ] **Step 2: Add the abbreviation table**

`core/source/src/main/resources/com/gekkotron/oikos/source/abbreviations.json` holds the HA MQTT abbreviation table (`homeassistant/components/mqtt/abbreviations.py`), restricted to keys that matter for the supported components plus common shared keys. Unknown abbreviations pass through unchanged, which is harmless because the mappers only read full names.

```json
{
  "payload": {
    "act_t": "action_topic", "act_tpl": "action_template",
    "avty": "availability", "avty_mode": "availability_mode", "avty_t": "availability_topic", "avty_tpl": "availability_template",
    "bri_cmd_t": "brightness_command_topic", "bri_cmd_tpl": "brightness_command_template", "bri_scl": "brightness_scale",
    "bri_stat_t": "brightness_state_topic", "bri_tpl": "brightness_template", "bri_val_tpl": "brightness_value_template",
    "clr_temp_cmd_t": "color_temp_command_topic", "clr_temp_cmd_tpl": "color_temp_command_template",
    "clr_temp_stat_t": "color_temp_state_topic", "clr_temp_tpl": "color_temp_template", "clr_temp_val_tpl": "color_temp_value_template",
    "clrm": "color_mode", "clrm_stat_t": "color_mode_state_topic", "clrm_val_tpl": "color_mode_value_template",
    "cmd_off_tpl": "command_off_template", "cmd_on_tpl": "command_on_template",
    "cmd_t": "command_topic", "cmd_tpl": "command_template",
    "cmps": "components",
    "cod_arm_req": "code_arm_required", "cod_dis_req": "code_disarm_required", "cod_trig_req": "code_trigger_required",
    "curr_hum_t": "current_humidity_topic", "curr_hum_tpl": "current_humidity_template",
    "curr_temp_t": "current_temperature_topic", "curr_temp_tpl": "current_temperature_template",
    "dev": "device", "dev_cla": "device_class",
    "e": "encoding", "en": "enabled_by_default", "ent_cat": "entity_category", "ent_pic": "entity_picture",
    "exp_aft": "expire_after",
    "fan_mode_cmd_t": "fan_mode_command_topic", "fan_mode_cmd_tpl": "fan_mode_command_template",
    "fan_mode_stat_t": "fan_mode_state_topic", "fan_mode_stat_tpl": "fan_mode_state_template", "fan_modes": "fan_modes",
    "frc_upd": "force_update",
    "fx_cmd_t": "effect_command_topic", "fx_cmd_tpl": "effect_command_template", "fx_list": "effect_list",
    "fx_stat_t": "effect_state_topic", "fx_tpl": "effect_template", "fx_val_tpl": "effect_value_template",
    "hs_cmd_t": "hs_command_topic", "hs_cmd_tpl": "hs_command_template", "hs_stat_t": "hs_state_topic", "hs_val_tpl": "hs_value_template",
    "ic": "icon",
    "json_attr": "json_attributes", "json_attr_t": "json_attributes_topic", "json_attr_tpl": "json_attributes_template",
    "max_mirs": "max_mireds", "min_mirs": "min_mireds", "max_temp": "max_temp", "min_temp": "min_temp",
    "max": "max", "min": "min",
    "mode_cmd_t": "mode_command_topic", "mode_cmd_tpl": "mode_command_template",
    "mode_stat_t": "mode_state_topic", "mode_stat_tpl": "mode_state_template",
    "o": "origin", "obj_id": "object_id", "off_dly": "off_delay", "on_cmd_type": "on_command_type",
    "ops": "options", "opt": "optimistic", "p": "platform",
    "pl_arm_away": "payload_arm_away", "pl_arm_custom_b": "payload_arm_custom_bypass", "pl_arm_home": "payload_arm_home",
    "pl_arm_nite": "payload_arm_night", "pl_arm_vacation": "payload_arm_vacation",
    "pl_avail": "payload_available", "pl_cls": "payload_close", "pl_disarm": "payload_disarm",
    "pl_not_avail": "payload_not_available", "pl_off": "payload_off", "pl_on": "payload_on",
    "pl_open": "payload_open", "pl_prs": "payload_press", "pl_rst": "payload_reset", "pl_stop": "payload_stop",
    "pl_trig": "payload_trigger",
    "pos": "reports_position", "pos_clsd": "position_closed", "pos_open": "position_open",
    "pos_t": "position_topic", "pos_tpl": "position_template",
    "pow_cmd_t": "power_command_topic", "pow_cmd_tpl": "power_command_template",
    "pr_mode_cmd_t": "preset_mode_command_topic", "pr_mode_cmd_tpl": "preset_mode_command_template",
    "pr_mode_stat_t": "preset_mode_state_topic", "pr_mode_val_tpl": "preset_mode_value_template", "pr_modes": "preset_modes",
    "qos": "qos", "ret": "retain",
    "rgb_cmd_t": "rgb_command_topic", "rgb_cmd_tpl": "rgb_command_template", "rgb_stat_t": "rgb_state_topic", "rgb_val_tpl": "rgb_value_template",
    "set_pos_t": "set_position_topic", "set_pos_tpl": "set_position_template",
    "stat_cla": "state_class", "stat_clsd": "state_closed", "stat_closing": "state_closing",
    "stat_off": "state_off", "stat_on": "state_on", "stat_open": "state_open", "stat_opening": "state_opening",
    "stat_stopped": "state_stopped", "stat_t": "state_topic", "stat_tpl": "state_template", "stat_val_tpl": "state_value_template",
    "step": "step", "sug_dsp_prc": "suggested_display_precision",
    "sup_clrm": "supported_color_modes", "sup_feat": "supported_features",
    "swing_mode_cmd_t": "swing_mode_command_topic", "swing_mode_cmd_tpl": "swing_mode_command_template",
    "swing_mode_stat_t": "swing_mode_state_topic", "swing_mode_stat_tpl": "swing_mode_state_template",
    "t": "topic",
    "temp_cmd_t": "temperature_command_topic", "temp_cmd_tpl": "temperature_command_template",
    "temp_hi_cmd_t": "temperature_high_command_topic", "temp_hi_stat_t": "temperature_high_state_topic",
    "temp_lo_cmd_t": "temperature_low_command_topic", "temp_lo_stat_t": "temperature_low_state_topic",
    "temp_stat_t": "temperature_state_topic", "temp_stat_tpl": "temperature_state_template", "temp_unit": "temperature_unit",
    "tilt_clsd_val": "tilt_closed_value", "tilt_cmd_t": "tilt_command_topic", "tilt_cmd_tpl": "tilt_command_template",
    "tilt_max": "tilt_max", "tilt_min": "tilt_min", "tilt_opnd_val": "tilt_opened_value", "tilt_opt": "tilt_optimistic",
    "tilt_status_t": "tilt_status_topic", "tilt_status_tpl": "tilt_status_template",
    "uniq_id": "unique_id", "unit_of_meas": "unit_of_measurement", "val_tpl": "value_template",
    "whit_cmd_t": "white_command_topic", "whit_scl": "white_scale",
    "xy_cmd_t": "xy_command_topic", "xy_cmd_tpl": "xy_command_template", "xy_stat_t": "xy_state_topic", "xy_val_tpl": "xy_value_template"
  },
  "device": {
    "cns": "connections", "ids": "identifiers", "name": "name", "mf": "manufacturer", "mdl": "model", "mdl_id": "model_id",
    "hw": "hw_version", "sw": "sw_version", "sa": "suggested_area", "sn": "serial_number", "cu": "configuration_url"
  },
  "origin": {
    "name": "name", "sw": "sw_version", "url": "support_url"
  }
}
```

- [ ] **Step 3: Add the fixtures**

`core/source/src/test/resources/discovery/z2m_light.json` (topic `homeassistant/light/0x00178801086a1b2c/light/config`):

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"},{"topic":"zigbee2mqtt/rooms/salon/lampe/availability","value_template":"{{ value_json.state }}"}],"availability_mode":"all","brightness":true,"brightness_scale":254,"command_topic":"zigbee2mqtt/rooms/salon/lampe/set","device":{"identifiers":["zigbee2mqtt_0x00178801086a1b2c"],"manufacturer":"Philips","model":"Hue white ambiance E27","name":"rooms/salon/lampe","via_device":"zigbee2mqtt_bridge_0x00124b0022aa33bb"},"name":null,"object_id":"rooms/salon/lampe","origin":{"name":"Zigbee2MQTT","sw":"2.1.0","url":"https://www.zigbee2mqtt.io"},"schema":"json","state_topic":"zigbee2mqtt/rooms/salon/lampe","supported_color_modes":["color_temp"],"unique_id":"0x00178801086a1b2c_light_zigbee2mqtt"}
```

`z2m_cover.json` (topic `homeassistant/cover/0xa4c138f00d1e2f3a/cover/config`):

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"}],"command_topic":"zigbee2mqtt/rooms/salon/volet/set","device":{"identifiers":["zigbee2mqtt_0xa4c138f00d1e2f3a"],"manufacturer":"Tuya","model":"Curtain motor","name":"rooms/salon/volet","suggested_area":"Salon"},"device_class":"shutter","name":null,"object_id":"rooms/salon/volet","origin":{"name":"Zigbee2MQTT","sw":"2.1.0","url":"https://www.zigbee2mqtt.io"},"payload_close":"CLOSE","payload_open":"OPEN","payload_stop":"STOP","position_template":"{{ value_json.position }}","position_topic":"zigbee2mqtt/rooms/salon/volet","set_position_template":"{ \"position\": {{ position }} }","set_position_topic":"zigbee2mqtt/rooms/salon/volet/set","state_closing":"CLOSING","state_opening":"OPENING","state_stopped":"STOP","state_topic":"zigbee2mqtt/rooms/salon/volet","unique_id":"0xa4c138f00d1e2f3a_cover_zigbee2mqtt","value_template":"{% if \"moving\" in value_json and value_json.moving %} {{ value_json.moving }} {% else %} STOP {% endif %}"}
```

`z2m_contact.json` (topic `homeassistant/binary_sensor/0x00158d0004a1b2c3/contact/config`):

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"}],"device":{"identifiers":["zigbee2mqtt_0x00158d0004a1b2c3"],"manufacturer":"Aqara","model":"Door and window sensor","name":"rooms/entree/porte"},"device_class":"door","name":"Door","object_id":"rooms/entree/porte_door","origin":{"name":"Zigbee2MQTT","sw":"2.1.0","url":"https://www.zigbee2mqtt.io"},"payload_off":true,"payload_on":false,"state_topic":"zigbee2mqtt/rooms/entree/porte","unique_id":"0x00158d0004a1b2c3_contact_zigbee2mqtt","value_template":"{{ value_json.contact }}"}
```

`z2m_motion.json` (topic `homeassistant/binary_sensor/0x00158d0005b2c3d4/occupancy/config`):

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"}],"device":{"identifiers":["zigbee2mqtt_0x00158d0005b2c3d4"],"manufacturer":"Aqara","model":"Motion sensor","name":"rooms/couloir/mouvement"},"device_class":"motion","name":"Occupancy","object_id":"rooms/couloir/mouvement_occupancy","origin":{"name":"Zigbee2MQTT","sw":"2.1.0","url":"https://www.zigbee2mqtt.io"},"payload_off":false,"payload_on":true,"state_topic":"zigbee2mqtt/rooms/couloir/mouvement","unique_id":"0x00158d0005b2c3d4_occupancy_zigbee2mqtt","value_template":"{{ value_json.occupancy }}"}
```

`z2m_temperature.json` (topic `homeassistant/sensor/0x00158d0006c3d4e5/temperature/config`):

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"}],"device":{"identifiers":["zigbee2mqtt_0x00158d0006c3d4e5"],"manufacturer":"Aqara","model":"Temperature and humidity sensor","name":"rooms/salon/capteur"},"device_class":"temperature","enabled_by_default":true,"name":"Temperature","object_id":"rooms/salon/capteur_temperature","origin":{"name":"Zigbee2MQTT","sw":"2.1.0","url":"https://www.zigbee2mqtt.io"},"state_class":"measurement","state_topic":"zigbee2mqtt/rooms/salon/capteur","unique_id":"0x00158d0006c3d4e5_temperature_zigbee2mqtt","unit_of_measurement":"°C","value_template":"{{ value_json.temperature }}"}
```

`z2m_humidity.json` (topic `homeassistant/sensor/0x00158d0006c3d4e5/humidity/config`):

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"}],"device":{"identifiers":["zigbee2mqtt_0x00158d0006c3d4e5"],"manufacturer":"Aqara","model":"Temperature and humidity sensor","name":"rooms/salon/capteur"},"device_class":"humidity","enabled_by_default":true,"name":"Humidity","object_id":"rooms/salon/capteur_humidity","origin":{"name":"Zigbee2MQTT","sw":"2.1.0","url":"https://www.zigbee2mqtt.io"},"state_class":"measurement","state_topic":"zigbee2mqtt/rooms/salon/capteur","unique_id":"0x00158d0006c3d4e5_humidity_zigbee2mqtt","unit_of_measurement":"%","value_template":"{{ value_json.humidity }}"}
```

`tasmota_relay.json` (topic `homeassistant/switch/ABC123/relay_1/config`, fully abbreviated):

```json
{"name":"Relay","~":"tasmota_ABC123/","stat_t":"~stat/RESULT","cmd_t":"~cmnd/POWER","val_tpl":"{{value_json.POWER}}","pl_on":"ON","pl_off":"OFF","avty_t":"~tele/LWT","pl_avail":"Online","pl_not_avail":"Offline","uniq_id":"ABC123_RL_1","dev":{"ids":["ABC123"],"name":"Tasmota","mdl":"Sonoff Basic","mf":"ITEAD","sw":"13.0","sa":"Garage"}}
```

`device_based.json` (topic `homeassistant/device/ea334450945afc/config`; from the HA docs example):

```json
{"dev":{"ids":"ea334450945afc","name":"Kitchen","mf":"Bla electronics","mdl":"xya","sw":"1.0","sn":"ea334450945afc","hw":"1.0rev2"},"o":{"name":"bla2mqtt","sw":"2.1","url":"https://bla2mqtt.example.com/support"},"cmps":{"some_unique_component_id1":{"p":"sensor","device_class":"temperature","unit_of_measurement":"°C","value_template":"{{ value_json.temperature}}","unique_id":"temp01ae_t"},"some_unique_id2":{"p":"sensor","device_class":"humidity","unit_of_measurement":"%","value_template":"{{ value_json.humidity}}","unique_id":"temp01ae_h"},"removed_one":{"p":"sensor"}},"state_topic":"sensorBedroom/state","qos":2}
```

- [ ] **Step 4: Write the failing tests**

`core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`:

```kotlin
package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryParserTest {
    private val prefix = "homeassistant"

    private fun fixture(name: String) = javaClass.getResource("/discovery/$name")!!.readText()

    private fun single(topic: String, payload: String): DiscoveredEntity {
        val r = DiscoveryParser.parse(prefix, topic, payload)
        assertIs<DiscoveryResult.Entities>(r, "expected entities, got $r")
        return r.items.single()
    }

    private val DiscoveredEntity.b get() = entity.bindings as MqttBindings

    @Test
    fun `z2m json light`() {
        val d = single("homeassistant/light/0x00178801086a1b2c/light/config", fixture("z2m_light.json"))
        assertEquals("0x00178801086a1b2c_light_zigbee2mqtt", d.entity.uniqueId)
        assertEquals(Kind.LIGHT, d.entity.kind)
        assertEquals("rooms/salon/lampe", d.entity.name)
        assertTrue(d.entity.capabilities.brightness)
        assertEquals(setOf("color_temp"), d.entity.capabilities.colorModes)
        assertEquals("zigbee2mqtt_0x00178801086a1b2c", d.device?.id)
        assertEquals("Philips", d.device?.manufacturer)
        assertEquals(d.device?.id, d.entity.deviceId)
        assertEquals("zigbee2mqtt/rooms/salon/lampe", d.b.str("state_topic"))
    }

    @Test
    fun `z2m cover keeps position capability and suggested area`() {
        val d = single("homeassistant/cover/0xa4c138f00d1e2f3a/cover/config", fixture("z2m_cover.json"))
        assertEquals(Kind.COVER, d.entity.kind)
        assertEquals("shutter", d.entity.deviceClass)
        assertTrue(d.entity.capabilities.position)
        assertEquals("Salon", d.device?.suggestedArea)
    }

    // Review Focus 1
    @Test
    fun `contact sensor keeps boolean payload_on as Python string`() {
        val d = single("homeassistant/binary_sensor/0x00158d0004a1b2c3/contact/config", fixture("z2m_contact.json"))
        assertEquals(Kind.BINARY_SENSOR, d.entity.kind)
        assertEquals("door", d.entity.deviceClass)
        assertEquals("False", d.b.str("payload_on"))
        assertEquals("True", d.b.str("payload_off"))
        assertEquals("rooms/entree/porte Door", d.entity.name)
    }

    @Test
    fun `motion and sensors`() {
        val motion = single("homeassistant/binary_sensor/0x00158d0005b2c3d4/occupancy/config", fixture("z2m_motion.json"))
        assertEquals("motion", motion.entity.deviceClass)
        val temp = single("homeassistant/sensor/0x00158d0006c3d4e5/temperature/config", fixture("z2m_temperature.json"))
        val hum = single("homeassistant/sensor/0x00158d0006c3d4e5/humidity/config", fixture("z2m_humidity.json"))
        assertEquals("°C", temp.entity.unit)
        assertEquals("rooms/salon/capteur Temperature", temp.entity.name)
        assertEquals(temp.b.str("state_topic"), hum.b.str("state_topic"))
        assertEquals(temp.device, hum.device)
    }

    @Test
    fun `abbreviated config with base topic`() {
        val d = single("homeassistant/switch/ABC123/relay_1/config", fixture("tasmota_relay.json"))
        assertEquals("ABC123_RL_1", d.entity.uniqueId)
        assertEquals(Kind.SWITCH, d.entity.kind)
        assertEquals("tasmota_ABC123/stat/RESULT", d.b.str("state_topic"))
        assertEquals("tasmota_ABC123/cmnd/POWER", d.b.str("command_topic"))
        assertEquals("tasmota_ABC123/tele/LWT", d.b.str("availability_topic"))
        assertEquals("Online", d.b.str("payload_available"))
        assertEquals("{{value_json.POWER}}", d.b.str("value_template"))
        assertNull(d.b.config["~"])
        assertEquals(Device("ABC123", "Tasmota", "ITEAD", "Sonoff Basic", "Garage"), d.device)
        assertEquals("Tasmota Relay", d.entity.name)
    }

    @Test
    fun `base topic as suffix and inside availability list`() {
        val d = single(
            "homeassistant/switch/x/config",
            """{"~":"base","cmd_t":"cmd/~","avty":[{"t":"~/avail"}],"uniq_id":"u"}""",
        )
        assertEquals("cmd/base", d.b.str("command_topic"))
        assertEquals("""[{"topic":"base/avail"}]""", d.b.config["availability"].toString())
    }

    @Test
    fun `device-based discovery inherits shared options`() {
        val r = DiscoveryParser.parse(prefix, "homeassistant/device/ea334450945afc/config", fixture("device_based.json"))
        assertIs<DiscoveryResult.Entities>(r)
        assertEquals(listOf("temp01ae_t", "temp01ae_h"), r.items.map { it.entity.uniqueId })
        r.items.forEach {
            assertEquals("sensorBedroom/state", (it.entity.bindings as MqttBindings).str("state_topic"))
            assertEquals(2, (it.entity.bindings as MqttBindings).int("qos", 0))
            assertEquals("Kitchen", it.device?.name)
        }
        assertEquals("ea334450945afc", r.items.first().device?.id)
        assertEquals(emptyList(), r.skipped)
    }

    @Test
    fun `device-based discovery reports bad components but keeps good ones`() {
        val r = DiscoveryParser.parse(
            prefix, "homeassistant/device/d1/config",
            """{"dev":{"ids":"d1","name":"D"},"cmps":{"a":{"p":"sensor","stat_t":"s","uniq_id":"a1"},"b":{"p":"vacuum","uniq_id":"b1"}}}""",
        )
        assertIs<DiscoveryResult.Entities>(r)
        assertEquals(listOf("a1"), r.items.map { it.entity.uniqueId })
        assertEquals(1, r.skipped.size)
        assertTrue("vacuum" in r.skipped.single())
    }

    @Test
    fun `empty payload removes`() {
        assertEquals(DiscoveryResult.Removed, DiscoveryParser.parse(prefix, "homeassistant/light/a/b/config", ""))
    }

    @Test
    fun `non-config topics are not discovery`() {
        assertEquals(DiscoveryResult.NotDiscovery, DiscoveryParser.parse(prefix, "homeassistant/status", "online"))
        assertEquals(DiscoveryResult.NotDiscovery, DiscoveryParser.parse(prefix, "zigbee2mqtt/light/a/config", "{}"))
        assertEquals(DiscoveryResult.NotDiscovery, DiscoveryParser.parse(prefix, "homeassistant/a/b/c/d/config", "{}"))
        assertIs<DiscoveryResult.Entities>(
            DiscoveryParser.parse("custom", "custom/switch/x/config", """{"cmd_t":"c","uniq_id":"u"}"""),
        )
    }

    // Review Focus 3
    @Test
    fun `invalid JSON and non-object payloads are skipped`() {
        listOf("{not json", "[1,2,3]", "\"text\"", "{\"a\":").forEach { payload ->
            assertIs<DiscoveryResult.Skipped>(DiscoveryParser.parse(prefix, "homeassistant/switch/x/config", payload), payload)
        }
    }

    @Test
    fun `validation failures are skipped with a reason`() {
        fun reason(topic: String, payload: String) =
            (DiscoveryParser.parse(prefix, topic, payload) as DiscoveryResult.Skipped).reason
        assertTrue("vacuum" in reason("homeassistant/vacuum/x/config", """{"cmd_t":"c"}"""))
        assertTrue("command_topic" in reason("homeassistant/switch/x/config", """{"stat_t":"s"}"""))
        assertTrue("state_topic" in reason("homeassistant/sensor/x/config", """{"name":"n"}"""))
        assertTrue("options" in reason("homeassistant/select/x/config", """{"cmd_t":"c","options":[]}"""))
        assertTrue("template" in reason("homeassistant/light/x/config", """{"cmd_t":"c","schema":"template"}"""))
    }

    @Test
    fun `missing unique_id is derived from the topic`() {
        assertEquals("switch_node_obj", single("homeassistant/switch/node/obj/config", """{"cmd_t":"c"}""").entity.uniqueId)
        assertEquals("switch_obj", single("homeassistant/switch/obj/config", """{"cmd_t":"c"}""").entity.uniqueId)
    }

    @Test
    fun `names fall back to device then object id`() {
        assertEquals("obj", single("homeassistant/switch/obj/config", """{"cmd_t":"c"}""").entity.name)
        assertEquals(
            "Plug",
            single("homeassistant/switch/obj/config", """{"cmd_t":"c","name":null,"dev":{"ids":["p"],"name":"Plug"}}""").entity.name,
        )
        assertEquals(
            "Plug power",
            single("homeassistant/switch/obj/config", """{"cmd_t":"c","name":"Plug power","dev":{"ids":["p"],"name":"Plug"}}""").entity.name,
        )
    }

    @Test
    fun `devices without identifiers use connections or are dropped`() {
        val withConn = single(
            "homeassistant/switch/o/config",
            """{"cmd_t":"c","dev":{"cns":[["mac","aa:bb"]],"name":"M"}}""",
        )
        assertEquals("mac:aa:bb", withConn.device?.id)
        assertNull(single("homeassistant/switch/o/config", """{"cmd_t":"c","dev":{"name":"M"}}""").device)
    }

    @Test
    fun `capabilities per kind`() {
        val climate = single("homeassistant/climate/c/config", """{"modes":["off","heat"],"min_temp":10}""").entity.capabilities
        assertEquals(listOf("off", "heat"), climate.hvacModes)
        assertEquals(10.0, climate.minTemp)
        assertEquals(35.0, climate.maxTemp)
        val number = single("homeassistant/number/n/config", """{"cmd_t":"c","max":10,"step":0.5}""").entity.capabilities
        assertEquals(1.0, number.min)
        assertEquals(10.0, number.max)
        assertEquals(0.5, number.step)
        val select = single("homeassistant/select/s/config", """{"cmd_t":"c","ops":["a","b"]}""").entity.capabilities
        assertEquals(listOf("a", "b"), select.options)
        val alarm = single(
            "homeassistant/alarm_control_panel/a/config",
            """{"cmd_t":"c","code":"REMOTE_CODE","sup_feat":["arm_away"]}""",
        ).entity.capabilities
        assertEquals(setOf(AlarmMode.AWAY), alarm.alarmModes)
        assertTrue(alarm.codeArmRequired)
        val basicLight = single("homeassistant/light/l/config", """{"cmd_t":"c","bri_cmd_t":"b","rgb_cmd_t":"r"}""").entity.capabilities
        assertTrue(basicLight.brightness)
        assertEquals(setOf("rgb"), basicLight.colorModes)
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./gradlew :core:source:test`
Expected: FAIL, with compilation errors (`Unresolved reference: DiscoveryParser`).

- [ ] **Step 6: Implement abbreviations**

`core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/Abbreviations.kt`:

```kotlin
package com.gekkotron.oikos.source.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Expands HA MQTT discovery abbreviations (table embedded as a resource, spec §4.3). */
internal object Abbreviations {
    private val tables: Map<String, Map<String, String>> by lazy {
        val text = checkNotNull(Abbreviations::class.java.getResourceAsStream("/com/gekkotron/oikos/source/abbreviations.json")) {
            "abbreviations.json missing from resources"
        }.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonObject.mapValues { (_, table) ->
            table.jsonObject.mapValues { it.value.jsonPrimitive.content }
        }
    }

    fun expand(raw: JsonObject): JsonObject {
        val main = tables.getValue("payload")
        return JsonObject(
            rename(raw, main).mapValues { (key, value) ->
                when (key) {
                    "device" -> (value as? JsonObject)?.let { rename(it, tables.getValue("device")) } ?: value
                    "origin" -> (value as? JsonObject)?.let { rename(it, tables.getValue("origin")) } ?: value
                    "availability" -> (value as? JsonArray)
                        ?.let { list -> JsonArray(list.map { item -> (item as? JsonObject)?.let { rename(it, main) } ?: item }) }
                        ?: value
                    "components" -> (value as? JsonObject)
                        ?.let { cmps -> JsonObject(cmps.mapValues { (_, c) -> (c as? JsonObject)?.let(::expand) ?: c }) }
                        ?: value
                    else -> value
                }
            },
        )
    }

    /** Full key names win over abbreviations when both are present. */
    private fun rename(o: JsonObject, table: Map<String, String>): JsonObject = JsonObject(
        buildMap {
            for ((k, v) in o) {
                val full = table[k] ?: k
                if (k == full || full !in this) put(full, v)
            }
        },
    )
}
```

- [ ] **Step 7: Implement the parser**

`core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt`:

```kotlin
package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.kinds.AlarmPanelMapper
import com.gekkotron.oikos.state.kinds.ClimateMapper
import com.gekkotron.oikos.state.kinds.NumberMapper
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class DiscoveredEntity(val entity: Entity, val device: Device?)

sealed interface DiscoveryResult {
    data object NotDiscovery : DiscoveryResult
    data object Removed : DiscoveryResult
    data class Skipped(val reason: String) : DiscoveryResult
    data class Entities(val items: List<DiscoveredEntity>, val skipped: List<String> = emptyList()) : DiscoveryResult
}

object DiscoveryParser {
    private val KINDS = mapOf(
        "light" to Kind.LIGHT,
        "switch" to Kind.SWITCH,
        "cover" to Kind.COVER,
        "climate" to Kind.CLIMATE,
        "sensor" to Kind.SENSOR,
        "binary_sensor" to Kind.BINARY_SENSOR,
        "alarm_control_panel" to Kind.ALARM_PANEL,
        "number" to Kind.NUMBER,
        "select" to Kind.SELECT,
        "button" to Kind.BUTTON,
    )
    private val REQUIRES_COMMAND = setOf(Kind.SWITCH, Kind.LIGHT, Kind.NUMBER, Kind.SELECT, Kind.BUTTON, Kind.ALARM_PANEL)
    private val REQUIRES_STATE = setOf(Kind.SENSOR, Kind.BINARY_SENSOR)
    private val LIGHT_SCHEMAS = setOf("default", "basic", "json")

    private sealed interface Built {
        data class Ok(val item: DiscoveredEntity) : Built
        data class Bad(val reason: String) : Built
    }

    fun parse(prefix: String, topic: String, payload: String): DiscoveryResult {
        if (!topic.startsWith("$prefix/")) return DiscoveryResult.NotDiscovery
        val parts = topic.removePrefix("$prefix/").split('/')
        if (parts.size !in 3..4 || parts.last() != "config") return DiscoveryResult.NotDiscovery
        val component = parts[0]
        val nodeId = if (parts.size == 4) parts[1] else null
        val objectId = parts[parts.size - 2]
        if (payload.isBlank()) return DiscoveryResult.Removed

        val raw = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return DiscoveryResult.Skipped("payload is not a JSON object")
        val config = Abbreviations.expand(raw)

        if (component == "device") return parseDeviceBased(config, objectId)
        return when (val built = build(component, expandBaseTopic(config), nodeId, objectId)) {
            is Built.Ok -> DiscoveryResult.Entities(listOf(built.item))
            is Built.Bad -> DiscoveryResult.Skipped(built.reason)
        }
    }

    private fun parseDeviceBased(config: JsonObject, objectId: String): DiscoveryResult {
        val components = config["components"] as? JsonObject
            ?: return DiscoveryResult.Skipped("device discovery without 'cmps'")
        val shared = config - "components"
        val items = mutableListOf<DiscoveredEntity>()
        val skipped = mutableListOf<String>()
        for ((componentId, element) in components) {
            val c = element as? JsonObject
            if (c == null) {
                skipped += "$componentId: not an object"
                continue
            }
            val platform = (c["platform"] as? JsonPrimitive)?.contentOrNull
            if (platform == null) {
                skipped += "$componentId: missing platform"
                continue
            }
            if (c.keys == setOf("platform")) continue // removal marker for this component
            val merged = expandBaseTopic(JsonObject(shared + c - "platform"))
            when (val built = build(platform, merged, objectId, componentId)) {
                is Built.Ok -> items += built.item
                is Built.Bad -> skipped += "$componentId: ${built.reason}"
            }
        }
        return if (items.isEmpty() && skipped.isNotEmpty()) DiscoveryResult.Skipped(skipped.joinToString("; "))
        else DiscoveryResult.Entities(items, skipped)
    }

    private fun build(component: String, config: JsonObject, nodeId: String?, objectId: String): Built {
        val kind = KINDS[component] ?: return Built.Bad("unsupported component '$component'")
        val b = MqttBindings(component, config)
        if (kind == Kind.LIGHT) {
            val schema = b.str("schema") ?: "default"
            if (schema !in LIGHT_SCHEMAS) return Built.Bad("light schema '$schema' unsupported")
        }
        if (kind in REQUIRES_COMMAND && b.str("command_topic") == null) return Built.Bad("missing command_topic")
        if (kind in REQUIRES_STATE && b.str("state_topic") == null) return Built.Bad("missing state_topic")
        if (kind == Kind.SELECT && b.strList("options").isNullOrEmpty()) return Built.Bad("select without options")

        val device = parseDevice(config["device"])
        val uniqueId = b.str("unique_id") ?: listOfNotNull(component, nodeId, objectId).joinToString("_")
        val entity = Entity(
            uniqueId = uniqueId,
            deviceId = device?.id,
            name = displayName(b.str("name"), device, objectId),
            kind = kind,
            deviceClass = b.str("device_class"),
            unit = b.str("unit_of_measurement"),
            icon = b.str("icon"),
            capabilities = capabilities(kind, b),
            bindings = b,
        )
        return Built.Ok(DiscoveredEntity(entity, device))
    }

    private fun displayName(entityName: String?, device: Device?, objectId: String): String = when {
        entityName == null -> device?.name ?: objectId
        device != null && !entityName.startsWith(device.name) -> "${device.name} $entityName"
        else -> entityName
    }

    private fun parseDevice(element: JsonElement?): Device? {
        val o = element as? JsonObject ?: return null
        val d = MqttBindings("device", o)
        val ids = when (val i = o["identifiers"]) {
            is JsonArray -> i.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(i.contentOrNull)
            else -> emptyList()
        }
        val connections = (o["connections"] as? JsonArray).orEmpty().mapNotNull { pair ->
            (pair as? JsonArray)?.joinToString(":") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        }
        val id = ids.firstOrNull() ?: connections.firstOrNull() ?: return null
        return Device(id, d.str("name") ?: id, d.str("manufacturer"), d.str("model"), d.str("suggested_area"))
    }

    private fun capabilities(kind: Kind, b: MqttBindings): Capabilities = when (kind) {
        Kind.LIGHT -> if (b.str("schema") == "json") {
            Capabilities(brightness = b.bool("brightness", false), colorModes = b.strList("supported_color_modes").orEmpty().toSet())
        } else {
            Capabilities(
                brightness = b.str("brightness_command_topic") != null,
                colorModes = if (b.str("rgb_command_topic") != null) setOf("rgb") else emptySet(),
            )
        }
        Kind.COVER -> Capabilities(position = b.str("set_position_topic") != null)
        Kind.CLIMATE -> Capabilities(
            hvacModes = ClimateMapper.modes(b),
            minTemp = b.double("min_temp") ?: ClimateMapper.DEFAULT_MIN_TEMP,
            maxTemp = b.double("max_temp") ?: ClimateMapper.DEFAULT_MAX_TEMP,
            tempStep = b.double("temp_step") ?: ClimateMapper.DEFAULT_TEMP_STEP,
        )
        Kind.NUMBER -> Capabilities(
            min = b.double("min") ?: NumberMapper.DEFAULT_MIN,
            max = b.double("max") ?: NumberMapper.DEFAULT_MAX,
            step = b.double("step") ?: NumberMapper.DEFAULT_STEP,
        )
        Kind.SELECT -> Capabilities(options = b.strList("options").orEmpty())
        Kind.ALARM_PANEL -> Capabilities(
            alarmModes = AlarmPanelMapper.supportedModes(b),
            codeArmRequired = AlarmPanelMapper.codeRequired(b, arming = true),
        )
        else -> Capabilities()
    }

    /** Resolves `~` in `*_topic` keys and `availability[].topic`, then drops `~`. */
    private fun expandBaseTopic(config: JsonObject): JsonObject {
        val base = (config["~"] as? JsonPrimitive)?.contentOrNull ?: return config
        fun fix(v: JsonElement): JsonElement {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return v
            return when {
                s.startsWith("~") -> JsonPrimitive(base + s.substring(1))
                s.endsWith("~") -> JsonPrimitive(s.dropLast(1) + base)
                else -> v
            }
        }
        return JsonObject(
            (config - "~").mapValues { (key, value) ->
                when {
                    key.endsWith("_topic") -> fix(value)
                    key == "availability" && value is JsonArray -> JsonArray(
                        value.map { item ->
                            (item as? JsonObject)?.let { o -> JsonObject(o.mapValues { (k, v) -> if (k == "topic") fix(v) else v }) } ?: item
                        },
                    )
                    else -> value
                }
            },
        )
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:source:test`
Expected: PASS (16 tests).

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/source/
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(source): HA discovery parser with abbreviations, base topic and device-based cmps" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 12: `MqttDiscoverySource`: wiring discovery, overrides, areas, states and commands

**Files:**
- Create: `core/source/src/main/kotlin/com/gekkotron/oikos/source/EntitySource.kt`, `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt`
- Modify: `README.md` (status + module list)
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt`

**Interfaces:**
- Consumes:
  - `DiscoveryParser`, `DiscoveryResult`, `DiscoveredEntity` (Task 11)
  - `StateStore`, `CommandTracker` (Tasks 9–10), `KindMappers`, `Encoded` (Tasks 5–8)
  - `MqttTransport`, `MqttMessage`, `PublishFailed`, `isValidPublishTopic`, `FakeMqttTransport` (Task 3)
  - `UiOverrides`, `OikosJson`, `AreasDocument`, `UNASSIGNED_AREA` (Task 1)
- Produces (consumed by the feature plans):

```kotlin
interface EntitySource {
    val entities: StateFlow<Map<String, Entity>>        // overrides applied: name, area (id), ui
    val devices: StateFlow<Map<String, Device>>
    val areas: StateFlow<List<Area>>                     // declared (by order, name), then implicit (by name), then Unassigned
    val commandFailures: SharedFlow<CommandFailure>
    fun states(): Flow<Map<String, EntityState>>
    suspend fun send(uniqueId: String, command: Command): CommandResult
    suspend fun setOverride(uniqueId: String, override: UiOverride)
    suspend fun setAreas(areas: List<Area>)
}

class MqttDiscoverySource(
    transport: MqttTransport,
    scope: CoroutineScope,
    prefix: String = MqttDiscoverySource.DEFAULT_PREFIX,     // "homeassistant"
    diagnostics: DiagnosticSink = DiagnosticSink.None,
    commandTimeout: Duration = 5.seconds,
    now: () -> Instant = Instant::now,
) : EntitySource
// companion: DEFAULT_PREFIX, UI_PREFIX = "oikos/ui/", AREAS_ID = "_areas", AREAS_TOPIC, SUBSCRIPTION_DEBOUNCE = 500.milliseconds
```

Behaviour (spec §4.3):
- **Subscriptions:** subscribe `{<prefix>/#, oikos/ui/#}` immediately. Every time the entity set changes, recompute the state topics (debounced 500 ms) and set `base + stateTopics`.
- **Messages:** a config topic goes to the parser; `oikos/ui/_areas` updates the areas; `oikos/ui/<id>` updates an override; everything else goes to the `StateStore`. A handler exception is reported as a diagnostic and never kills the collector.
- **Config changes:**
  - A config topic's entities are replaced as a whole on each message, so a re-paired device leaves no ghost.
  - A config that becomes invalid removes that topic's previous entities and reports the problem.
- **`send`:**
  - Rejects unknown or unavailable entities and unsupported commands.
  - Marks the entity pending **before** publishing, so an echo that arrives very fast cannot be overtaken.
  - On `PublishFailed`, it aborts silently and returns `Rejected`.
  - Entities without state feedback (no state topic, or `optimistic: true`) are confirmed right after the publish.
- **`setOverride`:** applies locally, then publishes the retained JSON. It rejects ids that are empty, equal to `_areas`, or contain `+`, `#` or NUL.

- [ ] **Step 1: Write the failing tests**

`core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt`:

```kotlin
package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MqttDiscoverySourceTest {
    private val lightTopic = "homeassistant/light/0x01/light/config"
    private val lightId = "0x01_light_zigbee2mqtt"
    private val lightConfig =
        """{"schema":"json","command_topic":"z2m/lampe/set","state_topic":"z2m/lampe","brightness":true,"unique_id":"$lightId","name":null,"device":{"identifiers":["z_lampe"],"name":"Lampe","suggested_area":"Salon"}}"""

    private fun switchConfig(id: String, extra: String = "") =
        """{"command_topic":"sw/$id/set","state_topic":"sw/$id","unique_id":"$id"$extra}"""

    private class Harness(
        val transport: FakeMqttTransport,
        val source: MqttDiscoverySource,
        val diagnostics: MutableList<Diagnostic>,
    )

    private fun TestScope.start(transport: FakeMqttTransport = FakeMqttTransport()): Harness {
        val diagnostics = mutableListOf<Diagnostic>()
        val source = MqttDiscoverySource(transport, backgroundScope, diagnostics = { diagnostics += it })
        runCurrent()
        return Harness(transport, source, diagnostics)
    }

    private fun TestScope.settle() {
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
    }

    private suspend fun Harness.state(id: String) = source.states().first().getValue(id)

    @Test
    fun `subscribes to discovery and overrides first`() = runTest {
        val h = start()
        assertEquals(setOf("homeassistant/#", "oikos/ui/#"), h.transport.subscriptionHistory.first())
    }

    @Test
    fun `retained config becomes an entity and its state topic is subscribed after the debounce`() = runTest {
        val transport = FakeMqttTransport()
        transport.deliver(lightTopic, lightConfig, retain = true)
        val h = start(transport)

        val light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe", light.name)
        assertEquals("Salon", light.area)
        assertEquals(setOf("homeassistant/#", "oikos/ui/#"), h.transport.subscriptions)

        settle()
        assertTrue("z2m/lampe" in h.transport.subscriptions)
        h.transport.deliver("z2m/lampe", """{"state":"ON","brightness":254}""")
        runCurrent()
        assertEquals(StateValue.Light(true, 254), h.state(lightId).value)
    }

    @Test
    fun `subscription updates are debounced`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        h.transport.deliver("homeassistant/switch/b/config", switchConfig("b"))
        h.transport.deliver("homeassistant/switch/c/config", switchConfig("c"))
        runCurrent()
        settle()
        assertEquals(2, h.transport.subscriptionHistory.size)
        assertTrue(h.transport.subscriptions.containsAll(setOf("sw/a", "sw/b", "sw/c")))
    }

    @Test
    fun `override name and area take precedence and can be cleared`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        h.transport.deliver("oikos/ui/$lightId", """{"name":"Lampe salon","area":"Cuisine","favorite":true}""")
        runCurrent()
        var light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe salon", light.name)
        assertEquals("Cuisine", light.area)
        assertTrue(light.ui.favorite)

        h.transport.deliver("oikos/ui/$lightId", "")
        runCurrent()
        light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe", light.name)
        assertEquals("Salon", light.area)
    }

    @Test
    fun `areas resolve by id or name and are ordered declared, implicit, unassigned`() = runTest {
        val h = start()
        h.transport.deliver("oikos/ui/_areas", """{"areas":[{"id":"garage","name":"Garage","order":2},{"id":"kitchen","name":"Cuisine","order":1}]}""")
        h.transport.deliver(lightTopic, lightConfig) // suggested_area Salon -> implicit
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        h.transport.deliver("homeassistant/switch/b/config", switchConfig("b"))
        h.transport.deliver("oikos/ui/a", """{"area":"Cuisine"}""")
        runCurrent()

        assertEquals("kitchen", h.source.entities.value.getValue("a").area)
        assertEquals(UNASSIGNED_AREA, h.source.entities.value.getValue("b").area)
        assertEquals(listOf("kitchen", "garage", "Salon", UNASSIGNED_AREA), h.source.areas.value.map { it.id })
    }

    @Test
    fun `empty config removes the entity and its state`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.deliver(lightTopic, "")
        runCurrent()
        assertFalse(lightId in h.source.entities.value)
        assertFalse(lightId in h.source.states().first())
    }

    // Review Focus 4
    @Test
    fun `config replaced under the same topic drops the old entity`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/plug/config", switchConfig("old"))
        runCurrent()
        h.transport.deliver("homeassistant/switch/plug/config", switchConfig("new"))
        runCurrent()
        assertEquals(setOf("new"), h.source.entities.value.keys)
    }

    // Review Focus 3
    @Test
    fun `malformed config does not disturb other entities`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        h.transport.deliver("homeassistant/switch/bad/config", "{oops")
        runCurrent()
        assertEquals(setOf(lightId), h.source.entities.value.keys)
        assertEquals(1, h.diagnostics.size)
        assertTrue("homeassistant/switch/bad/config" in h.diagnostics.single().message)

        h.transport.deliver("homeassistant/switch/bad/config", switchConfig("fixed"))
        runCurrent()
        assertEquals(setOf(lightId, "fixed"), h.source.entities.value.keys)
    }

    @Test
    fun `send publishes, marks pending, and a state message confirms`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        settle()
        h.transport.deliver("z2m/lampe", """{"state":"OFF"}""")
        runCurrent()

        assertEquals(CommandResult.Accepted, h.source.send(lightId, Command.TurnOn()))
        assertEquals("z2m/lampe/set" to """{"state":"ON"}""", h.transport.publishedStrings().last())
        assertTrue(h.state(lightId).pending)

        h.transport.deliver("z2m/lampe", """{"state":"ON"}""")
        runCurrent()
        assertFalse(h.state(lightId).pending)
        assertEquals(StateValue.Light(true, null), h.state(lightId).value)
    }

    @Test
    fun `unconfirmed command reverts and is reported`() = runTest {
        val h = start()
        val failures = mutableListOf<CommandFailure>()
        backgroundScope.launch { h.source.commandFailures.collect { failures += it } }
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        runCurrent()
        settle()
        h.transport.deliver("sw/a", "OFF")
        runCurrent()

        h.source.send("a", Command.TurnOn())
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(StateValue.OnOff(false), h.state("a").value)
        assertFalse(h.state("a").pending)
    }

    @Test
    fun `optimistic entity without state topic is confirmed immediately`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/o/config", """{"command_topic":"o/set","unique_id":"o"}""")
        runCurrent()
        h.source.send("o", Command.TurnOn())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(StateValue.OnOff(true), h.state("o").value)
        assertFalse(h.state("o").pending)
    }

    @Test
    fun `send rejects unknown, unavailable and unsupported`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/u/config", switchConfig("u", ""","availability_topic":"sw/u/avail""""))
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        assertIs<CommandResult.Rejected>(h.source.send("nope", Command.TurnOn()))
        assertEquals(CommandResult.Rejected("unavailable"), h.source.send("u", Command.TurnOn()))
        assertIs<CommandResult.Rejected>(h.source.send(lightId, Command.SetPosition(10)))
        assertTrue(h.transport.published.isEmpty())
    }

    @Test
    fun `publish failure is rejected and leaves no pending state`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.failPublishes = true
        assertIs<CommandResult.Rejected>(h.source.send(lightId, Command.TurnOn()))
        assertFalse(h.state(lightId).pending)
        assertEquals(StateValue.Unknown, h.state(lightId).value)
    }

    @Test
    fun `setOverride applies locally and publishes retained JSON`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.source.setOverride(lightId, UiOverride(name = "X"))
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
        assertEquals(MqttMessage("oikos/ui/$lightId", """{"name":"X"}""".encodeToByteArray(), retained = true), h.transport.published.last())
        runCurrent()
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
    }

    @Test
    fun `setOverride rejects ids that cannot be topics`() = runTest {
        val h = start()
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("a#b", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("a+b", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("_areas", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("", UiOverride()) }
    }

    @Test
    fun `setAreas publishes the areas document`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("salon", "Salon", order = 1)))
        assertEquals("oikos/ui/_areas" to """{"areas":[{"id":"salon","name":"Salon","order":1}]}""", h.transport.publishedStrings().last())
        assertEquals(listOf("salon"), h.source.areas.value.map { it.id })
    }

    @Test
    fun `status and unknown topics are harmless`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/status", "online")
        h.transport.deliver("oikos/ui/x", "not json")
        runCurrent()
        assertTrue(h.source.entities.value.isEmpty())
        assertEquals(1, h.diagnostics.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:source:test --tests '*MqttDiscoverySourceTest*'`
Expected: FAIL, with compilation errors (`Unresolved reference: MqttDiscoverySource`).

- [ ] **Step 3: Implement the interface**

`core/source/src/main/kotlin/com/gekkotron/oikos/source/EntitySource.kt`:

```kotlin
package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.UiOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Where entities come from. v1: MQTT discovery; later: Home Assistant WebSocket (spec §4.3). */
interface EntitySource {
    /** After overrides are applied (name, area id, ui). */
    val entities: StateFlow<Map<String, Entity>>
    val devices: StateFlow<Map<String, Device>>

    /** Declared areas (by order, then name), then implicit ones (by name), then Unassigned if used. */
    val areas: StateFlow<List<Area>>

    /** Optimistic commands that were not confirmed in time (snackbar "Not confirmed — Retry"). */
    val commandFailures: SharedFlow<CommandFailure>

    fun states(): Flow<Map<String, EntityState>>

    suspend fun send(uniqueId: String, command: Command): CommandResult

    suspend fun setOverride(uniqueId: String, override: UiOverride)

    suspend fun setAreas(areas: List<Area>)
}
```

- [ ] **Step 4: Implement the source**

`core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt`:

```kotlin
package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.AreasDocument
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.OikosJson
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.model.UiOverrides
import com.gekkotron.oikos.source.discovery.DiscoveredEntity
import com.gekkotron.oikos.source.discovery.DiscoveryParser
import com.gekkotron.oikos.source.discovery.DiscoveryResult
import com.gekkotron.oikos.state.CommandTracker
import com.gekkotron.oikos.state.StateStore
import com.gekkotron.oikos.state.kinds.Encoded
import com.gekkotron.oikos.state.kinds.KindMappers
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.isValidPublishTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Home Assistant MQTT Discovery + `oikos/ui/…` overrides (spec §4.3). */
@OptIn(FlowPreview::class)
class MqttDiscoverySource(
    private val transport: MqttTransport,
    scope: CoroutineScope,
    private val prefix: String = DEFAULT_PREFIX,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
    commandTimeout: Duration = 5.seconds,
    now: () -> Instant = Instant::now,
) : EntitySource {
    private val store = StateStore(now, diagnostics)
    private val tracker = CommandTracker(store, scope, commandTimeout)

    private val lock = Any()
    private val discovered = linkedMapOf<String, List<DiscoveredEntity>>() // config topic -> entities
    private val overrides = mutableMapOf<String, UiOverride>()
    private var areasDocument = AreasDocument()

    private val _entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val entities: StateFlow<Map<String, Entity>> = _entities.asStateFlow()
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()
    private val _areas = MutableStateFlow<List<Area>>(emptyList())
    override val areas: StateFlow<List<Area>> = _areas.asStateFlow()
    override val commandFailures: SharedFlow<CommandFailure> = tracker.failures

    private val baseFilters = setOf("$prefix/#", "$UI_PREFIX#")
    private val stateTopics = MutableStateFlow<Set<String>>(emptySet())

    private class Snapshot(val entities: Map<String, Entity>, val devices: Map<String, Device>, val areas: List<Area>)

    init {
        // Undispatched so the collector is subscribed before any subscription can replay retained messages.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.messages().collect { handle(it) } }
        scope.launch {
            transport.setSubscriptions(baseFilters)
            var applied = emptySet<String>()
            stateTopics.debounce(SUBSCRIPTION_DEBOUNCE).collect { topics ->
                if (topics != applied) {
                    applied = topics
                    transport.setSubscriptions(baseFilters + topics)
                }
            }
        }
    }

    override fun states(): Flow<Map<String, EntityState>> = store.states

    override suspend fun send(uniqueId: String, command: Command): CommandResult {
        val entity = _entities.value[uniqueId] ?: return CommandResult.Rejected("unknown entity $uniqueId")
        val bindings = entity.bindings as? MqttBindings ?: return CommandResult.Rejected("not an MQTT entity")
        val state = store.states.value[uniqueId]
        if (state != null && !state.available) return CommandResult.Rejected("unavailable")
        val mapper = KindMappers.forKind(entity.kind)
        val previous = state?.value ?: StateValue.Unknown
        val publish = when (val encoded = mapper.encode(bindings, command, previous)) {
            is Encoded.NotSupported -> return CommandResult.Rejected(encoded.reason)
            is Encoded.Publish -> encoded
        }
        val hasFeedback = mapper.stateTopics(bindings).isNotEmpty() && !bindings.bool("optimistic", false)
        tracker.track(uniqueId, command, mapper.predict(command, previous), hasFeedback)
        try {
            for (m in publish.messages) transport.publish(m.topic, m.payload.encodeToByteArray(), m.qos, m.retain)
        } catch (e: PublishFailed) {
            tracker.abort(uniqueId)
            return CommandResult.Rejected(e.message ?: "publish failed")
        }
        if (!hasFeedback) store.confirmPending(uniqueId)
        return CommandResult.Accepted
    }

    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        require(uniqueId.isNotEmpty() && uniqueId != AREAS_ID && isValidPublishTopic(UI_PREFIX + uniqueId)) {
            "unique_id '$uniqueId' cannot be used in an override topic"
        }
        synchronized(lock) { overrides[uniqueId] = override }
        publishEntities()
        transport.publish(UI_PREFIX + uniqueId, UiOverrides.encode(override).encodeToByteArray(), qos = 1, retain = true)
    }

    override suspend fun setAreas(areas: List<Area>) {
        val document = AreasDocument(areas)
        synchronized(lock) { areasDocument = document }
        publishEntities()
        val json = OikosJson.encodeToString(AreasDocument.serializer(), document)
        transport.publish(AREAS_TOPIC, json.encodeToByteArray(), qos = 1, retain = true)
    }

    private fun handle(message: MqttMessage) {
        val topic = message.topic
        try {
            val payload = message.payloadString()
            when {
                topic == AREAS_TOPIC -> onAreas(payload)
                topic.startsWith(UI_PREFIX) -> onOverride(topic.removePrefix(UI_PREFIX), payload)
                else -> when (val r = DiscoveryParser.parse(prefix, topic, payload)) {
                    DiscoveryResult.NotDiscovery -> store.onMessage(topic, payload)
                    DiscoveryResult.Removed -> replaceDiscovered(topic, null)
                    is DiscoveryResult.Skipped -> {
                        report("$topic: ${r.reason}")
                        replaceDiscovered(topic, null)
                    }
                    is DiscoveryResult.Entities -> {
                        r.skipped.forEach { report("$topic: $it") }
                        replaceDiscovered(topic, r.items)
                    }
                }
            }
        } catch (e: Exception) {
            report("unexpected error handling $topic: $e")
        }
    }

    private fun onAreas(payload: String) {
        val document = if (payload.isEmpty()) {
            AreasDocument()
        } else {
            runCatching { OikosJson.decodeFromString(AreasDocument.serializer(), payload) }.getOrNull()
                ?: return report("$AREAS_TOPIC: invalid areas document")
        }
        synchronized(lock) { areasDocument = document }
        publishEntities()
    }

    private fun onOverride(uniqueId: String, payload: String) {
        if (payload.isEmpty()) {
            synchronized(lock) { overrides.remove(uniqueId) }
        } else {
            val override = UiOverrides.decode(payload) ?: return report("$UI_PREFIX$uniqueId: invalid override")
            synchronized(lock) { overrides[uniqueId] = override }
        }
        publishEntities()
    }

    private fun replaceDiscovered(topic: String, items: List<DiscoveredEntity>?) {
        synchronized(lock) {
            if (items == null) discovered.remove(topic) else discovered[topic] = items
        }
        publishEntities()
    }

    private fun publishEntities() {
        val snapshot = synchronized(lock) {
            val all = discovered.values.flatten()
            val declared = areasDocument.areas
            val entities = all.associate { d ->
                val ui = overrides[d.entity.uniqueId] ?: UiOverride()
                val area = resolveArea(ui.area ?: d.device?.suggestedArea ?: UNASSIGNED_AREA, declared)
                d.entity.uniqueId to d.entity.copy(name = ui.name ?: d.entity.name, area = area, ui = ui)
            }
            Snapshot(entities, all.mapNotNull { it.device }.associateBy { it.id }, computeAreas(declared, entities.values))
        }
        store.setEntities(snapshot.entities.values)
        _entities.value = snapshot.entities
        _devices.value = snapshot.devices
        _areas.value = snapshot.areas
        stateTopics.value = store.subscriptionTopics()
    }

    private fun resolveArea(raw: String, declared: List<Area>): String =
        declared.firstOrNull { it.id == raw || it.name == raw }?.id ?: raw

    private fun computeAreas(declared: List<Area>, entities: Collection<Entity>): List<Area> {
        val used = entities.mapTo(mutableSetOf()) { it.area }
        val declaredIds = declared.mapTo(mutableSetOf()) { it.id }
        val ordered = declared.sortedWith(compareBy<Area>({ it.order ?: Int.MAX_VALUE }, { it.name }))
        val implicit = (used - declaredIds - UNASSIGNED_AREA).sorted().map { Area(it, it) }
        val unassigned = if (UNASSIGNED_AREA in used && UNASSIGNED_AREA !in declaredIds) listOf(Area(UNASSIGNED_AREA, UNASSIGNED_AREA)) else emptyList()
        return ordered + implicit + unassigned
    }

    private fun report(message: String) = diagnostics.report(Diagnostic("discovery", message))

    companion object {
        const val DEFAULT_PREFIX = "homeassistant"
        const val UI_PREFIX = "oikos/ui/"
        const val AREAS_ID = "_areas"
        const val AREAS_TOPIC = UI_PREFIX + AREAS_ID
        val SUBSCRIPTION_DEBOUNCE: Duration = 500.milliseconds
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:source:test`
Expected: PASS (all 16 parser tests + 17 source tests).

- [ ] **Step 6: Update the README**

In `README.md`, replace the line

```markdown
> **Status:** design phase. See the [design spec](docs/superpowers/specs/2026-09-24-oikos-design.md) and [mockups](docs/design/mockups/).
```

with

```markdown
> **Status:** foundation in progress. The pure-Kotlin core (discovery, templates, state, crypto) is built and tested; transports and UI come next. See the [design spec](docs/superpowers/specs/2026-09-24-oikos-design.md) and [mockups](docs/design/mockups/).

## Modules

| Module | Role |
|---|---|
| `:core:model` | Domain types: `Entity`, `StateValue`, `Command`, `UiOverride`, `Area` |
| `:core:crypto` | AES-256-GCM envelope for Athena-HttpMqttGateway |
| `:core:transport` | `MqttTransport` contract and topic matching (+ a test fake) |
| `:core:state` | Jinja-subset templates, one mapper per kind, `StateStore`, optimistic `CommandTracker` |
| `:core:source` | HA MQTT Discovery parser and `MqttDiscoverySource` |

Build and test: `./gradlew check koverXmlReport`.
```

- [ ] **Step 7: Full verification**

Run: `./gradlew clean check koverXmlReport`
Expected: BUILD SUCCESSFUL, with every module's tests passing and `build/reports/kover/report.xml` present.

Run: `grep -c '<class ' build/reports/kover/report.xml`
Expected: a non-zero count, so all five modules are in the merged report.

- [ ] **Step 8: Commit**

```bash
git add core/source/ README.md
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(source): MqttDiscoverySource with overrides, areas, debounced subscriptions and optimistic send" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes (for the executor)

- **Spec coverage in this plan:**
  - §4.1 core modules model, crypto, transport (interface), state, source
  - §4.2 domain, §4.3 `EntitySource` + discovery + overrides + areas + debounce
  - §4.4 templates, kinds, optimistic flow; §4.5 interface; §4.6 crypto
  - §9 build, Kover, Codecov, CI
  - §10 tests for crypto, source, state
- **Deferred to later plans:**
  - Direct/Gateway transports and `TransportSelector` (§4.5)
  - DataStore/SecretStore (§4.7) and push (§4.8)
  - All UI (§5), the e2e fixture, bridge and Mqtt2FCMPush
- **Known deviations, all additive and listed under "Design decisions locked here":**
  - `Entity.area`/`ui`
  - nullable `lastUpdate`
  - `EntitySource.commandFailures`/`setAreas`
- If a pinned version fails to resolve (for example the Kover or JUnit artifacts), bump only that entry in `gradle/libs.versions.toml` to the newest stable release on Maven Central and note it in the commit message. Do not change the APIs used.
