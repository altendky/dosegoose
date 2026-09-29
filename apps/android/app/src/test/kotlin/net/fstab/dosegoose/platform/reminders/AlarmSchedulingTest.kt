package net.fstab.dosegoose.platform.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSchedulingTest {
    @Test
    fun `exact scheduling is used when allowed`() {
        val calls = mutableListOf<String>()

        scheduleAlarm(true, { calls += "exact" }, { calls += "inexact" })

        assertEquals(listOf("exact"), calls)
    }

    @Test
    fun `inexact scheduling is used when exact access is unavailable`() {
        val calls = mutableListOf<String>()

        scheduleAlarm(false, { calls += "exact" }, { calls += "inexact" })

        assertEquals(listOf("inexact"), calls)
    }

    @Test
    fun `revocation during exact scheduling immediately falls back`() {
        val calls = mutableListOf<String>()

        scheduleAlarm(
            exactAlarmsAllowed = true,
            exact = {
                calls += "exact"
                throw SecurityException("revoked")
            },
            inexact = { calls += "inexact" },
        )

        assertEquals(listOf("exact", "inexact"), calls)
    }
}
