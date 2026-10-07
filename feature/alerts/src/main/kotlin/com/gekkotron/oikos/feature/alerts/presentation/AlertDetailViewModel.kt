package com.gekkotron.oikos.feature.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

sealed interface DetailState {
    data object Loading : DetailState

    /** "This alert is no longer available". */
    data object Missing : DetailState

    /** [deviceId] is the entity "Open device" opens, or null. */
    data class Found(val alert: Alert, val deviceId: String?) : DetailState
}

sealed interface DetailEvent {
    data object Sent : DetailEvent

    data class CouldNotSend(val label: String) : DetailEvent
}

@HiltViewModel
class AlertDetailViewModel @Inject constructor(
    private val repository: AlertRepository,
    private val videos: VideoCache,
    private val replies: ReplyQueue,
    private val source: EntitySource,
) : ViewModel() {
    fun state(id: String): Flow<DetailState> = combine(repository.byId(id), source.entities) { alert, entities ->
        if (alert == null) DetailState.Missing else DetailState.Found(alert, EntityMatch.find(alert.entityId, entities))
    }

    /** Downloads the clip first, then hands the file to the player (spec §6.3). */
    fun video(alert: Alert): Flow<VideoState> = alert.videoUrl?.let { videos.file(alert.id, it) } ?: emptyFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = _events.receiveAsFlow()

    /** The same worker as the notification's buttons; the outcome comes back as a snackbar event. */
    fun send(alertId: String, action: AlertAction) {
        val id = replies.enqueue(alertId, action.id, action.label)
        viewModelScope.launch {
            val outcome = withTimeoutOrNull(REPLY_WAIT) { replies.state(id).first { it != ReplyState.PENDING } } ?: return@launch
            _events.send(if (outcome == ReplyState.SENT) DetailEvent.Sent else DetailEvent.CouldNotSend(action.label))
        }
    }

    private companion object {
        /** Five attempts with an exponential backoff from 10 s finish well within this. */
        val REPLY_WAIT = 10.minutes
    }
}
