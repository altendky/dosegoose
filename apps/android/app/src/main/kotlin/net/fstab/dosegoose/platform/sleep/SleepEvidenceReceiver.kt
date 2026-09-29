package net.fstab.dosegoose.platform.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.SleepClassifyEvent
import java.time.Instant
import java.time.ZoneId
import net.fstab.dosegoose.DoseGooseApplication

class SleepEvidenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SLEEP_CLASSIFICATION || !SleepClassifyEvent.hasEvents(intent)) {
            return
        }
        val zoneId = ZoneId.systemDefault()
        val observations = SleepClassifyEvent.extractEvents(intent).map { event ->
            val localTime = Instant.ofEpochMilli(event.timestampMillis).atZone(zoneId).toLocalTime()
            SleepClassificationObservation(
                epochMillis = event.timestampMillis,
                localMinuteOfDay = localTime.hour * 60 + localTime.minute,
                confidence = event.confidence,
                motion = event.motion,
                light = event.light,
            )
        }
        if (observations.isEmpty()) return

        (context.applicationContext as DoseGooseApplication).runReminderWork(goAsync()) {
            val result = sleepPreferences.evaluate(observations)
            result.confirmedAtEpochMillis?.let { confirmedAt ->
                reminderCoordinator.acceptSleepEvidence(confirmedAt / 1_000L)
            }
        }
    }
}
