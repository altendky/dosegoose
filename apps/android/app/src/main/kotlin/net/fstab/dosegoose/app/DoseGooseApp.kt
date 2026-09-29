package net.fstab.dosegoose.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.fstab.dosegoose.designsystem.theme.DoseGooseTheme
import net.fstab.dosegoose.feature.medications.MedicationEditorScreen
import net.fstab.dosegoose.feature.medications.MedicationsScreen
import net.fstab.dosegoose.feature.schedule.ScheduleScreen
import net.fstab.dosegoose.feature.settings.SettingsScreen
import net.fstab.dosegoose.feature.today.TodayScreen

@Composable
fun DoseGooseApp(
    viewModel: DoseGooseViewModel = viewModel(),
    onRequestNotificationPermission: () -> Unit = {},
    onRequestExactAlarmAccess: () -> Unit = {},
    onRequestActivityPermission: () -> Unit = {},
) {
    val state = viewModel.uiState
    DoseGooseTheme(themeMode = state.themeMode) {
        DoseGooseContent(
            state = state,
            onAction = viewModel::dispatch,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onRequestExactAlarmAccess = onRequestExactAlarmAccess,
            onRequestActivityPermission = onRequestActivityPermission,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseGooseContent(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
    onRequestExactAlarmAccess: () -> Unit = {},
    onRequestActivityPermission: () -> Unit = {},
) {
    val editor = state.editor
    val secondarySurface = state.settingsOpen || editor != null
    val backAction = state.backAction()
    BackHandler(enabled = backAction != null) {
        onAction(requireNotNull(backAction))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.settingsOpen -> "Settings"
                            editor?.medicationId == null && editor != null -> "Add medication"
                            editor != null -> "Edit medication"
                            else -> "Dose Goose"
                        },
                    )
                },
                navigationIcon = {
                    if (secondarySurface) {
                        TextButton(
                            onClick = { onAction(requireNotNull(backAction)) },
                        ) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (!secondarySurface) {
                        TextButton(onClick = { onAction(AppAction.OpenSettings) }) {
                            Text("Settings")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!secondarySurface) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = state.destination == destination,
                            onClick = {
                                onAction(AppAction.SelectDestination(destination))
                            },
                            icon = { Text(if (state.destination == destination) "●" else "○") },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!secondarySurface && state.destination == TopLevelDestination.Medications) {
                FloatingActionButton(onClick = { onAction(AppAction.StartAddingMedication) }) {
                    Text("Add")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(Modifier.fillMaxSize()) {
                state.storageMessage?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    when {
                        state.isLoading -> CircularProgressIndicator(
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentSize(),
                        )
                        state.settingsOpen -> SettingsScreen(
                            themeMode = state.themeMode,
                            alertReadiness = state.alertReadiness,
                            overnightSleepSettings = state.overnightSleepSettings,
                            onThemeModeChanged = { onAction(AppAction.SetThemeMode(it)) },
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onRequestExactAlarmAccess = onRequestExactAlarmAccess,
                            onRequestActivityPermission = onRequestActivityPermission,
                            onSleepWindowStartChanged = {
                                onAction(AppAction.SetSleepWindowStart(it))
                            },
                            onSleepWindowEndChanged = {
                                onAction(AppAction.SetSleepWindowEnd(it))
                            },
                        )
                        editor != null -> MedicationEditorScreen(
                            editor = editor,
                            themeMode = state.themeMode,
                            onAction = onAction,
                        )
                        else -> DestinationContent(state, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationContent(
    state: AppState,
    onAction: (AppAction) -> Unit,
) {
    when (state.destination) {
        TopLevelDestination.Today -> TodayScreen(
            doses = state.todayDoses,
            earlierIntakeEditor = state.earlierIntakeEditor,
            outsideWindowConfirmation = state.outsideWindowConfirmation,
            themeMode = state.themeMode,
            onAction = onAction,
        )
        TopLevelDestination.Schedule -> ScheduleScreen(medications = state.medications)
        TopLevelDestination.Medications -> MedicationsScreen(
            medications = state.medications,
            onEdit = { onAction(AppAction.StartEditingMedication(it)) },
            onEnabledChanged = { medicationId, enabled ->
                onAction(AppAction.SetMedicationEnabled(medicationId, enabled))
            },
        )
    }
}
