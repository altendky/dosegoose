package net.fstab.dosegoose.corebridge

import net.fstab.dosegoose.uniffi.CoreTransition
import net.fstab.dosegoose.uniffi.DoseProjection
import net.fstab.dosegoose.uniffi.EffectKind
import net.fstab.dosegoose.uniffi.GuidanceKind
import net.fstab.dosegoose.uniffi.RecordingAvailabilityKind
import net.fstab.dosegoose.uniffi.RejectionCode
import net.fstab.dosegoose.uniffi.RequestedEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DoseCoreBridgeMappingTest {
    @Test
    fun `generated transition maps every value needed by Android storage`() {
        val transition = CoreTransition(
            snapshotJson = "snapshot",
            projection = DoseProjection(
                guidance = GuidanceKind.OVERDUE,
                recordingAvailability = RecordingAvailabilityKind.AVAILABLE,
                dueInSeconds = null,
                lateBySeconds = 61u,
                remainingSeconds = 119u,
                intakeAtUnixSeconds = null,
                activityAcceptedAtUnixSeconds = 150,
                quietVisible = true,
                intrusiveVisible = false,
                snoozedUntilUnixSeconds = 200,
            ),
            effects = EffectKind.entries.mapIndexed { index, kind ->
                RequestedEffect(
                    kind = kind,
                    occurrenceId = "occurrence",
                    deviceId = "device",
                    atUnixSeconds = index.toLong(),
                )
            },
            accepted = false,
            rejection = RejectionCode.INTAKE_TIME_IN_FUTURE,
        )

        val result = transition.toDomain()

        assertEquals("snapshot", result.snapshotJson)
        assertEquals(CoreGuidance.Overdue, result.projection.guidance)
        assertEquals(CoreRecordingAvailability.Available, result.projection.recordingAvailability)
        assertEquals(61L, result.projection.lateBySeconds)
        assertEquals(119L, result.projection.remainingSeconds)
        assertEquals(true, result.projection.quietVisible)
        assertEquals(200L, result.projection.snoozedUntilEpochSeconds)
        assertEquals(150L, result.projection.activityAcceptedAtEpochSeconds)
        assertEquals(CoreEffectKind.entries, result.effects.map { it.kind })
        assertFalse(result.accepted)
        assertEquals(CoreRejection.IntakeTimeInFuture, result.rejection)
    }

    @Test
    fun `unsigned core durations must fit Android signed storage`() {
        val transition = CoreTransition(
            snapshotJson = "snapshot",
            projection = DoseProjection(
                guidance = GuidanceKind.UPCOMING,
                recordingAvailability = RecordingAvailabilityKind.NOT_YET_DUE,
                dueInSeconds = ULong.MAX_VALUE,
                lateBySeconds = null,
                remainingSeconds = null,
                intakeAtUnixSeconds = null,
                activityAcceptedAtUnixSeconds = null,
                quietVisible = false,
                intrusiveVisible = false,
                snoozedUntilUnixSeconds = null,
            ),
            effects = emptyList(),
            accepted = true,
            rejection = null,
        )

        assertThrows(IllegalArgumentException::class.java) { transition.toDomain() }
    }
}
