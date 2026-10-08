package com.gekkotron.oikos

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.db.AlertDatabase
import com.gekkotron.oikos.alerts.demo.DemoAlerts
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.LocalBroker
import com.gekkotron.oikos.data.ProfileState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DemoAlertSeederTest {
    private lateinit var db: AlertDatabase
    private lateinit var repo: AlertRepository
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val demo = ProfileState.Ready(ConnectionProfile.Demo)
    private val direct = ProfileState.Ready(ConnectionProfile.Direct(LocalBroker("192.168.1.91")))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AlertDatabase::class.java).allowMainThreadQueries().build()
        repo = AlertRepository(db.alerts()) { now }
    }

    /**
     * The seeder runs where production runs it (the app scope: SupervisorJob + Dispatchers.Default), not on runTest's
     * virtual-time scheduler: there its first Room access hung on CI until the poll gave up, with Room still empty.
     */
    private val seederErrors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val seederScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> seederErrors += e })

    @After
    fun tearDown() {
        seederScope.cancel()
        db.close()
    }

    private suspend fun ids() = repo.all(null).first().map { it.id }.sorted()

    private val demoIds = DemoAlerts.all(now).map { it.id }.sorted()

    /** Room writes run on its own executor, in real time: poll on a real dispatcher, not under virtual time. */
    private suspend fun awaitIds(expected: List<String>, step: String) = withContext(Dispatchers.Default) {
        var seen: List<String> = emptyList()
        val done = withTimeoutOrNull(5_000) {
            while (true) {
                seen = ids()
                if (seen == expected) break
                delay(20)
            }
        }
        // A bare timeout says nothing in CI's console: name the step and what Room held.
        if (done == null) {
            val errors = seederErrors.joinToString { it.stackTraceToString().lines().take(6).joinToString(" | ") }
            throw AssertionError("$step: after 5 s Room held $seen, expected $expected; seeder errors: [$errors]; threads:\n${busyThreads()}")
        }
    }

    /** Where the app's threads are parked: a seeder that neither writes nor throws is stuck somewhere in here. */
    private fun busyThreads(): String = Thread.getAllStackTraces()
        .filter { (t, frames) -> frames.any { f -> f.className.startsWith("androidx.room") || f.className.startsWith("com.gekkotron") } || t.name.contains("arch_disk_io") }
        .entries.joinToString("\n") { (t, frames) -> "[${t.name} ${t.state}] " + frames.take(14).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(300) }

    @Test
    fun `entering the demo seeds its six alerts`(): Unit = runTest {
        val states = MutableStateFlow<ProfileState>(ProfileState.None)
        DemoAlertSeeder(states, repo, { now }, seederScope).start()
        assertTrue(ids().isEmpty())
        states.value = demo
        awaitIds(demoIds, "first seed")
    }

    @Test
    fun `a real house never gets demo alerts`(): Unit = runTest {
        val states = MutableStateFlow<ProfileState>(direct)
        DemoAlertSeeder(states, repo, { now }, seederScope).start()
        states.value = ProfileState.Locked
        settle()
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `starting twice seeds once and leftovers are replaced, not added to`(): Unit = runTest {
        val states = MutableStateFlow<ProfileState>(demo)
        val seeder = DemoAlertSeeder(states, repo, { now }, seederScope)
        seeder.start()
        seeder.start()
        awaitIds(demoIds, "seed after two starts")
        repo.upsert(DemoAlerts.all(now)[0].copy(id = "stray"))
        states.value = ProfileState.None
        settle() // StateFlow conflates: let the seeder see the house go before the demo comes back
        states.value = demo
        awaitIds(demoIds, "reseed after leaving and re-entering the demo")
    }
}
