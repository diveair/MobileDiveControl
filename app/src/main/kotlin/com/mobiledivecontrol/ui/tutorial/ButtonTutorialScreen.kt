package com.mobiledivecontrol.ui.tutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.theme.DiveColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The button map, taught with the buttons.
 *
 * The order walks the hardware the way a hand does — the standalone key first, then round the d-pad
 * clockwise, then the lever's two jobs — so the diver's thumb learns a route rather than a list.
 * Nothing here is configurable: an intro that disagrees with the housing in the diver's hands is
 * worse than no intro, so the sequence stays pinned to the physical layout.
 */
object IntroCarousel {
    val frames: List<IntroFrame> = listOf(
        IntroFrame(HousingControl.MenuOk, "Menu/OK\nHold 3 sec >> OFF"),
        IntroFrame(HousingControl.Up, "UP"),
        IntroFrame(HousingControl.Right, "RIGHT"),
        IntroFrame(HousingControl.Down, "DOWN"),
        IntroFrame(HousingControl.Left, "LEFT"),
        // Bare tilted housing between the plates: the view swings round before anything on the new
        // angle lights up, so the plate change reads as a scene change rather than the old plate
        // suddenly wearing the new plate's overlays.
        IntroFrame.NeutralTilt,
        IntroFrame(HousingControl.SliderSlide, "USER MAPPED SLIDER"),
        IntroFrame(HousingControl.SliderPress, "SHUTTER"),
        // And the bare front view before the loop wraps back to Menu/OK, for the same reason.
        IntroFrame.Neutral,
    )

    /** Wraps rather than clamps: the carousel loops for as long as the diver leaves it running. */
    fun frameAt(index: Int): IntroFrame = frames[index.mod(frames.size)]
}

/**
 * What the intro is showing right now.
 *
 * Ordered the way the obstacles actually arrive: the app cannot scan without permission, cannot
 * connect without finding the housing, and cannot teach the buttons until they work.
 */
enum class IntroPhase {
    NeedsPermissions,
    TurnOnHousing,
    Connecting,
    JustConnected,
    Carousel,
    LinkLost,
}

/**
 * The first thing the app shows, on every launch.
 *
 * One screen does three jobs that used to be three screens: it asks for permissions, it reports the
 * housing link coming up, and it teaches the button map. They share the same artwork and the same
 * two text boxes, so the whole startup reads as one drawing changing its mind rather than a stack of
 * screens swapping — which matters, because the thing being taught is that this drawing *is* the
 * housing in the diver's hands.
 *
 * The carousel runs itself. Nothing here waits for input: a diver holding a housing they have not
 * learned to use yet should not have to guess which button advances a lesson about which button does
 * what. One press of anything — or one tap — ends the whole intro for the session, and that decision
 * lives in the view model because the packet has to be swallowed before it reaches the control core.
 *
 * Opaque black rather than a scrim over the viewfinder: a half-visible camera behind the drawing
 * invites the diver to try to use it, and the housing buttons are not routed to it yet.
 *
 * @param permissionsGranted whether everything the housing link and camera need has been granted.
 * @param bleState the live link state, which drives the whole pre-carousel sequence.
 * @param missingPermissions outstanding permissions, phrased by purpose rather than by Android's
 *   names, shown while [permissionsGranted] is false.
 * @param onDismiss touch fallback. The housing drives this normally, but a diver whose housing
 *   battery dies here must not be locked out of their camera by an intro.
 */
@Composable
fun IntroCarouselScreen(
    permissionsGranted: Boolean,
    bleState: BleConnectionState,
    missingPermissions: List<String>,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onPermissionsSetup: () -> Unit = {},
) {
    // Once the link has been up, a later drop is a *loss* rather than a "still looking" — the diver
    // needs the difference, because one is normal startup and the other means something broke.
    var everConnected by remember { mutableStateOf(false) }
    var carouselStarted by remember { mutableStateOf(false) }
    var frameIndex by remember { mutableStateOf(0) }

    val ready = bleState == BleConnectionState.Ready

    LaunchedEffect(ready) {
        if (ready) everConnected = true
    }

    val phase = when {
        !permissionsGranted -> IntroPhase.NeedsPermissions
        ready && carouselStarted -> IntroPhase.Carousel
        ready -> IntroPhase.JustConnected
        everConnected -> IntroPhase.LinkLost
        bleState == BleConnectionState.Connecting ||
            bleState == BleConnectionState.DiscoveringServices ||
            bleState == BleConnectionState.Subscribing ||
            bleState == BleConnectionState.Reconnecting -> IntroPhase.Connecting
        else -> IntroPhase.TurnOnHousing
    }

    // Let the connected banner stand on its own before the lesson starts. The diver has just been
    // told the housing is talking; overwriting that in the same breath wastes it. Keyed on the phase
    // rather than on the link, so the beat is measured from when the banner is actually on screen —
    // a link that comes up while the diver is still granting permissions has not shown them
    // anything yet.
    if (phase == IntroPhase.JustConnected) {
        LaunchedEffect(Unit) {
            delay(CONNECTED_BANNER_MS)
            carouselStarted = true
        }
    }

    // One clock owns the whole carousel rhythm: the pill's blink IS the page-turn. The alpha
    // rises over one leg and falls over the next, and the frame index advances exactly at the
    // peak — the instant the words are fully hidden behind the pill — so every time the label
    // fades back in it is already the next instruction. Deriving both from one loop makes the
    // choreography exact by construction; a separate timer would drift against the blink and
    // eventually change the words in plain view.
    //
    // This is also the only ticker on the screen, alive only while the carousel runs: a paused
    // intro keeps no coroutine, a dismissed one keeps nothing. `frameIndex` survives the pause,
    // so a link that comes back resumes where it left off.
    val pillFlash = remember { mutableFloatStateOf(0f) }
    // The label's dim follows the pill on ordinary cycles, but on a cycle that leads into a
    // plate swap the words stay hidden once covered: the pill fades out over an empty screen
    // and the next thing seen is the crossfade, never a ghost of the old instruction.
    val labelDim = remember { mutableFloatStateOf(0f) }
    // Which plate the pill belongs to this cycle -- frozen at cycle start, so the pill finishes
    // its fade-out where it faded in even though the frame underneath has already turned.
    var pillTilted by remember { mutableStateOf(false) }
    if (phase == IntroPhase.Carousel) {
        LaunchedEffect(Unit) {
            pillFlash.floatValue = 0f
            var cycleStart = -1L
            var cycleDwell = 0L
            var turned = false
            var bareUntil = -1L
            var deferredCycle = false
            fun planCycle(now: Long) {
                cycleStart = now
                cycleDwell = dwellFor(IntroCarousel.frameAt(frameIndex))
                pillTilted = IntroCarousel.frameAt(frameIndex).tilted
                deferredCycle = IntroCarousel.frameAt(frameIndex + 1).control == null
            }
            while (isActive) {
                withFrameMillis { now ->
                    if (cycleStart < 0L) {
                        planCycle(now)
                    }
                    var inCycle = now - cycleStart
                    var fadeInEnd = cycleDwell + PILL_FADE_MS
                    var holdEnd = fadeInEnd + PILL_HOLD_MS
                    val cycleLen = holdEnd + PILL_FADE_MS
                    if (inCycle >= cycleLen) {
                        // Cycles have per-frame lengths now, so the boundary is walked rather
                        // than computed by modulo. Restarting at `now` drifts by at most one
                        // vsync per cycle, which no eye can hold onto.
                        if (!turned) {
                            // The deferred turn: this cycle led into a plate swap, so the page
                            // waited for the pill to finish its whole fade before changing the
                            // scene — the flash and the crossfade never share a frame.
                            frameIndex += 1
                            bareUntil = now + BARE_BEAT_MS
                        }
                        planCycle(now)
                        inCycle = 0L
                        turned = false
                        fadeInEnd = cycleDwell + PILL_FADE_MS
                        holdEnd = fadeInEnd + PILL_HOLD_MS
                    }

                    // Asymmetric on purpose: the pill repeats forever, so it earns only a short
                    // appearance per cycle while the instruction — the thing actually being
                    // taught — keeps the centre for the long dwell. Order within a cycle:
                    // dwell (words alone) → quick fade in → brief covered hold → quick fade out.
                    val flash = when {
                        inCycle < cycleDwell -> 0f
                        inCycle < fadeInEnd ->
                            FastOutSlowInEasing.transform(
                                (inCycle - cycleDwell).toFloat() / PILL_FADE_MS,
                            )
                        inCycle < holdEnd -> 1f
                        else ->
                            FastOutSlowInEasing.transform(
                                1f - (inCycle - holdEnd).toFloat() / PILL_FADE_MS,
                            )
                    }
                    pillFlash.floatValue = flash
                    // The words stay hidden from the moment the pill covers them on a
                    // swap-leading cycle AND for as long as a bare scene-change frame is
                    // current: the deferred turn changes `frameIndex` a beat before the
                    // crossfade visibly takes over, and those in-between frames still render
                    // the old lesson — unmasked, they are exactly the LEFT/SHUTTER ghost.
                    labelDim.floatValue = when {
                        deferredCycle && inCycle >= holdEnd -> 1f
                        IntroCarousel.frameAt(frameIndex).control == null -> 1f
                        else -> flash
                    }

                    // Lesson-to-lesson pages turn at the visible→invisible edge: the first
                    // frame of fade-out, the last instant the words are still fully covered, so
                    // the next instruction, its highlight and the pill's exit all read as one
                    // motion. A turn that leads onto a bare plate-swap frame is DEFERRED to the
                    // end of the cycle instead: the pill appears and fades out completely over
                    // its own lesson (LEFT and SHUTTER get the same full beat as every other
                    // frame), and only then does the scene change.
                    if (inCycle >= holdEnd && !turned &&
                        IntroCarousel.frameAt(frameIndex + 1).control != null
                    ) {
                        frameIndex += 1
                        turned = true
                        bareUntil = -1L
                    }

                    // Ride through the bare frame after its short beat, WITHOUT spending the
                    // cycle's page-turn — one frame, one blink, always. The dwell is re-snapshot
                    // for the lesson that emerges, so a frame owed a longer dwell still gets its
                    // bonus even though it entered mid-cycle behind a scene change.
                    if (bareUntil > 0L && now >= bareUntil) {
                        frameIndex += 1
                        bareUntil = -1L
                        // A full cycle restart, not just a dwell re-snapshot: the bare beat
                        // already ate into the running cycle, and without resetting the clock
                        // the lesson emerging from a plate swap (USER MAPPED SLIDER, MENU/OK)
                        // was on screen ~400 ms less than every other lesson.
                        planCycle(now)
                        turned = false
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(phase) {
                detectTapGestures {
                    if (phase == IntroPhase.NeedsPermissions) onPermissionsSetup() else onDismiss()
                }
            },
    ) {
        HousingDiagram(
            frame = if (phase == IntroPhase.Carousel) {
                IntroCarousel.frameAt(frameIndex)
            } else {
                IntroFrame.Neutral
            },
            labelDim = if (phase == IntroPhase.Carousel) labelDim else null,
            highlightBoost = if (phase == IntroPhase.Carousel) pillFlash else null,
            modifier = Modifier.fillMaxSize(),
        )

        // Each overlay is composed only while it has something to say. That is not tidiness: an
        // InfiniteTransition schedules a frame callback for as long as it is in composition, so an
        // invisible banner would keep the display awake at 60 Hz for nothing.
        if (phase != IntroPhase.Carousel) {
            IntroMessage(
                phase = phase,
                missingPermissions = missingPermissions,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The pill promises that a press does something. It appears only once the carousel is
        // running — earlier it would be an instruction the hardware cannot honour, and during the
        // connected beat it would sit on top of the lime banner it is supposed to follow.
        if (phase == IntroPhase.Carousel) {
            ContinuePill(
                flash = pillFlash,
                tilted = pillTilted,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The banner or permission block for the current phase, drawn in plate space.
 *
 * Plate space rather than Compose layout so the message lands in the same measured box on the
 * artwork at every screen size, and so the whole overlay is one cached draw: the text is laid out
 * when the phase changes and never again.
 *
 * The pulse rides on the layer's alpha rather than on each shape's colour. That means the display
 * list is recorded once and every subsequent frame is a property change on a RenderNode — no
 * recomposition, no relayout, and not even a re-record of the drawing commands. It also fades the
 * banner as one object, so the black text never dissolves into the black behind it.
 */
@Composable
private fun IntroMessage(
    phase: IntroPhase,
    missingPermissions: List<String>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val pulse = rememberPulseAlpha(
        active = phase.pulses,
        min = BANNER_PULSE_MIN,
        halfPeriodMs = BANNER_PULSE_HALF_MS,
        easing = LinearEasing,
        label = "intro_banner",
    )

    Spacer(
        modifier = modifier
            .graphicsLayer { alpha = pulse.value }
            .drawWithCache {
                val scale = plateScale(size)
                val origin = plateOrigin(size, scale)
                val banner = measurer.layOutBanner(phase, scale, density, fontScale)
                val permissions = if (phase == IntroPhase.NeedsPermissions) {
                    measurer.layOutPermissions(missingPermissions, scale, density, fontScale)
                } else {
                    null
                }

                onDrawBehind {
                    translate(left = origin.x, top = origin.y) {
                        banner?.let { drawBanner(it, scale) }
                        permissions?.let { drawPermissions(it, scale) }
                    }
                }
            },
    )
}

/**
 * "PRESS ANY BUTTON TO CONTINUE", flashing.
 *
 * The flash is the whole point of the pill — it is the app saying "your move" to someone who cannot
 * touch the screen — so it runs entirely on the render path: the rounded rect and its text are
 * recorded once, and the two animated values are multiplied inside a `graphicsLayer` lambda.
 *
 * The one-shot reveal is an [Animatable] rather than `animateFloatAsState` so the pill starts from
 * zero the first time it enters composition instead of snapping in at full brightness. It has no
 * fade-out: when the link drops the pill is removed outright, because a promise that a press will
 * do something has to stop being made the instant it stops being true.
 */
@Composable
private fun ContinuePill(flash: State<Float>, tilted: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    // The pulse is owned by the screen, not created here: the same value dims the frame label
    // underneath, and two independently-phased animations would drift out of complement.
    // Its cycle starts at zero, so the pill's first appearance IS the requested fade-in.

    Spacer(
        modifier = modifier
            .graphicsLayer { alpha = flash.value }
            .drawWithCache {
                // The pill lives inside whichever safe box the current plate offers — the front
                // plate's screen, or the tilted plate's lens (the SHUTTER label's home). The
                // lens is narrower, so there the sentence wraps to two lines rather than
                // shrinking into a squint.
                val pillBox = pillBoxFor(tilted)
                val textBox = PlateBox(
                    left = pillBox.left + PILL_TEXT_INSET_X,
                    top = pillBox.top + PILL_TEXT_INSET_Y,
                    right = pillBox.right - PILL_TEXT_INSET_X,
                    bottom = pillBox.bottom - PILL_TEXT_INSET_Y,
                )
                val scale = plateScale(size)
                val origin = plateOrigin(size, scale)
                val label = measurer.fitPlateText(
                    text = CONTINUE_TEXT,
                    style = TextStyle(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    box = textBox,
                    scale = scale,
                    density = density,
                    fontScale = fontScale,
                    startPlatePx = PILL_TEXT_PX,
                    minPlatePx = PILL_TEXT_MIN_PX,
                    maxLines = if (tilted) 2 else 1,
                )

                onDrawBehind {
                    translate(left = origin.x, top = origin.y) {
                        drawRoundRect(
                            color = LabelBlue,
                            topLeft = Offset(pillBox.left * scale, pillBox.top * scale),
                            size = Size(pillBox.width * scale, pillBox.height * scale),
                            cornerRadius = CornerRadius(PILL_CORNER * scale),
                        )
                        drawText(
                            textLayoutResult = label,
                            color = Color.Black,
                            topLeft = Offset(
                                pillBox.centreX * scale - label.size.width / 2f,
                                pillBox.centreY * scale - label.size.height / 2f,
                            ),
                        )
                    }
                }
            },
    )
}

/**
 * The pill centred in the active plate's safe box, at a hair under the box's width so its
 * corners never kiss the artwork's drawing lines.
 */
private fun pillBoxFor(tilted: Boolean): PlateBox {
    val safe = if (tilted) TILT_SAFE_BOX else PORT_BOX
    val width = safe.width * PILL_FIT_FRACTION
    val height = if (tilted) PILL_TILT_HEIGHT else PILL_HEIGHT
    return PlateBox(
        left = safe.centreX - width / 2f,
        top = safe.centreY - height / 2f,
        right = safe.centreX + width / 2f,
        bottom = safe.centreY + height / 2f,
    )
}

/**
 * The sealing hand-off, shown once the intro is dismissed and only while the housing still needs
 * sealing: the tilted housing, the instruction in the lens, and a two-act mime on loop.
 *
 * Act one, the cap comes off: the blue cap lifts out of its seat behind an unscrew arc, leaving
 * a ghost ring where it was. Act two, the ask: the cap parks back greyed-out and the DOWN button flashes on the front plate — do the first thing, then press the second. The acts alternate for as long as the
 * screen is up, because a mime that plays once is a mime the diver looked away from.
 *
 * This screen is a doorway, not a lesson: DOWN walks through it (the words and the flashing button both say so), and it also steps aside on its own the moment the housing reports the cap actually coming
 * off, or that a vacuum already exists. The view model owns both exits; this composable only
 * draws. Its one clock lives only while it is composed, and every animated value is read inside
 * the draw lambda.
 */
@Composable
fun SealCapPromptScreen(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    val phaseMs = remember { mutableFloatStateOf(0f) }
    // Which act the BASE PLATE is in. Separate from the draw-phase clock on purpose: the plate
    // choice is composition state (it feeds HousingDiagram's crossfade), and writing the same
    // Boolean every frame is free — Compose skips same-value writes — so recomposition happens
    // exactly twice per cycle, at the act boundaries.
    var actTwo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (isActive) {
            withFrameMillis { now ->
                if (start < 0L) start = now
                val t = ((now - start) % CAP_CYCLE_MS).toFloat()
                phaseMs.floatValue = t
                actTwo = t >= CAP_LIFT_MS
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        // Act one plays on the tilted plate where the cap lives; act two crossfades to the front
        // plate so the DOWN being asked for flashes on the actual control cluster — the diagram
        // switching views is what makes "now press the button" read without any words changing.
        HousingDiagram(
            frame = if (actTwo) {
                IntroFrame(control = null, label = CAP_PROMPT_ACT_TWO, tilted = false)
            } else {
                IntroFrame(control = null, label = CAP_PROMPT_ACT_ONE, glowText = CAP_PROMPT_GLOW, tilted = true)
            },
            modifier = Modifier.fillMaxSize(),
        )

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val scale = plateScale(size)
                    val origin = plateOrigin(size, scale)
                    val seat = Offset(CAP_CX * scale, CAP_CY * scale)

                    onDrawBehind {
                        val t = phaseMs.floatValue
                        translate(left = origin.x, top = origin.y) {
                            if (t < CAP_LIFT_MS) {
                                drawCapComingOff(t / CAP_LIFT_MS, seat, scale)
                            } else {
                                val b = (t - CAP_LIFT_MS) / (CAP_CYCLE_MS - CAP_LIFT_MS)
                                drawDownAsk(b, scale)
                            }
                        }
                    }
                },
        )
    }
}

/** Act one: the cap lifts out of a ghosted seat behind an unscrew arc. */
private fun DrawScope.drawCapComingOff(progress: Float, seat: Offset, scale: Float) {
    val lift = FastOutSlowInEasing.transform(progress)

    // Where the cap belongs, kept visible so the motion reads as "removed from here".
    drawCircle(
        color = CapGhostGrey,
        radius = CAP_R * scale,
        center = seat,
        style = Stroke(width = CAP_GHOST_STROKE * scale),
    )

    // The unscrew arc fades as the cap gets away — mid-air rotation is not a thing caps do —
    // and it SPINS counter-clockwise while it lives, because a motion arrow that moves teaches
    // the gesture in a way a frozen one only implies.
    val arcAlpha = (1f - lift * 1.6f).coerceIn(0f, 1f)
    if (arcAlpha > 0f) rotate(
        degrees = -progress * CAP_ARC_SPIN_DEG,
        pivot = seat,
    ) {
        val arcRadius = (CAP_R + CAP_ARC_GAP) * scale
        drawArc(
            color = GestureBlue.copy(alpha = arcAlpha),
            startAngle = CAP_ARC_START_DEG,
            sweepAngle = CAP_ARC_SWEEP_DEG,
            useCenter = false,
            topLeft = Offset(seat.x - arcRadius, seat.y - arcRadius),
            size = Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = CAP_ARC_STROKE * scale, cap = StrokeCap.Round),
        )
        // Arrowhead at the arc's end, tangent to it. The tangent's sign follows the sweep's: a
        // negative sweep is counter-clockwise — left loosens, the way a real thread unscrews —
        // and the head has to point the way the arc travels or the mime teaches tightening.
        val endRad = Math.toRadians((CAP_ARC_START_DEG + CAP_ARC_SWEEP_DEG).toDouble())
        val tip = Offset(
            seat.x + arcRadius * kotlin.math.cos(endRad).toFloat(),
            seat.y + arcRadius * kotlin.math.sin(endRad).toFloat(),
        )
        val tangent = endRad + if (CAP_ARC_SWEEP_DEG > 0f) Math.PI / 2.0 else -Math.PI / 2.0
        val head = CAP_ARC_HEAD * scale
        val hx = kotlin.math.cos(tangent).toFloat()
        val hy = kotlin.math.sin(tangent).toFloat()
        val path = Path().apply {
            moveTo(tip.x + hx * head, tip.y + hy * head)
            lineTo(tip.x - hy * head * 0.7f, tip.y + hx * head * 0.7f)
            lineTo(tip.x + hy * head * 0.7f, tip.y - hx * head * 0.7f)
            close()
        }
        drawPath(path, GestureBlue.copy(alpha = arcAlpha))
    }

    // The cap itself, lifting away from the shell.
    val lifted = Offset(
        seat.x + CAP_LIFT_DX * lift * scale,
        seat.y + CAP_LIFT_DY * lift * scale,
    )
    drawCircle(GestureBlue.copy(alpha = 0.22f), CAP_GLOW_R * scale, lifted)
    drawCircle(GestureBlue, CAP_R * scale, lifted)
}

/**
 * Act two: the DOWN button flashing in its real place on the front plate.
 *
 * Drawn with the same fill-plus-bright-ring profile as the carousel's lit buttons — measured off
 * the reference — so "the flashing thing" and "the thing the carousel taught you" are visibly the
 * same object. The flash rides the measured style's alpha; the geometry never moves.
 */
private fun DrawScope.drawDownAsk(progress: Float, scale: Float) {
    val centre = BUTTON_CENTRES[HousingControl.Down] ?: return
    val at = Offset(centre.first * scale, centre.second * scale)

    // One full flash per act: in, out.
    val flash = 1f - kotlin.math.abs(1f - 2f * progress)
    val alpha = 0.25f + 0.75f * FastOutSlowInEasing.transform(flash)

    drawCircle(HighlightFill.copy(alpha = alpha), DOWN_ASK_FILL_R * scale, at)
    drawCircle(
        color = HighlightRing.copy(alpha = alpha),
        radius = DOWN_ASK_RING_R * scale,
        center = at,
        style = Stroke(width = DOWN_ASK_RING_STROKE * scale),
    )
}

// --- measured content -------------------------------------------------------------------------

/** A banner, laid out once per phase. */
private class MeasuredBanner(
    val fill: Color,
    val ink: Color,
    val primary: TextLayoutResult,
    val secondary: TextLayoutResult?,
    val badge: Boolean,
)

/** The permission ask, laid out once. */
private class MeasuredPermissions(
    val title: TextLayoutResult,
    val items: List<TextLayoutResult>,
    val hint: TextLayoutResult,
)

/** Whether this phase's banner breathes. Steady means settled; breathing means still working. */
private val IntroPhase.pulses: Boolean
    get() = this == IntroPhase.TurnOnHousing ||
        this == IntroPhase.Connecting ||
        this == IntroPhase.LinkLost

private fun TextMeasurer.layOutBanner(
    phase: IntroPhase,
    scale: Float,
    density: Float,
    fontScale: Float,
): MeasuredBanner? {
    val bold = TextStyle(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    val plain = TextStyle(fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)

    fun primary(text: String, box: PlateBox) = fitPlateText(
        text = text,
        style = bold,
        box = box,
        scale = scale,
        density = density,
        fontScale = fontScale,
        startPlatePx = BANNER_PRIMARY_PX,
        minPlatePx = BANNER_TEXT_MIN_PX,
        maxLines = 2,
    )

    fun secondary(text: String) = fitPlateText(
        text = text,
        style = plain,
        box = BANNER_TEXT_BOX,
        scale = scale,
        density = density,
        fontScale = fontScale,
        startPlatePx = BANNER_SECONDARY_PX,
        minPlatePx = BANNER_TEXT_MIN_PX,
        maxLines = 2,
    )

    return when (phase) {
        IntroPhase.NeedsPermissions, IntroPhase.Carousel -> null

        IntroPhase.TurnOnHousing -> MeasuredBanner(
            fill = DiveColors.Warning,
            ink = Color.Black,
            primary = primary("HOLD SHUTTER TO TURN ON HOUSING", BANNER_TEXT_BOX),
            secondary = secondary("WHICH WILL CONNECT BLUETOOTH"),
            badge = false,
        )

        IntroPhase.Connecting -> MeasuredBanner(
            fill = DiveColors.Warning,
            ink = Color.Black,
            primary = primary("CONNECTING TO HOUSING", BANNER_TEXT_BOX),
            secondary = null,
            badge = false,
        )

        IntroPhase.JustConnected -> MeasuredBanner(
            fill = ConnectedLime,
            ink = Color.Black,
            primary = primary("HOUSING CONNECTED", BANNER_BADGE_TEXT_BOX),
            secondary = null,
            badge = true,
        )

        IntroPhase.LinkLost -> MeasuredBanner(
            fill = DiveColors.Critical,
            ink = Color.White,
            primary = primary("HOUSING DISCONNECTED", BANNER_TEXT_BOX),
            secondary = secondary("Hold SHUTTER for 3 seconds to turn it on"),
            badge = false,
        )
    }
}

/**
 * Names what each permission is *for* rather than what it is called.
 *
 * "Storage" means nothing to a diver; "so your photos are saved" is the thing they actually care
 * about, and a permission whose purpose is obvious gets granted instead of dismissed. The wording
 * itself is supplied by the caller — this only lays it out.
 */
private fun TextMeasurer.layOutPermissions(
    missing: List<String>,
    scale: Float,
    density: Float,
    fontScale: Float,
): MeasuredPermissions {
    fun line(text: String, sizePlatePx: Float, weight: FontWeight) = fitPlateText(
        text = text,
        style = TextStyle(fontWeight = weight, textAlign = TextAlign.Center),
        box = PERMISSION_TEXT_BOX,
        scale = scale,
        density = density,
        fontScale = fontScale,
        startPlatePx = sizePlatePx,
        minPlatePx = BANNER_TEXT_MIN_PX,
        maxLines = 2,
    )

    return MeasuredPermissions(
        title = line("ALLOW ACCESS TO CONTINUE", PERMISSION_TITLE_PX, FontWeight.Bold),
        items = missing.map { line(it, PERMISSION_ITEM_PX, FontWeight.Normal) },
        hint = line("Tap Allow on each prompt", PERMISSION_HINT_PX, FontWeight.Normal),
    )
}

// --- plate-space painting ---------------------------------------------------------------------

/**
 * A rounded plate-space banner across the housing port.
 *
 * The rectangle keeps the geometry measured off the reference, but grows downwards and upwards from
 * its centre when a second line needs the room. Fixed height plus clipped text is the one outcome
 * this must never produce.
 */
private fun DrawScope.drawBanner(banner: MeasuredBanner, scale: Float) {
    val gap = BANNER_LINE_GAP * scale
    val contentHeight = banner.primary.size.height.toFloat() +
        (banner.secondary?.let { gap + it.size.height } ?: 0f)
    val height = maxOf(BANNER_BOX.height * scale, contentHeight + 2f * BANNER_PAD_Y * scale)
    val width = BANNER_BOX.width * scale
    val centreX = BANNER_BOX.centreX * scale
    val centreY = BANNER_BOX.centreY * scale

    drawRoundRect(
        color = banner.fill,
        topLeft = Offset(centreX - width / 2f, centreY - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(BANNER_CORNER * scale),
    )

    val badgeRadius = BANNER_BADGE_R * scale
    val rowWidth = banner.primary.size.width +
        if (banner.badge) BANNER_BADGE_GAP * scale + badgeRadius * 2f else 0f
    val rowLeft = centreX - rowWidth / 2f
    var y = centreY - contentHeight / 2f

    drawText(
        textLayoutResult = banner.primary,
        color = banner.ink,
        topLeft = Offset(rowLeft, y),
    )
    if (banner.badge) {
        drawCheckBadge(
            centre = Offset(rowLeft + rowWidth - badgeRadius, y + banner.primary.size.height / 2f),
            radius = badgeRadius,
        )
    }

    banner.secondary?.let { second ->
        y += banner.primary.size.height + gap
        drawText(
            textLayoutResult = second,
            color = banner.ink.copy(alpha = BANNER_SECONDARY_INK),
            topLeft = Offset(centreX - second.size.width / 2f, y),
        )
    }
}

/**
 * Black disc with a white tick, drawn as strokes.
 *
 * Vector rather than an emoji or an icon font: both render differently across OEM skins, and a
 * connection indicator that changes shape between phones is one more thing support has to explain.
 */
private fun DrawScope.drawCheckBadge(centre: Offset, radius: Float) {
    drawCircle(color = Color.Black, radius = radius, center = centre)
    val d = radius * 2f
    val tick = Path().apply {
        moveTo(centre.x - d * 0.24f, centre.y + d * 0.02f)
        lineTo(centre.x - d * 0.06f, centre.y + d * 0.20f)
        lineTo(centre.x + d * 0.25f, centre.y - d * 0.19f)
    }
    drawPath(
        path = tick,
        color = Color.White,
        style = Stroke(width = d * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** The permission ask, stacked and centred on the port. */
private fun DrawScope.drawPermissions(block: MeasuredPermissions, scale: Float) {
    val titleGap = PERMISSION_TITLE_GAP * scale
    val itemGap = PERMISSION_ITEM_GAP * scale
    val hintGap = PERMISSION_HINT_GAP * scale

    var height = block.title.size.height + titleGap + block.hint.size.height + hintGap
    block.items.forEachIndexed { index, item ->
        height += item.size.height + if (index == 0) 0f else itemGap
    }

    val centreX = PORT_BOX.centreX * scale
    var y = PORT_BOX.centreY * scale - height / 2f

    drawText(
        textLayoutResult = block.title,
        color = LabelBlue,
        topLeft = Offset(centreX - block.title.size.width / 2f, y),
    )
    y += block.title.size.height + titleGap

    block.items.forEachIndexed { index, item ->
        if (index > 0) y += itemGap
        drawText(
            textLayoutResult = item,
            color = DiveColors.TextSecondary,
            topLeft = Offset(centreX - item.size.width / 2f, y),
        )
        y += item.size.height
    }

    y += hintGap
    drawText(
        textLayoutResult = block.hint,
        color = DiveColors.TextMuted,
        topLeft = Offset(centreX - block.hint.size.width / 2f, y),
    )
}

// --- timing ------------------------------------------------------------------------------------

/** The connected banner gets a beat of its own before the lesson takes the screen. */
private const val CONNECTED_BANNER_MS = 2500L

/**
 * The pill's cycle segments — dwell + fade-in + hold + fade-out is one instruction's lifetime,
 * so these ARE the carousel's tempo (~1.35 s standard, ~1.49 s for the extended-dwell frames).
 *
 * Deliberately bottom-heavy. The pill flashes for the whole session, so per cycle it is visible
 * for ~0.63 s (fade + hold + fade) and gone for ~0.72 s — the instruction being taught keeps the
 * centre longer than the reminder that interrupts it. The page turns at the start of the
 * fade-out. Every number here is field-tuned; change them only against the housing on a desk.
 */
private const val PILL_DWELL_MS = 900L
private const val PILL_FADE_MS = 225L

/** Stretched so the pill's visible share (fade+hold+fade = 630 ms) runs ~10% past the original. */
private const val PILL_HOLD_MS = 250L

/**
 * How long a bare plate-change frame stays up: just past the 300 ms dissolve. A scene change
 * only needs to be *seen*, not dwelt on.
 */
private const val BARE_BEAT_MS = 400L


// --- the cap doorway's two-act mime -------------------------------------------------------------

/** Act one (cap lifts) runs to [CAP_LIFT_MS]; act two (DOWN flashes) fills the rest of the cycle
 * — 2000 ms, a quarter longer than it used to hold, because the confirm instruction is the one
 * the diver has to act on and it kept leaving before it was read. */
private const val CAP_CYCLE_MS = 3800L
private const val CAP_LIFT_MS = 1800L

/**
 * The doorway's two lines, one per act: the tilted view asks for the action while the cap mime
 * plays, the front view asks for the confirm while DOWN flashes. Each act carries only its own
 * sentence — the split is what keeps either screen readable at a glance.
 */
private const val CAP_PROMPT_ACT_ONE = "REMOVE BLUE CAP\nTO OPEN VACUUM PORT"

/** The physical thing act one points at; rendered glowing by [IntroFrame.glowText]. */
private const val CAP_PROMPT_GLOW = "BLUE CAP"
private const val CAP_PROMPT_ACT_TWO = "PRESS DOWN\nONCE BLUE CAP IS OFF"

/** Where the cap travels when it comes off: downward and slightly out, ~two cap-radii clear. */
private const val CAP_LIFT_DX = 30f
private const val CAP_LIFT_DY = 170f

/** The ghost ring marking the seat the cap left. Brighter than the artwork's own strokes. */
private val CapGhostGrey = Color(0xFF515A63)
private const val CAP_GHOST_STROKE = 6f

/**
 * The unscrew arc hugging the cap's rim. The sweep is NEGATIVE: Compose sweeps positive angles
 * clockwise in its y-down space, and an unscrew mime must run counter-clockwise — left loosens.
 */
private const val CAP_ARC_GAP = 30f
private const val CAP_ARC_STROKE = 14f
private const val CAP_ARC_HEAD = 30f
/** How far the arc travels per act — a bit under a full turn, obviously rotation. */
private const val CAP_ARC_SPIN_DEG = 300f

private const val CAP_ARC_START_DEG = -30f
private const val CAP_ARC_SWEEP_DEG = -130f

/** Act two's flashing DOWN button, in the measured lit-button style (fill r30, ring r33 w5). */
private const val DOWN_ASK_FILL_R = 30.5f
private const val DOWN_ASK_RING_R = 33f
private const val DOWN_ASK_RING_STROKE = 5f

/**
 * Per-frame dwell bonuses, field-tuned against the housing on a desk: Menu/OK (the only
 * two-line label) and both lever lessons earn 20% of the standard cycle. The D-pad frames keep
 * the brisk standard beat — their labels are one word each.
 */
private val DWELL_BONUS_MS: Map<HousingControl, Long> = mapOf(
    HousingControl.MenuOk to 340L,
    HousingControl.SliderSlide to 340L,
    HousingControl.SliderPress to 340L,
)

private fun dwellFor(frame: IntroFrame): Long =
    PILL_DWELL_MS + (frame.control?.let { DWELL_BONUS_MS[it] } ?: 0L)

private const val BANNER_PULSE_MIN = 0.55f
private const val BANNER_PULSE_HALF_MS = 700

// --- geometry, in the plates' own 2340x1080 pixels ---------------------------------------------

private const val CONTINUE_TEXT = "PRESS ANY BUTTON TO CONTINUE"

/**
 * The reference pill's proportions from 2.jpg, re-homed per plate: centred in the front plate's
 * screen (PORT_BOX) or the tilted plate's lens (TILT_SAFE_BOX), sized to the box it lives in.
 */
private const val PILL_FIT_FRACTION = 0.94f
private const val PILL_HEIGHT = 130f
private const val PILL_TILT_HEIGHT = 200f
private const val PILL_TEXT_INSET_X = 40f
private const val PILL_TEXT_INSET_Y = 10f
private const val PILL_CORNER = 36f
private const val PILL_TEXT_PX = 64f
private const val PILL_TEXT_MIN_PX = 28f

/**
 * Measured off 1.jpg: the lime banner occupies x 571-1472 / y 482-618 in plate pixels.
 *
 * Note this is *not* the x 488-1268 / y 412-528 quoted in the design contract — that rectangle was
 * measured on a 2000x923 render of the reference and never scaled back up by the 1.17 factor, which
 * would have put the banner 143 px left and 68 px high of where the reference actually draws it.
 * These numbers come straight off the full-size pixels.
 */
private val BANNER_BOX = PlateBox(left = 571f, top = 482f, right = 1472f, bottom = 618f)
private val BANNER_TEXT_BOX = PlateBox(left = 615f, top = 470f, right = 1428f, bottom = 630f)
private val BANNER_BADGE_TEXT_BOX = PlateBox(left = 615f, top = 470f, right = 1320f, bottom = 630f)
private const val BANNER_CORNER = 24f
private const val BANNER_PAD_Y = 26f
private const val BANNER_PRIMARY_PX = 64f
private const val BANNER_SECONDARY_PX = 44f
private const val BANNER_TEXT_MIN_PX = 24f
private const val BANNER_LINE_GAP = 14f
private const val BANNER_SECONDARY_INK = 0.82f

/** The tick badge in 1.jpg measures ~72 plate px across, set ~42 px clear of the text. */
private const val BANNER_BADGE_R = 36f
private const val BANNER_BADGE_GAP = 42f

private val PERMISSION_TEXT_BOX = PlateBox(left = 535f, top = 300f, right = 1535f, bottom = 780f)
private const val PERMISSION_TITLE_PX = 72f
private const val PERMISSION_ITEM_PX = 46f
private const val PERMISSION_HINT_PX = 40f
private const val PERMISSION_TITLE_GAP = 34f
private const val PERMISSION_ITEM_GAP = 12f
private const val PERMISSION_HINT_GAP = 40f
