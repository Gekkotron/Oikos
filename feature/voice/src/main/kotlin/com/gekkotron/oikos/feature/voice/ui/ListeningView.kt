package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.voice.R

@Composable
internal fun ColumnScope.ListeningView(partial: String) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "micAlpha",
    )
    val cd = stringResource(R.string.voice_mic_cd)
    StaticIcon(OikosGlyph.MIC, OikosTheme.colors.warning, Modifier.size(56.dp).alpha(pulse).semantics { contentDescription = cd })
    Text(
        partial.ifBlank { stringResource(R.string.voice_listening) },
        style = OikosTheme.type.display.copy(fontSize = 20.sp),
        color = OikosTheme.colors.content,
        textAlign = TextAlign.Center,
    )
}
