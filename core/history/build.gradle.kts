plugins {
    id("oikos.kmp.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:state"))
            api(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            api(project(":core:transport"))
        }
    }
}
