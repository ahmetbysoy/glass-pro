package com.glasspro.tracker.data.remote

import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.LiquidationWindow
import com.glasspro.tracker.data.remote.adapter.ExchangeAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Consolidates the real liquidation streams of every adapter into a single
 * ordered event flow, keeps per-symbol rolling windows (used by the cascade
 * trigger and the liquidation component of the scoring model) and maintains a
 * global deduplication so a liquidation broadcast by multiple venues is
 * counted once.
 */
class LiquidationFeedManager(
    private val adapters: List<ExchangeAdapter>,
    private val scope: CoroutineScope
) {

    private val _events = MutableSharedFlow<LiquidationEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<LiquidationEvent> = _events.asSharedFlow()

    private val _recentEvents = MutableStateFlow<List<LiquidationEvent>>(emptyList())
    val recentEvents: StateFlow<List<LiquidationEvent>> = _recentEvents.asStateFlow()

    private val recentSnapshot = mutableListOf<LiquidationEvent>()
    private val windows = ConcurrentHashMap<String, ArrayDeque<LiquidationEvent>>()
    private val crossVenueDedup = LinkedHashMap<String, Long>()

    private val lock = Any()

    fun start() {
        for (adapter in adapters) {
            scope.launch {
                adapter.liquidationEvents.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    private fun handleEvent(event: LiquidationEvent) {
        synchronized(lock) {
            // Cross-venue dedup: same symbol + side + notional within the same
            // second is treated as the same liquidation observed twice
            // (e.g. Binance WSS + REST backup, or Binance + OKX for majors).
            val epochSec = event.timestampNs / 1_000_000_000L
            val dedupKey = "${event.symbol}|${event.side.name}|${event.notionalUsd.toInt()}|$epochSec"
            val nowMs = System.currentTimeMillis()
            if (crossVenueDedup.containsKey(dedupKey)) return
            crossVenueDedup[dedupKey] = nowMs
            if (crossVenueDedup.size > 8192) {
                // Prune keys older than 30 minutes.
                val cutoff = nowMs - 1_800_000L
                val it = crossVenueDedup.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().value < cutoff) it.remove()
                }
            }

            // Rolling window (3 minutes) per symbol for cascade detection.
            val deque = windows.getOrPut(event.symbol) { ArrayDeque() }
            val cutoffNs = System.currentTimeMillis() * 1_000_000L - 180_000_000_000L
            while (deque.isNotEmpty() && deque.first.timestampNs < cutoffNs) {
                deque.removeFirst()
            }
            deque.addLast(event)

            // Recent snapshot for the live feed (last 300 events).
            recentSnapshot.add(event)
            if (recentSnapshot.size > 300) {
                recentSnapshot.removeAt(0)
            }
        }
        _events.tryEmit(event)
        _recentEvents.value = synchronized(lock) { recentSnapshot.toList() }
    }

    /**
     * Liquidation window for [windowMs] ending now, per symbol.
     */
    fun windowFor(symbol: String, windowMs: Long = 180_000L): LiquidationWindow {
        val cutoffNs = System.currentTimeMillis() * 1_000_000L - windowMs * 1_000_000L
        val deque = windows[symbol] ?: return LiquidationWindow()
        synchronized(lock) {
            var longNotional = 0.0
            var shortNotional = 0.0
            var longCount = 0
            var shortCount = 0
            var startNs = 0L
            var endNs = 0L
            for (e in deque) {
                if (e.timestampNs < cutoffNs) continue
                if (startNs == 0L) startNs = e.timestampNs
                endNs = maxOf(endNs, e.timestampNs)
                when (e.side) {
                    LiquidationSide.LONG -> {
                        longNotional += e.notionalUsd
                        longCount++
                    }
                    LiquidationSide.SHORT -> {
                        shortNotional += e.notionalUsd
                        shortCount++
                    }
                }
            }
            return LiquidationWindow(
                longNotionalUsd = longNotional,
                shortNotionalUsd = shortNotional,
                longCount = longCount,
                shortCount = shortCount,
                windowStartNs = startNs,
                windowEndNs = endNs
            )
        }
    }

    /**
     * Resets in-memory windows, snapshots and dedup state (used when the user
     * clears history).
     */
    fun clearHistory() {
        synchronized(lock) {
            recentSnapshot.clear()
            windows.clear()
            crossVenueDedup.clear()
        }
        _recentEvents.value = emptyList()
    }

    /**
     * Total notional liquidated per symbol in the last [windowMs].
     */
    fun liquidatedSymbols(windowMs: Long = 180_000L): List<Pair<String, Double>> {
        val cutoffNs = System.currentTimeMillis() * 1_000_000L - windowMs * 1_000_000L
        val totals = HashMap<String, Double>()
        synchronized(lock) {
            for ((symbol, deque) in windows) {
                var sum = 0.0
                for (e in deque) {
                    if (e.timestampNs >= cutoffNs) sum += e.notionalUsd
                }
                if (sum > 0.0) totals[symbol] = sum
            }
        }
        return totals.entries.sortedByDescending { it.value }
    }
}
