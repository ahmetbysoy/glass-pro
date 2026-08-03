package com.glasspro.tracker.data.remote.adapter

import android.util.Log
import com.glasspro.tracker.core.model.Candle
import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.OrderBookLevel
import com.glasspro.tracker.core.model.Trade
import com.glasspro.tracker.core.math.Stats.safeDouble
import com.glasspro.tracker.core.math.Stats.safeLong
import com.glasspro.tracker.core.util.Deduplicator
import com.glasspro.tracker.data.remote.rest.RestClient
import com.glasspro.tracker.data.remote.ws.WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.json.JSONObject

/**
 * Binance USD-M futures adapter.
 *
 * Liquidation source (real, public, unauthenticated):
 *   - WSS `!forceOrder@arr` — all-market forced liquidation orders stream.
 *   - REST `fapi/v1/allForceOrders` — polled backup when the WSS is down.
 *
 * Market data (fapi.binance.com REST v1/v3):
 *   premiumIndex (mark price + last funding rate), 24h ticker,
 *   open interest + 5m history (OI change 1h), taker long/short ratio,
 *   klines, depth (base units), aggTrades.
 *
 * Quantities on USD-M futures are already expressed in base-asset units, so
 * no contract multiplier is applied for the order book / trades. Liquidation
 * notional = averagePrice x executedQty.
 */
class BinanceFuturesAdapter(
    private val restClient: RestClient,
    private val wsClientFactory: () -> WebSocketClient
) : ExchangeAdapter {

    override val exchangeName: String = "Binance"

    private val _health = MutableStateFlow(
        AdapterHealth(exchangeName, AdapterStatus.DOWN, 0, 0L, null)
    )
    override val health: StateFlow<AdapterHealth> = _health.asStateFlow()

    private val _liquidationEvents = MutableSharedFlow<LiquidationEvent>(extraBufferCapacity = 256)
    override val liquidationEvents: SharedFlow<LiquidationEvent> = _liquidationEvents.asSharedFlow()

    private val deduplicator = Deduplicator(capacity = 8192)
    private var wsClient: WebSocketClient? = null
    private var restPollJob: Job? = null
    private var liquidationsReceived = 0L
    private var lastMessageAtMs = 0L

    private var wsUrlIndex = 0

    private val wsUrls = listOf(
        "wss://fstream.binance.com/ws/!forceOrder@arr",
        "wss://fstream.binance.com/market/ws/!forceOrder@arr",
        "wss://stream.binancefuture.com/ws/!forceOrder@arr"
    )

    private val restBase = "https://fapi.binance.com"

    override fun start(scope: CoroutineScope) {
        connectWs(scope)
        restPollJob = scope.launch {
            while (isActive) {
                // Cross-check the REST force-order feed every 30 seconds.
                // The deduplicator removes overlaps with the WSS stream.
                pollForceOrders()
                delay(REST_POLL_INTERVAL_MS)
            }
        }
    }

    private fun connectWs(scope: CoroutineScope) {
        val client = wsClientFactory()
        wsClient = client
        client.start(
            url = wsUrls[wsUrlIndex % wsUrls.size],
            subscribePayload = null, // Binance forceOrder streams subscribe via URL
            protocolPingEveryMs = 0L, // Binance sends its own ping frames (auto-pong handled)
            staleAfterMs = 60_000L
        ) { raw ->
            handleForceOrderMessage(raw)
        }
        scope.launch {
            client.state.collect { state ->
                val status = when (state) {
                    com.glasspro.tracker.data.remote.ws.WsConnectionState.LIVE -> AdapterStatus.LIVE
                    com.glasspro.tracker.data.remote.ws.WsConnectionState.STALE ->
                        AdapterStatus.DEGRADED
                    else -> AdapterStatus.DOWN
                }
                _health.value = AdapterHealth(
                    exchange = exchangeName,
                    status = status,
                    liquidationsReceived = liquidationsReceived,
                    lastMessageAgeMs = if (lastMessageAtMs > 0) {
                        System.currentTimeMillis() - lastMessageAtMs
                    } else {
                        0L
                    },
                    lastError = if (status == AdapterStatus.DOWN) "WSS bağlantısı kurulamadı" else null
                )
            }
        }
    }

    private fun handleForceOrderMessage(raw: String) {
        lastMessageAtMs = System.currentTimeMillis()
        try {
            val json = JSONObject(raw)
            // The all-market stream pushes one force-order event object per
            // frame; tolerate a wrapped array for forward compatibility.
            val events = json.optJSONArray("data")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } ?: listOf(json)
            for (event in events) {
                val order = event.optJSONObject("o") ?: continue
                val status = order.optString("X", "")
                if (status != "FILLED") continue  // only executed liquidations matter
                val symbol = order.optString("s", "")
                val orderSide = order.optString("S", "")
                val avgPrice = safeDouble(order.optString("ap")) ?: 0.0
                val qty = safeDouble(order.optString("z")) ?: 0.0
                val tradeTimeMs = safeLong(order.optString("T")) ?: 0L
                if (symbol.isBlank() || avgPrice <= 0.0 || qty <= 0.0) continue

                val baseSymbol = SymbolNormalizer.toBase(symbol)
                val side = LiquidationSide.fromForceOrderSide(orderSide)
                val id = "BINANCE:F:$tradeTimeMs:$symbol:$qty:$avgPrice"
                if (!deduplicator.isNew(id)) continue

                val eventTimeMs = safeLong(event.optString("E")) ?: tradeTimeMs
                emitLiquidation(
                    LiquidationEvent(
                        id = id,
                        exchange = exchangeName,
                        symbol = baseSymbol,
                        side = side,
                        price = avgPrice,
                        quantity = qty,
                        notionalUsd = avgPrice * qty,
                        timestampNs = eventTimeMs * 1_000_000L,
                        sequence = null,
                        isSnapshot = false,
                        sourceChannel = "!forceOrder@arr"
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "forceOrder parse error: ${e.message}")
        }
    }

    private suspend fun pollForceOrders() {
        try {
            val array = restClient.getJsonArray("$restBase/fapi/v1/allForceOrders", mapOf("limit" to "50"))
                ?: return
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val status = o.optString("status", "")
                if (status != "FILLED") continue
                val symbol = o.optString("symbol", "")
                val orderSide = o.optString("side", "")
                val avgPrice = safeDouble(o.optString("averagePrice"))
                    ?: safeDouble(o.optString("price"))
                    ?: 0.0
                val qty = safeDouble(o.optString("executedQty"))
                    ?: safeDouble(o.optString("origQty"))
                    ?: 0.0
                val timeMs = safeLong(o.optString("time")) ?: 0L
                if (symbol.isBlank() || avgPrice <= 0.0 || qty <= 0.0) continue

                val baseSymbol = SymbolNormalizer.toBase(symbol)
                val side = LiquidationSide.fromForceOrderSide(orderSide)
                val id = "BINANCE:R:$timeMs:$symbol:$qty:$avgPrice"
                if (!deduplicator.isNew(id)) continue

                emitLiquidation(
                    LiquidationEvent(
                        id = id,
                        exchange = exchangeName,
                        symbol = baseSymbol,
                        side = side,
                        price = avgPrice,
                        quantity = qty,
                        notionalUsd = avgPrice * qty,
                        timestampNs = timeMs * 1_000_000L,
                        sequence = null,
                        isSnapshot = false,
                        sourceChannel = "allForceOrders"
                    )
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "pollForceOrders failed: ${e.message}")
        }
    }

    private fun emitLiquidation(event: LiquidationEvent) {
        liquidationsReceived++
        _health.value = _health.value.copy(liquidationsReceived = liquidationsReceived)
        // SharedFlow emission is safe from any thread via tryEmit on a buffered flow.
        _liquidationEvents.tryEmit(event)
    }

    override fun stop() {
        wsClient?.stop()
        restPollJob?.cancel()
        _health.value = _health.value.copy(status = AdapterStatus.DOWN)
    }

    override suspend fun fetchTicker(symbol: String): TickerData? {
        val flat = SymbolNormalizer.flat(symbol)
        val json = restClient.getJson("$restBase/fapi/v1/ticker/24hr", mapOf("symbol" to flat)) ?: return null
        return TickerData(
            last = safeDouble(json.optString("lastPrice")),
            high24h = safeDouble(json.optString("highPrice")),
            low24h = safeDouble(json.optString("lowPrice")),
            open24h = safeDouble(json.optString("openPrice")),
            quoteVolume24h = safeDouble(json.optString("quoteVolume"))
        )
    }

    override suspend fun fetchDerivativeData(symbol: String): DerivativeData? {
        val flat = SymbolNormalizer.flat(symbol)
        val premium = restClient.getJson("$restBase/fapi/v1/premiumIndex", mapOf("symbol" to flat))
        val oiNow = restClient.getJson("$restBase/fapi/v1/openInterest", mapOf("symbol" to flat))
        val oiHist = restClient.getJsonArray(
            "$restBase/futures/data/openInterestHist",
            mapOf("symbol" to flat, "period" to "5m", "limit" to "50")
        )
        val taker = restClient.getJsonArray(
            "$restBase/futures/data/takerlongshortRatio",
            mapOf("symbol" to flat, "period" to "5m", "limit" to "13")
        )

        val funding = premium?.optJSONArray("data")?.getJSONObject(0)?.optString("lastFundingRate")?.let {
            safeDouble(it)
        } ?: premium?.optString("lastFundingRate")?.let { safeDouble(it) }

        val oiBase = oiNow?.optString("openInterest")?.let { safeDouble(it) }
        val oiUsd = oiNow?.optString("openInterestValue")?.let { safeDouble(it) }

        var oiChange1h: Double? = null
        if (oiHist != null && oiHist.length() >= 13) {
            val a = oiHist.getJSONObject(0).optString("sumOpenInterest").let { safeDouble(it) }
            val b = oiHist.getJSONObject(12).optString("sumOpenInterest").let { safeDouble(it) }
            if (a != null && b != null && b > 0.0) oiChange1h = (a / b - 1.0) * 100.0
        }

        var takerBuyPct: Double? = null
        if (taker != null && taker.length() > 0) {
            var buySum = 0.0
            var sellSum = 0.0
            for (i in 0 until taker.length()) {
                val row = taker.getJSONObject(i)
                buySum += safeDouble(row.optString("buyVol")) ?: 0.0
                sellSum += safeDouble(row.optString("sellVol")) ?: 0.0
            }
            val total = buySum + sellSum
            if (total > 0.0) takerBuyPct = buySum / total * 100.0
        }

        if (funding == null && oiBase == null && oiUsd == null && oiChange1h == null && takerBuyPct == null) {
            return null
        }
        return DerivativeData(
            fundingRate = funding,
            openInterestBase = oiBase,
            openInterestUsd = oiUsd,
            oiChangePct1h = oiChange1h,
            takerBuyPct = takerBuyPct,
            lsRatio = null
        )
    }

    override suspend fun fetchOrderBook(symbol: String, limit: Int): OrderBookData? {
        val flat = SymbolNormalizer.flat(symbol)
        val json = restClient.getJson(
            "$restBase/fapi/v1/depth",
            mapOf("symbol" to flat, "limit" to limit.toString())
        ) ?: return null
        val bids = parseBookLevels(json.optJSONArray("bids"))
        val asks = parseBookLevels(json.optJSONArray("asks"))
        return OrderBookData(bids, asks)
    }

    private fun parseBookLevels(arr: org.json.JSONArray?): List<OrderBookLevel> {
        val levels = mutableListOf<OrderBookLevel>()
        if (arr == null) return levels
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val px = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            levels.add(OrderBookLevel(px, qty))
        }
        return levels
    }

    override suspend fun fetchTrades(symbol: String, limit: Int): List<Trade>? {
        val flat = SymbolNormalizer.flat(symbol)
        val arr = restClient.getJsonArray(
            "$restBase/fapi/v1/aggTrades",
            mapOf("symbol" to flat, "limit" to limit.toString())
        ) ?: return null
        val trades = mutableListOf<Trade>()
        for (i in 0 until arr.length()) {
            val row = arr.getJSONObject(i)
            val px = safeDouble(row.optString("p")) ?: continue
            val qty = safeDouble(row.optString("q")) ?: continue
            val timeMs = safeLong(row.optString("T")) ?: 0L
            val isBuyerMaker = row.optBoolean("m", false)
            trades.add(
                Trade(
                    timestampNs = timeMs * 1_000_000L,
                    price = px,
                    quantity = qty,
                    side = if (isBuyerMaker) LiquidationSide.SHORT else LiquidationSide.LONG,
                    exchange = exchangeName
                )
            )
        }
        return trades
    }

    override suspend fun fetchCandles(symbol: String, timeframe: String, limit: Int): List<Candle>? {
        val flat = SymbolNormalizer.flat(symbol)
        val interval = BinanceInterval.from(timeframe) ?: return null
        val arr = restClient.getJsonArray(
            "$restBase/fapi/v1/klines",
            mapOf("symbol" to flat, "interval" to interval, "limit" to limit.toString())
        ) ?: return null
        val candles = mutableListOf<Candle>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val openTime = row.optLong(0)
            val open = row.optString(1).toDoubleOrNull() ?: continue
            val high = row.optString(2).toDoubleOrNull() ?: continue
            val low = row.optString(3).toDoubleOrNull() ?: continue
            val close = row.optString(4).toDoubleOrNull() ?: continue
            val volume = row.optString(5).toDoubleOrNull() ?: 0.0
            candles.add(Candle(openTime, open, high, low, close, volume))
        }
        return candles
    }

    private object BinanceInterval {
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
        private const val TAG = "BinanceAdapter"
        private const val REST_POLL_INTERVAL_MS = 30_000L
    }
}
