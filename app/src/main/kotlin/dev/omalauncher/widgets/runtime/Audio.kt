package dev.omalauncher.widgets.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the audio capture is doing, shared by [AudioCaptureService], which runs it, and the widgets that read it.
 *
 * Capture is Android's AudioPlaybackCapture: it copies what other apps play, once the user has agreed to the
 * system's recording prompt (MediaProjection) and the launcher holds RECORD_AUDIO. It only reads audio while a
 * widget listens ([listeners]); with none, the projection stays open and the reading stops.
 */
object AudioCapture {
    data class State(
        val capturing: Boolean = false,
        val levels: Levels? = null,
    )

    val state = MutableStateFlow(State())

    /** How many widgets are listening right now. */
    val listeners = MutableStateFlow(0)

    /** Reads audio through [projection] while anyone listens, until cancelled. */
    suspend fun run(projection: MediaProjection) {
        state.value = State(capturing = true)
        try {
            listeners.map { it > 0 }.distinctUntilChanged().collectLatest { listening ->
                if (listening) {
                    read(projection)
                } else {
                    state.value = State(capturing = true)
                }
            }
        } finally {
            state.value = State()
        }
    }

    private suspend fun read(projection: MediaProjection) =
        withContext(Dispatchers.IO) {
            val rec = open(projection) ?: return@withContext
            // A read that waits for audio that isn't coming only ends when the recorder stops, so when this is
            // cancelled, something else has to stop it: the loop below can't, it's the one that's waiting.
            val watcher =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { rec.stop() }
                    }
                }
            try {
                rec.startRecording()
                val analyzer = SpectrumAnalyzer(rec.sampleRate)
                val raw = ShortArray(analyzer.hop * 2)
                val mono = FloatArray(analyzer.hop)
                while (isActive) {
                    val n = rec.read(raw, 0, raw.size)
                    // Negative is an error: the projection ended, or the recorder died.
                    if (n < 0) break
                    val frames = n / 2
                    if (frames == 0) continue
                    for (i in 0 until frames) mono[i] = (raw[2 * i] + raw[2 * i + 1]) / 65536f
                    if (analyzer.push(mono, frames)) state.value = State(capturing = true, levels = analyzer.analyze())
                }
            } finally {
                watcher.cancel()
                runCatching { rec.stop() }
                rec.release()
            }
        }

    /** Stereo, since that's what playback capture hands out; 48 kHz, else 44.1. */
    @SuppressLint("MissingPermission")
    private fun open(projection: MediaProjection): AudioRecord? {
        val config =
            AudioPlaybackCaptureConfiguration
                .Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        for (rate in intArrayOf(48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec =
                runCatching {
                    AudioRecord
                        .Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(
                            AudioFormat
                                .Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build(),
                        ).setBufferSizeInBytes(max(min, 16 * 1024) * 2)
                        .build()
                }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec.release()
        }
        return null
    }
}

/**
 * The audio other apps play, as levels, for `source a = android.audio`, and `audio(...)` to start or stop it.
 * Needs RECORD_AUDIO and the system's recording prompt, which `audio("start")` brings up.
 */
class AudioLevels(
    private val context: Context,
) {
    private val granted get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun value(): Value = AudioCapture.state.value.toValue()

    /** Emits at most [SpectrumAnalyzer.FPS] times a second while capturing, and counts as a listener while collected. */
    fun updates(): Flow<Value> =
        AudioCapture.state
            .onStart { AudioCapture.listeners.update { it + 1 } }
            .onCompletion { AudioCapture.listeners.update { it - 1 } }
            .map { it.toValue() }
            .conflate()

    /** audio("start"), "stop" or "toggle". */
    fun control(command: String) {
        val on = AudioCapture.state.value.capturing
        when (command) {
            "start" -> if (!on) start()
            "stop" -> stop()
            "toggle" -> if (on) stop() else start()
        }
    }

    private fun start() {
        context.startActivity(Intent(context, AudioPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun stop() {
        context.stopService(Intent(context, AudioCaptureService::class.java))
    }

    private fun AudioCapture.State.toValue(): Value {
        fun n(x: Float?) = Num(((x ?: 0f) * 100).roundToInt() / 100.0)
        return Obj(
            mapOf(
                "available" to bool(granted),
                "active" to bool(capturing),
                "sub" to n(levels?.sub),
                "bass" to n(levels?.bass),
                "mid" to n(levels?.mid),
                "high" to n(levels?.high),
                "bars" to Lst(levels?.bars?.map { n(it) } ?: List(BARS) { n(0f) }),
            ),
        )
    }

    private companion object {
        const val BARS = 32
    }
}
