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
    primary = Color(0xFF0077D6), // Vivid Azure — deeper, livelier neon on bright surfaces
    onPrimary = Color(0xFFFFFFFF), // White
    primaryContainer = Color(0xFFC8E8FF), // Pale Neon
    onPrimaryContainer = Color(0xFF001D31), // Deep Navy
    secondary = Color(0xFF3B7BFF), // Electric Blue
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFDBE6FF), // Pale Electric
    onSecondaryContainer = Color(0xFF001945), // Deep Blue
    tertiary = Color(0xFF0093C9), // Neon Cyan
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFBFF0FF), // Pale Cyan
    onTertiaryContainer = Color(0xFF003442), // Deep Cyan
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFF4F8FE), // Azure White
    onBackground = Color(0xFF16202F), // Ink Navy
    surface = Color(0xFFFDFEFF), // Cloud White
    onSurface = Color(0xFF16202F), // Ink Navy
    surfaceVariant = Color(0xFFE3ECF8), // Cool Azure
    onSurfaceVariant = Color(0xFF41506A), // Slate Blue
    outline = Color(0xFF6E7F9C), // Medium Gray Blue
    outlineVariant = Color(0xFFD5E2F3), // Light Azure Line
    inverseSurface = Color(0xFF2B3240), // Dark Navy
    inverseOnSurface = Color(0xFFEDF2FA), // Very Light Blue
    inversePrimary = Color(0xFF00C8FF), // Neon Glow
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF0077D6), // Vivid Azure
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFF4F8FE), // Azure White
    surfaceContainer = Color(0xFFEDF3FB), // Pale Azure
    surfaceContainerHigh = Color(0xFFE8EFF9), // Light Azure
    surfaceContainerHighest = Color(0xFFE2EAF6), // Soft Azure
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF00A8F5), // Icon Neon Ring Blue
    onPrimary = Color(0xFF041224), // Deep Navy
    primaryContainer = Color(0xFF0A4A73), // Neon Deep
    onPrimaryContainer = Color(0xFFCBEFFF), // Pale Neon
    secondary = Color(0xFF4D8DFF), // Electric Blue Glow
    onSecondary = Color(0xFF00264F), // Deep Electric
    secondaryContainer = Color(0xFF1B4487), // Electric Deep
    onSecondaryContainer = Color(0xFFD6E3FF), // Pale Electric
    tertiary = Color(0xFF00E5FF), // Cyan Glow
    onTertiary = Color(0xFF003444), // Deep Cyan
    tertiaryContainer = Color(0xFF00718A), // Cyan Deep
    onTertiaryContainer = Color(0xFFB8F6FF), // Pale Cyan
    error = Color(0xFFFF5470), // Neon Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = Color(0xFF030813), // Zero Icon Navy
    onBackground = Color(0xFFDDE7F5), // Ice Blue White
    surface = Color(0xFF081428), // Deep Navy
    onSurface = Color(0xFFDDE7F5), // Ice Blue White
    surfaceVariant = Color(0xFF15223B), // Midnight Blue
    onSurfaceVariant = Color(0xFFA8BBD6), // Blue Gray
    outline = Color(0xFF5D7291), // Steel Blue
    outlineVariant = Color(0xFF273B58), // Dark Steel
    inverseSurface = Color(0xFFDDE7F5), // Ice Blue White
    inverseOnSurface = Color(0xFF0A101E), // Deep Navy
    inversePrimary = Color(0xFF35C6FF), // Neon Glow
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF00A8F5), // Icon Neon
    surfaceContainerLowest = Color(0xFF02060F), // Icon Void
    surfaceContainerLow = Color(0xFF081428), // Deep Navy
    surfaceContainer = Color(0xFF0C1A32), // Midnight
    surfaceContainerHigh = Color(0xFF12233E), // Midnight Blue
    surfaceContainerHighest = Color(0xFF1A2C4A), // Twilight Blue
)

// Semantic Colors
val colorPing = Color(0xFF4ADE80) // Neon Green
val colorPingRed = Color(0xFFFF5470) // Neon Red
val colorConfigType = Color(0xFF38BDF8) // Neon Sky
val colorFabActive = Color(0xFF00C8FF) // NEON Blue
val colorFabInactiveLight = Color(0xFFB8C4D6) // Light Blue Gray
val colorFabInactiveDark = Color(0xFF273B58) // Dark Steel
val dividerColorLight = Color(0xFFE0E8F2) // Light Blue Gray
val dividerColorDark = Color(0xFF273B58) // Dark Steel

// Zero VPN neon gradient tokens — sampled from the real icon
val colorNeonCyan = Color(0xFF35C6FF) // Icon Glow Cyan
val colorNeonBlue = Color(0xFF0E7BFF) // Electric Blue
val colorNeonGlow = Color(0xFF00A8F5) // Icon Ring Neon
val colorDisconnectRed = Color(0xFFFF3D71) // Neon Red
val colorDisconnectOrange = Color(0xFFFF7A45) // Neon Orange

// Toast Colors 70%
val toastNormalBgLight = Color(0xB3353A3E) // Dark Gray
val toastNormalBgDark = Color(0xB34A4F54) // Darker Gray
val toastSuccessBg = Color(0xB3388E3C) // Green
val toastErrorBg = Color(0xB3D50000) // Red
val toastInfoBg = Color(0xB33F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    // Zero VPN ships with the neon-dark look by default ("2" = dark).
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "2") ?: "2"
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
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "2") ?: "2"
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
