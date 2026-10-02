package com.gekkotron.oikos.designsystem.alarm

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosColors
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.R
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.components.PendingDot
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command

/** The Security tab's alarm card: colour and text by state, then [AlarmControls] (security spec §4.2). */
@Composable
fun AlarmHero(
    name: String,
    state: AlarmState?,
    available: Boolean,
    pending: Boolean,
    stale: Boolean = false,
    caps: Capabilities,
    onSend: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OikosTheme.colors
    val accent = alarmAccent(state, available, c)
    val triggered = available && state == AlarmState.TRIGGERED
    val armed = AlarmActions.of(state, available, caps).current != null
    val pulses = available && (state == AlarmState.ARMING || state == AlarmState.PENDING || state == AlarmState.DISARMING)
    val alpha = if (pulses && animationsEnabled()) {
        val pulse by rememberInfiniteTransition(label = "alarm").animateFloat(
            initialValue = 0.10f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "alarm-pulse",
        )
        pulse
    } else {
        when {
            triggered -> 0.35f
            armed -> 0.22f
            else -> 0.12f
        }
    }
    val stateText = when {
        !available -> stringResource(R.string.alarm_unavailable)
        state == null -> stringResource(R.string.alarm_unknown)
        else -> stringResource(alarmHeroLabel(state))
    }
    val staleText = stringResource(R.string.alarm_stale)
    OikosCard(modifier.fillMaxWidth().testTag("alarm-hero").alpha(if (stale) 0.6f else 1f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = alpha), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) {
                    liveRegion = if (triggered) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    if (stale) stateDescription = staleText
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StaticIcon(OikosGlyph.SHIELD, accent, Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = OikosTheme.type.label, color = c.contentMuted)
                    Text(stateText, style = OikosTheme.type.display.copy(fontSize = 22.sp))
                }
                if (pending) PendingDot()
            }
            AlarmControls(state, available, caps, onSend)
        }
    }
}

/** The alarm's colour by state, shared by [AlarmHero] and the selected mode in [AlarmControls]. */
internal fun alarmAccent(state: AlarmState?, available: Boolean, c: OikosColors): Color = when {
    !available || state == null -> c.contentMuted
    state == AlarmState.DISARMED || state == AlarmState.DISARMING -> c.connected
    state == AlarmState.PENDING -> c.warning
    state == AlarmState.TRIGGERED -> c.heat
    else -> c.grid
}

@Composable
private fun animationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return true
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}
