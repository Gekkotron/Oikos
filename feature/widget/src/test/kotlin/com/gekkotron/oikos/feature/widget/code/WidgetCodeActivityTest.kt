package com.gekkotron.oikos.feature.widget.code

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.feature.widget.testing.TestWidgetApp
import com.gekkotron.oikos.model.CodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestWidgetApp::class, qualifiers = "w411dp-h914dp")
class WidgetCodeActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestWidgetApp>()

    @Test
    fun the_code_goes_with_the_command_to_the_scheduler() {
        val intent = WidgetCodeActivity.intent(app, "a", WidgetCommand.Disarm, CodeFormat.NUMBER)
        ActivityScenario.launch<WidgetCodeActivity>(intent).use { scenario ->
            listOf("1", "2", "3", "4", "OK").forEach { compose.onNodeWithText(it).performClick() }
            compose.waitForIdle()
            assertEquals(listOf(Triple("a", WidgetCommand.Disarm as WidgetCommand, "1234")), app.scheduler.sent)
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun missing_extras_finish_without_sending() {
        val intent = Intent(ApplicationProvider.getApplicationContext<Context>(), WidgetCodeActivity::class.java)
        ActivityScenario.launch<WidgetCodeActivity>(intent).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            assertTrue(app.scheduler.sent.isEmpty())
        }
    }
}
