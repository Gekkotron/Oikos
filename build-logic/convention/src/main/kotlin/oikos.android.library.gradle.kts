// Android library with Kover and JUnit 4 + Robolectric host tests. AGP 9 compiles Kotlin itself.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

oikosAndroidTesting()
