package net.fstab.dosegoose.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import net.fstab.dosegoose.designsystem.theme.DoseGooseTheme
import org.hamcrest.Matchers.endsWith
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DoseGooseContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun renderApp() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf(AppState.initial().copy(todayDoses = previewDoses()))
            }
            DoseGooseTheme(themeMode = state.themeMode) {
                DoseGooseContent(
                    state = state,
                    onAction = { state = AppReducer.reduce(state, it) },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun todayShowsCoreBackedStatusGroups() {
        composeRule.onAllNodesWithText("Needed now").assertCountEquals(2)
        composeRule.onAllNodesWithText("Taken").assertCountEquals(2)
        composeRule.onAllNodesWithText("Upcoming").assertCountEquals(2)
    }

    @Test
    fun topLevelNavigationOpensScheduleAndMedicationManagement() {
        composeRule.onNodeWithText("Schedule").performClick()
        composeRule.onNodeWithText("Daily schedule").assertIsDisplayed()

        composeRule.onNodeWithText("Medications").performClick()
        composeRule.onNodeWithText("Manage each medication and its once-daily schedule.")
            .assertIsDisplayed()
    }

    @Test
    fun addMedicationFlowUpdatesTheList() {
        composeRule.onNodeWithText("Medications").performClick()
        composeRule.onNodeWithText("Add").performClick()
        composeRule.onNodeWithTag("medication-name").performTextInput("Aspirin")
        composeRule.onNodeWithText("Save medication").performClick()

        composeRule.onNodeWithText("Aspirin").assertIsDisplayed()
    }

    @Test
    fun blankMedicationNameShowsFocusedValidation() {
        composeRule.onNodeWithText("Medications").performClick()
        composeRule.onNodeWithText("Add").performClick()
        composeRule.onNodeWithText("Save medication").performClick()

        composeRule.onNodeWithText("Enter a medication name.").assertIsDisplayed()
    }

    @Test
    fun settingsSupportsSystemLightAndDarkOverrides() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithTag("theme-system").assertIsSelected()

        composeRule.onNodeWithTag("theme-dark").performClick()

        composeRule.onNodeWithTag("theme-dark").assertIsSelected()
    }

    @Test
    fun disabledMedicationLeavesTheTodayProjection() {
        composeRule.onNodeWithText("Medications").performClick()
        composeRule.onNodeWithTag("medication-enabled-${DemoIds.MAGNESIUM_MEDICATION}")
            .performClick()
        composeRule.onNodeWithText("Today").performClick()

        composeRule.onAllNodesWithText("Magnesium").assertCountEquals(0)
    }

    @Test
    fun systemBackClosesMedicationEditorWithoutLeavingTheApp() {
        composeRule.onNodeWithText("Medications").performClick()
        composeRule.onNodeWithText("Vitamin D").performClick()
        composeRule.onNodeWithText("Edit medication").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithText("Manage each medication and its once-daily schedule.")
            .assertIsDisplayed()
    }

    @Test
    fun systemBackClosesSettingsWithoutLeavingTheApp() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Follow the device theme or choose an override for Dose Goose.")
            .assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithText("A clear view of what’s needed, taken, and still ahead.")
            .assertIsDisplayed()
    }

    @Test
    fun systemBackReturnsTopLevelDestinationToToday() {
        composeRule.onNodeWithText("Schedule").performClick()
        composeRule.onNodeWithText("Daily schedule").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithText("A clear view of what’s needed, taken, and still ahead.")
            .assertIsDisplayed()
    }

    @Test
    fun systemBackDismissesEarlierIntakeDialogWithoutLeavingTheApp() {
        composeRule.onNodeWithText("Record earlier").performClick()
        onView(withClassName(endsWith("TimePicker"))).check(matches(isDisplayed()))

        pressBack()

        onView(withClassName(endsWith("TimePicker"))).check(doesNotExist())
        composeRule.onNodeWithText("A clear view of what’s needed, taken, and still ahead.")
            .assertIsDisplayed()
    }

    private fun previewDoses(): List<TodayDose> = AppState.initial().medications.mapIndexed {
            index,
            medication,
        ->
        val status = listOf(DoseStatus.Needed, DoseStatus.Taken, DoseStatus.Upcoming)[index]
        TodayDose(
            occurrenceId = "occurrence-$index",
            medicationId = medication.id,
            medicationName = medication.name,
            scheduledAtEpochSeconds = 1_800L + index,
            lateDeadlineEpochSeconds = 7_200L + index,
            scheduledTimeLabel = medication.schedule.displayTime,
            zoneId = "UTC",
            observedAtEpochSeconds = 3_600L,
            status = status,
            guidance = when (status) {
                DoseStatus.Needed -> "Due now"
                DoseStatus.Taken -> "Recorded"
                DoseStatus.Upcoming -> "Due in 1h"
                DoseStatus.Attention -> "Late window ended"
            },
            recordingAvailability = when (status) {
                DoseStatus.Needed -> RecordingAvailability.Available
                DoseStatus.Taken -> RecordingAvailability.AlreadyRecorded
                DoseStatus.Upcoming -> RecordingAvailability.NotYetDue
                DoseStatus.Attention -> RecordingAvailability.LateWindowElapsed
            },
        )
    }
}
