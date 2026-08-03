package com.glasspro.tracker.data.remote

import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.data.remote.rest.RestClient

/**
 * Global macro bias from the Crypto Fear & Greed Index
 * (real, public: api.alternative.me). Cached for one hour and applied as a
 * bias on the momentum blend exactly like the reference engine.
 */
class MacroService(private val restClient: RestClient) {

    data class FearAndGreed(
        val value: Int,
        val classification: String,
        val trend: Int
    )

    private var cached: FearAndGreed? = null
    private var cachedAtMs = 0L

    suspend fun current(): FearAndGreed? {
        val now = System.currentTimeMillis()
        if (cached != null && now - cachedAtMs < CACHE_TTL_MS) return cached
        val json = restClient.getJson(FNG_URL, retries = 1) ?: return cached
        val data = json.optJSONArray("data")
        if (data == null || data.length() == 0) return cached
        val latest = data.getJSONObject(0)
        val value = latest.optInt("value", 50)
        val classification = latest.optString("value_classification", "Neutral")
        val lastValue = data.getJSONObject(data.length() - 1).optInt("value", value)
        val result = FearAndGreed(value, classification, value - lastValue)
        cached = result
        cachedAtMs = now
        return result
    }

    /**
     * Macro bias added to the momentum component (reference engine rules).
     */
    fun biasFor(value: Int?): Double = when {
        value == null -> 0.0
        value < 25 -> 15.0
        value < 40 -> 5.0
        value > 75 -> -15.0
        value > 60 -> -5.0
        else -> 0.0
    }

    companion object {
        private const val FNG_URL = "https://api.alternative.me/fng/?limit=10"
        private const val CACHE_TTL_MS = 3_600_000L
    }
}
