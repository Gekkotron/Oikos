package com.gekkotron.oikos.shared

// Phase 0 scaffold anchor. Proves the Android app links to the KMP shared module at
// build time (via :app dependency) and at runtime (referenced from OikosApplication).
// Remove once another member in commonMain carries that role.
object OikosShared {
    const val VERSION: String = "0.1-kmp-scaffold"
}
