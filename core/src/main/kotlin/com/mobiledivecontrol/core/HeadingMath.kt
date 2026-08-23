package com.mobiledivecontrol.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan

/** Back-camera axes expressed in the magnetic east/north/up world frame. */
data class CameraBasis(
    val rightEast: Double,
    val rightNorth: Double,
    val rightUp: Double,
    val screenUpEast: Double,
    val screenUpNorth: Double,
    val screenUpUp: Double,
    val forwardEast: Double,
    val forwardNorth: Double,
    val forwardUp: Double,
)

/** One point of the perspective-projected navigation marker, centred in a [-1, 1] viewport. */
data class ProjectedArrowPoint(
    val x: Double,
    val y: Double,
)

/**
 * Two faces of a shallow 3D navigation-arrow prism.
 *
 * The prism lies in the magnetic east/north plane. [lowerFace] and [upperFace] share their
 * perimeter order, allowing the renderer to connect corresponding points without knowing any
 * sensor or camera mathematics.
 */
data class NavigationArrowMesh(
    val lowerFace: List<ProjectedArrowPoint>,
    val upperFace: List<ProjectedArrowPoint>,
    val yawErrorDegrees: Double?,
)

/** Pure bearing math shared by the Android sensors, gesture ray, HUD and JVM tests. */
object HeadingMath {
    fun normalize(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    /** Signed shortest turn from [fromDegrees] to [toDegrees], in [-180, 180). */
    fun shortestDelta(fromDegrees: Double, toDegrees: Double): Double =
        ((toDegrees - fromDegrees + 540.0) % 360.0) - 180.0

    /**
     * Bearing of the back camera's optical axis from Android's rotation-vector matrix.
     *
     * Android device +Z points out through the display; the back camera looks along -Z. The
     * rotation matrix maps device vectors into east/north/up world coordinates, so column 2 is
     * the display normal and its negation is the back-camera ray. A nearly vertical ray has no
     * defensible compass heading and returns null instead of preserving a stale value.
     */
    fun backCameraHeading(rotationMatrix: FloatArray): Double? {
        val basis = backCameraBasis(rotationMatrix) ?: return null
        return cameraHeading(basis)
    }

    /** Horizontal azimuth of [basis]'s optical axis, or null when the camera is near vertical. */
    fun cameraHeading(basis: CameraBasis): Double? {
        val east = basis.forwardEast
        val north = basis.forwardNorth
        if (hypot(east, north) < MIN_HEADING_PROJECTION) return null
        return normalize(atan2(east, north) * 180.0 / PI)
    }

    /**
     * Continuous yaw of the complete camera frame, including a vertical optical axis.
     *
     * Forward azimuth alone is singular when the camera looks at the floor or sky. Removing the
     * forward axis' vertical component through screen-up supplies the same north/east reference
     * at every pitch: forward while level, image-top while looking down, and inverse image-top
     * while looking up. A pure pitch therefore cannot manufacture a compass turn.
     */
    fun cameraFrameHeading(basis: CameraBasis): Double? {
        val east = basis.forwardEast - basis.forwardUp * basis.screenUpEast
        val north = basis.forwardNorth - basis.forwardUp * basis.screenUpNorth
        if (hypot(east, north) < MIN_FRAME_HEADING_PROJECTION) return null
        return normalize(atan2(east, north) * 180.0 / PI)
    }

    /** [rotationMatrix] maps screen-right, screen-up and display-out axes into world space. */
    fun backCameraBasis(rotationMatrix: FloatArray): CameraBasis? {
        if (rotationMatrix.size < 9) return null
        return CameraBasis(
            rightEast = rotationMatrix[0].toDouble(),
            rightNorth = rotationMatrix[3].toDouble(),
            rightUp = rotationMatrix[6].toDouble(),
            screenUpEast = rotationMatrix[1].toDouble(),
            screenUpNorth = rotationMatrix[4].toDouble(),
            screenUpUp = rotationMatrix[7].toDouble(),
            // Device/screen +Z points through the display; the back camera looks along -Z.
            forwardEast = -rotationMatrix[2].toDouble(),
            forwardNorth = -rotationMatrix[5].toDouble(),
            forwardUp = -rotationMatrix[8].toDouble(),
        )
    }

    /**
     * Rotate a screen-up navigation triangle toward a horizontal world heading through the live
     * camera basis. This is a camera-perspective projection: pitch and roll affect the cue, not
     * merely magnetic yaw. A target exactly ahead points up; exactly behind points down.
     */
    fun navigationArrowRotation(basis: CameraBasis, targetHeadingDegrees: Double): Double {
        val radians = normalize(targetHeadingDegrees) * PI / 180.0
        val targetEast = kotlin.math.sin(radians)
        val targetNorth = kotlin.math.cos(radians)
        val screenX = targetEast * basis.rightEast + targetNorth * basis.rightNorth
        val screenUp = targetEast * basis.screenUpEast + targetNorth * basis.screenUpNorth
        val forward = targetEast * basis.forwardEast + targetNorth * basis.forwardNorth
        if (hypot(screenX, screenUp) < 0.04) return if (forward >= 0.0) 0.0 else 180.0
        return shortestDelta(0.0, atan2(screenX, screenUp) * 180.0 / PI)
    }

    /**
     * Perspective-project a real 3D navigation arrow onto the camera display.
     *
     * A heading is a horizontal world direction, so the marker is modelled as a shallow arrow
     * resting on a virtual horizontal plane in front of and below the camera. This is materially
     * different from rotating a flat HUD icon: when the phone is upright and faces the target,
     * the arrow's far tip converges toward the camera vanishing point; pitching or rolling the
     * phone changes the projected face, side walls and foreshortening.
     *
     * The virtual placement is relative to the optical axis, not a claimed physical seabed
     * position. It supplies perspective while the stored target remains an azimuth only.
     */
    fun navigationArrowMesh(
        basis: CameraBasis,
        targetHeadingDegrees: Double,
    ): NavigationArrowMesh {
        val radians = normalize(targetHeadingDegrees) * PI / 180.0
        val target = Vector3(
            east = kotlin.math.sin(radians),
            north = kotlin.math.cos(radians),
            up = 0.0,
        )
        // Horizontal right of the target direction. Keeping this in world space is what makes
        // phone roll rotate the entire projected plane instead of merely rotating an icon.
        val targetRight = Vector3(
            east = kotlin.math.cos(radians),
            north = -kotlin.math.sin(radians),
            up = 0.0,
        )
        val cameraForward = Vector3(
            basis.forwardEast,
            basis.forwardNorth,
            basis.forwardUp,
        )
        val centre = cameraForward * ARROW_DISTANCE - WORLD_UP * ARROW_PLANE_DROP

        val lowerRaw = ARROW_OUTLINE.map { vertex ->
            project(
                world = centre + target * vertex.along + targetRight * vertex.across,
                basis = basis,
            )
        }
        val upperRaw = ARROW_OUTLINE.map { vertex ->
            project(
                world = centre +
                    target * vertex.along +
                    targetRight * vertex.across +
                    WORLD_UP * ARROW_THICKNESS,
                basis = basis,
            )
        }

        // Preserve the perspective aspect ratio but keep every orientation inside the compact HUD
        // viewport. A common scale for both faces retains the extrusion and foreshortening.
        val all = lowerRaw + upperRaw
        val centreX = all.sumOf { it.x } / all.size
        val centreY = all.sumOf { it.y } / all.size
        val extent = all.maxOf { max(abs(it.x - centreX), abs(it.y - centreY)) }
            .coerceAtLeast(MIN_NORMALIZATION_EXTENT)
        val scale = ARROW_VIEWPORT_EXTENT / extent
        fun normalizePoint(point: ProjectedArrowPoint) = ProjectedArrowPoint(
            x = (point.x - centreX) * scale,
            y = (point.y - centreY) * scale,
        )

        val yawError = cameraFrameHeading(basis)?.let { cameraHeading ->
            shortestDelta(cameraHeading, targetHeadingDegrees)
        }
        return NavigationArrowMesh(
            lowerFace = lowerRaw.map(::normalizePoint),
            upperFace = upperRaw.map(::normalizePoint),
            yawErrorDegrees = yawError,
        )
    }

    /**
     * Correct only magnetic yaw while preserving the live pitch and roll of every camera axis.
     *
     * The rotation-vector matrix is already an orthonormal device-to-world transform. Applying
     * one common rotation around world-up preserves that orthonormal basis and changes no up
     * component, so a stationary heading lock cannot flatten or otherwise corrupt tilt.
     */
    fun alignBasisHeading(basis: CameraBasis, stableHeadingDegrees: Double): CameraBasis {
        val rawHeading = cameraFrameHeading(basis) ?: return basis
        val correction = shortestDelta(rawHeading, stableHeadingDegrees) * PI / 180.0
        val cosine = kotlin.math.cos(correction)
        val sine = kotlin.math.sin(correction)
        fun rotate(east: Double, north: Double): Pair<Double, Double> =
            (east * cosine + north * sine) to (-east * sine + north * cosine)

        val right = rotate(basis.rightEast, basis.rightNorth)
        val screenUp = rotate(basis.screenUpEast, basis.screenUpNorth)
        val forward = rotate(basis.forwardEast, basis.forwardNorth)
        return CameraBasis(
            rightEast = right.first,
            rightNorth = right.second,
            rightUp = basis.rightUp,
            screenUpEast = screenUp.first,
            screenUpNorth = screenUp.second,
            screenUpUp = basis.screenUpUp,
            forwardEast = forward.first,
            forwardNorth = forward.second,
            forwardUp = basis.forwardUp,
        )
    }

    /**
     * Absolute yaw rate around gravity, in radians/second.
     *
     * Pitch and roll must reshape the perspective arrow without unlocking compass yaw. World-up
     * gyro velocity remains valid even while the optical axis is vertical, where differentiating
     * optical azimuth is singular and amplifies tiny magnetometer errors into large turns.
     */
    fun headingAngularSpeed(angularVelocityUp: Double): Double? =
        angularVelocityUp.takeIf(Double::isFinite)?.let(::abs)

    /** Map a normalized horizontal image coordinate onto the camera's optical field of view. */
    fun targetFromImageRay(
        cameraHeadingDegrees: Double,
        normalizedX: Double,
        horizontalFovDegrees: Double,
    ): Double {
        val x = normalizedX.coerceIn(0.0, 1.0)
        val halfFovRadians = horizontalFovDegrees.coerceIn(1.0, 179.0) * PI / 360.0
        val rayOffset = atan((2.0 * x - 1.0) * tan(halfFovRadians))
        return normalize(cameraHeadingDegrees + rayOffset * 180.0 / PI)
    }

    private fun project(world: Vector3, basis: CameraBasis): ProjectedArrowPoint {
        val cameraX = world.east * basis.rightEast +
            world.north * basis.rightNorth +
            world.up * basis.rightUp
        val cameraY = world.east * basis.screenUpEast +
            world.north * basis.screenUpNorth +
            world.up * basis.screenUpUp
        val cameraZ = (world.east * basis.forwardEast +
            world.north * basis.forwardNorth +
            world.up * basis.forwardUp).coerceAtLeast(MIN_PROJECTION_DEPTH)
        return ProjectedArrowPoint(
            x = cameraX / cameraZ,
            // Canvas Y grows down while the camera's screen-up axis grows up.
            y = -cameraY / cameraZ,
        )
    }

    private data class ArrowVertex(
        val along: Double,
        val across: Double,
    )

    private data class Vector3(
        val east: Double,
        val north: Double,
        val up: Double,
    ) {
        operator fun plus(other: Vector3) = Vector3(
            east + other.east,
            north + other.north,
            up + other.up,
        )

        operator fun minus(other: Vector3) = Vector3(
            east - other.east,
            north - other.north,
            up - other.up,
        )

        operator fun times(value: Double) = Vector3(east * value, north * value, up * value)
    }

    private val WORLD_UP = Vector3(0.0, 0.0, 1.0)

    // Standard concave navigation dart, clockwise as viewed from above. The centre rear vertex
    // points into the body to form the familiar tail notch; perspective and the shallow second
    // face give the complete silhouette depth.
    private val ARROW_OUTLINE = listOf(
        ArrowVertex(along = 0.95, across = 0.0),
        ArrowVertex(along = -0.78, across = 0.52),
        ArrowVertex(along = -0.26, across = 0.0),
        ArrowVertex(along = -0.78, across = -0.52),
    )

    private const val ARROW_DISTANCE = 5.0
    private const val ARROW_PLANE_DROP = 2.4
    private const val ARROW_THICKNESS = 0.16
    private const val ARROW_VIEWPORT_EXTENT = 0.92
    private const val MIN_PROJECTION_DEPTH = 0.25
    private const val MIN_NORMALIZATION_EXTENT = 1e-6
    private const val MIN_HEADING_PROJECTION = 0.12
    private const val MIN_FRAME_HEADING_PROJECTION = 1e-6
}
