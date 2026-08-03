package com.glasspro.tracker.data.engine

import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.model.LiquidationWindow
import kotlin.math.abs
import kotlin.math.max

/**
 * Derivative and liquidation analytics:
 *
 *  - Funding component score: extreme positive funding (> +0.01%) means longs
 *    pay and crowd risk builds; extreme negative funding means shorts pay and
 *    a squeeze is possible. Scaled like the reference engine.
 *  - Open-interest component score: OI growing with price = conviction; OI
 *    growing against price = distribution.
 *  - Liquidation component score: imbalance between short and long liquidation
 *    notional in the trailing window (short liquidations imply buy pressure).
 *  - Liquidation-hunt probability: how close the price sits to the largest
 *    liquidation cluster price (stop-hunt zone).
 */
object DerivativeAnalytics {

    data class FundingAnalysis(
        val fundingRatePct: Double?,
        val score: Double,
        val trend: String   // "up" | "down" | "flat"
    )

    data class OiAnalysis(
        val oiChangePct1h: Double?,
        val score: Double?
    )

    data class LiquidationAnalysis(
        val longNotionalUsd: Double,
        val shortNotionalUsd: Double,
        val imbalancePct: Double,
        val score: Double,
        val liqHunt: Double
    )

    private const val NEUTRAL_FUNDING_PCT = 0.01

    fun funding(fundingRatePct: Double?, previousFundingPct: Double?): FundingAnalysis {
        val fw = fundingRatePct
        if (fw == null) {
            return FundingAnalysis(null, 0.0, "flat")
        }
        val score = when {
            fw > NEUTRAL_FUNDING_PCT -> Stats.clamp(-minOf(60.0, (fw - NEUTRAL_FUNDING_PCT) / NEUTRAL_FUNDING_PCT * 30.0))
            fw < NEUTRAL_FUNDING_PCT -> Stats.clamp(minOf(60.0, (NEUTRAL_FUNDING_PCT - fw) / NEUTRAL_FUNDING_PCT * 30.0))
            else -> 0.0
        }
        val trend = when {
            previousFundingPct == null -> "flat"
            fw > previousFundingPct + 0.0001 -> "up"
            fw < previousFundingPct - 0.0001 -> "down"
            else -> "flat"
        }
        return FundingAnalysis(fw, score, trend)
    }

    fun openInterest(oiChangePct1h: Double?, priceChangePct1h: Double?): OiAnalysis {
        if (oiChangePct1h == null || priceChangePct1h == null) {
            return OiAnalysis(oiChangePct1h, null)
        }
        val score = if (oiChangePct1h > 0.0) {
            val magnitude = 20.0 + minOf(55.0, abs(oiChangePct1h) * 3.0)
            Stats.clamp(if (priceChangePct1h >= 0.0) magnitude else -magnitude)
        } else {
            val magnitude = 8.0 + minOf(20.0, abs(oiChangePct1h))
            Stats.clamp(if (priceChangePct1h >= 0.0) magnitude else -magnitude)
        }
        return OiAnalysis(oiChangePct1h, score)
    }

    fun liquidation(
        window: LiquidationWindow,
        price: Double,
        highestLiquidationPrice: Double?
    ): LiquidationAnalysis {
        val total = window.totalNotionalUsd
        val imbalance = if (total > 0.0) {
            100.0 * (window.shortNotionalUsd - window.longNotionalUsd) / total
        } else {
            0.0
        }
        val score = Stats.clamp(imbalance * 0.8)

        val liqHunt = if (highestLiquidationPrice != null && highestLiquidationPrice > 0.0 && price > 0.0) {
            val dist = abs(price - highestLiquidationPrice) / price * 100.0
            Stats.clamp(100.0 - dist * 20.0, 10.0, 95.0)
        } else {
            Stats.clamp(20.0 + abs(imbalance) * 0.35, 10.0, 95.0)
        }

        return LiquidationAnalysis(
            longNotionalUsd = window.longNotionalUsd,
            shortNotionalUsd = window.shortNotionalUsd,
            imbalancePct = imbalance,
            score = score,
            liqHunt = liqHunt
        )
    }

    /** Highest liquidation price in the trailing window, if any. */
    fun highestLiquidationPrice(window: LiquidationWindow, recentEvents: List<com.glasspro.tracker.core.model.LiquidationEvent>): Double? {
        val recent = recentEvents
            .filter { it.timestampNs >= System.currentTimeMillis() * 1_000_000L - 3_600_000_000_000L }
            .maxByOrNull { it.price }
        return recent?.price
    }
}
