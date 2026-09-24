package com.mobiledivecontrol.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import com.mobiledivecontrol.core.DiveStopAlert
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Phone alerts are supplementary: housing attenuation and Android audio settings still apply. */
class DiveStopNotifier(context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var track: AudioTrack? = null
    private var previousVolume: Int? = null
    private var raisedVolume: Int? = null
    private val finish = Runnable { releaseAlert() }
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var speechFailed = false
    private var utterance: String? = null
    private var phrase: String? = null
    private var earliestFinishMs = 0L
    private var alertNumber = 0L
    private var closed = false
    private val sayPhrase = object : Runnable {
        override fun run() {
            val text = phrase ?: return
            if (!speechReady) {
                if (!speechFailed) handler.postDelayed(this, 100L)
                return
            }
            val parameters = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            }
            if (speech?.speak(text, TextToSpeech.QUEUE_FLUSH, parameters, utterance) == TextToSpeech.ERROR)
                Log.e("DiveStopVoice", "Speech playback failed")
        }
    }

    init {
        speech = TextToSpeech(context.applicationContext) { status -> handler.post {
            if (closed) return@post
            val engine = speech ?: return@post
            if (status != TextToSpeech.SUCCESS) {
                speechFailed = true
                Log.e("DiveStopVoice", "Speech engine unavailable")
                return@post
            }
            val voice = engine.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == Locale.US }
                    .thenByDescending { it.quality }.thenBy { it.latency })?.firstOrNull()
            if (voice == null || engine.setVoice(voice) == TextToSpeech.ERROR) {
                speechFailed = true
                Log.e("DiveStopVoice", "No installed offline English voice")
                return@post
            }
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setSpeechRate(0.82f)
            engine.setPitch(0.95f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { Log.i("DiveStopVoice", "Speaking $id") }
                override fun onDone(id: String?) { finishSpeech(id) }
                @Deprecated("Android legacy callback")
                override fun onError(id: String?) { Log.e("DiveStopVoice", "Failed $id"); finishSpeech(id) }
            })
            speechReady = true
            Log.i("DiveStopVoice", "Offline voice ready: ${voice.name}")
        } }
    }

    private fun finishSpeech(id: String?) { handler.post {
        if (id != utterance) return@post
        Log.i("DiveStopVoice", "Finished $id")
        handler.removeCallbacks(finish)
        handler.postDelayed(finish, (earliestFinishMs - SystemClock.uptimeMillis()).coerceAtLeast(0))
    } }

    fun notify(alert: DiveStopAlert) {
        handler.removeCallbacks(finish)
        releaseAlert()
        val completed = alert == DiveStopAlert.Completed
        utterance = "${alert.name}-${++alertNumber}"
        phrase = if (completed) "SAFETY STOP FINISHED" else "BEGIN SAFETY STOP"
        earliestFinishMs = SystemClock.uptimeMillis() + if (completed) 4_300L else 2_000L
        runCatching {
            // Start: 1.5 × the original pattern. Completion: 2 ×, with a long final pulse.
            val pattern = if (completed) longArrayOf(0, 900, 360, 900, 360, 1600)
                else longArrayOf(0, 330, 225, 330, 225, 330)
            val amplitudes = IntArray(pattern.size) { if (it % 2 == 0) 0 else 255 }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1), attributes)
        }
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            raisedVolume = max
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
        runCatching {
            // Generate the complete signal: short system acknowledgement tones ignore longer
            // requested durations. Three urgent pulses start; two rising chimes confirm completion.
            val pcm = sound(completed)
            val player = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track = player
            check(player.write(pcm, 0, pcm.size) == pcm.size)
            player.setVolume(1f)
            player.play()
        }
        handler.postDelayed(sayPhrase, if (completed) 2_550L else 1_950L)
        // Release even if a broken speech engine never sends its completion callback.
        handler.postDelayed(finish, 12_000L)
    }

    private fun releaseAlert() {
        handler.removeCallbacks(sayPhrase)
        utterance = null
        phrase = null
        runCatching { speech?.stop() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { vibrator?.cancel() }
        runCatching {
            // Preserve a volume change the user made during the alert.
            previousVolume?.let { old ->
                if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == raisedVolume)
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, old, 0)
            }
        }
        previousVolume = null
        raisedVolume = null
    }

    fun close() {
        closed = true
        handler.removeCallbacks(finish)
        releaseAlert()
        runCatching { speech?.shutdown() }
        speech = null
    }

    private fun sound(completed: Boolean): ShortArray {
        val seconds = if (completed) 2.4 else 1.8
        val notes = doubleArrayOf(660.0, 880.0, 1100.0, 1320.0)
        return ShortArray((SAMPLE_RATE * seconds).toInt()) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val period = if (completed) 0.3 else 0.6
            val local = t % period
            val length = if (completed) 0.27 else 0.45
            val frequency = if (completed) notes[((t / period).toInt()) % notes.size] else 1000.0
            // Brief ramps prevent clicks; mixed harmonics remain within PCM limits.
            val envelope = min(local / 0.008, (length - local) / 0.012).coerceIn(0.0, 1.0)
            val signal = (0.75 * sin(2 * PI * frequency * t) + 0.25 * sin(2 * PI * frequency * 2 * t))
            (Short.MAX_VALUE * signal * envelope).toInt().toShort()
        }
    }

    private companion object { const val SAMPLE_RATE = 48_000 }
}
