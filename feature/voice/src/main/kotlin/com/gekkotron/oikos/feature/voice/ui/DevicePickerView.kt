package com.gekkotron.oikos.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.feature.voice.R
import com.gekkotron.oikos.feature.voice.VoiceSessionState
import com.gekkotron.oikos.voice.VoiceCatalog
import com.gekkotron.oikos.voice.VoiceEntity
import com.gekkotron.oikos.voice.VoiceLexicon
import java.text.Normalizer

private val DIACRITICS = Regex("\\p{Mn}+")

private fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

/** Entities matching [filter], grouped by area in catalog area order; entities of unknown areas come last. */
private fun groupedMatches(catalog: VoiceCatalog, filter: String): List<Pair<String?, List<VoiceEntity>>> {
    val needle = fold(filter.trim())
    val matching = catalog.entities.filter { e ->
        needle.isEmpty() ||
            fold(e.displayName).contains(needle) ||
            fold(catalog.area(e.areaId)?.displayName.orEmpty()).contains(needle) ||
            fold(VoiceLexicon.kindLabel(catalog.lang, e.kind)).contains(needle)
    }
    val byArea = matching.groupBy { it.areaId }
    val known = catalog.areas.mapNotNull { a -> byArea[a.id]?.let { a.displayName to it } }
    val knownIds = catalog.areas.map { it.id }.toSet()
    val orphans = matching.filter { it.areaId !in knownIds }
    return if (orphans.isEmpty()) known else known + (null to orphans)
}

@Composable
internal fun ColumnScope.DevicePickerView(
    picking: VoiceSessionState.Picking,
    catalog: VoiceCatalog,
    stateHints: Map<String, String>,
    onPickDevice: (uniqueId: String) -> Unit,
    onFilterChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = OikosTheme.colors
    val groups = remember(catalog, picking.filter) { groupedMatches(catalog, picking.filter) }
    OutlinedTextField(
        value = picking.filter,
        onValueChange = onFilterChange,
        placeholder = { Text(stringResource(R.string.voice_picker_search_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (groups.isEmpty()) {
        Text(stringResource(R.string.voice_picker_empty), style = OikosTheme.type.body, color = c.contentMuted)
    } else {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { (areaName, entities) ->
                if (areaName != null) {
                    item(key = "area:$areaName") {
                        Text(areaName, style = OikosTheme.type.label, color = c.contentMuted)
                    }
                }
                items(entities.size, key = { entities[it].uniqueId }) { i ->
                    val e = entities[i]
                    val kind = VoiceLexicon.kindLabel(catalog.lang, e.kind)
                    val hint = stateHints[e.uniqueId]
                    DeviceRow(e.displayName, if (hint.isNullOrBlank()) kind else "$kind · $hint") { onPickDevice(e.uniqueId) }
                }
            }
        }
    }
    VoiceRow(stringResource(R.string.voice_picker_back), onBack)
}

/** Same pill look as [VoiceRow], with a bold name and a muted trailing detail. */
@Composable
private fun DeviceRow(name: String, detail: String, onClick: () -> Unit) {
    val c = OikosTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(c.chip)
            .border(1.dp, c.chipBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            name,
            style = OikosTheme.type.body.copy(fontWeight = FontWeight.Bold),
            color = c.content,
            modifier = Modifier.weight(1f),
        )
        Text(detail, style = OikosTheme.type.body, color = c.contentMuted, modifier = Modifier.padding(start = 12.dp))
    }
}
