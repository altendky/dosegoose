package net.fstab.dosegoose.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import net.fstab.dosegoose.app.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F1D8),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4C635A),
    background = Color(0xFFF7FAF6),
    surface = Color(0xFFF7FAF6),
    surfaceVariant = Color(0xFFDCE5DF),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AD5BD),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFB5F1D8),
    secondary = Color(0xFFB3CCC0),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
    surfaceVariant = Color(0xFF3F4944),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DoseGooseTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.resolvesToDark(isSystemInDarkTheme())
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
