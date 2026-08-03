package com.glasspro.tracker.data.remote.adapter

import android.util.Log
import com.glasspro.tracker.core.math.Stats.safeDouble
import com.glasspro.tracker.core.math.Stats.safeLong
import com.glasspro.tracker.core.model.Candle
import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.OrderBookLevel
import com.glasspro.tracker.core.model.Trade
import com.glasspro.tracker.core.util.Deduplicator
import com.glasspro.tracker.data.remote.rest.RestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Bybit v5 linear perpetual adapter.
 *
 * Bybit does not expose a public liquidation WebSocket in v5; the REST
 * endpoint `v5/market/liquidation` is polled instead. All other market data
 * comes from the v5 market endpoints.
 *
 * Sizes are reported in contracts; the [ContractMultipliers] table converts
 * contracts to base-asset units so notional values are correct per symbol.
 */
class BybitAdapter(
    private val restClient: RestClient,
    private val watchlist: () -> List<String>
) : ExchangeAdapter {

    override val exchangeName: String = "Bybit"

    private val _health = MutableStateFlow(
        AdapterHealth(exchangeName, AdapterStatus.DOWN, 0, 0L, null)
    )
    override val health: StateFlow<AdapterHealth> = _health.asStateFlow()

    private val _liquidationEvents = MutableSharedFlow<LiquidationEvent>(extraBufferCapacity = 256)
    override val liquidationEvents: SharedFlow<LiquidationEvent> = _liquidationEvents.asSharedFlow()

    private val deduplicator = Deduplicator(capacity = 8192)
    private var pollJob: Job? = null
    private var liquidationsReceived = 0L
    private var lastSuccessAtMs = 0L

    private val restBase = "https://api.bybit.com"

    override fun start(scope: CoroutineScope) {
        pollJob = scope.launch {
            while (isActive) {
                pollLiquidations()
                pollHealthCheck()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollLiquidations() {
        for (symbol in watchlist()) {
            try {
                val flat = SymbolNormalizer.flat(symbol)
                val json = restClient.getJson(
                    "$restBase/v5/market/liquidation",
                    mapOf("category" to "linear", "symbol" to flat, "limit" to "100")
                ) ?: continue
                val list = json.optJSONObject("result")?.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val d = list.getJSONObject(i)
                    parseLiquidation(symbol, d)
                }
            } catch (e: Exception) {
                Log.d(TAG, "liquidation poll error for $symbol: ${e.message}")
            }
        }
    }

    private fun parseLiquidation(symbol: String, d: JSONObject) {
        val price = safeDouble(d.optString("price")) ?: return
        val sizeContracts = safeDouble(d.optString("size")) ?: return
        val timeMs = safeLong(d.optString("updatedTime")) ?: 0L
        if (price <= 0.0 || sizeContracts <= 0.0) return

        val multiplier = ContractMultipliers.bybit(symbol)
        val baseQty = sizeContracts * multiplier
        val side = LiquidationSide.fromForceOrderSide(d.optString("side", ""))
        val id = "BYBIT:R:$timeMs:$symbol:$sizeContracts:$price"
        if (!deduplicator.isNew(id)) return

        liquidationsReceived++
        _health.value = _health.value.copy(liquidationsReceived = liquidationsReceived)
        _liquidationEvents.tryEmit(
            LiquidationEvent(
                id = id,
                exchange = exchangeName,
                symbol = symbol,
                side = side,
                price = price,
                quantity = baseQty,
                notionalUsd = baseQty * price,
                timestampNs = timeMs * 1_000_000L,
                sequence = null,
                isSnapshot = false,
                sourceChannel = "v5/market/liquidation"
            )
        )
    }

    private suspend fun pollHealthCheck() {
        val json = restClient.getJson(
            "$restBase/v5/market/tickers",
            mapOf("category" to "linear", "symbol" to "BTCUSDT")
        )
        val live = json?.optJSONObject("result")?.optJSONArray("list")?.length() ?: 0
        if (live > 0) {
            lastSuccessAtMs = System.currentTimeMillis()
            _health.value = _health.value.copy(
                status = AdapterStatus.LIVE,
                lastError = null
            )
        } else {
            val age = if (lastSuccessAtMs > 0) System.currentTimeMillis() - lastSuccessAtMs else Long.MAX_VALUE
            _health.value = _health.value.copy(
                status = if (age < STALE_MS) AdapterStatus.LIVE else AdapterStatus.DEGRADED,
                lastError = if (age >= STALE_MS) "REST yanıtı yok" else null
            )
        }
    }

    override fun stop() {
        pollJob?.cancel()
        _health.value = _health.value.copy(status = AdapterStatus.DOWN)
    }

    override suspend fun fetchTicker(symbol: String): TickerData? {
        val flat = SymbolNormalizer.flat(symbol)
        val json = restClient.getJson(
            "$restBase/v5/market/tickers",
            mapOf("category" to "linear", "symbol" to flat)
        ) ?: return null
        val d = json.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0) ?: return null
        return TickerData(
            last = safeDouble(d.optString("lastPrice")),
            high24h = safeDouble(d.optString("highPrice24h")),
            low24h = safeDouble(d.optString("lowPrice24h")),
            open24h = safeDouble(d.optString("openPrice24h")),
            quoteVolume24h = safeDouble(d.optString("turnover24h"))
        )
    }

    override suspend fun fetchDerivativeData(symbol: String): DerivativeData? {
        val flat = SymbolNormalizer.flat(symbol)
        val funding = restClient.getJson(
            "$restBase/v5/market/funding/history",
            mapOf("category" to "linear", "symbol" to flat, "limit" to "30")
        )
        val oiHist = restClient.getJson(
            "$restBase/v5/market/open-interest",
            mapOf("category" to "linear", "symbol" to flat, "intervalTime" to "5min", "limit" to "50")
        )
        val ratio = restClient.getJson(
            "$restBase/v5/market/account-ratio",
            mapOf("category" to "linear", "symbol" to flat, "period" to "5min", "limit" to "10")
        )

        val fundingRate = funding?.optJSONObject("result")?.optJSONArray("list")
            ?.optJSONObject(0)?.optString("fundingRate")?.let { safeDouble(it) }

        val oiList = oiHist?.optJSONObject("result")?.optJSONArray("list")
        val oiContracts = oiList?.optJSONObject(0)?.optString("openInterest")?.let { safeDouble(it) }
        var oiChange1h: Double? = null
        if (oiList != null && oiList.length() >= 13) {
            val a = oiList.getJSONObject(0).optString("openInterest").let { safeDouble(it) }
            val b = oiList.getJSONObject(12).optString("openInterest").let { safeDouble(it) }
            if (a != null && b != null && b > 0.0) oiChange1h = (a / b - 1.0) * 100.0
        }
        val multiplier = ContractMultipliers.bybit(symbol)
        val price = fetchTicker(symbol)?.last
        val oiBase = oiContracts?.let { it * multiplier }
        val oiUsd = if (oiBase != null && price != null) oiBase * price else null

        var lsRatio: Double? = null
        val ratioList = ratio?.optJSONObject("result")?.optJSONArray("list")
        if (ratioList != null && ratioList.length() > 0) {
            val buyRatio = safeDouble(ratioList.getJSONObject(0).optString("buyRatio"))
            val sellRatio = safeDouble(ratioList.getJSONObject(0).optString("sellRatio"))
            if (buyRatio != null && sellRatio != null && sellRatio > 0.0) {
                lsRatio = buyRatio / sellRatio
            }
        }

        if (fundingRate == null && oiBase == null && oiChange1h == null && lsRatio == null) return null
        return DerivativeData(
            fundingRate = fundingRate,
            openInterestBase = oiBase,
            openInterestUsd = oiUsd,
            oiChangePct1h = oiChange1h,
            takerBuyPct = null, // covered by Binance takerlongshortRatio
            lsRatio = lsRatio
        )
    }

    override suspend fun fetchOrderBook(symbol: String, limit: Int): OrderBookData? {
        val flat = SymbolNormalizer.flat(symbol)
        val json = restClient.getJson(
            "$restBase/v5/market/orderbook",
            mapOf("category" to "linear", "symbol" to flat, "limit" to limit.toString())
        ) ?: return null
        val result = json.optJSONObject("result") ?: return null
        val multiplier = ContractMultipliers.bybit(symbol)
        val bids = parseLevels(result.optJSONArray("b"), multiplier)
        val asks = parseLevels(result.optJSONArray("a"), multiplier)
        return OrderBookData(bids, asks)
    }

    private fun parseLevels(arr: JSONArray?, multiplier: Double): List<OrderBookLevel> {
        val levels = mutableListOf<OrderBookLevel>()
        if (arr == null) return levels
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val px = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            levels.add(OrderBookLevel(px, qty * multiplier))
        }
        return levels
    }

    override suspend fun fetchTrades(symbol: String, limit: Int): List<Trade>? {
        val flat = SymbolNormalizer.flat(symbol)
        val json = restClient.getJson(
            "$restBase/v5/market/recent-trade",
            mapOf("category" to "linear", "symbol" to flat, "limit" to limit.toString())
        ) ?: return null
        val list = json.optJSONObject("result")?.optJSONArray("list") ?: return null
        val multiplier = ContractMultipliers.bybit(symbol)
        val trades = mutableListOf<Trade>()
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i)
            val px = safeDouble(t.optString("price")) ?: continue
            val sz = safeDouble(t.optString("size")) ?: continue
            val timeNs = safeLong(t.optString("time")) ?: 0L
            val side = if (t.optString("side", "").lowercase(Locale.US) == "buy") {
                LiquidationSide.LONG
            } else {
                LiquidationSide.SHORT
            }
            trades.add(
                Trade(
                    timestampNs = timeNs,
                    price = px,
                    quantity = sz * multiplier,
                    side = side,
                    exchange = exchangeName
                )
            )
        }
        return trades
    }

    override suspend fun fetchCandles(symbol: String, timeframe: String, limit: Int): List<Candle>? {
        val flat = SymbolNormalizer.flat(symbol)
        val interval = BybitInterval.from(timeframe) ?: return null
        val json = restClient.getJson(
            "$restBase/v5/market/kline",
            mapOf("category" to "linear", "symbol" to flat, "interval" to interval, "limit" to limit.toString())
        ) ?: return null
        val list = json.optJSONObject("result")?.optJSONArray("list") ?: return null
        val candles = mutableListOf<Candle>()
        for (i in 0 until list.length()) {
            val row = list.getJSONObject(i)
            val ts = safeLong(row.optString("startTime")) ?: continue
            val open = safeDouble(row.optString("openPrice")) ?: continue
            val high = safeDouble(row.optString("highPrice")) ?: continue
            val low = safeDouble(row.optString("lowPrice")) ?: continue
            val close = safeDouble(row.optString("closePrice")) ?: continue
            val volume = safeDouble(row.optString("volume")) ?: 0.0
            candles.add(Candle(ts, open, high, low, close, volume))
        }
        return candles.sortedBy { it.timestampMs }
    }

    private object BybitInterval {
        fun from(timeframe: String): String? = when (timeframe.lowercase()) {
            "1m" -> "1"
            "5m" -> "5"
            "15m" -> "15"
            "1h" -> "60"
            "4h" -> "240"
            else -> null
        }
    }

    companion object {
        private const val TAG = "BybitAdapter"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val STALE_MS = 30_000L
    }
}
