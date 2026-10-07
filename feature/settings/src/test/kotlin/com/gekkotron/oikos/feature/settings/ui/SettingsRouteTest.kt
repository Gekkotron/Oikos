package com.gekkotron.oikos.feature.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.SecretStore
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.settings.FakePushStatusSource
import com.gekkotron.oikos.feature.settings.presentation.SettingsViewModel
import com.gekkotron.oikos.transport.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class SettingsRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()

    private object PassThrough : SecretStore {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(cipher: ByteArray) = cipher.copyOf()
    }

    @Test
    fun `the push status is re-read every time the screen resumes`(): Unit {
        val push = FakePushStatusSource()
        val vm = SettingsViewModel(
            ProfileRepository.create(tmp.root.resolve("profile.json"), PassThrough, scope),
            AppPreferences.create(tmp.root.resolve("prefs.json"), scope),
            push,
        )
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = registry
        }
        registry = LifecycleRegistry.createUnsafe(owner)
        registry.currentState = Lifecycle.State.STARTED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                OikosTheme(ThemeChoice.NIGHT_GLASS) {
                    SettingsRoute(Route.AUTO, {}, {}, {}, {}, {}, viewModel = vm)
                }
            }
        }
        compose.runOnIdle { assertEquals(0, push.refreshes) }
        compose.runOnIdle { registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(1, push.refreshes) }
        compose.runOnIdle { registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, push.refreshes) }
        assertTrue(push.refreshes >= 2)
    }
}
