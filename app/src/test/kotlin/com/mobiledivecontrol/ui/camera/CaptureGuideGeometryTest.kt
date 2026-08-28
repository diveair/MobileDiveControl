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
        assertTrue(guide.arcs.zipWithNext().all { (outer, inner) -> inner.radiusY < outer.radiusY })
        assertTrue(guide.arcs.zipWithNext().all { (outer, inner) ->
            assertEquals(1.6180339f, outer.radiusY / inner.radiusY, 0.0001f)
            arcEndpoints(outer).any { outerPoint ->
                arcEndpoints(inner).any { innerPoint -> outerPoint.distanceTo(innerPoint) < 0.00001f }
            }
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

    private fun arcEndpoints(arc: NormalizedGuideArc): List<GuidePoint> =
        listOf(arc.startAngle, arc.startAngle + arc.sweepAngle).map { angle ->
            val radians = Math.toRadians(angle.toDouble())
            GuidePoint(
                x = arc.centerX + arc.radiusX * cos(radians).toFloat(),
                y = arc.centerY + arc.radiusY * sin(radians).toFloat(),
            )
        }

    private data class GuidePoint(val x: Float, val y: Float) {
        fun distanceTo(other: GuidePoint): Float = hypot(x - other.x, y - other.y)
    }
}
