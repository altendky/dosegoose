package net.fstab.dosegoose

import android.app.Application
import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.fstab.dosegoose.data.RoomMedicationRepository
import net.fstab.dosegoose.data.SleepPreferences
import net.fstab.dosegoose.data.ThemePreferences
import net.fstab.dosegoose.data.TodayRepository
import net.fstab.dosegoose.data.local.DoseGooseDatabase
import net.fstab.dosegoose.corebridge.UniFFIDoseCoreBridge
import net.fstab.dosegoose.platform.AndroidForegroundAuthorizationProvider
import net.fstab.dosegoose.platform.SystemPlatformTimeSource
import net.fstab.dosegoose.platform.activity.ActivityEvidenceManager
import net.fstab.dosegoose.platform.reminders.AndroidReminderPlatform
import net.fstab.dosegoose.platform.reminders.ReminderCoordinator
import net.fstab.dosegoose.platform.sleep.SleepEvidenceManager

class DoseGooseApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: DoseGooseDatabase by lazy { DoseGooseDatabase.create(this) }
    val medicationRepository: RoomMedicationRepository by lazy {
        RoomMedicationRepository(database.medicationDao())
    }
    val themePreferences: ThemePreferences by lazy { ThemePreferences(this) }
    val sleepPreferences: SleepPreferences by lazy { SleepPreferences(this) }
    val todayRepository: TodayRepository by lazy {
        TodayRepository(
            medicationDao = database.medicationDao(),
            todayDao = database.todayDao(),
            coreBridge = UniFFIDoseCoreBridge(),
            timeSource = SystemPlatformTimeSource(),
            authorizationProvider = AndroidForegroundAuthorizationProvider(this),
        )
    }
    val activityEvidenceManager: ActivityEvidenceManager by lazy {
        ActivityEvidenceManager(this)
    }
    val sleepEvidenceManager: SleepEvidenceManager by lazy {
        SleepEvidenceManager(this)
    }
    val reminderCoordinator: ReminderCoordinator by lazy {
        ReminderCoordinator(
            todayRepository,
            AndroidReminderPlatform(this),
            activityEvidenceManager,
            sleepEvidenceManager,
        )
    }

    override fun onCreate() {
        super.onCreate()
        reminderCoordinator.initializeChannels()
    }

    fun runReminderWork(
        pendingResult: BroadcastReceiver.PendingResult,
        work: suspend DoseGooseApplication.() -> Unit,
    ) {
        applicationScope.launch {
            try {
                work()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
