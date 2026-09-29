package net.fstab.dosegoose.corebridge

import net.fstab.dosegoose.uniffi.CoreTransition as UniFFITransition
import net.fstab.dosegoose.uniffi.DoseProjection as UniFFIProjection
import net.fstab.dosegoose.uniffi.EffectKind as UniFFIEffectKind
import net.fstab.dosegoose.uniffi.GuidanceKind as UniFFIGuidanceKind
import net.fstab.dosegoose.uniffi.InitializeDoseInput
import net.fstab.dosegoose.uniffi.RecordingAvailabilityKind as UniFFIAvailability
import net.fstab.dosegoose.uniffi.RejectionCode as UniFFIRejection
import net.fstab.dosegoose.uniffi.ReminderKindInput as UniFFIReminderKind
import net.fstab.dosegoose.uniffi.acceptDoseActivity
import net.fstab.dosegoose.uniffi.dismissDoseReminder
import net.fstab.dosegoose.uniffi.initializeDose
import net.fstab.dosegoose.uniffi.observeDose
import net.fstab.dosegoose.uniffi.projectDose
import net.fstab.dosegoose.uniffi.recordDose
import net.fstab.dosegoose.uniffi.recordDoseOutsideWindow
import net.fstab.dosegoose.uniffi.returnDoseToInactive
import net.fstab.dosegoose.uniffi.snoozeDose

data class DoseInitialization(
    val occurrenceId: String,
    val medicationId: String,
    val deviceId: String,
    val scheduledAtEpochSeconds: Long,
    val lateWindowSeconds: Long,
    val earlyWindowSeconds: Long = 0,
)

enum class CoreGuidance {
    Upcoming,
    EarlyAvailable,
    Due,
    Overdue,
    LateWindowElapsed,
    Recorded,
}

enum class CoreRecordingAvailability {
    NotYetDue,
    Available,
    LateWindowElapsed,
    AlreadyRecorded,
}

enum class CoreEffectKind {
    PresentQuiet,
    PresentIntrusively,
    CancelQuiet,
    CancelIntrusive,
    ScheduleEvaluation,
}

enum class CoreReminderKind {
    Quiet,
    Intrusive,
}

enum class CoreRejection {
    StaleEvent,
    ForegroundAuthorizationRequired,
    DoseNotDue,
    LateWindowElapsed,
    AlreadyRecorded,
    ActivityNotAccepted,
    SnoozeMustEndInFuture,
    SnoozeBeyondLateWindow,
    IntakeTimeInFuture,
    IntakeBeforeAvailableWindow,
}

data class CoreDoseProjection(
    val guidance: CoreGuidance,
    val recordingAvailability: CoreRecordingAvailability,
    val dueInSeconds: Long?,
    val lateBySeconds: Long?,
    val remainingSeconds: Long?,
    val intakeAtEpochSeconds: Long?,
    val activityAcceptedAtEpochSeconds: Long?,
    val quietVisible: Boolean,
    val intrusiveVisible: Boolean,
    val snoozedUntilEpochSeconds: Long?,
)

data class CoreRequestedEffect(
    val kind: CoreEffectKind,
    val occurrenceId: String,
    val deviceId: String,
    val atEpochSeconds: Long?,
)

data class CoreDoseTransition(
    val snapshotJson: String,
    val projection: CoreDoseProjection,
    val effects: List<CoreRequestedEffect>,
    val accepted: Boolean,
    val rejection: CoreRejection?,
)

interface DoseCoreBridge {
    fun initialize(input: DoseInitialization, nowEpochSeconds: Long): CoreDoseTransition

    fun observe(snapshotJson: String, nowEpochSeconds: Long): CoreDoseTransition

    fun project(snapshotJson: String, nowEpochSeconds: Long): CoreDoseProjection

    fun acceptActivity(snapshotJson: String, nowEpochSeconds: Long): CoreDoseTransition

    fun snooze(
        snapshotJson: String,
        nowEpochSeconds: Long,
        untilEpochSeconds: Long,
    ): CoreDoseTransition

    fun returnToInactive(
        snapshotJson: String,
        nowEpochSeconds: Long,
        appIsForeground: Boolean,
        deviceIsUnlocked: Boolean,
    ): CoreDoseTransition

    fun dismissReminder(
        snapshotJson: String,
        nowEpochSeconds: Long,
        kind: CoreReminderKind,
    ): CoreDoseTransition

    fun record(
        snapshotJson: String,
        nowEpochSeconds: Long,
        intakeAtEpochSeconds: Long?,
        appIsForeground: Boolean,
        deviceIsUnlocked: Boolean,
    ): CoreDoseTransition

    fun recordOutsideWindow(
        snapshotJson: String,
        nowEpochSeconds: Long,
        intakeAtEpochSeconds: Long,
        appIsForeground: Boolean,
        deviceIsUnlocked: Boolean,
    ): CoreDoseTransition
}

class UniFFIDoseCoreBridge : DoseCoreBridge {
    override fun initialize(
        input: DoseInitialization,
        nowEpochSeconds: Long,
    ): CoreDoseTransition = initializeDose(
        InitializeDoseInput(
            occurrenceId = input.occurrenceId,
            medicationId = input.medicationId,
            deviceId = input.deviceId,
            scheduledAtUnixSeconds = input.scheduledAtEpochSeconds,
            earlyWindowSeconds = input.earlyWindowSeconds,
            lateWindowSeconds = input.lateWindowSeconds,
        ),
        nowEpochSeconds,
    ).toDomain()

    override fun observe(
        snapshotJson: String,
        nowEpochSeconds: Long,
    ): CoreDoseTransition = observeDose(snapshotJson, nowEpochSeconds).toDomain()

    override fun record(
        snapshotJson: String,
        nowEpochSeconds: Long,
        intakeAtEpochSeconds: Long?,
        appIsForeground: Boolean,
        deviceIsUnlocked: Boolean,
    ): CoreDoseTransition = recordDose(
        snapshotJson,
        nowEpochSeconds,
        intakeAtEpochSeconds,
        appIsForeground,
        deviceIsUnlocked,
    ).toDomain()

    override fun recordOutsideWindow(
        snapshotJson: String,
        nowEpochSeconds: Long,
        intakeAtEpochSeconds: Long,
        appIsForeground: Boolean,
        deviceIsUnlocked: Boolean,
    ): CoreDoseTransition = recordDoseOutsideWindow(
        snapshotJson,
        nowEpochSeconds,
        intakeAtEpochSeconds,
        appIsForeground,
        deviceIsUnlocked,
    ).toDomain()

    override fun project(snapshotJson: String, nowEpochSeconds: Long): CoreDoseProjection =
        projectDose(snapshotJson, nowEpochSeconds).toDomain()

    override fun acceptActivity(
        snapshotJson: String,
        nowEpochSeconds: Long,
    ): CoreDoseTransition = acceptDoseActivity(snapshotJson, nowEpochSeconds).toDomain()

    override fun snooze(
        snapshotJson: String,
        nowEpochSeconds: Long,
        untilEpochSeconds: Long,
    ): CoreDoseTransition = snoozeDose(
        snapshotJson,
        nowEpochSeconds,
        untilEpochSeconds,
    ).toDomain()

    override fun returnToInactive(
        snapshotJson: String,
        nowEpochSeconds: Long,
        appIsForeground: Boolean,
        deviceIsUnlocked: Boolean,
    ): CoreDoseTransition = returnDoseToInactive(
        snapshotJson,
        nowEpochSeconds,
        appIsForeground,
        deviceIsUnlocked,
    ).toDomain()

    override fun dismissReminder(
        snapshotJson: String,
        nowEpochSeconds: Long,
        kind: CoreReminderKind,
    ): CoreDoseTransition = dismissDoseReminder(
        snapshotJson,
        nowEpochSeconds,
        when (kind) {
            CoreReminderKind.Quiet -> UniFFIReminderKind.QUIET
            CoreReminderKind.Intrusive -> UniFFIReminderKind.INTRUSIVE
        },
    ).toDomain()
}

internal fun UniFFITransition.toDomain(): CoreDoseTransition = CoreDoseTransition(
    snapshotJson = snapshotJson,
    projection = projection.toDomain(),
    effects = effects.map { effect ->
        CoreRequestedEffect(
            kind = when (effect.kind) {
                UniFFIEffectKind.PRESENT_QUIET -> CoreEffectKind.PresentQuiet
                UniFFIEffectKind.PRESENT_INTRUSIVELY -> CoreEffectKind.PresentIntrusively
                UniFFIEffectKind.CANCEL_QUIET -> CoreEffectKind.CancelQuiet
                UniFFIEffectKind.CANCEL_INTRUSIVE -> CoreEffectKind.CancelIntrusive
                UniFFIEffectKind.SCHEDULE_EVALUATION -> CoreEffectKind.ScheduleEvaluation
            },
            occurrenceId = effect.occurrenceId,
            deviceId = effect.deviceId,
            atEpochSeconds = effect.atUnixSeconds,
        )
    },
    accepted = accepted,
    rejection = rejection?.toDomain(),
)

private fun UniFFIProjection.toDomain(): CoreDoseProjection = CoreDoseProjection(
    guidance = when (guidance) {
        UniFFIGuidanceKind.UPCOMING -> CoreGuidance.Upcoming
        UniFFIGuidanceKind.EARLY_AVAILABLE -> CoreGuidance.EarlyAvailable
        UniFFIGuidanceKind.DUE -> CoreGuidance.Due
        UniFFIGuidanceKind.OVERDUE -> CoreGuidance.Overdue
        UniFFIGuidanceKind.LATE_WINDOW_ELAPSED -> CoreGuidance.LateWindowElapsed
        UniFFIGuidanceKind.RECORDED -> CoreGuidance.Recorded
    },
    recordingAvailability = when (recordingAvailability) {
        UniFFIAvailability.NOT_YET_DUE -> CoreRecordingAvailability.NotYetDue
        UniFFIAvailability.AVAILABLE -> CoreRecordingAvailability.Available
        UniFFIAvailability.LATE_WINDOW_ELAPSED -> CoreRecordingAvailability.LateWindowElapsed
        UniFFIAvailability.ALREADY_RECORDED -> CoreRecordingAvailability.AlreadyRecorded
    },
    dueInSeconds = dueInSeconds.toSignedLong(),
    lateBySeconds = lateBySeconds.toSignedLong(),
    remainingSeconds = remainingSeconds.toSignedLong(),
    intakeAtEpochSeconds = intakeAtUnixSeconds,
    activityAcceptedAtEpochSeconds = activityAcceptedAtUnixSeconds,
    quietVisible = quietVisible,
    intrusiveVisible = intrusiveVisible,
    snoozedUntilEpochSeconds = snoozedUntilUnixSeconds,
)

private fun ULong?.toSignedLong(): Long? = this?.let {
    require(it <= Long.MAX_VALUE.toULong()) { "core duration does not fit in Android Long" }
    it.toLong()
}

private fun UniFFIRejection.toDomain(): CoreRejection = when (this) {
    UniFFIRejection.STALE_EVENT -> CoreRejection.StaleEvent
    UniFFIRejection.FOREGROUND_AUTHORIZATION_REQUIRED ->
        CoreRejection.ForegroundAuthorizationRequired
    UniFFIRejection.DOSE_NOT_DUE -> CoreRejection.DoseNotDue
    UniFFIRejection.LATE_WINDOW_ELAPSED -> CoreRejection.LateWindowElapsed
    UniFFIRejection.ALREADY_RECORDED -> CoreRejection.AlreadyRecorded
    UniFFIRejection.ACTIVITY_NOT_ACCEPTED -> CoreRejection.ActivityNotAccepted
    UniFFIRejection.SNOOZE_MUST_END_IN_FUTURE -> CoreRejection.SnoozeMustEndInFuture
    UniFFIRejection.SNOOZE_BEYOND_LATE_WINDOW -> CoreRejection.SnoozeBeyondLateWindow
    UniFFIRejection.INTAKE_TIME_IN_FUTURE -> CoreRejection.IntakeTimeInFuture
    UniFFIRejection.INTAKE_BEFORE_AVAILABLE_WINDOW -> CoreRejection.IntakeBeforeAvailableWindow
}
