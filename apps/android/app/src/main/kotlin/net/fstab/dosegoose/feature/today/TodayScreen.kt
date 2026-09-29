package net.fstab.dosegoose.feature.today

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import net.fstab.dosegoose.app.AppAction
import net.fstab.dosegoose.app.DoseStatus
import net.fstab.dosegoose.app.EarlierIntakeEditorState
import net.fstab.dosegoose.app.OutsideWindowConfirmationState
import net.fstab.dosegoose.app.RecordingAvailability
import net.fstab.dosegoose.app.ThemeMode
import net.fstab.dosegoose.app.TodayDose

@Composable
fun TodayScreen(
    doses: List<TodayDose>,
    earlierIntakeEditor: EarlierIntakeEditorState?,
    outsideWindowConfirmation: OutsideWindowConfirmationState?,
    themeMode: ThemeMode,
    onAction: (AppAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Today",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "A clear view of what’s needed, taken, and still ahead.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (doses.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No scheduled doses are visible for today.",
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        DoseStatus.entries.forEach { status ->
            val matching = doses.filter { it.status == status }
            if (matching.isNotEmpty()) {
                item {
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(matching, key = TodayDose::occurrenceId) { dose ->
                    DoseCard(dose = dose, onAction = onAction)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    earlierIntakeEditor?.let { editor ->
        EarlierIntakeDialog(editor = editor, themeMode = themeMode, onAction = onAction)
    }
    outsideWindowConfirmation?.let { confirmation ->
        OutsideWindowDialog(confirmation = confirmation, onAction = onAction)
    }
}

@Composable
private fun DoseCard(
    dose: TodayDose,
    onAction: (AppAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (dose.status) {
                DoseStatus.Needed -> MaterialTheme.colorScheme.errorContainer
                DoseStatus.Taken -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(dose.medicationName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        dose.scheduledTimeLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = dose.status.label,
                    color = statusColor(dose.status),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(dose.guidance, style = MaterialTheme.typography.bodyMedium)
            if (dose.activityAccepted && dose.recordingAvailability == RecordingAvailability.Available) {
                Text(
                    "Walking or running detected · intrusive reminders enabled",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = {
                        onAction(AppAction.ReturnToQuietReminders(dose.occurrenceId))
                    },
                ) {
                    Text("Return to regular reminders")
                }
            }
            dose.intakeTimeLabel?.let { intake ->
                Text(
                    "Taken $intake",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (dose.recordingAvailability == RecordingAvailability.Available) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAction(AppAction.RecordDoseNow(dose.occurrenceId)) },
                    ) {
                        Text("Take now")
                    }
                    OutlinedButton(
                        onClick = {
                            onAction(AppAction.StartRecordingEarlier(dose.occurrenceId))
                        },
                    ) {
                        Text("Record earlier")
                    }
                }
            }
        }
    }
}

@Composable
private fun EarlierIntakeDialog(
    editor: EarlierIntakeEditorState,
    themeMode: ThemeMode,
    onAction: (AppAction) -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = themeMode.resolvesToDark(isSystemInDarkTheme())
    DisposableEffect(editor.occurrenceId, editor.intakeAtEpochSeconds, darkTheme) {
        val initialTime = Instant.ofEpochSecond(editor.intakeAtEpochSeconds)
            .atZone(ZoneId.of(editor.zoneId))
        var confirmed = false
        val dialog = TimePickerDialog(
            context,
            if (darkTheme) {
                android.R.style.Theme_Material_Dialog_Alert
            } else {
                android.R.style.Theme_Material_Light_Dialog_Alert
            },
            { _, hour, minute ->
                confirmed = true
                onAction(AppAction.ConfirmRecordingEarlier(hour, minute))
            },
            initialTime.hour,
            initialTime.minute,
            DateFormat.is24HourFormat(context),
        )
        dialog.setTitle("When did you take it?")
        dialog.setOnDismissListener {
            if (!confirmed) onAction(AppAction.CancelRecordingEarlier)
        }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}

@Composable
private fun OutsideWindowDialog(
    confirmation: OutsideWindowConfirmationState,
    onAction: (AppAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CancelOutsideWindowConfirmation) },
        title = { Text("Outside recording window") },
        text = {
            Text(
                "You chose ${confirmation.selectedTimeLabel}. " +
                    "This dose’s recording window is " +
                    "${confirmation.availableFromTimeLabel}–${confirmation.lateDeadlineTimeLabel}. " +
                    "Record it anyway?",
            )
        },
        confirmButton = {
            TextButton(onClick = { onAction(AppAction.RecordOutsideWindow) }) {
                Text("Record anyway")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onAction(AppAction.CancelOutsideWindowConfirmation) }) {
                    Text("Cancel")
                }
                TextButton(onClick = {
                    onAction(AppAction.StartRecordingEarlier(confirmation.occurrenceId))
                }) {
                    Text("Change time")
                }
            }
        },
    )
}

@Composable
private fun statusColor(status: DoseStatus): Color = when (status) {
    DoseStatus.Needed -> MaterialTheme.colorScheme.error
    DoseStatus.Upcoming -> MaterialTheme.colorScheme.primary
    DoseStatus.Taken -> Color(0xFF3E7B57)
    DoseStatus.Attention -> MaterialTheme.colorScheme.tertiary
}
