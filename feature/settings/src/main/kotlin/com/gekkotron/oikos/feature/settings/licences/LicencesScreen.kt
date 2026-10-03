package com.gekkotron.oikos.feature.settings.licences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.settings.R
import com.gekkotron.oikos.feature.settings.ui.SettingsTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [id] tells rows apart: library names repeat (several "Annotation" artifacts), so it is not the name. */
data class LicenceRow(val name: String, val version: String?, val licence: String, val text: String, val id: String = name)

/**
 * The AboutLibraries JSON as rows; unreadable JSON gives no rows rather than a crash.
 *
 * Parsed with kotlinx.serialization rather than aboutlibraries-core: that artifact is Java 21 bytecode and
 * needs compileSdk 37, while the build and CI run JDK 17 on compileSdk 36.
 */
fun licenceRows(json: String): List<LicenceRow> = try {
    val root = Json.parseToJsonElement(json).jsonObject
    val licences = root["licenses"]?.jsonObject.orEmpty()
    root["libraries"]?.jsonArray.orEmpty().map { it.jsonObject }.mapIndexed { index, lib ->
        val used = lib["licenses"]?.jsonArray.orEmpty().mapNotNull { id -> licences[id.jsonPrimitive.content]?.jsonObject }
        LicenceRow(
            name = lib.string("name") ?: lib.string("uniqueId").orEmpty(),
            version = lib.string("artifactVersion"),
            licence = used.mapNotNull { it.string("name") }.joinToString(", "),
            text = used.mapNotNull { it.string("content") }.joinToString("\n\n"),
            id = "${lib.string("uniqueId").orEmpty()}#$index",
        )
    }.sortedBy { it.name.lowercase() }
} catch (e: Exception) {
    emptyList()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

@Composable
fun LicencesRoute(loadJson: () -> String, onBack: () -> Unit) {
    val rows by produceState(emptyList<LicenceRow>(), loadJson) {
        value = withContext(Dispatchers.IO) { licenceRows(loadJson()) }
    }
    LicencesScreen(rows, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(rows: List<LicenceRow>, onBack: () -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        topBar = { SettingsTopBar(stringResource(R.string.licences_title), onBack) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(rows, key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth().heightIn(min = OikosDimens.minTouch).clickable { openId = row.id }.padding(vertical = 8.dp)) {
                    Text(row.name, style = OikosTheme.type.body)
                    Text(listOfNotNull(row.version, row.licence).filter { it.isNotBlank() }.joinToString(" · "), style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted)
                }
            }
        }
    }
    val open = openId?.let { id -> rows.firstOrNull { it.id == id } }
    if (open != null) {
        ModalBottomSheet(onDismissRequest = { openId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(open.name, style = OikosTheme.type.cardName)
                Text(open.text.ifBlank { open.licence }, style = OikosTheme.type.label, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { openId = null }, modifier = Modifier.align(Alignment.End).heightIn(min = OikosDimens.minTouch)) {
                    Text(stringResource(R.string.licences_close))
                }
            }
        }
    }
}
