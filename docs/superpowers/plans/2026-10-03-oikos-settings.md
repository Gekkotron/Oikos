# Oikos Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Settings screen opened from a gear on Home: per-phone theme, shared room order, connection summary with "Set up again" behind a confirmation, and About with open-source licences.

**Architecture:** A new `:feature:settings` module holds three screens (Settings, Room order, Licences) and their ViewModels. Theme is stored per phone in a new `AppPreferences` DataStore in `:core:data` and applied by `MainActivity` through `AppViewModel`. Room order reuses the existing shared `oikos/ui/_areas` document through `EntitySource.setAreas`. The route picker and radio row move to `:core:designsystem` so Home's link sheet and Settings share them.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Jetpack Compose (BOM 2026.06.01), Navigation 3, Hilt, DataStore 1.2.1 + kotlinx-serialization, `sh.calvin.reorderable:reorderable:3.1.0`, AboutLibraries 15.2.0 (`com.mikepenz.aboutlibraries.plugin.android` + `com.mikepenz:aboutlibraries-core`), JUnit 4 + Robolectric 4.17 host tests.

**Spec:** `docs/superpowers/specs/2026-10-02-oikos-settings-design.md`

## Global Constraints

- Gradle runs with the sandbox disabled (`dangerouslyDisableSandbox: true`), or `~/.gradle` writes fail.
- Every commit uses `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …` and ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Every new user-facing string has an English (`values/`) and a French (`values-fr/`) entry.
- The password and the gateway key are never shown in Settings, never stored in UI state, never logged.
- Theme is per phone (DataStore file `datastore/app-preferences.json`), never published; "Set up again" (`ProfileRepository.clear()`) does not touch it.
- Room order is shared: only `EntitySource.setAreas` writes it; "Unassigned" (`UNASSIGNED_AREA`) is never written to the areas document and is always shown last.
- Touch targets ≥ `OikosDimens.minTouch`; radio groups use `selectableGroup()` + `Role.RadioButton`.
- Module convention: `oikos.android.library`, `oikos.android.compose`, `oikos.hilt`; tests are JUnit 4 + Robolectric with `@Config(qualifiers = "w411dp-h914dp")` for Compose.
- `./gradlew check` must be green at the end. Known flaky on master: `OnboardingViewModelTest`, `DirectConnectionTesterTest` (pass alone).

## Review Focus

1. **A failed reorder publish whose rollback leaves `source.areas` unchanged**: the `StateFlow` does not re-emit, so the dragged list must still snap back (Task 6, `SaveFailed` resets the local list; test in Task 6).
2. **The room list changes while the Rooms screen is open** (a device is moved to a new room on another phone): a later drop must not drop the new room. `RoomOrder.apply` appends rooms missing from `newOrder` and ignores ids no longer present (tests in Task 5).
3. **Theme file missing, corrupt, or holding an unknown name** (written by a newer app): the app starts in System, never crashes (tests in Task 1).
4. **Settings stays open across "Set up again" or while the profile is Locked/None**: the Connection section shows nothing instead of crashing, and the gear cannot push Settings twice (tests in Tasks 3 and 8).
5. **Secrets reaching the screen**: `ConnectionSummary` for a profile with password and gateway key contains neither, in fields or `toString()` (test in Task 3).

---

## File Structure

Create:
- `core/data/src/main/kotlin/com/gekkotron/oikos/data/AppPreferences.kt` — per-phone preferences store (theme).
- `core/data/src/test/kotlin/com/gekkotron/oikos/data/AppPreferencesTest.kt`
- `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/RadioRows.kt` — `OikosRadioRow`, `RoutePicker`.
- `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/components/RoutePickerTest.kt`
- `feature/settings/build.gradle.kts`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/ConnectionSummary.kt`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModel.kt`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/AppVersion.kt`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/SettingsScreen.kt`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrder.kt`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrderViewModel.kt`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrderScreen.kt`
- `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/licences/LicencesScreen.kt`
- `feature/settings/src/main/res/values/strings.xml`, `feature/settings/src/main/res/values-fr/strings.xml`
- `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/…` (tests listed per task)
- `app/config/libraries/inter.json`, `app/config/libraries/fraunces.json`, `app/config/licenses/OFL-1.1.json`
- `app/src/test/kotlin/com/gekkotron/oikos/LicencesResourceTest.kt`

Modify:
- `settings.gradle.kts` (include `:feature:settings`), `gradle/libs.versions.toml`, `build-logic/convention/build.gradle.kts`, `app/build.gradle.kts`
- `app/src/main/kotlin/com/gekkotron/oikos/di/DataModule.kt`, `app/src/test/kotlin/com/gekkotron/oikos/TestDataModule.kt`
- `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt`, `MainActivity.kt`, `navigation/OikosNavHost.kt`
- `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/StaticIcon.kt` (GEAR, DRAG_HANDLE glyphs), designsystem strings
- `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheet.kt`, `HomeScreen.kt`, `RoomPicker.kt`, home strings
- Tests: `feature/home/src/test/.../ui/LinkSheetTest.kt`, `app/src/test/.../MainActivityTest.kt`, `core/designsystem/src/test/.../icons/OikosGlyphTest.kt`
- Spec: `docs/superpowers/specs/2026-10-02-oikos-settings-design.md` (Task 9)

---

### Task 1: `AppPreferences` (per-phone theme store)

**Files:**
- Create: `core/data/src/main/kotlin/com/gekkotron/oikos/data/AppPreferences.kt`
- Create: `core/data/src/test/kotlin/com/gekkotron/oikos/data/AppPreferencesTest.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/di/DataModule.kt`
- Modify: `app/src/test/kotlin/com/gekkotron/oikos/TestDataModule.kt`

**Interfaces:**
- Produces: `enum class ThemePreference { SYSTEM, NIGHT_GLASS, WARM_MINIMAL }`; `class AppPreferences { val theme: StateFlow<ThemePreference?>; suspend fun setTheme(theme: ThemePreference) }` (null = not loaded yet); `AppPreferences.create(context: Context, scope: CoroutineScope)`, `AppPreferences.create(file: File, scope: CoroutineScope)`; `AppPreferences.FILE = "datastore/app-preferences.json"`. Hilt `@Singleton` binding in `DataModule` and `TestDataModule`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.gekkotron.oikos.data

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class AppPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String = "prefs.json") = tmp.root.resolve(name)
    private fun TestScope.prefs(f: File = file()) = AppPreferences.create(f, backgroundScope)
    private suspend fun AppPreferences.loaded() = theme.filterNotNull().first()

    @Test
    fun `missing file is System`() = runTest {
        assertEquals(ThemePreference.SYSTEM, prefs().loaded())
    }

    @Test
    fun `theme round-trips`() = runTest {
        val p = prefs()
        p.setTheme(ThemePreference.WARM_MINIMAL)
        assertEquals(ThemePreference.WARM_MINIMAL, p.theme.first { it == ThemePreference.WARM_MINIMAL })
    }

    @Test
    fun `a corrupt file falls back to System`() = runTest {
        val f = file().apply { writeText("{not json") }
        assertEquals(ThemePreference.SYSTEM, prefs(f).loaded())
    }

    @Test
    fun `an unknown theme name from a newer app is System`() = runTest {
        val f = file().apply { writeText("""{"theme":"OLED_BLACK"}""") }
        assertEquals(ThemePreference.SYSTEM, prefs(f).loaded())
    }

    @Test
    fun `clearing the profile keeps the theme`() = runTest {
        val p = prefs()
        p.setTheme(ThemePreference.NIGHT_GLASS)
        val profiles = ProfileRepository.create(file("profile.json"), FakeSecretStore(), backgroundScope)
        profiles.save(ConnectionProfile.Demo)
        profiles.clear()
        assertEquals(ThemePreference.NIGHT_GLASS, p.theme.first { it == ThemePreference.NIGHT_GLASS })
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*AppPreferencesTest*'`
Expected: FAIL, compilation error "Unresolved reference: AppPreferences".

- [ ] **Step 3: Implement**

```kotlin
package com.gekkotron.oikos.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The theme the user picked on this phone (settings spec §3). Mirrors `ThemeChoice` without depending on the design system. */
enum class ThemePreference { SYSTEM, NIGHT_GLASS, WARM_MINIMAL }

/** The theme is kept as a name so a value written by a newer app decodes as System instead of corrupting the file. */
@Serializable
internal data class StoredPreferences(val theme: String = ThemePreference.SYSTEM.name)

/** Per-phone preferences, kept apart from the profile so "Set up again" leaves them alone. */
class AppPreferences internal constructor(private val store: DataStore<StoredPreferences>, scope: CoroutineScope) {
    /** null until the file has been read. */
    val theme: StateFlow<ThemePreference?> = store.data
        .map { stored -> ThemePreference.entries.firstOrNull { it.name == stored.theme } ?: ThemePreference.SYSTEM }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setTheme(theme: ThemePreference) {
        store.updateData { it.copy(theme = theme.name) }
    }

    companion object {
        const val FILE = "datastore/app-preferences.json"

        fun create(context: Context, scope: CoroutineScope): AppPreferences = create(context.filesDir.resolve(FILE), scope)

        fun create(file: File, scope: CoroutineScope): AppPreferences {
            val store = DataStoreFactory.create(
                serializer = PreferencesSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredPreferences() },
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { file },
            )
            return AppPreferences(store, scope)
        }
    }
}

internal object PreferencesSerializer : Serializer<StoredPreferences> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: StoredPreferences = StoredPreferences()

    override suspend fun readFrom(input: InputStream): StoredPreferences = try {
        json.decodeFromString(StoredPreferences.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable preferences", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable preferences", e)
    }

    override suspend fun writeTo(t: StoredPreferences, output: OutputStream) {
        output.write(json.encodeToString(StoredPreferences.serializer(), t).encodeToByteArray())
    }
}
```

Add to `DataModule` (after `profiles`):

```kotlin
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): AppPreferences =
        AppPreferences.create(context, scope)
```

Add to `TestDataModule` (each test gets its own file, like the profile):

```kotlin
    @Provides
    @Singleton
    fun preferences(@ApplicationScope scope: CoroutineScope): AppPreferences =
        AppPreferences.create(Files.createTempDirectory("oikos").resolve("app-preferences.json").toFile(), scope)
```

(import `com.gekkotron.oikos.data.AppPreferences` in both.)

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*AppPreferencesTest*' :app:compileDebugUnitTestKotlin`
Expected: PASS (5 tests), app test sources compile.

- [ ] **Step 5: Commit**

```bash
git add core/data app/src/main/kotlin/com/gekkotron/oikos/di/DataModule.kt app/src/test/kotlin/com/gekkotron/oikos/TestDataModule.kt
git commit -m "feat(data): per-phone AppPreferences store for the theme"
```

---

### Task 2: Shared radio row, route picker and new glyphs in the design system

**Files:**
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/RadioRows.kt`
- Create: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/components/RoutePickerTest.kt`
- Modify: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/StaticIcon.kt`
- Modify: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/OikosGlyphTest.kt`
- Modify: `core/designsystem/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheet.kt` (use the shared `RoutePicker`, delete the private one)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/RoomPicker.kt` (use `OikosRadioRow`, delete `RadioRow`)
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml` (delete `link_route_*`)

**Interfaces:**
- Produces: `@Composable fun OikosRadioRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`; `@Composable fun RoutePicker(selected: Route, onSelect: (Route) -> Unit, modifier: Modifier = Modifier)` (title "Connection", options Automatic / Home network / Gateway); `OikosGlyph.GEAR`, `OikosGlyph.DRAG_HANDLE`.

- [ ] **Step 1: Write the failing tests**

`RoutePickerTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.transport.Route
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RoutePickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the selected route and reports a pick`() {
        val picked = mutableListOf<Route>()
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { RoutePicker(Route.AUTO, onSelect = { picked += it }) } }
        compose.onNodeWithText("Connection").assertExists()
        compose.onNodeWithText("Automatic").assertIsSelected()
        compose.onNodeWithText("Gateway").performClick()
        assertEquals(listOf(Route.GATEWAY), picked)
    }
}
```

In `OikosGlyphTest`, add `OikosGlyph.GEAR, OikosGlyph.DRAG_HANDLE` to the list in `energy glyphs parse to non empty paths` (rename the test to `glyphs parse to non empty paths`).

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*RoutePickerTest*' --tests '*OikosGlyphTest*'`
Expected: FAIL, "Unresolved reference: RoutePicker" / "GEAR".

- [ ] **Step 3: Implement**

Glyphs, appended before the `;` in `OikosGlyph`:

```kotlin
    GEAR(listOf(area("M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"), line("M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0z"))),
    DRAG_HANDLE(listOf(line("M5 9h14M5 15h14"))),
```

`RadioRows.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.transport.Route

/** One option of a radio group; the whole row is the touch target. */
@Composable
fun OikosRadioRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = OikosTheme.type.body)
    }
}

/** How to reach a profile that has a gateway (gateway spec §4); shared by the link sheet and Settings. */
@Composable
fun RoutePicker(selected: Route, onSelect: (Route) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.route_title), style = OikosTheme.type.label, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            OikosRadioRow(stringResource(R.string.route_auto), selected == Route.AUTO, { onSelect(Route.AUTO) })
            OikosRadioRow(stringResource(R.string.route_direct), selected == Route.DIRECT, { onSelect(Route.DIRECT) })
            OikosRadioRow(stringResource(R.string.route_gateway), selected == Route.GATEWAY, { onSelect(Route.GATEWAY) })
        }
    }
}
```

(`OikosDimens` is `com.gekkotron.oikos.designsystem.OikosDimens`.)

Design-system strings — `values/strings.xml`:

```xml
    <string name="route_title">Connection</string>
    <string name="route_auto">Automatic</string>
    <string name="route_direct">Home network</string>
    <string name="route_gateway">Gateway</string>
```

`values-fr/strings.xml`:

```xml
    <string name="route_title">Connexion</string>
    <string name="route_auto">Automatique</string>
    <string name="route_direct">Réseau local</string>
    <string name="route_gateway">Passerelle</string>
```

In `LinkSheet.kt`: delete the private `RoutePicker`, import `com.gekkotron.oikos.designsystem.components.RoutePicker`; the call `if (route != null) RoutePicker(route, onRouteChange)` is unchanged. Delete `link_route_title`, `link_route_auto`, `link_route_direct`, `link_route_gateway` from both home string files.

In `RoomPicker.kt`: delete `internal fun RadioRow`, replace its call sites with `OikosRadioRow(label, selected, onClick)` (import from `com.gekkotron.oikos.designsystem.components`). Run `grep -rn "RadioRow(" feature/home/src` to find every call site.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:designsystem:testDebugUnitTest :feature:home:testDebugUnitTest`
Expected: PASS (existing `LinkSheetTest` and Rooms tests stay green).

- [ ] **Step 5: Commit**

```bash
git add core/designsystem feature/home
git commit -m "refactor(designsystem): shared radio row and route picker; gear and drag-handle glyphs"
```

---

### Task 3: `:feature:settings` module and `ConnectionSummary`

**Files:**
- Modify: `settings.gradle.kts` (add `include(":feature:settings")` after `:feature:security`)
- Create: `feature/settings/build.gradle.kts`
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/ConnectionSummary.kt`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/presentation/ConnectionSummaryTest.kt`

**Interfaces:**
- Produces: `sealed interface ConnectionSummary { data object Demo; data class Direct(val broker: String, val tls: Boolean, val ws: Boolean, val username: String?, val prefix: String, val gatewayUrl: String?) }`; `fun ConnectionSummary.Companion.from(state: ProfileState): ConnectionSummary?` (null for Loading / None / Locked).

- [ ] **Step 1: Create the module**

`feature/settings/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.settings"
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.GatewayConfig
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConnectionSummaryTest {
    private val key = ByteArray(GatewayConfig.KEY_BYTES) { 0x5a }
    private val direct = ConnectionProfile.Direct(
        LocalBroker("192.168.1.91", 8883, tls = true, username = "oikos", password = "hunter2-secret"),
        prefix = "homeassistant",
        gateway = GatewayConfig("https://gw.example", key, "192.168.1.91"),
    )

    @Test
    fun `direct profile with a gateway`() {
        assertEquals(
            ConnectionSummary.Direct("192.168.1.91:8883", tls = true, ws = false, username = "oikos", prefix = "homeassistant", gatewayUrl = "https://gw.example"),
            ConnectionSummary.from(ProfileState.Ready(direct)),
        )
    }

    @Test
    fun `direct profile without a gateway or user`() {
        val plain = ConnectionProfile.Direct(LocalBroker("mqtt.local"))
        assertEquals(
            ConnectionSummary.Direct("mqtt.local:1883", tls = false, ws = false, username = null, prefix = "homeassistant", gatewayUrl = null),
            ConnectionSummary.from(ProfileState.Ready(plain)),
        )
    }

    @Test
    fun `demo profile`() {
        assertEquals(ConnectionSummary.Demo, ConnectionSummary.from(ProfileState.Ready(ConnectionProfile.Demo)))
    }

    @Test
    fun `no profile, locked or loading has no summary`() {
        assertNull(ConnectionSummary.from(ProfileState.None))
        assertNull(ConnectionSummary.from(ProfileState.Locked))
        assertNull(ConnectionSummary.from(ProfileState.Loading))
    }

    @Test
    fun `the summary carries neither the password nor the key`() {
        val text = ConnectionSummary.from(ProfileState.Ready(direct)).toString()
        assertFalse("hunter2-secret" in text)
        assertFalse(key.joinToString("") { "%02x".format(it) } in text)
        assertFalse("5a5a" in text.lowercase())
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests '*ConnectionSummaryTest*'`
Expected: FAIL, "Unresolved reference: ConnectionSummary".

- [ ] **Step 4: Implement**

```kotlin
package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState

/** What Settings shows of the profile (settings spec §5.1). Built field by field so no secret can ride along. */
sealed interface ConnectionSummary {
    data object Demo : ConnectionSummary

    data class Direct(
        val broker: String,
        val tls: Boolean,
        val ws: Boolean,
        val username: String?,
        val prefix: String,
        val gatewayUrl: String?,
    ) : ConnectionSummary

    companion object {
        fun from(state: ProfileState): ConnectionSummary? = when (state) {
            is ProfileState.Ready -> when (val p = state.profile) {
                ConnectionProfile.Demo -> Demo
                is ConnectionProfile.Direct -> Direct(
                    broker = "${p.local.host}:${p.local.port}",
                    tls = p.local.tls,
                    ws = p.local.ws,
                    username = p.local.username,
                    prefix = p.prefix,
                    gatewayUrl = p.gateway?.url,
                )
            }
            ProfileState.Loading, ProfileState.None, ProfileState.Locked -> null
        }
    }
}
```

- [ ] **Step 5: Run the test**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests '*ConnectionSummaryTest*'`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts feature/settings
git commit -m "feat(settings): new module and a secret-free connection summary"
```

---

### Task 4: Settings screen and ViewModel (theme, rooms row, connection, about)

**Files:**
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModel.kt`
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/AppVersion.kt`
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/SettingsScreen.kt`
- Create: `feature/settings/src/main/res/values/strings.xml`, `feature/settings/src/main/res/values-fr/strings.xml`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModelTest.kt`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `AppPreferences`, `ThemePreference` (Task 1); `RoutePicker`, `OikosRadioRow`, `OikosGlyph` (Task 2); `ConnectionSummary` (Task 3); `ProfileRepository.state`.
- Produces:
  - `data class SettingsUi(val theme: ThemePreference = ThemePreference.SYSTEM, val connection: ConnectionSummary? = null)`
  - `class SettingsViewModel @Inject constructor(profiles: ProfileRepository, preferences: AppPreferences) { val state: StateFlow<SettingsUi>; fun setTheme(theme: ThemePreference) }`
  - `fun appVersion(context: Context): String` → `"0.12"` or `"0.12 · debug"`
  - `@Composable fun SettingsRoute(route: Route?, onRouteChange: (Route) -> Unit, onSetUpAgain: () -> Unit, onOpenRooms: () -> Unit, onOpenLicences: () -> Unit, onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel())`
  - `@Composable fun SettingsScreen(state: SettingsUi, version: String, route: Route?, onTheme: (ThemePreference) -> Unit, onRouteChange: (Route) -> Unit, onSetUpAgain: () -> Unit, onOpenRooms: () -> Unit, onOpenLicences: () -> Unit, onBack: () -> Unit)`
  - `internal fun SettingsTopBar(title: String, onBack: () -> Unit)` (reused by Tasks 6 and 7) and the string `settings_rooms` (Task 6 title). The gear's description string lives in `:feature:home` (Task 8).

- [ ] **Step 1: Strings**

`values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="settings_title">Settings</string>
    <string name="settings_back">Back</string>
    <string name="settings_theme">Theme</string>
    <string name="settings_theme_system">Follow the system</string>
    <string name="settings_theme_night">Night Glass</string>
    <string name="settings_theme_warm">Warm Minimal</string>
    <string name="settings_rooms">Rooms</string>
    <string name="settings_rooms_summary">Order of the room chips, room assignment</string>
    <string name="settings_connection">Connection</string>
    <string name="settings_demo">Demo house</string>
    <string name="settings_broker">Broker</string>
    <string name="settings_tls">TLS</string>
    <string name="settings_ws">WebSocket</string>
    <string name="settings_user">User</string>
    <string name="settings_prefix">Discovery prefix</string>
    <string name="settings_gateway">Gateway</string>
    <string name="settings_none">—</string>
    <string name="settings_set_up_again">Set up connection again</string>
    <string name="settings_set_up_again_title">Set up again?</string>
    <string name="settings_set_up_again_body">This forgets the connection on this phone. You\'ll need the setup QR code.</string>
    <string name="settings_set_up_again_confirm">Set up again</string>
    <string name="settings_cancel">Cancel</string>
    <string name="settings_about">About</string>
    <string name="settings_version">Oikos %1$s</string>
    <string name="settings_notice">Copyright 2026 Gekkotron. Oikos bundles the Inter and Fraunces typefaces, licensed under the SIL Open Font License 1.1.</string>
    <string name="settings_licences">Open-source licences</string>
</resources>
```

`values-fr/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="settings_title">Réglages</string>
    <string name="settings_back">Retour</string>
    <string name="settings_theme">Thème</string>
    <string name="settings_theme_system">Suivre le système</string>
    <string name="settings_theme_night">Night Glass</string>
    <string name="settings_theme_warm">Warm Minimal</string>
    <string name="settings_rooms">Pièces</string>
    <string name="settings_rooms_summary">Ordre des pièces, attribution des appareils</string>
    <string name="settings_connection">Connexion</string>
    <string name="settings_demo">Maison de démonstration</string>
    <string name="settings_broker">Broker</string>
    <string name="settings_tls">TLS</string>
    <string name="settings_ws">WebSocket</string>
    <string name="settings_user">Utilisateur</string>
    <string name="settings_prefix">Préfixe de découverte</string>
    <string name="settings_gateway">Passerelle</string>
    <string name="settings_none">—</string>
    <string name="settings_set_up_again">Reconfigurer la connexion</string>
    <string name="settings_set_up_again_title">Reconfigurer ?</string>
    <string name="settings_set_up_again_body">La connexion sera oubliée sur ce téléphone. Il faudra le QR code de configuration.</string>
    <string name="settings_set_up_again_confirm">Reconfigurer</string>
    <string name="settings_cancel">Annuler</string>
    <string name="settings_about">À propos</string>
    <string name="settings_version">Oikos %1$s</string>
    <string name="settings_notice">Copyright 2026 Gekkotron. Oikos inclut les polices Inter et Fraunces, sous licence SIL Open Font License 1.1.</string>
    <string name="settings_licences">Licences open source</string>
</resources>
```

- [ ] **Step 2: Write the failing tests**

`SettingsViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.data.ThemePreference
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private object PassThrough : SecretStore {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(cipher: ByteArray) = cipher.copyOf()
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state follows the theme and the profile`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile.json"), PassThrough, backgroundScope)
        val vm = SettingsViewModel(profiles, prefs)
        backgroundScope.launch { vm.state.collect {} }
        profiles.save(ConnectionProfile.Demo)
        assertEquals(ConnectionSummary.Demo, vm.state.first { it.connection != null }.connection)
        vm.setTheme(ThemePreference.WARM_MINIMAL)
        assertEquals(ThemePreference.WARM_MINIMAL, vm.state.first { it.theme == ThemePreference.WARM_MINIMAL }.theme)
    }

    @Test
    fun `a cleared profile leaves no connection summary`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile.json"), PassThrough, backgroundScope)
        val vm = SettingsViewModel(profiles, prefs)
        backgroundScope.launch { vm.state.collect {} }
        profiles.save(ConnectionProfile.Demo)
        vm.state.first { it.connection != null }
        profiles.clear()
        assertEquals(null, vm.state.first { it.connection == null }.connection)
    }
}
```

`SettingsScreenTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.settings.presentation.ConnectionSummary
import com.gekkotron.oikos.feature.settings.presentation.SettingsUi
import com.gekkotron.oikos.transport.Route
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val themes = mutableListOf<ThemePreference>()
    private var setUpAgain = 0
    private var rooms = 0
    private var licences = 0

    private val direct = ConnectionSummary.Direct("192.168.1.91:1883", tls = true, ws = false, username = "oikos", prefix = "homeassistant", gatewayUrl = "https://gw.example")

    private fun show(state: SettingsUi = SettingsUi(connection = direct), route: Route? = Route.AUTO) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            SettingsScreen(
                state, version = "0.12 · debug", route = route,
                onTheme = { themes += it }, onRouteChange = {}, onSetUpAgain = { setUpAgain++ },
                onOpenRooms = { rooms++ }, onOpenLicences = { licences++ }, onBack = {},
            )
        }
    }

    @Test
    fun `theme rows show the choice and report a pick`() {
        show()
        compose.onNodeWithText("Follow the system").assertIsSelected()
        compose.onNodeWithText("Warm Minimal").performClick()
        assertEquals(listOf(ThemePreference.WARM_MINIMAL), themes)
    }

    @Test
    fun `connection summary shows the broker and the gateway, and the route picker`() {
        show()
        compose.onNodeWithText("192.168.1.91:1883").performScrollTo().assertExists()
        compose.onNodeWithText("https://gw.example").performScrollTo().assertExists()
        compose.onNodeWithText("Automatic").performScrollTo().assertIsSelected()
    }

    @Test
    fun `no gateway, no route picker`() {
        show(route = null)
        compose.onNodeWithText("Automatic").assertDoesNotExist()
    }

    @Test
    fun `set up again asks first; cancel does nothing, confirm calls back once`() {
        show()
        compose.onNodeWithText("Set up connection again").performScrollTo().performClick()
        compose.onNodeWithText("Set up again?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, setUpAgain)
        compose.onNodeWithText("Set up connection again").performScrollTo().performClick()
        compose.onNodeWithText("Set up again").performClick()
        assertEquals(1, setUpAgain)
        compose.onNodeWithText("Set up again?").assertDoesNotExist()
    }

    @Test
    fun `rooms and licences rows navigate, version is shown`() {
        show()
        compose.onNodeWithText("Rooms").performScrollTo().performClick()
        compose.onNodeWithText("Open-source licences").performScrollTo().performClick()
        compose.onNodeWithText("Oikos 0.12 · debug").performScrollTo().assertExists()
        assertEquals(1, rooms)
        assertEquals(1, licences)
    }

    @Test
    fun `no profile shows no connection rows and does not crash`() {
        show(SettingsUi(connection = null), route = null)
        compose.onNodeWithText("Broker").assertDoesNotExist()
        compose.onNodeWithText("Set up connection again").performScrollTo().assertExists()
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: FAIL, "Unresolved reference: SettingsViewModel / SettingsScreen".

- [ ] **Step 4: Implement the ViewModel and version helper**

`SettingsViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(val theme: ThemePreference = ThemePreference.SYSTEM, val connection: ConnectionSummary? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    val state: StateFlow<SettingsUi> = combine(preferences.theme, profiles.state) { theme, profile ->
        SettingsUi(theme ?: ThemePreference.SYSTEM, ConnectionSummary.from(profile))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }
}
```

`AppVersion.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.presentation

import android.content.Context
import android.content.pm.ApplicationInfo

/** "0.12", or "0.12 · debug" on a debuggable build. Read from the package, so this module needs no BuildConfig. */
fun appVersion(context: Context): String {
    val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return if (debug) "$name · debug" else name
}
```

- [ ] **Step 5: Implement the screen**

`SettingsScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosRadioRow
import com.gekkotron.oikos.designsystem.components.RoutePicker
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.presentation.ConnectionSummary
import com.gekkotron.oikos.feature.settings.presentation.SettingsUi
import com.gekkotron.oikos.feature.settings.presentation.SettingsViewModel
import com.gekkotron.oikos.feature.settings.presentation.appVersion
import com.gekkotron.oikos.transport.Route

@Composable
fun SettingsRoute(
    route: Route?,
    onRouteChange: (Route) -> Unit,
    onSetUpAgain: () -> Unit,
    onOpenRooms: () -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    SettingsScreen(state, version, route, viewModel::setTheme, onRouteChange, onSetUpAgain, onOpenRooms, onOpenLicences, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUi,
    version: String,
    route: Route?,
    onTheme: (ThemePreference) -> Unit,
    onRouteChange: (Route) -> Unit,
    onSetUpAgain: () -> Unit,
    onOpenRooms: () -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        topBar = { SettingsTopBar(stringResource(R.string.settings_title), onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section(stringResource(R.string.settings_theme)) {
                Column(Modifier.selectableGroup()) {
                    OikosRadioRow(stringResource(R.string.settings_theme_system), state.theme == ThemePreference.SYSTEM, { onTheme(ThemePreference.SYSTEM) })
                    OikosRadioRow(stringResource(R.string.settings_theme_night), state.theme == ThemePreference.NIGHT_GLASS, { onTheme(ThemePreference.NIGHT_GLASS) })
                    OikosRadioRow(stringResource(R.string.settings_theme_warm), state.theme == ThemePreference.WARM_MINIMAL, { onTheme(ThemePreference.WARM_MINIMAL) })
                }
            }
            NavRow(stringResource(R.string.settings_rooms), stringResource(R.string.settings_rooms_summary), onOpenRooms)
            Section(stringResource(R.string.settings_connection)) {
                ConnectionRows(state.connection)
                if (route != null) RoutePicker(route, onRouteChange, Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_set_up_again))
                }
            }
            Section(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_version, version), style = OikosTheme.type.body)
                Text(stringResource(R.string.settings_notice), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
                NavRow(stringResource(R.string.settings_licences), null, onOpenLicences)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_set_up_again_title)) },
            text = { Text(stringResource(R.string.settings_set_up_again_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onSetUpAgain() }) { Text(stringResource(R.string.settings_set_up_again_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** Shared by the three Settings screens: a back arrow and a title on the transparent background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.settings_back)
    TopAppBar(
        title = { Text(title, style = OikosTheme.type.display) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Text("←", style = OikosTheme.type.cardValue)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = OikosTheme.colors.content, navigationIconContentColor = OikosTheme.colors.content),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun NavRow(title: String, summary: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OikosTheme.type.body)
            if (summary != null) Text(summary, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
        Text("›", style = OikosTheme.type.cardValue, color = OikosTheme.colors.contentMuted)
    }
}

@Composable
private fun ConnectionRows(summary: ConnectionSummary?) {
    when (summary) {
        null -> Unit
        ConnectionSummary.Demo -> Text(stringResource(R.string.settings_demo), style = OikosTheme.type.body)
        is ConnectionSummary.Direct -> {
            val tags = listOfNotNull(
                stringResource(R.string.settings_tls).takeIf { summary.tls },
                stringResource(R.string.settings_ws).takeIf { summary.ws },
            )
            InfoRow(stringResource(R.string.settings_broker), summary.broker, tags.joinToString(" · ").ifEmpty { null })
            InfoRow(stringResource(R.string.settings_user), summary.username ?: stringResource(R.string.settings_none))
            InfoRow(stringResource(R.string.settings_prefix), summary.prefix)
            summary.gatewayUrl?.let { InfoRow(stringResource(R.string.settings_gateway), it) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, extra: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted, modifier = Modifier.width(140.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = OikosTheme.type.body)
            if (extra != null) Text(extra, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
    }
}
```

Notes for the implementer:
- `OikosType` has `display`, `cardName`, `cardValue`, `label`, `body`; `OikosDimens` is `com.gekkotron.oikos.designsystem.OikosDimens`. Remove imports the final code does not use.
- Replace the `"←"` text with `StaticIcon(OikosGlyph.…)` only if a back-arrow glyph exists; do not add one in this task.
- The `Set up again` dialog button text ("Set up again") differs from the row text ("Set up connection again") on purpose; the test relies on it.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: PASS (ConnectionSummary 5, SettingsViewModel 2, SettingsScreen 6).

- [ ] **Step 7: Commit**

```bash
git add feature/settings
git commit -m "feat(settings): Settings screen with theme, connection summary and a confirmed set-up-again"
```

---

### Task 5: `RoomOrder` and `RoomOrderViewModel`

**Files:**
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrder.kt`
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrderViewModel.kt`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/FakeEntitySource.kt`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrderTest.kt`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrderViewModelTest.kt`

**Interfaces:**
- Consumes: `EntitySource.areas: StateFlow<List<Area>>`, `EntitySource.setAreas(List<Area>)`, `PublishFailed`, `UNASSIGNED_AREA`.
- Produces:
  - `object RoomOrder { fun apply(areas: List<Area>, newOrder: List<String>): List<Area> }`
  - `data class RoomOrderUi(val rooms: List<Area> = emptyList(), val hasUnassigned: Boolean = false)`
  - `sealed interface RoomOrderEvent { data object SaveFailed }`
  - `class RoomOrderViewModel @Inject constructor(source: EntitySource) { val state: StateFlow<RoomOrderUi>; val events: Flow<RoomOrderEvent>; fun commit(order: List<String>); fun move(id: String, delta: Int) }`

- [ ] **Step 1: Write the fake and the failing tests**

`FakeEntitySource.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings

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

/** Mirrors MqttDiscoverySource.setAreas: optimistic update, rollback to the confirmed list on failure. */
class FakeEntitySource : EntitySource {
    override val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))
    override val entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val areas = MutableStateFlow<List<Area>>(emptyList())
    override val commandFailures = MutableSharedFlow<CommandFailure>()
    val published = mutableListOf<List<Area>>()
    var failWrites = false

    override fun states() = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    override suspend fun send(uniqueId: String, command: Command): CommandResult = CommandResult.Accepted
    override suspend fun setOverride(uniqueId: String, override: UiOverride) = error("not used")
    override suspend fun setAreas(areas: List<Area>) {
        if (failWrites) throw PublishFailed("offline")
        published += areas
        this.areas.value = areas.sortedBy { it.order ?: Int.MAX_VALUE }
    }
}
```

`RoomOrderTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.rooms

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import org.junit.Test
import kotlin.test.assertEquals

class RoomOrderTest {
    private val bath = Area("bath", "Bath", icon = "bath", order = 0)
    private val living = Area("living", "Living", order = 1)
    private val julien = Area("julien", "Julien") // implicit: computed, never declared
    private val unassigned = Area(UNASSIGNED_AREA, UNASSIGNED_AREA)

    @Test
    fun `reorders and numbers every room, keeping names and icons`() {
        assertEquals(
            listOf(Area("living", "Living", order = 0), Area("julien", "Julien", order = 1), Area("bath", "Bath", icon = "bath", order = 2)),
            RoomOrder.apply(listOf(bath, living, julien, unassigned), listOf("living", "julien", "bath")),
        )
    }

    @Test
    fun `unassigned is never written, even if asked`() {
        val out = RoomOrder.apply(listOf(bath, unassigned), listOf(UNASSIGNED_AREA, "bath"))
        assertEquals(listOf(Area("bath", "Bath", icon = "bath", order = 0)), out)
    }

    @Test
    fun `a room that appeared after the drag started is kept, after the ordered ones`() {
        val garage = Area("garage", "Garage")
        assertEquals(
            listOf(Area("living", "Living", order = 0), Area("bath", "Bath", icon = "bath", order = 1), Area("garage", "Garage", order = 2)),
            RoomOrder.apply(listOf(bath, living, garage), listOf("living", "bath")),
        )
    }

    @Test
    fun `an id that is gone is ignored`() {
        assertEquals(
            listOf(Area("bath", "Bath", icon = "bath", order = 0)),
            RoomOrder.apply(listOf(bath), listOf("office", "bath")),
        )
    }
}
```

`RoomOrderViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.rooms

import com.gekkotron.oikos.feature.settings.FakeEntitySource
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomOrderViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        areas.value = listOf(Area("bath", "Bath"), Area("living", "Living"), Area("office", "Office"), Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ids(vm: RoomOrderViewModel) = vm.state.value.rooms.map { it.id }

    @Test
    fun `unassigned is split off the reorderable list`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(listOf("bath", "living", "office"), ids(vm))
        assertTrue(vm.state.value.hasUnassigned)
    }

    @Test
    fun `a drop publishes once, an unchanged drop not at all`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        vm.commit(listOf("bath", "living", "office"))
        assertEquals(0, source.published.size)
        vm.commit(listOf("office", "bath", "living"))
        assertEquals(1, source.published.size)
        assertEquals(listOf("office", "bath", "living"), ids(vm))
    }

    @Test
    fun `move up and move down publish at once, and stop at the ends`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        vm.move("office", -1)
        assertEquals(listOf("bath", "office", "living"), ids(vm))
        vm.move("bath", -1)
        assertEquals(1, source.published.size)
    }

    @Test
    fun `a failed publish emits SaveFailed and keeps the old order`() = runTest(dispatcher) {
        val vm = RoomOrderViewModel(source)
        backgroundScope.launch { vm.state.collect {} }
        val events = mutableListOf<RoomOrderEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        source.failWrites = true
        vm.commit(listOf("living", "bath", "office"))
        assertEquals(listOf<RoomOrderEvent>(RoomOrderEvent.SaveFailed), events)
        assertEquals(listOf("bath", "living", "office"), ids(vm))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests '*RoomOrder*'`
Expected: FAIL, "Unresolved reference: RoomOrder".

- [ ] **Step 3: Implement**

`RoomOrder.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.rooms

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA

/** Builds the areas document for a new chip order (settings spec §4.1). */
object RoomOrder {
    /**
     * [areas] is `EntitySource.areas` (the computed list); [newOrder] the room ids as the user left them.
     * Ids no longer in [areas] are skipped, rooms of [areas] missing from [newOrder] (they appeared during the
     * drag) follow in their current order, and Unassigned is never written.
     */
    fun apply(areas: List<Area>, newOrder: List<String>): List<Area> {
        val rooms = areas.filter { it.id != UNASSIGNED_AREA }
        val byId = rooms.associateBy { it.id }
        val ordered = newOrder.distinct().mapNotNull(byId::get)
        val rest = rooms.filter { it !in ordered }
        return (ordered + rest).mapIndexed { index, area -> area.copy(order = index) }
    }
}
```

`RoomOrderViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.PublishFailed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoomOrderUi(val rooms: List<Area> = emptyList(), val hasUnassigned: Boolean = false)

sealed interface RoomOrderEvent {
    data object SaveFailed : RoomOrderEvent
}

@HiltViewModel
class RoomOrderViewModel @Inject constructor(private val source: EntitySource) : ViewModel() {
    val state: StateFlow<RoomOrderUi> = source.areas
        .map(::toUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), toUi(source.areas.value))

    private val _events = Channel<RoomOrderEvent>(Channel.BUFFERED)
    val events: Flow<RoomOrderEvent> = _events.receiveAsFlow()

    /** The list as the user dropped it; publishes once, and only if it changed. */
    fun commit(order: List<String>) {
        if (order == state.value.rooms.map { it.id }) return
        viewModelScope.launch {
            try {
                source.setAreas(RoomOrder.apply(source.areas.value, order))
            } catch (e: PublishFailed) {
                _events.send(RoomOrderEvent.SaveFailed)
            }
        }
    }

    /** TalkBack's Move up (-1) / Move down (+1). */
    fun move(id: String, delta: Int) {
        val ids = state.value.rooms.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        commit(ids)
    }

    private fun toUi(areas: List<Area>) = RoomOrderUi(areas.filter { it.id != UNASSIGNED_AREA }, areas.any { it.id == UNASSIGNED_AREA })
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests '*RoomOrder*'`
Expected: PASS (RoomOrder 4, RoomOrderViewModel 4).

- [ ] **Step 5: Commit**

```bash
git add feature/settings
git commit -m "feat(settings): room order model and ViewModel over the shared areas document"
```

---

### Task 6: Room order screen (drag handles, TalkBack actions, snap-back)

**Files:**
- Modify: `gradle/libs.versions.toml` (add `reorderable = "3.1.0"` and `reorderable = { module = "sh.calvin.reorderable:reorderable", version.ref = "reorderable" }`)
- Modify: `feature/settings/build.gradle.kts` (add `implementation(libs.reorderable)`)
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrderScreen.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/rooms/RoomOrderScreenTest.kt`

**Interfaces:**
- Consumes: `RoomOrderUi`, `RoomOrderEvent`, `RoomOrderViewModel` (Task 5); `SettingsTopBar` (Task 4); `OikosGlyph.DRAG_HANDLE` (Task 2).
- Produces: `@Composable fun RoomOrderRoute(onAssignRooms: () -> Unit, onBack: () -> Unit, viewModel: RoomOrderViewModel = hiltViewModel())`; `@Composable fun RoomOrderScreen(state: RoomOrderUi, onCommit: (List<String>) -> Unit, onMove: (String, Int) -> Unit, onAssignRooms: () -> Unit, onBack: () -> Unit, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }, resetKey: Int = 0)`. `resetKey` changes on `SaveFailed` so the local list snaps back to `state.rooms` even when `state` did not change.

- [ ] **Step 1: Strings**

`values/`:

```xml
    <string name="rooms_order_hint">Drag to change the order of the room chips on Home.</string>
    <string name="rooms_unassigned">Unassigned</string>
    <string name="rooms_drag">Drag to reorder %1$s</string>
    <string name="rooms_move_up">Move up</string>
    <string name="rooms_move_down">Move down</string>
    <string name="rooms_save_failed">Couldn\'t save the order. Check the connection.</string>
    <string name="rooms_assign">Assign rooms</string>
```

`values-fr/`:

```xml
    <string name="rooms_order_hint">Faites glisser pour changer l\'ordre des pièces sur l\'accueil.</string>
    <string name="rooms_unassigned">Non assigné</string>
    <string name="rooms_drag">Faire glisser %1$s</string>
    <string name="rooms_move_up">Monter</string>
    <string name="rooms_move_down">Descendre</string>
    <string name="rooms_save_failed">Impossible d\'enregistrer l\'ordre. Vérifiez la connexion.</string>
    <string name="rooms_assign">Attribuer les pièces</string>
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.gekkotron.oikos.feature.settings.rooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.model.Area
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RoomOrderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val rooms = listOf(Area("bath", "Bath"), Area("living", "Living"), Area("office", "Office"))
    private val moves = mutableListOf<Pair<String, Int>>()
    private var assign = 0

    @Test
    fun `rooms are listed with unassigned last and not draggable`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RoomOrderScreen(RoomOrderUi(rooms, hasUnassigned = true), {}, { _, _ -> }, {}, {}) }
        }
        compose.onNodeWithText("Bath").assertExists()
        compose.onNodeWithText("Unassigned").assertExists()
        compose.onNode(hasText("Unassigned") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertDoesNotExist()
    }

    @Test
    fun `talkback move actions, none past the ends`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RoomOrderScreen(RoomOrderUi(rooms), {}, { id, d -> moves += id to d }, {}, {}) }
        }
        compose.onNodeWithText("Living").performCustomAccessibilityActionWithLabel("Move up")
        compose.onNodeWithText("Living").performCustomAccessibilityActionWithLabel("Move down")
        assertEquals(listOf("living" to -1, "living" to 1), moves)
        compose.onNode(hasText("Bath") and SemanticsMatcher("has Move up") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == "Move up" }
        }).assertDoesNotExist()
    }

    @Test
    fun `assign rooms button calls back`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RoomOrderScreen(RoomOrderUi(rooms), {}, { _, _ -> }, { assign++ }, {}) }
        }
        compose.onNodeWithText("Assign rooms").performClick()
        assertEquals(1, assign)
    }

    @Test
    fun `a new reset key puts the list back to the state`() {
        var key by mutableIntStateOf(0)
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RoomOrderScreen(RoomOrderUi(rooms), {}, { _, _ -> }, {}, {}, resetKey = key) }
        }
        key = 1
        compose.waitForIdle()
        assertEquals(3, compose.onAllNodesWithText("Bath").fetchSemanticsNodes().size + compose.onAllNodesWithText("Living").fetchSemanticsNodes().size + compose.onAllNodesWithText("Office").fetchSemanticsNodes().size)
    }
}
```

(If `performCustomAccessibilityActionWithLabel` is not in this ui-test version, run the action directly: `compose.onNodeWithText("Living").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move up" }.action()` inside `compose.runOnIdle { … }`.)

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests '*RoomOrderScreenTest*'`
Expected: FAIL, "Unresolved reference: RoomOrderScreen".

- [ ] **Step 4: Implement**

```kotlin
package com.gekkotron.oikos.feature.settings.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.ui.SettingsTopBar
import com.gekkotron.oikos.model.Area
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun RoomOrderRoute(onAssignRooms: () -> Unit, onBack: () -> Unit, viewModel: RoomOrderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var resetKey by remember { mutableIntStateOf(0) }
    val failed = stringResource(R.string.rooms_save_failed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RoomOrderEvent.SaveFailed -> {
                    resetKey++ // the source rolled back to an equal list, so state alone would not re-emit
                    snackbar.showSnackbar(failed)
                }
            }
        }
    }
    RoomOrderScreen(state, viewModel::commit, viewModel::move, onAssignRooms, onBack, snackbar, resetKey)
}

@Composable
fun RoomOrderScreen(
    state: RoomOrderUi,
    onCommit: (List<String>) -> Unit,
    onMove: (String, Int) -> Unit,
    onAssignRooms: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    resetKey: Int = 0,
) {
    // The list the user is dragging; it follows the source whenever the source (or a failed save) moves on.
    var list by remember(state.rooms, resetKey) { mutableStateOf(state.rooms) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = list.indexOfFirst { it.id == from.key }
        val toIndex = list.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) list = list.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }
    val moveUp = stringResource(R.string.rooms_move_up)
    val moveDown = stringResource(R.string.rooms_move_down)
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        topBar = { SettingsTopBar(stringResource(R.string.settings_rooms), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hint") {
                Text(stringResource(R.string.rooms_order_hint), style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(list, key = { it.id }) { room ->
                val index = list.indexOf(room)
                ReorderableItem(reorderState, key = room.id) {
                    RoomRow(
                        room.name,
                        modifier = Modifier.semantics {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(moveUp) { onMove(room.id, -1); true }.takeIf { index > 0 },
                                CustomAccessibilityAction(moveDown) { onMove(room.id, 1); true }.takeIf { index < list.lastIndex },
                            )
                        },
                    ) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .draggableHandle(onDragStopped = { onCommit(list.map { it.id }) })
                                .clearAndSetSemantics {},
                        ) {
                            StaticIcon(OikosGlyph.DRAG_HANDLE, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
            if (state.hasUnassigned) {
                item(key = "unassigned") { RoomRow(stringResource(R.string.rooms_unassigned)) {} }
            }
            item(key = "assign") {
                OutlinedButton(onClick = onAssignRooms, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(stringResource(R.string.rooms_assign)) }
            }
        }
    }
}

@Composable
private fun RoomRow(name: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    OikosCard(modifier = modifier.fillMaxWidth(), minHeight = OikosDimens.minTouch) {
        Row(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = OikosTheme.type.body, modifier = Modifier.weight(1f))
            trailing()
        }
    }
}
```

Notes for the implementer:
- `draggableHandle` is a member of `ReorderableCollectionItemScope` (the `ReorderableItem` content lambda's receiver), so it must be called inside that lambda as written. If 3.1.0's signature differs, check the library's README for `draggableHandle(onDragStarted, onDragStopped)`.
- `OikosCard(modifier, …, onClick = null, minHeight, content: ColumnScope.() -> Unit)`: with no `onClick` it is not clickable, which is what the row needs.
- `index` is computed from `list`, so the actions shown match the dragged order.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: PASS (all settings tests).

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml feature/settings
git commit -m "feat(settings): drag-to-reorder rooms with TalkBack move actions"
```

---

### Task 7: Open-source licences (AboutLibraries)

**Files:**
- Modify: `gradle/libs.versions.toml`: `aboutlibraries = "15.2.0"`; libraries `aboutlibraries-core = { module = "com.mikepenz:aboutlibraries-core", version.ref = "aboutlibraries" }` and `aboutlibraries-gradle-plugin = { module = "com.mikepenz.aboutlibraries.plugin:aboutlibraries-plugin", version.ref = "aboutlibraries" }`
- Modify: `build-logic/convention/build.gradle.kts` (add `implementation(libs.aboutlibraries.gradle.plugin)` so `:app` can apply it by id, like google-services)
- Modify: `app/build.gradle.kts` (apply `id("com.mikepenz.aboutlibraries.plugin.android")`, `aboutLibraries { … }`, `implementation(project(":feature:settings"))`)
- Create: `app/config/libraries/inter.json`, `app/config/libraries/fraunces.json`, `app/config/licenses/OFL-1.1.json`
- Modify: `feature/settings/build.gradle.kts` (add `implementation(libs.aboutlibraries.core)`)
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/licences/LicencesScreen.kt`
- Modify: settings strings (`licences_title`, `licences_close`)
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/licences/LicencesScreenTest.kt`
- Create: `app/src/test/kotlin/com/gekkotron/oikos/LicencesResourceTest.kt`

**Interfaces:**
- Consumes: `SettingsTopBar` (Task 4).
- Produces: `data class LicenceRow(val name: String, val version: String?, val licence: String, val text: String)`; `fun licenceRows(json: String): List<LicenceRow>` (sorted by name, case-insensitive); `@Composable fun LicencesRoute(loadJson: () -> String, onBack: () -> Unit)`; `@Composable fun LicencesScreen(rows: List<LicenceRow>, onBack: () -> Unit)`. The app passes `loadJson = { resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() } }`.

- [ ] **Step 1: Gradle wiring**

`app/build.gradle.kts` plugins block: add `id("com.mikepenz.aboutlibraries.plugin.android")`. After the `android { }` block:

```kotlin
aboutLibraries {
    // Reproducible builds: no network lookups; licence texts come from the plugin and app/config.
    offlineMode = true
    collect {
        configPath = file("config")
        // Debug-only tooling (ui-tooling, test manifest) is not shipped to testers.
        filterVariants.addAll("release")
    }
    export {
        excludeFields.addAll("developers", "funding")
    }
}
```

Add `implementation(project(":feature:settings"))` to `dependencies`.

`app/config/libraries/inter.json`:

```json
{
  "uniqueId": "rsms:inter",
  "artifactVersion": "variable",
  "name": "Inter",
  "description": "Inter typeface, bundled by Oikos.",
  "website": "https://github.com/rsms/inter",
  "licenses": ["OFL-1.1"]
}
```

`app/config/libraries/fraunces.json`:

```json
{
  "uniqueId": "undercasetype:fraunces",
  "artifactVersion": "variable",
  "name": "Fraunces",
  "description": "Fraunces typeface, bundled by Oikos.",
  "website": "https://github.com/undercasetype/Fraunces",
  "licenses": ["OFL-1.1"]
}
```

`app/config/licenses/OFL-1.1.json`: `{"hash": "OFL-1.1", "name": "SIL Open Font License 1.1", "url": "https://openfontlicense.org", "content": "<the full text of core/designsystem/licenses/Inter-OFL.txt from the line \"This Font Software is licensed…\" to the end, JSON-escaped>"}`. Generate it rather than pasting by hand:

```bash
python3 - <<'EOF'
import json, pathlib
text = pathlib.Path("core/designsystem/licenses/Inter-OFL.txt").read_text()
body = text[text.index("This Font Software is licensed"):]
out = {"hash": "OFL-1.1", "name": "SIL Open Font License 1.1", "url": "https://openfontlicense.org", "content": body}
p = pathlib.Path("app/config/licenses/OFL-1.1.json"); p.parent.mkdir(parents=True, exist_ok=True)
p.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")
EOF
```

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, and `find app/build -name aboutlibraries.json` lists a generated file. If the plugin rejects AGP 9.4 / Kotlin 2.4 (spec §9 risk), stop and report it with the error. The fallback (a Gradle task that writes the same JSON shape from `releaseRuntimeClasspath` POMs) is a separate decision.

- [ ] **Step 2: Write the failing tests**

`LicencesScreenTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.licences

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class LicencesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val json = """
        {"metadata":{"generated":"2026-10-03"},
         "libraries":[
           {"uniqueId":"rsms:inter","artifactVersion":"variable","name":"Inter","licenses":["OFL-1.1"]},
           {"uniqueId":"androidx.core:core","artifactVersion":"1.17.0","name":"Core","licenses":["Apache-2.0"]}],
         "licenses":{
           "OFL-1.1":{"hash":"OFL-1.1","name":"SIL Open Font License 1.1","content":"OFL body"},
           "Apache-2.0":{"hash":"Apache-2.0","name":"Apache License 2.0","content":"Apache body"}}}
    """.trimIndent()

    @Test
    fun `rows are parsed and sorted by name`() {
        val rows = licenceRows(json)
        assertEquals(listOf("Core", "Inter"), rows.map { it.name })
        assertEquals(LicenceRow("Inter", "variable", "SIL Open Font License 1.1", "OFL body"), rows[1])
    }

    @Test
    fun `tapping a row shows the licence text`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { LicencesScreen(licenceRows(json), onBack = {}) } }
        compose.onNodeWithText("Inter").assertExists()
        compose.onNodeWithText("SIL Open Font License 1.1", substring = true).assertExists()
        compose.onNodeWithText("Inter").performClick()
        compose.onNodeWithText("OFL body").assertExists()
    }

    @Test
    fun `broken json gives an empty list, not a crash`() {
        assertEquals(emptyList(), licenceRows("{oops"))
    }
}
```

`app/src/test/kotlin/com/gekkotron/oikos/LicencesResourceTest.kt` (pins the plugin output and the font entries):

```kotlin
package com.gekkotron.oikos

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.settings.licences.licenceRows
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LicencesResourceTest {
    @Test
    fun `generated licences include the bundled fonts and real dependencies`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val json = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        val names = licenceRows(json).map { it.name }
        assertTrue("Inter" in names && "Fraunces" in names, "fonts missing: $names")
        assertTrue(names.size > 10, "too few libraries: ${names.size}")
    }
}
```

(If `androidx.test.core` is not on the app test classpath, use the `HiltTestApplication`-free `RuntimeEnvironment.getApplication()` from Robolectric instead.)

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests '*Licences*' :app:testDebugUnitTest --tests '*LicencesResourceTest*'`
Expected: FAIL, "Unresolved reference: licenceRows".

- [ ] **Step 4: Implement**

Strings, `values/`: `<string name="licences_title">Open-source licences</string>` and `<string name="licences_close">Close</string>`; `values-fr/`: `Licences open source`, `Fermer`.

`LicencesScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.settings.licences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.ui.SettingsTopBar
import com.mikepenz.aboutlibraries.Libs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LicenceRow(val name: String, val version: String?, val licence: String, val text: String)

/** The AboutLibraries JSON as rows; unreadable JSON gives no rows rather than a crash. */
fun licenceRows(json: String): List<LicenceRow> = try {
    Libs.Builder().withJson(json).build().libraries.map { lib ->
        LicenceRow(
            name = lib.name,
            version = lib.artifactVersion,
            licence = lib.licenses.joinToString(", ") { it.name },
            text = lib.licenses.mapNotNull { it.licenseContent }.joinToString("\n\n"),
        )
    }.sortedBy { it.name.lowercase() }
} catch (e: Exception) {
    emptyList()
}

@Composable
fun LicencesRoute(loadJson: () -> String, onBack: () -> Unit) {
    val rows by produceState(emptyList<LicenceRow>(), loadJson) {
        value = withContext(Dispatchers.IO) { licenceRows(loadJson()) }
    }
    LicencesScreen(rows, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(rows: List<LicenceRow>, onBack: () -> Unit) {
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        topBar = { SettingsTopBar(stringResource(R.string.licences_title), onBack) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(rows, key = { it.name + it.version }) { row ->
                Column(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable { openName = row.name }.padding(vertical = 8.dp)) {
                    Text(row.name, style = OikosTheme.type.body)
                    Text(listOfNotNull(row.version, row.licence).joinToString(" · "), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
                }
            }
        }
    }
    val open = openName?.let { name -> rows.firstOrNull { it.name == name } }
    if (open != null) {
        ModalBottomSheet(onDismissRequest = { openName = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(open.name, style = OikosTheme.type.cardName)
                Text(open.text.ifBlank { open.licence }, style = OikosTheme.type.label, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
```

(`licenseContent` is the AboutLibraries `License` field holding the text; if 15.x names it differently, check `com.mikepenz.aboutlibraries.entity.License`.)

- [ ] **Step 5: Run the tests**

Run: `./gradlew :feature:settings:testDebugUnitTest :app:testDebugUnitTest --tests '*LicencesResourceTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build-logic app/build.gradle.kts app/config app/src/test/kotlin/com/gekkotron/oikos/LicencesResourceTest.kt feature/settings
git commit -m "feat(settings): open-source licences from AboutLibraries, fonts included"
```

---

### Task 8: App wiring (navigation, gear, trimmed link sheet, live theme)

**Files:**
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/AppViewModel.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheet.kt`
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/LinkSheetTest.kt`
- Modify: `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt`
- Modify: `app/src/test/kotlin/com/gekkotron/oikos/navigation/MainTabsTest.kt` (only if it asserts `showsTabBar` for every key)

**Interfaces:**
- Consumes: `AppPreferences`/`ThemePreference` (Task 1), `SettingsRoute` (Task 4), `RoomOrderRoute` (Task 6), `LicencesRoute` (Task 7), `OikosGlyph.GEAR` (Task 2).
- Produces: `SettingsKey`, `RoomOrderKey`, `LicencesKey` (`@Serializable data object … : NavKey`); `AppViewModel.theme: StateFlow<ThemeChoice>`; `HomeRoute(onOpenSettings: () -> Unit, route, onRouteChange, windowInsets, viewModel)` (replaces `onSetUpAgain` and `onAssignRooms`); `LinkSheet(link, route, onRouteChange, onOpenSettings, onDismiss)`.

- [ ] **Step 1: Update the tests first**

`LinkSheetTest.kt`: change `show(...)` to take `onOpenSettings: () -> Unit = {}` instead of `onSetUpAgain`, pass `onOpenSettings = onOpenSettings` to `HomeScreen`, and replace the first test with:

```kotlin
    @Test
    fun `the sheet explains the link and links to Settings, without set-up or rooms buttons`() {
        var opened = false
        show(LinkState.Connected(TransportMode.DEMO), onOpenSettings = { opened = true })
        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("exploring the demo house", substring = true).assertExists()
        compose.onNodeWithText("Set up connection again").assertDoesNotExist()
        compose.onNodeWithText("Assign rooms").assertDoesNotExist()
        compose.onNodeWithText("Connection settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun `the gear opens Settings`() {
        var opened = 0
        show(LinkState.Connected(TransportMode.DEMO), onOpenSettings = { opened++ })
        compose.onNode(hasContentDescription("Settings")).performClick()
        assertEquals(1, opened)
    }
```

Remove `onSetUpAgain = {}` from the other `HomeScreen(...)` calls in this file (`grep -n onSetUpAgain feature/home/src/test -r` and fix every hit, including other test files).

`MainActivityTest.kt`: add helpers and use them:

```kotlin
    private fun openSettings() {
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Theme"), timeoutMillis = 10_000)
    }

    private fun setUpAgain() {
        openSettings()
        compose.onNodeWithText("Set up connection again").performScrollTo().performClick()
        compose.onNodeWithText("Set up again").performClick()
    }
```

Replace every pair `compose.onNodeWithText("Demo").performClick()` + `compose.onNodeWithText("Set up connection again").performClick()` with `setUpAgain()`. Replace the Assign-rooms test body's pill steps with:

```kotlin
        openSettings()
        compose.onNodeWithText("Rooms").performScrollTo().performClick()
        compose.onNodeWithText("Assign rooms").performClick()
        finishRooms("Skip")
        compose.waitUntilAtLeastOneExists(hasText("Assign rooms"), timeoutMillis = 10_000) // back on the Rooms screen
```

Add:

```kotlin
    @Test
    fun `settings opens from the gear, the theme sticks, and back returns Home`() {
        openDemo()
        openSettings()
        compose.onNodeWithText("Warm Minimal").performClick()
        compose.onNodeWithText("Warm Minimal").assertIsSelected()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Floor lamp", substring = true), timeoutMillis = 10_000)
    }

    @Test
    fun `a double tap on the gear opens Settings once`() {
        openDemo()
        val gear = compose.onNode(hasContentDescription("Settings"))
        gear.performClick()
        gear.performClick()
        compose.waitUntilAtLeastOneExists(hasText("Theme"), timeoutMillis = 10_000)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Floor lamp", substring = true), timeoutMillis = 10_000)
    }
```

(imports: `androidx.compose.ui.test.assertIsSelected`. The second click may land after Settings covers the gear; either way back must reach Home in one press.)

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*LinkSheetTest*' :app:testDebugUnitTest --tests '*MainActivityTest*'`
Expected: FAIL, "No value passed for parameter onOpenSettings" / nodes not found.

- [ ] **Step 3: Home and link sheet**

Home strings — `values/`: delete `link_set_up_again` and `link_assign_rooms`; add `<string name="settings_open">Settings</string>` and `<string name="link_settings">Connection settings</string>`. `values-fr/`: delete the same two; add `Réglages` and `Réglages de connexion`.

`LinkSheet.kt` becomes:

```kotlin
/** Link status and the route choice when the profile has a gateway; everything else lives in Settings (settings spec §5.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkSheet(
    link: LinkState,
    route: Route?,
    onRouteChange: (Route) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(linkDescription(link), style = OikosTheme.type.body, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (route != null) RoutePicker(route, onRouteChange)
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.link_settings)) }
        }
    }
}
```

(import `androidx.compose.material3.TextButton`; drop the `OutlinedButton` import if unused.)

`HomeScreen.kt`:
- `HomeRoute`: replace `onSetUpAgain` and `onAssignRooms` params with `onOpenSettings: () -> Unit = {}`; pass it on.
- `HomeScreen`: same replacement of the two params; pass `onSettingsClick = onOpenSettings` to `HomeHeader`; the `LinkSheet` call becomes `LinkSheet(state.link, route = route, onRouteChange = onRouteChange, onOpenSettings = { linkSheet = false; onOpenSettings() }, onDismiss = { linkSheet = false })`.
- `HomeHeader`: add `onSettingsClick: () -> Unit = {}`; after the search `IconButton`:

```kotlin
            val settingsLabel = stringResource(R.string.settings_open)
            IconButton(onClick = onSettingsClick) {
                StaticIcon(OikosGlyph.GEAR, modifier = Modifier.size(24.dp).semantics { contentDescription = settingsLabel })
            }
```

- [ ] **Step 4: AppViewModel, MainActivity, navigation**

`AppViewModel` — add `private val preferences: AppPreferences` to the constructor and:

```kotlin
    /** The theme picked on this phone; System until the store has loaded (settings spec §3.2). */
    val theme: StateFlow<ThemeChoice> = preferences.theme
        .map { pref -> pref?.let { ThemeChoice.valueOf(it.name) } ?: ThemeChoice.SYSTEM }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeChoice.SYSTEM)
```

(`ThemeChoice` and `ThemePreference` have the same constant names; `AppViewModelThemeTest` is not needed: the MainActivity test covers it.)

`MainActivity.setContent`:

```kotlin
        setContent {
            val app: AppViewModel = hiltViewModel()
            val theme by app.theme.collectAsStateWithLifecycle()
            OikosTheme(theme) {
                OikosBackground { OikosNavHost(app) }
            }
        }
```

(imports: `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel`, `androidx.lifecycle.compose.collectAsStateWithLifecycle`, `androidx.compose.runtime.getValue`. `OikosNavHost(viewModel: AppViewModel = hiltViewModel())` already takes it, so the same instance is shared.)

`OikosNavHost.kt` — keys:

```kotlin
@Serializable
data object SettingsKey : NavKey

@Serializable
data object RoomOrderKey : NavKey

@Serializable
data object LicencesKey : NavKey
```

A push helper next to them (guards a double tap):

```kotlin
/** Pushes [key] unless it is already on top (a double tap on the gear opens Settings once). */
internal fun push(stack: MutableList<NavKey>, key: NavKey) {
    if (stack.lastOrNull() != key) stack.add(key)
}
```

Entries:

```kotlin
                entry<HomeKey> {
                    HomeRoute(
                        onOpenSettings = { push(backStack, SettingsKey) },
                        route = route,
                        onRouteChange = viewModel::setRoute,
                        windowInsets = tabInsets,
                    )
                }
                entry<SettingsKey> {
                    SettingsRoute(
                        route = route,
                        onRouteChange = viewModel::setRoute,
                        onSetUpAgain = viewModel::setUpAgain,
                        onOpenRooms = { push(backStack, RoomOrderKey) },
                        onOpenLicences = { push(backStack, LicencesKey) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<RoomOrderKey> {
                    RoomOrderRoute(onAssignRooms = { push(backStack, RoomsKey) }, onBack = { backStack.removeLastOrNull() })
                }
                entry<LicencesKey> {
                    val context = LocalContext.current
                    LicencesRoute(
                        loadJson = { context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() } },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
```

The existing `RoomsKey` entry's `onDone` already pops when the stack has more than one entry, so from Room order it returns there. `selectTab` already removes side tabs only; Settings is never a tab, so `showsTabBar` stays as is (no tab bar on Settings). `resetToRoot` clears Settings when "Set up again" flips the root to onboarding.

Add a unit test for `push` to `app/src/test/kotlin/com/gekkotron/oikos/navigation/MainTabsTest.kt` (or a new `PushTest.kt` there):

```kotlin
    @Test
    fun `push does not stack the same key twice`() {
        val stack = mutableListOf<NavKey>(HomeKey)
        push(stack, SettingsKey)
        push(stack, SettingsKey)
        assertEquals(listOf<NavKey>(HomeKey, SettingsKey), stack)
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :feature:home:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app feature/home
git commit -m "feat(app): gear opens Settings; link sheet keeps status and route; live theme"
```

---

### Task 9: Full check, device pass, spec follow-up

**Files:**
- Modify: `docs/superpowers/specs/2026-10-02-oikos-settings-design.md`

- [ ] **Step 1: Full check**

Run: `./gradlew check` (sandbox disabled).
Expected: BUILD SUCCESSFUL. If only `OnboardingViewModelTest` or `DirectConnectionTesterTest` fail, re-run them alone; they are known flaky on master.

- [ ] **Step 2: Install and try it on the Pixel 6a**

Run: `./gradlew :app:installDebug` (serial `28281JEGR02735`). With the live profile (do not "Set up again"):
1. Home shows a gear next to search; tapping it opens Settings with no tab bar.
2. Warm Minimal switches the whole app at once; System brings Night Glass back (phone in dark mode); the choice survives an app restart.
3. Rooms: drag one room above another, go back to Home: the chips follow. Reopen Rooms: the order stuck.
4. Connection shows the broker, TLS flag, user, prefix, gateway URL; no password, no key. The route picker works.
5. "Set up connection again" shows the dialog; **Cancel** (do not confirm on the live phone).
6. About shows "Oikos 0.<code> · debug"; Open-source licences lists Inter, Fraunces and AndroidX; tapping one shows its text.
7. The pill sheet shows status, route, "Connection settings".
8. The user arms Home or Night on the alarm; take the armed-chips screenshot (`adb exec-out screencap -p`) for the deferred Security check.

- [ ] **Step 3: Update the spec where the implementation deviated, and commit**

Known deviations to write into the spec:
- §6: the version comes from `PackageManager` (`appVersion(context)`), not `BuildConfig`; the licence list is loaded from JSON the app passes in (`LicencesRoute(loadJson)`).
- §4.1: `RoomOrder.apply(areas, newOrder)` appends rooms missing from `newOrder` and skips ids no longer present.
- §5.2: the shared pieces are `OikosRadioRow` and `RoutePicker` in `:core:designsystem/components`; route strings moved to the design system.
- §2: a `push` helper guards double taps on the gear.

```bash
git add docs/superpowers/specs/2026-10-02-oikos-settings-design.md
git commit -m "docs(settings): spec follows the implementation"
```
