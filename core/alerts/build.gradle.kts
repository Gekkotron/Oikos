plugins {
    id("oikos.android.library")
    id("oikos.serialization")
    id("oikos.room")
}

android {
    namespace = "com.gekkotron.oikos.alerts"
}

// Exported schemas as unit-test assets, so MigrationTestHelper can build the v1 database (localized alerts spec §5.1).
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("$projectDir/schemas") }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.coil.core)
    // LocalProbe, GatewayDownloader and (through the gateway SDK) OkHttp.
    api(project(":core:transport"))
    implementation(project(":core:data")) // isLikelyLocalHost
    // AlertDatabase extends RoomDatabase: every module that builds it must see the supertype.
    api(libs.androidx.room.runtime)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.room.testing)
}
