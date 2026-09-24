plugins {
    id("oikos.android.library")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.data"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
