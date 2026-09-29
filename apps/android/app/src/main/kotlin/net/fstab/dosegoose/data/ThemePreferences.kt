package net.fstab.dosegoose.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import net.fstab.dosegoose.app.ThemeMode

internal val Context.settingsDataStore by preferencesDataStore(name = "settings")

class ThemePreferences(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[THEME_MODE_KEY]
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: ThemeMode.System
        }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { preferences -> preferences[THEME_MODE_KEY] = themeMode.name }
    }

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }
}
