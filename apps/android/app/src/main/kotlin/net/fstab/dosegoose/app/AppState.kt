package net.fstab.dosegoose.app

import net.fstab.dosegoose.platform.sleep.OvernightSleepSettings

enum class TopLevelDestination(val label: String) {
    Today("Today"),
    Schedule("Schedule"),
    Medications("Medications"),
}

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    ;

    fun resolvesToDark(systemIsDark: Boolean): Boolean = when (this) {
        System -> systemIsDark
        Light -> false
        Dark -> true
    }
}

enum class DoseStatus(val label: String) {
    Needed("Needed now"),
    Upcoming("Upcoming"),
    Taken("Taken"),
    Attention("Needs attention"),
}

data class OnceDailySchedule(
    val id: String,
    val hour: Int,
    val minute: Int,
    val lateWindowMinutes: Int,
    val earlyWindowMinutes: Int = 0,
) {
    init {
        require(hour in 0..23)
        require(minute in 0..59)
        require(earlyWindowMinutes in 0..24 * 60)
        require(lateWindowMinutes in 0..24 * 60)
    }

    val displayTime: String
        get() {
            val displayHour = when (val normalized = hour % 12) {
                0 -> 12
                else -> normalized
            }
            val suffix = if (hour < 12) "AM" else "PM"
            return "$displayHour:${minute.toString().padStart(2, '0')} $suffix"
        }
}

data class Medication(
    val id: String,
    val name: String,
    val schedule: OnceDailySchedule,
    val enabled: Boolean,
)

enum class RecordingAvailability {
    NotYetDue,
    Available,
    LateWindowElapsed,
    AlreadyRecorded,
}

data class TodayDose(
    val occurrenceId: String,
    val medicationId: String,
    val medicationName: String,
    val scheduledAtEpochSeconds: Long,
    val availableFromEpochSeconds: Long = scheduledAtEpochSeconds,
    val lateDeadlineEpochSeconds: Long,
    val scheduledTimeLabel: String,
    val zoneId: String,
    val observedAtEpochSeconds: Long,
    val status: DoseStatus,
    val guidance: String,
    val recordingAvailability: RecordingAvailability,
    val intakeAtEpochSeconds: Long? = null,
    val intakeTimeLabel: String? = null,
    val activityAccepted: Boolean = false,
)

data class EarlierIntakeEditorState(
    val occurrenceId: String,
    val intakeAtEpochSeconds: Long,
    val minimumEpochSeconds: Long,
    val maximumEpochSeconds: Long,
    val lateDeadlineEpochSeconds: Long,
    val zoneId: String,
)

data class OutsideWindowConfirmationState(
    val occurrenceId: String,
    val intakeAtEpochSeconds: Long,
    val selectedTimeLabel: String,
    val availableFromTimeLabel: String,
    val lateDeadlineTimeLabel: String,
)

data class MedicationEditorState(
    val medicationId: String?,
    val scheduleId: String?,
    val name: String,
    val hour: Int,
    val minute: Int,
    val lateWindowMinutes: Int,
    val earlyWindowMinutes: Int = 0,
    val validationMessage: String? = null,
)

data class AlertReadiness(
    val notificationsAllowed: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val activityEvidence: ActivityEvidenceReadiness = ActivityEvidenceReadiness.PermissionRequired,
    val sleepEvidence: SleepEvidenceReadiness = SleepEvidenceReadiness.PermissionRequired,
)

enum class ActivityEvidenceReadiness {
    PermissionRequired,
    Ready,
    Monitoring,
    Unavailable,
    RegistrationFailed,
}

enum class SleepEvidenceReadiness {
    PermissionRequired,
    Ready,
    Monitoring,
    Unavailable,
    RegistrationFailed,
}

data class AppState(
    val destination: TopLevelDestination,
    val themeMode: ThemeMode,
    val settingsOpen: Boolean,
    val medications: List<Medication>,
    val todayDoses: List<TodayDose> = emptyList(),
    val editor: MedicationEditorState?,
    val earlierIntakeEditor: EarlierIntakeEditorState? = null,
    val outsideWindowConfirmation: OutsideWindowConfirmationState? = null,
    val isLoading: Boolean = false,
    val storageMessage: String? = null,
    val alertReadiness: AlertReadiness = AlertReadiness(),
    val overnightSleepSettings: OvernightSleepSettings = OvernightSleepSettings(),
) {
    companion object {
        fun initial(): AppState = AppState(
            destination = TopLevelDestination.Today,
            themeMode = ThemeMode.System,
            settingsOpen = false,
            medications = listOf(
                Medication(
                    id = DemoIds.VITAMIN_D_MEDICATION,
                    name = "Vitamin D",
                    schedule = OnceDailySchedule(
                        id = DemoIds.VITAMIN_D_SCHEDULE,
                        hour = 8,
                        minute = 0,
                        earlyWindowMinutes = 120,
                        lateWindowMinutes = 120,
                    ),
                    enabled = true,
                ),
                Medication(
                    id = DemoIds.OMEGA_3_MEDICATION,
                    name = "Omega-3",
                    schedule = OnceDailySchedule(
                        id = DemoIds.OMEGA_3_SCHEDULE,
                        hour = 12,
                        minute = 30,
                        earlyWindowMinutes = 60,
                        lateWindowMinutes = 180,
                    ),
                    enabled = true,
                ),
                Medication(
                    id = DemoIds.MAGNESIUM_MEDICATION,
                    name = "Magnesium",
                    schedule = OnceDailySchedule(
                        id = DemoIds.MAGNESIUM_SCHEDULE,
                        hour = 21,
                        minute = 0,
                        earlyWindowMinutes = 60,
                        lateWindowMinutes = 120,
                    ),
                    enabled = true,
                ),
            ),
            editor = null,
        )

        fun loading(): AppState = initial().copy(medications = emptyList(), isLoading = true)
    }
}

fun AppState.backAction(): AppAction? = when {
    outsideWindowConfirmation != null -> AppAction.CancelOutsideWindowConfirmation
    earlierIntakeEditor != null -> AppAction.CancelRecordingEarlier
    editor != null -> AppAction.CancelMedicationEditing
    settingsOpen -> AppAction.CloseSettings
    destination != TopLevelDestination.Today ->
        AppAction.SelectDestination(TopLevelDestination.Today)
    else -> null
}

object DemoIds {
    const val VITAMIN_D_MEDICATION = "42aa830d-371f-42d0-9305-b8ddfe902731"
    const val VITAMIN_D_SCHEDULE = "e89647c1-0483-4d4d-9ebe-aafb34218637"
    const val OMEGA_3_MEDICATION = "bc24df5c-40eb-443e-bf6a-652e29b72527"
    const val OMEGA_3_SCHEDULE = "880ace74-a038-4140-89e0-e9214e4ad9a2"
    const val MAGNESIUM_MEDICATION = "70000bd8-72c2-4e4d-a78c-af06f23ef237"
    const val MAGNESIUM_SCHEDULE = "69a30e3d-6ad0-4710-bc18-ea7c257fe446"
}

sealed interface AppAction {
    data class SelectDestination(val destination: TopLevelDestination) : AppAction

    data object OpenSettings : AppAction

    data object CloseSettings : AppAction

    data class SetThemeMode(val mode: ThemeMode) : AppAction

    data class SetSleepWindowStart(val minuteOfDay: Int) : AppAction

    data class SetSleepWindowEnd(val minuteOfDay: Int) : AppAction

    data object StartAddingMedication : AppAction

    data class StartEditingMedication(val medicationId: String) : AppAction

    data class SetEditorName(val name: String) : AppAction

    data class SetEditorTime(val hour: Int, val minute: Int) : AppAction

    data class SetEditorEarlyWindow(val minutes: Int) : AppAction

    data class SetEditorLateWindow(val minutes: Int) : AppAction

    data class SaveMedication(
        val newMedicationId: String? = null,
        val newScheduleId: String? = null,
    ) : AppAction

    data object CancelMedicationEditing : AppAction

    data class SetMedicationEnabled(val medicationId: String, val enabled: Boolean) : AppAction

    data class StartRecordingEarlier(val occurrenceId: String) : AppAction

    data object CancelRecordingEarlier : AppAction

    data class ConfirmRecordingEarlier(val hour: Int, val minute: Int) : AppAction

    data object CancelOutsideWindowConfirmation : AppAction

    data object RecordOutsideWindow : AppAction

    data class RecordDoseNow(val occurrenceId: String) : AppAction

    data class ReturnToQuietReminders(val occurrenceId: String) : AppAction
}

object AppReducer {
    fun reduce(state: AppState, action: AppAction): AppState = when (action) {
        is AppAction.SelectDestination -> state.copy(
            destination = action.destination,
            settingsOpen = false,
            editor = null,
            earlierIntakeEditor = null,
            outsideWindowConfirmation = null,
        )
        AppAction.OpenSettings -> state.copy(
            settingsOpen = true,
            editor = null,
            earlierIntakeEditor = null,
            outsideWindowConfirmation = null,
        )
        AppAction.CloseSettings -> state.copy(settingsOpen = false)
        is AppAction.SetThemeMode -> state.copy(themeMode = action.mode)
        is AppAction.SetSleepWindowStart -> updateSleepWindow(state, action.minuteOfDay, true)
        is AppAction.SetSleepWindowEnd -> updateSleepWindow(state, action.minuteOfDay, false)
        AppAction.StartAddingMedication -> state.copy(
            settingsOpen = false,
            earlierIntakeEditor = null,
            outsideWindowConfirmation = null,
            editor = MedicationEditorState(
                medicationId = null,
                scheduleId = null,
                name = "",
                hour = 8,
                minute = 0,
                earlyWindowMinutes = 120,
                lateWindowMinutes = 120,
            ),
        )
        is AppAction.StartEditingMedication -> startEditing(state, action.medicationId)
        is AppAction.SetEditorName -> updateEditor(state) { copy(name = action.name) }
        is AppAction.SetEditorTime -> updateEditor(state) {
            copy(hour = action.hour, minute = action.minute)
        }
        is AppAction.SetEditorEarlyWindow -> updateEditor(state) {
            copy(earlyWindowMinutes = action.minutes)
        }
        is AppAction.SetEditorLateWindow -> updateEditor(state) {
            copy(lateWindowMinutes = action.minutes)
        }
        is AppAction.SaveMedication -> saveMedication(state, action)
        AppAction.CancelMedicationEditing -> state.copy(editor = null)
        is AppAction.SetMedicationEnabled -> state.copy(
            medications = state.medications.map { medication ->
                if (medication.id == action.medicationId) {
                    medication.copy(enabled = action.enabled)
                } else {
                    medication
                }
            },
            todayDoses = if (action.enabled) {
                state.todayDoses
            } else {
                state.todayDoses.filterNot { it.medicationId == action.medicationId }
            },
        )
        is AppAction.StartRecordingEarlier -> startRecordingEarlier(state, action)
        AppAction.CancelRecordingEarlier -> state.copy(earlierIntakeEditor = null)
        is AppAction.ConfirmRecordingEarlier -> state.copy(earlierIntakeEditor = null)
        AppAction.CancelOutsideWindowConfirmation -> state.copy(outsideWindowConfirmation = null)
        AppAction.RecordOutsideWindow -> state.copy(outsideWindowConfirmation = null)
        is AppAction.RecordDoseNow -> state
        is AppAction.ReturnToQuietReminders -> state
    }

    private fun updateSleepWindow(
        state: AppState,
        minuteOfDay: Int,
        start: Boolean,
    ): AppState {
        if (minuteOfDay !in 0 until 24 * 60) return state
        val current = state.overnightSleepSettings
        if (start && minuteOfDay == current.endMinuteOfDay ||
            !start && minuteOfDay == current.startMinuteOfDay
        ) {
            return state.copy(storageMessage = "Sleep window start and end must differ.")
        }
        return state.copy(
            overnightSleepSettings = if (start) {
                current.copy(startMinuteOfDay = minuteOfDay)
            } else {
                current.copy(endMinuteOfDay = minuteOfDay)
            },
        )
    }

    private fun startEditing(state: AppState, medicationId: String): AppState {
        val medication = state.medications.firstOrNull { it.id == medicationId } ?: return state
        return state.copy(
            settingsOpen = false,
            earlierIntakeEditor = null,
            outsideWindowConfirmation = null,
            editor = MedicationEditorState(
                medicationId = medication.id,
                scheduleId = medication.schedule.id,
                name = medication.name,
                hour = medication.schedule.hour,
                minute = medication.schedule.minute,
                earlyWindowMinutes = medication.schedule.earlyWindowMinutes,
                lateWindowMinutes = medication.schedule.lateWindowMinutes,
            ),
        )
    }

    private fun updateEditor(
        state: AppState,
        transform: MedicationEditorState.() -> MedicationEditorState,
    ): AppState = state.copy(editor = state.editor?.transform()?.copy(validationMessage = null))

    private fun saveMedication(state: AppState, action: AppAction.SaveMedication): AppState {
        val editor = state.editor ?: return state
        val name = editor.name.trim()
        val validationMessage = when {
            name.isEmpty() -> "Enter a medication name."
            editor.hour !in 0..23 -> "Choose an hour from 0 through 23."
            editor.minute !in 0..59 -> "Choose a minute from 0 through 59."
            editor.earlyWindowMinutes !in 0..24 * 60 ->
                "Choose an early window from 0 through 1,440 minutes."
            editor.lateWindowMinutes !in 0..24 * 60 ->
                "Choose a late window from 0 through 1,440 minutes."
            editor.medicationId == null && action.newMedicationId.isNullOrBlank() ->
                "Unable to create a medication identifier."
            editor.scheduleId == null && action.newScheduleId.isNullOrBlank() ->
                "Unable to create a schedule identifier."
            else -> null
        }
        if (validationMessage != null) {
            return state.copy(editor = editor.copy(validationMessage = validationMessage))
        }

        val schedule = OnceDailySchedule(
            id = editor.scheduleId ?: requireNotNull(action.newScheduleId),
            hour = editor.hour,
            minute = editor.minute,
            earlyWindowMinutes = editor.earlyWindowMinutes,
            lateWindowMinutes = editor.lateWindowMinutes,
        )
        val medications = if (editor.medicationId == null) {
            state.medications + Medication(
                id = requireNotNull(action.newMedicationId),
                name = name,
                schedule = schedule,
                enabled = true,
            )
        } else {
            state.medications.map { medication ->
                if (medication.id == editor.medicationId) {
                    medication.copy(name = name, schedule = schedule)
                } else {
                    medication
                }
            }
        }
        return state.copy(medications = medications, editor = null)
    }

    private fun startRecordingEarlier(
        state: AppState,
        action: AppAction.StartRecordingEarlier,
    ): AppState {
        val dose = state.todayDoses.firstOrNull {
            it.occurrenceId == action.occurrenceId &&
                it.recordingAvailability == RecordingAvailability.Available
        } ?: return state.copy(outsideWindowConfirmation = null)
        val maximum = dose.observedAtEpochSeconds.coerceAtLeast(dose.availableFromEpochSeconds)
        return state.copy(
            outsideWindowConfirmation = null,
            earlierIntakeEditor = EarlierIntakeEditorState(
                occurrenceId = dose.occurrenceId,
                intakeAtEpochSeconds = maximum,
                minimumEpochSeconds = dose.availableFromEpochSeconds,
                maximumEpochSeconds = maximum,
                lateDeadlineEpochSeconds = dose.lateDeadlineEpochSeconds,
                zoneId = dose.zoneId,
            ),
        )
    }
}
