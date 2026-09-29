package net.fstab.dosegoose.data

const val DEFAULT_SNOOZE_SECONDS = 10 * 60L
const val INTRUSIVE_REALERT_SECONDS = 10 * 60L

/** The next fixed-cadence alert after [nowEpochSeconds], bounded by the late deadline. */
internal fun nextIntrusiveRealertAt(
    nowEpochSeconds: Long,
    lastPresentationAtEpochSeconds: Long?,
    lateDeadlineEpochSeconds: Long,
): Long? {
    val anchor = lastPresentationAtEpochSeconds ?: return null
    if (anchor > nowEpochSeconds || nowEpochSeconds >= lateDeadlineEpochSeconds) return null
    val periods = (nowEpochSeconds - anchor) / INTRUSIVE_REALERT_SECONDS + 1
    val next = anchor + periods * INTRUSIVE_REALERT_SECONDS
    return next.takeIf { it > nowEpochSeconds && it < lateDeadlineEpochSeconds }
}

/** A receiver must not replay an old or prematurely delivered alert. */
internal fun intrusiveRealertIsDue(
    requestedAtEpochSeconds: Long,
    nowEpochSeconds: Long,
    lastPresentationAtEpochSeconds: Long?,
    lateDeadlineEpochSeconds: Long,
): Boolean {
    val anchor = lastPresentationAtEpochSeconds ?: return false
    val elapsed = requestedAtEpochSeconds - anchor
    return requestedAtEpochSeconds <= nowEpochSeconds &&
        requestedAtEpochSeconds < lateDeadlineEpochSeconds &&
        elapsed >= INTRUSIVE_REALERT_SECONDS &&
        elapsed % INTRUSIVE_REALERT_SECONDS == 0L
}

internal fun snoozeFitsLateWindow(
    nowEpochSeconds: Long,
    scheduledAtEpochSeconds: Long,
    lateWindowMinutes: Int,
): Boolean = nowEpochSeconds + DEFAULT_SNOOZE_SECONDS <=
    scheduledAtEpochSeconds + lateWindowMinutes * 60L
