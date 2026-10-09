package com.gekkotron.oikos.feature.alerts.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.media.MediaException
import com.gekkotron.oikos.alerts.media.MediaFailure
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import com.gekkotron.oikos.feature.alerts.R
import com.gekkotron.oikos.feature.alerts.presentation.AlertDetailViewModel
import com.gekkotron.oikos.feature.alerts.presentation.DetailEvent
import com.gekkotron.oikos.feature.alerts.presentation.DetailState
import com.gekkotron.oikos.push.AlertChannels
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@StringRes
internal fun mediaErrorRes(failure: MediaFailure): Int = when (failure) {
    MediaFailure.Gone, MediaFailure.NotAllowed -> R.string.alert_media_gone
    MediaFailure.AuthFailed -> R.string.alert_media_login
    is MediaFailure.Unavailable -> R.string.alert_media_failed
}

internal fun Throwable?.mediaFailure(): MediaFailure =
    (this as? MediaException)?.failure ?: MediaFailure.Unavailable(this?.message ?: "error")

/**
 * The detail sheet for [alertId], from a row or from the `oikos://alert/<id>` link (push alerts spec §7.3).
 * [deviceSheet] is Home's control sheet, passed in by the app so this module does not depend on Home.
 */
@Composable
fun AlertDetailRoute(
    alertId: String,
    onDismiss: () -> Unit,
    deviceSheet: (@Composable (id: String, onDismiss: () -> Unit) -> Unit)? = null,
    viewModel: AlertDetailViewModel = hiltViewModel(),
) {
    val loaded by remember(alertId) { viewModel.state(alertId) }.collectAsStateWithLifecycle(DetailState.Loading)
    // Without a device sheet there is nothing "Open device" could open, so the button is not offered.
    val state = (loaded as? DetailState.Found)?.takeIf { deviceSheet == null }?.copy(deviceId = null) ?: loaded
    var playing by rememberSaveable(alertId) { mutableStateOf(false) }
    val found = state as? DetailState.Found
    val video by produceState<VideoState?>(null, found?.alert?.id, found?.alert?.videoUrl, playing) {
        value = null
        if (playing && found != null) viewModel.video(found.alert).collect { value = it }
    }
    var deviceId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            when (event) {
                DetailEvent.Sent -> snackbar.showSnackbar(resources.getString(R.string.alert_sent))
                is DetailEvent.CouldNotSend -> snackbar.showSnackbar(resources.getString(R.string.alert_send_failed, event.label))
            }
        }
    }
    AlertDetailSheet(
        state, video,
        onPlay = { playing = true },
        onAction = { action -> found?.let { viewModel.send(it.alert.id, action) } },
        onOpenDevice = { deviceId = found?.deviceId },
        onDismiss = onDismiss,
        snackbarHostState = snackbar,
    )
    deviceId?.let { id -> deviceSheet?.invoke(id) { deviceId = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailSheet(
    state: DetailState,
    video: VideoState?,
    onPlay: () -> Unit,
    onAction: (AlertAction) -> Unit,
    onOpenDevice: () -> Unit,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    playerContent: @Composable (File) -> Unit = { VideoPlayer(it) },
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // Fully expanded and scrollable, so the picture, text and buttons stay reachable at 200 % font (spec §5.3 of the parent).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AlertDetailContent(state, video, onPlay, onAction, onOpenDevice, snackbarHostState, playerContent, zone)
    }
}

@Composable
fun AlertDetailContent(
    state: DetailState,
    video: VideoState?,
    onPlay: () -> Unit,
    onAction: (AlertAction) -> Unit,
    onOpenDevice: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    playerContent: @Composable (File) -> Unit = { VideoPlayer(it) },
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            DetailState.Loading -> Unit
            DetailState.Missing -> Text(stringResource(R.string.alert_gone), style = OikosTheme.type.body, modifier = Modifier.padding(vertical = 24.dp))
            is DetailState.Found -> Found(state, video, onPlay, onAction, onOpenDevice, playerContent, zone)
        }
        SnackbarHost(snackbarHostState)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Found(
    state: DetailState.Found,
    video: VideoState?,
    onPlay: () -> Unit,
    onAction: (AlertAction) -> Unit,
    onOpenDevice: () -> Unit,
    playerContent: @Composable (File) -> Unit,
    zone: ZoneId,
) {
    val alert = state.alert
    if (alert.imageUrl != null || alert.videoUrl != null) MediaBlock(alert, video, onPlay, playerContent)
    Text(alert.title, style = OikosTheme.type.display.copy(fontSize = 22.sp))
    val body = alertBody(alert)
    if (body.isNotBlank()) Text(body, style = OikosTheme.type.body)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoChip(stringResource(AlertChannels.nameRes(alert.channel)))
        if (alert.resolved) InfoChip(stringResource(R.string.alerts_resolved))
    }
    Text(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0]).withZone(zone).format(alert.sentAt),
        style = OikosTheme.type.label,
        color = OikosTheme.colors.contentMuted,
    )
    if (alert.shownActions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            alert.shownActions.forEach { action -> FilledTonalButton(onClick = { onAction(action) }) { Text(action.label) } }
        }
    }
    if (state.deviceId != null) {
        TextButton(onClick = onOpenDevice, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alert_open_device)) }
    }
}

@Composable
private fun MediaBlock(alert: Alert, video: VideoState?, onPlay: () -> Unit, playerContent: @Composable (File) -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(OikosTheme.colors.card)
            .border(1.dp, OikosTheme.colors.cardBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (video is VideoState.Ready) {
            playerContent(video.file)
            return@Box
        }
        // A clip that is gone or refused leaves only the message, on its own surface: no picture underneath.
        if (video is VideoState.Failed) {
            MediaMessage(stringResource(mediaErrorRes(video.failure)))
            return@Box
        }
        val image = alert.imageUrl
        if (image != null) {
            AlertImage(
                image, Modifier.fillMaxSize(), contentDescription = stringResource(R.string.alert_picture, alert.title),
                error = { cause -> MediaMessage(stringResource(mediaErrorRes(cause.mediaFailure()))) },
            )
        } else {
            StaticIcon(alert.channel.glyph(), modifier = Modifier.size(48.dp))
        }
        when {
            video is VideoState.Downloading -> ProgressRing(video.fraction)
            alert.videoUrl != null -> {
                val label = stringResource(R.string.alert_play_video)
                FilledTonalIconButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = label }) {
                    StaticIcon(OikosGlyph.PLAY, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float?) {
    val label = stringResource(R.string.alert_downloading_video)
    val modifier = Modifier.size(48.dp).semantics { contentDescription = label }
    if (fraction != null) CircularProgressIndicator(progress = { fraction }, modifier = modifier) else CircularProgressIndicator(modifier = modifier)
}

@Composable
private fun MediaMessage(text: String) {
    Box(Modifier.fillMaxSize().background(OikosTheme.colors.card), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = OikosTheme.type.label,
            color = OikosTheme.colors.content,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun InfoChip(text: String) {
    val c = OikosTheme.colors
    Text(
        text,
        style = OikosTheme.type.label,
        color = c.content,
        modifier = Modifier.clip(CircleShape).background(c.chip).border(1.dp, c.chipBorder, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
