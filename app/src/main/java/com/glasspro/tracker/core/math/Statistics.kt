package com.glasspro.tracker.core.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deterministic, dependency-free statistics helpers used by every analysis
 * engine. All functions are pure and unit-testable. No random sources anywhere
 * in this package.
 */
object Stats {

    fun clamp(value: Double, lo: Double = -100.0, hi: Double = 100.0): Double =
        if (value.isNaN() || value.isInfinite()) 0.0 else value.coerceIn(lo, hi)

    fun clampInt(value: Int, lo: Int, hi: Int): Int = value.coerceIn(lo, hi)

    /** Parses a numeric string (possibly containing commas or whitespace). */
    fun safeDouble(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().replace(",", "").toDoubleOrNull()?.let {
            if (it.isNaN() || it.isInfinite()) null else it
        }
    }

    fun safeDoubleOrZero(raw: String?): Double = safeDouble(raw) ?: 0.0

    fun safeLong(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().toLongOrNull()
    }

    fun mean(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        return values.sum() / values.size
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /** Sample standard deviation (n-1 denominator). */
    fun stdDev(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = mean(values) ?: return null
        val variance = values.sumOf { (it - m) * (it - m) } / (values.size - 1)
        return sqrt(variance)
    }

    /**
     * Median absolute deviation scaled to the normal distribution
     * (0.6745 * 1.4826 ≈ 1). Used by the robust z-score conflict filter so a
     * single manipulated price cannot corrupt the consensus.
     */
    fun madScaled(values: List<Double>): Double? {
        val m = median(values) ?: return null
        val deviations = values.map { abs(it - m) }
        val d = median(deviations) ?: return null
        return d * 1.4826
    }

    /**
     * Robust z-score: how many scaled-MADs a value deviates from the median.
     * Values with |z| > 3 are treated as outliers by the Quant Security Layer.
     */
    fun robustZScore(value: Double, values: List<Double>): Double? {
        val m = median(values) ?: return null
        val scale = madScaled(values) ?: return null
        if (scale <= 0.0) return null
        return abs(value - m) / scale
    }

    /** Pearson product-moment correlation coefficient. */
    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 2) return null
        val mx = mean(x) ?: return null
        val my = mean(y) ?: return null
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in x.indices) {
            val xd = x[i] - mx
            val yd = y[i] - my
            num += xd * yd
            dx += xd * xd
            dy += yd * yd
        }
        val denom = sqrt(dx * dy)
        if (denom == 0.0) return null
        return (num / denom).coerceIn(-1.0, 1.0)
    }

    /**
     * Exponential weighted moving average (Wilder's / EMA style).
     * @param period smoothing window; alpha = 2/(period+1) for standard EMA.
     */
    fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty() || period <= 0) return emptyList()
        val alpha = 2.0 / (period + 1)
        val result = ArrayList<Double>(values.size)
        var previous = values.first()
        result.add(previous)
        for (i in 1 until values.size) {
            previous = alpha * values[i] + (1 - alpha) * previous
            result.add(previous)
        }
        return result
    }

    /** Simple moving average over the last [period] values; null if insufficient. */
    fun smaLast(values: List<Double>, period: Int): Double? {
        if (values.size < period) return null
        return values.takeLast(period).average()
    }

    /** Simple moving average series (same length as input, leading nulls). */
    fun smaSeries(values: List<Double>, period: Int): List<Double?> {
        if (values.isEmpty() || period <= 0) return emptyList()
        val result = ArrayList<Double?>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            result.add(if (i >= period - 1) sum / period else null)
        }
        return result
    }

    /**
     * Exponential weighted mean with alpha = 1/period (used by RSI's
     * Wilder smoothing). Seed is the simple average of the first [period]
     * values.
     */
    fun wilderSmooth(values: List<Double>, period: Int): List<Double> {
        if (values.size < period) return emptyList()
        val alpha = 1.0 / period
        val result = ArrayList<Double>(values.size)
        var prev = values.take(period).average()
        result.add(prev)
        for (i in period until values.size) {
            prev = alpha * values[i] + (1 - alpha) * prev
            result.add(prev)
        }
        return result
    }

    /** True range series. */
    fun trueRanges(high: List<Double>, low: List<Double>, close: List<Double>): List<Double> {
        if (high.size != low.size || low.size != close.size) return emptyList()
        val tr = ArrayList<Double>(high.size)
        tr.add(high.first() - low.first())
        for (i in 1 until high.size) {
            val h = high[i]
            val l = low[i]
            val pc = close[i - 1]
            tr.add(maxOf(h - l, abs(h - pc), abs(l - pc)))
        }
        return tr
    }

    /** Percent change between two values; null when base is zero. */
    fun pctChange(from: Double, to: Double): Double? {
        if (from == 0.0) return null
        return (to - from) / from * 100.0
    }
}
