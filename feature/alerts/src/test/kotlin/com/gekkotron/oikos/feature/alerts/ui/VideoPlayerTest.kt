package com.gekkotron.oikos.feature.alerts.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class VideoPlayerTest {
    @get:Rule
    val compose = createComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `the player is paused when the app is stopped and not before`(): Unit {
        val owner = TestOwner().also { it.registry.currentState = Lifecycle.State.RESUMED }
        lateinit var player: ExoPlayer
        compose.setContent {
            val context = LocalContext.current
            player = androidx.compose.runtime.remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { PauseWhenStopped(player) }
        }
        compose.runOnIdle { assertTrue(player.playWhenReady) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle {
            assertFalse(player.playWhenReady)
            player.release()
        }
    }
}
