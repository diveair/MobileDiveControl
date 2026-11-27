package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import java.io.Serializable

internal data class UhdCaptureProfile(
    val cameraId: String,
    val physicalId: String?,
    val lens: String,
    val zoom: Float,
    val fps: Int,
    val constrained: Boolean,
    val codec: TimeLapseVideoCodec,
    /** Single full-size camera texture feeding the encoder and viewfinder through EGL. */
    val relay: Boolean = false,
    val expectedSensorId: String? = physicalId,
    val samsungSlowMotion: Boolean = false,
) : Serializable {
    internal fun certificateKey(fingerprint: String, audio: Boolean): String {
        val prefix = if (fps >= 100) listOf("v4", relay, expectedSensorId, if (audio) "audio" else "silent") else listOf("v1")
        val key = (prefix + listOf(fingerprint, cameraId, physicalId, lens, zoom, fps, constrained, codec)).joinToString("|")
        // The main-lens viewfinder changed from 1080p to 720p. Its old qualification
        // applied to a different output configuration. The ultrawide was already 720p.
        return when {
            !samsungSlowMotion -> key
            lens == "1x" -> "$key|samsung-slow-motion-2"
            else -> "$key|samsung-slow-motion-1"
        }
    }

    internal fun hasCertificate(fingerprint: String, audio: Boolean, exists: (String) -> Boolean): Boolean =
        exists(certificateKey(fingerprint, audio)) || (fps >= 100 && !audio && exists(certificateKey(fingerprint, true)))

    // Samsung discovery creates this route only for the two supported optical lenses.
    // A measured route is selectable even before the optional moving-subject test.
    internal fun selectableOn(fingerprint: String, qualified: Boolean): Boolean =
        qualified || (samsungSlowMotion && fingerprint == SamsungUhdSlowMotion.MEASURED_FIRMWARE)

    fun verified(context: Context, audio: Boolean = true): Boolean {
        val certificates = prefs(context)
        return hasCertificate(Build.FINGERPRINT, audio) { certificates.getBoolean(it, false) }
    }

    fun markVerified(context: Context, audio: Boolean) =
        prefs(context).edit().putBoolean(certificateKey(Build.FINGERPRINT, audio), true).apply()

    // Regular HFR can rely on FPS session parameters and a lower-bandwidth preview.
    // Keep the output combination that existing 60 FPS certificates measured.
    internal val regularHfrSession get() = fps >= 100 && !constrained

    fun config(audio: Boolean) = PreparedCamera2Recorder.Config(cameraId = cameraId, physicalCameraId = physicalId,
        size = size, highSpeedFps = fps, playbackFps = fps, codec = codec, recordAudio = audio, uhdProfile = this,
        regularUhd = regularHfrSession, slowMotion = samsungSlowMotion)

    companion object {
        val size by lazy { Size(3840, 2160) }
        private fun prefs(context: Context) = context.getSharedPreferences("verified_uhd_capture", Context.MODE_PRIVATE)

        fun discover(context: Context, cameraId: String, physicalId: String?, lens: String, zoom: Float,
                     expectedSensorId: String? = physicalId): List<UhdCaptureProfile> {
            // API 29 supplies both hardware encoder identity and thermal status.
            if (Build.VERSION.SDK_INT < 29) return emptyList()
            val manager = context.getSystemService(CameraManager::class.java)
            val camera = manager.getCameraCharacteristics(cameraId)
            val sensor = physicalId?.let(manager::getCameraCharacteristics) ?: camera
            val map = sensor.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            val regularRanges = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
            val uhd = size
            val duration = if (uhd in sizes) map.getOutputMinFrameDuration(MediaRecorder::class.java, uhd) else 0L
            val highRanges = if (physicalId == null) runCatching {
                if (uhd in map.highSpeedVideoSizes.orEmpty()) map.getHighSpeedVideoFpsRangesFor(uhd).toList() else emptyList()
            }.getOrDefault(emptyList()) else emptyList()
            val uhdTexture = highRanges.isNotEmpty() && runCatching {
                uhd in map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            }.getOrDefault(false)
            return buildList {
                for (fps in listOf(60, 100, 120)) {
                    val fixed = Range(fps, fps)
                    val regular = fixed in regularRanges && uhd in sizes &&
                        (duration <= 0 || 1e9 / duration >= fps * 0.98)
                    val high = fixed in highRanges
                    if (!regular && !high) continue
                    for ((mime, codec) in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC to TimeLapseVideoCodec.HEVC,
                        MediaFormat.MIMETYPE_VIDEO_AVC to TimeLapseVideoCodec.H264)) {
                        if (!hasHardwareEncoder(mime, fps)) continue
                        if (regular) add(UhdCaptureProfile(cameraId, physicalId, lens, zoom, fps, false, codec,
                            expectedSensorId = expectedSensorId))
                        if (high) {
                            val direct = UhdCaptureProfile(cameraId, null, lens, zoom, fps, true, codec,
                                expectedSensorId = expectedSensorId)
                            add(direct)
                            if (fps >= 100 && uhdTexture) add(direct.copy(relay = true))
                        }
                    }
                }
            }
        }

        private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }

        internal fun hasHardwareEncoder(mime: String, fps: Int): Boolean {
            val encoders = codecs.filter { info ->
                info.isEncoder && mime in info.supportedTypes && runCatching {
                    val caps = info.getCapabilitiesForType(mime)
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats &&
                        caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
                }.getOrDefault(false)
            }
            // MediaRecorder chooses an encoder by MIME.
            return encoders.isNotEmpty() && encoders.all { it.isHardwareAccelerated }
        }
    }
}
