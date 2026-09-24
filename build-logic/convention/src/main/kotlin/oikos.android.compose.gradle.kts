// Jetpack Compose for an Android module. Apply after oikos.android.library or oikos.android.application.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

plugins.withId("com.android.library") {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> { buildFeatures.compose = true }
}
plugins.withId("com.android.application") {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension> { buildFeatures.compose = true }
}

dependencies {
    val bom = platform(libs.findLibrary("compose-bom").get())
    "implementation"(bom)
    "testImplementation"(bom)
    "implementation"(libs.findLibrary("compose-ui").get())
    "implementation"(libs.findLibrary("compose-material3").get())
    "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
    "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
    "testImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
}
