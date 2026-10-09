package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AreaTest {
    @Test
    fun `group names round-trip and an old document decodes with none`() {
        val doc = AreasDocument(listOf(Area("living", "Living room", groupNames = mapOf("cover" to "Volets baie"))))
        val json = OikosJson.encodeToString(AreasDocument.serializer(), doc)
        assertEquals(doc, OikosJson.decodeFromString(AreasDocument.serializer(), json))
        val old = OikosJson.decodeFromString(AreasDocument.serializer(), """{"areas":[{"id":"living","name":"Living room"}]}""")
        assertEquals(emptyMap(), old.areas.single().groupNames)
    }
}
