plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
}

android {
    namespace = "com.gekkotron.oikos.designsystem"
}

dependencies {
    // LinkState for the shared connection pill.
    implementation(project(":core:transport"))
}
