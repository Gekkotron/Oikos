# Oikos — Localized alerts design

Status: approved design, 2026-10-09. Extends the push alerts spec (`2026-10-07-oikos-push-alerts-design.md`, payload v1) across three repos: OikosPush (service), OikosPush/bridges/frigate, and the Oikos app.

## 1. Goal and scope

**Goal.** Alerts read in the phone's (or the app's per-app) language. Today the Frigate bridge writes English text ("Car in Driveway") and the app shows it verbatim, so a French phone reads English. After this, a French phone reads "Voiture dans la zone Driveway" and an English one "Car in Driveway", from the same alert.

**User decisions**
- A **generic** mechanism: the payload carries a sentence key plus arguments (`loc`); only Frigate keys are defined now, other sources can add keys later without new plumbing.
- About **20 common Frigate labels** are translated; anything else falls back to the server's English `title`/`body` (never a half-translated sentence).
- The text is composed **at display time** from data stored with the alert, so a language change re-translates the history.

**Out of scope**
- Translating user-made names (camera, zone, recognised person): they are shown as written.
- Other sources' keys (Jeedom, alarm, energy…), more languages than English and French, re-writing notifications already on screen.

## 2. Payload (v1, additive)

New optional field:

```json
"loc": { "key": "frigate.object", "args": { "label": "car", "zone": "Driveway", "who": "Julien" } }
```

- `key`: `[a-z0-9_.]+`, 1–64 characters; names a sentence the app knows.
- `args`: object of string → string, at most 8 entries, keys `[a-z0-9_]+` (≤ 32 chars), values ≤ 200 characters. Raw words the app translates stay raw (`label: "car"`); user-made names are sent already formatted.
- `title` stays **required** and `body` keeps today's meaning: they are the fallback for older apps, unknown keys and unknown labels.
- Legacy-format payloads never carry `loc`.

## 3. OikosPush service

- `Alert` (normalize.py) gains `loc: dict | None`.
- v1 normalisation validates `loc` against §2. An invalid `loc` is **dropped with a warning log; the alert is still delivered**.
- FCM data map: one extra entry `loc` = compact JSON string (`separators=(",", ":")`), only when present.
- The retained `oikos-push/history` buffer stores `loc` with the alert, so offline catch-up keeps it.
- Size: `loc` adds a few hundred bytes, far under FCM's 4 KB data limit.

## 4. Frigate bridge

`Bridge.alert()` keeps building today's English `title`/`body` unchanged and adds:

```python
"loc": {"key": "frigate.object", "args": {"label": <raw Frigate label>, "zone": <first matching zone, prettified>, "who": <sub_label>}}
```

`zone` and `who` are omitted when absent. `label` is the raw, lowercase Frigate label (e.g. `car`, `license_plate`); a missing label is not sent (no `loc`).

## 5. App

### 5.1 Model and storage (`:core:alerts`)
- `data class AlertLoc(val key: String, val args: Map<String, String>)`; `Alert.loc: AlertLoc?`.
- `AlertParser` reads `data["loc"]` leniently: malformed JSON, wrong types or a missing key → `null`; the alert itself is kept.
- Room `AlertDatabase` v1 → v2: nullable `loc` TEXT column holding the JSON, via `@AutoMigration(from = 1, to = 2)` (schemas are exported). Existing rows get `null`.

### 5.2 Composing the text
`AlertText.body(alert: Alert, resources: Resources): String`:
- known `loc.key` with its required args recognised → the localized sentence;
- otherwise (no `loc`, unknown key, unknown label, missing `label`) → `alert.body`.
- It reads `:core:alerts` string resources in the given configuration; tests run it under Robolectric with `en` and `fr` qualifiers (amended 2026-10-09: no separate `AlertStrings` interface).
- The title is unchanged (the server's camera name).

### 5.3 `frigate.object`

| Args present | English | French |
|---|---|---|
| label | Car detected | Voiture détectée |
| label + zone | Car in Driveway | Voiture dans la zone Driveway |
| label + who | Julien (person) | Julien (personne) |
| label + who + zone | Julien (person) in Driveway | Julien (personne) dans la zone Driveway |

Each label has **two strings**: the noun and its "detected" sentence, so French agreement (détecté/détectée) is in the translation, not in code. Inside parentheses the noun is lower-cased with the current locale.

Labels (raw Frigate names): `person`, `car`, `truck`, `bus`, `motorcycle`, `bicycle`, `dog`, `cat`, `bird`, `horse`, `package`, `face`, `license_plate`, `amazon`, `dhl`, `dpd`, `fedex`, `gls`, `ups`, `usps`.

French nouns: Personne, Voiture, Camion, Bus, Moto, Vélo, Chien, Chat, Oiseau, Cheval, Colis, Visage, Plaque d'immatriculation; delivery brands keep their name (Amazon, DHL, DPD, FedEx, GLS, UPS, USPS). "Detected" forms agree with the noun (Voiture détectée, Camion détecté, Amazon détecté…).

### 5.4 Where it is used
`AlertNotifier` (content text, big-text and big-picture summary), `AlertRow` (Recent alerts and the Alerts screen) and `AlertDetailSheet` call `AlertText.body`. A notification already posted keeps the language it was posted in.

## 6. Rollout
Every step is backward compatible; the order only decides when French appears:
1. OikosPush: accept, forward and store `loc`; redeploy on the Geekom.
2. App: parse, store and display `loc`; ships through App Distribution.
3. Frigate bridge: send `loc`; redeploy.

## 7. Edge cases
- Unknown label spelling or case → fallback to `body`.
- Unknown extra args are ignored.
- `cancel` payloads carry no `loc`.
- An arg value over 200 characters makes OikosPush drop the whole `loc` (the alert falls back to its English `body`); names are not truncated (amended 2026-10-09).

## 8. Testing
- **OikosPush (pytest):** valid `loc` kept; each invalid shape dropped while the alert is still delivered; `loc` present in the FCM data map as compact JSON and in the history buffer; legacy payloads have none.
- **Bridge (pytest):** `loc` for label only, with zone, with who, with both; no `loc` without a label; `title`/`body` unchanged.
- **App unit:** parser (valid, malformed, missing key, non-string args); `AlertText` for every row of §5.3 in English and French (Robolectric `fr` qualifier) plus fallback for unknown key, unknown label and missing label; every label in §5.3 has both strings in both languages; Room v1 → v2 migration test from the exported schema.
- **App UI:** `AlertRow` shows the French sentence under the `fr` locale.
- **Device:** a car at the Driveway camera reads "Voiture dans la zone Driveway" on a French phone; switching Oikos to English in Android settings shows "Car in Driveway" in the Alerts screen.
