package com.mobiledivecontrol.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Text that gives up size before it gives up words.
 *
 * Every HUD string here is an instruction to a diver wearing gloves at depth, so a clipped
 * ending is worse than small type: "TARGET REACHED — CLOSE THE BLUE C" is not a shorter
 * instruction, it is a wrong one. The layout is measured, and while the laid-out result
 * overflows its box the font drops one step and is measured again, down to [minFontSize].
 *
 * Nothing is drawn until the search settles (via [drawWithContent], so the gate costs no
 * recomposition) because a visible jump from 24 sp to 17 sp reads as a glitch. The step is 2 sp
 * rather than 1 to halve the number of measure passes that gate has to hide — a banner that takes
 * a fifth of a second to appear is a banner the diver has already looked away from.
 *
 * [maxLines] of 2 is the default rather than 1: wrapping is the cheaper way to fit a long string,
 * and shrinking only picks up what wrapping could not.
 *
 * @param minFontSize the floor. Below roughly 11 sp the text stops being readable through a
 *   housing port in low visibility, so at that point clipping is preferable to a lie of legibility
 *   and the caller should shorten the string instead.
 */
@Composable
fun AutoShrinkText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    minFontSize: TextUnit = 11.sp,
    stepSp: Float = 2f,
) {
    val startSize = if (style.fontSize.isSpecified) style.fontSize else DEFAULT_START_SIZE

    // Keyed on everything that changes what "fits" means, so a new string re-runs the search
    // from the full size instead of inheriting the previous string's shrink.
    var fontSize by remember(text, startSize, maxLines) { mutableStateOf(startSize) }
    var settled by remember(text, startSize, maxLines) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        style = style.copy(fontSize = fontSize),
        maxLines = maxLines,
        softWrap = true,
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        onTextLayout = { layout ->
            if (!settled) {
                if (layout.hasVisualOverflow && fontSize.value - stepSp >= minFontSize.value) {
                    fontSize = (fontSize.value - stepSp).sp
                } else {
                    settled = true
                }
            }
        },
    )
}

/**
 * [AnnotatedString] variant, for the one banner headline that carries a colour change mid-line.
 * Same search, same gate; the span colours ride along untouched while only the size shrinks.
 */
@Composable
fun AutoShrinkText(
    text: AnnotatedString,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    minFontSize: TextUnit = 11.sp,
    stepSp: Float = 2f,
) {
    val startSize = if (style.fontSize.isSpecified) style.fontSize else DEFAULT_START_SIZE

    // Keyed on the CHARACTERS, not the AnnotatedString: span colours animate (the flashing
    // accent) without changing what fits, and re-running the search on every blink would hide
    // the text for the frames each search takes.
    var fontSize by remember(text.text, startSize, maxLines) { mutableStateOf(startSize) }
    var settled by remember(text.text, startSize, maxLines) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        style = style.copy(fontSize = fontSize),
        maxLines = maxLines,
        softWrap = true,
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        onTextLayout = { layout ->
            if (!settled) {
                if (layout.hasVisualOverflow && fontSize.value - stepSp >= minFontSize.value) {
                    fontSize = (fontSize.value - stepSp).sp
                } else {
                    settled = true
                }
            }
        },
    )
}

/** Only reached when the caller passes a style with no concrete size; body copy is a safe guess. */
private val DEFAULT_START_SIZE = 16.sp
