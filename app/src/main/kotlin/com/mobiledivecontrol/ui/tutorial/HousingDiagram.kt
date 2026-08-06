package com.mobiledivecontrol.ui.tutorial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.mobiledivecontrol.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A control the intro can point at.
 *
 * [SliderSlide] and [SliderPress] are the same physical lever. Sliding it and pressing it are
 * different gestures on different axes, so they get their own frames and their own arrow.
 */
enum class HousingControl {
    MenuOk,
    Up,
    Right,
    Down,
    Left,
    SliderSlide,
    SliderPress,

    /** The blue vacuum valve cap — the gear-toothed knob on the tilted plate. */
    ValveCap,
}

/**
 * One frame of the intro: which control lights up, and the words that go with it.
 *
 * [Neutral] is a frame in its own right rather than a null. The intro fades *to* the bare housing
 * whenever it has something to say instead — permissions, connecting, a dropped link — and a
 * crossfade needs both ends to be a thing it can draw.
 */
data class IntroFrame(
    val control: HousingControl?,
    val label: String,
    /** Substring of [label] rendered bright with a halo — the physical thing being pointed at. */
    val glowText: String? = null,
    /**
     * Which plate this frame stands on. Defaults from the control, but is explicit state rather
     * than a derivation because the bare scene-change frames have no control at all — a plain
     * "show the tilted housing, nothing lit" is a real frame the carousel needs.
     */
    val tilted: Boolean =
        control == HousingControl.SliderSlide ||
            control == HousingControl.SliderPress ||
            control == HousingControl.ValveCap,
) {
    companion object {
        val Neutral = IntroFrame(control = null, label = "")
        val NeutralTilt = IntroFrame(control = null, label = "", tilted = true)
    }
}

/**
 * Label and continue-pill blue.
 *
 * Sampled from `DiveIT Screenshots/2.jpg`: the "APP MENU" glyphs and the continue pill are the same
 * colour, mode RGB(106, 195, 255) over 7238 pixels of pill fill.
 */
val LabelBlue = Color(0xFF6AC3FF)

/**
 * Slider fill and gesture arrows.
 *
 * Sampled from `DiveIT Screenshots/7.jpg`, slider bounding box x 485-682 / y 269-312: modal colour
 * RGB(62, 197, 255) over 4424 lit pixels, and the double arrow below it samples identically
 * (mode RGB(62, 197, 255) over 1314 pixels). Deliberately brighter than [HighlightFill] — the
 * reference lights the lever harder than the buttons because it is teaching a gesture, not a press.
 */
internal val GestureBlue = Color(0xFF3EC5FF)

/**
 * Lit-button fill and rim.
 *
 * Radial profile of the lit dot in `2.jpg` at (1708,436): flat RGB(40,113,145) fill to r≈30, then a
 * bright ring peaking RGB(66,192,246) at r≈33, black by r≈39. An earlier sweep that started at r=38
 * missed the ring entirely and shipped a flat dull disc — the user caught it against the reference.
 */
internal val HighlightFill = Color(0xFF287191)
internal val HighlightRing = Color(0xFF42C0F6)

/**
 * Shutter-paddle fill.
 *
 * Sampled from `DiveIT Screenshots/8.jpg` (the CONFIRM frame): mean RGB(58,175,225) over 5810 lit
 * pixels. On that frame the knurled wheel is NOT lit — the paddle above it is the shutter.
 */
internal val PaddleBlue = Color(0xFF3AAFE1)

/**
 * Connected-banner lime.
 *
 * Sampled from `DiveIT Screenshots/1.jpg`: mode RGB(150, 238, 68) over 59355 pixels of banner fill.
 * Lives here rather than in the app palette because it belongs to the intro alone.
 */
internal val ConnectedLime = Color(0xFF96EE44)

/**
 * The housing, with at most one control lit.
 *
 * The artwork is the manufacturer's own technical drawing rather than a redrawn approximation. A
 * diver matching the picture to the object in their hands needs the picture to *be* the object — an
 * idealised schematic is exactly the kind of "close enough" that sends someone pressing the wrong
 * button at ten metres.
 *
 * Everything the app owns — the lit control, the label, the arrows — is drawn on top in code, so the
 * artwork and the state can never disagree about what is currently highlighted.
 *
 * All geometry below is in the plates' native 2340x1080 pixel space, measured off the drawings by
 * ring-matching rather than eye, and rescaled to whatever size this composable is given. That keeps
 * the overlays welded to the artwork on any screen.
 *
 * ## Why this is one cached canvas
 *
 * The carousel changes frame every 2.6 s but animates continuously. Laying the frame out as
 * composables would recompose 60 times a second for a breathe and a crossfade that only ever touch
 * pixels. Instead the expensive work — the ContentScale.Fit maths and the measure-and-shrink text
 * layout — happens in a [drawWithCache] build block that re-runs only when the frame or the size
 * changes, and the two animated values are read inside `onDrawBehind`, which invalidates draw alone.
 *
 * @param frame what to show. [IntroFrame.Neutral] draws the bare front plate.
 */
@Composable
fun HousingDiagram(
    frame: IntroFrame,
    /**
     * How far to dim the frame's label, 0..1, read in the draw phase. The continue pill occupies
     * the same centre the labels do, so its owner passes its own flash value here and the two
     * become complementary: pill in, words out — while the lit control stays lit throughout.
     */
    labelDim: State<Float>? = null,
    /**
     * How far to brighten the lit control, 0..1, read in the draw phase. Driven by the continue
     * pill's flash so the hardware being taught surges with the ask and settles as it fades —
     * brightness only, the sampled hues stay untouched.
     */
    highlightBoost: State<Float>? = null,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val front = rememberHousingPlate(R.drawable.housing_front)
    val tilt = rememberHousingPlate(R.drawable.housing_tilt)

    // A slow breathe, not a blink. Faster reads as an alarm, and this screen is teaching someone
    // something rather than warning them about anything. Only alive while a control is actually
    // lit: on the neutral frame there is nothing to breathe, and an animation clock running behind
    // a still picture is a frame callback per vsync for no pixels.
    val breathe = rememberPulseAlpha(
        active = frame.control != null,
        min = HIGHLIGHT_BREATHE_MIN,
        halfPeriodMs = HIGHLIGHT_BREATHE_MS,
        easing = FastOutSlowInEasing,
        label = "housing_highlight",
    )

    // Crossfade bookkeeping. `outgoing` is non-null only while a fade is running, which is what
    // lets a fade *to* the neutral frame be distinguished from having nothing to fade from.
    var incoming by remember { mutableStateOf(frame) }
    var outgoing by remember { mutableStateOf<IntroFrame?>(null) }
    // A hand-rolled fade for the same reason as [rememberPulseAlpha]: Animatable.animateTo obeys
    // the system animator scale, and at scale 0 the dissolve became a hard cut that showed one
    // plate wearing the other plate's overlays for a frame.
    val fade = remember { mutableFloatStateOf(1f) }

    LaunchedEffect(frame) {
        if (frame == incoming) return@LaunchedEffect
        outgoing = incoming
        incoming = frame
        fade.floatValue = 0f
        var start = -1L
        while (isActive && fade.floatValue < 1f) {
            withFrameMillis { now ->
                if (start < 0L) start = now
                fade.floatValue = ((now - start).toFloat() / CROSSFADE_MS).coerceAtMost(1f)
            }
        }
        // Dropping the outgoing frame costs one recomposition per frame change, and buys a draw
        // lambda that does no work at all for the 2.3 s the frame is steady.
        outgoing = null
    }

    Spacer(
        modifier = modifier.drawWithCache {
            val scale = plateScale(size)
            val origin = plateOrigin(size, scale)
            // Snapshotted into locals so the draw lambda can never see a frame whose label or plate
            // was measured for a different frame.
            val shown = incoming
            val fadingFrom = outgoing
            val shownLabel = measurer.layOutLabel(shown, scale, density, fontScale)
            val fadingLabel = fadingFrom?.let { measurer.layOutLabel(it, scale, density, fontScale) }
            val shownPlate = if (shown.tilted) tilt else front
            val fadingPlate = fadingFrom?.let { if (it.tilted) tilt else front }
            val anyLit = shown.control != null || fadingFrom?.control != null

            onDrawBehind {
                // The only snapshot reads in the whole draw path. All happen here, in the draw
                // phase, so a 60 Hz animation never re-enters composition or layout. The breathe is
                // only read when something is lit, so a settled neutral frame reads nothing at all
                // and its display list is recorded once and reused.
                val progress = fade.floatValue
                val breath = if (anyLit) breathe.value else 1f
                val wordsAlpha = 1f - (labelDim?.value ?: 0f)
                val boost = highlightBoost?.value ?: 0f

                translate(left = origin.x, top = origin.y) {
                    // Crossfading a plate against itself would dip to 75% opacity mid-fade, so the
                    // artwork is only ever cross-dissolved when it genuinely changes; on the five
                    // button frames it simply stays put and the highlight moves.
                    if (fadingPlate != null && fadingPlate !== shownPlate) {
                        drawPlate(fadingPlate, scale, alpha = 1f)
                        drawPlate(shownPlate, scale, alpha = progress)
                    } else {
                        drawPlate(shownPlate, scale, alpha = 1f)
                    }

                    if (fadingFrom != null) {
                        // The outgoing frame surrenders its words instantly: the page only ever
                        // turns while the pill covers the centre, so the old label is already
                        // hidden when the fade starts — and because the pill clears faster than
                        // the crossfade finishes, letting the old label ride the crossfade would
                        // re-expose it for a ~100 ms ghost between pill-out and label-death.
                        // Only the highlight glides; the words are a hard swap.
                        drawFrame(fadingFrom, fadingLabel.orEmpty(), scale, breath, alpha = 1f - progress, wordsAlpha = 0f, boost = boost)
                        drawFrame(shown, shownLabel, scale, breath, alpha = progress, wordsAlpha, boost)
                    } else {
                        drawFrame(shown, shownLabel, scale, breath, alpha = 1f, wordsAlpha, boost)
                    }
                }
            }
        },
    )
}

/** Draws a plate at its native aspect, already inside the plate-space translate. */
private fun DrawScope.drawPlate(plate: ImageBitmap?, scale: Float, alpha: Float) {
    if (plate == null || alpha <= 0f) return
    drawImage(
        image = plate,
        dstOffset = IntOffset.Zero,
        dstSize = IntSize((PLATE_W * scale).roundToInt(), (PLATE_H * scale).roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/** Everything the app owns for one frame: the lit control and its words. */
private fun DrawScope.drawFrame(
    frame: IntroFrame?,
    label: List<TextLayoutResult>,
    scale: Float,
    breathe: Float,
    alpha: Float,
    wordsAlpha: Float = 1f,
    boost: Float = 0f,
) {
    if (frame == null || alpha <= 0.01f) return
    val control = frame.control
    if (control != null) {
        if (frame.tilted) {
            drawSlider(control, scale, alpha, boost)
        } else {
            drawButton(control, scale, breathe, alpha, boost)
        }
    }
    // The label alone takes the extra dim: it shares the centre with the continue pill and yields
    // to it, while the highlight keeps pointing at the hardware the whole time.
    val labelAlpha = alpha * wordsAlpha
    if (label.isNotEmpty() && labelAlpha > 0.01f) {
        val box = if (frame.tilted) TILT_SAFE_BOX else PORT_BOX
        val totalHeight = label.sumOf { it.size.height }
        var top = box.centreY * scale - totalHeight / 2f
        for (line in label) {
            // Alpha-modulated, never colour-overridden: an override would flatten the glow
            // span back into the base blue.
            drawText(
                textLayoutResult = line,
                topLeft = Offset(box.centreX * scale - line.size.width / 2f, top),
                alpha = labelAlpha,
            )
            top += line.size.height
        }
    }
}

/**
 * Fills the one live button.
 *
 * The plate already carries all five buttons unlit, so only the active one is painted here. Drawing
 * the inactive ones again would mean two sources of truth for the same circles.
 */
private fun DrawScope.drawButton(
    control: HousingControl,
    scale: Float,
    breathe: Float,
    alpha: Float,
    boost: Float = 0f,
) {
    val centre = BUTTON_CENTRES[control] ?: return
    val at = Offset(centre.first * scale, centre.second * scale)
    val a = breathe * alpha
    // Fill-then-rim, matching the measured radial profile: dark disc to r≈30, bright ring at r≈33.
    drawCircle(
        color = HighlightFill.brightened(boost).copy(alpha = a),
        radius = HIGHLIGHT_FILL_R * scale,
        center = at,
    )
    drawCircle(
        color = HighlightRing.brightened(boost).copy(alpha = a),
        radius = HIGHLIGHT_RING_R * scale,
        center = at,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = HIGHLIGHT_RING_W * scale),
    )
}

/**
 * A brightness-only lift: every channel scaled up in place and clamped, so the hue the colour
 * was sampled at never moves — it just runs hotter while the boost is up.
 */
private fun Color.brightened(t: Float): Color {
    if (t <= 0.01f) return this
    val k = 1f + HIGHLIGHT_BOOST_MAX * t
    return Color(
        red = (red * k).coerceAtMost(1f),
        green = (green * k).coerceAtMost(1f),
        blue = (blue * k).coerceAtMost(1f),
        alpha = alpha,
    )
}

/** Peak brightness lift while the continue pill is fully lit. */
private const val HIGHLIGHT_BOOST_MAX = 0.55f

/**
 * Lights whichever control this lever frame teaches.
 *
 * The two frames light DIFFERENT hardware, per the reference: SELECT/USER-MAPPED lights the knurled
 * wheel with a slide arrow (7.jpg), while SHUTTER lights the paddle above it with a press arrow and
 * leaves the wheel dark (8.jpg). Lighting the wheel for both was a real bug the user caught.
 */
private fun DrawScope.drawSlider(control: HousingControl, scale: Float, alpha: Float, boost: Float = 0f) {
    val shaft = ARROW_SHAFT * scale
    val head = ARROW_HEAD * scale

    if (control == HousingControl.ValveCap) {
        // Filled edge to edge, the way the reference app fills its cap illustration: the whole
        // knob is the thing being pointed at, and a diver in gloves grabs the whole knob.
        val centre = Offset(CAP_CX * scale, CAP_CY * scale)
        drawCircle(GestureBlue.copy(alpha = 0.22f * alpha), CAP_GLOW_R * scale, centre)
        drawCircle(GestureBlue.copy(alpha = alpha), CAP_R * scale, centre)
        return
    }

    if (control == HousingControl.SliderSlide) {
        val ink = GestureBlue.brightened(boost).copy(alpha = alpha)
        drawRoundRect(
            color = ink,
            topLeft = Offset(SLIDER_X0 * scale, SLIDER_Y0 * scale),
            size = Size((SLIDER_X1 - SLIDER_X0) * scale, (SLIDER_Y1 - SLIDER_Y0) * scale),
            cornerRadius = CornerRadius(SLIDER_CORNER * scale),
        )
        val y = SLIDE_ARROW_Y * scale
        drawLine(ink, Offset(SLIDE_ARROW_X0 * scale, y), Offset(SLIDE_ARROW_X1 * scale, y), shaft)
        arrowHead(ink, Offset(SLIDE_ARROW_X0 * scale, y), head, dx = -1f, dy = 0f)
        arrowHead(ink, Offset(SLIDE_ARROW_X1 * scale, y), head, dx = 1f, dy = 0f)
    } else {
        val ink = PaddleBlue.brightened(boost).copy(alpha = alpha)
        // The paddle is an angled capsule; rotate around its own centroid so the measured centre
        // stays put while the lozenge follows the artwork's tilt.
        rotate(
            degrees = PADDLE_ANGLE_DEG,
            pivot = Offset(PADDLE_CX * scale, PADDLE_CY * scale),
        ) {
            drawRoundRect(
                color = ink,
                topLeft = Offset(
                    (PADDLE_CX - PADDLE_LEN / 2f) * scale,
                    (PADDLE_CY - PADDLE_THICK / 2f) * scale,
                ),
                size = Size(PADDLE_LEN * scale, PADDLE_THICK * scale),
                cornerRadius = CornerRadius(PADDLE_THICK / 2f * scale),
            )
        }
        val x = PRESS_ARROW_X * scale
        drawLine(ink, Offset(x, PRESS_ARROW_Y0 * scale), Offset(x, PRESS_ARROW_Y1 * scale), shaft)
        arrowHead(ink, Offset(x, PRESS_ARROW_Y1 * scale), head, dx = 0f, dy = 1f)
    }
}

private fun DrawScope.arrowHead(ink: Color, tip: Offset, head: Float, dx: Float, dy: Float) {
    // Perpendicular to the direction of travel, so one routine serves both axes.
    val px = -dy
    val py = dx
    val path = Path().apply {
        moveTo(tip.x + dx * head, tip.y + dy * head)
        lineTo(tip.x + px * head - dx * head * 0.15f, tip.y + py * head - dy * head * 0.15f)
        lineTo(tip.x - px * head - dx * head * 0.15f, tip.y - py * head - dy * head * 0.15f)
        close()
    }
    drawPath(path, ink)
}

/**
 * An alpha that breathes between [min] and 1, or a constant 1 when [active] is false.
 *
 * Two things matter here and neither is the maths. First, the transition is only *created* while
 * something is breathing: an [androidx.compose.animation.core.InfiniteTransition] asks for a frame
 * callback on every vsync for as long as it is in composition, so leaving a dormant one behind a
 * still screen costs a wake-up 60 times a second. Second, the result is handed back as a [State]
 * rather than an unwrapped `Float`, because the caller is expected to read it inside a draw or
 * `graphicsLayer` lambda — reading it here, in composition, would recompose the caller per frame.
 *
 * @param halfPeriodMs one leg of the cycle. The spec quotes full periods and the animation reverses
 *   rather than restarting, so a 1.1 s pulse is a 550 ms tween.
 */
@Composable
internal fun rememberPulseAlpha(
    active: Boolean,
    min: Float,
    halfPeriodMs: Int,
    easing: Easing,
    label: String,
): State<Float> {
    val value = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(active, min, halfPeriodMs) {
        if (!active) {
            value.floatValue = 1f
            return@LaunchedEffect
        }
        // Driven straight off the choreographer rather than the animation system, because this
        // device family can have animator_duration_scale = 0 (developer options / remove
        // animations). The standard animation clock snaps to the end state under that setting,
        // which turned the flashing pill into a solid block on the user's phone. These pulses are
        // functional signals, not decoration, so they run off wall-frame time unconditionally.
        var start = -1L
        val period = 2L * halfPeriodMs
        while (isActive) {
            withFrameMillis { now ->
                if (start < 0L) start = now
                val t = ((now - start) % period).toFloat() / halfPeriodMs   // 0..2
                val phase = if (t <= 1f) t else 2f - t                       // triangle 0..1..0
                value.floatValue = min + (1f - min) * easing.transform(phase)
            }
        }
    }
    return value
}

/**
 * Lays out a frame's label inside whichever safe box that frame's plate offers.
 *
 * A label with a hard line break gets block lettering: each line laid out at its OWN size so
 * every line spans the same width — "PRESS DOWN" big, its qualifier smaller but equally wide,
 * the way a sign painter would set them. A plain label stays a single wrapped layout.
 */
private fun TextMeasurer.layOutLabel(
    frame: IntroFrame,
    scale: Float,
    density: Float,
    fontScale: Float,
): List<TextLayoutResult> {
    if (frame.label.isEmpty()) return emptyList()
    val box = if (frame.tilted) TILT_SAFE_BOX else PORT_BOX
    val style = TextStyle(textAlign = TextAlign.Center, color = LabelBlue)
    if (!frame.label.contains('\n')) {
        return listOf(
            fitPlateText(
                text = glowAnnotated(frame.label, frame.glowText, scale),
                style = style,
                box = box,
                scale = scale,
                density = density,
                fontScale = fontScale,
            ),
        )
    }

    val lines = frame.label.split('\n')
        .filter { it.isNotBlank() }
        .map { glowAnnotated(it, frame.glowText, scale) }
    // Unbounded: each line is measured at its natural single-line width. (A finite huge number
    // here crashes — Constraints bit-packs its sizes and cannot represent arbitrary ints.)
    val wide = Constraints()

    // Probe each line once to learn its width-per-plate-pixel, then solve for the one shared
    // width W that satisfies every constraint: no line above the start size, the stack no
    // taller than the box, the width no wider than the box.
    val probes = lines.map { line ->
        measure(
            text = line,
            style = style.copy(fontSize = platePx(BLOCK_PROBE_PX, scale, density, fontScale)),
            maxLines = 1,
            constraints = wide,
        )
    }
    // Plate pixels of font per real pixel of width, per line.
    val unitPerWidth = probes.map { BLOCK_PROBE_PX / it.size.width.coerceAtLeast(1) }
    val boxWidthPx = box.width * scale
    val boxHeightPx = box.height * scale
    var widthPx = boxWidthPx
    // Cap: the widest-set line (largest unit) must not exceed the start size.
    widthPx = minOf(widthPx, LABEL_START_PX / unitPerWidth.max())
    // Cap: total height at this width, scaled linearly from the probes.
    val probeTotalHeight = probes.indices.sumOf { i ->
        (probes[i].size.height * (widthPx * unitPerWidth[i] / BLOCK_PROBE_PX)).toDouble()
    }.toFloat()
    if (probeTotalHeight > boxHeightPx) {
        widthPx *= boxHeightPx / probeTotalHeight
    }

    return lines.mapIndexed { i, line ->
        var sizePlatePx = (widthPx * unitPerWidth[i]).coerceAtLeast(LABEL_MIN_PX * 0.5f)
        var laid = measure(
            text = line,
            style = style.copy(fontSize = platePx(sizePlatePx, scale, density, fontScale)),
            maxLines = 1,
            constraints = wide,
        )
        // Glyph advances do not scale perfectly linearly between the probe size and the final
        // one, and a few percent of drift between lines is exactly what the eye catches on
        // block lettering. One correction pass against the measured width closes the gap.
        val correction = widthPx / laid.size.width.coerceAtLeast(1)
        if (correction in 0.5f..2f && kotlin.math.abs(correction - 1f) > 0.005f) {
            sizePlatePx = (sizePlatePx * correction).coerceAtLeast(LABEL_MIN_PX * 0.5f)
            laid = measure(
                text = line,
                style = style.copy(fontSize = platePx(sizePlatePx, scale, density, fontScale)),
                maxLines = 1,
                constraints = wide,
            )
        }
        laid
    }
}

/**
 * The glow span: the physical thing the sentence points at, set brighter than the sentence and
 * wrapped in a same-colour halo. The blur scales with the plate so the halo neither vanishes on
 * a large screen nor swallows the word on a small one.
 */
private fun glowAnnotated(text: String, glow: String?, scale: Float): AnnotatedString =
    buildAnnotatedString {
        val start = glow?.let { text.indexOf(it) } ?: -1
        if (glow != null && start >= 0) {
            append(text.substring(0, start))
            withStyle(
                SpanStyle(
                    // The exact blue the cap mime paints the cap with, so the words and the
                    // object read as the same thing.
                    color = GestureBlue,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = GestureBlue,
                        blurRadius = (GLOW_BLUR_PLATE_PX * scale).coerceAtLeast(4f),
                    ),
                ),
            ) { append(glow) }
            append(text.substring(start + glow.length))
        } else {
            append(text)
        }
    }

// --- plate space -----------------------------------------------------------------------------

/** A rectangle in plate pixels. */
internal data class PlateBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f
}

internal const val PLATE_W = 2340f
internal const val PLATE_H = 1080f

/** ContentScale.Fit, expressed as screen pixels per plate pixel. */
internal fun plateScale(size: Size): Float = min(size.width / PLATE_W, size.height / PLATE_H)

/** Where the drawn plate starts inside a composable of [size], for the same Fit mapping. */
internal fun plateOrigin(size: Size, scale: Float): Offset = Offset(
    x = (size.width - PLATE_W * scale) / 2f,
    y = (size.height - PLATE_H * scale) / 2f,
)

/**
 * A plate-pixel length expressed as sp.
 *
 * The font scale is divided out along with the density on purpose. These glyphs are welded to
 * artwork geometry: a diver with system font scaling turned up must not get a label that grows out
 * of the one box on the drawing where it is guaranteed not to touch a line.
 */
internal fun platePx(value: Float, scale: Float, density: Float, fontScale: Float): TextUnit =
    TextUnit(value * scale / (density * fontScale), TextUnitType.Sp)

/**
 * The largest size on a fixed ladder at which [text] still fits [box].
 *
 * Measure and shrink rather than one hand-tuned size. The strings are fixed today, but the boxes are
 * the only parts of the artwork that are provably free of grey drawing lines, and text that spills
 * out of one on some screen sizes and not others is precisely the silent breakage the product
 * forbids. The loop costs a handful of cached measure passes, and only when the frame changes.
 *
 * Both axes are checked: `hasVisualOverflow` catches a word wider than the box and a wrap past
 * [maxLines], while the explicit height comparison catches a block that fits horizontally but runs
 * off the bottom.
 */
internal fun TextMeasurer.fitPlateText(
    text: String,
    style: TextStyle,
    box: PlateBox,
    scale: Float,
    density: Float,
    fontScale: Float,
    startPlatePx: Float = LABEL_START_PX,
    minPlatePx: Float = LABEL_MIN_PX,
    stepPlatePx: Float = LABEL_STEP_PX,
    maxLines: Int = LABEL_MAX_LINES,
): TextLayoutResult = fitPlateText(
    AnnotatedString(text), style, box, scale, density, fontScale,
    startPlatePx, minPlatePx, stepPlatePx, maxLines,
)

internal fun TextMeasurer.fitPlateText(
    text: AnnotatedString,
    style: TextStyle,
    box: PlateBox,
    scale: Float,
    density: Float,
    fontScale: Float,
    startPlatePx: Float = LABEL_START_PX,
    minPlatePx: Float = LABEL_MIN_PX,
    stepPlatePx: Float = LABEL_STEP_PX,
    maxLines: Int = LABEL_MAX_LINES,
): TextLayoutResult {
    val constraints = Constraints(maxWidth = (box.width * scale).toInt().coerceAtLeast(1))
    val maxHeight = box.height * scale
    var platePixels = startPlatePx
    while (true) {
        val laid = measure(
            text = text,
            style = style.copy(
                fontSize = platePx(platePixels, scale, density, fontScale),
                lineHeight = platePx(platePixels * LABEL_LINE_SPACING, scale, density, fontScale),
            ),
            overflow = TextOverflow.Clip,
            maxLines = maxLines,
            constraints = constraints,
        )
        val fits = !laid.hasVisualOverflow && laid.size.height <= maxHeight
        if (fits || platePixels - stepPlatePx < minPlatePx) return laid
        platePixels -= stepPlatePx
    }
}

// --- geometry, in the plates' own 2340x1080 pixels --------------------------------------------

/** Measured on the supplied artwork by ring-matching; every one scored a perfect fit. */
internal val BUTTON_CENTRES: Map<HousingControl, Pair<Float, Float>> = mapOf(
    HousingControl.MenuOk to (1710f to 438f),
    HousingControl.Up to (1774f to 524f),
    HousingControl.Left to (1710f to 588f),
    HousingControl.Right to (1838f to 588f),
    HousingControl.Down to (1774f to 652f),
)

/** Fill-and-rim radii from the radial profile of 2.jpg: fill to r≈30, rim peak r≈33, gone by 39. */
private const val HIGHLIGHT_FILL_R = 30.5f
private const val HIGHLIGHT_RING_R = 33f
private const val HIGHLIGHT_RING_W = 5f
private const val HIGHLIGHT_BREATHE_MIN = 0.75f
private const val HIGHLIGHT_BREATHE_MS = 1500

/** 300 ms, per the design contract: long enough to read as a dissolve, short enough to feel exact. */
private const val CROSSFADE_MS = 300

private const val LABEL_START_PX = 88f

/** Probe size for block-lettered labels — arbitrary; only width ratios are read from it. */
private const val BLOCK_PROBE_PX = 60f


/** Halo radius in plate pixels; multiplied by the live plate scale before drawing. */
private const val GLOW_BLUR_PLATE_PX = 14f
private const val LABEL_MIN_PX = 32f
private const val LABEL_STEP_PX = 4f
private const val LABEL_MAX_LINES = 3
private const val LABEL_LINE_SPACING = 1.22f

/**
 * The clear black area inside the port on `housing_front`.
 *
 * Verified against the plate itself, not by eye: every pixel in x 495-1575 / y 290-790 has luminance
 * zero, so nothing drawn inside it can touch a drawing line.
 */
internal val PORT_BOX = PlateBox(left = 495f, top = 290f, right = 1575f, bottom = 790f)

/**
 * The clear black area inside the lens square on `housing_tilt`.
 *
 * The lens square itself runs to roughly x 1112-1888 / y 232-884 but carries stray artwork near its
 * left edge. x 1320-1860 / y 300-800 is the inset that measures luminance zero across every pixel.
 */
internal val TILT_SAFE_BOX = PlateBox(left = 1320f, top = 300f, right = 1860f, bottom = 800f)

/** The knurled lever on the top edge, located on the tilted plate. */
private const val SLIDER_X0 = 485f
private const val SLIDER_X1 = 682f
private const val SLIDER_Y0 = 269f
private const val SLIDER_Y1 = 312f
private const val SLIDER_CORNER = 16f

/**
 * The vacuum valve cap on the tilt plate — the gear-toothed knob. Centre and radius measured off
 * the artwork by ring-matching plus a zoomed visual pass; the fill covers the teeth.
 */
internal const val CAP_CX = 1020f
internal const val CAP_CY = 478f
internal const val CAP_R = 84f
internal const val CAP_GLOW_R = 118f

private const val SLIDE_ARROW_X0 = 500f
private const val SLIDE_ARROW_X1 = 690f
private const val SLIDE_ARROW_Y = 380f

/**
 * The shutter paddle on the tilted plate, PCA-fitted to the lit pixels of 8.jpg: centroid
 * (564, 207), major axis −7.1°, ~210 px long. Drawn as a rotated capsule.
 */
private const val PADDLE_CX = 564f
private const val PADDLE_CY = 207f
private const val PADDLE_LEN = 210f
private const val PADDLE_THICK = 62f
private const val PADDLE_ANGLE_DEG = -7.1f

/** Press arrow drops onto the paddle from above, ending clear of its top edge (per 8.jpg). */
private const val PRESS_ARROW_X = 520f
private const val PRESS_ARROW_Y0 = 78f
private const val PRESS_ARROW_Y1 = 148f
private const val ARROW_SHAFT = 20f
private const val ARROW_HEAD = 40f
