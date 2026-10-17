# Oikos Voice Answers and Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add spoken answers for read-only queries ("quelle est la température du salon ?" → *"Il fait 22 degrés dans le salon"*) and turn the dead-end *Autre…* row into a real two-step picker (searchable device list → kind-scoped action chooser) with tap-to-teach, both in FR and EN.

**Architecture:** Extends voice v1 (b473d55). `:core:voice` grows a `QUERY_STATE` action, a `QUERY` verb, an `attributes` lexicon (température/humidité/…), a `QueryHint` on `VoiceIntent`, and a pure `AnswerFormatter` that composes a sentence per kind per language from an `EntityState` snapshot. `:feature:voice` grows a `TtsSpeaker` wrapping Android `TextToSpeech`, two new sheet states (`Spoken`, `Picking`), a `VoiceCommandRunner.query()` snapshot path, and three new Compose views (`SpokenView`, `DevicePickerView`, `ActionPickerView`). Settings grows a TTS availability line. The existing voice toggle gates both STT and TTS; no new permission.

**Tech Stack:** Kotlin 2.4.20, Jetpack Compose (Material 3), kotlinx.coroutines, Hilt, Android `TextToSpeech` (built-in), JUnit4 + Robolectric + Turbine for `:feature:voice`, JUnit Jupiter for `:core:voice`, Compose screenshot tests (Robolectric) for new views.

**Spec:** `docs/superpowers/specs/2026-10-17-oikos-voice-answers-and-picker-design.md`

## Global Constraints

- **Branch:** `feat/voice-answers`, created from `master` at the start of Task 1. `git pull --rebase origin master` before every push (`distribute.yml` pushes `Update version code` commits); never force-push.
- **Commit identity on every commit** (including every subagent dispatch): `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`. Each commit message ends with:
  ```
  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  Claude-Session: 06525751-0831-48fc-87ca-7b0092c158d3
  ```
  Verify `git log --format='%ae %ce'` on the new range before any push; the author/committer must be `60887050+Gekkotron@users.noreply.github.com`.
- **Android version:** TTS is available since API 4 and needs no permission; the voice feature is already API 31+ (unchanged by this plan).
- **Opt-in gate:** the existing `AppPreferences.voiceEnabled` toggle enables both STT and TTS; no new toggle, no new runtime permission.
- **Supported entity kinds (voice catalog):** `Kind.LIGHT, SWITCH, COVER, ALARM_PANEL, SENSOR, BINARY_SENSOR, CLIMATE, VACUUM, WATER_HEATER, NUMBER`. Filter unchanged: `partOf == null && !ui.hidden`.
- **Query precedence:** when a sentence yields both a `QUERY` verb/structural phrase and a state verb (e.g. "est-ce que les volets sont fermés"), `QUERY` wins; the state verb becomes a `QueryHint` on the intent (`EXPECT_ON | EXPECT_OFF | EXPECT_OPEN | EXPECT_CLOSED`).
- **Attribute precedence:** when a sentence yields both an attribute word (`température`) and a device word (`volet`), the attribute wins (queries by attribute are the common case).
- **Picker scope (v1):** `SET_POSITION` and `SET_BRIGHTNESS` are deliberately **omitted** from the picker (keeps the picker two steps); `ARM/DISARM` rows go through the existing `ConfirmNeeded` flow.
- **Multi-sensor same-room aggregation:** continuous classes (`temperature, humidity, pressure, illuminance`) → average with *"en moyenne"* prefix when `n > 1`, preferring a sensor whose name is just the device-class noun (`"Température"`) or `${room} ${class}` as the ambient one; discrete classes → list by entity name.
- **Staleness:** when any targeted entity's `EntityState.stale == true`, the sentence appends `(mise à jour il y a Xh)` / `(updated Xh ago)` once at the end.
- **Unavailable:** `StateValue.Unknown | Unsupported` or `available == false` renders as *"je n'ai pas la valeur de …"* / *"I don't have a reading for …"*.
- **Strings:** every new user-visible string goes in both `values/strings.xml` and `values-fr/strings.xml` of the owning module. Keys prefixed `voice_` (feature:voice) or `settings_voice_` (feature:settings).
- **Sheet auto-close:** 5 s on `Spoken` (new), 2 s on `Done` (unchanged). Any touch on the sheet keeps it open.
- **Android ICU regex:** escape literal `]` and `}` in any `Regex` (ICU throws at class init even though JVM tests pass). Applies to `:core:voice`'s new structural phrase regexes.
- **Model dependency rule:** `:core:voice` stays on `:core:model` only. `AnswerFormatter` is pure Kotlin; no Android, no coroutines, no serialization.
- **Module test commands:**
  - `./gradlew :core:voice:test`
  - `./gradlew :feature:voice:testDebugUnitTest`
  - `./gradlew :feature:settings:testDebugUnitTest`
  - Full gate before push: `./gradlew check`
- **Post-push CI directive** (from memory `oikos-voice-assistant` + `check-ci-after-push`): a few minutes after each push, check both `ci.yml` and `distribute.yml` with `gh run list --branch feat/voice-answers --limit 6`; on failure root-cause, fix, verify locally, commit, rebase, push, re-check until both are green and the Firebase build uploads.

## Review Focus

Five input classes the spec implies but no task's tests exercise unless pinned. Each line names the input and the behaviour a reasonable user would expect, most likely first.

1. **A query against a sensor whose `EntityState.value` is `Unknown` (first boot, never received a message).** The sentence must be the "I don't have a reading" fallback; nothing is spoken as if it were data. Pinned by Task 5, test `an Unknown or Unsupported state renders as the no-reading fallback`.
2. **A query against a sensor whose `EntityState.stale == true` (cache replay, no live message yet).** The sentence must carry the "updated X ago" staleness tail. Pinned by Task 5, test `a stale state appends the updated-X-ago tail once`.
3. **TTS fr-FR voice pack not installed on the device.** `TtsSpeaker.availability` reports `LanguageMissing`; `VoiceSessionState.Spoken` sets `speakable = false`; the sheet shows the sentence as text only and does not call `speak`. Pinned by Task 6, test `TtsSpeaker reports LanguageMissing when isLanguageAvailable is LANG_MISSING_DATA`.
4. **A sentence that pairs an attribute word with a device word in the same room ("quelle est l'humidité du salon" when the salon also has temperature + door sensors).** The attribute wins; only humidity entities are returned; the sentence talks about humidity, not temperature. Pinned by Task 3, test `attribute wins over device word and narrows candidates to the matching device-class`.
5. **Three temperature sensors in the salon when asked "quelle est la température du salon".** When one is named `"Température"` or `"Salon température"`, that one wins (ambient preference). Otherwise the average is spoken with *"en moyenne"*. Pinned by Task 5, test `multi-sensor same-room continuous average prefers an ambient-named entity`.

---

### Task 1: `:core:voice` data additions — `QUERY_STATE`, `QueryHint`, `VoiceIntent.hint`, `Verb.QUERY`, attributes table, structural phrases

**Files:**
- Modify: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceModels.kt`
- Modify: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceLexicon.kt`
- Modify: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceLexiconTest.kt` (or create if absent)
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceModelsTest.kt`

**Interfaces:**
- Consumes: `VoiceLang` (existing in `VoiceModels.kt`).
- Produces:
  - `enum class VoiceAction { …, QUERY_STATE }`
  - `enum class QueryHint { EXPECT_ON, EXPECT_OFF, EXPECT_OPEN, EXPECT_CLOSED }`
  - `data class VoiceIntent(action, entityIds, value?, hint: QueryHint? = null)` (new field, nullable, default `null` → all existing call-sites keep compiling)
  - `VoiceLexicon.Verb.QUERY` (new enum value)
  - `VoiceLexicon.Table.attributes: Map<String, String>` (new field with per-language entries, see spec §3.1)
  - `VoiceLexicon.Table.structuralQuery: List<Regex>` (new field — the FR / EN "est-ce que" / "is it" / "what is the" family; the regex matches and the matcher inserts a synthetic `QUERY` token)

- [ ] **Step 1: Write the failing tests**

In `VoiceModelsTest.kt`:
```kotlin
@Test fun `VoiceIntent default hint is null`() { … hint == null … }
@Test fun `VoiceAction contains QUERY_STATE`() { … }
@Test fun `QueryHint has four expected values`() { … }
```

In `VoiceLexiconTest.kt` (add):
```kotlin
@Test fun `FR attributes map temperature humidite batterie puissance consommation tension courant luminosite pression niveau etat`() { … }
@Test fun `EN attributes map temperature humidity battery power energy voltage current illuminance pressure level state`() { … }
@Test fun `FR verbs contain QUERY entries (quelle, combien, lis, dis, montre)`() { … }
@Test fun `EN verbs contain QUERY entries (what, how, show, tell, read)`() { … }
@Test fun `FR structural phrases match est-ce que, qu'est-ce que`() { … }
@Test fun `EN structural phrases match is it, are the, what is the`() { … }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:voice:test --tests "*VoiceModelsTest*" --tests "*VoiceLexiconTest*"`
Expected: compilation failure (new types absent) then test failures (missing map entries, missing enum values).

- [ ] **Step 3: Add `QUERY_STATE` to `VoiceAction`, add `QueryHint` enum, add `hint: QueryHint? = null` to `VoiceIntent`, add `Verb.QUERY`, add `attributes` + `structuralQuery` fields to `VoiceLexicon.Table`, populate both `FR` and `EN` tables as listed in the spec §3.1 and §4**

The attribute values are the HA `device_class` strings as spelled in `Entity.deviceClass` elsewhere in the codebase (`temperature, humidity, battery, power, energy, voltage, current, illuminance, pressure` — exact lowercase strings) and the sentinel `"state"` for the generic marker.

Regexes anchor on word boundaries; escape literal `]` and `}` per the ICU rule (none here, but keep the discipline). Rewrite `"est-ce que"` / `"qu'est-ce que"` with optional apostrophe handling — the matcher's `VoiceText.prepare` lowercases and folds accents before matching, so write the regex against that normalized form.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS. All existing tests continue to pass (additive change).

- [ ] **Step 5: Commit**

Message: `feat(voice): add QUERY_STATE action, QueryHint, attributes lexicon, structural query phrases`

---

### Task 2: `:core:voice` catalog — `VoiceEntity.unit` + broaden `SUPPORTED`

**Files:**
- Modify: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceCatalog.kt`
- Modify: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceCatalogTest.kt` (or create if absent)

**Interfaces:**
- Consumes: `Entity.unit`, `Entity.kind` (existing in `:core:model`).
- Produces: `VoiceEntity` gains `val unit: String?` as its last field (preserve existing call-site order via a trailing default where possible, otherwise update the few `VoiceCatalog`/`VoiceCatalogBuilder` call-sites; keep `VoiceEntity` a plain `data class`). `VoiceCatalogBuilder.SUPPORTED` grows to include `SENSOR, BINARY_SENSOR, CLIMATE, VACUUM, WATER_HEATER, NUMBER`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `VoiceCatalogBuilder keeps SENSOR, BINARY_SENSOR, CLIMATE, VACUUM, WATER_HEATER, NUMBER`() { … }
@Test fun `VoiceEntity carries Entity unit`() { … build … assertEquals("°C", … ) }
@Test fun `partOf and hidden entities are still dropped from the new kinds`() { … }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:voice:test --tests "*VoiceCatalogTest*"`
Expected: FAIL (new kinds dropped, `unit` field missing).

- [ ] **Step 3: Add `val unit: String?` to `VoiceEntity`, populate from `Entity.unit` in `VoiceCatalogBuilder.build`, expand `SUPPORTED` to the ten kinds listed in global constraints**

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS. If any existing matcher test regresses because a sensor now shows up in a shortlist, fix the test to pass the new catalog (the behaviour is intentional).

- [ ] **Step 5: Commit**

Message: `feat(voice): include queryable kinds in catalog and carry entity unit`

---

### Task 3: `:core:voice` `RuleMatcher` — resolve `QUERY_STATE`, attribute shortlist, `QueryHint`

**Files:**
- Modify: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/RuleMatcher.kt`
- Modify: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/RuleMatcherTest.kt`

**Interfaces:**
- Consumes: Task 1 (`Verb.QUERY`, `attributes`, `structuralQuery`, `QueryHint`), Task 2 (new catalog kinds).
- Produces: `RuleMatcher.resolve(text)` now returns `VoiceIntent(action = QUERY_STATE, entityIds, value = null, hint = …)` for query phrases; the shortlist narrows by attribute → device-class concept when an attribute word is heard.

- [ ] **Step 1: Write the failing tests**

Table-driven; one assertion per row. All in `RuleMatcherTest.kt`:

```kotlin
// Single-entity queries
@Test fun `quelle est la temperature du salon targets only the salon temperature sensor`()
@Test fun `combien d humidite dans le bureau targets only the office humidity sensor`()

// Group queries
@Test fun `est-ce que les volets sont fermes resolves to QUERY_STATE on every cover`()
@Test fun `les lumieres sont elles allumees resolves to QUERY_STATE on every light`()

// Hint
@Test fun `fermes sets hint to EXPECT_CLOSED`()
@Test fun `allumee sets hint to EXPECT_ON`()
@Test fun `plain quelle temperature leaves hint null`()

// Attribute precedence
@Test fun `attribute wins over device word and narrows candidates to the matching device-class`()
// (REVIEW FOCUS #4) — "quelle est l'humidite du salon" against a salon
// that has temperature + humidity + door sensors returns only the humidity entity.

// EN mirror
@Test fun `what is the temperature in the living room targets the living temperature sensor`()
@Test fun `are the shutters closed resolves to QUERY_STATE with EXPECT_CLOSED`()

// Attribute without a room goes across rooms
@Test fun `quelle est la temperature returns every temperature sensor`()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:voice:test --tests "*RuleMatcherTest*"`
Expected: FAIL (resolver returns null for query sentences).

- [ ] **Step 3: Implement query resolution**

In `RuleMatcher`:
- `parse` first runs each `table.structuralQuery` regex against the normalized sentence; a match sets `queryStructural = true`.
- The per-word loop recognizes `table.verbs[w] == Verb.QUERY` and also populates a new `attrs: Set<String>` from `table.attributes[w]`.
- The `Parsed` record gains `queryStructural: Boolean`, `attrs: Set<String>`.
- `resolve` picks `Verb.QUERY` when `queryStructural` or any `Verb.QUERY` word was heard (prefer `QUERY` over a state verb when both are present); otherwise falls back to the existing precedence.
- Shortlisting: when the action is `QUERY_STATE`, candidates are filtered by `attrs` matching `Entity.deviceClass` (or by `dev` when no attribute was heard, same as today); the special `state`/`status` marker matches any kind without narrowing by device-class.
- Group/plural handling: unchanged (`p.plural || p.all`) — a plural query returns every matching entity.
- Hint population: when the sentence has both a `QUERY` origin and one of the known state verbs (`CLOSE → EXPECT_CLOSED`, `OPEN → EXPECT_OPEN`, `ON → EXPECT_ON`, `OFF → EXPECT_OFF`), set `VoiceIntent.hint`; otherwise `null`.

Add a branch in `actionFor` returning `QUERY_STATE` for `Verb.QUERY` on any kind (no value, no code).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS. If any pre-existing matcher test breaks because a sentence it covers is now interpreted as `QUERY_STATE`, re-examine the test — it may now need `null` hint on a command intent, or it may surface a real precedence bug this task should fix in the parser.

- [ ] **Step 5: Commit**

Message: `feat(voice): resolve QUERY_STATE with attribute shortlist and state-verb hint`

---

### Task 4: `:core:voice` `CommandMapper` — short-circuit `QUERY_STATE`

**Files:**
- Modify: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/CommandMapper.kt`
- Modify: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/CommandMapperTest.kt`

**Interfaces:**
- Consumes: Task 1 (`VoiceAction.QUERY_STATE`).
- Produces: `CommandMapper.map(intent, catalog, code)` returns `emptyList()` when `intent.action == QUERY_STATE` (nothing to publish). All other actions unchanged.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `map returns an empty list for QUERY_STATE regardless of catalog or code`() {
    val catalog = …someCatalogWithASensor…
    val intent = VoiceIntent(VoiceAction.QUERY_STATE, listOf("sensor1"))
    assertEquals(emptyList<Pair<String, Command>>(), CommandMapper.map(intent, catalog))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:voice:test --tests "*CommandMapperTest*"`
Expected: FAIL (today falls through the `when`, returns a list of nulls → empty but silently; test anchors the behaviour).

- [ ] **Step 3: Add the branch**

Add `VoiceAction.QUERY_STATE -> null` to `commandFor` (so `mapNotNull` drops it) OR an early return in `map` — pick whichever reads cleaner next to the existing `when`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(voice): CommandMapper short-circuits QUERY_STATE`

---

### Task 5: `:core:voice` `AnswerFormatter` — pure answer composition

**Files:**
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/AnswerFormatter.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/AnswerFormatterTest.kt`

**Interfaces:**
- Consumes: Task 1 (`VoiceIntent`, `QueryHint`), Task 2 (`VoiceEntity.unit`), `EntityState` + `StateValue` from `:core:model`, `VoiceCatalog` + `VoiceArea` for room lookup.
- Produces:
  ```kotlin
  data class Answer(val spoken: String, val display: String, val speakable: Boolean, val staleIds: List<String>)
  object AnswerFormatter {
      fun format(lang: VoiceLang, intent: VoiceIntent, states: Map<String, EntityState>, catalog: VoiceCatalog): Answer
  }
  ```
  `spoken` is what TTS says, `display` is what the sheet shows (usually identical to `spoken`; keep two fields so a future "shorter spoken, longer display" variant is cheap). `speakable = false` only when the formatter produced placeholder text that would be confusing to read aloud (empty/unknown states).

- [ ] **Step 1: Write the failing tests**

A table-driven test (JUnit 5 `@TestFactory` or a plain parameterized list) covering, at minimum:
```kotlin
// Single-entity sensor
`FR temperature sensor renders "Il fait 22 degres dans le salon"`()
`EN temperature sensor renders "It's 22 degrees in the living"`()
`FR humidity sensor includes percent`()
`FR battery sensor leads with device name`()

// Boolean kinds
`FR light on renders "La lumiere du salon est allumee"`()
`FR light on with brightness appends ", luminosite 60 %"`()
`FR cover open renders "Le volet du salon est ouvert"`()
`FR cover with position 42 renders "partiellement ouvert (42 %)"`()
`FR binary_sensor door closed renders "La porte du garage est fermee"`()
`FR alarm armed_away renders localized state`()

// Climate / water_heater / vacuum / number
`FR climate renders current, target, mode`()
`FR vacuum renders state and battery`()
`FR water_heater renders like climate`()
`FR number renders name, value, unit`()

// Group aggregation (boolean)
`FR all shutters closed renders "Oui, tous les volets sont fermes"`()
`FR mixed shutters renders "k volets sur n sont fermes, n-k ouverts"`()
`FR hint EXPECT_CLOSED leads with Oui or Non`()

// Group aggregation (sensor)
`FR 3 temperatures in salon averages with "en moyenne"`()
`FR 3 temperatures with one named Temperature wins as ambient and skips average`()
// (REVIEW FOCUS #5)
`FR temperatures across 3 rooms renders "Salon 22, Cuisine 23, Bureau 21" with unit only once`()
`FR discrete power sensors list per entity, no average`()

// Fallbacks (REVIEW FOCUS #1 and #2)
`an Unknown or Unsupported state renders as the no-reading fallback`()
`an unavailable entity renders as the no-reading fallback`()
`a stale state appends the updated-X-ago tail once`()

// EN mirrors for a representative subset (temperature, cover group, humidity, stale, unknown).
```

Each test builds a tiny `VoiceCatalog` + `Map<String, EntityState>` from fixtures (helpers at the bottom of the test file; no shared fixtures module needed for v1).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:voice:test --tests "*AnswerFormatterTest*"`
Expected: FAIL (class absent → compilation error, then test failures as rows are added).

- [ ] **Step 3: Implement `AnswerFormatter`**

Shape:
- A `kindBranch(intent, states, catalog, lang) → Answer` big `when` by first entity's kind.
- Each kind branch pulls its localized template strings from a private `object FrTemplates` / `EnTemplates`; keep every string literal in the formatter file (not in Android resources — `:core:voice` is pure Kotlin).
- Group aggregation: group `intent.entityIds` by kind + device-class + area; apply the rules in global constraints and spec §4.
- Ambient preference: within a `(kind, deviceClass, area)` group of continuous sensors with `n > 1`, if any entity's `displayName` folds to the device-class noun (`"Température"`) or `"${areaName} ${deviceClassNoun}"`, pick it and skip averaging.
- Hint consumption: when `intent.hint != null` and the aggregated boolean state can be compared, lead with `"Oui"` (all match) or `"Non"` (none / some don't).
- Staleness: collect `staleIds` from `states` entries; append the tail once if non-empty, using `Duration.between(lastUpdate, now)` — take `now` as a parameter with a default `Instant::now` to keep tests deterministic.
- `speakable`: `false` only when the whole answer is "I don't have a reading" (nothing meaningful to say).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(voice): add AnswerFormatter with per-kind templates, group aggregation, staleness`

---

### Task 6: `:feature:voice` `TtsSpeaker` — Android TTS wrapper

**Files:**
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/TtsSpeaker.kt`
- Create: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/TtsSpeakerTest.kt`

**Interfaces:**
- Consumes: Android `android.speech.tts.TextToSpeech`, `VoiceLang` from `:core:voice`.
- Produces:
  ```kotlin
  enum class TtsAvailability { Unknown, Ready, LanguageMissing, EngineMissing }
  interface TtsEngine { // tiny seam over TextToSpeech so JVM tests can double it
      fun setLanguage(locale: Locale): Int
      fun isLanguageAvailable(locale: Locale): Int
      fun speak(text: String, queueMode: Int): Int
      fun shutdown()
  }
  class TtsSpeaker(private val engineFactory: (OnInitListener) -> TtsEngine) {
      val availability: StateFlow<TtsAvailability>
      fun speak(text: String)
      fun release()
  }
  ```
  The real `AndroidTtsSpeaker` factory (one tiny file wrapping `TextToSpeech`) wires the interface in `di/`; the test uses a fake. `speak` always calls with `QUEUE_FLUSH` (new answer replaces older). `release` calls `shutdown()` and nulls the engine.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `availability starts Unknown and becomes Ready on init success with language available`()
@Test fun `TtsSpeaker reports LanguageMissing when isLanguageAvailable is LANG_MISSING_DATA`()
   // (REVIEW FOCUS #3)
@Test fun `availability becomes EngineMissing on init error`()
@Test fun `speak flushes the queue (QUEUE_FLUSH)`()
@Test fun `speak is a no-op when availability is LanguageMissing or EngineMissing`()
@Test fun `release calls shutdown and ignores subsequent speak calls`()
```

Use a `FakeTtsEngine` recorded-call double; the `TtsSpeaker` constructor takes a factory so the test provides it and triggers the `OnInitListener` manually.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:voice:testDebugUnitTest --tests "*TtsSpeakerTest*"`
Expected: FAIL (classes absent).

- [ ] **Step 3: Implement `TtsEngine`, `TtsAvailability`, `TtsSpeaker` (pure JVM-testable) and a thin `AndroidTtsEngine` in the same file wrapping `TextToSpeech`**

The `AndroidTtsEngine` constructor takes a `Context` and an `OnInitListener`; its implementation is three one-liners plus `shutdown`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(voice): add TtsSpeaker wrapping Android TextToSpeech`

---

### Task 7: `:feature:voice` session state — `Spoken` and `Picking`, `autoCloses` update

**Files:**
- Modify: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceSession.kt`
- Modify: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/AutoCloseTest.kt`

**Interfaces:**
- Consumes: Task 5 (`Answer`), Task 1 (`VoiceAction`, `QueryHint`), existing `VoiceEntity`, `Suggestion`.
- Produces:
  ```kotlin
  sealed interface VoiceSessionState {
      …existing…
      data class Spoken(val text: String, val speakable: Boolean, val staleIds: List<String>) : VoiceSessionState
      data class Picking(val step: PickStep, val heardText: String, val filter: String, val pickedId: String? = null, val suggestions: List<Suggestion>) : VoiceSessionState
  }
  enum class PickStep { Device, Action }
  ```
  `autoCloses(Spoken) == true`; `autoCloses(Picking) == false`. `VoiceSheet` already special-cases the auto-close delay — the new value for `Spoken` is 5 s (vs 2 s for `Done`); introduce a `fun autoCloseDelayMs(VoiceSessionState): Long` so the sheet doesn't branch inline.

- [ ] **Step 1: Write the failing tests**

In `AutoCloseTest.kt`:
```kotlin
@Test fun `autoCloses is true for Spoken`()
@Test fun `autoCloses is false for Picking`()
@Test fun `autoCloseDelayMs is 5000 for Spoken and 2000 for successful Done`()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:voice:testDebugUnitTest --tests "*AutoCloseTest*"`
Expected: FAIL (state classes and the helper absent).

- [ ] **Step 3: Add the two state classes, `PickStep` enum, and `autoCloseDelayMs`**

Keep `suggestions: List<Suggestion>` on `Picking` so pressing *Back* from `PickStep.Device` can rebuild the suggestions view without re-running the engine.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(voice): add Spoken and Picking session states and autoCloseDelayMs`

---

### Task 8: `:feature:voice` `VoiceCommandRunner.query()`

**Files:**
- Modify: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceCommandRunner.kt`
- Modify: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/VoiceCommandRunnerTest.kt`

**Interfaces:**
- Consumes: Task 5 (`AnswerFormatter`, `Answer`), existing `EntitySource.states()`.
- Produces:
  ```kotlin
  data class QueryResult(val answer: Answer)
  interface CommandRunner {
      …existing run…
      suspend fun query(intent: VoiceIntent, catalog: VoiceCatalog): QueryResult
  }
  ```
  The implementation snapshots `entitySource().states().first()`, calls `AnswerFormatter.format(catalog.lang, intent, snapshot, catalog)`, returns `QueryResult(answer)`. No MQTT publish, so `MqttDownException` is NOT thrown — cached state is enough to answer, and staleness is marked by the formatter. The runner still resolves `entitySource` lazily (same cold-start guard as the control path).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `query snapshots the current states and returns the AnswerFormatter output`()
@Test fun `query uses the lazily resolved EntitySource and never captures a stale one`()
@Test fun `query returns a stale answer when the broker is disconnected but a cached snapshot exists`()
```

Use the existing `Fakes.kt` + `Fixtures.kt`; add a simple fake `EntitySource` that emits one `Map<String, EntityState>` and a `LinkState` the test controls.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:voice:testDebugUnitTest --tests "*VoiceCommandRunnerTest*"`
Expected: FAIL (method absent).

- [ ] **Step 3: Implement `VoiceCommandRunner.query`**

Signature: `override suspend fun query(intent: VoiceIntent, catalog: VoiceCatalog): QueryResult = coroutineScope { … }`. Use `states().first()` (first emission) with no timeout — the entity source always emits at least an empty map immediately.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(voice): add VoiceCommandRunner.query that snapshots EntityState and formats`

---

### Task 9: `:feature:voice` `VoiceViewModel` — route `QUERY_STATE`, picker transitions, teach-on-pick

**Files:**
- Modify: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceViewModel.kt`
- Modify: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/VoiceSessionTest.kt`

**Interfaces:**
- Consumes: Tasks 6, 7, 8 (`TtsSpeaker`, new session states, `CommandRunner.query`), existing `VoiceEngine.resolve`.
- Produces: new VM entry points
  ```kotlin
  fun onPickOther()                            // NotUnderstood → Picking(Device)
  fun onPickDevice(uniqueId: String)           // Picking(Device) → Picking(Action, pickedId = uniqueId)
  fun onPickAction(action: VoiceAction, remember: Boolean)  // Picking(Action) → run or query, optionally saveShortcut(heardText, intent)
  fun onPickBack()                             // Action → Device, Device → NotUnderstood(from Picking.suggestions)
  fun onPickFilterChange(filter: String)       // keeps the Picking state, updates filter
  fun onRepeatSpoken()                         // re-speaks the last Spoken.text
  ```
  Internal: when a resolved intent has `action == QUERY_STATE`, call `runner.query(…)`, transition to `Spoken(answer.display, answer.speakable, answer.staleIds)`, and call `TtsSpeaker.speak(answer.spoken)` once if `speakable`.

- [ ] **Step 1: Write the failing tests**

In `VoiceSessionTest.kt` (Turbine-style over `state: StateFlow<VoiceSessionState>`):
```kotlin
@Test fun `a QUERY_STATE intent transitions to Spoken with the formatter sentence and speaks it once`()
@Test fun `Spoken is skipped past speak when TTS availability is LanguageMissing`()
@Test fun `onPickOther from NotUnderstood goes to Picking(Device) with the heard text as filter`()
@Test fun `onPickDevice goes to Picking(Action, pickedId)`()
@Test fun `onPickAction runs a control intent through the runner and lands in Done`()
@Test fun `onPickAction with QUERY_STATE goes through runner query and lands in Spoken`()
@Test fun `onPickAction with remember=true saves a shortcut mapping heardText to the intent`()
@Test fun `onPickAction with remember=false does not save a shortcut`()
@Test fun `onPickBack from Action returns to Picking(Device) with same filter and pickedId null`()
@Test fun `onPickBack from Device returns to NotUnderstood with the original suggestions`()
@Test fun `onPickFilterChange updates filter but keeps the step and pickedId`()
@Test fun `onRepeatSpoken calls TtsSpeaker speak again`()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:voice:testDebugUnitTest --tests "*VoiceSessionTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement the new VM entry points and the QUERY route**

Inject `TtsSpeaker` through the existing `:feature:voice/di` module (constructor-inject into `VoiceViewModel`). For `onPickAction`, build the intent `VoiceIntent(action, listOf(pickedId))` with no value and no hint, then dispatch to the same `run()` / `query()` path a matched intent uses — reuse `Confirmation.needsConfirmation` so `ARM/DISARM` picks still land in `ConfirmNeeded`.

For teach: when `remember == true` and the action succeeded (control) or produced a `Spoken` (query), build a `VoiceShortcut` with `phrase = heardText` (as said, pre-normalization), `lang = catalog.lang`, `action`, `entityIds = [pickedId]`, `value = null`. Call `entitySource.saveShortcut(shortcut)` (existing).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(voice): route QUERY_STATE to Spoken via TTS and add Picker transitions in VoiceViewModel`

---

### Task 10: `:feature:voice` picker UI — `DevicePickerView` and `ActionPickerView`

**Files:**
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/DevicePickerView.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/ActionPickerView.kt`
- Modify: `feature/voice/src/main/res/values/strings.xml` (keys below)
- Modify: `feature/voice/src/main/res/values-fr/strings.xml` (same keys, FR copy)
- Create: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/ui/DevicePickerViewScreenshotTest.kt`
- Create: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/ui/ActionPickerViewScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 7 (`Picking` state, `PickStep`), Task 9 (`onPickDevice`, `onPickAction`, `onPickBack`, `onPickFilterChange`), `VoiceCatalog`, `VoiceArea`, current `EntityState` snapshot (passed down for the state-hint column — read once in the VM and included on the `Picking` state as `preview: Map<String, String>` or sourced from a dedicated flow).
- Produces: two internal `@Composable fun ColumnScope.X(…)` that render inside the existing `VoiceSheet`.

Strings to add (both `values/` and `values-fr/`):
- `voice_picker_search_hint`
- `voice_picker_empty`
- `voice_picker_back`
- `voice_picker_remember_phrase`
- `voice_picker_read_value`
- `voice_picker_read_state`

- [ ] **Step 1: Write the failing screenshot tests**

Mirror the existing `UnderstoodViewScreenshotTest` / `NotUnderstoodViewScreenshotTest` pattern (`sc instructions review` governs the pattern already used in the repo). One shot per view per theme (light / dark), seeded with a tiny fixed catalog:
```kotlin
@Test fun devicePicker_default_light()
@Test fun devicePicker_default_dark()
@Test fun devicePicker_filtered_light()
@Test fun actionPicker_light_light()         // picking a LIGHT
@Test fun actionPicker_sensor_light()        // picking a SENSOR (only Read)
@Test fun actionPicker_cover_dark()
@Test fun actionPicker_alarm_light()         // showing ARM_HOME / ARM_AWAY / DISARM
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:voice:testDebugUnitTest --tests "*PickerViewScreenshotTest*"`
Expected: FAIL (views absent).

- [ ] **Step 3: Implement the two views**

`DevicePickerView`:
- Top: `OutlinedTextField` filter (prefilled with `Picking.filter`), on-change calls `onPickFilterChange`.
- Body: `LazyColumn`, grouped by `VoiceArea`, sorted by the catalog's area order. Each row = `VoiceRow` with entity name + a muted trailing `Text` of the current state hint (On / Off / 22° / Open) from the snapshot (null-tolerant).
- Filter: case-and-accent-folded contains against `entity.displayName`, `area.displayName`, and the entity's kind label (reuse `VoiceLexicon.actionLabel`-style helper or add a tiny `kindLabel(lang, kind)` in `VoiceLexicon`).
- Bottom: a `VoiceRow` *Retour* / *Back* calling `onPickBack`.

`ActionPickerView`:
- Header: entity name + area display name.
- Rows: as specified in design §3.3 — kind-scoped actions, with *Lire la valeur* / *Read value* as the lone row for SENSOR/BINARY_SENSOR/CLIMATE/VACUUM/WATER_HEATER/NUMBER, and *Lire l'état* / *Read state* as the first row for LIGHT/SWITCH/COVER/ALARM_PANEL plus the control actions (minus `SET_POSITION` and `SET_BRIGHTNESS`).
- A small *Mémoriser cette phrase* / *Remember this phrase* chip (reuse Material 3 `FilterChip` or an in-house pill) above the actions; its state is held locally in the composable and passed to `onPickAction(action, remember = chip.selected)`.
- Bottom: *Retour* row calling `onPickBack`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: PASS; new reference screenshots committed.

- [ ] **Step 5: Commit**

Message: `feat(voice): add DevicePickerView and ActionPickerView`

---

### Task 11: `:feature:voice` sheet wiring — `SpokenView`, `VoiceSheet` routing, `NotUnderstoodView` Autre

**Files:**
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/SpokenView.kt`
- Modify: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/VoiceSheet.kt`
- Modify: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/NotUnderstoodView.kt`
- Modify: `feature/voice/src/main/res/values/strings.xml` and `values-fr/strings.xml` (keys below)
- Create: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/ui/SpokenViewScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 7 (`Spoken`, `Picking`), Task 9 (`onPickOther`, `onRepeatSpoken`, `onPickBack`), Task 10 (`DevicePickerView`, `ActionPickerView`).
- Produces: `SpokenView(text, speakable, onRepeat)` — a `Text` with the sentence and (when `speakable`) a `Repeat` row.

Strings to add:
- `voice_repeat` / `voice_spoken_stale_suffix` (literal "il y a Xh ago" is formatter-side; this string is for UI chrome only, if needed).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun spokenView_light()
@Test fun spokenView_dark()
@Test fun spokenView_text_only_when_not_speakable_light()
```

And in `VoiceSheetTest` (if present, else a tiny new file): assert that `VoiceSheet` dispatches `Spoken` to `SpokenView`, `Picking(Device, …)` to `DevicePickerView`, `Picking(Action, …)` to `ActionPickerView`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:voice:testDebugUnitTest --tests "*SpokenView*" --tests "*VoiceSheet*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`SpokenView` renders the text with `OikosTheme.type.body` and `OikosTheme.colors.content` and shows a `VoiceRow` *Répéter* / *Repeat* only when `speakable`.

`VoiceSheet`: add branches for `Spoken` → `SpokenView(current.text, current.speakable, onRepeatSpoken)` and `Picking` → routed to either `DevicePickerView` or `ActionPickerView` based on `step`.

`NotUnderstoodView`: replace the `onCancel` wired to the *Autre…* row with a new `onPickOther: () -> Unit` parameter; `VoiceSheet` passes `viewModel::onPickOther`. The `Ignorer` row still calls `onCancel`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: PASS; new reference screenshots committed.

- [ ] **Step 5: Commit**

Message: `feat(voice): add SpokenView, wire VoiceSheet to Picking and Spoken, Autre opens the picker`

---

### Task 12: `:feature:settings` TTS availability line

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/VoiceSection.kt`
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/VoiceSettingsUi.kt` (if present; otherwise the file holding the state shape consumed by `VoiceSection`)
- Modify: `feature/settings/src/main/res/values/strings.xml` + `values-fr/strings.xml`
- Modify: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModelTest.kt` (or create if absent)
- Create (if absent): `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/ui/VoiceSectionScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 6 (`TtsSpeaker.availability: StateFlow<TtsAvailability>`).
- Produces: a `ttsAvailability: TtsAvailability` field on the existing settings UI state next to the STT one; `VoiceSection` renders a second line labeled *Voix de réponse*. When `LanguageMissing`, a *Télécharger* / *Download* action launches `Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)`; when `EngineMissing`, the line is informational only.

Strings to add:
- `settings_voice_tts_label` (`"Voix de réponse"` / `"Response voice"`)
- `settings_voice_tts_ready`
- `settings_voice_tts_language_missing`
- `settings_voice_tts_engine_missing`
- `settings_voice_tts_download_action`

- [ ] **Step 1: Write the failing tests**

```kotlin
// SettingsViewModelTest
@Test fun `ttsAvailability flows from TtsSpeaker through to UI state`()

// VoiceSectionScreenshotTest
@Test fun voiceSection_tts_ready_light()
@Test fun voiceSection_tts_language_missing_shows_download_light()
@Test fun voiceSection_tts_engine_missing_light()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

Inject `TtsSpeaker` into `SettingsViewModel` (same Hilt scope as `SpeechAvailability`); combine its `availability` flow into the existing settings state. In `VoiceSection`, render the second status line below the STT one; wrap the *Télécharger* row in an `rememberLauncherForActivityResult(StartActivityForResult)` that launches `Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Message: `feat(settings): surface TTS availability line with Télécharger action`

---

### Task 13: Full gate, push, CI, device checklist

**Files:** none (verification and delivery).

**Interfaces:** everything from Tasks 1–12 is wired.

- [ ] **Step 1: Full test gate**

Run: `./gradlew check`
Expected: PASS. If a Robolectric Compose test hits the flaky `SettingsVM UncaughtExceptionsBeforeTest` or `SnapshotStateObserver multithread` paths (see memory `settings-viewmodel-flake`), rerun first before investigating.

- [ ] **Step 2: Rebase and push**

```bash
git fetch origin
git pull --rebase origin master
git push -u origin feat/voice-answers
```

- [ ] **Step 3: Watch CI and Firebase**

A few minutes after push, run `gh run list --branch feat/voice-answers --limit 6`. Both `ci.yml` and `distribute.yml` must land green. On failure root-cause, fix, verify locally, commit, rebase, push, re-check — repeat until green and the Firebase build uploads. Authorized without asking per the memory `check-ci-after-push` directive.

- [ ] **Step 4: Merge into master**

Fast-forward or no-ff per the repo's existing voice-feature pattern (b473d55 was a fast-forward merge). Push master. Watch CI + Firebase on master.

- [ ] **Step 5: Device checklist (post-merge)**

Run the six checks from spec §9 on a physical device after the Firebase build installs:
1. Voice toggle on → Settings shows "Voix de réponse : disponible".
2. "Quelle est la température du salon ?" → spoken + text.
3. "Est-ce que les volets sont fermés ?" → aggregated FR answer.
4. "Allume la guirlande" (unknown device) → *Autre…* → search "guir" → pick → *Allumer* → runs + offers *Mémoriser*.
5. Repeat #4's phrase → straight-through (shortcut).
6. Disable system TTS pack → sentence shown as text only; Settings line shows *Télécharger*.

- [ ] **Step 6: Update memory**

Append a line under `[Voice assistant]` in `memory/MEMORY.md`: date, status, notable device-check outcomes, any follow-ups.

---

## Self-review notes

Spec coverage spot-check: §1–2 goals/anti-goals → Task 11 UX; §3.1 lexicon/catalog/models → Tasks 1–2; §3.1 matcher/mapper → Tasks 3–4; §3.2 TTS and session states → Tasks 6–7; §3.2 runner and VM → Tasks 8–9; §3.3 picker UI → Task 10; §3.4 Settings → Task 12; §4 templates and aggregation → Task 5; §5 teach → Task 9; §6 picker tap-only path → Tasks 10 + 9; §7 risks → covered by Review Focus pins; §8 test shape → Tasks 1–12 each own their tests; §9 delivery → Task 13.

The plan ends at under half the length of the spec, and each task's steps either pick a tool, set a signature, or verify a run. No "handle edge cases" lines.
