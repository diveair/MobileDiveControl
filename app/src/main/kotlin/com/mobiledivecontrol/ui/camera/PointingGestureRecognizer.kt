package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class PointingGesture(
    val normalizedX: Double,
    val horizontalFovDegrees: Double,
    val confidence: Float,
)

/**
 * CPU-only, on-device pointing detector for the existing CameraX analysis stream.
 *
 * VIDEO mode is intentional: like LIVE_STREAM it reuses hand tracking between frames, while its
 * synchronous call gives the reusable RGBA buffer an exact lifetime. CameraX drops stale analysis
 * frames if inference is busy, so the 12.5 fps ceiling adds responsiveness without a work queue.
 */
internal class PointingGestureRecognizer(
    private val context: Context,
    private val onPoint: (normalizedX: Double, confidence: Float) -> Unit,
) : AutoCloseable {
    private var recognizer: GestureRecognizer? = null
    private var lastAnalyzedAtMs = 0L
    private var packedRgbaBuffer: ByteBuffer? = null
    private val processingOptionsByRotation = mutableMapOf<Int, ImageProcessingOptions>()
    private var inferenceWarmupFrames = 0
    private var inferenceSamples = 0
    private var inferenceTotalMs = 0L
    private var inferenceMaxMs = 0L
    private var recognizerUsesGpu = false
    private var gpuDisabled = false
    private val tracker = PointingGestureTracker()

    fun analyze(image: ImageProxy, frontCamera: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAnalyzedAtMs < ANALYSIS_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalyzedAtMs = now
        val engine = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (engine == null) {
            image.close()
            return
        }

        val rotation = image.imageInfo.rotationDegrees
        try {
            val result = image.use { frame ->
                val source = rgbaBuffer(frame)
                ByteBufferImageBuilder(
                    source,
                    frame.width,
                    frame.height,
                    MPImage.IMAGE_FORMAT_RGBA,
                ).build().use { mpImage ->
                    engine.recognizeForVideo(
                        mpImage,
                        processingOptions(rotation),
                        now,
                    )
                }
            }
            recordInferenceLatency(SystemClock.uptimeMillis() - now)
            consume(result, now, mirrorHorizontally = frontCamera)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Pointing inference failed", error)
            if (recognizerUsesGpu) {
                // A device may accept GPU graph creation but reject an op on the first frame.
                // Disable it for this controller lifetime and recover on CPU on the next frame.
                recognizer?.close()
                recognizer = null
                recognizerUsesGpu = false
                gpuDisabled = true
            }
            consume(null, now, mirrorHorizontally = frontCamera)
        } finally {
            // ImageProxy.close is idempotent; image.use above normally closed it already.
            image.close()
        }
    }

    private fun createRecognizer(): GestureRecognizer? {
        if (!gpuDisabled) {
            createRecognizer(Delegate.GPU)?.let { recognizer ->
                recognizerUsesGpu = true
                return recognizer
            }
            gpuDisabled = true
        }
        return createRecognizer(Delegate.CPU)?.also { recognizerUsesGpu = false }
    }

    private fun createRecognizer(delegate: Delegate): GestureRecognizer? = runCatching {
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate)
                    .build(),
            )
            .setNumHands(1)
            // Geometry plus three-frame confirmation is the precision gate. Slightly lower
            // detector thresholds recover dim, colour-shifted and partly occluded dive hands.
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
            .setRunningMode(RunningMode.VIDEO)
            .build()
        GestureRecognizer.createFromOptions(context, options).also {
            Log.i(TAG, "On-device pointing recognizer ready (delegate=$delegate, latest-frame mode)")
        }
    }.onFailure { error ->
        Log.w(TAG, "Could not initialize pointing recognizer with $delegate", error)
    }.getOrNull()

    private fun consume(
        result: GestureRecognizerResult?,
        now: Long,
        mirrorHorizontally: Boolean,
    ) {
        val landmarks = result?.landmarks()?.firstOrNull()
        if (landmarks == null || landmarks.size < 21) {
            markMissing()
            return
        }

        val category = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
        val modelConfidence = category
            ?.takeIf { it.categoryName() == POINTING_CATEGORY && it.score() >= MODEL_CONFIDENCE }
            ?.score()
        val imagePoints = landmarks.map {
            val x = it.x().toDouble()
            GestureLandmark(
                x = if (mirrorHorizontally) 1.0 - x else x,
                y = it.y().toDouble(),
                z = it.z().toDouble(),
            )
        }
        val worldPoints = result.worldLandmarks().firstOrNull()?.map {
            GestureLandmark(it.x().toDouble(), it.y().toDouble(), it.z().toDouble())
        }.orEmpty()
        val estimate = estimatePointingGesture(
            imagePoints = imagePoints,
            worldPoints = worldPoints,
            modelConfidence = modelConfidence,
        )
        if (estimate == null) return markMissing()

        tracker.observe(estimate, now)?.let { stable ->
            Log.i(
                TAG,
                "Point accepted x=${"%.3f".format(stable.normalizedX)} " +
                    "confidence=${"%.2f".format(stable.confidence)} " +
                    "fast=${stable.fastPathEligible} " +
                    "frameToDecision=${SystemClock.uptimeMillis() - now}ms",
            )
            onPoint(stable.normalizedX, stable.confidence)
        }
    }

    private fun markMissing() {
        tracker.markMissing()
    }

    /** One post-warmup sample at startup gives an on-device performance fact without log spam. */
    private fun recordInferenceLatency(elapsedMs: Long) {
        if (inferenceWarmupFrames++ < INFERENCE_WARMUP_FRAMES) return
        if (inferenceSamples >= INFERENCE_BENCHMARK_FRAMES) return
        inferenceSamples++
        inferenceTotalMs += elapsedMs
        inferenceMaxMs = maxOf(inferenceMaxMs, elapsedMs)
        if (inferenceSamples == INFERENCE_BENCHMARK_FRAMES) {
            Log.i(
                TAG,
                "Pointing pipeline benchmark avg=${inferenceTotalMs / inferenceSamples}ms " +
                    "max=${inferenceMaxMs}ms over $inferenceSamples frames",
            )
        }
    }

    private fun processingOptions(rotationDegrees: Int): ImageProcessingOptions {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return processingOptionsByRotation.getOrPut(normalized) {
            ImageProcessingOptions.builder()
                .setRotationDegrees(normalized)
                .build()
        }
    }

    /** CameraX may pad RGBA rows; MediaPipe's byte-buffer image must remain tightly packed. */
    private fun rgbaBuffer(image: ImageProxy): ByteBuffer {
        val plane = image.planes.first()
        val packedRowBytes = image.width * 4
        val packedByteCount = packedRowBytes * image.height
        if (plane.pixelStride == 4 && plane.rowStride == packedRowBytes) {
            // The recognizer call is synchronous and runs inside image.use, so this is a safe
            // zero-copy view of CameraX's plane. MPImage.close does not own/recycle ByteBuffers.
            return plane.buffer.duplicate().apply {
                rewind()
                limit(packedByteCount)
            }.slice()
        }

        val packed = packedRgbaBuffer
            ?.takeIf { it.capacity() == packedByteCount }
            ?: ByteBuffer.allocateDirect(packedByteCount).also { packedRgbaBuffer = it }
        packed.clear()
        val source = plane.buffer.duplicate()
        if (plane.pixelStride == 4) {
            for (y in 0 until image.height) {
                val rowStart = y * plane.rowStride
                source.limit(source.capacity())
                source.position(rowStart)
                source.limit(rowStart + packedRowBytes)
                packed.put(source)
            }
        } else {
            for (y in 0 until image.height) {
                val rowStart = y * plane.rowStride
                for (x in 0 until image.width) {
                    val offset = rowStart + x * plane.pixelStride
                    packed.put(source.get(offset))
                    packed.put(source.get(offset + 1))
                    packed.put(source.get(offset + 2))
                    packed.put(source.get(offset + 3))
                }
            }
        }
        packed.flip()
        return packed
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
        packedRgbaBuffer = null
        processingOptionsByRotation.clear()
    }

    private companion object {
        const val TAG = "PointingGesture"
        const val MODEL_ASSET = "gesture_recognizer.task"
        const val POINTING_CATEGORY = "Pointing_Up"
        // MediaPipe is assistance, not the viewfinder. Cap it at 12.5 fps so its GPU/CPU work
        // cannot monopolise the analysis thread or contend with CameraX surface restoration.
        const val ANALYSIS_INTERVAL_MS = 80L
        const val MODEL_CONFIDENCE = 0.55f
        const val INFERENCE_WARMUP_FRAMES = 3
        const val INFERENCE_BENCHMARK_FRAMES = 30
    }
}

internal data class GestureLandmark(
    val x: Double,
    val y: Double,
    val z: Double = 0.0,
)

internal data class PointingPoseEstimate(
    val normalizedX: Double,
    val confidence: Float,
    /** Independent pose geometry is strong enough to accept this frame without temporal delay. */
    val fastPathEligible: Boolean = false,
)

/**
 * Scores a pointing pose from first principles.
 *
 * World landmarks make the bend tests independent of image aspect ratio, hand scale, roll and
 * perspective. Image landmarks are retained only for the image-plane aim. The canned
 * `Pointing_Up` score is corroborating evidence, never a substitute for the finger geometry.
 */
internal fun estimatePointingGesture(
    imagePoints: List<GestureLandmark>,
    worldPoints: List<GestureLandmark> = emptyList(),
    modelConfidence: Float? = null,
): PointingPoseEstimate? {
    if (imagePoints.size < HAND_LANDMARK_COUNT) return null
    val posePoints = worldPoints.takeIf { it.size >= HAND_LANDMARK_COUNT } ?: imagePoints
    if (posePoints.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return null

    val palmScale = maxOf(
        distance3(posePoints[WRIST], posePoints[MIDDLE_MCP]),
        distance3(posePoints[INDEX_MCP], posePoints[PINKY_MCP]),
    )
    if (palmScale < MIN_VECTOR_LENGTH) return null

    val indexLinearity = chainLinearity(posePoints, INDEX_MCP, INDEX_PIP, INDEX_DIP, INDEX_TIP)
    val indexPipStraightness = straightness(
        jointCosine(posePoints, INDEX_MCP, INDEX_PIP, INDEX_DIP),
    )
    val indexDipStraightness = straightness(
        jointCosine(posePoints, INDEX_PIP, INDEX_DIP, INDEX_TIP),
    )
    val indexExtension = ((distance3(posePoints[INDEX_MCP], posePoints[INDEX_TIP]) / palmScale - 0.45) / 0.35)
        .coerceIn(0.0, 1.0)
    if (
        indexLinearity < MIN_INDEX_LINEARITY ||
        indexPipStraightness < MIN_INDEX_JOINT_STRAIGHTNESS ||
        indexDipStraightness < MIN_INDEX_JOINT_STRAIGHTNESS ||
        indexExtension < MIN_INDEX_EXTENSION
    ) {
        return null
    }

    val otherFingers = listOf(
        FingerJoints(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_DIP, MIDDLE_TIP),
        FingerJoints(RING_MCP, RING_PIP, RING_DIP, RING_TIP),
        FingerJoints(PINKY_MCP, PINKY_PIP, PINKY_DIP, PINKY_TIP),
    )
    val otherExtended = otherFingers.count { finger ->
        chainLinearity(posePoints, finger.mcp, finger.pip, finger.dip, finger.tip) >= OTHER_FINGER_EXTENDED_LINEARITY &&
            straightness(jointCosine(posePoints, finger.mcp, finger.pip, finger.dip)) >= OTHER_FINGER_STRAIGHTNESS
    }
    if (otherExtended > 0) return null

    val foldScores = otherFingers.map { finger -> foldedScore(posePoints, finger) }
    if (foldScores.count { it >= MIN_FOLDED_FINGER_SCORE } < MIN_FOLDED_FINGERS) return null

    val indexQuality = (
        indexLinearity + indexPipStraightness + indexDipStraightness + indexExtension
        ) / 4.0
    val foldQuality = foldScores.sortedDescending().take(MIN_FOLDED_FINGERS).average()
    val geometryConfidence = (0.56 + 0.34 * indexQuality + 0.10 * foldQuality)
        .coerceIn(0.0, 0.96)
    val combinedConfidence = if (modelConfidence != null) {
        (geometryConfidence * 0.72 + modelConfidence.coerceIn(0f, 1f) * 0.28)
            .coerceIn(geometryConfidence, 0.99)
    } else {
        geometryConfidence
    }

    val pip = imagePoints[INDEX_PIP]
    val dip = imagePoints[INDEX_DIP]
    val tip = imagePoints[INDEX_TIP]
    val longDirection = unit2(tip.x - pip.x, tip.y - pip.y) ?: return null
    val tipDirection = unit2(tip.x - dip.x, tip.y - dip.y) ?: longDirection
    val direction = unit2(
        longDirection.first * 0.7 + tipDirection.first * 0.3,
        longDirection.second * 0.7 + tipDirection.second * 0.3,
    ) ?: longDirection
    val indexLength = distance2(imagePoints[INDEX_MCP], tip)
    val aimX = (tip.x + direction.first * indexLength * AIM_EXTENSION_INDEX_LENGTHS)
        .coerceIn(0.0, 1.0)

    val fastPathEligible = geometryConfidence >= FAST_PATH_GEOMETRY_CONFIDENCE ||
        (modelConfidence != null &&
            modelConfidence >= FAST_PATH_MODEL_CONFIDENCE &&
            geometryConfidence >= FAST_PATH_CORROBORATED_GEOMETRY_CONFIDENCE)
    return PointingPoseEstimate(
        normalizedX = aimX,
        confidence = combinedConfidence.toFloat(),
        fastPathEligible = fastPathEligible,
    )
}

/** Compatibility helper retained for focused geometry tests and call sites outside MediaPipe. */
internal fun isPointingHandLandmarks(points: List<Pair<Double, Double>>): Boolean =
    estimatePointingGesture(
        imagePoints = points.map { (x, y) -> GestureLandmark(x, y) },
    ) != null

internal class PointingGestureTracker(
    private val stableHoldMs: Long = STABLE_HOLD_MS,
    private val reaimHoldMs: Long = REAIM_HOLD_MS,
    private val stableFramesRequired: Int = STABLE_CONFIRMATION_FRAMES,
    private val reaimFramesRequired: Int = REAIM_CONFIRMATION_FRAMES,
) {
    private var candidateX = Double.NaN
    private var candidateConfidence = 0f
    private var candidateSinceMs = 0L
    private var candidateFrames = 0
    private var reaimCandidateX = Double.NaN
    private var reaimCandidateConfidence = 0f
    private var reaimCandidateSinceMs = 0L
    private var reaimCandidateFrames = 0
    private var missingFrames = 0
    private var gestureActive = false
    private var lastEmittedX = Double.NaN

    fun observe(estimate: PointingPoseEstimate, nowMs: Long): PointingPoseEstimate? {
        missingFrames = 0
        if (estimate.fastPathEligible) {
            if (gestureActive && abs(estimate.normalizedX - lastEmittedX) < REAIM_DISTANCE) {
                resetReaimCandidate()
                return null
            }
            gestureActive = true
            lastEmittedX = estimate.normalizedX
            resetInitialCandidate()
            resetReaimCandidate()
            return estimate
        }
        return if (gestureActive) observeReaim(estimate, nowMs) else observeInitial(estimate, nowMs)
    }

    private fun observeInitial(estimate: PointingPoseEstimate, nowMs: Long): PointingPoseEstimate? {
        if (candidateX.isNaN() || abs(estimate.normalizedX - candidateX) > CANDIDATE_TOLERANCE) {
            candidateX = estimate.normalizedX
            candidateConfidence = estimate.confidence
            candidateSinceMs = nowMs
            candidateFrames = 1
            return null
        }
        val smoothed = smooth(candidateX, candidateConfidence, estimate)
        candidateX = smoothed.normalizedX
        candidateConfidence = smoothed.confidence
        candidateFrames++
        if (candidateFrames < stableFramesRequired || nowMs - candidateSinceMs < stableHoldMs) return null

        gestureActive = true
        lastEmittedX = candidateX
        resetReaimCandidate()
        return PointingPoseEstimate(candidateX, candidateConfidence)
    }

    private fun observeReaim(estimate: PointingPoseEstimate, nowMs: Long): PointingPoseEstimate? {
        if (abs(estimate.normalizedX - lastEmittedX) < REAIM_DISTANCE) {
            resetReaimCandidate()
            return null
        }
        if (
            reaimCandidateX.isNaN() ||
            abs(estimate.normalizedX - reaimCandidateX) > CANDIDATE_TOLERANCE
        ) {
            reaimCandidateX = estimate.normalizedX
            reaimCandidateConfidence = estimate.confidence
            reaimCandidateSinceMs = nowMs
            reaimCandidateFrames = 1
            return null
        }
        val smoothed = smooth(reaimCandidateX, reaimCandidateConfidence, estimate)
        reaimCandidateX = smoothed.normalizedX
        reaimCandidateConfidence = smoothed.confidence
        reaimCandidateFrames++
        if (
            reaimCandidateFrames < reaimFramesRequired ||
            nowMs - reaimCandidateSinceMs < reaimHoldMs
        ) return null

        val emitted = PointingPoseEstimate(reaimCandidateX, reaimCandidateConfidence)
        lastEmittedX = emitted.normalizedX
        resetReaimCandidate()
        return emitted
    }

    private fun smooth(
        currentX: Double,
        currentConfidence: Float,
        estimate: PointingPoseEstimate,
    ): PointingPoseEstimate {
        val weight = (0.25 + estimate.confidence * 0.20).coerceIn(0.25, 0.45)
        return PointingPoseEstimate(
            normalizedX = currentX * (1.0 - weight) + estimate.normalizedX * weight,
            confidence = currentConfidence * 0.65f + estimate.confidence * 0.35f,
        )
    }

    private fun resetReaimCandidate() {
        reaimCandidateX = Double.NaN
        reaimCandidateConfidence = 0f
        reaimCandidateSinceMs = 0L
        reaimCandidateFrames = 0
    }

    private fun resetInitialCandidate() {
        candidateX = Double.NaN
        candidateConfidence = 0f
        candidateSinceMs = 0L
        candidateFrames = 0
    }

    /** One or two dropped detections do not force the diver to begin the hold again. */
    fun markMissing() {
        missingFrames++
        if (missingFrames < RESET_AFTER_MISSING_FRAMES) return
        resetInitialCandidate()
        resetReaimCandidate()
        gestureActive = false
    }
}

private data class FingerJoints(
    val mcp: Int,
    val pip: Int,
    val dip: Int,
    val tip: Int,
)

private fun foldedScore(points: List<GestureLandmark>, finger: FingerJoints): Double {
    val linearity = chainLinearity(points, finger.mcp, finger.pip, finger.dip, finger.tip)
    val compactness = ((0.90 - linearity) / 0.42).coerceIn(0.0, 1.0)
    val pipBend = 1.0 - straightness(jointCosine(points, finger.mcp, finger.pip, finger.dip))
    val dipBend = 1.0 - straightness(jointCosine(points, finger.pip, finger.dip, finger.tip))
    return (compactness * 0.55 + maxOf(pipBend, dipBend) * 0.45).coerceIn(0.0, 1.0)
}

private fun chainLinearity(
    points: List<GestureLandmark>,
    mcp: Int,
    pip: Int,
    dip: Int,
    tip: Int,
): Double {
    val pathLength = distance3(points[mcp], points[pip]) +
        distance3(points[pip], points[dip]) +
        distance3(points[dip], points[tip])
    if (pathLength < MIN_VECTOR_LENGTH) return 0.0
    return (distance3(points[mcp], points[tip]) / pathLength).coerceIn(0.0, 1.0)
}

private fun straightness(cosine: Double): Double =
    ((-cosine - 0.55) / 0.45).coerceIn(0.0, 1.0)

private fun jointCosine(
    points: List<GestureLandmark>,
    a: Int,
    joint: Int,
    b: Int,
): Double {
    val ax = points[a].x - points[joint].x
    val ay = points[a].y - points[joint].y
    val az = points[a].z - points[joint].z
    val bx = points[b].x - points[joint].x
    val by = points[b].y - points[joint].y
    val bz = points[b].z - points[joint].z
    val denominator = sqrt(ax * ax + ay * ay + az * az) * sqrt(bx * bx + by * by + bz * bz)
    if (denominator < MIN_VECTOR_LENGTH) return 1.0
    return ((ax * bx + ay * by + az * bz) / denominator).coerceIn(-1.0, 1.0)
}

private fun distance3(a: GestureLandmark, b: GestureLandmark): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun distance2(a: GestureLandmark, b: GestureLandmark): Double =
    hypot(a.x - b.x, a.y - b.y)

private fun unit2(x: Double, y: Double): Pair<Double, Double>? {
    val length = hypot(x, y)
    return if (length < MIN_VECTOR_LENGTH) null else (x / length) to (y / length)
}

private const val HAND_LANDMARK_COUNT = 21
private const val WRIST = 0
private const val INDEX_MCP = 5
private const val INDEX_PIP = 6
private const val INDEX_DIP = 7
private const val INDEX_TIP = 8
private const val MIDDLE_MCP = 9
private const val MIDDLE_PIP = 10
private const val MIDDLE_DIP = 11
private const val MIDDLE_TIP = 12
private const val RING_MCP = 13
private const val RING_PIP = 14
private const val RING_DIP = 15
private const val RING_TIP = 16
private const val PINKY_MCP = 17
private const val PINKY_PIP = 18
private const val PINKY_DIP = 19
private const val PINKY_TIP = 20
private const val MIN_VECTOR_LENGTH = 1e-6
private const val MIN_INDEX_LINEARITY = 0.84
private const val MIN_INDEX_JOINT_STRAIGHTNESS = 0.48
private const val MIN_INDEX_EXTENSION = 0.42
private const val OTHER_FINGER_EXTENDED_LINEARITY = 0.86
private const val OTHER_FINGER_STRAIGHTNESS = 0.55
private const val MIN_FOLDED_FINGER_SCORE = 0.38
private const val MIN_FOLDED_FINGERS = 2
private const val AIM_EXTENSION_INDEX_LENGTHS = 0.45
private const val FAST_PATH_GEOMETRY_CONFIDENCE = 0.88
private const val FAST_PATH_MODEL_CONFIDENCE = 0.78f
private const val FAST_PATH_CORROBORATED_GEOMETRY_CONFIDENCE = 0.82
private const val STABLE_HOLD_MS = 120L
private const val REAIM_HOLD_MS = 120L
private const val STABLE_CONFIRMATION_FRAMES = 3
private const val REAIM_CONFIRMATION_FRAMES = 3
private const val RESET_AFTER_MISSING_FRAMES = 4
private const val CANDIDATE_TOLERANCE = 0.055
private const val REAIM_DISTANCE = 0.035
