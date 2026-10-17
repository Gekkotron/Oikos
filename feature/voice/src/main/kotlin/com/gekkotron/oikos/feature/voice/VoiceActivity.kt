package com.gekkotron.oikos.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.voice.ui.VoiceSheet
import com.gekkotron.oikos.voice.VoiceLang
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VoiceActivity : ComponentActivity() {
    private val viewModel: VoiceViewModel by viewModels()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (!awaitVoiceEnabled(viewModel.preferences.voiceEnabled)) {
                finish()
                return@launch
            }
            // The user's theme, not the system one, so the sheet matches the app it opens over.
            val theme = viewModel.preferences.theme.filterNotNull().first().toChoice()
            setContent {
                val dark = when (theme) {
                    ThemeChoice.NIGHT_GLASS -> true
                    ThemeChoice.WARM_MINIMAL -> false
                    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                }
                // Transparent bars: the translucent window otherwise draws them black over the app behind.
                DisposableEffect(dark) {
                    val style = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }
                OikosTheme(theme) {
                    Surface(color = Color.Transparent) {
                        val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
                        VoiceSheet(
                            state = viewModel.state,
                            onPick = viewModel::pickSuggestion,
                            onConfirm = viewModel::confirm,
                            onCode = viewModel::provideCode,
                            onCancel = { finish() },
                            catalog = catalog,
                            onRepeat = viewModel::onRepeatSpoken,
                            onPickOther = viewModel::onPickOther,
                            onPickDevice = viewModel::onPickDevice,
                            onPickAction = viewModel::onPickAction,
                            onPickBack = viewModel::onPickBack,
                            onPickFilterChange = viewModel::onPickFilterChange,
                        )
                    }
                }
            }
            if (ContextCompat.checkSelfPermission(this@VoiceActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVoice()
            } else {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoice() {
        if (!viewModel.started) viewModel.start(VoiceLang.fromAppLocale(Locale.getDefault().toLanguageTag()))
    }

    override fun onDestroy() {
        viewModel.cancel()
        super.onDestroy()
    }
}

private fun ThemePreference.toChoice(): ThemeChoice = when (this) {
    ThemePreference.SYSTEM -> ThemeChoice.SYSTEM
    ThemePreference.NIGHT_GLASS -> ThemeChoice.NIGHT_GLASS
    ThemePreference.WARM_MINIMAL -> ThemeChoice.WARM_MINIMAL
}
