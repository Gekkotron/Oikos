plugins {
    id("oikos.jvm.library")
    id("oikos.serialization")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:state"))
    api(project(":core:transport"))
    api(project(":core:voice"))
    testImplementation(testFixtures(project(":core:transport")))
}
