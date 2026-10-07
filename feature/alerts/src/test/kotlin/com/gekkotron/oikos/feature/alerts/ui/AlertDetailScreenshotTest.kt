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
        // The text node sits inside the chip's padding, so scan the pixels above it (to 10 dp): any non-page colour is the chip's edge or fill.
        fun hasEdge(text: String): Boolean {
            val r = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            val x = r.center.x.toInt()
            return (r.top.toInt() - 30..r.top.toInt()).any { bitmap.getPixel(x, it.coerceAtLeast(0)) != page }
        }
        assertTrue(hasEdge("Alarm"), "channel chip has no border or background")
        assertTrue(hasEdge("Resolved"), "resolved chip has no border or background")
        assertTrue(hasEdge("Media no longer available"), "media surface is invisible")
    }
}
