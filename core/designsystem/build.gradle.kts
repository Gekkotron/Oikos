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
    // The alarm UI takes AlarmState, Capabilities and Command (security spec §4.1).
    api(project(":core:model"))
    implementation(libs.androidx.activity.compose) // the permission launcher
    implementation(libs.androidx.core.ktx)
}
