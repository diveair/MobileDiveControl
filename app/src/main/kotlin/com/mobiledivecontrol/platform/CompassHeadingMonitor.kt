package com.mobiledivecontrol.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import com.mobiledivecontrol.core.CameraBasis
import com.mobiledivecontrol.core.HeadingMath
import com.mobiledivecontrol.core.HeadingStabilizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

enum class CompassAccuracy {
    Unavailable,
    Unreliable,
    Low,
    Medium,
    High,
}

data class CompassReading(
    val headingDegrees: Double? = null,
    val accuracy: CompassAccuracy = CompassAccuracy.Unavailable,
    val cameraBasis: CameraBasis? = null,
)

/**
 * Continuous magnetic yaw of the back-camera frame.
 *
 * Gravity supplies tilt and the calibrated magnetic-field sensor supplies absolute yaw. This
 * intentionally avoids vendor rotation-vector fusion: the connected SM-S921W reports no heading
 * accuracy for that sensor and its fused yaw diverges from its own calibrated magnetometer.
 * The heading follows the optical axis while horizontal, then transitions to image-top at a
 * floor-facing view instead of becoming undefined. Compose receives at most 10 Hz.
 */
class CompassHeadingMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravityVector = FloatArray(3)
    private val magneticVector = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val screenRotationMatrix = FloatArray(9)
    private val display = context.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)
    private val _reading = MutableStateFlow(
        CompassReading(
            accuracy = if (gravitySensor == null || magneticSensor == null) {
                CompassAccuracy.Unavailable
            } else {
                CompassAccuracy.Unreliable
            },
        ),
    )
    val reading: StateFlow<CompassReading> = _reading.asStateFlow()

    private val headingStabilizer = HeadingStabilizer()
    private var registered = false
    private var gravityReady = false
    private var magneticReady = false
    private var lastGravityAtMs = Long.MIN_VALUE
    private var lastMagneticAtMs = Long.MIN_VALUE
    private var magneticFilterStartedAtMs = Long.MIN_VALUE
    private var lastPublishedAtMs = 0L
    private var lastGyroAtMs = Long.MIN_VALUE
    private var lastGyroX = 0.0
    private var lastGyroY = 0.0
    private var lastGyroZ = 0.0
    private var lastRawHeading: Double? = null
    private var lastRawHeadingAtMs: Long? = null
    private var accuracy = CompassAccuracy.Unreliable

    fun start() {
        if (registered) return
        val gravity = gravitySensor ?: return
        val magnetic = magneticSensor ?: return
        headingStabilizer.reset()
        gravityReady = false
        magneticReady = false
        lastGravityAtMs = Long.MIN_VALUE
        lastMagneticAtMs = Long.MIN_VALUE
        magneticFilterStartedAtMs = Long.MIN_VALUE
        lastGyroAtMs = Long.MIN_VALUE
        lastGyroX = 0.0
        lastGyroY = 0.0
        lastGyroZ = 0.0
        lastRawHeading = null
        lastRawHeadingAtMs = null
        val gravityRegistered = sensorManager.registerListener(
            this,
            gravity,
            SensorManager.SENSOR_DELAY_GAME,
        )
        val magneticRegistered = sensorManager.registerListener(
            this,
            magnetic,
            MAGNETIC_SAMPLING_PERIOD_US,
        )
        registered = gravityRegistered && magneticRegistered
        if (registered) {
            gyroscope?.let { gyro ->
                sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            sensorManager.unregisterListener(this)
            _reading.value = CompassReading(accuracy = CompassAccuracy.Unavailable)
        }
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            lastGyroX = event.values.getOrElse(0) { 0f }.toDouble()
            lastGyroY = event.values.getOrElse(1) { 0f }.toDouble()
            lastGyroZ = event.values.getOrElse(2) { 0f }.toDouble()
            lastGyroAtMs = now
            return
        }
        when (event.sensor) {
            gravitySensor -> {
                event.values.copyInto(gravityVector, endIndex = minOf(3, event.values.size))
                gravityReady = event.values.size >= 3
                lastGravityAtMs = now
            }
            magneticSensor -> {
                if (event.values.size >= 3) {
                    if (!magneticReady) {
                        event.values.copyInto(magneticVector, endIndex = 3)
                        magneticFilterStartedAtMs = now
                        magneticReady = true
                    } else {
                        // Camera electronics produce high-frequency magnetic spikes on this phone.
                        // A time-constant filter averages those samples without making behaviour
                        // depend on the vendor's actual callback rate.
                        val elapsedMs = (now - lastMagneticAtMs).coerceIn(1L, 250L)
                        val alpha = 1.0 - exp(-elapsedMs / MAGNETIC_FILTER_TIME_CONSTANT_MS)
                        repeat(3) { index ->
                            magneticVector[index] = (
                                magneticVector[index] +
                                    alpha * (event.values[index] - magneticVector[index])
                                ).toFloat()
                        }
                    }
                }
                lastMagneticAtMs = now
            }
            else -> return
        }

        if (!gravityReady || !magneticReady ||
            now - lastGravityAtMs > ABSOLUTE_SENSOR_STALE_AFTER_MS ||
            now - lastMagneticAtMs > ABSOLUTE_SENSOR_STALE_AFTER_MS ||
            now - magneticFilterStartedAtMs < MAGNETIC_WARMUP_MS
        ) return

        if (!SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                gravityVector,
                magneticVector,
            )
        ) return
        val (screenX, screenY) = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            screenX,
            screenY,
            screenRotationMatrix,
        )
        val cameraBasis = HeadingMath.backCameraBasis(screenRotationMatrix)
        val raw = cameraBasis?.let(HeadingMath::cameraFrameHeading)
        if (raw == null) {
            if (now - lastPublishedAtMs >= PUBLISH_INTERVAL_MS) {
                _reading.value = CompassReading(null, accuracy, cameraBasis)
                lastPublishedAtMs = now
            }
            return
        }

        // Gyro motion distinguishes an intentional turn from magnetic wander. If a device lacks
        // a gyro (or its event is momentarily stale), derive a conservative angular speed from
        // successive absolute headings so the compass still remains usable.
        val fallbackSpeed = lastRawHeading?.let { previousRaw ->
            val elapsed = (now - (lastRawHeadingAtMs ?: now)).coerceAtLeast(1L) / 1_000.0
            abs(HeadingMath.shortestDelta(previousRaw, raw)) * PI / 180.0 / elapsed
        } ?: 0.0
        val gyroHeadingSpeed = if (
            gyroscope != null &&
            now - lastGyroAtMs <= GYRO_STALE_AFTER_MS
        ) {
            // Gyroscope axes are in the device's natural frame. The un-remapped rotation matrix
            // converts that physical angular-velocity vector to east/north/up world coordinates.
            val worldUp = rotationMatrix[6] * lastGyroX +
                rotationMatrix[7] * lastGyroY +
                rotationMatrix[8] * lastGyroZ
            HeadingMath.headingAngularSpeed(worldUp)
        } else {
            null
        }
        val angularSpeed = gyroHeadingSpeed ?: fallbackSpeed
        lastRawHeading = raw
        lastRawHeadingAtMs = now

        val stableHeading = headingStabilizer.update(raw, angularSpeed, now)
        // Correct yaw in the basis as well as the number. Otherwise a steady "327°" could sit
        // above an arrow still moving on sub-degree raw magnetometer changes. World-up rotation
        // preserves the rotation matrix's pitch, roll and orthonormality.
        val stableBasis = HeadingMath.alignBasisHeading(cameraBasis, stableHeading)
        if (now - lastPublishedAtMs >= PUBLISH_INTERVAL_MS) {
            _reading.value = CompassReading(stableHeading, accuracy, stableBasis)
            lastPublishedAtMs = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, value: Int) {
        if (sensor != magneticSensor) return
        accuracy = when (value) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracy.High
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracy.Medium
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassAccuracy.Low
            else -> CompassAccuracy.Unreliable
        }
        _reading.value = _reading.value.copy(accuracy = accuracy)
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 100L
        const val GYRO_STALE_AFTER_MS = 120L
        const val ABSOLUTE_SENSOR_STALE_AFTER_MS = 500L
        const val MAGNETIC_SAMPLING_PERIOD_US = 20_000
        const val MAGNETIC_FILTER_TIME_CONSTANT_MS = 250.0
        const val MAGNETIC_WARMUP_MS = 1_000L
    }
}
