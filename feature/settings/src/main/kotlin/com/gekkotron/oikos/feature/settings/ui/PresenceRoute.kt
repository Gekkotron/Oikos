package com.gekkotron.oikos.feature.settings.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.presentation.PresenceSettingsViewModel

/** Settings-screen slot that drives [PresenceSection]. Keeps every Android system call (permissions, LocationManager) here. */
@Composable
fun PresenceSettingsSlot(
    onInfoMessage: (String) -> Unit,
    viewModel: PresenceSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionTick by rememberSaveable { mutableStateOf(0) }
    val status = remember(permissionTick) { permissionStatus(context) }
    val locationUnavailableMessage = stringResource(R.string.presence_location_needed)

    var intendedEnable by rememberSaveable { mutableStateOf(false) }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionTick++
        if (granted && intendedEnable) {
            viewModel.setEnabled(true)
            intendedEnable = false
        }
    }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionTick++
        if (!granted) {
            intendedEnable = false
            return@rememberLauncherForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (intendedEnable) {
            viewModel.setEnabled(true)
            intendedEnable = false
        }
    }

    LaunchedEffect(Unit) { permissionTick++ }

    PresenceSection(
        ui = ui,
        permissionStatus = status,
        onToggle = { on ->
            when {
                !on -> {
                    intendedEnable = false
                    viewModel.setEnabled(false)
                }
                status == PresencePermissionStatus.Granted -> viewModel.setEnabled(true)
                else -> {
                    intendedEnable = true
                    fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        },
        onAliasChange = viewModel::setAlias,
        onUseCurrentLocation = {
            val fix = lastKnownFix(context)
            if (fix != null) viewModel.setHome(fix.first, fix.second) else onInfoMessage(locationUnavailableMessage)
        },
        onRadiusChange = viewModel::setRadius,
        onRequestPermission = {
            when (status) {
                PresencePermissionStatus.FineMissing, PresencePermissionStatus.Denied -> fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                PresencePermissionStatus.BackgroundMissing -> backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                PresencePermissionStatus.Granted -> {}
            }
        },
    )
}

private fun permissionStatus(context: Context): PresencePermissionStatus {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine) return PresencePermissionStatus.FineMissing
    val backgroundNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    if (!backgroundNeeded) return PresencePermissionStatus.Granted
    val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    return if (background) PresencePermissionStatus.Granted else PresencePermissionStatus.BackgroundMissing
}

/** No Play Services dependency — reads whatever fix the system has from GPS or NETWORK. Null when neither has one. */
private fun lastKnownFix(context: Context): Pair<Double, Double>? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    for (p in providers) {
        try {
            @Suppress("MissingPermission")
            val fix = manager.getLastKnownLocation(p) ?: continue
            return fix.latitude to fix.longitude
        } catch (_: SecurityException) {
            continue
        }
    }
    return null
}
