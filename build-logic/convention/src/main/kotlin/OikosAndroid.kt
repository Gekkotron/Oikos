import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/** JUnit 4 + Robolectric host tests, shared by every Android module. */
internal fun Project.oikosAndroidTesting() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val kotlinVersion = libs.findVersion("kotlin").get().requiredVersion
    dependencies.apply {
        add("testImplementation", libs.findLibrary("junit4").get())
        add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", libs.findLibrary("robolectric").get())
        add("testImplementation", libs.findLibrary("androidx-test-ext-junit").get())
    }
    // ui-test-manifest is debug-only, so release host tests cannot host Compose tests.
    tasks.withType<Test>().matching { it.name.contains("Release") }.configureEach { enabled = false }
    // CI only shows the console: print a failing test's message, cause chain and stack, not just its type.
    tasks.withType<Test>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showCauses = true
            showStackTraces = true
        }
    }
}
