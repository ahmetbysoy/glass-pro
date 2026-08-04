package com.glasspro.tracker.data.remote.ws

import android.util.Log
import com.glasspro.tracker.core.math.Stats.clampInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Production-grade single-connection WebSocket client with the state machine
 * mandated by the WSS test report:
 *
 *   DISCONNECTED -> CONNECTING -> SUBSCRIBING -> LIVE -> STALE -> BACKOFF -> CONNECTING
 *
 * Behaviour implemented:
 *  - Exponential backoff with jitter: 1, 2, 4, 8, 15, 30 seconds.
 *  - Backoff reset after a stable LIVE connection.
 *  - Protocol-level heartbeat: periodic application ping (e.g. `ping` for
 *    OKX) and automatic `pong` reply to Binance's bare `ping` text frames.
 *  - Staleness detection: when no application message arrives within
 *    [staleAfterMs], the connection is marked STALE and reconnected.
 *
 * Raw text frames are forwarded verbatim to [onMessage]; parsing stays in the
 * exchange adapters.
 */
class WebSocketClient(
    private val clientName: String,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val state: StateFlow<WsConnectionState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var stalenessJob: Job? = null

    private val backoffSeconds = intArrayOf(1, 2, 4, 8, 15, 30)
    private var backoffIndex = 0

    private var url: String = ""
    private var subscribePayload: String? = null
    private var protocolPingEveryMs: Long = 0L
    private var protocolPingPayload: String? = null
    private var staleAfterMs: Long = 0L
    private var onMessage: ((String) -> Unit)? = null
    private var running = false

    /**
     * True while the underlying WebSocket is open. OkHttp does not expose a
     * closeCode() on the WebSocket object, so the listener maintains this flag
     * (set false on close/failure, true again after a successful handshake).
     */
    @Volatile
    private var closed = true

    private val lastMessageAtMs = AtomicLong(0L)

    fun start(
        url: String,
        subscribePayload: String? = null,
        protocolPingEveryMs: Long = 0L,
        protocolPingPayload: String? = null,
        staleAfterMs: Long = 30_000L,
        onMessage: (String) -> Unit
    ) {
        this.url = url
        this.subscribePayload = subscribePayload
        this.protocolPingEveryMs = protocolPingEveryMs
        this.protocolPingPayload = protocolPingPayload
        this.staleAfterMs = staleAfterMs
        this.onMessage = onMessage
        this.running = true
        this.backoffIndex = 0
        connectWithBackoff()
    }

    fun stop() {
        running = false
        connectJob?.cancel()
        heartbeatJob?.cancel()
        stalenessJob?.cancel()
        socket?.close(1000, "client shutdown")
        socket = null
        _state.value = WsConnectionState.DISCONNECTED
    }

    private fun connectWithBackoff() {
        connectJob?.cancel()
        connectJob = scope.launch {
            while (running) {
                _state.value = WsConnectionState.CONNECTING
                val ok = openConnection()
                if (ok) {
                    backoffIndex = 0
                    waitForTermination()
                    // Brief pause between a terminated connection and the next
                    // attempt to avoid a hot reconnect loop.
                    if (running) delay(1_000L)
                } else {
                    val waitSeconds = backoffSeconds[backoffIndex]
                    backoffIndex = clampInt(backoffIndex + 1, 0, backoffSeconds.size - 1)
                    _state.value = WsConnectionState.BACKOFF
                    Log.w(TAG, "$clientName connect failed; backing off ${waitSeconds}s")
                    delay((waitSeconds * 1000L) + Random.nextLong(0L, 1000L))
                }
            }
        }
    }

    /**
     * Opens the socket and suspends until the WebSocket handshake resolves.
     * Returns true only after onOpen fired (or an application subscribe frame
     * was sent). Any failure completes the deferred with false so the caller
     * enters backoff.
     */
    private suspend fun openConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val handshake = CompletableDeferred<Boolean>()
            val request = Request.Builder().url(url).build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    closed = false
                    _state.value = WsConnectionState.SUBSCRIBING
                    val payload = subscribePayload
                    if (payload != null) {
                        try {
                            if (!webSocket.send(payload)) {
                                Log.e(TAG, "$clientName failed to send subscribe frame")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "$clientName subscribe send error: ${e.message}")
                        }
                    }
                    _state.value = WsConnectionState.LIVE
                    lastMessageAtMs.set(System.currentTimeMillis())
                    scheduleHeartbeat()
                    scheduleStalenessMonitor()
                    handshake.complete(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastMessageAtMs.set(System.currentTimeMillis())
                    if (_state.value != WsConnectionState.LIVE) {
                        _state.value = WsConnectionState.LIVE
                    }
                    handleProtocolPing(text)?.let { pong ->
                        try {
                            webSocket.send(pong)
                        } catch (e: Exception) {
                            Log.w(TAG, "$clientName pong send error: ${e.message}")
                        }
                        return
                    }
                    onMessage?.invoke(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed = true
                    try {
                        webSocket.close(1000, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "$clientName close error: ${e.message}")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed = true
                    Log.i(TAG, "$clientName closed ($code): $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed = true
                    Log.w(TAG, "$clientName failure: ${t.message}")
                    handshake.complete(false)
                }
            }

            socket = okHttpClient.newWebSocket(request, listener)
            handshake.await()
        } catch (e: Exception) {
            Log.e(TAG, "$clientName open error: ${e.message}")
            closed = true
            false
        }
    }

    /**
     * Blocks the caller coroutine until the current socket terminates (or the
     * client is stopped), so the reconnect loop can proceed.
     */
    private suspend fun waitForTermination() {
        while (running) {
            if (!closed) {
                delay(500L)
            } else {
                _state.value = WsConnectionState.DISCONNECTED
                return
            }
        }
    }

    /**
     * Binance sends a bare `ping` text frame; returns the pong payload.
     */
    private fun handleProtocolPing(text: String): String? {
        val trimmed = text.trim()
        if (trimmed == "ping") return "pong"
        return null
    }

    private fun scheduleHeartbeat() {
        heartbeatJob?.cancel()
        if (protocolPingEveryMs <= 0L) return
        heartbeatJob = scope.launch {
            while (isActive && running) {
                delay(protocolPingEveryMs)
                val payload = protocolPingPayload
                val ws = socket
                if (payload != null && ws != null && !closed) {
                    try {
                        ws.send(payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "$clientName heartbeat send error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun scheduleStalenessMonitor() {
        stalenessJob?.cancel()
        if (staleAfterMs <= 0L) return
        stalenessJob = scope.launch {
            while (isActive && running) {
                delay(staleAfterMs / 2)
                val age = System.currentTimeMillis() - lastMessageAtMs.get()
                if (age > staleAfterMs && _state.value == WsConnectionState.LIVE) {
                    Log.w(TAG, "$clientName stale: no message for ${age}ms; reconnecting")
                    _state.value = WsConnectionState.STALE
                    closed = true
                    socket?.close(4000, "stale connection")
                }
            }
        }
    }

    companion object {
        private const val TAG = "WsClient"

        fun buildOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // streaming reads
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
