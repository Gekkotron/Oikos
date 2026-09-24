package com.gekkotron.oikos.feature.onboarding.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.onboarding.R

internal enum class CameraPermission { WAITING, GRANTED, DENIED }

@Composable
internal fun ScanScreen(error: PayloadError?, onText: (String) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var permission by remember {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        mutableStateOf(if (granted) CameraPermission.GRANTED else CameraPermission.WAITING)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permission = if (ok) CameraPermission.GRANTED else CameraPermission.DENIED
    }
    LaunchedEffect(Unit) {
        if (permission == CameraPermission.WAITING) launcher.launch(Manifest.permission.CAMERA)
    }
    ScanContent(
        permission = permission,
        error = error,
        onText = onText,
        onManual = onManual,
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onBack = onBack,
    )
}

@Composable
internal fun ScanContent(
    permission: CameraPermission,
    error: PayloadError?,
    onText: (String) -> Unit,
    onManual: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    camera: @Composable (Modifier, (String) -> Unit) -> Unit = { modifier, callback -> QrCamera(modifier, callback) },
) {
    SecureWindow()
    OnboardingColumn {
        Text(stringResource(R.string.scan_title), style = OikosTheme.type.display)
        when (permission) {
            CameraPermission.GRANTED -> camera(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)), onText)
            CameraPermission.WAITING -> Text(stringResource(R.string.scan_waiting), style = OikosTheme.type.body)
            CameraPermission.DENIED -> {
                Text(stringResource(R.string.scan_denied), style = OikosTheme.type.body)
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_open_settings)) }
            }
        }
        if (error != null) {
            Text(
                payloadErrorText(error),
                style = OikosTheme.type.body,
                color = OikosTheme.colors.warning,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_manual)) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
    }
}
