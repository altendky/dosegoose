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
import net.fstab.dosegoose.app.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ThemePreferencesTest {
    private lateinit var preferencesFile: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferencesFile = File(context.cacheDir, "theme-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        preferencesFile.delete()
    }

    @Test
    fun missingPreferenceDefaultsToSystemTheme() = runBlocking {
        val preferences = preferences()

        assertEquals(ThemeMode.System, preferences.themeMode.first())
    }

    @Test
    fun eachExplicitThemeRoundTripsThroughDataStore() = runBlocking {
        val preferences = preferences()

        ThemeMode.entries.forEach { expected ->
            preferences.setThemeMode(expected)
            assertEquals(expected, preferences.themeMode.first())
        }
    }

    private fun preferences(): ThemePreferences = ThemePreferences(
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { preferencesFile },
        ),
    )
}
