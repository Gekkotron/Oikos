plugins {
    id("oikos.root")
}

dependencies {
    kover(project(":core:model"))
    kover(project(":core:crypto"))
    kover(project(":core:transport"))
    kover(project(":core:state"))
    kover(project(":core:source"))
    kover(project(":core:data"))
    kover(project(":core:demo"))
    kover(project(":core:designsystem"))
    kover(project(":feature:home"))
    kover(project(":app"))
}
