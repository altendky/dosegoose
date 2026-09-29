package net.fstab.dosegoose.platform.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import net.fstab.dosegoose.DoseGooseApplication
import net.fstab.dosegoose.corebridge.CoreReminderKind

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EVALUATE && intent.action != ACTION_RE_ALERT) return
        (context.applicationContext as DoseGooseApplication).runReminderWork(goAsync()) {
            activityEvidenceManager.refresh()
            if (intent.action == ACTION_RE_ALERT) {
                val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return@runReminderWork
                val requestedAt = intent.getLongExtra(EXTRA_REQUESTED_AT, Long.MIN_VALUE)
                reminderCoordinator.realert(occurrenceId, requestedAt)
            } else {
                reminderCoordinator.reconcileAndSynchronize()
            }
        }
    }

    companion object {
        fun pendingIntent(
            context: Context,
            identity: String,
            requestCode: Int,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_EVALUATE
                data = "dosegoose://alarm/$identity".toUri()
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun realertPendingIntent(
            context: Context,
            occurrenceId: String,
            atEpochSeconds: Long,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            occurrenceId.hashCode(),
            realertIntent(context, occurrenceId).apply {
                putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                putExtra(EXTRA_REQUESTED_AT, atEpochSeconds)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )

        fun existingRealertPendingIntent(context: Context, occurrenceId: String): PendingIntent? =
            PendingIntent.getBroadcast(
                context,
                occurrenceId.hashCode(),
                realertIntent(context, occurrenceId),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )

        private fun realertIntent(context: Context, occurrenceId: String) =
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_RE_ALERT
                data = "dosegoose://realert/$occurrenceId".toUri()
            }

        private const val ACTION_EVALUATE = "net.fstab.dosegoose.action.EVALUATE"
        private const val ACTION_RE_ALERT = "net.fstab.dosegoose.action.RE_ALERT"
        private const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        private const val EXTRA_REQUESTED_AT = "requested_at"
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_DISMISS, ACTION_SNOOZE, ACTION_SILENCE, ACTION_RESTORE)) return
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { CoreReminderKind.valueOf(it) }.getOrNull() }
            ?: return
        val application = context.applicationContext as DoseGooseApplication
        application.runReminderWork(goAsync()) {
            when (intent.action) {
                // Older installed notifications can still carry these actions. They must
                // not turn a notification gesture into a change to the dose policy.
                ACTION_DISMISS, ACTION_RESTORE -> reminderCoordinator.restoreNotification()
                ACTION_SNOOZE -> if (kind == CoreReminderKind.Intrusive) {
                    reminderCoordinator.snooze(occurrenceId)
                }
                ACTION_SILENCE -> if (kind == CoreReminderKind.Intrusive) {
                    reminderCoordinator.snooze(occurrenceId)
                }
                else -> Unit
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "net.fstab.dosegoose.action.DISMISS"
        const val ACTION_SNOOZE = "net.fstab.dosegoose.action.SNOOZE"
        const val ACTION_SILENCE = "net.fstab.dosegoose.action.SILENCE"
        const val ACTION_RESTORE = "net.fstab.dosegoose.action.RESTORE"
        private const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        private const val EXTRA_KIND = "kind"

        fun pendingIntent(
            context: Context,
            action: String,
            occurrenceId: String,
            kind: CoreReminderKind,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            "$action:$occurrenceId:${kind.name}".hashCode(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                this.action = action
                data = "dosegoose://action/$action/$occurrenceId/${kind.name}".toUri()
                putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                putExtra(EXTRA_KIND, kind.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        (context.applicationContext as DoseGooseApplication).runReminderWork(goAsync()) {
            when (intent.action) {
                Intent.ACTION_TIME_CHANGED -> {
                    sleepPreferences.resetForClockChange()
                    todayRepository.rebaseActivityEvidenceForClockChange()
                }
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    sleepPreferences.clearCandidate(System.currentTimeMillis())
                }
            }
            reminderCoordinator.reconcileAndSynchronize()
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
