package net.fstab.dosegoose.feature.medications

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import net.fstab.dosegoose.app.AppAction
import net.fstab.dosegoose.app.Medication
import net.fstab.dosegoose.app.MedicationEditorState
import net.fstab.dosegoose.app.ThemeMode

@Composable
fun MedicationsScreen(
    medications: List<Medication>,
    onEdit: (String) -> Unit,
    onEnabledChanged: (String, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Medications",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Manage each medication and its once-daily schedule.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(medications, key = Medication::id) { medication ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onEdit(medication.id) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(medication.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Daily at ${medication.schedule.displayTime}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Early ${formatDuration(medication.schedule.earlyWindowMinutes)} · " +
                                "late ${formatDuration(medication.schedule.lateWindowMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = medication.enabled,
                        onCheckedChange = { onEnabledChanged(medication.id, it) },
                        modifier = Modifier.testTag("medication-enabled-${medication.id}"),
                    )
                }
            }
        }
        if (medications.isEmpty()) {
            item { Text("No medications yet. Use Add to create one.") }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
fun MedicationEditorScreen(
    editor: MedicationEditorState,
    themeMode: ThemeMode,
    onAction: (AppAction) -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = themeMode.resolvesToDark(isSystemInDarkTheme())
    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (editor.medicationId == null) {
                    "What should Dose Goose track?"
                } else {
                    "Update this medication"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            OutlinedTextField(
                value = editor.name,
                onValueChange = { onAction(AppAction.SetEditorName(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("medication-name"),
                label = { Text("Medication name") },
                singleLine = true,
                isError = editor.validationMessage != null && editor.name.isBlank(),
                supportingText = editor.validationMessage?.let { message ->
                    { Text(message) }
                },
            )
        }
        item {
            EditorSection(
                title = "Due time",
                description = "The once-daily time when this medication is due, using the phone’s local time.",
            ) {
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            if (darkTheme) {
                                android.R.style.Theme_Material_Dialog_Alert
                            } else {
                                android.R.style.Theme_Material_Light_Dialog_Alert
                            },
                            { _, hour, minute ->
                                onAction(AppAction.SetEditorTime(hour, minute))
                            },
                            editor.hour,
                            editor.minute,
                            DateFormat.is24HourFormat(context),
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(formatTime(editor.hour, editor.minute, DateFormat.is24HourFormat(context)))
                }
            }
        }
        item {
            EditorSection(
                title = "Early notification and taking",
                description = "A regular notification appears this long before the due time, and the dose becomes available to record.",
            ) {
                OutlinedButton(
                    onClick = {
                        onAction(
                            AppAction.SetEditorEarlyWindow(
                                (editor.earlyWindowMinutes - 30).coerceAtLeast(0),
                            ),
                        )
                    },
                    enabled = editor.earlyWindowMinutes > 0,
                ) {
                    Text("− 30 min")
                }
                Text(
                    text = formatDuration(editor.earlyWindowMinutes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = {
                        onAction(
                            AppAction.SetEditorEarlyWindow(
                                (editor.earlyWindowMinutes + 30).coerceAtMost(24 * 60),
                            ),
                        )
                    },
                    enabled = editor.earlyWindowMinutes < 24 * 60,
                ) {
                    Text("+ 30 min")
                }
            }
        }
        item {
            EditorSection(
                title = "Late-dose window",
                description = "How long the scheduled dose remains available to record before overdue guidance takes over.",
            ) {
                OutlinedButton(
                    onClick = {
                        onAction(
                            AppAction.SetEditorLateWindow(
                                (editor.lateWindowMinutes - 30).coerceAtLeast(0),
                            ),
                        )
                    },
                    enabled = editor.lateWindowMinutes > 0,
                ) {
                    Text("− 30 min")
                }
                Text(
                    text = formatDuration(editor.lateWindowMinutes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = {
                        onAction(
                            AppAction.SetEditorLateWindow(
                                (editor.lateWindowMinutes + 30).coerceAtMost(24 * 60),
                            ),
                        )
                    },
                    enabled = editor.lateWindowMinutes < 24 * 60,
                ) {
                    Text("+ 30 min")
                }
            }
        }
        item {
            HorizontalDivider()
            Text(
                text = "Medication and schedule changes are stored locally on this device.",
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    onAction(
                        AppAction.SaveMedication(
                            newMedicationId = if (editor.medicationId == null) {
                                UUID.randomUUID().toString()
                            } else {
                                null
                            },
                            newScheduleId = if (editor.scheduleId == null) {
                                UUID.randomUUID().toString()
                            } else {
                                null
                            },
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save medication")
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun EditorSection(
    title: String,
    description: String,
    content: @Composable RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0 -> "$remainingMinutes min"
        remainingMinutes == 0 -> "$hours hr"
        else -> "$hours hr $remainingMinutes min"
    }
}

private fun formatTime(hour: Int, minute: Int, use24Hour: Boolean): String {
    val skeleton = if (use24Hour) "Hm" else "hm"
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern))
}
