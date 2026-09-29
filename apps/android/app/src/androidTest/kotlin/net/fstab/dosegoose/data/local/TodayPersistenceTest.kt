package net.fstab.dosegoose.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayPersistenceTest {
    private lateinit var database: DoseGooseDatabase
    private lateinit var medicationDao: MedicationDao
    private lateinit var todayDao: TodayDao

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DoseGooseDatabase::class.java).build()
        medicationDao = database.medicationDao()
        todayDao = database.todayDao()
        medicationDao.upsertMedicationEntity(
            MedicationEntity("medication", "Vitamin D", enabled = true, sortOrder = 0),
        )
        medicationDao.upsertScheduleEntity(
            OnceDailyScheduleEntity("schedule", "medication", 8, 0, 120),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun occurrenceAndRequestedEffectsArePersistedAtomicallyInOrder() = runBlocking {
        assertTrue(
            todayDao.insertOccurrenceWithEffects(
                occurrence(),
                listOf(effect("PresentQuiet"), effect("ScheduleEvaluation")),
            ),
        )
        todayDao.updateOccurrenceWithEffects(
            occurrence().copy(guidance = "Recorded", coreSnapshotJson = "snapshot-2"),
            listOf(effect("CancelQuiet"), effect("CancelIntrusive")),
        )

        val restored = todayDao.occurrence("occurrence")
        val effects = todayDao.pendingEffects()
        assertEquals("snapshot-2", restored?.coreSnapshotJson)
        assertEquals("Recorded", restored?.guidance)
        assertEquals(listOf(0, 1, 2, 3), effects.map { it.sequenceNumber })
        assertEquals(
            listOf("PresentQuiet", "ScheduleEvaluation", "CancelQuiet", "CancelIntrusive"),
            effects.map { it.kind },
        )
    }

    @Test
    fun scheduleAndLocalDateUniquelyIdentifyAnOccurrence() = runBlocking {
        assertTrue(todayDao.insertOccurrenceWithEffects(occurrence(), emptyList()))

        val inserted = todayDao.insertOccurrenceWithEffects(
            occurrence().copy(id = "different-id"),
            emptyList(),
        )

        assertFalse(inserted)
        assertEquals("occurrence", todayDao.occurrence("schedule", "2026-09-20")?.id)
    }

    @Test
    fun disabledMedicationIsHiddenWithoutDeletingOccurrenceHistory() = runBlocking {
        todayDao.insertOccurrenceWithEffects(occurrence(), emptyList())
        medicationDao.updateEnabled("medication", false)

        assertTrue(todayDao.observeEnabledOccurrences().first().isEmpty())
        assertEquals("occurrence", todayDao.occurrence("occurrence")?.id)
    }

    @Test
    fun pendingEffectsAreAcknowledgedIdempotentlyInDurableOrder() = runBlocking {
        todayDao.insertOccurrenceWithEffects(
            occurrence(),
            listOf(effect("PresentQuiet"), effect("ScheduleEvaluation")),
        )
        val pending = todayDao.unhandledEffects()

        assertEquals(listOf("PresentQuiet", "ScheduleEvaluation"), pending.map { it.kind })
        assertEquals(1, todayDao.markEffectHandled(pending.first().id))
        assertEquals(0, todayDao.markEffectHandled(pending.first().id))
        assertEquals(listOf("ScheduleEvaluation"), todayDao.unhandledEffects().map { it.kind })
    }

    @Test
    fun effectCutoffDoesNotAcknowledgeWorkCreatedAfterSnapshot() = runBlocking {
        todayDao.insertOccurrenceWithEffects(
            occurrence(),
            listOf(effect("PresentQuiet"), effect("ScheduleEvaluation")),
        )
        val cutoff = requireNotNull(todayDao.maximumUnhandledEffectId())
        todayDao.updateOccurrenceWithEffects(
            occurrence().copy(guidance = "Recorded", coreSnapshotJson = "snapshot-2"),
            listOf(effect("CancelQuiet")),
        )

        assertEquals(2, todayDao.markEffectsHandledThrough(cutoff))
        assertEquals(listOf("CancelQuiet"), todayDao.unhandledEffects().map { it.kind })
    }

    private fun occurrence(): DoseOccurrenceEntity = DoseOccurrenceEntity(
        id = "occurrence",
        medicationId = "medication",
        scheduleId = "schedule",
        medicationName = "Vitamin D",
        localDate = "2026-09-20",
        scheduledAtEpochSeconds = 1_000,
        zoneId = "UTC",
        utcOffsetSeconds = 0,
        lateWindowMinutes = 120,
        snapshotVersion = 1,
        coreSnapshotJson = "snapshot-1",
        guidance = "Due",
        recordingAvailability = "Available",
        dueInSeconds = null,
        lateBySeconds = null,
        remainingSeconds = null,
        intakeAtEpochSeconds = null,
        updatedAtEpochSeconds = 1_000,
    )

    private fun effect(kind: String): PendingEffectEntity = PendingEffectEntity(
        occurrenceId = "occurrence",
        sequenceNumber = 0,
        kind = kind,
        deviceId = "device",
        atEpochSeconds = null,
        requestedAtEpochSeconds = 1_000,
    )
}
