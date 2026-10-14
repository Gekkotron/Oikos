package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.transport.LinkState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class GroupCustomizeSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val group = HomeUiBuilder.build(
        listOf(
            testEntity("bay", Kind.COVER, "Bay window", area = "living"),
            testEntity("side", Kind.COVER, "Side window", area = "living"),
            testEntity("patio", Kind.COVER, "Patio door", area = "living"),
        ).associateBy { it.uniqueId },
        emptyMap(), listOf(Area("living", "Living room")), RoomFilter.All, LinkState.Connecting, Locale.US,
    ).cards.single { it.isGroup }

    private var saved: Triple<String, Boolean, List<String>>? = null

    private fun show() = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { GroupCustomizeSheet(group, onSave = { n, f, r -> saved = Triple(n, f, r) }, onDismiss = {}) }
    }

    @Test
    fun `unticking a member and saving reports it as removed, with the typed name`() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("Baie")
        compose.onNodeWithText("Patio door").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(Triple("Baie", false, listOf("patio")), saved)
    }

    @Test
    fun `Ungroup all removes every member`() {
        show()
        compose.onNodeWithText("Ungroup all").performScrollTo().performClick()
        assertEquals(Triple("", false, listOf("bay", "patio", "side")), saved) // members are in card sort order (by name)
    }

    @Test
    fun `toggling Favorite and saving reports the group as favorited`() {
        show()
        compose.onNodeWithText("Favourite").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(Triple(group.name, true, emptyList()), saved)
    }

    @Test
    fun `a member that joins while the sheet is open is not ungrouped by Save`() {
        var card by mutableStateOf(group)
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { GroupCustomizeSheet(card, onSave = { n, f, r -> saved = Triple(n, f, r) }, onDismiss = {}) } }
        compose.onNodeWithText("Patio door").performScrollTo().performClick()
        val newcomer = group.members.first().copy(id = "garden", name = "Garden window")
        card = group.copy(members = group.members + newcomer)
        compose.waitForIdle()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(Triple("", false, listOf("patio")), saved)
    }
}
