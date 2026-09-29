package net.fstab.dosegoose.app

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal sealed interface EarlierIntakeTimeSelection {
    data class Available(val epochSeconds: Long) : EarlierIntakeTimeSelection
    data class OutsideWindow(val epochSeconds: Long) : EarlierIntakeTimeSelection
    data object Future : EarlierIntakeTimeSelection
    data object Invalid : EarlierIntakeTimeSelection
}

/** Match a time-only picker choice to the latest valid occurrence in the dose's window. */
internal fun resolveEarlierIntakeTime(
    editor: EarlierIntakeEditorState,
    hour: Int,
    minute: Int,
): Long? {
    if (hour !in 0..23 || minute !in 0..59) return null
    val zone = ZoneId.of(editor.zoneId)
    val firstDate = Instant.ofEpochSecond(editor.minimumEpochSeconds).atZone(zone).toLocalDate()
    var date = Instant.ofEpochSecond(editor.maximumEpochSeconds).atZone(zone).toLocalDate()
    while (!date.isBefore(firstDate)) {
        val candidate = candidates(date.atTime(hour, minute), zone)
            .filter { it in editor.minimumEpochSeconds..editor.maximumEpochSeconds }
            .maxOrNull()
        if (candidate != null) return candidate
        date = date.minusDays(1)
    }
    return null
}

internal fun resolveSelectedEarlierIntakeTime(
    editor: EarlierIntakeEditorState,
    hour: Int,
    minute: Int,
): EarlierIntakeTimeSelection {
    if (hour !in 0..23 || minute !in 0..59) return EarlierIntakeTimeSelection.Invalid
    resolveEarlierIntakeTime(editor, hour, minute)?.let {
        return EarlierIntakeTimeSelection.Available(it)
    }

    val zone = ZoneId.of(editor.zoneId)
    val currentDate = Instant.ofEpochSecond(editor.maximumEpochSeconds).atZone(zone).toLocalDate()
    val chosenToday = candidates(currentDate.atTime(hour, minute), zone)
    if (chosenToday.isEmpty()) return EarlierIntakeTimeSelection.Invalid
    chosenToday.filter { it <= editor.maximumEpochSeconds }.maxOrNull()?.let {
        return EarlierIntakeTimeSelection.OutsideWindow(it)
    }

    val firstDate = Instant.ofEpochSecond(editor.minimumEpochSeconds).atZone(zone).toLocalDate()
    if (firstDate.isBefore(currentDate)) {
        candidates(currentDate.minusDays(1).atTime(hour, minute), zone)
            .filter { it <= editor.maximumEpochSeconds }
            .maxOrNull()?.let { return EarlierIntakeTimeSelection.OutsideWindow(it) }
    }
    return EarlierIntakeTimeSelection.Future
}

private fun candidates(localTime: LocalDateTime, zone: ZoneId): List<Long> =
    zone.rules.getValidOffsets(localTime)
        .map { offset -> ZonedDateTime.ofLocal(localTime, zone, offset).toEpochSecond() }
