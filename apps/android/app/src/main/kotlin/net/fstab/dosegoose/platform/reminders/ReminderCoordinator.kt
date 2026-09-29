package net.fstab.dosegoose.platform.reminders

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fstab.dosegoose.corebridge.CoreReminderKind
import net.fstab.dosegoose.data.DEFAULT_SNOOZE_SECONDS
import net.fstab.dosegoose.data.ReconcileResult
import net.fstab.dosegoose.data.RecordDoseResult
import net.fstab.dosegoose.data.ReminderStateStore
import net.fstab.dosegoose.data.intrusiveRealertIsDue
import net.fstab.dosegoose.data.nextIntrusiveRealertAt
import net.fstab.dosegoose.platform.activity.ActivityEvidenceMonitor
import net.fstab.dosegoose.platform.activity.NoActivityEvidenceMonitor
import net.fstab.dosegoose.platform.sleep.NoSleepEvidenceMonitor
import net.fstab.dosegoose.platform.sleep.SleepEvidenceMonitor

class ReminderCoordinator(
    private val repository: ReminderStateStore,
    private val platform: ReminderPlatform,
    private val activityEvidenceMonitor: ActivityEvidenceMonitor = NoActivityEvidenceMonitor,
    private val sleepEvidenceMonitor: SleepEvidenceMonitor = NoSleepEvidenceMonitor,
) {
    private val mutex = Mutex()

    fun capabilities(): ReminderCapabilities = platform.capabilities()

    fun initializeChannels() = platform.createChannels()

    suspend fun reconcileAndSynchronize(): ReconcileResult = mutex.withLock {
        val result = repository.reconcile()
        synchronize(result.nextBoundaryEpochSeconds)
        result
    }

    suspend fun synchronizeCurrentState() = mutex.withLock {
        synchronize(nextBoundaryEpochSeconds = null)
    }

    suspend fun restoreNotification() = mutex.withLock {
        // Android can let users clear an ongoing card. Restore the current state
        // without turning that gesture into a dose transition or a fresh sound.
        synchronize(nextBoundaryEpochSeconds = null, suppressAlert = true)
    }

    suspend fun dismiss(
        occurrenceId: String,
        kind: CoreReminderKind,
    ): RecordDoseResult? = mutex.withLock {
        val result = repository.dismissReminder(occurrenceId, kind)
        synchronize(nextBoundaryEpochSeconds = null)
        result
    }

    suspend fun snooze(occurrenceId: String): RecordDoseResult? = mutex.withLock {
        val state = repository.reminderState()
        val occurrence = state.occurrences.firstOrNull { it.occurrenceId == occurrenceId }
            ?: return@withLock null
        if (!occurrence.enabled || !occurrence.intrusiveVisible || !occurrence.recordingAvailable) {
            synchronize(nextBoundaryEpochSeconds = null)
            return@withLock null
        }
        val until = minOf(
            state.nowEpochSeconds + DEFAULT_SNOOZE_SECONDS,
            occurrence.lateDeadlineEpochSeconds,
        )
        if (until <= state.nowEpochSeconds) {
            synchronize(nextBoundaryEpochSeconds = null)
            return@withLock null
        }
        val result = repository.snooze(occurrenceId, until)
        synchronize(nextBoundaryEpochSeconds = null)
        result
    }

    suspend fun realert(occurrenceId: String, requestedAtEpochSeconds: Long): ReconcileResult =
        mutex.withLock {
            val result = repository.reconcile()
            synchronize(
                nextBoundaryEpochSeconds = result.nextBoundaryEpochSeconds,
                realertRequest = occurrenceId to requestedAtEpochSeconds,
            )
            result
        }

    suspend fun acceptActivityEvidence(): Int = mutex.withLock {
        val reconciliation = repository.reconcile()
        val acceptedCount = repository.acceptActivityEvidence()
        synchronize(reconciliation.nextBoundaryEpochSeconds)
        acceptedCount
    }

    suspend fun acceptSleepEvidence(confirmedAtEpochSeconds: Long): Boolean = mutex.withLock {
        val accepted = repository.acceptSleepEvidence(confirmedAtEpochSeconds)
        val reconciliation = repository.reconcile()
        synchronize(reconciliation.nextBoundaryEpochSeconds)
        accepted
    }

    suspend fun returnToInactive(
        occurrenceId: String,
        appIsForeground: Boolean,
    ): RecordDoseResult? = mutex.withLock {
        val result = repository.returnToInactive(occurrenceId, appIsForeground)
        synchronize(nextBoundaryEpochSeconds = null)
        result
    }

    suspend fun cancelMedication(medicationId: String) = mutex.withLock {
        repository.reminderState().occurrences
            .filter { it.medicationId == medicationId }
            .forEach { occurrence ->
                platform.cancel(occurrence.occurrenceId)
                platform.cancelIntrusiveRealert(occurrence.occurrenceId)
            }
        synchronize(nextBoundaryEpochSeconds = null)
    }

    private suspend fun synchronize(
        nextBoundaryEpochSeconds: Long?,
        realertRequest: Pair<String, Long>? = null,
        suppressAlert: Boolean = false,
    ) {
        val state = repository.reminderState()
        state.occurrences.forEach { occurrence ->
            val activeIntrusive = occurrence.enabled && occurrence.intrusiveVisible &&
                occurrence.recordingAvailable &&
                state.nowEpochSeconds < occurrence.lateDeadlineEpochSeconds
            when {
                activeIntrusive -> {
                    val requestedRealert = realertRequest?.takeIf {
                        it.first == occurrence.occurrenceId && intrusiveRealertIsDue(
                            requestedAtEpochSeconds = it.second,
                            nowEpochSeconds = state.nowEpochSeconds,
                            lastPresentationAtEpochSeconds =
                                occurrence.lastIntrusivePresentationAtEpochSeconds,
                            lateDeadlineEpochSeconds = occurrence.lateDeadlineEpochSeconds,
                        )
                    }
                    val alert = !suppressAlert &&
                        (occurrence.occurrenceId in state.pendingIntrusiveAlertIds ||
                            requestedRealert != null)
                    platform.showIntrusive(occurrence, alert)
                    nextIntrusiveRealertAt(
                        state.nowEpochSeconds,
                        occurrence.lastIntrusivePresentationAtEpochSeconds,
                        occurrence.lateDeadlineEpochSeconds,
                    )?.let { platform.scheduleIntrusiveRealert(occurrence.occurrenceId, it) }
                        ?: platform.cancelIntrusiveRealert(occurrence.occurrenceId)
                }
                occurrence.enabled && occurrence.quietVisible -> {
                    platform.showQuiet(occurrence, alert = !suppressAlert && !occurrence.activityAccepted)
                    platform.cancelIntrusiveRealert(occurrence.occurrenceId)
                }
                else -> {
                    platform.cancel(occurrence.occurrenceId)
                    platform.cancelIntrusiveRealert(occurrence.occurrenceId)
                }
            }
        }
        state.futureEvaluations.forEach(platform::scheduleEvaluation)
        nextBoundaryEpochSeconds?.let(platform::scheduleReconciliation)
        activityEvidenceMonitor.setMonitoringRequired(state.activityMonitoringRequired)
        sleepEvidenceMonitor.setMonitoringRequired(state.sleepMonitoringRequired)
        state.pendingEffectCutoffId?.let { repository.acknowledgePendingEffectsThrough(it) }
    }
}
