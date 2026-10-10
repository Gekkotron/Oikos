# Oikos — Voice commands design

Status: approved design, 2026-10-10. Built on a prototype run on a Pixel 6a (reference code: `~/Documents/SourcesPerso/OikosVoicePrototype`, outside this repo).

## 1. Goals and scope

**Goal.** Say "ouvre les volets du salon" and Oikos does it, with no rules to write. Speech is recognized on the phone, the phrase is matched against the house's own entities, and anything not understood is taught by tapping the right command once.

**User decisions**
- Voice is **optional**: a Settings switch, **off by default**. Off means no mic button, no permission prompt.
- Entry points: a **mic button on Home**, a **mic on the existing widget**, and a **new dedicated voice widget**. All three open the same overlay.
- An understood command **runs immediately**; the result is shown with a ✗ Faux button to correct it. Alarm and siren actions always confirm first.
- Learned shortcuts are **shared through MQTT**, like Customize overrides.
- Language follows the phone/app: **French or English**, one at a time.
- No LLM anywhere (measured too slow: 3–6 s on the Geekom, 5–36 s on the phone, and inaccurate on the phone).
- Approach A: a pure-Kotlin engine module `:core:voice` and an Android module `:feature:voice`.

**Out of scope (v1)**
- Wake word ("Hey Oikos"): planned phase 2, opt-in (openWakeWord or microWakeWord; Porcupine's free tier ended 2026-06-30).
- Registering as the default assistant app.
- Spoken replies (TTS) and questions ("quelle température dans le salon ?").
- Climate temperatures, number/select/button entities, light colours and effects.
- Two languages at once.

## 2. Modules

### 2.1 `:core:voice` (pure Kotlin, depends on `:core:model` only)

- **`VoiceLexicon`**: data only, one table per language (`fr`, `en`):
  - verbs → `VoiceAction`;
  - device words (shutter/volet, light/lumière/led, heater/chauffage, plug/prise, siren/sirène, tv/télé, ceiling/plafond, christmas lights/guirlande, pool/piscine, north/nord…), each mapped to a language-neutral concept so English raw names ("shutter_tv") match French speech;
  - room words (living/salon/séjour, office/bureau, bath/salle de bain, kitchen/cuisine, bedroom/chambre, stair/escalier, upstairs/étage, outdoor/dehors/jardin…);
  - filler words, and value phrases (« à N % », « à moitié », "N percent", "half").
  Nothing in it is house-specific; a new language is a new table.
- **`VoiceCatalog`**: built from the live `List<Entity>` and `List<Area>`. It keeps entities that are standalone (`partOf == null`), not hidden, and of kind LIGHT, SWITCH, COVER or ALARM_PANEL. It uses the display name (override first) and the effective area. An area whose name is not a room word is treated as a person's room ("la chambre de Rémi", "chez Rémi", "Rémi").
- **`VoiceIntent(action, entityIds, value?)`** with `VoiceAction` ∈ TURN_ON, TURN_OFF, OPEN, CLOSE, STOP, SET_POSITION, SET_BRIGHTNESS, ARM_AWAY, ARM_HOME, DISARM.
- **`RuleMatcher`**: the prototype's matcher, made language-agnostic. Normalization, verb detection, value extraction, room and device concept matching, and a Levenshtein ≤ 1 fuzzy match on query tokens of 5+ characters. One rule changes: **a singular phrase plus a room with several matching devices means the whole room group** ("la lumière du salon" = every light of the living room); naming a device still picks it alone. "Light class" = kind LIGHT, or a SWITCH whose name has a light word. It returns `null` when unsure, never a guess.
- **`Suggester`**: up to 6 ranked `Suggestion(intent, label)`. Candidates come from the room and device words heard; groups come before singles when a room was said; a detected verb comes first, otherwise the natural defaults per kind.
- **`ShortcutMatcher`**: matches a normalized phrase (lowercase, no accents or punctuation, filler words dropped, per language) against learned shortcuts.
- **`VoiceEngine.resolve(text, catalog, shortcuts)`** → `Understood(intent, source = SHORTCUT | RULES)` or `NotUnderstood(suggestions)`. Shortcuts are tried first.
- **`CommandMapper`**: `VoiceIntent` → `List<Pair<uniqueId, Command>>`:

| Action | Kinds | Command |
|---|---|---|
| TURN_ON / TURN_OFF | LIGHT, SWITCH | `TurnOn()` / `TurnOff` |
| OPEN / CLOSE / STOP | COVER | `Open` / `Close` / `Stop` |
| SET_POSITION n | COVER with position | `SetPosition(n)` |
| SET_BRIGHTNESS n | LIGHT with brightness | `TurnOn(brightness = round(n × 2.55))` |
| ARM_AWAY / ARM_HOME / DISARM | ALARM_PANEL | `Arm(AWAY/HOME, code)` / `Disarm(code)` |

  Incompatible pairs are dropped. An intent with no command left is rejected.
- **`needsConfirmation(intent)`**: true for any ALARM_PANEL action, and for any entity whose name or device class says siren.

### 2.2 `:feature:voice` (Android)

- **`SpeechCapture`**: wraps `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31) with `EXTRA_PREFER_OFFLINE` and partial results. The language is `fr-FR` when the app locale is French, otherwise `en-US`. On `ERROR_LANGUAGE_UNAVAILABLE` / `NOT_SUPPORTED` it calls `triggerModelDownload` and reports it. The manifest declares `<queries><intent><action android:name="android.speech.RecognitionService"/></intent></queries>`; without it the recognizer is reported unavailable (seen in the prototype).
- **`VoiceSession`** (state holder, ViewModel): `Listening(partial)` → `Understood` → `Executing` → `Done(result)`; or → `NotUnderstood(suggestions)` → (pick) → `Learned` → `Executing` → `Done`; or `Confirm` (alarm/siren, with the code keypad when required) → `Executing`; plus `Error(kind)`.
- **`VoiceActivity`**: a translucent activity hosting a Compose bottom sheet. Android only lets an app record while one of its screens is visible, so widgets open this activity. It is excluded from recents and finishes when the sheet closes.
- **`VoiceShortcutRepository`**: shortcuts as retained MQTT messages (§4), exposed as a `Flow<List<VoiceShortcut>>`, with `learn` and `forget`.
- **Availability**: Oikos's minSdk is 28 but the on-device recognizer needs **Android 12 (API 31)**, and `SpeechRecognizer.isOnDeviceRecognitionAvailable` must be true. Otherwise the switch is shown disabled with the reason. There is no fallback to the default recognizer, which may send audio to a server (§5).
- **Settings**:
  - **Commande vocale / Voice commands** switch. Turning it on asks for `RECORD_AUDIO`; a refusal leaves it off with an explanation.
  - **Raccourcis vocaux / Voice shortcuts** list: phrase → action label, broken ones flagged, tap to delete (with confirmation).
- **Voice widget**: a small widget with a mic button that opens `VoiceActivity`.

### 2.3 Changes to existing modules

- `:core:data`: `AppPreferences` gains `voiceEnabled` (default false).
- `:feature:home`: a mic button in the top bar when `voiceEnabled`, opening `VoiceActivity`.
- `:feature:widget`: the existing widget shows a mic button when `voiceEnabled`.
- `:core:source`: subscribe to and publish the shortcut topics (§4), following the pattern used for `oikos/ui/…` overrides.
- `:app`: wiring, and the `RECORD_AUDIO` permission in the manifest.

## 3. Behaviour

1. **Listening**: pulsing mic and live partial text. It ends on silence or on a tap (cancel).
2. **Understood**: the commands are sent at once, one per entity through `EntitySource.send`. The sheet shows "✓ Ouvrir · Volets Living (4)" and closes after about 2 s unless touched. **✗ Faux** opens the suggestions; picking one runs it and **replaces** the shortcut for that phrase. Oikos doesn't undo the wrong action; the correct one fixes it.
3. **Not understood**: "Tu voulais dire… ?" with the suggestions, **Autre…** (choose an action, then the compatible entities sorted by room, then a value of 0/25/50/75/100 when needed) and **Ignorer**. A pick runs and is learned.
4. **Confirm**: alarm and siren actions show "Désarmer l'alarme ?" with ✓ / ✗, and `CodeKeypadSheet` when the panel needs a code. This applies whatever the source (rules, shortcut or suggestion).

**Errors**
- MQTT not connected: "Pas de connexion à la maison"; nothing is sent.
- Recognizer or language pack unavailable: explained; a download is requested when possible.
- Silence or no match from the recognizer: "Je n'ai rien entendu" with retry.
- Permission revoked: voice turns itself off and Settings says why.
- Some entities reject or time out: the sheet lists them in red; the others stay done.

## 4. Shortcut storage

- Topic: `<prefix>/voice/shortcut/<id>`, **retained**, using the same prefix as Customize overrides. `<id>` is a stable hash of `lang + normalized phrase`, so teaching the same phrase again overwrites it, and two phones teaching different phrases never collide.
- Payload: `{"v":1,"phrase":"il fait sombre dans le salon","lang":"fr","action":"TURN_ON","entityIds":["…"],"value":null}`. The phrase is stored as said; the key uses the normalized form.
- Forget = an empty retained payload on that topic.
- Shortcuts for the other language are ignored, not deleted.
- An entity id that no longer exists is skipped. A shortcut with none left is listed as broken and never matches.
- The shortcuts are cached with the other retained messages in `EntityCache`, so they work at cold start.

## 5. Privacy

- Recognition runs on the phone (on-device recognizer, offline preferred). No audio is stored.
- Transcripts live only in memory for the current session.
- Only phrases the user chooses to teach are published, to the house's own broker.

## 6. Testing

- **`:core:voice` (JVM)**:
  - Rule-matcher suites in French and English; suggester; shortcut normalization; `CommandMapper` (each action × kind, incompatible pairs rejected).
  - Fixtures: a sanitized copy of the real house (names, rooms, kinds; fake ids) and `DemoHouse`.
  - A test prints the phrase → result coverage table for review.
- **`:feature:voice`**:
  - `VoiceSession` transitions with a fake recognizer and a fake `EntitySource`, including confirm, partial failure and permission loss.
  - Shortcut repository: decode/encode, forget, broken shortcuts, the other language ignored.
  - Robolectric screenshots of listening, understood, suggestions and confirm.
- **Device checklist (Pixel 6a)**:
  - Turning the switch on prompts for the microphone permission.
  - The mic works from Home, from the existing widget and from the voice widget.
  - "ouvre les volets du salon" moves the real shutters.
  - A taught phrase works on a second phone.
  - Airplane mode shows the error and sends nothing.
  - Demo mode works against `DemoHouse`.
