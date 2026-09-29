package net.fstab.dosegoose.data

import java.time.LocalDate
import net.fstab.dosegoose.corebridge.CoreRecordingAvailability
import net.fstab.dosegoose.data.local.DoseOccurrenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TodayVisibilityPolicyTest(
    private val description: String,
    private val priorAvailability: CoreRecordingAvailability,
    private val todayScheduleOffsetSeconds: Long,
    private val todayEarlyWindowMinutes: Int,
    private val priorExpected: Boolean,
) {
    @Test
    fun `prior daily occurrence remains only until the new occurrence is due`() {
        val context = TodayDisplayContext(DATE, NOW)
        val prior = occurrence(
            id = "prior",
            scheduleId = "schedule",
            localDate = DATE.minusDays(1),
            scheduledAtEpochSeconds = NOW - 3_600,
            availability = priorAvailability,
        )
        val current = occurrence(
            id = "current",
            scheduleId = "schedule",
            localDate = DATE,
            scheduledAtEpochSeconds = NOW + todayScheduleOffsetSeconds,
            availability = CoreRecordingAvailability.NotYetDue,
            earlyWindowMinutes = todayEarlyWindowMinutes,
        )

        val selected = selectTodayOccurrences(listOf(prior, current), context).map { it.id }

        assertEquals(description, priorExpected, "prior" in selected)
        assertTrue("current" in selected)
    }

    companion object {
        private val DATE = LocalDate.of(2026, 9, 21)
        private const val NOW = 1_000_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf<Any>(
                "recordable prior before today's schedule",
                CoreRecordingAvailability.Available,
                1L,
                0,
                true,
            ),
            arrayOf<Any>(
                "recordable prior exactly when today's dose is due",
                CoreRecordingAvailability.Available,
                0L,
                0,
                false,
            ),
            arrayOf<Any>(
                "recordable prior after today's dose became due",
                CoreRecordingAvailability.Available,
                -1L,
                0,
                false,
            ),
            arrayOf<Any>(
                "expired prior before today's schedule",
                CoreRecordingAvailability.LateWindowElapsed,
                1L,
                0,
                false,
            ),
            arrayOf<Any>(
                "recorded prior before today's schedule",
                CoreRecordingAvailability.AlreadyRecorded,
                1L,
                0,
                false,
            ),
            arrayOf<Any>(
                "not-yet-due prior is invalid for carryover",
                CoreRecordingAvailability.NotYetDue,
                1L,
                0,
                false,
            ),
            arrayOf<Any>(
                "recordable prior leaves when today's early window opens",
                CoreRecordingAvailability.Available,
                3_600L,
                60,
                false,
            ),
            arrayOf<Any>(
                "recordable prior remains one minute before today's early window",
                CoreRecordingAvailability.Available,
                3_600L,
                59,
                true,
            ),
        )
    }
}

class TodayVisibilityScheduleIsolationTest {
    @Test
    fun `a due occurrence hides only the prior occurrence from its own schedule`() {
        val date = LocalDate.of(2026, 9, 21)
        val context = TodayDisplayContext(date, 1_000_000L)
        val selected = selectTodayOccurrences(
            listOf(
                occurrence("prior-a", "schedule-a", date.minusDays(1), 900_000L),
                occurrence("prior-b", "schedule-b", date.minusDays(1), 900_000L),
                occurrence("current-a", "schedule-a", date, 1_000_000L),
                occurrence("current-b", "schedule-b", date, 1_000_001L),
            ),
            context,
        ).map { it.id }

        assertEquals(listOf("prior-b", "current-a", "current-b"), selected)
    }

    @Test
    fun `tomorrow replaces today when a cross-midnight early window opens`() {
        val date = LocalDate.of(2026, 9, 21)
        val context = TodayDisplayContext(date, 1_000_000L)
        val selected = selectTodayOccurrences(
            listOf(
                occurrence("current", "schedule", date, 900_000L),
                occurrence(
                    "next",
                    "schedule",
                    date.plusDays(1),
                    1_003_600L,
                    earlyWindowMinutes = 60,
                ),
            ),
            context,
        ).map { it.id }

        assertEquals(listOf("next"), selected)
    }
}

private fun occurrence(
    id: String,
    scheduleId: String,
    localDate: LocalDate,
    scheduledAtEpochSeconds: Long,
    availability: CoreRecordingAvailability = CoreRecordingAvailability.Available,
    earlyWindowMinutes: Int = 0,
) = DoseOccurrenceEntity(
    id = id,
    medicationId = "medication-$scheduleId",
    scheduleId = scheduleId,
    medicationName = "Medication",
    localDate = localDate.toString(),
    scheduledAtEpochSeconds = scheduledAtEpochSeconds,
    zoneId = "UTC",
    utcOffsetSeconds = 0,
    lateWindowMinutes = 120,
    earlyWindowMinutes = earlyWindowMinutes,
    snapshotVersion = 1,
    coreSnapshotJson = "{}",
    guidance = "Upcoming",
    recordingAvailability = availability.name,
    dueInSeconds = null,
    lateBySeconds = null,
    remainingSeconds = null,
    intakeAtEpochSeconds = null,
    updatedAtEpochSeconds = scheduledAtEpochSeconds,
)
