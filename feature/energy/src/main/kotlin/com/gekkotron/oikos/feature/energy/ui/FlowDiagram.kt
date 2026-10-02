package com.gekkotron.oikos.feature.energy.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.energy.R
import com.gekkotron.oikos.feature.energy.presentation.EnergyNode
import com.gekkotron.oikos.feature.energy.presentation.EnergyUiState
import com.gekkotron.oikos.feature.energy.presentation.Flow
import com.gekkotron.oikos.feature.energy.presentation.NodeKind
import com.gekkotron.oikos.feature.energy.presentation.NodeStatus
import java.util.Locale

private val NODE = 44.dp
private val DASH = 6.dp

/** Bubble width: the circle plus room for its value and a short consumer name. */
private val BUBBLE = 88.dp

@Composable
internal fun FlowDiagram(state: EnergyUiState, locale: Locale, onMore: () -> Unit, onNode: (EnergyNode) -> Unit = {}, modifier: Modifier = Modifier) {
    val positions = remember(state.diagram) { FlowLayout.positions(state.diagram) }
    val description = energyDescription(state, locale)
    // A frame clock runs only while some edge actually moves; the time is read in the draw phase, so frames
    // redraw the Canvas without recomposing.
    val animate = animationsEnabled() && state.diagram.any { it.flow != Flow.IDLE }
    val seconds = remember { mutableDoubleStateOf(0.0) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameNanos { it }
            while (true) withInfiniteAnimationFrameNanos { seconds.doubleValue = (it - start) / 1e9 }
        }
    }
    val colors = OikosTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(FlowLayout.ASPECT)) {
        val w = maxWidth
        val h = maxHeight
        Box(Modifier.matchParentSize().testTag("energy-diagram").clearAndSetSemantics { contentDescription = description }) {
            Canvas(Modifier.matchParentSize()) {
                val house = positions["house"] ?: return@Canvas
                val hub = Offset(house.first * size.width, house.second * size.height)
                val dash = DASH.toPx()
                state.diagram.filter { it.kind != NodeKind.HOUSE && it.kind != NodeKind.WATER }.forEach { n ->
                    val p = positions[n.id] ?: return@forEach
                    val at = Offset(p.first * size.width, p.second * size.height)
                    // Drawn in the direction energy travels, so the dash moves with it.
                    val (from, to) = if (n.flow == Flow.OUT) hub to at else at to hub
                    val control = Offset((from.x + to.x) / 2, if (n.kind == NodeKind.SOLAR) (from.y + to.y) / 2 else from.y)
                    val path = Path().apply { moveTo(from.x, from.y); quadraticTo(control.x, control.y, to.x, to.y) }
                    val idle = n.flow == Flow.IDLE
                    val offset = if (animate && !idle) -FlowLayout.dashCycle(seconds.doubleValue, FlowLayout.speed(n.value)) * 2 * dash else 0f
                    drawPath(
                        path,
                        color = edgeColor(n.kind, colors).copy(alpha = if (idle) 0.25f else 0.9f),
                        style = Stroke(
                            width = FlowLayout.strokeDp(n.value, n.flow).dp.toPx(),
                            pathEffect = if (idle) null else PathEffect.dashPathEffect(floatArrayOf(dash, dash), offset),
                        ),
                    )
                }
            }
            state.diagram.filter { it.kind != NodeKind.MORE }.forEach { n ->
                val p = positions[n.id] ?: return@forEach
                // Touch only: the cards below are the accessible way into the same details (the diagram is one sentence).
                NodeBubble(n, locale, Modifier.placeBubble(p, w, h).clickable { onNode(n) })
            }
        }
        state.diagram.firstOrNull { it.kind == NodeKind.MORE }?.let { more ->
            val p = positions[more.id] ?: return@let
            val action = stringResource(R.string.energy_more_action, state.consumers.size)
            Box(
                Modifier
                    .place(p, w, h)
                    .size(NODE)
                    .clip(CircleShape)
                    .border(1.dp, colors.heat, CircleShape)
                    .clickable(role = Role.Button, onClick = onMore)
                    .semantics { contentDescription = action; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.energy_more, more.count), style = OikosTheme.type.label, color = colors.content) }
        }
    }
}

/** Centres a [NODE]-sized box on the fractional position [p]. */
private fun Modifier.place(p: Pair<Float, Float>, w: Dp, h: Dp): Modifier =
    offset(x = w * p.first - NODE / 2, y = h * p.second - NODE / 2)

/** A [BUBBLE]-wide bubble whose circle is centred on [p], whatever the width of its labels. */
private fun Modifier.placeBubble(p: Pair<Float, Float>, w: Dp, h: Dp): Modifier =
    offset(x = w * p.first - BUBBLE / 2, y = h * p.second - NODE / 2).width(BUBBLE)

@Composable
internal fun NodeBubble(n: EnergyNode, locale: Locale, modifier: Modifier) {
    val colors = OikosTheme.colors
    Column(modifier.alpha(statusAlpha(n.status)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(NODE).background(edgeColor(n.kind, colors).copy(alpha = 0.18f), CircleShape).border(1.dp, edgeColor(n.kind, colors), CircleShape),
            contentAlignment = Alignment.Center,
        ) { StaticIcon(glyph(n.kind), modifier = Modifier.size(22.dp)) }
        Text(nodeValueText(n, locale), style = OikosTheme.type.label, color = colors.content, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        // Every bubble is named: the role ("Solar", "Grid"…) or the consumer's name (spec §2).
        Text(nodeLabel(n), style = OikosTheme.type.label, color = colors.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

internal fun glyph(kind: NodeKind): OikosGlyph = when (kind) {
    NodeKind.SOLAR -> OikosGlyph.SUN
    NodeKind.GRID -> OikosGlyph.PYLON
    NodeKind.BATTERY -> OikosGlyph.BATTERY
    NodeKind.HOUSE -> OikosGlyph.HOUSE
    NodeKind.CONSUMER, NodeKind.MORE -> OikosGlyph.PLUG
    NodeKind.WATER -> OikosGlyph.DROP
}

internal fun edgeColor(kind: NodeKind, colors: com.gekkotron.oikos.designsystem.OikosColors): Color = when (kind) {
    NodeKind.SOLAR -> colors.solar
    NodeKind.GRID -> colors.grid
    NodeKind.BATTERY -> colors.connected
    NodeKind.HOUSE -> colors.content
    NodeKind.CONSUMER, NodeKind.MORE -> colors.heat
    NodeKind.WATER -> colors.water
}

internal fun statusAlpha(status: NodeStatus): Float = when (status) {
    NodeStatus.LIVE -> 1f
    NodeStatus.STALE -> 0.6f
    NodeStatus.UNAVAILABLE -> 0.38f
}

@Composable
private fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}
