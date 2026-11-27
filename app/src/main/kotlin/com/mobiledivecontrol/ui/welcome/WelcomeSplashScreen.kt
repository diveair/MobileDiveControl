package com.mobiledivecontrol.ui.welcome

import com.mobiledivecontrol.ui.components.MessageBanner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
 * The welcome card leads every launch. On a fresh install the seal instruction appears on the
 * housing animation after permission setup. Returning users see it on this card.
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
    showSealInstruction: Boolean = true,
    durationMs: Long = NauticaseBranding.WELCOME_SCREEN_DURATION_MS,
    onFinished: () -> Unit = {},
) {
    val finish by rememberUpdatedState(onFinished)
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
        ) {
            Text(
                text = NauticaseBranding.WELCOME_LINE,
                color = DiveColors.TextSecondary,
                fontSize = (20 * scale).sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.08.em,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height((8 * scale).dp))
            val logo = painterResource(NauticaseBranding.LOGO)
            Image(
                painter = logo,
                contentDescription = NauticaseBranding.APP_NAME,
                modifier = Modifier.width((LOGO_WIDTH_DP * scale).dp)
                    .aspectRatio(logo.intrinsicSize.width / logo.intrinsicSize.height),
            )
            if (!showSealInstruction) return@Column
            Spacer(Modifier.height((22 * scale).dp))
            val reveal = remember { Animatable(0f) }
            LaunchedEffect(Unit) { reveal.animateTo(1f, tween(WELCOME_FADE_IN_MS)) }
            MessageBanner(DiveColors.Warning, modifier = Modifier.graphicsLayer { alpha = reveal.value }) {
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
private const val LOGO_WIDTH_DP = 350f
private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 1.6f
