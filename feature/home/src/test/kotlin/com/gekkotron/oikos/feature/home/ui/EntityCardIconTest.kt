package com.gekkotron.oikos.feature.home.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.home.presentation.HomeUiBuilder
import com.gekkotron.oikos.feature.home.presentation.RoomFilter
import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class EntityCardIconTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an open door card keeps its description and the icon adds no semantics`() {
        val door = testEntity("door", Kind.BINARY_SENSOR, "Front door", deviceClass = "door")
        val card = HomeUiBuilder.build(
            mapOf("door" to door), mapOf("door" to EntityState("door", StateValue.OnOff(true))),
            emptyList(), RoomFilter.All, LinkState.Connecting, Locale.US,
        ).card("door")!!
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { EntityCard(card, onClick = {}, onLongClick = {}) } }
        // R.string.card_description "%1$s, %2$s, %3$s" with value_contact_open and area_unassigned.
        compose.onNodeWithTag("card-door").assertContentDescriptionEquals("Front door, Open, Unassigned")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image), useUnmergedTree = true)
            .assertCountEquals(0)
    }
}
