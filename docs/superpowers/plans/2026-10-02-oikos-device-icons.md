# Device Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the emoji card glyphs with drawn duotone icons: a rolling shutter that follows its position, a door whose leaf and handle swing, an inward-opening window, a rolling garage door, and static icons for every other kind.

**Architecture:** `:core:designsystem` gets pure drawing composables (`icons/`) that take numbers and colors and know nothing about `Kind`. `:feature:home` gets `KindIcon.kt`: a pure `iconSpec(card)` mapping, an animation driver `rememberIconProgress(spec, animate)`, and the `KindIcon` composable that `EntityCard` calls. `OikosCard` gains an `accent` border for open doors and windows.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas, `Animatable`, `PathParser`), JUnit 4 + Robolectric + Compose UI test (`createComposeRule`).

**Spec:** `docs/superpowers/specs/2026-10-01-oikos-device-icons-design.md` (mockup `docs/design/mockups/device-icons-motion.html`).

## Global Constraints

- 24 × 24 grid; stroke 1.6, round caps and joins; surface fill alpha 0.22; roller box alpha 0.35; fixed glass alpha 0.10. Icons render at 24 dp.
- Neutral color = `LocalContentColor`; open accent = `OikosTheme.colors.warning`; closed accent = `OikosTheme.colors.connected`.
- Door/window/garage tween 500 ms `CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)`; shutter position tween 600 ms `CubicBezierEasing(0.45f, 0f, 0.55f, 1f)`; moving drift = full travel in 8 000 ms, linear.
- First composition never animates. Animator duration scale 0 → every change snaps, no drift.
- The icon is hidden from TalkBack (`clearAndSetSemantics {}`); the card's content description does not change.
- No change to `:core:model`, `:core:state`, `:core:source`, `:core:transport`.
- Commits: `git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit`, message ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. zsh: never put the `-c` flags in a variable.
- Spec refinement found while planning: on a Warm Minimal **on** card (espresso background), the dark amber `warning` is unreadable, so a lit bulb or plug uses `warning` only when `OikosTheme.colors.isDark`, and the card's content color (`LocalContentColor`) otherwise (Task 6).

## Review Focus

1. A door that flaps open → closed → open faster than 500 ms must swing from where it is, never jump to an end (pinned in Task 5: retarget mid-tween).
2. A cover that reports a position outside 0..100 (some Z2M devices send 101 or −1) draws clamped (pinned in Task 4).
3. A cover with a position but state STOPPED or UNKNOWN uses the position (pinned in Task 4).
4. An unavailable door or window draws neutral and closed, and its card gets no accent border (pinned in Task 4 and Task 6).
5. A shutter told OPENING while already fully open must not stall or crash on a zero-length drift (pinned in Task 5).

---

## File structure

| File | Responsibility |
|---|---|
| Create `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/IconGeometry.kt` | Grid constants, the door/handle/sash corner sets, point interpolation |
| Create `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/IconCanvas.kt` | The 24-unit canvas and the shared stroke/surface draw helpers |
| Create `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/OpeningIcons.kt` | `ShutterIcon`, `GarageIcon`, `DoorIcon`, `WindowIcon` |
| Create `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/StaticIcon.kt` | `OikosGlyph` enum (SVG path data) and `StaticIcon` |
| Create `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/OikosIconsPreview.kt` | Preview sheet, both themes |
| Modify `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/OikosCard.kt` | `accent` parameter, `cardBorderColor()` |
| Create `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt` | `IconSpec`, `iconSpec()`, `rememberIconProgress()`, `KindIcon()`, `animationsEnabled()` |
| Delete `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindGlyphs.kt` | Replaced by `KindIcon.kt` |
| Modify `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt` | Call `KindIcon`, pass `accent` |
| Tests | `core/designsystem/src/test/.../icons/IconGeometryTest.kt`, `.../icons/IconsSmokeTest.kt`, `.../OikosCardTest.kt` (extend), `feature/home/src/test/.../ui/IconSpecTest.kt`, `.../ui/IconProgressTest.kt`, `.../ui/EntityCardIconTest.kt` |

Test commands: designsystem `./gradlew :core:designsystem:testDebugUnitTest`, home `./gradlew :feature:home:testDebugUnitTest`, everything `./gradlew check`.

---

### Task 1: Icon geometry

**Files:**
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/IconGeometry.kt`
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/IconGeometryTest.kt`

**Interfaces:**
- Produces: `internal object IconGeometry` with `GRID`, `STROKE`, `FILL_ALPHA`, `BOX_ALPHA`, `GLASS_ALPHA`, `SHUTTER_TRAVEL`, `GARAGE_TRAVEL`, `DOOR_LEAF_CLOSED/OPEN`, `DOOR_HANDLE_CLOSED/OPEN`, `SASH_CLOSED/OPEN` (all `List<Offset>`), and `fun between(a: List<Offset>, b: List<Offset>, t: Float): List<Offset>` (t clamped to 0..1).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gekkotron.oikos.designsystem.icons

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconGeometryTest {
    @Test
    fun `between interpolates every corner and clamps t`() {
        assertEquals(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 0f))
        assertEquals(IconGeometry.DOOR_LEAF_OPEN, IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 1f))
        assertEquals(IconGeometry.DOOR_LEAF_OPEN, IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 1.7f))
        assertEquals(Offset(15.75f, 4.25f), IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, 0.5f)[1])
    }

    @Test
    fun `the door handle stays on the leaf at every step of the swing`() {
        for (step in 0..10) {
            val t = step / 10f
            val leaf = IconGeometry.between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, t)
            IconGeometry.between(IconGeometry.DOOR_HANDLE_CLOSED, IconGeometry.DOOR_HANDLE_OPEN, t).forEach { p ->
                assertTrue(inside(p, leaf), "handle point $p leaves the leaf at t=$t")
            }
        }
    }

    @Test
    fun `the open window sash grows past the frame toward the viewer`() {
        val open = IconGeometry.SASH_OPEN
        assertTrue(open.minOf { it.y } < 3f && open.maxOf { it.y } > 21f)
        assertEquals(4f, open.minOf { it.x }, "the hinge stays on the frame")
    }

    /** Convex polygon, corners in order: the point is on the same side of every edge. */
    private fun inside(p: Offset, poly: List<Offset>): Boolean {
        val signs = poly.indices.map { i ->
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        }
        return signs.all { it >= 0f } || signs.all { it <= 0f }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*IconGeometryTest*'`
Expected: compilation FAILS, `Unresolved reference: IconGeometry`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.gekkotron.oikos.designsystem.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp

/** Device icon geometry on a 24-unit grid (device-icons spec §2). */
internal object IconGeometry {
    const val GRID = 24f
    const val STROKE = 1.6f
    const val FILL_ALPHA = 0.22f
    const val BOX_ALPHA = 0.35f
    const val GLASS_ALPHA = 0.10f

    /** Shutter curtain travel: the opening is `y 6.5–21`. */
    const val SHUTTER_TRAVEL = 14.5f

    /** Garage curtain travel: the opening is `y 11–21`. */
    const val GARAGE_TRAVEL = 10f

    val DOOR_LEAF_CLOSED = listOf(Offset(6f, 3f), Offset(18f, 3f), Offset(18f, 21f), Offset(6f, 21f))
    val DOOR_LEAF_OPEN = listOf(Offset(6f, 3f), Offset(13.5f, 5.5f), Offset(13.5f, 21.5f), Offset(6f, 21f))

    /** Both ends sit at fixed positions on the leaf (80 % / 63 % across, 55 % down), so the handle never leaves it. */
    val DOOR_HANDLE_CLOSED = listOf(Offset(15.6f, 12.9f), Offset(13.6f, 12.9f))
    val DOOR_HANDLE_OPEN = listOf(Offset(12f, 14f), Offset(10.75f, 13.77f))

    /** The left sash swings inward: its free edge comes toward the viewer and grows past the frame. */
    val SASH_CLOSED = listOf(Offset(4f, 3f), Offset(12f, 3f), Offset(12f, 21f), Offset(4f, 21f))
    val SASH_OPEN = listOf(Offset(4f, 3f), Offset(8.5f, 1f), Offset(8.5f, 23f), Offset(4f, 21f))

    fun between(a: List<Offset>, b: List<Offset>, t: Float): List<Offset> {
        val f = t.coerceIn(0f, 1f)
        return a.zip(b) { p, q -> lerp(p, q, f) }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*IconGeometryTest*'`
Expected: 3 tests PASS. (If `inside` fails at t=1 for the handle end at `(10.75, 13.77)` because of float rounding on the edge, the geometry is wrong, not the test: re-check the spec §2.2 values.)

- [ ] **Step 5: Commit**

```bash
git add core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/IconGeometry.kt core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/IconGeometryTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(designsystem): device icon geometry - door leaf, handle and inward sash

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Opening icons (shutter, garage, door, window)

**Files:**
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/IconCanvas.kt`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/OpeningIcons.kt`
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/IconsSmokeTest.kt`

**Interfaces:**
- Consumes: `IconGeometry` (Task 1).
- Produces (public):
  - `fun ShutterIcon(openFraction: Float, modifier: Modifier = Modifier)`
  - `fun GarageIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier)`
  - `fun DoorIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier)`
  - `fun WindowIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier)`
  - `val OikosIconSize: Dp = 24.dp`
- Produces (internal, for Task 3): `IconCanvas(modifier, draw: DrawScope.(Color) -> Unit)`, `DrawScope.outline(path, color, width)`, `DrawScope.surface(path, color, alpha)`, `polygon(points): Path`.

- [ ] **Step 1: Write the failing smoke test**

```kotlin
package com.gekkotron.oikos.designsystem.icons

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconsSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every opening icon draws at 0, half and full, out of range included, at 24 dp`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                Row {
                    ShutterIcon(0.6f, Modifier.testTag("shutter"))
                    for (t in listOf(-0.5f, 0f, 0.5f, 1f, 1.5f)) {
                        ShutterIcon(t)
                        GarageIcon(t, Color.Yellow)
                        DoorIcon(t, Color.Yellow)
                        WindowIcon(t, null)
                    }
                }
            }
        }
        compose.onNodeWithTag("shutter").assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*IconsSmokeTest*'`
Expected: compilation FAILS, `Unresolved reference: ShutterIcon`.

- [ ] **Step 3: Write the canvas helpers**

`IconCanvas.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val OikosIconSize: Dp = 24.dp

/**
 * A square canvas whose [draw] works in 24 grid units (device-icons spec §2), handed the neutral
 * color (the card's content color). A caller's size modifier wins over the 24 dp default.
 */
@Composable
internal fun IconCanvas(modifier: Modifier, draw: DrawScope.(neutral: Color) -> Unit) {
    val neutral = LocalContentColor.current
    Canvas(modifier.size(OikosIconSize)) {
        val s = size.minDimension / IconGeometry.GRID
        scale(s, s, pivot = Offset.Zero) { draw(neutral) }
    }
}

internal fun DrawScope.outline(path: Path, color: Color, width: Float = IconGeometry.STROKE) =
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** A duotone surface: a soft fill plus its outline. */
internal fun DrawScope.surface(path: Path, color: Color, alpha: Float = IconGeometry.FILL_ALPHA) {
    drawPath(path, color, alpha = alpha)
    outline(path, color)
}

internal fun polygon(points: List<Offset>, close: Boolean = true) = Path().apply {
    moveTo(points[0].x, points[0].y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    if (close) close()
}
```

- [ ] **Step 4: Write the opening icons**

`OpeningIcons.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import com.gekkotron.oikos.designsystem.icons.IconGeometry.BOX_ALPHA
import com.gekkotron.oikos.designsystem.icons.IconGeometry.GARAGE_TRAVEL
import com.gekkotron.oikos.designsystem.icons.IconGeometry.GLASS_ALPHA
import com.gekkotron.oikos.designsystem.icons.IconGeometry.SHUTTER_TRAVEL
import com.gekkotron.oikos.designsystem.icons.IconGeometry.between

/** A roller shutter (spec §2.1): the curtain is drawn closed and raised by [openFraction] (0 closed, 1 open). */
@Composable
fun ShutterIcon(openFraction: Float, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    val f = openFraction.coerceIn(0f, 1f)
    outline(Path().apply { addRoundRect(RoundRect(3.5f, 3f, 20.5f, 21f, CornerRadius(2f))) }, neutral)
    clipRect(3.5f, 6.5f, 20.5f, 21f) {
        translate(top = -SHUTTER_TRAVEL * f) {
            curtain(neutral, left = 3.5f, top = 6.5f, right = 20.5f, slats = listOf(9f, 11.5f, 14f, 16.5f, 19f))
        }
    }
    // Above the curtain, so a raised curtain disappears into the box.
    val box = RoundRect(3.5f, 3f, 20.5f, 6.5f, topLeft = CornerRadius(2f), topRight = CornerRadius(2f))
    surface(Path().apply { addRoundRect(box) }, neutral, BOX_ALPHA)
}

/** A rolling garage door (spec §2.4), open/closed accent on the curtain. */
@Composable
fun GarageIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    val f = openness.coerceIn(0f, 1f)
    outline(polygon(listOf(Offset(3f, 21f), Offset(3f, 9f), Offset(12f, 4f), Offset(21f, 9f), Offset(21f, 21f)), close = false), neutral)
    clipRect(6f, 11f, 18f, 21f) {
        translate(top = -GARAGE_TRAVEL * f) {
            curtain(accent ?: neutral, left = 6f, top = 11f, right = 18f, slats = listOf(13.5f, 16f, 18.5f))
        }
    }
    drawRect(neutral, Offset(6f, 9.5f), Size(12f, 1.5f), alpha = BOX_ALPHA)
}

/** A door seen from inside (spec §2.2): the leaf and its lever handle swing together by [openness]. */
@Composable
fun DoorIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    outline(polygon(listOf(Offset(6f, 21f), Offset(6f, 3f), Offset(18f, 3f), Offset(18f, 21f)), close = false), neutral)
    outline(polygon(listOf(Offset(4f, 21f), Offset(20f, 21f)), close = false), neutral)
    surface(polygon(between(IconGeometry.DOOR_LEAF_CLOSED, IconGeometry.DOOR_LEAF_OPEN, openness)), accent ?: neutral)
    val handle = between(IconGeometry.DOOR_HANDLE_CLOSED, IconGeometry.DOOR_HANDLE_OPEN, openness)
    drawLine(neutral, handle[0], handle[1], strokeWidth = 1.9f, cap = StrokeCap.Round)
}

/** A French window (spec §2.3): the left sash swings inward by [openness]; the right pane is fixed. */
@Composable
fun WindowIcon(openness: Float, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    outline(Path().apply { addRoundRect(RoundRect(4f, 3f, 20f, 21f, CornerRadius(1.5f))) }, neutral)
    drawRect(neutral, Offset(12f, 3f), Size(8f, 18f), alpha = GLASS_ALPHA)
    outline(polygon(listOf(Offset(12f, 12f), Offset(20f, 12f)), close = false), neutral)
    surface(polygon(between(IconGeometry.SASH_CLOSED, IconGeometry.SASH_OPEN, openness)), accent ?: neutral)
}

/** A closed curtain filling `left..right × top..21`: fill, slat lines, and a bottom rail. */
private fun DrawScope.curtain(color: Color, left: Float, top: Float, right: Float, slats: List<Float>) {
    drawRect(color, Offset(left, top), Size(right - left, 21f - top), alpha = IconGeometry.FILL_ALPHA)
    slats.forEach { y -> drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = 1f, alpha = 0.75f) }
    drawLine(color, Offset(left + 0.7f, 20.6f), Offset(right - 0.7f, 20.6f), strokeWidth = 1.8f, cap = StrokeCap.Round)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*IconsSmokeTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/IconCanvas.kt core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/OpeningIcons.kt core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/IconsSmokeTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(designsystem): rolling shutter and garage, swinging door, inward window icons

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Static icons, card accent border, preview sheet

**Files:**
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/StaticIcon.kt`
- Create: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/icons/OikosIconsPreview.kt`
- Modify: `core/designsystem/src/main/kotlin/com/gekkotron/oikos/designsystem/components/OikosCard.kt` (signature at lines 50-58, border at line 101)
- Test: `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/icons/IconsSmokeTest.kt` (add a test), `core/designsystem/src/test/kotlin/com/gekkotron/oikos/designsystem/OikosCardTest.kt` (add a test)

**Interfaces:**
- Consumes: `IconCanvas`, `outline`, `surface` (Task 2).
- Produces: `enum class OikosGlyph { BULB, PLUG, SNOWFLAKE, THERMOMETER, DROP, BOLT, TREND, WALKER, FLAME, SHIELD, SLIDER, LIST, PLAY, DOT }`; `fun StaticIcon(glyph: OikosGlyph, accent: Color? = null, modifier: Modifier = Modifier)`; `OikosCard(..., accent: Boolean = false, ...)`; `internal fun cardBorderColor(colors: OikosColors, on: Boolean, accent: Boolean): Color`.

- [ ] **Step 1: Write the failing tests**

Add to `IconsSmokeTest`:

```kotlin
    @Test
    fun `every static glyph draws, with and without an accent`() {
        compose.setContent {
            OikosTheme(ThemeChoice.WARM_MINIMAL) {
                Row {
                    OikosGlyph.entries.forEach { glyph ->
                        StaticIcon(glyph)
                        StaticIcon(glyph, Color.Yellow)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
```

Add to `OikosCardTest`:

```kotlin
    @Test
    fun `the accent border is the warning color, only when the card is not on`() {
        val c = NightGlassColors
        assertEquals(c.warning.copy(alpha = 0.35f), cardBorderColor(c, on = false, accent = true))
        assertEquals(c.cardOnBorder, cardBorderColor(c, on = true, accent = true))
        assertEquals(c.cardBorder, cardBorderColor(c, on = false, accent = false))
    }
```

with imports `com.gekkotron.oikos.designsystem.components.cardBorderColor`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:designsystem:testDebugUnitTest --tests '*IconsSmokeTest*' --tests '*OikosCardTest*'`
Expected: compilation FAILS, `Unresolved reference: OikosGlyph` and `cardBorderColor`.

- [ ] **Step 3: Write `StaticIcon.kt`**

```kotlin
package com.gekkotron.oikos.designsystem.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/** One stroke of a glyph in SVG path syntax on the 24-unit grid; a surface is filled as well as outlined. */
private class Part(val d: String, val surface: Boolean)

private fun line(d: String) = Part(d, surface = false)
private fun area(d: String) = Part(d, surface = true)

/** Static duotone glyphs (spec §2.5). The accent, when given, fills the surfaces (a lit bulb, a live plug). */
enum class OikosGlyph(private val parts: List<Part>) {
    BULB(listOf(area("M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"), line("M9.5 18.5h5M10.5 21h3"))),
    PLUG(listOf(area("M7 9h10v3a5 5 0 0 1-10 0z"), line("M9.5 4v5M14.5 4v5M12 17v4"))),
    SNOWFLAKE(listOf(line("M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9.5 4.5 12 7l2.5-2.5M9.5 19.5 12 17l2.5 2.5"))),
    THERMOMETER(listOf(area("M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z"), line("M12 9v7"))),
    DROP(listOf(area("M12 3.5c3.5 4.2 6 7.6 6 10.5a6 6 0 0 1-12 0c0-2.9 2.5-6.3 6-10.5z"))),
    BOLT(listOf(area("M13 3 5.5 13.5H12L11 21l7.5-10.5H12z"))),
    TREND(listOf(line("M4 19h16M5 15l4-4 3 3 6-6M15 8h3v3"))),
    WALKER(listOf(area("M14 5.5a1.75 1.75 0 1 1-3.5 0 1.75 1.75 0 0 1 3.5 0z"), line("M12 9l-2.5 4.5 3 2.5V21M12 9l3 3.5 2.5.5M9.5 13.5 7 21M10.5 10.5 7.5 12"))),
    FLAME(listOf(area("M12 3c3 3.5 6 6.4 6 10.5a6 6 0 0 1-12 0c0-2.4 1.2-4.3 3-5.8.2 1.8 1 3 2.3 3.6C10.6 8.5 10.9 5.6 12 3z"))),
    SHIELD(listOf(area("M12 3 19 6v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"))),
    SLIDER(listOf(line("M4 8h16M4 16h16"), area("M10 8a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM18 16a2 2 0 1 1-4 0 2 2 0 0 1 4 0z"))),
    LIST(listOf(line("M9 7h11M9 12h11M9 17h11M5 7h1M5 12h1M5 17h1"))),
    PLAY(listOf(area("M8 5.5v13l10.5-6.5z"))),
    DOT(listOf(area("M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0z"))),
    ;

    /** Parsed once per glyph. */
    internal val paths: List<Pair<Path, Boolean>> by lazy { parts.map { PathParser().parsePathString(it.d).toPath() to it.surface } }
}

@Composable
fun StaticIcon(glyph: OikosGlyph, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    glyph.paths.forEach { (path, isSurface) ->
        if (isSurface) surface(path, accent ?: neutral) else outline(path, neutral)
    }
}
```

- [ ] **Step 4: Add the accent to `OikosCard`**

In `OikosCard.kt`, add the parameter after `on`:

```kotlin
    on: Boolean = false,
    /** A warm border without the "on" gradient: an open door or window (device-icons spec §2). */
    accent: Boolean = false,
```

Replace line 101 `.border(1.dp, if (on) c.cardOnBorder else c.cardBorder, shape)` with:

```kotlin
                .border(1.dp, cardBorderColor(c, on, accent), shape)
```

and add at the end of the file (import `androidx.compose.ui.graphics.Color` and `com.gekkotron.oikos.designsystem.OikosColors`):

```kotlin
internal fun cardBorderColor(colors: OikosColors, on: Boolean, accent: Boolean): Color = when {
    on -> colors.cardOnBorder
    accent -> colors.warning.copy(alpha = 0.35f)
    else -> colors.cardBorder
}
```

- [ ] **Step 5: Write the preview sheet**

`OikosIconsPreview.kt`:

```kotlin
package com.gekkotron.oikos.designsystem.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice

@Composable
private fun Sheet(choice: ThemeChoice) = OikosTheme(choice) {
    val c = OikosTheme.colors
    Column(Modifier.background(c.backgroundBottom).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (t in listOf(0f, 0.5f, 1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val accent = if (t > 0f) c.warning else c.connected
                ShutterIcon(t, Modifier.size(40.dp))
                DoorIcon(t, accent, Modifier.size(40.dp))
                WindowIcon(t, accent, Modifier.size(40.dp))
                GarageIcon(t, accent, Modifier.size(40.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OikosGlyph.entries.forEach { StaticIcon(it, if (it == OikosGlyph.BULB) c.warning else null) }
        }
    }
}

@Preview(name = "Icons — Night Glass")
@Composable
private fun IconsNightGlass() = Sheet(ThemeChoice.NIGHT_GLASS)

@Preview(name = "Icons — Warm Minimal")
@Composable
private fun IconsWarmMinimal() = Sheet(ThemeChoice.WARM_MINIMAL)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: all PASS (the existing `OikosCardTest` cases too: the default `accent = false` keeps every border as before).

- [ ] **Step 7: Commit**

```bash
git add core/designsystem
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(designsystem): static duotone glyphs, card accent border, icon preview sheet

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `iconSpec` — which icon and which target

**Files:**
- Create: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt` (spec part only in this task)
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/IconSpecTest.kt`

**Interfaces:**
- Consumes: `OikosGlyph` (Task 3); `CardUi`, `CardValue` (existing, `presentation/HomeModels.kt`).
- Produces:

```kotlin
internal enum class SwingShape { DOOR, WINDOW, GARAGE }
internal sealed interface IconSpec {
    /** [target] null: no position and not OPEN/CLOSED, keep the drawn value. */
    data class Shutter(val target: Float?, val moving: CoverState?) : IconSpec
    /** [open] null: unknown or unavailable, drawn neutral and closed. */
    data class Swing(val shape: SwingShape, val open: Boolean?) : IconSpec
    data class Static(val glyph: OikosGlyph, val on: Boolean) : IconSpec
}
internal val IconSpec.openContact: Boolean
internal fun iconSpec(card: CardUi): IconSpec
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconSpecTest {
    private fun spec(kind: Kind, value: StateValue?, deviceClass: String? = null, available: Boolean = true): IconSpec {
        val e = testEntity("e", kind, deviceClass = deviceClass)
        val states = value?.let { mapOf("e" to EntityState("e", it, available = available)) } ?: emptyMap()
        val card = HomeUiBuilder.build(mapOf("e" to e), states, emptyList(), RoomFilter.All, LinkState.Connecting, Locale.US).card("e")!!
        return iconSpec(card)
    }

    @Test
    fun `a shutter targets its position, clamped, whatever its state`() {
        assertEquals(IconSpec.Shutter(0.6f, null), spec(Kind.COVER, StateValue.Cover(CoverState.OPEN, 60)))
        assertEquals(IconSpec.Shutter(0.3f, null), spec(Kind.COVER, StateValue.Cover(CoverState.STOPPED, 30)))
        assertEquals(IconSpec.Shutter(0.3f, null), spec(Kind.COVER, StateValue.Cover(CoverState.UNKNOWN, 30)))
        assertEquals(IconSpec.Shutter(1f, null), spec(Kind.COVER, StateValue.Cover(CoverState.OPEN, 101)))
        assertEquals(IconSpec.Shutter(0f, null), spec(Kind.COVER, StateValue.Cover(CoverState.CLOSED, -1)))
    }

    @Test
    fun `a shutter without position is fully open or closed, else keeps its drawing`() {
        assertEquals(IconSpec.Shutter(1f, null), spec(Kind.COVER, StateValue.Cover(CoverState.OPEN)))
        assertEquals(IconSpec.Shutter(0f, null), spec(Kind.COVER, StateValue.Cover(CoverState.CLOSED)))
        assertEquals(IconSpec.Shutter(null, null), spec(Kind.COVER, StateValue.Cover(CoverState.STOPPED)))
        assertEquals(IconSpec.Shutter(null, null), spec(Kind.COVER, null))
    }

    @Test
    fun `a moving shutter carries its direction`() {
        assertEquals(IconSpec.Shutter(0.4f, CoverState.OPENING), spec(Kind.COVER, StateValue.Cover(CoverState.OPENING, 40)))
        assertEquals(IconSpec.Shutter(null, CoverState.CLOSING), spec(Kind.COVER, StateValue.Cover(CoverState.CLOSING)))
    }

    @Test
    fun `doors, windows and garage doors swing with their contact`() {
        assertEquals(IconSpec.Swing(SwingShape.DOOR, true), spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "door"))
        assertEquals(IconSpec.Swing(SwingShape.DOOR, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "opening"))
        assertEquals(IconSpec.Swing(SwingShape.WINDOW, true), spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "window"))
        assertEquals(IconSpec.Swing(SwingShape.GARAGE, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "garage_door"))
    }

    @Test
    fun `an unknown or unavailable contact is neutral and not an open contact`() {
        assertEquals(IconSpec.Swing(SwingShape.DOOR, null), spec(Kind.BINARY_SENSOR, null, "door"))
        val unavailable = spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "door", available = false)
        assertEquals(IconSpec.Swing(SwingShape.DOOR, null), unavailable)
        assertFalse(unavailable.openContact)
        assertTrue(spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "window").openContact)
    }

    @Test
    fun `other kinds get their static glyph, lit only for lights and switches that are on`() {
        assertEquals(IconSpec.Static(OikosGlyph.BULB, true), spec(Kind.LIGHT, StateValue.Light(true, 180)))
        assertEquals(IconSpec.Static(OikosGlyph.PLUG, false), spec(Kind.SWITCH, StateValue.OnOff(false)))
        assertEquals(IconSpec.Static(OikosGlyph.THERMOMETER, false), spec(Kind.SENSOR, StateValue.Numeric(20.0), "temperature"))
        assertEquals(IconSpec.Static(OikosGlyph.DROP, false), spec(Kind.SENSOR, StateValue.Numeric(50.0), "humidity"))
        assertEquals(IconSpec.Static(OikosGlyph.BOLT, false), spec(Kind.SENSOR, StateValue.Numeric(5.0), "energy"))
        assertEquals(IconSpec.Static(OikosGlyph.TREND, false), spec(Kind.SENSOR, StateValue.Numeric(5.0), "pm25"))
        assertEquals(IconSpec.Static(OikosGlyph.WALKER, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(true), "motion"))
        assertEquals(IconSpec.Static(OikosGlyph.FLAME, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "smoke"))
        assertEquals(IconSpec.Static(OikosGlyph.DOT, false), spec(Kind.BINARY_SENSOR, StateValue.OnOff(false), "vibration"))
        assertEquals(IconSpec.Static(OikosGlyph.SNOWFLAKE, false), spec(Kind.CLIMATE, null))
        assertEquals(IconSpec.Static(OikosGlyph.SHIELD, false), spec(Kind.ALARM_PANEL, null))
        assertEquals(IconSpec.Static(OikosGlyph.SLIDER, false), spec(Kind.NUMBER, null))
        assertEquals(IconSpec.Static(OikosGlyph.LIST, false), spec(Kind.SELECT, null))
        assertEquals(IconSpec.Static(OikosGlyph.PLAY, false), spec(Kind.BUTTON, null))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*IconSpecTest*'`
Expected: compilation FAILS, `Unresolved reference: iconSpec`.

- [ ] **Step 3: Write the spec part of `KindIcon.kt`**

```kotlin
package com.gekkotron.oikos.feature.home.ui

import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.CardValue
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind

internal enum class SwingShape { DOOR, WINDOW, GARAGE }

/** What a card's icon draws (device-icons spec §4.3), before animation. */
internal sealed interface IconSpec {
    /** [target] null: no position and not OPEN/CLOSED, keep the drawn value. */
    data class Shutter(val target: Float?, val moving: CoverState?) : IconSpec

    /** [open] null: unknown or unavailable, drawn neutral and closed. */
    data class Swing(val shape: SwingShape, val open: Boolean?) : IconSpec

    data class Static(val glyph: OikosGlyph, val on: Boolean) : IconSpec
}

/** An open door, window or garage door: the card gets the accent border. */
internal val IconSpec.openContact: Boolean get() = this is IconSpec.Swing && open == true

internal fun iconSpec(card: CardUi): IconSpec = when (card.kind) {
    Kind.COVER -> shutter(card.value as? CardValue.Cover)
    Kind.BINARY_SENSOR -> swingShape(card.deviceClass)
        ?.let { IconSpec.Swing(it, if (card.available) (card.value as? CardValue.Contact)?.on else null) }
        ?: IconSpec.Static(binaryGlyph(card.deviceClass), on = false)
    Kind.LIGHT -> IconSpec.Static(OikosGlyph.BULB, card.on)
    Kind.SWITCH -> IconSpec.Static(OikosGlyph.PLUG, card.on)
    Kind.CLIMATE -> IconSpec.Static(OikosGlyph.SNOWFLAKE, on = false)
    Kind.SENSOR -> IconSpec.Static(sensorGlyph(card.deviceClass), on = false)
    Kind.ALARM_PANEL -> IconSpec.Static(OikosGlyph.SHIELD, on = false)
    Kind.NUMBER -> IconSpec.Static(OikosGlyph.SLIDER, on = false)
    Kind.SELECT -> IconSpec.Static(OikosGlyph.LIST, on = false)
    Kind.BUTTON -> IconSpec.Static(OikosGlyph.PLAY, on = false)
}

private fun shutter(v: CardValue.Cover?): IconSpec.Shutter {
    val target = when {
        v == null -> null
        v.position != null -> v.position.coerceIn(0, 100) / 100f
        v.state == CoverState.OPEN -> 1f
        v.state == CoverState.CLOSED -> 0f
        else -> null
    }
    return IconSpec.Shutter(target, v?.state?.takeIf { it == CoverState.OPENING || it == CoverState.CLOSING })
}

private fun swingShape(deviceClass: String?) = when (deviceClass) {
    "door", "opening" -> SwingShape.DOOR
    "window" -> SwingShape.WINDOW
    "garage_door" -> SwingShape.GARAGE
    else -> null
}

private fun sensorGlyph(deviceClass: String?) = when (deviceClass) {
    "temperature" -> OikosGlyph.THERMOMETER
    "humidity", "moisture" -> OikosGlyph.DROP
    "power", "energy" -> OikosGlyph.BOLT
    else -> OikosGlyph.TREND
}

private fun binaryGlyph(deviceClass: String?) = when (deviceClass) {
    "motion", "occupancy", "presence" -> OikosGlyph.WALKER
    "smoke" -> OikosGlyph.FLAME
    "moisture" -> OikosGlyph.DROP
    else -> OikosGlyph.DOT
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*IconSpecTest*'`
Expected: 6 tests PASS. If the unavailable case fails because `CardFormatter` already turns an unavailable value into something other than `Contact`, the `card.available` check still yields `null`: read the failure before changing anything.

- [ ] **Step 5: Commit**

```bash
git add feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/IconSpecTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): icon spec per card - shutter target and direction, contact swing, static glyphs

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `rememberIconProgress` — the animation driver

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt` (append)
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/IconProgressTest.kt`

**Interfaces:**
- Consumes: `IconSpec` (Task 4).
- Produces: `@Composable internal fun rememberIconProgress(spec: IconSpec, animate: Boolean): State<Float>`; constants `SWING_MS = 500`, `SHUTTER_MS = 600`, `DRIFT_FULL_MS = 8000`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.model.CoverState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class IconProgressTest {
    @get:Rule
    val compose = createComposeRule()

    private var spec by mutableStateOf<IconSpec>(IconSpec.Swing(SwingShape.DOOR, false))
    private var animate by mutableStateOf(true)
    private var drawn = -1f

    private fun start(initial: IconSpec) {
        spec = initial
        compose.mainClock.autoAdvance = false
        compose.setContent { drawn = rememberIconProgress(spec, animate).value }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    @Test
    fun `an open door draws open at once on first composition`() {
        start(IconSpec.Swing(SwingShape.DOOR, true))
        assertEquals(1f, drawn)
    }

    @Test
    fun `a shutter draws its position at once on first composition`() {
        start(IconSpec.Shutter(0.6f, null))
        assertEquals(0.6f, drawn)
    }

    @Test
    fun `a door swings open in 500 ms`() {
        start(IconSpec.Swing(SwingShape.DOOR, false))
        spec = IconSpec.Swing(SwingShape.DOOR, true)
        advance(250)
        assertTrue(drawn > 0.1f && drawn < 0.99f, "mid-swing at 250 ms, was $drawn")
        advance(300)
        assertEquals(1f, drawn)
    }

    @Test
    fun `a door that flaps back mid-swing turns around from where it is`() {
        start(IconSpec.Swing(SwingShape.DOOR, false))
        spec = IconSpec.Swing(SwingShape.DOOR, true)
        advance(200)
        val mid = drawn
        spec = IconSpec.Swing(SwingShape.DOOR, false)
        advance(16)
        assertTrue(drawn > 0f && drawn <= mid + 0.01f, "no jump: $mid then $drawn")
        advance(600)
        assertEquals(0f, drawn)
    }

    @Test
    fun `a moving shutter drifts at a full travel per 8 s, and a position update retargets`() {
        start(IconSpec.Shutter(0f, null))
        spec = IconSpec.Shutter(0f, CoverState.OPENING)
        advance(2000)
        assertEquals(0.25f, drawn, 0.03f)
        spec = IconSpec.Shutter(0.5f, CoverState.OPENING)
        advance(600)
        assertEquals(0.5f, drawn, 0.03f)
        advance(4000)
        assertEquals(1f, drawn, 0.03f)
    }

    @Test
    fun `opening a shutter that is already open neither stalls nor crashes`() {
        start(IconSpec.Shutter(1f, null))
        spec = IconSpec.Shutter(1f, CoverState.OPENING)
        advance(100)
        assertEquals(1f, drawn)
    }

    @Test
    fun `with animations off every change snaps and nothing drifts`() {
        animate = false
        start(IconSpec.Shutter(0f, null))
        spec = IconSpec.Shutter(0.7f, null)
        advance(16)
        assertEquals(0.7f, drawn)
        spec = IconSpec.Shutter(null, CoverState.OPENING)
        advance(4000)
        assertEquals(0.7f, drawn)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*IconProgressTest*'`
Expected: compilation FAILS, `Unresolved reference: rememberIconProgress`.

- [ ] **Step 3: Write the driver** (append to `KindIcon.kt`; add imports `androidx.compose.animation.core.Animatable`, `CubicBezierEasing`, `LinearEasing`, `tween`, `androidx.compose.runtime.Composable`, `LaunchedEffect`, `State`, `remember`, `kotlin.math.abs`, `kotlin.math.roundToInt`)

```kotlin
internal const val SWING_MS = 500
internal const val SHUTTER_MS = 600
internal const val DRIFT_FULL_MS = 8000
private val SWING_EASING = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)
private val SHUTTER_EASING = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

private fun IconSpec.target(): Float? = when (this) {
    is IconSpec.Shutter -> target
    is IconSpec.Swing -> if (open == true) 1f else 0f
    is IconSpec.Static -> null
}

/**
 * The drawn fraction for [spec] (spec §3): 0 closed … 1 open. The first composition draws the state at
 * once; later changes tween from wherever the drawing is, so a flapping contact turns around instead of
 * jumping. A moving shutter drifts toward its end stop at [DRIFT_FULL_MS] per full travel. With
 * [animate] false every change snaps and nothing drifts.
 */
@Composable
internal fun rememberIconProgress(spec: IconSpec, animate: Boolean): State<Float> {
    val progress = remember { Animatable(spec.target() ?: 0f) }
    LaunchedEffect(spec, animate) {
        val target = spec.target()
        if (!animate) {
            if (target != null) progress.snapTo(target)
            return@LaunchedEffect
        }
        when (spec) {
            is IconSpec.Swing -> progress.animateTo(target ?: 0f, tween(SWING_MS, easing = SWING_EASING))
            is IconSpec.Shutter -> {
                if (target != null) progress.animateTo(target, tween(SHUTTER_MS, easing = SHUTTER_EASING))
                val end = when (spec.moving) {
                    CoverState.OPENING -> 1f
                    CoverState.CLOSING -> 0f
                    else -> return@LaunchedEffect
                }
                val ms = (abs(end - progress.value) * DRIFT_FULL_MS).roundToInt()
                if (ms > 0) progress.animateTo(end, tween(ms, easing = LinearEasing))
            }
            is IconSpec.Static -> Unit
        }
    }
    return progress.asState()
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*IconProgressTest*'`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/IconProgressTest.kt
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): icon animation driver - swing, shutter tween and drift, reduced motion

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `KindIcon` on the cards

**Files:**
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindIcon.kt` (append `KindIcon`, move `animationsEnabled`)
- Delete: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindGlyphs.kt`
- Modify: `feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCard.kt:26,37,47,54`
- Test: `feature/home/src/test/kotlin/com/gekkotron/oikos/feature/home/ui/EntityCardIconTest.kt`

**Interfaces:**
- Consumes: `iconSpec`, `openContact`, `rememberIconProgress` (Tasks 4–5); `ShutterIcon`, `DoorIcon`, `WindowIcon`, `GarageIcon`, `StaticIcon`, `OikosCard(accent = …)` (Tasks 2–3).
- Produces: `@Composable internal fun KindIcon(spec: IconSpec, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the test that pins today's card semantics**

```kotlin
package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class EntityCardIconTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an open door card keeps its description and the icon adds no semantics`() {
        val door = testEntity("door", Kind.BINARY_SENSOR, "Front door", deviceClass = "door")
        val card = HomeUiBuilder.build(
            mapOf("door" to door), mapOf("door" to EntityState("door", StateValue.OnOff(true))),
            emptyList(), RoomFilter.All, LinkState.Connecting, Locale.US,
        ).card("door")!!
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { EntityCard(card, onClick = {}, onLongClick = {}) } }
        // R.string.card_description "%1$s, %2$s, %3$s" with value_contact_open and area_unassigned.
        compose.onNodeWithTag("card-door").assertContentDescriptionEquals("Front door, Open, Unassigned")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image), useUnmergedTree = true)
            .assertCountEquals(0)
    }
}
```

- [ ] **Step 2: Run it on the current code**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*EntityCardIconTest*'`
Expected: PASS on the current (emoji) code. This is a characterisation test: it must still pass, unchanged, after Step 4. If it fails here, the expected text is wrong; fix the string from `res/values/strings.xml`, not the code.

- [ ] **Step 3: Write `KindIcon` and move `animationsEnabled`** (append to `KindIcon.kt`; imports `android.provider.Settings`, `androidx.compose.animation.animateColorAsState`, `androidx.compose.material3.LocalContentColor`, `androidx.compose.runtime.getValue`, `androidx.compose.ui.Modifier`, `androidx.compose.ui.platform.LocalContext`, `androidx.compose.ui.platform.LocalInspectionMode`, `androidx.compose.ui.semantics.clearAndSetSemantics`, `com.gekkotron.oikos.designsystem.OikosTheme`, `com.gekkotron.oikos.designsystem.icons.*`)

```kotlin
/** The card icon, decorative: hidden from TalkBack, the card's own description carries the state. */
@Composable
internal fun KindIcon(spec: IconSpec, modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val progress by rememberIconProgress(spec, animate)
    val c = OikosTheme.colors
    val neutral = LocalContentColor.current
    val m = modifier.clearAndSetSemantics { }
    when (spec) {
        is IconSpec.Shutter -> ShutterIcon(progress, m)
        is IconSpec.Swing -> {
            val target = when (spec.open) {
                true -> c.warning
                false -> c.connected
                null -> neutral
            }
            val accent by animateColorAsState(target, tween(if (animate) SWING_MS else 0), label = "contact-accent")
            when (spec.shape) {
                SwingShape.DOOR -> DoorIcon(progress, accent, m)
                SwingShape.WINDOW -> WindowIcon(progress, accent, m)
                SwingShape.GARAGE -> GarageIcon(progress, accent, m)
            }
        }
        // Warm Minimal's on-card is espresso: its dark amber would vanish, so a lit glyph uses the card's content color there.
        is IconSpec.Static -> StaticIcon(spec.glyph, if (spec.on) (if (c.isDark) c.warning else neutral) else null, m)
    }
}

@Composable
private fun animationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return true
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}
```

- [ ] **Step 4: Wire `EntityCard` and delete `KindGlyphs.kt`**

In `EntityCard.kt`:
- remove line 37 (`val moving = …`) and the `CoverState` import (line 26);
- add `val icon = iconSpec(card)` in its place;
- add `accent = icon.openContact,` after `on = card.on,` (line 47);
- replace line 54 `KindGlyph(card.kind, card.deviceClass, moving)` with `KindIcon(icon)`.

Then delete the old file:

```bash
git rm feature/home/src/main/kotlin/com/gekkotron/oikos/feature/home/ui/KindGlyphs.kt
```

- [ ] **Step 5: Run the home tests**

Run: `./gradlew :feature:home:testDebugUnitTest`
Expected: all PASS, `EntityCardIconTest` included (same description as before, no image node). If a `HomeScreenTest` looked for an emoji text such as "🪟", that test was checking the old glyph: change it to check the card tag instead, and say so in the commit message.

- [ ] **Step 6: Run the whole check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (lint, detekt/ktlint if configured, all unit tests).

- [ ] **Step 7: Look at the previews and the demo**

Open `OikosIconsPreview.kt` in Android Studio and check both sheets, then install the debug build and open the demo house (`DemoHouse`: "Shutter", "Front door"). Open/close the shutter from its sheet and check that the curtain glides; the front door card should show the amber leaf and warm border while open.

- [ ] **Step 8: Commit**

```bash
git add feature/home
git -c user.name=Gekkotron -c user.email=60887050+Gekkotron@users.noreply.github.com commit -m "feat(home): drawn device icons on the cards - rolling shutter, swinging door and window, accent border when open

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
