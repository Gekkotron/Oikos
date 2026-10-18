plugins {
    id("oikos.root")
}

tasks.register("increaseBuildNumber") {
    val versionCodeProvider = providers.gradleProperty("versionCode")
    val versionFile = file("app/version.properties")
    doFirst {
        val versionCode = versionCodeProvider.get()
        println("Set app version : ($versionCode)")
        versionFile.writeText("VERSION_CODE=$versionCode\n")
    }
}

dependencies {
    kover(project(":shared"))
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
    kover(project(":feature:alerts"))
    kover(project(":app"))
}
