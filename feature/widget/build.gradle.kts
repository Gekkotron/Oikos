plugins {
    id("oikos.android.library")
    id("oikos.android.compose")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.feature.widget"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:history"))
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.androidx.work.testing)
}
