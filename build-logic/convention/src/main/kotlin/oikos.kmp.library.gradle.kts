// Kotlin Multiplatform library with an Android target, built on AGP 9's dedicated
// `com.android.kotlin.multiplatform.library` plugin (the old `com.android.library` +
// `kotlin.multiplatform` pairing was removed in AGP 9.0). commonMain + androidMain;
// iosX64 / iosArm64 / iosSimulatorArm64 targets land in Phase 6.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(17)
    android {
        compileSdk = 36
        minSdk = 28
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
