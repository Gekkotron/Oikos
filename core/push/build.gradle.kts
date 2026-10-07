plugins {
    id("oikos.android.library")
    id("oikos.serialization")
}

android {
    namespace = "com.gekkotron.oikos.push"
}

dependencies {
    api(project(":core:alerts"))
    api(project(":core:transport"))
    implementation(project(":core:data"))
    testImplementation(testFixtures(project(":core:transport")))
}
