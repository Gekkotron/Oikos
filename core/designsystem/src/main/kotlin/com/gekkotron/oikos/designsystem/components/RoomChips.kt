package com.gekkotron.oikos.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme

data class ChipItem(val key: String, val label: String)

@Composable
fun RoomChips(items: List<ChipItem>, selectedKey: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = OikosTheme.colors
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.key }) { item ->
            val selected = item.key == selectedKey
            Box(
                Modifier
                    .heightIn(min = OikosDimens.minTouch)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(item.key) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.label,
                    style = OikosTheme.type.label,
                    color = if (selected) c.chipSelectedContent else c.content,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) c.chipSelected else c.chip)
                        .border(1.dp, if (selected) c.chipSelected else c.chipBorder, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
