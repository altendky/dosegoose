package net.fstab.dosegoose.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TodayDao {
    @Query(
        """
        SELECT dose_occurrences.*
        FROM dose_occurrences
        INNER JOIN medications ON medications.id = dose_occurrences.medication_id
        INNER JOIN once_daily_schedules ON once_daily_schedules.id = dose_occurrences.schedule_id
        WHERE medications.enabled = 1
          AND dose_occurrences.scheduled_at_epoch_seconds >=
              once_daily_schedules.effective_from_epoch_seconds
        ORDER BY scheduled_at_epoch_seconds, dose_occurrences.id
        """,
    )
    abstract fun observeEnabledOccurrences(): Flow<List<DoseOccurrenceEntity>>

    @Query(
        """
        SELECT dose_occurrences.*
        FROM dose_occurrences
        INNER JOIN medications ON medications.id = dose_occurrences.medication_id
        INNER JOIN once_daily_schedules ON once_daily_schedules.id = dose_occurrences.schedule_id
        WHERE medications.enabled = 1
          AND dose_occurrences.scheduled_at_epoch_seconds >=
              once_daily_schedules.effective_from_epoch_seconds
        ORDER BY scheduled_at_epoch_seconds, dose_occurrences.id
        """,
    )
    abstract suspend fun observeEnabledOccurrencesSnapshot(): List<DoseOccurrenceEntity>

    @Query("SELECT * FROM dose_occurrences WHERE id = :occurrenceId")
    abstract suspend fun occurrence(occurrenceId: String): DoseOccurrenceEntity?

    @Query(
        "SELECT * FROM dose_occurrences WHERE schedule_id = :scheduleId AND local_date = :localDate",
    )
    abstract suspend fun occurrence(scheduleId: String, localDate: String): DoseOccurrenceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOccurrence(occurrence: DoseOccurrenceEntity): Long

    @Update
    abstract suspend fun updateOccurrence(occurrence: DoseOccurrenceEntity): Int

    @Insert
    abstract suspend fun insertEffects(effects: List<PendingEffectEntity>)

    @Query(
        "SELECT COALESCE(MAX(sequence_number), -1) FROM pending_effects " +
            "WHERE occurrence_id = :occurrenceId",
    )
    abstract suspend fun maximumEffectSequence(occurrenceId: String): Int

    @Query("SELECT * FROM pending_effects ORDER BY id")
    abstract suspend fun pendingEffects(): List<PendingEffectEntity>

    @Query("SELECT * FROM pending_effects WHERE handled = 0 ORDER BY id")
    abstract suspend fun unhandledEffects(): List<PendingEffectEntity>

    @Query(
        "SELECT MAX(requested_at_epoch_seconds) FROM pending_effects " +
            "WHERE occurrence_id = :occurrenceId AND kind = 'PresentIntrusively'",
    )
    abstract suspend fun lastIntrusivePresentationAt(occurrenceId: String): Long?

    @Query("SELECT MAX(id) FROM pending_effects WHERE handled = 0")
    abstract suspend fun maximumUnhandledEffectId(): Long?

    @Query("UPDATE pending_effects SET handled = 1 WHERE id = :effectId AND handled = 0")
    abstract suspend fun markEffectHandled(effectId: Long): Int

    @Query("UPDATE pending_effects SET handled = 1 WHERE handled = 0 AND id <= :effectId")
    abstract suspend fun markEffectsHandledThrough(effectId: Long): Int

    @Query(
        """
        SELECT dose_occurrences.*, medications.enabled AS medication_enabled,
               once_daily_schedules.effective_from_epoch_seconds AS schedule_effective_from
        FROM dose_occurrences
        INNER JOIN medications ON medications.id = dose_occurrences.medication_id
        INNER JOIN once_daily_schedules ON once_daily_schedules.id = dose_occurrences.schedule_id
        WHERE dose_occurrences.scheduled_at_epoch_seconds >= :earliestScheduledAtEpochSeconds
           OR EXISTS (
               SELECT 1
               FROM pending_effects
               WHERE pending_effects.occurrence_id = dose_occurrences.id
                 AND pending_effects.handled = 0
           )
        ORDER BY dose_occurrences.scheduled_at_epoch_seconds, dose_occurrences.id
        """,
    )
    abstract suspend fun alertOccurrences(
        earliestScheduledAtEpochSeconds: Long,
    ): List<AlertOccurrence>

    @Query(
        """
        SELECT pending_effects.*
        FROM pending_effects
        INNER JOIN dose_occurrences ON dose_occurrences.id = pending_effects.occurrence_id
        INNER JOIN medications ON medications.id = dose_occurrences.medication_id
        INNER JOIN once_daily_schedules ON once_daily_schedules.id = dose_occurrences.schedule_id
        WHERE pending_effects.kind = 'ScheduleEvaluation'
          AND pending_effects.at_epoch_seconds > :nowEpochSeconds
          AND medications.enabled = 1
          AND dose_occurrences.scheduled_at_epoch_seconds >=
              once_daily_schedules.effective_from_epoch_seconds
          AND dose_occurrences.guidance NOT IN ('Recorded', 'LateWindowElapsed')
        ORDER BY pending_effects.at_epoch_seconds, pending_effects.id
        """,
    )
    abstract suspend fun futureEvaluationEffects(
        nowEpochSeconds: Long,
    ): List<PendingEffectEntity>

    @Transaction
    open suspend fun insertOccurrenceWithEffects(
        occurrence: DoseOccurrenceEntity,
        effects: List<PendingEffectEntity>,
    ): Boolean {
        if (insertOccurrence(occurrence) == -1L) return false
        insertSequencedEffects(occurrence.id, effects)
        return true
    }

    @Transaction
    open suspend fun updateOccurrenceWithEffects(
        occurrence: DoseOccurrenceEntity,
        effects: List<PendingEffectEntity>,
    ) {
        check(updateOccurrence(occurrence) == 1) {
            "Occurrence ${occurrence.id} does not exist"
        }
        insertSequencedEffects(occurrence.id, effects)
    }

    private suspend fun insertSequencedEffects(
        occurrenceId: String,
        effects: List<PendingEffectEntity>,
    ) {
        if (effects.isEmpty()) return
        val firstSequence = maximumEffectSequence(occurrenceId) + 1
        insertEffects(
            effects.mapIndexed { index, effect ->
                effect.copy(sequenceNumber = firstSequence + index)
            },
        )
    }
}
