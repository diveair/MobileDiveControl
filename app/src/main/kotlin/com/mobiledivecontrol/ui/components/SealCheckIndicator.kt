package com.mobiledivecontrol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiledivecontrol.core.NO_SUCTION_WARNING
import com.mobiledivecontrol.core.SLOW_LEAK_WARNING_PREFIX
import com.mobiledivecontrol.core.VACUUM_NOT_BUILDING_WARNING
import com.mobiledivecontrol.core.SafetyState
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
@Composable
fun SealCheckIndicator(
    safety: SafetyState,
    housingConnected: Boolean,
    /** Where the top-anchored elements sit; the centred banners ignore it by design. */
    topPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    if (!housingConnected) return
    val stage = sealStage(safety) ?: return

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
    if (safety.sealState == SealState.Passed && !resultVisible) return

    Box(modifier = modifier) {
        when {
            // The two ask-the-diver moments take the centre of the screen: they are the only seal
            // stages that exist purely to be answered, and centre is where an instruction that
            // owns the next button press belongs. Everything that merely *reports* stays at the
            // top edge where it cannot sit on the subject.
            stage.centered -> CenteredSealBanner(
                stage = stage,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(CENTERED_BANNER_WIDTH),
            )

            stage.fullWidth -> SealBanner(
                stage = stage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding)
                    .fillMaxWidth(),
            )

            else -> SealChip(
                stage = stage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding),
            )
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
private fun CenteredSealBanner(stage: SealStage, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(
                color = stage.background.copy(alpha = 0.94f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // One line, always: a centred headline is a verdict, and a verdict that wraps reads as
        // two competing verdicts. The shrink search absorbs the length instead.
        val headlineStyle = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
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
            Spacer(modifier = Modifier.height(4.dp))
            val detailStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
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
            Spacer(modifier = Modifier.height(2.dp))
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(
                color = stage.background.copy(alpha = 0.92f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
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
private fun sealStage(safety: SafetyState): SealStage? {
    // The adopted-vacuum reminder outranks the state branches for both monitoring AND passed:
    // adoption starts a clock that keeps promoting the seal on its own, and a diver who leaves
    // the intro up for three minutes must not have the cap question silently outrun by their own
    // seal getting healthier. It shows until Menu/OK answers it, whatever tier the hold reaches.
    val holding = safety.sealState == SealState.LeakMonitoring || safety.sealState == SealState.Passed
    if (holding && safety.capCloseReminder && !safety.checkDismissed) {
        return SealStage(
            headline = "VACUUM REACHED",
            detail = "CLOSE THE BLUE CAP",
            hint = "Press Menu/OK for camera controls",
            background = DiveColors.DiveCyan,
            centered = true,
        )
    }
    return sealStageFor(safety)
}

private fun sealStageFor(safety: SafetyState): SealStage? = when (safety.sealState) {

    // The confirm is UP, which `InputRouter` intercepts in this state: pressing it says "I have
    // checked and re-sealed", resets the seal to Unknown, and the normal start flow takes over.
    // These banners only ever mean an EARLY depressurisation — a shell that vents after the
    // provisional hold is treated as deliberately opened and never comes here.
    //
    // Which banner depends on what the app was TOLD about the cap. Menu/OK on the close-the-cap
    // banner (`checkDismissed`, which survives into the hold and is cleared by every path that
    // reopens the port) means the diver claimed a sealed housing — losing pressure after that
    // claim is a leak, red and unambiguous. Without that acknowledgement the cap was plausibly
    // still off and the release plausibly the diver's own hand on it, so the same event gets
    // caution colours and language that allows for both readings.
    SealState.Failed -> if (safety.restartFailAgoMinutes != null) {
        // The boot record promised a proven seal and today's readings disproved it: the vacuum
        // was lost while nobody was watching. The headline leads with how long ago the seal was
        // last verified good, because "failed" without "since when" is not actionable.
        val minutes = safety.restartFailAgoMinutes ?: -1L
        val ago = when {
            minutes < 0 -> null
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
        SealStage(
            headline = if (ago != null) "SEAL FAILED ($ago)" else "SEAL FAILED",
            detail = if (ago != null) {
                "DO NOT DIVE. Vacuum was lost while the app was closed.\nSeal last verified $ago ago.\nOpen Housing to check O-ring."
            } else {
                "DO NOT DIVE. Vacuum was lost while the app was closed.\nOpen Housing to check O-ring."
            },
            detailAccent = "DO NOT DIVE.",
            hint = "Re-seal Housing, then PRESS UP to re-establish vacuum",
            background = DiveColors.Critical,
            foreground = Color.White,
            centered = true,
        )
    } else if (safety.warning?.startsWith(SLOW_LEAK_WARNING_PREFIX) == true) {
        // The decay watch caught a leak the housing's own firmware alarm is too coarse to see —
        // its green light is probably STILL ON. Field case: a hair in the O-ring bled -21 to
        // -15 kPa with the light green throughout. Named head-on, because a red banner beside a
        // green light otherwise reads as the app being broken.
        SealStage(
            headline = "VACUUM SEAL RISK — SLOW LEAK",
            detail = "DIVE NOT RECOMMENDED. Housing light may still show green —\nthis monitor is more sensitive. Open Housing,\ncheck O-ring and Gray Air Drain Valve.",
            detailAccent = "DIVE NOT RECOMMENDED.",
            hint = "Re-seal Housing, then PRESS UP to re-establish vacuum",
            background = DiveColors.Critical,
            foreground = Color.White,
            centered = true,
        )
    } else if (safety.checkDismissed) {
        SealStage(
            headline = "VACUUM SEAL FAILED — LEAK DETECTED",
            detail = "DO NOT DIVE. Remove BLUE CAP.\nOpen Housing to check O-ring.",
            detailAccent = "DO NOT DIVE.",
            detailTint = "BLUE CAP" to GestureBlue,
            hint = "Re-seal Housing, then PRESS UP to re-establish vacuum",
            background = DiveColors.Critical,
            foreground = Color.White,
            centered = true,
        )
    } else {
        SealStage(
            headline = "VACUUM INACTIVE — DO NOT DIVE",
            headlineAccent = "DO NOT DIVE",
            detail = "If you did not release vacuum seal,\nOpen Housing to inspect O-ring.",
            hint = "Reseal housing, then PRESS UP to restore vacuum",
            background = DiveColors.Warning,
            centered = true,
        )
    }

    // Centred like the cap prompt that follows it: the pump-to-cap sequence is one conversation,
    // and its two banners appearing in the same spot means the diver's eyes never have to move.
    // A stalled pump never lingers here — the machine stops the motor and drops back to the
    // pre-pump state, where the diagnosis banners below take over.
    SealState.Vacuuming -> SealStage(
        headline = "VACUUM PUMPING",
        detail = vacuumDetail(safety),
        hint = "DOWN cancels",
        progress = vacuumProgress(safety),
        background = DiveColors.Warning,
        centered = true,
    )

    // The cap has to be back on before leak detection means anything, so both of these states say
    // the same physical thing. Showing it a beat early is better than a gap where nothing is asked.
    //
    // Dismissable, unlike the other in-flight stages: the pump has already reached target, so the
    // diver who screws the cap on without reading the chip loses nothing by silencing it first.
    SealState.MotorStopping,
    SealState.WaitingForCoverClosed,
        -> if (safety.checkDismissed) {
        null
    } else {
        SealStage(
            headline = "VACUUM REACHED",
            detail = "CLOSE THE BLUE CAP",
            hint = "Press Menu/OK for camera controls",
            background = DiveColors.DiveCyan,
            centered = true,
        )
    }

    // While the housing is being watched for leaks, the diver is shooting — the hold itself is
    // reported by `VacuumCountdownChip` in the status row, and the adopted-vacuum reminder is
    // already handled above the state branches.
    SealState.LeakMonitoring -> null

    // Amber until the hard verify: "SEAL OK (3 min)" is progress, not a verdict, and green on
    // it would let a three-minute hold wear the same colour as a proven one. No accent bar —
    // the announcement is the whole chip.
    SealState.Passed -> SealStage(
        headline = passedLabel(safety),
        background = when (safety.sealConfidence) {
            SealConfidence.Monitoring, SealConfidence.Provisional -> DiveColors.Warning
            else -> DiveColors.Success
        },
        plainChip = true,
    )

    SealState.Warning -> SealStage(
        headline = "SEAL UNCERTAIN",
        hint = safety.warning ?: "Re-run the check",
        background = DiveColors.Warning,
    )

    SealState.CoverOpen,
    SealState.ReadyToVacuum,
        -> when {
        // The machine just shut the pump off on its own; these banners are the only place the
        // diver learns why. Menu/OK retries (the router treats this state as a start prompt),
        // DOWN closes them.
        safety.warning == NO_SUCTION_WARNING && !safety.checkDismissed -> SealStage(
            headline = "NO VACUUM SUCTION DETECTED",
            detail = "Pump Stopped. Unscrew BLUE CAP Fully. Then\nPress Menu/OK to pump again. If BLUE CAP\nwas already off, Check Housing Seal.",
            detailTint = "BLUE CAP" to GestureBlue,
            hint = "DOWN closes this message",
            background = DiveColors.Warning,
            centered = true,
        )
        safety.warning == VACUUM_NOT_BUILDING_WARNING && !safety.checkDismissed -> SealStage(
            headline = "VACUUM NOT BUILDING",
            detail = "Pump Stopped. Air is leaking into Housing.\nCheck O-ring is clean/seated. Check Gray Air Drain Valve.\nSeal Housing. Next press Menu/OK to pump again.",
            hint = "DOWN closes this message",
            background = DiveColors.Warning,
            centered = true,
        )
        else -> startPrompt(safety)
    }

    SealState.Unknown -> when {
        safety.checkDismissed -> null
        // A deliberate release at the surface: the cap is already off, so the doorway that asks
        // the diver to remove it never shows. Instead this banner names the two legitimate next
        // moves — pump again, or open up and take the phone out. OK re-pumps (the router treats
        // this banner as a live start prompt); DOWN dismisses like every other centred banner.
        safety.vacuumReleasedPrompt -> SealStage(
            headline = "VACUUM RELEASED",
            columns = listOf(
                "BLUE CAP IS OFF",
                "Press Menu/OK to",
                "re-establish vacuum",
            ) to listOf(
                "YOU MAY OPEN",
                "the housing to",
                "remove your phone",
            ),
            background = DiveColors.DiveCyan,
            centered = true,
        )
        safety.coverOpen == true -> startPrompt(safety)
        // Cover state unknown means the housing has not told us anything yet; claiming the seal is
        // unchecked would be guessing, and the product does not guess about seal state.
        safety.coverOpen == false -> if (safety.verifiedVacuumKpa != null) null else SealStage(
            headline = "SEAL NOT CHECKED",
            hint = "OPEN THE BLUE CAP TO START",
            background = DiveColors.Warning,
        )
        else -> null
    }
}

/**
 * The one moment OK is safe to borrow from the camera: the cap is physically off, so the diver is
 * on the boat with the housing open, not shooting.
 */
private fun startPrompt(safety: SafetyState): SealStage? {
    if (safety.checkDismissed || safety.coverOpen != true) return null
    // A primed boot record awaiting its first pressure sample: the housing may be sealed and
    // about to prove it. Half a second of silence beats flashing a pump offer that adoption is
    // about to yank away.
    if (safety.verifiedVacuumKpa != null) return null
    return SealStage(
        headline = "PRESS MENU/OK TO START VACUUM PUMP",
        hint = "DOWN dismisses",
        background = DiveColors.Warning,
        centered = true,
    )
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

/** Centred banners take a comfortable strip of the frame, never the whole width. */
private const val CENTERED_BANNER_WIDTH = 0.6f
