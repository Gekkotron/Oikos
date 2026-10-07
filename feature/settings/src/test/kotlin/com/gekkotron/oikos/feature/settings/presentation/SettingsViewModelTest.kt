package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.feature.settings.FakePushStatusSource
import com.gekkotron.oikos.push.PushStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private object PassThrough : SecretStore {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(cipher: ByteArray) = cipher.copyOf()
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state follows the theme and the profile`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile.json"), PassThrough, backgroundScope)
        val vm = SettingsViewModel(profiles, prefs, FakePushStatusSource())
        backgroundScope.launch { vm.state.collect {} }
        profiles.save(ConnectionProfile.Demo)
        assertEquals(ConnectionSummary.Demo, vm.state.first { it.connection != null }.connection)
        vm.setTheme(ThemePreference.WARM_MINIMAL)
        assertEquals(ThemePreference.WARM_MINIMAL, vm.state.first { it.theme == ThemePreference.WARM_MINIMAL }.theme)
    }

    @Test
    fun `a cleared profile leaves no connection summary`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile.json"), PassThrough, backgroundScope)
        val vm = SettingsViewModel(profiles, prefs, FakePushStatusSource())
        backgroundScope.launch { vm.state.collect {} }
        profiles.save(ConnectionProfile.Demo)
        vm.state.first { it.connection != null }
        profiles.clear()
        assertEquals(null, vm.state.first { it.connection == null }.connection)
    }

    @Test
    fun `state carries the push status and follows it`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs2.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile2.json"), PassThrough, backgroundScope)
        val push = FakePushStatusSource(PushStatus.Waiting)
        val vm = SettingsViewModel(profiles, prefs, push)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(PushStatus.Waiting, vm.state.value.push)
        push.status.value = PushStatus.NotificationsOff
        assertEquals(PushStatus.NotificationsOff, vm.state.first { it.push == PushStatus.NotificationsOff }.push)
    }

    @Test
    fun `register again and refresh go to the push source`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs3.json"), backgroundScope)
        val profiles = ProfileRepository.create(tmp.root.resolve("profile3.json"), PassThrough, backgroundScope)
        val push = FakePushStatusSource()
        val vm = SettingsViewModel(profiles, prefs, push)
        vm.registerAgain()
        vm.refreshPush()
        assertEquals(1, push.registerAgains)
        assertEquals(1, push.refreshes)
    }
}
