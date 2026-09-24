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
import kotlinx.coroutines.flow.Flow

/** Spec §4.4 / §7: "Not confirmed — Retry" and refused commands, as snackbars. */
@Composable
fun HomeEventsEffect(events: Flow<HomeEvent>, snackbar: SnackbarHostState, onRetry: (String, Command) -> Unit) {
    val resources = LocalContext.current.resources
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(events, snackbar) {
        events.collect { event ->
            when (event) {
                is HomeEvent.NotConfirmed -> {
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.home_not_confirmed),
                        actionLabel = resources.getString(R.string.home_retry),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) retry(event.id, event.command)
                }
                is HomeEvent.Rejected -> snackbar.showSnackbar(resources.getString(R.string.home_rejected, event.reason))
            }
        }
    }
}
