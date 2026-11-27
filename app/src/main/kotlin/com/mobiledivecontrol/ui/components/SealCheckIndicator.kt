package com.mobiledivecontrol.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import com.mobiledivecontrol.core.HousingSealTransitions.Location
import com.mobiledivecontrol.core.SLOW_LEAK_WARNING_PREFIX
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import com.mobiledivecontrol.core.SafetyState
import com.mobiledivecontrol.core.SafetyCommand
import com.mobiledivecontrol.core.VacuumReleaseChoice
import com.mobiledivecontrol.core.SealConfidence
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.theme.DiveColors
import com.mobiledivecontrol.ui.tutorial.GestureBlue
import kotlinx.coroutines.delay

/**
 * The vacuum seal check, rendered so it never costs the diver a shot.
 *
 * This deliberately is not a wizard. The seal check runs on the boat while people are already
 * framing the first shot, and a full-screen procedure would either be dismissed reflexively or
 * cover the viewfinder at the exact moment something swims past. So the whole seven-step workflow
 * lives in HUD elements that start as a prompt and get out of the way once they have an answer.
 *
 * Only two stages earn the full-width banner: [SealState.Vacuuming], because the motor is
 * physically running and the diver must be able to stop it, and [SealState.Failed], because
 * getting in the water with a leaking housing is the one outcome worth interrupting for.
 * Everything else is a chip.
 *
 * The five-minute hold is not here at all — it is a readout, not a prompt, so it lives in
 * [VacuumCountdownChip] up in the status row beside the temperature and the clock, where it can
 * sit for half an hour without ever being in the way.
 *
 * Renders nothing at all when there is no housing on the link, when the check has been dismissed,
 * or ten seconds after a result — the viewfinder is the product.
 */
val LocalSealBannerObserver = staticCompositionLocalOf<(Banner?) -> Unit> { {} }

@Composable
fun SealCheckIndicator(
    safety: SafetyState,
    housingConnected: Boolean,
    zeroDepth: Boolean,
    /** Where the top-anchored elements sit; the centred banners ignore it by design. */
    topPadding: Dp = 0.dp,
    /** Actual top-chip/banner height, so neighbouring HUD elements never guess its footprint. */
    onTopContentHeightChanged: (Int) -> Unit = {},
    showStatus: Boolean = true,
    /** Only the background edges remain visible while the full safety-stop card covers this banner. */
    coveredBackdrop: Color? = null,
    onCommand: (SafetyCommand) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // A result is an event, not a condition. It announces itself, then leaves — and announces
    // itself again each time the confidence tier climbs, which is the only way "5 min" ever becomes
    // "30 min" on screen without the chip camping there for half an hour.
    var resultVisible by remember { mutableStateOf(true) }
    LaunchedEffect(safety.sealState, safety.sealConfidence) {
        resultVisible = true
        if (safety.sealState == SealState.Passed) {
            delay(RESULT_VISIBLE_MS)
            resultVisible = false
        }
    }
    val holdStart = safety.leakMonitoringStartedAtEpochMs?.takeIf {
        housingConnected && safety.sealMonitor.banner == Banner.B16 &&
            (safety.sealState == SealState.LeakMonitoring || safety.sealState == SealState.Passed)
    }
    var phase by remember(holdStart) { androidx.compose.runtime.mutableIntStateOf(leakTestPhase(holdStart, System.currentTimeMillis())) }
    LaunchedEffect(holdStart) {
        if (holdStart == null) return@LaunchedEffect
        for (boundary in listOf(4_500L, PROVISIONAL_MS)) {
            delay((holdStart + boundary - System.currentTimeMillis()).coerceAtLeast(0L))
            phase = leakTestPhase(holdStart, System.currentTimeMillis())
        }
    }
    val stage = if (housingConnected) sealStage(safety, phase, zeroDepth) else null
    val visibleStage = stage?.takeUnless {
        safety.sealMonitor.banner == Banner.Status && (!resultVisible || !showStatus)
    }
    val hasTopContent = visibleStage != null && !visibleStage.centered
    LaunchedEffect(hasTopContent) {
        if (!hasTopContent) onTopContentHeightChanged(0)
    }
    val observer = LocalSealBannerObserver.current
    val displayed = safety.sealMonitor.banner.takeIf { visibleStage != null }
    DisposableEffect(observer, displayed) {
        observer(displayed)
        onDispose { observer(null) }
    }
    if (visibleStage == null) return

    Box(modifier = modifier) {
        when {
            visibleStage.centered -> {
                val release = displayed == Banner.Release || displayed == Banner.ConfirmRelease
                MessageBanner(
                    background = if (release) DiveColors.DeepBlack else (coveredBackdrop ?: visibleStage.background).copy(alpha = 0.94f),
                    modifier = Modifier.align(Alignment.Center),
                    border = if (release) BorderStroke(1.dp, DiveColors.DiveCyan.copy(alpha = 0.55f)) else null,
                ) {
                    when {
                        release -> VacuumReleaseBanner(safety, onCommand)
                        displayed == Banner.B15 -> InspectionBanner(visibleStage)
                        else -> CenteredSealBanner(visibleStage, isVacuumSetup = displayed == Banner.B03)
                    }
                }
            }

            visibleStage.fullWidth -> SealBanner(
                stage = visibleStage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding)
                    .onSizeChanged { onTopContentHeightChanged(it.height) }
                    .fillMaxWidth(),
            )

            else -> SealChip(
                stage = visibleStage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding)
                    .onSizeChanged { onTopContentHeightChanged(it.height) },
            )
        }
    }
}

@Composable
private fun vacuumHeadlineStyle() = MaterialTheme.typography.headlineSmall.copy(
    fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center)

@Composable
private fun vacuumDetailStyle() = MaterialTheme.typography.titleMedium.copy(
    fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center)

@Composable
private fun InspectionBanner(stage: SealStage) {
    val condensed = remember { FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD)) }
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AutoShrinkText(stage.headline, color = stage.foreground, maxLines = 1, modifier = Modifier.fillMaxWidth(),
            style = vacuumHeadlineStyle().copy(fontSize = 26.sp, lineHeight = 28.sp, letterSpacing = 0.5.sp))
        val detail = remember(stage.detail) { AnnotatedString(requireNotNull(stage.detail)) }
        val preferred = vacuumDetailStyle().copy(fontFamily = condensed, fontWeight = FontWeight.Bold,
            fontSize = 18.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = with(density) { 24.sp.toDp() }), contentAlignment = Alignment.Center) {
            val width = constraints.maxWidth
            val fitted = remember(detail, preferred, width, measurer, density) {
                val natural = measurer.measure(detail, style = preferred, softWrap = false, maxLines = 1).size.width
                val fit = ((width - 2).coerceAtLeast(1).toFloat() / natural.coerceAtLeast(1)).coerceAtMost(1f)
                // Keep the letters tall, with at most 10% extra condensation. Longer lines and
                // narrower devices take the remaining adjustment in font size.
                val stretch = fit.coerceAtLeast(0.9f)
                var style = preferred.copy(fontSize = with(density) { (preferred.fontSize.toPx() * fit / stretch).toSp() },
                    textGeometricTransform = TextGeometricTransform(scaleX = stretch))
                // Android rounds glyph advances at the final size. Verify the complete line at that size.
                val line = Constraints(maxWidth = (width - 2).coerceAtLeast(1))
                while (style.fontSize.value > 1f &&
                    measurer.measure(detail, style = style, constraints = line, maxLines = 1).hasVisualOverflow) {
                    style = style.copy(fontSize = (style.fontSize.value - 0.25f).sp)
                }
                style
            }
            Text(detail, color = stage.foreground, style = fitted, maxLines = 1,
                modifier = Modifier.fillMaxWidth())
        }
        Text(requireNotNull(stage.hint), color = stage.foreground.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 15.sp),
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun releaseHintStyle(asking: Boolean) = MaterialTheme.typography.labelSmall.copy(
    fontSize = if (asking) 12.sp else 10.sp, lineHeight = if (asking) 16.sp else 13.sp, letterSpacing = 0.sp)

@Composable
private fun releaseButtonStyle(asking: Boolean): TextStyle {
    val style = vacuumDetailStyle().copy(fontWeight = FontWeight.Bold)
    if (asking) return style
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { releaseButtonMaxWidth.roundToPx() - 2 * releaseButtonPadding.roundToPx() }
    return remember(measurer, style, available) {
        var fitted = style
        while (fitted.fontSize.value > 11f &&
            measurer.measure(RELEASE_SETUP, style = fitted, softWrap = false).size.width > available) {
            fitted = fitted.copy(fontSize = (fitted.fontSize.value - 1f).sp)
        }
        fitted
    }
}

@Composable
private fun VacuumReleaseBanner(safety: SafetyState, onCommand: (SafetyCommand) -> Unit, modifier: Modifier = Modifier) {
    val asking = safety.sealMonitor.banner == Banner.ConfirmRelease
    val hintStyle = releaseHintStyle(asking)
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp)) {
        AutoShrinkText(if (asking) RELEASE_QUESTION else "VACUUM RELEASED", color = DiveColors.TextPrimary,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            maxLines = 1, modifier = Modifier.fillMaxWidth().offset(y = 3.dp))
        val buttonStyle = releaseButtonStyle(asking)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()) {
            Text("LEFT / RIGHT TO SELECT", color = DiveColors.TextPrimary, style = hintStyle,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(end = 12.dp))
            // Equal weights give both choices the width required by the longer label at the user's font scale.
            Row(Modifier.width(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VacuumReleaseChoice.entries.forEach { choice ->
                    val right = choice == VacuumReleaseChoice.RestartPump
                    val selected = if (asking) safety.sealMonitor.releaseAnswerYes == right else safety.vacuumReleaseChoice == choice
                    val foreground = if (selected) DiveColors.DeepBlack else DiveColors.TextPrimary
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).widthIn(min = if (asking) 76.dp else 118.dp)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                onCommand(if (asking) SafetyCommand.SelectReleaseAnswer(right) else SafetyCommand.SelectVacuumReleaseChoice(choice))
                            }.heightIn(min = 48.dp),
                    ) {
                        Text(if (asking) (if (right) "YES" else "NO") else (if (right) RELEASE_SETUP else "SHUT DOWN"),
                            color = foreground, maxLines = 1, modifier = Modifier.fillMaxWidth()
                                .background(if (selected) DiveColors.DiveCyan else Color.Transparent, RoundedCornerShape(5.dp))
                                .border(1.dp, if (selected) DiveColors.DiveCyan else DiveColors.TextPrimary.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                                .padding(horizontal = releaseButtonPadding, vertical = 4.dp),
                            style = buttonStyle)
                    }
                }
            }
            Box(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("MENU/OK CONFIRMS", color = DiveColors.DiveCyan, style = hintStyle,
                    modifier = Modifier.clickable(role = Role.Button) {
                        onCommand(if (asking) SafetyCommand.ConfirmReleaseAnswer else SafetyCommand.ConfirmVacuumReleaseChoice)
                    }.padding(vertical = 3.dp))
            }
        }
    }
}

/** Half-period of the flashing headline accent: 2 sharp blinks per second, readable at arm's length. */
private const val ACCENT_FLASH_MS = 450L

/** The bright phase of a flashing tint span — full-saturation electric blue, not a pastel wash. */
private val TINT_FLASH_BRIGHT = Color(0xFF00D8FF)

/**
 * Half-period of the tint blink — deliberately slower than [ACCENT_FLASH_MS], so DO NOT DIVE
 * reads as the urgent signal and the cap as the steady pointer. Coprime-ish with the accent's
 * period so the two flashes drift instead of ever settling into lockstep.
 */
private const val TINT_FLASH_MS = 700L

/**
 * [full] with each listed substring recoloured — the flashing DO-NOT-DIVE span, the cap named in
 * its own blue. Every occurrence of each, applied left to right; overlaps are a caller bug.
 */
private fun accented(full: String, spans: List<Pair<String, SpanStyle>>) = buildAnnotatedString {
    val found = spans
        .flatMap { (text, style) ->
            generateSequence(full.indexOf(text)) { prev -> full.indexOf(text, prev + 1) }
                .takeWhile { it >= 0 }
                .map { Triple(it, text, style) }
        }
        .sortedBy { it.first }
    var cursor = 0
    for ((start, text, style) in found) {
        if (start < cursor) continue
        append(full.substring(cursor, start))
        withStyle(style) { append(text) }
        cursor = start + text.length
    }
    append(full.substring(cursor))
}

/**
 * The centred ask: one banner, one instruction, one button that answers it.
 *
 * Solid fill rather than the translucent chip treatment — this sits over the middle of the
 * viewfinder, and an instruction ghosted over moving water reads as part of the scene.
 */
@Composable
private fun CenteredSealBanner(stage: SealStage, isVacuumSetup: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        // Vacuum setup moves the space between lines above the title, keeping the dismissal hint anchored.
        if (isVacuumSetup) Spacer(Modifier.height(5.dp))
        // One line, always: a centred headline is a verdict, and a verdict that wraps reads as
        // two competing verdicts. The shrink search absorbs the length instead.
        val headlineStyle = vacuumHeadlineStyle()
        // One toggle drives every flashing accent on the banner. The accent flashes white/black
        // rather than sitting in a static colour: no single colour stands out against both the
        // caution amber and the critical red, but alternation does — the movement is the signal.
        // Plain coroutine toggle, not an Animatable — animator scale is 0 on real dive phones
        // and must not freeze this.
        var accentBright by remember { mutableStateOf(true) }
        if (stage.headlineAccent != null || stage.detailAccent != null) {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(ACCENT_FLASH_MS)
                    accentBright = !accentBright
                }
            }
        }
        // The tint span blinks on its own, faster clock, deliberately out of step with the
        // white/black accent — the two phrases are separate signals and must read as such.
        var tintBright by remember { mutableStateOf(false) }
        if (stage.detailTint != null) {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(TINT_FLASH_MS)
                    tintBright = !tintBright
                }
            }
        }
        val accentColor = if (accentBright) Color.White else DiveColors.DeepBlack

        if (stage.headlineAccent != null && stage.headline.contains(stage.headlineAccent)) {
            AutoShrinkText(
                text = accented(
                    stage.headline,
                    listOf(stage.headlineAccent to SpanStyle(color = accentColor)),
                ),
                color = stage.foreground,
                style = headlineStyle,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AutoShrinkText(
                text = stage.headline,
                color = stage.foreground,
                style = headlineStyle,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        stage.detail?.let { detail ->
            Spacer(modifier = Modifier.height(if (isVacuumSetup) 1.dp else 4.dp))
            val detailStyle = vacuumDetailStyle()
            val detailSpans = listOfNotNull(
                stage.detailAccent?.let { it to SpanStyle(color = accentColor) },
                stage.detailTint?.let { (text, base) ->
                    text to SpanStyle(
                        color = if (tintBright) TINT_FLASH_BRIGHT else base,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
            if (detailSpans.isNotEmpty()) {
                AutoShrinkText(
                    text = accented(detail, detailSpans),
                    color = stage.foreground,
                    style = detailStyle,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AutoShrinkText(
                    text = detail,
                    color = stage.foreground,
                    style = detailStyle,
                    // Three lines, not two: the stall diagnosis names a cause AND a remedy, and
                    // truncating the remedy leaves the diver with only the bad news.
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        stage.columns?.let { (left, right) -> SealBannerColumns(left, right, stage.foreground) }
        stage.progress?.let { fraction -> VacuumProgress(fraction, stage.foreground) }
        stage.hint?.let { hint ->
            if (!isVacuumSetup) Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = hint,
                color = stage.foreground.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Two next-moves side by side, divided down the middle so neither reads as a caption of the
 * other. Each stack's first line is its own small headline; the lines beneath it explain.
 */
@Composable
private fun SealBannerColumns(left: List<String>, right: List<String>, foreground: Color) {
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        SealBannerColumn(left, foreground, Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(foreground.copy(alpha = 0.35f), RoundedCornerShape(1.dp)),
        )
        SealBannerColumn(right, foreground, Modifier.weight(1f))
    }
}

@Composable
private fun SealBannerColumn(lines: List<String>, foreground: Color, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 10.dp),
    ) {
        lines.forEachIndexed { index, line ->
            AutoShrinkText(
                text = line,
                color = if (index == 0) foreground else foreground.copy(alpha = 0.85f),
                style = if (index == 0) {
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                },
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            if (index == 0) Spacer(modifier = Modifier.height(3.dp))
        }
    }
}

/**
 * Full-width treatment. Reserved for "the motor is running" and "do not get in the water".
 *
 * Headline and detail both auto-shrink rather than clip: "SEAL FAILED — LEAK DETECTED" at 22 sp
 * with letter spacing is wider than a portrait phone, and the half of that sentence which fits
 * ("SEAL FAILED — LEAK DETE") is not a usable warning.
 */
@Composable
private fun SealBanner(stage: SealStage, modifier: Modifier = Modifier) {
    MessageBanner(stage.background.copy(alpha = 0.92f), modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            AutoShrinkText(
                text = stage.headline,
                color = stage.foreground,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            stage.detail?.let { detail ->
                AutoShrinkText(
                    text = detail,
                    color = stage.foreground,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            stage.progress?.let { fraction -> VacuumProgress(fraction, stage.foreground) }
            stage.hint?.let { hint ->
                Text(
                    text = hint,
                    color = stage.foreground.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Chip treatment — an accent bar, the instruction, and the button that clears it.
 *
 * Headline and hint stack instead of sharing a line. Side by side in a [Row] the headline takes
 * the width it wants and the hint is squeezed to whatever is left, which on a narrow screen is
 * nothing — the diver would see "TARGET REACHED — CLOSE THE BLUE CAP" and no clue that a button
 * exists to clear it. Two short lines cost a few dp of viewfinder; a hidden affordance costs the
 * chip its purpose.
 */
@Composable
private fun SealChip(stage: SealStage, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                color = DiveColors.DeepBlack.copy(alpha = 0.72f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (!stage.plainChip) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (stage.hint == null) 16.dp else 28.dp)
                    .background(stage.background, RoundedCornerShape(2.dp)),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column {
            AutoShrinkText(
                text = stage.headline,
                color = stage.background,
                style = MaterialTheme.typography.titleMedium,
            )
            stage.hint?.let { hint ->
                AutoShrinkText(
                    text = hint,
                    color = DiveColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    minFontSize = 9.sp,
                )
            }
        }
    }
}

/** Thin progress rail. The number above it is the truth; this is only for pace. */
@Composable
private fun VacuumProgress(fraction: Float, foreground: Color) {
    Spacer(modifier = Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(foreground.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(foreground, RoundedCornerShape(2.dp)),
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * @param fullWidth whether this stage has earned the banner. See the class doc — only two do.
 */
private data class SealStage(
    val headline: String,
    val detail: String? = null,
    val hint: String? = null,
    val progress: Float? = null,
    val background: Color,
    val foreground: Color = DiveColors.DeepBlack,
    val fullWidth: Boolean = false,
    /** True for the two stages that ask for a button press; they render dead-centre. */
    val centered: Boolean = false,
    /**
     * Substring of [headline] to flash white/black — for the caution banner whose headline must
     * carry "DO NOT DIVE" louder than the amber around it can manage with any single colour. A
     * headline with an accent also refuses to wrap: the phrase is the message, and half of it on
     * another line is a different message.
     */
    val headlineAccent: String? = null,
    /** Same flashing treatment inside [detail], for the leak banner's DO NOT DIVE opener. */
    val detailAccent: String? = null,
    /** Chips only: drop the accent bar and let the coloured headline stand alone. */
    val plainChip: Boolean = false,
    /** Substring of [detail] in a steady colour of its own — the cap named in cap blue. */
    val detailTint: Pair<String, Color>? = null,
    /**
     * Two side-by-side line stacks under the headline, for the one banner that offers two
     * equally valid next moves. First line of each stack is its own headline.
     */
    val columns: Pair<List<String>, List<String>>? = null,
)

/**
 * Maps safety state to exactly one HUD element, or to nothing.
 *
 * Order matters: a failure outranks everything, and a running motor outranks a prompt.
 */
internal fun leakTestPhase(start: Long?, now: Long): Int = when {
    start == null || now - start < 4_500L -> 0
    now - start < PROVISIONAL_MS -> 1
    else -> 2
}

private fun sealStage(safety: SafetyState, holdPhase: Int, zeroDepth: Boolean): SealStage? {
    val m = safety.sealMonitor
    val earlyFailure = (safety.restartFailAgoMinutes ?: m.lostWhileOfflineMinutes)?.takeIf { it >= 0 }?.let {
        if (it < 60) "SEAL FAILED ($it min)" else "SEAL FAILED (${it / 60}h ${it % 60}m)"
    }
    fun warning(title: String, detail: String, hint: String? = null, critical: Boolean = false,
                background: Color = if (critical) DiveColors.Critical else DiveColors.Warning): SealStage =
        SealStage(headline = title, detail = listOfNotNull(earlyFailure, detail).joinToString("\n"),
            hint = hint, centered = true, foreground = if (critical) Color.White else DiveColors.DeepBlack,
            background = background)
    val setup = "DOWN for vacuum setup · MENU/OK acknowledges"
    return when (m.banner) {
        Banner.B00, Banner.B01 -> null
        Banner.Release, Banner.ConfirmRelease -> SealStage(headline = "VACUUM RELEASED", background = DiveColors.DiveCyan, centered = true)
        Banner.B03 -> SealStage(headline = "ESTABLISH VACUUM", detail = if (zeroDepth)
            "Press DOWN to start vacuum pump." else RETURN_TO_SURFACE,
            hint = if (m.inspected && safety.coverOpen != true) "Open the blue cap before starting the test. Press MENU/OK to dismiss."
                else "Press MENU/OK to dismiss.",
            centered = true, foreground = DiveColors.DeepBlack, background = DiveColors.Warning)
        Banner.B04 -> warning("CAUTION — CHECK VACUUM SEAL", "Dive not recommended. Check O-ring, blue cap & silver air drain valve." +
            if (safety.warning?.startsWith(SLOW_LEAK_WARNING_PREFIX) == true) " Housing light may still show green." else "", setup)
        Banner.B05 -> warning("VACUUM INACTIVE — DO NOT DIVE", "Check seal. Press DOWN for vacuum setup.", "MENU/OK acknowledges")
        Banner.B06 -> warning("CAUTION — RETURN TO SURFACE", "Possible vacuum leak. Surface safely. Remedy any water ingress.", "MENU/OK dismisses")
        Banner.B07 -> warning("WARNING — VACUUM SEAL FAILURE", "Surface safely. Inspect housing seal and remedy any water ingress.", "MENU/OK acknowledges", true)
        Banner.B08 -> warning("CAUTION — SEAL INTEGRITY UNCONFIRMED", "Surface safely. Inspect housing for water ingress.", "MENU/OK dismisses")
        Banner.B09 -> warning("WARNING — NON-SEALED DIVE DETECTED", "Inspect housing seal and remedy any water ingress.", setup, true)
        Banner.B10 -> warning("WARNING — VACUUM NOT ESTABLISHED", "Surface safely and inspect housing seal. Remedy water ingress.", "MENU/OK acknowledges", true)
        Banner.B11 -> warning("CAUTION — VACUUM STATUS UNKNOWN", "Seal integrity cannot be confirmed. Surface safely if underwater.")
        Banner.B12 -> warning("CAUTION — DEPTH STATUS UNKNOWN", "Depth unavailable. Confirm surface before any vacuum operation.")
        Banner.B13 -> warning("CAUTION — INSPECT VACUUM SEAL", "Possible underwater seal loss. Inspect housing at surface and remedy any water ingress.", setup)
        Banner.B14 -> warning("WARNING — INSPECT HOUSING", "Vacuum integrity may have failed underwater. Remedy any water ingress.", setup, true)
        Banner.B15 -> SealStage(headline = "HOUSING SEAL CHECK REQUIRED", detail = when {
            m.location == Location.Underwater -> "SURFACE SAFELY, REMEDY ANY WATER INGRESS, INSPECT O-RING, BLUE CAP & SILVER AIR DRAIN VALVE"
            m.submergedSinceSealCheck -> "REMEDY ANY WATER OR SALT INGRESS, INSPECT O-RING, BLUE CAP & SILVER AIR DRAIN VALVE"
            else -> "INSPECT O-RING INTEGRITY, CHECK BLUE CAP & SILVER AIR DRAIN VALVE FOR LEAKS"
        }, hint = "then press MENU/OK to confirm", centered = true, background = DiveColors.Warning)
        Banner.B16 -> if (holdPhase > 0) SealStage(headline = "LEAK TEST", plainChip = true,
            background = if (holdPhase == 1) DiveColors.Critical else Color.Yellow)
        else SealStage(headline = "VACUUM LEAK TEST", detail = when (safety.sealState) {
            SealState.Vacuuming -> vacuumDetail(safety)
            SealState.MotorStopping, SealState.WaitingForCoverClosed -> "CLOSE THE BLUE CAP"
            else -> "Checking seal hold. Keep housing out of water."
        }, hint = if (safety.sealState == SealState.Vacuuming) "Keep housing out of water. DOWN cancels." else null,
            background = DiveColors.Warning, centered = true)
        Banner.B17 -> warning("LEAK TEST FAILED — DO NOT DIVE", "Change O-ring. Inspect housing for damage." +
            if (m.failures >= 4) " Contact manufacturer." else "", "DOWN to recheck at surface",
            critical = true, background = DiveColors.CriticalDim)
        Banner.B18 -> SealStage(headline = "LEAK TEST PASSED", hint = "Vacuum stable. Normal mode.", background = DiveColors.Success)
        Banner.B19 -> warning("LEAK TEST INTERRUPTED", "Return to surface, reconnect and repeat test.", "DOWN to recheck at surface")
        Banner.Cap -> warning("SEAL NOT CHECKED", "OPEN THE BLUE CAP TO START")
        Banner.NoSuction -> warning("NO VACUUM SUCTION DETECTED", "Pump stopped. Unscrew blue cap fully. If already off, check housing seal.", setup)
        Banner.NotBuilding -> warning("VACUUM NOT BUILDING", "Pump stopped. Check O-ring and silver air drain valve.", "At surface, press DOWN for leak check.")
        Banner.Pumping -> SealStage(headline = "VACUUM PUMPING", detail = vacuumDetail(safety),
            hint = "DOWN cancels", progress = vacuumProgress(safety), centered = true, background = DiveColors.Warning)
        Banner.Reached -> SealStage(headline = "VACUUM REACHED", detail = "CLOSE THE BLUE CAP",
            hint = CAMERA_CONTROLS_HINT, centered = true, background = DiveColors.DiveCyan)
        Banner.Uncertain -> SealStage(headline = "SEAL UNCERTAIN", hint = safety.warning ?: "Re-run the check", background = DiveColors.Warning)
        Banner.Status -> if (safety.sealState == SealState.Passed) SealStage(headline = passedLabel(safety),
            background = if (safety.sealConfidence <= SealConfidence.Provisional) DiveColors.Warning else DiveColors.Success,
            plainChip = true) else null
    }
}

private fun passedLabel(safety: SafetyState): String {
    // Never a bare "PASSED". The elapsed qualifier is the evidence, and hiding it would let a
    // three-minute hold read exactly like a thirty-minute one. Real minutes, not tier names:
    // a hold restored at 7 minutes says "(7 min)", and a restart never rounds the evidence
    // down to the last tier it crossed.
    val minutes = safety.leakMonitoringElapsedMs / 60_000L
    return when {
        safety.sealConfidence == SealConfidence.Monitoring -> "SEAL OK"
        minutes < 5 -> "SEAL OK ($minutes min)"
        else -> "SEAL PASSED ($minutes min)"
    }
}

private fun vacuumDetail(safety: SafetyState): String {
    val drop = pressureDropKpa(safety) ?: return "Reading pressure…"
    return "-%.1f kPa of -%.0f kPa".format(drop, VACUUM_TARGET_DELTA_KPA)
}

private fun vacuumProgress(safety: SafetyState): Float? {
    val drop = pressureDropKpa(safety) ?: return null
    return (drop / VACUUM_TARGET_DELTA_KPA).toFloat()
}

/**
 * How far below surface ambient the shell has been pulled.
 *
 * Measured against the baseline captured with the cover open, never against a fixed constant: the
 * check has to work the same on a boat at sea level and on a lake at altitude.
 */
private fun pressureDropKpa(safety: SafetyState): Double? {
    val current = safety.barometricPressureKpa ?: return null
    val baseline = safety.baselinePressureKpa ?: safety.surfaceAmbientKpa ?: return null
    return (baseline - current).coerceAtLeast(0.0)
}

// --- The hold: countdown, then countdown with a badge, then just the badge ---

/**
 * The five-minute hold, as a status-row readout rather than a prompt.
 *
 * The hold is the actual measurement, and it runs for minutes while the diver is already shooting,
 * so it is styled like the depth and temperature pills instead of like an alert: no banner, no
 * button hint, nothing that has to be acknowledged. It reports three things in sequence, because
 * "how long until this means something" has two different answers:
 *
 * 1. **3:00 down** — the manufacturer's provisional tier. Reaching it is what promotes the seal to
 *    [SealState.Passed], so this is the number the diver is actually waiting on.
 * 2. **badge + 2:00 down** — the remainder to the 5-minute hard verify. The badge appears here
 *    because the seal has already passed; the number is only the evidence still accumulating.
 * 3. **solid badge** — five clean minutes, nothing left to count. This is the state the chip
 *    spends the rest of the dive in.
 *
 * Deliberately not dismissable and deliberately blind to `checkDismissed`: that flag silences
 * prompts, and silencing a prompt must not silence the instrument that says the housing is dry.
 *
 * Failure, a skipped check and a reset all remove it — none of them leaves a hold to report.
 */
@Composable
fun VacuumCountdownChip(
    safety: SafetyState,
    housingConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!housingConnected) return

    if (safety.sealMonitor.dismissedVacuumSetup) {
        Column(
            modifier = modifier.background(DiveColors.DeepBlack.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("DO NOT DIVE", color = DiveColors.Critical, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold)
            Text("Use Menu to Establish Seal", color = DiveColors.TextPrimary, style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    // The cluster exists whenever there is a vacuum worth reporting — the timer is only its
    // second act. MotorStopping and the cap wait have a live reading but no hold yet, and they
    // deserve the number on screen just as much as the monitored states do.
    val readout = vacuumReadout(safety)
    val holding = safety.sealState == SealState.LeakMonitoring || safety.sealState == SealState.Passed
    val startedAt = if (holding) safety.leakMonitoringStartedAtEpochMs else null
    if (readout == null && startedAt == null) return

    // Wall clock, not `leakMonitoringElapsedMs`: that only advances when a barometric sample
    // arrives, and a countdown that stalls for a few hundred ms reads as a frozen app.
    var nowMs by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    val elapsedMs = if (startedAt != null) (nowMs - startedAt).coerceAtLeast(0L) else 0L
    val counting = startedAt != null && elapsedMs < HARD_VERIFY_MS

    if (readout == null && !counting) return

    // The one ticker, and it exists only while there are digits to move. Once the hold is past
    // 5:00 the chip is just the name and the green reading — the reading's colour carries the
    // verdict for the rest of the dive. Leaving the branch cancels the coroutine.
    if (counting) {
        LaunchedEffect(startedAt ?: 0L) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    // Phase 1 counts to the 3-min promotion; after that the same chip counts the remainder to the
    // 5-min hard verify, which is a different question and so gets a different target.
    val remainingMs = when {
        !counting -> null
        elapsedMs < PROVISIONAL_MS -> PROVISIONAL_MS - elapsedMs
        else -> HARD_VERIFY_MS - elapsedMs
    }

    // Two decks: the name and the live reading on top, the countdown beneath them — the reading
    // is the fact, the timer is how long the fact has been trusted.
    // The word and the timer tell the trust story in colour: red while the first three minutes
    // are still on trial, amber through the 5-min hard verify, green once it is done. Before a
    // hold exists (cap wait) the name stays neutral — nothing has been promised yet.
    val phaseColor = when {
        startedAt == null -> DiveColors.TextSecondary
        elapsedMs < PROVISIONAL_MS -> DiveColors.Critical
        elapsedMs < HARD_VERIFY_MS -> DiveColors.Warning
        else -> DiveColors.Success
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // Matches the other status pills so this reads as one of them, not as an alert.
            .background(
                color = DiveColors.DeepBlack.copy(alpha = 0.62f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The chip names itself. Bare digits in the status row could be a stopwatch, a dive
            // timer or a recording length; a diver should never have to remember which pill is
            // which through a wet port.
            Text(
                text = "VACUUM",
                color = phaseColor,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                fontWeight = FontWeight.Bold,
            )
            if (readout != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    // A pulled vacuum is a pressure deficit, written negative — U+2212, not a
                    // hyphen, because it sits directly against digits.
                    text = "−%.1f kPa".format(readout.kpa),
                    color = if (readout.passed) DiveColors.Success else DiveColors.Warning,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (remainingMs != null) {
            Text(
                text = formatRemaining(remainingMs),
                color = phaseColor,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Ceiling division so the chip opens on a full 3:00 rather than flicking straight to 2:59, and so
 * 0:00 only appears at the instant the tier is actually reached.
 */
private fun formatRemaining(remainingMs: Long): String {
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Mirrors `SafetyThresholds.vacuumTargetDeltaKpa` for display only.
 *
 * The state machine still owns the pass/fail decision; this drives the progress bar, and a chip
 * that cannot compile is worse than one number written down twice.
 */
private const val VACUUM_TARGET_DELTA_KPA = 20.0

/** Mirrors `SafetyThresholds.provisionalMs` — 3 min, the tier that promotes the seal to Passed. */
private const val PROVISIONAL_MS = 180_000L

/** Mirrors `SafetyThresholds.manufacturerMinimumMs` — 5 min, the maker's stated hard verify. */
private const val HARD_VERIFY_MS = 300_000L

/** How long a result stays on screen before the viewfinder gets its space back. */
private const val RESULT_VISIBLE_MS = 10_000L

private const val RELEASE_QUESTION = "DID YOU RELEASE THE VACUUM?"
private const val RELEASE_SETUP = "VACUUM SETUP"
private val releaseButtonPadding = 6.dp
private val releaseButtonMaxWidth = 140.dp
private const val RETURN_TO_SURFACE = "Return to surface, then press DOWN to start vacuum pump."
private const val CAMERA_CONTROLS_HINT = "Press Menu/OK for camera controls"
