package com.gekkotron.oikos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.navigation.OikosNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** The same instance `hiltViewModel()` hands to the composition below: both use this activity's store. */
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            TesterUpdates.check(this)
            // A rotation re-delivers the same intent: only a fresh launch is a tap on a notification.
            handleIntent(intent)
        }
        setContent {
            val app: AppViewModel = hiltViewModel()
            val theme by app.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeChoice.NIGHT_GLASS -> true
                ThemeChoice.WARM_MINIMAL -> false
                ThemeChoice.SYSTEM, null -> isSystemInDarkTheme()
            }
            // The bar icons follow the chosen theme, not the system one (the default style would).
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            OikosTheme(theme ?: ThemeChoice.SYSTEM) {
                // Until the preferences have loaded only the background is drawn, so Home never flashes in the wrong theme.
                OikosBackground { if (theme != null) OikosNavHost(app) }
            }
        }
    }

    /** `singleTop`: a tap on a notification while the app is open arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        DeepLinks.linkToOpen(intent)?.let(appViewModel::openLink)
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
