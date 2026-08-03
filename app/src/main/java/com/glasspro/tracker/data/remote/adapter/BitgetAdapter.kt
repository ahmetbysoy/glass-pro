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
import java.util.Locale

/**
 * Bitget v2 USDT-FUTURES adapter (market data only). Bitget does not publish
 * a public liquidation feed; this adapter therefore never emits liquidation
 * events. It contributes funding, open interest, order book, trades and
 * candles to the multi-venue consensus.
 */
class BitgetAdapter(
    private val restClient: RestClient
) : ExchangeAdapter {

    override val exchangeName: String = "Bitget"

    private val _health = MutableStateFlow(
        AdapterHealth(exchangeName, AdapterStatus.DOWN, 0, 0L, null)
    )
    override val health: StateFlow<AdapterHealth> = _health.asStateFlow()

    private val _liquidationEvents = MutableSharedFlow<LiquidationEvent>(0)
    override val liquidationEvents: SharedFlow<LiquidationEvent> = _liquidationEvents.asSharedFlow()

    private var pollJob: Job? = null
    private var lastSuccessAtMs = 0L

    private val restBase = "https://api.bitget.com"

    override fun start(scope: CoroutineScope) {
        pollJob = scope.launch {
            while (isActive) {
                val json = restClient.getJson(
                    "$restBase/api/v2/mix/market/ticker",
                    mapOf("symbol" to "BTCUSDT", "productType" to "USDT-FUTURES")
                )
                if (json?.optJSONArray("data")?.length() ?: 0 > 0) {
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
        val flat = SymbolNormalizer.flat(symbol)
        val json = restClient.getJson(
            "$restBase/api/v2/mix/market/ticker",
            mapOf("symbol" to flat, "productType" to "USDT-FUTURES")
        ) ?: return null
        val d = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        return TickerData(
            last = safeDouble(d.optString("lastPr")),
            high24h = safeDouble(d.optString("high24h")),
            low24h = safeDouble(d.optString("low24h")),
            open24h = safeDouble(d.optString("open24h")),
            quoteVolume24h = safeDouble(d.optString("usdtVol"))
        )
    }

    override suspend fun fetchDerivativeData(symbol: String): DerivativeData? {
        val flat = SymbolNormalizer.flat(symbol)
        val funding = restClient.getJson(
            "$restBase/api/v2/mix/market/current-fund-rate",
            mapOf("symbol" to flat, "productType" to "USDT-FUTURES")
        )
        val oiJson = restClient.getJson(
            "$restBase/api/v2/mix/market/open-interest",
            mapOf("symbol" to flat, "productType" to "USDT-FUTURES")
        )
        val fundingRate = funding?.optJSONArray("data")?.optJSONObject(0)?.optString("fundingRate")?.let {
            safeDouble(it)
        }
        val oiContracts = oiJson?.optJSONArray("data")?.optJSONObject(0)?.optString("size")?.let { safeDouble(it) }
        val multiplier = ContractMultipliers.bitget(symbol)
        val price = fetchTicker(symbol)?.last
        val oiBase = oiContracts?.let { it * multiplier }
        val oiUsd = if (oiBase != null && price != null) oiBase * price else null

        if (fundingRate == null && oiBase == null) return null
        return DerivativeData(
            fundingRate = fundingRate,
            openInterestBase = oiBase,
            openInterestUsd = oiUsd,
            oiChangePct1h = null,
            takerBuyPct = null,
            lsRatio = null
        )
    }

    override suspend fun fetchOrderBook(symbol: String, limit: Int): OrderBookData? {
        val flat = SymbolNormalizer.flat(symbol)
        val json = restClient.getJson(
            "$restBase/api/v2/mix/market/orderbook",
            mapOf("symbol" to flat, "productType" to "USDT-FUTURES", "type" to "step0", "limit" to limit.toString())
        ) ?: return null
        val d = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        val multiplier = ContractMultipliers.bitget(symbol)
        val bids = parseLevels(d.optJSONArray("bids"), multiplier)
        val asks = parseLevels(d.optJSONArray("asks"), multiplier)
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
            "$restBase/api/v2/mix/market/fills",
            mapOf("symbol" to flat, "productType" to "USDT-FUTURES", "limit" to limit.toString())
        ) ?: return null
        val data = json.optJSONArray("data") ?: return null
        val multiplier = ContractMultipliers.bitget(symbol)
        val trades = mutableListOf<Trade>()
        for (i in 0 until data.length()) {
            val t = data.getJSONObject(i)
            val px = safeDouble(t.optString("price")) ?: continue
            val sz = safeDouble(t.optString("size")) ?: continue
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
        val granularity = BitgetGranularity.from(timeframe) ?: return null
        val json = restClient.getJson(
            "$restBase/api/v2/mix/market/candles",
            mapOf("symbol" to flat, "productType" to "USDT-FUTURES", "granularity" to granularity, "limit" to limit.toString())
        ) ?: return null
        val data = json.optJSONArray("data") ?: return null
        val candles = mutableListOf<Candle>()
        for (i in 0 until data.length()) {
            val row = data.getJSONObject(i)
            val ts = safeLong(row.optString("ts")) ?: continue
            val open = safeDouble(row.optString("open")) ?: continue
            val high = safeDouble(row.optString("high")) ?: continue
            val low = safeDouble(row.optString("low")) ?: continue
            val close = safeDouble(row.optString("close")) ?: continue
            val volume = safeDouble(row.optString("volume")) ?: 0.0
            candles.add(Candle(ts, open, high, low, close, volume))
        }
        return candles.sortedBy { it.timestampMs }
    }

    private object BitgetGranularity {
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
        private const val POLL_INTERVAL_MS = 15_000L
        private const val STALE_MS = 45_000L
    }
}
