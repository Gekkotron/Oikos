package com.gekkotron.oikos.feature.alerts.presentation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.Alert
import com.gekkotron.oikos.alerts.AlertAction
import com.gekkotron.oikos.alerts.AlertChannel
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.Severity
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.media.Media
import com.gekkotron.oikos.alerts.media.MediaSource
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.alerts.media.VideoState
import com.gekkotron.oikos.feature.alerts.FakeEntitySource
import com.gekkotron.oikos.model.Bindings
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.ReplyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlertDetailViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val source = FakeEntitySource()
    private val replyState = MutableStateFlow(ReplyState.PENDING)
    private val enqueued = mutableListOf<Triple<String, String, String>>()

    private val replies = object : ReplyQueue {
        override fun enqueue(alertId: String, actionId: String, label: String): UUID {
            enqueued += Triple(alertId, actionId, label)
            return UUID.randomUUID()
        }

        override fun state(id: UUID): Flow<ReplyState> = replyState

        override fun cancelAll() = Unit
    }

    private object NoBindings : Bindings

    private val clipSource = object : MediaSource {
        override suspend fun bytes(url: String, maxBytes: Long, onProgress: (Long, Long?) -> Unit) = Result.success(Media(ByteArray(3), "video/mp4"))
    }

    private fun alert(entityId: String? = null, videoUrl: String? = null) = Alert(
        "frigate-1", AlertChannel.CAMERA, Severity.WARNING, "Front door", "Person", null, videoUrl, entityId,
        listOf(AlertAction("disarm", "Disarm")), "frigate", false, false, Instant.EPOCH, Instant.EPOCH,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun vm() = AlertDetailViewModel(repo, VideoCache(File(tmp.root, "video"), clipSource), replies, source)

    @Test
    fun `an alert in Room is Found with its matching device`(): Unit = runTest(dispatcher) {
        source.entities.value = mapOf("front_door" to Entity("front_door", null, "Front door", Kind.BINARY_SENSOR, "door", null, null, Capabilities(), NoBindings))
        repo.upsert(alert(entityId = "binary_sensor.front_door"))
        val found = assertIs<DetailState.Found>(vm().state("frigate-1").first())
        assertEquals("front_door", found.deviceId)
        assertEquals("Front door", found.alert.title)
    }

    @Test
    fun `no matching entity means no Open device`(): Unit = runTest(dispatcher) {
        repo.upsert(alert(entityId = "binary_sensor.window"))
        assertEquals(null, assertIs<DetailState.Found>(vm().state("frigate-1").first()).deviceId)
    }

    @Test
    fun `an alert that is not in Room is Missing`(): Unit = runTest(dispatcher) {
        assertEquals(DetailState.Missing, vm().state("nope").first())
    }

    @Test
    fun `a clip is downloaded and ready, an alert without a clip has no video`(): Unit = runTest(dispatcher) {
        val states = vm().video(alert(videoUrl = "http://h/c.mp4")).toList()
        assertIs<VideoState.Ready>(states.last())
        assertEquals(emptyList(), vm().video(alert()).toList())
    }

    @Test
    fun `an action is queued and the outcome is reported as Sent`(): Unit = runTest(dispatcher) {
        val vm = vm()
        vm.send("frigate-1", AlertAction("disarm", "Disarm"))
        assertEquals(listOf(Triple("frigate-1", "disarm", "Disarm")), enqueued)
        replyState.value = ReplyState.SENT
        assertEquals(DetailEvent.Sent, vm.events.first())
    }

    @Test
    fun `a reply that gives up is reported with its label`(): Unit = runTest(dispatcher) {
        val vm = vm()
        vm.send("frigate-1", AlertAction("disarm", "Disarm"))
        replyState.value = ReplyState.FAILED
        assertEquals(DetailEvent.CouldNotSend("Disarm"), vm.events.first())
    }
}
