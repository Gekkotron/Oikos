package com.gekkotron.oikos.feature.onboarding

import com.gekkotron.oikos.data.ConnectionProfile
import kotlinx.coroutines.flow.Flow

sealed interface TestFailure {
    data object Unreachable : TestFailure
    data object Rejected : TestFailure
    data object Tls : TestFailure

    /** The gateway did not accept the key from the setup code. */
    data object KeyRejected : TestFailure

    /** The gateway refused the request as expired: the phone's clock is off. */
    data object ClockSkew : TestFailure
    data class Other(val message: String) : TestFailure
}

sealed interface TestState {
    data object Connecting : TestState
    data class Failed(val failure: TestFailure) : TestState

    /** Connected; [devices] are the names discovered so far, sorted. [viaGateway]: the broker was out of reach. */
    data class Connected(val devices: List<String>, val viaGateway: Boolean = false) : TestState
}

/** Tries a draft profile without saving it (spec §5.1). Cancelling the flow closes the test connection. */
fun interface ConnectionTester {
    fun test(draft: ConnectionProfile.Direct): Flow<TestState>
}
