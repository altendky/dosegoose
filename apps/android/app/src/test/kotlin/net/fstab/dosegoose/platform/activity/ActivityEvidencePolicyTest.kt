package net.fstab.dosegoose.platform.activity

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ActivityEvidencePolicyTest(
    private val activityType: Int,
    private val transitionType: Int,
    private val expected: Boolean,
) {
    @Test
    fun `only walking or running enter is accepted`() {
        assertEquals(
            expected,
            acceptsActivityEvidence(
                listOf(ActivityTransitionObservation(activityType, transitionType, 100)),
            ),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "activity={0}, transition={1}, accepted={2}")
        fun cases(): List<Array<Any>> {
            val enter = ActivityTransition.ACTIVITY_TRANSITION_ENTER
            val exit = ActivityTransition.ACTIVITY_TRANSITION_EXIT
            val activityTypes = listOf(
                DetectedActivity.IN_VEHICLE,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.ON_FOOT,
                DetectedActivity.STILL,
                DetectedActivity.UNKNOWN,
                DetectedActivity.TILTING,
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                999,
            )
            return activityTypes.flatMap { activityType ->
                listOf(enter, exit).map { transitionType ->
                    arrayOf<Any>(
                        activityType,
                        transitionType,
                        transitionType == enter && activityType in setOf(
                            DetectedActivity.WALKING,
                            DetectedActivity.RUNNING,
                        ),
                    )
                }
            }
        }
    }
}

class ActivityEvidenceBatchPolicyTest {
    @Test
    fun `latest relevant exit defeats an earlier enter`() {
        val accepted = acceptsActivityEvidence(
            listOf(
                ActivityTransitionObservation(
                    DetectedActivity.WALKING,
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    100,
                ),
                ActivityTransitionObservation(
                    DetectedActivity.WALKING,
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                    200,
                ),
            ),
        )

        assertEquals(false, accepted)
    }

    @Test
    fun `irrelevant newer event does not hide the latest relevant enter`() {
        val accepted = acceptsActivityEvidence(
            listOf(
                ActivityTransitionObservation(
                    DetectedActivity.RUNNING,
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    100,
                ),
                ActivityTransitionObservation(
                    DetectedActivity.IN_VEHICLE,
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                    200,
                ),
            ),
        )

        assertEquals(true, accepted)
    }

    @Test
    fun `empty event batch is rejected`() {
        assertEquals(false, acceptsActivityEvidence(emptyList()))
    }
}
