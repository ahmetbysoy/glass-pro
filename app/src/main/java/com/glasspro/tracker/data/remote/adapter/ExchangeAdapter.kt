package com.glasspro.tracker.data.remote.adapter

import com.glasspro.tracker.core.model.Candle
import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.OrderBookLevel
import com.glasspro.tracker.core.model.Trade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Health state of one exchange adapter as seen by the UI.
 */
enum class AdapterStatus {
    /** Connection open and messages flowing (or REST answers recent). */
    LIVE,

    /** Connected but no fresh data within the staleness window. */
    DEGRADED,

    /** Connection lost and reconnect in backoff. */
    DOWN
}

data class AdapterHealth(
    val exchange: String,
    val status: AdapterStatus,
    val liquidationsReceived: Long,
    val lastMessageAgeMs: Long,
    val lastError: String?
)

/**
 * A single exchange venue. Two categories of capability exist:
 *
 *  - **Liquidation streaming**: Binance and OKX expose public liquidation
 *    WebSocket channels; Bybit exposes a REST liquidation endpoint that is
 *    polled. Other venues do not publish liquidations, so their adapters
 *    simply never emit liquidation events.
 *  - **Market data on demand**: every adapter can answer ticker, funding,
 *    open-interest, order book, trades and candles requests; the
 *    [MarketDataService] aggregates them into a cross-exchange consensus.
 *
 * Adapters are pure data collectors: no randomness, no synthesized values.
 * When a venue returns nothing, the adapter returns null and the aggregator
 * moves to the next venue.
 */
interface ExchangeAdapter {

    val exchangeName: String

    val health: StateFlow<AdapterHealth>

    /**
     * Real liquidation events as they arrive. Adapters with no liquidation
     * channel keep this flow empty.
     */
    val liquidationEvents: SharedFlow<LiquidationEvent>

    fun start(scope: CoroutineScope)

    fun stop()

    suspend fun fetchTicker(symbol: String): TickerData?

    suspend fun fetchDerivativeData(symbol: String): DerivativeData?

    suspend fun fetchOrderBook(symbol: String, limit: Int): OrderBookData?

    suspend fun fetchTrades(symbol: String, limit: Int): List<Trade>?

    suspend fun fetchCandles(symbol: String, timeframe: String, limit: Int): List<Candle>?
}

data class TickerData(
    val last: Double?,
    val high24h: Double?,
    val low24h: Double?,
    val open24h: Double?,
    val quoteVolume24h: Double?
)

data class DerivativeData(
    val fundingRate: Double?,
    val openInterestBase: Double?,
    val openInterestUsd: Double?,
    val oiChangePct1h: Double?,
    val takerBuyPct: Double?,
    val lsRatio: Double?
)

data class OrderBookData(
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>
)
