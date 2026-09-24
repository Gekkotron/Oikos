plugins {
    id("oikos.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:state"))
    api(project(":core:transport"))
    testImplementation(testFixtures(project(":core:transport")))
}
