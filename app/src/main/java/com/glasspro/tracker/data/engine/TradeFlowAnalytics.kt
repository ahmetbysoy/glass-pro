package com.glasspro.tracker.data.engine

import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.Trade
import com.glasspro.tracker.core.model.WhaleTrade

/**
 * Trade-flow analytics over the consolidated real trade stream:
 *
 *  - Cumulative volume delta (CVD).
 *  - Aggressor buy/sell share.
 *  - Price/CVD divergence detection: price moved up while CVD fell (or vice
 *    versa), which flags weak-handed moves.
 *  - Whale trades: trades whose size is >= 10x the median trade size AND
 *    whose USD value is >= $4,000 (methodology of the reference engine).
 *  - Composite trade-flow score used as the "Trade Flow" component.
 */
object TradeFlowAnalytics {

    data class Result(
        val cvd: Double,
        val buyPct: Double,
        val sellPct: Double,
        val divergence: String,        // "Yok", "BULLISH DIVERJANS", "BEARISH DIVERJANS"
        val whaleTrades: List<WhaleTrade>,
        val score: Double
    )

    fun analyze(trades: List<Trade>): Result {
        if (trades.isEmpty()) {
            return Result(0.0, 50.0, 50.0, "Yok", emptyList(), 0.0)
        }
        val sorted = trades.sortedBy { it.timestampNs }

        var cvd = 0.0
        var buyVol = 0.0
        var sellVol = 0.0
        for (t in sorted) {
            if (t.side == LiquidationSide.LONG) {
                cvd += t.quantity
                buyVol += t.quantity
            } else {
                cvd -= t.quantity
                sellVol += t.quantity
            }
        }
        val totalVol = buyVol + sellVol
        val buyPct = if (totalVol > 0.0) 100.0 * buyVol / totalVol else 50.0
        val sellPct = 100.0 - buyPct

        // Divergence: median price of the first 10 vs the last 10 trades.
        var divergence = "Yok"
        if (sorted.size >= 20) {
            val early = sorted.take(10).map { it.price }
            val late = sorted.takeLast(10).map { it.price }
            val ps = Stats.median(early) ?: 0.0
            val pe = Stats.median(late) ?: 0.0
            if (ps > 0.0) {
                val tpx = (pe / ps - 1.0) * 100.0
                if (tpx > 0.05 && cvd < 0.0) divergence = "BEARISH DIVERJANS"
                else if (tpx < -0.05 && cvd > 0.0) divergence = "BULLISH DIVERJANS"
            }
        }

        // Whale trades.
        val sizes = sorted.map { it.quantity }
        val medSize = Stats.median(sizes) ?: 1.0
        val whaleThreshold = medSize * 10.0
        val whaleTrades = sorted.asReversed()
            .filter { it.quantity >= whaleThreshold && it.quantity * it.price >= 4000.0 }
            .take(5)
            .map {
                WhaleTrade(
                    timestampNs = it.timestampNs,
                    price = it.price,
                    size = it.quantity,
                    valueUsd = it.quantity * it.price,
                    side = it.side,
                    exchange = it.exchange
                )
            }

        val divBonus = when (divergence) {
            "BULLISH DIVERJANS" -> 25.0
            "BEARISH DIVERJANS" -> -25.0
            else -> 0.0
        }
        val score = Stats.clamp((buyPct - sellPct) * 1.5 + divBonus)

        return Result(cvd, buyPct, sellPct, divergence, whaleTrades, score)
    }
}
