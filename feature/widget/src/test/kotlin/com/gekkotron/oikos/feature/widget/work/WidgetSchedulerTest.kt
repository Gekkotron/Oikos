package com.gekkotron.oikos.feature.widget.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSchedulerTest {
    private lateinit var wm: WorkManager
    private lateinit var scheduler: WorkManagerWidgetScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        wm = WorkManager.getInstance(context)
        scheduler = WorkManagerWidgetScheduler { wm }
    }

    private fun periodic() = wm.getWorkInfosForUniqueWork(WorkManagerWidgetScheduler.PERIODIC).get()

    @Test
    fun ensure_periodic_twice_keeps_one_work_with_the_same_id() {
        scheduler.ensurePeriodic()
        val first = periodic().single().id
        scheduler.ensurePeriodic()
        assertEquals(first, periodic().single().id)
    }

    @Test
    fun cancel_periodic_cancels() {
        scheduler.ensurePeriodic()
        scheduler.cancelPeriodic()
        assertEquals(WorkInfo.State.CANCELLED, periodic().single().state)
    }

    @Test
    fun refresh_now_replaces_a_queued_refresh() {
        scheduler.refreshNow()
        scheduler.refreshNow()
        val active = wm.getWorkInfosForUniqueWork(WorkManagerWidgetScheduler.NOW).get().filter { !it.state.isFinished }
        assertEquals(1, active.size)
    }

    @Test
    fun send_enqueues_one_tagged_command() {
        scheduler.send("a", WidgetCommand.Disarm, "1234")
        assertEquals(1, wm.getWorkInfosByTag(WorkManagerWidgetScheduler.TAG_COMMAND).get().size)
    }
}
