plugins {
    id("oikos.root")
}

dependencies {
    kover(project(":core:model"))
    kover(project(":core:crypto"))
    kover(project(":core:transport"))
}
