package com.gekkotron.oikos.feature.security.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.security.on
import com.gekkotron.oikos.feature.security.presentation.SecurityUiBuilder
import com.gekkotron.oikos.feature.security.sensor
import com.gekkotron.oikos.history.LastChange
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertTrue

/** Last-change spec §5: the Openings section with durations, written to build/screenshots for a look. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SinceScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-14T10:00:00Z")

    private fun render(name: String) {
        val state = SecurityUiBuilder.build(
            listOf(
                sensor("front", "door", name = "Front door", area = "entrance"),
                sensor("garage", "garage_door", name = "Garage door", area = "garage"),
                sensor("bath", "window", name = "Bathroom window", area = "bathroom"),
                sensor("cellar", "door", name = "Cellar door", area = "cellar"),
            ).associateBy { it.uniqueId },
            listOf(on("front", false), on("garage", true), on("bath", false), on("cellar", false)).associateBy { it.uniqueId },
            listOf(Area("entrance", "Entrance"), Area("garage", "Garage"), Area("bathroom", "Bathroom"), Area("cellar", "Cellar")),
            LinkState.Connecting,
            mapOf(
                "front" to LastChange.At(now.minusSeconds(2 * 3_600)),
                "garage" to LastChange.At(now.minusSeconds(40 * 60)),
                "bath" to LastChange.At(now.minusSeconds(5 * 86_400)),
                "cellar" to LastChange.OlderThan(Duration.ofDays(30)),
            ),
        )
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { OikosBackground { SecurityScreen(state, now = now) } } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots/$name.png").also { it.parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step 7) for (y in 0 until bitmap.height step 7) seen += bitmap.getPixel(x, y)
        assertTrue(seen.size > 1, "blank render")
    }

    @Test
    @Config(qualifiers = "en-w411dp-h914dp-xxhdpi")
    fun english() {
        render("security-since-en")
        compose.onNodeWithText("Closed · 30+ d", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h914dp-xxhdpi")
    fun french() {
        render("security-since-fr")
        compose.onNodeWithText("Fermé · 30+ j", useUnmergedTree = true).assertExists()
    }
}
