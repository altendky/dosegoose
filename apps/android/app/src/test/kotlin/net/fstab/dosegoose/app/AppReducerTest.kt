package net.fstab.dosegoose.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReducerTest {
    @Test
    fun `initial state opens today with system appearance`() {
        val state = AppState.initial()

        assertEquals(TopLevelDestination.Today, state.destination)
        assertEquals(ThemeMode.System, state.themeMode)
        assertFalse(state.settingsOpen)
        assertNull(state.editor)
        assertEquals(3, state.medications.size)
    }

    @Test
    fun `selecting a destination closes transient surfaces`() {
        val original = AppState.initial().copy(
            settingsOpen = true,
            editor = editor(),
        )

        val result = reduce(original, AppAction.SelectDestination(TopLevelDestination.Schedule))

        assertEquals(TopLevelDestination.Schedule, result.destination)
        assertFalse(result.settingsOpen)
        assertNull(result.editor)
    }

    @Test
    fun `opening settings closes the medication editor`() {
        val result = reduce(
            AppState.initial().copy(editor = editor()),
            AppAction.OpenSettings,
        )

        assertTrue(result.settingsOpen)
        assertNull(result.editor)
    }

    @Test
    fun `closing settings preserves the selected destination`() {
        val original = AppState.initial().copy(
            destination = TopLevelDestination.Medications,
            settingsOpen = true,
        )

        val result = reduce(original, AppAction.CloseSettings)

        assertEquals(TopLevelDestination.Medications, result.destination)
        assertFalse(result.settingsOpen)
    }

    @Test
    fun `selecting a theme updates only the preference`() {
        val original = AppState.initial()

        val result = reduce(original, AppAction.SetThemeMode(ThemeMode.Dark))

        assertEquals(ThemeMode.Dark, result.themeMode)
        assertEquals(original.medications, result.medications)
        assertEquals(original.destination, result.destination)
    }

    @Test
    fun `sleep window start and end update independently`() {
        val original = AppState.initial()

        val withStart = reduce(original, AppAction.SetSleepWindowStart(21 * 60))
        val withEnd = reduce(withStart, AppAction.SetSleepWindowEnd(11 * 60 + 30))

        assertEquals(21 * 60, withEnd.overnightSleepSettings.startMinuteOfDay)
        assertEquals(11 * 60 + 30, withEnd.overnightSleepSettings.endMinuteOfDay)
    }

    @Test
    fun `sleep window rejects identical boundaries`() {
        val original = AppState.initial()

        val result = reduce(
            original,
            AppAction.SetSleepWindowStart(original.overnightSleepSettings.endMinuteOfDay),
        )

        assertEquals(original.overnightSleepSettings, result.overnightSleepSettings)
        assertEquals("Sleep window start and end must differ.", result.storageMessage)
    }

    @Test
    fun `sleep window rejects out of range minute`() {
        val original = AppState.initial()

        val result = reduce(original, AppAction.SetSleepWindowEnd(24 * 60))

        assertSame(original, result)
    }

    @Test
    fun `starting add uses safe once-daily defaults`() {
        val result = reduce(
            AppState.initial().copy(
                settingsOpen = true,
                earlierIntakeEditor = earlierIntakeEditor(),
            ),
            AppAction.StartAddingMedication,
        )

        assertFalse(result.settingsOpen)
        assertNull(result.earlierIntakeEditor)
        assertEquals(editor(), result.editor)
    }

    @Test
    fun `starting edit copies the chosen medication into the editor`() {
        val original = AppState.initial().copy(
            settingsOpen = true,
            earlierIntakeEditor = earlierIntakeEditor(),
        )

        val result = reduce(original, AppAction.StartEditingMedication(DemoIds.OMEGA_3_MEDICATION))

        assertEquals(
            MedicationEditorState(
                medicationId = DemoIds.OMEGA_3_MEDICATION,
                scheduleId = DemoIds.OMEGA_3_SCHEDULE,
                name = "Omega-3",
                hour = 12,
                minute = 30,
                lateWindowMinutes = 180,
                earlyWindowMinutes = 60,
            ),
            result.editor,
        )
        assertFalse(result.settingsOpen)
        assertNull(result.earlierIntakeEditor)
    }

    @Test
    fun `editing an unknown medication changes nothing`() {
        val original = AppState.initial()

        val result = reduce(original, AppAction.StartEditingMedication("unknown"))

        assertSame(original, result)
    }

    @Test
    fun `editor field change clears an earlier validation message`() {
        val original = AppState.initial().copy(
            editor = editor().copy(validationMessage = "Old error"),
        )

        val result = reduce(original, AppAction.SetEditorName("Aspirin"))

        assertEquals("Aspirin", result.editor?.name)
        assertNull(result.editor?.validationMessage)
    }

    @Test
    fun `editor actions are no-ops when no editor is open`() {
        val original = AppState.initial()

        val result = reduce(original, AppAction.SetEditorTime(17, 0))

        assertEquals(original, result)
    }

    @Test
    fun `blank medication name is rejected without closing the editor`() {
        val original = AppState.initial().copy(editor = editor(name = "  "))

        val result = reduce(original, AppAction.SaveMedication())

        assertEquals("Enter a medication name.", result.editor?.validationMessage)
        assertEquals(original.medications, result.medications)
    }

    @Test
    fun `out of range editor hour is rejected without constructing a schedule`() {
        val original = AppState.initial().copy(editor = editor(name = "Aspirin").copy(hour = 24))

        val result = reduce(original, AppAction.SaveMedication())

        assertEquals("Choose an hour from 0 through 23.", result.editor?.validationMessage)
        assertEquals(original.medications, result.medications)
    }

    @Test
    fun `out of range editor minute is rejected`() {
        val original = AppState.initial().copy(editor = editor(name = "Aspirin").copy(minute = 60))

        val result = reduce(original, AppAction.SaveMedication())

        assertEquals("Choose a minute from 0 through 59.", result.editor?.validationMessage)
    }

    @Test
    fun `out of range late window is rejected`() {
        val original = AppState.initial().copy(
            editor = editor(name = "Aspirin").copy(lateWindowMinutes = 1_441),
        )

        val result = reduce(original, AppAction.SaveMedication())

        assertEquals(
            "Choose a late window from 0 through 1,440 minutes.",
            result.editor?.validationMessage,
        )
    }

    @Test
    fun `out of range early window is rejected`() {
        val original = AppState.initial().copy(
            editor = editor(name = "Aspirin").copy(earlyWindowMinutes = 1_441),
        )

        val result = reduce(original, AppAction.SaveMedication())

        assertEquals(
            "Choose an early window from 0 through 1,440 minutes.",
            result.editor?.validationMessage,
        )
    }

    @Test
    fun `new medication requires an explicit medication identifier`() {
        val original = AppState.initial().copy(editor = editor(name = "Aspirin"))

        val result = reduce(
            original,
            AppAction.SaveMedication(newScheduleId = NEW_SCHEDULE_ID),
        )

        assertEquals(
            "Unable to create a medication identifier.",
            result.editor?.validationMessage,
        )
        assertEquals(original.medications, result.medications)
    }

    @Test
    fun `new medication requires an explicit schedule identifier`() {
        val original = AppState.initial().copy(editor = editor(name = "Aspirin"))

        val result = reduce(
            original,
            AppAction.SaveMedication(newMedicationId = NEW_MEDICATION_ID),
        )

        assertEquals(
            "Unable to create a schedule identifier.",
            result.editor?.validationMessage,
        )
        assertEquals(original.medications, result.medications)
    }

    @Test
    fun `saving a new medication trims its name and uses supplied identifiers`() {
        val original = AppState.initial().copy(
            editor = editor(name = "  Aspirin  ").copy(hour = 7, minute = 15),
        )

        val result = reduce(
            original,
            AppAction.SaveMedication(
                newMedicationId = NEW_MEDICATION_ID,
                newScheduleId = NEW_SCHEDULE_ID,
            ),
        )

        assertNull(result.editor)
        assertEquals(4, result.medications.size)
        assertEquals(
            Medication(
                id = NEW_MEDICATION_ID,
                name = "Aspirin",
                schedule = OnceDailySchedule(
                    id = NEW_SCHEDULE_ID,
                    hour = 7,
                    minute = 15,
                    lateWindowMinutes = 120,
                    earlyWindowMinutes = 120,
                ),
                enabled = true,
            ),
            result.medications.last(),
        )
    }

    @Test
    fun `saving into an empty list uses the supplied identifiers`() {
        val original = AppState.initial().copy(
            medications = emptyList(),
            editor = editor(name = "Aspirin"),
        )

        val result = reduce(
            original,
            AppAction.SaveMedication(NEW_MEDICATION_ID, NEW_SCHEDULE_ID),
        )

        assertEquals(NEW_MEDICATION_ID, result.medications.single().id)
        assertEquals(NEW_SCHEDULE_ID, result.medications.single().schedule.id)
    }

    @Test
    fun `saving an edit preserves enablement`() {
        val original = reduce(
            AppState.initial(),
            AppAction.StartEditingMedication(DemoIds.VITAMIN_D_MEDICATION),
        )
        val renamed = reduce(original, AppAction.SetEditorName("Vitamin D3"))
        val rescheduled = reduce(renamed, AppAction.SetEditorTime(9, 0))

        val result = reduce(rescheduled, AppAction.SaveMedication())

        val medication = result.medications.first { it.id == DemoIds.VITAMIN_D_MEDICATION }
        assertEquals("Vitamin D3", medication.name)
        assertEquals(9, medication.schedule.hour)
        assertEquals(DemoIds.VITAMIN_D_SCHEDULE, medication.schedule.id)
        assertTrue(medication.enabled)
    }

    @Test
    fun `cancel closes the editor without saving`() {
        val original = AppState.initial().copy(editor = editor(name = "Aspirin"))

        val result = reduce(original, AppAction.CancelMedicationEditing)

        assertNull(result.editor)
        assertEquals(original.medications, result.medications)
    }

    @Test
    fun `save is a no-op when no editor is open`() {
        val original = AppState.initial()

        val result = reduce(original, AppAction.SaveMedication())

        assertSame(original, result)
    }

    @Test
    fun `enabling action changes only the matching medication`() {
        val original = AppState.initial()

        val result = reduce(
            original,
            AppAction.SetMedicationEnabled(DemoIds.OMEGA_3_MEDICATION, false),
        )

        assertFalse(result.medications.first { it.id == DemoIds.OMEGA_3_MEDICATION }.enabled)
        assertEquals(
            original.medications.filterNot { it.id == DemoIds.OMEGA_3_MEDICATION },
            result.medications.filterNot { it.id == DemoIds.OMEGA_3_MEDICATION },
        )
    }

    @Test
    fun `enabling an unknown medication leaves values unchanged`() {
        val original = AppState.initial()

        val result = reduce(original, AppAction.SetMedicationEnabled("unknown", false))

        assertEquals(original, result)
    }

    @Test
    fun `record earlier opens at the last observed time with explicit bounds`() {
        val dose = todayDose()
        val original = AppState.initial().copy(todayDoses = listOf(dose))

        val result = reduce(original, AppAction.StartRecordingEarlier(dose.occurrenceId))

        assertEquals(
            EarlierIntakeEditorState(
                occurrenceId = dose.occurrenceId,
                intakeAtEpochSeconds = 3_600L,
                minimumEpochSeconds = 1_800L,
                maximumEpochSeconds = 3_600L,
                lateDeadlineEpochSeconds = 7_200L,
                zoneId = "UTC",
            ),
            result.earlierIntakeEditor,
        )
    }

    @Test
    fun `record earlier is unavailable before due or after the late window`() {
        RecordingAvailability.entries
            .filterNot { it == RecordingAvailability.Available }
            .forEach { availability ->
                val original = AppState.initial().copy(
                    todayDoses = listOf(todayDose().copy(recordingAvailability = availability)),
                )

                val result = reduce(
                    original,
                    AppAction.StartRecordingEarlier("occurrence"),
                )

                assertNull(result.earlierIntakeEditor)
            }
    }

    @Test
    fun `confirming the system time picker closes the earlier intake editor`() {
        val open = reduce(
            AppState.initial().copy(todayDoses = listOf(todayDose())),
            AppAction.StartRecordingEarlier("occurrence"),
        )

        val result = reduce(open, AppAction.ConfirmRecordingEarlier(0, 45))

        assertNull(result.earlierIntakeEditor)
    }

    @Test
    fun `cancel record earlier closes only that editor`() {
        val open = reduce(
            AppState.initial().copy(todayDoses = listOf(todayDose())),
            AppAction.StartRecordingEarlier("occurrence"),
        )

        val result = reduce(open, AppAction.CancelRecordingEarlier)

        assertNull(result.earlierIntakeEditor)
        assertEquals(open.medications, result.medications)
    }

    @Test
    fun `warning can be cancelled without recording`() {
        val open = AppState.initial().copy(outsideWindowConfirmation = outsideWindowConfirmation())

        val result = reduce(open, AppAction.CancelOutsideWindowConfirmation)

        assertNull(result.outsideWindowConfirmation)
        assertEquals(open.todayDoses, result.todayDoses)
    }

    @Test
    fun `explicit override consumes the warning`() {
        val open = AppState.initial().copy(outsideWindowConfirmation = outsideWindowConfirmation())

        val result = reduce(open, AppAction.RecordOutsideWindow)

        assertNull(result.outsideWindowConfirmation)
    }

    @Test
    fun `changing an out-of-window time reopens the picker`() {
        val open = AppState.initial().copy(
            todayDoses = listOf(todayDose()),
            outsideWindowConfirmation = outsideWindowConfirmation(),
        )

        val result = reduce(open, AppAction.StartRecordingEarlier("occurrence"))

        assertNull(result.outsideWindowConfirmation)
        assertEquals("occurrence", result.earlierIntakeEditor?.occurrenceId)
    }

    @Test
    fun `disabling medication immediately hides its current occurrences`() {
        val original = AppState.initial().copy(todayDoses = listOf(todayDose()))

        val result = reduce(
            original,
            AppAction.SetMedicationEnabled(DemoIds.VITAMIN_D_MEDICATION, false),
        )

        assertTrue(result.todayDoses.isEmpty())
    }

    @Test
    fun `leaving today closes the earlier intake editor`() {
        val open = reduce(
            AppState.initial().copy(todayDoses = listOf(todayDose())),
            AppAction.StartRecordingEarlier("occurrence"),
        )

        val result = reduce(
            open,
            AppAction.SelectDestination(TopLevelDestination.Schedule),
        )

        assertNull(result.earlierIntakeEditor)
    }

    private fun reduce(state: AppState, action: AppAction): AppState =
        AppReducer.reduce(state, action)

    private fun editor(name: String = ""): MedicationEditorState = MedicationEditorState(
        medicationId = null,
        scheduleId = null,
        name = name,
        hour = 8,
        minute = 0,
        lateWindowMinutes = 120,
        earlyWindowMinutes = 120,
    )

    private fun todayDose(): TodayDose = TodayDose(
        occurrenceId = "occurrence",
        medicationId = DemoIds.VITAMIN_D_MEDICATION,
        medicationName = "Vitamin D",
        scheduledAtEpochSeconds = 1_800L,
        lateDeadlineEpochSeconds = 7_200L,
        scheduledTimeLabel = "12:30 AM",
        zoneId = "UTC",
        observedAtEpochSeconds = 3_600L,
        status = DoseStatus.Needed,
        guidance = "Due now",
        recordingAvailability = RecordingAvailability.Available,
    )

    private fun earlierIntakeEditor(): EarlierIntakeEditorState = EarlierIntakeEditorState(
        occurrenceId = "occurrence",
        intakeAtEpochSeconds = 3_600L,
        minimumEpochSeconds = 1_800L,
        maximumEpochSeconds = 3_600L,
        lateDeadlineEpochSeconds = 7_200L,
        zoneId = "UTC",
    )

    private fun outsideWindowConfirmation(): OutsideWindowConfirmationState =
        OutsideWindowConfirmationState(
            occurrenceId = "occurrence",
            intakeAtEpochSeconds = 900L,
            selectedTimeLabel = "Jan 1, 12:15 AM",
            availableFromTimeLabel = "Jan 1, 12:30 AM",
            lateDeadlineTimeLabel = "Jan 1, 2:00 AM",
        )

    private companion object {
        const val NEW_MEDICATION_ID = "c740989a-798d-45bf-8efd-305faf4e9701"
        const val NEW_SCHEDULE_ID = "09842a0c-4e3e-422f-8270-8ca4a26046ac"
    }
}
