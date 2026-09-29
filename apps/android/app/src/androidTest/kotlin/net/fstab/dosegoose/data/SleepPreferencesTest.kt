package net.fstab.dosegoose.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.fstab.dosegoose.platform.sleep.OvernightSleepSettings
import net.fstab.dosegoose.platform.sleep.SleepClassificationObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SleepPreferencesTest {
    private lateinit var preferencesFile: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferencesFile = File(context.cacheDir, "sleep-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        preferencesFile.delete()
    }

    @Test
    fun missingPreferencesUseTheOvernightDefaults() = runBlocking {
        assertEquals(OvernightSleepSettings(), preferences().settings.first())
    }

    @Test
    fun configuredWindowRoundTripsThroughDataStore() = runBlocking {
        val preferences = preferences()

        preferences.setStartMinuteOfDay(21 * 60 + 15)
        preferences.setEndMinuteOfDay(10 * 60 + 45)

        assertEquals(
            OvernightSleepSettings(21 * 60 + 15, 10 * 60 + 45),
            preferences.settings.first(),
        )
    }

    @Test
    fun evidenceAccumulatesAcrossSeparateBroadcastBatches() = runBlocking {
        val preferences = preferences()

        val first = preferences.evaluate(listOf(observation(0), observation(minutes(30))))
        val second = preferences.evaluate(
            listOf(
                observation(minutes(60)),
                observation(minutes(90)),
                observation(minutes(120)),
            ),
        )

        assertNull(first.confirmedAtEpochMillis)
        assertEquals(minutes(120), second.confirmedAtEpochMillis)
    }

    @Test
    fun clearingCandidateKeepsProcessedWatermarkAndRejectsOldEvidence() = runBlocking {
        val preferences = preferences()
        preferences.evaluate(listOf(observation(minutes(60))))

        preferences.clearCandidate(minutes(60))
        val old = preferences.evaluate(listOf(observation(minutes(30))))

        assertNull(old.confirmedAtEpochMillis)
        assertNull(old.state.candidateStartedAtEpochMillis)
        assertEquals(minutes(60), old.state.lastProcessedAtEpochMillis)
    }

    @Test
    fun changingWindowStartClearsCandidateButKeepsEventWatermark() = runBlocking {
        val preferences = preferences()
        preferences.evaluate(listOf(observation(minutes(10))))

        preferences.setStartMinuteOfDay(22 * 60)
        val result = preferences.evaluate(listOf(observation(minutes(20))))

        assertEquals(minutes(20), result.state.candidateStartedAtEpochMillis)
        assertEquals(minutes(20), result.state.lastProcessedAtEpochMillis)
    }

    @Test
    fun changingWindowEndClearsCandidateButKeepsEventWatermark() = runBlocking {
        val preferences = preferences()
        preferences.evaluate(listOf(observation(minutes(10))))

        preferences.setEndMinuteOfDay(11 * 60)
        val result = preferences.evaluate(listOf(observation(minutes(20))))

        assertEquals(minutes(20), result.state.candidateStartedAtEpochMillis)
        assertEquals(minutes(20), result.state.lastProcessedAtEpochMillis)
    }

    @Test
    fun clockChangeResetAllowsEarlierWallClockEvidence() = runBlocking {
        val preferences = preferences()
        preferences.evaluate(listOf(observation(minutes(60))))

        preferences.resetForClockChange()
        val result = preferences.evaluate(listOf(observation(minutes(30))))

        assertEquals(minutes(30), result.state.candidateStartedAtEpochMillis)
        assertEquals(minutes(30), result.state.lastProcessedAtEpochMillis)
    }

    private fun preferences(): SleepPreferences = SleepPreferences(
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { preferencesFile },
        ),
    )

    private fun observation(epochMillis: Long) = SleepClassificationObservation(
        epochMillis = epochMillis,
        localMinuteOfDay = 23 * 60,
        confidence = 90,
        motion = 1,
        light = 1,
    )

    private fun minutes(value: Int): Long = value * 60_000L
}
