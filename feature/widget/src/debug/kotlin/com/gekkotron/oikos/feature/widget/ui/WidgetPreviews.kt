@file:OptIn(ExperimentalGlancePreviewApi::class)

package com.gekkotron.oikos.feature.widget.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind

// Android Studio previews with sample data. Light and dark follow the preview's own night setting.

private val none: Action = actionStartActivity(ComponentName("preview", "preview.Noop"))
private val actions = object : WidgetActions {
    override fun tap(action: TileAction): Action = none
    override fun refresh(): Action = none
    override fun reconfigure(): Action = none
    override fun openApp(): Action = none
    override fun openSettings(): Action = none
}

private val now = System.currentTimeMillis()

private fun t(name: String, kind: Kind, icon: TileIcon, value: TileValue, f: (TileSnapshot) -> TileSnapshot = { it }) =
    f(TileSnapshot(name, name, kind, icon, value, updatedAt = now))

private val sample = listOf(
    t("Living room", Kind.LIGHT, TileIcon.LIGHT, TileValue.Light(true, 60)),
    t("Kettle", Kind.SWITCH, TileIcon.SWITCH, TileValue.OnOff(false)),
    t("Bedroom blind", Kind.COVER, TileIcon.COVER, TileValue.Cover(CoverState.OPEN, 60)),
    t("Alarm", Kind.ALARM_PANEL, TileIcon.ALARM, TileValue.Alarm(AlarmState.ARMED_AWAY)),
    t("Hallway", Kind.SENSOR, TileIcon.TEMPERATURE, TileValue.Measure(21.4, "°C")),
    t("Porch light", Kind.LIGHT, TileIcon.LIGHT, TileValue.Light(true, null)) { it.copy(status = TileStatus.FAILED) },
    t("Heat pump", Kind.CLIMATE, TileIcon.CLIMATE, TileValue.Climate("heat", 20.5, 21.0)),
    t("Garage", Kind.SENSOR, TileIcon.SENSOR, TileValue.Text("Away")) { it.copy(updatedAt = now - 3_600_000) },
)

private fun state(count: Int, link: LinkStatus = LinkStatus.FRESH) =
    WidgetUiState(sample.take(count), null, link, lastFresh = now, now = now, configured = true)

@Preview(widthDp = 180, heightDp = 60)
@Composable
fun Preview2x1() = WidgetContent(state(1), actions)

@Preview(widthDp = 250, heightDp = 60)
@Composable
fun Preview4x1Cover() = WidgetContent(state(3).copy(tiles = listOf(sample[2])), actions)

@Preview(widthDp = 180, heightDp = 180)
@Composable
fun Preview2x2() = WidgetContent(state(4), actions)

@Preview(widthDp = 250, heightDp = 180)
@Composable
fun Preview4x2() = WidgetContent(state(4, LinkStatus.OFFLINE), actions)

@Preview(widthDp = 250, heightDp = 300)
@Composable
fun Preview4x4() = WidgetContent(state(8), actions)

@Preview(widthDp = 180, heightDp = 110)
@Composable
fun PreviewSetUp() = WidgetContent(state(1, LinkStatus.NOT_CONFIGURED), actions)

@Preview(widthDp = 180, heightDp = 110)
@Composable
fun PreviewEmpty() = WidgetContent(state(0).copy(configured = false), actions)
