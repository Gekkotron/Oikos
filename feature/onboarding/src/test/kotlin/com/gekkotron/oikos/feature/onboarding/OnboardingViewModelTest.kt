package com.gekkotron.oikos.feature.onboarding

import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.PayloadError
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secrets = PassThroughSecretStore()
    private val tester = FakeConnectionTester()
    private lateinit var profiles: ProfileRepository
    private lateinit var vm: OnboardingViewModel

    private val qr = """{"v":1,"local":{"host":"192.168.1.91","user":"oikos","pass":"s3cret"}}"""
    private val qrProfile = ConnectionProfile.Direct(LocalBroker("192.168.1.91", username = "oikos", password = "s3cret"))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        profiles = ProfileRepository.create(tmp.root.resolve("profile.json"), secrets, io)
        vm = OnboardingViewModel(profiles, tester)
    }

    @After
    fun tearDown() {
        // vm is never cleared by a ViewModelStore in this direct-construction test, so its
        // viewModelScope (and its collection of profiles.state) would otherwise leak past this
        // test method and race the next test's Dispatchers.setMain()/resetMain() swap. Joining
        // (not just requesting cancellation) is required: profiles.state is backed by a real
        // Dispatchers.IO DataStore write that can emit at any wall-clock moment, so the
        // StateFlow subscriber slot must be fully unregistered before Main is reset.
        closeViewModel(vm)
        Dispatchers.resetMain()
        io.cancel()
    }

    private fun closeViewModel(viewModel: OnboardingViewModel) = runBlocking {
        viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test
    fun `a valid QR code goes to Connect and tests the parsed profile`() {
        vm.openScan()
        vm.onQrScanned(qr)
        assertEquals(Step.CONNECT, vm.state.value.step)
        assertEquals(listOf(qrProfile), tester.drafts)
    }

    @Test
    fun `an invalid QR code shows an error and stays on Scan`() {
        vm.openScan()
        vm.onQrScanned("https://example.com")
        assertEquals(Step.SCAN, vm.state.value.step)
        assertEquals(PayloadError.NotOikosCode, vm.state.value.scanError)
        assertTrue(tester.drafts.isEmpty())
    }

    @Test
    fun `a valid code after an invalid one connects`() {
        vm.openScan()
        repeat(5) { vm.onQrScanned("not a setup code") }
        vm.onQrScanned(qr)
        assertEquals(Step.CONNECT, vm.state.value.step)
        assertEquals(listOf(qrProfile), tester.drafts)
    }

    @Test
    fun `scans outside the Scan step are ignored`() {
        vm.onQrScanned(qr)
        assertEquals(Step.WELCOME, vm.state.value.step)
        assertTrue(tester.drafts.isEmpty())
    }

    @Test
    fun `manual form with errors does not connect and shows errors`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = "mqtt://x"))
        vm.submitManual()
        assertEquals(Step.MANUAL, vm.state.value.step)
        assertEquals(setOf(FormField.HOST), vm.state.value.formErrors)
        assertTrue(tester.drafts.isEmpty())
    }

    @Test
    fun `errors stay hidden until the first submit`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = ""))
        assertEquals(emptySet(), vm.state.value.formErrors)
    }

    @Test
    fun `a valid manual form connects`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = "broker.local"))
        vm.submitManual()
        assertEquals(Step.CONNECT, vm.state.value.step)
        assertEquals(listOf(ConnectionProfile.Direct(LocalBroker("broker.local"))), tester.drafts)
    }

    @Test
    fun `test results reach the state`() {
        vm.openScan()
        vm.onQrScanned(qr)
        tester.result.value = TestState.Failed(TestFailure.Rejected)
        assertEquals(TestState.Failed(TestFailure.Rejected), vm.state.value.test)
    }

    @Test
    fun `retry restarts the test`() {
        vm.openScan()
        vm.onQrScanned(qr)
        vm.retry()
        assertEquals(2, tester.drafts.size)
        assertEquals(1, tester.active)
    }

    @Test
    fun `continue does nothing until connected`() = runTest {
        vm.openScan()
        vm.onQrScanned(qr)
        vm.finish()
        assertEquals(ProfileState.None, profiles.state.first { it !is ProfileState.Loading })
    }

    @Test
    fun `continue cancels the test before saving`() = runTest {
        vm.openScan()
        vm.onQrScanned(qr)
        tester.result.value = TestState.Connected(listOf("Kettle"))
        vm.finish()
        assertEquals(ProfileState.Ready(qrProfile), profiles.state.first { it is ProfileState.Ready })
        assertEquals(0, tester.active)
    }

    @Test
    fun `a failed save shows an error instead of crashing, and continue can be tried again`() = runBlocking {
        secrets.failEncrypt = true
        vm.openScan()
        vm.onQrScanned(qr)
        tester.result.value = TestState.Connected(listOf("Kettle"))
        vm.finish()
        val failed = withTimeout(10.seconds) { vm.state.first { it.saveFailed && !it.saving } }
        assertEquals(Step.CONNECT, failed.step)
        assertEquals(ProfileState.None, profiles.state.first { it !is ProfileState.Loading })

        secrets.failEncrypt = false
        vm.finish()
        assertEquals(ProfileState.Ready(qrProfile), withTimeout(10.seconds) { profiles.state.first { it is ProfileState.Ready } })
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun `back from Connect cancels the test and returns to where it came from`() {
        vm.openManual()
        vm.updateForm(ManualForm(host = "broker.local"))
        vm.submitManual()
        assertTrue(vm.back())
        assertEquals(Step.MANUAL, vm.state.value.step)
        assertEquals(0, tester.active)
    }

    @Test
    fun `back on Welcome is not handled`() = assertFalse(vm.back())

    @Test
    fun `try the demo saves the demo profile`() = runTest {
        vm.tryDemo()
        assertEquals(ProfileState.Ready(ConnectionProfile.Demo), profiles.state.first { it is ProfileState.Ready })
    }

    @Test
    fun `a Locked profile shows the unlock notice`() = runTest {
        profiles.save(qrProfile)
        profiles.state.first { it is ProfileState.Ready }
        val copy = tmp.root.resolve("copy.json").also { tmp.root.resolve("profile.json").copyTo(it) }
        secrets.fail = true
        val locked = ProfileRepository.create(copy, secrets, io)
        val lockedVm = OnboardingViewModel(locked, tester)
        locked.state.first { it == ProfileState.Locked }
        assertTrue(lockedVm.state.first { it.locked }.locked)
        // A second, locally-scoped ViewModel in this test; release it the same way tearDown() does for vm.
        closeViewModel(lockedVm)
    }
}
