plugins {
    id("oikos.kmp.jvm.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
