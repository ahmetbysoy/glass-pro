package com.glasspro.tracker.data.remote

import android.util.Log
import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.model.Candle
import com.glasspro.tracker.core.model.Trade
import com.glasspro.tracker.data.remote.adapter.DerivativeData
import com.glasspro.tracker.data.remote.adapter.ExchangeAdapter
import com.glasspro.tracker.data.remote.adapter.OrderBookData
import com.glasspro.tracker.data.remote.adapter.TickerData
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Aggregated, normalized market data for one symbol pulled from every live
 * adapter. All values are real exchange data; the Quant Security Layer below
 * discards prices that deviate from the robust median, never invents values.
 */
data class MarketBundle(
    val symbol: String,
    val price: Double,
    val priceMin: Double,
    val priceMax: Double,
    val providerCount: Int,
    val dispersionPct: Double,
    val tickers: Map<String, TickerData>,
    val orderBooks: Map<String, OrderBookData>,
    val trades: List<Trade>,
    val candles: Map<String, List<Candle>>,
    val derivatives: Map<String, DerivativeData>,
    val fundingRatePct: Double?,
    val globalOiUsd: Double?,
    val oiChangePct1h: Double?,
    val takerBuyPct: Double?,
    val lsRatio: Double?,
    val conflicts: List<String>
)

/**
 * Pulls real market data from all adapters in parallel and produces the
 * cross-exchange consensus used by the analysis engines.
 *
 * Quant Security Layer (from the reference engine): the median of direct
 * venue prices is computed; any venue quoting more than 2% away from that
 * median is rejected as an outlier (robust MAD z-score check as backup).
 */
class MarketDataService(
    val adapters: List<ExchangeAdapter>
) {

    /**
     * Lightweight consensus price (ticker round-trips only). Used by the
     * verification loop where a full bundle would be wasteful.
     */
    suspend fun fetchConsensusPrice(symbol: String): Double? = coroutineScope {
        val tickerJobs = adapters.map { adapter ->
            async { adapter.exchangeName to adapter.fetchTicker(symbol) }
        }
        val prices = tickerJobs.mapNotNull { it.await() }
            .mapNotNull { (_, t) -> t.last }
        if (prices.isEmpty()) return@coroutineScope null
        val median = Stats.median(prices) ?: return@coroutineScope null
        val accepted = prices.filter { p ->
            val dev = kotlin.math.abs(p - median) / median * 100.0
            val z = Stats.robustZScore(p, prices)
            dev <= MAX_DEVIATION_PCT && (z == null || z <= 3.0)
        }
        if (accepted.isEmpty()) return@coroutineScope null
        Stats.median(accepted)
    }

    suspend fun fetchBundle(symbol: String): MarketBundle? = coroutineScope {
        val tickerJobs = adapters.map { adapter ->
            async { adapter.exchangeName to adapter.fetchTicker(symbol) }
        }
        val tickers = tickerJobs.mapNotNull { it.await() }.toMap()

        val prices = tickers.mapNotNull { (_, t) -> t.last }
        if (prices.isEmpty()) {
            Log.w(TAG, "No real price available for $symbol from any venue")
            return@coroutineScope null
        }

        // --- Quant Security Layer ---
        val median = Stats.median(prices)!!
        val accepted = mutableMapOf<String, TickerData>()
        val conflicts = mutableListOf<String>()
        for ((name, t) in tickers) {
            val price = t.last ?: continue
            val deviation = kotlin.math.abs(price - median) / median * 100.0
            val z = Stats.robustZScore(price, prices)
            if (deviation <= MAX_DEVIATION_PCT && (z == null || z <= 3.0)) {
                accepted[name] = t
            } else {
                conflicts.add("SAPKIN FİYAT $name (reddedildi - sapma %${"%.1f".format(deviation)}, z=${"%.1f".format(z ?: 0.0)})")
                Log.w(TAG, "Quant Security Layer rejected $name price $price (dev ${"%.2f".format(deviation)}%, z=${"%.1f".format(z ?: 0.0)})")
            }
        }

        val acceptedPrices = accepted.mapNotNull { (_, t) -> t.last }
        if (acceptedPrices.isEmpty()) {
            Log.w(TAG, "All venue prices rejected for $symbol; no consensus")
            return@coroutineScope null
        }

        val consensus = Stats.median(acceptedPrices)!!
        val dispersion = Stats.mean(acceptedPrices)?.let { m ->
            if (m > 0.0) ((acceptedPrices.maxOrNull()!! - acceptedPrices.minOrNull()!!) / m) * 100.0 else 0.0
        } ?: 0.0

        // --- Parallel secondary fetches ---
        val bookJobs = adapters.map { adapter ->
            async { adapter.exchangeName to adapter.fetchOrderBook(symbol, 200) }
        }
        val books = bookJobs.mapNotNull { it.await() }.toMap()

        val tradeJobs = adapters.map { adapter ->
            async { adapter.exchangeName to adapter.fetchTrades(symbol, 500) }
        }
        val tradesByVenue = tradeJobs.mapNotNull { it.await() }.toMap()
        val trades = tradesByVenue.values.flatten().sortedBy { it.timestampNs }

        val candleJobs = TFs.map { tf ->
            async {
                // OKX first, then Binance as fallback; both are always available
                // for the watchlist instruments.
                val okx = adapters.firstOrNull { it.exchangeName == "OKX" }
                    ?.fetchCandles(symbol, tf, 300)
                val binance = adapters.firstOrNull { it.exchangeName == "Binance" }
                    ?.fetchCandles(symbol, tf, 300)
                tf to (okx ?: binance ?: emptyList())
            }
        }
        val candles = candleJobs.mapNotNull { it.await() }.toMap()

        val derivJobs = adapters.map { adapter ->
            async { adapter.exchangeName to adapter.fetchDerivativeData(symbol) }
        }
        val derivatives = derivJobs.mapNotNull { it.await() }.toMap()

        val fundingRates = derivatives.mapNotNull { (_, d) -> d.fundingRate?.let { it } }
        val fundingRatePct = Stats.mean(fundingRates)?.let { it * 100.0 }

        val oiUsdValues = derivatives.mapNotNull { (_, d) -> d.openInterestUsd }
        val globalOiUsd = oiUsdValues.takeIf { it.isNotEmpty() }?.sum()

        val oiChange1h = derivatives.values.firstNotNullOfOrNull { it.oiChangePct1h }
        val takerBuyPct = derivatives.values.firstNotNullOfOrNull { it.takerBuyPct }
        val lsRatio = derivatives.values.firstNotNullOfOrNull { it.lsRatio }

        MarketBundle(
            symbol = symbol,
            price = consensus,
            priceMin = acceptedPrices.minOrNull() ?: consensus,
            priceMax = acceptedPrices.maxOrNull() ?: consensus,
            providerCount = acceptedPrices.size,
            dispersionPct = dispersion,
            tickers = accepted,
            orderBooks = books,
            trades = trades,
            candles = candles,
            derivatives = derivatives,
            fundingRatePct = fundingRatePct,
            globalOiUsd = globalOiUsd,
            oiChangePct1h = oiChange1h,
            takerBuyPct = takerBuyPct,
            lsRatio = lsRatio,
            conflicts = conflicts
        )
    }

    companion object {
        private const val TAG = "MarketDataService"
        private const val MAX_DEVIATION_PCT = 2.0
        val TFs = listOf("5m", "15m", "1h", "4h")
    }
}
