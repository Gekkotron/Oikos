plugins {
    id("oikos.android.application")
    id("oikos.android.compose")
    id("oikos.hilt")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos"
    defaultConfig {
        applicationId = "com.gekkotron.oikos"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:demo"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:home"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
