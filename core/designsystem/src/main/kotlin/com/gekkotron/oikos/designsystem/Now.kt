package com.gekkotron.oikos.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant

/** The current time, refreshed every minute so "2 min ago" does not freeze on a screen left open. */
@Composable
fun rememberNow(): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(60_000)
        value = Instant.now()
    }
}
