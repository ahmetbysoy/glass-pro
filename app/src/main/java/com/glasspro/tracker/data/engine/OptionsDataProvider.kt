package com.glasspro.tracker.data.engine

import android.util.Log
import com.glasspro.tracker.core.math.Stats
import com.glasspro.tracker.data.remote.rest.RestClient

/**
 * Optional real options-data source. Deribit is the only venue with a free
 * public implied-volatility index API covering perps. Only symbols with an
 * actual options market (BTC, ETH) return a value; for every other symbol the
 * provider returns null and the engine excludes the component with weight
 * renormalization — the absence is real (no market), never a placeholder.
 */
interface OptionsDataProvider {
    /**
     * @return a -100..100 options score from realized DVOL dynamics, or null
     *   when no options market exists for the symbol.
     */
    suspend fun fetchVolatilityChange(symbol: String): Double?
}

class DeribitOptionsProvider(private val restClient: RestClient) : OptionsDataProvider {

    override suspend fun fetchVolatilityChange(symbol: String): Double? {
        val currency = when (symbol.uppercase()) {
            "BTC" -> "BTC"
            "ETH" -> "ETH"
            else -> return null // no options market on Deribit for this symbol
        }
        val nowMs = System.currentTimeMillis()
        val startMs = nowMs - 24 * 3_600_000L
        val json = restClient.getJson(
            "https://www.deribit.com/api/v2/public/get_volatility_index_data",
            mapOf(
                "currency" to currency,
                "resolution" to "3600",
                "start_timestamp" to startMs.toString(),
                "end_timestamp" to nowMs.toString()
            ),
            retries = 1
        ) ?: return null
        val data = json.optJSONObject("result")?.optJSONArray("data") ?: return null
        if (data.length() < 2) return null

        val closes = mutableListOf<Double>()
        for (i in 0 until data.length()) {
            val row = data.optJSONArray(i) ?: continue
            val close = row.optDouble(4, Double.NaN) // [ts, open, high, low, close]
            if (!close.isNaN()) closes.add(close)
        }
        if (closes.size < 2) return null
        val first = closes.first()
        val last = closes.last()
        if (first <= 0.0) return null
        val dvolChangePct = (last / first - 1.0) * 100.0

        // Rapidly rising implied vol widens expected ranges and makes any
        // directional bias less reliable; falling vol firms direction.
        return when {
            dvolChangePct > 15.0 -> -30.0
            dvolChangePct > 5.0 -> -15.0
            dvolChangePct < -10.0 -> 15.0
            dvolChangePct < -3.0 -> 10.0
            else -> 0.0
        }
    }
}
