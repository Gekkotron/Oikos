plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.alerts"
}

dependencies {
    implementation(project(":core:alerts"))
    implementation(project(":core:push"))
    implementation(project(":core:source"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
