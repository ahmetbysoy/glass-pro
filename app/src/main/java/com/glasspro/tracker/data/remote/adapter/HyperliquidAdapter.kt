package com.glasspro.tracker.data.remote.adapter

import android.util.Log
import com.glasspro.tracker.core.math.Stats.safeDouble
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
 * Hyperliquid perp adapter (market data only via the public /info POST API).
 * Contributes consensus price, funding, OI, order book and trades. Hyperliquid
 * has no public liquidation feed, so no liquidation events are emitted.
 */
class HyperliquidAdapter(
    private val restClient: RestClient
) : ExchangeAdapter {

    override val exchangeName: String = "Hyperliquid"

    private val _health = MutableStateFlow(
        AdapterHealth(exchangeName, AdapterStatus.DOWN, 0, 0L, null)
    )
    override val health: StateFlow<AdapterHealth> = _health.asStateFlow()

    private val _liquidationEvents = MutableSharedFlow<LiquidationEvent>(0)
    override val liquidationEvents: SharedFlow<LiquidationEvent> = _liquidationEvents.asSharedFlow()

    private var pollJob: Job? = null
    private var lastSuccessAtMs = 0L

    private val infoUrl = "https://api.hyperliquid.xyz/info"

    override fun start(scope: CoroutineScope) {
        pollJob = scope.launch {
            while (isActive) {
                val ctxs = fetchMetaAndAssetCtxs()
                if (ctxs != null) {
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

    private suspend fun fetchMetaAndAssetCtxs(): Pair<List<String>, List<JSONObject>>? {
        val body = JSONObject().put("type", "metaAndAssetCtxs")
        val resp = restClient.postJson(infoUrl, body) ?: return null
        val universe = resp.optJSONArray(0) ?: return null
        val ctxs = resp.optJSONArray(1) ?: return null
        val names = mutableListOf<String>()
        for (i in 0 until universe.length()) {
            names.add(universe.optJSONObject(i)?.optString("name", "") ?: "")
        }
        val ctxList = mutableListOf<JSONObject>()
        for (i in 0 until ctxs.length()) {
            ctxList.add(ctxs.getJSONObject(i))
        }
        return names to ctxList
    }

    private fun ctxFor(symbol: String): JSONObject? {
        val pair = try {
            fetchMetaAndAssetCtxs() ?: return null
        } catch (e: Exception) {
            Log.d(TAG, "ctxFor failed: ${e.message}")
            return null
        }
        val (names, ctxs) = pair
        val idx = names.indexOf(SymbolNormalizer.hyperliquidCoin(symbol))
        if (idx < 0 || idx >= ctxs.size) return null
        return ctxs[idx]
    }

    override suspend fun fetchTicker(symbol: String): TickerData? {
        val ctx = ctxFor(symbol) ?: return null
        val markPx = safeDouble(ctx.optString("markPx"))
        val dayNtlVlm = safeDouble(ctx.optString("dayNtlVlm"))
        val prevDayPx = safeDouble(ctx.optString("prevDayPx"))
        val dayHigh = safeDouble(ctx.optString("dayHigh"))
        val dayLow = safeDouble(ctx.optString("dayLow"))
        return TickerData(
            last = markPx,
            high24h = dayHigh,
            low24h = dayLow,
            open24h = prevDayPx,
            quoteVolume24h = dayNtlVlm
        )
    }

    override suspend fun fetchDerivativeData(symbol: String): DerivativeData? {
        val ctx = ctxFor(symbol) ?: return null
        val funding = safeDouble(ctx.optString("funding"))
        val oiBase = safeDouble(ctx.optString("openInterest"))
        val price = safeDouble(ctx.optString("markPx"))
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
        val body = JSONObject()
            .put("type", "l2Book")
            .put("coin", SymbolNormalizer.hyperliquidCoin(symbol))
        val resp = restClient.postJson(infoUrl, body) ?: return null
        val levels = resp.optJSONArray("levels") ?: return null
        val bids = parseLevels(levels.optJSONArray(0))
        val asks = parseLevels(levels.optJSONArray(1))
        return OrderBookData(bids, asks)
    }

    private fun parseLevels(arr: JSONArray?): List<OrderBookLevel> {
        val levels = mutableListOf<OrderBookLevel>()
        if (arr == null) return levels
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val px = safeDouble(obj.optString("px")) ?: continue
            val sz = safeDouble(obj.optString("sz")) ?: continue
            levels.add(OrderBookLevel(px, sz))
        }
        return levels
    }

    override suspend fun fetchTrades(symbol: String, limit: Int): List<Trade>? {
        val body = JSONObject()
            .put("type", "recentTrades")
            .put("coin", SymbolNormalizer.hyperliquidCoin(symbol))
        val resp = restClient.postJson(infoUrl, body) ?: return null
        val arr = resp.optJSONArray("trades") ?: resp.optJSONArray("0") ?: return null
        val trades = mutableListOf<Trade>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val px = safeDouble(t.optString("px")) ?: continue
            val sz = safeDouble(t.optString("sz")) ?: continue
            val timeNs = t.optLong("time", 0L)
            val side = if (t.optString("side", "").lowercase() == "buy") {
                LiquidationSide.LONG
            } else {
                LiquidationSide.SHORT
            }
            trades.add(Trade(timeNs, px, sz, side, exchangeName))
        }
        return trades
    }

    override suspend fun fetchCandles(symbol: String, timeframe: String, limit: Int): List<Candle>? {
        // Hyperliquid candleSnapshot requires start/end epochs; skipped here
        // because OKX/Binance always supply candles for the watchlist.
        return null
    }

    companion object {
        private const val TAG = "HyperliquidAdapter"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val STALE_MS = 45_000L
    }
}
