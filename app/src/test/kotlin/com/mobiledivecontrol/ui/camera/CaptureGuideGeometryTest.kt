package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class CaptureGuideGeometryTest {
    @Test
    fun `guide uses successive golden rectangle subdivisions`() {
        val guide = fibonacciGuideGeometry(eyeOnLeft = false)

        assertEquals(8, guide.arcs.size)
        assertEquals(7, guide.lines.size)
        assertEquals(0.61803395f, guide.lines[0].startX, 0.000001f)
        assertEquals(0.61803395f, guide.lines[1].startY, 0.000001f)
        assertEquals(0.61803395f, guide.arcs[0].centerX, 0.000001f)
        assertEquals(1f, guide.arcs[0].centerY, 0.000001f)
        assertEquals(180f, guide.arcs[0].startAngle, 0.000001f)
        assertTrue(guide.arcs.zipWithNext().all { (outer, inner) -> inner.radiusY < outer.radiusY })
        assertTrue(guide.arcs.zipWithNext().all { (outer, inner) ->
            assertEquals(1.6180339f, outer.radiusY / inner.radiusY, 0.0001f)
            val outerEnd = arcPoint(outer, outer.startAngle + outer.sweepAngle)
            val innerStart = arcPoint(inner, inner.startAngle)
            val outerTangent = arcTangent(outer, outer.startAngle + outer.sweepAngle)
            val innerTangent = arcTangent(inner, inner.startAngle)
            outerEnd.distanceTo(innerStart) < 0.00001f && outerTangent.dot(innerTangent) > 0.9999f
        })
    }

    @Test
    fun `left and right guides are exact horizontal mirrors`() {
        val right = fibonacciGuideGeometry(eyeOnLeft = false)
        val left = fibonacciGuideGeometry(eyeOnLeft = true)

        right.lines.zip(left.lines).forEach { (source, mirror) ->
            assertEquals(1f - source.startX, mirror.startX, 0.000001f)
            assertEquals(source.startY, mirror.startY, 0.000001f)
            assertEquals(1f - source.endX, mirror.endX, 0.000001f)
            assertEquals(source.endY, mirror.endY, 0.000001f)
        }
        right.arcs.zip(left.arcs).forEach { (source, mirror) ->
            assertEquals(1f - source.centerX, mirror.centerX, 0.000001f)
            assertEquals(source.centerY, mirror.centerY, 0.000001f)
            assertEquals(source.radiusX, mirror.radiusX, 0.000001f)
            assertEquals(source.radiusY, mirror.radiusY, 0.000001f)
        }
    }

    @Test
    fun `top and bottom guides are exact vertical mirrors`() {
        val bottom = fibonacciGuideGeometry(eyeOnLeft = false, eyeOnTop = false)
        val top = fibonacciGuideGeometry(eyeOnLeft = false, eyeOnTop = true)

        bottom.lines.zip(top.lines).forEach { (source, mirror) ->
            assertEquals(source.startX, mirror.startX, 0.000001f)
            assertEquals(1f - source.startY, mirror.startY, 0.000001f)
            assertEquals(source.endX, mirror.endX, 0.000001f)
            assertEquals(1f - source.endY, mirror.endY, 0.000001f)
        }
        bottom.arcs.zip(top.arcs).forEach { (source, mirror) ->
            assertEquals(source.centerX, mirror.centerX, 0.000001f)
            assertEquals(1f - source.centerY, mirror.centerY, 0.000001f)
            assertEquals(source.radiusX, mirror.radiusX, 0.000001f)
            assertEquals(source.radiusY, mirror.radiusY, 0.000001f)
        }
    }

    @Test
    fun `all visible construction coordinates remain inside the capture frame`() {
        listOf(false, true).forEach { eyeOnLeft ->
            listOf(false, true).forEach { eyeOnTop ->
                val guide = fibonacciGuideGeometry(eyeOnLeft = eyeOnLeft, eyeOnTop = eyeOnTop)
                guide.lines.forEach { line ->
                    listOf(line.startX, line.startY, line.endX, line.endY).forEach { coordinate ->
                        assertTrue(coordinate in 0f..1f)
                    }
                }
                guide.arcs.forEach { arc ->
                    assertTrue(arc.centerX in 0f..1f)
                    assertTrue(arc.centerY in 0f..1f)
                    assertEquals(90f, arc.sweepAngle)
                }
            }
        }
    }

    @Test
    fun `every mirrored spiral keeps tangent joins`() {
        listOf(false, true).forEach { eyeOnLeft ->
            listOf(false, true).forEach { eyeOnTop ->
                val arcs = fibonacciGuideGeometry(eyeOnLeft = eyeOnLeft, eyeOnTop = eyeOnTop).arcs
                arcs.zipWithNext().forEach { (outer, inner) ->
                    val outerEndpoints = listOf(
                        outer.startAngle to arcPoint(outer, outer.startAngle),
                        (outer.startAngle + outer.sweepAngle) to
                            arcPoint(outer, outer.startAngle + outer.sweepAngle),
                    )
                    val innerEndpoints = listOf(
                        inner.startAngle to arcPoint(inner, inner.startAngle),
                        (inner.startAngle + inner.sweepAngle) to
                            arcPoint(inner, inner.startAngle + inner.sweepAngle),
                    )
                    val join = outerEndpoints.flatMap { outerEndpoint ->
                        innerEndpoints.map { innerEndpoint -> outerEndpoint to innerEndpoint }
                    }.minBy { (outerEndpoint, innerEndpoint) ->
                        outerEndpoint.second.distanceTo(innerEndpoint.second)
                    }
                    assertTrue(join.first.second.distanceTo(join.second.second) < 0.00001f)
                    assertTrue(
                        kotlin.math.abs(
                            arcTangent(outer, join.first.first)
                                .dot(arcTangent(inner, join.second.first)),
                        ) > 0.9999f,
                    )
                }
            }
        }
    }

    private fun arcPoint(arc: NormalizedGuideArc, angle: Float): GuidePoint {
        val radians = Math.toRadians(angle.toDouble())
        return GuidePoint(
            x = arc.centerX + arc.radiusX * cos(radians).toFloat(),
            y = arc.centerY + arc.radiusY * sin(radians).toFloat(),
        )
    }

    private fun arcTangent(arc: NormalizedGuideArc, angle: Float): GuidePoint {
        val radians = Math.toRadians(angle.toDouble())
        return GuidePoint(
            x = -arc.radiusX * sin(radians).toFloat(),
            y = arc.radiusY * cos(radians).toFloat(),
        ).normalized()
    }

    private data class GuidePoint(val x: Float, val y: Float) {
        fun distanceTo(other: GuidePoint): Float = hypot(x - other.x, y - other.y)
        fun dot(other: GuidePoint): Float = x * other.x + y * other.y
        fun normalized(): GuidePoint {
            val length = hypot(x, y)
            return GuidePoint(x / length, y / length)
        }
    }
}
