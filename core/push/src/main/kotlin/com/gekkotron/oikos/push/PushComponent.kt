package com.gekkotron.oikos.push

/**
 * What the Android entry points (service, receivers) need. `:core:push` has no Hilt: `:app` builds the objects and
 * `OikosApplication` hands them out through [PushComponentProvider].
 */
interface PushComponent {
    val ingestor: AlertIngestor
    val registrar: PushRegistrar
    val replies: ReplyQueue
}

interface PushComponentProvider {
    fun pushComponent(): PushComponent
}
