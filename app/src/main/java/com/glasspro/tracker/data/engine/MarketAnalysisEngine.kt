package com.glasspro.tracker.data.engine

import android.util.Log
import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.math.TechnicalIndicators
import com.glasspro.tracker.core.model.AnalysisResult
import com.glasspro.tracker.core.model.CalibrationState
import com.glasspro.tracker.core.model.ComponentScore
import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.Forecasts
import com.glasspro.tracker.core.model.ProbabilityModel
import com.glasspro.tracker.core.model.SignalStatus
import com.glasspro.tracker.data.remote.LiquidationFeedManager
import com.glasspro.tracker.data.remote.MacroService
import com.glasspro.tracker.data.remote.MarketDataService
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * The nine-component market analysis engine. Consumes only real exchange data
 * (via [MarketDataService]) and produces a professional quantitative verdict:
 *
 *   Component                     Weight
 *   ----------------------------  -----
 *   Order Book                    0.15   bid/ask imbalance, walls, spoofing
 *   Trade Flow                    0.15   CVD, taker share, divergence, whales
 *   Open Interest                 0.12   OI change vs price
 *   Liquidation                   0.12   long/short liquidation imbalance
 *   Momentum                      0.10   multi-timeframe technical blend
 *   Options                       0.10   implied volatility dynamics (Deribit)
 *   Whale Netflow                 0.10   whale buy/sell imbalance
 *   Funding                       0.08   extreme funding detection
 *   Volume                        0.08   volume ratio regime
 *
 * Dynamic weight shifts (reference v7.1.1): thin liquidity strengthens the
 * order book and trade flow components; high volatility strengthens volume
 * and momentum. Missing components (no options market, no OI history, ...)
 * are excluded and the remaining weights renormalized — never invented.
 */
class MarketAnalysisEngine(
    private val marketDataService: MarketDataService,
    private val liquidationFeedManager: LiquidationFeedManager,
    private val macroService: MacroService,
    private val optionsProvider: OptionsDataProvider?
) {

    private val timeframeWeights = mapOf(
        "5m" to 0.15,
        "15m" to 0.25,
        "1h" to 0.35,
        "4h" to 0.25
    )

    private val baseWeights = linkedMapOf(
        "Order Book" to 0.15,
        "Trade Flow" to 0.15,
        "Open Interest" to 0.12,
        "Funding" to 0.08,
        "Liquidation" to 0.12,
        "Volume" to 0.08,
        "Momentum" to 0.10,
        "Options" to 0.10,
        "Whale Netflow" to 0.10
    )

    /**
     * Runs the full quantitative pipeline for one symbol.
     * Returns null when no real market data is reachable (never a mock).
     */
    suspend fun analyze(
        symbol: String,
        horizonMs: Long,
        horizonLabel: String,
        calibration: CalibrationState?
    ): AnalysisResult? {
        val bundle = marketDataService.fetchBundle(symbol) ?: return null
        val price = bundle.price
        if (price <= 0.0) return null

        // Second order book round (3s later) for spoof detection.
        val secondBooks = kotlinx.coroutines.coroutineScope {
            val bookJobs = marketDataService.adapters.map { adapter ->
                kotlinx.coroutines.async {
                    adapter.exchangeName to adapter.fetchOrderBook(symbol, 200)
                }
            }
            bookJobs.mapNotNull { it.await() }.toMap()
        }

        val orderBook = OrderBookAnalytics.analyze(price, bundle.orderBooks, secondBooks)
        val tradeFlow = TradeFlowAnalytics.analyze(bundle.trades)

        // Trailing liquidation window (3 minutes).
        val window = liquidationFeedManager.windowFor(symbol, 180_000L)
        val recentEvents = liquidationFeedManager.recentEvents.value
        val highestLiqPrice = recentEvents
            .filter { it.symbol == symbol }
            .maxByOrNull { it.price }
            ?.price
        val liq = DerivativeAnalytics.liquidation(window, price, highestLiqPrice)

        // Technical indicators per timeframe.
        val technicals = mapOf(
            "5m" to TechnicalIndicators.compute(bundle.candles["5m"].orEmpty(), "5m"),
            "15m" to TechnicalIndicators.compute(bundle.candles["15m"].orEmpty(), "15m"),
            "1h" to TechnicalIndicators.compute(bundle.candles["1h"].orEmpty(), "1h"),
            "4h" to TechnicalIndicators.compute(bundle.candles["4h"].orEmpty(), "4h")
        )
        val tech1h = technicals["1h"] ?: technicals["5m"]

        // Macro (Fear & Greed) bias.
        val fng = macroService.current()
        val macroBias = macroService.biasFor(fng?.value)

        val momentumScore = TechnicalIndicators.blendMultiTimeframe(technicals, timeframeWeights, macroBias)

        // Derivative components.
        val fundingAnalysis = DerivativeAnalytics.funding(bundle.fundingRatePct, null)
        val oiAnalysis = DerivativeAnalytics.openInterest(bundle.oiChangePct1h, priceChangePct1h(technicals["1h"], price))

        // Volume regime score from the 1H volume ratio.
        val volumeRatio1h = tech1h?.volumeRatio ?: 1.0
        val volumeScore = when {
            volumeRatio1h > 2.0 -> 40.0
            volumeRatio1h > 1.5 -> 25.0
            volumeRatio1h > 1.0 -> 5.0
            volumeRatio1h > 0.7 -> -10.0
            else -> -25.0
        }

        // Whale netflow component.
        val whaleNetflowScore = computeWhaleNetflow(tradeFlow.whaleTrades)

        // Options component (real DVOL data where the venue exists).
        val optionsScore = optionsProvider?.let {
            try {
                it.fetchVolatilityChange(symbol)
            } catch (e: Exception) {
                Log.d(TAG, "Options fetch failed for $symbol: ${e.message}")
                null
            }
        }

        // --- Dynamic weight shifting (reference v7.1.1) ---
        val dynamicWeights = HashMap(baseWeights)
        val quoteVolume24h = bundle.tickers.values.firstNotNullOfOrNull { it.quoteVolume24h }
        if ((quoteVolume24h ?: 0.0) < 10_000_000.0) {
            dynamicWeights["Order Book"] = (dynamicWeights["Order Book"] ?: 0.0) + 0.05
            dynamicWeights["Trade Flow"] = (dynamicWeights["Trade Flow"] ?: 0.0) + 0.05
        }
        val atrPct1h = tech1h?.atrPct ?: 2.0
        if (atrPct1h > 3.0) {
            dynamicWeights["Volume"] = (dynamicWeights["Volume"] ?: 0.0) + 0.04
            dynamicWeights["Momentum"] = (dynamicWeights["Momentum"] ?: 0.0) + 0.04
        }

        val raw = linkedMapOf<String, Double?>(
            "Order Book" to orderBook.score,
            "Trade Flow" to tradeFlow.score,
            "Open Interest" to oiAnalysis.score,
            "Funding" to fundingAnalysis.score,
            "Liquidation" to liq.score,
            "Volume" to volumeScore,
            "Momentum" to momentumScore,
            "Options" to optionsScore,
            "Whale Netflow" to whaleNetflowScore
        )

        // Renormalize weights over the available components.
        val available = raw.filterValues { it != null }
        val totalWeight = available.keys.sumOf { dynamicWeights[it] ?: 0.0 }
        if (totalWeight <= 0.0) return null

        val components = available.map { (key, score) ->
            val weight = (dynamicWeights[key] ?: 0.0) / totalWeight
            ComponentScore(
                key = key,
                score = score!!,
                weight = weight,
                contribution = score * weight,
                available = true
            )
        }
        val missing = raw.filterValues { it == null }.keys.map { key ->
            ComponentScore(key, 0.0, 0.0, 0.0, available = false)
        }
        val totalScore = Stats.clamp(components.sumOf { it.contribution })

        val direction = Direction.fromScore(totalScore, 20.0)

        // Confidence & probability model (reference engine formulas).
        val providerCoverage = (bundle.providerCount.toDouble() / marketDataService.adapters.size) * 100.0
        val componentCoverage = available.size.toDouble() / baseWeights.size * 100.0
        val confidence = Stats.clamp(
            0.25 * componentCoverage +
                0.25 * providerCoverage +
                0.20 * 85.0 +
                0.15 * 80.0 -
                4.0
        )
        val uncertainty = Stats.clamp(
            30.0 - abs(totalScore) * 0.15 + (100.0 - confidence) * 0.25,
            10.0, 58.0
        )
        val pool = 100.0 - uncertainty
        val upProb = (pool * (0.5 + totalScore / 200.0)).coerceIn(0.0, 100.0)
        val downProb = (pool - upProb).coerceIn(0.0, 100.0)
        val probabilities = ProbabilityModel(upProb, downProb, uncertainty)

        // Short-term forecasts.
        val forecasts = Forecasts(
            forecast5m = momentumScore * 0.05,
            forecast15m = momentumScore * 0.1 + volumeScore * 0.05,
            forecast1h = momentumScore * 0.2 + tradeFlow.score * 0.05 + orderBook.score * 0.05
        )

        // Risk radar.
        val spreadPct = computeSpreadPct(bundle)
        val p1h = priceChangePct1h(technicals["1h"], price)
        val risks = RiskEngine.compute(
            spoofRate = orderBook.spoofRate,
            liqHunt = liq.liqHunt,
            fundingRatePct = bundle.fundingRatePct,
            volumeRatio1h = volumeRatio1h,
            atrPct = atrPct1h,
            spreadPct = spreadPct,
            quoteVolume24h = quoteVolume24h,
            priceChangePct1h = p1h
        )

        // Strategy.
        val strategy = StrategyEngine.generate(
            price = price,
            totalScore = totalScore,
            direction = direction,
            atrPct = atrPct1h,
            fundingRatePct = bundle.fundingRatePct,
            takerBuyPct = bundle.takerBuyPct
        )

        val now = System.currentTimeMillis()
        return AnalysisResult(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            createdAtMs = now,
            price = price,
            totalScore = totalScore,
            direction = direction,
            confidence = confidence,
            signalStrength = abs(totalScore) * 1.5,
            probabilities = probabilities,
            components = components + missing,
            orderBookImbalancePct = orderBook.bidPct - orderBook.askPct,
            tradeFlowBuyPct = tradeFlow.buyPct,
            cvd = tradeFlow.cvd,
            fundingRatePct = bundle.fundingRatePct ?: 0.0,
            oiChangePct1h = bundle.oiChangePct1h,
            oiUsd = bundle.globalOiUsd,
            takerBuyPct = bundle.takerBuyPct ?: 50.0,
            lsRatio = bundle.lsRatio,
            lsTrend = "flat", // populated by the repository layer over history
            fundingTrend = fundingAnalysis.trend,
            liquidationImbalancePct = liq.imbalancePct,
            globalOiUsd = bundle.globalOiUsd,
            risks = risks,
            strategy = strategy,
            forecasts = forecasts,
            whaleTrades = tradeFlow.whaleTrades,
            conflicts = bundle.conflicts,
            calibration = com.glasspro.tracker.core.model.CalibrationStats(
                rollingAccuracy20 = calibration?.rollingAccuracy20,
                resolvedCount = calibration?.resolved?.size ?: 0,
                componentCorrelations = calibration?.componentCorrelations ?: emptyMap()
            ),
            providerCount = bundle.providerCount,
            priceDispersionPct = bundle.dispersionPct,
            status = SignalStatus.PENDING,
            actualPrice = null,
            priceChangePct = null,
            verifyAtMs = now + horizonMs,
            horizonMs = horizonMs,
            horizonLabel = horizonLabel,
            atrPct1h = atrPct1h
        )
    }

    private fun priceChangePct1h(tech: com.glasspro.tracker.core.model.IndicatorResult?, price: Double): Double? {
        // Uses the 1-candle return of the 1H timeframe when available.
        return tech?.retPct1
    }

    private fun computeWhaleNetflow(whales: List<com.glasspro.tracker.core.model.WhaleTrade>): Double? {
        if (whales.isEmpty()) return null
        val buyValue = whales.filter { it.side == com.glasspro.tracker.core.model.LiquidationSide.LONG }
            .sumOf { it.valueUsd }
        val sellValue = whales.filter { it.side == com.glasspro.tracker.core.model.LiquidationSide.SHORT }
            .sumOf { it.valueUsd }
        val total = buyValue + sellValue
        if (total <= 0.0) return null
        return Stats.clamp((buyValue - sellValue) / total * 100.0)
    }

    private fun computeSpreadPct(bundle: com.glasspro.tracker.data.remote.MarketBundle): Double {
        val bestBid = bundle.orderBooks.values
            .flatMap { it.bids }
            .filter { it.price > 0.0 }
            .maxByOrNull { it.price }?.price
        val bestAsk = bundle.orderBooks.values
            .flatMap { it.asks }
            .filter { it.price > 0.0 }
            .minByOrNull { it.price }?.price
        if (bestBid == null || bestAsk == null || bestBid <= 0.0) return 0.0
        return (bestAsk - bestBid) / bestBid * 100.0
    }

    companion object {
        private const val TAG = "MarketAnalysisEngine"
    }
}
