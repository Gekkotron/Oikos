package com.gekkotron.oikos

import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Fills Room with the six demo alerts each time the demo profile becomes current (push alerts spec §7.6). */
class DemoAlertSeeder(
    private val states: Flow<ProfileState>,
    private val repository: AlertRepository,
    private val clock: () -> Instant = Instant::now,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Safe to call from every `AppViewModel`: only the first call collects. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            states.map { (it as? ProfileState.Ready)?.profile == ConnectionProfile.Demo }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    repository.reset()
                    DemoAlerts.all(clock()).forEach { alert -> repository.upsert(alert) }
                }
        }
    }
}
