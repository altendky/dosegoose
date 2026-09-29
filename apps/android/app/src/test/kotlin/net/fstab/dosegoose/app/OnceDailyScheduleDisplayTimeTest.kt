package net.fstab.dosegoose.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OnceDailyScheduleDisplayTimeTest(
    private val hour: Int,
    private val minute: Int,
    private val expected: String,
) {
    @Test
    fun `formats time without depending on the process locale`() {
        val schedule = OnceDailySchedule(
            id = "schedule",
            hour = hour,
            minute = minute,
            lateWindowMinutes = 120,
        )

        assertEquals(expected, schedule.displayTime)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}:{1} becomes {2}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(0, 0, "12:00 AM"),
            arrayOf(0, 5, "12:05 AM"),
            arrayOf(8, 9, "8:09 AM"),
            arrayOf(11, 59, "11:59 AM"),
            arrayOf(12, 0, "12:00 PM"),
            arrayOf(13, 30, "1:30 PM"),
            arrayOf(23, 59, "11:59 PM"),
        )
    }
}
