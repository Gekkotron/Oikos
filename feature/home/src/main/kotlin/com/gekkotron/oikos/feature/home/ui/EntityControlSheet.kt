package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.feature.home.presentation.CustomizeLoad
import com.gekkotron.oikos.feature.home.presentation.CustomizeViewModel

/**
 * Home's control sheet for [id], for screens that show a device Home does not own (the alert sheet's "Open device",
 * push alerts spec §7.3). Its Customize link opens the same override sheet Home uses.
 */
@Composable
fun EntityControlSheet(
    id: String,
    onDismiss: () -> Unit,
    onSaveFailed: () -> Unit,
    viewModel: CustomizeViewModel = hiltViewModel(),
) {
    val load by remember(id) { viewModel.load(id) }.collectAsStateWithLifecycle(CustomizeLoad.Loading)
    var customizing by rememberSaveable(id) { mutableStateOf(false) }
    when (val l = load) {
        CustomizeLoad.Loading -> Unit
        CustomizeLoad.Gone -> LaunchedEffect(id) { onDismiss() }
        is CustomizeLoad.Ready ->
            if (customizing) {
                CustomizeSheet(
                    l.card,
                    l.rooms,
                    onSave = { rooms, override -> viewModel.save(id, rooms, override, onSaveFailed) },
                    onDismiss = { customizing = false },
                )
            } else {
                ControlSheet(l.card, onSend = viewModel::send, onCustomize = { customizing = true }, onDismiss = onDismiss)
            }
    }
}
