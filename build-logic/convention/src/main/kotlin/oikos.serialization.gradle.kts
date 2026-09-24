// kotlinx-serialization compiler plugin + JSON runtime (exposed as api).
plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(libs.findLibrary("kotlinx-serialization-json").get())
}
