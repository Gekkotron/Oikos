package com.gekkotron.oikos.feature.home.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.distinctColors
import com.gekkotron.oikos.feature.home.luminanceAt
import com.gekkotron.oikos.feature.home.presentation.CardUi
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.HomeUiState
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.renderPng
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertTrue

/** Room groups spec §10: the group cards and sheets in both themes. Sheets live in their own window, so their content is drawn directly. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class GroupScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun state(): HomeUiState = HomeUiBuilder.build(
        listOf(
            testEntity("bay", Kind.COVER, "Bay window", area = "living", capabilities = Capabilities(position = true)),
            testEntity("side", Kind.COVER, "Side window", area = "living", capabilities = Capabilities(position = true)),
            testEntity("patio", Kind.COVER, "Patio door", area = "living"),
            testEntity("l1", Kind.LIGHT, "Ceiling", area = "living", capabilities = Capabilities(brightness = true)),
            testEntity("l2", Kind.LIGHT, "Floor lamp", area = "living"),
            testEntity("temp", Kind.SENSOR, "Temperature", area = "living", unit = "°C"),
        ).associateBy { it.uniqueId },
        mapOf(
            "bay" to EntityState("bay", StateValue.Cover(CoverState.OPEN, 100)),
            "side" to EntityState("side", StateValue.Cover(CoverState.OPEN, 80)),
            "patio" to EntityState("patio", StateValue.Cover(CoverState.CLOSED, null)),
            "l1" to EntityState("l1", StateValue.Light(true, 200)),
            "temp" to EntityState("temp", StateValue.Numeric(21.4)),
        ),
        listOf(Area("living", "Living room")), RoomFilter.Area("living"), LinkState.Connected(TransportMode.DEMO), Locale.US,
    )

    private fun group(kind: Kind): CardUi = state().cards.single { it.isGroup && it.kind == kind }

    private fun assertTheme(name: String, dark: Boolean) {
        val bitmap = compose.renderPng(name)
        assertTrue(bitmap.distinctColors() > 8)
        if (dark) assertTrue(bitmap.luminanceAt(4, 4) < 0.35f) else assertTrue(bitmap.luminanceAt(4, 4) > 0.6f)
    }

    private fun home(theme: ThemeChoice) = compose.setContent {
        OikosTheme(theme) { OikosBackground { HomeScreen(state = state(), onSelect = {}, onTap = {}, onSend = { _, _ -> }, now = LocalTime.NOON) } }
    }

    private fun sheet(theme: ThemeChoice, kind: Kind) = compose.setContent {
        OikosTheme(theme) {
            OikosBackground {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    GroupControls(group(kind), onSend = { _, _ -> }, onSendGroup = { _, _ -> }, onOpenMember = {})
                }
            }
        }
    }

    @Test
    fun `home with groups in Night Glass`() {
        home(ThemeChoice.NIGHT_GLASS)
        assertTheme("groups-home-night", dark = true)
    }

    @Test
    fun `home with groups in Warm Minimal`() {
        home(ThemeChoice.WARM_MINIMAL)
        assertTheme("groups-home-warm", dark = false)
    }

    @Test
    fun `shutter group sheet in Night Glass`() {
        sheet(ThemeChoice.NIGHT_GLASS, Kind.COVER)
        assertTheme("groups-sheet-covers-night", dark = true)
    }

    @Test
    fun `light group sheet in Warm Minimal`() {
        sheet(ThemeChoice.WARM_MINIMAL, Kind.LIGHT)
        assertTheme("groups-sheet-lights-warm", dark = false)
    }
}
