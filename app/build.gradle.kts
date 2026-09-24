plugins {
    id("oikos.android.application")
    id("oikos.android.compose")
    id("oikos.hilt")
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
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
