plugins {
    id("oikos.android.library")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.alerts"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
