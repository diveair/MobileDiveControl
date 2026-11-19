package com.mobiledivecontrol.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.branding.NauticaseBranding
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.delay

/**
 * The welcome card shown first on every launch: logo, "Welcome to" / "NAUTICASE", and the one physical
 * instruction that has to happen before anything else — put the phone in and seal the housing.
 *
 * It owns its own clock: after [durationMs] it calls [onFinished] exactly once, and only then
 * does MainActivity start Android's permission prompts. Taps are swallowed rather than routed to
 * the intro underneath, so a stray touch while sliding the phone into the housing cannot start
 * permission setup early.
 *
 * Sized from the screen rather than fixed dp so the stack fits every landscape phone without
 * scrolling: the whole layout is drawn against a 720x360 reference and scaled to fit.
 */
@Composable
fun WelcomeSplashScreen(
    modifier: Modifier = Modifier,
    durationMs: Long = NauticaseBranding.WELCOME_SCREEN_DURATION_MS,
    onFinished: () -> Unit = {},
) {
    val finish by rememberUpdatedState(onFinished)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(WELCOME_FADE_IN_MS))
    }
    LaunchedEffect(durationMs) {
        delay(durationMs.coerceAtLeast(0L))
        finish()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        val scale = minOf(maxWidth / REFERENCE_WIDTH_DP.dp, maxHeight / REFERENCE_HEIGHT_DP.dp)
            .coerceIn(MIN_SCALE, MAX_SCALE)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer { alpha = reveal.value },
        ) {
            Image(
                painter = painterResource(NauticaseBranding.LOGO),
                contentDescription = "${NauticaseBranding.APP_NAME} logo",
                modifier = Modifier.size((LOGO_DP * scale).dp),
            )
            Spacer(Modifier.height((10 * scale).dp))
            Text(
                text = NauticaseBranding.WELCOME_LINE,
                color = DiveColors.TextSecondary,
                fontSize = (20 * scale).sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.08.em,
                textAlign = TextAlign.Center,
            )
            Text(
                text = NauticaseBranding.APP_NAME,
                color = DiveColors.TextPrimary,
                fontSize = (50 * scale).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.22.em,
                textAlign = TextAlign.Center,
                // Wide tracking adds trailing space after the last glyph; pad the start to match
                // so the word sits optically centred under the logo.
                modifier = Modifier.padding(start = (11 * scale).dp),
            )
            Spacer(Modifier.height((22 * scale).dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .background(DiveColors.Warning, RoundedCornerShape((12 * scale).dp))
                    .padding(horizontal = (28 * scale).dp, vertical = (10 * scale).dp),
            ) {
                Text(
                    text = NauticaseBranding.WELCOME_PROMPT,
                    color = Color.Black,
                    fontSize = (22 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.04.em,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val WELCOME_FADE_IN_MS = 350
private const val REFERENCE_WIDTH_DP = 720f
private const val REFERENCE_HEIGHT_DP = 360f
private const val LOGO_DP = 132f
private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 1.6f
