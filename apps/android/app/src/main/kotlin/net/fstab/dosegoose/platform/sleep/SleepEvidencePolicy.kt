package net.fstab.dosegoose.platform.sleep

data class OvernightSleepSettings(
    val startMinuteOfDay: Int = DEFAULT_SLEEP_WINDOW_START_MINUTE,
    val endMinuteOfDay: Int = DEFAULT_SLEEP_WINDOW_END_MINUTE,
) {
    init {
        require(startMinuteOfDay in 0 until MINUTES_PER_DAY)
        require(endMinuteOfDay in 0 until MINUTES_PER_DAY)
    }
}

data class SleepClassificationObservation(
    val epochMillis: Long,
    val localMinuteOfDay: Int,
    val confidence: Int,
    val motion: Int,
    val light: Int,
) {
    init {
        require(localMinuteOfDay in 0 until MINUTES_PER_DAY)
        require(confidence in 0..100)
        require(motion in 1..6)
        require(light in 1..6)
    }
}

data class SleepPolicyState(
    val candidateStartedAtEpochMillis: Long? = null,
    val lastQualifyingAtEpochMillis: Long? = null,
    val lastProcessedAtEpochMillis: Long? = null,
)

data class SleepPolicyResult(
    val state: SleepPolicyState,
    val confirmedAtEpochMillis: Long? = null,
)

internal fun evaluateSleepEvidence(
    initialState: SleepPolicyState,
    observations: List<SleepClassificationObservation>,
    settings: OvernightSleepSettings,
): SleepPolicyResult {
    var candidateStartedAt = initialState.candidateStartedAtEpochMillis
    var lastQualifyingAt = initialState.lastQualifyingAtEpochMillis
    var lastProcessedAt = initialState.lastProcessedAtEpochMillis

    for (observation in observations.sortedBy(SleepClassificationObservation::epochMillis)) {
        if (lastProcessedAt != null && observation.epochMillis <= lastProcessedAt) continue
        lastProcessedAt = observation.epochMillis

        if (!settings.contains(observation.localMinuteOfDay)) {
            candidateStartedAt = null
            lastQualifyingAt = null
            continue
        }

        when {
            observation.confidence <= AWAKE_CONFIDENCE_MAXIMUM -> {
                candidateStartedAt = null
                lastQualifyingAt = null
            }
            observation.confidence >= SLEEP_CONFIDENCE_MINIMUM -> {
                val gapIsTooLong = lastQualifyingAt?.let {
                    observation.epochMillis - it > MAX_QUALIFYING_GAP_MILLIS
                } ?: true
                if (candidateStartedAt == null || gapIsTooLong) {
                    candidateStartedAt = observation.epochMillis
                }
                lastQualifyingAt = observation.epochMillis
                if (observation.epochMillis - requireNotNull(candidateStartedAt) >=
                    REQUIRED_SLEEP_EVIDENCE_MILLIS
                ) {
                    return SleepPolicyResult(
                        state = SleepPolicyState(lastProcessedAtEpochMillis = lastProcessedAt),
                        confirmedAtEpochMillis = observation.epochMillis,
                    )
                }
            }
            lastQualifyingAt != null &&
                observation.epochMillis - lastQualifyingAt > MAX_QUALIFYING_GAP_MILLIS -> {
                candidateStartedAt = null
                lastQualifyingAt = null
            }
        }
    }

    return SleepPolicyResult(
        SleepPolicyState(
            candidateStartedAtEpochMillis = candidateStartedAt,
            lastQualifyingAtEpochMillis = lastQualifyingAt,
            lastProcessedAtEpochMillis = lastProcessedAt,
        ),
    )
}

internal fun OvernightSleepSettings.contains(minuteOfDay: Int): Boolean = when {
    startMinuteOfDay == endMinuteOfDay -> false
    startMinuteOfDay < endMinuteOfDay -> minuteOfDay in startMinuteOfDay until endMinuteOfDay
    else -> minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
}

internal const val SLEEP_CONFIDENCE_MINIMUM = 80
internal const val AWAKE_CONFIDENCE_MAXIMUM = 20
internal const val REQUIRED_SLEEP_EVIDENCE_MILLIS = 2 * 60 * 60 * 1_000L
internal const val MAX_QUALIFYING_GAP_MILLIS = 30 * 60 * 1_000L
internal const val DEFAULT_SLEEP_WINDOW_START_MINUTE = 20 * 60
internal const val DEFAULT_SLEEP_WINDOW_END_MINUTE = 12 * 60
private const val MINUTES_PER_DAY = 24 * 60
