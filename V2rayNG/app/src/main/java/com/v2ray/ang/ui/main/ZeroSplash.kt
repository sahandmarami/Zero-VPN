package com.v2ray.ang.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R

/** What the Zero VPN launch loading screen is currently doing. */
enum class ZeroSplashPhase { Checking, Downloading, Ready }

data class ZeroSplashState(
    val visible: Boolean = true,
    val phase: ZeroSplashPhase = ZeroSplashPhase.Checking,
    /** Embedded Xray core version, e.g. "25.9.11". */
    val coreVersion: String? = null,
    /** True when the embedded core matches the latest official release. */
    val coreUpToDate: Boolean? = null,
    /** Download progress percent while fetching an app/core update. */
    val downloadProgress: Int = 0,
    /** Version string of the update APK that finished downloading. */
    val downloadedVersion: String? = null,
)

/**
 * Zero VPN launch loading screen: brand mark with pulsing neon glow, a live
 * status line and progress. On every open it checks the official sources and,
 * when a newer build (carrying the newest Xray core) exists, downloads it
 * right here — afterwards the app opens and the update can be installed.
 */
@Composable
fun ZeroSplashScreen(
    state: ZeroSplashState,
    onInstallUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = rememberInfiniteTransition(label = "splashPulse")
    val glowT by pulse.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI.toFloat()),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "splashGlow"
    )

    // Fade the status caption smoothly when the text changes.
    val statusText = when (state.phase) {
        ZeroSplashPhase.Checking -> stringResource(R.string.zero_splash_checking)
        ZeroSplashPhase.Downloading -> stringResource(R.string.zero_splash_downloading)
        ZeroSplashPhase.Ready -> stringResource(R.string.zero_splash_ready)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05070E)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Pulsing neon glow behind the brand mark
            Canvas(modifier = Modifier.size(220.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colorZeroNeon.copy(alpha = 0.22f + 0.08f * sin(glowT)),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width / 2f
                    )
                )
            }
            Image(
                painter = painterResource(R.drawable.zero_logo),
                contentDescription = null,
                modifier = Modifier.size(104.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.app_name),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.zero_splash_version, BuildConfig.VERSION_NAME),
            color = Color(0xFF7C8CA6),
            fontSize = 12.sp
        )

        Spacer(Modifier.height(34.dp))

        when (state.phase) {
            ZeroSplashPhase.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.downloadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 56.dp)
                        .height(5.dp),
                    color = colorZeroNeonSoft,
                    trackColor = Color(0xFF14202F),
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "$statusText  ${state.downloadProgress}%",
                    color = Color(0xFFB7C4D6),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Three breathing dots as the working indicator
                    repeat(3) { i ->
                        val dotT by pulse.animateFloat(
                            initialValue = 0.25f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 650, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = androidx.compose.animation.core.StartOffset(i * 180)
                            ),
                            label = "splashDot$i"
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 5.dp)
                                .size(9.dp)
                                .alpha(dotT)
                                .background(
                                    color = colorZeroNeonSoft,
                                    shape = RoundedCornerShape(50)
                                )
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = statusText,
                        color = Color(0xFFB7C4D6),
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Core status line
        val coreText = when {
            state.coreVersion == null -> null
            state.coreUpToDate == true ->
                stringResource(R.string.zero_splash_core_ok, state.coreVersion)
            else -> stringResource(R.string.zero_splash_core_old, state.coreVersion)
        }
        if (coreText != null) {
            Text(
                text = coreText,
                color = Color(0xFF6E7F99),
                fontSize = 11.sp
            )
        }

        // Install button once the newest build (with the latest core) is here.
        if (state.downloadedVersion != null) {
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onInstallUpdate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorZeroNeon,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = stringResource(R.string.zero_install_update),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun sin(t: Float): Float = kotlin.math.sin(t).toFloat()
