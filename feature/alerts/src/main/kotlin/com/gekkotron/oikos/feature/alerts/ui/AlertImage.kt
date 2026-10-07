package com.gekkotron.oikos.feature.alerts.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil3.compose.SubcomposeAsyncImage
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.alerts.media.AlertMedia
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.feature.alerts.R

@DrawableRes
internal fun demoImageRes(url: String): Int? = when (url) {
    DemoAlerts.FRONT_DOOR_IMAGE -> R.drawable.demo_alert_front_door
    DemoAlerts.GARDEN_IMAGE -> R.drawable.demo_alert_garden
    DemoAlerts.GARAGE_IMAGE -> R.drawable.demo_alert_garage
    else -> null
}

/** Channel glyphs from the existing icon set: no new drawing for the alerts screens. */
fun AlertChannel.glyph(): OikosGlyph = when (this) {
    AlertChannel.ALARM -> OikosGlyph.SHIELD
    AlertChannel.SECURITY -> OikosGlyph.WALKER
    AlertChannel.CAMERA -> OikosGlyph.PLAY
    AlertChannel.ENERGY -> OikosGlyph.BOLT
    AlertChannel.CLIMATE -> OikosGlyph.THERMOMETER
    AlertChannel.SYSTEM -> OikosGlyph.GEAR
    AlertChannel.MISC -> OikosGlyph.DOT
}

/**
 * A picture of an alert. `demo://` urls are bundled drawables; everything else goes through Coil as an [AlertMedia],
 * the only request type the alert fetcher (and so the gateway) handles. [error] gets the failure to explain.
 */
@Composable
fun AlertImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    error: @Composable (Throwable?) -> Unit = {},
) {
    val demo = demoImageRes(url)
    if (demo != null) {
        Image(painterResource(demo), contentDescription, modifier, contentScale = ContentScale.Crop)
        return
    }
    SubcomposeAsyncImage(
        model = AlertMedia(url),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = { Box(Modifier.fillMaxSize().background(OikosTheme.colors.card)) },
        error = { state -> error(state.result.throwable) },
    )
}
