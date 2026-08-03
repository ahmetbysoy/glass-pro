package com.glasspro.tracker.data.engine

import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.core.model.OrderBookLevel
import com.glasspro.tracker.data.remote.adapter.OrderBookData
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Cross-venue order book analytics. Books from every adapter are merged into
 * a single price-bucketed book, then the following are computed (methodology
 * of the PRO reference engine):
 *
 *  - Bid/ask imbalance inside the ±1% band around the consensus price.
 *  - Supports and resistances (largest bid/ask buckets).
 *  - Whale walls (buckets >= 10x the average bucket size) with the exact
 *    venue attribution per price level.
 *  - Spoof rate: the share of large walls present in snapshot t0 that
 *    disappeared by snapshot t1 (3 seconds later), normalised.
 *  - Composite order-book score used as the "Order Book" component.
 */
object OrderBookAnalytics {

    data class Wall(
        val price: Double,
        val size: Double,
        val side: String,       // "bid" or "ask"
        val exchange: String
    )

    data class Result(
        val bidPct: Double,
        val askPct: Double,
        val supports: List<OrderBookLevel>,
        val resistances: List<OrderBookLevel>,
        val walls: List<Wall>,
        val spoofRate: Double,
        val score: Double
    )

    /**
     * @param consensusPrice the Quant-Security-filtered median price.
     * @param snapshot the most recent order book snapshot per venue.
     * @param previousSnapshot an earlier snapshot (3s ago) per venue, used for
     *   spoof detection; pass an empty map to disable spoofing detection.
     */
    fun analyze(
        consensusPrice: Double,
        snapshot: Map<String, OrderBookData>,
        previousSnapshot: Map<String, OrderBookData>
    ): Result {
        val price = consensusPrice
        if (price <= 0.0) {
            return Result(50.0, 50.0, emptyList(), emptyList(), emptyList(), 0.0, 0.0)
        }

        val tick = price * 0.0005
        val lo = price * 0.97
        val hi = price * 1.03

        // Bucket all levels across venues into a single aggregated book.
        val bidAgg = HashMap<Double, Double>()
        val askAgg = HashMap<Double, Double>()
        for ((_, book) in snapshot) {
            for (level in book.bids) {
                if (level.price < lo || level.price > hi) continue
                val key = bucketKey(level.price, tick)
                bidAgg[key] = (bidAgg[key] ?: 0.0) + level.quantity
            }
            for (level in book.asks) {
                if (level.price < lo || level.price > hi) continue
                val key = bucketKey(level.price, tick)
                askAgg[key] = (askAgg[key] ?: 0.0) + level.quantity
            }
        }
        val bids = bidAgg.entries.sortedByDescending { it.key }.map { OrderBookLevel(it.key, it.value) }
        val asks = askAgg.entries.sortedBy { it.key }.map { OrderBookLevel(it.key, it.value) }

        // ±1% band imbalance.
        val band = price * 0.01
        val bid1 = bids.filter { it.price >= price - band && it.price < price }.sumOf { it.quantity }
        val ask1 = asks.filter { it.price > price && it.price <= price + band }.sumOf { it.quantity }
        val total1 = bid1 + ask1
        val bidPct = if (total1 > 0.0) 100.0 * bid1 / total1 else 50.0
        val askPct = 100.0 - bidPct

        // Supports / resistances.
        val supports = bids.take(5)
        val resistances = asks.take(5)

        // Whale walls.
        val allSizes = bids.map { it.quantity } + asks.map { it.quantity }
        val avgSize = Stats.mean(allSizes) ?: 0.0
        val wallThreshold = avgSize * 10.0
        val wallsRaw = (bids.filter { it.quantity >= wallThreshold }.map { it to "bid" } +
                asks.filter { it.quantity >= wallThreshold }.map { it to "ask" })
            .sortedByDescending { it.first.quantity }
            .take(10)

        val walls = wallsRaw.map { (level, side) ->
            Wall(
                price = level.price,
                size = level.quantity,
                side = side,
                exchange = attributeExchange(snapshot, level.price, tick)
            )
        }
        val bidWalls = walls.count { it.side == "bid" }
        val askWalls = walls.count { it.side == "ask" }

        // Spoof detection between two snapshots (per venue, large levels only).
        val spoofRate = if (previousSnapshot.isNotEmpty()) {
            detectSpoof(price, snapshot, previousSnapshot, tick)
        } else {
            0.0
        }

        val score = Stats.clamp(
            (bidPct - askPct) * 1.5 +
                (bidWalls - askWalls) * 5.0 -
                spoofRate * 0.2
        )

        return Result(
            bidPct = bidPct,
            askPct = askPct,
            supports = supports,
            resistances = resistances,
            walls = walls,
            spoofRate = spoofRate,
            score = score
        )
    }

    private fun bucketKey(price: Double, tick: Double): Double {
        val k = (price / tick).roundToLong() * tick
        return (k * 1_000_000.0).roundToLong() / 1_000_000.0
    }

    private fun attributeExchange(
        snapshot: Map<String, OrderBookData>,
        price: Double,
        tick: Double
    ): String {
        var bestExchange = "CEX"
        var maxSize = 0.0
        for ((exchange, book) in snapshot) {
            for (level in book.bids + book.asks) {
                if (abs(level.price - price) <= tick && level.quantity > maxSize) {
                    maxSize = level.quantity
                    bestExchange = exchange
                }
            }
        }
        return bestExchange
    }

    private fun detectSpoof(
        price: Double,
        current: Map<String, OrderBookData>,
        previous: Map<String, OrderBookData>,
        tick: Double
    ): Double {
        val band = price * 0.02
        fun largeLevels(book: OrderBookData): Set<Pair<String, Double>> {
            val result = mutableSetOf<Pair<String, Double>>()
            val levels = book.bids + book.asks
            if (levels.isEmpty()) return result
            val mean = levels.map { it.quantity }.average()
            for (l in levels) {
                if (abs(l.price - price) <= band && l.quantity >= mean * 5.0) {
                    result.add(l.price to bucketKey(l.price, tick))
                }
            }
            return result
        }

        var l1 = 0
        var l3 = 0
        var gone = 0
        val keys = (current.keys + previous.keys).toSet()
        for (venue in keys) {
            val before = previous[venue]?.let { largeLevels(it) } ?: emptySet()
            val after = current[venue]?.let { largeLevels(it) } ?: emptySet()
            gone += (before - after).size
            l1 += before.size
            l3 += after.size
        }
        val total = l1 + l3
        return if (total > 0) 100.0 * gone / total else 0.0
    }
}
