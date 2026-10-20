# Oikos-os Compatible Water Heater View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app decodes the WaterHeaterController discovery that Oikos-os uses and shows the Oikos-os water heater card: a tank glyph that glows while heating, the tank temperature, an activity chip and the inlet line.

**Architecture:** Four layers, bottom-up. (1) The `core/state` template engine gains arithmetic, ordering comparisons, dict literals and `tojson`. (2) `StateStore` applies `json_attributes_template`, and water heaters with `modes: []` keep their reported mode. (3) A pure `HeaterView` in `feature/home` presentation, ported from Oikos-os `HeaterView::from_state`, drives `CardUi.on` and the card. (4) The Compose card, sheet, strings, demo house and screenshots.

**Tech Stack:** Kotlin (KMP `commonMain` for the template engine, JVM for `StateStore`), Jetpack Compose + Material 3, Robolectric screenshot tests, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-20-oikos-water-heater-view-design.md` (read it first; Appendix A is the discovery message, Appendix B the sample payload). Reference implementation: `~/superconductor/projects/Oikos-os` commit `8522fe6`, file `crates/oikos-web/src/cards/water_heater.rs`.

## Global Constraints

- Oikos-os wins on behaviour disagreements (spec §Intent).
- The Jeedom heater must not regress: Force switch, eco/performance chips, target stepper and the `Force power` companion (spec §Anti-goals).
- A template the engine cannot evaluate still fails through `TemplateResult.Unsupported`/`Error` and the existing diagnostics, never as a silent wrong value.
- Card highlight = `HeaterView.heating`, kept in one place so it can be retuned (spec §Decided).
- English + French strings in `feature/home/src/main/res/values{,-fr}/strings.xml`.
- Commit identity on every commit: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`, message ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage only the files the task touches. The working tree has unrelated uncommitted launcher-icon changes and `todos.md` — never stage those.
- Test commands: `./gradlew :core:state:jvmTest`, `./gradlew :core:source:jvmTest`, `./gradlew :core:demo:test`, `./gradlew :feature:home:testDebugUnitTest`, `./gradlew :feature:widget:testDebugUnitTest`.

## Review Focus

1. **Subtraction vs negative literals:** `{{ 0.35 - 1 }}`, `{{ value_json.a -1 }}` and `{{ (1 - 2) }}` must subtract. `{{ -1 }}` and `{{ x | default(-1) }}` must stay negative literals. Tests in Task 1.
2. **Precedence with filters:** `{{ 1 + 2 | round }}` filters only `2` (Jinja), while `{{ (1 + 2.6) | round }}` gives `4.0`. Test in Task 1.
3. **A broken attributes template must not wipe good attributes:** a render error or a non-object result keeps the previous attributes and reports once. Test in Task 3.
4. **Integer vs float in attributes:** `power` from `| round | int` must arrive as a `Long` (`43`), and `inlet_temperature` from `round(1)` as a `Double` (`17.9`). `HeaterView` must accept both `Long` and `Double` for both fields. Tests in Tasks 2 and 5.
5. **Jeedom heater before its first state:** with no target the sheet hides the stepper, but the mode chips still show and Force still works. Tests in Tasks 5 and 7.

---

### Task 1: Template arithmetic and ordering comparisons

**Files:**
- Modify: `core/state/src/commonMain/kotlin/com/gekkotron/oikos/state/template/TemplateParser.kt`
- Modify: `core/state/src/commonMain/kotlin/com/gekkotron/oikos/state/template/Templates.kt`
- Test: `core/state/src/commonTest/kotlin/com/gekkotron/oikos/state/template/TemplatesTest.kt`

**Interfaces:**
- Produces: `Expression.Arithmetic(left: Expression, op: Char, right: Expression)` with `op` in `+ - * /`, and `Expression.Compare(left: Expression, op: String, right: Expression)` with `op` in `> < >= <=`. Grammar (lowest → highest): inline if < or < and < not < comparison (`== != > < >= <= in is`) < additive (`+ -`) < multiplicative (`* /`) < filter < postfix. So `parseComparison` calls a new `parseAdditive`, which calls `parseMultiplicative`, which calls `parseFiltered`.

- [ ] **Step 1: Write the failing tests** in `TemplatesTest`:

```kotlin
@Test
fun `arithmetic follows precedence and true division`() {
    assertEquals(7L, v("{{ 1 + 2 * 3 }}", "{}"))
    assertEquals(9L, v("{{ (1 + 2) * 3 }}", "{}"))
    assertEquals(0.5, v("{{ 1 / 2 }}", "{}"))
    assertEquals(2.0, v("{{ 4 / 2 }}", "{}"))
    assertEquals(-0.65, v("{{ 0.35 - 1 }}", "{}") as Double, 1e-9)
    assertEquals(4L, v("{{ value_json.a -1 }}", """{"a":5}"""))
    assertEquals(-1L, v("{{ (1 - 2) }}", "{}"))
    assertEquals(-1L, v("{{ -1 }}", "{}"))
    assertEquals(-1L, v("{{ value_json.nope | default(-1) }}", "{}"))
    assertEquals("ab", v("{{ 'a' + 'b' }}", "{}"))
}

@Test
fun `filters bind tighter than arithmetic`() {
    assertEquals(3.0, v("{{ 1 + 2.4 | round }}", "{}"))
    assertEquals(4.0, v("{{ (1 + 2.6) | round }}", "{}"))
}

@Test
fun `ordering comparisons`() {
    assertEquals(true, v("{{ 2 > 1 }}", "{}"))
    assertEquals(false, v("{{ 2 < 1 }}", "{}"))
    assertEquals(true, v("{{ 2 >= 2.0 }}", "{}"))
    assertEquals(true, v("{{ 1 <= 2 }}", "{}"))
    assertEquals("heat", v("""{{ "heat" if (value_json.a + value_json.b) > 0 else "off" }}""", """{"a":0,"b":3}"""))
    assertEquals("off", v("""{{ "heat" if (value_json.a + value_json.b) > 0 else "off" }}""", """{"a":0,"b":0}"""))
}

@Test
fun `arithmetic on non-numbers is an error`() {
    assertIs<TemplateResult.Error>(Templates.renderValue("{{ value_json.s * 2 }}", """{"s":"x"}"""))
    assertIs<TemplateResult.Error>(Templates.renderValue("{{ value_json.nope + 1 }}", "{}"))
}
```

Note: `1 + 2.4 | round` is `1 + 2.0` = `3.0` (Long + Double → Double).

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :core:state:jvmTest --tests '*TemplatesTest*'`
Expected: FAIL with `UnsupportedTemplate` ("unsupported syntax '+'" or similar).

- [ ] **Step 3: Implement it**

- Tokenizer: emit `Token.Op` for `+ * /` and for `>`, `<`, `>=`, `<=`. `-` lexes as part of a number literal only when the previous token is absent, a `Token.Op`, or a `Token.Sym` in `( , [ | :`. Otherwise it is `Token.Op("-")`.
- Evaluator: `Long op Long` stays `Long` for `+ - *`. Any `Double` operand gives a `Double`. `/` always gives a `Double`. `String + String` concatenates. Operands are coerced with `looseNumber`. `Undefined`, null or a non-numeric operand throws `TemplateEvalError("cannot apply 'op' to …")`. Ordering compares numbers via `toDouble()`; non-numbers throw `TemplateEvalError`.

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `./gradlew :core:state:jvmTest`
Expected: PASS, including every existing `TemplatesTest`.

- [ ] **Step 5: Commit** — `feat(template): arithmetic and ordering comparisons`

---

### Task 2: Dict literals, `tojson`, and the Appendix A templates end to end

**Files:**
- Modify: `TemplateParser.kt`, `Templates.kt` (same package as Task 1)
- Test: `TemplatesTest.kt`

**Interfaces:**
- Consumes: Task 1 grammar.
- Produces: `Expression.DictLiteral(entries: List<Pair<String, Expression>>)`, which evaluates to a `Map<String, Any?>` (insertion-ordered). The `tojson` filter is added to `TemplateParser.FILTERS`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `dict literal with tojson`() {
    assertEquals("""{"a":1,"b":"x"}""", v("""{{ {"a": 1, "b": "x"} | tojson }}""", "{}"))
    assertEquals("""{"t":17.9,"p":43}""", v("""{{ {"t": value_json.t | round(1), "p": (value_json.p / 3) | round | int} | tojson }}""", """{"t":17.94,"p":129}"""))
}

@Test
fun `tojson serializes scalars, null and booleans`() {
    assertEquals("\"a\"", v("{{ 'a' | tojson }}", "{}"))
    assertEquals("1.5", v("{{ 1.5 | tojson }}", "{}"))
    assertEquals("true", v("{{ true | tojson }}", "{}"))
    assertEquals("null", v("{{ none | tojson }}", "{}"))
}

// Appendix B payload; the templates are copied verbatim from spec Appendix A.
private val heaterData = """{"adc_result_mv":604,"triac_0_power":40,"triac_1_power":45,"triac_2_power":44,"temp_0":17.94,"temp_1":41.2,"measuredFrequency":50.02,"frequencyError":0}"""

@Test
fun `WaterHeaterController templates render`() {
    assertEquals(58.0, v("{{ (80 + (0.35 - value_json.adc_result_mv / 1000) * 86.67) | round(1) }}", heaterData))
    assertEquals("heat", v("""{{ "heat" if (value_json.triac_0_power + value_json.triac_1_power + value_json.triac_2_power) > 0 else "off" }}""", heaterData))
    assertEquals(
        """{"inlet_temperature":17.9,"power":43}""",
        v("""{{ {"inlet_temperature": value_json.temp_0 | round(1), "power": ((value_json.triac_0_power + value_json.triac_1_power + value_json.triac_2_power) / 3) | round | int} | tojson }}""", heaterData),
    )
    assertEquals("""{"power0": 40}""", v("""{"power0": {{ value | int }}}""", "40"))
}
```

Copy the Appendix A templates verbatim from the spec: un-escape `\"` to `"` exactly once and change nothing else.

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :core:state:jvmTest --tests '*TemplatesTest*'`
Expected: FAIL ("unsupported syntax '{'" / "filter 'tojson' is not supported").

- [ ] **Step 3: Implement it**

- Tokenize `{ } :` as `Token.Sym`. In `parsePrimary`, `{` starts a dict: string-literal keys only (anything else is `UnsupportedTemplate`), `key: parseInlineIf()` entries separated by `,`, closed by `}`.
- `tojson` uses kotlinx `JsonElement` building: Map → `JsonObject`, List/`JsonArray` → array, String → string, Long/Double → number, Boolean → bool, null → `null`. `Undefined` throws `TemplateEvalError`. Output is compact (no spaces).
- **`}}` scanning (spec §1, builder's call):** either make `TemplateParser.parse` skip `}}` while inside a dict (track brace depth outside string literals), or add a test that pins the limit: a nested dict ending in `}}` returns `TemplateResult.Unsupported`. Pick one and write its test.

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `./gradlew :core:state:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit** — `feat(template): dict literals and tojson`

---

### Task 3: `json_attributes_template` in `StateStore`

**Files:**
- Modify: `core/state/src/jvmMain/kotlin/com/gekkotron/oikos/state/StateStore.kt:177-186`
- Test: `core/state/src/jvmTest/kotlin/com/gekkotron/oikos/state/StateStoreTest.kt`

**Interfaces:**
- Consumes: `Templates.renderValue(template, payload): TemplateResult` and the `tojson` filter from Task 2.
- Produces: `EntityState.attributes` holding the rendered object, with normalized values (`Long`/`Double`/`String`/`Boolean`/null).

- [ ] **Step 1: Write the failing tests** (follow the existing `json attributes are parsed` test's setup at line 139):

```kotlin
@Test
fun `json attributes template reshapes the payload`() {
    // entity with json_attributes_topic "s/attr" and
    // json_attributes_template """{{ {"power": value_json.p | int} | tojson }}"""
    store.onMessage("s/attr", """{"p":43.2,"noise":1}""")
    assertEquals(mapOf("power" to 43L), attributesOf("l"))
}

@Test
fun `a failing attributes template keeps the previous attributes and reports once`() {
    // template """{{ {"power": value_json.p * 2} | tojson }}"""
    store.onMessage("s/attr", """{"p":2}""")
    store.onMessage("s/attr", """{"p":"x"}""")
    store.onMessage("s/attr", """{"p":"y"}""")
    assertEquals(mapOf("power" to 4L), attributesOf("l"))
    assertEquals(1, diagnostics.count { "attributes" in it.message })
}

@Test
fun `an attributes template that renders a non-object is ignored`() {
    // template "{{ value_json.p }}"
    store.onMessage("s/attr", """{"p":2}""")
    assertEquals(emptyMap(), attributesOf("l"))
}
```

Use whatever store/diagnostics fixtures the file already has; add a recording `DiagnosticSink` if there isn't one.

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :core:state:jvmTest --tests '*StateStoreTest*'`
Expected: FAIL (the raw payload lands as attributes).

- [ ] **Step 3: Implement it**

`parseAttributes(t: Tracked, id: String, payload: String)`:
- Read `json_attributes_template` with `bindings.str`.
- If it is present, render it with `Templates.renderValue`. A `Value` whose value is a `String` is parsed as a JSON object. A `Value` holding a `JsonObject` or a `Map` is used directly.
- Anything else returns null and calls `reportOnce(id, "attributes", "cannot read attributes: …")`.
- With no template, behaviour is unchanged.

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `./gradlew :core:state:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit** — `feat(state): apply json_attributes_template`

---

### Task 4: Keep the reported mode when `modes` is empty, and parse Appendix A

**Files:**
- Modify: `core/state/src/commonMain/kotlin/com/gekkotron/oikos/state/kinds/ThermostatBase.kt:24-56`
- Modify: `core/state/src/commonMain/kotlin/com/gekkotron/oikos/state/kinds/WaterHeaterMapper.kt`
- Test: `core/state/src/commonTest/kotlin/com/gekkotron/oikos/state/kinds/WaterHeaterMapperTest.kt`
- Test: `core/source/src/jvmTest/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`

**Interfaces:**
- Produces: `ThermostatBase.decode(b, topic, payload, previous, modes: List<String>?)`, where `null` means accept any non-blank mode. `WaterHeaterMapper.decode` passes `modes(b).ifEmpty { null }`. `ClimateMapper` keeps passing its list. Encoding still uses `modes(b)`, so an empty list keeps `SetHvacMode`/`TurnOff` `NotSupported`.

- [ ] **Step 1: Write the failing tests**

`WaterHeaterMapperTest` (Appendix A heater component bindings, expanded: `modes: []`, `current_temperature_topic`, `mode_state_topic` = `water-heater/data`, both templates verbatim):

```kotlin
@Test
fun `empty modes keep the reported mode and cannot be commanded`() {
    val decoded = WaterHeaterMapper.decode(heater, "water-heater/data", heaterData, StateValue.Unknown)
    assertEquals(StateValue.Climate("heat", null, 58.0), (decoded as Decoded.Value).value)  // match the Decoded shape the file already asserts on
    assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(heater, Command.SetHvacMode("heat"), StateValue.Unknown))
    assertIs<Encoded.NotSupported>(WaterHeaterMapper.encode(heater, Command.TurnOff, StateValue.Unknown))
}

@Test
fun `absent modes still filter against the defaults`() {
    // bindings without "modes", mode template yielding "heat" → mode stays null
}
```

`DiscoveryParserTest`:

```kotlin
@Test
fun `WaterHeaterController device discovery parses into one device`() {
    // topic "oikos/device/water_heater/config", payload = spec Appendix A verbatim
    // assert: one WATER_HEATER (uniqueId "water_heater", hvacModes empty), sensors
    // water_heater_tank_temp / water_heater_temp_0 / water_heater_temp_1 / water_heater_frequency,
    // numbers water_heater_power_0..2, binary_sensor water_heater_frequency_error,
    // all with the same deviceId; the heater's bindings carry json_attributes_topic
    // "water-heater/data" and json_attributes_template.
}
```

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :core:state:jvmTest :core:source:jvmTest`
Expected: the mapper test FAILS (mode null). The discovery test may already pass; if so, keep it as the regression guard.

- [ ] **Step 3: Implement the nullable `modes` in `ThermostatBase.decode` and the `ifEmpty { null }` in `WaterHeaterMapper.decode`**

Update the KDoc on `WaterHeaterMapper` to describe the empty-modes rule.

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `./gradlew :core:state:jvmTest :core:source:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit** — `feat(water-heater): keep the reported mode when modes is empty`

---

### Task 5: `HeaterView` and the card highlight

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HeaterView.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt` (`CardUi`)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt` (`card()`, `isOn`)
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HeaterViewTest.kt` (new)
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderWaterHeaterTest.kt`

**Interfaces:**
- Produces:

```kotlin
/** Oikos-os `HeaterView::from_state` (crates/oikos-web/src/cards/water_heater.rs). Card highlight rule lives here only. */
data class HeaterView(val heating: Boolean, val power: Int?, val inlet: Double?, val chip: HeaterChip?) {
    companion object { fun of(state: EntityState?): HeaterView }
}
sealed interface HeaterChip {
    data class Heating(val power: Int) : HeaterChip
    data object Idle : HeaterChip
    data object Off : HeaterChip
    data class Mode(val mode: String) : HeaterChip
}
```

- `CardUi` gains `val heater: HeaterView? = null`, set only for `Kind.WATER_HEATER`. For water heaters, `on = heater.heating`.

- [ ] **Step 1: Write the failing tests.** `HeaterViewTest` ports the eight Oikos-os tests one for one. Use `EntityState("wh", StateValue.Climate(mode, null, 58.5), attributes = …)` and match `StateValue.Climate`'s actual parameter order. Then add:

```kotlin
@Test fun `power 43 heats`() = assertEquals(HeaterView(true, 43, 17.9, HeaterChip.Heating(43)), of("heat", mapOf("inlet_temperature" to 17.9, "power" to 43L)))
@Test fun `power zero is idle even in heat mode`() = assertEquals(HeaterView(false, 0, null, HeaterChip.Idle), of("heat", mapOf("power" to 0L)))
@Test fun `without power the mode decides`() = assertEquals(HeaterView(true, null, null, HeaterChip.Mode("eco")), of("eco", emptyMap()))
@Test fun `mode off without power is off`() = assertEquals(HeaterView(false, null, null, HeaterChip.Off), of("OFF", emptyMap()))
@Test fun `no mode and no power has no chip`() = assertEquals(HeaterView(false, null, null, null), of(null, emptyMap()))
@Test fun `non-numeric attributes are ignored`() = assertEquals(HeaterView(false, null, null, HeaterChip.Off), of("off", mapOf("power" to "43", "inlet_temperature" to null)))
@Test fun `inlet accepts an integer`() = assertEquals(12.0, of(null, mapOf("inlet_temperature" to 12L)).inlet)
@Test fun `power is clamped`() { assertEquals(100, of(null, mapOf("power" to 140.4)).power); assertEquals(false, of(null, mapOf("power" to -5L)).heating) }
@Test fun `NaN power is ignored`() = assertEquals(null, of(null, mapOf("power" to Double.NaN)).power)
@Test fun `null state has no chip`() = assertEquals(HeaterView(false, null, null, null), HeaterView.of(null))
```

In `HomeUiBuilderWaterHeaterTest`, replace `on is true only in performance` with:

```kotlin
@Test
fun `on follows heating`() {
    assertTrue(build("performance").on)
    assertTrue(build("eco").on)
    assertEquals(false, build("off").on)
}

@Test
fun `card carries the heater view`() {
    assertEquals(HeaterChip.Mode("eco"), build("eco").heater?.chip)
}

@Test
fun `force toggle is unchanged`() {
    assertEquals(true, build("performance").forceToggle)
    assertEquals(false, build("eco").forceToggle)
    assertNull(build("heat", modes = emptyList()).forceToggle)
}
```

Keep every other existing test in that file.

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HeaterView*' --tests '*WaterHeater*'`
Expected: FAIL (compilation: `HeaterView` undefined).

- [ ] **Step 3: Implement `HeaterView.of` and wire `card()`/`isOn`**

Rounding must match Rust `f64::round` (half away from zero): clamp to 0..100 first, then `roundToInt()`, which is the same as half away from zero for non-negative values. Do not use `kotlin.math.round` (half-even). Mode `off` is matched case-insensitively. If `feature/widget` or other exhaustive `when`s need it, nothing changes there (no new `Kind`).

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit** — `feat(home): water heater view model ported from Oikos-os`

---

### Task 6: The water heater card

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/WaterHeaterCard.kt` (tank glyph + card body)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/CardText.kt` (water heater value text; remove `waterHeaterSubtitle`)
- Modify: `feature/home/src/main/res/values/strings.xml`, `values-fr/strings.xml`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WaterHeaterCardTest.kt` (new, Robolectric Compose)

**Interfaces:**
- Consumes: `CardUi.heater: HeaterView?`, `HeaterChip` (Task 5), `waterHeaterModeLabel(mode)` (CardText.kt).
- Produces: `@Composable internal fun WaterTankGlyph(heating: Boolean, modifier: Modifier = Modifier)` and `@Composable internal fun heaterChipText(chip: HeaterChip): String`. The water heater card shares `EntityCard`'s frame: `EntityCard` branches its body on `card.heater != null` and keeps one `OikosCard`, the Force switch and the pending dot.

New strings (EN / FR), exact:

| name | EN | FR |
|---|---|---|
| `water_heater_tank` | Tank | Ballon |
| `water_heater_heating_power` | Heating · %1$d %% | Chauffe · %1$d %% |
| `water_heater_idle` | Idle | Au repos |
| `water_heater_inlet` | Inlet %1$s | Arrivée %1$s |
| `water_heater_mode_heat` | Heating | Chauffe |
| `card_description_water_heater` | %1$s, %2$s, %3$s, %4$s | %1$s, %2$s, %3$s, %4$s |

The chip for `Off` reuses `value_off`. `waterHeaterModeLabel` gains `"heat" -> water_heater_mode_heat`. The `card_description_water_heater` args are name, temperature, chip-and-inlet text joined with ", ", and room.

Delete `waterHeaterSubtitle` and the strings `water_heater_eco`, `water_heater_eco_target`, `water_heater_forced`, `water_heater_forced_to`, `water_heater_hot`, `water_heater_hot_target` and `water_heater_target`, in both languages. First `grep -rn` the repo, including `feature/voice` and `feature/widget`, and keep any string still referenced.

- [ ] **Step 1: Write the failing tests** in `WaterHeaterCardTest` (look at `WaterHeaterScreenshotTest` for how it builds a `CardUi`):

```kotlin
@Test fun `heating card shows tank temperature, chip and inlet`() {
    // heater = HeaterView(true, 43, 17.9, HeaterChip.Heating(43)), value Climate current "58"
    compose.onNodeWithText("58°").assertExists()
    compose.onNodeWithText("Heating · 43 %").assertExists()
    compose.onNodeWithText("Inlet 17.9 °C").assertExists()
    compose.onNodeWithText("Tank").assertExists()
}
@Test fun `idle card has no inlet line without inlet`() { /* HeaterView(false, 0, null, Idle): "Idle" exists, no node containing "Inlet" */ }
@Test fun `jeedom card keeps the force switch and shows the mode chip`() { /* forceToggle=false, chip Mode("eco"): "Eco" exists, Force switch found by contentDescription "Force Water heater" */ }
@Test fun `unknown temperature shows the none text`() { /* current null → value_none text */ }
```

The inlet is formatted with `CardFormatter.number(inlet, locale)` plus `" °C"`, or the entity unit when there is one.

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*WaterHeaterCardTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement the glyph, the body branch and the strings**

The glyph is a Compose `Canvas`, the same size as `KindIcon`: a rounded vertical tank outline and a zig-zag heating element in its lower half. While `heating`, it uses the card accent fill and element colour; otherwise the muted content colour. Animation is the builder's call (a static tint is fine); if animated, gate it on reduced motion. Body order: top row (glyph | Force switch | pending dot), name, "Tank" caption + temperature in `cardValue` style, chip (a small `Surface` pill, tinted while heating), inlet line with a decorative "↓ ". The card stays non-wide unless the screenshot in Task 8 shows it can't fit.

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS. (`WaterHeaterScreenshotTest` may need its `CardUi` builders updated with `heater = HeaterView.of(...)`; do that here so the suite stays green. Task 8 extends it.)

- [ ] **Step 5: Commit** — `feat(home): water heater card draws the tank with chip and inlet`

---

### Task 7: The sheet shows only what the heater has

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt:278-318` (`WaterHeaterControls`)
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WaterHeaterCardTest.kt` (sheet cases; or a new `WaterHeaterControlsTest.kt`)

**Interfaces:**
- Consumes: `CardUi.state` (`StateValue.Climate`), `capabilities.hvacModes`, `card.companions`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `no modes and no target shows only the power numbers`() {
    // hvacModes = [], Climate("heat", target = null, current = 58.0), companions Power 1/2/3
    compose.onNodeWithText("Target").assertDoesNotExist()   // R.string.sheet_target
    compose.onAllNodes(hasClickAction() and hasText("Heating")).assertCountEquals(0)  // no mode chips
    compose.onNodeWithText("Power 1").assertExists()
}
@Test fun `jeedom heater without a target keeps its mode chips`() {
    // hvacModes = [off, eco, performance], target null → chips "Eco", "Force" exist, "Target" absent
}
@Test fun `jeedom heater with a target shows the stepper`() { /* target 60 → "60°" and "Target" exist */ }
```

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*WaterHeater*'`
Expected: FAIL ("Target" exists).

- [ ] **Step 3: Implement it.** Render the chip `FlowRow` only when the filtered modes are non-empty. Render the target title and stepper only when `climate?.target != null`, and step from that target (drop the midpoint fallback).

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit** — `feat(home): water heater sheet hides controls the heater lacks`

---

### Task 8: Demo house, title check and screenshots

**Files:**
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt:74,401-430` (`waterHeater`)
- Modify: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoBrokerTest.kt:40,122-138`
- Modify: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WaterHeaterScreenshotTest.kt`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderWaterHeaterTest.kt` (title)
- Possibly modify: `HomeUiBuilder.kt` `displayName` / `companionName` (only if the title test fails)

**Interfaces:**
- Consumes: everything above.
- Produces: demo entity ids `demo_water_heater`, `demo_water_heater_triac_0`, `demo_water_heater_triac_1`, `demo_water_heater_triac_2` (named "Power 1/2/3"). `demo_water_heater_force_power` is removed. The energy sensor `demo_water_heater_power` is untouched.

- [ ] **Step 1: Write the failing tests**

`DemoBrokerTest`: update the expected-id list (line 40). Replace `forcing the water heater round-trips…` with:

```kotlin
@Test
fun `demo water heater reads like the WaterHeaterController`() = runTest {
    val h = start()
    settle()
    val heater = assertIs<StateValue.Climate>(h.value("demo_water_heater"))
    assertEquals("heat", heater.mode)
    assertEquals(58.0, heater.current)
    assertEquals(mapOf("inlet_temperature" to 17.9, "power" to 43L), h.state("demo_water_heater").attributes) // add a state() helper if missing
    assertEquals(CommandResult.Accepted, h.source.send("demo_water_heater_triac_0", Command.SetNumber(0.0))) // use the repo's actual number command
    assertEquals(CommandResult.Accepted, h.source.send("demo_water_heater_triac_1", Command.SetNumber(0.0)))
    assertEquals(CommandResult.Accepted, h.source.send("demo_water_heater_triac_2", Command.SetNumber(0.0)))
    settle()
    assertEquals("off", assertIs<StateValue.Climate>(h.value("demo_water_heater")).mode)
}
```

`HomeUiBuilderWaterHeaterTest`: build an entity named the way `DiscoveryParser` names the Appendix A heater (component `name: null`, device "Water heater"; check the parser's `displayName` to get the exact `name`/`deviceName`/`label`), then assert the card name is exactly `"Water heater"` and no companion name starts with `"Water heater Water heater"`.

- [ ] **Step 2: Run the tests to make sure they fail**

Run: `./gradlew :core:demo:test :feature:home:testDebugUnitTest --tests '*WaterHeater*'`
Expected: the demo test FAILS. The title test may pass; if so, keep it as the regression guard and do not touch naming.

- [ ] **Step 3: Implement the demo heater**

Rewrite `waterHeater(...)` to publish, per component through the existing `config(...)` helper (on the `Water heater` device, area Garage, topic `demo/water_heater`), the Appendix A shapes. These are: the `water_heater` with `modes: []`, the three templates verbatim and `json_attributes_topic`/`json_attributes_template`; the tank, inlet and triac `sensor`s; and three `number`s with `command_template` `{"powerN": {{ value | int }}}` on `demo/water_heater/control`. Seed `doc(topic, …)` with the Appendix B payload. One `echo("demo/water_heater/control", topic)` merges any `powerN` key into `triac_N_power`. Keep `override("demo_water_heater_power", …)` as it is.

Fix the title only if Step 1's title test failed: at the naming site, and nowhere else.

- [ ] **Step 4: Screenshots.** In `WaterHeaterScreenshotTest`, render and check in light and dark: the controller card heating (`HeaterChip.Heating(43)`, inlet 17.9), the controller card idle, the Jeedom card in eco with its Force switch, and the controller sheet (no chips, no stepper, Power 1/2/3). Look at every PNG. If the heating card truncates text at `w411dp`, make water heater cards `wide = true` in `HomeUiBuilder.card()` and re-render.

- [ ] **Step 5: Run the full affected suites**

Run: `./gradlew :core:state:jvmTest :core:source:jvmTest :core:demo:test :feature:home:testDebugUnitTest :feature:widget:testDebugUnitTest :feature:voice:test`
Expected: all PASS.

- [ ] **Step 6: Commit** — `feat(demo): demo water heater is a WaterHeaterController`, plus `test(home): water heater screenshots` if the screenshots form a separate change.
