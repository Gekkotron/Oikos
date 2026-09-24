plugins {
    id("oikos.jvm.library")
    id("oikos.serialization")
}

dependencies {
    api(project(":core:transport"))
    testImplementation(project(":core:source"))
}
