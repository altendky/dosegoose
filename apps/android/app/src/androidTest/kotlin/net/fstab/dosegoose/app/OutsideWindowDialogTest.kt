package net.fstab.dosegoose.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.fstab.dosegoose.designsystem.theme.DoseGooseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OutsideWindowDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun warningShowsSelectedTimeAndFullRecordingWindow() {
        render { }

        composeRule.onNodeWithText("Outside recording window").assertIsDisplayed()
        composeRule.onNodeWithText("Sep 22, 5:30 AM", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sep 22, 6:00 AM–Sep 22, 12:00 PM", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Record anyway").assertIsDisplayed()
        composeRule.onNodeWithText("Change time").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun recordAnywayRequestsExplicitOverride() {
        var action: AppAction? = null
        render { action = it }

        composeRule.onNodeWithText("Record anyway").performClick()

        assertEquals(AppAction.RecordOutsideWindow, action)
    }

    @Test
    fun changeTimeReopensThePickerForTheSameOccurrence() {
        var action: AppAction? = null
        render { action = it }

        composeRule.onNodeWithText("Change time").performClick()

        assertEquals(AppAction.StartRecordingEarlier("occurrence"), action)
    }

    @Test
    fun cancelDoesNotRequestARecord() {
        var action: AppAction? = null
        render { action = it }

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(AppAction.CancelOutsideWindowConfirmation, action)
    }

    private fun render(onAction: (AppAction) -> Unit) {
        val state = AppState.initial().copy(
            medications = emptyList(),
            outsideWindowConfirmation = OutsideWindowConfirmationState(
                occurrenceId = "occurrence",
                intakeAtEpochSeconds = 1_000L,
                selectedTimeLabel = "Sep 22, 5:30 AM",
                availableFromTimeLabel = "Sep 22, 6:00 AM",
                lateDeadlineTimeLabel = "Sep 22, 12:00 PM",
            ),
        )
        composeRule.setContent {
            DoseGooseTheme(themeMode = state.themeMode) {
                DoseGooseContent(state = state, onAction = onAction)
            }
        }
    }
}
