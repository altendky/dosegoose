package net.fstab.dosegoose.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.fstab.dosegoose.platform.sleep.DEFAULT_SLEEP_WINDOW_END_MINUTE
import net.fstab.dosegoose.platform.sleep.DEFAULT_SLEEP_WINDOW_START_MINUTE
import net.fstab.dosegoose.platform.sleep.OvernightSleepSettings
import net.fstab.dosegoose.platform.sleep.SleepClassificationObservation
import net.fstab.dosegoose.platform.sleep.SleepPolicyResult
import net.fstab.dosegoose.platform.sleep.SleepPolicyState
import net.fstab.dosegoose.platform.sleep.evaluateSleepEvidence

class SleepPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val settings: Flow<OvernightSleepSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw exception
            }
        }
        .map(::settingsFrom)

    suspend fun currentSettings(): OvernightSleepSettings = settings.first()

    suspend fun setStartMinuteOfDay(minuteOfDay: Int) {
        require(minuteOfDay in 0 until MINUTES_PER_DAY)
        dataStore.edit {
            it[SLEEP_WINDOW_START_KEY] = minuteOfDay
            it.clearCandidate()
        }
    }

    suspend fun setEndMinuteOfDay(minuteOfDay: Int) {
        require(minuteOfDay in 0 until MINUTES_PER_DAY)
        dataStore.edit {
            it[SLEEP_WINDOW_END_KEY] = minuteOfDay
            it.clearCandidate()
        }
    }

    suspend fun evaluate(
        observations: List<SleepClassificationObservation>,
    ): SleepPolicyResult {
        var result: SleepPolicyResult? = null
        dataStore.edit { preferences ->
            result = evaluateSleepEvidence(
                initialState = stateFrom(preferences),
                observations = observations,
                settings = settingsFrom(preferences),
            )
            preferences.write(requireNotNull(result).state)
        }
        return requireNotNull(result)
    }

    suspend fun clearCandidate(processedThroughEpochMillis: Long) {
        require(processedThroughEpochMillis >= 0)
        dataStore.edit { preferences ->
            preferences.clearCandidate()
            val existingWatermark = preferences[LAST_PROCESSED_AT_KEY]
            preferences[LAST_PROCESSED_AT_KEY] = maxOf(
                existingWatermark ?: Long.MIN_VALUE,
                processedThroughEpochMillis,
            )
        }
    }

    suspend fun resetForClockChange() {
        dataStore.edit { preferences ->
            preferences.clearCandidate()
            preferences.remove(LAST_PROCESSED_AT_KEY)
        }
    }

    private fun settingsFrom(preferences: Preferences) = OvernightSleepSettings(
        startMinuteOfDay = preferences[SLEEP_WINDOW_START_KEY]
            ?: DEFAULT_SLEEP_WINDOW_START_MINUTE,
        endMinuteOfDay = preferences[SLEEP_WINDOW_END_KEY]
            ?: DEFAULT_SLEEP_WINDOW_END_MINUTE,
    )

    private fun stateFrom(preferences: Preferences) = SleepPolicyState(
        candidateStartedAtEpochMillis = preferences[CANDIDATE_STARTED_AT_KEY],
        lastQualifyingAtEpochMillis = preferences[LAST_QUALIFYING_AT_KEY],
        lastProcessedAtEpochMillis = preferences[LAST_PROCESSED_AT_KEY],
    )

    private fun MutablePreferences.write(state: SleepPolicyState) {
        setOrRemove(CANDIDATE_STARTED_AT_KEY, state.candidateStartedAtEpochMillis)
        setOrRemove(LAST_QUALIFYING_AT_KEY, state.lastQualifyingAtEpochMillis)
        setOrRemove(LAST_PROCESSED_AT_KEY, state.lastProcessedAtEpochMillis)
    }

    private fun MutablePreferences.clearCandidate() {
        remove(CANDIDATE_STARTED_AT_KEY)
        remove(LAST_QUALIFYING_AT_KEY)
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<Long>, value: Long?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
        val SLEEP_WINDOW_START_KEY = intPreferencesKey("sleep_window_start_minute")
        val SLEEP_WINDOW_END_KEY = intPreferencesKey("sleep_window_end_minute")
        val CANDIDATE_STARTED_AT_KEY = longPreferencesKey("sleep_candidate_started_at_millis")
        val LAST_QUALIFYING_AT_KEY = longPreferencesKey("sleep_last_qualifying_at_millis")
        val LAST_PROCESSED_AT_KEY = longPreferencesKey("sleep_last_processed_at_millis")
    }
}
