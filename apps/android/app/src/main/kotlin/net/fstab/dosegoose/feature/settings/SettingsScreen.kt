package net.fstab.dosegoose.feature.settings

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.fstab.dosegoose.app.ThemeMode
import net.fstab.dosegoose.app.AlertReadiness
import net.fstab.dosegoose.app.ActivityEvidenceReadiness
import net.fstab.dosegoose.app.SleepEvidenceReadiness
import net.fstab.dosegoose.platform.sleep.OvernightSleepSettings

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    alertReadiness: AlertReadiness,
    overnightSleepSettings: OvernightSleepSettings,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmAccess: () -> Unit,
    onRequestActivityPermission: () -> Unit,
    onSleepWindowStartChanged: (Int) -> Unit,
    onSleepWindowEndChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Appearance", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Follow the device theme or choose an override for Dose Goose.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = themeMode == mode,
                                    onClick = { onThemeModeChanged(mode) },
                                    role = Role.RadioButton,
                                )
                                .testTag("theme-${mode.name.lowercase()}")
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = null,
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(mode.label, fontWeight = FontWeight.SemiBold)
                                if (mode == ThemeMode.System) {
                                    Text(
                                        "Use the device light or dark setting",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Reminders", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Dose Goose uses regular due notifications and alarm-style alerts after accepted activity evidence.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Notifications", fontWeight = FontWeight.Bold)
                    Text(
                        if (alertReadiness.notificationsAllowed) {
                            "Allowed"
                        } else {
                            "Permission is needed before Dose Goose can alert you."
                        },
                    )
                    if (!alertReadiness.notificationsAllowed) {
                        Button(onClick = onRequestNotificationPermission) {
                            Text("Allow notifications")
                        }
                    }
                    Text("Reminder timing", fontWeight = FontWeight.Bold)
                    Text(
                        if (alertReadiness.exactAlarmsAllowed) {
                            "Precise alarms allowed"
                        } else {
                            "Using approximate Android timing until exact alarms are allowed."
                        },
                    )
                    if (!alertReadiness.exactAlarmsAllowed) {
                        Button(onClick = onRequestExactAlarmAccess) {
                            Text("Allow exact alarms")
                        }
                    }
                    Text("Activity detection", fontWeight = FontWeight.Bold)
                    Text(activityEvidenceDescription(alertReadiness.activityEvidence))
                    if (
                        alertReadiness.activityEvidence ==
                        ActivityEvidenceReadiness.PermissionRequired
                    ) {
                        Button(onClick = onRequestActivityPermission) {
                            Text("Allow physical activity")
                        }
                    }
                    if (
                        alertReadiness.activityEvidence ==
                        ActivityEvidenceReadiness.RegistrationFailed
                    ) {
                        Button(onClick = onRequestActivityPermission) {
                            Text("Retry activity detection")
                        }
                    }
                    Text("Overnight sleep reset", fontWeight = FontWeight.Bold)
                    Text(sleepEvidenceDescription(alertReadiness.sleepEvidence))
                    Text(
                        "After two hours of sustained sleep evidence in this window, " +
                            "Dose Goose resets activity for future reminders. An alarm already " +
                            "in progress stays active.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                showTimePicker(
                                    context = context,
                                    minuteOfDay = overnightSleepSettings.startMinuteOfDay,
                                    onSelected = onSleepWindowStartChanged,
                                )
                            },
                        ) {
                            Text(
                                "Starts ${formatMinuteOfDay(context, overnightSleepSettings.startMinuteOfDay)}",
                            )
                        }
                        Button(
                            onClick = {
                                showTimePicker(
                                    context = context,
                                    minuteOfDay = overnightSleepSettings.endMinuteOfDay,
                                    onSelected = onSleepWindowEndChanged,
                                )
                            },
                        ) {
                            Text(
                                "Ends ${formatMinuteOfDay(context, overnightSleepSettings.endMinuteOfDay)}",
                            )
                        }
                    }
                }
            }
        }
        item {
            Text("Coming in later slices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Privacy controls, export and backup, and deeper platform diagnostics.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Local data", fontWeight = FontWeight.Bold)
                    Text(
                        "Medication schedules and appearance choices remain on this device between app launches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private fun activityEvidenceDescription(readiness: ActivityEvidenceReadiness): String =
    when (readiness) {
        ActivityEvidenceReadiness.PermissionRequired ->
            "Permission is needed to detect walking or running while a dose is available."
        ActivityEvidenceReadiness.Ready ->
            "Ready. Detection starts only while an available dose needs activity evidence."
        ActivityEvidenceReadiness.Monitoring ->
            "Monitoring for a walking or running transition for an available dose."
        ActivityEvidenceReadiness.Unavailable ->
            "Unavailable on this device. Regular due reminders will continue to work."
        ActivityEvidenceReadiness.RegistrationFailed ->
            "Android could not start activity detection. Regular due reminders remain available."
    }

private fun sleepEvidenceDescription(readiness: SleepEvidenceReadiness): String = when (readiness) {
    SleepEvidenceReadiness.PermissionRequired ->
        "Physical activity permission is needed for automatic overnight reset."
    SleepEvidenceReadiness.Ready -> "Ready. Monitoring starts after activity is accepted."
    SleepEvidenceReadiness.Monitoring -> "Monitoring for sustained overnight sleep evidence."
    SleepEvidenceReadiness.Unavailable -> "Sleep detection is unavailable on this device."
    SleepEvidenceReadiness.RegistrationFailed ->
        "Android could not start sleep detection. The manual reset remains available."
}

private fun showTimePicker(
    context: android.content.Context,
    minuteOfDay: Int,
    onSelected: (Int) -> Unit,
) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onSelected(hour * 60 + minute) },
        minuteOfDay / 60,
        minuteOfDay % 60,
        DateFormat.is24HourFormat(context),
    ).show()
}

private fun formatMinuteOfDay(context: android.content.Context, minuteOfDay: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(java.util.Calendar.MINUTE, minuteOfDay % 60)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}
