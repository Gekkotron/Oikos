# Oikos — Settings: theme, room order, connection, about

Status: approved design, 2026-10-02. Parent spec: `2026-09-24-oikos-design.md` §5.2 (Settings) and §11 (phase 1). It is the phase 1 sub-project after Security, before Push alerts.

Nothing is house-specific (parent goal 7): the room order uses the existing shared `oikos/ui/_areas` document, so an HA user and this house get the same screen.

## 1. Goal

A **Settings** screen, opened from Home, with four sections:

1. **Theme**: System / Night Glass / Warm Minimal, saved per phone.
2. **Rooms**: reorder the rooms (the order of the Home room chips), shared by every phone; plus the existing "Assign rooms" step.
3. **Connection**: a read-only summary of the profile, the route picker, and "Set up again" behind a confirmation.
4. **About**: version, NOTICE, open-source licences.

Out of scope (parent §5.2 lists them; the user left them out):
- Logs viewer.
- A hidden-entities list (Home's "Hidden (N)" chip and the customise sheet already cover it).
- Language (Android 13+ per-app language in system settings already works with `values-fr`).
- Push registration status (comes with Push alerts).
- Renaming, deleting or re-iconing rooms.

## 2. Navigation

- A **gear icon** sits next to the search icon in the Home header (content description "Settings"). It pushes `SettingsKey`.
- `push` / `pop` helpers guard the back stack: a double tap on the gear opens Settings once, and a double tap on a back arrow pops one screen, never the whole stack.
- `SettingsKey` shows no tab bar (`showsTabBar` stays Home / Energy / Security). Back pops to Home.
- Settings → Rooms pushes `RoomOrderKey`. "Assign rooms" there pushes the existing `RoomsKey` (unchanged; its `onDone` pops back).
- New module **`:feature:settings`** (plugins `oikos.android.library`, `oikos.android.compose`, `oikos.hilt`; depends on `:core:source`, `:core:data`, `:core:designsystem`). The app wires its routes in `OikosNavHost`.

## 3. Theme

### 3.1 Storage (`:core:data`)

`AppPreferences`: a new DataStore (`app-preferences.json`, kotlinx-serialization, `ReplaceFileCorruptionHandler` → defaults), separate from `StoredProfile` because "Set up again" must not reset it.

```kotlin
@Serializable data class StoredPreferences(val theme: ThemeChoice = ThemeChoice.SYSTEM)
class AppPreferences { val theme: StateFlow<ThemeChoice>; suspend fun setTheme(choice: ThemeChoice) }
```

`ThemeChoice` lives in `:core:designsystem`; `:core:data` stores its enum name as a string (`"SYSTEM"`, `"NIGHT_GLASS"`, `"WARM_MINIMAL"`) so `:core:data` does not depend on the design system. An unknown name decodes as `SYSTEM`.

Per phone, never published: each family member keeps their own theme.

### 3.2 Applying it

`AppViewModel` exposes `theme: StateFlow<ThemeChoice?>` (null until the store has loaded). `MainActivity` keeps drawing the background, and passes the value to `OikosTheme(choice)` (System while null, to avoid a flash). A change recomposes in place, with no activity recreation.

### 3.3 UI

A "Theme" section with three radio rows in a `selectableGroup`. Selecting one saves immediately.

## 4. Rooms (room order)

### 4.1 Data

The order already exists: `Area.order` in the retained `oikos/ui/_areas` document, applied by `MqttDiscoverySource.computeAreas` (declared areas by `order` then name, then implicit areas by id, then "Unassigned"). `EntitySource.setAreas(areas)` publishes the whole document, updates `areas` at once, and rolls back to the confirmed document on `PublishFailed`. No source change is needed.

`RoomOrder.apply(areas: List<Area>, newOrder: List<String>): List<Area>` (pure, `:feature:settings`):
- `areas` is `source.areas` (the computed list); `newOrder` is the room ids in their new order ("Unassigned" is never in it).
- Returns the document to publish: every room in `newOrder` with `order = index`, keeping its name and icon. An implicit room (shown but not yet in the document) is added with its current displayed name.

The declared document itself is not exposed by `EntitySource`, so every shown room becomes declared on the first reorder. This is what `OverrideEditor.addRoom` already does. A room created later (order null) sorts after the ordered ones until the next reorder. A declared room that no entity uses is still in `areas` (`computeAreas` keeps every declared room), so it shows in the list and keeps its place. `RoomOrder.apply` also keeps a room that appeared during the drag (after the ordered ones) and skips an id that is gone.

### 4.2 Screen

- Title "Rooms", a short hint ("Drag to change the order of the room chips on Home."), then a `LazyColumn` of rows: room name and a drag handle at the end.
- Drag and drop with `sh.calvin.reorderable` (new dependency, version catalog). The list reorders locally while dragging. **On drop**, if the order changed, the ViewModel publishes once.
- Accessibility: each row has the custom actions "Move up" / "Move down" (not on the first / last row), which publish at once. The handle has no separate focus stop.
- "Unassigned", when present, is shown last, without a handle, and is not draggable.
- On `PublishFailed`: the list returns to `source.areas` (already rolled back) and a snackbar says "Couldn't save the order. Check the connection."
- Below the list: an "Assign rooms" button (moved from the link sheet).
- Demo mode runs the same `MqttDiscoverySource` over `DemoBroker`, so reordering works there too.

## 5. Connection

### 5.1 Summary (read-only)

From `ProfileState.Ready.profile`:
- **Demo**: "Demo house".
- **Direct**: "Broker" `host:port`, with "TLS" / "WebSocket" tags when set; "User" (username, or "—"); "Discovery prefix"; "Gateway" URL when the profile has one.

The password and the gateway key are **never** shown, or copied into the UI state. `ConnectionSummary.from(profile)` builds a plain data class with only the fields above, and is unit-tested for that.

### 5.2 Controls

- **Route** picker (Auto / Direct / Gateway), shown only when the profile has a gateway (same rule as `AppViewModel.route` today). `RoutePicker` and the `OikosRadioRow` it uses move from `:feature:home` to `:core:designsystem/components`, with the route strings, so both screens share them.
- **Set up again**: opens an `AlertDialog`: "Set up again? This forgets the connection on this phone. You'll need the setup QR code." with Cancel / "Set up again". Confirming calls the existing `AppViewModel.setUpAgain()`.

### 5.3 Link sheet (status pill)

It keeps the link status text and the route picker, and gains a "Connection settings" text button that opens Settings. "Assign rooms" and "Set up again" are removed from it. Its KDoc no longer says "Settings takes this over later".

## 6. About

- **Version**: "Oikos 0.<code>", plus " · debug" on debuggable builds, read from `PackageManager` (`appVersion(context)`), so `:feature:settings` needs no `BuildConfig`.
- **Licence notice**: the NOTICE text (copyright Gekkotron, bundled fonts).
- **Open-source licences**: a row that pushes `LicencesKey`, an Oikos-themed `LazyColumn` built from **AboutLibraries** (the `com.mikepenz.aboutlibraries.plugin.android` Gradle plugin in `:app`, offline mode). `aboutlibraries-core` is **not** used: 15.x needs compileSdk 37 and Java 21 bytecode, so `licenceRows(json)` parses the generated JSON with kotlinx.serialization; the app passes the JSON in (`LicencesRoute(loadJson)`). Licence texts the plugin does not bundle are committed under `app/config/licenses/` so every row has its text. Rows show library name, version, licence name; tapping one opens a sheet with the licence text. The two OFL fonts (Inter, Fraunces) are added as custom entries from `core/designsystem/licenses/`.

## 7. Architecture summary

| Unit | Module | Purpose |
|---|---|---|
| `AppPreferences`, `StoredPreferences` | `:core:data` | Per-phone theme storage |
| `RoutePicker` | `:core:designsystem` | Moved from `LinkSheet`, shared |
| `SettingsViewModel`, `SettingsScreen` | `:feature:settings` | Theme, connection summary, entry rows |
| `ConnectionSummary` | `:feature:settings` | Secret-free view of the profile |
| `RoomOrder`, `RoomOrderViewModel`, `RoomOrderScreen` | `:feature:settings` | Room reorder |
| `LicencesScreen` | `:feature:settings` | AboutLibraries list |
| `SettingsKey`, `RoomOrderKey`, `LicencesKey`, theme wiring | `:app` | Navigation, `MainActivity`, `AppViewModel` |

`SettingsViewModel` reads `ProfileRepository.state` / `route` and `AppPreferences`. The route and "Set up again" actions stay on `AppViewModel`, passed in as callbacks, as `HomeRoute` does today.

## 8. Testing

Unit (JVM / Robolectric):
- `AppPreferences`: default System; round-trip; a corrupt file → System; an unknown enum name → System; `ProfileRepository.clear()` leaves it untouched.
- `RoomOrder.apply`: reorder; implicit rooms promoted with their name; icon kept; Unassigned excluded.
- `RoomOrderViewModel`: one `setAreas` per drop, none when the order is unchanged; `PublishFailed` → snackbar event and list back to `source.areas`; Move up / Move down actions.
- `ConnectionSummary`: Direct / Demo / with and without gateway; the produced object's `toString()` contains neither the password nor the key.

Compose UI (Robolectric):
- Theme rows: selecting Warm Minimal calls `setTheme`.
- "Set up again" shows the dialog; Cancel does nothing; confirm calls the callback once.
- Link sheet: no "Assign rooms" / "Set up again"; "Connection settings" calls its callback.
- Licences list renders a generated entry and the two font entries.

Device (Pixel 6a): gear → Settings; switch the theme live; reorder two rooms and check the Home chips; back navigation; the alarm chips' armed look (the user arms Home or Night).

`./gradlew check` green.

## 9. Risks

- **AboutLibraries and AGP 9.4 / Kotlin 2.4**: the plugin must support them. If it doesn't, fall back to a static list generated by a small Gradle task over `runtimeClasspath` POMs, same screen.
- **`sh.calvin.reorderable`** with the Compose BOM 2026.06: check compatibility at plan time; the fallback is up/down arrows with the same publish rule.
- **Two phones reordering at once**: last write wins on the retained document, as for every override today.
- **Older app versions** already read `Area.order` (it has existed since Plan 1), so they show the new order too. An older app that re-saves the areas (its Rooms step) keeps `order`, since it round-trips the whole `Area`.
