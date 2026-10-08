package com.gekkotron.oikos.feature.settings.presentation

import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.defaultHistoryUrl

/** What Settings shows of the profile (settings spec §5.1). Built field by field so no secret can ride along. */
sealed interface ConnectionSummary {
    data object Demo : ConnectionSummary

    data class Direct(
        val broker: String,
        val tls: Boolean,
        val ws: Boolean,
        val username: String?,
        val prefix: String,
        val gatewayUrl: String?,
        /** The override; null = automatic. */
        val historyUrl: String?,
        /** Where history is looked for without an override. */
        val historyDefault: String,
    ) : ConnectionSummary

    companion object {
        fun from(state: ProfileState): ConnectionSummary? = when (state) {
            is ProfileState.Ready -> when (val p = state.profile) {
                ConnectionProfile.Demo -> Demo
                is ConnectionProfile.Direct -> Direct(
                    broker = "${p.local.host}:${p.local.port}",
                    tls = p.local.tls,
                    ws = p.local.ws,
                    username = p.local.username,
                    prefix = p.prefix,
                    gatewayUrl = p.gateway?.url,
                    historyUrl = p.historyUrl,
                    historyDefault = defaultHistoryUrl(p.local.host),
                )
            }
            ProfileState.Loading, ProfileState.None, ProfileState.Locked -> null
        }
    }
}
