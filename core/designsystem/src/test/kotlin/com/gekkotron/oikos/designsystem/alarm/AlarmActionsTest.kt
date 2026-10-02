package com.gekkotron.oikos.designsystem.alarm

import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmActionsTest {
    private val caps = Capabilities(alarmModes = setOf(AlarmMode.NIGHT, AlarmMode.HOME, AlarmMode.AWAY))
    private val modes = listOf(AlarmMode.HOME, AlarmMode.AWAY, AlarmMode.NIGHT)

    @Test
    fun `disarmed offers the supported modes in enum order and no disarm`() {
        assertEquals(AlarmActions(modes, null, disarm = false, enabled = true), AlarmActions.of(AlarmState.DISARMED, true, caps))
    }

    @Test
    fun `armed highlights its mode and offers disarm`() {
        assertEquals(AlarmActions(modes, AlarmMode.AWAY, disarm = true, enabled = true), AlarmActions.of(AlarmState.ARMED_AWAY, true, caps))
    }

    @Test
    fun `delays and triggered offer only disarm`() {
        for (s in listOf(AlarmState.ARMING, AlarmState.PENDING, AlarmState.TRIGGERED)) {
            assertEquals(AlarmActions(emptyList(), null, disarm = true, enabled = true), AlarmActions.of(s, true, caps), "$s")
        }
    }

    @Test
    fun `disarming offers nothing`() {
        assertEquals(AlarmActions(emptyList(), null, disarm = false, enabled = true), AlarmActions.of(AlarmState.DISARMING, true, caps))
    }

    @Test
    fun `unknown state offers disabled buttons`() {
        assertEquals(AlarmActions(modes, null, disarm = true, enabled = false), AlarmActions.of(null, true, caps))
    }

    @Test
    fun `unavailable disables everything but keeps the layout of its state`() {
        assertEquals(AlarmActions(modes, AlarmMode.HOME, disarm = true, enabled = false), AlarmActions.of(AlarmState.ARMED_HOME, false, caps))
    }

    @Test
    fun `code need follows the action`() {
        val c = Capabilities(codeArmRequired = false, codeDisarmRequired = true)
        assertFalse(AlarmActions.needsCode(c, Command.Arm(AlarmMode.AWAY)))
        assertTrue(AlarmActions.needsCode(c, Command.Disarm()))
        assertFalse(AlarmActions.needsCode(c, Command.TurnOff))
    }
}
