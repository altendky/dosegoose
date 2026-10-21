package net.fstab.dosegoose.platform.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fstab.dosegoose.DoseGooseApplication

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACTIVITY_TRANSITION || !ActivityTransitionResult.hasResult(intent)) {
            return
        }
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val observations = result.transitionEvents.map { event ->
            ActivityTransitionObservation(
                activityType = event.activityType,
                transitionType = event.transitionType,
                elapsedRealtimeNanos = event.elapsedRealTimeNanos,
            )
        }
        val batchId = UUID.randomUUID().toString()
        val receivedAtEpochMillis = System.currentTimeMillis()
        val receivedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val receipt = buildJsonObject {
            put("kind", "received")
            put("batch_id", batchId)
            put("received_at_epoch_millis", receivedAtEpochMillis)
            put("received_elapsed_realtime_nanos", receivedElapsedRealtimeNanos)
            put("boot_count", bootCount)
            // Charging describes receipt time, not the potentially historical event.
            put("battery_status_at_receipt", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            put("plugged_power_source_at_receipt", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            putJsonArray("events") {
                observations.forEach { event ->
                    addJsonObject {
                        put("activity_type", event.activityType)
                        put("transition_type", event.transitionType)
                        put("event_elapsed_realtime_nanos", event.elapsedRealtimeNanos)
                    }
                }
            }
        }

        (context.applicationContext as DoseGooseApplication).runReminderWork(goAsync()) {
            recordDiagnostic(receipt)
            val qualifies = acceptsActivityEvidence(observations)
            try {
                val acceptedCount = if (qualifies) {
                    reminderCoordinator.acceptActivityEvidence {
                        sleepPreferences.clearCandidate(System.currentTimeMillis())
                    }
                } else {
                    0
                }
                recordDiagnostic(buildJsonObject {
                    put("kind", "processed")
                    put("batch_id", batchId)
                    put("processed_at_epoch_millis", System.currentTimeMillis())
                    put("batch_qualifies", qualifies)
                    put("accepted_occurrence_count", acceptedCount)
                })
            } catch (exception: Exception) {
                recordDiagnostic(buildJsonObject {
                    put("kind", "processing_failed")
                    put("batch_id", batchId)
                    put("failed_at_epoch_millis", System.currentTimeMillis())
                    put("exception_type", exception.javaClass.simpleName)
                })
                throw exception
            }
        }
    }

    private fun DoseGooseApplication.recordDiagnostic(record: JsonObject) {
        try {
            activityDiagnosticLog.append(record)
        } catch (exception: IOException) {
            // Diagnostic storage failure must not suppress a medication reminder.
            Log.w("DoseGooseActivity", "Could not retain activity diagnostic event", exception)
        }
    }
}
