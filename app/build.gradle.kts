import java.io.FileInputStream
import java.util.Properties

plugins {
    id("oikos.android.application")
    id("oikos.android.compose")
    id("oikos.hilt")
    id("oikos.serialization")
    id("com.google.gms.google-services")
}

val versionPropsFile = file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.canRead()) {
        FileInputStream(versionPropsFile).use { load(it) }
    } else {
        throw GradleException("Could not read app/version.properties!")
    }
}
val appVersionCode = versionProps.getProperty("VERSION_CODE").toInt()

val signingPropsFile = file("signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.canRead()) {
        FileInputStream(signingPropsFile).use { load(it) }
    }
}
val hasSigningConfig = signingPropsFile.canRead()

android {
    namespace = "com.gekkotron.oikos"
    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                keyAlias = signingProps.getProperty("keyAlias")
                storePassword = signingProps.getProperty("storePassword")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    defaultConfig {
        applicationId = "com.gekkotron.oikos"
        versionCode = appVersionCode
        versionName = "0.1.0.$appVersionCode"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
