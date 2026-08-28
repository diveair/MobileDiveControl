package com.mobiledivecontrol.ui.camera

private const val PHI = 1.618033988749895f

internal data class NormalizedGuideLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

/** A quarter-ellipse arc in normalized capture coordinates. */
internal data class NormalizedGuideArc(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val startAngle: Float,
    val sweepAngle: Float = 90f,
)

internal data class FibonacciGuideGeometry(
    val lines: List<NormalizedGuideLine>,
    val arcs: List<NormalizedGuideArc>,
)

/**
 * Builds the standard Fibonacci guide: successive squares removed from a golden rectangle with
 * one tangent quarter-circle in each square. The eye can be reflected into any frame corner.
 */
internal fun fibonacciGuideGeometry(
    eyeOnLeft: Boolean,
    eyeOnTop: Boolean = false,
    iterations: Int = 8,
): FibonacciGuideGeometry {
    require(iterations > 0)

    var left = 0f
    var top = 0f
    var right = PHI
    var bottom = 1f
    val lines = mutableListOf<NormalizedGuideLine>()
    val arcs = mutableListOf<NormalizedGuideArc>()

    repeat(iterations) { iteration ->
        val orientation = iteration % 4
        val side: Float
        val centerX: Float
        val centerY: Float
        val startAngle: Float
        val divider: NormalizedGuideLine

        when (orientation) {
            0 -> {
                side = bottom - top
                val squareRight = left + side
                centerX = squareRight
                centerY = bottom
                startAngle = 180f
                divider = NormalizedGuideLine(squareRight, top, squareRight, bottom)
                left = squareRight
            }
            1 -> {
                side = right - left
                val squareBottom = top + side
                centerX = left
                centerY = squareBottom
                startAngle = 270f
                divider = NormalizedGuideLine(left, squareBottom, right, squareBottom)
                top = squareBottom
            }
            2 -> {
                side = bottom - top
                val squareLeft = right - side
                centerX = squareLeft
                centerY = top
                startAngle = 0f
                divider = NormalizedGuideLine(squareLeft, top, squareLeft, bottom)
                right = squareLeft
            }
            else -> {
                side = right - left
                val squareTop = bottom - side
                centerX = right
                centerY = squareTop
                startAngle = 90f
                divider = NormalizedGuideLine(left, squareTop, right, squareTop)
                bottom = squareTop
            }
        }

        if (iteration < iterations - 1) {
            lines += divider.normalizedAndMirrored(eyeOnLeft, eyeOnTop)
        }
        arcs += NormalizedGuideArc(
            centerX = centerX / PHI,
            centerY = centerY,
            radiusX = side / PHI,
            radiusY = side,
            startAngle = startAngle,
        ).mirroredIfNeeded(eyeOnLeft, eyeOnTop)
    }

    return FibonacciGuideGeometry(lines = lines, arcs = arcs)
}

private fun NormalizedGuideLine.normalizedAndMirrored(
    eyeOnLeft: Boolean,
    eyeOnTop: Boolean,
): NormalizedGuideLine {
    var transformed = copy(startX = startX / PHI, endX = endX / PHI)
    if (eyeOnLeft) {
        transformed = transformed.copy(
            startX = 1f - transformed.startX,
            endX = 1f - transformed.endX,
        )
    }
    if (eyeOnTop) {
        transformed = transformed.copy(
            startY = 1f - transformed.startY,
            endY = 1f - transformed.endY,
        )
    }
    return transformed
}

private fun NormalizedGuideArc.mirroredIfNeeded(
    eyeOnLeft: Boolean,
    eyeOnTop: Boolean,
): NormalizedGuideArc {
    var transformed = this
    if (eyeOnLeft) {
        val mirroredStart = normalizeAngle(180f - transformed.startAngle - transformed.sweepAngle)
        transformed = transformed.copy(centerX = 1f - transformed.centerX, startAngle = mirroredStart)
    }
    if (eyeOnTop) {
        val mirroredStart = normalizeAngle(360f - transformed.startAngle - transformed.sweepAngle)
        transformed = transformed.copy(centerY = 1f - transformed.centerY, startAngle = mirroredStart)
    }
    return transformed
}

private fun normalizeAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f
