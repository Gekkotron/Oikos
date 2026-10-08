package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.data.DEFAULT_FRIGATE_URL
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.OikosRadioRow
import com.gekkotron.oikos.designsystem.components.RoutePicker
import com.gekkotron.oikos.designsystem.permissions.openNotificationSettings
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.presentation.ConnectionSummary
import com.gekkotron.oikos.feature.settings.presentation.SettingsUi
import com.gekkotron.oikos.feature.settings.presentation.SettingsViewModel
import com.gekkotron.oikos.feature.settings.presentation.appVersion
import com.gekkotron.oikos.transport.Route
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun SettingsRoute(
    route: Route?,
    onRouteChange: (Route) -> Unit,
    onSetUpAgain: () -> Unit,
    onOpenRooms: () -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    clock: () -> Instant = Instant::now,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    // The "N min ago" text must move on without a new status emission, so the clock is state here.
    var now by remember { mutableStateOf(clock()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(60_000)
                now = clock()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        now = clock()
        viewModel.refreshPush()
        onPauseOrDispose {}
    }
    SettingsScreen(
        state, version, route, viewModel::setTheme, onRouteChange, onSetUpAgain, onOpenRooms, onOpenLicences, onBack,
        onRegisterAgain = viewModel::registerAgain, now = now, onSaveHistoryUrl = viewModel::setHistoryUrl, onSaveFrigate = viewModel::setFrigate,
        onOpenChannels = { openNotificationSettings(context) },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUi,
    version: String,
    route: Route?,
    onTheme: (ThemePreference) -> Unit,
    onRouteChange: (Route) -> Unit,
    onSetUpAgain: () -> Unit,
    onOpenRooms: () -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    onRegisterAgain: () -> Unit = {},
    onOpenChannels: () -> Unit = {},
    now: Instant = Instant.now(),
    onSaveHistoryUrl: (String) -> Boolean = { true },
    onSaveFrigate: (url: String, user: String, password: String) -> Boolean = { _, _, _ -> true },
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var pushSheet by rememberSaveable { mutableStateOf(false) }
    var historyDialog by rememberSaveable { mutableStateOf(false) }
    var frigateDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        topBar = { SettingsTopBar(stringResource(R.string.settings_title), onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section(stringResource(R.string.settings_theme)) {
                Column(Modifier.selectableGroup()) {
                    OikosRadioRow(stringResource(R.string.settings_theme_system), state.theme == ThemePreference.SYSTEM, { onTheme(ThemePreference.SYSTEM) })
                    OikosRadioRow(stringResource(R.string.settings_theme_night), state.theme == ThemePreference.NIGHT_GLASS, { onTheme(ThemePreference.NIGHT_GLASS) })
                    OikosRadioRow(stringResource(R.string.settings_theme_warm), state.theme == ThemePreference.WARM_MINIMAL, { onTheme(ThemePreference.WARM_MINIMAL) })
                }
            }
            NavRow(stringResource(R.string.settings_rooms), stringResource(R.string.settings_rooms_summary), onOpenRooms)
            NavRow(stringResource(R.string.settings_push), pushStatusText(state.push, now), onClick = { pushSheet = true })
            Section(stringResource(R.string.settings_connection)) {
                ConnectionRows(state.connection, onEditHistory = { historyDialog = true }, onEditFrigate = { frigateDialog = true })
                if (route != null) RoutePicker(route, onRouteChange, Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_set_up_again))
                }
            }
            Section(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_version, version), style = OikosTheme.type.body)
                Text(stringResource(R.string.settings_notice), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
                NavRow(stringResource(R.string.settings_licences), null, onOpenLicences)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_set_up_again_title)) },
            text = { Text(stringResource(R.string.settings_set_up_again_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onSetUpAgain() }) { Text(stringResource(R.string.settings_set_up_again_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    if (historyDialog) {
        val direct = state.connection as? ConnectionSummary.Direct
        HistoryUrlDialog(direct?.historyUrl.orEmpty(), direct?.historyDefault.orEmpty(), onSave = onSaveHistoryUrl, onDismiss = { historyDialog = false })
    }
    if (frigateDialog) {
        val direct = state.connection as? ConnectionSummary.Direct
        FrigateDialog(
            initialUrl = direct?.frigateUrl ?: DEFAULT_FRIGATE_URL,
            initialUser = direct?.frigateUser.orEmpty(),
            hasPassword = direct?.frigateHasPassword == true,
            onSave = onSaveFrigate,
            onDismiss = { frigateDialog = false },
        )
    }
    if (pushSheet) {
        PushSheet(state.push, now, onRegisterAgain, onOpenChannels, onDismiss = { pushSheet = false })
    }
}

/** Shared by the three Settings screens: a back arrow and a title on the transparent background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.settings_back)
    TopAppBar(
        title = { Text(title, style = OikosTheme.type.display) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Text("←", style = OikosTheme.type.cardValue, modifier = Modifier.clearAndSetSemantics {})
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = OikosTheme.colors.content, navigationIconContentColor = OikosTheme.colors.content),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun NavRow(title: String, summary: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OikosTheme.type.body)
            if (summary != null) Text(summary, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
        Text("›", style = OikosTheme.type.cardValue, color = OikosTheme.colors.contentMuted, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun ConnectionRows(summary: ConnectionSummary?, onEditHistory: () -> Unit, onEditFrigate: () -> Unit) {
    when (summary) {
        null -> Unit
        ConnectionSummary.Demo -> Text(stringResource(R.string.settings_demo), style = OikosTheme.type.body)
        is ConnectionSummary.Direct -> {
            val tags = listOfNotNull(
                stringResource(R.string.settings_tls).takeIf { summary.tls },
                stringResource(R.string.settings_ws).takeIf { summary.ws },
            )
            InfoRow(stringResource(R.string.settings_broker), summary.broker, tags.joinToString(" · ").ifEmpty { null })
            InfoRow(stringResource(R.string.settings_user), summary.username ?: stringResource(R.string.settings_none))
            InfoRow(stringResource(R.string.settings_prefix), summary.prefix)
            summary.gatewayUrl?.let { InfoRow(stringResource(R.string.settings_gateway), it) }
            InfoRow(
                stringResource(R.string.settings_history),
                summary.historyUrl ?: stringResource(R.string.settings_history_auto, summary.historyDefault),
                onClick = onEditHistory,
                onClickLabel = stringResource(R.string.settings_history_edit),
            )
            InfoRow(
                stringResource(R.string.settings_frigate),
                summary.frigateUrl ?: stringResource(R.string.settings_frigate_none),
                summary.frigateUser?.takeIf { summary.frigateHasPassword }?.let { stringResource(R.string.settings_frigate_login, it) },
                onClick = onEditFrigate,
                onClickLabel = stringResource(R.string.settings_history_edit),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryUrlDialog(initial: String, hint: String, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var failed by rememberSaveable { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            HistoryUrlDialogContent(
                text, failed, hint,
                onTextChange = { text = it; failed = false },
                onSave = { if (onSave(text)) onDismiss() else failed = true },
                onCancel = onDismiss,
            )
        }
    }
}

/** Stateless body of the history URL dialog, so it can be tested without a dialog window. */
@Composable
internal fun HistoryUrlDialogContent(text: String, failed: Boolean, hint: String, onTextChange: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val error = stringResource(R.string.settings_history_error)
    Column(Modifier.padding(24.dp)) {
        Text(stringResource(R.string.settings_history), style = OikosTheme.type.display)
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(hint) },
            singleLine = true,
            isError = failed,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { if (failed) error(error) },
        )
        // Always laid out (blank until a refusal) so the dialog does not resize when the error appears.
        Text(if (failed) error else "", style = OikosTheme.type.label, color = MaterialTheme.colorScheme.error, minLines = 1, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.settings_history_save)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrigateDialog(initialUrl: String, initialUser: String, hasPassword: Boolean, onSave: (String, String, String) -> Boolean, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var user by rememberSaveable { mutableStateOf(initialUser) }
    // Not saveable: a password must not land in the saved instance state.
    var password by remember { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            FrigateDialogContent(
                url, user, initialUser, password, hasPassword, failed,
                onUrl = { url = it; failed = false },
                onUser = { user = it },
                onPassword = { password = it },
                onSave = { if (onSave(url, user, password)) onDismiss() else failed = true },
                onCancel = onDismiss,
            )
        }
    }
}

/** Stateless body of the Frigate dialog, so it can be tested without a dialog window. */
@Composable
internal fun FrigateDialogContent(
    url: String,
    user: String,
    initialUser: String,
    password: String,
    hasPassword: Boolean,
    failed: Boolean,
    onUrl: (String) -> Unit,
    onUser: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val error = stringResource(R.string.settings_history_error)
    Column(Modifier.padding(24.dp)) {
        Text(stringResource(R.string.settings_frigate), style = OikosTheme.type.display)
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            label = { Text(stringResource(R.string.settings_frigate_hint)) },
            singleLine = true,
            isError = failed,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { if (failed) error(error) },
        )
        // Always laid out (blank until a refusal) so the dialog does not resize when the error appears.
        Text(if (failed) error else "", style = OikosTheme.type.label, color = MaterialTheme.colorScheme.error, minLines = 1, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(
            value = user,
            onValueChange = onUser,
            label = { Text(stringResource(R.string.settings_frigate_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.settings_frigate_password)) },
            // The stored password is kept only for the same user (see the ViewModel).
            supportingText = if (hasPassword && user.trim() == initialUser.trim()) ({ Text(stringResource(R.string.settings_frigate_unchanged)) }) else null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.settings_history_save)) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, extra: String? = null, onClick: (() -> Unit)? = null, onClickLabel: String? = null) {
    val tap = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().then(tap).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted, modifier = Modifier.width(140.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = OikosTheme.type.body)
            if (extra != null) Text(extra, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
        }
        if (onClick != null) Text("›", style = OikosTheme.type.body, color = OikosTheme.colors.contentMuted)
    }
}
