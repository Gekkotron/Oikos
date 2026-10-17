# Oikos — Voice answers and "Other" picker design

Status: draft, 2026-10-17. Extends `2026-10-10-oikos-voice-design.md` (voice v1, approved and merged b473d55). Builder-check done — five decisions settled below (§3.1 attributes table, §3.3 picker action scope, §3.1 `VoiceEntity.unit`, §3.1 `VoiceIntent.hint`, §4 multi-sensor-same-room aggregation).

## 1. Goals and scope

**Goals.**
1. Spoken answers for read-only queries. "Quelle est la température du salon ?" → the app says *"Il fait 22 degrés dans le salon"*. Same shape for humidity, battery, power, open/closed, on/off, lock state, climate current/target.
2. Plural / group queries. "Est-ce que les volets sont fermés ?" aggregates across every matching cover (optionally scoped to a room) and answers with a count: *"Oui, tous les volets sont fermés"* / *"Non, 2 volets sur 5 sont ouverts, 3 fermés"*.
3. The *Autre…* row in the not-understood sheet becomes a real two-step picker — searchable device list, then an action chooser scoped to that device's kind — with a *Mémoriser* chip that teaches the mapping so the same phrase goes straight through next time.

**Non-goals (v1).**
- No cloud TTS. Local Android `TextToSpeech` only; if the engine or the locale pack is missing we show the sentence as text and surface a hint in Settings.
- No LLM for answer composition. Sentences come from kind-specific templates (one per language per kind).
- No new wake-word work. Still tap-to-start.
- No changing climate target or vacuum settings from a query — read only. Those remain command verbs.
- No support for a third language in v1 (adding ES or IT drops in later; it's one new `VoiceLexicon.Table` + one new template set).
- No TTS voice selection UI. Default engine + default voice for the locale. Advanced users use Android system settings.

**Anti-goals (would make this a failure even if it technically ships).**
- A query that silently falls through to a control action ("ferme les volets" ↔ "est-ce que les volets sont fermés ?" must never close them).
- A picker whose list is just every entity in no order — unusable at 60+ devices.
- Answers that read a stale cached value as if it were live — the sentence must flag staleness ("… il y a 3 heures") or we don't speak at all.

## 2. User-visible decisions

- The query answer is a **full sentence**, spoken and shown as text in the sheet. Short answers only ("Oui, …" / "Non, …" / "Il fait …") — never a paragraph.
- When many sensors match (e.g. "quelle est la température" with no room), the sentence lists per-room: *"Salon 22, Cuisine 23, Bureau 21"* — no unit for brevity after the first.
- The picker lives inside the existing voice sheet, not a new screen. Back on step 2 returns to step 1; back on step 1 returns to the suggestions view.
- The *Mémoriser* chip is on the action step, not the device step: the pair saved is `heard phrase → VoiceIntent(action, [entityId])`, not just the entity, so retyping the phrase runs the same action.
- Sheet auto-close becomes **5 s** on `Spoken` (was 2 s on `Done`) — enough for the TTS to finish and the eye to read it; any tap on the sheet keeps it open as today.

## 3. Modules

### 3.1 `:core:voice` — pure Kotlin changes

- **`VoiceAction`** gains `QUERY_STATE`. No value, no code. It applies to every supported kind.
- **`VoiceLexicon.Verb`** gains `QUERY`. New vocabulary:
  - FR: `quelle, quel, quels, quelles, combien, affiche, affichez, dis, dis-moi, dites, donne, donnez, lit, lis, lire, montre, montrez, verifie, verifier`.
  - EN: `what, what's, how, how's, show, tell, read, give, check`.
  - Plus structural phrases (match before the per-word pass): FR `"est-ce que"`, `"est ce qu'"`, `"est ce qu"`, `"qu'est-ce que"`, `"qu'est ce que"`; EN `"is it"`, `"are the"`, `"are there"`, `"what is the"`, `"what are the"`. These rewrite to a synthetic `QUERY` verb token during normalization so the matcher doesn't have to inspect the whole sentence.
- **`VoiceLexicon.Table`** gains an `attributes: Map<String, String>` table (my call — closes a gap the builder-check caught). It maps attribute words to device-class concepts so queries like *"quelle est la température du salon"* resolve: without this, `RuleMatcher.parse()` would see `dev = {}, mods = {}` and bail. The initial seeding:
  - FR: `temperature, temperatures → temperature`; `humidite, humidites → humidity`; `batterie, batteries → battery`; `puissance → power`; `consommation → energy`; `tension → voltage`; `courant → current`; `luminosite, lux → illuminance`; `pression → pressure`; `niveau → level`; `etat, statut → state`.
  - EN: `temperature → temperature`; `humidity → humidity`; `battery → battery`; `power → power`; `energy → energy`; `voltage → voltage`; `current → current`; `illuminance, brightness level, lux → illuminance`; `pressure → pressure`; `level → level`; `state, status → state`.
  - `état / state / statut / status` is a generic marker that matches any kind without narrowing by device-class; the resolver still needs a room or device word to shortlist.
- **`RuleMatcher`** uses `attributes` the same way it uses `devices`: an attribute word fills a new `attrs: Set<String>` on `Parsed`. For `Verb.QUERY`, resolution narrows candidates by `attrs ⊆ deviceClass concept` *or* by `dev` (same as commands). Precedence: if a sentence has both an attribute (`température`) and a device word (`volet`), the attribute wins because queries by attribute are the common case.
- **`VoiceCatalogBuilder.SUPPORTED`** broadens to `LIGHT, SWITCH, COVER, ALARM_PANEL, SENSOR, BINARY_SENSOR, CLIMATE, VACUUM, WATER_HEATER, NUMBER`. The matcher's existing kind-gating in `actionFor` keeps control verbs on controllable kinds; `QUERY_STATE` is accepted on every kind. Entities with `partOf != null` or `ui.hidden` are still excluded (one voice entity per physical thing).
- **`VoiceEntity`** gains `val unit: String?` (my call — closes another builder-check gap). Sourced from `Entity.unit` in `VoiceCatalogBuilder.build`. Needed by `AnswerFormatter` for the `${u}` slot without reaching outside the catalog.
- **`VoiceIntent`** gains `val hint: QueryHint? = null` (my call), where `QueryHint = enum { EXPECT_ON, EXPECT_OFF, EXPECT_OPEN, EXPECT_CLOSED }`. Set by `RuleMatcher` when a `QUERY` phrase is paired with a state verb (`fermés → EXPECT_CLOSED`, `allumée → EXPECT_ON`, …). `null` on commands and on plain reads. Consumed only by `AnswerFormatter` for the Oui/Non lead; all other call-sites ignore it, so the API change is additive.
- **`RuleMatcher.actionFor`** gains a branch for `Verb.QUERY` → `VoiceAction.QUERY_STATE` for any `VoiceEntity`. Precedence: when a sentence yields both a `QUERY` verb and a state verb (e.g. "est-ce que les volets sont fermés" → `QUERY` + `CLOSE`), `QUERY` wins and the state verb is treated as a hint for the formatter (see §3.3). Plural handling already exists; the resolver returns every matching entity when the subject is plural or the sentence starts with a `QUERY` structural phrase.
- **`CommandMapper.map`** short-circuits for `QUERY_STATE`: it returns an empty list (nothing to send). Query execution reads state instead; see `VoiceCommandRunner.query` (§3.4).
- **`AnswerFormatter`** (new file, pure Kotlin). Input: `VoiceLang`, the resolved `VoiceIntent`, a snapshot `Map<String, EntityState>`, and the `VoiceCatalog`. Output: `Answer(spoken: String, display: String, isSpeakable: Boolean, staleIds: List<String>)`. One formatter per kind. See §4 for templates.
  - Group aggregation: when the intent targets more than one entity of the same kind, the formatter composes a group sentence (count-based or per-room, see §4). Mixed kinds in one intent (should be rare — only happens for the "all" fallback) format as a list.
  - Staleness: if any targeted entity's `EntityState.stale` is true, the sentence appends *"il y a N heures"* / *"N hours ago"* using `lastUpdate`. If `value is StateValue.Unknown` or `Unsupported` or `available = false`, that entity is reported as *"je n'ai pas la valeur de …"* / *"I don't have a reading for …"*.

### 3.2 `:feature:voice` — Android changes

- **`TtsSpeaker`** (new file). Thin wrapper around `android.speech.tts.TextToSpeech`:
  - Lazy init on first `speak`; `OnInitListener` sets the locale from `VoiceLang` and stores an availability status (`Ready`, `LanguageMissing`, `EngineMissing`).
  - `speak(text, flushQueue = true)` with `QUEUE_FLUSH` — a new answer replaces an older one, never queues.
  - `release()` on activity destroy.
  - Public `availability: StateFlow<TtsAvailability>` for Settings.
  - No runtime permission (TTS doesn't need one). If `isLanguageAvailable` returns `LANG_MISSING_DATA`, we trigger `ACTION_INSTALL_TTS_DATA` on user tap from Settings (not automatically from the voice flow).
- **`VoiceSessionState`** gains:
  - `Spoken(text: String, speakable: Boolean, stale: Boolean)` — replaces `Executing/Done` for `QUERY_STATE` intents. The sheet shows `text`, triggers `TtsSpeaker.speak(text)` once if `speakable`, and auto-closes after 5 s unless touched. A single *Répéter* / *Repeat* row replays the TTS.
  - `Picking(step: PickStep, filter: String, pickedId: String? = null)` with `PickStep.Device` / `PickStep.Action`.
- **`VoiceViewModel`** gains:
  - `onPickOther(heardText: String)` → transitions to `Picking(Device, filter = heardText)`.
  - `onPickDevice(id)` → `Picking(Action, filter, pickedId = id)`.
  - `onPickAction(action)` → builds a `VoiceIntent(action, [pickedId])`, runs it exactly like a matched one, and (if the *Mémoriser* chip is set) also calls `saveShortcut(phrase = originalHeardText, intent)`.
  - `onPickBack()` → `Action → Device`, `Device → NotUnderstood`.
- **`VoiceCommandRunner.query(intent, catalog)`** (new method). Snapshots `entitySource().states().first()`, filters to `intent.entityIds`, runs `AnswerFormatter.format(…)`, returns `QueryResult(text, speakable, stale)`. Called by `VoiceViewModel` when the resolved intent is `QUERY_STATE`.
- **`VoiceSheet`** router gets two new branches: `Spoken` (shows `text`, speaks it, offers *Répéter*) and `Picking` (routes to `DevicePickerView` or `ActionPickerView`).

### 3.3 UI — the Picker inside the sheet

- **`DevicePickerView`** (new). A search `TextField` pre-filled with the heard text, then a `LazyColumn` of entities grouped by `VoiceArea`:
  - Section headers = area display name, sorted the same way `:core:source` already sorts areas.
  - Each row: entity display name (bold) + kind label (muted) + a tiny state hint when available (On/Off, 22°, Ouvert) so the user can see at a glance what they'll land on.
  - Filtering: case-and-accent-folded contains match against entity name, area name, and the kind label (so typing "lum" matches lights, "temp" matches temperature sensors). Empty filter shows the whole list.
  - Max shown rows per screen: 50 (lazy-column, so scrolling the rest is free).
- **`ActionPickerView`** (new). Header shows the picked device name + area; body is a column of `VoiceRow`s, one per supported action, labels from `VoiceLexicon.actionLabel`. Scope in v1 (my call — the builder-check asked whether value- and code-requiring actions get picker rows):
  - For `SENSOR`, `BINARY_SENSOR`, `CLIMATE`, `VACUUM`, `WATER_HEATER`, `NUMBER`: a single row *Lire la valeur* / *Read value* → `QUERY_STATE`.
  - For `LIGHT`, `SWITCH`, `COVER`: *Lire l'état* / *Read state* as the first row, then the booleans (`TURN_ON/TURN_OFF`, `OPEN/CLOSE/STOP`). **`SET_POSITION` and `SET_BRIGHTNESS` are deliberately omitted from the picker in v1** — a picker row with a value would need a third slider step, and the natural way to set a value is already by voice ("mets le volet du salon à 30%"). Keeps the picker two steps.
  - For `ALARM_PANEL`: *Lire l'état* row plus `ARM_AWAY / ARM_HOME / DISARM`. Picking an arm/disarm row goes through the exact same flow a matched intent takes — `Confirmation.needsConfirmation` triggers `ConfirmNeeded` (keypad when `codeArmRequired` / `codeDisarmRequired`). No new confirmation path.
  - Below the actions: a small *Mémoriser cette phrase* / *Remember this phrase* toggle chip. Default on; off means run but don't teach.
  - Back row at the bottom returns to the device list.

### 3.4 Settings

- The existing voice toggle enables both STT and TTS; no new toggle.
- `VoiceSection` grows a second availability line below the STT one:
  - *Voix de réponse : disponible* / *…: pack de langue manquant* (with a *Télécharger* action launching `ACTION_INSTALL_TTS_DATA`) / *…: moteur non installé*.
  - When unavailable, queries still return the sentence as text (`speakable = false`).

### 3.5 Opt-in gate and permissions

- No new runtime permission (TTS needs none).
- The existing *mic* RECORD_AUDIO permission still covers STT. If voice is off, nothing from this spec is reachable.
- `OnTrimMemory` / activity destroy → `TtsSpeaker.release()`.

## 4. Answer templates (per kind, per language)

Each row is `kind → FR template | EN template`. `${v}` is the formatted value; `${u}` the unit (°, %, W, …) with sensible fallbacks when the entity has no `unit`; `${name}` the entity display name; `${room}` the area display name.

- **SENSOR, temperature (`deviceClass == "temperature"`):** `Il fait ${v}° dans le ${room}` | `It's ${v}° in the ${room}`. If the heard text names a device not a room: `${name} : ${v}°`.
- **SENSOR, humidity:** `L'humidité du ${room} est de ${v} %` | `Humidity in the ${room} is ${v} %`.
- **SENSOR, battery:** `Batterie de ${name} : ${v} %` | `${name} battery: ${v} %`.
- **SENSOR, power / energy / voltage / current / illuminance / pressure / … (fallback):** `${name} : ${v} ${u}` | `${name}: ${v} ${u}`.
- **BINARY_SENSOR, door / window / opening (deviceClass):** `La porte du ${room} est ${ouverte|fermée}` (gender from the device-class noun) | `The door in the ${room} is ${open|closed}`. For a plain binary sensor without a class: `${name} : ${actif|inactif}` | `${name}: ${on|off}`.
- **LIGHT:** `La lumière du ${room} est ${allumée|éteinte}` (+ `, luminosité ${pct} %` when `brightness != null`) | `The ${room} light is ${on|off}` (+ `, brightness ${pct} %`). Named: `${name} est …`.
- **SWITCH:** `${name} : ${allumé|éteint}` | `${name}: ${on|off}`.
- **COVER:** `Le volet du ${room} est ${ouvert|fermé|partiellement ouvert (${pos} %)|en cours d'ouverture|en cours de fermeture|arrêté}` | `The ${room} shutter is ${open|closed|partially open (${pos} %)|opening|closing|stopped}`. `partiellement ouvert` when `state == OPEN && position in 1..99`.
- **CLIMATE:** `${room} : ${v}° actuel, consigne ${target}°, mode ${mode}` | `${room}: ${v}° now, target ${target}°, mode ${mode}` (skip the missing fields).
- **VACUUM:** `${name} : ${state}, batterie ${battery} %` | `${name}: ${state}, battery ${battery} %`.
- **WATER_HEATER:** same shape as CLIMATE (current/target/mode).
- **ALARM_PANEL:** `L'alarme est ${state_fr}` | `The alarm is ${state_en}` (state localized from `AlarmState`).
- **NUMBER:** `${name} : ${v} ${u}` | `${name}: ${v} ${u}`.

**Group aggregation (same kind, more than one entity):**
- COVER / BINARY_SENSOR / LIGHT / SWITCH boolean-ish states:
  - All match → `Oui, tous les ${pluralKind} sont ${state}` | `Yes, all the ${pluralKind} are ${state}`.
  - None match → `Non, aucun ${pluralKind} n'est ${state}` | `No, none of the ${pluralKind} are ${state}`.
  - Mixed → `${k} ${pluralKind} sur ${n} sont ${state1}, ${n-k} ${state2}` | `${k} of ${n} ${pluralKind} are ${state1}, ${n-k} ${state2}`.
- SENSOR numeric:
  - **Same room, same device-class (my call — closes the "3 temperature sensors in the salon" gap the builder-check raised):**
    - **Continuous** classes (`temperature, humidity, pressure, illuminance`): average the values and lead with *"en moyenne"* when `n > 1`. Example: `Il fait 22° en moyenne dans le salon` (avg of 21/22/23). When one entity's name is just the device-class noun (`"Température"`, `"Humidité"`) or matches `${room} ${class}`, prefer that one as the ambient reading and skip the average.
    - **Discrete** classes (`power, energy, voltage, current, battery, …`): list by entity name, no average: `Capteur frigo : 42 W, Capteur TV : 85 W`. These can't be meaningfully averaged.
  - **Across rooms** (no room scoping, several rooms have the class): `${room1} : ${v1}, ${room2} : ${v2}, …` (unit only after the first). Each room first collapses by the same single-room rule above.
- Mixed kinds (unlikely): fall back to listing each entity's single-entity sentence, separated by `; `.

**State verb hint.** When the sentence has both a `QUERY` structural phrase and a state verb (e.g. "est-ce que les volets sont **fermés**"), the formatter uses the `state` of the verb to pick the Yes/No framing: `${state_match_count} / ${total}` and leads the sentence with *Oui* when all match or *Non* when none/some don't. If no state verb, we skip the Yes/No lead.

**Staleness.** When any targeted entity is stale, append `(mise à jour il y a ${durationFr})` | `(updated ${durationEn} ago)` to the whole sentence once.

## 5. Shortcut teaching for queries

The same `VoiceShortcut` schema as v1 covers queries: a phrase → `VoiceIntent(QUERY_STATE, [entityId])`. When teaching from the picker, we store the normalized `heardText` as the phrase and the chosen entity id. The shortcut matcher already prefers shortcuts over rules, so the next time the user says the same thing it bypasses the rules entirely.

Edge: a query shortcut to a sensor that's since been removed falls back to broken-shortcut handling (already in v1 via `ShortcutRow.broken`), and Settings → Voice → Shortcuts offers deletion as before.

## 6. State the picker lets you reach without voice

The picker's device list is every voice-addressable entity (same scope as `VoiceCatalogBuilder`). The action chooser surfaces exactly what the entity supports — not a flat matrix. That means a user who can't remember the right phrase has a tap-only path to every voice-reachable action; a successful tap through the picker is also tap-to-teach (unless the user turns the chip off). This is the "search input to find the right device to control or query" the user asked for.

## 7. Risks and open questions

- **TTS pack availability.** fr-FR is pre-installed on most Pixels; on cheap OEM phones it may be missing. Mitigation: show text in the sheet regardless of TTS state; Settings shows the status and the one-tap *Télécharger*.
- **Noisy TTS at night.** No volume control from the sheet in v1; relies on the system media/accessibility stream. A future *silencieux la nuit* option is possible but not here.
- **"Fermés" ambiguity.** "Ferme les volets" (close) vs. "les volets sont fermés" (query). Rule in §3.1 — `QUERY` structural phrase beats state verb — resolves it. Tested with the examples the user gave; adversarial phrases will be collected via the Settings → Shortcuts list in production.
- **Big houses.** 60–200 entities in the picker. LazyColumn + search handles it, but no fuzzy / ranking yet (contains match only). If users complain, add Levenshtein on the filter.
- **Multi-value queries.** "Quelle température et humidité dans le salon ?" — v1 picks the first matched kind (the one whose device word was heard first) and ignores the rest. Future improvement, not v1.

## 8. Test shape

- `AnswerFormatterTest` (JVM, pure): a table of (lang, intent, states) → expected sentence. Covers every kind in §4, group aggregation, staleness, unavailable.
- `RuleMatcherQueryTest` (JVM): the structural phrases, the `QUERY` precedence over state verbs, plural / group targeting, room scoping.
- `TtsSpeakerTest` (JVM via a `TextToSpeech` test double behind an interface): init callback paths (`Ready` / `LanguageMissing` / `EngineMissing`), queue flush on new `speak`.
- `VoiceViewModelTest` (JVM, existing Turbine style): `QUERY_STATE` resolution produces a `Spoken` state with the formatter's sentence and no commands sent; picker transitions `NotUnderstood → Picking(Device) → Picking(Action) → Spoken/Done` and the teach chip saves a shortcut.
- `NotUnderstoodViewScreenshotTest`, `DevicePickerViewScreenshotTest`, `ActionPickerViewScreenshotTest` — the usual Oikos screenshot tests (Robolectric Compose) in light + dark.

## 9. Delivery

Branch name: `feat/voice-answers`. Normal Oikos workflow (plan via `superpowers:writing-plans` after this doc is approved, then subagent-driven implementation, Gekkotron identity, push to `master`, watch CI + Firebase, device check).

Device checklist (post-merge):
1. Voice toggle on; Settings shows "Voix de réponse : disponible".
2. "Quelle est la température du salon ?" → spoken + text.
3. "Est-ce que les volets sont fermés ?" (group) → aggregated answer.
4. "Allume la guirlande" (unknown device) → tap *Autre…* → search "guir" → pick → *Allumer* → runs + offers to remember.
5. Same phrase repeated → runs straight through (shortcut).
6. Turn system TTS pack off → sentence shown as text, Settings surfaces *Télécharger*.
