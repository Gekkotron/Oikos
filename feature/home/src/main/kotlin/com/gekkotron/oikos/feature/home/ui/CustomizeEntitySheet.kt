package com.gekkotron.oikos.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.feature.home.presentation.CustomizeLoad
import com.gekkotron.oikos.feature.home.presentation.CustomizeViewModel

/** Home's override sheet for [id], for tabs that show devices Home leaves out (Security). */
@Composable
fun CustomizeEntitySheet(
    id: String,
    onDismiss: () -> Unit,
    onSaveFailed: () -> Unit,
    viewModel: CustomizeViewModel = hiltViewModel(),
) {
    val load by remember(id) { viewModel.load(id) }.collectAsStateWithLifecycle(CustomizeLoad.Loading)
    when (val l = load) {
        CustomizeLoad.Loading -> Unit
        CustomizeLoad.Gone -> LaunchedEffect(id) { onDismiss() }
        is CustomizeLoad.Ready -> CustomizeSheet(
            l.card,
            l.rooms,
            onSave = { newRooms, override -> viewModel.save(id, newRooms, override, onSaveFailed) },
            onDismiss = onDismiss,
        )
    }
}
