plugins {
    id("oikos.root")
}

tasks.register("increaseBuildNumber") {
    val versionCode: String by project
    doFirst {
        println("Set app version : ($versionCode)")
        file("app/version.properties").writeText("VERSION_CODE=$versionCode\n")
    }
}

dependencies {
    kover(project(":core:model"))
    kover(project(":core:crypto"))
    kover(project(":core:transport"))
    kover(project(":core:state"))
    kover(project(":core:source"))
    kover(project(":core:data"))
    kover(project(":core:alerts"))
    kover(project(":core:push"))
    kover(project(":core:demo"))
    kover(project(":core:designsystem"))
    kover(project(":feature:home"))
    kover(project(":feature:onboarding"))
    kover(project(":app"))
}
