package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * Small MediaPlayer-backed TextureView used by both recording review and the gallery.
 * TextureView is intentional: unlike VideoView's SurfaceView it composes correctly above the
 * CameraX preview and below the housing action chips.
 */
internal class LoopingVideoTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var source: Uri? = null
    private var player: MediaPlayer? = null
    private var shouldPlay = true
    private var videoWidth = 0
    private var videoHeight = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressListener: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    private var progressTickScheduled = false
    private val progressTick = object : Runnable {
        override fun run() {
            progressTickScheduled = false
            publishProgress()
            scheduleProgressTick()
        }
    }

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun play(uri: Uri, playing: Boolean = true) {
        shouldPlay = playing
        if (source == uri && player != null) {
            applyPlaybackState()
            return
        }
        source = uri
        if (isAvailable) openPlayer()
    }

    fun setProgressListener(listener: ((positionMs: Long, durationMs: Long) -> Unit)?) {
        progressListener = listener
        if (listener == null) {
            progressHandler.removeCallbacks(progressTick)
            progressTickScheduled = false
        } else {
            publishProgress()
            scheduleProgressTick()
        }
    }

    private fun applyPlaybackState() {
        val activePlayer = player ?: return
        runCatching {
            if (shouldPlay) {
                if (!activePlayer.isPlaying) activePlayer.start()
            } else if (activePlayer.isPlaying) {
                activePlayer.pause()
            }
        }
    }

    private fun openPlayer() {
        val uri = source ?: return
        val texture = surfaceTexture ?: return
        releasePlayer()
        val surface = Surface(texture)
        val candidate = MediaPlayer()
        try {
            try {
                candidate.setSurface(surface)
            } finally {
                surface.release()
            }
            candidate.isLooping = true
            candidate.setDataSource(context, uri)
            candidate.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                updateFitTransform()
            }
            candidate.setOnPreparedListener {
                applyPlaybackState()
                publishProgress()
            }
            candidate.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            player = candidate
            candidate.prepareAsync()
        } catch (error: Exception) {
            candidate.release()
            player = null
            Log.w("RecordingPreview", "Unable to open $uri", error)
        }
    }

    private fun updateFitTransform() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val fit = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val scaleX = fit * videoWidth / width
        val scaleY = fit * videoHeight / height
        setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, width / 2f, height / 2f)
            },
        )
    }

    private fun publishProgress() {
        val activePlayer = player ?: return
        val progress = runCatching {
            activePlayer.currentPosition.toLong() to activePlayer.duration.coerceAtLeast(0).toLong()
        }.getOrNull() ?: return
        progressListener?.invoke(progress.first, progress.second)
    }

    private fun scheduleProgressTick() {
        if (progressListener == null || progressTickScheduled || !isAttachedToWindow) return
        progressTickScheduled = true
        progressHandler.postDelayed(progressTick, PROGRESS_INTERVAL_MS)
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openPlayer()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateFitTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleProgressTick()
    }

    override fun onDetachedFromWindow() {
        progressHandler.removeCallbacks(progressTick)
        progressTickScheduled = false
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
