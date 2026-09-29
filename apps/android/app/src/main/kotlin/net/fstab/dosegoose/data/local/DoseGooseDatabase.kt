package net.fstab.dosegoose.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MedicationEntity::class,
        OnceDailyScheduleEntity::class,
        AppMetadataEntity::class,
        DoseOccurrenceEntity::class,
        PendingEffectEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class DoseGooseDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao

    abstract fun todayDao(): TodayDao

    companion object {
        fun create(context: Context): DoseGooseDatabase = Room.databaseBuilder(
            context.applicationContext,
            DoseGooseDatabase::class.java,
            "dosegoose.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dose_occurrences` (
                        `id` TEXT NOT NULL,
                        `medication_id` TEXT NOT NULL,
                        `schedule_id` TEXT NOT NULL,
                        `medication_name` TEXT NOT NULL,
                        `local_date` TEXT NOT NULL,
                        `scheduled_at_epoch_seconds` INTEGER NOT NULL,
                        `zone_id` TEXT NOT NULL,
                        `utc_offset_seconds` INTEGER NOT NULL,
                        `late_window_minutes` INTEGER NOT NULL,
                        `snapshot_version` INTEGER NOT NULL,
                        `core_snapshot_json` TEXT NOT NULL,
                        `guidance` TEXT NOT NULL,
                        `recording_availability` TEXT NOT NULL,
                        `due_in_seconds` INTEGER,
                        `late_by_seconds` INTEGER,
                        `remaining_seconds` INTEGER,
                        `intake_at_epoch_seconds` INTEGER,
                        `updated_at_epoch_seconds` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_dose_occurrences_schedule_id_local_date` ON " +
                        "`dose_occurrences` (`schedule_id`, `local_date`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dose_occurrences_medication_id` ON " +
                        "`dose_occurrences` (`medication_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_effects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurrence_id` TEXT NOT NULL,
                        `sequence_number` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `at_epoch_seconds` INTEGER,
                        `requested_at_epoch_seconds` INTEGER NOT NULL,
                        `handled` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_effects_occurrence_id_sequence_number` " +
                        "ON `pending_effects` (`occurrence_id`, `sequence_number`)",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `once_daily_schedules` ADD COLUMN " +
                        "`effective_from_epoch_seconds` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `once_daily_schedules` ADD COLUMN " +
                        "`early_window_minutes` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `dose_occurrences` ADD COLUMN " +
                        "`early_window_minutes` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
