# LED Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Any MQTT light that announces effects or a colour mode gets effect chips, its device's number/select controls and a hue wheel in its Home control sheet; the ZigLED converter announces `effect_speed` so the bedroom strip gets a speed slider.

**Architecture:** `core:model` carries the new state (`Light.effect`, `Light.color: Hs`) and command (`TurnOn.effect`, `TurnOn.color: Hs`). `core:state` maps them to MQTT (`LightMapper` + a pure `ColorMath` for hs ↔ rgb ↔ xy). `core:source` reads `effect_list` and folds a device's numbers/selects into its single light (`LightAttachment`, `partOf`). `feature:home` attaches those as `CardUi.companions` and draws them, the effect chips and a `HueWheel` in `LightControls`.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, Jetpack Compose (BOM 2026.06.01, Material 3), Robolectric Compose tests, JUnit/kotlin.test. ZigLED converter: zigbee-herdsman-converters modernExtend (JavaScript ESM).

**Spec:** `docs/superpowers/specs/2026-10-02-oikos-led-control-design.md`

## Global Constraints

- Nothing device-specific in Oikos: no code may test for `ZigLED`, `effect_speed`, `fire_1d` or any other device/effect name (parent spec goal 7). Fixtures may contain them.
- Colour on the model is `Hs(hue 0..<360, saturation 0..1)`; brightness stays separate (`brightness` 0..255). The wheel never changes brightness.
- JSON-schema colour is encoded in the light's own mode, picked from `supported_color_modes` in the order `xy` → `hs` → `rgb`/`rgbw`/`rgbww`; no colour mode → the colour is dropped.
- Folding: only `NUMBER` and `SELECT` entities, only into a device's **single** `LIGHT`; `SensorAttachment` wins on conflict.
- English UI strings use US spelling (`Color`); every new string has an `en` and a `fr` entry.
- Gradle must run with the sandbox disabled (`~/.gradle` writes fail otherwise). JVM modules test with `./gradlew :core:<m>:test`, `feature:home` with `./gradlew :feature:home:testDebugUnitTest`.
- `DirectConnectionTesterTest` (`:app`) is known flaky; a failure there alone is not a regression.
- Commits: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit …`, message ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Never write the user's real name anywhere.
- Work on a branch `feat/led-control` from `master` (Task 1 creates it). The ZigLED repo is a separate clone; its change is committed there, **not pushed**.

## Review Focus

1. **A state payload with `"effect": null` or no `color`** (Z2M sends both shapes) must keep the previous effect/colour, not wipe it — pinned in Task 3 (`a payload without effect or color keeps the previous ones`).
2. **Hue at the 0°/360° seam** (`{h: 360}`, a wheel touch exactly at the top, rgb→hs rounding to 360.0) must become hue 0, never throw from `Hs.init` — pinned in Task 2 (`hue never reaches 360`) and Task 3 (`hs payload at 360 wraps to 0`).
3. **A device with two lights** (e.g. a Z2M dual-channel controller) must not fold its numbers into either light — pinned in Task 6 (`nothing folds when the device has two lights`).
4. **Companions must still be commandable** after they leave the grid: `HomeUiState.card(id)` finds them, so the view model's stale check and `send(id, …)` work — pinned in Task 7 (`companions are found by card(id)`).
5. **The wheel after a state update**: the marker follows a new `state.color`, and a drag that started before the update sends the dragged colour, not the old one — pinned in Task 9 by the `LaunchedEffect`/`rememberUpdatedState` structure and the `tapping the wheel sends a color` test.

---

### Task 1: ZigLED converter announces `effect_speed` (ZigLED repo)

The ZigLED repo is already cloned at `$SCRATCH/ZigLED`, where `SCRATCH=/private/tmp/claude-501/-Users-julienhuguel-Documents-SourcesPerso-Oikos/075020fe-3801-4534-a80a-69136adcc639/scratchpad`. If it is missing: `git clone git@github.com-gekkotron:Gekkotron/ZigLED.git "$SCRATCH/ZigLED"`.

Z2M's Home Assistant extension marks any numeric named `effect_speed` as `entity_category: "config"`, `enabled_by_default: false` (its `NUMERIC_DISCOVERY_LOOKUP`). Z2M calls `definition.meta.overrideHaDiscoveryPayload(payload, options)` on every discovery payload of the device after resolving topics, so `payload.command_topic` is already `zigbee2mqtt/<name>/set/effect_speed`.

**Files:**
- Modify: `$SCRATCH/ZigLED/z2m/gekkotron_zigled.mjs`

- [ ] **Step 1: Add the override**

In `gekkotron_zigled.mjs`, add a `meta` entry to the exported definition, right after `endpoint: …,`:

```js
    meta: {
        // Z2M's HA extension hides any numeric named effect_speed (entity_category "config", disabled by
        // default). Speed is a primary control of this device, so announce it like effect_intensity.
        overrideHaDiscoveryPayload: (payload) => {
            if (typeof payload.command_topic === 'string' && payload.command_topic.endsWith('/set/effect_speed')) {
                delete payload.entity_category;
                payload.enabled_by_default = true;
            }
        },
    },
```

- [ ] **Step 2: Syntax check**

Run: `node --check "$SCRATCH/ZigLED/z2m/gekkotron_zigled.mjs"`
Expected: no output, exit 0. (The import of `zigbee-herdsman-converters` is not resolved by `--check`.)

- [ ] **Step 3: Commit (no push)**

```bash
cd "$SCRATCH/ZigLED"
git add z2m/gekkotron_zigled.mjs
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "fix(z2m): announce effect_speed to Home Assistant discovery

Z2M's HA extension marks any numeric named effect_speed as a disabled config
entity, so HA-discovery clients never see it. Override the discovery payload
so speed is announced like effect_intensity.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: Create the Oikos branch**

```bash
cd /Users/julienhuguel/Documents/SourcesPerso/Oikos
git switch -c feat/led-control
```

---

### Task 2: `Hs` and `ColorMath`

**Files:**
- Create: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Hs.kt`
- Create: `core/state/src/main/kotlin/com/gekkotron/oikos/state/ColorMath.kt`
- Test: `core/model/src/test/kotlin/com/gekkotron/oikos/model/HsTest.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/ColorMathTest.kt`

**Interfaces:**
- Produces: `data class Hs(val hue: Double, val saturation: Double)` (package `com.gekkotron.oikos.model`), `init` rejects hue outside `0.0 until 360.0` and saturation outside `0.0..1.0`.
- Produces: `object ColorMath` (package `com.gekkotron.oikos.state`) with `data class Xy(val x: Double, val y: Double)`, `val WHITE: Xy`, `fun hsToRgb(hs: Hs): Rgb`, `fun rgbToHs(rgb: Rgb): Hs`, `fun hsToXy(hs: Hs): Xy`, `fun xyToHs(xy: Xy): Hs`.

- [ ] **Step 1: Write the failing tests**

`core/model/src/test/kotlin/com/gekkotron/oikos/model/HsTest.kt`:

```kotlin
package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class HsTest {
    @Test
    fun `hue and saturation are range checked`() {
        Hs(0.0, 0.0)
        Hs(359.9, 1.0)
        assertFailsWith<IllegalArgumentException> { Hs(360.0, 0.5) }
        assertFailsWith<IllegalArgumentException> { Hs(-1.0, 0.5) }
        assertFailsWith<IllegalArgumentException> { Hs(10.0, 1.1) }
    }
}
```

`core/state/src/test/kotlin/com/gekkotron/oikos/state/ColorMathTest.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Rgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMathTest {
    private fun hueDistance(a: Double, b: Double) = abs(a - b).let { minOf(it, 360 - it) }

    @Test
    fun `hs to rgb at full value`() {
        assertEquals(Rgb(255, 0, 0), ColorMath.hsToRgb(Hs(0.0, 1.0)))
        assertEquals(Rgb(0, 255, 0), ColorMath.hsToRgb(Hs(120.0, 1.0)))
        assertEquals(Rgb(255, 255, 255), ColorMath.hsToRgb(Hs(200.0, 0.0)))
        assertEquals(Hs(240.0, 1.0), ColorMath.rgbToHs(Rgb(0, 0, 255)))
        assertEquals(Hs(0.0, 0.0), ColorMath.rgbToHs(Rgb(0, 0, 0)))
    }

    @Test
    fun `known xy points`() {
        assertEquals(ColorMath.Xy(0.6401, 0.33), ColorMath.hsToXy(Hs(0.0, 1.0)))
        assertEquals(ColorMath.Xy(0.3127, 0.329), ColorMath.hsToXy(Hs(0.0, 0.0)))
    }

    @Test
    fun `the strip's colour decodes to a blue`() {
        val hs = ColorMath.xyToHs(ColorMath.Xy(0.196, 0.203))
        assertTrue(hueDistance(hs.hue, 208.0) < 1.0, "hue ${hs.hue}")
        assertTrue(abs(hs.saturation - 0.78) < 0.01, "saturation ${hs.saturation}")
    }

    @Test
    fun `hs to xy and back`() {
        for (hue in 0 until 360 step 30) for (saturation in listOf(0.2, 0.5, 1.0)) {
            val back = ColorMath.xyToHs(ColorMath.hsToXy(Hs(hue.toDouble(), saturation)))
            assertTrue(hueDistance(back.hue, hue.toDouble()) < 1.0, "hue $hue/$saturation → ${back.hue}")
            assertTrue(abs(back.saturation - saturation) < 0.01, "saturation $hue/$saturation → ${back.saturation}")
        }
    }

    @Test
    fun `hue never reaches 360`() {
        // Just below red on the magenta side: rounding must wrap to 0, not build Hs(360.0, …).
        val hs = ColorMath.rgbToHs(Rgb(255, 0, 1))
        assertTrue(hs.hue < 360.0)
        val xy = ColorMath.xyToHs(ColorMath.hsToXy(Hs(359.99, 1.0)))
        assertTrue(xy.hue < 360.0)
    }

    @Test
    fun `degenerate xy falls back to white`() {
        assertEquals(Hs(0.0, 0.0), ColorMath.xyToHs(ColorMath.Xy(0.3, 0.0)))
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:model:test --tests '*HsTest*' :core:state:test --tests '*ColorMathTest*'`
Expected: compilation FAIL — `Unresolved reference: Hs` / `ColorMath`.

- [ ] **Step 3: Implement**

`core/model/src/main/kotlin/com/gekkotron/oikos/model/Hs.kt`:

```kotlin
package com.gekkotron.oikos.model

/** A light colour without brightness: [hue] in degrees 0..<360, [saturation] 0..1. */
data class Hs(val hue: Double, val saturation: Double) {
    init {
        require(hue >= 0.0 && hue < 360.0) { "hue must be 0..<360" }
        require(saturation in 0.0..1.0) { "saturation must be 0..1" }
    }
}
```

`core/state/src/main/kotlin/com/gekkotron/oikos/state/ColorMath.kt`:

```kotlin
package com.gekkotron.oikos.state

import com.gekkotron.oikos.model.Hs
import com.gekkotron.oikos.model.Rgb
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Light colour conversions (LED control spec §3.2). Colours carry no brightness: hs ↔ rgb works at full
 * value, xy ↔ rgb at Y = 1 normalised to the brightest channel. sRGB primaries, D65 white.
 */
object ColorMath {
    data class Xy(val x: Double, val y: Double)

    val WHITE = Xy(0.3127, 0.329)

    fun hsToRgb(hs: Hs): Rgb {
        val (r, g, b) = hsToUnit(hs)
        return Rgb(byte(r), byte(g), byte(b))
    }

    fun rgbToHs(rgb: Rgb): Hs = unitToHs(rgb.r / 255.0, rgb.g / 255.0, rgb.b / 255.0)

    fun hsToXy(hs: Hs): Xy {
        val (r, g, b) = hsToUnit(hs).map(::linear)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        val sum = x + y + z
        if (sum <= 0.0) return WHITE
        return Xy(round4(x / sum), round4(y / sum))
    }

    fun xyToHs(xy: Xy): Hs {
        if (xy.y <= 0.0) return Hs(0.0, 0.0)
        val bigX = xy.x / xy.y
        val bigZ = (1 - xy.x - xy.y) / xy.y
        val r = (3.2406 * bigX - 1.5372 - 0.4986 * bigZ).coerceAtLeast(0.0)
        val g = (-0.9689 * bigX + 1.8758 + 0.0415 * bigZ).coerceAtLeast(0.0)
        val b = (0.0557 * bigX - 0.2040 + 1.0570 * bigZ).coerceAtLeast(0.0)
        val max = maxOf(r, g, b)
        if (max <= 0.0) return Hs(0.0, 0.0)
        return unitToHs(gamma(r / max), gamma(g / max), gamma(b / max))
    }

    /** Gamma-encoded sRGB channels 0..1 at full value. */
    private fun hsToUnit(hs: Hs): List<Double> {
        val c = hs.saturation
        val h = hs.hue / 60.0
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = 1 - c
        return listOf(r + m, g + m, b + m)
    }

    private fun unitToHs(r: Double, g: Double, b: Double): Hs {
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        if (max <= 0.0 || d <= 0.0) return Hs(0.0, 0.0)
        val h = when (max) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        val hue = ((h % 360) + 360) % 360
        return Hs(if (hue >= 360.0) 0.0 else hue, (d / max).coerceIn(0.0, 1.0))
    }

    private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun gamma(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

    private fun byte(c: Double) = (c * 255).roundToInt().coerceIn(0, 255)

    private fun round4(v: Double) = (v * 10_000).roundToInt() / 10_000.0
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :core:model:test --tests '*HsTest*' :core:state:test --tests '*ColorMathTest*'`
Expected: PASS. If `known xy points` is off in the 4th decimal, print the computed value and check the matrices against the ones above before touching the expectation (red must be 0.6401/0.33, white 0.3127/0.329).

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/com/gekkotron/oikos/model/Hs.kt core/model/src/test/kotlin/com/gekkotron/oikos/model/HsTest.kt \
  core/state/src/main/kotlin/com/gekkotron/oikos/state/ColorMath.kt core/state/src/test/kotlin/com/gekkotron/oikos/state/ColorMathTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(model): Hs light colour and hs/rgb/xy conversions

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Effect and colour in the model and the JSON-schema light mapper

**Files:**
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Command.kt:11` (`TurnOn`)
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/StateValue.kt:13` (`Light`)
- Modify: `core/model/src/main/kotlin/com/gekkotron/oikos/model/Entity.kt:6-20` (`Capabilities`)
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/LightMapper.kt`
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/LightMapperTest.kt`

**Interfaces:**
- Consumes: `Hs`, `ColorMath` (Task 2).
- Produces: `Command.TurnOn(brightness: Int? = null, color: Hs? = null, effect: String? = null)`; `StateValue.Light(on: Boolean, brightness: Int? = null, effect: String? = null, color: Hs? = null)`; `Capabilities.effects: List<String> = emptyList()` (added right after `colorModes`).

- [ ] **Step 1: Write the failing tests**

In `LightMapperTest.kt`, change the import `com.gekkotron.oikos.model.Rgb` to `com.gekkotron.oikos.model.Hs`, add imports `kotlin.math.abs` and `kotlin.test.assertNull` and `kotlin.test.assertTrue`, and add these bindings next to `z2m`:

```kotlin
    /** The bedroom ZigLED strip, as Z2M 2.14.1 announces it. */
    private val zigled = bindings(
        "light",
        """{"schema":"json","state_topic":"z/leds","command_topic":"z/leds/set","brightness":true,"brightness_scale":254,
           "supported_color_modes":["xy"],"effect":true,"effect_list":["solid","breathe","fire_1d"]}""",
    )
    private fun withModes(vararg modes: String) = bindings(
        "light",
        """{"schema":"json","state_topic":"z/l","command_topic":"z/l/set","supported_color_modes":[${modes.joinToString(",") { "\"$it\"" }}]}""",
    )
```

Replace the body of `json schema commands` (its rgb assertion depended on sending rgb to a light with no colour mode) with:

```kotlin
    @Test
    fun `json schema commands`() {
        assertEquals(publishes("z/lampe/set" to """{"state":"ON","brightness":254}"""), LightMapper.encode(z2m, Command.TurnOn(brightness = 255), Unknown))
        assertEquals(publishes("z/lampe/set" to """{"state":"ON"}"""), LightMapper.encode(z2m, Command.TurnOn(color = Hs(0.0, 1.0)), Unknown))
        assertEquals(publishes("z/lampe/set" to """{"state":"OFF"}"""), LightMapper.encode(z2m, Command.TurnOff, Light(true, 10)))
    }
```

In `default schema rgb and toggle`, replace `Command.TurnOn(color = Rgb(1, 2, 3))` and its expected `"1,2,3"` with `Command.TurnOn(color = Hs(0.0, 1.0))` and `"255,0,0"`.

Add:

```kotlin
    @Test
    fun `decodes the strip's state payload`() {
        val payload = """{"active_count":96,"brightness":0,"color":{"x":0.196,"y":0.203},"color_mode":"xy","effect":"fire_1d",
            "effect_intensity":39,"effect_speed":38,"palette":1,"state":"OFF"}"""
        val light = (LightMapper.decode(zigled, "z/leds", payload, Unknown) as Decoded.State).value as Light
        assertEquals(false, light.on)
        assertEquals(0, light.brightness)
        assertEquals("fire_1d", light.effect)
        val color = light.color!!
        assertTrue(abs(color.hue - 208.0) < 1.0 && abs(color.saturation - 0.78) < 0.01, "$color")
    }

    @Test
    fun `effect-only and color-only payloads are state updates`() {
        val prev = Light(true, 200, "solid", Hs(10.0, 1.0))
        assertEquals(Decoded.State(prev.copy(effect = "breathe")), LightMapper.decode(zigled, "z/leds", """{"effect":"breathe"}""", prev))
        val red = (LightMapper.decode(zigled, "z/leds", """{"color":{"r":255,"g":0,"b":0}}""", prev) as Decoded.State).value as Light
        assertEquals(Hs(0.0, 1.0), red.color)
        assertEquals("solid", red.effect)
    }

    @Test
    fun `a payload without effect or color keeps the previous ones`() {
        val prev = Light(true, 200, "solid", Hs(10.0, 1.0))
        assertEquals(Decoded.State(prev.copy(on = false)), LightMapper.decode(zigled, "z/leds", """{"state":"OFF","effect":null}""", prev))
    }

    @Test
    fun `hs payloads in either key style, wrapping at 360`() {
        val a = (LightMapper.decode(zigled, "z/leds", """{"color":{"h":120,"s":50}}""", Unknown) as Decoded.State).value as Light
        assertEquals(Hs(120.0, 0.5), a.color)
        val b = (LightMapper.decode(zigled, "z/leds", """{"color":{"hue":360,"saturation":100}}""", Unknown) as Decoded.State).value as Light
        assertEquals(Hs(0.0, 1.0), b.color)
    }

    @Test
    fun `effect and color are encoded in the light's own mode`() {
        assertEquals(
            publishes("z/leds/set" to """{"state":"ON","effect":"fire_1d"}"""),
            LightMapper.encode(zigled, Command.TurnOn(effect = "fire_1d"), Unknown),
        )
        assertEquals(
            publishes("z/leds/set" to """{"state":"ON","color":{"x":0.6401,"y":0.33}}"""),
            LightMapper.encode(zigled, Command.TurnOn(color = Hs(0.0, 1.0)), Unknown),
        )
        assertEquals(
            publishes("z/l/set" to """{"state":"ON","color":{"h":210.0,"s":80.0}}"""),
            LightMapper.encode(withModes("hs", "color_temp"), Command.TurnOn(color = Hs(210.0, 0.8)), Unknown),
        )
        assertEquals(
            publishes("z/l/set" to """{"state":"ON","color":{"r":255,"g":0,"b":0}}"""),
            LightMapper.encode(withModes("rgbw"), Command.TurnOn(color = Hs(0.0, 1.0)), Unknown),
        )
    }

    @Test
    fun `prediction overlays effect and color`() {
        val prev = Light(true, 200, "solid", Hs(10.0, 1.0))
        assertEquals(Light(true, 200, "comet", Hs(10.0, 1.0)), LightMapper.predict(Command.TurnOn(effect = "comet"), prev))
        assertEquals(Light(true, 200, "solid", Hs(90.0, 0.5)), LightMapper.predict(Command.TurnOn(color = Hs(90.0, 0.5)), prev))
        assertEquals(Light(false, 200, "solid", Hs(10.0, 1.0)), LightMapper.predict(Command.TurnOff, prev))
        assertNull(LightMapper.predict(Command.TurnOn(), Unknown).let { (it as Light).effect })
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:state:test --tests '*LightMapperTest*'`
Expected: compilation FAIL (`Light` has no `effect`, `TurnOn` has no `effect`, `color` type mismatch).

- [ ] **Step 3: Implement the model changes**

`Command.kt`, replace `TurnOn`:

```kotlin
    /** [brightness] is 0..255; [color] carries no brightness; [effect] is one of the light's `effects`. */
    data class TurnOn(val brightness: Int? = null, val color: Hs? = null, val effect: String? = null) : Command {
        init {
            require(brightness == null || brightness in 0..255) { "brightness must be 0..255" }
        }
    }
```

`StateValue.kt`, replace `Light`:

```kotlin
    /** [brightness] is normalised to 0..255; [effect] and [color] are null until the light reports them. */
    data class Light(val on: Boolean, val brightness: Int? = null, val effect: String? = null, val color: Hs? = null) : StateValue
```

`Entity.kt`, in `Capabilities` after `val colorModes: Set<String> = emptySet(),` add:

```kotlin
    /** The light's `effect_list`, in published order; empty when it has no effects. */
    val effects: List<String> = emptyList(),
```

- [ ] **Step 4: Implement the JSON-schema mapping**

In `LightMapper.kt` add imports `com.gekkotron.oikos.model.Hs`, `com.gekkotron.oikos.model.Rgb`, `com.gekkotron.oikos.state.ColorMath`. Add, inside the object:

```kotlin
    private val RGB_MODES = setOf("rgb", "rgbw", "rgbww")

    /** `{x,y}`, then `{h|hue, s|saturation}` (s in 0..100), then `{r,g,b}`; null when none is complete. */
    private fun colorOf(o: JsonObject): Hs? {
        fun num(vararg keys: String) = keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.doubleOrNull }
        val x = num("x")
        val y = num("y")
        if (x != null && y != null) return ColorMath.xyToHs(ColorMath.Xy(x, y))
        val h = num("h", "hue")
        val s = num("s", "saturation")
        if (h != null && s != null) return Hs(((h % 360) + 360) % 360, (s / 100).coerceIn(0.0, 1.0))
        val r = num("r")
        val g = num("g")
        val bl = num("b")
        if (r != null && g != null && bl != null) {
            return ColorMath.rgbToHs(Rgb(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), bl.roundToInt().coerceIn(0, 255)))
        }
        return null
    }

    /** The colour in the light's own mode (spec §3.3), or null when it has no colour mode. */
    private fun colorJson(b: MqttBindings, hs: Hs): JsonObject? {
        val modes = b.strList("supported_color_modes").orEmpty()
        return when {
            "xy" in modes -> ColorMath.hsToXy(hs).let { buildJsonObject { put("x", it.x); put("y", it.y) } }
            "hs" in modes -> buildJsonObject { put("h", round1(hs.hue)); put("s", round1(hs.saturation * 100)) }
            modes.any { it in RGB_MODES } -> ColorMath.hsToRgb(hs).let { c -> buildJsonObject { put("r", c.r); put("g", c.g); put("b", c.b) } }
            else -> null
        }
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
```

In `decodeJson`, replace everything from `val stateWord = …` to the `return Decoded.State(...)` with:

```kotlin
        val stateWord = (obj["state"] as? JsonPrimitive)?.contentOrNull
        val rawBrightness = (obj["brightness"] as? JsonPrimitive)?.doubleOrNull
        val effect = (obj["effect"] as? JsonPrimitive)?.contentOrNull
        val color = (obj["color"] as? JsonObject)?.let(::colorOf)
        if (stateWord == null && rawBrightness == null && effect == null && color == null) return Decoded.Ignored
        val on = when (stateWord) {
            "ON" -> true
            "OFF" -> false
            else -> prev.on
        }
        val brightness = rawBrightness?.let { toByte(it, scale(b)) } ?: prev.brightness
        return Decoded.State(StateValue.Light(on, brightness, effect ?: prev.effect, color ?: prev.color))
```

In `encode`, replace the JSON body builder with:

```kotlin
            val body = buildJsonObject {
                put("state", if (on) "ON" else "OFF")
                turnOn?.brightness?.let { put("brightness", fromByte(it, scale(b))) }
                turnOn?.effect?.let { put("effect", it) }
                turnOn?.color?.let { hs -> colorJson(b, hs)?.let { put("color", it) } }
            }
```

and replace the default-schema rgb line with:

```kotlin
        turnOn?.color?.let { hs ->
            val c = ColorMath.hsToRgb(hs)
            b.str("rgb_command_topic")?.let { messages += outgoing(b, it, "${c.r},${c.g},${c.b}") }
        }
```

Replace `predict` with:

```kotlin
    override fun predict(command: Command, previous: StateValue): StateValue? {
        val on = targetOn(command, previous) ?: return null
        val prev = previous.asLight()
        val turnOn = command as? Command.TurnOn
        return StateValue.Light(on, turnOn?.brightness ?: prev.brightness, turnOn?.effect ?: prev.effect, turnOn?.color ?: prev.color)
    }
```

`asLight()` (`StateValue.Light(on = isOn())`) stays as is.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:model:test :core:state:test`
Expected: PASS. Then `./gradlew compileDebugKotlin compileTestKotlin compileDebugUnitTestKotlin` to find any other `TurnOn(color = Rgb…)` caller (there should be none outside `LightMapper` and its test); fix by converting with `ColorMath.rgbToHs` only if one shows up.

- [ ] **Step 6: Commit**

```bash
git add core/model core/state
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(light): effect and colour state and commands; colour sent in the light's own mode

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Default-schema effect and rgb state topics

**Files:**
- Modify: `core/state/src/main/kotlin/com/gekkotron/oikos/state/kinds/LightMapper.kt` (`stateTopics`, `decodeDefault`, `encode`)
- Test: `core/state/src/test/kotlin/com/gekkotron/oikos/state/kinds/LightMapperTest.kt`

**Interfaces:**
- Consumes: Task 3's `StateValue.Light(on, brightness, effect, color)`, `ColorMath.rgbToHs`, `publish(b, topicKey, templateKey, value)` from `kinds/Common.kt` (returns `Encoded`; `Encoded.Publish(messages: List<Outgoing>)`).

- [ ] **Step 1: Write the failing tests**

Add to `LightMapperTest.kt`:

```kotlin
    private val fancy = bindings(
        "light",
        """{"state_topic":"l/state","command_topic":"l/set","effect_state_topic":"l/fx","effect_command_topic":"l/fx/set",
           "effect_list":["solid","rainbow"],"rgb_state_topic":"l/rgb","rgb_command_topic":"l/rgb/set"}""",
    )

    @Test
    fun `default schema decodes effect and rgb state topics`() {
        assertEquals(setOf("l/state", "l/fx", "l/rgb"), LightMapper.stateTopics(fancy))
        assertEquals(Decoded.State(Light(true, null, "rainbow")), LightMapper.decode(fancy, "l/fx", "rainbow", Light(true, null)))
        assertEquals(Decoded.State(Light(true, null, null, Hs(240.0, 1.0))), LightMapper.decode(fancy, "l/rgb", "0,0,255", Light(true, null)))
        assertEquals(Decoded.Ignored, LightMapper.decode(fancy, "l/rgb", "0,0", Light(true, null)))
        assertEquals(Decoded.Ignored, LightMapper.decode(fancy, "l/rgb", "0,0,300", Light(true, null)))
    }

    @Test
    fun `default schema effect command`() {
        assertEquals(publishes("l/set" to "ON", "l/fx/set" to "rainbow"), LightMapper.encode(fancy, Command.TurnOn(effect = "rainbow"), Unknown))
        val templated = bindings("light", """{"command_topic":"l/set","effect_command_topic":"l/fx/set","effect_command_template":"FX:{{ value }}"}""")
        assertEquals(publishes("l/set" to "ON", "l/fx/set" to "FX:rainbow"), LightMapper.encode(templated, Command.TurnOn(effect = "rainbow"), Unknown))
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:state:test --tests '*LightMapperTest*'`
Expected: FAIL — `stateTopics` lacks `l/fx`, `l/rgb`; the effect publish is missing.

- [ ] **Step 3: Implement**

`stateTopics`, default-schema branch:

```kotlin
        else setOfNotNull(b.str("state_topic"), b.str("brightness_state_topic"), b.str("effect_state_topic"), b.str("rgb_state_topic"))
```

In `decodeDefault`, before `return parts.result(light)`:

```kotlin
        if (topic == b.str("effect_state_topic")) {
            parts.part(renderIncoming(b, "effect_value_template", payload)) { v ->
                val effect = v.asPayloadString()?.takeIf { it.isNotEmpty() } ?: return@part false
                light = light.copy(effect = effect)
                true
            }
        }
        if (topic == b.str("rgb_state_topic")) {
            parts.part(renderIncoming(b, "rgb_value_template", payload)) { v ->
                val channels = v.asPayloadString()?.split(',')?.map { it.trim().toIntOrNull() }
                if (channels == null || channels.size != 3 || channels.any { it == null || it !in 0..255 }) return@part false
                light = light.copy(color = ColorMath.rgbToHs(Rgb(channels[0]!!, channels[1]!!, channels[2]!!)))
                true
            }
        }
```

In `encode`, default-schema branch, after the brightness message and before the rgb block:

```kotlin
        turnOn?.effect?.let { effect ->
            (publish(b, "effect_command_topic", "effect_command_template", effect) as? Encoded.Publish)?.let { messages += it.messages }
        }
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :core:state:test`
Expected: PASS (all of `:core:state`).

- [ ] **Step 5: Commit**

```bash
git add core/state
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(light): default-schema effect and rgb state topics

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Discovery reads `effect_list`

**Files:**
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParser.kt:173-180` (`capabilities`, `Kind.LIGHT`)
- Create: `core/source/src/test/resources/discovery/zigled_light.json`
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/DiscoveryParserTest.kt`

**Interfaces:**
- Consumes: `Capabilities.effects` (Task 3).

- [ ] **Step 1: Add the fixture**

`core/source/src/test/resources/discovery/zigled_light.json` (captured from the user's broker, Z2M 2.14.1):

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"}],"brightness":true,"brightness_scale":254,"command_topic":"zigbee2mqtt/rooms/julien/leds/set","default_entity_id":"light.rooms/julien/leds","device":{"identifiers":["zigbee2mqtt_0x1051dbfffe1a4058"],"manufacturer":"Gekkotron","model":"Zigbee WS28xx LED controller on ESP32-C6","model_id":"ZigLED-1","name":"rooms/julien/leds","via_device":"zigbee2mqtt_bridge_0x00124b0022983b3e"},"effect":true,"effect_list":["solid","breathe","comet","wipe","sparkle","rainbow","candle","fire_1d","plasma_2d"],"name":null,"object_id":"rooms/julien/leds","origin":{"name":"Zigbee2MQTT","sw":"2.14.1","url":"https://www.zigbee2mqtt.io"},"schema":"json","state_topic":"zigbee2mqtt/rooms/julien/leds","supported_color_modes":["xy"],"unique_id":"0x1051dbfffe1a4058_light_zigbee2mqtt"}
```

- [ ] **Step 2: Write the failing tests**

Add to `DiscoveryParserTest.kt`:

```kotlin
    @Test
    fun `json light effects and colour modes`() {
        val e = single("homeassistant/light/0x1051dbfffe1a4058/light/config", fixture("zigled_light.json")).entity
        assertEquals(listOf("solid", "breathe", "comet", "wipe", "sparkle", "rainbow", "candle", "fire_1d", "plasma_2d"), e.capabilities.effects)
        assertEquals(setOf("xy"), e.capabilities.colorModes)
        val noFx = single(
            "homeassistant/light/x/config",
            """{"schema":"json","command_topic":"x/set","unique_id":"x","effect":false,"effect_list":["a"]}""",
        ).entity
        assertEquals(emptyList(), noFx.capabilities.effects)
    }

    @Test
    fun `default light effects need an effect command topic`() {
        val withTopic = single(
            "homeassistant/light/y/config",
            """{"command_topic":"y/set","effect_command_topic":"y/fx","effect_list":["solid","rainbow"],"unique_id":"y"}""",
        ).entity
        assertEquals(listOf("solid", "rainbow"), withTopic.capabilities.effects)
        val without = single("homeassistant/light/z/config", """{"command_topic":"z/set","effect_list":["solid"],"unique_id":"z"}""").entity
        assertEquals(emptyList(), without.capabilities.effects)
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:source:test --tests '*DiscoveryParserTest*'`
Expected: FAIL — `effects` is empty.

- [ ] **Step 4: Implement**

Replace the `Kind.LIGHT` branch of `capabilities`:

```kotlin
        Kind.LIGHT -> if (b.str("schema") == "json") {
            Capabilities(
                brightness = b.bool("brightness", false),
                colorModes = b.strList("supported_color_modes").orEmpty().toSet(),
                effects = if (b.bool("effect", false)) b.strList("effect_list").orEmpty() else emptyList(),
            )
        } else {
            Capabilities(
                brightness = b.str("brightness_command_topic") != null,
                colorModes = if (b.str("rgb_command_topic") != null) setOf("rgb") else emptySet(),
                effects = if (b.str("effect_command_topic") != null) b.strList("effect_list").orEmpty() else emptyList(),
            )
        }
```

- [ ] **Step 5: Run them to verify they pass**

Run: `./gradlew :core:source:test --tests '*DiscoveryParserTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/source
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(discovery): lights carry their effect list

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `LightAttachment` folds a device's numbers and selects into its light

**Files:**
- Create: `core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/LightAttachment.kt`
- Modify: `core/source/src/main/kotlin/com/gekkotron/oikos/source/MqttDiscoverySource.kt:368-371`
- Create: `core/source/src/test/resources/discovery/zigled_effect_intensity.json`
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/LightAttachmentTest.kt`
- Test: `core/source/src/test/kotlin/com/gekkotron/oikos/source/MqttDiscoverySourceTest.kt`

**Interfaces:**
- Produces: `object LightAttachment { fun compute(entities: Collection<Entity>): Map<String, String> }` — folded entity uniqueId → its light's uniqueId. Applied with `SensorAttachment` winning: `LightAttachment.compute(…) + SensorAttachment.compute(…)`.

- [ ] **Step 1: Add the fixture**

`core/source/src/test/resources/discovery/zigled_effect_intensity.json`:

```json
{"availability":[{"topic":"zigbee2mqtt/bridge/state","value_template":"{{ value_json.state }}"}],"command_topic":"zigbee2mqtt/rooms/julien/leds/set/effect_intensity","default_entity_id":"number.rooms/julien/leds_effect_intensity","device":{"identifiers":["zigbee2mqtt_0x1051dbfffe1a4058"],"manufacturer":"Gekkotron","model":"Zigbee WS28xx LED controller on ESP32-C6","model_id":"ZigLED-1","name":"rooms/julien/leds","via_device":"zigbee2mqtt_bridge_0x00124b0022983b3e"},"max":255,"min":0,"name":"Effect intensity","object_id":"rooms/julien/leds_effect_intensity","origin":{"name":"Zigbee2MQTT","sw":"2.14.1","url":"https://www.zigbee2mqtt.io"},"state_topic":"zigbee2mqtt/rooms/julien/leds","step":1,"unique_id":"0x1051dbfffe1a4058_effect_intensity_zigbee2mqtt","value_template":"{{ value_json[\"effect_intensity\"] }}"}
```

- [ ] **Step 2: Write the failing unit tests**

`core/source/src/test/kotlin/com/gekkotron/oikos/source/discovery/LightAttachmentTest.kt`:

```kotlin
package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.state.mqtt.MqttBindings
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LightAttachmentTest {
    private fun entity(id: String, kind: Kind, deviceId: String?) = Entity(
        uniqueId = id,
        deviceId = deviceId,
        name = id,
        kind = kind,
        deviceClass = null,
        unit = null,
        icon = null,
        capabilities = Capabilities(),
        bindings = MqttBindings(kind.name.lowercase(), buildJsonObject { }),
    )

    @Test
    fun `numbers and selects fold into the device's single light`() {
        val all = listOf(
            entity("speed", Kind.NUMBER, "strip"),
            entity("leds", Kind.LIGHT, "strip"),
            entity("mode", Kind.SELECT, "strip"),
        )
        assertEquals(mapOf("speed" to "leds", "mode" to "leds"), LightAttachment.compute(all))
    }

    @Test
    fun `nothing folds when the device has two lights`() {
        val all = listOf(entity("l1", Kind.LIGHT, "dual"), entity("l2", Kind.LIGHT, "dual"), entity("speed", Kind.NUMBER, "dual"))
        assertEquals(emptyMap(), LightAttachment.compute(all))
    }

    @Test
    fun `only numbers and selects fold, and only on the same device`() {
        val all = listOf(
            entity("leds", Kind.LIGHT, "strip"),
            entity("occupancy", Kind.BINARY_SENSOR, "strip"),
            entity("temp", Kind.SENSOR, "strip"),
            entity("plug", Kind.SWITCH, "strip"),
            entity("target", Kind.NUMBER, "heater"),
            entity("orphan", Kind.NUMBER, null),
        )
        assertEquals(emptyMap(), LightAttachment.compute(all))
    }
}
```

- [ ] **Step 3: Write the failing source test**

Add to `MqttDiscoverySourceTest.kt`, next to the Moving-sensor tests:

```kotlin
    @Test
    fun `a light's device numbers are folded into it regardless of arrival order`() = runTest {
        val lightTopic = "homeassistant/light/0x1051dbfffe1a4058/light/config"
        val numberTopic = "homeassistant/number/0x1051dbfffe1a4058/effect_intensity/config"
        val lightId = "0x1051dbfffe1a4058_light_zigbee2mqtt"
        val numberId = "0x1051dbfffe1a4058_effect_intensity_zigbee2mqtt"
        for (order in listOf(listOf(lightTopic, numberTopic), listOf(numberTopic, lightTopic))) {
            val h = start()
            for (topic in order) h.transport.deliver(topic, fixture(if (topic == lightTopic) "zigled_light.json" else "zigled_effect_intensity.json"))
            runCurrent()
            assertEquals(lightId, h.source.entities.value.getValue(numberId).partOf)
            assertNull(h.source.entities.value.getValue(lightId).partOf)
        }
    }
```

- [ ] **Step 4: Run them to verify they fail**

Run: `./gradlew :core:source:test --tests '*LightAttachmentTest*' --tests '*MqttDiscoverySourceTest*'`
Expected: compilation FAIL (`LightAttachment` unresolved).

- [ ] **Step 5: Implement**

`core/source/src/main/kotlin/com/gekkotron/oikos/source/discovery/LightAttachment.kt`:

```kotlin
package com.gekkotron.oikos.source.discovery

import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind

/**
 * Folds a device's number and select entities into its light (LED control spec §4.1): a strip's
 * "Effect speed" or "Palette" belongs in the light's sheet, not on its own card. Only a device with
 * exactly one light folds; with two, which light a number drives is ambiguous.
 */
object LightAttachment {
    private val FOLDED = setOf(Kind.NUMBER, Kind.SELECT)

    /** uniqueId -> owning light's uniqueId, for every number or select folded into a light. */
    fun compute(entities: Collection<Entity>): Map<String, String> {
        val lightOf = entities.filter { it.kind == Kind.LIGHT && it.deviceId != null }
            .groupBy { it.deviceId!! }
            .mapNotNull { (device, lights) -> lights.singleOrNull()?.let { device to it.uniqueId } }
            .toMap()
        if (lightOf.isEmpty()) return emptyMap()
        return entities.filter { it.kind in FOLDED }
            .mapNotNull { e -> e.deviceId?.let(lightOf::get)?.let { e.uniqueId to it } }
            .toMap()
    }
}
```

In `MqttDiscoverySource.kt`, replace

```kotlin
            val partOf = SensorAttachment.compute(entities.values)
```

with

```kotlin
            // SensorAttachment wins a conflict: it is applied last.
            val partOf = LightAttachment.compute(entities.values) + SensorAttachment.compute(entities.values)
```

and add `import com.gekkotron.oikos.source.discovery.LightAttachment` if the file imports discovery classes one by one.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:source:test`
Expected: PASS (all of `:core:source`, including the existing cover/Moving tests).

- [ ] **Step 7: Commit**

```bash
git add core/source
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(source): fold a device's numbers and selects into its single light

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Home attaches companions to light cards

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeModels.kt:64-88,104` (`CardUi`, `HomeUiState.card`)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilder.kt`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/HomeUiBuilderTest.kt`

**Interfaces:**
- Consumes: `Entity.partOf` set by Task 6; `Capabilities.effects`, `colorModes`.
- Produces: `CardUi.companions: List<CardUi> = emptyList()` (last constructor parameter); companions' `name` is their label (translated when known) or their name; `HomeUiState.card(id)` also returns companions; a light `hasSheet` when it has brightness, effects, a colour mode or companions.

- [ ] **Step 1: Write the failing tests**

Add to `HomeUiBuilderTest.kt` (it already has `areas`, `link`, `testEntity`, `Locale.US` in scope):

```kotlin
    @Test
    fun `a light carries its device's numbers as companions, sorted, off the grid and out of search`() {
        val all = listOf(
            testEntity("leds", Kind.LIGHT, "Leds", area = "living", deviceId = "zl"),
            testEntity("speed", Kind.NUMBER, "Effect speed", area = "living", deviceId = "zl", partOf = "leds"),
            testEntity("intensity", Kind.NUMBER, "Effect intensity", area = "living", deviceId = "zl", partOf = "leds"),
            testEntity("moving", Kind.SENSOR, "Moving", area = "living", deviceId = "zl", partOf = "leds"),
        ).associateBy { it.uniqueId }
        val states = mapOf("speed" to EntityState("speed", StateValue.Numeric(38.0)))
        val s = HomeUiBuilder.build(all, states, areas, RoomFilter.All, link, Locale.US)
        assertEquals(listOf("leds"), s.cards.map { it.id })
        val leds = s.cards.single()
        assertEquals(listOf("Effect intensity", "Effect speed"), leds.companions.map { it.name })
        assertEquals(StateValue.Numeric(38.0), leds.companions.single { it.id == "speed" }.state)
        assertTrue(leds.hasSheet)
        assertTrue(HomeUiBuilder.build(all, states, areas, RoomFilter.All, link, Locale.US, query = "speed").cards.isEmpty())
    }

    @Test
    fun `companions are found by card(id)`() {
        val all = listOf(
            testEntity("leds", Kind.LIGHT, "Leds", deviceId = "zl"),
            testEntity("speed", Kind.NUMBER, "Effect speed", deviceId = "zl", partOf = "leds"),
        ).associateBy { it.uniqueId }
        val s = HomeUiBuilder.build(all, emptyMap(), areas, RoomFilter.All, link, Locale.US)
        assertEquals("speed", s.card("speed")?.id)
        assertEquals("leds", s.card("leds")?.id)
    }

    @Test
    fun `a light with only effects or only a colour mode has a sheet`() {
        val all = listOf(
            testEntity("fx", Kind.LIGHT, "Fx", capabilities = Capabilities(effects = listOf("solid"))),
            testEntity("rgb", Kind.LIGHT, "Rgb", capabilities = Capabilities(colorModes = setOf("xy"))),
            testEntity("plain", Kind.LIGHT, "Plain"),
        ).associateBy { it.uniqueId }
        val byId = HomeUiBuilder.build(all, emptyMap(), areas, RoomFilter.All, link, Locale.US).cards.associateBy { it.id }
        assertTrue(byId.getValue("fx").hasSheet)
        assertTrue(byId.getValue("rgb").hasSheet)
        assertFalse(byId.getValue("plain").hasSheet)
    }
```

Add any missing imports (`com.gekkotron.oikos.model.EntityState`, `StateValue`, `Capabilities`, `kotlin.test.assertFalse`) that the file does not already have.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeUiBuilderTest*'`
Expected: compilation FAIL (`companions` unresolved).

- [ ] **Step 3: Implement the model**

`HomeModels.kt`, in `CardUi` after `val hidden: Boolean,` add:

```kotlin
    /** A light's folded device controls (speed, palette…), shown in its sheet instead of as cards. */
    val companions: List<CardUi> = emptyList(),
```

Replace `HomeUiState.card`:

```kotlin
    fun card(id: String): CardUi? =
        (cards + listOfNotNull(hero)).firstNotNullOfOrNull { c -> c.takeIf { it.id == id } ?: c.companions.firstOrNull { it.id == id } }
```

- [ ] **Step 4: Implement the builder**

In `HomeUiBuilder.kt`:

1. Add `private val COMPANION_KINDS = setOf(Kind.NUMBER, Kind.SELECT)` next to `SHEET_KINDS`.
2. In `build`, after `val standalone = …`, add:

```kotlin
        val children = entities.values.filter { it.partOf != null && it.kind in COMPANION_KINDS }.groupBy { it.partOf!! }
        fun companionsOf(e: Entity): List<CardUi> = if (e.kind != Kind.LIGHT) emptyList() else children[e.uniqueId].orEmpty()
            .map { c -> card(c, companionName(c, labels), states[c.uniqueId], areaNames, locale, emptyList()) }
            .sortedWith(compareBy({ it.name.lowercase(locale) }, { it.id }))
```

3. Change both `card(it, names.getValue(it.uniqueId), states[it.uniqueId], areaNames, locale)` calls (grid cards and hero) to pass `companionsOf(it)` as a sixth argument.
4. Add next to `displayName`:

```kotlin
    /** Inside a light's sheet the device name is redundant: "Effect speed", not "Leds Effect speed". */
    private fun companionName(entity: Entity, labels: LabelText): String {
        if (entity.ui.name != null) return entity.name
        val label = entity.label ?: return entity.name
        return labels(label) ?: label
    }
```

5. Change `card`'s signature to `private fun card(entity: Entity, name: String, state: EntityState?, areaNames: Map<String, String>, locale: Locale, companions: List<CardUi>): CardUi`, replace its `hasSheet = …` line with

```kotlin
            hasSheet = entity.kind in SHEET_KINDS || (entity.kind == Kind.LIGHT && hasLightSheet(entity, companions)),
```

and add `companions = companions,` as the last argument of the `CardUi(...)` call. Add:

```kotlin
    private fun hasLightSheet(entity: Entity, companions: List<CardUi>): Boolean {
        val caps = entity.capabilities
        return caps.brightness || caps.effects.isNotEmpty() || caps.colorModes.isNotEmpty() || companions.isNotEmpty()
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeUiBuilderTest*' --tests '*HomeViewModelTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): light cards carry their folded device controls as companions

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Light sheet — effect chips and companion controls

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/EffectNames.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt` (signature, `LightControls`)
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HomeScreen.kt:183-188` (the `ControlSheet(` call)
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/EffectNamesTest.kt`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/LightSheetTest.kt`

**Interfaces:**
- Consumes: `CardUi.companions` (Task 7), `StateValue.Light.effect`, `Command.TurnOn(effect = …)` (Task 3).
- Produces: `internal fun ControlSheet(card: CardUi, onSend: (String, Command) -> Unit, onCustomize: () -> Unit, onDismiss: () -> Unit)` — commands now carry the target id (a companion's id for its controls); `object EffectNames { fun label(raw: String): String }`; string `sheet_effect`.

- [ ] **Step 1: Write the failing tests**

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/presentation/EffectNamesTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import org.junit.Test
import kotlin.test.assertEquals

class EffectNamesTest {
    @Test
    fun `effect names are tidied, not translated`() {
        assertEquals("Fire 1d", EffectNames.label("fire_1d"))
        assertEquals("Plasma 2d", EffectNames.label("plasma_2d"))
        assertEquals("Rainbow", EffectNames.label("rainbow"))
        assertEquals("Color Loop", EffectNames.label("Color Loop"))
        assertEquals("", EffectNames.label(""))
    }
}
```

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/LightSheetTest.kt` (copy the imports, `@RunWith`, `@Config(qualifiers = "w411dp-h914dp")` and rule setup from `HomeScreenTest.kt`):

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Hs
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

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class LightSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val sends = mutableListOf<Pair<String, Command>>()

    private val entities = listOf(
        testEntity(
            "strip", Kind.LIGHT, "Strip", deviceId = "zl",
            capabilities = Capabilities(brightness = true, colorModes = setOf("xy"), effects = listOf("solid", "fire_1d", "rainbow")),
        ),
        testEntity("speed", Kind.NUMBER, "Effect speed", deviceId = "zl", partOf = "strip", capabilities = Capabilities(min = 0.0, max = 255.0, step = 1.0)),
        testEntity("lamp", Kind.LIGHT, "Lamp", capabilities = Capabilities(brightness = true)),
    ).associateBy { it.uniqueId }
    private val states = mapOf(
        "strip" to EntityState("strip", StateValue.Light(true, 120, "fire_1d", Hs(208.0, 0.78))),
        "speed" to EntityState("speed", StateValue.Numeric(38.0)),
        "lamp" to EntityState("lamp", StateValue.Light(true, 200)),
    )

    private fun show() = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            HomeScreen(
                state = HomeUiBuilder.build(entities, states, emptyList(), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US),
                onSelect = {}, onTap = {}, onSend = { id, c -> sends += id to c },
                now = LocalTime.NOON,
            )
        }
    }

    @Test
    fun `the strip's sheet shows its effects with the current one selected, and a tap sends it`() {
        show()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithText("Effect").assertExists()
        compose.onNodeWithText("Fire 1d").assertIsSelected()
        compose.onNodeWithText("Rainbow").assertIsNotSelected().performClick()
        assertEquals("strip" to Command.TurnOn(effect = "rainbow"), sends.last())
    }

    @Test
    fun `the device's numbers are in the sheet, not on the grid, and send to their own id`() {
        show()
        compose.onNodeWithTag("card-speed").assertDoesNotExist()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithText("Effect speed").performScrollTo().assertExists()
        compose.onNodeWithText("38").assertExists()
    }

    @Test
    fun `a dimmable light without effects shows no effect section`() {
        show()
        compose.onNodeWithTag("card-lamp").performTouchInput { longClick() }
        compose.onNodeWithText("Brightness").assertExists()
        compose.onNodeWithText("Effect").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*EffectNamesTest*' --tests '*LightSheetTest*'`
Expected: compilation FAIL (`EffectNames` unresolved); after a stub, `LightSheetTest` fails on the missing "Effect" text.

- [ ] **Step 3: Implement**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/presentation/EffectNames.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.presentation

import java.util.Locale

/** Effect names as published ("fire_1d") → a chip label ("Fire 1d"); tidied, never translated (LED spec §4.2). */
object EffectNames {
    fun label(raw: String): String = raw.replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
}
```

Strings — `values/strings.xml`, next to `sheet_brightness`:

```xml
    <string name="sheet_effect">Effect</string>
```

`values-fr/strings.xml`:

```xml
    <string name="sheet_effect">Effet</string>
```

`ControlSheet.kt`:

1. Change the signature to `internal fun ControlSheet(card: CardUi, onSend: (String, Command) -> Unit, onCustomize: () -> Unit, onDismiss: () -> Unit)`.
2. Inside, before `ModalBottomSheet`, add `val send: (Command) -> Unit = { onSend(card.id, it) }` and pass `send` to every `*Controls(card, …)` except `LightControls`, which gets `LightControls(card, onSend)`.
3. Replace `LightControls` with:

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightControls(card: CardUi, onSend: (String, Command) -> Unit) {
    val light = card.state as? StateValue.Light
    val send: (Command) -> Unit = { onSend(card.id, it) }
    Row(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sheet_power), Modifier.weight(1f))
        Switch(checked = light?.on == true, onCheckedChange = { send(if (it) Command.TurnOn() else Command.TurnOff) })
    }
    if (card.capabilities.brightness) {
        var value by remember(light?.brightness) { mutableFloatStateOf((light?.brightness ?: 255).toFloat()) }
        Text(stringResource(R.string.sheet_brightness))
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..255f,
            onValueChangeFinished = { send(Command.TurnOn(brightness = value.roundToInt())) },
        )
    }
    if (card.capabilities.effects.isNotEmpty()) {
        Text(stringResource(R.string.sheet_effect))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (effect in card.capabilities.effects) {
                FilterChip(
                    selected = light?.effect == effect,
                    onClick = { send(Command.TurnOn(effect = effect)) },
                    label = { Text(EffectNames.label(effect)) },
                )
            }
        }
    }
    for (companion in card.companions) {
        Text(companion.name)
        when (companion.kind) {
            Kind.NUMBER -> NumberControls(companion) { onSend(companion.id, it) }
            Kind.SELECT -> SelectControls(companion) { onSend(companion.id, it) }
            else -> Unit
        }
    }
}
```

Add imports `androidx.compose.foundation.layout.ExperimentalLayoutApi`, `androidx.compose.foundation.layout.FlowRow`, `com.gekkotron.oikos.feature.home.presentation.EffectNames`. If the compiler reports `FlowRow` is no longer experimental (opt-in unnecessary warning), drop the `@OptIn` and the `ExperimentalLayoutApi` import.

`HomeScreen.kt`, in the `ControlSheet(` call, replace `onSend = { onSend(sheetCard.id, it) },` with `onSend = onSend,`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS (all of `:feature:home`, including the existing `HomeScreenTest` sheet tests).

- [ ] **Step 5: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): light sheet offers effects and the device's own controls

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Hue wheel

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/WheelMath.kt`
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HueWheel.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/ControlSheet.kt` (`LightControls`, colour section)
- Modify: `feature/home/src/main/res/values/strings.xml`, `feature/home/src/main/res/values-fr/strings.xml`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WheelMathTest.kt`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/LightSheetTest.kt`

**Interfaces:**
- Consumes: `Hs` (Task 2), `StateValue.Light.color`, `Command.TurnOn(color = …)` (Task 3), `LightControls` (Task 8).
- Produces: `internal object WheelMath { fun hsAt(x: Float, y: Float, diameter: Float): Hs; fun pointOf(hs: Hs, diameter: Float): Pair<Float, Float> }`; `@Composable internal fun HueWheel(color: Hs?, onPick: (Hs) -> Unit, modifier: Modifier = Modifier)`; strings `sheet_color`, `sheet_color_state`.

- [ ] **Step 1: Write the failing tests**

`feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/WheelMathTest.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.model.Hs
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WheelMathTest {
    private fun near(expected: Double, actual: Double, tolerance: Double = 0.01) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, got $actual")

    @Test
    fun `the centre is white and the rim is fully saturated`() {
        assertEquals(Hs(0.0, 0.0), WheelMath.hsAt(50f, 50f, 100f))
        WheelMath.hsAt(50f, 0f, 100f).let { near(0.0, it.hue); near(1.0, it.saturation) }
    }

    @Test
    fun `hue runs clockwise from the top`() {
        near(90.0, WheelMath.hsAt(100f, 50f, 100f).hue)
        near(180.0, WheelMath.hsAt(50f, 100f, 100f).hue)
        near(270.0, WheelMath.hsAt(0f, 50f, 100f).hue)
    }

    @Test
    fun `outside the rim clamps to full saturation and the seam stays below 360`() {
        near(1.0, WheelMath.hsAt(200f, 50f, 100f).saturation)
        assertTrue(WheelMath.hsAt(49.9999f, 0f, 100f).hue < 360.0)
    }

    @Test
    fun `pointOf inverts hsAt`() {
        val (x, y) = WheelMath.pointOf(WheelMath.hsAt(70f, 20f, 100f), 100f)
        near(70.0, x.toDouble(), 0.05)
        near(20.0, y.toDouble(), 0.05)
    }
}
```

Add to `LightSheetTest.kt` (imports: `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.test.click`, `androidx.compose.ui.test.onNodeWithContentDescription`, `kotlin.test.assertTrue`):

```kotlin
    @Test
    fun `tapping the wheel sends a color`() {
        show()
        compose.onNodeWithTag("card-strip").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Color").performScrollTo().performTouchInput { click(Offset(width / 2f, 1f)) }
        val (id, command) = sends.last()
        assertEquals("strip", id)
        val color = (command as Command.TurnOn).color!!
        assertTrue(color.hue < 2.0 || color.hue > 358.0, "hue ${color.hue}")
        assertTrue(color.saturation > 0.95, "saturation ${color.saturation}")
    }

    @Test
    fun `no wheel without a color mode`() {
        show()
        compose.onNodeWithTag("card-lamp").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Color").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*WheelMathTest*' --tests '*LightSheetTest*'`
Expected: compilation FAIL (`WheelMath` unresolved).

- [ ] **Step 3: Implement `WheelMath`**

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/WheelMath.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.model.Hs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Hue wheel geometry (LED spec §4.3): hue 0° at the top, clockwise; saturation = distance from the centre / radius. */
internal object WheelMath {
    fun hsAt(x: Float, y: Float, diameter: Float): Hs {
        val r = diameter / 2.0
        val dx = x - r
        val dy = y - r
        val distance = hypot(dx, dy)
        if (r <= 0.0 || distance == 0.0) return Hs(0.0, 0.0)
        var hue = Math.toDegrees(atan2(dx, -dy))
        if (hue < 0) hue += 360.0
        if (hue >= 360.0) hue -= 360.0
        return Hs(hue, (distance / r).coerceAtMost(1.0))
    }

    fun pointOf(hs: Hs, diameter: Float): Pair<Float, Float> {
        val r = diameter / 2.0
        val a = Math.toRadians(hs.hue)
        return Pair((r + hs.saturation * r * sin(a)).toFloat(), (r - hs.saturation * r * cos(a)).toFloat())
    }
}
```

- [ ] **Step 4: Implement `HueWheel`**

Strings — `values/strings.xml`:

```xml
    <string name="sheet_color">Color</string>
    <string name="sheet_color_state">Hue %1$d°, saturation %2$d %%</string>
```

`values-fr/strings.xml`:

```xml
    <string name="sheet_color">Couleur</string>
    <string name="sheet_color_state">Teinte %1$d°, saturation %2$d %%</string>
```

`feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/HueWheel.kt`:

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.model.Hs
import kotlin.math.roundToInt

/** Sweep stops: a sweep gradient starts at 3 o'clock, the wheel's hue 0 is at 12, so stop i is hue 30·i + 90. */
private val SWEEP = List(13) { i -> Color.hsv(((i * 30 + 90) % 360).toFloat(), 1f, 1f) }

/**
 * Hue around, saturation to the centre (LED spec §4.3). The marker follows [color]; a tap or a drag moves it
 * locally and [onPick] fires once, on the tap or at the end of the drag.
 */
@Composable
internal fun HueWheel(color: Hs?, onPick: (Hs) -> Unit, modifier: Modifier = Modifier) {
    var picked by remember { mutableStateOf(color ?: Hs(0.0, 0.0)) }
    LaunchedEffect(color) { if (color != null) picked = color }
    val pick by rememberUpdatedState(onPick)
    val description = stringResource(R.string.sheet_color)
    val state = stringResource(R.string.sheet_color_state, picked.hue.roundToInt() % 360, (picked.saturation * 100).roundToInt())
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 280.dp)
        Canvas(
            Modifier
                .size(side)
                .semantics { contentDescription = description; stateDescription = state }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        picked = WheelMath.hsAt(p.x, p.y, size.width.toFloat())
                        pick(picked)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, _ -> picked = WheelMath.hsAt(change.position.x, change.position.y, size.width.toFloat()) },
                        onDragEnd = { pick(picked) },
                    )
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(SWEEP, center), radius)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius), radius)
            val (x, y) = WheelMath.pointOf(picked, size.minDimension)
            drawCircle(Color.Black.copy(alpha = 0.35f), 13.5.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
            drawCircle(Color.White, 12.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
        }
    }
}
```

In `ControlSheet.kt` `LightControls`, after the companions loop:

```kotlin
    if (card.capabilities.colorModes.isNotEmpty()) {
        Text(stringResource(R.string.sheet_color))
        HueWheel(light?.color, onPick = { send(Command.TurnOn(color = it)) })
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: PASS. If the tap test lands outside the circle because of padding, keep the tap at `Offset(width / 2f, 1f)` on the Canvas node itself (it is the node carrying the content description), not on a parent.

- [ ] **Step 6: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): hue wheel in the light sheet

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Demo LED strip

**Files:**
- Modify: `core/demo/src/main/kotlin/com/gekkotron/oikos/demo/DemoHouse.kt`
- Test: `core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoHouseTest.kt`

**Interfaces:**
- Consumes: nothing new from Oikos code (the demo speaks MQTT JSON); exercised end to end by Tasks 3–9.

- [ ] **Step 1: Write the failing test**

`core/demo/src/test/kotlin/com/gekkotron/oikos/demo/DemoHouseTest.kt`:

```kotlin
package com.gekkotron.oikos.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DemoHouseTest {
    private val strip = "demo/bedroom/strip"

    @Test
    fun `the LED strip announces effects, a colour mode and two numbers on its device`() {
        val configs = DemoHouse().retainedMessages().filter { it.topic.endsWith("/config") }
        val light = configs.single { it.topic == "homeassistant/light/oikos_demo/demo_bedroom_strip/config" }
        assertTrue(""""effect_list":["solid","breathe","rainbow","candle"]""" in light.payload, light.payload)
        assertTrue(""""supported_color_modes":["xy"]""" in light.payload, light.payload)
        val numbers = configs.filter { it.topic.startsWith("homeassistant/number/oikos_demo/demo_bedroom_strip_") }
        assertEquals(2, numbers.size)
        assertTrue(numbers.all { "\"demo_led_strip\"" in it.payload })
    }

    @Test
    fun `strip commands are echoed into its state document`() {
        val house = DemoHouse()
        val speed = assertNotNull(house.onCommand("$strip/set/effect_speed", "40"))
        assertEquals(40, Json.parseToJsonElement(speed.payload).jsonObject.getValue("effect_speed").jsonPrimitive.int)
        val fx = assertNotNull(house.onCommand("$strip/set", """{"state":"ON","effect":"candle"}"""))
        val doc = Json.parseToJsonElement(fx.payload).jsonObject
        assertEquals(JsonPrimitive("candle"), doc["effect"])
        assertEquals(40, doc.getValue("effect_speed").jsonPrimitive.int)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:demo:test --tests '*DemoHouseTest*'`
Expected: FAIL — no strip config.

- [ ] **Step 3: Implement**

In `DemoHouse.init`, after the two `light(…)` lines:

```kotlin
        ledStrip("demo_bedroom_strip", "LED strip", "Bedroom", "demo/bedroom/strip")
```

Add next to `light(…)`:

```kotlin
    /** An addressable strip: a JSON light with effects and xy colour, plus speed and intensity numbers on the same device. */
    private fun ledStrip(id: String, device: String, area: String, topic: String) {
        val effects = listOf("solid", "breathe", "rainbow", "candle")
        config("light", id, device, null, area) {
            put("schema", "json")
            put("brightness", true)
            put("brightness_scale", 255)
            putJsonArray("supported_color_modes") { add("xy") }
            put("effect", true)
            putJsonArray("effect_list") { effects.forEach { add(it) } }
            put("state_topic", topic)
            put("command_topic", "$topic/set")
        }
        for ((field, label) in listOf("effect_speed" to "Effect speed", "effect_intensity" to "Effect intensity")) {
            config("number", "${id}_$field", device, label, area) {
                put("state_topic", topic)
                put("value_template", "{{ value_json.$field }}")
                put("command_topic", "$topic/set/$field")
                put("min", 0)
                put("max", 255)
                put("step", 1)
            }
            echo("$topic/set/$field", topic) { p -> p.toDoubleOrNull()?.let { obj(field to it.roundToInt().coerceIn(0, 255)) } }
        }
        doc(
            topic,
            buildJsonObject {
                put("state", "ON")
                put("brightness", 160)
                put("effect", "rainbow")
                put("color", buildJsonObject { put("x", 0.196); put("y", 0.203) })
                put("effect_speed", 128)
                put("effect_intensity", 128)
            },
        )
        echo("$topic/set", topic) { p -> runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() }
    }
```

- [ ] **Step 4: Run the demo and app tests**

Run: `./gradlew :core:demo:test :app:testDebugUnitTest`
Expected: PASS. If an `:app` test counts demo entities or cards, update its expected count by exactly the strip (+1 light card; the two numbers are folded and do not add cards) and say so in the commit message.

- [ ] **Step 5: Commit**

```bash
git add core/demo app
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(demo): bedroom LED strip with effects, colour, speed and intensity

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Whole-build check and on-device verification

**Files:** none new (fixes only, if the check finds something).

- [ ] **Step 1: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. A lone `DirectConnectionTesterTest` failure is the known flake: re-run `./gradlew :app:testDebugUnitTest --tests '*DirectConnectionTesterTest*'` once and report it if it still fails.

- [ ] **Step 2: Install on the Pixel 6a (serial 28281JEGR02735)**

Run: `./gradlew :app:installDebug && adb shell monkey -p com.gekkotron.oikos.debug -c android.intent.category.LAUNCHER 1`

- [ ] **Step 3: Drive it against the real strip**

With `adb exec-out screencap -p > "$SCRATCH/led-N.png"` after each action, and reading every screenshot:
1. Home: the `leds` card is there; no "Effect intensity", "Palette" or "Active count" cards in All or in the Julien room.
2. Long-press `leds`: Power, Brightness, Effect chips (9, the current one selected), Active count / Effect intensity / Palette sliders (and Effect speed once the converter is reloaded), the Color wheel with the marker at the strip's colour.
3. Tap "Rainbow": the chip selection moves and the strip changes. Drag the wheel to green and release: the strip turns green when the palette is 0.
4. Demo mode (if reachable from the link sheet) shows the "LED strip" with four effects and two sliders.

- [ ] **Step 4: Report**

Report to the user: test results, the screenshots' findings, that the ZigLED commit is local only, and the deploy steps for the converter (copy `z2m/gekkotron_zigled.mjs` into Z2M's `external_converters/`, restart Z2M, then the strip's sheet gains "Effect speed").
