plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.hilt")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.feature.settings"
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:push"))
    implementation(project(":core:voice"))
    implementation(project(":feature:voice"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.reorderable)
}
