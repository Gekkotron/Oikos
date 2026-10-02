package com.gekkotron.oikos.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.gekkotron.oikos.R
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon

/** Energy and Security sit on top of Home and replace each other, so back always returns to Home (security spec §5). */
private val SIDE_TABS: Set<NavKey> = setOf(EnergyKey, SecurityKey)

internal fun selectTab(stack: MutableList<NavKey>, key: NavKey) {
    if (key == HomeKey) {
        stack.removeAll { it in SIDE_TABS }
        return
    }
    if (stack.lastOrNull() == key) return
    stack.removeAll { it in SIDE_TABS }
    stack.add(key)
}

/**
 * A new root (onboarding finished, "set up again") replaces the whole stack. The same root keeps it, so a
 * stack restored after rotation or a theme change stays on the Energy tab instead of jumping back to Home.
 */
internal fun resetToRoot(stack: MutableList<NavKey>, root: NavKey) {
    if (stack.firstOrNull() == root) return
    stack.add(root)
    stack.removeAll { it != root }
}

internal fun showsTabBar(top: NavKey?): Boolean = top == HomeKey || (top != null && top in SIDE_TABS)

@Composable
internal fun MainTabBar(selected: NavKey?, onSelect: (NavKey) -> Unit) {
    val colors = OikosTheme.colors
    val itemColors = NavigationBarItemDefaults.colors(
        // The icon sits on the indicator pill; the label sits below it, on the bar.
        selectedIconColor = colors.chipSelectedContent,
        selectedTextColor = colors.content,
        unselectedIconColor = colors.contentMuted,
        unselectedTextColor = colors.contentMuted,
        indicatorColor = colors.chipSelected,
    )
    NavigationBar(containerColor = colors.backgroundTop.copy(alpha = 0.92f)) {
        NavigationBarItem(
            selected = selected == HomeKey,
            onClick = { onSelect(HomeKey) },
            icon = { StaticIcon(OikosGlyph.HOUSE, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_home)) },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selected == EnergyKey,
            onClick = { onSelect(EnergyKey) },
            icon = { StaticIcon(OikosGlyph.BOLT, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_energy)) },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selected == SecurityKey,
            onClick = { onSelect(SecurityKey) },
            icon = { StaticIcon(OikosGlyph.SHIELD, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_security)) },
            colors = itemColors,
        )
    }
}
