package dev.omalauncher.widgets.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumTest {
    private val rate = 48_000

    /** Runs [seconds] of a sine of [hz] at [amp] through the analyzer and returns the last frame. */
    private fun play(
        hz: Double,
        amp: Double = 0.5,
        seconds: Double = 1.0,
        a: SpectrumAnalyzer = SpectrumAnalyzer(rate),
    ): Levels {
        val chunk = FloatArray(a.hop)
        var n = 0L
        var last: Levels? = null
        repeat((seconds * rate / a.hop).toInt()) {
            for (i in chunk.indices) chunk[i] = (amp * sin(2 * PI * hz * (n++) / rate)).toFloat()
            if (a.push(chunk, chunk.size)) last = a.analyze()
        }
        return last!!
    }

    @Test fun eachBandHearsItsOwnFrequency() {
        // A Hann window leaks a pure tone into its neighbours, most into the next band for a tone this low:
        // 40 Hz still reads in the bass band, only clearly less than in sub bass.
        val sub = play(40.0)
        assertTrue(sub.sub > 0.9f && sub.bass < sub.sub - 0.3f && sub.mid < 0.05f && sub.high < 0.05f)
        val bass = play(120.0)
        assertTrue(bass.bass > 0.9f && bass.sub < bass.bass - 0.5f && bass.mid < 0.05f)
        val mid = play(1000.0)
        assertTrue(mid.mid > 0.9f && mid.bass < 0.05f && mid.high < 0.05f)
        val high = play(8000.0)
        assertTrue(high.high > 0.9f && high.mid < 0.05f)
    }

    @Test fun fullScaleSineReadsAboutZeroDbAndQuietReadsLower() {
        val loud = play(1000.0, amp = 1.0).mid
        val quiet = play(1000.0, amp = 0.01).mid
        assertTrue("$loud $quiet", loud > quiet + 0.2f)
        assertTrue(loud > 0.95f)
    }

    @Test fun silenceFallsToZeroWithoutEverRising() {
        val a = SpectrumAnalyzer(rate)
        play(120.0, a = a)
        val chunk = FloatArray(a.hop)
        // One frame of window's worth of silence isn't enough to clear the window, but the levels must decay.
        val levels = ArrayList<Float>()
        repeat(a.size / a.hop + 40) {
            if (a.push(chunk, chunk.size)) levels += a.analyze().bass
        }
        assertEquals(0f, levels.last(), 0.01f)
        assertTrue(levels.zipWithNext().all { (x, y) -> y <= x + 1e-6f })
    }

    @Test fun barsAreLogSpacedAndTheOneUnderTheToneIsLoudest() {
        val a = SpectrumAnalyzer(rate, barCount = 32)
        val l = play(1000.0, a = a)
        assertEquals(32, l.bars.size)
        val loudest = l.bars.indices.maxByOrNull { l.bars[it] }!!
        // 30 Hz to 16 kHz in 32 log steps: 1 kHz falls in bar 17 of 0..31.
        assertTrue("bar $loudest", loudest in 16..18)
    }

    @Test fun biggerWindowResolvesSubBassFinerButNeedsMoreSamples() {
        val a = SpectrumAnalyzer(rate, size = 8192)
        assertEquals(8192, a.size)
        val l = play(40.0, a = a, seconds = 1.5)
        // Narrower bins leak less into the bass band than 4096's did.
        assertTrue(l.sub > 0.9f && l.bass < 0.4f)
    }
}
