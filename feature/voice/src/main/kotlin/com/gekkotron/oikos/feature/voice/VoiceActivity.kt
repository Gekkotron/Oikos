package com.gekkotron.oikos.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.voice.ui.VoiceSheet
import com.gekkotron.oikos.voice.VoiceLang
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
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
            setContent {
                OikosTheme {
                    Surface(color = Color.Transparent) {
                        VoiceSheet(
                            state = viewModel.state,
                            onPick = viewModel::pickSuggestion,
                            onConfirm = viewModel::confirm,
                            onCode = viewModel::provideCode,
                            onCancel = { finish() },
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
