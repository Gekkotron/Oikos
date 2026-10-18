package com.gekkotron.oikos.voice

import com.gekkotron.oikos.model.Kind

object Confirmation {
    private val ALARM_ACTIONS = setOf(VoiceAction.ARM_AWAY, VoiceAction.ARM_HOME, VoiceAction.DISARM)

    fun needsConfirmation(intent: VoiceIntent, catalog: VoiceCatalog): Boolean {
        if (intent.action in ALARM_ACTIONS) return true
        return intent.entityIds.any { id ->
            val e = catalog.entity(id) ?: return@any false
            e.kind == Kind.ALARM_PANEL ||
                e.deviceClass.equals("siren", ignoreCase = true) ||
                e.displayName.contains("siren", ignoreCase = true) ||
                e.displayName.contains("sirène", ignoreCase = true)
        }
    }
}
