package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuleMatcherEnTest {
    private val catalog = VoiceCatalogBuilder.build(
        lang = VoiceLang.EN,
        entities = listOf(
            entity("living_shutter_1", "Living shutter 1", Kind.COVER, "living", position = true),
            entity("living_shutter_2", "Living shutter 2", Kind.COVER, "living", position = true),
            entity("living_light", "Living light", Kind.LIGHT, "living", brightness = true),
            entity("alarm", "Alarm", Kind.ALARM_PANEL, "entrance"),
        ),
        areas = listOf(area("living", "Living room"), area("entrance", "Entrance")),
    )
    private val matcher = RuleMatcher(catalog)

    @Test fun `open the shutters in the living room`() {
        val intent = matcher.resolve("open the shutters in the living room")
        assertNotNull(intent)
        assertEquals(VoiceAction.OPEN, intent.action)
        assertEquals(setOf("living_shutter_1", "living_shutter_2"), intent.entityIds.toSet())
    }

    @Test fun `set shutters to 30 percent`() {
        val intent = matcher.resolve("set the living shutters to 30 percent")
        assertNotNull(intent)
        assertEquals(VoiceAction.SET_POSITION, intent.action)
        assertEquals(30, intent.value)
    }

    @Test fun `disarm the alarm`() {
        val intent = matcher.resolve("disarm the alarm")
        assertNotNull(intent)
        assertEquals(VoiceAction.DISARM, intent.action)
    }
}
