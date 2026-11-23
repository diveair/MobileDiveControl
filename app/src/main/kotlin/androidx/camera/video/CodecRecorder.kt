package androidx.camera.video

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.camera.video.internal.encoder.EncoderImpl
import androidx.camera.video.internal.encoder.VideoEncoderConfig

/** Adapter to the pinned CameraX 1.5.3 encoder factory; no reflection. */
@SuppressLint("RestrictedApi")
object CodecRecorder {
    fun configure(builder: Recorder.Builder, hevc: Boolean) {
        builder.setVideoEncoderFactory { executor, config, usage ->
            val requested = (config as VideoEncoderConfig).toBuilder()
                .setMimeType(if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC)
                .setProfile(if (hevc) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                .build()
            EncoderImpl(executor, requested, usage)
        }
    }
}
