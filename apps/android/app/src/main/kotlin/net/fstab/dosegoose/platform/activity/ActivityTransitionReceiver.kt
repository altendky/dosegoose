package net.fstab.dosegoose.platform.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
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
        if (!acceptsActivityEvidence(observations)) return

        (context.applicationContext as DoseGooseApplication).runReminderWork(goAsync()) {
            sleepPreferences.clearCandidate(System.currentTimeMillis())
            reminderCoordinator.acceptActivityEvidence()
        }
    }
}
