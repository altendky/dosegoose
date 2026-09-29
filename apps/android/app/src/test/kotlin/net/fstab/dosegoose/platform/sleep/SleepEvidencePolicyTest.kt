package net.fstab.dosegoose.platform.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class SleepEvidencePolicyTest {
    @Test
    fun `exactly two hours of sustained high confidence confirms sleep`() {
        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            observationsAtMinutes(0, 30, 60, 90, 120),
            OvernightSleepSettings(),
        )

        assertEquals(minutes(120), result.confirmedAtEpochMillis)
        assertEquals(SleepPolicyState(lastProcessedAtEpochMillis = minutes(120)), result.state)
    }

    @Test
    fun `one millisecond short of two hours does not confirm`() {
        val observations = observationsAtMinutes(0, 30, 60, 90) +
            asleep(minutes(120) - 1)

        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            observations,
            OvernightSleepSettings(),
        )

        assertNull(result.confirmedAtEpochMillis)
        assertEquals(0L, result.state.candidateStartedAtEpochMillis)
    }

    @Test
    fun `strong awake evidence restarts the two hour clock`() {
        val observations = observationsAtMinutes(0, 30) + listOf(
            asleep(minutes(60), confidence = 10),
            asleep(minutes(70)),
            asleep(minutes(100)),
            asleep(minutes(130)),
            asleep(minutes(160)),
            asleep(minutes(190)),
        )

        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            observations,
            OvernightSleepSettings(),
        )

        assertEquals(minutes(190), result.confirmedAtEpochMillis)
    }

    @Test
    fun `brief ambiguous evidence does not break an otherwise sustained series`() {
        val observations = listOf(
            asleep(minutes(0)),
            asleep(minutes(20), confidence = 50),
            asleep(minutes(30)),
            asleep(minutes(60)),
            asleep(minutes(90)),
            asleep(minutes(120)),
        )

        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            observations,
            OvernightSleepSettings(),
        )

        assertEquals(minutes(120), result.confirmedAtEpochMillis)
    }

    @Test
    fun `long ambiguous gap discards the candidate`() {
        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            listOf(asleep(0), asleep(minutes(31), confidence = 50)),
            OvernightSleepSettings(),
        )

        assertNull(result.confirmedAtEpochMillis)
        assertNull(result.state.candidateStartedAtEpochMillis)
        assertNull(result.state.lastQualifyingAtEpochMillis)
    }

    @Test
    fun `long gap between qualifying classifications starts a new candidate`() {
        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            listOf(asleep(0), asleep(minutes(31))),
            OvernightSleepSettings(),
        )

        assertEquals(minutes(31), result.state.candidateStartedAtEpochMillis)
        assertEquals(minutes(31), result.state.lastQualifyingAtEpochMillis)
    }

    @Test
    fun `daytime classifications are ignored so naps do not reset activity`() {
        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            observationsAtMinutes(0, 30, 60, 90, 120, localMinuteOfDay = 13 * 60),
            OvernightSleepSettings(),
        )

        assertNull(result.confirmedAtEpochMillis)
        assertNull(result.state.candidateStartedAtEpochMillis)
    }

    @Test
    fun `stale and duplicate classifications do not change a newer candidate`() {
        val initial = SleepPolicyState(
            candidateStartedAtEpochMillis = minutes(60),
            lastQualifyingAtEpochMillis = minutes(90),
            lastProcessedAtEpochMillis = minutes(90),
        )

        val result = evaluateSleepEvidence(
            initial,
            listOf(asleep(minutes(30)), asleep(minutes(90))),
            OvernightSleepSettings(),
        )

        assertEquals(initial, result.state)
        assertNull(result.confirmedAtEpochMillis)
    }

    @Test
    fun `equal window boundaries disable automatic sleep confirmation`() {
        val result = evaluateSleepEvidence(
            SleepPolicyState(),
            observationsAtMinutes(0, 30, 60, 90, 120),
            OvernightSleepSettings(startMinuteOfDay = 60, endMinuteOfDay = 60),
        )

        assertNull(result.confirmedAtEpochMillis)
    }

    private fun observationsAtMinutes(
        vararg minutes: Int,
        localMinuteOfDay: Int = 23 * 60,
    ) = minutes.map { asleep(minutes(it), localMinuteOfDay = localMinuteOfDay) }

    private fun asleep(
        epochMillis: Long,
        confidence: Int = 90,
        localMinuteOfDay: Int = 23 * 60,
    ) = SleepClassificationObservation(
        epochMillis = epochMillis,
        localMinuteOfDay = localMinuteOfDay,
        confidence = confidence,
        motion = 1,
        light = 1,
    )

    private fun minutes(value: Int): Long = value * 60_000L
}

@RunWith(Parameterized::class)
class SleepWindowPolicyTest(
    private val start: Int,
    private val end: Int,
    private val minute: Int,
    private val expected: Boolean,
) {
    @Test
    fun `window membership respects inclusive start and exclusive end`() {
        assertEquals(expected, OvernightSleepSettings(start, end).contains(minute))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: [{0}, {1}) contains {2} = {3}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(20 * 60, 12 * 60, 20 * 60, true),
            arrayOf(20 * 60, 12 * 60, 23 * 60, true),
            arrayOf(20 * 60, 12 * 60, 0, true),
            arrayOf(20 * 60, 12 * 60, 12 * 60 - 1, true),
            arrayOf(20 * 60, 12 * 60, 12 * 60, false),
            arrayOf(20 * 60, 12 * 60, 15 * 60, false),
            arrayOf(8 * 60, 17 * 60, 8 * 60, true),
            arrayOf(8 * 60, 17 * 60, 17 * 60, false),
            arrayOf(8 * 60, 17 * 60, 7 * 60, false),
            arrayOf(60, 60, 60, false),
        )
    }
}
