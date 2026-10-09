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

    private var saved: Pair<String, List<String>>? = null

    private fun show() = compose.setContent {
        OikosTheme(ThemeChoice.NIGHT_GLASS) { GroupCustomizeSheet(group, onSave = { n, r -> saved = n to r }, onDismiss = {}) }
    }

    @Test
    fun `unticking a member and saving reports it as removed, with the typed name`() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("Baie")
        compose.onNodeWithText("Patio door").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals("Baie" to listOf("patio"), saved)
    }

    @Test
    fun `Ungroup all removes every member`() {
        show()
        compose.onNodeWithText("Ungroup all").performScrollTo().performClick()
        assertEquals("" to listOf("bay", "patio", "side"), saved) // members are in card sort order (by name)
    }
}
