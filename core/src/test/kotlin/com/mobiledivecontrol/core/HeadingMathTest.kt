package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadingMathTest {
    @Test
    fun `normalizes and takes the short turn across north`() {
        assertEquals(359.0, HeadingMath.normalize(-1.0), 0.0001)
        assertEquals(2.0, HeadingMath.shortestDelta(359.0, 1.0), 0.0001)
        assertEquals(-2.0, HeadingMath.shortestDelta(1.0, 359.0), 0.0001)
    }

    @Test
    fun `back camera optical axis reads rotation matrix column`() {
        // Back-camera -Z points north: matrix column Z is (east=0, north=-1, up=0).
        val north = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        assertEquals(0.0, HeadingMath.backCameraHeading(north)!!, 0.0001)

        // Back-camera -Z points east: matrix column Z is (-1, 0, 0).
        val east = floatArrayOf(
            0f, 0f, -1f,
            1f, 0f, 0f,
            0f, -1f, 0f,
        )
        assertEquals(90.0, HeadingMath.backCameraHeading(east)!!, 0.0001)
    }

    @Test
    fun `vertical optical axis has no compass heading`() {
        val down = CameraBasis(
            rightEast = 1.0,
            rightNorth = 0.0,
            rightUp = 0.0,
            screenUpEast = 0.0,
            screenUpNorth = 1.0,
            screenUpUp = 0.0,
            forwardEast = 0.0,
            forwardNorth = 0.0,
            forwardUp = -1.0,
        )
        assertNull(HeadingMath.cameraHeading(down))
        assertEquals(0.0, HeadingMath.cameraFrameHeading(down)!!, 0.0001)
    }

    @Test
    fun `camera frame heading remains north continuously through downward pitch`() {
        listOf(0.0, 20.0, 45.0, 70.0, 89.0, 90.0).forEach { degrees ->
            val pitch = Math.toRadians(degrees)
            val basis = levelNorthBasis().copy(
                screenUpNorth = kotlin.math.sin(pitch),
                screenUpUp = kotlin.math.cos(pitch),
                forwardNorth = kotlin.math.cos(pitch),
                forwardUp = -kotlin.math.sin(pitch),
            )
            assertEquals(
                0.0,
                HeadingMath.cameraFrameHeading(basis)!!,
                0.0001,
                "pitch=$degrees",
            )
        }
    }

    @Test
    fun `navigation arrow projects world heading through camera basis`() {
        val levelNorth = CameraBasis(
            rightEast = 1.0,
            rightNorth = 0.0,
            rightUp = 0.0,
            screenUpEast = 0.0,
            screenUpNorth = 0.0,
            screenUpUp = 1.0,
            forwardEast = 0.0,
            forwardNorth = 1.0,
            forwardUp = 0.0,
        )
        assertEquals(0.0, HeadingMath.navigationArrowRotation(levelNorth, 0.0), 0.0001)
        assertEquals(90.0, HeadingMath.navigationArrowRotation(levelNorth, 90.0), 0.0001)
        assertEquals(180.0, HeadingMath.navigationArrowRotation(levelNorth, 180.0), 0.0001)
        assertEquals(-90.0, HeadingMath.navigationArrowRotation(levelNorth, 270.0), 0.0001)

        // Camera pitched upward: the north/horizon target is below the optical axis on screen.
        val pitchedUpNorth = levelNorth.copy(
            screenUpNorth = -0.5,
            screenUpUp = 0.866,
            forwardNorth = 0.866,
            forwardUp = 0.5,
        )
        assertEquals(-180.0, HeadingMath.navigationArrowRotation(pitchedUpNorth, 0.0), 0.0001)
    }

    @Test
    fun `upright on-heading arrow points into the camera perspective without collapsing`() {
        val uprightNorth = levelNorthBasis()
        val mesh = HeadingMath.navigationArrowMesh(uprightNorth, 0.0)
        val tip = mesh.upperFace[0]
        val tail = midpoint(mesh.upperFace[1], mesh.upperFace[3])
        val tailNotch = mesh.upperFace[2]

        assertEquals(4, mesh.upperFace.size, "the navigation silhouette must be a concave dart")
        assertTrue(tip.y < tail.y, "the far tip must converge upward toward the vanishing point")
        assertTrue(
            tailNotch.y < tail.y && tailNotch.y > tip.y,
            "the butt must have a pointy indent toward the arrow tip",
        )
        assertEquals(tail.x, tailNotch.x, 0.0001, "the tail notch must remain centred")
        assertTrue(mesh.upperFace.maxOf { it.x } - mesh.upperFace.minOf { it.x } > 0.25)
        assertTrue(mesh.lowerFace != mesh.upperFace, "the arrow must retain visible 3D extrusion")
        assertEquals(0.0, mesh.yawErrorDegrees!!, 0.0001)
    }

    @Test
    fun `phone pitch changes arrow perspective rather than only its compass rotation`() {
        val level = HeadingMath.navigationArrowMesh(levelNorthBasis(), 0.0)
        val pitch = Math.toRadians(35.0)
        val pitchedUp = levelNorthBasis().copy(
            screenUpNorth = -kotlin.math.sin(pitch),
            screenUpUp = kotlin.math.cos(pitch),
            forwardNorth = kotlin.math.cos(pitch),
            forwardUp = kotlin.math.sin(pitch),
        )
        val tilted = HeadingMath.navigationArrowMesh(pitchedUp, 0.0)

        val levelLength = distance(level.upperFace[0], midpoint(level.upperFace[1], level.upperFace[3]))
        val tiltedLength = distance(tilted.upperFace[0], midpoint(tilted.upperFace[1], tilted.upperFace[3]))
        val levelExtrusion = distance(level.lowerFace[0], level.upperFace[0])
        val tiltedExtrusion = distance(tilted.lowerFace[0], tilted.upperFace[0])
        assertTrue(kotlin.math.abs(levelLength - tiltedLength) > 0.02)
        assertTrue(kotlin.math.abs(levelExtrusion - tiltedExtrusion) > 0.01)
    }

    @Test
    fun `phone roll rotates the world-horizontal arrow plane`() {
        val level = HeadingMath.navigationArrowMesh(levelNorthBasis(), 0.0)
        val rolledClockwise = CameraBasis(
            rightEast = 0.0,
            rightNorth = 0.0,
            rightUp = -1.0,
            screenUpEast = 1.0,
            screenUpNorth = 0.0,
            screenUpUp = 0.0,
            forwardEast = 0.0,
            forwardNorth = 1.0,
            forwardUp = 0.0,
        )
        val rolled = HeadingMath.navigationArrowMesh(rolledClockwise, 0.0)
        val levelTip = level.upperFace[0]
        val levelTail = midpoint(level.upperFace[1], level.upperFace[3])
        val rolledTip = rolled.upperFace[0]
        val rolledTail = midpoint(rolled.upperFace[1], rolled.upperFace[3])

        assertTrue(kotlin.math.abs(levelTip.y - levelTail.y) > kotlin.math.abs(levelTip.x - levelTail.x))
        assertTrue(kotlin.math.abs(rolledTip.x - rolledTail.x) > kotlin.math.abs(rolledTip.y - rolledTail.y))
    }

    @Test
    fun `navigation dart points intuitively left right and behind around a level camera`() {
        fun direction(target: Double): ProjectedArrowPoint {
            val face = HeadingMath.navigationArrowMesh(levelNorthBasis(), target).upperFace
            val tail = midpoint(face[1], face[3])
            return ProjectedArrowPoint(face[0].x - tail.x, face[0].y - tail.y)
        }

        val right = direction(90.0)
        val behind = direction(180.0)
        val left = direction(270.0)
        assertTrue(right.x > 0.0 && kotlin.math.abs(right.x) > kotlin.math.abs(right.y))
        assertTrue(behind.y > 0.0 && kotlin.math.abs(behind.y) > kotlin.math.abs(behind.x))
        assertTrue(left.x < 0.0 && kotlin.math.abs(left.x) > kotlin.math.abs(left.y))
    }

    @Test
    fun `stationary yaw correction preserves pitch roll and orthonormal camera axes`() {
        val pitch = Math.toRadians(28.0)
        val northPitched = levelNorthBasis().copy(
            screenUpNorth = -kotlin.math.sin(pitch),
            screenUpUp = kotlin.math.cos(pitch),
            forwardNorth = kotlin.math.cos(pitch),
            forwardUp = kotlin.math.sin(pitch),
        )
        val eastPitched = HeadingMath.alignBasisHeading(northPitched, 90.0)

        assertEquals(90.0, HeadingMath.cameraFrameHeading(eastPitched)!!, 0.0001)
        assertEquals(northPitched.rightUp, eastPitched.rightUp, 0.0)
        assertEquals(northPitched.screenUpUp, eastPitched.screenUpUp, 0.0)
        assertEquals(northPitched.forwardUp, eastPitched.forwardUp, 0.0)
        assertEquals(1.0, axisLength(eastPitched.forwardEast, eastPitched.forwardNorth, eastPitched.forwardUp), 0.0001)
        assertEquals(
            0.0,
            dot(
                eastPitched.forwardEast,
                eastPitched.forwardNorth,
                eastPitched.forwardUp,
                eastPitched.screenUpEast,
                eastPitched.screenUpNorth,
                eastPitched.screenUpUp,
            ),
            0.0001,
        )
    }

    @Test
    fun `heading motion gate ignores pure pitch and responds to world yaw`() {
        val north = levelNorthBasis()
        assertEquals(
            0.0,
            HeadingMath.headingAngularSpeed(angularVelocityUp = 0.0)!!,
            0.0001,
        )
        assertEquals(
            0.25,
            HeadingMath.headingAngularSpeed(angularVelocityUp = 0.25)!!,
            0.0001,
        )

        val pitch = Math.toRadians(42.0)
        val pitchedNorth = north.copy(
            screenUpNorth = -kotlin.math.sin(pitch),
            screenUpUp = kotlin.math.cos(pitch),
            forwardNorth = kotlin.math.cos(pitch),
            forwardUp = kotlin.math.sin(pitch),
        )
        assertEquals(
            0.0,
            HeadingMath.headingAngularSpeed(angularVelocityUp = 0.0)!!,
            0.0001,
        )
        val straightDown = pitchedNorth.copy(
            screenUpNorth = 1.0,
            screenUpUp = 0.0,
            forwardNorth = 0.0,
            forwardUp = -1.0,
        )
        assertEquals(0.0, HeadingMath.cameraFrameHeading(straightDown)!!, 0.0001)
        assertEquals(0.0, HeadingMath.headingAngularSpeed(0.0)!!, 0.0001)
        assertEquals(0.25, HeadingMath.headingAngularSpeed(0.25)!!, 0.0001)
    }

    @Test
    fun `image centre preserves heading and edges follow horizontal field of view`() {
        assertEquals(350.0, HeadingMath.targetFromImageRay(350.0, 0.5, 80.0), 0.0001)
        assertEquals(30.0, HeadingMath.targetFromImageRay(350.0, 1.0, 80.0), 0.0001)
        assertEquals(310.0, HeadingMath.targetFromImageRay(350.0, 0.0, 80.0), 0.0001)
    }


    private fun levelNorthBasis() = CameraBasis(
        rightEast = 1.0,
        rightNorth = 0.0,
        rightUp = 0.0,
        screenUpEast = 0.0,
        screenUpNorth = 0.0,
        screenUpUp = 1.0,
        forwardEast = 0.0,
        forwardNorth = 1.0,
        forwardUp = 0.0,
    )

    private fun midpoint(a: ProjectedArrowPoint, b: ProjectedArrowPoint) = ProjectedArrowPoint(
        x = (a.x + b.x) / 2.0,
        y = (a.y + b.y) / 2.0,
    )

    private fun distance(a: ProjectedArrowPoint, b: ProjectedArrowPoint): Double = kotlin.math.hypot(
        a.x - b.x,
        a.y - b.y,
    )

    private fun axisLength(x: Double, y: Double, z: Double): Double =
        kotlin.math.sqrt(x * x + y * y + z * z)

    private fun dot(
        ax: Double,
        ay: Double,
        az: Double,
        bx: Double,
        by: Double,
        bz: Double,
    ): Double = ax * bx + ay * by + az * bz
}
