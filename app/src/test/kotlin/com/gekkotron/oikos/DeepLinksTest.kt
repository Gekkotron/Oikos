package com.gekkotron.oikos

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DeepLinksTest {
    @Test
    fun `an alert link gives its id`() {
        assertEquals("frigate-front-1696", DeepLinks.alertId(Uri.parse("oikos://alert/frigate-front-1696")))
    }

    @Test
    fun `an id with path characters survives the notification's own encoding`() {
        val built = Uri.Builder().scheme("oikos").authority("alert").appendPath("a/b c").build()
        assertEquals("a/b c", DeepLinks.alertId(built))
    }

    @Test
    fun `other schemes, hosts and empty ids are not alert links`() {
        assertNull(DeepLinks.alertId(Uri.parse("https://alert/x")))
        assertNull(DeepLinks.alertId(Uri.parse("oikos://reply/x")))
        assertNull(DeepLinks.alertId(Uri.parse("oikos://alert")))
        assertNull(DeepLinks.alertId(Uri.parse("oikos://alert/")))
        assertNull(DeepLinks.alertId(null))
    }

    private fun view(flags: Int = 0) = Intent(Intent.ACTION_VIEW, Uri.parse("oikos://alert/a1")).addFlags(flags)

    @Test
    fun `a tap on a notification opens its alert`() {
        assertEquals("a1", DeepLinks.alertToOpen(view()))
    }

    @Test
    fun `a relaunch from Recents does not replay the old notification link`() {
        assertNull(DeepLinks.alertToOpen(view(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertNull(DeepLinks.alertToOpen(null))
    }
}
