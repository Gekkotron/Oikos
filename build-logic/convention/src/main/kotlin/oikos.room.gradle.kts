// Room through KSP, schemas exported next to the module.
plugins {
    id("com.google.devtools.ksp")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("androidx-room-runtime").get())
    "implementation"(libs.findLibrary("androidx-room-ktx").get())
    "ksp"(libs.findLibrary("androidx-room-compiler").get())
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
