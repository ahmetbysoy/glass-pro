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
import com.glasspro.tracker.data.remote.ws.WebSocketClient
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
import java.util.concurrent.ConcurrentHashMap

/**
 * OKX v5 adapter.
 *
 * Liquidation source (real, public, unauthenticated):
 *   - WSS channel `liquidation-orders` on instType SWAP per uly.
 *   - REST `api/v5/public/liquidation-orders` — polled backup.
 *
 * Market data: ticker, funding-rate, open-interest, instruments (live ctVal),
 * candles, books, trades and the Rubik long/short account ratio.
 *
 * OKX reports sizes in contracts; every quantity is multiplied by the live
 * `ctVal` fetched from the instruments endpoint (cached per symbol).
 */
class OkxAdapter(
    private val restClient: RestClient,
    private val wsClientFactory: () -> WebSocketClient,
    private val watchlist: () -> List<String>
) : ExchangeAdapter {

    override val exchangeName: String = "OKX"

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

    private val ctValCache = ConcurrentHashMap<String, CtValEntry>()

    private data class CtValEntry(val multiplier: Double, val cachedAtMs: Long)

    private val wsUrl = "wss://ws.okx.com:8443/ws/v5/public"
    private val restBase = "https://www.okx.com"

    override fun start(scope: CoroutineScope) {
        val client = wsClientFactory()
        wsClient = client
        client.start(
            url = wsUrl,
            subscribePayload = buildSubscriptionPayload(),
            protocolPingEveryMs = 25_000L,
            protocolPingPayload = "ping",
            staleAfterMs = 60_000L
        ) { raw -> handleWsMessage(raw) }
        scope.launch {
            client.state.collect { state ->
                val status = when (state) {
                    com.glasspro.tracker.data.remote.ws.WsConnectionState.LIVE -> AdapterStatus.LIVE
                    com.glasspro.tracker.data.remote.ws.WsConnectionState.STALE -> AdapterStatus.DEGRADED
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
        restPollJob = scope.launch {
            while (isActive) {
                pollLiquidations()
                delay(REST_POLL_INTERVAL_MS)
            }
        }
    }

    /** Subscribes the liquidation-orders channel for every watchlist uly. */
    private fun buildSubscriptionPayload(): String {
        val args = JSONArray()
        for (symbol in watchlist()) {
            args.put(
                JSONObject()
                    .put("channel", "liquidation-orders")
                    .put("instType", "SWAP")
                    .put("uly", SymbolNormalizer.okxUly(symbol))
            )
        }
        return JSONObject().put("op", "subscribe").put("args", args).toString()
    }

    private fun handleWsMessage(raw: String) {
        lastMessageAtMs = System.currentTimeMillis()
        try {
            val json = JSONObject(raw)
            val data = json.optJSONArray("data") ?: return
            val channel = json.optJSONObject("arg")?.optString("channel", "") ?: ""
            if (channel != "liquidation-orders") return
            for (i in 0 until data.length()) {
                val d = data.getJSONObject(i)
                parseLiquidationOrder(d, isWs = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "liquidation WSS parse error: ${e.message}")
        }
    }

    private suspend fun pollLiquidations() {
        for (symbol in watchlist()) {
            try {
                val uly = SymbolNormalizer.okxUly(symbol)
                val json = restClient.getJson(
                    "$restBase/api/v5/public/liquidation-orders",
                    mapOf("instType" to "SWAP", "uly" to uly, "state" to "filled", "limit" to "100")
                ) ?: continue
                val data = json.optJSONArray("data") ?: continue
                for (i in 0 until data.length()) {
                    parseLiquidationOrder(data.getJSONObject(i), isWs = false)
                }
            } catch (e: Exception) {
                Log.d(TAG, "OKX liquidation poll error for $symbol: ${e.message}")
            }
        }
    }

    private fun parseLiquidationOrder(d: JSONObject, isWs: Boolean) {
        val instId = d.optString("instId", "")
        if (instId.isBlank()) return
        val baseSymbol = SymbolNormalizer.toBase(instId)
        val szContracts = safeDouble(d.optString("sz")) ?: return
        val price = safeDouble(d.optString("tdPx"))
            ?: safeDouble(d.optString("bkPx"))
            ?: return
        val tsMs = safeLong(d.optString("ts")) ?: 0L
        if (szContracts <= 0.0 || price <= 0.0) return

        val ctVal = ctValFor(baseSymbol)
        val baseQty = szContracts * ctVal
        val id = "OKX:${if (isWs) "W" else "R"}:$tsMs:$instId:$szContracts:$price"
        if (!deduplicator.isNew(id)) return

        // posSide is authoritative when present; otherwise invert order side.
        val posSideRaw = d.optString("posSide", "").lowercase(Locale.US)
        val side = when {
            posSideRaw == "long" -> LiquidationSide.LONG
            posSideRaw == "short" -> LiquidationSide.SHORT
            else -> LiquidationSide.fromForceOrderSide(d.optString("side", ""))
        }

        emitLiquidation(
            LiquidationEvent(
                id = id,
                exchange = exchangeName,
                symbol = baseSymbol,
                side = side,
                price = price,
                quantity = baseQty,
                notionalUsd = baseQty * price,
                timestampNs = tsMs * 1_000_000L,
                sequence = null,
                isSnapshot = false,
                sourceChannel = if (isWs) "liquidation-orders" else "liquidation-orders-rest"
            )
        )
    }

    /** Live ctVal from instruments endpoint, cached 1 hour, table fallback. */
    private fun ctValFor(symbol: String): Double {
        val cached = ctValCache[symbol]
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMs < 3_600_000L) {
            return cached.multiplier
        }
        var multiplier = ContractMultipliers.okx(symbol)
        try {
            val instId = SymbolNormalizer.okxSwap(symbol)
            // The instruments fetch is a one-off cache fill; runBlocking is
            // acceptable because the adapter's own dispatcher is IO-backed and
            // the call is infrequent (1h TTL).
            val fetched = kotlinx.coroutines.runBlocking { runBlockingForCtVal(instId) }
            fetched?.let { multiplier = it }
        } catch (e: Exception) {
            Log.d(TAG, "ctVal fetch failed for $symbol, using table: ${e.message}")
        }
        ctValCache[symbol] = CtValEntry(multiplier, System.currentTimeMillis())
        return multiplier
    }

    private suspend fun runBlockingForCtVal(instId: String): Double? {
        val json = restClient.getJson(
            "$restBase/api/v5/public/instruments",
            mapOf("instType" to "SWAP", "instId" to instId)
        ) ?: return null
        val data = json.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        return safeDouble(data.getJSONObject(0).optString("ctVal"))
    }

    private fun emitLiquidation(event: LiquidationEvent) {
        liquidationsReceived++
        _health.value = _health.value.copy(liquidationsReceived = liquidationsReceived)
        _liquidationEvents.tryEmit(event)
    }

    override fun stop() {
        wsClient?.stop()
        restPollJob?.cancel()
        _health.value = _health.value.copy(status = AdapterStatus.DOWN)
    }

    override suspend fun fetchTicker(symbol: String): TickerData? {
        val instId = SymbolNormalizer.okxSwap(symbol)
        val json = restClient.getJson("$restBase/api/v5/market/ticker", mapOf("instId" to instId)) ?: return null
        val d = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        return TickerData(
            last = safeDouble(d.optString("last")),
            high24h = safeDouble(d.optString("high24h")),
            low24h = safeDouble(d.optString("low24h")),
            open24h = safeDouble(d.optString("open24h")),
            quoteVolume24h = safeDouble(d.optString("vol24h"))
        )
    }

    override suspend fun fetchDerivativeData(symbol: String): DerivativeData? {
        val instId = SymbolNormalizer.okxSwap(symbol)
        val fundingJson = restClient.getJson("$restBase/api/v5/public/funding-rate", mapOf("instId" to instId))
        val oiJson = restClient.getJson("$restBase/api/v5/public/open-interest", mapOf("instType" to "SWAP", "instId" to instId))
        val lsJson = restClient.getJson(
            "$restBase/api/v5/rubik/stat/contracts/long-short-account-ratio",
            mapOf("ccy" to symbol.uppercase(Locale.US), "period" to "5m")
        )

        val funding = fundingJson?.optJSONArray("data")?.optJSONObject(0)?.optString("fundingRate")?.let {
            safeDouble(it)
        }
        val oiContracts = oiJson?.optJSONArray("data")?.optJSONObject(0)?.optString("oi")?.let { safeDouble(it) }
        val ctVal = ctValFor(symbol)
        val ticker = fetchTicker(symbol)
        val price = ticker?.last
        val oiBase = oiContracts?.let { it * ctVal }
        val oiUsd = if (oiBase != null && price != null) oiBase * price else null

        var lsRatio: Double? = null
        if (lsJson != null) {
            val data = lsJson.optJSONArray("data")
            if (data != null && data.length() > 0) {
                // Rubik rows are [timestamp, ratio] arrays.
                val row = data.optJSONArray(0)
                if (row != null && row.length() > 1) {
                    lsRatio = safeDouble(row.optString(1))
                }
            }
        }

        if (funding == null && oiBase == null && lsRatio == null) return null
        return DerivativeData(
            fundingRate = funding,
            openInterestBase = oiBase,
            openInterestUsd = oiUsd,
            oiChangePct1h = null, // OKX has no free OI history endpoint; Binance supplies it
            takerBuyPct = null,   // OKX Rubik exposes taker ratio only for top coins; Binance supplies it
            lsRatio = lsRatio
        )
    }

    override suspend fun fetchOrderBook(symbol: String, limit: Int): OrderBookData? {
        val instId = SymbolNormalizer.okxSwap(symbol)
        val json = restClient.getJson(
            "$restBase/api/v5/market/books",
            mapOf("instId" to instId, "sz" to limit.toString())
        ) ?: return null
        val book = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        val ctVal = ctValFor(symbol)
        val bids = parseLevels(book.optJSONArray("bids"), ctVal)
        val asks = parseLevels(book.optJSONArray("asks"), ctVal)
        return OrderBookData(bids, asks)
    }

    private fun parseLevels(arr: JSONArray?, ctVal: Double): List<OrderBookLevel> {
        val levels = mutableListOf<OrderBookLevel>()
        if (arr == null) return levels
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val px = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            levels.add(OrderBookLevel(px, qty * ctVal))
        }
        return levels
    }

    override suspend fun fetchTrades(symbol: String, limit: Int): List<Trade>? {
        val instId = SymbolNormalizer.okxSwap(symbol)
        val json = restClient.getJson(
            "$restBase/api/v5/market/trades",
            mapOf("instId" to instId, "limit" to limit.toString())
        ) ?: return null
        val data = json.optJSONArray("data") ?: return null
        val ctVal = ctValFor(symbol)
        val trades = mutableListOf<Trade>()
        for (i in 0 until data.length()) {
            val t = data.getJSONObject(i)
            val px = safeDouble(t.optString("px")) ?: continue
            val sz = safeDouble(t.optString("sz")) ?: continue
            val ts = safeLong(t.optString("ts")) ?: 0L
            val side = if (t.optString("side", "").lowercase(Locale.US) == "buy") {
                LiquidationSide.LONG
            } else {
                LiquidationSide.SHORT
            }
            trades.add(
                Trade(
                    timestampNs = ts * 1_000_000L,
                    price = px,
                    quantity = sz * ctVal,
                    side = side,
                    exchange = exchangeName
                )
            )
        }
        return trades
    }

    override suspend fun fetchCandles(symbol: String, timeframe: String, limit: Int): List<Candle>? {
        val instId = SymbolNormalizer.okxSwap(symbol)
        val bar = OkxBar.from(timeframe) ?: return null
        val json = restClient.getJson(
            "$restBase/api/v5/market/candles",
            mapOf("instId" to instId, "bar" to bar, "limit" to limit.toString())
        ) ?: return null
        val data = json.optJSONArray("data") ?: return null
        val candles = mutableListOf<Candle>()
        for (i in 0 until data.length()) {
            val row = data.optJSONArray(i) ?: continue
            val ts = row.optLong(0)
            val open = row.optString(1).toDoubleOrNull() ?: continue
            val high = row.optString(2).toDoubleOrNull() ?: continue
            val low = row.optString(3).toDoubleOrNull() ?: continue
            val close = row.optString(4).toDoubleOrNull() ?: continue
            val volume = row.optString(5).toDoubleOrNull() ?: 0.0
            candles.add(Candle(ts, open, high, low, close, volume))
        }
        // OKX returns newest-first; normalize to oldest-first.
        return candles.sortedBy { it.timestampMs }
    }

    private object OkxBar {
        fun from(timeframe: String): String? = when (timeframe.lowercase()) {
            "1m" -> "1m"
            "5m" -> "5m"
            "15m" -> "15m"
            "1h" -> "1H"
            "4h" -> "4H"
            else -> null
        }
    }

    companion object {
        private const val TAG = "OkxAdapter"
        private const val REST_POLL_INTERVAL_MS = 30_000L
    }
}
