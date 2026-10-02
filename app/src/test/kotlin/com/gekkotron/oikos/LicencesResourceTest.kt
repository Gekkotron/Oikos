package com.gekkotron.oikos

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.settings.licences.licenceRows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

// Only reads a resource, so the Hilt application is not needed.
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class LicencesResourceTest {
    @Test
    fun `generated licences include the bundled fonts and real dependencies`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val json = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        val names = licenceRows(json).map { it.name }
        assertTrue("Inter" in names && "Fraunces" in names, "fonts missing: $names")
        assertTrue(names.size > 10, "too few libraries: ${names.size}")
    }
}
