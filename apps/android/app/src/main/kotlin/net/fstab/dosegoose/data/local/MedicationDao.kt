package net.fstab.dosegoose.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.fstab.dosegoose.app.Medication

@Dao
abstract class MedicationDao {
    @Transaction
    @Query("SELECT * FROM medications ORDER BY sort_order, id")
    abstract fun observeMedications(): Flow<List<MedicationWithSchedule>>

    @Transaction
    @Query("SELECT * FROM medications WHERE enabled = 1 ORDER BY sort_order, id")
    abstract suspend fun enabledMedications(): List<MedicationWithSchedule>

    @Query("SELECT * FROM once_daily_schedules WHERE id = :scheduleId")
    abstract suspend fun schedule(scheduleId: String): OnceDailyScheduleEntity?

    @Query(
        "UPDATE once_daily_schedules SET effective_from_epoch_seconds = :effectiveFrom " +
            "WHERE medication_id = :medicationId",
    )
    abstract suspend fun updateScheduleEffectiveFrom(
        medicationId: String,
        effectiveFrom: Long,
    ): Int

    @Query("SELECT value FROM app_metadata WHERE `key` = :key")
    abstract suspend fun metadataValue(key: String): String?

    @Query("SELECT sort_order FROM medications WHERE id = :medicationId")
    abstract suspend fun sortOrderFor(medicationId: String): Int?

    @Query("SELECT MAX(sort_order) FROM medications")
    abstract suspend fun maximumSortOrder(): Int?

    @Upsert
    abstract suspend fun upsertMedicationEntity(medication: MedicationEntity)

    @Upsert
    abstract suspend fun upsertScheduleEntity(schedule: OnceDailyScheduleEntity)

    @Upsert
    abstract suspend fun upsertMetadata(metadata: AppMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMetadata(metadata: AppMetadataEntity): Long

    @Query("UPDATE medications SET enabled = :enabled WHERE id = :medicationId")
    abstract suspend fun updateEnabled(medicationId: String, enabled: Boolean): Int

    @Transaction
    open suspend fun metadataValueOrInsert(key: String, candidate: String): String {
        insertMetadata(AppMetadataEntity(key, candidate))
        return checkNotNull(metadataValue(key))
    }

    @Transaction
    open suspend fun initializeIfNeeded(seedMedications: List<Medication>) {
        if (metadataValue(SEED_METADATA_KEY) != null) return

        seedMedications.forEachIndexed { index, medication ->
            upsertMedication(medication, index)
        }
        upsertMetadata(AppMetadataEntity(SEED_METADATA_KEY, SEED_VERSION))
    }

    @Transaction
    open suspend fun upsertMedication(
        medication: Medication,
        effectiveFromEpochSeconds: Long? = null,
    ) {
        val sortOrder = sortOrderFor(medication.id) ?: (maximumSortOrder() ?: -1) + 1
        val previous = schedule(medication.schedule.id)
        val scheduleChanged = previous == null ||
            previous.hour != medication.schedule.hour ||
            previous.minute != medication.schedule.minute ||
            previous.earlyWindowMinutes != medication.schedule.earlyWindowMinutes ||
            previous.lateWindowMinutes != medication.schedule.lateWindowMinutes
        val effectiveFrom = if (scheduleChanged) {
            effectiveFromEpochSeconds ?: previous?.effectiveFromEpochSeconds ?: 0
        } else {
            previous.effectiveFromEpochSeconds
        }
        upsertMedication(medication, sortOrder, effectiveFrom)
    }

    private suspend fun upsertMedication(
        medication: Medication,
        sortOrder: Int,
        effectiveFromEpochSeconds: Long = 0,
    ) {
        upsertMedicationEntity(
            MedicationEntity(
                id = medication.id,
                name = medication.name,
                enabled = medication.enabled,
                sortOrder = sortOrder,
            ),
        )
        upsertScheduleEntity(
            OnceDailyScheduleEntity(
                id = medication.schedule.id,
                medicationId = medication.id,
                hour = medication.schedule.hour,
                minute = medication.schedule.minute,
                earlyWindowMinutes = medication.schedule.earlyWindowMinutes,
                lateWindowMinutes = medication.schedule.lateWindowMinutes,
                effectiveFromEpochSeconds = effectiveFromEpochSeconds,
            ),
        )
    }

    private companion object {
        const val SEED_METADATA_KEY = "initial_medications_seeded"
        const val SEED_VERSION = "1"
    }
}
