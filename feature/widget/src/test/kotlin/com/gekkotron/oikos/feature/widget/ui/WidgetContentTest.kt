package com.gekkotron.oikos.feature.widget.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetContentTest {
    private val now = 10_000_000L
    private val none: Action = actionStartActivity(ComponentName("test", "test.Noop"))
    private val noop = object : WidgetActions {
        override fun tap(action: TileAction): Action = none
        override fun refresh(): Action = none
        override fun reconfigure(): Action = none
        override fun openApp(): Action = none
        override fun openSettings(): Action = none
    }

    private fun tile(id: String, name: String, kind: Kind, value: TileValue, f: (TileSnapshot) -> TileSnapshot = { it }) =
        f(TileSnapshot(id, name, kind, TileIcon.SWITCH, value, updatedAt = now))

    private fun state(vararg tiles: TileSnapshot?, link: LinkStatus = LinkStatus.FRESH, configured: Boolean = true) =
        WidgetUiState(tiles.toList(), null, link, lastFresh = now - 60_000, now = now, configured = configured)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun small_size_shows_first_tile_only() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 48.dp))
        provideComposable {
            WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), tile("b", "Lamp", Kind.LIGHT, TileValue.Light(false, null))), noop)
        }
        onNode(hasText("Kettle")).assertExists()
        onNode(hasText("Lamp")).assertDoesNotExist()
    }

    @Test
    fun large_size_shows_all_tiles_and_footer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), tile("b", "Blind", Kind.COVER, TileValue.Cover(CoverState.OPEN, 60))), noop)
        }
        onNode(hasText("Kettle")).assertExists()
        onNode(hasText("Open 60 %")).assertExists()
        onNode(hasText("Updated")).assertExists()
    }

    @Test
    fun missing_tile_shows_dash_and_removed_shows_removed() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            WidgetContent(state(null, tile("b", "Old", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.REMOVED) }), noop)
        }
        onNode(hasText("Removed")).assertExists()
        onNode(hasText("Old")).assertExists()
    }

    @Test
    fun not_configured_shows_set_up() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), link = LinkStatus.NOT_CONFIGURED), noop) }
        onNode(hasText("Set up Oikos")).assertExists()
        onNode(hasText("Kettle")).assertDoesNotExist()
    }

    @Test
    fun empty_config_shows_hint() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { WidgetContent(state(configured = false), noop) }
        onNode(hasText("Long-press to choose devices")).assertExists()
    }

    @Test
    fun failed_tile_shows_marker() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.FAILED) }), noop) }
        onNode(hasText("!")).assertExists()
    }

    @Test
    fun pending_tile_shows_ellipsis() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.PENDING, pendingSince = now) }), noop) }
        onNode(hasText("…")).assertExists()
    }

    @Test
    fun offline_footer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), link = LinkStatus.OFFLINE), noop) }
        onNode(hasText("Offline")).assertExists()
    }

    @Test
    fun auth_failure_footer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), link = LinkStatus.AUTH_FAILED), noop) }
        onNode(hasText("Sign-in failed")).assertExists()
    }
}
