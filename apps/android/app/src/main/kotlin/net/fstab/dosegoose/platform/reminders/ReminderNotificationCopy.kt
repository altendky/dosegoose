package net.fstab.dosegoose.platform.reminders

import net.fstab.dosegoose.data.AlertOccurrenceState

internal fun reminderDueLine(occurrence: AlertOccurrenceState): String =
    "Due ${occurrence.scheduledTimeLabel}"

internal fun reminderCutoffLine(occurrence: AlertOccurrenceState): String =
    "Take by ${occurrence.lateDeadlineTimeLabel}"

internal fun snoozeActionText(occurrence: AlertOccurrenceState): String =
    if (occurrence.snoozeAvailable) "Snooze 10 min" else
        "Snooze until ${occurrence.lateDeadlineTimeLabel}"
