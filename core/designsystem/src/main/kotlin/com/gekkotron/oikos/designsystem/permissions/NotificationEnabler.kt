package com.gekkotron.oikos.designsystem.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** After a refused prompt: `showRationale` false means "don't ask again", so only the system settings can help. */
fun shouldOpenSettings(granted: Boolean, showRationale: Boolean): Boolean = !granted && !showRationale

fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * "Turn on" for notifications (push alerts spec §7.1, §7.5): asks for `POST_NOTIFICATIONS` on Android 13+, and opens
 * the app's notification settings when the system will no longer show the prompt, when notifications were switched
 * off in the settings with the permission still granted, and on older Android. [onResult] runs after the prompt.
 */
@Composable
fun rememberNotificationEnabler(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationale = context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } ?: false
        if (shouldOpenSettings(granted, rationale)) openNotificationSettings(context)
        onResult()
    }
    return remember(launcher, context) {
        {
            val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (canAsk) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(context)
        }
    }
}

/**
 * Asks for `POST_NOTIFICATIONS` once per launch while it is missing (Android 13+). The onboarding step is skipped for good
 * once finished, so this is what still asks after an upgrade or a "Not now". The system stops showing the prompt by
 * itself after the user refuses twice, so no settings detour here.
 */
@Composable
fun AskForNotificationsOnLaunch() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
