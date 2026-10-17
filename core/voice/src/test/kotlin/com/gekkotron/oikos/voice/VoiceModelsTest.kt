package com.gekkotron.oikos.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceModelsTest {
    @Test fun `VoiceIntent default hint is null`() {
        assertNull(VoiceIntent(VoiceAction.TURN_ON, listOf("a")).hint)
        assertNull(VoiceIntent(VoiceAction.SET_POSITION, listOf("a"), 40).hint)
    }

    @Test fun `VoiceIntent carries an explicit hint`() {
        assertEquals(QueryHint.EXPECT_CLOSED, VoiceIntent(VoiceAction.QUERY_STATE, listOf("a"), hint = QueryHint.EXPECT_CLOSED).hint)
    }

    @Test fun `VoiceAction contains QUERY_STATE`() {
        assertTrue(VoiceAction.entries.contains(VoiceAction.QUERY_STATE))
    }

    @Test fun `QueryHint has four expected values`() {
        assertEquals(
            listOf(QueryHint.EXPECT_ON, QueryHint.EXPECT_OFF, QueryHint.EXPECT_OPEN, QueryHint.EXPECT_CLOSED),
            QueryHint.entries,
        )
    }
}
