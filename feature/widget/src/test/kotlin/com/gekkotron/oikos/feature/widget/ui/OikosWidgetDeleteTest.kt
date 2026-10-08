package com.gekkotron.oikos.feature.widget.ui

import androidx.glance.appwidget.AppWidgetId
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.widget.model.WidgetConfig
import com.gekkotron.oikos.feature.widget.testing.TestWidgetApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Removing a widget forgets its config, inside Glance's own goAsync (a second goAsync() would return null and crash). */
@RunWith(AndroidJUnit4::class)
@Config(application = TestWidgetApp::class)
class OikosWidgetDeleteTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestWidgetApp>()

    @Test
    fun deleting_a_widget_forgets_only_its_config() = runTest {
        app.configs.put(7, WidgetConfig(listOf("a")))
        app.configs.put(8, WidgetConfig(listOf("b")))
        OikosWidget().onDelete(app, AppWidgetId(7))
        assertNull(app.configs.get(7))
        assertEquals(WidgetConfig(listOf("b")), app.configs.get(8))
    }
}
