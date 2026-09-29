package net.fstab.dosegoose.data.local

import net.fstab.dosegoose.app.DemoIds
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MedicationWithScheduleMappingTest(
    private val medicationId: String,
) {
    @Test
    fun `mapping preserves persisted medication and schedule fields`() {
        val stored = MedicationWithSchedule(
            medication = MedicationEntity(
                id = medicationId,
                name = "Test medication",
                enabled = false,
                sortOrder = 4,
            ),
            schedule = OnceDailyScheduleEntity(
                id = "schedule-id",
                medicationId = medicationId,
                hour = 17,
                minute = 45,
                lateWindowMinutes = 90,
            ),
        )

        val result = stored.toDomain()

        assertEquals(medicationId, result.id)
        assertEquals("Test medication", result.name)
        assertEquals(false, result.enabled)
        assertEquals("schedule-id", result.schedule.id)
        assertEquals(17, result.schedule.hour)
        assertEquals(45, result.schedule.minute)
        assertEquals(90, result.schedule.lateWindowMinutes)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String>> = listOf(
            arrayOf(DemoIds.VITAMIN_D_MEDICATION),
            arrayOf(DemoIds.OMEGA_3_MEDICATION),
            arrayOf(DemoIds.MAGNESIUM_MEDICATION),
            arrayOf("user-created-medication"),
        )
    }
}
