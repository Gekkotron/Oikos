package com.gekkotron.oikos.feature.alerts.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.sync.HistorySync
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.alerts.FakePushStatusSource
import com.gekkotron.oikos.feature.alerts.presentation.RecentAlertsViewModel
import com.gekkotron.oikos.transport.MqttMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class RecentAlertsRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: AlertDatabase
    private lateinit var history: HistorySync
    private lateinit var viewModel: RecentAlertsViewModel
    private val status = FakePushStatusSource()
    private val snackbar = SnackbarHostState()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        val repo = AlertRepository(db.alerts())
        history = HistorySync(MutableSharedFlow<MqttMessage>(), repo)
        viewModel = RecentAlertsViewModel(repo, status, history)
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) { RecentAlertsRoute(snackbar, onOpenAll = {}, viewModel = viewModel) }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the permission is re-read every time the tab resumes`(): Unit {
        compose.waitForIdle()
        val first = status.refreshes
        assertTrue(first >= 1)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        assertEquals(first, status.refreshes)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(first + 1, status.refreshes)
    }

    @Test
    fun `a sync that brought alerts shows the catch-up snackbar once and acknowledges it`(): Unit {
        runBlocking { history.handle("""[{"v":1,"id":"x","title":"X","sent_at":1790000000},{"v":1,"id":"y","title":"Y","sent_at":1790000001}]""") }
        compose.waitUntil(5_000) { snackbar.currentSnackbarData != null }
        assertEquals("2 alerts arrived while you were offline", snackbar.currentSnackbarData?.visuals?.message)
        assertEquals(0, viewModel.catchUp.value)
    }
}
