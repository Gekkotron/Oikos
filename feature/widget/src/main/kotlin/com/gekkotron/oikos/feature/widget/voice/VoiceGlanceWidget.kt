package com.gekkotron.oikos.feature.widget.voice

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.ui.voiceIntent

/** "Oikos voice": a single large mic that opens the voice sheet; it holds no state, so it never needs a refresh. */
class VoiceGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val open = actionStartActivity(voiceIntent(context))
        val label = context.getString(R.string.widget_voice)
        provideContent {
            Box(
                GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).clickable(open),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(R.drawable.ic_widget_mic),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(ColorProvider(day = Color(0xFF1F1B16), night = Color(0xFFE8ECFF))),
                    modifier = GlanceModifier.size(32.dp),
                )
            }
        }
    }
}
