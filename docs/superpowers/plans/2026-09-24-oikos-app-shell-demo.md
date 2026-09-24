# Oikos App Shell + Demo Home Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an installable Oikos debug APK. It runs the real discovery/state core against an in-process demo house, renders the Home screen in both themes, and gets installed on a connected phone.

**Architecture:** The core stays as it is. This plan adds four modules:
- `:core:demo` is a JVM `MqttTransport` implementation (`DemoBroker`). It holds retained HA-discovery configs and state for a fake house, echoes every command back as confirmed state, and ticks the power sensors.
- `:core:designsystem` holds the Night Glass / Warm Minimal tokens, the bundled fonts and the base components.
- `:feature:home` holds a pure `HomeUiBuilder` (entities + states → UI model), a Hilt `HomeViewModel` and the Compose Home screen with its control sheet.
- `:app` holds the Hilt graph and a Navigation 3 host. It binds `MqttTransport` to `DemoBroker` and `EntitySource` to the existing `MqttDiscoverySource`.

The demo data goes through exactly the same parsing, template, mapper and optimistic-command code as a real broker would.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1 (built-in Kotlin), Gradle 9.7.1, Compose BOM 2026.09.00 with Material 3, Navigation 3 1.2.0, Lifecycle 2.11.0, Hilt 2.60.1 with KSP 2.3.12, androidx.hilt 1.4.0, Robolectric 4.17 with JUnit 4 for Android modules, JUnit Jupiter for JVM modules (unchanged), Kover 0.9.9. Fonts: Inter and Fraunces variable TTFs (SIL OFL 1.1).

**Spec:** `docs/superpowers/specs/2026-09-24-oikos-design.md`. Sections covered:
- §4.1: the `:app`, `:core:designsystem` and `:feature:home` modules, plus the dependency rule
- §5.1: tokens, `EntityCard`, `StatusPill`, `RoomChips`, `ControlSheet`, `PendingDot`
- §5.2: Home, including hero weather, chips, grid, tap and control sheet
- §5.3: content descriptions, 4.5:1 contrast, 200 % font scale, 2/3/4 columns
- §4.4: the snackbar "Not confirmed — Retry"
- §7: unavailable cards
- §9: the Android build, Hilt, Navigation 3, EN/FR strings and the package name

**Later plans (not this one):**
- `DirectMqttTransport`, `GatewayTransport` and `TransportSelector` (§4.5)
- `:core:data`: settings, `SecretStore` and the entity cache (§4.7)
- Onboarding with QR (§5.2)
- Energy, Security and Settings screens, and the `GlassNavBar`
- The long-press override sheet (§5.2)
- Roborazzi screenshot tests and `@Preview`s (§5.1, §10)
- A real icon set (this plan uses emoji glyphs, as the mockups do)
- Push (§4.8), flavors, and the e2e fixture (§10)

**Deviation, until the override plan lands:** a long-press opens the *control* sheet, not the override sheet. Otherwise light brightness would be unreachable, because a tap toggles lights. The override plan moves this behind an "Edit" entry in the control sheet header.

## Global Constraints

- Package root `com.gekkotron.oikos`. The application id is `com.gekkotron.oikos`, and debug builds add the suffix `.debug` (spec §9).
- minSdk 28, targetSdk and compileSdk 36, JVM 17. There is one Kotlin version (`2.4.20`) for every Kotlin plugin (spec §9).
- AGP 9 compiles Kotlin itself ("built-in Kotlin"). Never apply `org.jetbrains.kotlin.android`.
- Robolectric runs at **SDK 34**. Each Android module has `src/test/resources/robolectric.properties` containing `sdk=34`, because Robolectric's SDK 35+ images need JDK 21 and the toolchain is JDK 17.
- Release unit-test tasks are disabled by the convention plugins, because `ui-test-manifest` is debug-only. Run Android tests with `testDebugUnitTest` or `check`.
- Module dependency rule (spec §4.1):
  - `:feature:home → :core:source, :core:designsystem` (it reaches model and state types through `:core:source`'s `api`), never `:core:transport` or `:core:demo` directly
  - `:core:demo → :core:transport` only
  - `:app` may depend on everything
  - no module depends on `:app`
- Nothing house-specific (spec §1.7). Demo ids are prefixed `demo_`, topics live under `demo/…`, and no IPs appear anywhere.
- UI strings exist in English (`values/`) and French (`values-fr/`) from the first commit that adds them (spec §1.7).
- Colours and fonts come only from `OikosTheme` tokens. There are no hard-coded colours in feature code.
- Accessibility (spec §5.3):
  - every entity card has content description "name, state, room"
  - toggle cards have a state description
  - touch targets are at least 48 dp
  - cards use `heightIn(min = …)`, never a fixed height
- Attribution: every byline or author field is `Gekkotron`. Every commit uses exactly this form (do not edit `.git/config`):
  ```bash
  git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "<message>" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
  ```
- Work on the branch `feat/app-shell`, never directly on `master`/`main`.
- Coverage: every new module is added to the root Kover aggregation; generated Hilt/Compose/R classes are excluded (spec §9).

## Review Focus

1. **Font scale 200 %.** Cards must grow rather than clip their value text, so cards may never have a fixed height. Pinned in Task 5 (`cards grow with font scale`).
2. **Entities with no state yet, or an unsupported template.** Right after launch every card shows `—`: nothing crashes and no stale value appears. Pinned in Task 4 (`unknown and unsupported states render as None`).
3. **An unavailable entity** (the demo garage plug publishes `offline`). The card is greyed out and labelled "Unavailable", and tapping it does nothing. Pinned in Task 5 (`unavailable card is disabled and says so`) and Task 2 (`garage plug is unavailable and rejects commands`).
4. **A command that is never confirmed.** The user sees "Not confirmed", and "Retry" re-sends the same command. Pinned in Task 4 (`unconfirmed command becomes a NotConfirmed event`) and Task 5 (`not confirmed shows a snackbar whose Retry resends`).
5. **The selected room disappears.** Examples: its last entity is hidden, or the area is renamed from another phone. The screen falls back to "All" instead of showing an empty grid with no selected chip. Pinned in Task 4 (`selection of a vanished area falls back to All`).

---

## File Structure

```
gradle/libs.versions.toml                          — + AGP, Compose, Hilt, KSP, Nav3, Lifecycle, Robolectric
gradle.properties                                  — + android.useAndroidX, bigger heap
settings.gradle.kts                                — + :core:demo, :core:designsystem, :feature:home, :app
build.gradle.kts                                   — + kover() for the new modules
build-logic/settings.gradle.kts                    — + google() repository
build-logic/convention/build.gradle.kts            — + AGP, compose-compiler, hilt, ksp Gradle plugins
build-logic/convention/src/main/kotlin/
  OikosAndroid.kt                                  — shared Android test deps + release-test switch-off
  oikos.android.application.gradle.kts             — com.android.application + Kover + SDK levels
  oikos.android.library.gradle.kts                 — com.android.library + Kover + SDK levels
  oikos.android.compose.gradle.kts                 — Compose compiler + BOM + UI test deps
  oikos.hilt.gradle.kts                            — KSP + Hilt
  oikos.root.gradle.kts                            — + Kover excludes for generated classes
.github/workflows/ci.yml                           — + Android SDK, assembleDebug, APK artifact
codecov.yml                                        — + ignore Application and Hilt modules

core/transport/…/MqttTransport.kt                  — + TransportMode.DEMO
core/source/…/EntitySource.kt                      — + val link: StateFlow<LinkState>
core/source/…/MqttDiscoverySource.kt               — + override val link

core/demo/build.gradle.kts
core/demo/src/main/kotlin/com/gekkotron/oikos/demo/
  DemoMessage.kt                                   — (topic, payload) pair
  DemoHouse.kt                                     — the fake house: configs, overrides, state docs, echoes, tick
  DemoBroker.kt                                    — thread-safe in-process MqttTransport over a DemoHouse
core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt

core/designsystem/build.gradle.kts
core/designsystem/licenses/{Inter,Fraunces}-OFL.txt
core/designsystem/src/main/res/font/{inter,fraunces}.ttf
core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/
  OikosColors.kt                                   — colour tokens + NightGlassColors + WarmMinimalColors
  OikosType.kt                                     — font families, text styles, Material typography
  OikosDimens.kt                                   — radius, spacing, touch, motion tokens
  OikosTheme.kt                                    — ThemeChoice, OikosTheme(), OikosTheme.colors/type, OikosBackground
  components/OikosCard.kt
  components/StatusPill.kt
  components/RoomChips.kt
  components/PendingDot.kt
core/designsystem/src/test/…                       — ContrastTest, ThemeTest, OikosCardTest, RoomChipsTest

feature/home/build.gradle.kts
feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/
  presentation/HomeModels.kt                       — RoomFilter, ChipUi, TapAction, CardValue, CardUi, HomeUiState, HomeEvent
  presentation/CardFormatter.kt                    — StateValue → CardValue, locale-aware numbers
  presentation/HomeUiBuilder.kt                    — entities + states + areas → HomeUiState
  presentation/StepMath.kt                         — setpoint / slider snapping
  presentation/HomeViewModel.kt                    — Hilt VM, saved filter, events
  ui/CardText.kt                                   — CardValue → localized string, mode/alarm labels
  ui/KindGlyphs.kt                                 — emoji glyph per kind/device class
  ui/EntityCard.kt
  ui/ControlSheet.kt
  ui/LinkPill.kt
  ui/HomeEventsEffect.kt
  ui/HomeScreen.kt                                 — HomeRoute (stateful) + HomeScreen (stateless)
feature/home/src/main/res/values{,-fr}/strings.xml
feature/home/src/test/…                            — builder, formatter, step math, VM, screen, events tests

app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/…                                 — app_name (en/fr), theme, launcher icon, resources.properties
app/src/main/kotlin/com/gekkotron/oikos/
  OikosApplication.kt
  MainActivity.kt
  di/AppModule.kt                                  — ApplicationScope, DemoBroker, MqttDiscoverySource
  navigation/OikosNavHost.kt                       — Navigation 3 back stack with HomeKey
app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt
README.md, NOTICE                                  — modules, "Try the demo", font licences
```

---

### Task 1: Android build logic and an installable `:app` skeleton

This task concentrates all the build risk (AGP 9, built-in Kotlin, Compose compiler, Hilt/KSP, Robolectric) into one small, verifiable deliverable: an APK that launches on the phone.

**Files:**
- Modify: `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `build-logic/settings.gradle.kts`, `build-logic/convention/build.gradle.kts`, `build-logic/convention/src/main/kotlin/oikos.root.gradle.kts`, `.github/workflows/ci.yml`, `codecov.yml`
- Create: `build-logic/convention/src/main/kotlin/OikosAndroid.kt`, `oikos.android.application.gradle.kts`, `oikos.android.library.gradle.kts`, `oikos.android.compose.gradle.kts`, `oikos.hilt.gradle.kts`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/gekkotron/oikos/{OikosApplication,MainActivity}.kt`, `app/src/main/res/values/{strings,themes,colors}.xml`, `app/src/main/res/values-fr/strings.xml`, `app/src/main/res/values-night/colors.xml`, `app/src/main/res/resources.properties`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Test: `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt`, `app/src/test/resources/robolectric.properties`

**Interfaces:**
- Consumes: the existing `oikos.jvm.library`, `oikos.serialization` and `oikos.root` convention plugins, and the `libs` catalog.
- Produces:
  - Convention plugin ids `oikos.android.application`, `oikos.android.library`, `oikos.android.compose` and `oikos.hilt`.
  - Catalog aliases used by later tasks: `libs.androidx.activity.compose`, `libs.androidx.lifecycle.viewmodel.compose`, `libs.androidx.lifecycle.runtime.compose`, `libs.androidx.lifecycle.viewmodel.navigation3`, `libs.androidx.navigation3.runtime`, `libs.androidx.navigation3.ui`, `libs.androidx.hilt.lifecycle.viewmodel.compose`, `libs.hilt.android.testing` and `libs.hilt.compiler`.
  - `com.gekkotron.oikos.OikosApplication` (`@HiltAndroidApp`) and `com.gekkotron.oikos.MainActivity` (`@AndroidEntryPoint`).

- [ ] **Step 1: Create the branch**

```bash
git checkout -b feat/app-shell
```

- [ ] **Step 2: Extend the version catalog**

In `gradle/libs.versions.toml`, append to `[versions]`:

```toml
agp = "9.4.1"
ksp = "2.3.12"
hilt = "2.60.1"
androidxHilt = "1.4.0"
composeBom = "2026.09.00"
activity = "1.13.0"
lifecycle = "2.11.0"
navigation3 = "1.2.0"
robolectric = "4.17"
junit4 = "4.13.2"
androidxTestExtJunit = "1.3.0"
```

Append to `[libraries]`:

```toml
android-gradle-plugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
compose-compiler-gradle-plugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }
hilt-gradle-plugin = { module = "com.google.dagger:hilt-android-gradle-plugin", version.ref = "hilt" }
ksp-gradle-plugin = { module = "com.google.devtools.ksp:symbol-processing-gradle-plugin", version.ref = "ksp" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
androidx-navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
androidx-navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
androidx-hilt-lifecycle-viewmodel-compose = { module = "androidx.hilt:hilt-lifecycle-viewmodel-compose", version.ref = "androidxHilt" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
```

- [ ] **Step 3: Gradle properties and build-logic classpath**

Replace `gradle.properties` with:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
kotlin.code.style=official
android.useAndroidX=true
```

In `build-logic/settings.gradle.kts`, change the repositories block to:

```kotlin
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
```

Replace `build-logic/convention/build.gradle.kts` with:

```kotlin
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
}
```

- [ ] **Step 4: Android convention plugins**

Create `build-logic/convention/src/main/kotlin/OikosAndroid.kt`:

```kotlin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/** JUnit 4 + Robolectric host tests, shared by every Android module. */
internal fun Project.oikosAndroidTesting() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val kotlinVersion = libs.findVersion("kotlin").get().requiredVersion
    dependencies.apply {
        add("testImplementation", libs.findLibrary("junit4").get())
        add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", libs.findLibrary("robolectric").get())
        add("testImplementation", libs.findLibrary("androidx-test-ext-junit").get())
    }
    // ui-test-manifest is debug-only, so release host tests cannot host Compose tests.
    tasks.withType<Test>().matching { it.name.contains("Release") }.configureEach { enabled = false }
}
```

Create `build-logic/convention/src/main/kotlin/oikos.android.library.gradle.kts`:

```kotlin
// Android library with Kover and JUnit 4 + Robolectric host tests. AGP 9 compiles Kotlin itself.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

oikosAndroidTesting()
```

Create `build-logic/convention/src/main/kotlin/oikos.android.application.gradle.kts`:

```kotlin
// Android application with Kover and JUnit 4 + Robolectric host tests. AGP 9 compiles Kotlin itself.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlinx.kover")
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        targetSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

oikosAndroidTesting()
```

Create `build-logic/convention/src/main/kotlin/oikos.android.compose.gradle.kts`:

```kotlin
// Jetpack Compose for an Android module. Apply after oikos.android.library or oikos.android.application.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

plugins.withId("com.android.library") {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> { buildFeatures.compose = true }
}
plugins.withId("com.android.application") {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension> { buildFeatures.compose = true }
}

dependencies {
    val bom = platform(libs.findLibrary("compose-bom").get())
    "implementation"(bom)
    "testImplementation"(bom)
    "implementation"(libs.findLibrary("compose-ui").get())
    "implementation"(libs.findLibrary("compose-material3").get())
    "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
    "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
    "testImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
}
```

Create `build-logic/convention/src/main/kotlin/oikos.hilt.gradle.kts`:

```kotlin
// Hilt through KSP.
plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("hilt-android").get())
    "ksp"(libs.findLibrary("hilt-compiler").get())
}
```

Replace `build-logic/convention/src/main/kotlin/oikos.root.gradle.kts` with:

```kotlin
// Applied to the root project only: hosts the merged Kover report.
plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*Hilt_*", "*_Factory", "*_Factory\$*", "*_MembersInjector", "*_HiltModules*",
                    "*_GeneratedInjector", "*_ComponentTreeDeps", "hilt_aggregated_deps.*", "dagger.hilt.internal.*",
                    "*.BuildConfig", "*.R", "*.R\$*", "*ComposableSingletons*",
                )
            }
        }
    }
}
```

- [ ] **Step 5: Write the failing launch test**

Create `app/src/test/resources/robolectric.properties`:

```properties
sdk=34
```

Create `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt`:

```kotlin
package com.gekkotron.oikos

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `launches and shows the app name`() {
        compose.onNodeWithText("Oikos").assertExists()
    }
}
```

- [ ] **Step 6: Add the module and run the test to verify it fails**

In `settings.gradle.kts`, append `include(":app")`. Create `app/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.application")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos"
    defaultConfig {
        applicationId = "com.gekkotron.oikos"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
```

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL at compilation with `Unresolved reference 'MainActivity'`. Any failure *before* compilation (plugin resolution, AGP/Gradle compatibility, KSP and built-in Kotlin) must be fixed now, because it belongs to this task's deliverable:
- If KSP rejects AGP built-in Kotlin, add `android.builtInKotlin=false` to `gradle.properties` and apply `id("org.jetbrains.kotlin.android")` in both Android convention plugins. Record the choice in the commit message.
- If Robolectric cannot find SDK 34, run once with network access so it downloads `android-all-instrumented`.

- [ ] **Step 7: Write the application, activity and resources**

`app/src/main/kotlin/com/gekkotron/oikos/OikosApplication.kt`:

```kotlin
package com.gekkotron.oikos

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OikosApplication : Application()
```

`app/src/main/kotlin/com/gekkotron/oikos/MainActivity.kt`:

```kotlin
package com.gekkotron.oikos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Text(stringResource(R.string.app_name)) }
    }
}
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".OikosApplication"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.Oikos">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name" translatable="false">Oikos</string>
</resources>
```

`app/src/main/res/values-fr/strings.xml` is present so that `generateLocaleConfig` declares French:

```xml
<resources>
    <string name="app_name_fr_marker" translatable="false">Oikos</string>
</resources>
```

`app/src/main/res/resources.properties`:

```properties
unqualifiedResLocale=en
```

`app/src/main/res/values/colors.xml`:

```xml
<resources>
    <color name="oikos_window">#F5F1EA</color>
    <color name="ic_launcher_background">#0B1020</color>
</resources>
```

`app/src/main/res/values-night/colors.xml`:

```xml
<resources>
    <color name="oikos_window">#0B1020</color>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.Oikos" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/oikos_window</item>
    </style>
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFC457"
        android:pathData="M54,34 L78,54 L72,54 L72,76 L60,76 L60,62 L48,62 L48,76 L36,76 L36,54 L30,54 Z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (1 test).

- [ ] **Step 9: Wire coverage, CI and codecov**

In `build.gradle.kts`, add `kover(project(":app"))` inside `dependencies { … }`.

Replace `.github/workflows/ci.yml` with:

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
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v5
      - name: Build, test, coverage
        run: ./gradlew check koverXmlReport :app:assembleDebug --stacktrace
      - uses: codecov/codecov-action@v5
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          files: build/reports/kover/report.xml
          fail_ci_if_error: false
      - uses: actions/upload-artifact@v4
        with:
          name: oikos-debug-apk
          path: app/build/outputs/apk/debug/*.apk
```

Replace `codecov.yml` with:

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
  - "app/src/main/kotlin/com/gekkotron/oikos/OikosApplication.kt"
  - "app/src/main/kotlin/com/gekkotron/oikos/di/**"
```

- [ ] **Step 10: Full build, then install on the phone**

Run: `./gradlew check koverXmlReport :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with all JVM tests still green.

Run: `adb devices -l`
Expected: one device in state `device`. If none is listed, skip the install and report it; the APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Run:
```bash
./gradlew :app:installDebug
adb shell am start -n com.gekkotron.oikos.debug/com.gekkotron.oikos.MainActivity
```
Expected: the phone shows "Oikos", and the launcher shows the amber house icon.

- [ ] **Step 11: Commit**

```bash
git add gradle gradle.properties settings.gradle.kts build.gradle.kts build-logic .github codecov.yml app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "build: Android convention plugins, Hilt, and installable :app skeleton" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `:core:demo`, an in-process demo house behind `MqttTransport`

**Files:**
- Modify: `core/transport/src/main/kotlin/com/gekkotron/oikos/transport/MqttTransport.kt` (the `TransportMode` enum)
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/EntitySource.kt`, `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt`
- Modify: `core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt` (one new test)
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `core/demo/build.gradle.kts`, `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/{DemoMessage,DemoHouse,DemoBroker}.kt`
- Test: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt`

**Interfaces:**
- Consumes:
  - `MqttTransport`, `LinkState`, `MqttMessage`, `PublishFailed`, `topicMatches(filter, topic)` and `isValidPublishTopic(topic)` from `:core:transport`
  - `MqttDiscoverySource(transport, scope, prefix, diagnostics, commandTimeout, now)`, in tests only
- Produces:
  - `enum class TransportMode { DIRECT, GATEWAY, DEMO }`
  - `EntitySource.link: StateFlow<LinkState>`
  - `class DemoBroker(scope: CoroutineScope, house: DemoHouse = DemoHouse(), echoDelay: Duration = 300.milliseconds) : MqttTransport` with `fun startTicking(interval: Duration = 3.seconds)`
  - `class DemoHouse(random: Random = Random(7))` with `retainedMessages(): List<DemoMessage>`, `onCommand(topic, payload): DemoMessage?` and `tick(): List<DemoMessage>`
  - `data class DemoMessage(val topic: String, val payload: String)`
  - Demo entity ids: `demo_living_lamp`, `demo_kitchen_spots`, `demo_coffee`, `demo_garage_plug`, `demo_living_shutter`, `demo_living_ac`, `demo_bedroom_temperature`, `demo_bedroom_humidity`, `demo_outdoor_temperature`, `demo_solar_power`, `demo_grid_power`, `demo_house_power`, `demo_front_door`, `demo_hallway_motion`, `demo_alarm`, `demo_water_heater_target`, `demo_vacuum_mode` and `demo_vacuum_start`
  - The display name of `demo_living_lamp` is `Floor lamp`

- [ ] **Step 1: Write the failing `link` test in `:core:source`**

Add to `MqttDiscoverySourceTest` (with `import com.gekkotron.oikos.transport.LinkState`):

```kotlin
    @Test
    fun `link mirrors the transport`() = runTest {
        val h = start()
        h.transport.setLink(LinkState.Offline("test"))
        assertEquals(LinkState.Offline("test"), h.source.link.value)
    }
```

Run: `./gradlew :core:source:test --tests '*MqttDiscoverySourceTest*link*'`
Expected: FAIL to compile, `Unresolved reference 'link'`.

- [ ] **Step 2: Add `link` and `DEMO`**

In `MqttTransport.kt`, replace the enum with:

```kotlin
enum class TransportMode { DIRECT, GATEWAY, DEMO }
```

In `EntitySource.kt`, add the import `com.gekkotron.oikos.transport.LinkState` and add this as the interface's first member:

```kotlin
    /** Broker connection, for the status pill. */
    val link: StateFlow<LinkState>
```

In `MqttDiscoverySource.kt`, add the import `com.gekkotron.oikos.transport.LinkState` and add this next to `override val commandFailures`:

```kotlin
    override val link: StateFlow<LinkState> = transport.link
```

Run: `./gradlew :core:source:test`
Expected: PASS (all tests).

- [ ] **Step 3: Create the module and write the failing demo tests**

In `settings.gradle.kts`, append `include(":core:demo")`. In `build.gradle.kts`, add `kover(project(":core:demo"))`.

`core/demo/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.jvm.library")
    id("oikos.serialization")
}

dependencies {
    api(project(":core:transport"))
    testImplementation(project(":core:source"))
}
```

`core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt`:

```kotlin
package com.gekkotron.oikos.demo

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.EnergyRole
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemoBrokerTest {
    private val allIds = setOf(
        "demo_living_lamp", "demo_kitchen_spots", "demo_coffee", "demo_garage_plug", "demo_living_shutter",
        "demo_living_ac", "demo_bedroom_temperature", "demo_bedroom_humidity", "demo_outdoor_temperature",
        "demo_solar_power", "demo_grid_power", "demo_house_power", "demo_front_door", "demo_hallway_motion",
        "demo_alarm", "demo_water_heater_target", "demo_vacuum_mode", "demo_vacuum_start",
    )

    private class Harness(val broker: DemoBroker, val source: MqttDiscoverySource, val diagnostics: List<Diagnostic>)

    private fun TestScope.start(): Harness {
        val broker = DemoBroker(backgroundScope)
        val diagnostics = mutableListOf<Diagnostic>()
        val source = MqttDiscoverySource(broker, backgroundScope, diagnostics = { diagnostics += it })
        runCurrent()
        settle()
        return Harness(broker, source, diagnostics)
    }

    /** Past the subscription debounce and the echo delay. */
    private fun TestScope.settle() {
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
    }

    private suspend fun Harness.value(id: String) = source.states().first().getValue(id).value

    @Test
    fun `every demo config becomes an entity without diagnostics`() = runTest {
        val h = start()
        assertEquals(allIds, h.source.entities.value.keys)
        assertEquals(emptyList(), h.diagnostics)
        assertEquals("Floor lamp", h.source.entities.value.getValue("demo_living_lamp").name)
    }

    @Test
    fun `declared areas and overrides are applied`() = runTest {
        val h = start()
        val e = h.source.entities.value
        assertEquals("living_room", e.getValue("demo_living_lamp").area)
        assertEquals(listOf("Living room", "Kitchen", "Bedroom", "Hallway", "Garage"), h.source.areas.value.take(5).map { it.name })
        assertEquals("hero_weather", e.getValue("demo_outdoor_temperature").ui.cardStyle)
        assertTrue(e.getValue("demo_living_lamp").ui.favorite)
        assertEquals(EnergyRole.SOLAR, e.getValue("demo_solar_power").ui.energyRole)
    }

    @Test
    fun `every stateful entity has a known state after settling`() = runTest {
        val h = start()
        val states = h.source.states().first()
        for (id in allIds - "demo_vacuum_start") {
            val v = states.getValue(id).value
            assertTrue(v !is StateValue.Unknown && v !is StateValue.Unsupported, "$id is $v")
        }
        assertEquals(StateValue.Light(true, 180), h.value("demo_living_lamp"))
        assertEquals(StateValue.OnOff(false), h.value("demo_front_door")) // contact=true means closed
    }

    @Test
    fun `toggling the lamp is confirmed and never reverted`() = runTest {
        val h = start()
        val failures = mutableListOf<CommandFailure>()
        backgroundScope.launch { h.source.commandFailures.collect { failures += it } }
        assertEquals(CommandResult.Accepted, h.source.send("demo_living_lamp", Command.Toggle))
        runCurrent()
        assertTrue(h.source.states().first().getValue("demo_living_lamp").pending)
        settle()
        val state = h.source.states().first().getValue("demo_living_lamp")
        assertEquals(StateValue.Light(false, 180), state.value)
        assertFalse(state.pending)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(emptyList(), failures)
        assertEquals(StateValue.Light(false, 180), h.value("demo_living_lamp"))
    }

    @Test
    fun `cover, climate, number, select and alarm commands are echoed`() = runTest {
        val h = start()
        h.source.send("demo_living_shutter", Command.SetPosition(25))
        h.source.send("demo_living_ac", Command.SetTemperature(21.5))
        h.source.send("demo_living_ac", Command.SetHvacMode("cool"))
        h.source.send("demo_water_heater_target", Command.SetNumber(58.0))
        h.source.send("demo_vacuum_mode", Command.SelectOption("turbo"))
        h.source.send("demo_alarm", Command.Arm(AlarmMode.AWAY))
        settle()
        assertEquals(StateValue.Cover(CoverState.OPEN, 25), h.value("demo_living_shutter"))
        val ac = assertIs<StateValue.Climate>(h.value("demo_living_ac"))
        assertEquals(21.5, ac.target)
        assertEquals("cool", ac.mode)
        assertEquals(StateValue.Numeric(58.0), h.value("demo_water_heater_target"))
        assertEquals(StateValue.Text("turbo"), h.value("demo_vacuum_mode"))
        assertEquals(StateValue.Alarm(AlarmState.ARMED_AWAY), h.value("demo_alarm"))
    }

    @Test
    fun `closing the shutter ends closed at zero`() = runTest {
        val h = start()
        h.source.send("demo_living_shutter", Command.Close)
        settle()
        assertEquals(StateValue.Cover(CoverState.CLOSED, 0), h.value("demo_living_shutter"))
    }

    @Test
    fun `garage plug is unavailable and rejects commands`() = runTest {
        val h = start()
        assertFalse(h.source.states().first().getValue("demo_garage_plug").available)
        assertEquals(CommandResult.Rejected("unavailable"), h.source.send("demo_garage_plug", Command.Toggle))
    }

    @Test
    fun `ticking moves energy values and keeps grid equal to house minus solar`() = runTest {
        val h = start()
        val before = h.value("demo_solar_power")
        h.broker.startTicking(3.seconds)
        repeat(5) {
            advanceTimeBy(3_001)
            runCurrent()
        }
        val solar = assertIs<StateValue.Numeric>(h.value("demo_solar_power")).value
        val grid = assertIs<StateValue.Numeric>(h.value("demo_grid_power")).value
        val house = assertIs<StateValue.Numeric>(h.value("demo_house_power")).value
        assertNotEquals(before, StateValue.Numeric(solar))
        assertEquals(house - solar, grid)
        assertTrue(solar in 0.0..4200.0)
    }

    @Test
    fun `link reports demo mode and invalid topics fail`() = runTest {
        val h = start()
        assertEquals(LinkState.Connected(TransportMode.DEMO), h.source.link.value)
        assertFailsWith<PublishFailed> { h.broker.publish("demo/+/set", "x".encodeToByteArray()) }
    }
}
```

Run: `./gradlew :core:demo:test`
Expected: FAIL to compile, `Unresolved reference 'DemoBroker'`.

- [ ] **Step 4: Implement `DemoMessage` and `DemoHouse`**

`core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoMessage.kt`:

```kotlin
package com.gekkotron.oikos.demo

data class DemoMessage(val topic: String, val payload: String)
```

`core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`:

```kotlin
package com.gekkotron.oikos.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A fake house published the way zigbee2mqtt and oikos-bridge would: retained HA discovery configs,
 * one JSON state document per device, and a command topic that is echoed back into that document.
 * Thread-safe.
 */
class DemoHouse(private val random: Random = Random(7)) {
    private class Echo(val stateTopic: String, val patch: (String) -> JsonObject?)

    private val configs = mutableListOf<DemoMessage>()
    private val extras = mutableListOf<DemoMessage>()
    private val docs = linkedMapOf<String, JsonObject>()
    private val echoes = mutableMapOf<String, Echo>()

    init {
        areas()
        light("demo_living_lamp", "Floor lamp", "Living room", "demo/living/lamp", on = true, brightness = 180)
        light("demo_kitchen_spots", "Kitchen spots", "Kitchen", "demo/kitchen/spots", on = false, brightness = 255)
        switch("demo_coffee", "Coffee machine", "Kitchen", "demo/kitchen/coffee", on = false, available = true)
        switch("demo_garage_plug", "Garage plug", "Garage", "demo/garage/plug", on = false, available = false)
        cover("demo_living_shutter", "Shutter", "Living room", "demo/living/shutter")
        climate("demo_living_ac", "Air conditioner", "Living room", "demo/living/ac")
        sensor("demo_bedroom_temperature", "Bedroom", "Temperature", "Bedroom", "demo/bedroom/climate", "temperature", "temperature", "°C")
        sensor("demo_bedroom_humidity", "Bedroom", "Humidity", "Bedroom", "demo/bedroom/climate", "humidity", "humidity", "%")
        doc("demo/bedroom/climate", obj("temperature" to 19.6, "humidity" to 54))
        sensor("demo_outdoor_temperature", "Outdoor temperature", null, "Outside", OUTDOOR, "temperature", "temperature", "°C")
        doc(OUTDOOR, obj("temperature" to 14.2))
        sensor("demo_solar_power", "Solar production", null, "Garage", ENERGY, "solar", "power", "W")
        sensor("demo_grid_power", "Grid", null, "Garage", ENERGY, "grid", "power", "W")
        sensor("demo_house_power", "House consumption", null, "Garage", ENERGY, "house", "power", "W")
        doc(ENERGY, obj("solar" to 2140, "grid" to -380, "house" to 1760))
        binarySensor("demo_front_door", "Front door", "Hallway", "demo/hallway/door", "door", "contact", onValue = false, initial = true)
        binarySensor("demo_hallway_motion", "Hallway motion", "Hallway", "demo/hallway/motion", "motion", "occupancy", onValue = true, initial = false)
        alarm("demo_alarm", "Alarm", "Hallway", "demo/alarm")
        number("demo_water_heater_target", "Water heater target", "Garage", "demo/garage/water_heater")
        vacuum("Living room", "demo/living/vacuum")
        override("demo_outdoor_temperature", """{"cardStyle":"hero_weather"}""")
        override("demo_living_lamp", """{"favorite":true}""")
        override("demo_living_ac", """{"favorite":true}""")
        override("demo_solar_power", """{"energyRole":"solar"}""")
        override("demo_grid_power", """{"energyRole":"grid"}""")
        override("demo_house_power", """{"energyRole":"house"}""")
    }

    /** Configs first, then overrides and areas, then state: the order a real broker replays retained messages. */
    @Synchronized
    fun retainedMessages(): List<DemoMessage> =
        configs + extras + docs.map { (topic, json) -> DemoMessage(topic, json.toString()) }

    /** The state message a real device would publish after receiving [payload] on [topic], or null. */
    @Synchronized
    fun onCommand(topic: String, payload: String): DemoMessage? {
        val echo = echoes[topic] ?: return null
        val patch = echo.patch(payload) ?: return null
        val merged = JsonObject(docs.getValue(echo.stateTopic) + patch)
        docs[echo.stateTopic] = merged
        return DemoMessage(echo.stateTopic, merged.toString())
    }

    /** One step of live sensor noise: solar and house drift, grid follows, outdoor temperature wobbles. */
    @Synchronized
    fun tick(): List<DemoMessage> {
        val energy = docs.getValue(ENERGY)
        val solar = (energy.number("solar") + random.nextInt(-150, 151)).coerceIn(0.0, 4200.0)
        val house = (energy.number("house") + random.nextInt(-120, 121)).coerceIn(300.0, 3500.0)
        docs[ENERGY] = obj("solar" to solar.roundToInt(), "grid" to (house - solar).roundToInt(), "house" to house.roundToInt())
        val outdoor = docs.getValue(OUTDOOR).number("temperature") + random.nextInt(-1, 2) / 10.0
        docs[OUTDOOR] = obj("temperature" to (outdoor * 10).roundToInt() / 10.0)
        return listOf(DemoMessage(ENERGY, docs.getValue(ENERGY).toString()), DemoMessage(OUTDOOR, docs.getValue(OUTDOOR).toString()))
    }

    private fun areas() {
        val areas = listOf("Living room", "Kitchen", "Bedroom", "Hallway", "Garage").withIndex().joinToString(",") { (i, name) ->
            """{"id":"${name.lowercase().replace(' ', '_')}","name":"$name","order":${i + 1}}"""
        }
        extras += DemoMessage("oikos/ui/_areas", """{"areas":[$areas]}""")
    }

    private fun override(id: String, json: String) {
        extras += DemoMessage("oikos/ui/$id", json)
    }

    private fun config(component: String, id: String, device: String, name: String?, area: String, body: JsonObjectBuilder.() -> Unit) {
        val json = buildJsonObject {
            put("unique_id", id)
            if (name == null) put("name", JsonPrimitive(null as String?)) else put("name", name)
            put("device", buildJsonObject {
                putJsonArray("identifiers") { add("demo_" + device.lowercase().replace(' ', '_')) }
                put("name", device)
                put("manufacturer", "Oikos demo")
                put("suggested_area", area)
            })
            body()
        }
        configs += DemoMessage("homeassistant/$component/oikos_demo/$id/config", json.toString())
    }

    private fun doc(topic: String, json: JsonObject) {
        docs[topic] = json
    }

    private fun echo(commandTopic: String, stateTopic: String, patch: (String) -> JsonObject?) {
        echoes[commandTopic] = Echo(stateTopic, patch)
    }

    private fun light(id: String, device: String, area: String, topic: String, on: Boolean, brightness: Int) {
        config("light", id, device, null, area) {
            put("schema", "json")
            put("brightness", true)
            put("brightness_scale", 255)
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        doc(topic, obj("state" to onOff(on), "brightness" to brightness))
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }

    private fun switch(id: String, device: String, area: String, topic: String, on: Boolean, available: Boolean) {
        config("switch", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.state }}")
            put("command_topic", "$topic/set")
            if (!available) put("availability_topic", "$topic/availability")
        }
        doc(topic, obj("state" to onOff(on)))
        if (!available) extras += DemoMessage("$topic/availability", "offline")
        echo("$topic/set", topic) { p -> if (p == "ON" || p == "OFF") obj("state" to p) else null }
    }

    private fun cover(id: String, device: String, area: String, topic: String) {
        config("cover", id, device, null, area) {
            put("device_class", "shutter")
            put("state_topic", topic)
            put("value_template", "{{ value_json.state }}")
            put("position_topic", topic)
            put("position_template", "{{ value_json.position }}")
            put("command_topic", "$topic/set")
            put("set_position_topic", "$topic/position/set")
        }
        doc(topic, obj("state" to "open", "position" to 60))
        echo("$topic/set", topic) { p ->
            when (p) {
                "OPEN" -> obj("state" to "open", "position" to 100)
                "CLOSE" -> obj("state" to "closed", "position" to 0)
                "STOP" -> obj("state" to "stopped")
                else -> null
            }
        }
        echo("$topic/position/set", topic) { p ->
            p.toIntOrNull()?.coerceIn(0, 100)?.let { obj("state" to if (it == 0) "closed" else "open", "position" to it) }
        }
    }

    private fun climate(id: String, device: String, area: String, topic: String) {
        val modes = listOf("off", "cool", "heat", "auto")
        config("climate", id, device, null, area) {
            putJsonArray("modes") { modes.forEach { add(it) } }
            put("min_temp", 16)
            put("max_temp", 30)
            put("temp_step", 0.5)
            put("current_temperature_topic", topic)
            put("current_temperature_template", "{{ value_json.current }}")
            put("temperature_state_topic", topic)
            put("temperature_state_template", "{{ value_json.target }}")
            put("mode_state_topic", topic)
            put("mode_state_template", "{{ value_json.mode }}")
            put("temperature_command_topic", "$topic/temperature/set")
            put("mode_command_topic", "$topic/mode/set")
        }
        doc(topic, obj("current" to 22.4, "target" to 21.0, "mode" to "heat"))
        echo("$topic/temperature/set", topic) { p -> p.toDoubleOrNull()?.let { obj("target" to it) } }
        echo("$topic/mode/set", topic) { p -> if (p in modes) obj("mode" to p) else null }
    }

    private fun sensor(
        id: String, device: String, name: String?, area: String, topic: String,
        field: String, deviceClass: String, unit: String,
    ) = config("sensor", id, device, name, area) {
        put("state_topic", topic)
        put("value_template", "{{ value_json.$field }}")
        put("device_class", deviceClass)
        put("unit_of_measurement", unit)
        put("state_class", "measurement")
    }

    /** zigbee2mqtt style: JSON booleans for payload_on/off (a door contact is `true` when closed). */
    private fun binarySensor(
        id: String, device: String, area: String, topic: String,
        deviceClass: String, field: String, onValue: Boolean, initial: Boolean,
    ) {
        config("binary_sensor", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.$field }}")
            put("device_class", deviceClass)
            put("payload_on", onValue)
            put("payload_off", !onValue)
        }
        doc(topic, obj(field to initial))
    }

    private fun alarm(id: String, device: String, area: String, topic: String) {
        config("alarm_control_panel", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.state }}")
            put("command_topic", "$topic/set")
            putJsonArray("supported_features") { listOf("arm_home", "arm_away", "arm_night").forEach { add(it) } }
        }
        doc(topic, obj("state" to "disarmed"))
        val states = mapOf("ARM_HOME" to "armed_home", "ARM_AWAY" to "armed_away", "ARM_NIGHT" to "armed_night", "DISARM" to "disarmed")
        echo("$topic/set", topic) { p -> states[p]?.let { obj("state" to it) } }
    }

    private fun number(id: String, device: String, area: String, topic: String) {
        config("number", id, device, null, area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.target }}")
            put("command_topic", "$topic/target/set")
            put("min", 40)
            put("max", 65)
            put("step", 1)
            put("unit_of_measurement", "°C")
        }
        doc(topic, obj("target" to 55))
        echo("$topic/target/set", topic) { p -> p.toDoubleOrNull()?.let { obj("target" to it) } }
    }

    private fun vacuum(area: String, topic: String) {
        val options = listOf("quiet", "balanced", "turbo")
        config("select", "demo_vacuum_mode", "Vacuum", "Power", area) {
            put("state_topic", topic)
            put("value_template", "{{ value_json.mode }}")
            put("command_topic", "$topic/mode/set")
            putJsonArray("options") { options.forEach { add(it) } }
        }
        config("button", "demo_vacuum_start", "Vacuum", "Start", area) {
            put("command_topic", "$topic/start")
        }
        doc(topic, obj("mode" to "balanced"))
        echo("$topic/mode/set", topic) { p -> if (p in options) obj("mode" to p) else null }
    }

    private companion object {
        const val ENERGY = "demo/energy"
        const val OUTDOOR = "demo/outdoor"

        fun onOff(on: Boolean) = if (on) "ON" else "OFF"

        fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
            for ((k, v) in pairs) when (v) {
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Number -> put(k, v)
                else -> error("unsupported demo value $v")
            }
        }

        fun JsonObject.number(key: String): Double = (get(key) as JsonPrimitive).doubleOrNull ?: 0.0
    }
}
```

- [ ] **Step 5: Implement `DemoBroker`**

`core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoBroker.kt`:

```kotlin
package com.gekkotron.oikos.demo

import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.TransportMode
import com.gekkotron.oikos.transport.isValidPublishTopic
import com.gekkotron.oikos.transport.topicMatches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-process broker for demo mode. It keeps retained messages, echoes commands back as state after
 * [echoDelay] (so the pending dot is visible), and can tick live sensors. Nothing leaves the device.
 */
class DemoBroker(
    private val scope: CoroutineScope,
    private val house: DemoHouse = DemoHouse(),
    private val echoDelay: Duration = 300.milliseconds,
) : MqttTransport {
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Connected(TransportMode.DEMO)).asStateFlow()

    private val messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 4096)
    private val mutex = Mutex()
    private val retained = linkedMapOf<String, ByteArray>()
    private var subscriptions: Set<String> = emptySet()

    init {
        for (m in house.retainedMessages()) retained[m.topic] = m.payload.encodeToByteArray()
    }

    override fun messages(): SharedFlow<MqttMessage> = messages

    override suspend fun setSubscriptions(filters: Set<String>) {
        val replay = mutex.withLock {
            val added = filters - subscriptions
            subscriptions = filters
            retained.filterKeys { topic -> added.any { topicMatches(it, topic) } }.toList()
        }
        for ((topic, payload) in replay) messages.emit(MqttMessage(topic, payload, retained = true))
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        if (!isValidPublishTopic(topic)) throw PublishFailed("invalid publish topic '$topic'")
        receive(topic, payload, retain)
        val echo = house.onCommand(topic, payload.decodeToString()) ?: return
        scope.launch {
            delay(echoDelay)
            receive(echo.topic, echo.payload.encodeToByteArray(), retain = true)
        }
    }

    /** Publishes live sensor updates every [interval] until the scope is cancelled. */
    fun startTicking(interval: Duration = 3.seconds) {
        scope.launch {
            while (true) {
                delay(interval)
                for (m in house.tick()) receive(m.topic, m.payload.encodeToByteArray(), retain = true)
            }
        }
    }

    private suspend fun receive(topic: String, payload: ByteArray, retain: Boolean) {
        val deliver = mutex.withLock {
            if (retain) {
                if (payload.isEmpty()) retained.remove(topic) else retained[topic] = payload
            }
            subscriptions.any { topicMatches(it, topic) }
        }
        if (deliver) messages.emit(MqttMessage(topic, payload, retained = false))
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:demo:test :core:source:test`
Expected: PASS. The most likely failures and their fixes:
- `every demo config becomes an entity without diagnostics` lists a skipped config. Fix the offending config key in `DemoHouse`; never relax the test.
- `every stateful entity has a known state` fails for a sensor. Its template field name does not match the doc key.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/transport core/source core/demo
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(demo): in-process DemoBroker with a fake house; EntitySource exposes link" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `:core:designsystem`, tokens, themes, fonts and base components

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `NOTICE`
- Create: `core/designsystem/build.gradle.kts`, `core/designsystem/licenses/{Inter,Fraunces}-OFL.txt`, `core/designsystem/src/main/res/font/{inter,fraunces}.ttf`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/{OikosColors,OikosType,OikosDimens,OikosTheme}.kt`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/{OikosCard,StatusPill,RoomChips,PendingDot}.kt`
- Test: `core/designsystem/src/test/resources/robolectric.properties`, `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/{ContrastTest,ThemeTest,OikosCardTest,RoomChipsTest}.kt`

**Interfaces:**
- Consumes: the `oikos.android.library` and `oikos.android.compose` plugins (Task 1).
- Produces (package `com.gekkotron.oikos.designsystem`):
  - `enum class ThemeChoice { SYSTEM, NIGHT_GLASS, WARM_MINIMAL }`
  - `@Composable fun OikosTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit)`
  - `object OikosTheme { val colors: OikosColors; val type: OikosType }`, with composable getters
  - `@Composable fun OikosBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)`
  - `data class OikosColors(...)`, which has `isDark`, `backgroundTop`, `backgroundBottom`, `card`, `cardBorder`, `cardOnStart`, `cardOnEnd`, `cardOnBorder`, `content`, `contentMuted`, `contentOnCardOn`, `chip`, `chipBorder`, `chipSelected`, `chipSelectedContent`, `connected`, `connectedContainer`, `warning`, `warningContainer`, `offline`, `offlineContainer`, `solar`, `water`, `grid`, `heat` and `shadow`
  - `val NightGlassColors: OikosColors` and `val WarmMinimalColors: OikosColors`
  - `data class OikosType(val display: TextStyle, val cardName: TextStyle, val cardValue: TextStyle, val label: TextStyle, val body: TextStyle)`
  - `object OikosDimens { cardRadius, navRadius, cardPadding, cardMinHeight, gridGap, minTouch }`, all `Dp`
  - `object OikosMotion { SHORT: Int; MEDIUM: Int; Emphasized: Easing }`
  - Package `…designsystem.components`:
    - `OikosCard(modifier, on = false, enabled = true, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)`
    - `enum class PillTone { CONNECTED, WARNING, OFFLINE }`
    - `StatusPill(label: String, tone: PillTone, modifier)`
    - `data class ChipItem(val key: String, val label: String)`
    - `RoomChips(items: List<ChipItem>, selectedKey: String, onSelect: (String) -> Unit, modifier)`
    - `PendingDot(modifier)`

- [ ] **Step 1: Module, fonts and licences**

In `settings.gradle.kts`, append `include(":core:designsystem")`. In `build.gradle.kts`, add `kover(project(":core:designsystem"))`.

`core/designsystem/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
}

android {
    namespace = "com.gekkotron.oikos.designsystem"
}
```

`core/designsystem/src/test/resources/robolectric.properties`:

```properties
sdk=34
```

Download the fonts (both are SIL OFL 1.1):

```bash
mkdir -p core/designsystem/src/main/res/font core/designsystem/licenses
curl -fsSL -o core/designsystem/src/main/res/font/inter.ttf "https://github.com/google/fonts/raw/main/ofl/inter/Inter%5Bopsz,wght%5D.ttf"
curl -fsSL -o core/designsystem/src/main/res/font/fraunces.ttf "https://github.com/google/fonts/raw/main/ofl/fraunces/Fraunces%5BSOFT,WONK,opsz,wght%5D.ttf"
curl -fsSL -o core/designsystem/licenses/Inter-OFL.txt "https://github.com/google/fonts/raw/main/ofl/inter/OFL.txt"
curl -fsSL -o core/designsystem/licenses/Fraunces-OFL.txt "https://github.com/google/fonts/raw/main/ofl/fraunces/OFL.txt"
ls -l core/designsystem/src/main/res/font
```
Expected: `inter.ttf` is about 870 KB and `fraunces.ttf` about 360 KB.

Append to `NOTICE`:

```
This product bundles the Inter and Fraunces typefaces, licensed under the SIL Open Font License 1.1
(see core/designsystem/licenses/).
```

- [ ] **Step 2: Write the failing tests**

`core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/ContrastTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertTrue

/** Spec §5.3: text contrast of at least 4.5:1 in both themes. */
class ContrastTest {
    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b).toDouble()
    }

    private fun pairs(c: OikosColors): Map<String, Pair<Color, Color>> {
        val base = c.backgroundTop // the lighter end of the Night Glass gradient: worst case for light text
        return mapOf(
            "content on background" to (c.content to base),
            "content on card" to (c.content to c.card.compositeOver(base)),
            "muted on card" to (c.contentMuted to c.card.compositeOver(base)),
            "on-card content" to (c.contentOnCardOn to c.cardOnStart.compositeOver(base)),
            "chip" to (c.content to c.chip.compositeOver(base)),
            "selected chip" to (c.chipSelectedContent to c.chipSelected.compositeOver(base)),
            "pill connected" to (c.connected to c.connectedContainer.compositeOver(base)),
            "pill warning" to (c.warning to c.warningContainer.compositeOver(base)),
            "pill offline" to (c.offline to c.offlineContainer.compositeOver(base)),
        )
    }

    @Test
    fun `night glass text pairs reach 4_5 to 1`() = assertAll(NightGlassColors)

    @Test
    fun `warm minimal text pairs reach 4_5 to 1`() = assertAll(WarmMinimalColors)

    private fun assertAll(colors: OikosColors) {
        for ((name, pair) in pairs(colors)) {
            val ratio = contrast(pair.first, pair.second)
            assertTrue(ratio >= 4.5, "$name is only %.2f:1".format(ratio))
        }
    }
}
```

`core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/ThemeTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun colorsFor(choice: ThemeChoice): OikosColors {
        lateinit var captured: OikosColors
        compose.setContent { OikosTheme(choice) { captured = OikosTheme.colors } }
        compose.waitForIdle()
        return captured
    }

    @Test
    fun `night glass is dark`() = assertEquals(NightGlassColors, colorsFor(ThemeChoice.NIGHT_GLASS))

    @Test
    fun `warm minimal is light`() = assertEquals(WarmMinimalColors, colorsFor(ThemeChoice.WARM_MINIMAL))

    @Test
    @Config(qualifiers = "night")
    fun `system follows a dark system`() = assertEquals(NightGlassColors, colorsFor(ThemeChoice.SYSTEM))

    @Test
    @Config(qualifiers = "notnight")
    fun `system follows a light system`() = assertEquals(WarmMinimalColors, colorsFor(ThemeChoice.SYSTEM))
}
```

`core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/OikosCardTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.components.OikosCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class OikosCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(enabled: Boolean = true) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            OikosCard(enabled = enabled, onClick = { events += "click" }, onLongClick = { events += "long" }) { Text("Lamp") }
        }
    }

    @Test
    fun `click and long click are reported`() {
        show()
        compose.onNodeWithText("Lamp").performClick()
        compose.onNodeWithText("Lamp").performTouchInput { longClick() }
        assertEquals(listOf("click", "long"), events)
    }

    @Test
    fun `disabled card ignores clicks`() {
        show(enabled = false)
        compose.onNodeWithText("Lamp").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), events)
    }
}
```

`core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/RoomChipsTest.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.components.ChipItem
import com.gekkotron.oikos.designsystem.components.RoomChips
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RoomChipsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `selected chip is marked and a click reports its key`() {
        val picked = mutableListOf<String>()
        compose.setContent {
            OikosTheme(ThemeChoice.WARM_MINIMAL) {
                RoomChips(listOf(ChipItem("all", "All"), ChipItem("area:kitchen", "Kitchen")), "all", { picked += it })
            }
        }
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Kitchen").assertIsNotSelected().performClick()
        assertEquals(listOf("area:kitchen"), picked)
    }
}
```

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: FAIL to compile, `Unresolved reference 'OikosColors'`.

- [ ] **Step 3: Tokens**

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/OikosColors.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class OikosColors(
    val isDark: Boolean,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val cardOnStart: Color,
    val cardOnEnd: Color,
    val cardOnBorder: Color,
    val content: Color,
    val contentMuted: Color,
    val contentOnCardOn: Color,
    val chip: Color,
    val chipBorder: Color,
    val chipSelected: Color,
    val chipSelectedContent: Color,
    val connected: Color,
    val connectedContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val offline: Color,
    val offlineContainer: Color,
    val solar: Color,
    val water: Color,
    val grid: Color,
    val heat: Color,
    val shadow: Color,
)

private val Ink = Color(0xFFE8ECFF)
private val Night = Color(0xFF0B1020)
private val Amber = Color(0xFFFFC457)
private val Mint = Color(0xFF6FF0BF)
private val Rose = Color(0xFFFF7A9A)

/** Spec §5.1, mockup `visual-style.html` option A. */
val NightGlassColors = OikosColors(
    isDark = true,
    backgroundTop = Color(0xFF1D2B55),
    backgroundBottom = Night,
    card = Color.White.copy(alpha = 0.06f),
    cardBorder = Color.White.copy(alpha = 0.09f),
    cardOnStart = Amber.copy(alpha = 0.28f),
    cardOnEnd = Amber.copy(alpha = 0.06f),
    cardOnBorder = Amber.copy(alpha = 0.35f),
    content = Ink,
    contentMuted = Ink.copy(alpha = 0.65f),
    contentOnCardOn = Ink,
    chip = Color.White.copy(alpha = 0.07f),
    chipBorder = Color.Transparent,
    chipSelected = Ink,
    chipSelectedContent = Night,
    connected = Mint,
    connectedContainer = Color(0xFF50E6AA).copy(alpha = 0.15f),
    warning = Amber,
    warningContainer = Amber.copy(alpha = 0.15f),
    offline = Rose,
    offlineContainer = Rose.copy(alpha = 0.15f),
    solar = Amber,
    water = Color(0xFF58E1FF),
    grid = Color(0xFF8F7BFF),
    heat = Rose,
    shadow = Color.Transparent,
)

private val Paper = Color(0xFFF5F1EA)
private val Espresso = Color(0xFF1F1B16)

/** Spec §5.1, mockup `visual-style.html` option B. */
val WarmMinimalColors = OikosColors(
    isDark = false,
    backgroundTop = Paper,
    backgroundBottom = Paper,
    card = Color(0xFFFFFDF9),
    cardBorder = Color(0xFFEFE8DC),
    cardOnStart = Espresso,
    cardOnEnd = Espresso,
    cardOnBorder = Espresso,
    content = Espresso,
    contentMuted = Espresso.copy(alpha = 0.65f),
    contentOnCardOn = Paper,
    chip = Color.Transparent,
    chipBorder = Color(0xFFD9D0C2),
    chipSelected = Espresso,
    chipSelectedContent = Paper,
    connected = Color(0xFF3F6B3A),
    connectedContainer = Color(0xFFE6EFE3),
    warning = Color(0xFF8A5A00),
    warningContainer = Color(0xFFF6E7C8),
    offline = Color(0xFFA23B4F),
    offlineContainer = Color(0xFFF6DDE2),
    solar = Color(0xFFD98B3A),
    water = Color(0xFF2B8FB3),
    grid = Color(0xFF6A5ACD),
    heat = Color(0xFFC4526B),
    shadow = Color(0xFF3C280A).copy(alpha = 0.10f),
)
```

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/OikosDimens.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.unit.dp

/** Spec §5.1 shared tokens: 4 dp grid, 20/24 dp radii, 48 dp touch targets. */
object OikosDimens {
    val cardRadius = 20.dp
    val navRadius = 24.dp
    val cardPadding = 12.dp
    val cardMinHeight = 88.dp
    val gridGap = 8.dp
    val minTouch = 48.dp
}

/** Spec §5.1: 200–350 ms with emphasized easing. */
object OikosMotion {
    const val SHORT = 200
    const val MEDIUM = 350
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
```

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/OikosType.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(
    variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600), variable(R.font.inter, 700),
)
val Fraunces = FontFamily(variable(R.font.fraunces, 400), variable(R.font.fraunces, 500))

@Immutable
data class OikosType(
    val display: TextStyle,
    val cardName: TextStyle,
    val cardValue: TextStyle,
    val label: TextStyle,
    val body: TextStyle,
)

/** Night Glass titles are bold Inter; Warm Minimal titles use the Fraunces serif (spec §5.1). */
fun oikosType(dark: Boolean) = OikosType(
    display = if (dark) {
        TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.02).em)
    } else {
        TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 28.sp)
    },
    cardName = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    cardValue = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
)

internal fun materialTypography(): Typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontFamily = Inter), titleMedium = titleMedium.copy(fontFamily = Inter),
        titleSmall = titleSmall.copy(fontFamily = Inter), bodyLarge = bodyLarge.copy(fontFamily = Inter),
        bodyMedium = bodyMedium.copy(fontFamily = Inter), bodySmall = bodySmall.copy(fontFamily = Inter),
        labelLarge = labelLarge.copy(fontFamily = Inter), labelMedium = labelMedium.copy(fontFamily = Inter),
        labelSmall = labelSmall.copy(fontFamily = Inter),
    )
}
```

- [ ] **Step 4: Theme and background**

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/OikosTheme.kt`:

```kotlin
package com.gekkotron.oikos.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ThemeChoice { SYSTEM, NIGHT_GLASS, WARM_MINIMAL }

private val LocalOikosColors = staticCompositionLocalOf { NightGlassColors }
private val LocalOikosType = staticCompositionLocalOf { oikosType(dark = true) }

private val NightScheme = darkColorScheme(
    primary = Color(0xFFFFC457), onPrimary = Color(0xFF0B1020), secondary = Color(0xFF6FF0BF),
    background = Color(0xFF0B1020), onBackground = Color(0xFFE8ECFF),
    surface = Color(0xFF151B33), onSurface = Color(0xFFE8ECFF), onSurfaceVariant = Color(0xFFB9BFD6),
    surfaceContainerLow = Color(0xFF151B33), surfaceContainer = Color(0xFF151B33), surfaceContainerHigh = Color(0xFF1B2240),
    secondaryContainer = Color(0xFF2A3358), onSecondaryContainer = Color(0xFFE8ECFF),
    inverseSurface = Color(0xFFE8ECFF), inverseOnSurface = Color(0xFF0B1020), outline = Color(0x33FFFFFF),
)

private val WarmScheme = lightColorScheme(
    primary = Color(0xFF1F1B16), onPrimary = Color(0xFFF5F1EA), secondary = Color(0xFFD98B3A),
    background = Color(0xFFF5F1EA), onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFDF9), onSurface = Color(0xFF1F1B16), onSurfaceVariant = Color(0xFF5E574E),
    surfaceContainerLow = Color(0xFFFFFDF9), surfaceContainer = Color(0xFFFFFDF9), surfaceContainerHigh = Color(0xFFF8F3EB),
    secondaryContainer = Color(0xFFECE5DA), onSecondaryContainer = Color(0xFF1F1B16),
    inverseSurface = Color(0xFF1F1B16), inverseOnSurface = Color(0xFFF5F1EA), outline = Color(0xFFD9D0C2),
)

/** Follows the system unless the user picked a theme (spec §1.1). */
@Composable
fun OikosTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.NIGHT_GLASS -> true
        ThemeChoice.WARM_MINIMAL -> false
    }
    val colors = if (dark) NightGlassColors else WarmMinimalColors
    CompositionLocalProvider(LocalOikosColors provides colors, LocalOikosType provides oikosType(dark)) {
        MaterialTheme(colorScheme = if (dark) NightScheme else WarmScheme, typography = materialTypography(), content = content)
    }
}

object OikosTheme {
    val colors: OikosColors
        @Composable @ReadOnlyComposable get() = LocalOikosColors.current
    val type: OikosType
        @Composable @ReadOnlyComposable get() = LocalOikosType.current
}

/** Night Glass: radial gradient from the top-left; Warm Minimal: flat paper. */
@Composable
fun OikosBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = OikosTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(c.backgroundBottom)
                if (c.backgroundTop != c.backgroundBottom) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(c.backgroundTop, c.backgroundBottom),
                            center = Offset(size.width * 0.2f, 0f),
                            radius = size.maxDimension * 0.9f,
                        ),
                    )
                }
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides c.content) { content() }
    }
}
```

- [ ] **Step 5: Components**

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/OikosCard.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme

/**
 * The base card: glass (Night Glass) or paper (Warm Minimal); `on` switches to the amber gradient or
 * the inverted card. Height is a minimum only, so text can grow at 200 % font scale (spec §5.3).
 */
@Composable
fun OikosCard(
    modifier: Modifier = Modifier,
    on: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = OikosTheme.colors
    val shape = RoundedCornerShape(OikosDimens.cardRadius)
    val background = if (on) Brush.linearGradient(listOf(c.cardOnStart, c.cardOnEnd)) else SolidColor(c.card)
    val clicks = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(enabled = enabled, onClick = onClick ?: {}, onLongClick = onLongClick)
    } else {
        Modifier
    }
    CompositionLocalProvider(LocalContentColor provides if (on) c.contentOnCardOn else c.content) {
        Column(
            modifier
                .alpha(if (enabled) 1f else 0.45f)
                .then(if (c.isDark) Modifier else Modifier.shadow(6.dp, shape, ambientColor = c.shadow, spotColor = c.shadow))
                .clip(shape)
                .background(background)
                .border(1.dp, if (on) c.cardOnBorder else c.cardBorder, shape)
                .then(clicks)
                .heightIn(min = OikosDimens.cardMinHeight)
                .padding(OikosDimens.cardPadding),
            verticalArrangement = Arrangement.SpaceBetween,
            content = content,
        )
    }
}
```

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/StatusPill.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme

enum class PillTone { CONNECTED, WARNING, OFFLINE }

@Composable
fun StatusPill(label: String, tone: PillTone, modifier: Modifier = Modifier) {
    val c = OikosTheme.colors
    val (fg, bg) = when (tone) {
        PillTone.CONNECTED -> c.connected to c.connectedContainer
        PillTone.WARNING -> c.warning to c.warningContainer
        PillTone.OFFLINE -> c.offline to c.offlineContainer
    }
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(6.dp))
        Text(label, style = OikosTheme.type.label, color = fg)
    }
}
```

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/RoomChips.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme

data class ChipItem(val key: String, val label: String)

@Composable
fun RoomChips(items: List<ChipItem>, selectedKey: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = OikosTheme.colors
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.key }) { item ->
            val selected = item.key == selectedKey
            Box(
                Modifier
                    .heightIn(min = OikosDimens.minTouch)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(item.key) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.label,
                    style = OikosTheme.type.label,
                    color = if (selected) c.chipSelectedContent else c.content,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) c.chipSelected else c.chip)
                        .border(1.dp, if (selected) c.chipSelected else c.chipBorder, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
```

`core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/PendingDot.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosMotion
import com.gekkotron.oikos.designsystem.OikosTheme

/** Pulses while an optimistic command waits for confirmation. */
@Composable
fun PendingDot(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "pending").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(OikosMotion.MEDIUM, easing = OikosMotion.Emphasized), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(OikosTheme.colors.solar))
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: PASS (8 tests). If a contrast pair fails, darken or lighten that token until it reaches 4.5:1; never lower the threshold.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts NOTICE core/designsystem
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(designsystem): Night Glass and Warm Minimal tokens, bundled fonts, card, pill, chips" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `:feature:home` presentation, the UI model and the ViewModel

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `feature/home/build.gradle.kts`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/{HomeModels,CardFormatter,HomeUiBuilder,StepMath,HomeViewModel}.kt`
- Test: `feature/home/src/test/resources/robolectric.properties`, `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/{TestEntities,FakeEntitySource}.kt`, `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/{CardFormatterTest,HomeUiBuilderTest,StepMathTest,HomeViewModelTest}.kt`

**Interfaces:**
- Consumes:
  - `EntitySource` (with `link`, from Task 2)
  - the model types `Entity`, `EntityState`, `StateValue`, `Area`, `Capabilities`, `Command`, `CommandResult`, `CommandFailure`, `Kind`, `CoverState`, `AlarmState`, `UiOverride` and `UNASSIGNED_AREA`
  - `LinkState` and `TransportMode`
- Produces (package `com.gekkotron.oikos.feature.home.presentation`):
  - `sealed interface RoomFilter { val key: String }` with `All`, `Favorites`, `Area(id)` and `RoomFilter.fromKey(key)`
  - `data class ChipUi(val filter: RoomFilter, val label: String?)`, where a null label means the UI localizes it (All / Favourites / Unassigned)
  - `enum class TapAction { TOGGLE, PRESS, OPEN_SHEET, NONE }`
  - `sealed interface CardValue` with `None`, `OnOff(on)`, `Brightness(percent)`, `Measurement(text, unit)`, `Text(value)`, `Cover(state, position)`, `Climate(current, target, mode)`, `Alarm(state)` and `Contact(deviceClass, on)`
  - `data class CardUi(id, name, kind, deviceClass, areaName: String?, value: CardValue, state: StateValue, capabilities: Capabilities, unit: String?, on, wide, available, pending, tap: TapAction, hasSheet: Boolean)`
  - `data class HomeUiState(loading, link, hero: CardUi?, chips: List<ChipUi>, selected: RoomFilter, cards: List<CardUi>)` with `fun card(id: String): CardUi?`
  - `sealed interface HomeEvent` with `NotConfirmed(id, command)` and `Rejected(id, reason)`
  - `object CardFormatter { fun format(entity, value, locale): CardValue; fun percent(brightness: Int): Int; fun number(value: Double, locale: Locale): String }`
  - `object HomeUiBuilder { const val HERO_WEATHER = "hero_weather"; fun build(entities, states, areas, selected, link, locale): HomeUiState }`
  - `object StepMath { fun stepTemperature(target: Double, delta: Double, min: Double, max: Double): Double; fun snap(value: Double, min: Double, step: Double): Double }`
  - `class HomeViewModel(source: EntitySource, savedState: SavedStateHandle)` with `state: StateFlow<HomeUiState>`, `events: Flow<HomeEvent>`, `select(RoomFilter)`, `tap(CardUi)` and `send(id: String, command: Command)`

- [ ] **Step 1: Module**

In `settings.gradle.kts`, append `include(":feature:home")`. In `build.gradle.kts`, add `kover(project(":feature:home"))`.

`feature/home/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.home"
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
```

`feature/home/src/test/resources/robolectric.properties`:

```properties
sdk=34
```

- [ ] **Step 2: Test helpers**

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/TestEntities.kt`:

```kotlin
package com.gekkotron.oikos.feature.home

import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride

object NoBindings : Bindings

fun testEntity(
    id: String,
    kind: Kind,
    name: String = id,
    area: String = UNASSIGNED_AREA,
    deviceClass: String? = null,
    unit: String? = null,
    ui: UiOverride = UiOverride(),
    capabilities: Capabilities = Capabilities(),
) = Entity(id, null, name, kind, deviceClass, unit, null, capabilities, NoBindings, area, ui)
```

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/FakeEntitySource.kt`:

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

    override fun states() = stateMap

    override suspend fun send(uniqueId: String, command: Command): CommandResult {
        sent += uniqueId to command
        return result
    }

    override suspend fun setOverride(uniqueId: String, override: UiOverride) = Unit

    override suspend fun setAreas(areas: List<Area>) = Unit
}
```

- [ ] **Step 3: Write the failing presentation tests**

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/CardFormatterTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class CardFormatterTest {
    private val sensor = testEntity("t", Kind.SENSOR, unit = "°C", deviceClass = "temperature")

    @Test
    fun `unknown and unsupported states render as None`() {
        assertEquals(CardValue.None, CardFormatter.format(sensor, StateValue.Unknown, Locale.US))
        assertEquals(CardValue.None, CardFormatter.format(sensor, StateValue.Unsupported, Locale.US))
    }

    @Test
    fun `numbers follow the locale with at most one decimal`() {
        assertEquals(CardValue.Measurement("21.5", "°C"), CardFormatter.format(sensor, StateValue.Numeric(21.54), Locale.US))
        assertEquals(CardValue.Measurement("21,5", "°C"), CardFormatter.format(sensor, StateValue.Numeric(21.5), Locale.FRANCE))
        assertEquals("2,140", CardFormatter.number(2140.0, Locale.US))
        assertEquals("19", CardFormatter.number(19.0, Locale.US))
    }

    @Test
    fun `lights show brightness percent only when on`() {
        val light = testEntity("l", Kind.LIGHT)
        assertEquals(CardValue.Brightness(71), CardFormatter.format(light, StateValue.Light(true, 180), Locale.US))
        assertEquals(CardValue.OnOff(false), CardFormatter.format(light, StateValue.Light(false, 180), Locale.US))
        assertEquals(CardValue.OnOff(true), CardFormatter.format(light, StateValue.Light(true, null), Locale.US))
    }

    @Test
    fun `binary sensors become Contact with their device class`() {
        val door = testEntity("d", Kind.BINARY_SENSOR, deviceClass = "door")
        assertEquals(CardValue.Contact("door", true), CardFormatter.format(door, StateValue.OnOff(true), Locale.US))
        val sw = testEntity("s", Kind.SWITCH)
        assertEquals(CardValue.OnOff(true), CardFormatter.format(sw, StateValue.OnOff(true), Locale.US))
    }

    @Test
    fun `cover, climate and alarm pass through`() {
        assertEquals(CardValue.Cover(CoverState.OPEN, 60), CardFormatter.format(sensor, StateValue.Cover(CoverState.OPEN, 60), Locale.US))
        assertEquals(
            CardValue.Climate("22.4", "21", "heat"),
            CardFormatter.format(sensor, StateValue.Climate("heat", 21.0, 22.4), Locale.US),
        )
        assertEquals(CardValue.Alarm(AlarmState.DISARMED), CardFormatter.format(sensor, StateValue.Alarm(AlarmState.DISARMED), Locale.US))
    }
}
```

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeUiBuilderTest {
    private val areas = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"), Area("garage", "Garage"))
    private val link = LinkState.Connected(TransportMode.DEMO)

    private fun build(vararg entities: com.gekkotron.oikos.model.Entity, states: Map<String, EntityState> = emptyMap(), selected: RoomFilter = RoomFilter.All, areaList: List<Area> = areas) =
        HomeUiBuilder.build(entities.associateBy { it.uniqueId }, states, areaList, selected, link, Locale.US)

    @Test
    fun `empty source is loading`() {
        val s = build()
        assertTrue(s.loading)
        assertEquals(listOf(ChipUi(RoomFilter.All, null)), s.chips)
    }

    @Test
    fun `hidden entities are dropped and the hero is taken out of the grid`() {
        val s = build(
            testEntity("lamp", Kind.LIGHT, area = "living"),
            testEntity("secret", Kind.SWITCH, area = "living", ui = UiOverride(hidden = true)),
            testEntity("outdoor", Kind.SENSOR, ui = UiOverride(cardStyle = HomeUiBuilder.HERO_WEATHER)),
        )
        assertFalse(s.loading)
        assertEquals(listOf("lamp"), s.cards.map { it.id })
        assertEquals("outdoor", s.hero?.id)
        assertEquals("outdoor", s.card("outdoor")?.id)
    }

    @Test
    fun `chips list All, Favourites when any, then only areas with visible cards in area order`() {
        val s = build(
            testEntity("coffee", Kind.SWITCH, area = "kitchen"),
            testEntity("lamp", Kind.LIGHT, area = "living", ui = UiOverride(favorite = true)),
            testEntity("x", Kind.SWITCH),
        )
        assertEquals(
            listOf(RoomFilter.All, RoomFilter.Favorites, RoomFilter.Area("living"), RoomFilter.Area("kitchen")),
            s.chips.map { it.filter },
        )
        assertEquals("Living room", s.chips[2].label)
        // "x" is unassigned but Unassigned is not in `areas` here, so it has no chip; see the next test.
    }

    @Test
    fun `unassigned chip has a null label for localisation`() {
        val s = build(testEntity("x", Kind.SWITCH), areaList = areas + Area(UNASSIGNED_AREA, UNASSIGNED_AREA))
        assertEquals(ChipUi(RoomFilter.Area(UNASSIGNED_AREA), null), s.chips.last())
        assertNull(s.cards.single().areaName)
    }

    @Test
    fun `filters select favourites or one area`() {
        val entities = arrayOf(
            testEntity("coffee", Kind.SWITCH, area = "kitchen"),
            testEntity("lamp", Kind.LIGHT, area = "living", ui = UiOverride(favorite = true)),
        )
        assertEquals(listOf("lamp"), build(*entities, selected = RoomFilter.Favorites).cards.map { it.id })
        assertEquals(listOf("coffee"), build(*entities, selected = RoomFilter.Area("kitchen")).cards.map { it.id })
    }

    @Test
    fun `selection of a vanished area falls back to All`() {
        val s = build(testEntity("coffee", Kind.SWITCH, area = "kitchen"), selected = RoomFilter.Area("garage"))
        assertEquals(RoomFilter.All, s.selected)
        assertEquals(listOf("coffee"), s.cards.map { it.id })
        val noFavourites = build(testEntity("coffee", Kind.SWITCH, area = "kitchen"), selected = RoomFilter.Favorites)
        assertEquals(RoomFilter.All, noFavourites.selected)
    }

    @Test
    fun `cards sort by override order, then area order, then name`() {
        val s = build(
            testEntity("b", Kind.SWITCH, name = "Blender", area = "kitchen"),
            testEntity("a", Kind.SWITCH, name = "apple", area = "kitchen"),
            testEntity("z", Kind.SWITCH, name = "Zeta", area = "living"),
            testEntity("first", Kind.SWITCH, name = "Last name", area = "garage", ui = UiOverride(order = 1)),
        )
        assertEquals(listOf("first", "z", "a", "b"), s.cards.map { it.id })
    }

    @Test
    fun `tap actions, sheets, on-state and wide cards follow the kind`() {
        val s = build(
            testEntity("light", Kind.LIGHT, capabilities = Capabilities(brightness = true)),
            testEntity("switch", Kind.SWITCH),
            testEntity("cover", Kind.COVER),
            testEntity("button", Kind.BUTTON),
            testEntity("power", Kind.SENSOR, deviceClass = "power"),
            testEntity("temp", Kind.SENSOR, deviceClass = "temperature"),
            states = mapOf(
                "light" to EntityState("light", StateValue.Light(true, 10)),
                "switch" to EntityState("switch", StateValue.OnOff(false)),
                "cover" to EntityState("cover", StateValue.Cover(CoverState.CLOSED, 0)),
            ),
        )
        val byId = s.cards.associateBy { it.id }
        assertEquals(TapAction.TOGGLE, byId.getValue("light").tap)
        assertTrue(byId.getValue("light").hasSheet)
        assertTrue(byId.getValue("light").on)
        assertEquals(TapAction.TOGGLE, byId.getValue("switch").tap)
        assertFalse(byId.getValue("switch").hasSheet)
        assertEquals(TapAction.OPEN_SHEET, byId.getValue("cover").tap)
        assertFalse(byId.getValue("cover").on)
        assertEquals(TapAction.PRESS, byId.getValue("button").tap)
        assertEquals(TapAction.NONE, byId.getValue("temp").tap)
        assertTrue(byId.getValue("power").wide)
        assertFalse(byId.getValue("temp").wide)
    }

    @Test
    fun `availability and pending come from the state, defaulting to available`() {
        val s = build(
            testEntity("plug", Kind.SWITCH),
            testEntity("fresh", Kind.SWITCH),
            states = mapOf("plug" to EntityState("plug", StateValue.OnOff(false), available = false, pending = true)),
        )
        val byId = s.cards.associateBy { it.id }
        assertFalse(byId.getValue("plug").available)
        assertTrue(byId.getValue("plug").pending)
        assertTrue(byId.getValue("fresh").available)
        assertEquals(CardValue.None, byId.getValue("fresh").value)
    }

    @Test
    fun `room filter keys round-trip`() {
        for (f in listOf(RoomFilter.All, RoomFilter.Favorites, RoomFilter.Area("living room"))) {
            assertEquals(f, RoomFilter.fromKey(f.key))
        }
        assertEquals(RoomFilter.All, RoomFilter.fromKey("garbage"))
    }
}
```

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/StepMathTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import org.junit.Test
import kotlin.test.assertEquals

class StepMathTest {
    @Test
    fun `setpoint steps are clamped and free of float drift`() {
        assertEquals(21.5, StepMath.stepTemperature(21.0, 0.5, 16.0, 30.0))
        assertEquals(30.0, StepMath.stepTemperature(29.8, 0.5, 16.0, 30.0))
        assertEquals(16.0, StepMath.stepTemperature(16.2, -0.5, 16.0, 30.0))
        assertEquals(20.3, StepMath.stepTemperature(20.2, 0.1, 7.0, 35.0))
    }

    @Test
    fun `slider values snap to the step grid from min`() {
        assertEquals(58.0, StepMath.snap(57.6, 40.0, 1.0))
        assertEquals(45.0, StepMath.snap(46.0, 40.0, 5.0))
        assertEquals(0.3, StepMath.snap(0.29, 0.0, 0.1))
    }
}
```

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModelTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.SavedStateHandle
import com.gekkotron.oikos.feature.home.FakeEntitySource
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeEntitySource().apply {
        entities.value = mapOf(
            "lamp" to testEntity("lamp", Kind.LIGHT, area = "kitchen"),
            "shutter" to testEntity("shutter", Kind.COVER, area = "kitchen"),
        )
        areas.value = listOf(Area("kitchen", "Kitchen"))
        stateMap.value = mapOf("lamp" to EntityState("lamp", StateValue.Light(true, 255)))
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state reflects the source`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        assertFalse(vm.state.value.loading)
        assertEquals(setOf("lamp", "shutter"), vm.state.value.cards.map { it.id }.toSet())
    }

    @Test
    fun `selected room survives recreation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        HomeViewModel(source, handle).select(RoomFilter.Area("kitchen"))
        val recreated = HomeViewModel(source, handle)
        backgroundScope.launch { recreated.state.collect {} }
        assertEquals(RoomFilter.Area("kitchen"), recreated.state.value.selected)
    }

    @Test
    fun `tapping toggles lights and ignores sheet cards`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle())
        backgroundScope.launch { vm.state.collect {} }
        val cards = vm.state.value.cards.associateBy { it.id }
        vm.tap(cards.getValue("lamp"))
        vm.tap(cards.getValue("shutter"))
        assertEquals(listOf("lamp" to Command.Toggle), source.sent)
    }

    @Test
    fun `unconfirmed command becomes a NotConfirmed event`() = runTest(dispatcher) {
        val vm = HomeViewModel(source, SavedStateHandle())
        source.commandFailures.emit(CommandFailure("lamp", Command.Toggle, "timeout"))
        assertEquals(HomeEvent.NotConfirmed("lamp", Command.Toggle), vm.events.first())
    }

    @Test
    fun `rejected command becomes a Rejected event`() = runTest(dispatcher) {
        source.result = CommandResult.Rejected("unavailable")
        val vm = HomeViewModel(source, SavedStateHandle())
        vm.send("lamp", Command.TurnOff)
        assertEquals(HomeEvent.Rejected("lamp", "unavailable"), vm.events.first())
    }
}
```

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: FAIL to compile, `Unresolved reference 'CardValue'`.

- [ ] **Step 4: Models**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState

sealed interface RoomFilter {
    val key: String

    data object All : RoomFilter {
        override val key = "all"
    }

    data object Favorites : RoomFilter {
        override val key = "favorites"
    }

    data class Area(val id: String) : RoomFilter {
        override val key get() = AREA_PREFIX + id
    }

    companion object {
        private const val AREA_PREFIX = "area:"

        fun fromKey(key: String): RoomFilter = when {
            key == Favorites.key -> Favorites
            key.startsWith(AREA_PREFIX) -> Area(key.removePrefix(AREA_PREFIX))
            else -> All
        }
    }
}

/** A null [label] is localized by the UI: All, Favourites or Unassigned. */
data class ChipUi(val filter: RoomFilter, val label: String?)

enum class TapAction { TOGGLE, PRESS, OPEN_SHEET, NONE }

/** What a card shows, before localisation. */
sealed interface CardValue {
    data object None : CardValue
    data class OnOff(val on: Boolean) : CardValue
    data class Brightness(val percent: Int) : CardValue
    data class Measurement(val text: String, val unit: String?) : CardValue
    data class Text(val value: String) : CardValue
    data class Cover(val state: CoverState, val position: Int?) : CardValue
    data class Climate(val current: String?, val target: String?, val mode: String?) : CardValue
    data class Alarm(val state: AlarmState) : CardValue
    data class Contact(val deviceClass: String?, val on: Boolean) : CardValue
}

data class CardUi(
    val id: String,
    val name: String,
    val kind: Kind,
    val deviceClass: String?,
    /** Null means Unassigned (localized by the UI). */
    val areaName: String?,
    val value: CardValue,
    val state: StateValue,
    val capabilities: Capabilities,
    val unit: String?,
    val on: Boolean,
    val wide: Boolean,
    val available: Boolean,
    val pending: Boolean,
    val tap: TapAction,
    val hasSheet: Boolean,
)

data class HomeUiState(
    val loading: Boolean = true,
    val link: LinkState = LinkState.Connecting,
    val hero: CardUi? = null,
    val chips: List<ChipUi> = listOf(ChipUi(RoomFilter.All, null)),
    val selected: RoomFilter = RoomFilter.All,
    val cards: List<CardUi> = emptyList(),
) {
    fun card(id: String): CardUi? = cards.firstOrNull { it.id == id } ?: hero?.takeIf { it.id == id }
}

sealed interface HomeEvent {
    data class NotConfirmed(val id: String, val command: Command) : HomeEvent
    data class Rejected(val id: String, val reason: String) : HomeEvent
}
```

- [ ] **Step 5: Formatter, step math and builder**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/CardFormatter.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

object CardFormatter {
    fun format(entity: Entity, value: StateValue, locale: Locale): CardValue = when (value) {
        StateValue.Unknown, StateValue.Unsupported -> CardValue.None
        is StateValue.OnOff ->
            if (entity.kind == Kind.BINARY_SENSOR) CardValue.Contact(entity.deviceClass, value.on) else CardValue.OnOff(value.on)
        is StateValue.Light -> {
            val brightness = value.brightness
            if (value.on && brightness != null) CardValue.Brightness(percent(brightness)) else CardValue.OnOff(value.on)
        }
        is StateValue.Numeric -> CardValue.Measurement(number(value.value, locale), entity.unit)
        is StateValue.Text -> CardValue.Text(value.value)
        is StateValue.Cover -> CardValue.Cover(value.state, value.position)
        is StateValue.Climate ->
            CardValue.Climate(value.current?.let { number(it, locale) }, value.target?.let { number(it, locale) }, value.mode)
        is StateValue.Alarm -> CardValue.Alarm(value.state)
    }

    /** 0..255 → 0..100. */
    fun percent(brightness: Int): Int = (brightness * 100 / 255.0).roundToInt()

    fun number(value: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }.format(value)
}
```

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/StepMath.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

object StepMath {
    /** [target] + [delta], clamped to [min]..[max], rounded to 2 decimals so 0.1 steps never drift. */
    fun stepTemperature(target: Double, delta: Double, min: Double, max: Double): Double =
        round2((target + delta).coerceIn(min, max))

    /** Nearest value on the grid min, min + step, … */
    fun snap(value: Double, min: Double, step: Double): Double =
        if (step <= 0) value else round2(min + ((value - min) / step).roundToLong() * step)

    private fun round2(v: Double) = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toDouble()
}
```

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState
import java.util.Locale

/** Entities + states + areas → everything the Home screen draws (spec §5.2). */
object HomeUiBuilder {
    const val HERO_WEATHER = "hero_weather"
    private val WIDE_CLASSES = setOf("power", "energy")
    private val SHEET_KINDS = setOf(Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL)

    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        selected: RoomFilter,
        link: LinkState,
        locale: Locale,
    ): HomeUiState {
        val areaIndex = areas.withIndex().associate { (i, a) -> a.id to i }
        val areaNames = areas.associate { it.id to it.name }
        val visible = entities.values.filterNot { it.ui.hidden }
        val hero = visible.filter { it.ui.cardStyle == HERO_WEATHER }.minByOrNull { it.uniqueId }
        val grid = visible.filter { it.uniqueId != hero?.uniqueId }

        val used = grid.mapTo(mutableSetOf()) { it.area }
        val chips = buildList {
            add(ChipUi(RoomFilter.All, null))
            if (grid.any { it.ui.favorite }) add(ChipUi(RoomFilter.Favorites, null))
            for (area in areas) {
                if (area.id in used) add(ChipUi(RoomFilter.Area(area.id), area.name.takeUnless { area.id == UNASSIGNED_AREA }))
            }
        }
        val effective = selected.takeIf { s -> chips.any { it.filter == s } } ?: RoomFilter.All

        val cards = grid
            .filter { matches(it, effective) }
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
        )
    }

    private fun matches(entity: Entity, filter: RoomFilter) = when (filter) {
        RoomFilter.All -> true
        RoomFilter.Favorites -> entity.ui.favorite
        is RoomFilter.Area -> entity.area == filter.id
    }

    private fun card(entity: Entity, state: EntityState?, areaNames: Map<String, String>, locale: Locale): CardUi {
        val value = state?.value ?: StateValue.Unknown
        return CardUi(
            id = entity.uniqueId,
            name = entity.name,
            kind = entity.kind,
            deviceClass = entity.deviceClass,
            areaName = if (entity.area == UNASSIGNED_AREA) null else areaNames[entity.area] ?: entity.area,
            value = CardFormatter.format(entity, value, locale),
            state = value,
            capabilities = entity.capabilities,
            unit = entity.unit,
            on = isOn(entity.kind, value),
            wide = entity.kind == Kind.SENSOR && entity.deviceClass in WIDE_CLASSES,
            available = state?.available ?: true,
            pending = state?.pending ?: false,
            tap = tapFor(entity.kind),
            hasSheet = entity.kind in SHEET_KINDS || (entity.kind == Kind.LIGHT && entity.capabilities.brightness),
        )
    }

    private fun tapFor(kind: Kind) = when (kind) {
        Kind.LIGHT, Kind.SWITCH -> TapAction.TOGGLE
        Kind.BUTTON -> TapAction.PRESS
        Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL -> TapAction.OPEN_SHEET
        Kind.SENSOR, Kind.BINARY_SENSOR -> TapAction.NONE
    }

    private fun isOn(kind: Kind, v: StateValue) = when (v) {
        is StateValue.Light -> v.on
        is StateValue.OnOff -> kind == Kind.SWITCH && v.on
        is StateValue.Cover -> v.state == CoverState.OPEN || v.state == CoverState.OPENING ||
            (v.state == CoverState.STOPPED && (v.position ?: 0) > 0)
        is StateValue.Climate -> v.mode != null && v.mode != "off"
        else -> false
    }
}
```

- [ ] **Step 6: ViewModel**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModel.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val source: EntitySource,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        source.entities,
        source.states(),
        source.areas,
        savedState.getStateFlow(KEY_FILTER, RoomFilter.All.key),
        source.link,
    ) { entities, states, areas, key, link ->
        HomeUiBuilder.build(entities, states, areas, RoomFilter.fromKey(key), link, Locale.getDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            source.commandFailures.collect { _events.send(HomeEvent.NotConfirmed(it.uniqueId, it.command)) }
        }
    }

    fun select(filter: RoomFilter) {
        savedState[KEY_FILTER] = filter.key
    }

    fun tap(card: CardUi) {
        when (card.tap) {
            TapAction.TOGGLE -> send(card.id, Command.Toggle)
            TapAction.PRESS -> send(card.id, Command.Press)
            TapAction.OPEN_SHEET, TapAction.NONE -> Unit
        }
    }

    fun send(id: String, command: Command) {
        viewModelScope.launch {
            val result = source.send(id, command)
            if (result is CommandResult.Rejected) _events.send(HomeEvent.Rejected(id, result.reason))
        }
    }

    private companion object {
        const val KEY_FILTER = "home.filter"
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS (all presentation tests).

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): UI model builder, card formatter and HomeViewModel" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `:feature:home` UI: the Home screen, entity cards and control sheet

**Files:**
- Create: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/{CardText,KindGlyphs,EntityCard,ControlSheet,LinkPill,HomeEventsEffect,HomeScreen}.kt`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/{HomeScreenTest,HomeEventsEffectTest}.kt`

**Interfaces:**
- Consumes:
  - from Task 4: `HomeUiState`, `CardUi`, `CardValue`, `ChipUi`, `RoomFilter`, `TapAction`, `HomeEvent`, `HomeViewModel`, `HomeUiBuilder`, `CardFormatter` and `StepMath`
  - from Task 3: `OikosTheme`, `OikosCard`, `StatusPill`, `PillTone`, `RoomChips`, `ChipItem` and `PendingDot`
- Produces (package `com.gekkotron.oikos.feature.home.ui`):
  - `@Composable fun HomeRoute(viewModel: HomeViewModel = hiltViewModel())`, which the app uses
  - `@Composable fun HomeScreen(state: HomeUiState, onSelect: (RoomFilter) -> Unit, onTap: (CardUi) -> Unit, onSend: (String, Command) -> Unit, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }, now: LocalTime = LocalTime.now())`
  - `@Composable fun HomeEventsEffect(events: Flow<HomeEvent>, snackbar: SnackbarHostState, onRetry: (String, Command) -> Unit)`
  - Each card has the test tag `card-<uniqueId>`.

- [ ] **Step 1: Strings (EN + FR)**

`feature/home/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="greeting_morning">Good morning</string>
    <string name="greeting_afternoon">Good afternoon</string>
    <string name="greeting_evening">Good evening</string>
    <string name="home_discovering">Discovering devices…</string>
    <string name="chip_all">All</string>
    <string name="chip_favorites">Favourites</string>
    <string name="area_unassigned">Unassigned</string>
    <string name="pill_connecting">Connecting…</string>
    <string name="pill_direct">Wi-Fi direct</string>
    <string name="pill_gateway">Gateway</string>
    <string name="pill_demo">Demo</string>
    <string name="pill_reconnecting">Reconnecting…</string>
    <string name="pill_offline">Offline</string>
    <string name="card_description">%1$s, %2$s, %3$s</string>
    <string name="value_none">—</string>
    <string name="value_on">On</string>
    <string name="value_off">Off</string>
    <string name="value_percent">%1$d%%</string>
    <string name="value_unavailable">Unavailable</string>
    <string name="value_cover_open">Open</string>
    <string name="value_cover_closed">Closed</string>
    <string name="value_cover_opening">Opening…</string>
    <string name="value_cover_closing">Closing…</string>
    <string name="value_cover_stopped">Stopped</string>
    <string name="value_cover_position">%1$d%% open</string>
    <string name="value_contact_open">Open</string>
    <string name="value_contact_closed">Closed</string>
    <string name="value_motion_detected">Motion</string>
    <string name="value_motion_clear">Clear</string>
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
    <string name="mode_off">Off</string>
    <string name="mode_cool">Cool</string>
    <string name="mode_heat">Heat</string>
    <string name="mode_auto">Auto</string>
    <string name="mode_dry">Dry</string>
    <string name="mode_fan_only">Fan</string>
    <string name="sheet_power">Power</string>
    <string name="sheet_brightness">Brightness</string>
    <string name="sheet_open">Open</string>
    <string name="sheet_stop">Stop</string>
    <string name="sheet_close">Close</string>
    <string name="sheet_position">Position</string>
    <string name="sheet_target">Target</string>
    <string name="sheet_decrease">Decrease</string>
    <string name="sheet_increase">Increase</string>
    <string name="sheet_code">Code</string>
    <string name="sheet_arm_home">Home</string>
    <string name="sheet_arm_away">Away</string>
    <string name="sheet_arm_night">Night</string>
    <string name="sheet_arm_vacation">Vacation</string>
    <string name="sheet_arm_custom">Custom</string>
    <string name="sheet_disarm">Disarm</string>
    <string name="home_not_confirmed">Not confirmed</string>
    <string name="home_retry">Retry</string>
    <string name="home_rejected">Command refused: %1$s</string>
</resources>
```

`feature/home/src/main/res/values-fr/strings.xml`:

```xml
<resources>
    <string name="greeting_morning">Bonjour</string>
    <string name="greeting_afternoon">Bon après-midi</string>
    <string name="greeting_evening">Bonsoir</string>
    <string name="home_discovering">Recherche des appareils…</string>
    <string name="chip_all">Tout</string>
    <string name="chip_favorites">Favoris</string>
    <string name="area_unassigned">Non assigné</string>
    <string name="pill_connecting">Connexion…</string>
    <string name="pill_direct">Wi-Fi direct</string>
    <string name="pill_gateway">Passerelle</string>
    <string name="pill_demo">Démo</string>
    <string name="pill_reconnecting">Reconnexion…</string>
    <string name="pill_offline">Hors ligne</string>
    <string name="card_description">%1$s, %2$s, %3$s</string>
    <string name="value_none">—</string>
    <string name="value_on">Allumé</string>
    <string name="value_off">Éteint</string>
    <string name="value_percent">%1$d %%</string>
    <string name="value_unavailable">Indisponible</string>
    <string name="value_cover_open">Ouvert</string>
    <string name="value_cover_closed">Fermé</string>
    <string name="value_cover_opening">Ouverture…</string>
    <string name="value_cover_closing">Fermeture…</string>
    <string name="value_cover_stopped">Arrêté</string>
    <string name="value_cover_position">Ouvert à %1$d %%</string>
    <string name="value_contact_open">Ouvert</string>
    <string name="value_contact_closed">Fermé</string>
    <string name="value_motion_detected">Mouvement</string>
    <string name="value_motion_clear">Calme</string>
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
    <string name="mode_off">Arrêt</string>
    <string name="mode_cool">Froid</string>
    <string name="mode_heat">Chaud</string>
    <string name="mode_auto">Auto</string>
    <string name="mode_dry">Sec</string>
    <string name="mode_fan_only">Ventilation</string>
    <string name="sheet_power">Alimentation</string>
    <string name="sheet_brightness">Luminosité</string>
    <string name="sheet_open">Ouvrir</string>
    <string name="sheet_stop">Stop</string>
    <string name="sheet_close">Fermer</string>
    <string name="sheet_position">Position</string>
    <string name="sheet_target">Consigne</string>
    <string name="sheet_decrease">Diminuer</string>
    <string name="sheet_increase">Augmenter</string>
    <string name="sheet_code">Code</string>
    <string name="sheet_arm_home">Maison</string>
    <string name="sheet_arm_away">Absent</string>
    <string name="sheet_arm_night">Nuit</string>
    <string name="sheet_arm_vacation">Vacances</string>
    <string name="sheet_arm_custom">Perso</string>
    <string name="sheet_disarm">Désarmer</string>
    <string name="home_not_confirmed">Non confirmé</string>
    <string name="home_retry">Réessayer</string>
    <string name="home_rejected">Commande refusée : %1$s</string>
</resources>
```

- [ ] **Step 2: Write the failing UI tests**

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreenTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
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
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val entities = listOf(
        testEntity("lamp", Kind.LIGHT, "Floor lamp", area = "living", capabilities = Capabilities(brightness = true)),
        testEntity("shutter", Kind.COVER, "Shutter", area = "living", capabilities = Capabilities(position = true)),
        testEntity(
            "ac", Kind.CLIMATE, "Air conditioner", area = "living",
            capabilities = Capabilities(hvacModes = listOf("off", "heat"), minTemp = 16.0, maxTemp = 30.0, tempStep = 0.5),
        ),
        testEntity("coffee", Kind.SWITCH, "Coffee machine", area = "kitchen"),
        testEntity("plug", Kind.SWITCH, "Garage plug", area = "garage"),
    ).associateBy { it.uniqueId }
    private val states = mapOf(
        "lamp" to EntityState("lamp", StateValue.Light(true, 180)),
        "shutter" to EntityState("shutter", StateValue.Cover(CoverState.OPEN, 60)),
        "ac" to EntityState("ac", StateValue.Climate("heat", 21.0, 22.4)),
        "coffee" to EntityState("coffee", StateValue.OnOff(false)),
        "plug" to EntityState("plug", StateValue.OnOff(false), available = false),
    )
    private val areas = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"), Area("garage", "Garage"))

    private val taps = mutableListOf<String>()
    private val sends = mutableListOf<Pair<String, Command>>()
    private val selects = mutableListOf<RoomFilter>()

    private fun state(): HomeUiState =
        HomeUiBuilder.build(entities, states, areas, RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US)

    private fun show(fontScale: () -> Float = { 1f }) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale())) {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                HomeScreen(
                    state = state(),
                    onSelect = { selects += it },
                    onTap = { taps += it.id },
                    onSend = { id, c -> sends += id to c },
                    now = LocalTime.NOON,
                )
            }
        }
    }

    @Test
    fun `tapping a light card calls onTap`() {
        show()
        compose.onNodeWithTag("card-lamp").performClick()
        assertEquals(listOf("lamp"), taps)
    }

    @Test
    fun `card describes name, state and room`() {
        show()
        compose.onNodeWithContentDescription("Floor lamp, 71%, Living room").assertExists()
        compose.onNodeWithContentDescription("Shutter, 60% open, Living room").assertExists()
    }

    @Test
    fun `header shows greeting and demo pill`() {
        show()
        compose.onNodeWithText("Good afternoon").assertExists()
        compose.onNodeWithText("Demo").assertExists()
    }

    @Test
    fun `tapping a cover opens its control sheet and Close sends Command_Close`() {
        show()
        compose.onNodeWithTag("card-shutter").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("shutter" to Command.Close), sends)
        assertEquals(emptyList(), taps)
    }

    @Test
    fun `climate sheet increases the setpoint by one step`() {
        show()
        compose.onNodeWithTag("card-ac").performClick()
        compose.onNodeWithContentDescription("Increase").performClick()
        assertEquals(listOf("ac" to Command.SetTemperature(21.5)), sends)
    }

    @Test
    fun `unavailable card is disabled and says so`() {
        show()
        compose.onNodeWithTag("card-plug").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Garage plug, Unavailable, Garage").assertExists()
        assertEquals(emptyList(), taps)
    }

    @Test
    fun `room chip selects its area`() {
        show()
        compose.onNodeWithText("Kitchen").performClick()
        assertEquals(listOf<RoomFilter>(RoomFilter.Area("kitchen")), selects)
    }

    @Test
    fun `cards grow with font scale`() {
        var scale by mutableFloatStateOf(1f)
        show { scale }
        val normal = compose.onNodeWithTag("card-coffee").getBoundsInRoot().let { it.bottom - it.top }
        scale = 2f
        compose.waitForIdle()
        val large = compose.onNodeWithTag("card-coffee").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(large > normal, "card height $normal at 1x vs $large at 2x")
    }
}
```

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/HomeEventsEffectTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.presentation.HomeEvent
import com.gekkotron.oikos.model.Command
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HomeEventsEffectTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    private val retried = mutableListOf<Pair<String, Command>>()

    private fun show() = compose.setContent {
        OikosTheme {
            val host = remember { SnackbarHostState() }
            HomeEventsEffect(events, host) { id, c -> retried += id to c }
            SnackbarHost(host)
        }
    }

    @Test
    fun `not confirmed shows a snackbar whose Retry resends`() {
        show()
        compose.waitForIdle()
        events.tryEmit(HomeEvent.NotConfirmed("lamp", Command.Toggle))
        compose.onNodeWithText("Not confirmed").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(listOf("lamp" to Command.Toggle), retried)
    }

    @Test
    fun `rejected shows the reason`() {
        show()
        compose.waitForIdle()
        events.tryEmit(HomeEvent.Rejected("plug", "unavailable"))
        compose.onNodeWithText("Command refused: unavailable").assertExists()
    }
}
```

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*ui*'`
Expected: FAIL to compile, `Unresolved reference 'HomeScreen'`.

- [ ] **Step 3: Text, glyphs and the status pill**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CardText.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState

private val OPENING_CLASSES = setOf("door", "window", "garage_door", "opening")
private val MOTION_CLASSES = setOf("motion", "occupancy", "presence")

@Composable
internal fun cardValueText(value: CardValue): String = when (value) {
    CardValue.None -> stringResource(R.string.value_none)
    is CardValue.OnOff -> stringResource(if (value.on) R.string.value_on else R.string.value_off)
    is CardValue.Brightness -> stringResource(R.string.value_percent, value.percent)
    is CardValue.Measurement -> listOfNotNull(value.text, value.unit).joinToString(" ")
    is CardValue.Text -> value.value
    is CardValue.Cover -> coverText(value)
    is CardValue.Climate ->
        listOfNotNull(value.current?.let { "$it°" }, value.mode?.let { modeLabel(it) })
            .joinToString(" · ")
            .ifEmpty { stringResource(R.string.value_none) }
    is CardValue.Alarm -> stringResource(alarmLabel(value.state))
    is CardValue.Contact -> stringResource(
        when (value.deviceClass) {
            in OPENING_CLASSES -> if (value.on) R.string.value_contact_open else R.string.value_contact_closed
            in MOTION_CLASSES -> if (value.on) R.string.value_motion_detected else R.string.value_motion_clear
            else -> if (value.on) R.string.value_on else R.string.value_off
        },
    )
}

@Composable
private fun coverText(value: CardValue.Cover): String {
    val position = value.position
    return when {
        value.state == CoverState.OPENING -> stringResource(R.string.value_cover_opening)
        value.state == CoverState.CLOSING -> stringResource(R.string.value_cover_closing)
        value.state == CoverState.CLOSED || position == 0 -> stringResource(R.string.value_cover_closed)
        position != null && position in 1..99 -> stringResource(R.string.value_cover_position, position)
        value.state == CoverState.OPEN || position == 100 -> stringResource(R.string.value_cover_open)
        value.state == CoverState.STOPPED -> stringResource(R.string.value_cover_stopped)
        else -> stringResource(R.string.value_none)
    }
}

@Composable
internal fun modeLabel(mode: String): String = when (mode) {
    "off" -> stringResource(R.string.mode_off)
    "cool" -> stringResource(R.string.mode_cool)
    "heat" -> stringResource(R.string.mode_heat)
    "auto" -> stringResource(R.string.mode_auto)
    "dry" -> stringResource(R.string.mode_dry)
    "fan_only" -> stringResource(R.string.mode_fan_only)
    else -> mode.replaceFirstChar { it.uppercase() }
}

internal fun alarmLabel(state: AlarmState): Int = when (state) {
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

internal fun armLabel(mode: AlarmMode): Int = when (mode) {
    AlarmMode.HOME -> R.string.sheet_arm_home
    AlarmMode.AWAY -> R.string.sheet_arm_away
    AlarmMode.NIGHT -> R.string.sheet_arm_night
    AlarmMode.VACATION -> R.string.sheet_arm_vacation
    AlarmMode.CUSTOM_BYPASS -> R.string.sheet_arm_custom
}
```

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindGlyphs.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.model.Kind

/** Emoji glyphs, as in the mockups, until the real icon set lands. */
internal fun kindGlyph(kind: Kind, deviceClass: String?): String = when (kind) {
    Kind.LIGHT -> "💡"
    Kind.SWITCH -> "🔌"
    Kind.COVER -> "🪟"
    Kind.CLIMATE -> "❄️"
    Kind.SENSOR -> when (deviceClass) {
        "temperature" -> "🌡️"
        "humidity", "moisture" -> "💧"
        "power", "energy" -> "⚡"
        else -> "📈"
    }
    Kind.BINARY_SENSOR -> when (deviceClass) {
        "door", "garage_door", "opening" -> "🚪"
        "window" -> "🪟"
        "motion", "occupancy", "presence" -> "🚶"
        "smoke" -> "🔥"
        "moisture" -> "💧"
        else -> "⚪"
    }
    Kind.ALARM_PANEL -> "🛡️"
    Kind.NUMBER -> "🎚️"
    Kind.SELECT -> "📋"
    Kind.BUTTON -> "▶️"
}
```

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/LinkPill.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gekkotron.oikos.designsystem.components.PillTone
import com.gekkotron.oikos.designsystem.components.StatusPill
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode

@Composable
internal fun LinkPill(link: LinkState, modifier: Modifier = Modifier) {
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
    StatusPill(stringResource(label), tone, modifier)
}
```

- [ ] **Step 4: The entity card**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.components.PendingDot
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.TapAction

@Composable
internal fun EntityCard(card: CardUi, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val valueText = if (card.available) cardValueText(card.value) else stringResource(R.string.value_unavailable)
    val room = card.areaName ?: stringResource(R.string.area_unassigned)
    val description = stringResource(R.string.card_description, card.name, valueText, room)
    val onText = stringResource(R.string.value_on)
    val offText = stringResource(R.string.value_off)
    val toggle = card.tap == TapAction.TOGGLE
    val interactive = card.tap != TapAction.NONE || onLongClick != null
    OikosCard(
        modifier = modifier
            .testTag("card-${card.id}")
            .semantics {
                contentDescription = description
                if (toggle) stateDescription = if (card.on) onText else offText
            },
        on = card.on,
        enabled = card.available,
        onClick = if (interactive) onClick else null,
        onLongClick = onLongClick,
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

- [ ] **Step 5: The control sheet**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt`:

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardFormatter
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.StepMath
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlSheet(card: CardUi, onSend: (Command) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(card.name, style = OikosTheme.type.display.copy(fontSize = 22.sp))
            Text(cardValueText(card.value), style = OikosTheme.type.body)
            when (card.kind) {
                Kind.LIGHT -> LightControls(card, onSend)
                Kind.COVER -> CoverControls(card, onSend)
                Kind.CLIMATE -> ClimateControls(card, onSend)
                Kind.NUMBER -> NumberControls(card, onSend)
                Kind.SELECT -> SelectControls(card, onSend)
                Kind.ALARM_PANEL -> AlarmControls(card, onSend)
                else -> Unit
            }
        }
    }
}

@Composable
private fun LightControls(card: CardUi, onSend: (Command) -> Unit) {
    val light = card.state as? StateValue.Light
    Row(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sheet_power), Modifier.weight(1f))
        Switch(checked = light?.on == true, onCheckedChange = { onSend(if (it) Command.TurnOn() else Command.TurnOff) })
    }
    if (card.capabilities.brightness) {
        var value by remember(light?.brightness) { mutableFloatStateOf((light?.brightness ?: 255).toFloat()) }
        Text(stringResource(R.string.sheet_brightness))
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..255f,
            onValueChangeFinished = { onSend(Command.TurnOn(brightness = value.roundToInt())) },
        )
    }
}

@Composable
private fun CoverControls(card: CardUi, onSend: (Command) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { onSend(Command.Open) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_open)) }
        FilledTonalButton(onClick = { onSend(Command.Stop) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_stop)) }
        FilledTonalButton(onClick = { onSend(Command.Close) }, Modifier.weight(1f)) { Text(stringResource(R.string.sheet_close)) }
    }
    if (card.capabilities.position) {
        val position = (card.state as? StateValue.Cover)?.position ?: 0
        var value by remember(position) { mutableFloatStateOf(position.toFloat()) }
        Text(stringResource(R.string.sheet_position))
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 0f..100f,
            steps = 19,
            onValueChangeFinished = { onSend(Command.SetPosition(value.roundToInt())) },
        )
    }
}

@Composable
private fun ClimateControls(card: CardUi, onSend: (Command) -> Unit) {
    val climate = card.state as? StateValue.Climate
    val caps = card.capabilities
    val min = caps.minTemp ?: 7.0
    val max = caps.maxTemp ?: 35.0
    val step = caps.tempStep ?: 1.0
    val target = climate?.target ?: climate?.current ?: ((min + max) / 2)
    val decrease = stringResource(R.string.sheet_decrease)
    val increase = stringResource(R.string.sheet_increase)
    Text(stringResource(R.string.sheet_target))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, -step, min, max))) },
            enabled = target > min,
            modifier = Modifier.semantics { contentDescription = decrease },
        ) { Text("−", fontSize = 22.sp) }
        Text(CardFormatter.number(target, Locale.getDefault()) + "°", style = OikosTheme.type.display)
        FilledTonalIconButton(
            onClick = { onSend(Command.SetTemperature(StepMath.stepTemperature(target, step, min, max))) },
            enabled = target < max,
            modifier = Modifier.semantics { contentDescription = increase },
        ) { Text("+", fontSize = 22.sp) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in caps.hvacModes) {
            FilterChip(selected = climate?.mode == mode, onClick = { onSend(Command.SetHvacMode(mode)) }, label = { Text(modeLabel(mode)) })
        }
    }
}

@Composable
private fun NumberControls(card: CardUi, onSend: (Command) -> Unit) {
    val caps = card.capabilities
    val min = caps.min ?: 0.0
    val max = caps.max ?: 100.0
    val step = caps.step ?: 1.0
    val current = (card.state as? StateValue.Numeric)?.value ?: min
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    val steps = ((max - min) / step).roundToInt() - 1
    Text(
        listOfNotNull(CardFormatter.number(StepMath.snap(value.toDouble(), min, step), Locale.getDefault()), card.unit).joinToString(" "),
        style = OikosTheme.type.display,
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = min.toFloat()..max.toFloat(),
        steps = if (steps in 0..200) steps else 0,
        onValueChangeFinished = { onSend(Command.SetNumber(StepMath.snap(value.toDouble(), min, step))) },
    )
}

@Composable
private fun SelectControls(card: CardUi, onSend: (Command) -> Unit) {
    val current = (card.state as? StateValue.Text)?.value
    for (option in card.capabilities.options) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = OikosDimens.minTouch)
                .selectable(selected = option == current, role = Role.RadioButton) { onSend(Command.SelectOption(option)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == current, onClick = null)
            Text(option, Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun AlarmControls(card: CardUi, onSend: (Command) -> Unit) {
    val caps = card.capabilities
    var code by rememberSaveable { mutableStateOf("") }
    if (caps.codeArmRequired) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.sheet_code)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val codeArg = code.ifEmpty { null }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in AlarmMode.entries.filter { it in caps.alarmModes }) {
            FilledTonalButton(onClick = { onSend(Command.Arm(mode, codeArg)) }) { Text(stringResource(armLabel(mode))) }
        }
    }
    Button(onClick = { onSend(Command.Disarm(codeArg)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.sheet_disarm)) }
}
```

- [ ] **Step 6: The events effect and the screen**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeEventsEffect.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.HomeEvent
import com.gekkotron.oikos.model.Command
import kotlinx.coroutines.flow.Flow

/** Spec §4.4 / §7: "Not confirmed — Retry" and refused commands, as snackbars. */
@Composable
fun HomeEventsEffect(events: Flow<HomeEvent>, snackbar: SnackbarHostState, onRetry: (String, Command) -> Unit) {
    val resources = LocalContext.current.resources
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(events, snackbar) {
        events.collect { event ->
            when (event) {
                is HomeEvent.NotConfirmed -> {
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.home_not_confirmed),
                        actionLabel = resources.getString(R.string.home_retry),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) retry(event.id, event.command)
                }
                is HomeEvent.Rejected -> snackbar.showSnackbar(resources.getString(R.string.home_rejected, event.reason))
            }
        }
    }
}
```

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.ChipItem
import com.gekkotron.oikos.designsystem.components.RoomChips
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
import com.gekkotron.oikos.feature.home.presentation.HomeViewModel
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.presentation.TapAction
import com.gekkotron.oikos.model.Command
import java.time.LocalTime

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    HomeEventsEffect(viewModel.events, snackbar, onRetry = viewModel::send)
    HomeScreen(state, viewModel::select, viewModel::tap, viewModel::send, snackbar)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelect: (RoomFilter) -> Unit,
    onTap: (CardUi) -> Unit,
    onSend: (String, Command) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    now: LocalTime = LocalTime.now(),
) {
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth < 600.dp -> 2
                maxWidth < 840.dp -> 3
                else -> 4
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
                verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { HomeHeader(state, now) }
                item(key = "chips", span = { GridItemSpan(maxLineSpan) }) { HomeChips(state, onSelect) }
                if (state.loading) {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.home_discovering), style = OikosTheme.type.body, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
                items(state.cards, key = { it.id }, span = { GridItemSpan(if (it.wide) minOf(2, maxLineSpan) else 1) }) { card ->
                    EntityCard(
                        card = card,
                        onClick = { if (card.tap == TapAction.OPEN_SHEET) sheetId = card.id else onTap(card) },
                        onLongClick = if (card.hasSheet) ({ sheetId = card.id }) else null,
                    )
                }
            }
        }
    }
    val sheetCard = sheetId?.let(state::card)
    if (sheetCard != null) {
        ControlSheet(sheetCard, onSend = { onSend(sheetCard.id, it) }, onDismiss = { sheetId = null })
    }
}

@Composable
private fun HomeHeader(state: HomeUiState, now: LocalTime) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(greeting(now), style = OikosTheme.type.display, modifier = Modifier.weight(1f))
            state.hero?.let { Text(cardValueText(it.value), style = OikosTheme.type.display) }
        }
        Spacer(Modifier.height(8.dp))
        LinkPill(state.link)
    }
}

@Composable
private fun HomeChips(state: HomeUiState, onSelect: (RoomFilter) -> Unit) {
    val all = stringResource(R.string.chip_all)
    val favorites = stringResource(R.string.chip_favorites)
    val unassigned = stringResource(R.string.area_unassigned)
    val items = state.chips.map { chip ->
        val label = chip.label ?: when (chip.filter) {
            RoomFilter.All -> all
            RoomFilter.Favorites -> favorites
            is RoomFilter.Area -> unassigned
        }
        ChipItem(chip.filter.key, label)
    }
    RoomChips(items, state.selected.key, onSelect = { onSelect(RoomFilter.fromKey(it)) })
}

@Composable
private fun greeting(now: LocalTime): String = stringResource(
    when (now.hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    },
)
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS (every Task 4 and Task 5 test). If `tapping a cover opens its control sheet` cannot find "Close", add `compose.waitForIdle()` after the card click; the sheet animates in. Do not replace the sheet with a dialog.

- [ ] **Step 8: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): Home screen, entity cards, control sheet and snackbars (EN/FR)" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `:app` wiring (Hilt graph, Navigation 3, demo mode) and an install on the phone

**Files:**
- Modify: `app/build.gradle.kts`, `app/src/main/kotlin/com/gekkotron/oikos/MainActivity.kt`, `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt`, `README.md`
- Create: `app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt`, `app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`

**Interfaces:**
- Consumes:
  - `DemoBroker(scope)` and `startTicking()` (Task 2)
  - `MqttDiscoverySource(transport, scope, diagnostics = …)`
  - `OikosTheme` and `OikosBackground` (Task 3)
  - `HomeRoute()` (Task 5)
- Produces:
  - the singleton bindings `MqttTransport` (a `DemoBroker`, ticking every 3 s) and `EntitySource` (a `MqttDiscoverySource`)
  - the `@ApplicationScope CoroutineScope` qualifier
  - `@Serializable data object HomeKey : NavKey`

- [ ] **Step 1: Write the failing end-to-end test**

Replace `app/src/test/kotlin/com/gekkotron/oikos/MainActivityTest.kt` with:

```kotlin
package com.gekkotron.oikos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real Hilt graph: DemoBroker → MqttDiscoverySource → HomeViewModel → Home screen. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h914dp")
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `demo house appears on the home screen`() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("Floor lamp", substring = true), timeoutMillis = 10_000)
        compose.onNodeWithText("Demo").assertExists()
        compose.waitUntilAtLeastOneExists(hasText("Living room"), timeoutMillis = 10_000)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL. `waitUntilAtLeastOneExists` times out, because the app still shows only "Oikos".

- [ ] **Step 2: Dependencies**

Replace `app/build.gradle.kts` with:

```kotlin
plugins {
    id("oikos.android.application")
    id("oikos.android.compose")
    id("oikos.hilt")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos"
    defaultConfig {
        applicationId = "com.gekkotron.oikos"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:demo"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:home"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
```

- [ ] **Step 3: The Hilt graph**

`app/src/main/kotlin/com/gekkotron/oikos/di/AppModule.kt`:

```kotlin
package com.gekkotron.oikos.di

import android.util.Log
import com.gekkotron.oikos.demo.DemoBroker
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.source.MqttDiscoverySource
import com.gekkotron.oikos.transport.MqttTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Demo mode is the only transport until the Direct/Gateway plan lands. */
    @Provides
    @Singleton
    fun transport(@ApplicationScope scope: CoroutineScope): MqttTransport =
        DemoBroker(scope).also { it.startTicking() }

    @Provides
    @Singleton
    fun entitySource(transport: MqttTransport, @ApplicationScope scope: CoroutineScope): EntitySource =
        MqttDiscoverySource(transport, scope, diagnostics = { Log.w("Oikos", "${it.source}: ${it.message}") })
}
```

- [ ] **Step 4: Navigation host and activity**

`app/src/main/kotlin/com/gekkotron/oikos/navigation/OikosNavHost.kt`:

```kotlin
package com.gekkotron.oikos.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gekkotron.oikos.feature.home.ui.HomeRoute
import kotlinx.serialization.Serializable

@Serializable
data object HomeKey : NavKey

@Composable
fun OikosNavHost() {
    val backStack = rememberNavBackStack(HomeKey)
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeKey> { HomeRoute() }
        },
    )
}
```

Replace `app/src/main/kotlin/com/gekkotron/oikos/MainActivity.kt` with:

```kotlin
package com.gekkotron.oikos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.navigation.OikosNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OikosTheme {
                OikosBackground { OikosNavHost() }
            }
        }
    }
}
```

If `rememberSaveableStateHolderNavEntryDecorator` or `rememberViewModelStoreNavEntryDecorator` does not resolve, look the decorator names up in the Navigation 3 1.2.0 and lifecycle-viewmodel-navigation3 2.11.0 API docs (context7: `androidx.navigation3`) and use those. Keep the same two decorators: saveable state first, then ViewModel store.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

Run: `./gradlew check koverXmlReport :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all modules green, and `build/reports/kover/report.xml` exists.

- [ ] **Step 6: Install on the phone and verify by hand**

```bash
adb devices -l
./gradlew :app:installDebug
adb shell am start -n com.gekkotron.oikos.debug/com.gekkotron.oikos.MainActivity
mkdir -p "$TMPDIR/oikos" && adb exec-out screencap -p > "$TMPDIR/oikos/home-system.png"
adb shell cmd uimode night yes && sleep 1 && adb exec-out screencap -p > "$TMPDIR/oikos/home-night.png"
adb shell cmd uimode night no && sleep 1 && adb exec-out screencap -p > "$TMPDIR/oikos/home-warm.png"
adb shell cmd locale set-app-locales com.gekkotron.oikos.debug --locales fr-FR && sleep 1 && adb exec-out screencap -p > "$TMPDIR/oikos/home-fr.png"
adb shell cmd locale set-app-locales com.gekkotron.oikos.debug --locales ""
adb shell cmd uimode night auto
```

Open each screenshot and check, then check these on the phone:
- **Night:** there is a radial gradient background, and the lamp and AC cards are amber.
- **Warm:** the background is paper, the title is serif, and on-cards are inverted.
- **French:** the text reads "Bon après-midi"/"Bonsoir" and the pill says "Démo".
- **Header:** the greeting and "14.2 °C" show, with the green "Demo" pill.
- **Chips:** All, Favourites, Living room, Kitchen, Bedroom, Hallway, Garage.
- **Floor lamp:** a tap shows the pending dot for about 0.3 s, then the card turns off; a long-press opens brightness.
- **Shutter:** a tap opens the sheet, and Close makes it read "Closed".
- **Air conditioner:** "+" raises the target by 0.5.
- **Alarm:** "Away" shows "Arming…" and then "Armed · Away".
- **Garage plug:** it is greyed out, reads "Unavailable" and does not react.
- **Power cards:** Solar/Grid/House values change every 3 s, on wide cards.
- **Font size:** with Settings → Display → Font size at max, the cards grow and no value text is clipped.

Record anything that fails as a bug to fix in this task before committing.

- [ ] **Step 7: README**

In `README.md`:
- Replace the status line with: `> **Status:** foundation and demo Home screen. The core and the Home screen run against a built-in demo house; real broker transports come next. See the [design spec](docs/superpowers/specs/2026-09-24-oikos-design.md) and [mockups](docs/design/mockups/).`
- Add these rows to the Modules table:

```markdown
| `:core:demo` | In-process demo broker with a fake house (discovery, state, echoed commands, live power) |
| `:core:designsystem` | Night Glass / Warm Minimal tokens, bundled Inter + Fraunces, cards, pill, chips |
| `:feature:home` | Home screen: rooms, cards, control sheet |
| `:app` | Hilt graph, Navigation 3, demo-mode wiring |
```

- Add this section after the table:

````markdown
## Try the demo

With a phone connected over USB (developer mode on):

```bash
./gradlew :app:installDebug
adb shell am start -n com.gekkotron.oikos.debug/com.gekkotron.oikos.MainActivity
```

The debug build talks to a built-in demo house, so no broker is needed.
````

- [ ] **Step 8: Commit**

```bash
git add app README.md
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(app): Hilt graph with demo transport, Navigation 3 host, Home as start screen" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes (for the executor)

- **Spec coverage:**

  | Spec item | Where |
  |---|---|
  | §4.1 modules and dependency rule | Tasks 1–6 (`:feature:home` never names `:core:transport`) |
  | §5.1 tokens and components | Task 3 (`ControlSheet` in Task 5; `GlassNavBar` deferred with the other screens) |
  | §5.2 Home | Task 5: hero weather, chips, grid, wide power cards, tap/toggle, sheets |
  | §5.3 accessibility | Task 5 content descriptions, Task 3 contrast test, Task 5 font-scale test, Task 5 2/3/4 columns (nav rail deferred) |
  | §4.4 snackbar | Task 4 and Task 5 |
  | §7 unavailable | Task 2 and Task 5 |
  | §9 stack | Task 1 |

  Deferred items are listed under "Later plans" in the header.
- **Type consistency:**
  - `EntitySource.link` is added in Task 2 and used by `FakeEntitySource` and `HomeViewModel` in Task 4.
  - `CardUi.hasSheet` and `TapAction` are defined in Task 4 and used in Task 5.
  - `StepMath` is defined in Task 4 and used by the Task 5 sheet.
  - `HomeRoute` is defined in Task 5 and used in Task 6.
  - `DemoBroker(scope)` / `startTicking()` are defined in Task 2 and used in Task 6.
- **Known sharp edges:**
  - Built-in Kotlin with KSP (Task 1 Step 6 fallback).
  - Robolectric SDK pinning.
  - The Navigation 3 decorator names (Task 6 Step 4 note).
  - Robolectric `ModalBottomSheet` timing (Task 5 Step 7 note).
  - Never "fix" any of these by weakening a test.
