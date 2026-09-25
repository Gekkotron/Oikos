package com.gekkotron.oikos.feature.home.presentation

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class EntityLabelsTest {
    @Test
    fun `normalize trims, lowercases, folds separators and collapses whitespace`() {
        assertEquals("local temperature", EntityLabels.normalize("  Local_Temperature "))
        assertEquals("battery low", EntityLabels.normalize("Battery-low"))
        assertEquals("co2", EntityLabels.normalize("CO2"))
        assertEquals("water leak", EntityLabels.normalize("water__leak"))
    }

    @Test
    fun `resId resolves known labels regardless of case or separators`() {
        assertNotNull(EntityLabels.resId("Temperature"))
        assertNotNull(EntityLabels.resId("local_temperature"))
        assertNotNull(EntityLabels.resId("BATTERY"))
    }

    @Test
    fun `resId returns null for unknown labels`() {
        assertNull(EntityLabels.resId("Frobnicator"))
        assertNull(EntityLabels.resId(""))
    }

    @Test
    fun `every mapped label resolves to a non-blank English string`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        EntityLabels.keys.forEach { key ->
            val resId = assertNotNull(EntityLabels.resId(key), "no resource for '$key'")
            assertTrue(context.getString(resId).isNotBlank(), "blank EN string for '$key'")
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `every mapped label resolves to a non-blank French string`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        EntityLabels.keys.forEach { key ->
            val resId = assertNotNull(EntityLabels.resId(key), "no resource for '$key'")
            assertTrue(context.getString(resId).isNotBlank(), "blank FR string for '$key'")
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `temperature translates to Temperature in French`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Température", context.getString(EntityLabels.resId("temperature")!!))
    }
}
