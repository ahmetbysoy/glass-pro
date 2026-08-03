package com.glasspro.tracker.core.model

/**
 * Pure market data primitives shared across the remote layer, the analysis
 * engines and the persistence layer. Every value that originates from an
 * exchange is carried as a decimal string in the DTO layer and converted to
 * Double only inside these models after explicit parsing; trade identifiers
 * always remain strings because exchange trade ids exceed 64-bit range.
 */

import java.util.Locale

enum class LiquidationSide(val wireName: String) {
    LONG("LONG"),
    SHORT("SHORT");

    companion object {
        fun fromWire(value: String?): LiquidationSide {
            val v = value?.trim()?.uppercase(Locale.US)
            return when (v) {
                "BUY", "B", "LONG" -> LONG      // aggressor bought (short position liquidated)
                "SELL", "S", "SHORT" -> SHORT   // aggressor sold (long position liquidated)
                else -> SHORT
            }
        }

        /**
         * Maps a force-order / liquidation-feed "side" field to the liquidated
         * *position* side. Exchanges report the side of the closing order,
         * which is the inverse of the position: a SELL order closes a LONG
         * position, a BUY order closes a SHORT position.
         */
        fun fromForceOrderSide(value: String?): LiquidationSide {
            val v = value?.trim()?.uppercase(Locale.US)
            return when (v) {
                "SELL", "S" -> LONG
                "BUY", "B" -> SHORT
                else -> SHORT
            }
        }
    }
}

/**
 * A single real liquidation event reported by an exchange.
 *
 * @param id stable event identifier built from exchange + trade/order id so the
 *   deduplicator can drop repeats across snapshot/update and reconnect cycles.
 * @param side the side of the *liquidated position*: LONG means longs were
 *   forced out (sell pressure), SHORT means shorts were forced out (buy
 *   pressure). Aggregators must map exchange "side" semantics to this model.
 * @param notionalUsd approximate liquidation value in USD. Adapters apply the
 *   instrument contract multiplier so values are comparable across exchanges.
 * @param timestampNs exchange-reported event time in epoch nanoseconds when
 *   available, otherwise the local receive time.
 * @param sequence optional per-channel sequence for gap detection.
 * @param isSnapshot true when the event arrived as part of a historical
 *   snapshot rather than a live update; snapshot events never trigger analysis.
 */
data class LiquidationEvent(
    val id: String,
    val exchange: String,
    val symbol: String,
    val side: LiquidationSide,
    val price: Double,
    val quantity: Double,
    val notionalUsd: Double,
    val timestampNs: Long,
    val sequence: String?,
    val isSnapshot: Boolean,
    val sourceChannel: String
)

/**
 * OHLCV candle. All timestamps are epoch milliseconds.
 */
data class Candle(
    val timestampMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

/**
 * One price level in an order book. For futures the quantity is already
 * multiplied by the instrument contract multiplier (i.e. base-asset units).
 */
data class OrderBookLevel(
    val price: Double,
    val quantity: Double
)

/**
 * A single public market trade (aggressor side).
 */
data class Trade(
    val timestampNs: Long,
    val price: Double,
    val quantity: Double,
    val side: LiquidationSide,
    val exchange: String
)

/**
 * Funding rate reported by one venue.
 */
data class FundingRate(
    val exchange: String,
    val rate: Double
)

/**
 * Open interest reported by one venue, expressed in base-asset units after
 * contract multiplier normalization.
 */
data class OpenInterest(
    val exchange: String,
    val quantityBase: Double,
    val quantityUsd: Double
)

/**
 * Aggregated liquidation notional observed in the most recent window per side.
 */
data class LiquidationWindow(
    val longNotionalUsd: Double = 0.0,
    val shortNotionalUsd: Double = 0.0,
    val longCount: Int = 0,
    val shortCount: Int = 0,
    val windowStartNs: Long = 0L,
    val windowEndNs: Long = 0L
) {
    val totalNotionalUsd: Double get() = longNotionalUsd + shortNotionalUsd
    val imbalancePct: Double
        get() = if (totalNotionalUsd > 0.0) {
            (shortNotionalUsd - longNotionalUsd) / totalNotionalUsd * 100.0
        } else {
            0.0
        }
}
