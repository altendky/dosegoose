package net.fstab.dosegoose.platform.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ActivityEvidenceStatus {
    PermissionRequired,
    Ready,
    Monitoring,
    Unavailable,
    RegistrationFailed,
}

interface ActivityEvidenceMonitor {
    fun setMonitoringRequired(required: Boolean)
}

object NoActivityEvidenceMonitor : ActivityEvidenceMonitor {
    override fun setMonitoringRequired(required: Boolean) = Unit
}

class ActivityEvidenceManager(private val context: Context) : ActivityEvidenceMonitor {
    private val mutableStatus = MutableStateFlow(currentIdleStatus())
    val status: StateFlow<ActivityEvidenceStatus> = mutableStatus

    private var monitoringRequired = false
    private var requestGeneration = 0

    @Synchronized
    override fun setMonitoringRequired(required: Boolean) {
        monitoringRequired = required
        requestGeneration += 1
        val generation = requestGeneration
        val idleStatus = currentIdleStatus()
        if (idleStatus != ActivityEvidenceStatus.Ready) {
            mutableStatus.value = idleStatus
            if (!required) removeUpdates(generation)
            return
        }
        if (!required) {
            mutableStatus.value = ActivityEvidenceStatus.Ready
            removeUpdates(generation)
            return
        }

        requestUpdates(generation)
    }

    fun refresh() = setMonitoringRequired(monitoringRequired)

    fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun updateStatus(generation: Int, newStatus: ActivityEvidenceStatus) {
        if (generation == requestGeneration) mutableStatus.value = newStatus
    }

    private fun removeUpdates(generation: Int) {
        if (!playServicesAvailable()) return
        try {
            removeUpdatesWithPermission(generation)
        } catch (_: SecurityException) {
            updateStatus(generation, ActivityEvidenceStatus.PermissionRequired)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(generation: Int) {
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(transitionRequest(), callbackPendingIntent())
                .addOnSuccessListener {
                    updateStatus(generation, ActivityEvidenceStatus.Monitoring)
                }
                .addOnFailureListener {
                    updateStatus(generation, ActivityEvidenceStatus.RegistrationFailed)
                }
        } catch (_: SecurityException) {
            updateStatus(generation, ActivityEvidenceStatus.PermissionRequired)
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeUpdatesWithPermission(generation: Int) {
        ActivityRecognition.getClient(context)
            .removeActivityTransitionUpdates(callbackPendingIntent())
            .addOnFailureListener {
                if (monitoringRequired) {
                    updateStatus(generation, ActivityEvidenceStatus.RegistrationFailed)
                }
            }
    }

    private fun currentIdleStatus(): ActivityEvidenceStatus = when {
        !playServicesAvailable() -> ActivityEvidenceStatus.Unavailable
        !permissionGranted() -> ActivityEvidenceStatus.PermissionRequired
        else -> ActivityEvidenceStatus.Ready
    }

    private fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    private fun callbackPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ActivityTransitionReceiver::class.java).apply {
            action = ACTION_ACTIVITY_TRANSITION
            setPackage(context.packageName)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        },
    )

    private companion object {
        const val REQUEST_CODE = 7_021
    }
}

internal const val ACTION_ACTIVITY_TRANSITION =
    "net.fstab.dosegoose.action.ACTIVITY_TRANSITION"

internal fun transitionRequest(): ActivityTransitionRequest = ActivityTransitionRequest(
    listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING).flatMap { activityType ->
        listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
            .map { transitionType ->
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(transitionType)
                    .build()
            }
    },
)

internal data class ActivityTransitionObservation(
    val activityType: Int,
    val transitionType: Int,
    val elapsedRealtimeNanos: Long,
)

internal fun acceptsActivityEvidence(events: List<ActivityTransitionObservation>): Boolean {
    val latest = events
        .filter { it.activityType == DetectedActivity.WALKING || it.activityType == DetectedActivity.RUNNING }
        .maxByOrNull(ActivityTransitionObservation::elapsedRealtimeNanos)
        ?: return false
    return latest.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
}
