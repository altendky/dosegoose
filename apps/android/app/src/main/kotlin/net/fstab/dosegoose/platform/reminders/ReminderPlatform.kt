package net.fstab.dosegoose.platform.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import net.fstab.dosegoose.MainActivity
import net.fstab.dosegoose.R
import net.fstab.dosegoose.corebridge.CoreReminderKind
import net.fstab.dosegoose.data.AlertOccurrenceState
import net.fstab.dosegoose.data.ScheduledEvaluation

data class ReminderCapabilities(
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
)

interface ReminderPlatform {
    fun createChannels()

    fun capabilities(): ReminderCapabilities

    fun showQuiet(occurrence: AlertOccurrenceState, alert: Boolean)

    fun showIntrusive(occurrence: AlertOccurrenceState, alert: Boolean)

    fun cancel(occurrenceId: String)

    fun scheduleIntrusiveRealert(occurrenceId: String, atEpochSeconds: Long)

    fun cancelIntrusiveRealert(occurrenceId: String)

    fun scheduleEvaluation(evaluation: ScheduledEvaluation)

    fun scheduleReconciliation(atEpochSeconds: Long)
}

class AndroidReminderPlatform(private val context: Context) : ReminderPlatform {
    private val notifications = NotificationManagerCompat.from(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun createChannels() {
        val due = NotificationChannel(
            DUE_CHANNEL,
            "Dose reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications when a medication becomes available to take"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val intrusive = NotificationChannel(
            INTRUSIVE_CHANNEL,
            "Active dose alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Sound and vibration after Dose Goose accepts activity evidence"
            enableVibration(true)
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannels(listOf(due, intrusive))
        notificationManager.deleteNotificationChannel(LEGACY_SILENT_DUE_CHANNEL)
    }

    override fun capabilities(): ReminderCapabilities = ReminderCapabilities(
        notificationsAllowed = notificationsAllowed(),
        exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms(),
    )

    @SuppressLint("MissingPermission")
    override fun showQuiet(occurrence: AlertOccurrenceState, alert: Boolean) {
        if (!notificationsAllowed()) return
        try {
            val switchingChannel = activeChannel(occurrence.occurrenceId) == INTRUSIVE_CHANNEL
            if (switchingChannel) cancel(occurrence.occurrenceId)
            notifications.cancel(occurrence.occurrenceId, LEGACY_INTRUSIVE_NOTIFICATION_ID)
            notifications.notify(
                occurrence.occurrenceId,
                NOTIFICATION_ID,
                notification(occurrence, intrusive = false, alert = alert),
            )
        } catch (_: SecurityException) {
            // Permission may be revoked between the explicit check and this call.
        }
    }

    @SuppressLint("MissingPermission")
    override fun showIntrusive(occurrence: AlertOccurrenceState, alert: Boolean) {
        if (!notificationsAllowed()) return
        try {
            val switchingChannel = activeChannel(occurrence.occurrenceId) != INTRUSIVE_CHANNEL
            if (switchingChannel || alert) cancel(occurrence.occurrenceId)
            notifications.cancel(occurrence.occurrenceId, LEGACY_INTRUSIVE_NOTIFICATION_ID)
            notifications.notify(
                occurrence.occurrenceId,
                NOTIFICATION_ID,
                notification(occurrence, intrusive = true, alert = alert),
            )
        } catch (_: SecurityException) {
            // Permission may be revoked between the explicit check and this call.
        }
    }

    override fun cancel(occurrenceId: String) {
        notifications.cancel(occurrenceId, NOTIFICATION_ID)
        notifications.cancel(occurrenceId, LEGACY_INTRUSIVE_NOTIFICATION_ID)
    }

    override fun scheduleIntrusiveRealert(occurrenceId: String, atEpochSeconds: Long) {
        // An already scheduled one-shot alarm must not be pushed past its due time by a
        // concurrent ordinary state refresh. The receiver schedules the next one after firing.
        if (ReminderAlarmReceiver.existingRealertPendingIntent(context, occurrenceId) != null) return
        schedule(atEpochSeconds, ReminderAlarmReceiver.realertPendingIntent(context, occurrenceId, atEpochSeconds))
    }

    override fun cancelIntrusiveRealert(occurrenceId: String) {
        ReminderAlarmReceiver.existingRealertPendingIntent(context, occurrenceId)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    override fun scheduleEvaluation(evaluation: ScheduledEvaluation) {
        schedule(
            evaluation.atEpochSeconds,
            ReminderAlarmReceiver.pendingIntent(
                context,
                "evaluation/${evaluation.effectId}",
                evaluation.effectId.hashCode(),
            ),
        )
    }

    override fun scheduleReconciliation(atEpochSeconds: Long) {
        schedule(
            atEpochSeconds,
            ReminderAlarmReceiver.pendingIntent(context, "reconcile", RECONCILE_REQUEST_CODE),
        )
    }

    private fun schedule(atEpochSeconds: Long, operation: PendingIntent) {
        val triggerAtMillis = Math.multiplyExact(atEpochSeconds, 1_000L)
        scheduleAlarm(
            exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms(),
            exact = {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation,
                )
            },
            inexact = {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation,
                )
            },
        )
    }

    private fun notification(
        occurrence: AlertOccurrenceState,
        intrusive: Boolean,
        alert: Boolean,
    ): Notification {
        val kind = if (intrusive) CoreReminderKind.Intrusive else CoreReminderKind.Quiet
        val builder = NotificationCompat.Builder(
            context,
            if (intrusive) INTRUSIVE_CHANNEL else DUE_CHANNEL,
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(occurrence.medicationName)
            .setContentText(reminderDueLine(occurrence))
            .setStyle(
                NotificationCompat.InboxStyle()
                    .addLine(reminderDueLine(occurrence))
                    .addLine(reminderCutoffLine(occurrence)),
            )
            .setCategory(
                if (intrusive) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER,
            )
            .setPriority(
                if (intrusive) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW,
            )
            .setContentIntent(openAppIntent(occurrence.occurrenceId))
            .setDeleteIntent(
                ReminderActionReceiver.pendingIntent(
                    context,
                    ReminderActionReceiver.ACTION_RESTORE,
                    occurrence.occurrenceId,
                    kind,
                ),
            )
            .setPublicVersion(
                NotificationCompat.Builder(context, DUE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Dose Goose reminder")
                    .setContentText("Open Dose Goose to review your medication schedule.")
                    .build(),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)

        if (intrusive) {
            builder.addAction(
                0,
                snoozeActionText(occurrence),
                ReminderActionReceiver.pendingIntent(
                    context,
                    ReminderActionReceiver.ACTION_SNOOZE,
                    occurrence.occurrenceId,
                    kind,
                ),
            )
        }
        builder.addAction(0, "Open app", openAppIntent(occurrence.occurrenceId))
        return builder.build()
    }

    private fun openAppIntent(occurrenceId: String): PendingIntent = PendingIntent.getActivity(
        context,
        occurrenceId.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "dosegoose://today/$occurrenceId".toUri()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && notifications.areNotificationsEnabled()

    private fun activeChannel(occurrenceId: String): String? = notificationManager.activeNotifications
        .firstOrNull { it.tag == occurrenceId && it.id == NOTIFICATION_ID }
        ?.notification?.channelId

    companion object {
        const val DUE_CHANNEL = "dose_due_v2"
        const val INTRUSIVE_CHANNEL = "dose_due_intrusive_v1"
        const val NOTIFICATION_ID = 1
        const val LEGACY_INTRUSIVE_NOTIFICATION_ID = 2
        private const val RECONCILE_REQUEST_CODE = 0xD05E
        private const val LEGACY_SILENT_DUE_CHANNEL = "dose_due_quiet_v1"
    }
}

internal fun scheduleAlarm(
    exactAlarmsAllowed: Boolean,
    exact: () -> Unit,
    inexact: () -> Unit,
) {
    if (exactAlarmsAllowed) {
        try {
            exact()
            return
        } catch (_: SecurityException) {
            // Access can be revoked after the capability check but before scheduling.
        }
    }
    inexact()
}
