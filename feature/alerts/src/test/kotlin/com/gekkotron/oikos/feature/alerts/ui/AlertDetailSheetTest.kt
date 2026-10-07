package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.media.MediaException
import com.gekkotron.oikos.alerts.media.MediaFailure
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.DetailState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class AlertDetailSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val camera = DemoAlerts.all(now)[1]
    private val alarm = DemoAlerts.all(now)[2]
    private val played = mutableListOf<Boolean>()
    private val actions = mutableListOf<AlertAction>()
    private var openedDevice = 0
    private var dismissed = 0

    private fun show(state: DetailState, video: VideoState? = null) = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) {
            AlertDetailSheet(
                state, video,
                onPlay = { played += true }, onAction = { actions += it }, onOpenDevice = { openedDevice++ }, onDismiss = { dismissed++ },
                playerContent = { file -> Text("player ${file.name}") },
                zone = ZoneOffset.UTC,
            )
        }
    }

    private fun withClip(device: String? = null) = DetailState.Found(camera.copy(videoUrl = "http://192.168.1.20:5000/clip.mp4"), device)

    @Test
    fun `an alert shows its title, body, channel, resolved flag and full date`() {
        show(DetailState.Found(alarm, null))
        compose.onNodeWithText("Alarm triggered").assertExists()
        compose.onNodeWithText("The garage door was forced. The alarm has been disarmed since.").assertExists()
        compose.onNodeWithText("Alarm").assertExists()
        compose.onNodeWithText("Resolved").assertExists()
        compose.onNodeWithText("Oct 7, 2026", substring = true).assertExists()
    }

    @Test
    fun `an alert that is not in Room says so`() {
        show(DetailState.Missing)
        compose.onNodeWithText("This alert is no longer available").assertExists()
    }

    @Test
    fun `action buttons report the tapped action`() {
        show(DetailState.Found(alarm, null))
        compose.onNodeWithText("Disarm").performClick()
        assertEquals(listOf(AlertAction("disarm", "Disarm")), actions)
    }

    @Test
    fun `at most three action buttons are shown`() {
        val many = alarm.copy(actions = (1..5).map { AlertAction("a$it", "Button $it") })
        show(DetailState.Found(many, null))
        compose.onNodeWithText("Button 3").assertExists()
        compose.onNodeWithText("Button 4").assertDoesNotExist()
    }

    @Test
    fun `Open device only shows when a device matches`() {
        show(DetailState.Found(camera, null))
        compose.onNodeWithText("Open device").assertDoesNotExist()
    }

    @Test
    fun `Open device opens the matched device`() {
        show(DetailState.Found(camera, "front_door"))
        compose.onNodeWithText("Open device").performClick()
        assertEquals(1, openedDevice)
    }

    @Test
    fun `a clip shows a play button that asks to download`() {
        show(withClip())
        compose.onNodeWithContentDescription("Play video").performClick()
        assertEquals(listOf(true), played)
    }

    @Test
    fun `an alert without a clip has no play button`() {
        show(DetailState.Found(camera, null))
        compose.onNodeWithContentDescription("Play video").assertDoesNotExist()
    }

    @Test
    fun `a downloading clip shows a progress ring with the fraction`() {
        show(withClip(), VideoState.Downloading(2, 4))
        compose.onNode(
            SemanticsMatcher("progress 0.5") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == 0.5f },
        ).assertExists()
        compose.onNodeWithContentDescription("Play video").assertDoesNotExist()
    }

    @Test
    fun `a downloading clip of unknown size shows an indeterminate ring`() {
        show(withClip(), VideoState.Downloading(0, null))
        compose.onNode(
            SemanticsMatcher("indeterminate") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate },
        ).assertExists()
    }

    @Test
    fun `a clip that is gone or refused says the media is no longer available`() {
        show(withClip(), VideoState.Failed(MediaFailure.Gone))
        compose.onNodeWithText("Media no longer available").assertExists()
    }

    @Test
    fun `gone media shows only the message, no picture underneath`() {
        show(withClip(), VideoState.Failed(MediaFailure.Gone))
        compose.onNodeWithText("Media no longer available").assertIsDisplayed()
        compose.onNodeWithContentDescription("Picture: Person in the garden").assertDoesNotExist()
    }

    @Test
    fun `a clip that failed for another reason says it could not load`() {
        show(withClip(), VideoState.Failed(MediaFailure.Unavailable("http 500")))
        compose.onNodeWithText("Couldn't load the media").assertExists()
    }

    @Test
    fun `a ready clip hands its file to the player`() {
        show(withClip(), VideoState.Ready(File("abc.mp4")))
        compose.onNodeWithText("player abc.mp4").assertExists()
    }

    @Test
    fun `a picture error maps to its message`() {
        assertEquals(R.string.alert_media_gone, mediaErrorRes(MediaException(MediaFailure.Gone).mediaFailure()))
        assertEquals(R.string.alert_media_gone, mediaErrorRes(MediaException(MediaFailure.NotAllowed).mediaFailure()))
        assertEquals(R.string.alert_media_failed, mediaErrorRes(MediaException(MediaFailure.Unavailable("x")).mediaFailure()))
        assertEquals(R.string.alert_media_failed, mediaErrorRes(RuntimeException("decoder").mediaFailure()))
        assertEquals(R.string.alert_media_failed, mediaErrorRes(null.mediaFailure()))
    }
}
