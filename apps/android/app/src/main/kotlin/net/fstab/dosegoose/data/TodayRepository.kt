package net.fstab.dosegoose.data

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fstab.dosegoose.app.DoseStatus
import net.fstab.dosegoose.app.RecordingAvailability
import net.fstab.dosegoose.app.TodayDose
import net.fstab.dosegoose.corebridge.CoreDoseProjection
import net.fstab.dosegoose.corebridge.CoreDoseTransition
import net.fstab.dosegoose.corebridge.CoreGuidance
import net.fstab.dosegoose.corebridge.CoreRecordingAvailability
import net.fstab.dosegoose.corebridge.CoreRejection
import net.fstab.dosegoose.corebridge.CoreReminderKind
import net.fstab.dosegoose.corebridge.DoseCoreBridge
import net.fstab.dosegoose.corebridge.DoseInitialization
import net.fstab.dosegoose.data.local.AppMetadataEntity
import net.fstab.dosegoose.data.local.DoseOccurrenceEntity
import net.fstab.dosegoose.data.local.MedicationDao
import net.fstab.dosegoose.data.local.PendingEffectEntity
import net.fstab.dosegoose.data.local.TodayDao
import net.fstab.dosegoose.platform.ForegroundAuthorizationProvider
import net.fstab.dosegoose.platform.PlatformMoment
import net.fstab.dosegoose.platform.PlatformTimeSource
import net.fstab.dosegoose.platform.occurrenceId

data class ReconcileResult(val nextBoundaryEpochSeconds: Long?)

data class RecordDoseResult(
    val accepted: Boolean,
    val rejection: CoreRejection?,
)

data class AlertOccurrenceState(
    val occurrenceId: String,
    val medicationId: String,
    val medicationName: String,
    val scheduledTimeLabel: String,
    val lateDeadlineTimeLabel: String,
    val scheduledAtEpochSeconds: Long = Long.MIN_VALUE,
    val due: Boolean = true,
    val guidance: String,
    val enabled: Boolean,
    val quietVisible: Boolean,
    val intrusiveVisible: Boolean,
    val snoozeAvailable: Boolean,
    val activityAccepted: Boolean,
    val recordingAvailable: Boolean,
    val lateDeadlineEpochSeconds: Long,
    val lastIntrusivePresentationAtEpochSeconds: Long?,
)

data class ScheduledEvaluation(
    val effectId: Long,
    val occurrenceId: String,
    val atEpochSeconds: Long,
)

data class ReminderStateSnapshot(
    val nowEpochSeconds: Long,
    val occurrences: List<AlertOccurrenceState>,
    val futureEvaluations: List<ScheduledEvaluation>,
    val pendingEffectCutoffId: Long?,
    val pendingIntrusiveAlertIds: Set<String>,
    val activityMonitoringRequired: Boolean,
    val sleepMonitoringRequired: Boolean,
)

interface ReminderStateStore {
    suspend fun reconcile(): ReconcileResult

    suspend fun dismissReminder(
        occurrenceId: String,
        kind: CoreReminderKind,
    ): RecordDoseResult?

    suspend fun snooze(
        occurrenceId: String,
        untilEpochSeconds: Long,
    ): RecordDoseResult?

    suspend fun acceptActivityEvidence(): Int

    suspend fun acceptSleepEvidence(confirmedAtEpochSeconds: Long): Boolean

    suspend fun returnToInactive(
        occurrenceId: String,
        appIsForeground: Boolean,
    ): RecordDoseResult?

    suspend fun reminderState(): ReminderStateSnapshot

    suspend fun acknowledgePendingEffectsThrough(effectId: Long)
}

class TodayRepository(
    private val medicationDao: MedicationDao,
    private val todayDao: TodayDao,
    private val coreBridge: DoseCoreBridge,
    private val timeSource: PlatformTimeSource,
    private val authorizationProvider: ForegroundAuthorizationProvider,
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() },
) : ReminderStateStore {
    private val todayDisplayContext = MutableStateFlow<TodayDisplayContext?>(null)
    private val transitionMutex = Mutex()

    val todayDoses: Flow<List<TodayDose>> = combine(
        todayDao.observeEnabledOccurrences(),
        todayDisplayContext,
    ) { occurrences, context ->
        if (context == null) return@combine emptyList()
        val now = timeSource.capture().epochSeconds
        selectTodayOccurrences(occurrences, context)
            .map { occurrence ->
                occurrence.toDomain(
                    timeSource,
                    coreBridge.project(occurrence.coreSnapshotJson, now)
                        .activityAcceptedAtEpochSeconds != null,
                )
            }
    }

    override suspend fun reconcile(): ReconcileResult = transitionMutex.withLock {
        val moment = timeSource.capture()
        val dates = listOf(
            moment.localDate.minusDays(1),
            moment.localDate,
            moment.localDate.plusDays(1),
        )
        val deviceId = medicationDao.metadataValue(DEVICE_ID_KEY)
            ?: medicationDao.metadataValueOrInsert(DEVICE_ID_KEY, newDeviceId())

        for (stored in medicationDao.enabledMedications()) {
            for (date in dates) {
                val scheduled = timeSource.resolve(
                    date,
                    stored.schedule.hour,
                    stored.schedule.minute,
                    moment.zoneId,
                )
                if (scheduled.epochSeconds < stored.schedule.effectiveFromEpochSeconds) {
                    continue
                }
                val existing = todayDao.occurrence(stored.schedule.id, date.toString())
                val deadline = scheduled.epochSeconds + stored.schedule.lateWindowMinutes * 60L
                if (existing == null && date != moment.localDate && deadline < moment.epochSeconds) {
                    continue
                }
                if (existing == null) {
                    val id = occurrenceId(stored.schedule.id, date)
                    val transition = coreBridge.initialize(
                        DoseInitialization(
                            occurrenceId = id,
                            medicationId = stored.medication.id,
                            deviceId = deviceId,
                            scheduledAtEpochSeconds = scheduled.epochSeconds,
                            earlyWindowSeconds = stored.schedule.earlyWindowMinutes * 60L,
                            lateWindowSeconds = stored.schedule.lateWindowMinutes * 60L,
                        ),
                        moment.epochSeconds,
                    )
                    val entity = transition.toEntity(
                        id = id,
                        medicationId = stored.medication.id,
                        scheduleId = stored.schedule.id,
                        medicationName = stored.medication.name,
                        localDate = date,
                        scheduledAtEpochSeconds = scheduled.epochSeconds,
                        zoneId = scheduled.zoneId,
                        utcOffsetSeconds = scheduled.utcOffsetSeconds,
                        earlyWindowMinutes = stored.schedule.earlyWindowMinutes,
                        lateWindowMinutes = stored.schedule.lateWindowMinutes,
                        updatedAtEpochSeconds = moment.epochSeconds,
                    )
                    if (!todayDao.insertOccurrenceWithEffects(
                            entity,
                            transition.effects.toEntities(moment.epochSeconds),
                        )
                    ) {
                        observeExisting(id, moment)
                    }
                } else {
                    observeExisting(existing.id, moment)
                }
            }
        }

        val activityAcceptedAt = deviceActivityAcceptedAt(moment.epochSeconds)
        if (activityAcceptedAt != null) {
            applyActiveDeviceState(moment.epochSeconds)
        }

        val nextMidnight = timeSource.resolve(
            moment.localDate.plusDays(1),
            0,
            0,
            moment.zoneId,
        ).epochSeconds
        todayDisplayContext.value = TodayDisplayContext(
            localDate = moment.localDate,
            nowEpochSeconds = moment.epochSeconds,
        )
        ReconcileResult(
            listOfNotNull(nextBoundary(moment.epochSeconds), nextMidnight)
                .filter { it > moment.epochSeconds }
                .minOrNull(),
        )
    }

    suspend fun record(
        occurrenceId: String,
        intakeAtEpochSeconds: Long?,
        appIsForeground: Boolean,
        outsideWindow: Boolean = false,
    ): RecordDoseResult = transitionMutex.withLock {
        val moment = timeSource.capture()
        val authorization = authorizationProvider.capture(appIsForeground)
        val occurrence = checkNotNull(todayDao.occurrence(occurrenceId)) {
            "Occurrence $occurrenceId does not exist"
        }
        val transition = if (outsideWindow) {
            coreBridge.recordOutsideWindow(
                occurrence.coreSnapshotJson,
                moment.epochSeconds,
                requireNotNull(intakeAtEpochSeconds),
                authorization.appIsForeground,
                authorization.deviceIsUnlocked,
            )
        } else {
            coreBridge.record(
                occurrence.coreSnapshotJson,
                moment.epochSeconds,
                intakeAtEpochSeconds,
                authorization.appIsForeground,
                authorization.deviceIsUnlocked,
            )
        }
        todayDao.updateOccurrenceWithEffects(
            occurrence.withTransition(transition, moment.epochSeconds),
            transition.effects.toEntities(moment.epochSeconds),
        )
        RecordDoseResult(transition.accepted, transition.rejection)
    }

    override suspend fun dismissReminder(
        occurrenceId: String,
        kind: CoreReminderKind,
    ): RecordDoseResult? = applyOccurrenceAction(occurrenceId) { occurrence, now ->
        coreBridge.dismissReminder(occurrence.coreSnapshotJson, now, kind)
    }

    override suspend fun snooze(
        occurrenceId: String,
        untilEpochSeconds: Long,
    ): RecordDoseResult? = applyOccurrenceAction(occurrenceId) { occurrence, now ->
        coreBridge.snooze(
            occurrence.coreSnapshotJson,
            now,
            untilEpochSeconds,
        )
    }

    suspend fun acceptActivity(occurrenceId: String): RecordDoseResult? =
        applyOccurrenceAction(occurrenceId) { occurrence, now ->
            coreBridge.acceptActivity(occurrence.coreSnapshotJson, now)
        }

    override suspend fun acceptActivityEvidence(): Int = transitionMutex.withLock {
        val now = timeSource.capture().epochSeconds
        medicationDao.upsertMetadata(AppMetadataEntity(DEVICE_ACTIVITY_KEY, now.toString()))
        applyActiveDeviceState(now)
    }

    override suspend fun acceptSleepEvidence(confirmedAtEpochSeconds: Long): Boolean =
        transitionMutex.withLock {
            val now = timeSource.capture().epochSeconds
            if (confirmedAtEpochSeconds > now) return@withLock false
            val stored = medicationDao.metadataValue(DEVICE_ACTIVITY_KEY)
            val activityAcceptedAt = stored
                ?.takeUnless { it == DEVICE_INACTIVE }
                ?.toLongOrNull()
                ?: return@withLock false
            if (confirmedAtEpochSeconds <= activityAcceptedAt) return@withLock false

            medicationDao.upsertMetadata(AppMetadataEntity(DEVICE_ACTIVITY_KEY, DEVICE_INACTIVE))
            medicationDao.upsertMetadata(
                AppMetadataEntity(DEVICE_SLEEP_ACCEPTED_AT_KEY, confirmedAtEpochSeconds.toString()),
            )
            true
        }

    suspend fun rebaseActivityEvidenceForClockChange() = transitionMutex.withLock {
        val stored = medicationDao.metadataValue(DEVICE_ACTIVITY_KEY)
        if (stored != null && stored != DEVICE_INACTIVE) {
            medicationDao.upsertMetadata(
                AppMetadataEntity(DEVICE_ACTIVITY_KEY, timeSource.capture().epochSeconds.toString()),
            )
        }
    }

    private suspend fun applyActiveDeviceState(now: Long): Int {
        var acceptedCount = 0
        for (stored in todayDao.alertOccurrences(now - MAX_LATE_WINDOW_SECONDS)) {
            val occurrence = stored.occurrence
            val projection = coreBridge.project(occurrence.coreSnapshotJson, now)
            val eligible = stored.medicationEnabled &&
                occurrence.scheduledAtEpochSeconds >= stored.scheduleEffectiveFrom &&
                projection.recordingAvailability == CoreRecordingAvailability.Available &&
                projection.activityAcceptedAtEpochSeconds == null
            if (!eligible) continue

            val transition = coreBridge.acceptActivity(occurrence.coreSnapshotJson, now)
            todayDao.updateOccurrenceWithEffects(
                occurrence.withTransition(transition, now),
                transition.effects.toEntities(now),
            )
            if (transition.accepted) acceptedCount += 1
        }
        return acceptedCount
    }

    override suspend fun returnToInactive(
        occurrenceId: String,
        appIsForeground: Boolean,
    ): RecordDoseResult? = transitionMutex.withLock {
        val occurrence = todayDao.occurrence(occurrenceId) ?: return@withLock null
        val now = timeSource.capture().epochSeconds
        val authorization = authorizationProvider.capture(appIsForeground)
        val transition = coreBridge.returnToInactive(
            occurrence.coreSnapshotJson,
            now,
            authorization.appIsForeground,
            authorization.deviceIsUnlocked,
        )
        if (transition.accepted) {
            medicationDao.upsertMetadata(AppMetadataEntity(DEVICE_ACTIVITY_KEY, DEVICE_INACTIVE))
            for (stored in todayDao.alertOccurrences(now - MAX_LATE_WINDOW_SECONDS)) {
                val activeOccurrence = stored.occurrence
                val projection = coreBridge.project(activeOccurrence.coreSnapshotJson, now)
                if (projection.activityAcceptedAtEpochSeconds == null) continue
                val reset = coreBridge.returnToInactive(
                    activeOccurrence.coreSnapshotJson,
                    now,
                    authorization.appIsForeground,
                    authorization.deviceIsUnlocked,
                )
                todayDao.updateOccurrenceWithEffects(
                    activeOccurrence.withTransition(reset, now),
                    reset.effects.toEntities(now),
                )
            }
        } else {
            todayDao.updateOccurrenceWithEffects(
                occurrence.withTransition(transition, now),
                transition.effects.toEntities(now),
            )
        }
        RecordDoseResult(transition.accepted, transition.rejection)
    }

    override suspend fun reminderState(): ReminderStateSnapshot = transitionMutex.withLock {
        val now = timeSource.capture().epochSeconds
        val occurrences = todayDao.alertOccurrences(now - MAX_LATE_WINDOW_SECONDS).map { stored ->
            val occurrence = stored.occurrence
            val projection = coreBridge.project(occurrence.coreSnapshotJson, now)
            val todayDose = occurrence.toDomain(timeSource)
            AlertOccurrenceState(
                occurrenceId = occurrence.id,
                medicationId = occurrence.medicationId,
                medicationName = occurrence.medicationName,
                scheduledTimeLabel = todayDose.scheduledTimeLabel,
                lateDeadlineTimeLabel = timeSource.format(
                    occurrence.scheduledAtEpochSeconds + occurrence.lateWindowMinutes * 60L,
                    occurrence.zoneId,
                ),
                scheduledAtEpochSeconds = occurrence.scheduledAtEpochSeconds,
                due = occurrence.scheduledAtEpochSeconds <= now,
                guidance = todayDose.guidance,
                enabled = stored.medicationEnabled &&
                    occurrence.scheduledAtEpochSeconds >= stored.scheduleEffectiveFrom,
                quietVisible = projection.quietVisible,
                intrusiveVisible = projection.intrusiveVisible,
                snoozeAvailable = projection.intrusiveVisible &&
                    snoozeFitsLateWindow(
                        nowEpochSeconds = now,
                        scheduledAtEpochSeconds = occurrence.scheduledAtEpochSeconds,
                        lateWindowMinutes = occurrence.lateWindowMinutes,
                    ),
                activityAccepted = projection.activityAcceptedAtEpochSeconds != null,
                recordingAvailable = projection.recordingAvailability ==
                    CoreRecordingAvailability.Available,
                lateDeadlineEpochSeconds = occurrence.scheduledAtEpochSeconds +
                    occurrence.lateWindowMinutes * 60L,
                lastIntrusivePresentationAtEpochSeconds =
                    todayDao.lastIntrusivePresentationAt(occurrence.id),
            )
        }
        val futureEvaluations = todayDao.futureEvaluationEffects(now).mapNotNull { effect ->
            effect.atEpochSeconds?.let { at ->
                ScheduledEvaluation(effect.id, effect.occurrenceId, at)
            }
        }
        val deviceIsActive = medicationDao.metadataValue(DEVICE_ACTIVITY_KEY)
            ?.takeUnless { it == DEVICE_INACTIVE } != null
        ReminderStateSnapshot(
            nowEpochSeconds = now,
            occurrences = occurrences,
            futureEvaluations = futureEvaluations,
            pendingEffectCutoffId = todayDao.maximumUnhandledEffectId(),
            pendingIntrusiveAlertIds = todayDao.unhandledEffects()
                .filter { it.kind == "PresentIntrusively" }
                .map(PendingEffectEntity::occurrenceId)
                .toSet(),
            activityMonitoringRequired = !deviceIsActive && occurrences.any { occurrence ->
                occurrence.enabled &&
                    !occurrence.activityAccepted &&
                    occurrence.recordingAvailable
            },
            sleepMonitoringRequired = deviceIsActive,
        )
    }

    override suspend fun acknowledgePendingEffectsThrough(effectId: Long) =
        transitionMutex.withLock {
            todayDao.markEffectsHandledThrough(effectId)
            Unit
        }

    fun captureNow(): PlatformMoment = timeSource.capture()

    fun formatTime(epochSeconds: Long, zoneId: String): String =
        timeSource.format(epochSeconds, zoneId)

    private suspend fun applyOccurrenceAction(
        occurrenceId: String,
        transition: (DoseOccurrenceEntity, Long) -> CoreDoseTransition,
    ): RecordDoseResult? = transitionMutex.withLock {
        val occurrence = todayDao.occurrence(occurrenceId) ?: return@withLock null
        val now = timeSource.capture().epochSeconds
        val result = transition(occurrence, now)
        todayDao.updateOccurrenceWithEffects(
            occurrence.withTransition(result, now),
            result.effects.toEntities(now),
        )
        RecordDoseResult(result.accepted, result.rejection)
    }

    private suspend fun observeExisting(occurrenceId: String, moment: PlatformMoment) {
        val occurrence = checkNotNull(todayDao.occurrence(occurrenceId))
        val transition = coreBridge.observe(occurrence.coreSnapshotJson, moment.epochSeconds)
        todayDao.updateOccurrenceWithEffects(
            occurrence.withTransition(transition, moment.epochSeconds),
            transition.effects.toEntities(moment.epochSeconds),
        )
    }

    private suspend fun deviceActivityAcceptedAt(now: Long): Long? {
        val stored = medicationDao.metadataValue(DEVICE_ACTIVITY_KEY)
        if (stored != null) return stored.takeUnless { it == DEVICE_INACTIVE }?.toLongOrNull()

        val migrated = todayDao.observeEnabledOccurrencesSnapshot()
            .mapNotNull { coreBridge.project(it.coreSnapshotJson, now).activityAcceptedAtEpochSeconds }
            .maxOrNull()
        medicationDao.upsertMetadata(
            AppMetadataEntity(DEVICE_ACTIVITY_KEY, migrated?.toString() ?: DEVICE_INACTIVE),
        )
        return migrated
    }

    private suspend fun nextBoundary(nowEpochSeconds: Long): Long? =
        todayDao.observeEnabledOccurrencesSnapshot()
            .mapNotNull { occurrence ->
                when (CoreGuidance.valueOf(occurrence.guidance)) {
                    CoreGuidance.Upcoming ->
                        occurrence.scheduledAtEpochSeconds - occurrence.earlyWindowMinutes * 60L
                    CoreGuidance.EarlyAvailable -> occurrence.scheduledAtEpochSeconds
                    CoreGuidance.Due,
                    CoreGuidance.Overdue,
                    -> occurrence.scheduledAtEpochSeconds + occurrence.lateWindowMinutes * 60L + 1L
                    CoreGuidance.LateWindowElapsed,
                    CoreGuidance.Recorded,
                    -> null
                }
            }
            .filter { it > nowEpochSeconds }
            .minOrNull()

    private companion object {
        const val DEVICE_ID_KEY = "device_id"
        const val DEVICE_ACTIVITY_KEY = "device_activity_accepted_at"
        const val DEVICE_SLEEP_ACCEPTED_AT_KEY = "device_sleep_accepted_at"
        const val DEVICE_INACTIVE = "inactive"
        const val MAX_LATE_WINDOW_SECONDS = 24 * 60 * 60L
    }
}

internal data class TodayDisplayContext(
    val localDate: LocalDate,
    val nowEpochSeconds: Long,
)

internal fun selectTodayOccurrences(
    occurrences: List<DoseOccurrenceEntity>,
    context: TodayDisplayContext,
): List<DoseOccurrenceEntity> {
    val today = context.localDate.toString()
    val yesterday = context.localDate.minusDays(1).toString()
    val tomorrow = context.localDate.plusDays(1).toString()
    fun DoseOccurrenceEntity.isActive() =
        scheduledAtEpochSeconds - earlyWindowMinutes * 60L <= context.nowEpochSeconds

    val activeTomorrowSchedules = occurrences.asSequence()
        .filter { it.localDate == tomorrow && it.isActive() }
        .map(DoseOccurrenceEntity::scheduleId)
        .toSet()
    val activeTodaySchedules = occurrences.asSequence()
        .filter { it.localDate == today && it.isActive() }
        .map(DoseOccurrenceEntity::scheduleId)
        .toSet()

    return occurrences.filter { occurrence ->
        occurrence.localDate == tomorrow && occurrence.isActive() ||
            occurrence.localDate == today && occurrence.scheduleId !in activeTomorrowSchedules ||
            occurrence.localDate == yesterday &&
            occurrence.recordingAvailability == CoreRecordingAvailability.Available.name &&
            occurrence.scheduleId !in activeTodaySchedules &&
            occurrence.scheduleId !in activeTomorrowSchedules
    }
}

private fun CoreDoseTransition.toEntity(
    id: String,
    medicationId: String,
    scheduleId: String,
    medicationName: String,
    localDate: LocalDate,
    scheduledAtEpochSeconds: Long,
    zoneId: String,
    utcOffsetSeconds: Int,
    earlyWindowMinutes: Int,
    lateWindowMinutes: Int,
    updatedAtEpochSeconds: Long,
): DoseOccurrenceEntity = DoseOccurrenceEntity(
    id = id,
    medicationId = medicationId,
    scheduleId = scheduleId,
    medicationName = medicationName,
    localDate = localDate.toString(),
    scheduledAtEpochSeconds = scheduledAtEpochSeconds,
    zoneId = zoneId,
    utcOffsetSeconds = utcOffsetSeconds,
    earlyWindowMinutes = earlyWindowMinutes,
    lateWindowMinutes = lateWindowMinutes,
    snapshotVersion = CURRENT_CORE_SNAPSHOT_VERSION,
    coreSnapshotJson = snapshotJson,
    guidance = projection.guidance.name,
    recordingAvailability = projection.recordingAvailability.name,
    dueInSeconds = projection.dueInSeconds,
    lateBySeconds = projection.lateBySeconds,
    remainingSeconds = projection.remainingSeconds,
    intakeAtEpochSeconds = projection.intakeAtEpochSeconds,
    updatedAtEpochSeconds = updatedAtEpochSeconds,
)

private fun DoseOccurrenceEntity.withTransition(
    transition: CoreDoseTransition,
    updatedAtEpochSeconds: Long,
): DoseOccurrenceEntity = copy(
    snapshotVersion = CURRENT_CORE_SNAPSHOT_VERSION,
    coreSnapshotJson = transition.snapshotJson,
    guidance = transition.projection.guidance.name,
    recordingAvailability = transition.projection.recordingAvailability.name,
    dueInSeconds = transition.projection.dueInSeconds,
    lateBySeconds = transition.projection.lateBySeconds,
    remainingSeconds = transition.projection.remainingSeconds,
    intakeAtEpochSeconds = transition.projection.intakeAtEpochSeconds,
    updatedAtEpochSeconds = updatedAtEpochSeconds,
)

private fun List<net.fstab.dosegoose.corebridge.CoreRequestedEffect>.toEntities(
    requestedAtEpochSeconds: Long,
): List<PendingEffectEntity> = map { effect ->
    PendingEffectEntity(
        occurrenceId = effect.occurrenceId,
        sequenceNumber = 0,
        kind = effect.kind.name,
        deviceId = effect.deviceId,
        atEpochSeconds = effect.atEpochSeconds,
        requestedAtEpochSeconds = requestedAtEpochSeconds,
    )
}

private fun DoseOccurrenceEntity.toDomain(
    timeSource: PlatformTimeSource,
    activityAccepted: Boolean = false,
): TodayDose {
    val projection = CoreDoseProjection(
        guidance = CoreGuidance.valueOf(guidance),
        recordingAvailability = CoreRecordingAvailability.valueOf(recordingAvailability),
        dueInSeconds = dueInSeconds,
        lateBySeconds = lateBySeconds,
        remainingSeconds = remainingSeconds,
        intakeAtEpochSeconds = intakeAtEpochSeconds,
        activityAcceptedAtEpochSeconds = null,
        quietVisible = false,
        intrusiveVisible = false,
        snoozedUntilEpochSeconds = null,
    )
    val status = when (projection.guidance) {
        CoreGuidance.Upcoming -> DoseStatus.Upcoming
        CoreGuidance.EarlyAvailable -> DoseStatus.Upcoming
        CoreGuidance.Due, CoreGuidance.Overdue -> DoseStatus.Needed
        CoreGuidance.LateWindowElapsed -> DoseStatus.Attention
        CoreGuidance.Recorded -> DoseStatus.Taken
    }
    val guidanceText = when (projection.guidance) {
        CoreGuidance.Upcoming -> "Due in ${projection.dueInSeconds.asDuration()}"
        CoreGuidance.EarlyAvailable ->
            "Available to take · due in ${projection.dueInSeconds.asDuration()}"
        CoreGuidance.Due -> "Due now"
        CoreGuidance.Overdue ->
            "${projection.lateBySeconds.asDuration()} late · " +
                "${projection.remainingSeconds.asDuration()} remaining"
        CoreGuidance.LateWindowElapsed ->
            "Late window ended ${projection.lateBySeconds.asDuration()} after schedule"
        CoreGuidance.Recorded -> "Recorded"
    }
    return TodayDose(
        occurrenceId = id,
        medicationId = medicationId,
        medicationName = medicationName,
        scheduledAtEpochSeconds = scheduledAtEpochSeconds,
        availableFromEpochSeconds = scheduledAtEpochSeconds - earlyWindowMinutes * 60L,
        lateDeadlineEpochSeconds = scheduledAtEpochSeconds + lateWindowMinutes * 60L,
        scheduledTimeLabel = timeSource.format(scheduledAtEpochSeconds, zoneId),
        zoneId = zoneId,
        observedAtEpochSeconds = updatedAtEpochSeconds,
        status = status,
        guidance = guidanceText,
        recordingAvailability = when (projection.recordingAvailability) {
            CoreRecordingAvailability.NotYetDue -> RecordingAvailability.NotYetDue
            CoreRecordingAvailability.Available -> RecordingAvailability.Available
            CoreRecordingAvailability.LateWindowElapsed -> RecordingAvailability.LateWindowElapsed
            CoreRecordingAvailability.AlreadyRecorded -> RecordingAvailability.AlreadyRecorded
        },
        intakeAtEpochSeconds = intakeAtEpochSeconds,
        intakeTimeLabel = intakeAtEpochSeconds?.let { timeSource.format(it, zoneId) },
        activityAccepted = activityAccepted,
    )
}

private fun Long?.asDuration(): String {
    val total = this ?: 0L
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "less than a minute"
    }
}

private const val CURRENT_CORE_SNAPSHOT_VERSION = 3
