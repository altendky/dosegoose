package net.fstab.dosegoose.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ThemeModeResolutionTest(
    private val mode: ThemeMode,
    private val systemIsDark: Boolean,
    private val expectedIsDark: Boolean,
) {
    @Test
    fun `resolves system and manual theme choices`() {
        assertEquals(expectedIsDark, mode.resolvesToDark(systemIsDark))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} with systemDark={1} resolves dark={2}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(ThemeMode.System, false, false),
            arrayOf(ThemeMode.System, true, true),
            arrayOf(ThemeMode.Light, false, false),
            arrayOf(ThemeMode.Light, true, false),
            arrayOf(ThemeMode.Dark, false, true),
            arrayOf(ThemeMode.Dark, true, true),
        )
    }
}
