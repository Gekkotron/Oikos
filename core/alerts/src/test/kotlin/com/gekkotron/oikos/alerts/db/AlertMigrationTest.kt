package com.gekkotron.oikos.alerts.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AlertMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AlertDatabase::class.java)

    @Test
    fun `migration 1 to 2 keeps rows and adds a null loc`() {
        helper.createDatabase("migrate.db", 1).use { db ->
            db.execSQL(
                "INSERT INTO alerts (id, channel, severity, title, body, imageUrl, videoUrl, entityId, actionsJson, source, silent, resolved, sentAt, receivedAt) " +
                    "VALUES ('a1', 'camera', 'info', 'Driveway', 'Car in Driveway', NULL, NULL, NULL, '[]', 'frigate', 0, 0, 1000, 1000)",
            )
        }
        helper.runMigrationsAndValidate("migrate.db", 2, true).use { db ->
            db.query("SELECT body, locJson FROM alerts WHERE id = 'a1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Car in Driveway", c.getString(0))
                assertNull(c.getString(1))
            }
        }
    }
}
