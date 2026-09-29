package net.fstab.dosegoose.platform

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class SystemPlatformTimeSourceTest {
    @Test
    fun `capture exposes one coherent instant date zone and offset`() {
        val source = SystemPlatformTimeSource(
            Clock.fixed(Instant.parse("2026-09-20T14:05:06Z"), ZoneId.of("America/New_York")),
        )

        val moment = source.capture()

        assertEquals(Instant.parse("2026-09-20T14:05:06Z").epochSecond, moment.epochSeconds)
        assertEquals(LocalDate.parse("2026-09-20"), moment.localDate)
        assertEquals("America/New_York", moment.zoneId)
        assertEquals(-4 * 3_600, moment.utcOffsetSeconds)
    }

    @Test
    fun `occurrence identity is stable for a schedule and local date`() {
        val first = occurrenceId("880ace74-a038-4140-89e0-e9214e4ad9a2", LocalDate.parse("2026-09-20"))
        val repeated = occurrenceId("880ace74-a038-4140-89e0-e9214e4ad9a2", LocalDate.parse("2026-09-20"))

        assertEquals(first, repeated)
        assertNotEquals(first, occurrenceId("other-schedule", LocalDate.parse("2026-09-20")))
        assertNotEquals(first, occurrenceId("880ace74-a038-4140-89e0-e9214e4ad9a2", LocalDate.parse("2026-09-21")))
    }

    @Test
    fun `format uses the occurrence zone instead of the host zone`() {
        val source = SystemPlatformTimeSource(Clock.systemUTC())

        val label = source.format(Instant.parse("2026-09-20T14:00:00Z").epochSecond, "America/New_York")

        assertEquals("Sep 20, 10:00 AM", label)
    }
}

@RunWith(Parameterized::class)
class ScheduledTimeResolutionTest(
    private val label: String,
    private val date: String,
    private val hour: Int,
    private val minute: Int,
    private val expectedInstant: String,
    private val expectedOffsetSeconds: Int,
) {
    @Test
    fun `local schedule follows documented zone rules`() {
        val result = SystemPlatformTimeSource(Clock.systemUTC()).resolve(
            LocalDate.parse(date),
            hour,
            minute,
            "America/New_York",
        )

        assertEquals(label, Instant.parse(expectedInstant).epochSecond, result.epochSeconds)
        assertEquals(label, expectedOffsetSeconds, result.utcOffsetSeconds)
        assertEquals(label, "America/New_York", result.zoneId)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("ordinary winter time", "2026-01-15", 8, 0, "2026-01-15T13:00:00Z", -18_000),
            arrayOf("ordinary summer time", "2026-07-15", 8, 0, "2026-07-15T12:00:00Z", -14_400),
            arrayOf("spring gap advances", "2026-03-08", 2, 30, "2026-03-08T07:30:00Z", -14_400),
            arrayOf("fall overlap uses earlier offset", "2026-11-01", 1, 30, "2026-11-01T05:30:00Z", -14_400),
        )
    }
}
