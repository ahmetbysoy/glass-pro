package com.glasspro.tracker.data.remote.adapter

import android.util.Log
import com.glasspro.tracker.core.math.Stats.safeDouble
import com.glasspro.tracker.core.math.Stats.safeLong
import com.glasspro.tracker.core.model.Candle
import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.OrderBookLevel
import com.glasspro.tracker.core.model.Trade
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

/**
 * Gate.io v4 USDT-perpetual adapter (market data only). Gate publishes no
 * public liquidation feed; the adapter contributes futures order book, trades,
 * ticker and funding/OI to the consensus. Order book sizes are signed on
 * Gate: positive = bid, negative = ask.
 */
class GateFuturesAdapter(
    private val restClient: RestClient
) : ExchangeAdapter {

    override val exchangeName: String = "Gate"

    private val _health = MutableStateFlow(
        AdapterHealth(exchangeName, AdapterStatus.DOWN, 0, 0L, null)
    )
    override val health: StateFlow<AdapterHealth> = _health.asStateFlow()

    private val _liquidationEvents = MutableSharedFlow<LiquidationEvent>(0)
    override val liquidationEvents: SharedFlow<LiquidationEvent> = _liquidationEvents.asSharedFlow()

    private var pollJob: Job? = null
    private var lastSuccessAtMs = 0L

    private val restBase = "https://api.gateio.ws"

    override fun start(scope: CoroutineScope) {
        pollJob = scope.launch {
            while (isActive) {
                val json = restClient.getJson("$restBase/api/v4/futures/usdt/tickers", mapOf("contract" to "BTC_USDT"))
                if (json?.length() ?: 0 > 0) {
                    lastSuccessAtMs = System.currentTimeMillis()
                    _health.value = _health.value.copy(status = AdapterStatus.LIVE, lastError = null)
                } else {
                    val age = if (lastSuccessAtMs > 0) System.currentTimeMillis() - lastSuccessAtMs else Long.MAX_VALUE
                    _health.value = _health.value.copy(
                        status = if (age < STALE_MS) AdapterStatus.LIVE else AdapterStatus.DEGRADED,
                        lastError = if (age >= STALE_MS) "REST yanıtı yok" else null
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        _health.value = _health.value.copy(status = AdapterStatus.DOWN)
    }

    override suspend fun fetchTicker(symbol: String): TickerData? {
        val contract = SymbolNormalizer.gatePerp(symbol)
        val json = restClient.getJsonArray("$restBase/api/v4/futures/usdt/tickers", mapOf("contract" to contract))
            ?: return null
        val d = json.optJSONObject(0) ?: return null
        return TickerData(
            last = safeDouble(d.optString("last")),
            high24h = safeDouble(d.optString("high_24h")),
            low24h = safeDouble(d.optString("low_24h")),
            open24h = null,
            quoteVolume24h = safeDouble(d.optString("volume_24h_quote"))
        )
    }

    override suspend fun fetchDerivativeData(symbol: String): DerivativeData? {
        val contract = SymbolNormalizer.gatePerp(symbol)
        val json = restClient.getJson("$restBase/api/v4/futures/usdt/contracts/$contract") ?: return null
        val funding = safeDouble(json.optString("funding_rate"))
        val oiContracts = safeDouble(json.optString("open_interest"))
        val oiBase = oiContracts // Gate futures OI is reported in base units
        val price = safeDouble(json.optString("mark_price"))
        val oiUsd = if (oiBase != null && price != null) oiBase * price else null
        if (funding == null && oiBase == null) return null
        return DerivativeData(
            fundingRate = funding,
            openInterestBase = oiBase,
            openInterestUsd = oiUsd,
            oiChangePct1h = null,
            takerBuyPct = null,
            lsRatio = null
        )
    }

    override suspend fun fetchOrderBook(symbol: String, limit: Int): OrderBookData? {
        val contract = SymbolNormalizer.gatePerp(symbol)
        val json = restClient.getJson(
            "$restBase/api/v4/futures/usdt/order_book",
            mapOf("contract" to contract, "limit" to limit.toString())
        ) ?: return null
        val bids = parseLevels(json.optJSONArray("bids"))
        val asks = parseLevels(json.optJSONArray("asks"))
        return OrderBookData(bids, asks)
    }

    private fun parseLevels(arr: JSONArray?): List<OrderBookLevel> {
        val levels = mutableListOf<OrderBookLevel>()
        if (arr == null) return levels
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val px = safeDouble(obj.optString("p")) ?: continue
            val sz = safeDouble(obj.optString("s")) ?: continue
            levels.add(OrderBookLevel(px, kotlin.math.abs(sz)))
        }
        return levels
    }

    override suspend fun fetchTrades(symbol: String, limit: Int): List<Trade>? {
        val contract = SymbolNormalizer.gatePerp(symbol)
        val arr = restClient.getJsonArray(
            "$restBase/api/v4/futures/usdt/trades",
            mapOf("contract" to contract, "limit" to limit.toString())
        ) ?: return null
        val trades = mutableListOf<Trade>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val px = safeDouble(t.optString("price")) ?: continue
            val sz = safeDouble(t.optString("size")) ?: continue
            val timeMs = safeLong(t.optString("create_time")) ?: 0L
            val side = if (sz >= 0.0) LiquidationSide.LONG else LiquidationSide.SHORT
            trades.add(
                Trade(
                    timestampNs = timeMs * 1_000_000L,
                    price = px,
                    quantity = kotlin.math.abs(sz),
                    side = side,
                    exchange = exchangeName
                )
            )
        }
        return trades
    }

    override suspend fun fetchCandles(symbol: String, timeframe: String, limit: Int): List<Candle>? {
        val contract = SymbolNormalizer.gatePerp(symbol)
        val interval = GateInterval.from(timeframe) ?: return null
        val arr = restClient.getJsonArray(
            "$restBase/api/v4/futures/usdt/candlesticks",
            mapOf("contract" to contract, "interval" to interval, "limit" to limit.toString())
        ) ?: return null
        val candles = mutableListOf<Candle>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val ts = row.optLong(0)
            val volume = row.optString(1).toDoubleOrNull() ?: 0.0
            val close = row.optString(2).toDoubleOrNull() ?: continue
            val high = row.optString(3).toDoubleOrNull() ?: continue
            val low = row.optString(4).toDoubleOrNull() ?: continue
            val open = row.optString(5).toDoubleOrNull() ?: continue
            candles.add(Candle(ts * 1000L, open, high, low, close, volume))
        }
        return candles.sortedBy { it.timestampMs }
    }

    private object GateInterval {
        fun from(timeframe: String): String? = when (timeframe.lowercase()) {
            "1m" -> "1m"
            "5m" -> "5m"
            "15m" -> "15m"
            "1h" -> "1h"
            "4h" -> "4h"
            else -> null
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 15_000L
        private const val STALE_MS = 45_000L
    }
}
