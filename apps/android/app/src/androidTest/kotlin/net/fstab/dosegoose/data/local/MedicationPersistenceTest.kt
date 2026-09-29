package net.fstab.dosegoose.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.fstab.dosegoose.app.AppState
import net.fstab.dosegoose.app.Medication
import net.fstab.dosegoose.app.OnceDailySchedule
import net.fstab.dosegoose.data.RoomMedicationRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class MedicationPersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: DoseGooseDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "persistence-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun initializationSeedsTheDemoMedicationsExactlyOnce() = runBlocking {
        val repository = repository()
        repository.initialize()

        val edited = repository.medications.first().first().copy(name = "Edited locally")
        repository.upsert(edited, 100)
        repository.initialize()

        val medications = repository.medications.first()
        assertEquals(AppState.initial().medications.size, medications.size)
        assertEquals("Edited locally", medications.first().name)
    }

    @Test
    fun medicationScheduleAndEnablementSurviveDatabaseReopen() = runBlocking {
        var repository = repository()
        repository.initialize()
        val original = repository.medications.first().first()
        repository.upsert(
            original.copy(
                name = "Vitamin D3",
                schedule = original.schedule.copy(hour = 9, minute = 15, lateWindowMinutes = 240),
            ),
            200,
        )
        repository.setEnabled(original.id, false, 300)

        database?.close()
        database = null
        repository = repository()

        val restored = repository.medications.first().first { it.id == original.id }
        assertEquals("Vitamin D3", restored.name)
        assertEquals(original.schedule.id, restored.schedule.id)
        assertEquals(9, restored.schedule.hour)
        assertEquals(15, restored.schedule.minute)
        assertEquals(240, restored.schedule.lateWindowMinutes)
        assertFalse(restored.enabled)
    }

    @Test
    fun userCreatedMedicationIsRestoredFromDurableState() = runBlocking {
        val repository = repository()
        repository.initialize()
        val medication = Medication(
            id = "4d97e265-a5ad-4d95-a599-e87f0033d241",
            name = "User medication",
            schedule = OnceDailySchedule(
                id = "ce19d296-8853-4e39-a55b-bb1ae627219a",
                hour = 6,
                minute = 30,
                lateWindowMinutes = 60,
            ),
            enabled = true,
        )

        repository.upsert(medication, 400)

        val restored = repository.medications.first().first { it.id == medication.id }
        assertEquals(medication, restored)
    }

    private fun repository(): RoomMedicationRepository {
        val opened = Room.databaseBuilder(context, DoseGooseDatabase::class.java, databaseName)
            .build()
        database = opened
        return RoomMedicationRepository(opened.medicationDao())
    }
}
