package com.glasspro.tracker.data.engine

import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.ResolvedPrediction
import com.glasspro.tracker.core.model.SignalStatus
import kotlin.math.abs

/**
 * Retrospective accuracy verification (methodology of the reference engine):
 *
 *  - The flat band is ATR-relative: `clamp(atrPct * 0.6, 0.3, 2.5)`. A LONG
 *    prediction hits when the realized change is above the band, a SHORT when
 *    below, NEUTRAL when inside.
 *  - Rolling accuracy over the last 20 resolved predictions per symbol.
 *  - Pearson correlation between each recorded component score and the
 *    realized price change over the resolved history — this quantifies which
 *    components actually predict for a given symbol.
 */
object CalibrationEngine {

    fun flatBand(atrPct: Double): Double =
        Stats.clamp(atrPct * 0.6, 0.3, 2.5)

    fun evaluate(direction: Direction, atrPct: Double, priceAtPrediction: Double, actualPrice: Double): SignalStatus {
        if (priceAtPrediction <= 0.0) return SignalStatus.MISS
        val changePct = (actualPrice - priceAtPrediction) / priceAtPrediction * 100.0
        val band = flatBand(atrPct)
        return when (direction) {
            Direction.LONG -> if (changePct >= band) SignalStatus.HIT else SignalStatus.MISS
            Direction.SHORT -> if (changePct <= -band) SignalStatus.HIT else SignalStatus.MISS
            Direction.NEUTRAL -> if (abs(changePct) < band) SignalStatus.HIT else SignalStatus.MISS
        }
    }

    fun rollingAccuracy(resolved: List<ResolvedPrediction>): Double? {
        val recent = resolved.takeLast(20)
        if (recent.size < 5) return null
        val hits = recent.count { p ->
            evaluate(p.direction, p.atrPct, p.priceAtPrediction, p.priceAfter) == SignalStatus.HIT
        }
        return hits.toDouble() / recent.size
    }

    /**
     * Pearson correlation of each component with the realized price change.
     * Only components with non-zero variance on both sides are included.
     */
    fun componentCorrelations(resolved: List<ResolvedPrediction>): Map<String, Double> {
        if (resolved.size < 5) return emptyMap()
        val changes = resolved.map { p ->
            if (p.priceAtPrediction > 0.0) {
                (p.priceAfter - p.priceAtPrediction) / p.priceAtPrediction * 100.0
            } else {
                0.0
            }
        }
        if (changes.distinct().size < 2) return emptyMap()

        val keys = resolved.firstNotNullOfOrNull { it.components.keys } ?: return emptyMap()
        val result = mutableMapOf<String, Double>()
        for (key in keys) {
            val scores = resolved.mapNotNull { it.components[key] }
            if (scores.size < 5) continue
            val paired = scores.zip(changes)
            // Only keep the slice where both sides vary.
            val distinctScores = scores.distinct().size
            val distinctChanges = changes.distinct().size
            if (distinctScores > 1 && distinctChanges > 1) {
                val corr = Stats.pearson(scores, changes)
                if (corr != null) result[key] = corr
            }
        }
        return result
    }
}
