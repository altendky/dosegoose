package net.fstab.dosegoose.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "migration-${UUID.randomUUID()}.db"
        helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            DoseGooseDatabase::class.java,
        )
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromOneToTwoPreservesConfigurationAndAddsOccurrenceStorage() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                "INSERT INTO medications (id, name, enabled, sort_order) " +
                    "VALUES ('medication', 'Vitamin D', 1, 0)",
            )
            execSQL(
                "INSERT INTO once_daily_schedules " +
                    "(id, medication_id, hour, minute, late_window_minutes) " +
                    "VALUES ('schedule', 'medication', 8, 0, 120)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            DoseGooseDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT name FROM medications WHERE id = 'medication'").use {
                it.moveToFirst()
                assertEquals("Vitamin D", it.getString(0))
            }
            database.query("SELECT COUNT(*) FROM dose_occurrences").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM pending_effects").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun migrationFromTwoToThreeMakesExistingSchedulesImmediatelyEffective() {
        helper.createDatabase(databaseName, 2).apply {
            execSQL(
                "INSERT INTO medications (id, name, enabled, sort_order) " +
                    "VALUES ('medication', 'Vitamin D', 1, 0)",
            )
            execSQL(
                "INSERT INTO once_daily_schedules " +
                    "(id, medication_id, hour, minute, late_window_minutes) " +
                    "VALUES ('schedule', 'medication', 8, 0, 120)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            DoseGooseDatabase.MIGRATION_2_3,
        ).use { database ->
            database.query(
                "SELECT effective_from_epoch_seconds FROM once_daily_schedules " +
                    "WHERE id = 'schedule'",
            ).use {
                it.moveToFirst()
                assertEquals(0L, it.getLong(0))
            }
        }
    }

    @Test
    fun migrationFromThreeToFourPreservesRowsAndDefaultsEarlyWindowsToZero() {
        helper.createDatabase(databaseName, 3).apply {
            execSQL(
                "INSERT INTO medications (id, name, enabled, sort_order) " +
                    "VALUES ('medication', 'Vitamin D', 1, 0)",
            )
            execSQL(
                "INSERT INTO once_daily_schedules " +
                    "(id, medication_id, hour, minute, late_window_minutes, " +
                    "effective_from_epoch_seconds) " +
                    "VALUES ('schedule', 'medication', 8, 0, 120, 0)",
            )
            execSQL(
                """
                INSERT INTO dose_occurrences (
                    id, medication_id, schedule_id, medication_name, local_date,
                    scheduled_at_epoch_seconds, zone_id, utc_offset_seconds,
                    late_window_minutes, snapshot_version, core_snapshot_json,
                    guidance, recording_availability, due_in_seconds,
                    late_by_seconds, remaining_seconds, intake_at_epoch_seconds,
                    updated_at_epoch_seconds
                ) VALUES (
                    'occurrence', 'medication', 'schedule', 'Vitamin D', '2026-09-21',
                    1000, 'UTC', 0, 120, 1, '{}', 'Upcoming', 'NotYetDue',
                    60, NULL, NULL, NULL, 940
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            DoseGooseDatabase.MIGRATION_3_4,
        ).use { database ->
            database.query(
                "SELECT early_window_minutes FROM once_daily_schedules " +
                    "WHERE id = 'schedule'",
            ).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            database.query(
                "SELECT early_window_minutes FROM dose_occurrences " +
                    "WHERE id = 'occurrence'",
            ).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }
}
