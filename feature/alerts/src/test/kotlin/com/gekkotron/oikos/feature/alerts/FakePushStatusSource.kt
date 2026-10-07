package com.gekkotron.oikos.feature.alerts

import com.gekkotron.oikos.push.PushStatus
import com.gekkotron.oikos.push.PushStatusSource
import kotlinx.coroutines.flow.MutableStateFlow

class FakePushStatusSource(initial: PushStatus = PushStatus.Waiting) : PushStatusSource {
    override val status = MutableStateFlow(initial)
    var refreshes = 0
    var registerAgains = 0

    override fun refresh() {
        refreshes++
    }

    override fun registerAgain() {
        registerAgains++
    }
}
