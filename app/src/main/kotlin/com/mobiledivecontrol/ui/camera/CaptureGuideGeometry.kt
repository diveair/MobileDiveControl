package com.mobiledivecontrol.ui.camera

private const val PHI = 1.618033988749895f

internal data class NormalizedGuideLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

/** An ellipse arc in normalized capture coordinates; radii may extend outside the viewport. */
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
 * a tangent quarter-circle in each square. Left and right are exact horizontal mirrors.
 */
internal fun fibonacciGuideGeometry(
    eyeOnLeft: Boolean,
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
                centerX = left
                centerY = top
                startAngle = 0f
                divider = NormalizedGuideLine(left + side, top, left + side, bottom)
                left += side
            }
            1 -> {
                side = right - left
                centerX = right
                centerY = top
                startAngle = 90f
                divider = NormalizedGuideLine(left, top + side, right, top + side)
                top += side
            }
            2 -> {
                side = bottom - top
                centerX = right
                centerY = bottom
                startAngle = 180f
                divider = NormalizedGuideLine(right - side, top, right - side, bottom)
                right -= side
            }
            else -> {
                side = right - left
                centerX = left
                centerY = bottom
                startAngle = 270f
                divider = NormalizedGuideLine(left, bottom - side, right, bottom - side)
                bottom -= side
            }
        }

        if (iteration < iterations - 1) lines += divider.normalizedAndMirrored(eyeOnLeft)
        arcs += NormalizedGuideArc(
            centerX = centerX / PHI,
            centerY = centerY,
            radiusX = side / PHI,
            radiusY = side,
            startAngle = startAngle,
        ).mirroredIfNeeded(eyeOnLeft)
    }

    return FibonacciGuideGeometry(lines = lines, arcs = arcs)
}

private fun NormalizedGuideLine.normalizedAndMirrored(eyeOnLeft: Boolean): NormalizedGuideLine {
    val normalized = copy(startX = startX / PHI, endX = endX / PHI)
    return if (!eyeOnLeft) normalized else normalized.copy(
        startX = 1f - normalized.startX,
        endX = 1f - normalized.endX,
    )
}

private fun NormalizedGuideArc.mirroredIfNeeded(eyeOnLeft: Boolean): NormalizedGuideArc {
    if (!eyeOnLeft) return this
    val mirroredStart = ((180f - startAngle - sweepAngle) % 360f + 360f) % 360f
    return copy(centerX = 1f - centerX, startAngle = mirroredStart)
}
