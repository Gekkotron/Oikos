// Applied to the root project only: hosts the merged Kover report.
plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*Hilt_*", "*_Factory", "*_Factory\$*", "*_MembersInjector", "*_HiltModules*",
                    "*_GeneratedInjector", "*_ComponentTreeDeps", "hilt_aggregated_deps.*", "dagger.hilt.internal.*",
                    "*.BuildConfig", "*.R", "*.R\$*", "*ComposableSingletons*", "*KeystoreSecretStore*",
                )
            }
        }
    }
}
