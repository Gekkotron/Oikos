package com.gekkotron.oikos.connection

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.effectiveHistoryUrl
import com.gekkotron.oikos.history.DemoHistoryClient
import com.gekkotron.oikos.history.EnergyScale
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.history.HistoryRange
import com.gekkotron.oikos.history.HistoryRef
import com.gekkotron.oikos.history.HttpHistoryClient
import com.gekkotron.oikos.transport.HttpGetResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The demo's fake history, or OikosHistory at the profile's URL (the broker host's by default) (history spec §4.2, §4.5). */
class ProfileHistoryClient(
    private val states: Flow<ProfileState>,
    get: suspend (String) -> HttpGetResult,
    scope: CoroutineScope,
) : HistoryClient {
    private val demo = DemoHistoryClient()
    private val http = HttpHistoryClient(baseUrl = { (profile() as? ConnectionProfile.Direct)?.effectiveHistoryUrl() }, get = get)

    override val available: StateFlow<Boolean> = states
        .map { (it as? ProfileState.Ready)?.profile.let { p -> p == ConnectionProfile.Demo || p is ConnectionProfile.Direct } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private suspend fun profile(): ConnectionProfile? = (states.first { it !is ProfileState.Loading } as? ProfileState.Ready)?.profile
    private suspend fun current(): HistoryClient = if (profile() == ConnectionProfile.Demo) demo else http

    override suspend fun series(ref: HistoryRef, range: HistoryRange) = current().series(ref, range)
    override suspend fun daily(ref: HistoryRef, days: Int, scale: EnergyScale) = current().daily(ref, days, scale)
    override suspend fun today(ref: HistoryRef, scale: EnergyScale) = current().today(ref, scale)
    override suspend fun lastChanges(refs: List<HistoryRef>) = current().lastChanges(refs)
}
