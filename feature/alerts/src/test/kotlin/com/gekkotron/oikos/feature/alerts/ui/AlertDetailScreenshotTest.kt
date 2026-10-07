package com.gekkotron.oikos.feature.alerts.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.distinctColors
import com.gekkotron.oikos.feature.alerts.luminanceAt
import com.gekkotron.oikos.feature.alerts.presentation.DetailState
import com.gekkotron.oikos.feature.alerts.renderPng
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class AlertDetailScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private fun render(theme: ThemeChoice, name: String, state: DetailState, video: VideoState? = null) = run {
        compose.setContent {
            OikosTheme(theme) {
                OikosBackground {
                    AlertDetailContent(state, video, onPlay = {}, onAction = {}, onOpenDevice = {}, zone = ZoneOffset.UTC)
                }
            }
        }
        compose.renderPng(name)
    }

    @Test
    fun `picture, clip button and action buttons in Night Glass`() {
        val alert = DemoAlerts.all(now)[2].copy(videoUrl = "http://192.168.1.20:5000/clip.mp4")
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "alert-detail-night", DetailState.Found(alert, "garage_door"))
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) < 0.35f)
    }

    @Test
    fun `resolved alert in Warm Minimal`() {
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "alert-detail-warm", DetailState.Found(DemoAlerts.all(now)[2], null))
        assertTrue(bitmap.distinctColors() > 8)
        assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    @Test
    fun `gone media in Night Glass`() {
        val alert = DemoAlerts.all(now)[1].copy(videoUrl = "http://192.168.1.20:5000/clip.mp4")
        val bitmap = render(ThemeChoice.NIGHT_GLASS, "alert-detail-gone", DetailState.Found(alert, null), VideoState.Failed(com.gekkotron.oikos.alerts.media.MediaFailure.Gone))
        assertTrue(bitmap.distinctColors() > 8)
    }

    @Test
    fun `chips and the media surface have a visible edge in Warm Minimal`() {
        val alert = DemoAlerts.all(now)[2].copy(videoUrl = "http://192.168.1.20:5000/clip.mp4")
        val bitmap = render(ThemeChoice.WARM_MINIMAL, "alert-detail-warm-chips", DetailState.Found(alert, null), VideoState.Failed(com.gekkotron.oikos.alerts.media.MediaFailure.Gone))
        val page = bitmap.getPixel(4, 600)
        // The text node sits inside the chip's 12 dp side padding, so the chip's left edge is ~36 px left of the text at xxhdpi.
        // The 1 dp border band must be the chipBorder colour (#D9D0C2), not the page colour or text anti-aliasing.
        fun borderLeftOf(text: String): Boolean {
            val r = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            val y = r.center.y.toInt()
            return (r.left.toInt() - 40..r.left.toInt() - 28).any { isChipBorder(bitmap.getPixel(it, y)) }
        }
        assertTrue(borderLeftOf("Alarm"), "channel chip border is not drawn")
        assertTrue(borderLeftOf("Resolved"), "resolved chip border is not drawn")
        // The failed-media message sits on the card surface (#FFFDF9) with the card border, not on the page colour.
        val m = compose.onNodeWithText("Media no longer available").fetchSemanticsNode().boundsInRoot
        val card = bitmap.getPixel(m.center.x.toInt(), m.top.toInt() - 12)
        assertTrue(close(card, 0xFF, 0xFD, 0xF9), "media message is not on the card surface")
    }

    private fun close(pixel: Int, r: Int, g: Int, b: Int, tol: Int = 6) =
        kotlin.math.abs(((pixel shr 16) and 0xFF) - r) <= tol && kotlin.math.abs(((pixel shr 8) and 0xFF) - g) <= tol && kotlin.math.abs((pixel and 0xFF) - b) <= tol

    private fun isChipBorder(pixel: Int) = close(pixel, 217, 208, 194, tol = 12)
}
