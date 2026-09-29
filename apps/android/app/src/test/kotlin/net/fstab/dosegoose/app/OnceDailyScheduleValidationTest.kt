package net.fstab.dosegoose.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnceDailyScheduleValidationTest {
    @Test
    fun `accepts the lower bounds`() {
        assertEquals(schedule(0, 0, 0), schedule(0, 0, 0))
    }

    @Test
    fun `accepts the upper bounds`() {
        assertEquals(schedule(23, 59, 1_440, 1_440), schedule(23, 59, 1_440, 1_440))
    }

    @Test
    fun `rejects an hour below zero`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(-1, 0, 120) }
    }

    @Test
    fun `rejects an hour above twenty three`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(24, 0, 120) }
    }

    @Test
    fun `rejects a minute below zero`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(8, -1, 120) }
    }

    @Test
    fun `rejects a minute above fifty nine`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(8, 60, 120) }
    }

    @Test
    fun `rejects a negative late window`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(8, 0, -1) }
    }

    @Test
    fun `rejects a late window longer than one day`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(8, 0, 1_441) }
    }

    @Test
    fun `rejects a negative early window`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(8, 0, 120, -1) }
    }

    @Test
    fun `rejects an early window longer than one day`() {
        assertThrows(IllegalArgumentException::class.java) { schedule(8, 0, 120, 1_441) }
    }

    private fun schedule(
        hour: Int,
        minute: Int,
        lateWindowMinutes: Int,
        earlyWindowMinutes: Int = 0,
    ) = OnceDailySchedule(
        id = "schedule",
        hour = hour,
        minute = minute,
        lateWindowMinutes = lateWindowMinutes,
        earlyWindowMinutes = earlyWindowMinutes,
    )
}
