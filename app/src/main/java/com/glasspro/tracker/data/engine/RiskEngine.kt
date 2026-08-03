package com.glasspro.tracker.data.engine

import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.model.RiskReport
import kotlin.math.abs
import kotlin.math.min

/**
 * Risk and manipulation radar (methodology of the PRO reference engine).
 *
 *  - Spoof rate from order book snapshot diffing.
 *  - Liquidation-hunt probability.
 *  - Funding deviance from the neutral +0.01% baseline.
 *  - Volume/price mismatch (1H volume ratio).
 *
 * The manipulation index is a weighted blend of those four; the general risk
 * index additionally penalises wide spreads and thin 24h liquidity.
 */
object RiskEngine {

    fun compute(
        spoofRate: Double,
        liqHunt: Double,
        fundingRatePct: Double?,
        volumeRatio1h: Double?,
        atrPct: Double,
        spreadPct: Double,
        quoteVolume24h: Double?,
        priceChangePct1h: Double?
    ): RiskReport {
        val fundingDeviance = fundingRatePct?.let {
            Stats.clamp(abs(it - 0.01) / 0.01 * 10.0, 0.0, 100.0)
        } ?: 0.0

        val vr = volumeRatio1h ?: 1.0
        val volumePriceMismatch = Stats.clamp(abs(vr - 1.0) * 20.0, 0.0, 100.0)

        val manipulationIndex = Stats.clamp(
            0.35 * spoofRate +
                0.25 * liqHunt +
                0.20 * fundingDeviance +
                0.20 * volumePriceMismatch,
            0.0, 100.0
        )

        val volPenalty = when {
            quoteVolume24h == null -> 20.0
            quoteVolume24h < 1_000_000.0 -> 20.0
            quoteVolume24h < 10_000_000.0 -> 10.0
            else -> 0.0
        }
        val spreadPenalty = if (spreadPct > 0.05) 25.0 else 0.0

        val generalRisk = Stats.clamp(
            0.25 * min(100.0, atrPct * 18.0) +
                0.25 * manipulationIndex +
                0.25 * liqHunt +
                0.15 * volPenalty +
                0.10 * spreadPenalty,
            0.0, 100.0
        )

        val p1h = priceChangePct1h ?: 0.0
        val falseBreakout = Stats.clamp(liqHunt * 0.55 + min(25.0, abs(p1h) * 3.0) + 15.0, 0.0, 100.0)

        return RiskReport(
            manipulationIndex = manipulationIndex,
            spoofRate = spoofRate,
            liquidationHunt = liqHunt,
            fundingDeviance = fundingDeviance,
            volumePriceMismatch = volumePriceMismatch,
            falseBreakout = falseBreakout,
            generalRisk = generalRisk
        )
    }
}
