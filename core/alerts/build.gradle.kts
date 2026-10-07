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
}
