package com.gekkotron.oikos.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicFilterTest {
    @Test
    fun `exact match`() {
        assertTrue(topicMatches("a/b", "a/b"))
        assertFalse(topicMatches("a/b", "a/c"))
        assertFalse(topicMatches("a/b", "a/b/c"))
    }

    @Test
    fun `single-level wildcard`() {
        assertTrue(topicMatches("a/+/c", "a/b/c"))
        assertFalse(topicMatches("a/+", "a/b/c"))
        assertTrue(topicMatches("fcm-push/tokens/+", "fcm-push/tokens/abc"))
    }

    @Test
    fun `multi-level wildcard includes the parent level`() {
        assertTrue(topicMatches("homeassistant/#", "homeassistant/light/x/config"))
        assertTrue(topicMatches("a/#", "a"))
        assertTrue(topicMatches("#", "anything/at/all"))
    }

    @Test
    fun `dollar topics are not matched by leading wildcards`() {
        assertFalse(topicMatches("#", "\$SYS/broker/uptime"))
        assertFalse(topicMatches("+/broker", "\$SYS/broker"))
        assertTrue(topicMatches("\$SYS/#", "\$SYS/broker/uptime"))
    }

    @Test
    fun `publish topics cannot contain wildcards`() {
        assertTrue(isValidPublishTopic("oikos/ui/0x00_light"))
        assertFalse(isValidPublishTopic("oikos/ui/a+b"))
        assertFalse(isValidPublishTopic("oikos/ui/#"))
        assertFalse(isValidPublishTopic(""))
    }
}
