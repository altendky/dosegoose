package net.fstab.dosegoose.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import net.fstab.dosegoose.app.Medication
import net.fstab.dosegoose.app.OnceDailySchedule

@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

@Entity(
    tableName = "once_daily_schedules",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["medication_id"], unique = true)],
)
data class OnceDailyScheduleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "medication_id") val medicationId: String,
    val hour: Int,
    val minute: Int,
    @ColumnInfo(name = "late_window_minutes") val lateWindowMinutes: Int,
    @ColumnInfo(name = "effective_from_epoch_seconds") val effectiveFromEpochSeconds: Long = 0,
    @ColumnInfo(name = "early_window_minutes") val earlyWindowMinutes: Int = 0,
)

data class AlertOccurrence(
    @Embedded val occurrence: DoseOccurrenceEntity,
    @ColumnInfo(name = "medication_enabled") val medicationEnabled: Boolean,
    @ColumnInfo(name = "schedule_effective_from") val scheduleEffectiveFrom: Long,
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(
    tableName = "dose_occurrences",
    indices = [
        Index(value = ["schedule_id", "local_date"], unique = true),
        Index(value = ["medication_id"]),
    ],
)
data class DoseOccurrenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "medication_id") val medicationId: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    @ColumnInfo(name = "medication_name") val medicationName: String,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "scheduled_at_epoch_seconds") val scheduledAtEpochSeconds: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "utc_offset_seconds") val utcOffsetSeconds: Int,
    @ColumnInfo(name = "late_window_minutes") val lateWindowMinutes: Int,
    @ColumnInfo(name = "snapshot_version") val snapshotVersion: Int,
    @ColumnInfo(name = "core_snapshot_json") val coreSnapshotJson: String,
    val guidance: String,
    @ColumnInfo(name = "recording_availability") val recordingAvailability: String,
    @ColumnInfo(name = "due_in_seconds") val dueInSeconds: Long?,
    @ColumnInfo(name = "late_by_seconds") val lateBySeconds: Long?,
    @ColumnInfo(name = "remaining_seconds") val remainingSeconds: Long?,
    @ColumnInfo(name = "intake_at_epoch_seconds") val intakeAtEpochSeconds: Long?,
    @ColumnInfo(name = "updated_at_epoch_seconds") val updatedAtEpochSeconds: Long,
    @ColumnInfo(name = "early_window_minutes") val earlyWindowMinutes: Int = 0,
)

@Entity(
    tableName = "pending_effects",
    indices = [Index(value = ["occurrence_id", "sequence_number"])],
)
data class PendingEffectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "sequence_number") val sequenceNumber: Int,
    val kind: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "at_epoch_seconds") val atEpochSeconds: Long?,
    @ColumnInfo(name = "requested_at_epoch_seconds") val requestedAtEpochSeconds: Long,
    val handled: Boolean = false,
)

data class MedicationWithSchedule(
    @Embedded val medication: MedicationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "medication_id",
    )
    val schedule: OnceDailyScheduleEntity,
)

fun MedicationWithSchedule.toDomain(): Medication = Medication(
    id = medication.id,
    name = medication.name,
    schedule = OnceDailySchedule(
        id = schedule.id,
        hour = schedule.hour,
        minute = schedule.minute,
        earlyWindowMinutes = schedule.earlyWindowMinutes,
        lateWindowMinutes = schedule.lateWindowMinutes,
    ),
    enabled = medication.enabled,
)
