package net.fstab.dosegoose.feature.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.fstab.dosegoose.app.Medication

@Composable
fun ScheduleScreen(medications: List<Medication>) {
    val scheduled = medications
        .filter(Medication::enabled)
        .sortedWith(compareBy({ it.schedule.hour }, { it.schedule.minute }, Medication::name))
    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Daily schedule", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Each enabled medication currently repeats once every local calendar day.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(scheduled, key = Medication::id) { medication ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        medication.schedule.displayTime,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Column {
                        Text(medication.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Early: ${medication.schedule.earlyWindowMinutes} min · " +
                                "late: ${medication.schedule.lateWindowMinutes} min",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (scheduled.isEmpty()) {
            item { Text("No enabled daily schedules.") }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
