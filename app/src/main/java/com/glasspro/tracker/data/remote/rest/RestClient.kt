package com.glasspro.tracker.data.remote.rest

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Synchronous HTTP helper used by every exchange adapter. All calls run on
 * Dispatchers.IO; JSON is parsed with org.json so the adapters stay dependency
 * light. A bounded retry (with linear backoff) covers transient failures;
 * errors are logged and surfaced as null so callers can fall back to the next
 * venue instead of crashing.
 */
class RestClient(private val okHttpClient: OkHttpClient) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getJson(
        url: String,
        params: Map<String, String> = emptyMap(),
        retries: Int = 2
    ): JSONObject? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt <= retries) {
            try {
                val finalUrl = if (params.isEmpty()) {
                    url
                } else {
                    val query = params.entries.joinToString("&") { (k, v) ->
                        "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                    }
                    if (url.contains("?")) "$url&$query" else "$url?$query"
                }
                val request = Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body.isNullOrBlank()) {
                        Log.d(TAG, "GET ${response.code} $url")
                        return@use null
                    }
                    return@withContext try {
                        JSONObject(body)
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON parse failed for $url: ${e.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                attempt++
                if (attempt <= retries) {
                    Thread.sleep(ATTEMPT_DELAY_MS * attempt)
                } else {
                    Log.d(TAG, "GET failed after retries: $url -> ${e.message}")
                }
            }
        }
        null
    }

    suspend fun getJsonArray(
        url: String,
        params: Map<String, String> = emptyMap(),
        retries: Int = 2
    ): JSONArray? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt <= retries) {
            try {
                val finalUrl = if (params.isEmpty()) {
                    url
                } else {
                    val query = params.entries.joinToString("&") { (k, v) ->
                        "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                    }
                    if (url.contains("?")) "$url&$query" else "$url?$query"
                }
                val request = Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body.isNullOrBlank()) {
                        Log.d(TAG, "GET ${response.code} $url")
                        return@use null
                    }
                    return@withContext try {
                        JSONArray(body)
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON array parse failed for $url: ${e.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                attempt++
                if (attempt <= retries) {
                    Thread.sleep(ATTEMPT_DELAY_MS * attempt)
                } else {
                    Log.d(TAG, "GET array failed after retries: $url -> ${e.message}")
                }
            }
        }
        null
    }

    /**
     * POST returning a JSON array body. Used by Hyperliquid's /info endpoint,
     * whose metaAndAssetCtxs response is a top-level array
     * `[universe, assetCtxs]`.
     */
    suspend fun postJsonArray(
        url: String,
        body: JSONObject,
        retries: Int = 2
    ): JSONArray? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt <= retries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val respBody = response.body?.string()
                    if (!response.isSuccessful || respBody.isNullOrBlank()) {
                        Log.d(TAG, "POST array ${response.code} $url")
                        return@use null
                    }
                    return@withContext try {
                        JSONArray(respBody)
                    } catch (e: Exception) {
                        Log.w(TAG, "POST array parse failed for $url: ${e.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                attempt++
                if (attempt <= retries) {
                    Thread.sleep(ATTEMPT_DELAY_MS * attempt)
                } else {
                    Log.d(TAG, "POST array failed after retries: $url -> ${e.message}")
                }
            }
        }
        null
    }

    /**
     * POST with a JSON body (used by Hyperliquid's /info endpoint).
     */
    suspend fun postJson(
        url: String,
        body: JSONObject,
        retries: Int = 2
    ): JSONObject? = withContext(Dispatchers.IO) {
        var attempt = 0
        while (attempt <= retries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val respBody = response.body?.string()
                    if (!response.isSuccessful || respBody.isNullOrBlank()) {
                        Log.d(TAG, "POST ${response.code} $url")
                        return@use null
                    }
                    return@withContext try {
                        JSONObject(respBody)
                    } catch (e: Exception) {
                        Log.w(TAG, "POST parse failed for $url: ${e.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                attempt++
                if (attempt <= retries) {
                    Thread.sleep(ATTEMPT_DELAY_MS * attempt)
                } else {
                    Log.d(TAG, "POST failed after retries: $url -> ${e.message}")
                }
            }
        }
        null
    }

    companion object {
        private const val TAG = "RestClient"
        private const val ATTEMPT_DELAY_MS = 250L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun buildOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
