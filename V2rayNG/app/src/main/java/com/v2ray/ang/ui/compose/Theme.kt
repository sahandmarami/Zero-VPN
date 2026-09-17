package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF0D9488), // Zero Teal
    onPrimary = Color(0xFFFFFFFF), // White
    primaryContainer = Color(0xFFCCFBF1), // Pale Teal
    onPrimaryContainer = Color(0xFF042F2E), // Deep Teal
    secondary = Color(0xFF0284C7), // Sky Blue
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFE0F2FE), // Pale Sky
    onSecondaryContainer = Color(0xFF082F49), // Deep Sky
    tertiary = Color(0xFF10B981), // Emerald
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFA7F3D0), // Pale Emerald
    onTertiaryContainer = Color(0xFF064E3B), // Dark Emerald
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFF6F8F9), // Off White
    onBackground = Color(0xFF191C1D), // Near Black
    surface = Color(0xFFFFFFFF), // White
    onSurface = Color(0xFF191C1D), // Near Black
    surfaceVariant = Color(0xFFECF1F2), // Cool Gray
    onSurfaceVariant = Color(0xFF40484A), // Dark Gray
    outline = Color(0xFF70787A), // Medium Gray
    outlineVariant = Color(0xFFDAE1E2), // Light Gray
    inverseSurface = Color(0xFF2B3132), // Dark Gray
    inverseOnSurface = Color(0xFFEDF2F2), // Very Light Gray
    inversePrimary = Color(0xFF5EEAD4), // Teal Glow
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF0D9488), // Zero Teal
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFF6F8F9), // Very Light Gray
    surfaceContainer = Color(0xFFF0F3F4), // Light Gray
    surfaceContainerHigh = Color(0xFFEAEDEE), // Light Gray
    surfaceContainerHighest = Color(0xFFE4E8E9), // Light Gray
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF2DD4BF), // Zero Teal Glow
    onPrimary = Color(0xFF042F2E), // Deep Teal
    primaryContainer = Color(0xFF0F766E), // Teal Deep
    onPrimaryContainer = Color(0xFFCCFBF1), // Pale Teal
    secondary = Color(0xFF38BDF8), // Sky Glow
    onSecondary = Color(0xFF082F49), // Deep Sky
    secondaryContainer = Color(0xFF0369A1), // Sky Deep
    onSecondaryContainer = Color(0xFFE0F2FE), // Pale Sky
    tertiary = Color(0xFF6EE7B7), // Emerald Glow
    onTertiary = Color(0xFF064E3B), // Dark Emerald
    tertiaryContainer = Color(0xFF059669), // Emerald Deep
    onTertiaryContainer = Color(0xFFA7F3D0), // Pale Emerald
    error = Color(0xFFFFB4AB), // Light Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = Color(0xFF101318), // Zero Black
    onBackground = Color(0xFFE4E8EA), // Light Gray
    surface = Color(0xFF16191E), // Gooey Surface
    onSurface = Color(0xFFE4E8EA), // Light Gray
    surfaceVariant = Color(0xFF232830), // Slate
    onSurfaceVariant = Color(0xFFC2C9CC), // Gray
    outline = Color(0xFF8C9498), // Grayish
    outlineVariant = Color(0xFF3A4148), // Dark Gray
    inverseSurface = Color(0xFFE4E8EA), // Light Gray
    inverseOnSurface = Color(0xFF16191E), // Gooey Surface
    inversePrimary = Color(0xFF0D9488), // Zero Teal
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF2DD4BF), // Teal Glow
    surfaceContainerLowest = Color(0xFF0C0F13), // Near Black
    surfaceContainerLow = Color(0xFF14171C), // Dark
    surfaceContainer = Color(0xFF191D23), // Dark
    surfaceContainerHigh = Color(0xFF232830), // Slate
    surfaceContainerHighest = Color(0xFF2E343C), // Lighter Slate
)

// Semantic Colors
val colorPing = Color(0xFF22C55E) // Green
val colorPingRed = Color(0xFFEF4444) // Red
val colorConfigType = Color(0xFF38BDF8) // Sky Blue
val colorFabActive = Color(0xFF14B8A6) // Zero Teal
val colorFabInactiveLight = Color(0xFFB8BEC6) // Light Gray
val colorFabInactiveDark = Color(0xFF3A4048) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

// Toast Colors 70%
val toastNormalBgLight = Color(0xB3353A3E) // Dark Gray
val toastNormalBgDark = Color(0xB34A4F54) // Darker Gray
val toastSuccessBg = Color(0xB3388E3C) // Green
val toastErrorBg = Color(0xB3D50000) // Red
val toastInfoBg = Color(0xB33F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
