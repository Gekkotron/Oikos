package com.gekkotron.oikos.feature.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.TileActions
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.Kind
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class WidgetUiState(
    /** One entry per configured id, in order; null = never seen. */
    val tiles: List<TileSnapshot?>,
    val armMode: AlarmMode?,
    val link: LinkStatus,
    val lastFresh: Long?,
    val now: Long,
    val configured: Boolean,
)

/** Turns what a tap means into a Glance [Action]; the widget supplies the Android side. */
interface WidgetActions {
    fun tap(action: TileAction): Action

    fun refresh(): Action

    fun reconfigure(): Action

    fun openApp(): Action

    fun openSettings(): Action
}

/** The single large tile when the widget is one row high (widgets spec §2.2). */
private val SINGLE_TILE_MAX_HEIGHT = 100.dp

/** Wide enough for two columns, or for a cover tile's Open / Stop / Close buttons. */
private val WIDE = 180.dp
private val FOOTER = 28.dp
private val ROW = 64.dp

/** How many tiles the grid shows at a size; the config screen uses the same rule. */
fun tilesThatFit(width: Dp, height: Dp): Int {
    if (height < SINGLE_TILE_MAX_HEIGHT) return 1
    val columns = if (width >= WIDE) 2 else 1
    val rows = ((height - FOOTER) / ROW).toInt().coerceAtLeast(1)
    return columns * rows
}

@Composable
fun WidgetContent(state: WidgetUiState, actions: WidgetActions) {
    val context = LocalContext.current
    val size = LocalSize.current
    Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(8.dp)) {
        when {
            state.link == LinkStatus.NOT_CONFIGURED -> Message(context.getString(R.string.widget_set_up), actions.openApp())
            !state.configured -> Message(context.getString(R.string.widget_empty), actions.reconfigure())
            size.height < SINGLE_TILE_MAX_HEIGHT -> Tile(state.tiles.firstOrNull(), state, actions, large = true, modifier = GlanceModifier.fillMaxSize())
            else -> {
                Grid(state, actions, columns = if (size.width >= WIDE) 2 else 1, count = tilesThatFit(size.width, size.height))
                Footer(state, actions)
            }
        }
    }
}

@Composable
private fun Grid(state: WidgetUiState, actions: WidgetActions, columns: Int, count: Int) {
    Column(GlanceModifier.fillMaxWidth()) {
        state.tiles.take(count).chunked(columns).forEach { row ->
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                row.forEachIndexed { i, tile ->
                    val gap = if (i < columns - 1) 4.dp else 0.dp
                    Tile(tile, state, actions, large = false, modifier = GlanceModifier.defaultWeight().padding(end = gap))
                }
                repeat(columns - row.size) { Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun Tile(tile: TileSnapshot?, state: WidgetUiState, actions: WidgetActions, large: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val status = tile?.effectiveStatus(state.now)
    val dim = tile == null || tile.isStale(state.now) || !tile.available
    val text = when {
        tile == null -> "—"
        status == TileStatus.REMOVED -> context.getString(R.string.widget_removed)
        status == TileStatus.PENDING -> "…"
        else -> TileText.of(context, tile.value, Locale.getDefault())
    }
    val tap = tile?.let { actions.tap(TileActions.forTap(it, state.armMode)) } ?: actions.openApp()
    val color = if (dim) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface
    Row(
        modifier.background(GlanceTheme.colors.secondaryContainer).cornerRadius(14.dp).padding(8.dp).clickable(tap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(iconRes(tile?.icon)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = GlanceModifier.size(if (large) 28.dp else 20.dp),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(tile?.name ?: "—", maxLines = 1, style = TextStyle(color = color, fontSize = if (large) 14.sp else 12.sp))
            Text(text, maxLines = 1, style = TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = if (large) 18.sp else 14.sp))
        }
        if (status == TileStatus.FAILED) {
            Text("!", style = TextStyle(color = GlanceTheme.colors.error, fontWeight = FontWeight.Bold, fontSize = 16.sp))
        }
        if (tile != null && large && tile.kind == Kind.COVER && status != TileStatus.REMOVED && LocalSize.current.width >= WIDE) {
            CoverButtons(tile, actions)
        }
    }
}

@Composable
private fun CoverButtons(tile: TileSnapshot, actions: WidgetActions) {
    val context = LocalContext.current
    listOf(
        WidgetCommand.Open to R.string.widget_cover_open,
        WidgetCommand.Stop to R.string.widget_cover_stop,
        WidgetCommand.Close to R.string.widget_cover_close,
    ).forEach { (command, label) ->
        Text(
            context.getString(label),
            style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(actions.tap(TileActions.coverButton(tile, command))),
        )
    }
}

@Composable
private fun Footer(state: WidgetUiState, actions: WidgetActions) {
    val context = LocalContext.current
    val time = state.lastFresh?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    val label: String
    val onClick: Action
    when (state.link) {
        LinkStatus.OFFLINE -> {
            label = time?.let { context.getString(R.string.widget_offline, it) } ?: context.getString(R.string.widget_offline_never)
            onClick = actions.refresh()
        }
        LinkStatus.AUTH_FAILED -> {
            label = context.getString(R.string.widget_sign_in_failed)
            onClick = actions.openSettings()
        }
        else -> {
            label = time?.let { context.getString(R.string.widget_updated, it) }.orEmpty()
            onClick = actions.refresh()
        }
    }
    Row(GlanceModifier.fillMaxWidth().height(24.dp).clickable(onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), modifier = GlanceModifier.defaultWeight())
        Image(
            ImageProvider(R.drawable.ic_widget_refresh),
            contentDescription = context.getString(R.string.widget_refresh),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(18.dp).clickable(actions.refresh()),
        )
    }
}

@Composable
private fun Message(text: String, onClick: Action) {
    Box(GlanceModifier.fillMaxSize().clickable(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
    }
}

private fun iconRes(icon: TileIcon?): Int = when (icon) {
    TileIcon.LIGHT -> R.drawable.ic_widget_light
    TileIcon.SWITCH -> R.drawable.ic_widget_switch
    TileIcon.COVER -> R.drawable.ic_widget_cover
    TileIcon.CLIMATE -> R.drawable.ic_widget_climate
    TileIcon.TEMPERATURE -> R.drawable.ic_widget_temperature
    TileIcon.ALARM -> R.drawable.ic_widget_alarm
    TileIcon.BUTTON -> R.drawable.ic_widget_button
    TileIcon.SENSOR, null -> R.drawable.ic_widget_sensor
}
