plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
}

android {
    namespace = "com.gekkotron.oikos.feature.home"
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:history"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose) // BackHandler closes the search
}
