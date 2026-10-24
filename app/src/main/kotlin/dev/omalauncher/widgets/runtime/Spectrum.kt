package dev.omalauncher.widgets.runtime

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One frame of levels, each 0..1: the four bands, and [bars] log-spaced from [SpectrumAnalyzer.BAR_LO_HZ] up. */
class Levels(
    val sub: Float,
    val bass: Float,
    val mid: Float,
    val high: Float,
    val bars: FloatArray,
)

/**
 * Turns audio into levels: a Hann window, an FFT of [size] samples, the loudest bin in each band in dB (with a
 * tilt that lifts the highs, which music has far less energy in), mapped to 0..1 between [minDb] and [maxDb],
 * and smoothed to rise fast and fall slowly. A bin is `sampleRate / size` Hz wide: at 48 kHz and 4096 that is
 * 11.7 Hz, so sub bass (20-60 Hz) gets 4 bins; 8192 halves that, and makes the response slower.
 *
 * Feed it with [push] as samples arrive; when it returns true, [analyze] has a new frame. Not thread safe.
 */
class SpectrumAnalyzer(
    val sampleRate: Int,
    val size: Int = 4096,
    val barCount: Int = 32,
    private val minDb: Float = -80f,
    private val maxDb: Float = -12f,
) {
    init {
        require(size >= 256 && size and (size - 1) == 0) { "size must be a power of two, 256 or more" }
        require(barCount >= 1)
    }

    /** Samples between frames. */
    val hop = sampleRate / FPS

    private val hzPerBin = sampleRate.toFloat() / size
    private val half = size / 2
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / (size - 1))).toFloat() }
    private val cosTable = FloatArray(half) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(half) { (-sin(2.0 * PI * it / size)).toFloat() }
    private val reversed = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    private val samples = FloatArray(size)
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val db = FloatArray(half)
    private var fresh = 0

    /** Bar edges in Hz, log-spaced; the top stops short of the Nyquist frequency, where the filters roll off. */
    private val barEdges =
        FloatArray(barCount + 1).also {
            val lo = ln(BAR_LO_HZ)
            val hi = ln(min(BAR_HI_HZ, sampleRate * 0.45f))
            for (i in it.indices) it[i] = exp(lo + (hi - lo) * i / barCount)
        }

    /** The four bands, then the bars. */
    private val smoothed = FloatArray(4 + barCount)

    /** Adds the first [count] of [src]; true once a [hop]'s worth is new. */
    fun push(
        src: FloatArray,
        count: Int,
    ): Boolean {
        val n = min(count, size)
        System.arraycopy(samples, n, samples, 0, size - n)
        System.arraycopy(src, count - n, samples, size - n, n)
        fresh += count
        return fresh >= hop
    }

    fun analyze(): Levels {
        fresh = 0
        var peak = 0f
        for (i in 0 until size) {
            val s = samples[i]
            peak = max(peak, abs(s))
            re[i] = s * window[i]
            im[i] = 0f
        }
        val loud = peak >= SILENCE
        if (loud) {
            fft()
            // A full-scale sine under a Hann window peaks at size / 4, so this reads 0 dB for full scale.
            val norm = 4f / size
            for (k in 1 until half) {
                val f = k * hzPerBin
                db[k] = 20f * log10(hypot(re[k], im[k]) * norm + 1e-9f) + tilt(f)
            }
        }
        BANDS.forEachIndexed { i, (lo, hi) -> follow(i, if (loud) level(lo, hi) else 0f) }
        for (b in 0 until barCount) follow(4 + b, if (loud) level(barEdges[b], barEdges[b + 1]) else 0f)
        return Levels(smoothed[0], smoothed[1], smoothed[2], smoothed[3], smoothed.copyOfRange(4, smoothed.size))
    }

    /** Rises fast, falls slowly. */
    private fun follow(
        i: Int,
        target: Float,
    ) {
        val old = smoothed[i]
        smoothed[i] = old + (target - old) * (if (target > old) ATTACK else RELEASE)
    }

    /** The loudest bin from [loHz] to [hiHz], 0..1; a range narrower than a bin reads between the two it falls across. */
    private fun level(
        loHz: Float,
        hiHz: Float,
    ): Float {
        val lo = max(1, Math.ceil((loHz / hzPerBin).toDouble()).toInt())
        val hi = min(half - 1, floor(hiHz / hzPerBin).toInt())
        val d =
            if (lo <= hi) {
                var m = Float.NEGATIVE_INFINITY
                for (k in lo..hi) m = max(m, db[k])
                m
            } else {
                val pos = ((loHz + hiHz) / 2f / hzPerBin).coerceIn(1f, half - 2f)
                val k = pos.toInt()
                db[k] + (db[k + 1] - db[k]) * (pos - k)
            }
        return ((d - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    }

    private fun tilt(hz: Float) = TILT_DB_PER_OCTAVE * max(0f, (ln(hz / TILT_FROM_HZ) / LN2).toFloat())

    private val LN2 = ln(2f)

    private fun fft() {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val h = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                for (k in 0 until h) {
                    val wr = cosTable[k * step]
                    val wi = sinTable[k * step]
                    val a = i + k
                    val b = a + h
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** Frames a second; the widget re-reads its sources on each. */
        const val FPS = 30

        /** Sub bass, bass, mid, high. */
        val BANDS = listOf(20f to 60f, 60f to 250f, 250f to 4000f, 4000f to 16000f)

        const val BAR_LO_HZ = 30f
        const val BAR_HI_HZ = 16000f

        /** Under this peak (about -80 dB) a window counts as silence and the levels fall to 0. */
        const val SILENCE = 1e-4f

        /** Music falls off about this fast with frequency; adding it back keeps the highs from sitting flat. */
        const val TILT_DB_PER_OCTAVE = 3f
        const val TILT_FROM_HZ = 250f

        /** Per frame, how much of the way to a new level it goes: up, and down. */
        const val ATTACK = 0.8f
        const val RELEASE = 0.15f
    }
}
