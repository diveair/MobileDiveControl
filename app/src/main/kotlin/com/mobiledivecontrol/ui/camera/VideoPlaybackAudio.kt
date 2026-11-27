package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Called on the recording finalizer. Only the audio needs decoding to change playback speed. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun exportPlaybackAudio(context: Context, source: File, destination: File, speed: Double) {
    check(Looper.myLooper() != Looper.getMainLooper())
    val finished = CompletableFuture<Unit>()
    val main = Handler(Looper.getMainLooper())
    var exporter: Transformer? = null
    main.post {
        runCatching {
            val processor = SonicAudioProcessor().apply { setSpeed(speed.toFloat()) }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setRemoveVideo(true).setEffects(Effects(listOf(processor), emptyList())).build()
            exporter = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.complete(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished.completeExceptionally(exportException)
                    }
                }).build()
            exporter!!.start(item, destination.absolutePath)
        }.onFailure { finished.completeExceptionally(it) }
    }
    try {
        finished.get()
    } catch (interrupted: InterruptedException) {
        main.post { exporter?.cancel() }
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (error: ExecutionException) {
        throw (error.cause ?: error)
    }
}
