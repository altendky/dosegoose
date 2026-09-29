package net.fstab.dosegoose.platform.sleep

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
import com.google.android.gms.location.SleepSegmentRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SleepEvidenceStatus {
    PermissionRequired,
    Ready,
    Monitoring,
    Unavailable,
    RegistrationFailed,
}

interface SleepEvidenceMonitor {
    fun setMonitoringRequired(required: Boolean)
}

object NoSleepEvidenceMonitor : SleepEvidenceMonitor {
    override fun setMonitoringRequired(required: Boolean) = Unit
}

class SleepEvidenceManager(private val context: Context) : SleepEvidenceMonitor {
    private val mutableStatus = MutableStateFlow(currentIdleStatus())
    val status: StateFlow<SleepEvidenceStatus> = mutableStatus

    private val registrationPolicy = SleepRegistrationPolicy()

    @Synchronized
    override fun setMonitoringRequired(required: Boolean) {
        registrationPolicy.setRequired(required)
        driveRegistration()
    }

    @Synchronized
    fun refresh() = driveRegistration()

    @Synchronized
    private fun driveRegistration() {
        val idleStatus = currentIdleStatus()
        if (registrationPolicy.required && idleStatus != SleepEvidenceStatus.Ready) {
            mutableStatus.value = idleStatus
            return
        }
        if (!registrationPolicy.required && !playServicesAvailable()) {
            mutableStatus.value = idleStatus
            return
        }

        when (registrationPolicy.beginNextOperation()) {
            SleepRegistrationOperation.Register -> requestUpdates()
            SleepRegistrationOperation.Remove -> removeUpdates()
            null -> if (!registrationPolicy.operationInFlight) {
                mutableStatus.value = if (registrationPolicy.required) {
                    SleepEvidenceStatus.Monitoring
                } else {
                    idleStatus
                }
            }
        }
    }

    private fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun operationFinished(
        operation: SleepRegistrationOperation,
        succeeded: Boolean,
        failureStatus: SleepEvidenceStatus = SleepEvidenceStatus.RegistrationFailed,
    ) {
        val desiredChangedWhileRunning = registrationPolicy.required !=
            (operation == SleepRegistrationOperation.Register)
        registrationPolicy.complete(operation, succeeded)
        if (!succeeded && !desiredChangedWhileRunning) {
            mutableStatus.value = failureStatus
            return
        }
        driveRegistration()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        try {
            ActivityRecognition.getClient(context)
                .requestSleepSegmentUpdates(
                    callbackPendingIntent(),
                    SleepSegmentRequest(SleepSegmentRequest.CLASSIFY_EVENTS_ONLY),
                )
                .addOnSuccessListener {
                    operationFinished(SleepRegistrationOperation.Register, succeeded = true)
                }
                .addOnFailureListener {
                    operationFinished(SleepRegistrationOperation.Register, succeeded = false)
                }
        } catch (_: SecurityException) {
            operationFinished(
                SleepRegistrationOperation.Register,
                succeeded = false,
                failureStatus = SleepEvidenceStatus.PermissionRequired,
            )
        }
    }

    private fun removeUpdates() {
        try {
            removeUpdatesWithPermission()
        } catch (_: SecurityException) {
            operationFinished(
                SleepRegistrationOperation.Remove,
                succeeded = false,
                failureStatus = SleepEvidenceStatus.PermissionRequired,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeUpdatesWithPermission() {
        ActivityRecognition.getClient(context)
            .removeSleepSegmentUpdates(callbackPendingIntent())
            .addOnSuccessListener {
                operationFinished(SleepRegistrationOperation.Remove, succeeded = true)
            }
            .addOnFailureListener {
                operationFinished(SleepRegistrationOperation.Remove, succeeded = false)
            }
    }

    private fun currentIdleStatus(): SleepEvidenceStatus = when {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) ->
            SleepEvidenceStatus.Unavailable
        !playServicesAvailable() -> SleepEvidenceStatus.Unavailable
        !permissionGranted() -> SleepEvidenceStatus.PermissionRequired
        else -> SleepEvidenceStatus.Ready
    }

    private fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    private fun callbackPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SleepEvidenceReceiver::class.java).apply {
            action = ACTION_SLEEP_CLASSIFICATION
            setPackage(context.packageName)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        },
    )

    private companion object {
        const val REQUEST_CODE = 7_022
    }
}

internal enum class SleepRegistrationOperation {
    Register,
    Remove,
}

internal class SleepRegistrationPolicy {
    var required: Boolean = false
        private set

    private var registered: Boolean? = null
    private var inFlight: SleepRegistrationOperation? = null

    val operationInFlight: Boolean
        get() = inFlight != null

    fun setRequired(required: Boolean) {
        this.required = required
    }

    fun beginNextOperation(): SleepRegistrationOperation? {
        if (inFlight != null || registered == required) return null
        return if (required) {
            SleepRegistrationOperation.Register
        } else {
            SleepRegistrationOperation.Remove
        }.also { inFlight = it }
    }

    fun complete(operation: SleepRegistrationOperation, succeeded: Boolean) {
        require(inFlight == operation) { "Completed sleep registration operation is not active" }
        inFlight = null
        if (succeeded) registered = operation == SleepRegistrationOperation.Register
    }
}

internal const val ACTION_SLEEP_CLASSIFICATION =
    "net.fstab.dosegoose.action.SLEEP_CLASSIFICATION"
