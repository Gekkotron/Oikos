package com.gekkotron.oikos.navigation

import org.junit.Test
import kotlin.test.assertEquals

class AlertLinkTest {
    @Test
    fun `nothing pending does nothing`() {
        assertEquals(AlertLinkAction.NONE, alertLinkAction(HomeKey, null))
    }

    @Test
    fun `a pending link opens once Home is the root`() {
        assertEquals(AlertLinkAction.OPEN, alertLinkAction(HomeKey, "a1"))
    }

    @Test
    fun `a pending link waits while rooms or the notification step are still ahead`() {
        assertEquals(AlertLinkAction.WAIT, alertLinkAction(RoomsKey, "a1"))
        assertEquals(AlertLinkAction.WAIT, alertLinkAction(NotificationsKey, "a1"))
    }

    @Test
    fun `a pending link is dropped on onboarding, there is no house to show it in`() {
        assertEquals(AlertLinkAction.DROP, alertLinkAction(OnboardingKey, "a1"))
    }
}
