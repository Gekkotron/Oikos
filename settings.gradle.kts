pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "Oikos"

include(":core:model")
include(":core:crypto")
include(":core:transport")
include(":core:state")
include(":core:source")
include(":app")
include(":core:demo")
include(":core:designsystem")
include(":feature:home")
