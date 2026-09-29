package net.fstab.dosegoose.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReminderPolicyTest(
    private val description: String,
    private val secondsRemaining: Long,
    private val expected: Boolean,
) {
    @Test
    fun `ten minute snooze must fit entirely inside late window`() {
        val scheduledAt = 1_000L
        val lateWindowMinutes = 20
        val deadline = scheduledAt + lateWindowMinutes * 60L

        assertEquals(
            description,
            expected,
            snoozeFitsLateWindow(
                nowEpochSeconds = deadline - secondsRemaining,
                scheduledAtEpochSeconds = scheduledAt,
                lateWindowMinutes = lateWindowMinutes,
            ),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf<Any>("more than ten minutes", 601L, true),
            arrayOf<Any>("exactly ten minutes", 600L, true),
            arrayOf<Any>("one second short", 599L, false),
            arrayOf<Any>("at deadline", 0L, false),
        )
    }
}

@RunWith(Parameterized::class)
class IntrusiveRealertScheduleTest(
    private val description: String,
    private val now: Long,
    private val anchor: Long?,
    private val deadline: Long,
    private val expected: Long?,
) {
    @Test
    fun `next alert follows fixed cadence within late window`() {
        assertEquals(description, expected, nextIntrusiveRealertAt(now, anchor, deadline))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf<Any?>("new presentation", 1_000L, 1_000L, 3_000L, 1_600L),
            arrayOf<Any?>("one second before", 1_599L, 1_000L, 3_000L, 1_600L),
            arrayOf<Any?>("on boundary", 1_600L, 1_000L, 3_000L, 2_200L),
            arrayOf<Any?>("missed intervals", 2_900L, 1_000L, 4_000L, 3_400L),
            arrayOf<Any?>("at deadline", 3_000L, 1_000L, 3_000L, null),
            arrayOf<Any?>("next beyond deadline", 1_500L, 1_000L, 1_599L, null),
            arrayOf<Any?>("at deadline suppressed", 1_599L, 1_000L, 1_600L, null),
            arrayOf<Any?>("no presentation", 1_000L, null, 3_000L, null),
            arrayOf<Any?>("future presentation", 1_000L, 1_001L, 3_000L, null),
        )
    }
}

@RunWith(Parameterized::class)
class IntrusiveRealertValidationTest(
    private val description: String,
    private val requestedAt: Long,
    private val now: Long,
    private val anchor: Long?,
    private val deadline: Long,
    private val expected: Boolean,
) {
    @Test
    fun `receiver accepts only due current cadence`() {
        assertEquals(
            description,
            expected,
            intrusiveRealertIsDue(requestedAt, now, anchor, deadline),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf<Any?>("exactly due", 1_600L, 1_600L, 1_000L, 3_000L, true),
            arrayOf<Any?>("delivered late", 1_600L, 1_700L, 1_000L, 3_000L, true),
            arrayOf<Any?>("early delivery", 1_600L, 1_599L, 1_000L, 3_000L, false),
            arrayOf<Any?>("stale after snooze", 1_600L, 1_600L, 1_300L, 3_000L, false),
            arrayOf<Any?>("wrong cadence", 1_601L, 1_601L, 1_000L, 3_000L, false),
            arrayOf<Any?>("past deadline", 2_200L, 2_200L, 1_000L, 2_000L, false),
            arrayOf<Any?>("at deadline", 1_600L, 1_600L, 1_000L, 1_600L, false),
            arrayOf<Any?>("missing anchor", 1_600L, 1_600L, null, 3_000L, false),
        )
    }
}
