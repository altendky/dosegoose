package net.fstab.dosegoose.app

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EarlierIntakeTimeTest {
    @Test
    fun `picker time resolves on the same day`() {
        val editor = editor("2026-09-22T06:00", "2026-09-22T08:24")

        assertEquals(epoch("2026-09-22T07:15"), resolveEarlierIntakeTime(editor, 7, 15))
        assertEquals(
            EarlierIntakeTimeSelection.Available(epoch("2026-09-22T07:15")),
            resolveSelectedEarlierIntakeTime(editor, 7, 15),
        )
    }

    @Test
    fun `picker time can resolve to the previous day across midnight`() {
        val editor = editor("2026-09-21T23:00", "2026-09-22T01:00")

        assertEquals(epoch("2026-09-21T23:30"), resolveEarlierIntakeTime(editor, 23, 30))
    }

    @Test
    fun `repeated clock time resolves to the latest valid date`() {
        val editor = editor("2026-09-21T08:00", "2026-09-22T09:00")

        assertEquals(epoch("2026-09-22T08:30"), resolveEarlierIntakeTime(editor, 8, 30))
    }

    @Test
    fun `selection outside the available window is rejected`() {
        val editor = editor("2026-09-22T06:15", "2026-09-22T08:24")

        assertNull(resolveEarlierIntakeTime(editor, 6, 0))
        assertNull(resolveEarlierIntakeTime(editor, 8, 25))
        assertEquals(
            EarlierIntakeTimeSelection.OutsideWindow(epoch("2026-09-22T06:00")),
            resolveSelectedEarlierIntakeTime(editor, 6, 0),
        )
        assertEquals(
            EarlierIntakeTimeSelection.Future,
            resolveSelectedEarlierIntakeTime(editor, 8, 25),
        )
    }

    @Test
    fun `selection before a cross-midnight window offers the prior date`() {
        val editor = editor("2026-09-21T23:00", "2026-09-22T01:00")

        assertEquals(
            EarlierIntakeTimeSelection.OutsideWindow(epoch("2026-09-21T22:30")),
            resolveSelectedEarlierIntakeTime(editor, 22, 30),
        )
    }

    @Test
    fun `current minute is not rejected due to seconds in captured now`() {
        val editor = editor("2026-09-22T06:00", "2026-09-22T08:24").copy(
            maximumEpochSeconds = epoch("2026-09-22T08:24") + 37,
        )

        assertEquals(epoch("2026-09-22T08:24"), resolveEarlierIntakeTime(editor, 8, 24))
    }

    @Test
    fun `nonexistent daylight saving time is rejected`() {
        val editor = editor(
            "2026-03-08T01:00",
            "2026-03-08T04:00",
            zoneId = "America/New_York",
        )

        assertNull(resolveEarlierIntakeTime(editor, 2, 30))
        assertEquals(
            EarlierIntakeTimeSelection.Invalid,
            resolveSelectedEarlierIntakeTime(editor, 2, 30),
        )
    }

    @Test
    fun `repeated daylight saving time selects latest valid instant`() {
        val editor = editor(
            "2026-11-01T00:30",
            "2026-11-01T02:30",
            zoneId = "America/New_York",
        )
        val expected = LocalDateTime.parse("2026-11-01T01:30")
            .atZone(ZoneId.of(editor.zoneId))
            .withLaterOffsetAtOverlap()
            .toEpochSecond()

        assertEquals(expected, resolveEarlierIntakeTime(editor, 1, 30))
    }

    @Test
    fun `invalid picker values are rejected`() {
        val editor = editor("2026-09-22T06:00", "2026-09-22T08:24")

        assertNull(resolveEarlierIntakeTime(editor, -1, 0))
        assertNull(resolveEarlierIntakeTime(editor, 24, 0))
        assertNull(resolveEarlierIntakeTime(editor, 8, 60))
    }

    private fun editor(
        minimum: String,
        maximum: String,
        zoneId: String = "UTC",
    ): EarlierIntakeEditorState = EarlierIntakeEditorState(
        occurrenceId = "occurrence",
        intakeAtEpochSeconds = epoch(maximum, zoneId),
        minimumEpochSeconds = epoch(minimum, zoneId),
        maximumEpochSeconds = epoch(maximum, zoneId),
        lateDeadlineEpochSeconds = epoch(maximum, zoneId),
        zoneId = zoneId,
    )

    private fun epoch(value: String, zoneId: String = "UTC"): Long =
        LocalDateTime.parse(value).atZone(ZoneId.of(zoneId)).toEpochSecond()
}
