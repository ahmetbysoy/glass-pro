package com.glasspro.tracker.core.math

import com.glasspro.tracker.core.math.Stats.clamp
import com.glasspro.tracker.core.math.Stats.ema
import com.glasspro.tracker.core.math.Stats.mean
import com.glasspro.tracker.core.math.Stats.smaLast
import com.glasspro.tracker.core.math.Stats.stdDev
import com.glasspro.tracker.core.math.Stats.trueRanges
import com.glasspro.tracker.core.math.Stats.wilderSmooth
import com.glasspro.tracker.core.model.Candle
import com.glasspro.tracker.core.model.IndicatorResult

/**
 * Technical indicator suite (RSI-14, EMA 9/21, MACD 12/26/9, ATR-14%,
 * Stochastic %K 14, Bollinger %B 20/2, volume ratio 20, 1- and 3-candle
 * returns) computed on real candles. Mirrors the methodology of the PRO
 * engine: a composite -100..100 score is produced per timeframe, then the
 * multi-timeframe module blends the scores with the configured weights.
 */
object TechnicalIndicators {

    private const val MIN_CANDLES = 60

    /**
     * Computes the full indicator set for one candle series.
     * Returns null when fewer than 60 candles are available.
     */
    fun compute(candles: List<Candle>, timeframe: String): IndicatorResult? {
        if (candles.size < MIN_CANDLES) return null

        val sorted = candles.sortedBy { it.timestampMs }
        val close = sorted.map { it.close }
        val high = sorted.map { it.high }
        val low = sorted.map { it.low }
        val volume = sorted.map { it.volume }
        val currentClose = close.last()
        if (currentClose <= 0.0) return null

        // --- RSI(14) with Wilder smoothing ---
        val deltas = mutableListOf<Double>()
        for (i in 1 until close.size) deltas.add(close[i] - close[i - 1])
        val gains = deltas.map { it.coerceAtLeast(0.0) }
        val losses = deltas.map { (-it).coerceAtLeast(0.0) }
        val avgGain = wilderSmooth(gains, 14)
        val avgLoss = wilderSmooth(losses, 14)
        val rsi: Double = if (avgGain.isNotEmpty() && avgLoss.isNotEmpty()) {
            val ag = avgGain.last()
            val al = avgLoss.last()
            if (al == 0.0) 100.0 else (100.0 - 100.0 / (1.0 + ag / al))
        } else {
            50.0
        }

        // --- EMA 9/21 trend state ---
        val ema9 = ema(close, 9)
        val ema21 = ema(close, 21)
        val e9 = ema9.last()
        val e21 = ema21.last()
        val emaState = when {
            e9 > e21 -> "Bull"
            e9 < e21 -> "Bear"
            else -> "Mixed"
        }

        // --- MACD(12,26,9) histogram ---
        val macdLine = ema(close, 12).zip(ema(close, 26)) { a, b -> a - b }
        val signalLine = ema(macdLine, 9)
        val macdHistogram = macdLine.last() - signalLine.last()

        // --- ATR(14) percent ---
        val tr = trueRanges(high, low, close)
        val atr = if (tr.size >= 14) {
            wilderSmooth(tr, 14).last()
        } else {
            tr.average()
        }
        val atrPct = atr / currentClose * 100.0

        // --- Stochastic %K(14) ---
        val lo14 = low.takeLast(14).minOrNull() ?: currentClose
        val hi14 = high.takeLast(14).maxOrNull() ?: currentClose
        val stochK = if (hi14 > lo14) {
            (currentClose - lo14) / (hi14 - lo14) * 100.0
        } else {
            50.0
        }

        // --- Bollinger %B(20, 2) ---
        val sma20 = smaLast(close, 20) ?: currentClose
        val sd20 = stdDev(close.takeLast(20)) ?: 0.0
        val bbPct = if (sd20 > 0.0) {
            (currentClose - (sma20 - 2 * sd20)) / (4 * sd20) * 100.0
        } else {
            50.0
        }

        // --- Volume ratio (current / 20-bar average) ---
        val volAvg20 = smaLast(volume, 20) ?: 1.0
        val volumeRatio = if (volAvg20 > 0.0) volume.last() / volAvg20 else 1.0

        // --- 1- and 3-candle returns ---
        val ret1 = Stats.pctChange(close[close.size - 2], currentClose) ?: 0.0
        val ret3 = if (close.size >= 4) {
            Stats.pctChange(close[close.size - 4], currentClose) ?: 0.0
        } else {
            0.0
        }

        // --- Composite technical score (methodology from the PRO engine) ---
        val trendBias = if (emaState == "Bull") 22.0 else if (emaState == "Bear") -22.0 else 0.0
        val momentumTerm = clamp(ret1 / maxOf(atrPct, 0.05) * 12.0, -20.0, 20.0)
        val composite = clamp((rsi - 50.0) * 1.1 + trendBias + momentumTerm)

        return IndicatorResult(
            timeframe = timeframe,
            rsi = rsi,
            emaState = emaState,
            macdHistogram = macdHistogram,
            atrPct = atrPct,
            stochK = stochK,
            bollingerBPct = bbPct,
            volumeRatio = volumeRatio,
            retPct1 = ret1,
            retPct3 = ret3,
            compositeScore = composite
        )
    }

    /**
     * Blends per-timeframe indicator scores with the timeframe weights.
     * Missing timeframes are excluded and weights renormalized over the
     * available set so a data outage never biases the result.
     */
    fun blendMultiTimeframe(
        perTimeframe: Map<String, IndicatorResult?>,
        timeframeWeights: Map<String, Double>,
        macroBias: Double
    ): Double {
        val entries = perTimeframe.filterValues { it != null }
        val totalWeight = entries.entries.sumOf { (tf, _) -> timeframeWeights[tf] ?: 0.0 }
        if (entries.isEmpty() || totalWeight <= 0.0) return 0.0
        val blended = entries.entries.sumOf { (tf, ind) ->
            (ind!!.compositeScore) * (timeframeWeights[tf] ?: 0.0)
        } / totalWeight
        return clamp(blended + macroBias)
    }
}
