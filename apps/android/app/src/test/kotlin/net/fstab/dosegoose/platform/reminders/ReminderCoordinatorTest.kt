package net.fstab.dosegoose.platform.reminders

import kotlinx.coroutines.runBlocking
import net.fstab.dosegoose.corebridge.CoreReminderKind
import net.fstab.dosegoose.data.AlertOccurrenceState
import net.fstab.dosegoose.data.ReconcileResult
import net.fstab.dosegoose.data.RecordDoseResult
import net.fstab.dosegoose.data.ReminderStateStore
import net.fstab.dosegoose.data.ReminderStateSnapshot
import net.fstab.dosegoose.data.ScheduledEvaluation
import net.fstab.dosegoose.platform.activity.ActivityEvidenceMonitor
import net.fstab.dosegoose.platform.sleep.SleepEvidenceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderCoordinatorTest {
    @Test
    fun `reconciliation projects current presentation and every durable boundary`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(
                occurrence("quiet", enabled = true, quiet = true, intrusive = false),
                occurrence("intrusive", enabled = true, quiet = true, intrusive = true),
                occurrence("disabled", enabled = false, quiet = true, intrusive = true),
            ),
            evaluations = listOf(
                ScheduledEvaluation(4, "quiet", 1_100),
                ScheduledEvaluation(8, "intrusive", 1_200),
            ),
            boundary = 1_050,
        )
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).reconcileAndSynchronize()

        assertEquals(
            listOf(
                "show-quiet:quiet:true",
                "cancel-realert:quiet",
                "show-intrusive:intrusive:false",
                "cancel-realert:intrusive",
                "cancel:disabled",
                "cancel-realert:disabled",
                "evaluation:4@1100",
                "evaluation:8@1200",
                "reconcile@1050",
            ),
            platform.events,
        )
        assertEquals(42L, store.acknowledgedThrough)
    }

    @Test
    fun `accepted activity is applied after reconciliation and then synchronized`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(
                occurrence("due", enabled = true, quiet = true, intrusive = true)
                    .copy(activityAccepted = true, recordingAvailable = true),
            ),
        )
        val platform = FakePlatform()
        val monitor = FakeActivityMonitor()

        val count = ReminderCoordinator(store, platform, monitor).acceptActivityEvidence()

        assertEquals(1, count)
        assertEquals(1, store.reconcileCount)
        assertEquals(1, store.activityEvidenceCount)
        assertEquals(listOf(false), monitor.requirements)
        assertTrue(platform.events.contains("show-intrusive:due:false"))
    }

    @Test
    fun `synchronization enables detection only when inactive due activity is needed`() =
        runBlocking {
            val store = FakeStore(
                occurrences = listOf(
                    occurrence("due", enabled = true, quiet = true, intrusive = false)
                        .copy(recordingAvailable = true),
                ),
            )
            val monitor = FakeActivityMonitor()

            ReminderCoordinator(store, FakePlatform(), monitor).synchronizeCurrentState()

            assertEquals(listOf(true), monitor.requirements)
        }

    @Test
    fun `sleep evidence clears active latch before reconciliation and synchronizes monitors`() =
        runBlocking {
            val store = FakeStore(occurrences = emptyList()).apply {
                deviceIsActive = true
            }
            val activityMonitor = FakeActivityMonitor()
            val sleepMonitor = FakeSleepMonitor()

            val accepted = ReminderCoordinator(
                store,
                FakePlatform(),
                activityMonitor,
                sleepMonitor,
            ).acceptSleepEvidence(900L)

            assertTrue(accepted)
            assertEquals(listOf("sleep:900", "reconcile"), store.transitionEvents)
            assertEquals(listOf(false), activityMonitor.requirements)
            assertEquals(listOf(false), sleepMonitor.requirements)
        }

    @Test
    fun `sleep monitoring follows the durable device activity latch`() = runBlocking {
        val store = FakeStore(occurrences = emptyList()).apply { deviceIsActive = true }
        val monitor = FakeSleepMonitor()

        ReminderCoordinator(
            store,
            FakePlatform(),
            sleepEvidenceMonitor = monitor,
        ).synchronizeCurrentState()

        assertEquals(listOf(true), monitor.requirements)
    }

    @Test
    fun `dismiss synchronizes without immediately observing and re-presenting`() = runBlocking {
        val store = FakeStore(occurrences = emptyList())
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).dismiss("occurrence", CoreReminderKind.Quiet)

        assertEquals(0, store.reconcileCount)
        assertEquals("occurrence" to CoreReminderKind.Quiet, store.dismissed)
        assertEquals(42L, store.acknowledgedThrough)
    }

    @Test
    fun `intrusive snooze ends ten minutes after action`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(occurrence("dose", true, quiet = true, intrusive = true)),
        )

        ReminderCoordinator(store, FakePlatform()).snooze("dose")

        assertEquals("dose" to 1_600L, store.snoozed)
    }

    @Test
    fun `platform failure leaves effects unacknowledged for replay`() {
        val store = FakeStore(
            occurrences = listOf(occurrence("quiet", true, quiet = true, intrusive = false)),
        )
        val platform = FakePlatform(failOnQuiet = true)

        val result = runCatching {
            runBlocking { ReminderCoordinator(store, platform).reconcileAndSynchronize() }
        }

        assertTrue(result.isFailure)
        assertEquals(null, store.acknowledgedThrough)
    }

    @Test
    fun `cancel medication clears every matching presentation then synchronizes all state`() =
        runBlocking {
            val store = FakeStore(
                occurrences = listOf(
                    occurrence("first", enabled = true, quiet = false, intrusive = false)
                        .copy(medicationId = "target"),
                    occurrence("second", enabled = false, quiet = true, intrusive = true)
                        .copy(medicationId = "target"),
                    occurrence("unrelated", enabled = true, quiet = true, intrusive = false),
                ),
            )
            val platform = FakePlatform()

            ReminderCoordinator(store, platform).cancelMedication("target")

            assertEquals(
                listOf(
                    "cancel:first",
                    "cancel-realert:first",
                    "cancel:second",
                    "cancel-realert:second",
                    "cancel:first",
                    "cancel-realert:first",
                    "cancel:second",
                    "cancel-realert:second",
                    "show-quiet:unrelated:true",
                    "cancel-realert:unrelated",
                ),
                platform.events,
            )
            assertEquals(42L, store.acknowledgedThrough)
        }

    @Test
    fun `synchronize current state does not invent a global boundary`() = runBlocking {
        val store = FakeStore(
            occurrences = emptyList(),
            evaluations = listOf(ScheduledEvaluation(9, "dose", 2_000)),
            boundary = 1_900,
        )
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).synchronizeCurrentState()

        assertEquals(listOf("evaluation:9@2000"), platform.events)
        assertEquals(0, store.reconcileCount)
        assertEquals(42L, store.acknowledgedThrough)
    }

    @Test
    fun `new intrusive effect alerts once and schedules next alert`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(
                occurrence("dose", true, quiet = true, intrusive = true)
                    .copy(lastIntrusivePresentationAtEpochSeconds = 1_000L),
            ),
        ).apply { pendingIntrusiveAlertIds = setOf("dose") }
        val platform = FakePlatform()
        val coordinator = ReminderCoordinator(store, platform)

        coordinator.synchronizeCurrentState()
        assertEquals(listOf("show-intrusive:dose:true", "realert:dose@1600"), platform.events)

        platform.events.clear()
        store.pendingIntrusiveAlertIds = emptySet()
        coordinator.synchronizeCurrentState()
        assertEquals(listOf("show-intrusive:dose:false", "realert:dose@1600"), platform.events)
    }

    @Test
    fun `due re-alert sounds and schedules next cadence`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(
                occurrence("dose", true, quiet = true, intrusive = true)
                    .copy(lastIntrusivePresentationAtEpochSeconds = 1_000L),
            ),
        ).apply { nowEpochSeconds = 1_600L }
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).realert("dose", 1_600L)

        assertEquals(1, store.reconcileCount)
        assertEquals(listOf("show-intrusive:dose:true", "realert:dose@2200"), platform.events)
    }

    @Test
    fun `stale re-alert cannot sound after snooze or a newer presentation`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(
                occurrence("dose", true, quiet = true, intrusive = false)
                    .copy(lastIntrusivePresentationAtEpochSeconds = 1_300L),
            ),
        ).apply { nowEpochSeconds = 1_600L }
        val platform = FakePlatform()
        val coordinator = ReminderCoordinator(store, platform)

        coordinator.realert("dose", 1_600L)
        assertEquals(listOf("show-quiet:dose:true", "cancel-realert:dose"), platform.events)

        platform.events.clear()
        store.occurrences = listOf(
            occurrence("dose", true, quiet = true, intrusive = true)
                .copy(lastIntrusivePresentationAtEpochSeconds = 1_300L),
        )
        coordinator.realert("dose", 1_600L)
        assertEquals(listOf("show-intrusive:dose:false", "realert:dose@1900"), platform.events)
    }

    @Test
    fun `short remaining window snoozes to cutoff without dismissing dose`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(
                occurrence("dose", true, quiet = true, intrusive = true)
                    .copy(lastIntrusivePresentationAtEpochSeconds = 1_000L),
            ),
        ).apply { nowEpochSeconds = 2_900L }

        ReminderCoordinator(store, FakePlatform()).snooze("dose")

        assertEquals("dose" to 3_000L, store.snoozed)
        assertEquals(null, store.dismissed)
        assertEquals(0, store.reconcileCount)
    }

    @Test
    fun `stale snooze action cannot snooze an inactive dose`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(occurrence("dose", true, quiet = true, intrusive = false)),
        )
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).snooze("dose")

        assertEquals(null, store.snoozed)
        assertEquals(listOf("show-quiet:dose:true", "cancel-realert:dose"), platform.events)
    }

    @Test
    fun `cutoff suppresses meaningless final second alarm`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(occurrence("dose", true, quiet = true, intrusive = true)),
        ).apply { nowEpochSeconds = 3_000L }
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).synchronizeCurrentState()

        assertEquals(listOf("show-quiet:dose:true", "cancel-realert:dose"), platform.events)
    }

    @Test
    fun `snooze at cutoff is inert`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(occurrence("dose", true, quiet = true, intrusive = true)),
        ).apply { nowEpochSeconds = 3_000L }

        ReminderCoordinator(store, FakePlatform()).snooze("dose")

        assertEquals(null, store.snoozed)
        assertEquals(null, store.dismissed)
    }

    @Test
    fun `clearing a quiet card restores it silently without dismissing the dose`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(occurrence("dose", true, quiet = true, intrusive = false)),
        )
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).restoreNotification()

        assertEquals(listOf("show-quiet:dose:false", "cancel-realert:dose"), platform.events)
        assertEquals(null, store.dismissed)
        assertEquals(null, store.snoozed)
    }

    @Test
    fun `clearing an intrusive card restores it silently and preserves repeat alarm`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(
                occurrence("dose", true, quiet = true, intrusive = true)
                    .copy(lastIntrusivePresentationAtEpochSeconds = 1_000L),
            ),
        ).apply { pendingIntrusiveAlertIds = setOf("dose") }
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).restoreNotification()

        assertEquals(
            listOf("show-intrusive:dose:false", "realert:dose@1600"),
            platform.events,
        )
        assertEquals(null, store.dismissed)
        assertEquals(null, store.snoozed)
    }

    @Test
    fun `inactive or completed dose cancels re-alert`() = runBlocking {
        val store = FakeStore(
            occurrences = listOf(occurrence("dose", true, quiet = false, intrusive = false)),
        )
        val platform = FakePlatform()

        ReminderCoordinator(store, platform).synchronizeCurrentState()

        assertEquals(listOf("cancel:dose", "cancel-realert:dose"), platform.events)
    }

    private fun occurrence(
        id: String,
        enabled: Boolean,
        quiet: Boolean,
        intrusive: Boolean,
    ) = AlertOccurrenceState(
        occurrenceId = id,
        medicationId = "medication-$id",
        medicationName = "Medication $id",
        scheduledTimeLabel = "8:00 AM",
        lateDeadlineTimeLabel = "10:00 AM",
        guidance = "Due now",
        enabled = enabled,
        quietVisible = quiet,
        intrusiveVisible = intrusive,
        snoozeAvailable = intrusive,
        activityAccepted = false,
        recordingAvailable = intrusive,
        lateDeadlineEpochSeconds = 3_000L,
        lastIntrusivePresentationAtEpochSeconds = null,
    )

    private class FakeStore(
        var occurrences: List<AlertOccurrenceState>,
        private val evaluations: List<ScheduledEvaluation> = emptyList(),
        private val boundary: Long? = null,
    ) : ReminderStateStore {
        var acknowledgedThrough: Long? = null
        var reconcileCount = 0
        var dismissed: Pair<String, CoreReminderKind>? = null
        var snoozed: Pair<String, Long>? = null
        var activityEvidenceCount = 0
        var nowEpochSeconds = 1_000L
        var pendingIntrusiveAlertIds: Set<String> = emptySet()
        var deviceIsActive = false
        val transitionEvents = mutableListOf<String>()

        override suspend fun reconcile(): ReconcileResult {
            reconcileCount += 1
            transitionEvents += "reconcile"
            return ReconcileResult(boundary)
        }

        override suspend fun dismissReminder(
            occurrenceId: String,
            kind: CoreReminderKind,
        ): RecordDoseResult {
            dismissed = occurrenceId to kind
            return RecordDoseResult(true, null)
        }

        override suspend fun snooze(
            occurrenceId: String,
            untilEpochSeconds: Long,
        ): RecordDoseResult {
            snoozed = occurrenceId to untilEpochSeconds
            return RecordDoseResult(true, null)
        }

        override suspend fun acceptActivityEvidence(): Int {
            activityEvidenceCount += 1
            return 1
        }

        override suspend fun acceptSleepEvidence(confirmedAtEpochSeconds: Long): Boolean {
            transitionEvents += "sleep:$confirmedAtEpochSeconds"
            val accepted = deviceIsActive
            deviceIsActive = false
            return accepted
        }

        override suspend fun returnToInactive(
            occurrenceId: String,
            appIsForeground: Boolean,
        ): RecordDoseResult = RecordDoseResult(true, null)

        override suspend fun reminderState() = ReminderStateSnapshot(
            nowEpochSeconds = nowEpochSeconds,
            occurrences = occurrences,
            futureEvaluations = evaluations,
            pendingEffectCutoffId = 42,
            pendingIntrusiveAlertIds = pendingIntrusiveAlertIds,
            activityMonitoringRequired = occurrences.any {
                it.enabled && it.recordingAvailable && !it.activityAccepted
            },
            sleepMonitoringRequired = deviceIsActive,
        )

        override suspend fun acknowledgePendingEffectsThrough(effectId: Long) {
            acknowledgedThrough = effectId
        }
    }

    private class FakeActivityMonitor : ActivityEvidenceMonitor {
        val requirements = mutableListOf<Boolean>()

        override fun setMonitoringRequired(required: Boolean) {
            requirements += required
        }
    }

    private class FakeSleepMonitor : SleepEvidenceMonitor {
        val requirements = mutableListOf<Boolean>()

        override fun setMonitoringRequired(required: Boolean) {
            requirements += required
        }
    }

    private class FakePlatform(
        private val failOnQuiet: Boolean = false,
    ) : ReminderPlatform {
        val events = mutableListOf<String>()

        override fun createChannels() = Unit

        override fun capabilities() = ReminderCapabilities(
            notificationsAllowed = true,
            exactAlarmsAllowed = true,
        )

        override fun showQuiet(occurrence: AlertOccurrenceState, alert: Boolean) {
            if (failOnQuiet) error("notification failure")
            events += "show-quiet:${occurrence.occurrenceId}:$alert"
        }

        override fun showIntrusive(occurrence: AlertOccurrenceState, alert: Boolean) {
            events += "show-intrusive:${occurrence.occurrenceId}:$alert"
        }

        override fun cancel(occurrenceId: String) {
            events += "cancel:$occurrenceId"
        }

        override fun scheduleIntrusiveRealert(occurrenceId: String, atEpochSeconds: Long) {
            events += "realert:$occurrenceId@$atEpochSeconds"
        }

        override fun cancelIntrusiveRealert(occurrenceId: String) {
            events += "cancel-realert:$occurrenceId"
        }

        override fun scheduleEvaluation(evaluation: ScheduledEvaluation) {
            events += "evaluation:${evaluation.effectId}@${evaluation.atEpochSeconds}"
        }

        override fun scheduleReconciliation(atEpochSeconds: Long) {
            events += "reconcile@$atEpochSeconds"
        }
    }
}
