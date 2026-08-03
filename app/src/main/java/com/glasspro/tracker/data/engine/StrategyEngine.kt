package com.glasspro.tracker.data.engine

import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.StrategySignal
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ATR-based position strategy generator (methodology of the reference engine):
 *
 *  - Stop loss at 1.5x ATR, take profit at 3.0x ATR from entry.
 *  - Direction from the aggregate score with a +/-20 neutral band.
 *  - Leverage recommendation inversely proportional to volatility,
 *    clamped between 2x and 50x.
 *  - Alerts for extreme funding and taker-pressure regimes.
 */
object StrategyEngine {

    fun generate(
        price: Double,
        totalScore: Double,
        direction: Direction,
        atrPct: Double,
        fundingRatePct: Double?,
        takerBuyPct: Double?
    ): StrategySignal {
        val slDist = price * (atrPct * 1.5 / 100.0)
        val tpDist = price * (atrPct * 3.0 / 100.0)

        val (sl, tp) = when (direction) {
            Direction.LONG -> (price - slDist) to (price + tpDist)
            Direction.SHORT -> (price + slDist) to (price - tpDist)
            Direction.NEUTRAL -> null to null
        }

        val leverage = if (atrPct > 0.0) {
            Stats.clampInt((15.0 / max(atrPct, 0.5)).roundToInt(), 2, 50)
        } else {
            10
        }

        val alerts = mutableListOf<String>()
        val fw = fundingRatePct
        if (fw != null) {
            if (fw < -0.01) {
                alerts.add("Funding negatif (${"%.4f".format(fw)}%) → short squeeze potansiyeli yüksek")
            } else if (fw > 0.02) {
                alerts.add("Funding aşırı pozitif (${"%.4f".format(fw)}%) → long tasfiyesi/kapitülasyon riski")
            }
        }
        val tbp = takerBuyPct
        if (tbp != null) {
            if (tbp > 55.0) {
                alerts.add("Taker alıcılar baskın (%${"%.0f".format(tbp)}) → momentum alımları destekliyor")
            } else if (tbp < 45.0) {
                alerts.add("Taker satıcılar baskın (%${"%.0f".format(tbp)}) → momentum satımları destekliyor")
            }
        }

        return StrategySignal(
            side = direction,
            entry = price,
            stopLoss = sl,
            takeProfit = tp,
            leverage = leverage,
            alerts = alerts
        )
    }
}
