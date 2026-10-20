package com.gekkotron.oikos.feature.home.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.gekkotron.oikos.feature.home.R
import com.gekkotron.oikos.feature.home.presentation.HomeEvent
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.Kind
import kotlinx.coroutines.flow.Flow

/** Spec §4.4 / §7: "Not confirmed — Retry" and refused commands, as snackbars. */
@Composable
fun HomeEventsEffect(
    events: Flow<HomeEvent>,
    snackbar: SnackbarHostState,
    onRetry: (String, Command) -> Unit,
    onRetryGroup: (String, Command, List<String>) -> Unit = { _, _, _ -> },
    onAsk: (HomeEvent.AskBeforeAction) -> Unit = {},
) {
    val resources = LocalContext.current.resources
    val retry by rememberUpdatedState(onRetry)
    val retryGroup by rememberUpdatedState(onRetryGroup)
    val ask by rememberUpdatedState(onAsk)
    LaunchedEffect(events, snackbar) {
        events.collect { event ->
            when (event) {
                is HomeEvent.AskBeforeAction -> ask(event)
                is HomeEvent.NotConfirmed -> {
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.home_not_confirmed),
                        actionLabel = resources.getString(R.string.home_retry),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) retry(event.id, event.command)
                }
                is HomeEvent.Rejected -> snackbar.showSnackbar(resources.getString(R.string.home_rejected, event.reason))
                HomeEvent.SaveFailed -> snackbar.showSnackbar(resources.getString(R.string.home_save_failed))
                HomeEvent.WaitingForConnection -> snackbar.showSnackbar(resources.getString(R.string.home_waiting_connection))
                is HomeEvent.GroupNotConfirmed -> {
                    val message = if (event.failedIds.size == 1) {
                        resources.getString(R.string.home_group_not_confirmed_one, event.failedNames.single())
                    } else {
                        val plural = if (event.kind == Kind.COVER) R.plurals.home_group_not_confirmed_covers else R.plurals.home_group_not_confirmed_lights
                        resources.getQuantityString(plural, event.failedIds.size, event.failedIds.size)
                    }
                    val result = snackbar.showSnackbar(message = message, actionLabel = resources.getString(R.string.home_retry), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) retryGroup(event.groupId, event.command, event.failedIds)
                }
            }
        }
    }
}
