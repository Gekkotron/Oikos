package com.gekkotron.oikos.feature.alerts.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/** Inline Media3 playback of a downloaded clip (push alerts spec §6.3). The player pauses when the app stops and is released with the composable. */
@Composable
fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    PauseWhenStopped(player)
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
        modifier = modifier.fillMaxSize(),
    )
}

/** Stops a clip's audio from playing on once the app leaves the screen. */
@Composable
internal fun PauseWhenStopped(player: Player) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.playWhenReady = false }
}
