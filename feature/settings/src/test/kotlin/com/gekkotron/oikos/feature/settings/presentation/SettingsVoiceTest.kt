package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.feature.settings.FakeEntitySource
import com.gekkotron.oikos.feature.settings.FakePushStatusSource
import com.gekkotron.oikos.feature.voice.SpeechAvailability
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsVoiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    // DataStore runs on its own scope, not on the test's backgroundScope: its cancellation during @After
    // would otherwise hit Dispatchers.Main.immediate after resetMain and crash on CI runners.
    private val storage = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private object PassThrough : SecretStore {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(cipher: ByteArray) = cipher.copyOf()
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        storage.cancel()
        Dispatchers.resetMain()
    }

    private fun shortcut(phrase: String, lang: VoiceLang, vararg ids: String) =
        VoiceShortcut(VoiceShortcut.id(lang, phrase), phrase, lang, VoiceIntent(VoiceAction.TURN_ON, ids.toList()))

    private fun kotlinx.coroutines.test.TestScope.vm(source: FakeEntitySource, prefs: AppPreferences): SettingsViewModel {
        val profiles = ProfileRepository.create(tmp.root.resolve("profile.json"), PassThrough, storage)
        val vm = SettingsViewModel(profiles, prefs, FakePushStatusSource(), source, SpeechAvailability.Status.Ready)
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun `voice is off by default`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs.json"), storage)
        val vm = vm(FakeEntitySource(), prefs)
        assertEquals(false, vm.state.value.voice.enabled)
    }

    @Test
    fun `setVoiceEnabled persists the preference`() = runTest(dispatcher) {
        val prefs = AppPreferences.create(tmp.root.resolve("prefs.json"), storage)
        val vm = vm(FakeEntitySource(), prefs)
        vm.setVoiceEnabled(true)
        assertEquals(true, prefs.voiceEnabled.first { it == true })
        assertTrue(vm.state.first { it.voice.enabled }.voice.enabled)
    }

    @Test
    fun `a shortcut whose entity is gone is listed as broken`() = runTest(dispatcher) {
        val source = FakeEntitySource()
        val sc = shortcut("movie night", VoiceLang.FR, "light.gone")
        source.shortcuts.value = mapOf(sc.id to sc)
        val vm = vm(source, AppPreferences.create(tmp.root.resolve("prefs.json"), storage))
        val rows = vm.state.value.voice.shortcuts
        assertEquals(1, rows.size)
        assertTrue(rows.single().broken)
        assertEquals("movie night", rows.single().phrase)
    }

    @Test
    fun `a shortcut in the other language keeps its own lang`() = runTest(dispatcher) {
        val source = FakeEntitySource()
        val sc = shortcut("movie night", VoiceLang.EN, "light.gone")
        source.shortcuts.value = mapOf(sc.id to sc)
        val vm = vm(source, AppPreferences.create(tmp.root.resolve("prefs.json"), storage))
        val row = vm.state.value.voice.shortcuts.single()
        assertEquals(VoiceLang.EN, row.lang)
        assertTrue(row.label.endsWith("(1)"))
    }

    @Test
    fun `deleteShortcut removes the row`() = runTest(dispatcher) {
        val source = FakeEntitySource()
        val sc = shortcut("movie night", VoiceLang.FR, "light.gone")
        source.shortcuts.value = mapOf(sc.id to sc)
        val vm = vm(source, AppPreferences.create(tmp.root.resolve("prefs.json"), storage))
        vm.deleteShortcut(sc.id)
        assertTrue(vm.state.value.voice.shortcuts.isEmpty())
    }
}
