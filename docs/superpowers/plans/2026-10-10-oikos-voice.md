# Oikos Voice Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional voice commands to Oikos — say "ouvre les volets du salon" and the real shutters move, with no rules to write; a tap-to-teach fallback for anything not understood; shortcuts shared across phones through retained MQTT.

**Architecture:** Two new modules. `:core:voice` is pure Kotlin on `:core:model`: lexicons per language, a `VoiceCatalog` built from the live `List<Entity>`/`List<Area>`, a `RuleMatcher` that produces a `VoiceIntent`, a `Suggester` that lists up to 6 ranked candidates, a `ShortcutMatcher` for learned phrases, a `CommandMapper` that turns an intent into `List<Pair<uniqueId, Command>>`, and a `VoiceEngine` that tries shortcuts first, then rules. `:feature:voice` is Android: `SpeechCapture` wraps `SpeechRecognizer.createOnDeviceSpeechRecognizer`, `VoiceSession` is a ViewModel state machine, `VoiceActivity` is a translucent host for the Compose bottom sheet, `VoiceShortcutRepository` reads/writes retained messages on `<prefix>/voice/shortcut/<id>` through `EntitySource`. Settings gains a toggle (off by default). Home grows a mic button in the top bar; the existing widget grows a mic button; a new voice widget mirrors it. All three open the same `VoiceActivity`.

**Tech Stack:** Kotlin 2.4.20, Jetpack Compose (Material 3), kotlinx.serialization, kotlinx.coroutines, Hilt, Glance (voice widget), AndroidX DataStore (`AppPreferences`), JUnit4 + Robolectric + Turbine for `:feature:voice`, JUnit Jupiter for `:core:voice`.

**Spec:** `docs/superpowers/specs/2026-10-10-oikos-voice-design.md`

## Global Constraints

- **Android version:** `SpeechRecognizer.createOnDeviceSpeechRecognizer` and `isOnDeviceRecognitionAvailable` need API 31. `:feature:voice` keeps `minSdk = 28` (same as the app) but every call that needs API 31 is guarded (and the Settings switch is shown disabled when the device cannot run it). There is no fallback to the default recognizer.
- **Manifest:** `<queries><intent><action android:name="android.speech.RecognitionService" /></intent></queries>` is required on API 30+ or Android's AppsFilter answers "not available" even when the recognizer exists (seen in the prototype).
- **Permission:** `RECORD_AUDIO` is requested the first time the switch is turned on; a refusal leaves it off. The permission is declared in `:app/AndroidManifest.xml` and used from `:feature:voice`.
- **Supported entity kinds (voice):** `Kind.LIGHT`, `Kind.SWITCH`, `Kind.COVER`, `Kind.ALARM_PANEL`. Everything else is dropped from the catalog.
- **Catalog filter:** `partOf == null` and `ui.hidden == false`. Area is the one `Entity.area` already carries (overrides have been applied).
- **Confirmation:** `needsConfirmation(intent, catalog)` is true for any `Action.ARM_AWAY | ARM_HOME | DISARM`, and for any entity whose kind is `ALARM_PANEL`, whose `deviceClass == "siren"`, or whose display name contains the word siren/sirène (case-insensitive).
- **MQTT topic for shortcuts:** `<prefix>/voice/shortcut/<id>`, retained, QoS 1. `<prefix>` is the one `MqttDiscoverySource.UI_PREFIX` is built from (currently `oikos`). `<id>` is a stable hex hash of `lang + ":" + normalizedPhrase` (SHA-256, first 16 bytes, hex). An empty retained payload forgets the shortcut.
- **Shortcut payload v1:** `{"v":1,"phrase":<as said>,"lang":"fr"|"en","action":"TURN_ON"|…,"entityIds":[…],"value":<int?>}`. Decoder ignores unknown keys; shortcuts whose `lang` differs from the current app locale are ignored (never deleted). An entity id that no longer exists in the live catalog is dropped at match time; an intent with no entity left is broken and never matches.
- **Language:** `fr` when the app locale's language is `fr`, `en` otherwise. The chosen language is passed into the engine; `:core:voice` never reads the Android locale.
- **Strings:** every new user-visible string lives in both `values/strings.xml` and `values-fr/strings.xml` for the module that owns it. The settings strings also carry alarm/siren confirmation wording.
- **Package names:** `:core:voice` → `com.gekkotron.oikos.voice`, `:feature:voice` → `com.gekkotron.oikos.feature.voice`.
- **Commit identity on every commit** (also inside every subagent dispatch): `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`. Each commit message ends with:
  ```
  Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
  Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
  ```
  Verify `git log --format='%ae %ce'` on the new range before any push; the author/committer must be `60887050+Gekkotron@users.noreply.github.com`.
- **Work on branch** `feat/voice` (created from `master` in Task 1).
- **Module test commands:**
  - `./gradlew :core:voice:test`
  - `./gradlew :feature:voice:testDebugUnitTest`
  - Full gate before push: `./gradlew check`
- **Android ICU regex:** escape literal `]` and `}` in any `Regex` (ICU throws at class init; JVM tests pass). None are planned, but `:feature:voice` runs on Android so this rule applies.
- **Model dependency only:** `:core:voice` depends on `:core:model` (for `Kind`, `Entity`, `Area`, `Command`, `AlarmMode`, `CodeFormat`). No Android, no coroutines, no serialization in `:core:voice` itself — serialization of the shortcut payload lives in `:feature:voice`.

## Review Focus

Five input classes the spec implies but no task's tests exercise unless pinned. Each line names the input and the behaviour a reasonable user would expect, most likely first.

1. **A broken shortcut (every target entity id has disappeared) that matches a spoken phrase.** It must be reported as broken in the shortcuts list, and the matcher must never return it; the next level (rules) still runs. Pinned by Task 4, test `a shortcut with no surviving entity id is broken and never matches`.
2. **A spoken phrase that resolves to a mix of entity kinds the intent cannot drive (e.g. "ferme" matched against a LIGHT).** `CommandMapper` must drop the incompatible pair and reject the intent when nothing survives; the sheet must not send a wrong command. Pinned by Task 3, test `incompatible kind-command pairs are dropped and an intent with none left is rejected`.
3. **A siren named "Siren" on a plain SWITCH entity.** `needsConfirmation` must be true although the kind is SWITCH, so the sheet asks before firing. Pinned by Task 3, test `needsConfirmation is true for a switch whose name is siren`.
4. **The broker drops the connection the moment the user confirms an alarm disarm.** `VoiceSession` must stay in Confirm/Executing until the publish fails, then land in `Error(MqttDown)` with nothing sent to any entity. Pinned by Task 7, test `an alarm confirm with a disconnected broker ends in Error without sending`.
5. **A phrase taught in French while the app is now in English, or vice versa.** The other-language shortcut must be ignored (never matched) and never deleted; its row in the shortcuts list stays visible with its lang chip, and a new teach for the same normalized phrase in the current lang coexists with it. Pinned by Task 4, test `shortcuts of the other language are ignored and preserved`.

---

### Task 1: `:core:voice` module, model types and lexicons

**Files:**
- Create: `core/voice/build.gradle.kts`
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceModels.kt`
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceLexicon.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceLexiconTest.kt`
- Modify: `settings.gradle.kts` (add `include(":core:voice")`)

**Interfaces:**
- Produces:
  - `enum class VoiceAction { TURN_ON, TURN_OFF, OPEN, CLOSE, STOP, SET_POSITION, SET_BRIGHTNESS, ARM_AWAY, ARM_HOME, DISARM }`
  - `enum class VoiceLang { FR, EN }` with companion `fun fromAppLocale(languageTag: String): VoiceLang` returning `FR` iff the tag starts with `"fr"`.
  - `data class VoiceIntent(val action: VoiceAction, val entityIds: List<String>, val value: Int? = null)`
  - `enum class VoiceSource { SHORTCUT, RULES }`
  - `sealed interface VoiceResolution { data class Understood(val intent: VoiceIntent, val source: VoiceSource) : VoiceResolution; data class NotUnderstood(val suggestions: List<Suggestion>) : VoiceResolution }`
  - `data class Suggestion(val intent: VoiceIntent, val label: String)`
  - `object VoiceLexicon { data class Table(val verbs: Map<String, Verb>, val devices: Map<String, String>, val modifiers: Map<String, String>, val rooms: Map<String, String>, val noise: Set<String>, val allWords: Set<String>, val alarmHomeWords: Set<String>, val shortcutStopWords: Set<String>, val phrases: List<Pair<Regex, String>>) ; fun of(lang: VoiceLang): Table ; enum class Verb { ON, OFF, OPEN, CLOSE, STOP, STOP_OR_OFF, SET, ARM, DISARM } ; fun actionLabel(lang: VoiceLang, a: VoiceAction): String }`

- [ ] **Step 0: Branch**

```bash
git switch -c feat/voice master
```

- [ ] **Step 1: Register the module and write its build file**

Append to `settings.gradle.kts`, right after `include(":core:source")`:

```kotlin
include(":core:voice")
```

Create `core/voice/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.jvm.library")
}

dependencies {
    implementation(project(":core:model"))
}
```

- [ ] **Step 2: Write the model file**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceModels.kt`:

```kotlin
package com.gekkotron.oikos.voice

enum class VoiceAction { TURN_ON, TURN_OFF, OPEN, CLOSE, STOP, SET_POSITION, SET_BRIGHTNESS, ARM_AWAY, ARM_HOME, DISARM }

enum class VoiceLang {
    FR, EN;

    companion object {
        fun fromAppLocale(languageTag: String): VoiceLang =
            if (languageTag.lowercase().startsWith("fr")) FR else EN
    }
}

data class VoiceIntent(val action: VoiceAction, val entityIds: List<String>, val value: Int? = null) {
    init { require(value == null || value in 0..100) { "value must be 0..100" } }
}

enum class VoiceSource { SHORTCUT, RULES }

data class Suggestion(val intent: VoiceIntent, val label: String)

sealed interface VoiceResolution {
    data class Understood(val intent: VoiceIntent, val source: VoiceSource) : VoiceResolution
    data class NotUnderstood(val suggestions: List<Suggestion>) : VoiceResolution
}
```

- [ ] **Step 3: Write the failing lexicon tests**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceLexiconTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceLexiconTest {
    @Test fun `fromAppLocale returns FR for fr-FR and fr`() {
        assertEquals(VoiceLang.FR, VoiceLang.fromAppLocale("fr-FR"))
        assertEquals(VoiceLang.FR, VoiceLang.fromAppLocale("fr"))
    }

    @Test fun `fromAppLocale returns EN for anything else`() {
        assertEquals(VoiceLang.EN, VoiceLang.fromAppLocale("en-US"))
        assertEquals(VoiceLang.EN, VoiceLang.fromAppLocale("de-DE"))
        assertEquals(VoiceLang.EN, VoiceLang.fromAppLocale(""))
    }

    @Test fun `fr table has French verbs mapped to the right concepts`() {
        val t = VoiceLexicon.of(VoiceLang.FR)
        assertEquals(VoiceLexicon.Verb.ON, t.verbs["allume"])
        assertEquals(VoiceLexicon.Verb.OFF, t.verbs["eteins"])
        assertEquals(VoiceLexicon.Verb.OPEN, t.verbs["ouvre"])
        assertEquals(VoiceLexicon.Verb.CLOSE, t.verbs["ferme"])
        assertEquals(VoiceLexicon.Verb.ARM, t.verbs["arme"])
        assertEquals(VoiceLexicon.Verb.DISARM, t.verbs["desarme"])
    }

    @Test fun `en table has English verbs mapped to the right concepts`() {
        val t = VoiceLexicon.of(VoiceLang.EN)
        assertEquals(VoiceLexicon.Verb.ON, t.verbs["turn"])
        assertEquals(VoiceLexicon.Verb.ON, t.verbs["switch"])
        assertEquals(VoiceLexicon.Verb.OPEN, t.verbs["open"])
        assertEquals(VoiceLexicon.Verb.CLOSE, t.verbs["close"])
        assertEquals(VoiceLexicon.Verb.ARM, t.verbs["arm"])
        assertEquals(VoiceLexicon.Verb.DISARM, t.verbs["disarm"])
    }

    @Test fun `both tables share the same canonical device concepts`() {
        val fr = VoiceLexicon.of(VoiceLang.FR).devices
        val en = VoiceLexicon.of(VoiceLang.EN).devices
        assertEquals("cover", fr["volet"])
        assertEquals("cover", en["shutter"])
        assertEquals("light", fr["lumiere"])
        assertEquals("light", en["light"])
        assertEquals("siren", fr["sirene"])
        assertEquals("siren", en["siren"])
    }

    @Test fun `both tables share the same canonical room concepts`() {
        val fr = VoiceLexicon.of(VoiceLang.FR).rooms
        val en = VoiceLexicon.of(VoiceLang.EN).rooms
        assertEquals("living", fr["salon"])
        assertEquals("living", en["living"])
        assertEquals("kitchen", fr["cuisine"])
        assertEquals("kitchen", en["kitchen"])
        assertEquals("bedroom", fr["chambre"])
        assertEquals("bedroom", en["bedroom"])
    }

    @Test fun `French action labels are translated`() {
        assertEquals("Allumer", VoiceLexicon.actionLabel(VoiceLang.FR, VoiceAction.TURN_ON))
        assertEquals("Désarmer", VoiceLexicon.actionLabel(VoiceLang.FR, VoiceAction.DISARM))
    }

    @Test fun `English action labels are translated`() {
        assertEquals("Turn on", VoiceLexicon.actionLabel(VoiceLang.EN, VoiceAction.TURN_ON))
        assertEquals("Disarm", VoiceLexicon.actionLabel(VoiceLang.EN, VoiceAction.DISARM))
    }

    @Test fun `noise contains filler words for its language`() {
        assertTrue("les" in VoiceLexicon.of(VoiceLang.FR).noise)
        assertTrue("the" in VoiceLexicon.of(VoiceLang.EN).noise)
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :core:voice:test`
Expected: FAIL — class `VoiceLexicon` not defined (and the model file compiles).

- [ ] **Step 5: Implement `VoiceLexicon`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceLexicon.kt`:

```kotlin
package com.gekkotron.oikos.voice

/** Language-neutral vocabulary tables. All keys are accent-folded, lowercase. */
object VoiceLexicon {
    enum class Verb { ON, OFF, OPEN, CLOSE, STOP, STOP_OR_OFF, SET, ARM, DISARM }

    data class Table(
        val verbs: Map<String, Verb>,
        val devices: Map<String, String>,
        val modifiers: Map<String, String>,
        val rooms: Map<String, String>,
        val noise: Set<String>,
        val allWords: Set<String>,
        val alarmHomeWords: Set<String>,
        val shortcutStopWords: Set<String>,
        val phrases: List<Pair<Regex, String>>,
    )

    private val FR = Table(
        verbs = buildMap {
            fun add(v: Verb, vararg ws: String) = ws.forEach { put(it, v) }
            add(Verb.ON, "allume", "allumer", "allumes", "allumez", "active", "activer", "actives", "activez", "marche", "demarre", "demarrer")
            add(Verb.OFF, "eteins", "eteint", "eteindre", "eteignez", "coupe", "couper", "coupes", "coupez", "desactive", "desactiver", "desactives", "desactivez")
            add(Verb.ARM, "arme", "armer", "armes", "armez")
            add(Verb.DISARM, "desarme", "desarmer", "desarmes", "desarmez")
            add(Verb.OPEN, "ouvre", "ouvrir", "ouvres", "ouvrez", "monte", "monter", "montes", "montez", "leve", "lever", "levez")
            add(Verb.CLOSE, "ferme", "fermer", "fermes", "fermez", "descends", "descend", "descendre", "descendez", "baisse", "baisser", "baisses", "baissez")
            add(Verb.STOP, "stop", "stoppe", "stopper", "stoppez")
            add(Verb.STOP_OR_OFF, "arrete", "arreter", "arretes", "arretez")
            add(Verb.SET, "mets", "met", "mettre", "mettez", "regle", "regler", "reglez", "passe", "passer")
        },
        devices = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("cover", "volet", "volets", "store", "stores", "rideau", "rideaux")
            add("light", "lumiere", "lumieres", "lampe", "lampes", "ampoule", "ampoules", "plafonnier", "plafonniers", "eclairage", "luminaire", "guirlande", "guirlandes", "led", "leds")
            add("heater", "chauffage", "chauffages", "radiateur", "radiateurs")
            add("plug", "prise", "prises")
            add("siren", "sirene", "sirenes")
            add("fan", "ventilateur", "ventilateurs")
            add("tv", "tele", "television", "teles")
        },
        modifiers = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("ceiling", "plafond", "plafonnier", "plafonniers")
            add("tv", "tele", "television", "teles")
            add("north", "nord")
            add("south", "sud")
            add("east", "est")
            add("west", "ouest")
            add("christmas", "noel", "guirlande", "guirlandes")
            add("pool", "piscine")
            add("door", "porte", "portes")
        },
        rooms = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("living", "salon", "sejour")
            add("kitchen", "cuisine")
            add("office", "bureau")
            add("bathroom", "bain", "bains")
            add("bedroom", "chambre")
            add("garage", "garage")
            add("outdoor", "dehors", "exterieur", "jardin", "terrasse")
            add("stairs", "escalier")
            add("upstairs", "etage")
            add("entrance", "entree")
            add("hallway", "couloir")
            add("basement", "cave")
            add("laundry", "buanderie")
        },
        noise = setOf(
            "le", "la", "les", "un", "une", "du", "de", "des", "au", "aux", "dans", "sur", "en", "et", "ou",
            "il", "te", "tu", "je", "ce", "ca", "cela", "qu", "ne", "pas", "si", "chez", "mode",
            "peux", "peut", "pouvez", "veux", "voudrais", "veuillez", "svp", "stp", "plait", "merci",
            "bonjour", "salut", "moi", "nous", "maintenant", "bien", "donc", "ici", "pour", "cent", "pourcent",
            "pct", "maison", "moitie", "toute", "tout", "tous", "toutes", "ok",
        ),
        allWords = setOf("tout", "tous", "toute", "toutes"),
        alarmHomeWords = setOf("nuit", "partiel", "partielle", "presence", "maison"),
        shortcutStopWords = setOf(
            "le", "la", "les", "l", "du", "de", "des", "d", "un", "une", "au", "aux", "a", "dans", "sur", "en",
            "et", "est", "il", "elle", "on", "je", "j", "me", "m", "s", "y", "ai", "stp", "svp",
        ),
        phrases = listOf(
            Regex("""\bsalle de bains?\b""") to "bathroom",
            Regex("""\bchauffe eau\b""") to "waterheater",
        ),
    )

    private val EN = Table(
        verbs = buildMap {
            fun add(v: Verb, vararg ws: String) = ws.forEach { put(it, v) }
            add(Verb.ON, "turn", "switch", "activate", "start")
            add(Verb.OFF, "cut", "deactivate", "shut")
            add(Verb.ARM, "arm")
            add(Verb.DISARM, "disarm")
            add(Verb.OPEN, "open", "raise", "lift")
            add(Verb.CLOSE, "close", "shut", "lower", "drop")
            add(Verb.STOP, "stop", "halt")
            add(Verb.STOP_OR_OFF, "end", "finish")
            add(Verb.SET, "set", "put")
        },
        devices = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("cover", "shutter", "shutters", "blind", "blinds", "curtain", "curtains")
            add("light", "light", "lights", "led", "leds", "lamp", "lamps", "bulb", "bulbs", "chandelier")
            add("heater", "heater", "heaters", "heating", "radiator", "radiators")
            add("plug", "plug", "plugs")
            add("siren", "siren", "sirens")
            add("fan", "fan", "fans")
            add("tv", "tv")
        },
        modifiers = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("ceiling", "ceiling")
            add("tv", "tv")
            add("north", "north")
            add("south", "south")
            add("east", "east")
            add("west", "west")
            add("christmas", "christmas")
            add("pool", "pool")
            add("door", "door", "doors")
        },
        rooms = buildMap {
            fun add(c: String, vararg ws: String) = ws.forEach { put(it, c) }
            add("living", "living", "lounge")
            add("kitchen", "kitchen")
            add("office", "office", "desk")
            add("bathroom", "bathroom", "bath")
            add("bedroom", "bedroom")
            add("garage", "garage")
            add("outdoor", "outdoor", "outside", "garden")
            add("stairs", "stair", "stairs")
            add("upstairs", "upstairs")
            add("entrance", "entrance")
            add("hallway", "hallway", "corridor")
            add("basement", "basement", "cellar")
            add("laundry", "laundry")
        },
        noise = setOf(
            "the", "a", "an", "to", "in", "on", "at", "please", "now", "ok", "and", "or",
            "i", "you", "we", "my", "our", "me", "us", "percent", "pct", "half", "all",
        ),
        allWords = setOf("all"),
        alarmHomeWords = setOf("night", "partial", "home", "stay", "presence"),
        shortcutStopWords = setOf(
            "the", "a", "an", "to", "in", "on", "at", "and", "or", "of", "for", "with",
            "i", "you", "we", "me", "my", "our", "please",
        ),
        phrases = listOf(
            Regex("""\bliving room\b""") to "living",
            Regex("""\bwater heater\b""") to "waterheater",
        ),
    )

    fun of(lang: VoiceLang): Table = when (lang) {
        VoiceLang.FR -> FR
        VoiceLang.EN -> EN
    }

    fun actionLabel(lang: VoiceLang, a: VoiceAction): String = when (lang) {
        VoiceLang.FR -> when (a) {
            VoiceAction.TURN_ON -> "Allumer"
            VoiceAction.TURN_OFF -> "Éteindre"
            VoiceAction.OPEN -> "Ouvrir"
            VoiceAction.CLOSE -> "Fermer"
            VoiceAction.STOP -> "Stop"
            VoiceAction.SET_POSITION -> "Position"
            VoiceAction.SET_BRIGHTNESS -> "Luminosité"
            VoiceAction.ARM_AWAY -> "Armer (absent)"
            VoiceAction.ARM_HOME -> "Armer (présent)"
            VoiceAction.DISARM -> "Désarmer"
        }
        VoiceLang.EN -> when (a) {
            VoiceAction.TURN_ON -> "Turn on"
            VoiceAction.TURN_OFF -> "Turn off"
            VoiceAction.OPEN -> "Open"
            VoiceAction.CLOSE -> "Close"
            VoiceAction.STOP -> "Stop"
            VoiceAction.SET_POSITION -> "Position"
            VoiceAction.SET_BRIGHTNESS -> "Brightness"
            VoiceAction.ARM_AWAY -> "Arm (away)"
            VoiceAction.ARM_HOME -> "Arm (home)"
            VoiceAction.DISARM -> "Disarm"
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS (10 tests).

- [ ] **Step 7: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add settings.gradle.kts core/voice
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(voice): add :core:voice module with model types and lexicons

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

Then verify the identity: `git log -1 --format='%ae %ce'` must print `60887050+Gekkotron@users.noreply.github.com 60887050+Gekkotron@users.noreply.github.com`.

---

### Task 2: `VoiceCatalog` built from live entities and areas

**Files:**
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceCatalog.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceCatalogTest.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/Fixtures.kt`

**Interfaces:**
- Consumes: `Entity`, `Area`, `Kind` from `:core:model`; `VoiceLang` from Task 1.
- Produces:
  - `data class VoiceEntity(val uniqueId: String, val displayName: String, val kind: Kind, val areaId: String, val deviceClass: String?, val brightness: Boolean, val position: Boolean, val codeFormat: CodeFormat, val alarmModes: Set<AlarmMode>, val codeArmRequired: Boolean, val codeDisarmRequired: Boolean)`
  - `data class VoiceArea(val id: String, val displayName: String, val isPersonRoom: Boolean, val roomConcept: String?)`
  - `class VoiceCatalog(val lang: VoiceLang, val entities: List<VoiceEntity>, val areas: List<VoiceArea>) { fun entity(uniqueId: String): VoiceEntity? ; fun area(id: String): VoiceArea? }`
  - `object VoiceCatalogBuilder { fun build(lang: VoiceLang, entities: Collection<Entity>, areas: Collection<Area>): VoiceCatalog }`

- [ ] **Step 1: Write the failing tests**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/Fixtures.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride

private object NoBindings : Bindings

fun entity(
    id: String,
    name: String,
    kind: Kind,
    area: String = "unassigned",
    deviceClass: String? = null,
    brightness: Boolean = false,
    position: Boolean = false,
    hidden: Boolean = false,
    partOf: String? = null,
    override: UiOverride = UiOverride(),
): Entity = Entity(
    uniqueId = id,
    deviceId = null,
    name = name,
    kind = kind,
    deviceClass = deviceClass,
    unit = null,
    icon = null,
    capabilities = Capabilities(brightness = brightness, position = position),
    bindings = NoBindings,
    area = area,
    ui = override.copy(hidden = hidden),
    partOf = partOf,
)

fun area(id: String, name: String): Area = Area(id = id, name = name)
```

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceCatalogTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceCatalogTest {
    private val areas = listOf(area("salon", "Salon"), area("remi", "Rémi"), area("kitchen", "Cuisine"))

    @Test fun `only supported kinds are kept`() {
        val entities = listOf(
            entity("l1", "Lampe", Kind.LIGHT, "salon"),
            entity("s1", "Prise", Kind.SWITCH, "salon"),
            entity("c1", "Volet", Kind.COVER, "salon"),
            entity("a1", "Alarme", Kind.ALARM_PANEL, "salon"),
            entity("t1", "Température", Kind.SENSOR, "salon"),
            entity("b1", "Mouvement", Kind.BINARY_SENSOR, "salon"),
            entity("n1", "Luminosité", Kind.NUMBER, "salon"),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals(setOf("l1", "s1", "c1", "a1"), catalog.entities.map { it.uniqueId }.toSet())
    }

    @Test fun `hidden entities are dropped`() {
        val entities = listOf(entity("l1", "Lampe", Kind.LIGHT, "salon", hidden = true))
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertTrue(catalog.entities.isEmpty())
    }

    @Test fun `entities with partOf are dropped`() {
        val entities = listOf(
            entity("cover", "Volet", Kind.COVER, "salon"),
            entity("moving", "Moving", Kind.BINARY_SENSOR, "salon", partOf = "cover"),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals(listOf("cover"), catalog.entities.map { it.uniqueId })
    }

    @Test fun `an override name wins over the entity name`() {
        val entities = listOf(
            entity("l1", "0x001", Kind.LIGHT, "salon", override = com.gekkotron.oikos.model.UiOverride(name = "Lampe salon")),
        )
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas)
        assertEquals("Lampe salon", catalog.entity("l1")!!.displayName)
    }

    @Test fun `an area whose name matches a room word is not a person room (FR)`() {
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, emptyList(), areas)
        val salon = catalog.area("salon")!!
        assertFalse(salon.isPersonRoom)
        assertEquals("living", salon.roomConcept)
    }

    @Test fun `an area whose name is a person is marked as a person room`() {
        val catalog = VoiceCatalogBuilder.build(VoiceLang.FR, emptyList(), areas)
        val remi = catalog.area("remi")!!
        assertTrue(remi.isPersonRoom)
        assertNull(remi.roomConcept)
    }

    @Test fun `EN lexicon recognizes English room words`() {
        val englishAreas = listOf(area("living", "Living room"), area("kitchen", "Kitchen"))
        val catalog = VoiceCatalogBuilder.build(VoiceLang.EN, emptyList(), englishAreas)
        assertEquals("living", catalog.area("living")!!.roomConcept)
        assertEquals("kitchen", catalog.area("kitchen")!!.roomConcept)
    }

    @Test fun `the catalog preserves capability flags`() {
        val entities = listOf(entity("l1", "Lampe", Kind.LIGHT, "salon", brightness = true))
        val l1 = VoiceCatalogBuilder.build(VoiceLang.FR, entities, areas).entity("l1")!!
        assertTrue(l1.brightness)
        assertFalse(l1.position)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:voice:test --tests 'com.gekkotron.oikos.voice.VoiceCatalogTest'`
Expected: FAIL — class `VoiceCatalog` not defined.

- [ ] **Step 3: Implement `VoiceCatalog`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceCatalog.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import java.text.Normalizer

data class VoiceEntity(
    val uniqueId: String,
    val displayName: String,
    val kind: Kind,
    val areaId: String,
    val deviceClass: String?,
    val brightness: Boolean,
    val position: Boolean,
    val codeFormat: CodeFormat,
    val alarmModes: Set<AlarmMode>,
    val codeArmRequired: Boolean,
    val codeDisarmRequired: Boolean,
)

data class VoiceArea(
    val id: String,
    val displayName: String,
    val isPersonRoom: Boolean,
    val roomConcept: String?,
)

class VoiceCatalog(
    val lang: VoiceLang,
    val entities: List<VoiceEntity>,
    val areas: List<VoiceArea>,
) {
    private val byId: Map<String, VoiceEntity> = entities.associateBy { it.uniqueId }
    private val areaById: Map<String, VoiceArea> = areas.associateBy { it.id }

    fun entity(uniqueId: String): VoiceEntity? = byId[uniqueId]
    fun area(id: String): VoiceArea? = areaById[id]
}

object VoiceCatalogBuilder {
    private val SUPPORTED = setOf(Kind.LIGHT, Kind.SWITCH, Kind.COVER, Kind.ALARM_PANEL)
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val LETTERS = Regex("[a-z]+")

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

    private fun tokens(s: String): List<String> =
        LETTERS.findAll(fold(s)).map { it.value }.filter { it.length >= 2 }.toList()

    fun build(lang: VoiceLang, entities: Collection<Entity>, areas: Collection<Area>): VoiceCatalog {
        val table = VoiceLexicon.of(lang)
        val voiceAreas = areas.map { a ->
            val concept = (tokens(a.id) + tokens(a.name)).firstNotNullOfOrNull { table.rooms[it] }
            VoiceArea(id = a.id, displayName = a.name, isPersonRoom = concept == null, roomConcept = concept)
        }
        val voiceEntities = entities.asSequence()
            .filter { it.kind in SUPPORTED }
            .filter { it.partOf == null }
            .filter { !it.ui.hidden }
            .map { e ->
                VoiceEntity(
                    uniqueId = e.uniqueId,
                    displayName = e.name,
                    kind = e.kind,
                    areaId = e.area,
                    deviceClass = e.deviceClass,
                    brightness = e.capabilities.brightness,
                    position = e.capabilities.position,
                    codeFormat = e.capabilities.codeFormat,
                    alarmModes = e.capabilities.alarmModes,
                    codeArmRequired = e.capabilities.codeArmRequired,
                    codeDisarmRequired = e.capabilities.codeDisarmRequired,
                )
            }
            .toList()
        return VoiceCatalog(lang, voiceEntities, voiceAreas)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:voice:test --tests 'com.gekkotron.oikos.voice.VoiceCatalogTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add core/voice
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(voice): build VoiceCatalog from live entities and areas

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 3: `RuleMatcher`, `Suggester`, `CommandMapper`, `needsConfirmation`

**Files:**
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/RuleMatcher.kt`
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/Suggester.kt`
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/CommandMapper.kt`
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/Confirmation.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/RuleMatcherFrTest.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/RuleMatcherEnTest.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/SuggesterTest.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/CommandMapperTest.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/ConfirmationTest.kt`

**Interfaces:**
- Consumes: everything from Task 1 and Task 2.
- Produces:
  - `class RuleMatcher(private val catalog: VoiceCatalog) { fun resolve(text: String): VoiceIntent? ; fun shortlist(text: String): List<VoiceEntity> ; fun detectAction(text: String, kind: Kind?): VoiceAction? ; fun mentionsArea(text: String): Boolean ; fun relevance(text: String, e: VoiceEntity): Int ; fun isLightClass(e: VoiceEntity): Boolean }`
  - `class Suggester(private val catalog: VoiceCatalog, private val rules: RuleMatcher) { fun suggest(text: String, max: Int = 6): List<Suggestion> }`
  - `object CommandMapper { fun map(intent: VoiceIntent, catalog: VoiceCatalog, code: String? = null): List<Pair<String, Command>> }` — returns empty when the intent has no compatible target.
  - `object Confirmation { fun needsConfirmation(intent: VoiceIntent, catalog: VoiceCatalog): Boolean }`
  - `object VoiceText { fun prepare(text: String, lang: VoiceLang): String ; fun words(prepared: String): List<String> ; fun normalizeShortcut(text: String, lang: VoiceLang): String }` (shared helpers used by both the rule matcher and the shortcut matcher)

- [ ] **Step 1: Write the failing shared-text test**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceText.kt`:

```kotlin
package com.gekkotron.oikos.voice

import java.text.Normalizer

object VoiceText {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_TEXT = Regex("[^a-z0-9%\\s]")
    private val LETTERS = Regex("[a-z]+")
    private val POLITE_FR = Regex("""\bs il (?:te|vous) plait\b""")
    private val POLITE_EN = Regex("""\bplease\b""")

    fun prepare(text: String, lang: VoiceLang): String {
        var s = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")
        s = s.replace(NON_TEXT, " ")
        for ((re, to) in VoiceLexicon.of(lang).phrases) s = re.replace(s, to)
        return s
    }

    fun words(prepared: String): List<String> =
        LETTERS.findAll(prepared).map { it.value }.filter { it.length >= 2 }.toList()

    /** Normalized shortcut key: no accents, no punctuation, no filler words. */
    fun normalizeShortcut(text: String, lang: VoiceLang): String {
        val table = VoiceLexicon.of(lang)
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val polite = (if (lang == VoiceLang.FR) POLITE_FR else POLITE_EN).replace(folded, " ")
        return polite.split(' ').filter { it.isNotEmpty() && it !in table.shortcutStopWords }.joinToString(" ")
    }
}
```

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceTextTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTextTest {
    @Test fun `prepare folds accents and lowercases`() {
        assertEquals("ouvre les volets du salon", VoiceText.prepare("Ouvre les volets du Salon.", VoiceLang.FR))
    }

    @Test fun `prepare rewrites multi-word FR phrases`() {
        assertEquals("allume la bathroom", VoiceText.prepare("allume la salle de bain", VoiceLang.FR))
    }

    @Test fun `prepare rewrites multi-word EN phrases`() {
        assertEquals("open the living shutter", VoiceText.prepare("open the living room shutter", VoiceLang.EN))
    }

    @Test fun `normalizeShortcut drops FR filler words`() {
        assertEquals("ouvre volets salon", VoiceText.normalizeShortcut("Ouvre les volets du salon", VoiceLang.FR))
    }

    @Test fun `normalizeShortcut drops EN filler words`() {
        assertEquals("open shutters living", VoiceText.normalizeShortcut("Open the shutters in the living room", VoiceLang.EN))
    }
}
```

- [ ] **Step 2: Write the failing rule-matcher tests (FR)**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/RuleMatcherFrTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleMatcherFrTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon_1", "Volet salon 1", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("volet_cuisine", "Volet cuisine", Kind.COVER, "kitchen", position = true),
            entity("lampe_salon", "Lampe salon", Kind.LIGHT, "salon", brightness = true),
            entity("lampe_bureau", "Lampe bureau", Kind.LIGHT, "office", brightness = true),
            entity("ampoule_salon_sw", "Ampoule salon", Kind.SWITCH, "salon"),
            entity("prise_cafe", "Prise café", Kind.SWITCH, "kitchen"),
            entity("alarme", "Alarme", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("salon", "Salon"), area("kitchen", "Cuisine"), area("office", "Bureau"), area("entrance", "Entrée")),
    )
    private val matcher = RuleMatcher(catalog)

    @Test fun `opens every shutter of a room when a room is said`() {
        val intent = matcher.resolve("ouvre les volets du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.OPEN, intent.action)
        assertEquals(setOf("volet_salon_1", "volet_salon_2"), intent.entityIds.toSet())
    }

    @Test fun `singular light plus room means the whole light group of the room`() {
        val intent = matcher.resolve("allume la lumière du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.TURN_ON, intent.action)
        // Includes the SWITCH named like a light (ampoule_salon_sw) alongside the LIGHT.
        assertEquals(setOf("lampe_salon", "ampoule_salon_sw"), intent.entityIds.toSet())
    }

    @Test fun `naming the device still picks only it`() {
        val intent = matcher.resolve("éteins la lampe du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.TURN_OFF, intent.action)
        assertEquals(listOf("lampe_salon"), intent.entityIds)
    }

    @Test fun `set position carries the value and only hits positionable members`() {
        val intent = matcher.resolve("mets les volets du salon à 30 %")
        assertNotNull(intent)
        assertEquals(VoiceAction.SET_POSITION, intent.action)
        assertEquals(30, intent.value)
        assertEquals(setOf("volet_salon_1", "volet_salon_2"), intent.entityIds.toSet())
    }

    @Test fun `set brightness carries the value as 0-100`() {
        val intent = matcher.resolve("mets la lampe du salon à 50 %")
        assertNotNull(intent)
        assertEquals(VoiceAction.SET_BRIGHTNESS, intent.action)
        assertEquals(50, intent.value)
    }

    @Test fun `arm away by default`() {
        val intent = matcher.resolve("arme l'alarme")
        assertNotNull(intent)
        assertEquals(VoiceAction.ARM_AWAY, intent.action)
        assertEquals(listOf("alarme"), intent.entityIds)
    }

    @Test fun `arm home when a home-mode word is said`() {
        val intent = matcher.resolve("arme l'alarme en mode nuit")
        assertEquals(VoiceAction.ARM_HOME, intent?.action)
    }

    @Test fun `an ambiguous phrase returns null`() {
        assertNull(matcher.resolve("fais quelque chose"))
    }

    @Test fun `a bad value like 150 percent returns null`() {
        assertNull(matcher.resolve("mets les volets du salon à 150 %"))
    }

    @Test fun `naming a non-existent room returns null`() {
        assertNull(matcher.resolve("ouvre les volets de la cave"))
    }

    @Test fun `tolerates a one-letter Levenshtein slip on long words`() {
        // "volats" → "volets" (1 edit, both >=5 chars)
        val intent = matcher.resolve("ouvre les volats du salon")
        assertNotNull(intent)
        assertEquals(VoiceAction.OPEN, intent.action)
    }
}
```

- [ ] **Step 3: Write the failing rule-matcher tests (EN)**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/RuleMatcherEnTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuleMatcherEnTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.EN,
        entities = listOf(
            entity("living_shutter_1", "Living shutter 1", Kind.COVER, "living", position = true),
            entity("living_shutter_2", "Living shutter 2", Kind.COVER, "living", position = true),
            entity("living_light", "Living light", Kind.LIGHT, "living", brightness = true),
            entity("alarm", "Alarm", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("living", "Living room"), area("entrance", "Entrance")),
    )
    private val matcher = RuleMatcher(catalog)

    @Test fun `open the shutters in the living room`() {
        val intent = matcher.resolve("open the shutters in the living room")
        assertNotNull(intent)
        assertEquals(VoiceAction.OPEN, intent.action)
        assertEquals(setOf("living_shutter_1", "living_shutter_2"), intent.entityIds.toSet())
    }

    @Test fun `set shutters to 30 percent`() {
        val intent = matcher.resolve("set the living shutters to 30 percent")
        assertNotNull(intent)
        assertEquals(VoiceAction.SET_POSITION, intent.action)
        assertEquals(30, intent.value)
    }

    @Test fun `disarm the alarm`() {
        val intent = matcher.resolve("disarm the alarm")
        assertNotNull(intent)
        assertEquals(VoiceAction.DISARM, intent.action)
    }
}
```

- [ ] **Step 4: Write the failing suggester tests**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/SuggesterTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggesterTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon", "Volet salon", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("lampe_salon", "Lampe salon", Kind.LIGHT, "salon", brightness = true),
        ),
        areas = listOf(area("salon", "Salon")),
    )
    private val rules = RuleMatcher(catalog)
    private val suggester = Suggester(catalog, rules)

    @Test fun `at most max suggestions`() {
        assertTrue(suggester.suggest("quelque chose", max = 3).size <= 3)
    }

    @Test fun `a room-only phrase lists the room groups first`() {
        val labels = suggester.suggest("salon").map { it.label }
        assertTrue(labels.first().contains("Volet") || labels.first().contains("Lumière"), "first: ${labels.first()}")
    }

    @Test fun `a detected verb comes first in the ranking`() {
        val suggestions = suggester.suggest("ferme salon")
        assertEquals(VoiceAction.CLOSE, suggestions.first().intent.action)
    }

    @Test fun `no duplicate action-entities pair is produced`() {
        val list = suggester.suggest("salon", max = 20)
        val keys = list.map { it.intent.action to it.intent.entityIds.toSet() }
        assertEquals(keys.size, keys.toSet().size)
    }
}
```

- [ ] **Step 5: Write the failing command-mapper tests**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/CommandMapperTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandMapperTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("light", "Lampe", Kind.LIGHT, "salon", brightness = true),
            entity("switch", "Prise", Kind.SWITCH, "salon"),
            entity("cover_pos", "Volet 1", Kind.COVER, "salon", position = true),
            entity("cover_nopos", "Volet 2", Kind.COVER, "salon", position = false),
            entity("alarm", "Alarme", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("salon", "Salon"), area("entrance", "Entrée")),
    )

    @Test fun `TURN_ON maps light and switch to TurnOn`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.TURN_ON, listOf("light", "switch")), catalog)
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.second is Command.TurnOn })
    }

    @Test fun `SET_BRIGHTNESS scales 0..100 into 0..255 rounded`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.SET_BRIGHTNESS, listOf("light"), value = 50), catalog)
        assertEquals(listOf("light" to Command.TurnOn(brightness = 128)), commands)
    }

    @Test fun `SET_POSITION only hits covers with position`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.SET_POSITION, listOf("cover_pos", "cover_nopos"), value = 30), catalog)
        assertEquals(listOf("cover_pos" to Command.SetPosition(30)), commands)
    }

    @Test fun `incompatible kind-command pairs are dropped and an intent with none left is rejected`() {
        // CLOSE on a LIGHT would be bogus — the LIGHT is dropped.
        val mixed = CommandMapper.map(VoiceIntent(VoiceAction.CLOSE, listOf("light", "cover_pos")), catalog)
        assertEquals(listOf("cover_pos" to Command.Close), mixed)

        // CLOSE on only a LIGHT yields nothing.
        val empty = CommandMapper.map(VoiceIntent(VoiceAction.CLOSE, listOf("light")), catalog)
        assertTrue(empty.isEmpty())
    }

    @Test fun `ARM_AWAY produces Arm AWAY on the panel with the code`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.ARM_AWAY, listOf("alarm")), catalog, code = "1234")
        assertEquals(listOf("alarm" to Command.Arm(AlarmMode.AWAY, code = "1234")), commands)
    }

    @Test fun `DISARM produces Disarm with the code`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.DISARM, listOf("alarm")), catalog, code = "9999")
        assertEquals(listOf("alarm" to Command.Disarm(code = "9999")), commands)
    }

    @Test fun `an entity id missing from the catalog is skipped`() {
        val commands = CommandMapper.map(VoiceIntent(VoiceAction.OPEN, listOf("cover_pos", "ghost")), catalog)
        assertEquals(listOf("cover_pos" to Command.Open), commands)
    }
}
```

- [ ] **Step 6: Write the failing confirmation tests**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/ConfirmationTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmationTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("alarm", "Alarme", Kind.ALARM_PANEL, "entrance"),
            entity("plug", "Prise", Kind.SWITCH, "salon"),
            entity("siren_sw", "Sirène extérieure", Kind.SWITCH, "outdoor"),
            entity("siren_dc", "Buzzer", Kind.SWITCH, "outdoor", deviceClass = "siren"),
        ),
        areas = listOf(area("entrance", "Entrée"), area("salon", "Salon"), area("outdoor", "Dehors")),
    )

    @Test fun `any alarm action always confirms`() {
        for (action in listOf(VoiceAction.ARM_AWAY, VoiceAction.ARM_HOME, VoiceAction.DISARM)) {
            assertTrue(Confirmation.needsConfirmation(VoiceIntent(action, listOf("alarm")), catalog))
        }
    }

    @Test fun `needsConfirmation is true for a switch whose name is siren`() {
        assertTrue(Confirmation.needsConfirmation(VoiceIntent(VoiceAction.TURN_ON, listOf("siren_sw")), catalog))
    }

    @Test fun `needsConfirmation is true for a switch whose device_class is siren`() {
        assertTrue(Confirmation.needsConfirmation(VoiceIntent(VoiceAction.TURN_ON, listOf("siren_dc")), catalog))
    }

    @Test fun `a plain plug never confirms`() {
        assertFalse(Confirmation.needsConfirmation(VoiceIntent(VoiceAction.TURN_ON, listOf("plug")), catalog))
    }
}
```

- [ ] **Step 7: Run every new test to verify they fail**

Run: `./gradlew :core:voice:test`
Expected: FAIL — classes `RuleMatcher`, `Suggester`, `CommandMapper`, `Confirmation` not defined.

- [ ] **Step 8: Implement `RuleMatcher`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/RuleMatcher.kt`. The implementation ports the prototype at `~/Documents/SourcesPerso/OikosVoicePrototype/main/intent/RuleMatcher.kt` with these differences from the prototype:
- Reads lexicon from `VoiceLexicon.of(catalog.lang)` instead of top-level `VERBS/DEVICE/ROOM/MODIFIER/NOISE/ALL_WORDS/ALARM_HOME_WORDS`.
- Uses `VoiceCatalog`, `VoiceEntity`, `VoiceArea` instead of the prototype's `Catalog`, `CatalogEntity`, `Area`.
- Kind comparison uses `Kind` enum instead of strings (`"light"` → `Kind.LIGHT`, `"switch"` → `Kind.SWITCH`, `"cover"` → `Kind.COVER`, `"alarm_control_panel"` → `Kind.ALARM_PANEL`).
- `isLightClass(e)` returns true for `Kind.LIGHT`, or `Kind.SWITCH` whose name tokens include a word mapping to `"light"` in the lexicon.
- `shortlist(text)` returns command entities of the catalog narrowed by room / device / alarm.
- When several command entities match a singular device word plus a room: the whole room group is returned (spec §2.1 one-rule change).
- No `elapsedMs`, no `Resolution`, no `Source`. The matcher returns a `VoiceIntent?`.
- `Levenshtein ≤ 1` tolerance only on tokens of 5+ characters (same as the prototype).
- Return `null` whenever ambiguous (two actions, no group, no plural), never a guess.

The file ends at ~380 LOC and matches the prototype's structure.

- [ ] **Step 9: Implement `Suggester`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/Suggester.kt`. Port `~/Documents/SourcesPerso/OikosVoicePrototype/main/intent/Suggester.kt` with these differences:
- Takes `VoiceCatalog` + `RuleMatcher`.
- `actionLabelFr` is replaced by `VoiceLexicon.actionLabel(catalog.lang, action)`.
- Noun words (`"Volets"`, `"Lumières"`, `"Appareils"`, `"Alarmes"` in FR; `"Shutters"`, `"Lights"`, `"Devices"`, `"Alarms"` in EN) come from a `fun groupNoun(lang: VoiceLang, cls: Cls): String` helper inside the file.
- Default actions per class: LIGHT/POWER → `[TURN_ON, TURN_OFF]`; COVER → `[OPEN, CLOSE]`; ALARM → `[ARM_AWAY, DISARM]`.
- Ranks: detected verb first, groups before singles when a room was said, higher relevance, class priority (LIGHT=0, POWER=1, COVER=2, ALARM=3), group size, label.

- [ ] **Step 10: Implement `CommandMapper`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/CommandMapper.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlin.math.roundToInt

object CommandMapper {
    fun map(intent: VoiceIntent, catalog: VoiceCatalog, code: String? = null): List<Pair<String, Command>> =
        intent.entityIds.mapNotNull { id -> catalog.entity(id)?.let { e -> commandFor(intent, e, code)?.let { id to it } } }

    private fun commandFor(intent: VoiceIntent, e: VoiceEntity, code: String?): Command? = when (intent.action) {
        VoiceAction.TURN_ON -> if (e.kind == Kind.LIGHT || e.kind == Kind.SWITCH) Command.TurnOn() else null
        VoiceAction.TURN_OFF -> if (e.kind == Kind.LIGHT || e.kind == Kind.SWITCH) Command.TurnOff else null
        VoiceAction.OPEN -> if (e.kind == Kind.COVER) Command.Open else null
        VoiceAction.CLOSE -> if (e.kind == Kind.COVER) Command.Close else null
        VoiceAction.STOP -> if (e.kind == Kind.COVER) Command.Stop else null
        VoiceAction.SET_POSITION -> if (e.kind == Kind.COVER && e.position && intent.value != null) Command.SetPosition(intent.value) else null
        VoiceAction.SET_BRIGHTNESS -> if (e.kind == Kind.LIGHT && e.brightness && intent.value != null) Command.TurnOn(brightness = (intent.value * 2.55).roundToInt().coerceIn(0, 255)) else null
        VoiceAction.ARM_AWAY -> if (e.kind == Kind.ALARM_PANEL) Command.Arm(AlarmMode.AWAY, code) else null
        VoiceAction.ARM_HOME -> if (e.kind == Kind.ALARM_PANEL) Command.Arm(AlarmMode.HOME, code) else null
        VoiceAction.DISARM -> if (e.kind == Kind.ALARM_PANEL) Command.Disarm(code) else null
    }
}
```

- [ ] **Step 11: Implement `Confirmation`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/Confirmation.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind

object Confirmation {
    private val ALARM_ACTIONS = setOf(VoiceAction.ARM_AWAY, VoiceAction.ARM_HOME, VoiceAction.DISARM)

    fun needsConfirmation(intent: VoiceIntent, catalog: VoiceCatalog): Boolean {
        if (intent.action in ALARM_ACTIONS) return true
        return intent.entityIds.any { id ->
            val e = catalog.entity(id) ?: return@any false
            e.kind == Kind.ALARM_PANEL ||
                e.deviceClass.equals("siren", ignoreCase = true) ||
                e.displayName.contains("siren", ignoreCase = true) ||
                e.displayName.contains("sirène", ignoreCase = true)
        }
    }
}
```

- [ ] **Step 12: Run every new test to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS. All tests from Tasks 1, 2, 3 are green.

- [ ] **Step 13: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add core/voice
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(voice): rules, suggestions, command mapping and confirmation

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 4: `ShortcutMatcher`, `VoiceEngine`, shortcut model and id hash

**Files:**
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceShortcut.kt`
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/ShortcutMatcher.kt`
- Create: `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceEngine.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/ShortcutMatcherTest.kt`
- Create: `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceEngineTest.kt`

**Interfaces:**
- Consumes: `VoiceCatalog`, `VoiceIntent`, `VoiceAction`, `VoiceLang` from Tasks 1–2; `VoiceText` from Task 3.
- Produces:
  - `data class VoiceShortcut(val id: String, val phrase: String, val lang: VoiceLang, val intent: VoiceIntent)` with a companion `fun id(lang: VoiceLang, normalizedPhrase: String): String` returning the first 16 bytes of SHA-256(`lang.name.lowercase() + ":" + normalizedPhrase`) as 32 lowercase hex characters.
  - `data class ShortcutStatus(val shortcut: VoiceShortcut, val broken: Boolean)` — `broken` means every target entity id is missing from the catalog or the shortcut's command mapping is empty.
  - `class ShortcutMatcher(private val catalog: VoiceCatalog, private val shortcuts: List<VoiceShortcut>) { fun resolve(text: String): VoiceIntent? ; fun status(): List<ShortcutStatus> }`
  - `class VoiceEngine(private val catalog: VoiceCatalog, private val shortcuts: List<VoiceShortcut>) { fun resolve(text: String): VoiceResolution }`

- [ ] **Step 1: Write the failing tests**

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/ShortcutMatcherTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutMatcherTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(entity("lampe", "Lampe salon", Kind.LIGHT, "salon")),
        areas = listOf(area("salon", "Salon")),
    )

    @Test fun `id is deterministic for the same lang and normalized phrase`() {
        val a = VoiceShortcut.id(VoiceLang.FR, "ouvre volets salon")
        val b = VoiceShortcut.id(VoiceLang.FR, "ouvre volets salon")
        assertEquals(a, b)
    }

    @Test fun `id differs for the other language`() {
        val fr = VoiceShortcut.id(VoiceLang.FR, "open shutters living")
        val en = VoiceShortcut.id(VoiceLang.EN, "open shutters living")
        assertFalse(fr == en)
    }

    @Test fun `id is 32 hex chars`() {
        val id = VoiceShortcut.id(VoiceLang.FR, "x")
        assertTrue(id.matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun `a matching normalized phrase returns the shortcut's intent`() {
        val sc = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.FR, "il fait sombre salon"),
            phrase = "Il fait sombre dans le salon",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe")),
        )
        val matcher = ShortcutMatcher(catalog, listOf(sc))
        val intent = matcher.resolve("Il fait sombre dans le salon.")
        assertNotNull(intent)
        assertEquals(VoiceAction.TURN_ON, intent.action)
        assertEquals(listOf("lampe"), intent.entityIds)
    }

    @Test fun `shortcuts of the other language are ignored and preserved`() {
        val other = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.EN, "turn on lamp"),
            phrase = "Turn on the lamp",
            lang = VoiceLang.EN,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe")),
        )
        val matcher = ShortcutMatcher(catalog, listOf(other))
        assertNull(matcher.resolve("Turn on the lamp"))
        // but still listed with its lang chip
        val statuses = matcher.status()
        assertEquals(1, statuses.size)
        assertEquals(VoiceLang.EN, statuses.first().shortcut.lang)
        assertFalse(statuses.first().broken)
    }

    @Test fun `a shortcut with no surviving entity id is broken and never matches`() {
        val sc = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.FR, "la fete"),
            phrase = "La fête",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("ghost")),
        )
        val matcher = ShortcutMatcher(catalog, listOf(sc))
        assertNull(matcher.resolve("La fête"))
        assertTrue(matcher.status().first().broken)
    }
}
```

Create `core/voice/src/test/kotlin/com/gekkotron/oikos/voice/VoiceEngineTest.kt`:

```kotlin
package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceEngineTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon_1", "Volet salon 1", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("lampe_salon", "Lampe salon", Kind.LIGHT, "salon"),
        ),
        areas = listOf(area("salon", "Salon")),
    )

    @Test fun `shortcuts are tried first`() {
        val sc = VoiceShortcut(
            id = VoiceShortcut.id(VoiceLang.FR, "il fait sombre salon"),
            phrase = "Il fait sombre dans le salon",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe_salon")),
        )
        val engine = VoiceEngine(catalog, listOf(sc))
        val resolution = engine.resolve("Il fait sombre dans le salon")
        val understood = assertIs<VoiceResolution.Understood>(resolution)
        assertEquals(VoiceSource.SHORTCUT, understood.source)
        assertEquals(listOf("lampe_salon"), understood.intent.entityIds)
    }

    @Test fun `rules fire when no shortcut matches`() {
        val resolution = VoiceEngine(catalog, emptyList()).resolve("ouvre les volets du salon")
        val understood = assertIs<VoiceResolution.Understood>(resolution)
        assertEquals(VoiceSource.RULES, understood.source)
        assertEquals(VoiceAction.OPEN, understood.intent.action)
    }

    @Test fun `not understood returns ranked suggestions`() {
        val resolution = VoiceEngine(catalog, emptyList()).resolve("salon")
        val not = assertIs<VoiceResolution.NotUnderstood>(resolution)
        assertTrue(not.suggestions.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :core:voice:test --tests 'com.gekkotron.oikos.voice.ShortcutMatcherTest' --tests 'com.gekkotron.oikos.voice.VoiceEngineTest'`
Expected: FAIL — classes `VoiceShortcut`, `ShortcutMatcher`, `VoiceEngine` not defined.

- [ ] **Step 3: Implement `VoiceShortcut`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceShortcut.kt`:

```kotlin
package com.gekkotron.oikos.voice

import java.security.MessageDigest

data class VoiceShortcut(
    /** 32 lowercase hex characters — the first 16 bytes of SHA-256(lang + ":" + normalized phrase). */
    val id: String,
    /** As said by the user; for the shortcuts list display. */
    val phrase: String,
    val lang: VoiceLang,
    val intent: VoiceIntent,
) {
    companion object {
        fun id(lang: VoiceLang, normalizedPhrase: String): String {
            val input = "${lang.name.lowercase()}:$normalizedPhrase"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

data class ShortcutStatus(val shortcut: VoiceShortcut, val broken: Boolean)
```

- [ ] **Step 4: Implement `ShortcutMatcher`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/ShortcutMatcher.kt`:

```kotlin
package com.gekkotron.oikos.voice

class ShortcutMatcher(
    private val catalog: VoiceCatalog,
    private val shortcuts: List<VoiceShortcut>,
) {
    private val sameLang = shortcuts.filter { it.lang == catalog.lang }
    private val byKey: Map<String, VoiceShortcut> =
        sameLang.associateBy { VoiceText.normalizeShortcut(it.phrase, catalog.lang) }

    fun resolve(text: String): VoiceIntent? {
        val key = VoiceText.normalizeShortcut(text, catalog.lang)
        if (key.isEmpty()) return null
        val sc = byKey[key] ?: return null
        return if (isBroken(sc)) null else sc.intent
    }

    fun status(): List<ShortcutStatus> = shortcuts.map { ShortcutStatus(it, broken = isBroken(it)) }

    private fun isBroken(sc: VoiceShortcut): Boolean {
        val survivors = sc.intent.entityIds.filter { catalog.entity(it) != null }
        if (survivors.isEmpty()) return true
        return CommandMapper.map(sc.intent.copy(entityIds = survivors), catalog).isEmpty()
    }
}
```

- [ ] **Step 5: Implement `VoiceEngine`**

Create `core/voice/src/main/kotlin/com/gekkotron/oikos/voice/VoiceEngine.kt`:

```kotlin
package com.gekkotron.oikos.voice

/** Tries shortcuts, then rules; falls back to suggestions when neither understands. */
class VoiceEngine(
    private val catalog: VoiceCatalog,
    private val shortcuts: List<VoiceShortcut>,
) {
    private val shortcutMatcher = ShortcutMatcher(catalog, shortcuts)
    private val ruleMatcher = RuleMatcher(catalog)
    private val suggester = Suggester(catalog, ruleMatcher)

    fun resolve(text: String): VoiceResolution {
        shortcutMatcher.resolve(text)?.let { return VoiceResolution.Understood(it, VoiceSource.SHORTCUT) }
        ruleMatcher.resolve(text)?.let { return VoiceResolution.Understood(it, VoiceSource.RULES) }
        return VoiceResolution.NotUnderstood(suggester.suggest(text))
    }
}
```

- [ ] **Step 6: Run every `:core:voice` test to verify they pass**

Run: `./gradlew :core:voice:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add core/voice
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(voice): shortcut matcher and VoiceEngine pipeline

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 5: `voiceEnabled` in `AppPreferences`

**Files:**
- Modify: `core/data/src/main/kotlin/com/gekkotron/oikos/data/AppPreferences.kt`
- Modify: `core/data/src/test/kotlin/com/gekkotron/oikos/data/AppPreferencesTest.kt`

**Interfaces:**
- Produces:
  - `AppPreferences.voiceEnabled: StateFlow<Boolean?>` (null until the file has been read; false by default).
  - `suspend fun AppPreferences.setVoiceEnabled(enabled: Boolean)`

- [ ] **Step 1: Write the failing tests**

Append to `AppPreferencesTest`:

```kotlin
    @Test
    fun `voiceEnabled defaults to false`() = runTest {
        assertEquals(false, prefs().voiceEnabled.filterNotNull().first())
    }

    @Test
    fun `voiceEnabled round-trips`() = runTest {
        val p = prefs()
        p.setVoiceEnabled(true)
        assertEquals(true, p.voiceEnabled.first { it == true })
        p.setVoiceEnabled(false)
        assertEquals(false, p.voiceEnabled.first { it == false })
    }

    @Test
    fun `an older file without voiceEnabled stays false`() = runTest {
        val f = file().apply { writeText("""{"theme":"WARM_MINIMAL"}""") }
        val p = prefs(f)
        assertEquals(false, p.voiceEnabled.filterNotNull().first())
        assertEquals(ThemePreference.WARM_MINIMAL, p.theme.first { it == ThemePreference.WARM_MINIMAL })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.gekkotron.oikos.data.AppPreferencesTest'`
Expected: FAIL — `voiceEnabled` and `setVoiceEnabled` not defined.

- [ ] **Step 3: Implement**

Edit `core/data/src/main/kotlin/com/gekkotron/oikos/data/AppPreferences.kt`:

Change the `StoredPreferences` data class:
```kotlin
@Serializable
internal data class StoredPreferences(val theme: String = ThemePreference.SYSTEM.name, val voiceEnabled: Boolean = false)
```

Add inside `AppPreferences`:
```kotlin
    val voiceEnabled: StateFlow<Boolean?> = store.data
        .map { it.voiceEnabled }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setVoiceEnabled(enabled: Boolean) {
        store.updateData { it.copy(voiceEnabled = enabled) }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.gekkotron.oikos.data.AppPreferencesTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add core/data
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(data): voiceEnabled app preference (off by default)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 6: Shortcut storage on `EntitySource` (`<prefix>/voice/shortcut/<id>` subscribe + publish)

**Files:**
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/EntitySource.kt`
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt`
- Create: `core/source/src/main/kotlin/com/gekkotron/oikos/source/VoiceShortcutDocument.kt`
- Modify: `core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt`
- Create: `core/source/src/test/kotlin/com/gekkotron/oikos/source/VoiceShortcutDocumentTest.kt`

**Interfaces:**
- Consumes: `VoiceShortcut`, `VoiceAction` from `:core:voice`; `MqttDiscoverySource` existing publish pattern.
- Produces:
  - `data class VoiceShortcutDocument(val v: Int = 1, val phrase: String, val lang: String, val action: String, val entityIds: List<String>, val value: Int? = null)` serialized with `OikosJson`, ignoring unknown keys.
  - On `EntitySource`:
    - `val shortcuts: StateFlow<Map<String, VoiceShortcut>>` keyed by `VoiceShortcut.id`.
    - `suspend fun saveShortcut(shortcut: VoiceShortcut)` publishes retained on `<prefix>/voice/shortcut/<id>`.
    - `suspend fun deleteShortcut(id: String)` publishes an empty retained payload.
  - `MqttDiscoverySource` adds `<prefix>/voice/shortcut/#` to its subscriptions, parses incoming payloads into `VoiceShortcut`, feeds them into `shortcuts`, and includes those topics in its journal.
  - **Dependency:** `:core:source/build.gradle.kts` adds `implementation(project(":core:voice"))`.

- [ ] **Step 1: Add `:core:voice` to `:core:source`**

Edit `core/source/build.gradle.kts`, append in `dependencies { … }`:

```kotlin
    implementation(project(":core:voice"))
```

- [ ] **Step 2: Write the failing document test**

Create `core/source/src/test/kotlin/com/gekkotron/oikos/source/VoiceShortcutDocumentTest.kt`:

```kotlin
package com.gekkotron.oikos.source

import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceLang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceShortcutDocumentTest {
    @Test fun `encode round-trips`() {
        val doc = VoiceShortcutDocument(
            phrase = "il fait sombre dans le salon",
            lang = "fr",
            action = VoiceAction.TURN_ON.name,
            entityIds = listOf("lampe"),
            value = null,
        )
        val json = VoiceShortcutDocument.encode(doc)
        assertEquals(doc, VoiceShortcutDocument.decode(json))
    }

    @Test fun `decode ignores unknown keys`() {
        val json = """{"v":1,"phrase":"x","lang":"fr","action":"TURN_ON","entityIds":["a"],"value":null,"future":42}"""
        val doc = VoiceShortcutDocument.decode(json)!!
        assertEquals("x", doc.phrase)
    }

    @Test fun `decode returns null on invalid json`() {
        assertNull(VoiceShortcutDocument.decode("not json"))
    }

    @Test fun `toShortcut maps to a VoiceShortcut using the id hash`() {
        val doc = VoiceShortcutDocument(phrase = "Il fait sombre", lang = "fr", action = "TURN_ON", entityIds = listOf("x"))
        val sc = doc.toShortcut() // returns null when action unknown or lang unknown
        assertEquals(VoiceLang.FR, sc!!.lang)
        assertEquals(VoiceAction.TURN_ON, sc.intent.action)
    }

    @Test fun `toShortcut returns null on unknown lang`() {
        val doc = VoiceShortcutDocument(phrase = "x", lang = "zz", action = "TURN_ON", entityIds = listOf("x"))
        assertNull(doc.toShortcut())
    }

    @Test fun `toShortcut returns null on unknown action`() {
        val doc = VoiceShortcutDocument(phrase = "x", lang = "fr", action = "BOGUS", entityIds = listOf("x"))
        assertNull(doc.toShortcut())
    }
}
```

- [ ] **Step 3: Write the failing source tests**

Append to `MqttDiscoverySourceTest`:

```kotlin
    @Test
    fun `voice shortcut topic is subscribed with the ui prefix`() = h.run {
        source.start()
        assertEquals(setOf("homeassistant/#", "oikos/ui/#", "oikos/voice/shortcut/#"), transport.subscriptions)
    }

    @Test
    fun `an incoming shortcut appears in the shortcuts flow`() = runTest {
        source.start()
        transport.deliver("oikos/voice/shortcut/abc1234567890abc1234567890abc123",
            """{"v":1,"phrase":"il fait sombre","lang":"fr","action":"TURN_ON","entityIds":["lampe"]}""")
        val map = source.shortcuts.first { it.isNotEmpty() }
        assertEquals(1, map.size)
        assertEquals("il fait sombre", map.values.first().phrase)
    }

    @Test
    fun `saveShortcut publishes a retained payload under the id topic`() = runTest {
        source.start()
        val sc = VoiceShortcut(
            id = "abc1234567890abc1234567890abc123",
            phrase = "test",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe")),
        )
        source.saveShortcut(sc)
        val last = transport.published.last()
        assertEquals("oikos/voice/shortcut/${sc.id}", last.topic)
        assertEquals(true, last.retained)
        assertEquals(1, last.qos)
    }

    @Test
    fun `deleteShortcut publishes an empty retained payload`() = runTest {
        source.start()
        source.deleteShortcut("abc1234567890abc1234567890abc123")
        val last = transport.published.last()
        assertEquals("oikos/voice/shortcut/abc1234567890abc1234567890abc123", last.topic)
        assertEquals(0, last.payload.size)
        assertEquals(true, last.retained)
    }
```

Add any missing imports at the top of the file (`com.gekkotron.oikos.voice.*`).

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :core:source:testDebugUnitTest`
Expected: FAIL on each new test — flow and methods not defined.

- [ ] **Step 5: Implement `VoiceShortcutDocument`**

Create `core/source/src/main/kotlin/com/gekkotron/oikos/source/VoiceShortcutDocument.kt`:

```kotlin
package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.OikosJson
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.serialization.Serializable

/** Retained JSON payload on `<prefix>/voice/shortcut/<id>` (voice spec §4). */
@Serializable
data class VoiceShortcutDocument(
    val v: Int = 1,
    val phrase: String,
    val lang: String,
    val action: String,
    val entityIds: List<String>,
    val value: Int? = null,
) {
    fun toShortcut(id: String? = null): VoiceShortcut? {
        val voiceLang = when (lang) {
            "fr" -> VoiceLang.FR
            "en" -> VoiceLang.EN
            else -> return null
        }
        val voiceAction = VoiceAction.entries.firstOrNull { it.name == action } ?: return null
        return VoiceShortcut(
            id = id ?: VoiceShortcut.id(voiceLang, phrase),
            phrase = phrase,
            lang = voiceLang,
            intent = VoiceIntent(voiceAction, entityIds, value),
        )
    }

    companion object {
        fun encode(doc: VoiceShortcutDocument): String = OikosJson.encodeToString(serializer(), doc)
        fun decode(payload: String): VoiceShortcutDocument? =
            runCatching { OikosJson.decodeFromString(serializer(), payload) }.getOrNull()
    }
}
```

- [ ] **Step 6: Implement in `EntitySource` and `MqttDiscoverySource`**

Edit `core/source/src/main/kotlin/com/gekkotron/oikos/source/EntitySource.kt`, add inside `interface EntitySource`:

```kotlin
    val shortcuts: StateFlow<Map<String, VoiceShortcut>>

    suspend fun saveShortcut(shortcut: VoiceShortcut)

    suspend fun deleteShortcut(id: String)
```

Edit `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt`:
- Add constant `const val VOICE_PREFIX = "oikos/voice/shortcut/"` next to `UI_PREFIX`.
- Add fields: `private val voiceShortcuts = mutableMapOf<String, VoiceShortcut>()`, `private val _shortcuts = MutableStateFlow<Map<String, VoiceShortcut>>(emptyMap())`.
- Override `shortcuts: StateFlow<Map<String, VoiceShortcut>> = _shortcuts`.
- Where subscriptions are built, add `VOICE_PREFIX + "#"`.
- In `handle(…)`, add a branch `topic.startsWith(VOICE_PREFIX) -> onShortcut(topic.removePrefix(VOICE_PREFIX), payload)`:
  - Empty payload → remove by id, update `_shortcuts.value = voiceShortcuts.toMap()`.
  - Otherwise → `VoiceShortcutDocument.decode(payload)?.toShortcut(id = <id>)` → `voiceShortcuts[id] = sc` → `_shortcuts.value = voiceShortcuts.toMap()`. On decode failure, call `report("$VOICE_PREFIX$id: invalid shortcut")`.
- In `journal()`, add every topic `VOICE_PREFIX + id` for ids present in `voiceShortcuts`.
- In `settle()`, mirror the UI_PREFIX cleanup: on restored-only topics starting with `VOICE_PREFIX`, drop the matching id from `voiceShortcuts` and refresh `_shortcuts.value`.
- Implement:
  ```kotlin
  override suspend fun saveShortcut(shortcut: VoiceShortcut) {
      val topic = VOICE_PREFIX + shortcut.id
      require(isValidPublishTopic(topic)) { "shortcut id '${shortcut.id}' is not a valid topic segment" }
      val payload = VoiceShortcutDocument.encode(
          VoiceShortcutDocument(
              phrase = shortcut.phrase,
              lang = shortcut.lang.name.lowercase(),
              action = shortcut.intent.action.name,
              entityIds = shortcut.intent.entityIds,
              value = shortcut.intent.value,
          )
      )
      transport.publish(topic, payload.encodeToByteArray(), qos = 1, retain = true)
  }

  override suspend fun deleteShortcut(id: String) {
      val topic = VOICE_PREFIX + id
      require(isValidPublishTopic(topic)) { "shortcut id '$id' is not a valid topic segment" }
      transport.publish(topic, ByteArray(0), qos = 1, retain = true)
  }
  ```
- `restore(messages)` orders `VOICE_PREFIX` topics after `UI_PREFIX` and before non-discovery.

- [ ] **Step 7: Add stubs to any `EntitySource` fake used by existing tests**

Grep: `rg "EntitySource" core feature app --files-with-matches -l`. For each fake implementation (e.g., `FakeEntitySource` in tests), add `override val shortcuts = MutableStateFlow(emptyMap<String, VoiceShortcut>())`, `override suspend fun saveShortcut(shortcut: VoiceShortcut) {}`, `override suspend fun deleteShortcut(id: String) {}`. Compile with `./gradlew assembleDebug` to find them all; fix any `missing override` the compiler reports before moving on.

- [ ] **Step 8: Run every test to verify they pass**

Run: `./gradlew :core:source:testDebugUnitTest :core:voice:test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add core/source core/voice
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(source): subscribe to and publish voice shortcuts on <prefix>/voice/shortcut/<id>

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 7: `:feature:voice` module, `SpeechCapture`, `VoiceSession` state machine (headless)

**Files:**
- Create: `feature/voice/build.gradle.kts`
- Create: `feature/voice/src/main/AndroidManifest.xml` (empty `<queries>` entry; the activity is added in Task 8)
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/SpeechCapture.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/SpeechAvailability.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceSession.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceCommandRunner.kt`
- Create: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/VoiceSessionTest.kt`
- Modify: `settings.gradle.kts` (`include(":feature:voice")`)

**Interfaces:**
- Consumes: `:core:voice`, `:core:source` (`EntitySource`), `:core:data` (`AppPreferences`), `:core:model`.
- Produces:
  - `fun interface SpeechCapture { fun start(callbacks: Callbacks) ; fun cancel() ; interface Callbacks { fun onPartial(text: String) ; fun onFinal(text: String) ; fun onError(kind: SpeechError) } }`
  - `enum class SpeechError { NO_SPEECH, LANGUAGE_UNAVAILABLE, SERVICE_UNAVAILABLE, PERMISSION_DENIED, UNKNOWN }`
  - `object SpeechAvailability { fun check(context: Context): Status ; sealed interface Status { data object Ready : Status ; data object NeedsApi31 : Status ; data object NoOnDevice : Status ; data class LanguagePackMissing(val tag: String) : Status } }` — the Android-21/30 fallback paths return `NeedsApi31`; the recognizer missing (manifest `<queries>` not merged, no Google speech installed) returns `NoOnDevice`.
  - `sealed interface VoiceSessionState { data class Listening(val partial: String) : VoiceSessionState ; data class ConfirmNeeded(val intent: VoiceIntent, val source: VoiceSource, val codeFormat: CodeFormat?) : VoiceSessionState ; data class Executing(val intent: VoiceIntent) : VoiceSessionState ; data class Done(val result: ExecutionResult) : VoiceSessionState ; data class NotUnderstood(val suggestions: List<Suggestion>) : VoiceSessionState ; data class Error(val kind: ErrorKind) : VoiceSessionState }`
  - `enum class ErrorKind { MQTT_DOWN, SPEECH, PERMISSION, SILENCE }`
  - `data class ExecutionResult(val intent: VoiceIntent, val source: VoiceSource, val succeeded: List<String>, val failed: List<Pair<String, String>>)` (`uniqueId → reason` for failures).
  - `class VoiceSession(private val catalog: VoiceCatalog, private val shortcuts: List<VoiceShortcut>, private val speech: SpeechCapture, private val runner: VoiceCommandRunner, private val scope: CoroutineScope) { val state: StateFlow<VoiceSessionState> ; fun start() ; fun cancel() ; fun pickSuggestion(suggestion: Suggestion, learnPhrase: String) ; fun provideCode(code: String) ; fun confirm(yes: Boolean) ; fun markWrong(/* opens the suggestion list for the last final transcript */) }`
  - `class VoiceCommandRunner(private val source: EntitySource) { suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String? = null): ExecutionResult }` — sends every mapped command in parallel through `source.send(uniqueId, command)`, collects accepted/rejected per entity, and reports `MQTT_DOWN` through an exception when `source.link.value !is LinkState.Connected`.

- [ ] **Step 1: Register the module and write its build file**

Append to `settings.gradle.kts`:

```kotlin
include(":feature:voice")
```

Create `feature/voice/build.gradle.kts`:

```kotlin
plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.voice"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:voice"))
    implementation(project(":core:source"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
```

Create `feature/voice/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>
    <application />
</manifest>
```

Check that `libs.turbine` is in `gradle/libs.versions.toml`; add it if not (version 1.1.0+).

- [ ] **Step 2: Write the failing state-machine tests**

First create `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/Fixtures.kt` — a sibling of the one in `:core:voice`, since those helpers are internal to their module's test source set and not visible across modules:

```kotlin
package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.UiOverride

private object NoBindings : Bindings

fun entity(
    id: String,
    name: String,
    kind: Kind,
    area: String = "unassigned",
    deviceClass: String? = null,
    brightness: Boolean = false,
    position: Boolean = false,
    hidden: Boolean = false,
    partOf: String? = null,
    override: UiOverride = UiOverride(),
): Entity = Entity(
    uniqueId = id,
    deviceId = null,
    name = name,
    kind = kind,
    deviceClass = deviceClass,
    unit = null,
    icon = null,
    capabilities = Capabilities(brightness = brightness, position = position),
    bindings = NoBindings,
    area = area,
    ui = override.copy(hidden = hidden),
    partOf = partOf,
)

fun area(id: String, name: String): Area = Area(id = id, name = name)
```

Then create `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/VoiceSessionTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.voice

import app.cash.turbine.test
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceCatalogBuilder
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {

    private fun catalog(): VoiceCatalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.FR,
        entities = listOf(
            entity("volet_salon_1", "Volet salon 1", Kind.COVER, "salon", position = true),
            entity("volet_salon_2", "Volet salon 2", Kind.COVER, "salon", position = true),
            entity("alarm", "Alarme", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("salon", "Salon"), area("entrance", "Entrée")),
    )

    @Test fun `understood opens the shutters and emits Done`() = runTest {
        val speech = FakeSpeechCapture()
        val runner = FakeRunner(resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted, "volet_salon_2" to CommandResult.Accepted))
        val session = VoiceSession(catalog(), shortcuts = emptyList(), speech = speech, runner = runner, scope = backgroundScope)
        session.state.test {
            assertIs<VoiceSessionState.Listening>(awaitItem())
            session.start()
            speech.emitFinal("ouvre les volets du salon")
            assertIs<VoiceSessionState.Executing>(awaitItem())
            val done = assertIs<VoiceSessionState.Done>(awaitItem())
            assertEquals(setOf("volet_salon_1", "volet_salon_2"), done.result.succeeded.toSet())
            assertTrue(done.result.failed.isEmpty())
        }
    }

    @Test fun `not understood emits NotUnderstood with suggestions`() = runTest {
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("salon") }, FakeRunner(), backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            val not = assertIs<VoiceSessionState.NotUnderstood>(awaitItem())
            assertTrue(not.suggestions.isNotEmpty())
        }
    }

    @Test fun `alarm action lands in ConfirmNeeded`() = runTest {
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("désarme l'alarme") }, FakeRunner(), backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            val confirm = assertIs<VoiceSessionState.ConfirmNeeded>(awaitItem())
            assertEquals(VoiceAction.DISARM, confirm.intent.action)
        }
    }

    @Test fun `an alarm confirm with a disconnected broker ends in Error without sending`() = runTest {
        val runner = FakeRunner(mqttDown = true)
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("désarme l'alarme") }, runner, backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1) // ConfirmNeeded
            session.provideCode("1234")
            session.confirm(yes = true)
            skipItems(1) // Executing (brief)
            val err = assertIs<VoiceSessionState.Error>(awaitItem())
            assertEquals(ErrorKind.MQTT_DOWN, err.kind)
            assertTrue(runner.sent.isEmpty())
        }
    }

    @Test fun `partial failures appear in Done result`() = runTest {
        val runner = FakeRunner(resultByEntity = mapOf(
            "volet_salon_1" to CommandResult.Accepted,
            "volet_salon_2" to CommandResult.Rejected("timeout"),
        ))
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("ouvre les volets du salon") }, runner, backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            skipItems(1) // Executing
            val done = assertIs<VoiceSessionState.Done>(awaitItem())
            assertEquals(listOf("volet_salon_1"), done.result.succeeded)
            assertEquals(listOf("volet_salon_2" to "timeout"), done.result.failed)
        }
    }

    @Test fun `picking a suggestion learns the shortcut and executes`() = runTest {
        var saved: VoiceShortcut? = null
        val runner = FakeRunner(onSave = { saved = it }, resultByEntity = mapOf("volet_salon_1" to CommandResult.Accepted, "volet_salon_2" to CommandResult.Accepted))
        val session = VoiceSession(catalog(), emptyList(), FakeSpeechCapture().also { it.scheduleFinal("quelque chose salon") }, runner, backgroundScope)
        session.state.test {
            skipItems(1)
            session.start()
            val not = assertIs<VoiceSessionState.NotUnderstood>(awaitItem())
            val choice = not.suggestions.first { it.intent.action == VoiceAction.OPEN }
            session.pickSuggestion(choice, learnPhrase = "quelque chose salon")
            skipItems(1) // Executing
            assertIs<VoiceSessionState.Done>(awaitItem())
        }
        requireNotNull(saved)
        assertEquals("quelque chose salon", saved!!.phrase)
    }
}
```

Create the fakes in the same test file (or a sibling `Fakes.kt`):

```kotlin
class FakeSpeechCapture : SpeechCapture {
    private var cb: SpeechCapture.Callbacks? = null
    private var pending: String? = null
    override fun start(callbacks: SpeechCapture.Callbacks) { cb = callbacks ; pending?.let { emitFinal(it) ; pending = null } }
    override fun cancel() { cb = null }
    fun scheduleFinal(text: String) { pending = text }
    fun emitFinal(text: String) { cb?.onFinal(text) }
    fun emitError(kind: SpeechError) { cb?.onError(kind) }
}

class FakeRunner(
    private val mqttDown: Boolean = false,
    private val resultByEntity: Map<String, CommandResult> = emptyMap(),
    private val onSave: (VoiceShortcut) -> Unit = {},
) : VoiceCommandRunner(object : com.gekkotron.oikos.source.EntitySource {
    override val link = TODO("unused in tests")
    override val entities = TODO("unused in tests")
    override val devices = TODO("unused in tests")
    override val areas = TODO("unused in tests")
    override val commandFailures = TODO("unused in tests")
    override val shortcuts = TODO("unused in tests")
    override fun states() = TODO("unused in tests")
    override suspend fun send(uniqueId: String, command: com.gekkotron.oikos.model.Command): CommandResult = resultByEntity[uniqueId] ?: CommandResult.Accepted
    override suspend fun setOverride(uniqueId: String, override: com.gekkotron.oikos.model.UiOverride) = Unit
    override suspend fun setAreas(areas: List<com.gekkotron.oikos.model.Area>) = Unit
    override suspend fun saveShortcut(shortcut: VoiceShortcut) { onSave(shortcut) }
    override suspend fun deleteShortcut(id: String) = Unit
}) {
    val sent = mutableListOf<Pair<String, com.gekkotron.oikos.model.Command>>()
    override suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String?, source: VoiceSource): ExecutionResult {
        if (mqttDown) throw com.gekkotron.oikos.feature.voice.MqttDownException()
        val commands = com.gekkotron.oikos.voice.CommandMapper.map(intent, catalog, code)
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for ((id, cmd) in commands) {
            sent += id to cmd
            when (val r = resultByEntity[id] ?: CommandResult.Accepted) {
                CommandResult.Accepted -> succeeded += id
                is CommandResult.Rejected -> failed += id to r.reason
            }
        }
        return ExecutionResult(intent, source, succeeded, failed)
    }
}
```

The real `VoiceCommandRunner` is non-open on purpose; the fake above overrides it for tests. Alternatively, make `VoiceCommandRunner` an `open class` or extract a `interface CommandRunner` and inject it into `VoiceSession`. Pick one of the two — the plan assumes the interface split; adjust the production code and the `VoiceSession` constructor accordingly if you choose that route.

(Preferred route — recommended by the plan author — is the interface split. Define `interface CommandRunner { suspend fun run(…): ExecutionResult }`, make `VoiceCommandRunner` the production implementation of it, and have `VoiceSession` depend on `CommandRunner`.)

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: FAIL — classes not defined.

- [ ] **Step 4: Implement `SpeechCapture`, `SpeechAvailability`**

Create `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/SpeechAvailability.kt`:

```kotlin
package com.gekkotron.oikos.feature.voice

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

object SpeechAvailability {
    sealed interface Status {
        data object Ready : Status
        data object NeedsApi31 : Status
        data object NoOnDevice : Status
        data class LanguagePackMissing(val tag: String) : Status
    }

    fun check(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.NeedsApi31
        return if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) Status.Ready else Status.NoOnDevice
    }
}
```

Create `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/SpeechCapture.kt`:

```kotlin
package com.gekkotron.oikos.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

enum class SpeechError { NO_SPEECH, LANGUAGE_UNAVAILABLE, SERVICE_UNAVAILABLE, PERMISSION_DENIED, UNKNOWN }

interface SpeechCapture {
    fun start(callbacks: Callbacks)
    fun cancel()

    interface Callbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(kind: SpeechError)
    }
}

/** Default Android on-device recognizer (API 31+). */
@RequiresApi(Build.VERSION_CODES.S)
class AndroidSpeechCapture(
    private val context: Context,
    private val localeTag: String,
) : SpeechCapture {
    private var recognizer: SpeechRecognizer? = null

    override fun start(callbacks: SpeechCapture.Callbacks) {
        cancel()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(callbacks::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                callbacks.onFinal(text)
            }
            override fun onError(error: Int) {
                val kind = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechError.LANGUAGE_UNAVAILABLE
                    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechError.SERVICE_UNAVAILABLE
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION_DENIED
                    else -> SpeechError.UNKNOWN
                }
                if (kind == SpeechError.LANGUAGE_UNAVAILABLE) {
                    // Spec §2.2: request the language pack from Google Speech.
                    val checkIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    }
                    runCatching { r.triggerModelDownload(checkIntent) }
                }
                callbacks.onError(kind)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    override fun cancel() {
        recognizer?.destroy()
        recognizer = null
    }
}
```

- [ ] **Step 5: Implement `MqttDownException`, `CommandRunner` interface, `VoiceCommandRunner`**

Create `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceCommandRunner.kt`:

```kotlin
package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.voice.CommandMapper
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MqttDownException : RuntimeException("mqtt is not connected")

interface CommandRunner {
    suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String? = null, source: VoiceSource = VoiceSource.RULES): ExecutionResult
}

open class VoiceCommandRunner(private val entitySource: EntitySource) : CommandRunner {
    override suspend fun run(intent: VoiceIntent, catalog: VoiceCatalog, code: String?, source: VoiceSource): ExecutionResult = coroutineScope {
        if (entitySource.link.value !is LinkState.Connected) throw MqttDownException()
        val commands = CommandMapper.map(intent, catalog, code)
        val results = commands.map { (id, cmd) ->
            async {
                val r = entitySource.send(id, cmd)
                id to r
            }
        }.awaitAll()
        val succeeded = results.filter { it.second is CommandResult.Accepted }.map { it.first }
        val failed = results.mapNotNull { (id, r) -> if (r is CommandResult.Rejected) id to r.reason else null }
        ExecutionResult(intent, source, succeeded, failed)
    }
}
```

- [ ] **Step 6: Implement `VoiceSession`**

Create `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceSession.kt`:

```kotlin
package com.gekkotron.oikos.feature.voice

import com.gekkotron.oikos.model.CodeFormat
import com.gekkotron.oikos.voice.Confirmation
import com.gekkotron.oikos.voice.Suggestion
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceEngine
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceResolution
import com.gekkotron.oikos.voice.VoiceShortcut
import com.gekkotron.oikos.voice.VoiceShortcut.Companion.id
import com.gekkotron.oikos.voice.VoiceSource
import com.gekkotron.oikos.voice.VoiceText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoiceSessionState {
    data class Listening(val partial: String = "") : VoiceSessionState
    data class ConfirmNeeded(val intent: VoiceIntent, val source: VoiceSource, val codeFormat: CodeFormat?) : VoiceSessionState
    data class Executing(val intent: VoiceIntent) : VoiceSessionState
    data class Done(val result: ExecutionResult) : VoiceSessionState
    data class NotUnderstood(val suggestions: List<Suggestion>) : VoiceSessionState
    data class Error(val kind: ErrorKind) : VoiceSessionState
}

enum class ErrorKind { MQTT_DOWN, SPEECH, PERMISSION, SILENCE }

data class ExecutionResult(
    val intent: VoiceIntent,
    val source: VoiceSource,
    val succeeded: List<String>,
    val failed: List<Pair<String, String>>,
)

class VoiceSession(
    private val catalog: VoiceCatalog,
    private val shortcuts: List<VoiceShortcut>,
    private val speech: SpeechCapture,
    private val runner: CommandRunner,
    private val scope: CoroutineScope,
    private val onLearn: suspend (VoiceShortcut) -> Unit = {},
) {
    private val _state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Listening(""))
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()
    private val engine = VoiceEngine(catalog, shortcuts)
    private var pendingIntent: VoiceIntent? = null
    private var pendingSource: VoiceSource = VoiceSource.RULES
    private var pendingCode: String? = null
    private var lastTranscript: String? = null

    fun start() {
        speech.start(object : SpeechCapture.Callbacks {
            override fun onPartial(text: String) { _state.value = VoiceSessionState.Listening(text) }
            override fun onFinal(text: String) {
                lastTranscript = text
                when (val r = engine.resolve(text)) {
                    is VoiceResolution.Understood -> handleIntent(r.intent, r.source)
                    is VoiceResolution.NotUnderstood -> _state.value = VoiceSessionState.NotUnderstood(r.suggestions)
                }
            }
            override fun onError(kind: SpeechError) {
                _state.value = VoiceSessionState.Error(
                    when (kind) {
                        SpeechError.NO_SPEECH -> ErrorKind.SILENCE
                        SpeechError.PERMISSION_DENIED -> ErrorKind.PERMISSION
                        else -> ErrorKind.SPEECH
                    }
                )
            }
        })
    }

    fun cancel() { speech.cancel() }

    fun pickSuggestion(suggestion: Suggestion, learnPhrase: String) {
        pendingIntent = suggestion.intent
        pendingSource = VoiceSource.RULES
        scope.launch {
            onLearn(
                VoiceShortcut(
                    id = id(catalog.lang, VoiceText.normalizeShortcut(learnPhrase, catalog.lang)),
                    phrase = learnPhrase,
                    lang = catalog.lang,
                    intent = suggestion.intent,
                )
            )
            handleIntent(suggestion.intent, VoiceSource.RULES)
        }
    }

    fun provideCode(code: String) { pendingCode = code }

    fun confirm(yes: Boolean) {
        val intent = pendingIntent ?: return
        if (!yes) {
            _state.value = VoiceSessionState.Done(ExecutionResult(intent, pendingSource, emptyList(), emptyList()))
            return
        }
        execute(intent, pendingSource, pendingCode)
    }

    private fun handleIntent(intent: VoiceIntent, source: VoiceSource) {
        pendingIntent = intent
        pendingSource = source
        if (Confirmation.needsConfirmation(intent, catalog)) {
            val format = intent.entityIds.firstNotNullOfOrNull { catalog.entity(it) }?.codeFormat
            _state.value = VoiceSessionState.ConfirmNeeded(intent, source, format)
            return
        }
        execute(intent, source, code = null)
    }

    private fun execute(intent: VoiceIntent, source: VoiceSource, code: String?) {
        _state.value = VoiceSessionState.Executing(intent)
        scope.launch {
            val result = try {
                runner.run(intent, catalog, code, source)
            } catch (e: MqttDownException) {
                _state.value = VoiceSessionState.Error(ErrorKind.MQTT_DOWN)
                return@launch
            }
            _state.value = VoiceSessionState.Done(result)
        }
    }
}
```

Also update the `CommandRunner` interface signature to `run(intent, catalog, code, voiceSource)` to match the session's call site.

- [ ] **Step 7: Run every `:feature:voice` test to verify they pass**

Run: `./gradlew :feature:voice:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add settings.gradle.kts feature/voice gradle/libs.versions.toml
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(voice): :feature:voice module with SpeechCapture, VoiceSession, VoiceCommandRunner

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 8: Compose voice sheet, translucent `VoiceActivity`, `VoiceViewModel`

**Files:**
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceViewModel.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceActivity.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/VoiceSheet.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/ListeningView.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/UnderstoodView.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/NotUnderstoodView.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/ui/ConfirmView.kt`
- Create: `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/di/VoiceModule.kt`
- Create: `feature/voice/src/main/res/values/strings.xml`
- Create: `feature/voice/src/main/res/values-fr/strings.xml`
- Modify: `feature/voice/src/main/AndroidManifest.xml` (add `<activity>`)
- Create: `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/ui/VoiceSheetScreenshotTest.kt`

**Interfaces:**
- Produces:
  - `@HiltViewModel class VoiceViewModel @Inject constructor(private val source: EntitySource, private val preferences: AppPreferences, @ApplicationContext private val appContext: Context) : ViewModel()` with:
    - `val state: StateFlow<VoiceSessionState>`
    - `fun start(launcherLang: VoiceLang)` builds a `VoiceCatalog`, collects live `source.shortcuts`, picks the right `SpeechCapture`, and delegates to a `VoiceSession`.
    - `fun pickSuggestion(suggestion: Suggestion)` — uses the last transcript as the learn phrase and calls `source.saveShortcut`.
    - `fun confirm(yes: Boolean)`
    - `fun provideCode(code: String)`
    - `fun cancel()`
  - `class VoiceActivity : ComponentActivity()` — translucent (`Theme.OikosWidget.Translucent` reused), excluded from recents, requests `RECORD_AUDIO` if missing, finishes when the sheet closes.
  - `VoiceSheet(state, onPick, onConfirm, onCode, onCancel)` is the Compose entry point rendered inside `BottomSheetScaffold`.

- [ ] **Step 1: Write the Compose screenshot/behaviour test skeleton**

Create `feature/voice/src/test/kotlin/com/gekkotron/oikos/feature/voice/ui/VoiceSheetScreenshotTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.feature.voice.ExecutionResult
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSheetScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `listening shows the pulsing mic and the partial text`() {
        val state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Listening("ouvre les vo"))
        rule.setContent { VoiceSheet(state = state, onPick = {}, onConfirm = {}, onCode = {}, onCancel = {}) }
        rule.onNodeWithText("ouvre les vo").assertExists()
    }

    @Test fun `understood shows the Faux button`() {
        val state = MutableStateFlow<VoiceSessionState>(
            VoiceSessionState.Done(
                ExecutionResult(
                    intent = VoiceIntent(VoiceAction.OPEN, listOf("x")),
                    source = VoiceSource.RULES,
                    succeeded = listOf("x"),
                    failed = emptyList(),
                )
            )
        )
        rule.setContent { VoiceSheet(state = state, onPick = {}, onConfirm = {}, onCode = {}, onCancel = {}) }
        rule.onNodeWithText("✗ Faux").assertExists()
    }

    @Test fun `confirm shows Disarm question for an alarm`() {
        val state = MutableStateFlow<VoiceSessionState>(
            VoiceSessionState.ConfirmNeeded(
                intent = VoiceIntent(VoiceAction.DISARM, listOf("alarm")),
                source = VoiceSource.RULES,
                codeFormat = com.gekkotron.oikos.model.CodeFormat.NUMBER,
            )
        )
        var confirmed = false
        rule.setContent { VoiceSheet(state = state, onPick = {}, onConfirm = { confirmed = it }, onCode = {}, onCancel = {}) }
        rule.onNodeWithText("Désarmer").performClick()
        assert(confirmed)
    }
}
```

- [ ] **Step 2: Add strings**

Create `feature/voice/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="voice_sheet_title">Voice</string>
    <string name="voice_listening">Listening…</string>
    <string name="voice_not_understood">Did you mean…?</string>
    <string name="voice_other">Other…</string>
    <string name="voice_ignore">Ignore</string>
    <string name="voice_wrong">✗ Wrong</string>
    <string name="voice_mqtt_down">No connection to the house</string>
    <string name="voice_silence">I didn\'t hear anything</string>
    <string name="voice_speech_error">Speech recognizer unavailable</string>
    <string name="voice_permission_denied">Microphone permission needed</string>
    <string name="voice_confirm_disarm">Disarm the alarm?</string>
    <string name="voice_confirm_arm_away">Arm the alarm (away)?</string>
    <string name="voice_confirm_arm_home">Arm the alarm (home)?</string>
    <string name="voice_confirm_siren">Turn on the siren?</string>
    <string name="voice_yes">Yes</string>
    <string name="voice_no">No</string>
    <string name="voice_cancel">Cancel</string>
    <string name="voice_mic_cd">Say a command</string>
    <string name="voice_widget_label">Voice</string>
    <string name="voice_widget_description">Say a command to Oikos</string>
    <string name="voice_disarm">Disarm</string>
    <string name="voice_arm_away">Arm (away)</string>
    <string name="voice_arm_home">Arm (home)</string>
</resources>
```

Create `feature/voice/src/main/res/values-fr/strings.xml` with the French translations (`Écoute…`, `Tu voulais dire… ?`, `Autre…`, `Ignorer`, `✗ Faux`, `Pas de connexion à la maison`, `Je n'ai rien entendu`, `Reconnaissance vocale indisponible`, `Permission micro requise`, `Désarmer l'alarme ?`, `Armer l'alarme (absent) ?`, `Armer l'alarme (présent) ?`, `Déclencher la sirène ?`, `Oui`, `Non`, `Annuler`, `Dis une commande`, `Vocal`, `Dis une commande à Oikos`, `Désarmer`, `Armer (absent)`, `Armer (présent)`).

- [ ] **Step 3: Write Compose sheet code**

Create the four view files and `VoiceSheet.kt` so each test above lands on something it can see. Each view renders its own state branch (see spec §3). `ConfirmView` uses `com.gekkotron.oikos.designsystem.alarm.CodeKeypadSheet` when `codeFormat` is `NUMBER` and the panel needs a code; otherwise a plain `✓ / ✗` row. `NotUnderstoodView` lists `state.suggestions.map { it.label }` as tappable rows plus an `Other…` entry (opens an action-then-entity-then-value picker, built on the catalog state) and `Ignore`. `UnderstoodView` shows the "✓ Ouvrir · <names> (<n>)" summary and the `✗ Wrong` button (replays the suggestion list for the last transcript — see `VoiceViewModel.markWrong()`).

- [ ] **Step 4: `VoiceViewModel`**

Create `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceViewModel.kt`:
- Collects `source.entities.value` and `source.areas.value` (snapshot taken at `start(…)`) to build a `VoiceCatalog`.
- Collects `source.shortcuts.value` (same snapshot).
- Instantiates `AndroidSpeechCapture(appContext, if (lang == VoiceLang.FR) "fr-FR" else "en-US")`, falls back to `Error(SPEECH)` on an `IllegalStateException`.
- `pickSuggestion(s)` sends to `VoiceSession.pickSuggestion(s, learnPhrase = lastTranscript)` where `lastTranscript` is captured by the ViewModel from the session (expose it on `VoiceSession` as `val lastTranscript: String?` or feed it back through `onLearn`).
- `onLearn = { source.saveShortcut(it) }`.
- `cancel()` tears down the session and cancels the recognizer.

- [ ] **Step 5: `VoiceActivity`**

Create `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/VoiceActivity.kt`:
- Extends `ComponentActivity` and `@AndroidEntryPoint`.
- `setContent { OikosTheme { Surface(color = Color.Transparent) { VoiceSheet(…) } } }`.
- Uses `rememberLauncherForActivityResult(RequestPermission()) { granted -> if (!granted) finish() }` on launch when `RECORD_AUDIO` is not granted.
- Reads `AppPreferences.voiceEnabled.value` — finishes immediately when false (defence against a stale widget tap).
- Looks up the current app locale (`AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()`) and passes it as `VoiceLang.fromAppLocale(tag)`.

- [ ] **Step 6: Add the activity to the manifest**

Edit `feature/voice/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>
    <application>
        <activity
            android:name=".VoiceActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:launchMode="singleTask"
            android:taskAffinity=""
            android:theme="@style/Theme.OikosWidget.Translucent" />
    </application>
</manifest>
```

- [ ] **Step 7: Hilt wiring**

Create `feature/voice/src/main/kotlin/com/gekkotron/oikos/feature/voice/di/VoiceModule.kt`:

```kotlin
package com.gekkotron.oikos.feature.voice.di

import com.gekkotron.oikos.feature.voice.CommandRunner
import com.gekkotron.oikos.feature.voice.VoiceCommandRunner
import com.gekkotron.oikos.source.EntitySource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoiceProvidesModule {
    @Provides @Singleton
    fun commandRunner(source: EntitySource): CommandRunner = VoiceCommandRunner(source)
}
```

- [ ] **Step 8: Run tests + manifest merge check**

Run: `./gradlew :feature:voice:testDebugUnitTest :feature:voice:assembleDebug`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add feature/voice
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(voice): Compose voice sheet, translucent VoiceActivity, Hilt wiring

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 9: Settings toggle + shortcuts list

**Files:**
- Modify: `feature/settings/build.gradle.kts` (add `:core:voice` and `:feature:voice` dependencies, or expose the pieces through `:core:source`).
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/SettingsScreen.kt`
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/VoiceSection.kt`
- Create: `feature/settings/src/main/kotlin/com/gekkotron/oikos/feature/settings/ui/VoiceShortcutsSection.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml`
- Modify: `feature/settings/src/main/res/values-fr/strings.xml`
- Create: `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsVoiceTest.kt`

**Interfaces:**
- Consumes: `AppPreferences.voiceEnabled/setVoiceEnabled`, `EntitySource.shortcuts/deleteShortcut`, `SpeechAvailability.check(context)`, `VoiceCatalogBuilder`, `ShortcutMatcher.status`.
- Produces:
  - `SettingsUi.voice: VoiceSettingsUi(enabled: Boolean, availability: SpeechAvailability.Status, shortcuts: List<ShortcutRow>)`
  - `data class ShortcutRow(val id: String, val phrase: String, val lang: VoiceLang, val label: String, val broken: Boolean)`
  - `SettingsViewModel.setVoiceEnabled(enabled: Boolean)` and `SettingsViewModel.deleteShortcut(id: String)`.

- [ ] **Step 1: Write the failing presentation test**

Create `feature/settings/src/test/kotlin/com/gekkotron/oikos/feature/settings/presentation/SettingsVoiceTest.kt` (JVM, uses fakes for `EntitySource` and `AppPreferences`). Assert that:
- When `voiceEnabled = false`, `voice.enabled = false`.
- Flipping on through `setVoiceEnabled(true)` calls `preferences.setVoiceEnabled(true)` once.
- `shortcuts` lists one row with `broken = true` when the shortcut points to a deleted entity.
- A row is tagged with its `lang` (not filtered out) when it is for the other language.

- [ ] **Step 2: Implement the state and UI**

Follow the existing `SettingsScreen` structure:
- Add a `VoiceSection(composable)` with:
  - A `Switch` for `voiceEnabled` disabled when `availability !is Ready`, with a one-line reason underneath (strings).
  - When `availability == NeedsApi31`: `voice_availability_needs_api_31`.
  - When `availability == NoOnDevice`: `voice_availability_no_on_device`.
- `VoiceShortcutsSection(composable)`:
  - Shows the list of `ShortcutRow`.
  - A broken row has a strikethrough phrase and a `warning` chip.
  - Each row is tappable → confirm dialog → `deleteShortcut(id)`.
- `SettingsViewModel.setVoiceEnabled(enabled)` writes the preference immediately. The `RECORD_AUDIO` permission request is launched from the Compose code with `rememberLauncherForActivityResult` — on a denial, call `setVoiceEnabled(false)` and show a snackbar.

Add new strings in both `values/` and `values-fr/` for every new resource used.

- [ ] **Step 3: Add build-file dependency on `:core:voice`**

Edit `feature/settings/build.gradle.kts`, add inside `dependencies`:

```kotlin
    implementation(project(":core:voice"))
```

Do not add `:feature:voice` to avoid dragging Android lifecycle into settings; the permission launcher is wired in Compose with the stock contracts.

- [ ] **Step 4: Run tests + manifest merge**

Run: `./gradlew :feature:settings:testDebugUnitTest :feature:settings:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add feature/settings
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(settings): voice commands switch and shortcuts list

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 10: Home mic button + existing-widget mic + new voice widget

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeViewModel.kt` (expose `voiceEnabled: StateFlow<Boolean>`)
- Modify: `feature/home/build.gradle.kts` (add `:core:data`)
- Modify: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/ui/<glance widget content>.kt` (add mic button when `voiceEnabled`)
- Create: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/voice/VoiceGlanceWidget.kt`
- Create: `feature/widget/src/main/kotlin/com/gekkotron/oikos/feature/widget/voice/VoiceGlanceReceiver.kt`
- Create: `feature/widget/src/main/res/xml/oikos_voice_widget_info.xml`
- Create: `feature/widget/src/main/res/drawable/ic_voice_widget_preview.xml`
- Modify: `feature/widget/src/main/AndroidManifest.xml` (new receiver)
- Modify: `feature/widget/src/main/res/values/strings.xml`
- Modify: `feature/widget/src/main/res/values-fr/strings.xml`
- Modify: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/StaticIcon.kt` (add `OikosGlyph.MIC`)
- Modify: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/OikosGlyphTest.kt` and the smoke test to cover the new glyph.

**Interfaces:**
- Consumes: `AppPreferences.voiceEnabled`.
- Produces:
  - `OikosGlyph.MIC` — new icon in the design system.
  - On Home: a `MicIconButton` next to Search when `voiceEnabled == true`, starts `VoiceActivity`.
  - On the existing widget: a mic cell appended to the top row when `voiceEnabled == true`, launches `VoiceActivity`.
  - A new `VoiceGlanceWidget` with just a large mic icon that opens `VoiceActivity`.

- [ ] **Step 1: Add `OikosGlyph.MIC`**

Pick a simple path (microphone silhouette, ~24×24 viewport). Add it to `OikosGlyph` so the existing smoke test picks it up. Update the glyph test's expected `entries.size`.

- [ ] **Step 2: Home mic button**

In `HomeScreen.kt`, right before the Search `IconButton` (line 280), when `voiceEnabled`:

```kotlin
val voiceLabel = stringResource(R.string.home_voice_cd)
IconButton(onClick = onVoiceClick) {
    StaticIcon(OikosGlyph.MIC, modifier = Modifier.size(24.dp).semantics { contentDescription = voiceLabel })
}
```

Add `R.string.home_voice_cd` in both strings files.

Wire `onVoiceClick`: `val ctx = LocalContext.current` → `ctx.startActivity(Intent(ctx, Class.forName("com.gekkotron.oikos.feature.voice.VoiceActivity")))`. Keep it reflective to avoid a direct dep from `:feature:home` on `:feature:voice`.

- [ ] **Step 3: `HomeViewModel.voiceEnabled`**

Add `preferences: AppPreferences` to `HomeViewModel`'s constructor and expose:
```kotlin
val voiceEnabled: StateFlow<Boolean> = preferences.voiceEnabled.map { it ?: false }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
```

Collect it in the `HomeRoute` and pass it down to `HomeScreen`.

- [ ] **Step 4: Mic cell on the existing widget**

Open the top-row builder of the existing widget's Glance code, grep: `rg "class .*(Widget|Glance).*: GlanceAppWidget" feature/widget/src/main`. Append a mic `Image(ImageProvider(R.drawable.ic_voice_widget_preview), modifier = GlanceModifier.clickable(actionStartActivity<VoiceActivity>()))` when the widget config says `voiceEnabled`. Fetch the preference by reading the same DataStore the widget already reads.

- [ ] **Step 5: Dedicated voice widget**

Create `VoiceGlanceWidget` with a single large mic that launches `VoiceActivity`. Register its receiver in the manifest with its own `oikos_voice_widget_info.xml` (min 1×1 cell).

- [ ] **Step 6: Compile and tests**

Run: `./gradlew :core:designsystem:testDebugUnitTest :feature:home:testDebugUnitTest :feature:widget:testDebugUnitTest assembleDebug`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com add core/designsystem feature/home feature/widget
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "$(cat <<'EOF'
feat(voice): mic on Home, mic on existing widget, new voice widget

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

### Task 11: App-level wiring, permission, and device checklist

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (add `<uses-permission android:name="android.permission.RECORD_AUDIO" />`).
- Modify: `app/build.gradle.kts` (add `implementation(project(":feature:voice"))`).
- Modify: `app/src/main/kotlin/com/gekkotron/oikos/OikosApplication.kt` (if any module index — none expected).

**Interfaces:**
- No new public API. The permission request lives in `VoiceActivity` and `SettingsScreen`.

- [ ] **Step 1: Permission in the manifest**

Add inside the `<manifest>` element of `app/src/main/AndroidManifest.xml`, next to the other `<uses-permission>` lines:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 2: Add `:feature:voice` to `:app`**

Edit `app/build.gradle.kts`, add inside `dependencies`:

```kotlin
    implementation(project(":feature:voice"))
```

- [ ] **Step 3: Final gate**

Run: `./gradlew check assembleDebug`
Expected: PASS.

- [ ] **Step 4: Device checklist (Pixel 6a, serial 28281JEGR02735)**

The implementer runs this on the test device after the final build, and records the results in the commit body of Step 5.

```
./gradlew :app:installDebug
adb -s 28281JEGR02735 shell am force-stop com.gekkotron.oikos.debug
adb -s 28281JEGR02735 shell am start -n com.gekkotron.oikos.debug/com.gekkotron.oikos.MainActivity
```

- [ ] 1. Turn the Settings switch on → RECORD_AUDIO permission prompt appears → grant → switch stays on.
- [ ] 2. Tap the Home mic → the sheet appears; say `allume la lampe du salon` → that lamp turns on; sheet closes within ~2 s.
- [ ] 3. Tap the mic on the existing widget → same sheet; say `ferme les volets du salon` → shutters close.
- [ ] 4. Pin the voice-only widget → tap it → same sheet.
- [ ] 5. Say a phrase that is not understood → "Tu voulais dire…?" shows up to 6 suggestions; tap one → it runs and is learned; tap `✗ Faux` → suggestions appear again.
- [ ] 6. On a second phone, say the taught phrase → it works (shortcut shared over MQTT).
- [ ] 7. Say `désarme l'alarme` → confirmation + code keypad → enter the correct code → alarm disarms.
- [ ] 8. Say `déclenche la sirène` → confirmation appears before anything is sent.
- [ ] 9. Put the phone in airplane mode → say any command → "Pas de connexion à la maison"; broker still shows no command.
- [ ] 10. In demo mode, say `allume les lumières du salon` → the demo lights flip on.
- [ ] 11. Revoke the microphone permission from Android settings → re-open the app; the Settings switch turns itself off with an explanation.
- [ ] 12. Verify `git log origin/master..HEAD --format='%ae %ce'` prints only `60887050+Gekkotron@users.noreply.github.com 60887050+Gekkotron@users.noreply.github.com` for every commit.

- [ ] **Step 5: Commit the checklist run**

```bash
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit --allow-empty -m "$(cat <<'EOF'
chore(voice): device-checklist pass on Pixel 6a (28281JEGR02735)

All 12 steps green. Branch feat/voice ready for review.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: 7c8bb12d-3780-4823-937b-fa7f0a90b5f3
EOF
)"
```

---

## Done

At this point:
- `:core:voice` provides a pure engine covered by JVM tests.
- `:feature:voice` provides the Android host (recognizer, session, sheet, activity).
- `:core:source` publishes and subscribes to retained voice shortcuts.
- `:core:data` carries the `voiceEnabled` preference, off by default.
- `:feature:home` and `:feature:widget` show the mic.
- `:app` holds the permission and the module hook-up.

Push after reviewing `git log origin/master..HEAD` and running `./gradlew check` one last time locally. The branch target is `master`.
