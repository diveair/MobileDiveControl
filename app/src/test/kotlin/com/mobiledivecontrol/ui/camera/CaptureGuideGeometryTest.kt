package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureGuideGeometryTest {
    @Test
    fun `guide uses successive golden rectangle subdivisions`() {
        val guide = fibonacciGuideGeometry(eyeOnLeft = false)

        assertEquals(8, guide.arcs.size)
        assertEquals(7, guide.lines.size)
        assertEquals(0.61803395f, guide.lines[0].startX, 0.000001f)
        assertEquals(0.61803395f, guide.lines[1].startY, 0.000001f)
        assertTrue(guide.arcs.zipWithNext().all { (outer, inner) -> inner.radiusY < outer.radiusY })
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
    fun `all visible construction coordinates remain inside the capture frame`() {
        fibonacciGuideGeometry(eyeOnLeft = false).lines.forEach { line ->
            listOf(line.startX, line.startY, line.endX, line.endY).forEach { coordinate ->
                assertTrue(coordinate in 0f..1f)
            }
        }
        fibonacciGuideGeometry(eyeOnLeft = false).arcs.forEach { arc ->
            assertTrue(arc.centerX in 0f..1f)
            assertTrue(arc.centerY in 0f..1f)
            assertEquals(90f, arc.sweepAngle)
        }
    }
}
