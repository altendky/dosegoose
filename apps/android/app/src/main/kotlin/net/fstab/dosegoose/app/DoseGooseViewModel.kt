package net.fstab.dosegoose.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fstab.dosegoose.DoseGooseApplication
import net.fstab.dosegoose.data.MedicationRepository
import net.fstab.dosegoose.data.SleepPreferences
import net.fstab.dosegoose.data.ThemePreferences
import net.fstab.dosegoose.data.TodayRepository
import net.fstab.dosegoose.platform.activity.ActivityEvidenceStatus
import net.fstab.dosegoose.platform.sleep.SleepEvidenceStatus

class DoseGooseViewModel(application: Application) : AndroidViewModel(application) {
    private val doseGooseApplication = application as DoseGooseApplication
    private val medicationRepository: MedicationRepository =
        doseGooseApplication.medicationRepository
    private val themePreferences: ThemePreferences = doseGooseApplication.themePreferences
    private val sleepPreferences: SleepPreferences = doseGooseApplication.sleepPreferences
    private val todayRepository: TodayRepository = doseGooseApplication.todayRepository
    private val reminderCoordinator = doseGooseApplication.reminderCoordinator
    private val activityEvidenceManager = doseGooseApplication.activityEvidenceManager
    private val sleepEvidenceManager = doseGooseApplication.sleepEvidenceManager
    private val reconcileMutex = Mutex()
    private var boundaryJob: Job? = null
    private var appIsForeground = false

    var uiState by mutableStateOf(AppState.loading())
        private set

    init {
        viewModelScope.launch {
            activityEvidenceManager.status.collectLatest { refreshAlertReadiness() }
        }
        viewModelScope.launch {
            sleepEvidenceManager.status.collectLatest { refreshAlertReadiness() }
        }
        viewModelScope.launch {
            try {
                medicationRepository.initialize()
                refreshAlertReadiness()
                reconcileToday()
                combine(
                    medicationRepository.medications,
                    themePreferences.themeMode,
                    todayRepository.todayDoses,
                    sleepPreferences.settings,
                ) { medications, themeMode, todayDoses, sleepSettings ->
                    CombinedUiData(medications, themeMode, todayDoses, sleepSettings)
                }.collect { data ->
                        uiState = uiState.copy(
                            medications = data.medications,
                            themeMode = data.themeMode,
                            todayDoses = data.todayDoses,
                            overnightSleepSettings = data.sleepSettings,
                            isLoading = false,
                        )
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                uiState = uiState.copy(
                    isLoading = false,
                    storageMessage = "Local data could not be loaded. Restart Dose Goose to try again.",
                )
            }
        }
    }

    fun dispatch(action: AppAction) {
        val previous = uiState
        var reduced = AppReducer.reduce(previous, action).let { result ->
            val rejectedSleepWindowChange =
                (action is AppAction.SetSleepWindowStart ||
                    action is AppAction.SetSleepWindowEnd) &&
                    result.overnightSleepSettings == previous.overnightSleepSettings &&
                    result.storageMessage == "Sleep window start and end must differ."
            result.copy(storageMessage = result.storageMessage.takeIf { rejectedSleepWindowChange })
        }
        if (action is AppAction.StartRecordingEarlier) {
            reduced = reduced.copy(
                earlierIntakeEditor = reduced.earlierIntakeEditor?.let { editor ->
                    val now = todayRepository.captureNow().epochSeconds
                        .coerceAtLeast(editor.minimumEpochSeconds)
                    editor.copy(
                        intakeAtEpochSeconds = now,
                        maximumEpochSeconds = now,
                    )
                },
            )
        }
        uiState = reduced

        when (action) {
            is AppAction.SaveMedication -> persistSavedMedication(previous, reduced, action)
            is AppAction.SetMedicationEnabled -> persistEnabledChange(previous, action)
            is AppAction.SetThemeMode -> persistTheme(action.mode)
            is AppAction.SetSleepWindowStart -> persistSleepWindowStart(previous, reduced, action)
            is AppAction.SetSleepWindowEnd -> persistSleepWindowEnd(previous, reduced, action)
            is AppAction.RecordDoseNow -> recordDose(action.occurrenceId, null)
            is AppAction.ReturnToQuietReminders -> returnToQuietReminders(action.occurrenceId)
            is AppAction.ConfirmRecordingEarlier -> {
                val editor = previous.earlierIntakeEditor ?: return
                when (val selection = resolveSelectedEarlierIntakeTime(editor, action.hour, action.minute)) {
                    is EarlierIntakeTimeSelection.Available ->
                        recordDose(editor.occurrenceId, selection.epochSeconds)
                    is EarlierIntakeTimeSelection.OutsideWindow -> {
                        uiState = uiState.copy(
                            outsideWindowConfirmation = OutsideWindowConfirmationState(
                                occurrenceId = editor.occurrenceId,
                                intakeAtEpochSeconds = selection.epochSeconds,
                                selectedTimeLabel = todayRepository.formatTime(
                                    selection.epochSeconds,
                                    editor.zoneId,
                                ),
                                availableFromTimeLabel = todayRepository.formatTime(
                                    editor.minimumEpochSeconds,
                                    editor.zoneId,
                                ),
                                lateDeadlineTimeLabel = todayRepository.formatTime(
                                    editor.lateDeadlineEpochSeconds,
                                    editor.zoneId,
                                ),
                            ),
                        )
                    }
                    EarlierIntakeTimeSelection.Future -> {
                        uiState = uiState.copy(
                            storageMessage = "That time is in the future. Choose a time at or before now.",
                        )
                    }
                    EarlierIntakeTimeSelection.Invalid -> {
                        uiState = uiState.copy(
                            storageMessage = "That clock time is unavailable. Choose another time.",
                        )
                    }
                }
            }
            AppAction.RecordOutsideWindow -> {
                val confirmation = previous.outsideWindowConfirmation ?: return
                recordDose(
                    confirmation.occurrenceId,
                    confirmation.intakeAtEpochSeconds,
                    outsideWindow = true,
                )
            }
            else -> Unit
        }
    }

    fun appEnteredForeground() {
        appIsForeground = true
        refreshAlertReadiness()
        activityEvidenceManager.refresh()
        sleepEvidenceManager.refresh()
        viewModelScope.launch { reconcileToday() }
    }

    fun appLeftForeground() {
        appIsForeground = false
    }

    fun alertPermissionChanged() {
        refreshAlertReadiness()
        viewModelScope.launch { reconcileToday() }
    }

    fun activityPermissionChanged() {
        activityEvidenceManager.refresh()
        sleepEvidenceManager.refresh()
        refreshAlertReadiness()
        viewModelScope.launch { reconcileToday() }
    }

    private fun returnToQuietReminders(occurrenceId: String) {
        viewModelScope.launch {
            try {
                val result = reminderCoordinator.returnToInactive(occurrenceId, appIsForeground)
                uiState = if (result?.accepted == true) {
                    uiState.copy(storageMessage = null)
                } else {
                    uiState.copy(storageMessage = result?.rejection.userMessage())
                }
                reconcileToday()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                uiState = uiState.copy(
                    storageMessage = "Activity state could not be changed. Try again.",
                )
            }
        }
    }

    private fun persistSavedMedication(
        previous: AppState,
        reduced: AppState,
        action: AppAction.SaveMedication,
    ) {
        if (previous.editor == null || reduced.editor != null) return
        val medicationId = previous.editor.medicationId ?: action.newMedicationId ?: return
        val medication = reduced.medications.firstOrNull { it.id == medicationId } ?: return
        launchStorageWrite(reconcileAfter = true) {
            medicationRepository.upsert(
                medication,
                todayRepository.captureNow().epochSeconds,
            )
        }
    }

    private fun persistEnabledChange(previous: AppState, action: AppAction.SetMedicationEnabled) {
        if (previous.medications.none { it.id == action.medicationId }) return
        launchStorageWrite(reconcileAfter = true) {
            medicationRepository.setEnabled(
                action.medicationId,
                action.enabled,
                todayRepository.captureNow().epochSeconds,
            )
            if (!action.enabled) {
                reminderCoordinator.cancelMedication(action.medicationId)
            }
        }
    }

    private fun persistTheme(themeMode: ThemeMode) {
        launchStorageWrite { themePreferences.setThemeMode(themeMode) }
    }

    private fun persistSleepWindowStart(
        previous: AppState,
        reduced: AppState,
        action: AppAction.SetSleepWindowStart,
    ) {
        if (reduced.overnightSleepSettings == previous.overnightSleepSettings) return
        launchStorageWrite { sleepPreferences.setStartMinuteOfDay(action.minuteOfDay) }
    }

    private fun persistSleepWindowEnd(
        previous: AppState,
        reduced: AppState,
        action: AppAction.SetSleepWindowEnd,
    ) {
        if (reduced.overnightSleepSettings == previous.overnightSleepSettings) return
        launchStorageWrite { sleepPreferences.setEndMinuteOfDay(action.minuteOfDay) }
    }

    private fun launchStorageWrite(
        reconcileAfter: Boolean = false,
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                write()
                if (reconcileAfter) reconcileToday()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                uiState = uiState.copy(
                    storageMessage = "That change could not be saved locally. Try again.",
                )
            }
        }
    }

    private fun recordDose(
        occurrenceId: String,
        intakeAtEpochSeconds: Long?,
        outsideWindow: Boolean = false,
    ) {
        viewModelScope.launch {
            try {
                val result = todayRepository.record(
                    occurrenceId = occurrenceId,
                    intakeAtEpochSeconds = intakeAtEpochSeconds,
                    appIsForeground = appIsForeground,
                    outsideWindow = outsideWindow,
                )
                if (result.accepted) {
                    uiState = uiState.copy(earlierIntakeEditor = null, storageMessage = null)
                } else {
                    uiState = uiState.copy(storageMessage = result.rejection.userMessage())
                }
                reconcileToday()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                uiState = uiState.copy(
                    storageMessage = "The dose could not be recorded. Nothing was marked taken.",
                )
            }
        }
    }

    private suspend fun reconcileToday() {
        reconcileMutex.withLock {
            try {
                val result = reminderCoordinator.reconcileAndSynchronize()
                boundaryJob?.cancel()
                boundaryJob = result.nextBoundaryEpochSeconds?.let { boundary ->
                    viewModelScope.launch {
                        val now = todayRepository.captureNow().epochSeconds
                        delay(((boundary - now).coerceAtLeast(1L)) * 1_000L)
                        boundaryJob = null
                        reconcileToday()
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                uiState = uiState.copy(
                    isLoading = false,
                    storageMessage = "Today’s doses could not be refreshed from local data.",
                )
            }
        }
    }

    private fun refreshAlertReadiness() {
        val capabilities = reminderCoordinator.capabilities()
        uiState = uiState.copy(
            alertReadiness = AlertReadiness(
                notificationsAllowed = capabilities.notificationsAllowed,
                exactAlarmsAllowed = capabilities.exactAlarmsAllowed,
                activityEvidence = when (activityEvidenceManager.status.value) {
                    ActivityEvidenceStatus.PermissionRequired ->
                        ActivityEvidenceReadiness.PermissionRequired
                    ActivityEvidenceStatus.Ready -> ActivityEvidenceReadiness.Ready
                    ActivityEvidenceStatus.Monitoring -> ActivityEvidenceReadiness.Monitoring
                    ActivityEvidenceStatus.Unavailable -> ActivityEvidenceReadiness.Unavailable
                    ActivityEvidenceStatus.RegistrationFailed ->
                        ActivityEvidenceReadiness.RegistrationFailed
                },
                sleepEvidence = when (sleepEvidenceManager.status.value) {
                    SleepEvidenceStatus.PermissionRequired ->
                        SleepEvidenceReadiness.PermissionRequired
                    SleepEvidenceStatus.Ready -> SleepEvidenceReadiness.Ready
                    SleepEvidenceStatus.Monitoring -> SleepEvidenceReadiness.Monitoring
                    SleepEvidenceStatus.Unavailable -> SleepEvidenceReadiness.Unavailable
                    SleepEvidenceStatus.RegistrationFailed ->
                        SleepEvidenceReadiness.RegistrationFailed
                },
            ),
        )
    }
}

private data class CombinedUiData(
    val medications: List<Medication>,
    val themeMode: ThemeMode,
    val todayDoses: List<TodayDose>,
    val sleepSettings: net.fstab.dosegoose.platform.sleep.OvernightSleepSettings,
)

private fun net.fstab.dosegoose.corebridge.CoreRejection?.userMessage(): String = when (this) {
    net.fstab.dosegoose.corebridge.CoreRejection.ForegroundAuthorizationRequired ->
        "Unlock your device and use the foreground app to record a dose."
    net.fstab.dosegoose.corebridge.CoreRejection.DoseNotDue ->
        "This dose is not due yet."
    net.fstab.dosegoose.corebridge.CoreRejection.LateWindowElapsed ->
        "The configured late window has ended, so this dose cannot be recorded."
    net.fstab.dosegoose.corebridge.CoreRejection.AlreadyRecorded ->
        "This dose was already recorded."
    net.fstab.dosegoose.corebridge.CoreRejection.IntakeTimeInFuture ->
        "The intake time cannot be in the future."
    net.fstab.dosegoose.corebridge.CoreRejection.IntakeBeforeAvailableWindow ->
        "The intake time cannot be before the early-taking window."
    else -> "The portable dose model rejected that action."
}
