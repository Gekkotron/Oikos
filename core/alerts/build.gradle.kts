plugins {
    id("oikos.android.library")
    id("oikos.serialization")
    id("oikos.room")
}

android {
    namespace = "com.gekkotron.oikos.alerts"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // LocalProbe, GatewayDownloader and (through the gateway SDK) OkHttp.
    api(project(":core:transport"))
    implementation(project(":core:data")) // isLikelyLocalHost
    testImplementation(libs.mockwebserver)
}
