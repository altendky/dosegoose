package net.fstab.dosegoose.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppBackPolicyTest(
    private val description: String,
    private val state: AppState,
    private val expected: AppAction?,
) {
    @Test
    fun `back resolves to the expected app action`() {
        assertEquals(description, expected, state.backAction())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf(
                "out-of-window warning is dismissed first",
                baseState().copy(
                    earlierIntakeEditor = earlierIntakeEditor(),
                    outsideWindowConfirmation = OutsideWindowConfirmationState(
                        occurrenceId = "occurrence",
                        intakeAtEpochSeconds = 900L,
                        selectedTimeLabel = "Jan 1, 12:15 AM",
                        availableFromTimeLabel = "Jan 1, 12:30 AM",
                        lateDeadlineTimeLabel = "Jan 1, 2:00 AM",
                    ),
                ),
                AppAction.CancelOutsideWindowConfirmation,
            ),
            arrayOf(
                "earlier-intake dialog is dismissed first",
                baseState().copy(
                    destination = TopLevelDestination.Schedule,
                    settingsOpen = true,
                    editor = medicationEditor(),
                    earlierIntakeEditor = earlierIntakeEditor(),
                ),
                AppAction.CancelRecordingEarlier,
            ),
            arrayOf(
                "medication editor closes before settings",
                baseState().copy(
                    settingsOpen = true,
                    editor = medicationEditor(),
                ),
                AppAction.CancelMedicationEditing,
            ),
            arrayOf(
                "settings closes before leaving its destination",
                baseState().copy(
                    destination = TopLevelDestination.Schedule,
                    settingsOpen = true,
                ),
                AppAction.CloseSettings,
            ),
            arrayOf(
                "schedule returns to the start destination",
                baseState().copy(destination = TopLevelDestination.Schedule),
                AppAction.SelectDestination(TopLevelDestination.Today),
            ),
            arrayOf(
                "medications returns to the start destination",
                baseState().copy(destination = TopLevelDestination.Medications),
                AppAction.SelectDestination(TopLevelDestination.Today),
            ),
            arrayOf(
                "today delegates back to Android",
                baseState(),
                null,
            ),
        )

        private fun baseState(): AppState = AppState.initial()

        private fun medicationEditor(): MedicationEditorState = MedicationEditorState(
            medicationId = DemoIds.VITAMIN_D_MEDICATION,
            scheduleId = DemoIds.VITAMIN_D_SCHEDULE,
            name = "Vitamin D",
            hour = 8,
            minute = 0,
            lateWindowMinutes = 120,
        )

        private fun earlierIntakeEditor(): EarlierIntakeEditorState = EarlierIntakeEditorState(
            occurrenceId = "occurrence",
            intakeAtEpochSeconds = 3_600L,
            minimumEpochSeconds = 1_800L,
            maximumEpochSeconds = 3_600L,
            lateDeadlineEpochSeconds = 7_200L,
            zoneId = "UTC",
        )
    }
}
