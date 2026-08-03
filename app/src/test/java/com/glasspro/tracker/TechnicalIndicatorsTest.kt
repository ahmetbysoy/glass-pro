package com.glasspro.tracker

import com.glasspro.tracker.core.math.TechnicalIndicators
import com.glasspro.tracker.core.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TechnicalIndicatorsTest {

    /** Deterministic synthetic candles (no randomness). */
    private fun candles(n: Int, uptrend: Boolean): List<Candle> {
        val now = System.currentTimeMillis()
        return (0 until n).map { i ->
            val base = 100.0 + (if (uptrend) i * 0.5 else -i * 0.5)
            val wiggle = sin(i.toDouble()) * 1.5
            val open = base + wiggle
            val close = base + wiggle + (if (uptrend) 0.8 else -0.8)
            Candle(
                timestampMs = now - (n - 1 - i) * 60_000L,
                open = open,
                high = maxOf(open, close) + 0.5,
                low = minOf(open, close) - 0.5,
                close = close,
                volume = 1000.0 + (i % 7) * 100.0
            )
        }
    }

    @Test
    fun `compute requires at least 60 candles`() {
        assertNull(TechnicalIndicators.compute(candles(30, true), "5m"))
    }

    @Test
    fun `uptrend yields bullish state`() {
        val result = TechnicalIndicators.compute(candles(120, true), "5m")!!
        assertEquals("Bull", result.emaState)
        assertTrue(result.compositeScore > 0.0)
    }

    @Test
    fun `downtrend yields bearish state`() {
        val result = TechnicalIndicators.compute(candles(120, false), "5m")!!
        assertEquals("Bear", result.emaState)
        assertTrue(result.compositeScore < 0.0)
    }

    @Test
    fun `indicator ranges are valid`() {
        val result = TechnicalIndicators.compute(candles(120, true), "1h")!!
        assertTrue(result.rsi in 0.0..100.0)
        assertTrue(result.stochK in 0.0..100.0)
        assertTrue(result.atrPct > 0.0)
        assertNotNull(result)
    }

    @Test
    fun `multi-timeframe blend is bounded`() {
        val perTf = mapOf(
            "5m" to TechnicalIndicators.compute(candles(120, true), "5m"),
            "15m" to TechnicalIndicators.compute(candles(120, true), "15m"),
            "1h" to TechnicalIndicators.compute(candles(120, true), "1h"),
            "4h" to TechnicalIndicators.compute(candles(120, true), "4h")
        )
        val weights = mapOf("5m" to 0.15, "15m" to 0.25, "1h" to 0.35, "4h" to 0.25)
        val blended = TechnicalIndicators.blendMultiTimeframe(perTf, weights, 0.0)
        assertTrue(blended in -100.0..100.0)
    }
}
