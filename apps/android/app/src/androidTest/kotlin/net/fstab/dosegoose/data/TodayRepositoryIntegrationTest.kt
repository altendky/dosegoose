package net.fstab.dosegoose.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.fstab.dosegoose.app.DemoIds
import net.fstab.dosegoose.app.DoseStatus
import net.fstab.dosegoose.app.Medication
import net.fstab.dosegoose.app.OnceDailySchedule
import net.fstab.dosegoose.corebridge.CoreRejection
import net.fstab.dosegoose.corebridge.UniFFIDoseCoreBridge
import net.fstab.dosegoose.data.local.DoseGooseDatabase
import net.fstab.dosegoose.platform.ForegroundAuthorizationFacts
import net.fstab.dosegoose.platform.ForegroundAuthorizationProvider
import net.fstab.dosegoose.platform.SystemPlatformTimeSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayRepositoryIntegrationTest {
    private lateinit var database: DoseGooseDatabase
    private lateinit var repository: TodayRepository
    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "today-repository-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, DoseGooseDatabase::class.java, databaseName).build()
        RoomMedicationRepository(database.medicationDao()).initialize()
        repository = createRepository()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun reconcileAndBackdatedRecordingUseThePortableCoreAndPersistItsEffects() = runBlocking {
        repository.reconcile()
        val initial = repository.todayDoses.first()
        val vitaminD = initial.first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }

        assertEquals(3, initial.size)
        assertEquals(DoseStatus.Needed, vitaminD.status)
        assertTrue(vitaminD.guidance.contains("30m late"))

        val result = repository.record(
            occurrenceId = vitaminD.occurrenceId,
            intakeAtEpochSeconds = Instant.parse("2026-09-20T08:15:00Z").epochSecond,
            appIsForeground = true,
        )

        assertTrue(result.accepted)
        assertNull(result.rejection)
        val recorded = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
        assertEquals(DoseStatus.Taken, recorded.status)
        assertEquals(Instant.parse("2026-09-20T08:15:00Z").epochSecond, recorded.intakeAtEpochSeconds)
        assertEquals(
            listOf(
                "ScheduleEvaluation",
                "ScheduleEvaluation",
                "PresentQuiet",
                "ScheduleEvaluation",
                "CancelQuiet",
            ),
            database.todayDao().pendingEffects()
                .filter { it.occurrenceId == vitaminD.occurrenceId }
                .map { it.kind },
        )
    }

    @Test
    fun backgroundRecordIsRejectedWithoutMarkingMedicationTaken() = runBlocking {
        repository.reconcile()
        val vitaminD = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }

        val result = repository.record(
            occurrenceId = vitaminD.occurrenceId,
            intakeAtEpochSeconds = null,
            appIsForeground = false,
        )

        assertFalse(result.accepted)
        assertEquals(CoreRejection.ForegroundAuthorizationRequired, result.rejection)
        assertEquals(
            DoseStatus.Needed,
            repository.todayDoses.first()
                .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
                .status,
        )
    }

    @Test
    fun recordedDoseSurvivesDatabaseAndRepositoryRestart() = runBlocking {
        repository.reconcile()
        val occurrenceId = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
            .occurrenceId
        assertTrue(repository.record(occurrenceId, null, appIsForeground = true).accepted)

        database.close()
        database = Room.databaseBuilder(context, DoseGooseDatabase::class.java, databaseName).build()
        repository = createRepository()
        repository.reconcile()

        val restored = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
        assertEquals(DoseStatus.Taken, restored.status)
        assertEquals(Instant.parse("2026-09-20T08:30:00Z").epochSecond, restored.intakeAtEpochSeconds)
    }

    @Test
    fun explicitOutOfWindowRecordPersistsAndOrdinaryRecordStillRejects() = runBlocking {
        repository.reconcile()
        val occurrenceId = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
            .occurrenceId
        val selectedAt = Instant.parse("2026-09-20T05:30:00Z").epochSecond

        val ordinary = repository.record(occurrenceId, selectedAt, appIsForeground = true)
        assertFalse(ordinary.accepted)
        assertEquals(CoreRejection.IntakeBeforeAvailableWindow, ordinary.rejection)

        val override = repository.record(
            occurrenceId,
            selectedAt,
            appIsForeground = true,
            outsideWindow = true,
        )
        assertTrue(override.accepted)
        assertEquals(3, database.todayDao().occurrence(occurrenceId)?.snapshotVersion)
        assertTrue(database.todayDao().pendingEffects().any {
            it.occurrenceId == occurrenceId && it.kind == "CancelQuiet"
        })

        database.close()
        database = Room.databaseBuilder(context, DoseGooseDatabase::class.java, databaseName).build()
        repository = createRepository()
        repository.reconcile()

        val restored = repository.todayDoses.first().first { it.occurrenceId == occurrenceId }
        assertEquals(DoseStatus.Taken, restored.status)
        assertEquals(selectedAt, restored.intakeAtEpochSeconds)
    }

    @Test
    fun medicationCreatedAfterItsScheduledTimeStartsWithTheNextOccurrence() = runBlocking {
        val medication = Medication(
            id = "4d97e265-a5ad-4d95-a599-e87f0033d241",
            name = "New morning medication",
            schedule = OnceDailySchedule(
                id = "ce19d296-8853-4e39-a55b-bb1ae627219a",
                hour = 8,
                minute = 0,
                lateWindowMinutes = 120,
            ),
            enabled = true,
        )
        RoomMedicationRepository(database.medicationDao()).upsert(
            medication,
            Instant.parse("2026-09-20T08:30:00Z").epochSecond,
        )

        repository.reconcile()

        assertTrue(repository.todayDoses.first().none { it.medicationId == medication.id })
    }

    @Test
    fun activityEvidenceActivatesOnlyDueInactiveOccurrencesAndIsIdempotent() = runBlocking {
        repository.reconcile()

        assertEquals(1, repository.acceptActivityEvidence())
        assertEquals(0, repository.acceptActivityEvidence())

        val doses = repository.todayDoses.first()
        assertTrue(doses.first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }.activityAccepted)
        assertFalse(doses.first { it.medicationId == DemoIds.OMEGA_3_MEDICATION }.activityAccepted)
        assertFalse(doses.first { it.medicationId == DemoIds.MAGNESIUM_MEDICATION }.activityAccepted)
        assertEquals(
            1,
            database.todayDao().pendingEffects().count { it.kind == "PresentIntrusively" },
        )
    }

    @Test
    fun earlyAvailableOccurrenceAcceptsActivityAndPresentsIntrusively() = runBlocking {
        val medication = Medication(
            id = "48a08ae5-434f-4fdf-bd0b-c3359fb5b395",
            name = "Noon medication",
            schedule = OnceDailySchedule(
                id = "86a1dc43-f216-4a1a-8a52-d59a31a53b85",
                hour = 12,
                minute = 0,
                lateWindowMinutes = 120,
                earlyWindowMinutes = 120,
            ),
            enabled = true,
        )
        RoomMedicationRepository(database.medicationDao()).upsert(
            medication,
            Instant.parse("2026-09-20T00:00:00Z").epochSecond,
        )
        repository = createRepository(Instant.parse("2026-09-20T10:00:00Z"))
        repository.reconcile()

        val early = repository.todayDoses.first().first { it.medicationId == medication.id }
        assertEquals(DoseStatus.Upcoming, early.status)
        assertTrue(early.guidance.contains("Available to take"))
        assertEquals(net.fstab.dosegoose.app.RecordingAvailability.Available, early.recordingAvailability)

        repository.acceptActivityEvidence()

        val activated = repository.todayDoses.first().first { it.medicationId == medication.id }
        assertTrue(activated.activityAccepted)
        assertEquals(
            1,
            database.todayDao().pendingEffects().count {
                it.occurrenceId == early.occurrenceId && it.kind == "PresentIntrusively"
            },
        )
    }

    @Test
    fun acceptedActivitySurvivesRestartAndRequiresAuthorizedForegroundReset() = runBlocking {
        repository.reconcile()
        assertEquals(1, repository.acceptActivityEvidence())
        val occurrenceId = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
            .occurrenceId

        database.close()
        database = Room.databaseBuilder(context, DoseGooseDatabase::class.java, databaseName).build()
        repository = createRepository()
        repository.reconcile()
        assertTrue(
            repository.todayDoses.first().first { it.occurrenceId == occurrenceId }.activityAccepted,
        )

        val unauthorized = repository.returnToInactive(occurrenceId, appIsForeground = false)
        assertFalse(checkNotNull(unauthorized).accepted)
        assertEquals(CoreRejection.ForegroundAuthorizationRequired, unauthorized.rejection)
        assertTrue(
            repository.todayDoses.first().first { it.occurrenceId == occurrenceId }.activityAccepted,
        )

        val authorized = repository.returnToInactive(occurrenceId, appIsForeground = true)
        assertTrue(checkNotNull(authorized).accepted)
        assertFalse(
            repository.todayDoses.first().first { it.occurrenceId == occurrenceId }.activityAccepted,
        )
    }

    @Test
    fun acceptedDeviceActivityActivatesTheNextDaysDoseAtItsEarlyBoundary() = runBlocking {
        repository.reconcile()
        assertEquals(1, repository.acceptActivityEvidence())

        repository = createRepository(Instant.parse("2026-09-21T06:00:00Z"))
        repository.reconcile()

        val nextDose = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
        assertEquals(DoseStatus.Upcoming, nextDose.status)
        assertTrue(nextDose.activityAccepted)
        assertTrue(
            database.todayDao().pendingEffects().any {
                it.occurrenceId == nextDose.occurrenceId && it.kind == "PresentIntrusively"
            },
        )
    }

    @Test
    fun confirmedSleepClearsDeviceLatchWithoutSilencingAnActiveOccurrence() = runBlocking {
        repository = createRepository(Instant.parse("2026-09-20T06:00:00Z"))
        repository.reconcile()
        assertEquals(1, repository.acceptActivityEvidence())
        val activeDose = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
        repository = createRepository()

        val accepted = repository.acceptSleepEvidence(
            Instant.parse("2026-09-20T08:29:00Z").epochSecond,
        )

        assertTrue(accepted)
        assertTrue(
            repository.todayDoses.first()
                .first { it.occurrenceId == activeDose.occurrenceId }
                .activityAccepted,
        )
        assertFalse(repository.reminderState().sleepMonitoringRequired)
    }

    @Test
    fun nextDaysDoseDoesNotInheritActivityAfterConfirmedSleep() = runBlocking {
        repository = createRepository(Instant.parse("2026-09-20T06:00:00Z"))
        repository.reconcile()
        assertEquals(1, repository.acceptActivityEvidence())
        repository = createRepository()
        assertTrue(
            repository.acceptSleepEvidence(
                Instant.parse("2026-09-20T08:29:00Z").epochSecond,
            ),
        )

        repository = createRepository(Instant.parse("2026-09-21T06:00:00Z"))
        repository.reconcile()

        val nextDose = repository.todayDoses.first()
            .first { it.medicationId == DemoIds.VITAMIN_D_MEDICATION }
        assertFalse(nextDose.activityAccepted)
        assertFalse(
            database.todayDao().pendingEffects().any {
                it.occurrenceId == nextDose.occurrenceId && it.kind == "PresentIntrusively"
            },
        )
    }

    @Test
    fun futureSleepEvidenceCannotClearDeviceActivity() = runBlocking {
        repository.reconcile()
        assertEquals(1, repository.acceptActivityEvidence())

        val accepted = repository.acceptSleepEvidence(
            Instant.parse("2026-09-20T08:31:00Z").epochSecond,
        )

        assertFalse(accepted)
        assertTrue(repository.reminderState().sleepMonitoringRequired)
    }

    @Test
    fun delayedSleepEvidenceCannotClearNewerActivity() = runBlocking {
        repository = createRepository(Instant.parse("2026-09-20T06:00:00Z"))
        repository.reconcile()
        assertEquals(1, repository.acceptActivityEvidence())

        repository = createRepository(Instant.parse("2026-09-20T08:30:00Z"))
        assertEquals(0, repository.acceptActivityEvidence())
        repository = createRepository(Instant.parse("2026-09-20T09:00:00Z"))

        assertFalse(
            repository.acceptSleepEvidence(
                Instant.parse("2026-09-20T08:00:00Z").epochSecond,
            ),
        )
        assertTrue(repository.reminderState().sleepMonitoringRequired)
    }

    @Test
    fun clockRollbackRebasesActiveLatchSoNewSleepCanEventuallyClearIt() = runBlocking {
        repository.reconcile()
        assertEquals(1, repository.acceptActivityEvidence())

        repository = createRepository(Instant.parse("2026-09-20T07:00:00Z"))
        repository.rebaseActivityEvidenceForClockChange()
        repository = createRepository(Instant.parse("2026-09-20T07:31:00Z"))

        assertTrue(
            repository.acceptSleepEvidence(
                Instant.parse("2026-09-20T07:30:00Z").epochSecond,
            ),
        )
        assertFalse(repository.reminderState().sleepMonitoringRequired)
    }

    @Test
    fun priorDoseCarriesAcrossMidnightOnlyUntilCurrentDoseBecomesDue() = runBlocking {
        val medication = Medication(
            id = "edca8f74-65f2-49e2-b92f-c32353b7631d",
            name = "Cross-midnight medication",
            schedule = OnceDailySchedule(
                id = "dca4e45a-7167-4e2a-a8f6-400b6d5c4661",
                hour = 23,
                minute = 30,
                lateWindowMinutes = 120,
            ),
            enabled = true,
        )
        RoomMedicationRepository(database.medicationDao()).upsert(
            medication,
            Instant.parse("2026-09-20T00:00:00Z").epochSecond,
        )
        repository = createRepository(Instant.parse("2026-09-21T00:30:00Z"))

        repository.reconcile()

        val carried = repository.todayDoses.first().filter { it.medicationId == medication.id }
        assertEquals(2, carried.size)
        assertEquals(listOf(DoseStatus.Needed, DoseStatus.Upcoming), carried.map { it.status })

        repository = createRepository(Instant.parse("2026-09-21T23:30:00Z"))
        repository.reconcile()

        val afterCurrentDue = repository.todayDoses.first()
            .filter { it.medicationId == medication.id }
        assertEquals(1, afterCurrentDue.size)
        assertEquals(DoseStatus.Needed, afterCurrentDue.single().status)
    }

    private fun createRepository(
        instant: Instant = Instant.parse("2026-09-20T08:30:00Z"),
    ): TodayRepository = TodayRepository(
        medicationDao = database.medicationDao(),
        todayDao = database.todayDao(),
        coreBridge = UniFFIDoseCoreBridge(),
        timeSource = SystemPlatformTimeSource(
            Clock.fixed(instant, ZoneId.of("UTC")),
        ),
        authorizationProvider = UnlockedAuthorization,
        newDeviceId = { "da4f2511-9c19-4b53-b5a0-7417efb06298" },
    )

    private object UnlockedAuthorization : ForegroundAuthorizationProvider {
        override fun capture(appIsForeground: Boolean): ForegroundAuthorizationFacts =
            ForegroundAuthorizationFacts(
                appIsForeground = appIsForeground,
                deviceIsUnlocked = true,
            )
    }
}
