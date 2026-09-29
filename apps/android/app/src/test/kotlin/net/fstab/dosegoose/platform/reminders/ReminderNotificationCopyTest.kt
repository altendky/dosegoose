package net.fstab.dosegoose.platform.reminders

import net.fstab.dosegoose.data.AlertOccurrenceState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderNotificationCopyTest {
    @Test
    fun `notification has distinct due and cutoff lines rather than a stale countdown`() {
        val occurrence = occurrence(snoozeAvailable = true)

        assertEquals("Due Sep 22, 6:00 AM", reminderDueLine(occurrence))
        assertEquals("Take by Sep 22, 12:00 PM", reminderCutoffLine(occurrence))
    }

    @Test
    fun `snooze action says ten minutes when the full interval fits`() {
        assertEquals("Snooze 10 min", snoozeActionText(occurrence(snoozeAvailable = true)))
    }

    @Test
    fun `snooze action names the cutoff when ten minutes do not fit`() {
        assertEquals(
            "Snooze until Sep 22, 12:00 PM",
            snoozeActionText(occurrence(snoozeAvailable = false)),
        )
    }

    private fun occurrence(snoozeAvailable: Boolean) = AlertOccurrenceState(
        occurrenceId = "dose",
        medicationId = "medication",
        medicationName = "escitalopram",
        scheduledTimeLabel = "Sep 22, 6:00 AM",
        lateDeadlineTimeLabel = "Sep 22, 12:00 PM",
        guidance = "Due now",
        enabled = true,
        quietVisible = true,
        intrusiveVisible = true,
        snoozeAvailable = snoozeAvailable,
        activityAccepted = true,
        recordingAvailable = true,
        lateDeadlineEpochSeconds = 3_000L,
        lastIntrusivePresentationAtEpochSeconds = 1_000L,
    )
}
