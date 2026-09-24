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
    packaging {
        resources {
            // HiveMQ pulls in Netty, whose jars carry duplicate metadata files.
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    implementation(project(":core:source"))
    implementation(project(":core:demo"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:transport"))
    implementation(project(":core:data"))
    implementation(project(":feature:home"))
    implementation(project(":feature:onboarding"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(libs.hilt.android.testing)
    testImplementation(testFixtures(project(":core:transport")))
    kspTest(libs.hilt.compiler)
}
