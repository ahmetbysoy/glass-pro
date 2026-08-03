package com.glasspro.tracker

import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.LiquidationWindow
import com.glasspro.tracker.core.model.OrderBookLevel
import com.glasspro.tracker.core.model.SignalStatus
import com.glasspro.tracker.core.model.Trade
import com.glasspro.tracker.data.engine.CalibrationEngine
import com.glasspro.tracker.data.engine.DerivativeAnalytics
import com.glasspro.tracker.data.engine.OrderBookAnalytics
import com.glasspro.tracker.data.engine.StrategyEngine
import com.glasspro.tracker.data.engine.TradeFlowAnalytics
import com.glasspro.tracker.data.remote.adapter.OrderBookData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginesTest {

    // ------------------------------------------------------------------
    // Order book analytics
    // ------------------------------------------------------------------

    @Test
    fun `bid heavy book produces positive score`() {
        val bids = (1..50).map { OrderBookLevel(100.0 - it * 0.01, 1000.0) }
        val asks = (1..50).map { OrderBookLevel(100.0 + it * 0.01, 100.0) }
        val snapshot = mapOf("OKX" to OrderBookData(bids, asks))
        val result = OrderBookAnalytics.analyze(100.0, snapshot, emptyMap())
        assertTrue(result.bidPct > 50.0)
        assertTrue(result.score > 0.0)
    }

    @Test
    fun `ask heavy book produces negative score`() {
        val bids = (1..50).map { OrderBookLevel(100.0 - it * 0.01, 100.0) }
        val asks = (1..50).map { OrderBookLevel(100.0 + it * 0.01, 1000.0) }
        val snapshot = mapOf("OKX" to OrderBookData(bids, asks))
        val result = OrderBookAnalytics.analyze(100.0, snapshot, emptyMap())
        assertTrue(result.askPct > 50.0)
        assertTrue(result.score < 0.0)
    }

    // ------------------------------------------------------------------
    // Trade flow analytics
    // ------------------------------------------------------------------

    @Test
    fun `buy dominated flow yields positive score and cvd`() {
        val trades = (1..50).map {
            Trade(
                timestampNs = it * 1_000_000L,
                price = 100.0,
                quantity = 10.0,
                side = LiquidationSide.LONG,
                exchange = "OKX"
            )
        }
        val result = TradeFlowAnalytics.analyze(trades)
        assertTrue(result.cvd > 0.0)
        assertTrue(result.buyPct > 50.0)
        assertTrue(result.score > 0.0)
    }

    @Test
    fun `whale detection catches outsized trades`() {
        val trades = (1..100).map {
            Trade(
                timestampNs = it * 1_000_000L,
                price = 100.0,
                quantity = 1.0,
                side = LiquidationSide.SHORT,
                exchange = "Binance"
            )
        } + listOf(
            Trade(1_000_000_000L, 100.0, 50.0, LiquidationSide.LONG, "OKX")
        )
        val result = TradeFlowAnalytics.analyze(trades)
        assertEquals(1, result.whaleTrades.size)
        assertTrue(result.whaleTrades.first().valueUsd >= 4000.0)
    }

    // ------------------------------------------------------------------
    // Derivative analytics
    // ------------------------------------------------------------------

    @Test
    fun `negative funding scores positive (squeeze fuel)`() {
        val result = DerivativeAnalytics.funding(-0.0005, null)
        assertTrue(result.score > 0.0)
    }

    @Test
    fun `extreme positive funding scores negative`() {
        val result = DerivativeAnalytics.funding(0.02, null)
        assertTrue(result.score < 0.0)
    }

    @Test
    fun `short liquidation imbalance scores positive`() {
        val window = LiquidationWindow(
            longNotionalUsd = 200_000.0,
            shortNotionalUsd = 800_000.0,
            longCount = 2,
            shortCount = 5
        )
        val result = DerivativeAnalytics.liquidation(window, 100.0, 99.5)
        assertTrue(result.imbalancePct > 0.0)
        assertTrue(result.score > 0.0)
    }

    // ------------------------------------------------------------------
    // Strategy engine
    // ------------------------------------------------------------------

    @Test
    fun `bullish score produces long with atr based sl tp`() {
        val strategy = StrategyEngine.generate(
            price = 100.0,
            totalScore = 40.0,
            direction = Direction.LONG,
            atrPct = 2.0,
            fundingRatePct = 0.0001,
            takerBuyPct = 60.0
        )
        assertEquals(Direction.LONG, strategy.side)
        assertTrue(strategy.stopLoss != null && strategy.stopLoss < 100.0)
        assertTrue(strategy.takeProfit != null && strategy.takeProfit > 100.0)
        assertTrue(strategy.leverage in 2..50)
        assertTrue(strategy.alerts.isNotEmpty())
    }

    @Test
    fun `bearish score produces short`() {
        val strategy = StrategyEngine.generate(
            price = 100.0,
            totalScore = -40.0,
            direction = Direction.SHORT,
            atrPct = 3.0,
            fundingRatePct = null,
            takerBuyPct = null
        )
        assertEquals(Direction.SHORT, strategy.side)
        assertTrue(strategy.stopLoss != null && strategy.stopLoss > 100.0)
        assertTrue(strategy.takeProfit != null && strategy.takeProfit < 100.0)
    }

    // ------------------------------------------------------------------
    // Calibration engine
    // ------------------------------------------------------------------

    @Test
    fun `evaluate long above band hits`() {
        val status = CalibrationEngine.evaluate(Direction.LONG, atrPct = 2.0, priceAtPrediction = 100.0, actualPrice = 102.0)
        assertEquals(SignalStatus.HIT, status)
    }

    @Test
    fun `evaluate long below band misses`() {
        val status = CalibrationEngine.evaluate(Direction.LONG, atrPct = 2.0, priceAtPrediction = 100.0, actualPrice = 100.2)
        assertEquals(SignalStatus.MISS, status)
    }

    @Test
    fun `flat band is atr relative`() {
        assertEquals(1.2, CalibrationEngine.flatBand(2.0), 1e-9)
        assertEquals(0.3, CalibrationEngine.flatBand(0.2), 1e-9)
        assertEquals(2.5, CalibrationEngine.flatBand(10.0), 1e-9)
    }
}
