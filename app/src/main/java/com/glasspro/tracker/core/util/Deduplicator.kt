package com.glasspro.tracker.core.util

import java.util.concurrent.ConcurrentHashMap

/**
 * LRU-style event deduplicator. Trades and liquidation events can arrive more
 * than once (snapshot + update, or after a reconnect replay). Each adapter
 * builds a stable event id; this class remembers the most recent
 * [capacity] ids per channel and reports whether an id has been seen before.
 * Thread-safe.
 */
class Deduplicator(private val capacity: Int = 4096) {

    private val seen = object : LinkedHashMap<String, Long>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > capacity
        }
    }

    private val lock = Any()

    /**
     * @return true when the id was not seen before (i.e. it is new).
     */
    fun isNew(id: String): Boolean {
        synchronized(lock) {
            if (seen.containsKey(id)) return false
            seen[id] = System.currentTimeMillis()
            return true
        }
    }

    fun clear() {
        synchronized(lock) { seen.clear() }
    }
}
