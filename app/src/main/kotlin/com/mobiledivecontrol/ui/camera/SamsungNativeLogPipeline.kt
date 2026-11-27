package com.mobiledivecontrol.ui.camera

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import android.view.Surface
import java.io.File

/** Firmware-specific native LOG path qualified against Samsung Pro Video on the connected S24. */
internal object SamsungNativeLogPipeline {
    const val BITRATE = 100_000_000
    const val READY_TIMEOUT_MS = 8_000L
    private val nativeHooksAvailable by lazy {
        runCatching {
            check(SamsungCameraParameters.available)
            MediaRecorder::class.java.getMethod("semSetAuthor", Int::class.javaPrimitiveType)
            MediaRecorder::class.java.getMethod("semSetRecordingMode", Int::class.javaPrimitiveType)
            true
        }.getOrDefault(false)
    }
    private val requestMode by lazy { CaptureRequest.Key("samsung.android.control.recordingDrMode", Int::class.javaObjectType) }
    private val shootingMode by lazy { CaptureRequest.Key("samsung.android.control.shootingMode", Int::class.javaObjectType) }
    private val stabilization by lazy { CaptureRequest.Key("samsung.android.control.swVideoStabilization", Byte::class.javaObjectType) }
    private val superStabilization by lazy { CaptureRequest.Key("samsung.android.control.swSuperVideoStabilization", Byte::class.javaObjectType) }
    private val recordingMinFps by lazy { CaptureRequest.Key("samsung.android.control.recordingMinFps", Int::class.javaObjectType) }
    private val recordingMaxFps by lazy { CaptureRequest.Key("samsung.android.control.recordingMaxFps", Int::class.javaObjectType) }
    private val streamType by lazy { CaptureRequest.Key("samsung.android.sensor.streamType", Int::class.javaObjectType) }
    private val recordingTrigger by lazy { CaptureRequest.Key("samsung.android.control.recordingTrigger", Int::class.javaObjectType) }
    private val colorTemperature by lazy { CaptureRequest.Key("samsung.android.control.colorTemperature", Int::class.javaObjectType) }
    private val resultMode by lazy { CaptureResult.Key("samsung.android.control.recordingDrMode", Int::class.javaObjectType) }

    fun isSupportedDevice(): Boolean = Build.MANUFACTURER.equals("samsung", true) &&
        Build.VERSION.SDK_INT >= 33 && nativeHooksAvailable

    fun requireSupported(cameraId: String, size: Size, fps: Int) {
        check(isSupportedDevice()) { "Native Samsung LOG hooks are unavailable on this phone." }
        check(cameraId == "0") { "Native Samsung LOG currently supports the rear main camera only." }
        check(fps == 30 && size in listOf(Size(1920, 1080), Size(3840, 2160))) {
            "Native Samsung LOG requires 1080p or 4K at 30 fps."
        }
    }

    fun configureCamera(camera: CameraDevice, stabilized: Boolean) {
        try {
            SamsungCameraParameters.set(camera, "shootingmode=35;operation_mode=none;recording_dr_mode=log10;sw-vdis=$stabilized;sw-super_vdis=false;recording-fps=30;stream_type=0;")
        } catch (error: Exception) {
            throw IllegalStateException("Samsung native LOG camera configuration is unavailable.", error)
        }
    }

    fun initializeRecorder(recorder: MediaRecorder) {
        try {
            MediaRecorder::class.java.getMethod("semSetAuthor", Int::class.javaPrimitiveType)
                .invoke(recorder, 0)
            MediaRecorder::class.java.getMethod("semSetRecordingMode", Int::class.javaPrimitiveType)
                .invoke(recorder, 27)
        } catch (error: Exception) {
            throw IllegalStateException("Samsung native LOG recorder initialization is unavailable.", error)
        }
    }

    fun configureRecorder(recorder: MediaRecorder) {
        recorder.setVideoEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            0)
    }

    fun output(surface: Surface, recording: Boolean): OutputConfiguration = OutputConfiguration(surface).apply {
        // Samsung's semCreateOutputConfiguration packs surface option1 into streamUseCase<<16.
        setDynamicRangeProfile(1L)
        if (recording) setStreamUseCase(65536L)
    }

    fun configureRequest(builder: CaptureRequest.Builder, stabilized: Boolean) {
        builder.set(requestMode, 5)
        builder.set(shootingMode, 35)
        builder.set(stabilization, if (stabilized) 1.toByte() else 0.toByte())
        builder.set(superStabilization, 0.toByte())
        builder.set(recordingMinFps, 30)
        builder.set(recordingMaxFps, 30)
        builder.set(streamType, 0)
        // Native Pro Video: FAST/FAST, OIS and Samsung VDIS on. Public camera 0 additionally
        // needs public EIS ON; private camera 56 uses public OFF. The matched-scene test
        // confirms this activates the same framing without a synthetic zoom multiplier.
        // The legacy HLG MINIMAL/OFF policy visibly increased noise in the native scene test.
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (stabilized)
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    }
    fun captureMode(result: TotalCaptureResult): Int? = result.get(resultMode)
    fun configureWhiteBalance(builder: CaptureRequest.Builder, kelvin: Int?) {
        builder.set(CaptureRequest.CONTROL_AWB_MODE, if (kelvin == null) CaptureRequest.CONTROL_AWB_MODE_AUTO else 101)
        if (kelvin != null) builder.set(colorTemperature, kelvin.coerceIn(2300, 10000))
    }
    fun setRecordingStopTrigger(builder: CaptureRequest.Builder, stop: Boolean) {
        builder.set(recordingTrigger, if (stop) 1 else 0)
    }
    fun isNativeResult(result: TotalCaptureResult): Boolean = captureMode(result) == 6

    /** Validate the encoded contract and return the actual video duration, excluding encoder startup. */
    fun validateOutput(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("Native LOG recording contains no video track.")
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                "Native LOG recording is not HEVC."
            }
            check(format.containsKey(MediaFormat.KEY_COLOR_STANDARD) &&
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT2020) {
                "Native LOG output verification failed: BT.2020 is missing."
            }
            val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null
            val range = if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) format.getInteger(MediaFormat.KEY_COLOR_RANGE) else null
            requireNativeColorMetadata(transfer, range)
            val csd = checkNotNull(format.getByteBuffer("csd-0")) { "Native LOG output has no HEVC sequence header." }
            val bytes = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
            requireMain10Sequence(bytes)
            val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
            check(durationMs > 0) { "Native LOG recording has no video duration." }
            return durationMs
        } finally { extractor.release() }
    }

    internal fun requireNativeColorMetadata(transfer: Int?, range: Int?) {
        // On this firmware, native VUI transfer_characteristics=2 maps to Android transfer=0.
        // The previous BT.2020_10 mismatch mapped to SDR_VIDEO=3 and must no longer pass.
        check(transfer == 0 && range == MediaFormat.COLOR_RANGE_LIMITED) {
            "Native LOG output verification failed: transfer=$transfer, range=$range; expected native unspecified transfer and limited range."
        }
    }

    internal fun requireMain10Sequence(bytes: ByteArray) {
        val sps = parseSps(bytes)
        check(sps.profile == 2 && sps.lumaBits == 10 && sps.chromaBits == 10) {
            "Native LOG requires Main10 with 10-bit samples; got $sps."
        }
    }

    private data class Sps(val profile: Int, val lumaBits: Int, val chromaBits: Int)
    private fun parseSps(bytes: ByteArray): Sps {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < bytes.size) {
            val prefix = when {
                bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
                bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) { starts += i to (i + prefix); i += prefix } else i++
        }
        val index = starts.indexOfFirst { (_, start) -> start < bytes.size && ((bytes[start].toInt() ushr 1) and 63) == 33 }
        check(index >= 0) { "Native LOG output lacks an Annex-B HEVC SPS." }
        val start = starts[index].second + 2
        val end = starts.getOrNull(index + 1)?.first ?: bytes.size
        val payload = ArrayList<Byte>()
        var zeros = 0
        for (p in start until end) {
            val value = bytes[p].toInt() and 255
            if (zeros >= 2 && value == 3) { zeros = 0; continue }
            payload += bytes[p]
            zeros = if (value == 0) zeros + 1 else 0
        }
        val bits = Bits(payload.toByteArray())
        bits.skip(4)
        val subLayers = bits.read(3)
        bits.skip(1)
        bits.skip(3)
        val profile = bits.read(5)
        bits.skip(88) // compatibility, constraints and general_level_idc
        val profileFlags = BooleanArray(subLayers)
        val levelFlags = BooleanArray(subLayers)
        for (layer in 0 until subLayers) { profileFlags[layer] = bits.read(1) == 1; levelFlags[layer] = bits.read(1) == 1 }
        if (subLayers > 0) bits.skip((8 - subLayers) * 2)
        for (layer in 0 until subLayers) {
            if (profileFlags[layer]) bits.skip(88)
            if (levelFlags[layer]) bits.skip(8)
        }
        bits.ue()
        if (bits.ue() == 3) bits.skip(1)
        bits.ue(); bits.ue()
        if (bits.read(1) == 1) repeat(4) { bits.ue() }
        return Sps(profile, bits.ue() + 8, bits.ue() + 8)
    }

    private class Bits(private val data: ByteArray) {
        private var position = 0
        fun read(count: Int): Int {
            check(position + count <= data.size * 8) { "Truncated HEVC sequence header." }
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((data[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
        fun skip(count: Int) { check(position + count <= data.size * 8); position += count }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { zeros++; check(zeros < 31) { "Invalid HEVC sequence header." } }
            return (1 shl zeros) - 1 + read(zeros)
        }
    }
}
