package com.gekkotron.oikos.feature.widget.work

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds an alarm code in memory only, between the keypad and the command worker (widgets spec §4). WorkManager keeps a
 * finished job's input on disk for about a day, so the input carries a one-time token instead of the code. A code is
 * handed out once; after a process death it is gone and the command is not sent.
 */
object CodeVault {
    private val codes = ConcurrentHashMap<String, String>()

    fun put(code: String): String = UUID.randomUUID().toString().also { codes[it] = code }

    fun take(token: String): String? = codes.remove(token)
}
